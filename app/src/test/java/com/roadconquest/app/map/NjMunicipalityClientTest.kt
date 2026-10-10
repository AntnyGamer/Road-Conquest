package com.roadconquest.app.map

import com.roadconquest.app.data.PlaceDiscovery
import com.roadconquest.app.data.PlaceKind
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], manifest = Config.NONE)
class NjMunicipalityClientTest {
    private fun response(name: String, polygon: String, type: String = "Borough") = """
        {"type":"FeatureCollection","features":[{
            "type":"Feature",
            "properties":{
                "NAME":"$name", "MUN_LABEL":"$name", "MUN_TYPE":"$type",
                "SQ_MILES": 3.2, "POP2020": 8800
            },
            "geometry":$polygon
        }]}
    """.trimIndent()

    private fun rectangle(west: Double, south: Double, east: Double, north: Double) = """
        {"type":"Polygon","coordinates":[[
            [$west,$south],[$east,$south],[$east,$north],[$west,$north],[$west,$south]
        ]]}
    """.trimIndent()

    @Test fun stateGisPointQueryReturnsOnlyMunicipalityContainingGpsFix() {
        val body = response("Gloucester City",
            rectangle(-75.15,39.85,-75.10,39.91))
        val inside = NjMunicipalityClient.parse(body,39.88,-75.13)
        assertNotNull(inside)
        assertEquals("Gloucester City", inside!!.officialName)
        assertEquals(8800L, inside.population)
        assertEquals(3.2 * 2.589988110336, inside.areaSquareKilometers,1e-7)
        assertNull(NjMunicipalityClient.parse(body,39.80,-75.13))
    }

    @Test fun cityAndTownshipWithSameBaseNameAreDistinctMunicipalities() {
        assertTrue(NjMunicipalityClient.equivalent("City of Gloucester", "Gloucester City"))
        assertTrue(NjMunicipalityClient.equivalent("Gloucester City", "City of Gloucester City"))
        assertFalse(NjMunicipalityClient.equivalent("Gloucester City", "Gloucester Township"))
        assertFalse(NjMunicipalityClient.equivalent("Gloucester Borough", "Gloucester City"))
        assertTrue(NjMunicipalityClient.equivalent("Pine Hill", "Borough of Pine Hill"))
    }

    @Test fun postalCommunityCannotBorrowSurroundingOfficialMunicipalityBoundary() {
        val actual = NjMunicipalityClient.parse(response(
            "East Greenwich Township", rectangle(-75.30,39.75,-75.20,39.86),
            type = "Township"
        ),39.81,-75.25)!!
        val clarksboro = PlaceDiscovery(
            PlaceKind.TOWN, "us|new jersey|clarksboro",
            "Clarksboro", "New Jersey", "United States",
            1L, 39.81,-75.25
        )
        assertNull(NjMunicipalityClient.asOverlay(clarksboro,actual))
        val correct = clarksboro.copy(
            key = "us|new jersey|east greenwich township",
            displayName = "East Greenwich Township"
        )
        assertNotNull(NjMunicipalityClient.asOverlay(correct,actual))
    }

    @Test fun invalidOrMissingGeoJsonDoesNotCreateAFalseBoundary() {
        assertNull(NjMunicipalityClient.parse(
            """{"type":"FeatureCollection","features":[]}""",39.8,-75.1))
        assertThrows(IOException::class.java) {
            NjMunicipalityClient.parse("""{"error":{"message":"denied"}}""",39.8,-75.1)
        }
    }

    @Test fun multipolygonAndHoleMustContainActualGpsCoordinate() {
        val body = response(
            "Island Town",
            """{"type":"MultiPolygon","coordinates":[
               [[[-75.3,39.7],[-75.1,39.7],[-75.1,39.9],[-75.3,39.9],[-75.3,39.7]],
                [[-75.25,39.75],[-75.15,39.75],[-75.15,39.85],[-75.25,39.85],[-75.25,39.75]]],
               [[[-74.8,39.7],[-74.7,39.7],[-74.7,39.9],[-74.8,39.9],[-74.8,39.7]]]
            ]}"""
        )
        assertNull(NjMunicipalityClient.parse(body,39.8,-75.2))
        assertNotNull(NjMunicipalityClient.parse(body,39.72,-75.28))
        assertNotNull(NjMunicipalityClient.parse(body,39.8,-74.75))
    }
}
