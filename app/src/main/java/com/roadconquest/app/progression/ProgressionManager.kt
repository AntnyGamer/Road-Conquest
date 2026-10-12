package com.roadconquest.app.progression

import android.content.Context
import android.os.BatteryManager
import com.roadconquest.app.achievements.AchievementMetrics
import com.roadconquest.app.achievements.Achievements
import com.roadconquest.app.data.DataSummary
import com.roadconquest.app.data.ProgressionRepository
import com.roadconquest.app.data.ProgressionSnapshot
import com.roadconquest.app.data.PlaceDiscovery
import com.roadconquest.app.data.PlaceKind
import com.roadconquest.app.map.PlaceGeometry
import com.roadconquest.app.map.PlaceOverlayCache
import com.roadconquest.app.map.UnitedStatesMunicipalBoundaries
import com.roadconquest.app.map.UnitedStatesMunicipalityAtPoint
import org.json.JSONObject
import java.util.UUID

data class PlaceResolutionResult(val added: Int, val newestVisitAt: Long)

object ProgressionManager {
    fun sync(context: Context, summary: DataSummary): ProgressionSnapshot {
        val repository = ProgressionRepository(context)
        var snapshot = repository.snapshot()
        val expectedGeneration = summary.historyGeneration.takeIf { it >= 0L }
        if (expectedGeneration != null && !repository.isHistoryGenerationCurrent(expectedGeneration)) {
            return snapshot
        }
        if (summary.roadsUnlockedCount > snapshot.rewardedRoads) {
            repository.syncRoadRewards(summary.roadsUnlockedCount, expectedGeneration)
            snapshot = repository.snapshot()
        }
        val achievements = Achievements.progress(context, summary, metrics(snapshot))
        var changed = false
        for (achievement in achievements) {
            if (achievement.unlocked && achievement.id !in snapshot.rewardedAchievements) {
                changed = repository.awardAchievement(
                    achievement.id,
                    achievement.rewardPoints,
                    expectedGeneration
                ) || changed
            }
        }
        return if (changed) repository.snapshot() else snapshot
    }

    fun recordBatteryFromSystem(context: Context): Boolean {
        val percent = context.getSystemService(BatteryManager::class.java)
            ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: return false
        if (percent !in 0..5) return false
        return recordBatteryPercent(context, percent)
    }

    fun recordBatteryPercent(context: Context, percent: Int): Boolean {
        val repository = ProgressionRepository(context)
        val changed = repository.recordBatteryPercent(percent)
        if (changed) {
            val metrics = metrics(repository.snapshot())
            Achievements.progress(DataSummary(0, 0, null, null), metrics)
                .asSequence()
                .filter { it.unlocked && it.id.startsWith("battery_") }
                .forEach { repository.awardAchievement(it.id, it.rewardPoints) }
        }
        return changed
    }

    fun recordCompletedAd(repository: ProgressionRepository, receiptId: String, points: Long): Boolean {
        if (!repository.recordCompletedAd(receiptId, points)) return false
        val metrics = metrics(repository.snapshot())
        Achievements.progress(DataSummary(0, 0, null, null), metrics)
            .asSequence()
            .filter { it.unlocked && it.id.startsWith("ads_") }
            .forEach { repository.awardAchievement(it.id, it.rewardPoints) }
        return true
    }

    fun resetLocalProgression(context: Context) {
        ProgressionRepository(context).clearProgression()
        Achievements.reset(context)
        PlaceOverlayCache.clear(context)
        com.roadconquest.app.util.Prefs.resetCosmetics(context)
        LauncherIcon.apply(context, false)
    }

    fun resolvePendingPlaces(context: Context, limit: Int = 6): Int =
        resolvePendingPlacesWithDetails(context, limit).added

    fun resolvePendingPlacesWithDetails(context: Context, limit: Int = 6): PlaceResolutionResult {
        require(limit > 0)
        val repository = ProgressionRepository(context)
        // Limit polygon parsing and database reads to one set per foreground batch.
        val municipalCache by lazy { PlaceResolver.VerifiedMunicipalCache(context) }
        // Do not manufacture new discovery notifications from replaying historical drives.
        var added = 0
        var newestVisitAt = 0L
        var processed = 0
        while (processed < limit) {
            val candidates = repository.pendingPlaceCandidates(limit - processed)
            if (candidates.isEmpty()) break
            for (candidate in candidates) {
                val discoveries = PlaceResolver.resolve(context, candidate, municipalCache)
                if (discoveries.isNullOrEmpty()) {
                    repository.deferCandidate(candidate)
                } else {
                    reconcileMissingStartingTown(context, repository, discoveries)
                    val newPlaces = repository.resolveCandidate(candidate, discoveries)
                    added += newPlaces
                    if (newPlaces > 0) newestVisitAt = maxOf(newestVisitAt, candidate.visitedAt)
                }
                processed++
                if (processed >= limit) break
            }
        }
        return PlaceResolutionResult(added, newestVisitAt)
    }

    /**
     * A failed baseline town lookup is released after repeated partial results.
     * When a later, VERIFIED boundary includes the original start coordinate,
     * it is the zero-point town, not a newly discovered place.
     */
    internal fun reconcileMissingStartingTown(
        context: Context,
        repository: ProgressionRepository,
        discoveries: List<PlaceDiscovery>,
        resolveOfficialAtStart: (PlaceDiscovery) -> PlaceDiscovery? = {
            UnitedStatesMunicipalityAtPoint.fetch(it)?.place
        }
    ) {
        if (!repository.hasMissingStartingTown()) return
        val start = repository.startingLocation() ?: return
        for (town in discoveries) {
            if (town.kind != PlaceKind.TOWN ||
                !UnitedStatesMunicipalBoundaries.applies(town)
            ) continue
            val verified = PlaceOverlayCache.readOfficialUs(context, town).data
                ?: continue
            val containsStart = runCatching {
                PlaceGeometry.contains(
                    JSONObject(verified.geometryJson), start.latitude, start.longitude
                )
            }.getOrDefault(false)
            if (!containsStart) continue
            // Both Census layers may cover the start. Containment alone cannot
            // establish which town takes precedence. Confirm the actual Census
            // point result before suppressing a legitimate discovery reward.
            val atStart = runCatching {
                resolveOfficialAtStart(town.copy(
                    latitude = start.latitude, longitude = start.longitude,
                    visitedAt = start.recordedAt
                ))
            }.getOrNull()
            if (atStart?.key == town.key &&
                repository.recordVerifiedStartingTown(town, start)
            ) break
        }
    }

    fun metrics(snapshot: ProgressionSnapshot) = AchievementMetrics(
        towns = snapshot.towns,
        states = snapshot.states,
        countries = snapshot.countries,
        adsWatched = snapshot.adsWatched,
        lowestBatteryPercent = snapshot.lowestBatteryPercent
    )
}

/**
 * One bridge per displayed ad. Capture the current history before showing the ad so late SDK
 * callbacks cannot repopulate deleted data. Its receipt also makes duplicate callbacks harmless.
 */
class AdRewardBridge(context: Context, private val points: Long) {
    private val repository = ProgressionRepository(context.applicationContext)
    private val receiptId = UUID.randomUUID().toString()

    init { require(points > 0L) }

    fun onCompletedAd(): Boolean = ProgressionManager.recordCompletedAd(repository, receiptId, points)
}
