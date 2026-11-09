package com.roadconquest.app

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Intent
import android.location.LocationManager
import android.location.Location
import android.os.Build
import com.roadconquest.app.util.LocationProviders
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * Run one Android SDK per JVM fork. Robolectric 4.17 may reopen a shared font
 * JAR filesystem when the same class creates several SDK sandboxes, causing a
 * FileSystemAlreadyExistsException unrelated to application behavior.
 *
 * Gradle's forkEvery = 1 keeps these classes isolated without dropping SDK
 * coverage or changing any assertions.
 */
abstract class LocationProviderRecoverySuite {
    @Test fun trackingRequestsBothPreciseSourcesWithoutUploadConsent() {
        val manager = RuntimeEnvironment.getApplication().getSystemService(LocationManager::class.java)
        val shadow = Shadows.shadowOf(manager)
        shadow.setLocationEnabled(true)
        shadow.setProviderEnabled("fused", true)
        shadow.setProviderEnabled("gps", true)
        shadow.setProviderEnabled("network", true)
        assertEquals(listOf("fused", "gps", "network"), LocationProviders.available(manager))
        val registered = mutableListOf<String>()
        LocationProviders.registerHighAccuracy(manager) { registered.add(it); true }
        assertEquals(listOf("fused", "gps"), registered)
        registered.clear()
        LocationProviders.registerHighAccuracy(manager) { registered.add(it); it == "network" }
        assertEquals(listOf("fused", "gps", "network"), registered)
        shadow.setProviderEnabled("gps", false)
        assertEquals(listOf("fused", "network"), LocationProviders.available(manager))
    }

    @Test fun startingPlaceRejectsFixesOlderThanTheCurrentLocationRequest() {
        val old = Location("gps").apply {
            time = 9_000L
            elapsedRealtimeNanos = 9_000_000_000L
        }
        val current = Location("gps").apply {
            time = 10_000L
            elapsedRealtimeNanos = 10_000_000_000L
        }
        assertFalse(LocationProviders.isFixSince(old, 10_000_000_000L, 10_000L))
        assertTrue(LocationProviders.isFixSince(current, 10_000_000_000L, 10_000L))

        // Providers without elapsedRealtime still fall back to wall-clock fix time.
        current.elapsedRealtimeNanos = 0L
        old.elapsedRealtimeNanos = 0L
        assertFalse(LocationProviders.isFixSince(old, 10_000_000_000L, 10_000L))
        assertTrue(LocationProviders.isFixSince(current, 10_000_000_000L, 10_000L))
    }

    @Test fun freshAccuracyWinsRegardlessOfProviderAndOldFixesCannotFreezeTracking() {
        fun fix(provider: String, seconds: Long, accuracyMeters: Float) = Location(provider).apply {
            time = seconds * 1_000L
            elapsedRealtimeNanos = seconds * 1_000_000_000L
            accuracy = accuracyMeters
        }
        assertFalse(LocationProviders.isBetterFix(fix("gps", 11, 20f), fix("fused", 10, 4f)))
        assertTrue(LocationProviders.isBetterFix(fix("gps", 11, 3f), fix("fused", 10, 8f)))
        assertTrue(LocationProviders.isBetterFix(fix("fused", 11, 3f), fix("gps", 10, 8f)))
        assertTrue(LocationProviders.isBetterFix(fix("fused", 10, 3f), fix("gps", 10, 8f)))
        assertFalse(LocationProviders.isBetterFix(fix("gps", 9, 1f), fix("fused", 10, 4f)))
        assertTrue(LocationProviders.isBetterFix(fix("gps", 13, 20f), fix("fused", 10, 4f)))
    }

