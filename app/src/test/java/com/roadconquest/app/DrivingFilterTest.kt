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

    @Test fun positionalJitterCannotOverrideTwoExplicitWalkingOrStoppedSpeeds() {
        for (speed in listOf(0f, 1.4f)) {
            assertFalse(accepts(point(40.0003, 13, speed), point(40.0, 10, speed)))
        }
        assertTrue(accepts(point(40.0003, 13).apply { removeSpeed() },
            point(40.0, 10).apply { removeSpeed() }))
    }

    @Test fun recentDrivingKeepsSlowTurnFixesButNeverStartsWalkingTripsOrAcceptsStops() {
        val service = TrackingService()
        val method = TrackingService::class.java.getDeclaredMethod("isUsableDrivingLocation",
            Location::class.java, Location::class.java).apply { isAccessible = true }
        val previous = point(40.0, 20, 1.1f).apply { accuracy = 3f }
        val current = point(40.00003, 23, 1.1f).apply { accuracy = 3f }
        assertFalse(method.invoke(service, current, previous) as Boolean)
        TrackingService::class.java.getDeclaredField("lastConfirmedDriving").apply { isAccessible = true }
            .set(service, point(40.0, 10, 8f))
        assertTrue(method.invoke(service, current, previous) as Boolean)
        assertFalse(method.invoke(service, point(40.00003, 23, 0f), point(40.0, 20, 0f)) as Boolean)
        assertFalse(method.invoke(service, point(40.00003, 63, 1.1f), point(40.0, 60, 1.1f)) as Boolean)
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

    @Test fun shortTurnKeepsItsActualApproachEvenInsideChordTolerance() {
        val service = TrackingService()
        val anchor = point(40.0, 10, 8f).apply { bearing = 90f }
        val approach = point(40.0, 13, 8f).apply { longitude = -73.99992; bearing = 90f }
        val end = point(40.000018, 14, 8f).apply { longitude = -73.99992; bearing = 0f }
        assertFalse("A short approach was previously compressed away",
            service.skippedFixCarriesGeometry(anchor, approach, end))
        assertEquals(listOf(approach), service.skippedFixesToRetain(anchor, listOf(approach), end))
    }

    @Test fun straightIntervalDoesNotRestoreRedundantApproachSamples() {
        val service = TrackingService()
        val anchor = point(40.0, 10, 8f).apply { bearing = 90f }
        val approach = point(40.0, 13, 8f).apply { longitude = -73.99992; bearing = 90f }
        val end = point(40.0, 16, 8f).apply { longitude = -73.99984; bearing = 94f }
        assertTrue(service.shouldPersistDrivingSample(anchor, end))
        assertTrue(service.skippedFixesToRetain(anchor, listOf(approach), end).isEmpty())
        anchor.bearing = 359f
        end.bearing = 1f
        assertTrue(service.skippedFixesToRetain(anchor, listOf(approach), end).isEmpty())
    }

    @Test fun approachRetentionIsBoundedAndDoesNotDuplicateCurveEvidence() {
        val service = TrackingService()
        val anchor = point(40.0, 10, 8f).apply { bearing = 90f }
        val early = point(40.0, 11, 8f).apply { longitude = -73.99996; bearing = 90f }
        val approach = point(40.0, 12, 8f).apply { longitude = -73.99992; bearing = 90f }
        val end = point(40.000018, 14, 8f).apply { longitude = -73.99992; bearing = 0f }
        assertEquals(listOf(approach), service.skippedFixesToRetain(anchor, listOf(early, approach), end))
        end.latitude = 40.0002
        assertEquals(listOf(early, approach), service.skippedFixesToRetain(anchor, listOf(early, approach), end))
        assertTrue(service.skippedFixesToRetain(anchor, emptyList(), end).isEmpty())
    }

    @Test fun backupCadenceScalesWithMatchingCapacity() {
        val start = 1_000_000L
        assertEquals(52_500L, TrackingService.remainingRemoteMatchingDelayMs(start, start, 25))
        assertEquals(0L, TrackingService.remainingRemoteMatchingDelayMs(start + 52_500L, start, 25))
        assertEquals(420_000L, TrackingService.remainingRemoteMatchingDelayMs(start, start, 200))
    }

    @Test fun forcedRemoteRetriesRespectSevenMinuteCap() {
        val time = 1_000_000L
        assertEquals(0L, TrackingService.remainingRemoteMatchingDelayMs(time, 0L))
        assertEquals(420_000L, TrackingService.remainingRemoteMatchingDelayMs(time, time))
        assertEquals(300_000L, TrackingService.remainingRemoteMatchingDelayMs(time + 120_000L, time))
        assertEquals(0L, TrackingService.remainingRemoteMatchingDelayMs(time + 420_000L, time))
        assertEquals(0L, TrackingService.remainingRemoteMatchingDelayMs(time - 1L, time))
    }

    @Test fun fullOneCreditPrimaryWindowCanLeaveEarlierWithoutSlowingSparseOrBackupWork() {
        val time = 1_000_000L
        assertEquals(210_000L, TrackingService.remainingRemoteMatchingDelayMs(time, time, 200, 97))
        assertEquals(0L, TrackingService.remainingRemoteMatchingDelayMs(time + 388_000L, time, 200, 97))
        assertEquals(420_000L, TrackingService.remainingRemoteMatchingDelayMs(time, time, 200, 96))
        assertEquals(52_500L, TrackingService.remainingRemoteMatchingDelayMs(time, time, 25, 97))
        // A local cache hit must leave the last actual network timestamp alone.
        assertEquals(0L, TrackingService.remainingRemoteMatchingDelayMs(time + 421_000L, time))
    }

    @Test fun nanAccuracyCannotAuthorizeDriving() {
        assertFalse(accepts(point(40.0002, 13).apply { accuracy = Float.NaN }, point(40.0, 10)))
    }
}
