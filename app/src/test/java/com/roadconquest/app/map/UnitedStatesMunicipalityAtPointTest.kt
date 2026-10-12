package com.roadconquest.app.map

import com.roadconquest.app.data.PlaceDiscovery
import com.roadconquest.app.data.PlaceKind
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], manifest = Config.NONE)
class UnitedStatesMunicipalityAtPointTest {
    private fun seed(state: String = "New Jersey") = PlaceDiscovery(
        PlaceKind.TOWN, "us|${state.lowercase()}|wrong postal town",
        "Wrong Postal Town", state, "United States", 1_000L, 39.82, -75.03
    )

    private fun feature(name: String, fips: String, west: Double, east: Double,
                        functional: String = "A") =
        """{"type":"Feature","properties":{
           "NAME":"$name","BASENAME":"$name","STATE":"$fips",
           "FUNCSTAT":"$functional","AREALAND":1500000,"AREAWATER":0
        },"geometry":{"type":"Polygon","coordinates":[[
            [$west,39.80],[$east,39.80],[$east,39.84],
            [$west,39.84],[$west,39.80]
        ]]}}"""

    private fun response(vararg features: String) =
        """{"type":"FeatureCollection","features":[${features.joinToString(",")}]}"""

    @Test fun resolvesByRecordedPositionNotNearbyPostalLocality() {
        val result = UnitedStatesMunicipalityAtPoint.parseResponse(
            seed(), response(
                feature("Adjacent borough", "34", -75.10, -75.04),
                feature("Actual borough", "34", -75.04, -75.01)
            ), 4
        )!!
        assertEquals("Actual borough", result.place.displayName)
        assertEquals("us|new jersey|actual borough", result.place.key)
        assertTrue(PlaceGeometry.contains(
            JSONObject(result.overlay.geometryJson), 39.82, -75.03
        ))
    }

    @Test fun malformedProviderBodyBecomesRetryableIoFailure() {
        try {
            UnitedStatesMunicipalityAtPoint.parseResponse(seed(), "<html>Service unavailable</html>", 4)
            fail("Invalid JSON must be treated as an I/O failure for the existing retry path")
        } catch (e: java.io.IOException) {
            assertTrue(e.cause is org.json.JSONException)
        }
    }

    @Test fun neverUsesPolygonFromDifferentState() {
        assertNull(UnitedStatesMunicipalityAtPoint.parseResponse(
            seed(), response(feature("Wrong city", "42", -75.04, -75.01)), 4
        ))
    }

    @Test fun skipsStatisticalSubdivisionsButAcceptsGovernmentTownships() {
        assertNull(UnitedStatesMunicipalityAtPoint.parseResponse(
            seed(), response(feature("Statistical township", "34", -75.04, -75.01, "S")), 1
        ))
        assertEquals("Actual township", UnitedStatesMunicipalityAtPoint.parseResponse(
            seed(), response(feature("Actual township", "34", -75.04, -75.01)), 1
        )?.place?.displayName)
    }

    @Test fun sameRulesForOtherStatesAndRejectsPointsOutsidePolygon() {
        val california = seed("California").copy(
            latitude = 34.06, longitude = -118.25
        )
        assertNull(UnitedStatesMunicipalityAtPoint.parseResponse(
            california, response(feature("Unrelated city", "06", -75.04, -75.01)), 4
        ))
        val texas = seed("Texas")
        assertEquals("Texas city", UnitedStatesMunicipalityAtPoint.parseResponse(
            texas, response(feature("Texas city", "48", -75.04, -75.01)), 4
        )?.place?.displayName)
    }
}
