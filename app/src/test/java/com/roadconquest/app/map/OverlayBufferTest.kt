package com.roadconquest.app.map

import com.roadconquest.app.data.RoadRecord
import java.lang.management.ManagementFactory
import java.nio.ByteBuffer
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37], manifest = Config.NONE)
class OverlayBufferTest {
    private fun road(id: String, coordinates: List<Pair<Double, Double>>) = RoadRecord(
        id, id, coordinates.joinToString(prefix = "[", postfix = "]") { (lat, lon) -> "[$lon,$lat]" },
        1000L, 2000L, -90.0, 90.0, -180.0, 180.0
    )

    private fun disconnected(count: Int, points: Int) = (0 until count).map { index ->
        road("separate-$index", (0 until points).map { point ->
            (40.0 + index * 0.002) to (-74.0 + point * 0.00001)
        })
    }

    private fun connected(count: Int, points: Int) = (0 until count).map { index ->
        road("connected-$index", (0 until points).map { point ->
            40.0 to (-74.0 + (index * (points - 1) + point) * 0.00001)
        })
    }

    private fun scenes(seed: Int): List<RoadRecord> {
        val lat = 10.0 + seed * 0.1
        val lon = -100.0 + seed * 0.1
        fun r(id: String, vararg points: Pair<Double, Double>) = road(id, points.toList())
        return when (seed % 6) {
            0 -> disconnected(seed % 9 + 1, seed % 17 + 2)
            1 -> connected(seed % 7 + 2, seed % 13 + 2)
            2 -> listOf(r("date-line", lat to 179.99, (lat + .001) to -179.99,
                (lat + .002) to 179.98, (lat + .003) to -179.98))
            3 -> listOf(r("west", lat to (lon - .001), lat to lon),
                r("east", lat to lon, lat to (lon + .001)),
                r("north", lat to lon, (lat + .001) to lon))
            4 -> listOf(r("loop", lat to lon, (lat + .001) to lon,
                (lat + .001) to (lon + .001), lat to lon))
            else -> listOf(r("duplicates", lat to lon, lat to lon, lat to (lon + .001), lat to (lon + .001)))
        }
    }

    private fun namedFragment(name: String, first: Long, last: Long, vararg coords: Pair<Double, Double>) =
        RoadRecord(name + first, name,
            coords.joinToString(prefix = "[", postfix = "]") { (lat, lon) -> "[$lon,$lat]" },
            first, last, -90.0, 90.0, -180.0, 180.0)

    @Test fun shortStraightSameRoadGapGetsVisibleConnectorWithoutNewStoredRoad() {
        val west = namedFragment("Main Street", 1_000L, 4_000L,
            40.0 to -74.0005, 40.0 to -74.00025)
        val east = namedFragment("Main Street", 6_000L, 8_000L,
            40.0 to -74.00010, 40.0 to -73.99985)
        val result = OverlayRoads.prepare(listOf(west, east))
        assertEquals("Straight, 13 m seam must not leave two blue fragments", 1, result.starts.size - 1)
        assertTrue("Display-only midpoint must be on the road",
            result.coordinates.indices.step(2).any {
                kotlin.math.abs(result.coordinates[it] - 40.0) < 1e-8 &&
                    result.coordinates[it + 1] in -74.00025..-74.00010
            })
    }

    @Test fun drivingStraightThroughNamedIntersectionDoesNotLoseTheBlueRoad() {
        val before = namedFragment("North Road", 1_000L, 4_000L,
            40.0 to -74.0005, 40.0 to -74.00025)
        val after = namedFragment("South Road", 6_000L, 8_000L,
            40.0 to -74.00010, 40.0 to -73.99985)
        fun display(gps: Boolean) = OverlayRoads.prepare(listOf(before, after),
            hasRecordedDriving = { _, _, _, _, _, _ -> gps },
            requireMatchedConnectorGeometry = true)
        assertEquals("Different road names alone cannot invent a connection",
            2, display(false).starts.size - 1)
        val connected = display(true)
        assertEquals("GPS-proven straight travel through the junction is continuous",
            1, connected.starts.size - 1)
        assertTrue("The join must stay on the existing matched centerline",
            connected.coordinates.indices.step(2).all {
                kotlin.math.abs(connected.coordinates[it] - 40.0) < 1e-8
            })
    }

    @Test fun gpsConfirmedStraightTravelWinsOverShallowIntersectionRayCrossing() {
        // These matched centerlines are only 9 degrees apart. Their tangent
        // rays intersect, but the recorded travel went straight through the
        // junction; the map must not invent a turning connector instead.
        val west = namedFragment("Road Before", 1_000L, 4_000L,
            40.0 to -74.00050, 40.0 to -74.00025)
        val east = namedFragment("Road After", 6_000L, 8_000L,
            40.00001 to -74.00010, 40.00004 to -73.99985)
        val overlay = OverlayRoads.prepare(listOf(west, east),
            hasRecordedDriving = { _, _, _, _, _, _ -> true },
            hasRecordedTurn = { _, _, _, _, _, _, _, _ -> false },
            requireMatchedConnectorGeometry = true)
        assertEquals("Verified near-straight intersection is still one blue line",
            1, overlay.starts.size - 1)
    }

