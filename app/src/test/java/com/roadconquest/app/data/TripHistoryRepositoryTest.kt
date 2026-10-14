package com.roadconquest.app.data

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37], manifest = Config.NONE)
class TripHistoryRepositoryTest {
    @Test fun stoplightsStayInOneTripButLongParkedStopsSplit() {
        val gap = TripHistoryRepository.TRIP_GAP_MS
        assertFalse(TripHistoryRepository.isTripBoundary(1_000_000L, 1_000_000L - 120_000L))
        assertFalse(TripHistoryRepository.isTripBoundary(1_000_000L, 1_000_000L - gap + 1L))
        assertTrue(TripHistoryRepository.isTripBoundary(1_000_000L, 1_000_000L - gap))
    }

    @Test fun invalidChronologyNeverMergesUnrelatedSegments() {
        assertTrue(TripHistoryRepository.isTripBoundary(1000, 2000))
        assertFalse(TripHistoryRepository.isTripBoundary(1000, 1000))
    }

    @Test fun skippedGapBeforeFinalSampleMustBreakTheRoute() {
        val context = RuntimeEnvironment.getApplication()
        TrackingRepository(context).clearHistory()
        val db = AppDatabase.get(context).writableDatabase
        val times = listOf(1_000_000L, 1_020_000L, 1_060_000L, 1_080_000L, 1_100_000L)
        times.forEachIndexed { index, time ->
            db.execSQL(
                "INSERT INTO track_points(latitude, longitude, accuracy_m, speed_mps, bearing_deg, timestamp_ms) VALUES (?,?,?,?,?,?)",
                arrayOf(40.0, -74.0 + index * 0.001, 5.0, 10.0, 0.0, time)
            )
        }

        val history = TripHistoryRepository(context)
        val trip = history.recentTrips().single()
        val downsampled = history.replay(trip, maxPoints = 2)
        assertEquals(2, downsampled.size)
        assertFalse(downsampled.first().breakBefore)
        assertTrue(downsampled.last().breakBefore)
        assertTrue(TripReplayRoute.visibleRanges(downsampled, 1).isEmpty())
    }

    @Test fun replayPreservesTrueGapsThroughSamplingAndRejectsReplacedHistory() {
        val context = RuntimeEnvironment.getApplication()
        val tracker = TrackingRepository(context)
        tracker.clearHistory()
        fun insert(times: List<Long>) {
            TrackingRepository(context).insertLocations(times.mapIndexed { i, time ->
                android.location.Location("gps").apply { latitude = 40.0; longitude = -74.0 + i * 0.0001; accuracy = 5f; this.time = time }
            })
        }
        insert((0..4).map { 1_000_000L + it * 20_000 })
        val history = TripHistoryRepository(context)
        val trip = history.recentTrips().single()
        assertFalse(history.replay(trip, 2).last().breakBefore)
        tracker.clearHistory()
        insert(listOf(1_000_000, 1_020_000, 1_060_000, 1_080_000, 1_100_000))
        assertTrue(history.replay(trip).isEmpty())
        assertTrue(history.replay(history.recentTrips().single(), 2).last().breakBefore)
    }
}
