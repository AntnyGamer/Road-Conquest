package com.roadconquest.app.diagnostics

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.crashlytics.FirebaseCrashlytics

/** Crash reports are opt-in. Never attach usernames, tokens, GPS, URLs or application logs. */
object CrashReporting {
    private const val PREFERENCES = "roadconquest_diagnostics"
    fun enabled(context: Context) = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        .getBoolean("crash_reports", false)
    fun initialize(context: Context) {
        if (!enabled(context) && FirebaseApp.getApps(context).isEmpty()) return
        if (FirebaseApp.getApps(context).isEmpty() && FirebaseApp.initializeApp(context) == null) return
        com.google.firebase.messaging.FirebaseMessaging.getInstance().isAutoInitEnabled = false
        apply(enabled(context))
    }
    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().putBoolean("crash_reports", enabled).apply()
        if (enabled) initialize(context)
        else if (FirebaseApp.getApps(context).isNotEmpty()) apply(false)
    }
    private fun apply(enabled: Boolean) {
        val crashlytics = FirebaseCrashlytics.getInstance()
        crashlytics.setCrashlyticsCollectionEnabled(enabled)
        if (!enabled) crashlytics.deleteUnsentReports()
    }
    fun clear(context: Context) {
        setEnabled(context, false)
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit()
    }
}
