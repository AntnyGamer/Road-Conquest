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
            PlaceKind.TOWN, "us|new jersey|sample borough", "Sample borough",
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
    }

    @Test fun newTownBecomesReusableImmediatelyWithoutReloading() {
        val town = PlaceDiscovery(
            PlaceKind.TOWN, "us|texas|actual city", "Actual city",
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
