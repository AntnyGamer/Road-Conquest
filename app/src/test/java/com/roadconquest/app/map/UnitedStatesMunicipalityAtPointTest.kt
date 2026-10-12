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

    @Test fun sameNamedMunicipalitiesUseDistinctOfficialIdentifiers() {
        val first = response(featureWithGeoid(
            "Washington township", "42", "4200300123", -75.04, -75.01
        ))
        val second = response(featureWithGeoid(
            "Washington township", "42", "4200500456", -75.04, -75.01
        ))
        val sample = seed("Pennsylvania")
        val a = UnitedStatesMunicipalityAtPoint.parseResponse(sample, first, 1)!!
        val b = UnitedStatesMunicipalityAtPoint.parseResponse(sample, second, 1)!!
        assertEquals(a.place.displayName, b.place.displayName)
        assertNotEquals(a.place.key, b.place.key)
        assertEquals("us|pennsylvania|census-1-4200300123", a.place.key)
        assertEquals("us|pennsylvania|census-1-4200500456", b.place.key)
    }

    @Test fun incorporatedAndSubdivisionIdsDoNotCollideEvenWithSameLabel() {
        val sample = seed()
        val name = "Example town"
        val incorporated = UnitedStatesMunicipalityAtPoint.parseResponse(
            sample, response(featureWithGeoid(name, "34", "3401234", -75.04, -75.01)), 4
        )!!
        val township = UnitedStatesMunicipalityAtPoint.parseResponse(
            sample, response(featureWithGeoid(name, "34", "3400100123", -75.04, -75.01)), 1
        )!!
        assertEquals("us|new jersey|census-4-3401234", incorporated.place.key)
        assertEquals("us|new jersey|census-1-3400100123", township.place.key)
        assertNotEquals(incorporated.place.key, township.place.key)
    }

    private fun featureWithGeoid(
        name: String, fips: String, geoid: String, west: Double, east: Double
    ) = feature(name, fips, west, east)
        .replace("\"FUNCSTAT\"", "\"GEOID\":\"$geoid\",\"FUNCSTAT\"")

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