    @Test fun passingAnIntersectionNeverConnectsToPerpendicularOrOffsetRoads() {
        val before = namedFragment("Main Road", 1_000L, 4_000L,
            40.0 to -74.0005, 40.0 to -74.00025)
        val sideStreet = namedFragment("Side Road", 6_000L, 8_000L,
            40.0 to -74.00010, 40.00025 to -74.00010)
        // Only ~10.6 degrees off the approach but 4.4 m sideways: the
        // heading check alone is not enough to exclude this parallel road.
        val offsetParallel = namedFragment("Other Carriageway", 6_000L, 8_000L,
            40.00004 to -73.99997, 40.00004 to -73.99973)
        for (wrongExit in listOf(sideStreet, offsetParallel)) {
            val result = OverlayRoads.prepare(listOf(before, wrongExit),
                hasRecordedDriving = { _, _, _, _, _, _ -> true },
                hasRecordedTurn = { _, _, _, _, _, _, _, _ -> false },
                requireMatchedConnectorGeometry = true)
            assertEquals("Proximity must not connect the wrong intersection branch: ${wrongExit.name}",
                2, result.starts.size - 1)
        }
    }

    @Test fun productionStraightGapNeedsRecordedDrivingProof() {
        val west = namedFragment("Main Street", 1_000L, 4_000L,
            40.0 to -74.0005, 40.0 to -74.00025)
        val east = namedFragment("Main Street", 6_000L, 8_000L,
            40.0 to -74.00010, 40.0 to -73.99985)
        var inspected = false
        val notDriven = OverlayRoads.prepare(listOf(west, east),
            hasRecordedDriving = { _, _, _, _, from, to ->
                inspected = true
                // The first-unlock pass and historical last-visit pass can
                // both ask about this same geometric seam.
                assertTrue(from == 1_000L || from == 4_000L)
                assertTrue(to == 6_000L || to == 8_000L)
                assertTrue(to > from)
                false
            })
        assertTrue("Eligible straight gap must ask for actual driving evidence", inspected)
        assertEquals("Roads without recorded movement remain separate", 2, notDriven.starts.size - 1)
        val driven = OverlayRoads.prepare(listOf(west, east),
            hasRecordedDriving = { _, _, _, _, _, _ -> true })
        assertEquals(1, driven.starts.size - 1)
    }

    @Test fun realRepositoryGpsEvidenceControlsVisibleStraightRepair() {
        val context: android.content.Context = RuntimeEnvironment.getApplication()
        com.roadconquest.app.data.TrackingRepository(context).clearHistory()
        val repo = com.roadconquest.app.data.TrackingRepository(context)
        val west = namedFragment("Main Street", 1_000L, 4_000L,
            40.0 to -74.0005, 40.0 to -74.00025)
        val east = namedFragment("Main Street", 6_000L, 8_000L,
            40.0 to -74.00010, 40.0 to -73.99985)
        fun display() = OverlayRoads.prepare(listOf(west, east),
            hasRecordedDriving = repo::hasRecordedDrivingAcrossGap)
        assertEquals(2, display().starts.size - 1)
        listOf(4_000L to -74.00025, 6_000L to -74.00010).forEach { (time, lon) ->
            repo.insertLocation(android.location.Location("gps").apply {
                latitude = 40.0; longitude = lon; this.time = time
                accuracy = 4f; speed = 5f
            })
        }
        assertEquals("Real GPS crossing, not just road names, should connect the gap",
            1, display().starts.size - 1)
    }

    @Test fun productionMapRepairsOnlyGpsConfirmedSameRoadStraightSeam() {
        val context: android.content.Context = RuntimeEnvironment.getApplication()
        com.roadconquest.app.data.TrackingRepository(context).clearHistory()
        val repo = com.roadconquest.app.data.TrackingRepository(context)
        val west = namedFragment("Main Street", 1_000L, 4_000L,
            40.0 to -74.0005, 40.0 to -74.00025)
        val east = namedFragment("Main Street", 6_000L, 8_000L,
            40.0 to -74.00010, 40.0 to -73.99985)
        fun visible() = OverlayRoads.prepare(listOf(west, east),
            hasRecordedDriving = repo::hasRecordedDrivingAcrossGap,
            requireMatchedConnectorGeometry = true)
        assertEquals(2, visible().starts.size - 1)
        for ((time, lon) in listOf(4_000L to -74.00025, 6_000L to -74.00010)) {
            repo.insertLocation(android.location.Location("gps").apply {
                latitude = 40.0; longitude = lon; this.time = time
                accuracy = 4f; speed = 5f
            })
        }
        assertEquals("A genuine straight crossing must connect in the production map",
            1, visible().starts.size - 1)
        assertEquals("No new road record is credited for an inferred display seam",
            0L, repo.getSummary().roadSegmentCount)
    }

