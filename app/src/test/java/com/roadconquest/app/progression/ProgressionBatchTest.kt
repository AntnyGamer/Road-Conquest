package com.roadconquest.app.progression

import android.content.Context
import android.location.Location
import com.roadconquest.app.data.*
import com.roadconquest.app.map.PlaceOverlayCache
import com.roadconquest.app.map.PlaceOverlayData
import com.roadconquest.app.util.Prefs
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37], manifest = Config.NONE)
class ProgressionBatchTest {
    private lateinit var context: Context
    private val start = Location("gps").apply {
        latitude = 39.7; longitude = -75.1; accuracy = 5f; time = 1_000L
    }
    private val town = PlaceDiscovery(PlaceKind.TOWN, "us|new jersey|census-4-3400001",
        "Starting borough", "New Jersey", "United States", 5_000L, 39.705, -75.095)
    private fun partial(candidate: PendingPlaceCandidate) = listOf(
        PlaceDiscovery(PlaceKind.COUNTRY, "us", "United States", visitedAt = candidate.visitedAt,
            latitude = candidate.latitude, longitude = candidate.longitude),
        PlaceDiscovery(PlaceKind.STATE, "us|new jersey", "New Jersey", "United States",
            "United States", candidate.visitedAt, candidate.latitude, candidate.longitude))

    @Before fun reset() {
        context = RuntimeEnvironment.getApplication()
        Prefs.setDeviceDataDeletionPending(context, false)
        TrackingRepository(context).clearHistory()
        PlaceOverlayCache.clear(context)
    }

    @Test fun thirdPartialBaselineReconcilesStartingTownInTheSameBatch() {
        val repository = ProgressionRepository(context)
        assertTrue(repository.recordBaselineCandidate(start))
        repeat(2) {
            val baseline = repository.pendingPlaceCandidates(nowMillis = Long.MAX_VALUE).single()
            repository.resolveCandidate(baseline, partial(baseline))
        }
        AppDatabase.get(context).writableDatabase.execSQL("UPDATE place_candidates SET next_attempt_ms=0")
        assertTrue(repository.recordPlaceCandidate(Location(start).apply {
            latitude = town.latitude; longitude = town.longitude; time = town.visitedAt
        }))
        val polygon = """{"type":"Polygon","coordinates":[[
            [-75.11,39.69],[-75.09,39.69],[-75.09,39.71],[-75.11,39.71],[-75.11,39.69]
        ]]}"""
        assertTrue(PlaceOverlayCache.writeOfficialUs(context, town,
            PlaceOverlayData(town.key, town.displayName, town.kind, null, 1.0, polygon)))
        var reconciliations = 0
        val result = ProgressionManager.resolvePendingPlacesWithDetails(context, 3,
            lookup = { candidate, _, _ ->
                if (candidate.cellX == Long.MIN_VALUE) partial(candidate) else listOf(town)
            },
            reconcile = { currentRepository, discoveries, current ->
                reconciliations++
                ProgressionManager.reconcileMissingStartingTown(context, currentRepository, discoveries,
                    isCurrent = current) { town }
            })
        assertEquals(1, reconciliations)
        assertEquals(0, result.added)
        assertEquals(0L, repository.snapshot().balance)
        assertEquals(0L, repository.snapshot().towns)
        assertFalse(repository.hasMissingStartingTown())
        assertEquals(start.time, repository.placeVisitTimes(PlaceKind.TOWN, town.key)!!.firstVisitedAt)
        assertTrue(repository.pendingPlaceCandidates(nowMillis = Long.MAX_VALUE).isEmpty())
    }

    private fun queuedCandidates(): ProgressionRepository {
        val repository = ProgressionRepository(context)
        repository.recordBaselineCandidate(start)
        val baseline = repository.pendingPlaceCandidates().single()
        repository.resolveCandidate(baseline, partial(baseline) + town.copy(visitedAt = start.time))
        repeat(2) { index ->
            assertTrue(repository.recordPlaceCandidate(Location(start).apply {
                latitude += .01 * (index + 1); time += 5_000L * (index + 1)
            }))
        }
        assertEquals(2, repository.pendingPlaceCandidates().size)
        return repository
    }

    @Test fun historyDeletionDuringLookupStopsTheAlreadyLoadedPage() {
        queuedCandidates()
        var lookups = 0
        val result = ProgressionManager.resolvePendingPlacesWithDetails(context, 6,
            lookup = { _, generation, current ->
                lookups++
                TrackingRepository(context).clearHistory()
                // The history guard must work even before boundary cleanup runs.
                assertEquals(generation, PlaceOverlayCache.generation())
                assertFalse(current())
                listOf(town)
            },
            reconcile = { _, _, _ -> fail("Deleted history must not start another lookup") })
        assertEquals(1, lookups)
        assertEquals(0, result.added)
        assertTrue(ProgressionRepository(context).visitedPlaces(PlaceKind.TOWN).isEmpty())
    }

    @Test fun cacheResetDuringLookupRetainsTheOriginalGenerationAndStopsThePage() {
        val repository = queuedCandidates()
        var lookups = 0
        ProgressionManager.resolvePendingPlacesWithDetails(context, 6,
            lookup = { _, generation, current ->
                lookups++
                PlaceOverlayCache.clear(context)
                assertFalse(current())
                assertFalse(PlaceOverlayCache.writeOfficialUs(context, town,
                    PlaceOverlayData(town.key, town.displayName, town.kind, null, 1.0,
                        """{"type":"Polygon","coordinates":[[[0,0],[1,0],[1,1],[0,0]]]}"""), generation))
                listOf(town)
            },
            reconcile = { _, _, _ -> fail("Stale cache generation must not reconcile") })
        assertEquals(1, lookups)
        assertEquals(2, repository.pendingPlaceCandidates().size)
        assertNull(PlaceOverlayCache.readOfficialUs(context, town).data)
    }

    @Test fun pendingDeletionStopsLookupsBeforeHistoryIsCleared() {
        queuedCandidates()
        Prefs.setDeviceDataDeletionPending(context, true)
        try {
            val result = ProgressionManager.resolvePendingPlacesWithDetails(context, 6,
                lookup = { _, _, _ -> fail("Deletion already revoked location lookups"); null },
                reconcile = { _, _, _ -> fail("Deletion already revoked reconciliation") })
            assertEquals(0, result.added)
        } finally { Prefs.setDeviceDataDeletionPending(context, false) }
    }

    @Test fun interruptedLookupKeepsCancellationAndStopsTheRemainingPage() {
        val repository = queuedCandidates()
        var lookups = 0
        try {
            val result = ProgressionManager.resolvePendingPlacesWithDetails(context, 6,
                lookup = { _, _, _ -> lookups++; throw InterruptedException("Worker stopped") },
                reconcile = { _, _, _ -> fail("Cancelled lookups cannot reconcile") })
            assertTrue(Thread.currentThread().isInterrupted)
            assertEquals(1, lookups)
            assertEquals(0, result.added)
            assertEquals(2, repository.pendingPlaceCandidates().size)
        } finally { Thread.interrupted() }
    }
}
