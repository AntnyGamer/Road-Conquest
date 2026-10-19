package com.roadconquest.app.map

import com.roadconquest.app.data.PlaceKind
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], manifest = Config.NONE)
class TownOverlayPartitionTest {
    @Test fun denselySharedBordersRemainContinuousInsteadOfTinySeparateStrokes() {
        // Both towns contain the same densely sampled survey edge. Dissolving
        // duplicates nodes every vertex; map tiling can discard those short pieces.
        fun shape(west: Boolean): String {
            val ring = JSONArray()
            if (west) {
                ring.put(JSONArray().put(-74.01).put(40.0))
                for (i in 0..100) ring.put(JSONArray().put(-74.0).put(40.0 + i * 0.00001))
                ring.put(JSONArray().put(-74.01).put(40.001))
                ring.put(JSONArray().put(-74.01).put(40.0))
            } else {
                for (i in 100 downTo 0) ring.put(JSONArray().put(-74.0).put(40.0 + i * 0.00001))
                ring.put(JSONArray().put(-73.99).put(40.0))
                ring.put(JSONArray().put(-73.99).put(40.001))
                ring.put(JSONArray().put(-74.0).put(40.001))
            }
            return JSONObject().put("type", "Polygon")
                .put("coordinates", JSONArray().put(ring)).toString()
        }
        val towns = listOf(town("West", "west", 1.0, shape(true)),
            town("East", "east", 1.0, shape(false)))
        for (borders in listOf(TownOverlayPartition.authoritativeOutlines(towns),
            TownOverlayPartition.render(towns).outlines,
            TownOverlayPartition.renderOfficialUs(towns).outlines)) {
            val lines = JSONObject(borders).getJSONArray("features").getJSONObject(0)
                .getJSONObject("geometry").getJSONArray("coordinates")
            assertEquals("Two outer arcs and one shared border", 3, lines.length())
            val shared = (0 until lines.length()).map { lines.getJSONArray(it) }
                .single { line -> (0 until line.length()).all {
                    line.getJSONArray(it).getDouble(0) == -74.0
                } }
            assertEquals("Keep every real survey vertex", 101, shared.length())
            val length = (0 until lines.length()).sumOf { index ->
                val line = lines.getJSONArray(index)
                (1 until line.length()).sumOf { i ->
                    val a = line.getJSONArray(i - 1)
                    val b = line.getJSONArray(i)
                    kotlin.math.hypot(b.getDouble(0) - a.getDouble(0), b.getDouble(1) - a.getDouble(1))
                }
            }
            assertEquals("Every edge once, including the shared separator", 0.043, length, 1e-10)
        }
    }

    @Test fun nearbyDisconnectedTownsNeverGainAnInventedConnectingBorder() {
        val borders = TownOverlayPartition.authoritativeOutlines(listOf(
            town("West", "west", 1.0, square(0.0, 0.0, 1.0, 1.0)),
            town("East", "east", 1.0, square(1.000000001, 0.0, 2.0, 1.0))))
        val lines = JSONObject(borders).getJSONArray("features").getJSONObject(0)
            .getJSONObject("geometry").getJSONArray("coordinates")
        assertEquals(2, lines.length())
        for (i in 0 until lines.length()) {
            val line = lines.getJSONArray(i)
            assertEquals(line.getJSONArray(0).toString(), line.getJSONArray(line.length() - 1).toString())
        }
    }

    private fun town(name: String, key: String, area: Double, polygon: String) =
        PlaceOverlayData(key, name, PlaceKind.TOWN, null, area, polygon)

    private fun square(west: Double, south: Double, east: Double, north: Double) =
        """{"type":"Polygon","coordinates":[[
            [$west,$south],[$east,$south],[$east,$north],[$west,$north],[$west,$south]
        ]]}"""

    private fun fillsAt(result: TownOverlayPartition.Result, latitude: Double, longitude: Double): Int {
        val features = JSONObject(result.fills).getJSONArray("features")
        return (0 until features.length()).count { index ->
            PlaceGeometry.contains(
                features.getJSONObject(index).getJSONObject("geometry"),
                latitude, longitude
            )
        }
    }

    @Test fun overlayViewportAcceptsOnlyTheSamePolygonGeometryTypes() {
        val polygon = square(-74.5, 39.8, -74.0, 40.2)
        assertEquals(OverlayViewport(-74.5, -74.0, 39.8, 40.2),
            OverlayViewport.ofGeometry(polygon))
        val multi = """{"type":"MultiPolygon","coordinates":[[[
            [-74.5,39.8],[-74.0,39.8],[-74.0,40.2],[-74.5,40.2]
        ]]]}"""
        assertEquals(OverlayViewport(-74.5, -74.0, 39.8, 40.2),
            OverlayViewport.ofGeometry(multi))
        assertEquals(null, OverlayViewport.ofGeometry(polygon.replace("Polygon", "LineString")))
    }

