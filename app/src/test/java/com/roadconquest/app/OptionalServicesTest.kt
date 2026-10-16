package com.roadconquest.app

import android.app.Application
import com.roadconquest.app.notifications.OptionalNotifications
import com.roadconquest.app.notifications.WeeklyReminderReceiver
import com.roadconquest.app.services.ServiceConfig
import com.roadconquest.app.diagnostics.CrashReporting
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.ZonedDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37], manifest = Config.NONE, application = Application::class)
class OptionalServicesTest {
    @Test fun defaultsDoNotOptIntoFirebaseOrNotifications() {
        val app = RuntimeEnvironment.getApplication()
        assertFalse(OptionalNotifications.friendsEnabled(app))
        assertFalse(OptionalNotifications.weeklyEnabled(app))
        assertFalse(CrashReporting.enabled(app))
        assertFalse(OptionalNotifications.acceptsFriend(app, "friend_request", "stale"))
        OptionalNotifications.setFriendsEnabled(app, true)
        assertFalse(OptionalNotifications.friendsEnabled(app)) // No signed-in account.
    }
    @Test fun queuedMessagesCannotDisplayAfterConsentOrAccountIsCleared() {
        val app = RuntimeEnvironment.getApplication()
        val prefs = app.getSharedPreferences("roadconquest_notifications", 0)
        prefs.edit().putBoolean("friends", true).putString("binding", "old-binding").commit()
        assertFalse(OptionalNotifications.acceptsFriend(app, "friend_request", "other-binding"))
        assertFalse(OptionalNotifications.acceptsFriend(app, "friend_request", "old-binding")) // Signed out.
        assertFalse(OptionalNotifications.acceptsFriend(app, "unknown", "old-binding"))
        OptionalNotifications.disableLocal(app)
        assertFalse(OptionalNotifications.friendsEnabled(app))
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
