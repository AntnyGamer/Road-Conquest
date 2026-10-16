package com.roadconquest.app.data

import android.content.Context
import android.location.Location
import com.roadconquest.app.map.FogGrid
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37], manifest = Config.NONE)
class TrackingRepositoryTest {
    private lateinit var repository: TrackingRepository

    @Before fun setup() {
        val context: Context = RuntimeEnvironment.getApplication()
        repository = TrackingRepository(context)
        repository.readableDatabase().execSQL("DELETE FROM track_points")
        repository.readableDatabase().execSQL("DELETE FROM skipped_driving_fixes")
        repository.readableDatabase().execSQL("DELETE FROM road_visits")
        repository.readableDatabase().execSQL("DELETE FROM roads")
    }

    private fun point(time: Long, lat: Double = 40.0, lon: Double = -74.0, speed: Float = 5f): Long =
        repository.insertLocation(Location("gps").apply {
            latitude = lat
            longitude = lon
            accuracy = 5f
            this.speed = speed
            this.time = time
        })

    @Test fun nearbyTileCountUsesOneQueryAndHandlesTheDateLine() {
        val context = RuntimeEnvironment.getApplication()
        AppDatabase.get(context).writableDatabase.delete("explored_grid", null, null)
        val origin = FogGrid.cell(40.0, 179.999)!!
        val nearby = FogGrid.neighbors(origin)
        val acrossDateLine = nearby.first { it.row == origin.row && it.column == 0 }
        val outside = FogGrid.neighbors(origin, radius = 3).first { it !in nearby }

        fun unlock(cell: FogGrid.Cell) {
            val (lat, lon) = FogGrid.center(cell)
            assertTrue(repository.recordExploredGridCell(Location("gps").apply {
                latitude = lat; longitude = lon; accuracy = 5f; time = 1_000_000L
            }))
        }
        unlock(origin)
        unlock(acrossDateLine)
        unlock(outside)
        assertEquals(2 to nearby.size, repository.nearbyExploration(40.0, 179.999))
        // An invalid position must never report arbitrary unlocked cells.
        assertEquals(0 to 0, repository.nearbyExploration(Double.NaN, 0.0))
    }

    @Test fun wholeRoadVisitsUseSeparateJourneysAcrossDistinctTinySegments() {
        // Real exports split each named road into hundreds of little fragments,
        // each recorded only once. Four fragments spanning two journeys are TWO
        // visits to the road, never "about one" nor four.
        val geometries = listOf(
            "[[-74.0000,40.0],[-73.9999,40.0]]",
            "[[-73.9999,40.0],[-73.9998,40.0]]",
            "[[-73.9998,40.0],[-73.9997,40.0]]",
            "[[-73.9997,40.0],[-73.9996,40.0]]"
        )
        val timestamps = listOf(
            1_000_000L, 1_003_000L, 1_800_000L, 1_803_000L
        )
        for (i in geometries.indices) {
            repository.upsertRoads(listOf(MatchedRoad(
                "Repeated Road", geometries[i], timestamps[i], timestamps[i] + 3_000L, 0.99
            )))
        }
        val roads = repository.getRoadsInBounds(41.0, -73.0, 39.0, -75.0)
        assertEquals(4, roads.size)
        assertEquals(1, roads.minOf { it.timesDriven })
        // The stored visits remain a single occurrence on every individual
        // segment, but road-wide estimation groups them by route identity.
        for (road in roads) assertEquals(2, repository.estimatedWholeRoadVisitCount(road))
    }

    @Test fun similarlyNamedDisconnectedRoadsDoNotShareDriveCounts() {
        repository.upsertRoads(listOf(
            MatchedRoad("Washington Avenue",
                "[[-74.0,40.0],[-73.9999,40.0]]", 1_000_000L, 1_003_000L, 1.0),
            MatchedRoad("Washington Avenue",
                "[[-74.5,40.4],[-74.4999,40.4]]", 2_000_000L, 2_003_000L, 1.0)
        ))
        val roads = repository.getRoadsInBounds(41.0, -73.0, 39.0, -75.0)
        assertEquals(2, roads.size)
        for (road in roads) assertEquals(1, repository.estimatedWholeRoadVisitCount(road))
    }

    @Test fun individualRoadVisitsRemainAvailableForVisualTurnRepair() {
        val geometry = "[[-74.001,40.0],[-74.0,40.0002]]"
        val first = MatchedRoad("Visit test", geometry, 1_000_000L, 1_005_000L, 0.99)
        val second = first.copy(firstTimestamp = 2_000_000L, lastTimestamp = 2_005_000L)
        repository.upsertRoads(listOf(first))
        repository.upsertRoads(listOf(second))
        // The bounded turn-repair query intentionally skips single-road viewports.
        repository.upsertRoads(listOf(MatchedRoad("Other road",
            "[[-74.005,40.0],[-74.004,40.0002]]", 3_000_000L, 3_005_000L, 0.99)))
        val roads = repository.getRoadsInBounds(41.0, -73.0, 39.0, -75.0)
        assertEquals(2, roads.size)
        val road = roads.single { it.name == "Visit test" }
        val visits = repository.getVisitWindowsForRoads(roads)[road.segmentId]
        assertNotNull(visits)
        assertEquals(setOf(1_000_000L to 1_005_000L, 2_000_000L to 2_005_000L),
            requireNotNull(visits).toSet())
        assertTrue(repository.getVisitWindowsForRoads(emptyList()).isEmpty())
    }