    @Test fun slightlyOlderNanosecondFixCannotMoveLiveLocationBackward() {
        val latest = Location("fused").apply {
            time = 10_000L
            elapsedRealtimeNanos = 10_000_500_000L
            accuracy = 20f
        }
        val older = Location("gps").apply {
            time = 10_000L
            elapsedRealtimeNanos = 10_000_000_000L
            accuracy = 1f
        }
        assertFalse("Even a sub-millisecond older fix must not replace the newest fix",
            LocationProviders.isBetterFix(older, latest))
        assertTrue("The more accurate fix is allowed at the same exact source time",
            LocationProviders.isBetterFix(older.apply {
                elapsedRealtimeNanos = latest.elapsedRealtimeNanos
            }, latest))
        assertTrue("A more accurate fix arriving later is accepted",
            LocationProviders.isBetterFix(older.apply {
                elapsedRealtimeNanos += 1_000_000L
            }, latest))
    }

    @Test fun invalidSpeedAccuracyCannotBeUsedAsDrivingEvidence() {
        val fix = Location("gps").apply {
            speed = 11f
            speedAccuracyMetersPerSecond = -0.5f
        }
        assertTrue(fix.hasSpeed())
        assertTrue(fix.hasSpeedAccuracy())
        assertNull("Negative uncertainty is invalid metadata, not a precise reading",
            LocationProviders.measuredSpeedOrNull(fix))
        assertEquals(0f, LocationProviders.reliableMeasuredSpeed(fix), 0f)

        fix.speedAccuracyMetersPerSecond = Float.NaN
        assertNull(LocationProviders.measuredSpeedOrNull(fix))
        fix.speedAccuracyMetersPerSecond = 4.1f
        assertNull(LocationProviders.measuredSpeedOrNull(fix))

        // Known and well-calibrated readings still work; stopped = 0 is distinct
        // from missing speed data, and the supported threshold is unchanged.
        fix.speedAccuracyMetersPerSecond = 4f
        assertEquals(11f, LocationProviders.measuredSpeedOrNull(fix)!!, 0f)
        fix.speed = 0f
        fix.speedAccuracyMetersPerSecond = 0f
        assertEquals(0f, LocationProviders.measuredSpeedOrNull(fix)!!, 0f)
    }

    @Test fun disablingGpsFallsBackToAvailableNetworkProvider() {
        val app = RuntimeEnvironment.getApplication()
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val manager = app.getSystemService(LocationManager::class.java)
        val shadow = Shadows.shadowOf(manager)
        shadow.removeProvider("fused")
        shadow.setLocationEnabled(true)
        shadow.setProviderEnabled("gps", true)
        shadow.setProviderEnabled("network", true)
        val controller = Robolectric.buildService(TrackingService::class.java)
        val service = controller.get()
        fun set(name: String, value: Any) = TrackingService::class.java.getDeclaredField(name).apply { isAccessible = true }.set(service, value)
        set("locationManager", manager); set("ready", true)
        try {
            val register = TrackingService::class.java.getDeclaredMethod("requestLocations").apply { isAccessible = true }
            register.invoke(service)
            assertTrue(service in shadow.getLocationUpdateListeners("gps"))
            assertEquals(0f, shadow.getLegacyLocationRequests("gps").single().minUpdateDistanceMeters, 0f)
            shadow.setProviderEnabled("gps", false)
            service.onProviderDisabled("gps")
            assertTrue(service in shadow.getLocationUpdateListeners("network"))
        } finally {
            controller.destroy()
        }
    }

    @Test fun providerBroadcastSwitchesBackToGpsAfterNetworkFallback() {
        val app = RuntimeEnvironment.getApplication()
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val manager = app.getSystemService(LocationManager::class.java)
        val shadow = Shadows.shadowOf(manager)
        shadow.removeProvider("fused")
        shadow.setLocationEnabled(true)
        shadow.setProviderEnabled("gps", false)
        shadow.setProviderEnabled("network", true)
        val controller = Robolectric.buildService(TrackingService::class.java)
        val service = controller.get()
        fun field(name: String) = TrackingService::class.java.getDeclaredField(name).apply { isAccessible = true }
        field("locationManager").set(service, manager)
        field("ready").set(service, true)
        val receiver = field("providerReceiver").get(service) as BroadcastReceiver
        try {
            MainActivity.trackingReceiverRegistered = true
            receiver.onReceive(service, Intent(LocationManager.PROVIDERS_CHANGED_ACTION))
            assertTrue(service in shadow.getLocationUpdateListeners("network"))
            shadow.setProviderEnabled("gps", true)
            receiver.onReceive(service, Intent(LocationManager.PROVIDERS_CHANGED_ACTION))
            assertTrue(service in shadow.getLocationUpdateListeners("gps"))
            assertFalse(service in shadow.getLocationUpdateListeners("network"))
            assertTrue(Shadows.shadowOf(app).broadcastIntents.any { it.action == TrackingService.ACTION_TRACKING_STATE_CHANGED })
        } finally {
            MainActivity.trackingReceiverRegistered = false
            controller.destroy()
        }
    }

