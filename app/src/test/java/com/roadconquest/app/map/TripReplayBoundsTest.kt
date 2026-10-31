package com.roadconquest.app.map

import com.roadconquest.app.data.SavedTripPoint
import org.junit.Assert.*
import org.junit.Test
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds

class TripReplayBoundsTest {
    private fun point(lat: Double, lon: Double) = SavedTripPoint(lat, lon, 0L)

    @Test fun dateLineReplayUsesTheNearbyWorldCopyInEitherDirection() {
        val points = listOf(point(-16.1, 179.99), point(-16.0, -179.99), point(-16.05, 180.0))
        val previous = LatLngBounds.Builder().apply {
            points.forEach { include(LatLng(it.latitude, it.longitude)) }
        }.build()
        assertTrue("The previous camera calculation spans almost the world", previous.longitudeSpan > 359.0)
        for (route in listOf(points, points.reversed())) {
            val bounds = tripReplayBounds(route)
            assertEquals(0.02, bounds.longitudeSpan, 1e-9)
            assertEquals(180.0, bounds.center.longitude, 1e-9)
            assertEquals(-16.1, bounds.latitudeSouth, 0.0)
            assertEquals(-16.0, bounds.latitudeNorth, 0.0)
            route.forEach {
                val lon = if (it.longitude < bounds.longitudeWest) it.longitude + 360.0 else it.longitude
                assertTrue(bounds.contains(LatLng(it.latitude, lon)))
            }
        }
    }

    @Test fun ordinaryAndWideRoutesKeepAllTheirPointsInsideTheCameraBounds() {
        for (points in listOf(
            listOf(point(40.0, -74.0)),
            listOf(point(40.0, -74.0), point(41.0, -73.0)),
            listOf(point(10.0, -170.0), point(20.0, -20.0), point(30.0, 100.0)),
            listOf(point(0.0, -180.0), point(0.0, 180.0)),
            listOf(point(40.0, -74.0), point(40.0, -74.0)))) {
            val bounds = tripReplayBounds(points)
            for (point in points) {
                val lon = if (point.longitude < bounds.longitudeWest) point.longitude + 360.0 else point.longitude
                assertTrue(bounds.contains(LatLng(point.latitude, lon)))
            }
            assertTrue(bounds.longitudeSpan <= 270.0)
        }
        val ordinary = tripReplayBounds(listOf(point(40.0, -74.0), point(41.0, -73.0)))
        assertEquals(-74.0, ordinary.longitudeWest, 0.0)
        assertEquals(-73.0, ordinary.longitudeEast, 0.0)
    }
}