    @Test fun deletingHistoryClearsEverythingAndPreventsOldWorkersFromRestoringIt() {
        val context = RuntimeEnvironment.getApplication()
        val fix = Location("gps").apply {
            latitude = 40.0; longitude = -74.0; accuracy = 5f; time = 1_000_000L
        }
        val road = MatchedRoad("Deleted road", "[[-74,40],[-74,40.0002]]", 1, 2, 1.0)
        val oldId = repository.insertLocation(fix)
        repository.recordExploredPlace(fix)
        repository.upsertRoads(listOf(road))
        TrackingRepository(context).clearHistory()

        // These represent a GPS queue and a matcher returning after the delete finished.
        assertEquals(-1L, repository.insertLocation(fix))
        repository.insertLocations(listOf(fix))
        assertFalse(repository.recordExploredPlace(fix))
        repository.upsertRoads(listOf(road))
        repository.completeMatch(listOf(road), listOf(oldId))
        repository.markMatched(listOf(oldId))
        repository.deferMatching(listOf(oldId), 2_000_000L)
        assertEquals(0L, repository.getSummary().trackPointCount)
        assertEquals(0L, repository.getSummary().roadsUnlockedCount)
        assertEquals(0.0, repository.getSummary().distanceMeters, 0.0)
        assertTrue(repository.getExploredPlacesInBounds(90.0, 180.0, -90.0, -180.0).isEmpty())

        val restarted = TrackingRepository(context)
        assertTrue(restarted.insertLocation(fix) > oldId)
        restarted.upsertRoads(listOf(road))
        assertTrue(restarted.recordExploredPlace(fix))
        assertEquals(1L, restarted.getSummary().trackPointCount)
        assertEquals(1L, restarted.getSummary().roadsUnlockedCount)
    }

    @Test fun batchesKeepFullSegmentOverlapAndRetryContext() {
        val ids = (0..5).map { point(1_000_000L + it * 3_000L) }
        val first = repository.loadMatchingWindow(limit = 4)
        assertEquals(ids.takeLast(4), first.points.map { it.id })
        assertEquals(ids.takeLast(2).toSet(), first.markableIds)
        repository.markMatched(first.markableIds.toList())

        val middle = repository.loadMatchingWindow(limit = 4)
        assertEquals(ids.subList(1, 5), middle.points.map { it.id })
        assertEquals(ids.subList(2, 4).toSet(), middle.markableIds)
        repository.markMatched(middle.markableIds.toList())

        val oldest = repository.loadMatchingWindow(limit = 4)
        assertEquals(listOf(ids[0], ids[1], ids[2]), oldest.points.map { it.id })
        assertEquals(ids.take(2).toSet(), oldest.markableIds)
    }

    @Test fun olderEightPointTurnGetsFullMatchedContextInsteadOfOnePointRetry() {
        val ids = (0..9).map { i ->
            point(1_000_000L + i * 2_000L, lat = 40.0 + i * 0.0001)
        }
        repository.markMatched(listOf(ids.first(), ids.last()))
        val window = repository.loadMatchingWindow(limit = 10, retryGapStartId = ids[1])
        assertEquals(ids, window.points.map { it.id })
        assertEquals(ids.subList(1, 9).toSet(), window.markableIds)
        assertTrue(window.points.first().matched)
        assertTrue(window.points.last().matched)
    }

    @Test fun shortOldGapStillRetainsMultipleMatchedApproachAndExitFixes() {
        val ids = (0..7).map { i ->
            point(1_000_000L + i * 2_000L, lat = 40.0 + i * 0.0001)
        }
        repository.markMatched(ids.filterIndexed { index, _ -> index !in 3..4 })
        val window = repository.loadMatchingWindow(limit = 10, retryGapStartId = ids[3])
        assertEquals(ids.subList(3, 5).toSet(), window.markableIds)
        assertTrue(window.points.any { it.id == ids[1] })
        assertTrue(window.points.any { it.id == ids[6] })
    }

    @Test fun newestAnchoredMultiPointGapWinsOverHundredsOfOlderIsolatedFixes() {
        // The October 10 export has 319 old isolated unmatched fixes ahead of
        // the six/eight-point intersections. Prioritize the latest recoverable
        // island without dropping any previous GPS or retry records.
        val ids = (0..420).map { i ->
            point(1_000_000L + i * 2_000L, lat = 40.0 + i * 0.00007)
        }
        val olderGap = (100..105).toSet()
        val newerGap = (355..362).toSet()
        val isolated = (10..330 step 3).toSet() - olderGap
        val leftUnmatched = olderGap + newerGap + isolated + setOf(420)
        repository.markMatched(ids.filterIndexed { i, _ -> i !in leftUnmatched })
        assertEquals(ids[355], repository.newestEligibleAnchoredGapStartId())
        assertEquals("Next priority slot should visit the next eligible corner",
            ids[100], repository.newestEligibleAnchoredGapStartId(beforeId = ids[355]))
        assertNull("After the oldest corner, priority selection wraps to the newest",
            repository.newestEligibleAnchoredGapStartId(beforeId = ids[100]))

        // Already tried: deferring the newer island should allow the next one
        // while older isolated points remain available for separate fair retries.
        repository.deferMatching(ids.subList(355, 363), System.currentTimeMillis() + 300_000L)
        assertEquals(ids[100], repository.newestEligibleAnchoredGapStartId())
        assertEquals(ids[10], repository.oldestEligibleRetryId())
        assertTrue(repository.loadMatchingWindow(limit = 10,
            retryGapStartId = ids[100]).markableIds.containsAll(ids.subList(100, 106)))
    }

    @Test fun prioritizationNeverIncludesOpenEndedOrUnanchoredOldGps() {
        val ids = (0..12).map { i ->
            point(1_000_000L + i * 2_000L, lat = 40.0 + i * 0.00007)
        }
        // No matched fix AFTER the latest island: do not send an unanchored
        // bearing-free retry and risk drawing a guessed connection.
        repository.markMatched(ids.take(5))
        assertNull(repository.newestEligibleAnchoredGapStartId())
        // A previously resolved gap may now have anchors.
        repository.markMatched(listOf(ids.last()))
        assertEquals(ids[5], repository.newestEligibleAnchoredGapStartId())
    }

    @Test fun liveMatchingBadgeIgnoresOldUnresolvedDrivesButShowsRecentPendingGps() {
        val start = 1_000_000L
        val now = start + 2 * 60 * 60_000L
        val oldId = point(start, lon = -74.0)
        assertFalse("A historic unresolved corner is not current tracking activity",
            repository.hasUnmatchedDrivingFixes(now))
        val recentId = point(now - 20_000L, lon = -74.0003)
        assertTrue(repository.hasUnmatchedDrivingFixes(now))
        repository.markMatched(listOf(recentId))
        assertFalse("Old unmatched evidence remains saved but cannot cause a permanent live warning",
            repository.hasUnmatchedDrivingFixes(now))
        assertTrue("Old evidence must remain retryable", repository.loadMatchingWindow().points.any {
            it.id == oldId
        })
    }

