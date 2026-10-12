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
class UnitedStatesMunicipalBoundariesTest {
    private fun place(name: String, state: String, lat: Double, lon: Double) =
        PlaceDiscovery(
            PlaceKind.TOWN, "us|${state.lowercase()}|${name.lowercase()}",
            name, state, "United States", 1_000L, lat, lon
        )

    private fun box(west: Double, south: Double, east: Double, north: Double) =
        """{"type":"Polygon","coordinates":[[
            [$west,$south],[$east,$south],[$east,$north],[$west,$north],[$west,$south]
        ]]}"""

    private fun item(name: String, basename: String, fips: String, geo: String) =
        """{"type":"Feature","properties":{
            "NAME":"$name","BASENAME":"$basename","STATE":"$fips",
            "LSADC":"25","AREALAND":1000000,"AREAWATER":10000
        },"geometry":$geo}"""

    private fun response(vararg features: String) =
        """{"type":"FeatureCollection","features":[${features.joinToString(",")}]}"""

    @Test fun censusBoundaryMissingFeaturesRetriesInsteadOfCreatingNegativeCache() {
        val place = place("Sample City", "New Jersey", 39.95, -75.15)
        for (body in listOf(
            """{"type":"FeatureCollection"}""",
            """{"type":"FeatureCollection","features":null}""",
            """{"type":"FeatureCollection","features":{}}"""
        )) {
            assertThrows(java.io.IOException::class.java) {
                UnitedStatesMunicipalBoundaries.parseResponse(place, body, 4)
            }
        }
        assertNull(UnitedStatesMunicipalBoundaries.parseResponse(
            place, response(), 4
        ))
    }

    @Test fun pennsylvaniaCityIsRetrievedFromOfficialIncorporatedPlaces() {
        val p = place("Philadelphia City", "Pennsylvania", 39.95, -75.15)
        assertTrue(UnitedStatesMunicipalBoundaries.applies(p))
        val data = UnitedStatesMunicipalBoundaries.parseResponse(
            p, response(
                item("Philadelphia city", "Philadelphia", "42",
                    box(-75.20,39.90,-75.10,40.00))
            ), 4
        )
        assertNotNull(data)
        assertTrue(PlaceGeometry.contains(
            JSONObject(data!!.geometryJson), 39.95, -75.15))
        assertEquals(1.01, data.areaSquareKilometers, 0.00001)
    }

    @Test fun cannotBorrowMunicipalityFromAnotherState() {
        val p = place("Springfield City", "Pennsylvania", 39.95, -75.15)
        assertNull(UnitedStatesMunicipalBoundaries.parseResponse(
            p, response(
                item("Springfield city", "Springfield", "25",
                    box(-75.20,39.90,-75.10,40.00))
            ), 4
        ))
    }

    @Test fun townshipAndIncorporatedCityMustNotBeInterchanged() {
        val p = place("Washington Township", "Pennsylvania", 40.00, -75.10)
        val township = item("Washington township", "Washington", "42",
            box(-75.20,39.90,-75.00,40.10))
        assertNull(UnitedStatesMunicipalBoundaries.parseResponse(
            p, response(township), 4
        ))
        assertNotNull(UnitedStatesMunicipalBoundaries.parseResponse(
            p, response(township), 1
        ))
        val city = place("Washington City", "Pennsylvania", 40.00, -75.10)
        assertNull(UnitedStatesMunicipalBoundaries.parseResponse(
            city, response(township), 1
        ))
        assertNull(UnitedStatesMunicipalBoundaries.parseResponse(
            city, response(township), 4
        ))
    }

