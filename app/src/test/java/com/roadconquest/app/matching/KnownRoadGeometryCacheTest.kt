package com.roadconquest.app.matching

import com.roadconquest.app.data.MatchedRoad
import com.roadconquest.app.data.TrackingRepository
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37], manifest = Config.NONE)
class KnownRoadGeometryCacheTest {
    @Test fun repeatedExactGeometryKeepsEveryCoordinateAndChangedTextIsReparsed() {
        val cache = KnownRoadGeometryCache()
        val original = "[[-74.00000001,40.0],[-73.999,40.0]]"
        val first = requireNotNull(cache.read(original))
        assertSame(first, cache.read(String(original.toCharArray())))
        assertEquals(-74.00000001, first.getJSONArray(0).getDouble(0), 0.0)
        val changed = requireNotNull(cache.read(original.replace("00000001", "00000002")))
        assertNotSame(first, changed)
        assertEquals(-74.00000002, changed.getJSONArray(0).getDouble(0), 0.0)
        assertEquals(-74.00000001, first.getJSONArray(0).getDouble(0), 0.0)
    }

    @Test fun invalidGeometryDoesNotHideALaterCorrectedPayload() {
        val cache = KnownRoadGeometryCache()
        assertNull(cache.read("not-json"))
        assertNull(cache.read("not-json"))
        val corrected = requireNotNull(cache.read("[[180,40],[-180,40]]"))
        assertEquals(180.0, corrected.getJSONArray(0).getDouble(0), 0.0)
        assertEquals(-180.0, corrected.getJSONArray(1).getDouble(0), 0.0)
    }

    @Test fun oversizedBodiesAreParsedWithoutReplacingTheOneSmallCachedBody() {
        val cache = KnownRoadGeometryCache()
        val small = "[[-74,40],[-73,40]]"
        val first = cache.read(small)
        val large = List(2_000) { "[-74.000001,40.000001]" }.joinToString(prefix = "[", postfix = "]")
        assertTrue(large.length > 16_384)
        val firstLarge = requireNotNull(cache.read(large))
        val secondLarge = requireNotNull(cache.read(large))
        assertNotSame(firstLarge, secondLarge)
        assertEquals(2_000, secondLarge.length())
        assertEquals(firstLarge.toString(), secondLarge.toString())
        assertSame(first, cache.read(small))
    }

    @Test fun currentRoadGeometryAndNewParallelRoadsStillControlEveryLookup() {
        val context = RuntimeEnvironment.getApplication()
        TrackingRepository(context).clearHistory()
        val repository = TrackingRepository(context)
        val original = "[[-74,40],[-73.999,40]]"
        repository.upsertRoads(listOf(MatchedRoad("Main", original, 1000, 2000, 0.99)))
        val read = KnownRoadGeometryCache()::read
        val road = requireNotNull(repository.findUnambiguousPreviouslyDrivenRoadNear(
            40.0, -73.9995, geometryReader = read))
        val moved = "[[-74,40.00007],[-73.999,40.00007]]"
        repository.readableDatabase().execSQL("UPDATE roads SET geometry_json = ?, min_lat = 40.00007, max_lat = 40.00007 WHERE segment_id = ?",
            arrayOf(moved, road.segmentId))
        assertNull(repository.findUnambiguousPreviouslyDrivenRoadNear(40.0, -73.9995, geometryReader = read))
        assertNotNull(repository.findUnambiguousPreviouslyDrivenRoadNear(40.00007, -73.9995, geometryReader = read))
        repository.upsertRoads(listOf(MatchedRoad("Parallel", "[[-74,40.00011],[-73.999,40.00011]]", 1000, 2000, 0.99)))
        assertNull(repository.findUnambiguousPreviouslyDrivenRoadNear(40.00007, -73.9995, geometryReader = read))
    }
}
