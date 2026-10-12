package com.roadconquest.app.progression

import android.location.Address
import com.roadconquest.app.data.PendingPlaceCandidate
import com.roadconquest.app.data.PlaceKind
import com.roadconquest.app.data.PlaceDiscovery
import com.roadconquest.app.map.PlaceOverlayData
import com.roadconquest.app.map.UnitedStatesMunicipalityAtPoint
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37], manifest = Config.NONE)
class PlaceResolverTest {
    private val candidate = PendingPlaceCandidate(
        cellX = 1, cellY = 2, latitude = 39.824, longitude = -75.125,
        visitedAt = 1000L, attempts = 0
    )

    private fun address(locality: String, subAdminArea: String) = Address(Locale.US).apply {
        countryName = "United States"
        countryCode = "US"
        adminArea = "New Jersey"
        this.locality = locality
        this.subAdminArea = subAdminArea
    }

    @Test fun abbreviatedAndFullStateNamesResolveToIdenticalUSVisitKeys() {
        val abbreviated = address("Woodbury", "Deptford Township").apply { adminArea = "NJ" }
        val full = address("Woodbury", "Deptford Township")
        val a = PlaceResolver.resolveAddresses(candidate, listOf(abbreviated))
        val b = PlaceResolver.resolveAddresses(candidate, listOf(full))
        assertEquals(b, a)
        assertEquals("New Jersey", a.single { it.kind == PlaceKind.STATE }.displayName)
        assertEquals("us|new jersey", a.single { it.kind == PlaceKind.STATE }.key)
        assertEquals(b.single { it.kind == PlaceKind.TOWN }.key,
            a.single { it.kind == PlaceKind.TOWN }.key)
    }

    @Test fun townshipNameOverridesNearbyPostalCity() {
        val places = PlaceResolver.resolveAddresses(
            candidate, listOf(address("Woodbury", "Deptford Township"))
        )
        assertEquals("Deptford Township", places.single { it.kind == PlaceKind.TOWN }.displayName)
        assertEquals("New Jersey", places.single { it.kind == PlaceKind.STATE }.displayName)
    }

    @Test fun postalLocalityIsOnlyAnUnverifiedCandidate() {
        val places = PlaceResolver.resolveAddresses(
            candidate, listOf(address("Pitman", "Gloucester County"))
        )
        assertEquals("Pitman", places.single { it.kind == PlaceKind.TOWN }.displayName)
        // PlaceResolver.resolve must confirm this candidate against a real polygon.
    }

    @Test fun missingMunicipalityDoesNotAccidentallyRewardCounty() {
        val places = PlaceResolver.resolveAddresses(
            candidate, listOf(address("", "Gloucester County"))
        )
        assertTrue(places.none { it.kind == PlaceKind.TOWN })
    }

    @Test fun municipalCacheParsesKnownPolygonsOnceForManyFixes() {
        val town = PlaceDiscovery(
            PlaceKind.TOWN, "us|new jersey|census-4-3401234", "Sample borough",
            "New Jersey", "United States", 1000L, 39.82, -75.03
        )
        val rectangle = JSONObject("""{"type":"Polygon","coordinates":[[
            [-75.04,39.81],[-75.02,39.81],[-75.02,39.83],
            [-75.04,39.83],[-75.04,39.81]
        ]]}""")
        var loads = 0
        val cache = PlaceResolver.VerifiedMunicipalCache {
            loads++
            mutableListOf(town to rectangle)
        }
        for (index in 0 until 6) {
            val fix = town.copy(latitude = 39.82, longitude = -75.03,
                visitedAt = 2000L + index)
            val found = cache.find(fix)
            assertEquals(town.key, found?.key)
            assertEquals(fix.visitedAt, found?.visitedAt)
        }
        assertEquals("Only one cache load for the entire discovery batch", 1, loads)
        assertNull(cache.find(town.copy(latitude = 39.90)))
        assertNull(cache.find(town.copy(parentName = "Pennsylvania")))
        // Both labels identify the same U.S. country. The pre-optimization
        // resolver reused this polygon without requiring exact country text.
        assertEquals(town.key, cache.find(town.copy(countryName = "USA"))?.key)
    }

