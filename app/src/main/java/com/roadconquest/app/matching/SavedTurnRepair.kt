package com.roadconquest.app.matching

import android.content.Context
import android.content.Intent
import android.util.Log
import com.roadconquest.app.TrackingService
import com.roadconquest.app.data.TrackPoint
import com.roadconquest.app.data.TrackingRepository
import com.roadconquest.app.util.Prefs
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Repairs previously recorded GPS while the map is open, even when Android Location
 * is OFF and a location foreground service is unavailable. Never requests live
 * location, invents geometry, or runs a persistent background service.
 */
object SavedTurnRepair {
    private const val OPEN_RETRY_COOLDOWN_MS = 5 * 60_000L
    private const val REPAIR_DEFERRAL_MS = 5 * 60_000L
    private const val MAX_GAPS_PER_OPEN = 2
    private const val PREFS_NAME = "saved_turn_repair"
    private const val KEY_LAST_OPEN_RETRY_MS = "last_open_retry_ms"
    private val inFlight = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "saved-road-repair").apply { isDaemon = true }
    }

    @Synchronized
    fun onMapOpened(context: Context) {
        val app = context.applicationContext
        if (Prefs.isDeviceDataDeletionPending(app) || Prefs.isTrackingPaused(app)) return
        if (!inFlight.compareAndSet(false, true)) return

        val now = System.currentTimeMillis()
        val prefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val last = prefs.getLong(KEY_LAST_OPEN_RETRY_MS, 0L)
        if (last != 0L && now - last in 0 until OPEN_RETRY_COOLDOWN_MS) {
            inFlight.set(false)
            return
        }
        // Limit automatic network retries across activity recreation and force-stops.
        prefs.edit().putLong(KEY_LAST_OPEN_RETRY_MS, now).apply()

        try {
            executor.execute {
                try {
                    if (!mayRepair(app)) return@execute

                    if (TrackingService.isRunning) {
                        // When live tracking is available, reuse its own serialized
                        // matcher rather than racing another network/SQLite worker.
                        app.startService(
                            Intent(app, TrackingService::class.java)
                                .setAction(TrackingService.ACTION_RETRY_SAVED_TURNS)
                        )
                    } else {
                        val repository = TrackingRepository(app)
                        // A deliberate app open is a new opportunity to check saved
                        // turns; don't let a stale five-minute retry backoff hide them.
                        repository.makePendingMatchingEligibleNow()
                        val recovered = repairEligibleGaps(
                            repository = repository,
                            match = OsrmMatcher()::match,
                            canContinue = { mayRepair(app) && !TrackingService.isRunning }
                        )
                        if (recovered > 0 && mayRepair(app)) {
                            app.sendBroadcast(
                                Intent(TrackingService.ACTION_ROADS_UPDATED)
                                    .setPackage(app.packageName)
                            )
                        }
                    }
                } catch (error: Exception) {
                    Log.w("RoadConquest", "Saved-road recovery will retry on a later opening", error)
                } finally {
                    inFlight.set(false)
                }
            }
        } catch (error: RuntimeException) {
            inFlight.set(false)
            Log.w("RoadConquest", "Could not schedule saved-road recovery", error)
        }
    }

    private fun mayRepair(context: Context) =
        !Prefs.isDeviceDataDeletionPending(context) && !Prefs.isTrackingPaused(context)

    /**
     * Purely works from stored fixes and routing evidence, no LocationManager.
     * Inject the matcher in tests to exercise accepted and rejected routes without
     * the public OSRM server. All normal matcher confidence and snap checks remain.
     */
    internal fun repairEligibleGaps(
        repository: TrackingRepository,
        match: (List<TrackPoint>) -> MatchResult?,
        canContinue: () -> Boolean = { true },
        maxGaps: Int = MAX_GAPS_PER_OPEN
    ): Int {
        var recovered = 0
        var beforeId: Long? = null
        repeat(maxGaps) {
            if (!canContinue()) return recovered
            val startId = repository.newestEligibleAnchoredGapStartId(beforeId = beforeId)
                ?: return recovered
            beforeId = startId
            val window = repository.loadMatchingWindow(limit = OsrmMatcher.MAX_MATCH_POINTS,
                retryGapStartId = startId)
            if (window.points.size < 2 || startId !in window.markableIds) return@repeat

            val result = try {
                match(window.points)
            } catch (error: Exception) {
                Log.w("RoadConquest", "Historical turn matching failed", error)
                null
            }
            if (!canContinue()) return recovered
            val acceptedRoads = result?.roads?.filter {
                it.confidence >= OsrmMatcher.MIN_ACCEPTABLE_CONFIDENCE
            }.orEmpty()
            val matchedIds = result?.matchedPointConfidences?.mapNotNull { (id, confidence) ->
                id.takeIf { it in window.markableIds &&
                    confidence >= OsrmMatcher.MIN_ACCEPTABLE_CONFIDENCE }
            }.orEmpty()
            if (acceptedRoads.isNotEmpty() && matchedIds.isNotEmpty()) {
                repository.completeMatch(acceptedRoads, matchedIds)
                recovered += matchedIds.size
            }
            // Never pretend an unmatched GPS fix was resolved. Avoid hammering
            // unroutable parking areas on every map redraw.
            val unresolved = window.markableIds - matchedIds.toSet()
            if (unresolved.isNotEmpty()) {
                repository.deferMatching(unresolved,
                    System.currentTimeMillis() + REPAIR_DEFERRAL_MS)
            }
        }
        return recovered
    }
}