    @Test fun pendingGpsIntervalsRemainContinuousUntilMatchingCompletes() {
        val ids = (0..4).map { point(1_000_000L + it * 3_000L, lon = -74.0 + it * 0.0001) }
        repository.markMatched(listOf(ids.first(), ids.last()))
        val pending = repository.getPendingRouteInBounds(41.0, -73.0, 39.0, -75.0)
        assertEquals(1, pending.size)
        assertEquals(5, org.json.JSONArray(pending.single().geometryJson).length())
        repository.markMatched(ids)
        assertTrue(repository.getPendingRouteInBounds(41.0, -73.0, 39.0, -75.0).isEmpty())
    }

    @Test fun oldUnmatchedEvidenceExpiresFromPreviewButRemainsRetryable() {
        val ids = listOf(
            point(1_000_000L, lon = -74.0),
            point(1_003_000L, lon = -73.9999)
        )
        val first = repository.getPendingRouteInBoundsResult(
            41.0, -73.0, 39.0, -75.0, visibleSinceMillis = 1_000_000L
        )
        assertEquals(1, first.roads.size)
        assertEquals(1_000_000L, first.oldestVisiblePendingTimestampMillis)
        val second = repository.getPendingRouteInBoundsResult(
            41.0, -73.0, 39.0, -75.0, visibleSinceMillis = 1_000_001L
        )
        assertEquals(1, second.roads.size)
        assertEquals(1_003_000L, second.oldestVisiblePendingTimestampMillis)
        val expired = repository.getPendingRouteInBoundsResult(
            41.0, -73.0, 39.0, -75.0, visibleSinceMillis = 1_003_001L
        )
        assertTrue(expired.roads.isEmpty())
        assertNull(expired.oldestVisiblePendingTimestampMillis)
        assertEquals(ids, repository.loadMatchingWindow().points.map { it.id })
    }

    @Test fun isolatedPendingFixCannotAccelerateAnotherLineExpiry() {
        point(1_000_000L)
        point(1_200_000L, lon = -73.9998)
        point(1_203_000L, lon = -73.9997)
        val pending = repository.getPendingRouteInBoundsResult(
            41.0, -73.0, 39.0, -75.0, visibleSinceMillis = 999_000L
        )
        assertEquals(1, pending.roads.size)
        assertEquals(2, org.json.JSONArray(pending.roads.single().geometryJson).length())
        assertEquals(1_200_000L, pending.oldestVisiblePendingTimestampMillis)
    }

    @Test fun recordedRouteNeverConnectsSeparateTripsOrMissingRawSamples() {
        point(1_000_000L)
        point(1_003_000L, lon = -73.9999)
        val removed = point(1_006_000L, lon = -73.9998)
        point(1_009_000L, lon = -73.9997)
        point(2_000_000L, lon = -73.9996)
        repository.readableDatabase().delete("track_points", "id = ?", arrayOf(removed.toString()))
        val pending = repository.getPendingRouteInBounds(41.0, -73.0, 39.0, -75.0)
        assertEquals(1, pending.size)
        assertEquals(2, org.json.JSONArray(pending.single().geometryJson).length())
    }

    @Test fun overlappingMatcherWindowsNeverDeleteEarlierGeometry() {
        val ids = (0..3).map { point(1_000_000L + it * 3_000L, lon = -74.0 + it * 0.0001) }
        repository.completeMatch(
            listOf(MatchedRoad("Main", "[[-74,40],[-73.9999,40]]", 1_000_000L, 1_003_000L, 1.0)),
            ids.take(2)
        )
        // This mimics an overlapping matcher window. The second completion must not erase
        // the first piece merely because their time/anchor ranges touch.
        repository.completeMatch(
            listOf(MatchedRoad("Main", "[[-73.9999,40],[-73.9998,40]]", 1_003_000L, 1_006_000L, 1.0)),
            ids.subList(1, 3)
        )
        val roads = repository.getRoadsInBounds(41.0, -73.0, 39.0, -75.0)
        assertEquals(2, roads.size)
        assertEquals(2, roads.map { it.geometryJson }.toSet().size)
        assertEquals(1L, repository.getSummary().roadsUnlockedCount)
    }

    @Test fun shortTurnRetryUsesSpareContextSlotsAtTenPointLimit() {
        val ids = (0..6).map { point(1_000_000L + it * 3_000L, lon = -74.0 + it * 0.0001) }
        repository.markMatched(listOf(ids[0], ids[1], ids[3], ids[4], ids[5], ids[6]))

        val retry = repository.loadMatchingWindow(limit = 10)
        assertEquals(setOf(ids[2]), retry.markableIds)
        assertEquals(ids, retry.points.map { it.id })
    }

    @Test fun retryBalancesContextWithoutMarkingAlreadyResolvedNeighbors() {
        val ids = (0..16).map { point(1_000_000L + it * 3_000L, lon = -74.0 + it * 0.0001) }
        repository.markMatched(ids.filter { it != ids[8] })

        val retry = repository.loadMatchingWindow(limit = 10)
        assertEquals(ids.subList(3, 13), retry.points.map { it.id })
        assertEquals(setOf(ids[8]), retry.markableIds)
    }

    @Test fun retryNearDriveEndUsesRemainingExitFixAndStaysWithinServerLimit() {
        val ids = (0..8).map { point(1_000_000L + it * 3_000L, lon = -74.0 + it * 0.0001) }
        repository.markMatched(ids.filter { it != ids[4] && it != ids[5] })

        val retry = repository.loadMatchingWindow(limit = 10)
        assertEquals(ids, retry.points.map { it.id })
        assertEquals(setOf(ids[4], ids[5]), retry.markableIds)
    }

    @Test fun expandedRetryContextStopsAtMissingRawFixesAndTripBoundaries() {
        val ids = (0..10).map { point(1_000_000L + it * 3_000L, lon = -74.0 + it * 0.0001) }
        repository.markMatched(ids.filter { it != ids[5] })
        repository.readableDatabase().delete("track_points", "id = ?", arrayOf(ids[2].toString()))
        repository.readableDatabase().delete("track_points", "id = ?", arrayOf(ids[8].toString()))
        point(2_000_000L)
        repository.markMatched(listOf(ids.last() + 1))

        val retry = repository.loadMatchingWindow(limit = 10)
        assertEquals(ids.subList(3, 8), retry.points.map { it.id })
        assertEquals(setOf(ids[5]), retry.markableIds)
    }

