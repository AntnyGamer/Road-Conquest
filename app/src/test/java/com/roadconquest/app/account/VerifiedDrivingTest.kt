package com.roadconquest.app.account

import android.location.Location
import android.os.SystemClock
import com.roadconquest.app.util.Prefs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37], manifest = Config.NONE)
class VerifiedDrivingTest {
    @Before fun setup() {
        val context = RuntimeEnvironment.getApplication()
        AccountClient.endpointOverrideForTests = "https://example.com"
        AccountStore.clear(context)
        Prefs.setDriveVerificationEnabled(context, true)
    }

    @After fun cleanup() {
        val context = RuntimeEnvironment.getApplication()
        AccountClient.endpointOverrideForTests = ""
        AccountStore.clear(context)
    }

    @Test fun expiredTriggerFixCanRetryCommittedEvidenceButCannotAddNewEvidence() {
        // A retry is scheduled 30 seconds after an upload failure. The old
        // triggering GPS fix has normally exceeded its 30-second live age limit
        // by then. Previously it was discarded before the pending upload retried.
        assertEquals(true, VerifiedDriving.canResumePreparedEvidence(true, false))
        // No queued upload means an expired GPS fix remains ineligible.
        assertEquals(false, VerifiedDriving.canResumePreparedEvidence(false, false))
        assertEquals(true, VerifiedDriving.canResumePreparedEvidence(false, true))
    }

    @Test fun retryCooldownKeepsFreshFixesBufferedInsteadOfDiscardingThem() {
        val context = RuntimeEnvironment.getApplication()
        val verified = VerifiedDriving(context) { true }
        VerifiedDriving::class.java.getDeclaredField("retryAfter").apply { isAccessible = true }
            .setLong(verified, SystemClock.elapsedRealtime() + 60_000L)

        verified.offer(Location("gps").apply {
            latitude = 40.0
            longitude = -74.0
            accuracy = 5f
            speed = 10f
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        })

        @Suppress("UNCHECKED_CAST")
        val queue = VerifiedDriving::class.java.getDeclaredField("pendingLocations").apply { isAccessible = true }
            .get(verified) as ArrayDeque<Location>
        assertEquals(1, queue.size)

        verified.close()
    }

    @Test fun permanentlyIneligibleFixesNeverEnterVerifiedQueue() {
        val context = RuntimeEnvironment.getApplication()
        val verified = VerifiedDriving(context) { true }
        val busy = VerifiedDriving::class.java.getDeclaredField("busy").apply { isAccessible = true }
            .get(verified) as AtomicBoolean
        busy.set(true)

        fun fix(provider: String = "gps", accuracy: Float = 5f, speed: Float = 10f) =
            Location(provider).apply {
                latitude = 40.0
                longitude = -74.0
                this.accuracy = accuracy
                this.speed = speed
                time = System.currentTimeMillis()
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos().coerceAtLeast(1L)
            }

        verified.offer(fix(provider = "fused"))
        verified.offer(fix(accuracy = 30f))
        verified.offer(fix(speed = 70f))
        verified.offer(fix().apply { removeSpeed() })

        @Suppress("UNCHECKED_CAST")
        val queue = VerifiedDriving::class.java.getDeclaredField("pendingLocations").apply { isAccessible = true }
            .get(verified) as ArrayDeque<Location>
        assertEquals(0, queue.size)

        verified.offer(fix())
        assertEquals(1, queue.size)

        busy.set(false)
        Prefs.setDriveVerificationEnabled(context, false)
        AccountClient.endpointOverrideForTests = ""
        verified.close()
    }

    @Test fun busyVerificationBuffersTheNewestLiveFixesWithinABound() {
        val context = RuntimeEnvironment.getApplication()
        val verified = VerifiedDriving(context) { true }
        val busy = VerifiedDriving::class.java.getDeclaredField("busy").apply { isAccessible = true }
            .get(verified) as AtomicBoolean
        busy.set(true)

        repeat(40) { index ->
            verified.offer(Location("gps").apply {
                latitude = index.toDouble()
                longitude = -74.0
                accuracy = 5f
                speed = 10f
                time = 1_000_000L + index * 3_000L
                elapsedRealtimeNanos = (1_000L + index * 3_000L) * 1_000_000L
            })
        }

        @Suppress("UNCHECKED_CAST")
        val queue = VerifiedDriving::class.java.getDeclaredField("pendingLocations").apply { isAccessible = true }
            .get(verified) as ArrayDeque<Location>
        assertEquals(32, queue.size)
        assertEquals(8.0, queue.first.latitude, 0.0)
        assertEquals(39.0, queue.last.latitude, 0.0)

        busy.set(false)
        Prefs.setDriveVerificationEnabled(context, false)
        AccountClient.endpointOverrideForTests = ""
        verified.close()
    }

    @Test fun hiddenLeaderboardsRejectLiveEvidenceEvenWithOldSharingConsent() {
        val context = RuntimeEnvironment.getApplication()
        val verified = VerifiedDriving(context)
        verified.offer(Location("gps").apply {
            latitude = 40.0; longitude = -74.0; accuracy = 5f; speed = 10f
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos().coerceAtLeast(1L)
        })
        @Suppress("UNCHECKED_CAST")
        val queue = VerifiedDriving::class.java.getDeclaredField("pendingLocations").apply { isAccessible = true }
            .get(verified) as ArrayDeque<Location>
        assertEquals(0, queue.size)
        verified.close()
    }
}
