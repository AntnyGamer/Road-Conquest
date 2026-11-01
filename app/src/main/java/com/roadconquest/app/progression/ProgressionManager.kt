package com.roadconquest.app.progression

import android.content.Context
import com.roadconquest.app.achievements.AchievementMetrics
import com.roadconquest.app.achievements.Achievements
import com.roadconquest.app.data.DataSummary
import com.roadconquest.app.data.ProgressionRepository
import com.roadconquest.app.data.ProgressionSnapshot
import com.roadconquest.app.data.PlaceDiscovery
import com.roadconquest.app.data.PlaceKind
import com.roadconquest.app.data.PendingPlaceCandidate
import com.roadconquest.app.map.PlaceGeometry
import com.roadconquest.app.map.PlaceOverlayCache
import com.roadconquest.app.map.UnitedStatesMunicipalBoundaries
import com.roadconquest.app.map.UnitedStatesMunicipalityAtPoint
import org.json.JSONObject
import java.util.UUID

data class PlaceResolutionResult(
    val added: Int,
    val newestVisitAt: Long,
    val placesUpdated: Boolean
)

object ProgressionManager {
    private val placeResolutionLock = Any()
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
        if (com.roadconquest.app.map.TownCompletionTracker.sync(context, snapshot, expectedGeneration)) {
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
        val weeklyChanged = com.roadconquest.app.data.WeeklyChallenges.sync(context, summary, snapshot)
        return if (changed || weeklyChanged) repository.snapshot() else snapshot
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
        com.roadconquest.app.map.TownCompletionTracker.reset()
        Achievements.reset(context)
        PlaceOverlayCache.clear(context)
        com.roadconquest.app.util.Prefs.resetCosmetics(context)
        LauncherIcon.apply(context, false)
    }

    fun resolvePendingPlaces(context: Context, limit: Int = 6): Int =
        resolvePendingPlacesWithDetails(context, limit).added

    fun resolvePendingPlacesWithDetails(context: Context, limit: Int = 6): PlaceResolutionResult {
        // Limit polygon parsing and database reads to one set per foreground batch.
        val municipalCache by lazy { PlaceResolver.VerifiedMunicipalCache(context) }
        // Reuse remote provider configuration across candidates in this bounded
        // batch, but create a fresh client on the next batch to honor config changes.
        val overlayClient by lazy { com.roadconquest.app.map.PlaceOverlayClient() }
        return resolvePendingPlacesWithDetails(context, limit,
            lookup = { candidate, generation, current ->
                PlaceResolver.resolve(context, candidate, municipalCache, overlayClient = overlayClient,
                    cacheGeneration = generation, isCurrent = current)
            },
            reconcile = { repository, discoveries, current ->
                reconcileMissingStartingTown(context, repository, discoveries, isCurrent = current)
            })
    }