    @Test fun finalizationMakesDeferredTurnPointsImmediatelyEligible() {
        val ids = (0..2).map { point(1_000_000L + it * 3_000L) }
        repository.deferMatching(ids, System.currentTimeMillis() + 300_000L)
        assertTrue(repository.loadMatchingWindow().points.isEmpty())

        repository.makePendingMatchingEligibleNow()
        assertEquals(ids, repository.loadMatchingWindow().points.map { it.id })
    }

    @Test fun separatedUnmatchedIslandsRetryWithResolvedNeighbors() {
        val ids = (0..5).map { point(1_000_000L + it * 3_000L) }
        repository.markMatched(listOf(ids[1], ids[3], ids[5]))

        val newest = repository.loadMatchingWindow(limit = 4)
        assertEquals(listOf(ids[3], ids[4], ids[5]), newest.points.map { it.id })
        assertEquals(setOf(ids[4]), newest.markableIds)
        repository.markMatched(newest.markableIds.toList())

        val middle = repository.loadMatchingWindow(limit = 4)
        assertEquals(listOf(ids[1], ids[2], ids[3]), middle.points.map { it.id })
        assertEquals(setOf(ids[2]), middle.markableIds)
    }

    @Test fun tenPointBatchesDrainBacklogWithoutMissingBoundaryEdges() {
        val ids = (0 until 27).map { point(1_000_000L + it * 3_000L) }
        val covered = mutableSetOf<Pair<Long, Long>>()
        var batches = 0
        while (true) {
            val window = repository.loadMatchingWindow(limit = 10)
            if (window.points.isEmpty()) break
            assertTrue(window.points.size in 2..10)
            covered += window.points.map { it.id }.zipWithNext()
            repository.markMatched(window.markableIds.toList())
            assertTrue("Backlog must make progress", ++batches <= 5)
        }
        assertEquals(4, batches)
        assertEquals(ids.zipWithNext().toSet(), covered)
    }

    @Test fun hundredPointProductionBatchesDrainLongDriveInTwoCalls() {
        val ids = (0 until 180).map { point(1_000_000L + it * 3_000L, lon = -74.0 + it * 0.00001) }
        val covered = mutableSetOf<Pair<Long, Long>>()
        var batches = 0
        while (true) {
            val window = repository.loadMatchingWindow(limit = 100)
            if (window.points.isEmpty()) break
            assertTrue(window.points.size in 2..100)
            covered += window.points.map { it.id }.zipWithNext()
            repository.markMatched(window.markableIds.toList())
            assertTrue("Bulk matching must drain the queue", ++batches <= 3)
        }
        assertEquals(2, batches)
        assertEquals(ids.zipWithNext().toSet(), covered)
    }

    @Test fun separateTripsAreNeverJoined() {
        point(1_000_000)
        point(1_003_000)
        val current = listOf(point(2_000_000), point(2_003_000))
        val window = repository.loadMatchingWindow()
        assertEquals(current, window.points.map { it.id })
        assertEquals(current.toSet(), window.markableIds)
    }

    @Test fun multiMinuteMovingGapNearTheSameAreaDoesNotInventAConnection() {
        val old = point(1_000_000L, 40.0000, speed = 5f)
        val current = listOf(
            point(1_120_000L, 40.0005, speed = 5f),
            point(1_123_000L, 40.0007, speed = 5f)
        )

        val window = repository.loadMatchingWindow()
        assertEquals(current, window.points.map { it.id })
        assertFalse(old in window.markableIds)

        val pending = repository.getPendingRouteInBounds(41.0, -73.0, 39.0, -75.0)
        assertEquals(1, pending.size)
        assertEquals(
            2,
            org.json.JSONArray(pending.single().geometryJson).length()
        )
    }

    @Test fun nearbySavedStopAnchorReconnectsWithinTheSameParkingPause() {
        val ids = listOf(
            point(1_000_000L),
            point(1_600_000L, 40.0004, speed = 0f),
            point(1_603_000L, 40.0006)
        )
        val window = repository.loadMatchingWindow()
        assertEquals(ids, window.points.map { it.id })
        assertEquals(ids.toSet(), window.markableIds)
    }

    @Test fun veryLongStopStartsANewMatchingSessionEvenWhenNearbyAndStationary() {
        point(1_000_000L, speed = 0f)
        val current = listOf(
            point(2_000_000L, 40.0004, speed = 0f),
            point(2_003_000L, 40.0006)
        )
        val window = repository.loadMatchingWindow()
        assertEquals(current, window.points.map { it.id })
        assertEquals(current.toSet(), window.markableIds)
    }

    @Test fun longGapDoesNotBridgeASeparateDistantDrive() {
        point(1_000_000L)
        val current = listOf(
            point(1_120_000L, 40.01),
            point(1_123_000L, 40.0102)
        )
        val window = repository.loadMatchingWindow()
        assertEquals(current, window.points.map { it.id })
        assertEquals(current.toSet(), window.markableIds)
    }

    @Test fun missingSpeedSentinelCannotMakeMovingLongGapLookStationary() {
        val old = point(1_000_000L, 40.0, speed = 0f)
        val current = listOf(
            point(1_045_000L, 40.00095, speed = 0f),
            point(1_048_000L, 40.00115, speed = 5f)
        )

        val window = repository.loadMatchingWindow()
        assertEquals(current, window.points.map { it.id })
        assertEquals(current.toSet(), window.markableIds)
        assertFalse(old in window.markableIds)
    }

    @Test fun fortyFiveSecondGpsBlackoutCannotInventAConnectingRoad() {
        point(1_000_000L, 40.0)
        val current = listOf(
            point(1_045_000L, 40.01),
            point(1_048_000L, 40.0102)
        )
        val window = repository.loadMatchingWindow()
        assertEquals(current, window.points.map { it.id })
        assertEquals(current.toSet(), window.markableIds)
        assertEquals(1, repository.getPendingRouteInBounds(41.0, -73.0, 39.0, -75.0).size)
        assertEquals(2, org.json.JSONArray(
            repository.getPendingRouteInBounds(41.0, -73.0, 39.0, -75.0).single().geometryJson
        ).length())
    }

