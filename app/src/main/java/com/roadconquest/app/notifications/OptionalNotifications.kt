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
import com.roadconquest.app.ForumActivity
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
    internal fun sessionDigest(token: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))
        val hex = CharArray(digest.size * 2)
        for (index in digest.indices) {
            val byte = digest[index].toInt() and 0xff
            hex[index * 2] = "0123456789abcdef"[byte ushr 4]
            hex[index * 2 + 1] = "0123456789abcdef"[byte and 0x0f]
        }
        return String(hex)
    }
    @Volatile private var deletion: com.google.android.gms.tasks.Task<Void>? = null
    private var lastRegistrationBinding: String? = null
    private var lastRegistrationAt = 0L
    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun friendsEnabled(context: Context) = prefs(context).getBoolean("friends", true)
    fun friendPostsEnabled(context: Context) = prefs(context).getBoolean("friend_posts", true)
    private fun pushEnabled(context: Context) = friendsEnabled(context) || friendPostsEnabled(context)
    fun weeklyEnabled(context: Context) = prefs(context).getBoolean("weekly", true)
    fun permission(context: Context) = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun initialize(context: Context) {
        WeeklyReminderReceiver.schedule(context)
        if (!pushEnabled(context) || !permission(context)) return
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
            if (!pushEnabled(context) || !permission(context) || prefs(context).getString("binding", null) != binding) return
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
        if (!pushEnabled(context) || !permission(context)) return@synchronized null
        val settings = prefs(context)
        val digest = sessionDigest(sessionToken)
        val current = settings.getString("binding", null)
        if (current != null && settings.getString("session_binding", null) == digest) return@synchronized current
        UUID.randomUUID().toString().also { binding ->
            settings.edit().putString("binding", binding).putString("session_binding", digest).commit()
            context.getSystemService(NotificationManager::class.java).cancel(9101)
        }
    }
    private fun updatePushPreference(context: Context) {
        synchronized(displayLock) {
            // A changed choice must bypass the unchanged-FID registration cache.
            prefs(context).edit().remove("registered_at").commit()
            lastRegistrationBinding = null
        }
        if (pushEnabled(context)) initialize(context)
        else {
            disableLocal(context)
            val session = AccountStore.load(context)
            if (session != null) executor.execute { runCatching { AccountClient.removeNotificationDevice(session.token) } }
        }
    }
    fun setFriendsEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("friends", enabled).commit()
        if (!enabled) context.getSystemService(NotificationManager::class.java).cancel(9101)
        updatePushPreference(context)
    }
    fun setFriendPostsEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("friend_posts", enabled).commit()
        if (!enabled) context.getSystemService(NotificationManager::class.java).cancel(9104)
        updatePushPreference(context)
    }
    /** Called before discarding an account session, including corrupt/expired credentials. */
    fun disableLocal(context: Context) {
        synchronized(displayLock) {
            prefs(context).edit().remove("binding").remove("session_binding")
                .remove("registered_id_hash").remove("registered_binding")
                .remove("registered_at").commit()
            lastRegistrationBinding = null
            context.getSystemService(NotificationManager::class.java).cancel(9101)
            context.getSystemService(NotificationManager::class.java).cancel(9104)
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
        // Turn off all three notification types before changing the push registration.
        // Updating them individually could briefly re-register a device for the
        // still-enabled friend-post channel during a Delete all data operation.
        check(prefs(context).edit().putBoolean("friends", false)
            .putBoolean("friend_posts", false).putBoolean("weekly", false).commit())
        updatePushPreference(context)
        context.getSystemService(NotificationManager::class.java).cancel(9102)
        WeeklyReminderReceiver.schedule(context)
    }
    fun register(context: Context, token: String) {
        val app = context.applicationContext
        // Snapshot the account and random binding together; callbacks never display stale-account alerts.
        val session = AccountStore.load(app) ?: return
        val binding = prefs(app).getString("binding", null) ?: return
        executor.execute {
            if (deletion?.isComplete == false || !pushEnabled(app) || !permission(app) || AccountStore.load(app)?.token != session.token ||
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
            val requests = friendsEnabled(app)
            val posts = friendPostsEnabled(app)
            runCatching { AccountClient.registerNotificationDevice(session.token, token, binding, requests, posts) }
                .onSuccess {
                    if (AccountStore.load(app)?.token == session.token &&
                        friendsEnabled(app) == requests && friendPostsEnabled(app) == posts && permission(app) &&
                        settings.getString("binding", null) == binding) {
                        settings.edit().putString("registered_id_hash", idHash)
                            .putString("registered_binding", binding)
                            .putLong("registered_at", System.currentTimeMillis()).apply()
                    }
                }
        }
    }
    private fun acceptsPush(context: Context, kind: String?, binding: String?): Boolean {
        val session = AccountStore.load(context) ?: return false
        val enabled = when (kind) {
            "friend_request" -> friendsEnabled(context)
            "friend_post" -> friendPostsEnabled(context)
            else -> false
        }
        return enabled && binding != null && permission(context) &&
            binding == prefs(context).getString("binding", null) &&
            prefs(context).getString("session_binding", null) == sessionDigest(session.token)
    }
    internal fun acceptsFriend(context: Context, kind: String?, binding: String?) =
        kind == "friend_request" && acceptsPush(context, kind, binding)
    internal fun acceptsForumPost(context: Context, kind: String?, binding: String?) =
        kind == "friend_post" && acceptsPush(context, kind, binding)

    fun friend(context: Context, kind: String?, binding: String?) {
        if (!acceptsPush(context, kind, binding)) return
        // Never hold displayLock while acquiring AccountStore's lock: logout uses the reverse order.
        synchronized(displayLock) {
            if (binding != prefs(context).getString("binding", null)) return@synchronized
            when (kind) {
                "friend_request" -> if (friendsEnabled(context)) post(context, false)
                "friend_post" -> if (friendPostsEnabled(context)) postForum(context)
            }
        }
    }
    private fun postForum(context: Context) {
        if (!permission(context)) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("friend-forum-posts",
            "Friends' community posts", NotificationManager.IMPORTANCE_DEFAULT))
        val content = PendingIntent.getActivity(context, 9104,
            Intent(context, ForumActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        manager.notify(9104, NotificationCompat.Builder(context, "friend-forum-posts")
            .setSmallIcon(R.drawable.ic_car)
            .setContentTitle("A friend posted in Road Conquest")
            .setContentText("Open the community to see what's new.")
            .setContentIntent(content).setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build())
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
