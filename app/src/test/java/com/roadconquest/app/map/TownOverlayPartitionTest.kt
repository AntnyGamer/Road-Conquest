package com.roadconquest.app.map

import com.roadconquest.app.data.PlaceKind
import org.json.JSONObject
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

    @Test fun officialNeighboringMunicipalitiesKeepTheirExactGeometryAndShareBoundary() {
        val west = town("West Borough", "us|nj|west", 2.0,
            square(-75.15, 39.80, -75.10, 39.86))
            .copy(authoritativeNjBoundary = true)
        val east = town("East Township", "us|nj|east", 3.0,
            square(-75.10, 39.80, -75.05, 39.86))
            .copy(authoritativeNjBoundary = true)
        val rendered = TownOverlayPartition.render(listOf(west, east))
        assertEquals(2, rendered.shown.size)
        // Official GIS coordinates must NOT be modified by the overlap
        // partitioner; separate clipped town polygons caused visual gaps.
        assertEquals(west.geometryJson, rendered.shown.first { it.key == west.key }.geometryJson)
        assertEquals(east.geometryJson, rendered.shown.first { it.key == east.key }.geometryJson)
        assertEquals(1, fillsAt(rendered, 39.83, -75.12))
        assertEquals(1, fillsAt(rendered, 39.83, -75.07))
        assertEquals(0, fillsAt(rendered, 39.83, -75.16))
        assertTrue(JSONObject(rendered.outlines).getJSONArray("features").length() > 0)
    }

    @Test fun officialAndLegacyMunicipalShapesCanCoexistWithoutMutatingOfficialGeometry() {
        val official = town("NJ Municipality", "nj", 1.0,
            square(-75.15, 39.80, -75.10, 39.86))
            .copy(authoritativeNjBoundary = true)
        val legacy = town("Other State", "non-nj", 3.0,
            square(-76.20, 39.80, -76.10, 39.90))
        val rendered = TownOverlayPartition.render(listOf(legacy, official))
        assertEquals(2, rendered.shown.size)
        assertEquals(official.geometryJson,
            rendered.shown.single { it.key == official.key }.geometryJson)
        assertEquals(1, fillsAt(rendered, 39.83, -75.12))
        assertEquals(1, fillsAt(rendered, 39.85, -76.15))
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
