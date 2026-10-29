package com.roadconquest.app

import android.Manifest
import android.app.Service
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import android.os.Build
import com.roadconquest.app.util.ForegroundSession
import com.roadconquest.app.util.Prefs
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 33, 37], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
class NotificationStopTest {
    @Before fun setup() {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("roadconquest_preferences", 0).edit().clear().commit()
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION, "${app.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        Shadows.shadowOf(app.getSystemService(LocationManager::class.java)).setLocationEnabled(true)
    }

    @Test fun liveUiBroadcastIsSkippedWhenMainMapReceiverIsStopped() {
        val app = RuntimeEnvironment.getApplication()
        val controller = Robolectric.buildService(TrackingService::class.java).create()
        val service = controller.get()
        val send = TrackingService::class.java.getDeclaredMethod(
            "sendLocationUpdate",
            Location::class.java,
            Double::class.javaPrimitiveType
        ).apply { isAccessible = true }
        val location = Location("gps").apply {
            latitude = 40.0
            longitude = -74.0
            accuracy = 5f
            time = System.currentTimeMillis()
        }
        try {
            MainActivity.trackingReceiverRegistered = false
            val before = Shadows.shadowOf(app).broadcastIntents
                .count { it.action == TrackingService.ACTION_LOCATION_UPDATE }
            send.invoke(service, location, 90.0)
            assertEquals(
                before,
                Shadows.shadowOf(app).broadcastIntents
                    .count { it.action == TrackingService.ACTION_LOCATION_UPDATE }
            )

            MainActivity.trackingReceiverRegistered = true
            send.invoke(service, location, 90.0)
            assertEquals(
                before + 1,
                Shadows.shadowOf(app).broadcastIntents
                    .count { it.action == TrackingService.ACTION_LOCATION_UPDATE }
            )
        } finally {
            MainActivity.trackingReceiverRegistered = false
            controller.destroy()
        }
    }