    @Test fun serviceStartedWhileLocationIsOffFollowsPlatformForegroundServiceRules() {
        val app = RuntimeEnvironment.getApplication()
        Shadows.shadowOf(app).grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            "${app.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
        )
        val manager = app.getSystemService(LocationManager::class.java)
        val shadow = Shadows.shadowOf(manager)
        shadow.setLocationEnabled(false)

        val controller = Robolectric.buildService(TrackingService::class.java).create()
        val service = controller.get()
        fun field(name: String) = TrackingService::class.java.getDeclaredField(name).apply { isAccessible = true }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                assertTrue(Shadows.shadowOf(service).isStoppedBySelf)
                assertFalse(field("ready").getBoolean(service))
                assertNull(Shadows.shadowOf(service).lastForegroundNotification)
            } else {
                assertFalse(Shadows.shadowOf(service).isStoppedBySelf)
                assertTrue(field("ready").getBoolean(service))
                assertNotNull(Shadows.shadowOf(service).lastForegroundNotification)
                assertEquals(android.app.Service.START_STICKY, service.onStartCommand(Intent(), 0, 1))

                shadow.setLocationEnabled(true)
                shadow.setProviderEnabled("gps", true)
                val receiver = field("providerReceiver").get(service) as BroadcastReceiver
                receiver.onReceive(service, Intent(LocationManager.MODE_CHANGED_ACTION))
                assertTrue(service in shadow.getLocationUpdateListeners("gps"))
            }
        } finally {
            controller.destroy()
        }
    }

    @Test fun locationOffRetainsRegistrationAndLocationOnRestoresIt() {
        val app = RuntimeEnvironment.getApplication()
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val manager = app.getSystemService(LocationManager::class.java)
        val shadow = Shadows.shadowOf(manager)
        shadow.removeProvider("fused")
        shadow.setLocationEnabled(true)
        shadow.setProviderEnabled("gps", true)
        val controller = Robolectric.buildService(TrackingService::class.java)
        val service = controller.get()
        fun field(name: String) = TrackingService::class.java.getDeclaredField(name).apply { isAccessible = true }
        field("locationManager").set(service, manager)
        field("ready").set(service, true)
        val receiver = field("providerReceiver").get(service) as BroadcastReceiver
        try {
            receiver.onReceive(service, Intent(LocationManager.MODE_CHANGED_ACTION))
            assertTrue(service in shadow.getLocationUpdateListeners("gps"))
            shadow.setLocationEnabled(false)
            receiver.onReceive(service, Intent(LocationManager.MODE_CHANGED_ACTION))
            assertTrue(service in shadow.getLocationUpdateListeners("gps"))
            shadow.setLocationEnabled(true)
            receiver.onReceive(service, Intent(LocationManager.MODE_CHANGED_ACTION))
            assertTrue(service in shadow.getLocationUpdateListeners("gps"))
        } finally { controller.destroy() }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], manifest = Config.NONE)
class LocationProviderRecovery31Test : LocationProviderRecoverySuite()

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class LocationProviderRecovery33Test : LocationProviderRecoverySuite()

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class LocationProviderRecovery35Test : LocationProviderRecoverySuite()

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], manifest = Config.NONE)
class LocationProviderRecovery37Test : LocationProviderRecoverySuite()