    @Test fun productionMapRepairsShortConfirmedTurnWithoutDonorGeometry() {
        val context: android.content.Context = RuntimeEnvironment.getApplication()
        com.roadconquest.app.data.TrackingRepository(context).clearHistory()
        val repo = com.roadconquest.app.data.TrackingRepository(context)
        val approach = namedFragment("Approach", 1_000L, 4_000L,
            39.9995 to -74.0, 39.99986 to -74.0)
        val exit = namedFragment("Exit", 10_000L, 14_000L,
            40.0 to -73.99980, 40.0 to -73.99950)
        fun display() = OverlayRoads.prepare(listOf(approach, exit),
            hasRecordedTurn = repo::hasRecordedDrivingThroughTurn,
            requireMatchedConnectorGeometry = true)
        assertEquals("Without the turn's GPS evidence, keep distinct roads", 2,
            display().starts.size - 1)
        for ((time, point) in listOf(
            4_000L to (39.99986 to -74.0),
            7_000L to (40.0 to -74.0),
            10_000L to (40.0 to -73.99980)
        )) {
            repo.insertLocation(android.location.Location("gps").apply {
                latitude = point.first; longitude = point.second; this.time = time
                accuracy = 4f; speed = 8f
            })
        }
        val result = display()
        assertEquals("Short, GPS-proven intersection must have connected blue geometry",
            1, result.starts.size - 1)
        assertTrue("Correct centerline corner should be visible",
            result.coordinates.indices.step(2).any {
                kotlin.math.abs(result.coordinates[it] - 40.0) < 1e-6 &&
                    kotlin.math.abs(result.coordinates[it + 1] + 74.0) < 1e-6
            })
        assertEquals(0L, repo.getSummary().roadSegmentCount)
    }

    @Test fun actualGpsThroughIntersectionRepairsLongerMissingTurnOnlyVisually() {
        val app: android.content.Context = RuntimeEnvironment.getApplication()
        com.roadconquest.app.data.TrackingRepository(app).clearHistory()
        val repo = com.roadconquest.app.data.TrackingRepository(app)
        // The road fragments stop 44-45 m short of a perpendicular intersection.
        // The old 30 m chord repair cannot connect this real 64 m gap.
        val approach = namedFragment("Approach", 1_000L, 4_000L,
            39.9990 to -74.0, 39.9996 to -74.0)
        val exit = namedFragment("Exit", 12_000L, 14_000L,
            40.0 to -73.99947, 40.0 to -73.9989)
        fun visible() = OverlayRoads.prepare(listOf(approach, exit),
            hasRecordedTurn = repo::hasRecordedDrivingThroughTurn,
            requireMatchedConnectorGeometry = true)
        assertEquals("Without a GPS-confirmed turn do not join intersection",
            2, visible().starts.size - 1)
        listOf(
            Triple(4_000L, 39.9996, -74.0),
            Triple(7_000L, 40.0, -74.0),
            Triple(10_000L, 40.0, -73.99947)
        ).forEach { (time, lat, lon) ->
            repo.insertLocation(android.location.Location("gps").apply {
                latitude = lat; longitude = lon; this.time = time
                accuracy = 4f; speed = 8f
            })
        }
        val result = visible()
        assertEquals("Recorded route turns across the junction, display only",
            1, result.starts.size - 1)
        assertTrue("The correct road intersection is part of the visible line",
            result.coordinates.indices.step(2).any {
                kotlin.math.abs(result.coordinates[it] - 40.0) < 0.000001 &&
                    kotlin.math.abs(result.coordinates[it + 1] - -74.0) < 0.000001
            })
        assertEquals("Visual repair adds no road unlocks", 0L,
            repo.getSummary().roadSegmentCount)
    }

