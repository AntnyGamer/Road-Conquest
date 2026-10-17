package com.roadconquest.app.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.roadconquest.app.AccountActivity
import com.roadconquest.app.ChallengesActivity
import com.roadconquest.app.R
import com.roadconquest.app.account.AccountClient
import com.roadconquest.app.account.AccountStore
import com.roadconquest.app.diagnostics.CrashReporting
import java.util.UUID
import java.util.concurrent.Executors

object OptionalNotifications {
    private const val PREFS = "roadconquest_notifications"
    private const val REGISTER_REFRESH_MS = 6 * 60 * 60_000L
    internal fun needsDeviceRegistration(
        cachedIdHash: String?, cachedBinding: String?, cachedAt: Long,
        idHash: String, binding: String, now: Long
    ): Boolean = cachedIdHash != idHash || cachedBinding != binding ||
        now - cachedAt !in 0 until REGISTER_REFRESH_MS
    private val executor = Executors.newSingleThreadExecutor()
    private val displayLock = Any()
    private fun sessionDigest(token: String) = java.security.MessageDigest.getInstance("SHA-256")
        .digest(token.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    @Volatile private var deletion: com.google.android.gms.tasks.Task<Void>? = null
    private var lastRegistrationBinding: String? = null
    private var lastRegistrationAt = 0L
    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun friendsEnabled(context: Context) = prefs(context).getBoolean("friends", true)
    fun weeklyEnabled(context: Context) = prefs(context).getBoolean("weekly", true)
    fun permission(context: Context) = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun initialize(context: Context) {
        WeeklyReminderReceiver.schedule(context)
        if (!friendsEnabled(context) || !permission(context)) return
        val session = AccountStore.load(context) ?: return
        deletion?.takeUnless { it.isComplete }?.let { pending ->
            pending.addOnCompleteListener { initialize(context.applicationContext) }
            return
        }
        val binding = prepareBinding(context, session.token) ?: return
        if (AccountStore.load(context)?.token != session.token) return
        if (FirebaseApp.getApps(context).isEmpty() && FirebaseApp.initializeApp(context) == null) return
        CrashReporting.initialize(context) // Separate crash consent remains authoritative.
        synchronized(displayLock) {
            if (!friendsEnabled(context) || !permission(context) || prefs(context).getString("binding", null) != binding) return
            val now = SystemClock.elapsedRealtime()
            if (lastRegistrationBinding == binding && now - lastRegistrationAt < 5 * 60_000L) return
            lastRegistrationBinding = binding
            lastRegistrationAt = now
        }
        FirebaseMessaging.getInstance().apply {
            // Explicit registration follows the saved setting, sign-in and Android permission.
            isAutoInitEnabled = false
            register().addOnFailureListener {
                synchronized(displayLock) {
                    if (lastRegistrationBinding == binding) lastRegistrationBinding = null
                }
            } // onRegistered delivers the current FID after registration succeeds.
        }
    }
    /** A new login gets a new binding without changing the user's saved notification choice. */
    internal fun prepareBinding(context: Context, sessionToken: String): String? = synchronized(displayLock) {
        if (!friendsEnabled(context) || !permission(context)) return@synchronized null
        val settings = prefs(context)
        val digest = sessionDigest(sessionToken)
        val current = settings.getString("binding", null)
        if (current != null && settings.getString("session_binding", null) == digest) return@synchronized current
        UUID.randomUUID().toString().also { binding ->
            settings.edit().putString("binding", binding).putString("session_binding", digest).commit()
            context.getSystemService(NotificationManager::class.java).cancel(9101)
        }
    }
    fun setFriendsEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("friends", enabled).commit()
        if (enabled) {
            initialize(context)
        } else {
            disableLocal(context)
            val session = AccountStore.load(context)
            if (session != null) executor.execute { runCatching { AccountClient.removeNotificationDevice(session.token) } }
        }
    }
    /** Called before discarding an account session, including corrupt/expired credentials. */
    fun disableLocal(context: Context) {
        synchronized(displayLock) {
            prefs(context).edit().remove("binding").remove("session_binding")
                .remove("registered_id_hash").remove("registered_binding")
                .remove("registered_at").commit()
            lastRegistrationBinding = null
            context.getSystemService(NotificationManager::class.java).cancel(9101)
        }
        if (FirebaseApp.getApps(context).isNotEmpty()) {
            FirebaseMessaging.getInstance().apply { isAutoInitEnabled = false; deletion = unregister() }
        }
    }
    fun setWeeklyEnabled(context: Context, enabled: Boolean) {
        synchronized(displayLock) {
            prefs(context).edit().putBoolean("weekly", enabled).commit()
            if (!enabled) context.getSystemService(NotificationManager::class.java).cancel(9102)
        }
        WeeklyReminderReceiver.schedule(context)
    }
    fun clear(context: Context) {
        setFriendsEnabled(context, false)
        setWeeklyEnabled(context, false)
    }
    fun register(context: Context, token: String) {
        val app = context.applicationContext
        // Snapshot the account and random binding together; callbacks never display stale-account alerts.
        val session = AccountStore.load(app) ?: return
        val binding = prefs(app).getString("binding", null) ?: return
        executor.execute {
            if (deletion?.isComplete == false || !friendsEnabled(app) || !permission(app) || AccountStore.load(app)?.token != session.token ||
                prefs(app).getString("session_binding", null) != sessionDigest(session.token) ||
                prefs(app).getString("binding", null) != binding) return@execute
            // The same FID can be emitted again on every app start. Re-registering
            // it immediately would spend one rate-limit reservation, a session
            // lookup and a database transaction without changing delivery.
            // Refresh well before the server's 30-day registration expiry; a
            // changed FID, login binding or consent bypasses the cache.
            val settings = prefs(app)
            val idHash = sessionDigest(token)
            val now = System.currentTimeMillis()
            if (!needsDeviceRegistration(
                    settings.getString("registered_id_hash", null),
                    settings.getString("registered_binding", null),
                    settings.getLong("registered_at", 0L),
                    idHash, binding, now
                )) return@execute
            runCatching { AccountClient.registerNotificationDevice(session.token, token, binding) }
                .onSuccess {
                    if (AccountStore.load(app)?.token == session.token && friendsEnabled(app) && permission(app) &&
                        settings.getString("binding", null) == binding) {
                        settings.edit().putString("registered_id_hash", idHash)
                            .putString("registered_binding", binding)
                            .putLong("registered_at", System.currentTimeMillis()).apply()
                    }
                }
        }
    }
    internal fun acceptsFriend(context: Context, kind: String?, binding: String?): Boolean {
        val session = AccountStore.load(context) ?: return false
        return kind == "friend_request" && binding != null && friendsEnabled(context) && permission(context) &&
            binding == prefs(context).getString("binding", null) &&
            prefs(context).getString("session_binding", null) == sessionDigest(session.token)
    }

