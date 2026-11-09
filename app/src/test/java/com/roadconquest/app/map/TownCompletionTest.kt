package com.roadconquest.app.map

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37], manifest = Config.NONE)
class TownCompletionTest {
    private val first = FogGrid.cell(40.0, -74.0)!!
    private fun coords(count: Int): DoubleArray = (0 until count).flatMap {
        FogGrid.center(first.copy(column = first.column + it)).let { p -> listOf(p.first, p.second) }
    }.toDoubleArray()
    private fun ring(west: Double, south: Double, east: Double, north: Double) = JSONArray().apply {
        for ((x, y) in listOf(west to south, east to south, east to north, west to north, west to south)) {
            put(JSONArray().put(x).put(y))
        }
    }
    private fun town(count: Int = 20): String {
        val center = FogGrid.center(first)
        val left = FogGrid.corners(center.first, center.second)
        val last = FogGrid.center(first.copy(column = first.column + count - 1))
        val right = FogGrid.corners(last.first, last.second)
        return JSONObject().put("type", "Polygon").put("coordinates",
            JSONArray().put(ring(left[1], left[4], right[3], left[0]))).toString()
    }

    @Test fun closedAndUnclosedTownRingsProduceIdenticalProgressAndAnchor() {
        val geometry = JSONObject(town(2))
        val center = FogGrid.center(first.copy(column = first.column + 1))
        val c = FogGrid.corners(center.first, center.second)
        geometry.getJSONArray("coordinates").put(
            ring(c[1] + 0.00001, c[4] + 0.00001, c[3] - 0.00001,
                (c[0] + c[4]) / 2.0))
        val closed = geometry.toString()
        val rings = geometry.getJSONArray("coordinates")
        for (index in 0 until rings.length()) {
            val polygonRing = rings.getJSONArray(index)
            polygonRing.remove(polygonRing.length() - 1)
        }
        val open = geometry.toString()
        val points = coords(1)
        val closedProgress = requireNotNull(TownCompletion().calculate(closed) { _, _, _, _ -> points })
        val openProgress = requireNotNull(TownCompletion().calculate(open) { _, _, _, _ -> points })
        assertEquals(closedProgress, openProgress)
        assertEquals(TownCompletion().anchor(closed), TownCompletion().anchor(open))
    }

    @Test fun exactTileCoverageUnlocksBronzeSilverAndGoldAt100Percent() {
        val engine = TownCompletion()
        for ((tiles, percent, medal) in listOf(
            Triple(0, 0, TownMedal.NONE), Triple(4, 20, TownMedal.NONE),
            Triple(5, 25, TownMedal.BRONZE), Triple(10, 50, TownMedal.SILVER),
            Triple(18, 90, TownMedal.SILVER), Triple(19, 95, TownMedal.SILVER),
            Triple(20, 100, TownMedal.GOLD))) {
            val progress = requireNotNull(engine.calculate(town()) { _, _, _, _ -> coords(tiles) })
            assertEquals(percent, progress.percent)
            assertEquals(medal, progress.medal)
        }
    }

    @Test fun duplicateSamplesAndTileCentersBeyondBoundaryCannotInflateOrLoseProgress() {
        val c = FogGrid.corners(40.0, -74.0)
        val west = c[1] + (c[3] - c[1]) * 0.1
        val east = c[1] + (c[3] - c[1]) * 0.2
        val boundary = JSONObject().put("type", "Polygon").put("coordinates",
            JSONArray().put(ring(west, c[4] + 0.001, east, c[0] - 0.001))).toString()
        val progress = requireNotNull(TownCompletion().calculate(boundary) { north, e, south, w ->
            val center = FogGrid.center(first)
            assertTrue(center.first in south..north)
            assertTrue(center.second in w..e)
            coords(1) + coords(1)
        })
        assertEquals(100, progress.percent)
        assertTrue(progress.longitude in west..east)
    }

    @Test fun holesUseTheirOwnAreaAndAnInteriorBadgeAnchor() {
        val c = FogGrid.corners(40.0, -74.0)
        val far = FogGrid.center(first.copy(column = first.column + 1))
        val d = FogGrid.corners(far.first, far.second)
        val geometry = JSONObject(town(2))
        // Remove half the second tile as a lake; the first tile is now 2/3 of the town area.
        val rings = geometry.getJSONArray("coordinates")
        rings.put(ring(d[1] + 0.00001, d[4] + 0.00001, d[3] - 0.00001,
            (d[0] + d[4]) / 2.0))
        val progress = requireNotNull(TownCompletion().calculate(geometry.toString()) { _, _, _, _ -> coords(1) })
        assertTrue(progress.percent in 65..67)
        assertTrue(PlaceGeometry.contains(geometry, progress.latitude, progress.longitude))
        assertEquals(100, TownCompletion().calculate(geometry.toString()) { _, _, _, _ -> coords(2) }!!.percent)
    }

    @Test fun disconnectedTownPartsContributeAreaWithoutFillingTheGapBetweenThem() {
        val start = JSONObject(town(1)).getJSONArray("coordinates")
        val center = FogGrid.center(first.copy(column = first.column + 3))
        val c = FogGrid.corners(center.first, center.second)
        val end = JSONArray().put(ring(c[1], c[4], c[3], c[0]))
        val geometry = JSONObject().put("type", "MultiPolygon")
            .put("coordinates", JSONArray().put(start).put(end))
        val progress = requireNotNull(TownCompletion().calculate(geometry.toString()) { _, _, _, _ -> coords(1) })
        assertEquals(50, progress.percent)
        assertTrue(PlaceGeometry.contains(geometry, progress.latitude, progress.longitude))
    }

