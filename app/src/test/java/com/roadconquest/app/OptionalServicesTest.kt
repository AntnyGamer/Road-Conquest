package com.roadconquest.app

import android.app.Application
import android.app.AlarmManager
import android.Manifest
import com.google.firebase.FirebaseApp
import com.roadconquest.app.account.AccountStore
import com.roadconquest.app.notifications.OptionalNotifications
import com.roadconquest.app.notifications.WeeklyReminderReceiver
import com.roadconquest.app.services.ServiceConfig
import com.roadconquest.app.diagnostics.CrashReporting
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.time.ZonedDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37], manifest = Config.NONE, application = Application::class)
class OptionalServicesTest {
    @Before fun resetPreferences() {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("roadconquest_notifications", 0).edit().clear().commit()
        app.getSharedPreferences("roadconquest_account", 0).edit().clear().commit()
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }
    @Test fun defaultsScheduleWeeklyReminderWithoutStartingFirebaseWhileSignedOut() {
        val app = RuntimeEnvironment.getApplication()
        assertTrue(OptionalNotifications.friendsEnabled(app))
        assertTrue(OptionalNotifications.weeklyEnabled(app))
        assertFalse(CrashReporting.enabled(app))
        assertFalse(OptionalNotifications.acceptsFriend(app, "friend_request", "stale"))
        OptionalNotifications.initialize(app)
        assertEquals(1, Shadows.shadowOf(app.getSystemService(AlarmManager::class.java)).scheduledAlarms.size)
        assertTrue(FirebaseApp.getApps(app).isEmpty())
    }
    @Test fun explicitOptOutSurvivesStartupLogoutAndLaterBindingAttempts() {
        val app = RuntimeEnvironment.getApplication()
        OptionalNotifications.setFriendsEnabled(app, false)
        OptionalNotifications.setWeeklyEnabled(app, false)
        OptionalNotifications.initialize(app)
        AccountStore.clear(app)
        assertFalse(OptionalNotifications.friendsEnabled(app))
        assertFalse(OptionalNotifications.weeklyEnabled(app))
        assertNull(OptionalNotifications.prepareBinding(app, "a-new-login"))
        assertTrue(Shadows.shadowOf(app.getSystemService(AlarmManager::class.java)).scheduledAlarms.isEmpty())
    }
    @Test @Config(sdk = [37]) fun deniedPermissionBlocksDeliveryWithoutChangingSavedChoices() {
        val app = RuntimeEnvironment.getApplication()
        Shadows.shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        OptionalNotifications.setFriendsEnabled(app, true)
        OptionalNotifications.setWeeklyEnabled(app, true)
        assertTrue(OptionalNotifications.friendsEnabled(app))
        assertTrue(OptionalNotifications.weeklyEnabled(app))
        assertNull(OptionalNotifications.prepareBinding(app, "a-login"))
        val alarms = Shadows.shadowOf(app.getSystemService(AlarmManager::class.java))
        assertTrue(alarms.scheduledAlarms.isEmpty())
        assertTrue(FirebaseApp.getApps(app).isEmpty())
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        OptionalNotifications.initialize(app)
        assertEquals(1, alarms.scheduledAlarms.size)
        assertNotNull(OptionalNotifications.prepareBinding(app, "a-login"))
        assertFalse(CrashReporting.enabled(app))
    }
    @Test fun logoutClearsBindingAndNewLoginGetsADifferentOne() {
        val app = RuntimeEnvironment.getApplication()
        val first = OptionalNotifications.prepareBinding(app, "first-login")
        assertNotNull(first)
        assertEquals(first, OptionalNotifications.prepareBinding(app, "first-login"))
        assertNotEquals(first, OptionalNotifications.prepareBinding(app, "second-login"))
        AccountStore.clear(app)
        assertTrue(OptionalNotifications.friendsEnabled(app))
        val prefs = app.getSharedPreferences("roadconquest_notifications", 0)
        assertNull(prefs.getString("binding", null))
        assertNull(prefs.getString("session_binding", null))
        assertNotEquals(first, OptionalNotifications.prepareBinding(app, "first-login"))
    }
    @Test fun unchangedDeviceRegistrationSkipsRedundantServerWritesButRefreshesSafely() {
        val now = 1_800_000_000_000L
        val id = "same-id-sha256"
        val binding = "same-session-binding"
        fun due(savedId: String?, savedBinding: String?, savedAt: Long, currentId: String = id,
                currentBinding: String = binding, currentTime: Long = now) =
            OptionalNotifications.needsDeviceRegistration(
                savedId, savedBinding, savedAt, currentId, currentBinding, currentTime)
        assertTrue(due(null, null, 0L))
        assertFalse(due(id, binding, now - 30_000L))
        assertFalse(due(id, binding, now - 5 * 60 * 60_000L))
        assertTrue(due(id, binding, now - 6 * 60 * 60_000L))
        assertTrue(due(id, binding, now, currentId = "rotated-id-sha256"))
        assertTrue(due(id, binding, now, currentBinding = "new-login"))
        assertTrue(due(id, binding, now + 60_000L)) // Clock moved backward.
    }

