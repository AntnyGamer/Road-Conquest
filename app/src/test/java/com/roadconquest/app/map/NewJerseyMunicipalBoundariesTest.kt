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
class NewJerseyMunicipalBoundariesTest {
    private fun place(name: String, latitude: Double, longitude: Double) =
        PlaceDiscovery(
            PlaceKind.TOWN, "us|new jersey|${name.lowercase()}",
            name, "New Jersey", "United States", 1_000L, latitude, longitude
        )

    private fun shape(west: Double, south: Double, east: Double, north: Double) =
        """{"type":"Polygon","coordinates":[[
            [$west,$south],[$east,$south],[$east,$north],[$west,$north],[$west,$south]
        ]]}"""

    private fun municipality(
        name: String, simple: String, kind: String, county: String,
        west: Double, south: Double, east: Double, north: Double,
        pop: Long = 1000
    ) = """{
        "type":"Feature",
        "properties":{"NAME":"$name","MUN":"$simple","MUN_TYPE":"$kind",
            "COUNTY":"$county","SQ_MILES":5.0,"POP2020":$pop},
        "geometry":${shape(west,south,east,north)}
    }"""

    private fun collection(vararg features: String) =
        """{"type":"FeatureCollection","features":[${features.joinToString(",")}]}"""

    @Test fun gloucesterCityAndGloucesterTownshipRemainDistinctOfficialMunicipalities() {
        val body = collection(
            municipality("Gloucester City", "Gloucester", "City", "Camden",
                -75.14,39.87,-75.10,39.92,10100),
            municipality("Gloucester Township", "Gloucester", "Township", "Camden",
                -75.10,39.87,-75.05,39.92,20300)
        )
        val city = NewJerseyMunicipalBoundaries.parseResponse(
            place("Gloucester City", 39.89,-75.12), body
        )
        val township = NewJerseyMunicipalBoundaries.parseResponse(
            place("Gloucester Township",39.89,-75.08),body
        )
        assertNotNull(city)
        assertNotNull(township)
        assertEquals(10100L, city!!.population)
        assertEquals(20300L, township!!.population)
        assertTrue(PlaceGeometry.contains(JSONObject(city.geometryJson),39.89,-75.12))
        assertTrue(PlaceGeometry.contains(JSONObject(township.geometryJson),39.89,-75.08))
        // Every point along the shared municipal interface is covered.
        for (i in 0..20) {
            val x = -75.14 + i * 0.0045
            val covered = PlaceGeometry.contains(JSONObject(city.geometryJson),39.89,x) ||
                PlaceGeometry.contains(JSONObject(township.geometryJson),39.89,x)
            assertTrue("Gap at longitude $x",covered)
        }
        val outlines = TownOverlayPartition.authoritativeOutlines(listOf(city,township))
        assertEquals("FeatureCollection",JSONObject(outlines).getString("type"))
        assertEquals(1,JSONObject(outlines).getJSONArray("features").length())
    }

    @Test fun polygonFromAnotherNamedTownCannotReplaceOfficialBoundary() {
        val other = collection(
            municipality("Gloucester Township","Gloucester","Township","Camden",
                -75.14,39.87,-75.10,39.92)
        )
        assertNull(NewJerseyMunicipalBoundaries.parseResponse(
            place("Gloucester City",39.89,-75.12),other
        ))
    }

    @Test fun closestCorrectlyNamedWashingtonTownshipWinsNotRemoteNamesake() {
        val body = collection(
            municipality("Washington Township","Washington","Township","Morris",
                -74.6,40.8,-74.5,40.9,9000),
            municipality("Washington Township","Washington","Township","Gloucester",
                -75.14,39.75,-75.05,39.85,55000)
        )
        val town = NewJerseyMunicipalBoundaries.parseResponse(
            place("Washington Township",39.80,-75.10),body
        )
        assertNotNull(town)
        assertEquals(55000L,town!!.population)
        assertTrue(PlaceGeometry.contains(JSONObject(town.geometryJson),39.8,-75.1))
    }

    @Test fun oldSamplingCellCenterJustOutsideRealBorderMayStillMatch() {
        val body = collection(
            municipality("Pine Hill Borough","Pine Hill","Borough","Camden",
                -75.1,39.75,-75.05,39.8)
        )
        // Old grid-center location ~600m outside town; do not discard it as
        // "unvisited" merely because it is not on the displayed official polygon.
        assertNotNull(NewJerseyMunicipalBoundaries.parseResponse(
            place("Pine Hill",39.804,-75.05),body
        ))
        assertNull(NewJerseyMunicipalBoundaries.parseResponse(
            place("Pine Hill",40.5,-74.5),body
        ))
    }

    @Test fun sourceIsOnlyUsedForNewJerseyTowns() {
        assertTrue(NewJerseyMunicipalBoundaries.applies(place("Camden",39.9,-75.1)))
        assertFalse(NewJerseyMunicipalBoundaries.applies(
            place("Camden",39.9,-75.1).copy(parentName="Pennsylvania")
        ))
        assertFalse(NewJerseyMunicipalBoundaries.applies(
            place("Camden",39.9,-75.1).copy(kind=PlaceKind.STATE)
        ))
    }
}
