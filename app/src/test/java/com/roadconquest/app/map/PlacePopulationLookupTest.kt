package com.roadconquest.app.map

import com.roadconquest.app.data.PlaceKind
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], manifest = Config.NONE)
class PlacePopulationLookupTest {
    @Test fun officialPlaceUsesExactGeoidFrom2020Census() {
        val info = PlaceOverlayInfo("us|new jersey|census-4-3428770", "Haddon Heights borough",
            PlaceKind.TOWN, null, 3.0)
        val request = requireNotNull(PlacePopulationLookup.requestFor(info))
        assertTrue(request.url.contains("Census2020/Places_CouSub_ConCity_SubMCD/MapServer/4/query"))
        val expected = """{"features":[{"attributes":{"STATE":"34","GEOID":"3428770","POP100":7495}}]}"""
        assertEquals(7495L, PlacePopulationLookup.parseResponse(request, expected)?.count)
        val wrong = """{"features":[{"attributes":{"STATE":"34","GEOID":"3428771","POP100":7495}}]}"""
        assertNull(PlacePopulationLookup.parseResponse(request, wrong))
    }

    @Test fun stateUsesOfficialStateGeoidAndDoesNotAcceptAnotherState() {
        val request = requireNotNull(PlacePopulationLookup.requestFor(
            PlaceOverlayInfo("us|new jersey", "New Jersey", PlaceKind.STATE, null, 22000.0)))
        assertTrue(request.url.contains("State_County/MapServer/0/query"))
        assertNull(PlacePopulationLookup.parseResponse(request,
            """{"features":[{"attributes":{"STATE":"42","GEOID":"42","POP100":13000000}}]}"""))
        assertEquals(9000000L, PlacePopulationLookup.parseResponse(request,
            """{"features":[{"attributes":{"STATE":"34","GEOID":"34","POP100":9000000}}]}""")?.count)
    }

    @Test fun foreignCountryCodeMustMatchReturnedIdentity() {
        val info = PlaceOverlayInfo("se", "Sweden", PlaceKind.COUNTRY, null, 1.0)
        val request = requireNotNull(PlacePopulationLookup.requestFor(info))
        assertEquals(10500000L, PlacePopulationLookup.parseResponse(request,
            """{"cca2":"SE","cca3":"SWE","population":10500000}""")?.count)
        assertNull(PlacePopulationLookup.parseResponse(request,
            """{"cca2":"NO","cca3":"NOR","population":5500000}"""))
    }

    @Test fun nonCensusTownNeverGetsAnotherTownPopulation() {
        assertNull(PlacePopulationLookup.requestFor(PlaceOverlayInfo(
            "us|new jersey|haddon heights", "Haddon Heights", PlaceKind.TOWN, null, 3.0)))
    }
}
