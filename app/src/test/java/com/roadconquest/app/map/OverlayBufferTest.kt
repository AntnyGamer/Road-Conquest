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