    @Test fun disablingNotificationsClearsOnlySuccessfulDeviceRegistrationCache() {
        val app = RuntimeEnvironment.getApplication()
        val settings = app.getSharedPreferences("roadconquest_notifications", 0)
        settings.edit().putString("registered_id_hash", "hash")
            .putString("registered_binding", "binding").putLong("registered_at", 123L).commit()
        OptionalNotifications.disableLocal(app)
        assertNull(settings.getString("registered_id_hash", null))
        assertNull(settings.getString("registered_binding", null))
        assertFalse(settings.contains("registered_at"))
        assertTrue(OptionalNotifications.friendsEnabled(app))
    }

    @Test fun queuedMessagesCannotDisplayAfterConsentOrAccountIsCleared() {
        val app = RuntimeEnvironment.getApplication()
        val prefs = app.getSharedPreferences("roadconquest_notifications", 0)
        prefs.edit().putBoolean("friends", true).putString("binding", "old-binding").commit()
        assertFalse(OptionalNotifications.acceptsFriend(app, "friend_request", "other-binding"))
        assertFalse(OptionalNotifications.acceptsFriend(app, "friend_request", "old-binding")) // Signed out.
        assertFalse(OptionalNotifications.acceptsFriend(app, "unknown", "old-binding"))
        OptionalNotifications.disableLocal(app)
        assertTrue(OptionalNotifications.friendsEnabled(app)) // Logout preserves the saved choice.
        assertNull(prefs.getString("binding", null))
    }
    @Test fun remindersScheduleTheNextMondayWithoutExactAlarmPermissions() {
        assertEquals("2026-10-19T18:00-04:00[America/New_York]", WeeklyReminderReceiver.nextReminder(
            ZonedDateTime.parse("2026-10-16T23:00-04:00[America/New_York]")).toString())
        assertEquals("2026-10-26T18:00-04:00[America/New_York]", WeeklyReminderReceiver.nextReminder(
            ZonedDateTime.parse("2026-10-19T18:00-04:00[America/New_York]")).toString())
        assertEquals(18, WeeklyReminderReceiver.nextReminder(ZonedDateTime.parse("2026-10-31T12:00-04:00[America/New_York]")).hour)
    }
    @Test fun publicConfigCannotChangeHostsOrConvertStringsToSwitches() {
        val parsed = ServiceConfig.parse("""{"version":1,"providers":{"openfreemap":false,"geoapify-tiles":"false","url":"https://evil.example"}}""")
        assertEquals(false, parsed["openfreemap"])
        assertEquals(true, parsed["geoapify-tiles"])
        assertFalse(parsed.containsKey("url"))
        assertTrue(runCatching { ServiceConfig.parse("""{"version":2,"providers":{}}""") }.isFailure)
    }
}
