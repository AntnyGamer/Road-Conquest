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

    @Test fun holesAndDisconnectedPartsUseTheirOwnAreaAndAnInteriorBadgeAnchor() {
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

    @Test fun unchangedTileRevisionReusesGeometryButHistoryResetRecalculates() {
        val engine = TownCompletion()
        assertEquals(25, engine.calculate(town(), 1L to 5L) { _, _, _, _ -> coords(5) }!!.percent)
        assertEquals(25, engine.calculate(town(), 1L to 5L) { _, _, _, _ -> error("Cached result") }!!.percent)
        assertEquals(0, engine.calculate(town(), 2L to 5L) { _, _, _, _ -> doubleArrayOf() }!!.percent)
    }

    @Test fun malformedBoundariesDoNotInventProgress() {
        assertNull(TownCompletion().calculate("{}") { _, _, _, _ -> coords(20) })
    }
}
