package com.roadconquest.app

import android.location.Location
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37], manifest = Config.NONE)
class DrivingFilterTest {
    private fun point(latitude: Double, second: Long, speed: Float = 5f) = Location("gps").apply {
        this.latitude = latitude; longitude = -74.0; accuracy = 5f
        this.speed = speed; time = 1_000_000L + second * 1_000
        elapsedRealtimeNanos = second * 1_000_000_000
    }

    private fun accepts(current: Location, previous: Location): Boolean {
        val method = TrackingService::class.java.getDeclaredMethod("isUsableDrivingLocation", Location::class.java, Location::class.java)
        method.isAccessible = true
        return method.invoke(TrackingService(), current, previous) as Boolean
    }

    @Test fun reportedDrivingSpeedCannotAuthorizeAnImpossiblePositionJump() {
        assertFalse(accepts(point(40.1, 13), point(40.0, 10)))
    }

    @Test fun ordinaryDrivingIsAccepted() {
        assertTrue(accepts(point(40.0002, 13), point(40.0, 10)))
    }

    @Test fun explicitlyUnreliableMeasuredSpeedCannotAuthorizeGpsDrift() {
        val previous = point(40.0, 10).apply { speedAccuracyMetersPerSecond = 10f }
        val current = point(40.00005, 13).apply { speedAccuracyMetersPerSecond = 10f }
        assertFalse(accepts(current, previous))
    }

    @Test fun poorSpeedAccuracyStillAllowsClearMovementEvidence() {
        val previous = point(40.0, 10).apply { speedAccuracyMetersPerSecond = 10f }
        val current = point(40.0003, 13).apply { speedAccuracyMetersPerSecond = 10f }
        assertTrue(accepts(current, previous))
    }

    @Test fun stationaryDriftAndOutOfOrderFixesAreRejected() {
        assertFalse(accepts(point(40.000001, 13, 0f), point(40.0, 10, 0f)))
        assertFalse(accepts(point(40.0002, 9), point(40.0, 10)))
    }

    @Test fun mockLocationsCannotUnlockLocalRoads() {
        assertFalse(accepts(point(40.0002, 13).apply { isMock = true }, point(40.0, 10)))
        assertFalse(accepts(point(40.0002, 13), point(40.0, 10).apply { isMock = true }))
    }

    @Test fun staleLocationSubscriptionHasRecoveryCooldown() {
        val now = 1_000_000L
        assertFalse(TrackingService.shouldRecoverLocationUpdates(now, now - 119_999L, now - 600_000L))
        assertFalse(TrackingService.shouldRecoverLocationUpdates(now, now - 180_000L, now - 299_999L))
        assertTrue(TrackingService.shouldRecoverLocationUpdates(now, now - 180_000L, now - 300_000L))
        assertFalse(TrackingService.shouldRecoverLocationUpdates(now, now + 1L, now - 300_000L))
        assertFalse(TrackingService.shouldRecoverLocationUpdates(now, now - 180_000L, now + 1L))
    }

    @Test fun straightFixesAreCompressedButCornersAndUncertainFixesArePreserved() {
        val service = TrackingService()
        val a = point(40.0, 10, 8f).apply { bearing = 90f }
        val straight = point(40.00002, 13, 8f).apply { bearing = 94f }
        assertFalse(service.shouldPersistDrivingSample(a, straight))
        assertTrue(service.shouldPersistDrivingSample(a, point(40.00004, 16, 8f).apply { bearing = 94f }))
        assertTrue(service.shouldPersistDrivingSample(a, point(40.00002, 13, 8f).apply { bearing = 112f }))
        assertTrue(service.shouldPersistDrivingSample(a, straight.apply { accuracy = 30f }))
        assertTrue(service.shouldPersistDrivingSample(a, straight.apply { accuracy = 5f; removeBearing() }))
    }

    @Test fun missedSCurveApexIsRecoveredForNormalMatching() {
        val service = TrackingService()
        val a = point(40.0, 10, 8f).apply { bearing = 90f }
        val bend = point(40.00008, 13, 8f).apply { longitude = -73.9999; bearing = 94f }
        val end = point(40.0, 16, 8f).apply { longitude = -73.9998; bearing = 92f }
        assertTrue(service.skippedFixCarriesGeometry(a, bend, end))
        val straight = point(40.0, 13, 8f).apply { longitude = -73.9999; bearing = 94f }
        assertFalse(service.skippedFixCarriesGeometry(a, straight, end))
    }

    @Test fun forcedRemoteRetriesRespectSevenMinuteCap() {
        val time = 1_000_000L
        assertEquals(0L, TrackingService.remainingRemoteMatchingDelayMs(time, 0L))
        assertEquals(420_000L, TrackingService.remainingRemoteMatchingDelayMs(time, time))
        assertEquals(300_000L, TrackingService.remainingRemoteMatchingDelayMs(time + 120_000L, time))
        assertEquals(0L, TrackingService.remainingRemoteMatchingDelayMs(time + 420_000L, time))
        assertEquals(0L, TrackingService.remainingRemoteMatchingDelayMs(time - 1L, time))
    }

    @Test fun nanAccuracyCannotAuthorizeDriving() {
        assertFalse(accepts(point(40.0002, 13).apply { accuracy = Float.NaN }, point(40.0, 10)))
    }
}