    internal fun resolvePendingPlacesWithDetails(
        context: Context, limit: Int,
        lookup: (PendingPlaceCandidate, Long, () -> Boolean) -> List<PlaceDiscovery>?,
        reconcile: (ProgressionRepository, List<PlaceDiscovery>, () -> Boolean) -> Unit
    ): PlaceResolutionResult = synchronized(placeResolutionLock) {
        require(limit > 0)
        val repository = ProgressionRepository(context)
        val cacheGeneration = PlaceOverlayCache.generation()
        fun current() = !Thread.currentThread().isInterrupted && repository.isHistoryCurrent() &&
            !com.roadconquest.app.util.Prefs.isDeviceDataDeletionPending(context) &&
            PlaceOverlayCache.generation() == cacheGeneration
        // Do not manufacture new discovery notifications from replaying historical drives.
        var added = 0
        var newestVisitAt = 0L
        var placesUpdated = false
        var startingTownConfirmed = false
        var processed = 0
        while (processed < limit) {
            if (!current()) break
            val candidates = repository.pendingPlaceCandidates(limit - processed)
            if (candidates.isEmpty()) break
            for (candidate in candidates) {
                // A malformed provider response or an Android geocoder runtime failure
                // must not abort the entire queue and strand every later municipality.
                // Retry only the failed fix with the existing persistent backoff.
                if (!current()) {
                    return@synchronized PlaceResolutionResult(added, newestVisitAt, placesUpdated)
                }
                val discoveries = try {
                    lookup(candidate, cacheGeneration, ::current)
                } catch (error: Exception) {
                    if (error is InterruptedException) Thread.currentThread().interrupt()
                    android.util.Log.w("RoadConquest", "Place candidate will retry", error)
                    null
                }
                if (!current()) return@synchronized PlaceResolutionResult(added, newestVisitAt, placesUpdated)
                if (discoveries.isNullOrEmpty()) {
                    repository.deferCandidate(candidate)
                } else {
                    val baseline = candidate.cellX == Long.MIN_VALUE && candidate.cellY == Long.MIN_VALUE
                    // Ordinary candidates cannot introduce a missing baseline kind.
                    // The baseline sentinel itself can do so when its retries expire.
                    val missingStartTown = !startingTownConfirmed && repository.hasMissingStartingTown()
                    if (missingStartTown) reconcile(repository, discoveries, ::current)
                    if (!current()) return@synchronized PlaceResolutionResult(added, newestVisitAt, placesUpdated)
                    val startTownRecovered = missingStartTown && !repository.hasMissingStartingTown()
                    val newPlaces = repository.resolveCandidate(candidate, discoveries)
                    startingTownConfirmed = !baseline && (!missingStartTown || startTownRecovered)
                    added += newPlaces
                    // A zero-point baseline or a late recovery changes visited-place
                    // overlays without awarding any points or showing a "new place" toast.
                    placesUpdated = placesUpdated || newPlaces > 0 || startTownRecovered ||
                        baseline
                    if (newPlaces > 0) newestVisitAt = maxOf(newestVisitAt, candidate.visitedAt)
                }
                processed++
                if (processed >= limit) break
            }
        }
        PlaceResolutionResult(added, newestVisitAt, placesUpdated)
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
        isCurrent: () -> Boolean = { repository.isHistoryCurrent() },
        resolveOfficialAtStart: (PlaceDiscovery) -> PlaceDiscovery? = {
            UnitedStatesMunicipalityAtPoint.fetch(it, isCurrent)?.place
        }
    ) {
        val cacheGeneration = PlaceOverlayCache.generation()
        fun current() = isCurrent() && !Thread.currentThread().isInterrupted &&
            PlaceOverlayCache.generation() == cacheGeneration
        if (!current() || !repository.hasMissingStartingTown()) return
        val start = repository.startingLocation() ?: return
        for (town in discoveries) {
            if (!current()) return
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
            if (!current()) return
            // Both Census layers may cover the start. Containment alone cannot
            // establish which town takes precedence. Confirm the actual Census
            // point result before suppressing a legitimate discovery reward.
            val atStart = runCatching {
                resolveOfficialAtStart(town.copy(
                    latitude = start.latitude, longitude = start.longitude,
                    visitedAt = start.recordedAt
                ))
            }.onFailure {
                if (it is InterruptedException) Thread.currentThread().interrupt()
            }.getOrNull()
            if (current() && atStart?.key == town.key &&
                repository.recordVerifiedStartingTown(town, start)
            ) break
        }
    }

    fun metrics(snapshot: ProgressionSnapshot) = AchievementMetrics(
        bronzeTowns = snapshot.bronzeTowns,
        silverTowns = snapshot.silverTowns,
        goldTowns = snapshot.goldTowns,
        towns = snapshot.towns,
        states = snapshot.states,
        countries = snapshot.countries,
        adsWatched = snapshot.adsWatched,
        rewardedRoads = snapshot.rewardedRoads,
        tilesUnlocked = snapshot.tilesUnlocked,
        rewardedAchievements = snapshot.rewardedAchievements,
        rewardedAchievementPoints = snapshot.rewardedAchievementPoints
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