    fun friend(context: Context, kind: String?, binding: String?) {
        if (!acceptsFriend(context, kind, binding)) return
        // Never hold displayLock while acquiring AccountStore's lock: logout uses the reverse order.
        synchronized(displayLock) {
            if (friendsEnabled(context) && binding == prefs(context).getString("binding", null)) post(context, false)
        }
    }
    internal fun post(context: Context, weekly: Boolean) = synchronized(displayLock) {
        if (!permission(context) || weekly && !weeklyEnabled(context)) return@synchronized
        val channel = if (weekly) "weekly-challenges" else "friend-requests"
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(channel,
            if (weekly) "Weekly challenge reminders" else "Friend requests", NotificationManager.IMPORTANCE_DEFAULT))
        val target = if (weekly) ChallengesActivity::class.java else AccountActivity::class.java
        val id = if (weekly) 9102 else 9101
        val content = PendingIntent.getActivity(context, id, Intent(context, target), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        manager.notify(id, NotificationCompat.Builder(context, channel).setSmallIcon(R.drawable.ic_car)
            .setContentTitle(if (weekly) "Your weekly challenges are ready" else "Road Conquest friend request")
            .setContentText(if (weekly) "Open Challenges to see this week's goals." else "Open your account to review pending requests.")
            .setContentIntent(content).setAutoCancel(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build())
    }
}