    @Test fun officialIncorporatedCityTakesPrecedenceOverOverlappingSubdivision() {
        val result = TownOverlayPartition.renderOfficialUs(listOf(
            town("County Township", "us|pa|census-1-4200100001", 4.0,
                square(3.0, 3.0, 5.0, 5.0)),
            town("Incorporated City", "us|pa|census-4-4201234", 16.0,
                square(0.0, 0.0, 4.0, 4.0))
        ))
        val features = JSONObject(result.fills).getJSONArray("features")
        val visibleAtOverlap = (0 until features.length()).map { features.getJSONObject(it) }
            .filter { PlaceGeometry.contains(it.getJSONObject("geometry"), 3.5, 3.5) }
        assertEquals(1, visibleAtOverlap.size)
        assertEquals("Incorporated City",
            visibleAtOverlap.single().getJSONObject("properties").getString("overlay_name"))
        assertEquals(1, fillsAt(result, 4.5, 4.5))
        assertEquals(2, result.shown.size)
        assertEquals(16.0, result.shown.single { it.name == "Incorporated City" }
            .areaSquareKilometers, 0.0)
    }

    @Test fun intersectingMunicipalitiesHaveSingleVisibleFillAcrossOverlap() {
        val result = TownOverlayPartition.render(listOf(
            town("Town A", "us|nj|a", 4.0, square(0.0, 0.0, 2.0, 2.0)),
            town("Town B", "us|nj|b", 4.0, square(1.0, 0.0, 3.0, 2.0))
        ))
        assertEquals(2, result.shown.size)
        assertEquals(1, fillsAt(result, 1.0, 0.5))
        assertEquals(1, fillsAt(result, 1.0, 1.5))
        assertEquals(1, fillsAt(result, 1.0, 2.5))
        assertEquals(0, fillsAt(result, 2.5, 1.5))
        val properties = JSONObject(result.fills).getJSONArray("features")
        assertEquals(setOf("Town A", "Town B"),
            (0 until properties.length()).map {
                properties.getJSONObject(it).getJSONObject("properties")
                    .getString("overlay_name")
            }.toSet())
    }

    @Test fun smallerVisitedBoroughKeepsItsAreaAndLargerTownGetsHole() {
        val result = TownOverlayPartition.render(listOf(
            town("Enclosing Township", "us|nj|township", 100.0, square(0.0, 0.0, 5.0, 5.0)),
            town("Inner Borough", "us|nj|borough", 2.0, square(1.0, 1.0, 2.0, 2.0))
        ))
        assertEquals(2, result.shown.size)
        assertEquals(1, fillsAt(result, 1.5, 1.5))
        assertEquals(1, fillsAt(result, 3.0, 3.0))
        // Municipal reference area and popup info remain unchanged by visual clipping.
        assertEquals(100.0, result.shown.single { it.name == "Enclosing Township" }
            .areaSquareKilometers, 0.0)
        assertEquals(2.0, result.shown.single { it.name == "Inner Borough" }
            .areaSquareKilometers, 0.0)
    }

    @Test fun duplicateAdministrativeGeometryCannotDoubleShadeTheTown() {
        val geometry = square(-75.15, 39.8, -75.10, 39.9)
        val result = TownOverlayPartition.render(listOf(
            town("Gloucester City", "us|nj|gloucester city", 5.0, geometry),
            town("City of Gloucester City", "us|nj|city of gloucester city", 5.0, geometry)
        ))
        assertEquals(1, result.shown.size)
        assertEquals(1, fillsAt(result, 39.85, -75.12))
    }

    @Test fun unclosedPolygonRingsStillCloseAndRenderIdentically() {
        val open = """{"type":"Polygon","coordinates":[[
            [0,0],[4,0],[4,4],[0,4]
        ]]}"""
        val closed = square(0.0, 0.0, 4.0, 4.0)
        val openResult = TownOverlayPartition.render(listOf(town("Open", "open", 16.0, open)))
        val closedResult = TownOverlayPartition.render(listOf(town("Open", "open", 16.0, closed)))
        assertEquals(closedResult.fills, openResult.fills)
        assertEquals(closedResult.outlines, openResult.outlines)
    }

    @Test fun disjointPolygonsAndTrueInteriorHolesRemainCorrect() {
        val shape = """{"type":"MultiPolygon","coordinates":[
            [[[0,0],[4,0],[4,4],[0,4],[0,0]],
             [[1,1],[2,1],[2,2],[1,2],[1,1]]],
            [[[5,0],[6,0],[6,1],[5,1],[5,0]]]
        ]}"""
        val result = TownOverlayPartition.render(listOf(
            town("Island Township", "us|nj|islands", 10.0, shape)
        ))
        assertEquals(1, fillsAt(result, 3.0, 3.0))
        assertEquals(0, fillsAt(result, 1.5, 1.5))
        assertEquals(1, fillsAt(result, 0.5, 5.5))
        assertTrue(JSONObject(result.outlines).getJSONArray("features").length() > 0)
    }

    @Test fun badLegacyPolygonDoesNotBreakOtherValidTownOverlays() {
        val result = TownOverlayPartition.render(listOf(
            town("Broken", "broken", 1.0, """{"type":"Point","coordinates":[0,0]}"""),
            town("Valid", "valid", 1.0, square(0.0, 0.0, 1.0, 1.0))
        ))
        assertEquals(listOf("valid"), result.shown.map(PlaceOverlayData::key))
        assertTrue(JSONObject(result.fills).getJSONArray("features").length() == 1)
    }
}
