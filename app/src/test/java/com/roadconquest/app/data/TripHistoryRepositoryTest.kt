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
    @Test fun tinyWalkingNoiseGroupsAreHiddenWithoutDeletingDataOrHidingCredibleShortDrives() {
        val context = RuntimeEnvironment.getApplication()
        TrackingRepository(context).clearHistory()
        val db = AppDatabase.get(context).writableDatabase
        fun insert(start: Long, speed: Double, accuracy: Double, meters: Double) {
            for (index in 0..1) db.execSQL("""INSERT INTO track_points
                (latitude,longitude,accuracy_m,speed_mps,bearing_deg,timestamp_ms,distance_m)
                VALUES (40,-74,?,?,0,?,?)""", arrayOf<Any>(accuracy,speed,start + index * 3_000,
                    if (index == 0) 0.0 else meters))
        }
        db.beginTransaction()
        try {
            insert(1_000_000, 0.0, 6.0, 25.0)
            insert(3_000_000, 1.4, 5.0, 45.0)
            insert(5_000_000, 8.0, 40.0, 30.0)
            insert(7_000_000, 8.0, 3.0, 12.0)
            insert(9_000_000, 8.0, 3.0, 0.0)
            insert(11_000_000, 0.0, 3.0, 0.0)
            // Fast walking/running may exceed the live driving filter's basic
            // speed threshold. It must not manufacture another 0.0-mile trip.
            insert(13_000_000, 2.6, 3.0, 0.0)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        val trips = TripHistoryRepository(context).recentTrips(2)
        // Legacy driving history can have no computed distance. Preserve its
        // replay/export when speed evidence exists, even with a zero total.
        assertEquals(listOf(9_000_000L, 7_000_000L), trips.map { it.startedAt })
        assertEquals(listOf(0.0, 12.0), trips.map { it.distanceMeters })
        db.rawQuery("SELECT COUNT(*) FROM track_points", null).use {
            assertTrue(it.moveToFirst()); assertEquals(14, it.getInt(0))
        }
    }

    @Test fun limitedRecentTripsKeepExactIdsCountsTimesAndDistanceWithALargeOlderArchive() {
        val context = RuntimeEnvironment.getApplication()
        TrackingRepository(context).clearHistory()
        val db = AppDatabase.get(context).writableDatabase
        db.execSQL("""WITH RECURSIVE n(x) AS (SELECT 0 UNION ALL SELECT x+1 FROM n WHERE x<19999)
            INSERT INTO track_points(latitude,longitude,accuracy_m,speed_mps,bearing_deg,timestamp_ms,distance_m)
            SELECT 40,-74,3,8,90,1000000+(x/2)*1000000+(x%2)*1000,1.5 FROM n""")
        val maxId = db.rawQuery("SELECT MAX(id) FROM track_points", null).use { check(it.moveToFirst()); it.getLong(0) }
        val trips = TripHistoryRepository(context).recentTrips(100)
        assertEquals(100, trips.size)
        trips.forEachIndexed { i, trip ->
            assertEquals(maxId - 2*i, trip.lastId)
            assertEquals(maxId - 2*i - 1, trip.firstId)
            assertEquals(1_000_000L + (9999-i)*1_000_000L, trip.startedAt)
            assertEquals(trip.startedAt + 1000, trip.endedAt)
            assertEquals(2, trip.pointCount)
            assertEquals(3.0, trip.distanceMeters, 0.0)
        }
    }

    @Test fun longTripsSpanPagesAndSingletonsBetweenTripsNeverConsumeTheTripLimit() {
        val context = RuntimeEnvironment.getApplication()
        TrackingRepository(context).clearHistory()
        val db = AppDatabase.get(context).writableDatabase
        fun insert(count: Int, start: Long, interval: Long) {
            db.execSQL("""WITH RECURSIVE n(x) AS (SELECT 0 UNION ALL SELECT x+1 FROM n WHERE x<?)
                INSERT INTO track_points(latitude,longitude,accuracy_m,speed_mps,bearing_deg,timestamp_ms,distance_m)
                SELECT 40,-74,3,8,90,?+x*?,0.5 FROM n""", arrayOf<Any>(count-1,start,interval))
        }
        insert(20, 1_000_000, 1000)
        insert(3, 10_000_000, 2_000_000) // Three singletons, each separated by a parked stop.
        insert(20000, 100_000_000, 3000)
        val history = TripHistoryRepository(context)
        val trips = history.recentTrips(2)
        assertEquals(listOf(20000, 20), trips.map { it.pointCount })
        assertEquals(listOf(10000.0, 10.0), trips.map { it.distanceMeters })
        assertEquals(listOf(100_000_000L, 1_000_000L), trips.map { it.startedAt })
        assertEquals(159_997_000L, trips.first().endedAt)
        assertEquals(trips.take(1), history.recentTrips(1))
    }

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
        db.beginTransaction()
        try {
            times.forEachIndexed { index, time ->
                db.execSQL(
                    "INSERT INTO track_points(latitude, longitude, accuracy_m, speed_mps, bearing_deg, timestamp_ms) VALUES (?,?,?,?,?,?)",
                    arrayOf<Any>(40.0, -74.0 + index * 0.001, 5.0, 10.0, 0.0, time)
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }

        val history = TripHistoryRepository(context)
        val trip = history.recentTrips().single()
        val downsampled = history.replay(trip, maxPoints = 2)
        assertEquals(2, downsampled.size)
        assertFalse(downsampled.first().breakBefore)
        assertTrue(downsampled.last().breakBefore)
        assertTrue(TripReplayRoute.prepare(downsampled).visibleRanges(1).isEmpty())
    }

    @Test fun replayDownsamplingPreservesSampleTimesAndTrueEndpoint() {
        val context = RuntimeEnvironment.getApplication()
        TrackingRepository(context).clearHistory()
        TrackingRepository(context).insertLocations((0 until 55).map { index ->
            android.location.Location("gps").apply { speed = 8f;
                latitude = 40.0
                longitude = -74.0 + index * 0.00001
                accuracy = 5f
                time = 1_000_000L + index * 5_000L
            }
        })
        val history = TripHistoryRepository(context)
        val trip = history.recentTrips().single()
        // ceil(55 / 6) = 10. Keep the five selected samples and the true last fix.
        val sampled = history.replay(trip, maxPoints = 6)
        assertEquals(listOf(0, 10, 20, 30, 40, 54).map { 1_000_000L + it * 5_000L },
            sampled.map { it.timestamp })
        assertTrue(sampled.none { it.breakBefore })
        // A final point already sampled by the stride must not be duplicated.
        assertEquals(55, history.replay(trip, maxPoints = 55).size)
    }

    @Test fun tripInsightsKeepTileAndPendingCountsAccurateInOneSnapshot() {
        val context = RuntimeEnvironment.getApplication()
        TrackingRepository(context).clearHistory()
        val repository = TrackingRepository(context)
        val fixes = (0..3).map { index ->
            android.location.Location("gps").apply { speed = 8f;
                latitude = 40.0
                longitude = -74.0 + index * 0.0001
                accuracy = 5f
                time = 1_000_000L + index * 10_000L
            }
        }
        repository.insertLocations(fixes)
        assertTrue(repository.recordExploredGridCell(fixes[1]))
        val history = TripHistoryRepository(context)
        val trip = history.recentTrips().single()
        val insights = history.insights(trip)
        assertEquals(0L, insights.newRoadGroups)
        assertEquals(0L, insights.newTowns)
        assertEquals(1L, insights.newTiles)
        assertEquals(4L, insights.pendingFixes)
    }

    @Test fun newRoadInsightsExcludeLaterFragmentsOfAlreadyUnlockedRoadGroups() {
        val context = RuntimeEnvironment.getApplication()
        TrackingRepository(context).clearHistory()
        val tracker = TrackingRepository(context)
        tracker.insertLocations((0..2).map { index -> android.location.Location("gps").apply {
            latitude = 40.0; longitude = -74.0 + index * 0.0001
            accuracy = 5f; speed = 8f; time = 1_000_000L + index * 10_000L
        } })
        val db = tracker.readableDatabase()
        fun road(id: String, group: String, firstAt: Long) = db.execSQL(
            """INSERT INTO roads(segment_id,name,geometry_json,first_unlocked_at,last_driven_at,
                road_group_id,min_lat,max_lat,min_lon,max_lon)
                VALUES (?,'Road','[[-74,40],[-73.9,40]]',?,?,?,40,40,-74,-73.9)""",
            arrayOf(id, firstAt, firstAt, group)
        )
        road("old", "shared", 900_000)
        road("fragment", "shared", 1_005_000)
        road("new-first", "fresh", 1_000_000)
        road("new-second", "fresh", 1_020_000)
        road("legacy-old", "", 900_000)
        road("legacy-new", "", 1_010_000)
        road("excluded", "x2|excluded", 1_010_000)
        road("future", "future", 1_020_001)
        val history = TripHistoryRepository(context)
        val insights = history.insights(history.recentTrips().single())
        assertEquals(2L, insights.newRoadGroups)
        assertEquals(0L, insights.newTowns)
        assertEquals(0L, insights.newTiles)
        assertEquals(3L, insights.pendingFixes)
    }

    @Test fun savedTripGpxDefaultsToPrivateEndpointsAndFullExportIsExplicit() {
        val context = RuntimeEnvironment.getApplication()
        val tracker = TrackingRepository(context)
        tracker.clearHistory()
        // Clear-history increments the repository generation; a stale repository
        // correctly refuses further writes. Use a newly attached repository.
        TrackingRepository(context).insertLocations((0..36).map { index ->
            android.location.Location("gps").apply { speed = 8f;
                latitude = 40.0
                longitude = -74.0 + index * 0.0001
                accuracy = 5f
                time = 1_000_000L + index * 10_000L
            }
        })
        val history = TripHistoryRepository(context)
        val trip = history.recentTrips().single()
        // Data-layer entitlement is enforced even if a new screen forgets
        // to check the Play purchase before invoking GPX export.
        com.roadconquest.app.billing.VipEntitlement.updateFromPlay(context, false)
        assertThrows(IllegalStateException::class.java) {
            history.exportGpx(trip, java.io.ByteArrayOutputStream())
        }
        assertThrows(IllegalArgumentException::class.java) { history.recentTrips(101) }
        com.roadconquest.app.billing.VipEntitlement.updateFromPlay(context, true)
        try {
            assertEquals(1, history.recentTrips(500).size)
        val privateOutput = java.io.ByteArrayOutputStream()
        val visible = history.exportGpx(trip, privateOutput)
        val hiddenXml = privateOutput.toString("UTF-8")
        assertEquals(13, visible) // Both privacy cutoffs remain inclusive.
        assertTrue(hiddenXml.startsWith("<?xml version="))
        assertTrue(hiddenXml.contains("<gpx version="))
        assertTrue(hiddenXml.contains("<trkseg>"))
        assertTrue(hiddenXml.contains("</trkseg>"))
        assertFalse(hiddenXml.contains("1970-01-01T00:16:40Z")) // first time
        val fullOutput = java.io.ByteArrayOutputStream()
        assertEquals(37, history.exportGpx(trip, fullOutput, hideTripEnds = false))
        assertTrue(fullOutput.toString("UTF-8").contains("1970-01-01T00:16:40Z"))
        tracker.clearHistory()
        assertThrows(IllegalStateException::class.java) {
            history.exportGpx(trip, java.io.ByteArrayOutputStream(), hideTripEnds = false)
        }
        } finally {
            com.roadconquest.app.billing.VipEntitlement.updateFromPlay(context, false)
        }
    }

    @Test fun resetDuringSlowGpxFlushInvalidatesExport() {
        val context = RuntimeEnvironment.getApplication()
        TrackingRepository(context).clearHistory()
        val tracker = TrackingRepository(context)
        tracker.insertLocations((0..2).map { i ->
            android.location.Location("gps").apply { speed = 8f;
                latitude = 40.0
                longitude = -74.0 + i * 0.0001
                accuracy = 5f
                time = 1_000_000L + i * 10_000L
            }
        })
        val history = TripHistoryRepository(context)
        val trip = history.recentTrips().single()
        val output = object : java.io.ByteArrayOutputStream() {
            override fun flush() {
                super.flush()
                // Simulate a reset while a cloud document provider finishes I/O.
                TrackingRepository(context).clearHistory()
            }
        }
        com.roadconquest.app.billing.VipEntitlement.updateFromPlay(context, true)
        try {
            assertThrows(IllegalStateException::class.java) {
                history.exportGpx(trip, output, hideTripEnds = false)
            }
            assertFalse(history.isCurrentTrip(trip))
        } finally {
            com.roadconquest.app.billing.VipEntitlement.updateFromPlay(context, false)
        }
    }

    @Test fun replayPreservesTrueGapsThroughSamplingAndRejectsReplacedHistory() {
        val context = RuntimeEnvironment.getApplication()
        val tracker = TrackingRepository(context)
        tracker.clearHistory()
        fun insert(times: List<Long>) {
            TrackingRepository(context).insertLocations(times.mapIndexed { i, time ->
                android.location.Location("gps").apply { speed = 8f; latitude = 40.0; longitude = -74.0 + i * 0.0001; accuracy = 5f; this.time = time }
            })
        }
        insert((0..4).map { 1_000_000L + it * 20_000 })
        val history = TripHistoryRepository(context)
        val trip = history.recentTrips().single()
        val oldGeneration = history.currentHistoryGeneration()
        assertEquals(oldGeneration, trip.historyGeneration)
        assertFalse(history.replay(trip, 2).last().breakBefore)
        tracker.clearHistory()
        assertNotEquals("A data reset invalidates every previously selected route",
            oldGeneration, history.currentHistoryGeneration())
        insert(listOf(1_000_000, 1_020_000, 1_060_000, 1_080_000, 1_100_000))
        assertTrue(history.replay(trip).isEmpty())
        assertTrue(history.replay(history.recentTrips().single(), 2).last().breakBefore)
    }
}