    @Test fun deferredNewestBatchDoesNotStarveOlderTrip() {
        val older = listOf(point(1_000_000), point(1_003_000))
        val newer = listOf(point(2_000_000), point(2_003_000))
        repository.deferMatching(newer, 10_000_000)
        assertEquals(older, repository.loadMatchingWindow(nowMillis = 5_000_000).points.map { it.id })
        assertEquals(newer, repository.loadMatchingWindow(nowMillis = 11_000_000).points.map { it.id })
    }

    @Test fun olderUnattemptedTurnGetsSpareBatchBeforeNewerExpiredRetry() {
        val ids = (0..8).map { point(1_000_000L + it * 3_000L, lon = -74.0 + it * 0.0001) }
        repository.markMatched(ids.filter { it != ids[2] && it != ids[7] })
        repository.deferMatching(listOf(ids[7]), 100L)

        // Live matching stays focused on the newest unfinished section.
        assertEquals(
            setOf(ids[7]),
            repository.loadMatchingWindow(limit = 10, nowMillis = 200L).markableIds
        )
        // Spare batches must not overlook an old corner merely because it has no backoff.
        val cap = repository.oldestEligibleRetryId(nowMillis = 200L)
        assertEquals(ids[2], cap)
        val retry = repository.loadMatchingWindow(limit = 10, nowMillis = 200L, maxPendingId = cap)
        assertEquals(setOf(ids[2]), retry.markableIds)
        assertTrue(retry.points.size >= 3)
        repository.markMatched(listOf(ids[2], ids[7]))
        assertNull(repository.oldestEligibleRetryId(nowMillis = 200L))
    }

    @Test fun expiredRetryCanBeSelectedWithoutGivingUpFreshFirstBatchOrdering() {
        val ids = (0..5).map { point(1_000_000L + it * 3_000L) }
        repository.markMatched(listOf(ids[0], ids[2], ids[3], ids[5]))
        repository.deferMatching(listOf(ids[1]), 100L)

        val fresh = repository.loadMatchingWindow(limit = 4, nowMillis = 200L)
        assertEquals(setOf(ids[4]), fresh.markableIds)

        val retryCap = repository.oldestEligibleRetryId(nowMillis = 200L)
        assertEquals(ids[1], retryCap)
        val retry = repository.loadMatchingWindow(limit = 4, nowMillis = 200L, maxPendingId = retryCap)
        assertEquals(setOf(ids[1]), retry.markableIds)
        assertEquals(listOf(ids[0], ids[1], ids[2]), retry.points.map { it.id })
    }

    @Test fun reverseDriveUpdatesTheSameSegmentAndKeepsFirstUnlockTime() {
        repository.upsertRoads(listOf(MatchedRoad("Main St", "[[-74,40],[-74.001,40]]", 100, 200, 1.0)))
        repository.upsertRoads(listOf(MatchedRoad("Main St", "[[-74.001,40],[-74,40]]", 180_300, 180_400, 1.0)))
        val road = repository.getRoadsInBounds(41.0, -73.0, 39.0, -75.0).single()
        assertEquals("52f290b759a36294152d6bac", road.segmentId)
        assertEquals(100L, road.firstUnlockedAt)
        assertEquals(180_400L, road.lastDrivenAt)
        assertEquals(2, road.timesDriven)
        assertFalse(road.timesDrivenExact)
    }

    @Test fun sameNamedRoadsWithTheSameEndpointsDoNotOverwriteDifferentGeometry() {
        repository.upsertRoads(listOf(
            MatchedRoad("Loop Road", "[[-74,40],[-73.9995,40.0005],[-74.001,40]]", 100, 200, 1.0),
            MatchedRoad("Loop Road", "[[-74,40],[-74.0005,39.9995],[-74.001,40]]", 300, 400, 1.0)
        ))
        val roads = repository.getRoadsInBounds(41.0, -73.0, 39.0, -75.0)
        assertEquals(2, roads.size)
        assertEquals(2, roads.map { it.segmentId }.toSet().size)
        assertEquals(2, roads.map { it.geometryJson }.toSet().size)
        assertEquals(1L, repository.getSummary().roadsUnlockedCount)
    }

    @Test fun overlappingMatcherRetryDoesNotInflateTimesDriven() {
        val geometry = "[[-74,40],[-74.001,40]]"
        repository.upsertRoads(listOf(MatchedRoad("Main St", geometry, 100, 200, 1.0)))
        repository.upsertRoads(listOf(MatchedRoad("Main St", geometry, 150, 250, 1.0)))
        assertEquals(1, repository.getRoadsInBounds(41.0, -73.0, 39.0, -75.0).single().timesDriven)

        repository.upsertRoads(listOf(MatchedRoad("Main St", geometry, 180_300, 180_400, 1.0)))
        assertEquals(2, repository.getRoadsInBounds(41.0, -73.0, 39.0, -75.0).single().timesDriven)
    }

    @Test fun adjacentMatcherBatchesAndTrafficLightPauseRemainOneVisit() {
        val geometry = "[[-74,40],[-74.001,40]]"
        repository.upsertRoads(listOf(MatchedRoad("Main St", geometry, 1_000_000, 1_009_000, 0.95)))
        repository.upsertRoads(listOf(MatchedRoad("Main St", geometry, 1_020_000, 1_030_000, 0.95)))
        repository.upsertRoads(listOf(MatchedRoad("Main St", geometry, 1_100_000, 1_105_000, 0.95)))
        val road = repository.getRoadsInBounds(41.0, -73.0, 39.0, -75.0).single()
        assertEquals(1, road.timesDriven)
        assertFalse(road.timesDrivenExact)
    }

    @Test fun leavingRoadThenQuicklyReturningCountsSeparatePasses() {
        val geometry = "[[-74,40],[-74.001,40]]"
        repository.upsertRoads(listOf(MatchedRoad("Main St", geometry, 1_000_000, 1_010_000, 0.95)))
        // A precise raw sample over 100 meters from this segment proves departure.
        point(1_045_000, lat = 40.004, lon = -74.003)
        repository.upsertRoads(listOf(MatchedRoad("Main St", geometry, 1_085_000, 1_095_000, 0.95)))
        assertEquals(2, repository.getRoadsInBounds(41.0, -73.0, 39.0, -75.0).single().timesDriven)
    }