    @Test fun intersectionRepairRejectsWrongDirectionEvenWithThreeNearbyFixes() {
        val app: android.content.Context = RuntimeEnvironment.getApplication()
        com.roadconquest.app.data.TrackingRepository(app).clearHistory()
        val repo = com.roadconquest.app.data.TrackingRepository(app)
        val approach = namedFragment("Approach", 1_000L, 4_000L,
            39.9990 to -74.0, 39.9996 to -74.0)
        val exit = namedFragment("Exit", 12_000L, 14_000L,
            40.0 to -73.99947, 40.0 to -73.9989)
        // Positions are near the junction but chronological direction is reversed.
        listOf(
            Triple(4_000L, 40.0, -73.99947),
            Triple(7_000L, 40.0, -74.0),
            Triple(10_000L, 39.9996, -74.0)
        ).forEach { (time, lat, lon) ->
            repo.insertLocation(android.location.Location("gps").apply {
                latitude = lat; longitude = lon; this.time = time
                accuracy = 4f; speed = 8f
            })
        }
        val result = OverlayRoads.prepare(listOf(approach, exit),
            hasRecordedTurn = repo::hasRecordedDrivingThroughTurn,
            requireMatchedConnectorGeometry = true)
        assertEquals(2, result.starts.size - 1)
    }

    @Test fun straightSeamRequiresSameNameGoodGeometryAndRecentChronology() {
        val west = namedFragment("Main Street", 1_000L, 4_000L,
            40.0 to -74.0005, 40.0 to -74.00025)
        val correct = namedFragment("Main Street", 6_000L, 8_000L,
            40.0 to -74.00010, 40.0 to -73.99985)
        val wrongName = correct.copy(name = "Other Road")
        val distant = correct.copy(geometryJson = "[[-73.9995,40],[-73.9992,40]]")
        val late = correct.copy(firstUnlockedAt = 30_000L, lastDrivenAt = 32_000L)
        val wrongDirection = correct.copy(geometryJson = "[[-74.00010,40],[-74.00030,40]]")
        for (candidate in listOf(wrongName, distant, late, wrongDirection)) {
            assertEquals("Unsafe connector must not be drawn: $candidate",
                2, OverlayRoads.prepare(listOf(west, candidate)).starts.size - 1)
        }
    }

    @Test fun baselineSceneFixtureCapturesEveryCoordinateBitAndChainStart() {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = ByteBuffer.allocate(8)
        for (seed in 0 until 240) {
            val prepared = OverlayRoads.prepare(scenes(seed))
            for (size in listOf(prepared.coordinates.size, prepared.starts.size)) {
                bytes.clear(); bytes.putLong(size.toLong()); digest.update(bytes.array())
            }
            for (coordinate in prepared.coordinates) {
                bytes.clear(); bytes.putLong(coordinate.toRawBits()); digest.update(bytes.array())
            }
            for (start in prepared.starts) {
                bytes.clear(); bytes.putLong(start.toLong()); digest.update(bytes.array())
            }
            assertEquals(0, prepared.starts.first())
            assertEquals(prepared.coordinates.size, prepared.starts.last())
        }
        val signature = digest.digest().joinToString("") { "%02x".format(it) }
        println("OVERLAY_SCENES sdk=${RuntimeEnvironment.getApiLevel()} cases=240 sha256=$signature")
    }

    @Test fun separatePreparationsNeverShareMutableOutputArrays() {
        for (seed in 0 until 24) {
            val roads = scenes(seed)
            val first = OverlayRoads.prepare(roads)
            val second = OverlayRoads.prepare(roads)
            assertNotSame(first.coordinates, second.coordinates)
            assertNotSame(first.starts, second.starts)
            val expectedCoordinates = second.coordinates.copyOf()
            val expectedStarts = second.starts.copyOf()
            first.coordinates.fill(123.0)
            first.starts.fill(123)
            val third = OverlayRoads.prepare(roads)
            assertArrayEquals(expectedCoordinates, second.coordinates, 0.0)
            assertArrayEquals(expectedStarts, second.starts)
            assertArrayEquals(expectedCoordinates, third.coordinates, 0.0)
            assertArrayEquals(expectedStarts, third.starts)
        }
    }

    @Test fun allocationFixtureUsesActualPreparationForExactAndTrimmedBuffers() {
        val allocation = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        allocation.isThreadAllocatedMemoryEnabled = true
        for ((name, roads) in listOf("separate" to disconnected(128, 256), "joined" to connected(32, 64))) {
            fun prepare(): OverlayRoads = OverlayRoads.prepare(roads)
            repeat(4) { prepare() }
            val rounds = ArrayList<Long>()
            var coordinateCount = 0
            var chainCount = 0
            repeat(3) {
                val thread = Thread.currentThread().id
                val before = allocation.getThreadAllocatedBytes(thread)
                val prepared = prepare()
                val allocated = allocation.getThreadAllocatedBytes(thread) - before
                rounds += allocated
                coordinateCount = prepared.coordinates.size
                chainCount = prepared.starts.size - 1
                assertEquals(prepared.coordinates.size, prepared.starts.last())
            }
            println("OVERLAY_ALLOCATION sdk=${RuntimeEnvironment.getApiLevel()} fixture=$name rounds=$rounds coordinates=$coordinateCount chains=$chainCount")
        }
    }
}