    @Test fun overlappingCachedMunicipalitiesRequireOfficialLookupInsteadOfVisitOrder() {
        val countySubdivision = PlaceDiscovery(
            PlaceKind.TOWN, "us|ohio|census-1-3900100123", "Sample township",
            "Ohio", "United States", 1000L, 40.0, -82.95
        )
        val incorporated = countySubdivision.copy(
            key = "us|ohio|census-4-3905678", displayName = "Sample city", visitedAt = 2000L
        )
        val outer = JSONObject("""{"type":"Polygon","coordinates":[[
            [-83.0,39.9],[-82.9,39.9],[-82.9,40.1],[-83.0,40.1],[-83.0,39.9]
        ]]}""")
        val inner = JSONObject("""{"type":"Polygon","coordinates":[[
            [-82.97,39.97],[-82.93,39.97],[-82.93,40.03],
            [-82.97,40.03],[-82.97,39.97]
        ]]}""")
        val insideBoth = countySubdivision.copy(
            latitude = 40.0, longitude = -82.95, visitedAt = 3000L
        )
        for (ordered in listOf(
            mutableListOf(countySubdivision to outer, incorporated to inner),
            mutableListOf(incorporated to inner, countySubdivision to outer)
        )) {
            val cache = PlaceResolver.VerifiedMunicipalCache { ordered }
            // An incorporated town always wins over overlapping county
            // subdivisions, regardless of cache insertion order.
            assertEquals(incorporated.key, cache.find(insideBoth)?.key)
            // A cached subdivision alone is not authoritative: a different
            // unvisited incorporated municipality may also cover this fix.
            assertNull(cache.find(insideBoth.copy(longitude = -82.99)))
        }
    }

    @Test fun legacyAndSubdivisionOnlyCachesCannotSuppressOfficialPointLookup() {
        val oldKey = PlaceDiscovery(
            PlaceKind.TOWN, "us|ohio|sample township", "Sample township",
            "Ohio", "United States", 1000L, 40.0, -82.95
        )
        val subdivision = oldKey.copy(key = "us|ohio|census-1-3900100123")
        val polygon = JSONObject("""{"type":"Polygon","coordinates":[[
            [-83.0,39.9],[-82.9,39.9],[-82.9,40.1],
            [-83.0,40.1],[-83.0,39.9]
        ]]}""")
        assertNull(PlaceResolver.VerifiedMunicipalCache {
            mutableListOf(oldKey to polygon)
        }.find(oldKey))
        assertNull(PlaceResolver.VerifiedMunicipalCache {
            mutableListOf(subdivision to polygon)
        }.find(subdivision))
    }

    @Test fun newTownBecomesReusableImmediatelyWithoutReloading() {
        val town = PlaceDiscovery(
            PlaceKind.TOWN, "us|texas|census-4-4801234", "Actual city",
            "Texas", "United States", 1000L, 30.27, -97.74
        )
        val shape = """{"type":"Polygon","coordinates":[[
            [-97.75,30.26],[-97.73,30.26],[-97.73,30.28],
            [-97.75,30.28],[-97.75,30.26]
        ]]}"""
        var loads = 0
        val cache = PlaceResolver.VerifiedMunicipalCache {
            loads++
            mutableListOf()
        }
        assertNull(cache.find(town))
        cache.remember(UnitedStatesMunicipalityAtPoint.Result(
            town, PlaceOverlayData(town.key, town.displayName, town.kind,
                null, 1.0, shape)
        ))
        assertEquals(town.key, cache.find(town.copy(visitedAt = 3000L))?.key)
        assertEquals(1, loads)
    }
}
