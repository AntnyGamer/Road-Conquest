package com.roadconquest.app.progression

import android.location.Address
import com.roadconquest.app.data.PendingPlaceCandidate
import com.roadconquest.app.data.PlaceKind
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
}