    @Test fun multiPartTownAnchorStillChoosesLargestDisconnectedPolygon() {
        val large = JSONObject(town(2))
        val distantCenter = FogGrid.center(first.copy(column = first.column + 12))
        val distant = FogGrid.corners(distantCenter.first, distantCenter.second)
        val small = JSONArray().put(ring(distant[1], distant[4], distant[3], distant[0]))
        val multi = JSONObject().put("type", "MultiPolygon").put(
            "coordinates", JSONArray().put(large.getJSONArray("coordinates")).put(small))
        val anchor = requireNotNull(TownCompletion().anchor(multi.toString()))
        assertTrue("The medal must stay in the largest piece",
            PlaceGeometry.contains(large, anchor.first, anchor.second))
        val progress = requireNotNull(TownCompletion().calculate(multi.toString()) { _, _, _, _ -> coords(2) })
        assertTrue(PlaceGeometry.contains(large, progress.latitude, progress.longitude))
        assertTrue("The distant piece must still count toward total area", progress.percent in 65..67)
    }

    @Test fun visualTownPartitionPreservesOriginalCompletionAndSuppliesAVisibleAnchor() {
        val engine = TownCompletion()
        val original = requireNotNull(engine.calculate(town(4)) { _, _, _, _ -> coords(1) })
        val a = FogGrid.center(first.copy(column = first.column + 1))
        val b = FogGrid.center(first.copy(column = first.column + 2))
        val left = FogGrid.corners(a.first, a.second)
        val right = FogGrid.corners(b.first, b.second)
        val smaller = JSONObject().put("type", "Polygon").put("coordinates",
            JSONArray().put(ring(left[1], left[4], right[3], right[0]))).toString()
        val outer = PlaceOverlayData("outer", "Outer", com.roadconquest.app.data.PlaceKind.TOWN, null, 4.0, town(4), original)
        val inner = PlaceOverlayData("inner", "Inner", com.roadconquest.app.data.PlaceKind.TOWN, null, 2.0, smaller)
        val shown = TownOverlayPartition.render(listOf(outer, inner)).shown.first { it.key == "outer" }
        assertEquals(25, shown.completion!!.percent)
        assertFalse(PlaceGeometry.contains(JSONObject(shown.geometryJson), original.latitude, original.longitude))
        val anchor = requireNotNull(engine.anchor(shown.geometryJson))
        assertTrue(PlaceGeometry.contains(JSONObject(shown.geometryJson), anchor.first, anchor.second))
    }

    @Test fun unchangedTileRevisionReusesGeometryButHistoryResetRecalculates() {
        val engine = TownCompletion()
        assertEquals(25, engine.calculate(town(), 1L to 5L) { _, _, _, _ -> coords(5) }!!.percent)
        assertEquals(25, engine.calculate(town(), 1L to 5L) { _, _, _, _ -> error("Cached result") }!!.percent)
        assertEquals(0, engine.calculate(town(), 2L to 5L) { _, _, _, _ -> doubleArrayOf() }!!.percent)
    }

    @Test fun boundedTownShapeCacheStillEvictsLeastRecentlyUsedGeometry() {
        fun shape(index: Int): String {
            val west = -74.0 + index * 0.01
            return JSONObject().put("type", "Polygon")
                .put("coordinates", JSONArray().put(ring(west, 40.0, west + 0.005, 40.005)))
                .toString()
        }
        val engine = TownCompletion()
        val revision = 11L to 23L
        val firstShape = shape(0)
        assertEquals(0, engine.calculate(firstShape, revision) { _, _, _, _ ->
            doubleArrayOf()
        }!!.percent)
        for (index in 1..32) {
            assertNotNull(engine.calculate(shape(index), revision) { _, _, _, _ ->
                doubleArrayOf()
            })
        }
        // The 32 newest entries retain their prior revision and result.
        assertEquals(0, engine.calculate(shape(32), revision) { _, _, _, _ ->
            error("Latest geometry must remain cached")
        }!!.percent)
        // The first entry must be recomputed, proving it was evicted at 33.
        assertNull(engine.calculate(firstShape, revision) { _, _, _, _ ->
            error("The oldest geometry must have been evicted")
        })
    }

    @Test fun malformedBoundariesDoNotInventProgress() {
        assertNull(TownCompletion().calculate("{}") { _, _, _, _ -> coords(20) })
    }

    @Test fun townsAcrossTheDateLineDoNotBecomeAlmostWorldWide() {
        val boundary = JSONObject().put("type", "Polygon").put("coordinates",
            JSONArray().put(ring(179.995, 39.999, -179.995, 40.001))).toString()
        val west = FogGrid.cell(40.0, 179.999)!!
        val east = FogGrid.cell(40.0, -179.999)!!
        val samples = listOf(west, east).flatMap { FogGrid.center(it).let { p -> listOf(p.first, p.second) } }.toDoubleArray()
        val progress = requireNotNull(TownCompletion().calculate(boundary) { north, e, south, w ->
            assertTrue(w > e)
            assertTrue(north - south < 0.1)
            samples
        })
        assertEquals(100, progress.percent)
        assertTrue(kotlin.math.abs(progress.longitude) > 179.99)
    }
}