    @Test fun notificationStopsWithoutOpeningAnActivityAndBlocksStickyAndBootRestarts() {
        val app = RuntimeEnvironment.getApplication()
        val controller = Robolectric.buildService(TrackingService::class.java).create()
        val service = controller.get()
        try {
            assertEquals(Service.START_STICKY, service.onStartCommand(Intent(), 0, 1))
            val notification = Shadows.shadowOf(service).lastForegroundNotification
            val action = notification.actions.single()
            assertEquals("Stop tracking", action.title.toString())
            val pending = Shadows.shadowOf(action.actionIntent)
            assertTrue(pending.isServiceIntent)
            assertTrue(pending.isImmutable)
            assertEquals(TrackingService::class.java.name, pending.savedIntent.component!!.className)
            assertEquals(TrackingService.ACTION_STOP_UNTIL_OPEN, pending.savedIntent.action)
            assertEquals(Service.START_NOT_STICKY, service.onStartCommand(pending.savedIntent, 0, 2))
            assertTrue(Prefs.isTrackingPaused(app))
            assertTrue(Shadows.shadowOf(service).isStoppedBySelf)
            assertTrue(Shadows.shadowOf(service).isForegroundStopped)
            assertNull(Shadows.shadowOf(app).nextStartedActivity)
            for (event in listOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)) {
                BootReceiver().onReceive(app, Intent(event))
                assertNull(Shadows.shadowOf(app).nextStartedService)
            }
            assertEquals(Service.START_NOT_STICKY, service.onStartCommand(null, 0, 3))
        } finally { controller.destroy() }
    }

    @Test fun locationOffForegroundServiceFollowsPlatformPrerequisites() {
        val app = RuntimeEnvironment.getApplication()
        Shadows.shadowOf(app.getSystemService(LocationManager::class.java)).setLocationEnabled(false)
        val controller = Robolectric.buildService(TrackingService::class.java).create()
        try {
            val service = controller.get()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                assertTrue(Shadows.shadowOf(service).isStoppedBySelf)
                assertNull(Shadows.shadowOf(service).lastForegroundNotification)
            } else {
                val notification = Shadows.shadowOf(service).lastForegroundNotification
                assertNotNull(notification)
                assertEquals(
                    "Road Conquest is ready",
                    notification.extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString()
                )
                assertEquals(
                    "Waiting for Android Location to be turned on",
                    notification.extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString()
                )
            }
        } finally {
            controller.destroy()
        }
    }

    @Test fun pausedServiceCannotSubscribeToGpsUntilTheAppResumesTracking() {
        val app = RuntimeEnvironment.getApplication()
        val manager = app.getSystemService(LocationManager::class.java)
        Prefs.setTrackingPaused(app, true)
        val stopped = Robolectric.buildService(TrackingService::class.java).create()
        try {
            assertTrue(Shadows.shadowOf(stopped.get()).isStoppedBySelf)
            assertNull(Shadows.shadowOf(stopped.get()).lastForegroundNotification)
            assertEquals(Service.START_NOT_STICKY, stopped.get().onStartCommand(null, 0, 1))
            assertTrue(Shadows.shadowOf(manager).getLocationUpdateListeners("gps").isEmpty())
        } finally { stopped.destroy() }
        Prefs.setTrackingPaused(app, false)
        val resumed = Robolectric.buildService(TrackingService::class.java).create()
        try {
            assertEquals(Service.START_STICKY, resumed.get().onStartCommand(Intent(), 0, 1))
            assertNotNull(Shadows.shadowOf(resumed.get()).lastForegroundNotification)
        } finally { resumed.destroy() }
    }

    @Test fun shadeDismissalScreenChangesAndRecreationDoNotCountAsReopening() {
        val session = ForegroundSession()
        assertTrue(session.onStart()) // Main opens.
        // A notification shade causes pause/resume, with no activity start/stop.
        assertFalse(session.onStart()) // Settings starts before Main stops.
        session.onStop(false)
        session.onStop(true) // Settings rotates/recreates while visible.
        assertFalse(session.onStart())
        assertFalse(session.onStart()) // Main returns before Settings stops.
        session.onStop(false)
        session.onStop(false) // Whole app leaves the screen.
        assertTrue(session.onStart())
    }

    @Test fun secondaryScreenReopenResumesButInternalNavigationAndDeletionDoNot() {
        val app = RuntimeEnvironment.getApplication()
        val session = ForegroundSession()
        Prefs.setManualOnly(app, true)
        Prefs.setTrackingPaused(app, true)
        assertTrue(session.onStart(app))
        assertFalse(Prefs.isTrackingPaused(app))
        assertEquals(TrackingService::class.java.name, Shadows.shadowOf(app).nextStartedService.component!!.className)

        Prefs.setTrackingPaused(app, true)
        assertFalse(session.onStart(app))
        assertTrue("Internal navigation must retain the notification stop", Prefs.isTrackingPaused(app))
        assertNull(Shadows.shadowOf(app).nextStartedService)
        session.onStop(false)
        session.onStop(false)
        Prefs.setDeviceDataDeletionPending(app, true)
        assertTrue(session.onStart(app))
        assertTrue(Prefs.isTrackingPaused(app))
        assertNull(Shadows.shadowOf(app).nextStartedService)
    }

    @Test fun secondaryScreenReopenReleasesPauseWhenLocationCannotStartYet() {
        val app = RuntimeEnvironment.getApplication()
        Shadows.shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        Prefs.setTrackingPaused(app, true)
        ForegroundSession().onStart(app)
        assertFalse("Map permission recovery must not remain blocked by the old pause", Prefs.isTrackingPaused(app))
        assertNull(Shadows.shadowOf(app).nextStartedService)
    }

    @Test fun unrelatedActionDoesNotPauseOrStopTracking() {
        val app = RuntimeEnvironment.getApplication()
        val controller = Robolectric.buildService(TrackingService::class.java).create()
        try {
            val service = controller.get()
            assertEquals(Service.START_STICKY, service.onStartCommand(Intent("other"), 0, 1))
            assertFalse(Prefs.isTrackingPaused(app))
            assertFalse(Shadows.shadowOf(service).isStoppedBySelf)
        } finally { controller.destroy() }
    }

    @Test fun pausedTrackingDoesNotAutoResumeWhileDeviceDeletionIsPending() {
        val app = RuntimeEnvironment.getApplication()
        Prefs.setTrackingPaused(app, true)
        Prefs.setManualOnly(app, true)
        assertTrue("Manual notification stops still resume on app reopen", Prefs.shouldResumePausedTracking(app))

        Prefs.setDeviceDataDeletionPending(app, true)
        assertFalse(Prefs.shouldResumePausedTracking(app))

        Prefs.setDeviceDataDeletionPending(app, false)
        assertTrue(Prefs.shouldResumePausedTracking(app))
    }


    @Test fun pendingDeletionBlocksServiceAndBootEvenBeforeOtherStopFlagsPersist() {
        val app = RuntimeEnvironment.getApplication()
        Prefs.setManualOnly(app, false)
        Prefs.setTrackingPaused(app, false)
        Prefs.markEverStarted(app)
        Prefs.setDeviceDataDeletionPending(app, true)

        val stopped = Robolectric.buildService(TrackingService::class.java).create()
        try {
            assertTrue(Shadows.shadowOf(stopped.get()).isStoppedBySelf)
            assertNull(Shadows.shadowOf(stopped.get()).lastForegroundNotification)
            assertEquals(Service.START_NOT_STICKY, stopped.get().onStartCommand(Intent(), 0, 1))
        } finally {
            stopped.destroy()
        }

        BootReceiver().onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertNull(Shadows.shadowOf(app).nextStartedService)
        Prefs.setDeviceDataDeletionPending(app, false)
    }

}