    @Test fun offRoadEvidenceAfterFirst120FixesStillSeparatesRoadVisits() {
        val geometry = "[[-74,40],[-74.001,40]]"
        repository.upsertRoads(listOf(MatchedRoad(
            "Long sample visit", geometry, 1_000_000L, 1_010_000L, 0.99
        )))
        // Older code looked at only the first 120 accurate fixes in the
        // two-minute gap. Here all of those are on the road, while a later
        // precise fix proves that the driver left before returning.
        repeat(130) { index ->
            point(1_011_000L + index * 500L, lat = 40.0, lon = -74.0005)
        }
        point(1_090_000L, lat = 40.004, lon = -74.003)
        repository.upsertRoads(listOf(MatchedRoad(
            "Long sample visit", geometry, 1_105_000L, 1_115_000L, 0.99
        )))
        val road = repository.getRoadsInBounds(41.0, -73.0, 39.0, -75.0).single()
        assertEquals("A later GPS departure must not be hidden by a fixed scan cap",
            2, road.timesDriven)
        assertEquals(2, repository.estimatedRoadVisitCount(road))
    }

    @Test fun longOutOfOrderMatchedWindowReconnectsEarlierLegacyFragments() {
        val geometry = "[[-74,40],[-74.001,40]]"
        repository.upsertRoads(listOf(MatchedRoad("Main St", geometry, 1_000_000, 1_010_000, 0.99)))
        val road = repository.getRoadsInBounds(41.0, -73.0, 39.0, -75.0).single()

        // Old releases stored this additional fragment separately because only
        // strictly overlapping windows were joined. It is 90 seconds after the
        // first fragment, but outside the new late batch's initial 120s query.
        val db = repository.readableDatabase()
        db.execSQL(
            "INSERT INTO road_visits(segment_id,started_at,ended_at) VALUES (?,?,?)",
            arrayOf(road.segmentId, 1_100_000L, 1_500_000L)
        )
        db.execSQL("UPDATE roads SET drive_count = 2 WHERE segment_id = ?",
            arrayOf(road.segmentId))

        repository.upsertRoads(listOf(MatchedRoad("Main St", geometry, 1_490_000, 1_495_000, 0.99)))
        val updated = repository.getRoadsInBounds(41.0, -73.0, 39.0, -75.0).single()
        assertEquals(1, updated.timesDriven)
        db.rawQuery("SELECT started_at,ended_at FROM road_visits WHERE segment_id = ?",
            arrayOf(road.segmentId)).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1_000_000L, cursor.getLong(0))
            assertEquals(1_500_000L, cursor.getLong(1))
            assertFalse(cursor.moveToNext())
        }
    }

    @Test fun transitiveMergeNeverErasesGpsConfirmedReturn() {
        val geometry = "[[-74,40],[-74.001,40]]"
        repository.upsertRoads(listOf(MatchedRoad("Main St", geometry, 1_000_000, 1_010_000, 0.99)))
        val road = repository.getRoadsInBounds(41.0, -73.0, 39.0, -75.0).single()
        val db = repository.readableDatabase()
        db.execSQL(
            "INSERT INTO road_visits(segment_id,started_at,ended_at) VALUES (?,?,?)",
            arrayOf(road.segmentId, 1_100_000L, 1_500_000L)
        )
        // There was a real departure from the road inside that 90-second gap.
        point(1_060_000L, lat = 40.004, lon = -74.003)
        repository.upsertRoads(listOf(MatchedRoad("Main St", geometry, 1_490_000, 1_495_000, 0.99)))
        assertEquals(2, repository.getRoadsInBounds(41.0, -73.0, 39.0, -75.0).single().timesDriven)
    }

    @Test fun tappingHistoricRoadEstimatesMergedPassesWithoutModifyingSavedVisits() {
        val geometry = "[[-74,40],[-74.001,40]]"
        repository.upsertRoads(listOf(MatchedRoad("Legacy St", geometry, 1_000_000, 1_010_000, 0.99)))
        val road = repository.getRoadsInBounds(41.0, -73.0, 39.0, -75.0).single()
        val db = repository.readableDatabase()
        db.execSQL("INSERT INTO road_visits(segment_id, started_at, ended_at) VALUES (?,?,?)",
            arrayOf(road.segmentId, 1_080_000L, 1_095_000L))
        db.execSQL("UPDATE roads SET drive_count = 2 WHERE segment_id = ?", arrayOf(road.segmentId))

        val stored = repository.getRoadsInBounds(41.0, -73.0, 39.0, -75.0).single()
        assertEquals(2, stored.timesDriven)
        assertEquals(1, repository.estimatedRoadVisitCount(stored))
        assertEquals("Read-only recount must preserve original intervals", 2L,
            db.rawQuery("SELECT COUNT(*) FROM road_visits WHERE segment_id = ?",
                arrayOf(road.segmentId)).use { cursor ->
                assertTrue(cursor.moveToFirst())
                cursor.getLong(0)
            })
    }

    @Test fun historicRecountRetainsGpsConfirmedSeparateTrips() {
        val geometry = "[[-74,40],[-74.001,40]]"
        repository.upsertRoads(listOf(MatchedRoad("Legacy St", geometry, 1_000_000, 1_010_000, 0.99)))
        val road = repository.getRoadsInBounds(41.0, -73.0, 39.0, -75.0).single()
        val db = repository.readableDatabase()
        db.execSQL("INSERT INTO road_visits(segment_id, started_at, ended_at) VALUES (?,?,?)",
            arrayOf(road.segmentId, 1_080_000L, 1_095_000L))
        point(1_045_000L, lat = 40.004, lon = -74.003)
        assertEquals(2, repository.estimatedRoadVisitCount(road))
    }

    @Test fun unexpectedProcessDeathRestoresTrailingCompressedGpsOnce() {
        fun fix(lon: Double, timestamp: Long) = Location("gps").apply {
            latitude = 40.0; longitude = lon; accuracy = 3f
            speed = 8f; bearing = 90f; time = timestamp
        }
        val first = fix(-74.0, 1_000_000L)
        val middle = fix(-73.9999, 1_003_000L)
        val terminal = fix(-73.9998, 1_006_000L)
        repository.insertLocation(first)
        repository.recordSkippedDrivingFix(middle)
        repository.recordSkippedDrivingFix(terminal)
        assertTrue(repository.recoverTrailingSkippedDrivingFix())
        assertFalse(repository.recoverTrailingSkippedDrivingFix())
        assertEquals(2L, repository.getSummary().trackPointCount)
        val expected = TravelDistance.between(first.latitude, first.longitude, first.time,
            middle.latitude, middle.longitude, middle.time) +
            TravelDistance.between(middle.latitude, middle.longitude, middle.time,
                terminal.latitude, terminal.longitude, terminal.time)
        assertEquals(expected, repository.getSummary().distanceMeters, 0.5)
    }

    @Test fun flushedCompressedEndpointRestoresMileageWithoutDuplicateDistance() {
        fun fix(lon: Double, time: Long) = Location("gps").apply {
            latitude = 40.0; longitude = lon; accuracy = 3f; speed = 8f; bearing = 90f
            this.time = time
        }
        val a = fix(-74.0, 1_000_000L)
        val skipped = fix(-73.9999, 1_003_000L)
        repository.insertLocation(a)
        repository.recordSkippedDrivingFix(skipped)
        repository.insertLocation(skipped)
        assertEquals(2L, repository.getSummary().trackPointCount)
        assertEquals(TravelDistance.between(a.latitude, a.longitude, a.time,
            skipped.latitude, skipped.longitude, skipped.time), repository.getSummary().distanceMeters, 0.5)
    }

    @Test fun compressedGpsIsRetainedLocallyAndRestoresMeasuredTripDistance() {
        fun fix(lat: Double, lon: Double, time: Long) = Location("gps").apply {
            latitude = lat; longitude = lon; accuracy = 3f; speed = 8f; bearing = 90f
            this.time = time
        }
        val a = fix(40.0, -74.0, 1_000_000L)
        val bend = fix(40.00010, -73.9999, 1_003_000L)
        val b = fix(40.0, -73.9998, 1_006_000L)
        repository.insertLocation(a)
        repository.recordSkippedDrivingFix(bend)
        repository.insertLocation(b)
        val db = repository.readableDatabase()
        assertEquals(1L, db.rawQuery("SELECT COUNT(*) FROM skipped_driving_fixes", null).use {
            it.moveToFirst(); it.getLong(0)
        })
        assertEquals(2L, repository.getSummary().trackPointCount)
        val expected = TravelDistance.between(a.latitude, a.longitude, a.time, bend.latitude, bend.longitude, bend.time) +
            TravelDistance.between(bend.latitude, bend.longitude, bend.time, b.latitude, b.longitude, b.time)
        assertEquals(expected, repository.getSummary().distanceMeters, 0.5)
        assertTrue(expected > TravelDistance.between(a.latitude, a.longitude, a.time, b.latitude, b.longitude, b.time) + 5.0)
    }

    @Test fun curvedRoadGroupingPreservesConnectedAndDisconnectedDecisions() {
        fun road(id: String, vertices: String) = RoadGrouping.Road(
            segmentId = id, name = "Example Road", geometryJson = vertices,
            minLatitude = 39.9999, maxLatitude = 40.0002,
            minLongitude = -74.001, maxLongitude = -73.997
        )
        val original = road("a", "[[-74.001,40.0],[-74.0005,40.0001],[-74.0,40.0]]")
        val connected = road("b", "[[-73.99999,40.0],[-73.9995,40.0001]]")
        val disconnected = road("c", "[[-73.997,40.0002],[-73.9965,40.0002]]")
        assertTrue(RoadGrouping.connected(original, connected))
        assertFalse(RoadGrouping.connected(original, disconnected))
    }

    @Test fun roadHitTestingReturnsOnlyNearbySavedRoads() {
        repository.upsertRoads(listOf(
            MatchedRoad("Tap Me", "[[-74.001,40],[-74.000,40]]", 100, 200, 1.0)
        ))
        val road = repository.findRoadNear(40.0, -74.0005, 30.0)
        assertNotNull(road)
        assertEquals("Tap Me", road!!.name)
        assertEquals(85.0, repository.roadLengthMeters(road), 5.0)
        assertNull(repository.findRoadNear(40.01, -74.0005, 30.0))
    }

    @Test fun localRoadDistanceReusesVerticesAcrossValidMultiSegmentPolyline() {
        repository.upsertRoads(listOf(
            MatchedRoad("Curving road", "[[-74,40],[-73.9999,40],[-73.9998,40.0001],[-73.9997,40.0001]]",
                1_000_000L, 1_010_000L, 0.99)
        ))
        assertNotNull(repository.findUnambiguousPreviouslyDrivenRoadNear(40.0, -73.99995))
        assertNotNull(repository.findUnambiguousPreviouslyDrivenRoadNear(40.0001, -73.99975))
        assertNull(repository.findUnambiguousPreviouslyDrivenRoadNear(40.0, -73.99975))
    }

    @Test fun ambiguousParallelSavedRoadsCannotResolveGpsLocally() {
        repository.upsertRoads(listOf(
            MatchedRoad("Original", "[[-74,40],[-73.999,40]]", 1_000_000L, 1_010_000L, 0.99),
            MatchedRoad("Parallel", "[[-74,40.00006],[-73.999,40.00006]]",
                1_000_000L, 1_010_000L, 0.99)
        ))
        assertNull(repository.findUnambiguousPreviouslyDrivenRoadNear(40.0, -73.9995))
    }

    @Test fun preciseRepeatDriveIsMatchedOnDeviceWithoutUnlockingNewRoads() {
        repository.upsertRoads(listOf(MatchedRoad(
            "Previously driven", "[[-74,40],[-73.999,40]]", 1_000_000L, 1_010_000L, 0.99)))
        val ids = (0 until 6).map { index ->
            repository.insertLocation(Location("gps").apply {
                latitude = 40.0
                longitude = -74.0 + index * 0.0001
                accuracy = 3f
                speed = 8f
                bearing = 90f
                time = 2_000_000L + index * 6_000L
            })
        }
        val window = repository.loadMatchingWindow(100)
        val reused = com.roadconquest.app.matching.KnownRoadReuse.find(
            repository, window.points, window.markableIds)
        assertEquals(6, repository.completeKnownRoadVisits(reused))
        assertEquals(ids, reused.flatMap { it.pointIds })
        assertEquals(1L, repository.getSummary().roadSegmentCount)
        assertTrue(repository.loadMatchingWindow(100).points.isEmpty())
        val known = repository.getRoadsInBounds(41.0, -73.0, 39.0, -75.0).single()
        assertEquals(2_030_000L, known.lastDrivenAt)
        assertTrue(known.timesDriven >= 2)
    }

    @Test fun backwardWallClockChangeBreaksTheMatchingWindow() {
        point(2_000_000)
        point(2_003_000)
        val newId = point(1_000_000)
        assertEquals(listOf(newId), repository.loadMatchingWindow().points.map { it.id })
    }

    @Test(expected = IllegalArgumentException::class)
    fun malformedRoadCannotSilentlyReportSuccessfulPersistence() {
        repository.upsertRoads(listOf(MatchedRoad("bad", "[[181,40],[182,40]]", 100, 200, 1.0)))
    }

    @Test fun completeMatchRollsBackRoadsWhenPointUpdateFails() {
        val id = point(1_000_000)
        val db = repository.readableDatabase()
        db.execSQL("CREATE TRIGGER fail_resolution BEFORE UPDATE OF matched ON track_points BEGIN SELECT RAISE(ABORT, 'injected update failure'); END")
        try {
            try {
                repository.completeMatch(listOf(MatchedRoad("Main", "[[-74,40],[-74.001,40]]", 100, 200, 1.0)), listOf(id))
                fail("Expected the injected update failure")
            } catch (_: android.database.sqlite.SQLiteException) {
                assertEquals(0L, repository.getSummary().roadSegmentCount)
                assertEquals(setOf(id), repository.loadMatchingWindow().markableIds)
            }
        } finally {
            db.execSQL("DROP TRIGGER fail_resolution")
        }
    }

    @Test fun validCompleteMatchCommitsRoadAndResolutionTogether() {
        val id = point(1_000_000)
        repository.completeMatch(listOf(MatchedRoad("Main", "[[-74,40],[-74.001,40]]", 100, 200, 1.0)), listOf(id))
        assertEquals(1L, repository.getSummary().roadSegmentCount)
        assertTrue(repository.loadMatchingWindow().points.isEmpty())
    }

    @Test fun deferredDeadlineIgnoresResolvedAndAlreadyEligiblePoints() {
        val ids = (0..2).map { point(1_000_000L + it * 3_000L) }
        repository.deferMatching(listOf(ids[0]), 5_000)
        repository.deferMatching(listOf(ids[1]), 10_000)
        repository.deferMatching(listOf(ids[2]), 15_000)
        assertEquals(10_000L, repository.nextDeferredMatchAttempt(6_000))
        repository.markMatched(listOf(ids[1]))
        assertEquals(15_000L, repository.nextDeferredMatchAttempt(6_000))
        assertNull(repository.nextDeferredMatchAttempt(16_000))
    }

    @Test fun resolutionHandlesMoreThanOneSqlParameterChunk() {
        val ids = (0..450).map { point(1_000_000L + it * 3_000L) }
        repository.markMatched(ids)
        assertTrue(repository.loadMatchingWindow().points.isEmpty())
        assertEquals(451L, repository.getSummary().trackPointCount)
    }

    @Test fun nonFiniteOptionalSensorFieldsDoNotTurnIntoNullDatabaseValues() {
        repository.insertLocation(Location("gps").apply {
            latitude = 40.0; longitude = -74.0; accuracy = 5f; time = 1_000_000
            // Newer Android setters reject NaN themselves; older versions allow it.
            try { speed = Float.NaN } catch (_: IllegalArgumentException) { }
            try { bearing = Float.NaN } catch (_: IllegalArgumentException) { }
        })
        repository.readableDatabase().rawQuery("SELECT speed_mps,bearing_deg FROM track_points", null).use {
            assertTrue(it.moveToFirst())
            assertEquals(0f, it.getFloat(0), 0f)
            assertEquals(0f, it.getFloat(1), 0f)
        }
    }
    @Test fun poorSpeedAccuracyIsNotPersistedAsTrustedMatcherSpeed() {
        repository.insertLocation(Location("gps").apply {
            latitude = 40.0
            longitude = -74.0
            accuracy = 5f
            speed = 9f
            speedAccuracyMetersPerSecond = 10f
            time = 1_000_000L
        })
        repository.insertLocation(Location("gps").apply {
            latitude = 40.0002
            longitude = -74.0
            accuracy = 5f
            speed = 9f
            speedAccuracyMetersPerSecond = 1f
            time = 1_003_000L
        })

        repository.readableDatabase().rawQuery(
            "SELECT speed_mps FROM track_points ORDER BY id ASC",
            null
        ).use {
            assertTrue(it.moveToFirst())
            assertEquals(0f, it.getFloat(0), 0f)
            assertTrue(it.moveToNext())
            assertEquals(9f, it.getFloat(0), 0f)
        }
    }

    @Test fun boundedRoadQueryReportsWhetherViewportCoverageIsComplete() {
        repository.upsertRoads(listOf(
            MatchedRoad("One", "[[-74.0000,40.0000],[-74.0001,40.0000]]", 100, 200, 1.0),
            MatchedRoad("Two", "[[-74.0000,40.0001],[-74.0001,40.0001]]", 200, 300, 1.0),
            MatchedRoad("Three", "[[-74.0000,40.0002],[-74.0001,40.0002]]", 300, 400, 1.0)
        ))
        val truncated = repository.getRoadsInBoundsResult(40.01, -73.99, 39.99, -74.01, limit = 2)
        assertEquals(2, truncated.roads.size)
        assertFalse(truncated.complete)

        val complete = repository.getRoadsInBoundsResult(40.01, -73.99, 39.99, -74.01, limit = 3)
        assertEquals(3, complete.roads.size)
        assertTrue(complete.complete)

        val uncapped = repository.getRoadsInBoundsResult(
            40.01, -73.99, 39.99, -74.01, limit = Int.MAX_VALUE
        )
        assertEquals(3, uncapped.roads.size)
        assertTrue(uncapped.complete)
    }

    @Test fun pendingLineCrossingViewportRendersEvenWhenBothEndpointsAreOutside() {
        point(1_000_000L, lat = 40.0, lon = -74.01)
        point(1_030_000L, lat = 40.0, lon = -73.99)
        val pending = repository.getPendingRouteInBounds(
            north = 40.0005,
            east = -73.9999,
            south = 39.9995,
            west = -74.0001
        )
        assertEquals(1, pending.size)
        assertEquals(2, org.json.JSONArray(pending.single().geometryJson).length())
    }

}