    @Test fun officialIdSelectsExactSameNamedMunicipalityAndNeverFallsBackToNeighbor() {
        val p = place("Washington Township", "Pennsylvania", 40.0, -75.1)
            .copy(key = "us|pennsylvania|census-1-4200300123")
        val correct = item("Washington township", "Washington", "42",
            box(-75.2, 39.9, -75.0, 40.1))
            .replace("\"LSADC\"", "\"GEOID\":\"4200300123\",\"LSADC\"")
        val neighbor = item("Washington township", "Washington", "42",
            box(-75.21, 39.91, -75.01, 40.11))
            .replace("\"LSADC\"", "\"GEOID\":\"4200500456\",\"LSADC\"")
        val chosen = UnitedStatesMunicipalBoundaries.parseResponse(
            p, response(neighbor, correct), 1
        )
        assertNotNull(chosen)
        assertTrue(PlaceGeometry.contains(
            JSONObject(chosen!!.geometryJson), 40.0, -75.1
        ))
        // A nearby same-named feature does not prove the requested official ID.
        assertNull(UnitedStatesMunicipalBoundaries.parseResponse(
            p, response(neighbor), 1
        ))
        assertNull(UnitedStatesMunicipalBoundaries.parseResponse(
            p, response(correct), 4
        ))
    }

    @Test fun duplicateNamesChooseClosestGeographicMunicipality() {
        val p = place("Washington Township", "Pennsylvania", 40.0, -75.10)
        val close = item("Washington township", "Washington", "42",
            box(-75.2,39.90,-75.00,40.1))
        val far = item("Washington township", "Washington", "42",
            box(-79.0,40.8,-78.8,41.0))
        val result = UnitedStatesMunicipalBoundaries.parseResponse(
            p, response(far, close), 1
        )
        assertNotNull(result)
        assertTrue(PlaceGeometry.contains(
            JSONObject(result!!.geometryJson), 40.00,-75.10))
    }

    @Test fun sourceHandlesNewJerseyExactlyLikeOtherStatesButNotForeignCountries() {
        assertTrue(UnitedStatesMunicipalBoundaries.applies(
            place("Camden City", "New Jersey", 39.95,-75.05)
        ))
        assertFalse(UnitedStatesMunicipalBoundaries.applies(
            place("London", "England", 51.50,-0.10)
                .copy(countryName = "United Kingdom")
        ))
        assertTrue(UnitedStatesMunicipalBoundaries.isUnitedStatesKey(
            "us|pennsylvania|philadelphia city"
        ))
        assertFalse(UnitedStatesMunicipalBoundaries.isUnitedStatesKey(
            "gb|england|london"
        ))
    }
    @Test fun newJerseyCityAndTownshipUseSameNationwideCensusProvider() {
        val city = place("Gloucester City", "New Jersey", 39.89, -75.12)
        val township = place("Gloucester Township", "New Jersey", 39.89, -75.06)
        val cityShape = item("Gloucester city", "Gloucester", "34",
            box(-75.14, 39.87, -75.10, 39.92))
        val townshipShape = item("Gloucester township", "Gloucester", "34",
            box(-75.10, 39.87, -75.05, 39.92))
        assertTrue(UnitedStatesMunicipalBoundaries.applies(city))
        assertTrue(UnitedStatesMunicipalBoundaries.applies(township))
        val a = UnitedStatesMunicipalBoundaries.parseResponse(city, response(cityShape), 4)
        val b = UnitedStatesMunicipalBoundaries.parseResponse(township, response(townshipShape), 1)
        assertNotNull(a)
        assertNotNull(b)
        assertNull(UnitedStatesMunicipalBoundaries.parseResponse(township, response(cityShape), 4))
        assertNull(UnitedStatesMunicipalBoundaries.parseResponse(city, response(townshipShape), 1))
        val officialBorders = TownOverlayPartition.authoritativeOutlines(listOf(a!!, b!!))
        assertEquals(1, JSONObject(officialBorders).getJSONArray("features").length())
    }

    @Test fun californiaAndTexasHaveTheSameEligibilityAsNewJersey() {
        assertTrue(UnitedStatesMunicipalBoundaries.applies(
            place("Los Angeles City", "California", 34.05, -118.24)
        ))
        assertTrue(UnitedStatesMunicipalBoundaries.applies(
            place("Austin City", "Texas", 30.27, -97.74)
        ))
        assertTrue(UnitedStatesMunicipalBoundaries.applies(
            place("Pine Hill Borough", "New Jersey", 39.79, -75.02)
        ))
    }

}
