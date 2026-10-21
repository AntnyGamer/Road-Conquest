package com.roadconquest.app.map

import com.roadconquest.app.data.PlaceKind
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37], manifest = Config.NONE)
class OverlayFeatureReuseTest {
    private fun place(json: String, index: Int = 0) = PlaceOverlayData(
        "key-$index", "Name \"$index\"\n雪", PlaceKind.STATE,
        if (index % 2 == 0) null else 123456L, 42.25, json)

    private fun collection(features: JSONArray) = JSONObject()
        .put("type", "FeatureCollection").put("features", features).toString()

    @Test fun exactFillsAndOutlinesPreserveHolesIslandsSignedZeroAndExtraCoordinates() {
        val shell = "[[0,0],[4,0],[4,4],[0,0]]"
        val hole = "[[1,1],[2,1],[1,2],[1,1]]"
        val island = "[[8,8,12],[9,8,13],[8,9,14],[8,8,12]]"
        val zero = "[[-0.0,-0],[0,-0.0],[0.0,1],[-0.0,-0]]"
        val geometries = listOf(
            "{\"type\":\"Polygon\",\"coordinates\":[$shell,$hole]}",
            "{\"type\":\"MultiPolygon\",\"coordinates\":[[$shell],[$island,$hole]]}",
            "{\"type\":\"Polygon\",\"coordinates\":[$zero]}")
        val places = geometries.mapIndexed { index, json -> place(json, index) }
        val expectedFills = JSONArray().apply { places.forEach { put(it.featureJson()) } }
        val expectedOutlines = JSONArray()
        for (ring in listOf(shell, hole, shell, island, hole, zero)) {
            expectedOutlines.put(JSONObject().put("type", "Feature").put("properties", JSONObject())
                .put("geometry", JSONObject().put("type", "LineString")
                    .put("coordinates", JSONArray(ring))))
        }
        val actual = overlayFeatureCollections(places)
        assertEquals(collection(expectedFills), actual.first)
        assertEquals(collection(expectedOutlines), actual.second)
        assertEquals(geometries, places.map { it.geometryJson })
        assertEquals(actual, overlayFeatureCollections(places))
    }

    @Test fun unsupportedAndIncompleteShapesRetainFillsAndTheSameRingFiltering() {
        val places = listOf(
            place("{\"type\":\"Point\",\"coordinates\":[1,2]}"),
            place("{\"type\":\"Polygon\"}", 1),
            place("{\"type\":\"Polygon\",\"coordinates\":[null,[],[[0,0]]]}" , 2))
        val expectedFills = JSONArray().apply { places.forEach { put(it.featureJson()) } }
        val actual = overlayFeatureCollections(places)
        assertEquals(collection(expectedFills), actual.first)
        assertEquals(collection(JSONArray()), actual.second)
        assertEquals(collection(JSONArray()) to collection(JSONArray()), overlayFeatureCollections(emptyList()))
    }

    @Test fun invalidGeometryStillFailsTheFillConversion() {
        for (json in listOf("bad", "[]", "{\"type\":", "null")) {
            assertThrows(JSONException::class.java) { place(json).featureJson() }
            assertThrows(JSONException::class.java) { overlayFeatureCollections(listOf(place(json))) }
        }
    }
}
