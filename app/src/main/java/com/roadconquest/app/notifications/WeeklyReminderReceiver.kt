package com.roadconquest.app.notifications

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.time.DayOfWeek
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters

/** Local inexact Monday reminder: no cloud job, GPS upload, or exact-alarm permission. */
class WeeklyReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION || !OptionalNotifications.weeklyEnabled(context)) return
        OptionalNotifications.post(context, true)
        schedule(context)
    }
    companion object {
        private const val ACTION = "com.roadconquest.app.WEEKLY_REMINDER"
        internal fun nextReminder(now: ZonedDateTime): ZonedDateTime {
            val next = now.with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY)).withHour(18).withMinute(0).withSecond(0).withNano(0)
            return if (next.isAfter(now)) next else next.plusWeeks(1)
        }
        fun schedule(context: Context) {
            val pending = PendingIntent.getBroadcast(context, 9102, Intent(context, WeeklyReminderReceiver::class.java).setAction(ACTION),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val alarm = context.getSystemService(AlarmManager::class.java)
            alarm.cancel(pending)
            if (OptionalNotifications.weeklyEnabled(context) && OptionalNotifications.permission(context)) {
                alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextReminder(ZonedDateTime.now()).toInstant().toEpochMilli(), pending)
            }
        }
    }
}
