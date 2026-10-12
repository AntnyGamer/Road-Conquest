package com.roadconquest.app.account

import android.content.Context
import android.location.Location
import android.os.SystemClock
import android.util.Base64
import com.google.android.gms.tasks.Tasks
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.StandardIntegrityManager
import com.roadconquest.app.util.Prefs
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Only live fixes enter competition. Saved history/imports and local totals are never uploaded. */
class VerifiedDriving(context: Context) {
    private val context = context.applicationContext
    private val executor = Executors.newSingleThreadScheduledExecutor()
    private val busy = AtomicBoolean(false)
    @Volatile private var drainTask: java.util.concurrent.ScheduledFuture<*>? = null
    private val queueLock = Any()
    private val pendingLocations = ArrayDeque<Location>()
    private val points = mutableListOf<JSONObject>()
    private var accountToken: String? = null
    private var consentVersion = -1L
    private var run: JSONObject? = null
    private var provider: StandardIntegrityManager.StandardIntegrityTokenProvider? = null
    private var pendingEvidence: String? = null
    private var retryAfter = 0L
    @Volatile private var accepting = true
    @Volatile private var closed = false

    // Location callbacks only copy into a bounded in-memory queue. Slow account or Play
    // Integrity calls cannot make the foreground service drop every fix received meanwhile.
    fun offer(location: Location) {
        if (!accepting || !Prefs.isDriveVerificationEnabled(context) || !AccountClient.isConfigured()) return
        // Tracking intentionally listens to fused + GPS for local reliability, but verified
        // scoring only ever accepts live GPS fixes with usable accuracy/speed metadata. Reject
        // permanently-ineligible fixes before copying/enqueueing them; accept() repeats these
        // checks as defense in depth. Do not move the age check here because a queued fix's age
        // is intentionally evaluated at consumption time.
        if (location.isMock || location.provider != "gps" ||
            !location.hasAccuracy() || location.accuracy !in 0.01f..25f ||
            !location.hasSpeed() || location.speed !in 0f..65f ||
            location.elapsedRealtimeNanos <= 0L
        ) return
        synchronized(queueLock) {
            if (!accepting) return
            if (pendingLocations.size >= MAX_BUFFERED_FIXES) pendingLocations.removeFirst()
            pendingLocations.addLast(Location(location))
        }
        scheduleDrain()
    }

    private fun scheduleDrain() {
        if (!accepting || !busy.compareAndSet(false, true)) return
        val delay = (retryAfter - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        val submitted = runCatching {
            drainTask = executor.schedule({
                try {
                    drainQueuedLocations()
                } finally {
                    drainTask = null
                    busy.set(false)
                    if (accepting && synchronized(queueLock) { pendingLocations.isNotEmpty() }) {
                        scheduleDrain()
                    }
                }
            }, delay, TimeUnit.MILLISECONDS)
        }.isSuccess
        if (!submitted) busy.set(false)
    }

    private fun drainQueuedLocations() {
        while (true) {
            val location = synchronized(queueLock) {
                if (pendingLocations.isEmpty()) null else pendingLocations.removeFirst()
            } ?: return
            try {
                accept(location)
            } catch (_: Exception) {
                // Keep a committed-but-unacknowledged batch and every still-fresh queued fix.
                // Retry after a short cooldown; never turn an account/Integrity outage into a
                // permanent hole in otherwise valid competitive mileage.
                provider = null
                retryAfter = SystemClock.elapsedRealtime() + RETRY_AFTER_FAILURE_MS
                synchronized(queueLock) {
                    if (pendingLocations.size >= MAX_BUFFERED_FIXES) pendingLocations.removeLast()
                    pendingLocations.addFirst(location)
                }
                return
            }
        }
    }

    private fun accept(location: Location) {
        val session = AccountStore.load(context)
        if (session == null || !Prefs.isDriveVerificationEnabled(context)) { reset(); return }
        val currentConsent = Prefs.driveVerificationConsentVersion(context)
        if (accountToken == null) {
            // State is empty after construction/reset, so binding the current account must not
            // discard newer live fixes already waiting behind this one in the queue.
            accountToken = session.token
            consentVersion = currentConsent
        } else if (accountToken != session.token || consentVersion != currentConsent) {
            reset()
            accountToken = session.token
            consentVersion = currentConsent
        }
        if (SystemClock.elapsedRealtime() < retryAfter) return
        val mocked = location.isMock
        val age = (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000L
        val freshValidFix = !mocked && location.provider == "gps" && location.hasAccuracy() &&
            location.accuracy in 0.01f..25f && location.hasSpeed() && location.speed in 0f..65f &&
            location.elapsedRealtimeNanos > 0L && age in 0..MAX_BUFFERED_LOCATION_AGE_MS
        // A failed submission queues the triggering fix for a 30-second retry. By then
        // that fix is normally too old to contribute NEW evidence, but its previously
        // prepared batch must still be acknowledged. Never let fix age discard the batch.
        if (!canResumePreparedEvidence(pendingEvidence != null, freshValidFix)) return
        if (run == null || provider == null) {
            val status = AccountClient.competition()
            if (!status.optBoolean("available")) { reset(); retryAfter = SystemClock.elapsedRealtime() + 300_000L; return }
            val project = status.getString("cloud_project").toLong()
            provider = Tasks.await(
                IntegrityManagerFactory.createStandard(context).prepareIntegrityToken(
                    StandardIntegrityManager.PrepareIntegrityTokenRequest.builder().setCloudProjectNumber(project).build()
                ), 10, TimeUnit.SECONDS
            )
            if (run == null) {
                run = AccountClient.startVerifiedDrive(session.token)
                return // Only fixes captured after issuance of the server challenge are eligible.
            }
        }
        // Retry the same evidence rather than rebuilding a batch that may already have earned credit.
        if (pendingEvidence != null) {
            if (System.currentTimeMillis() - JSONObject(pendingEvidence!!).getJSONArray("fixes").getJSONObject(0).getLong("time") > 120_000L) {
                reset(); return
            }
            // Finish the exact previously-committed evidence first, then continue with this
            // live fix. If this fix was already the old batch's last point, the timestamp
            // de-duplication below drops it; if it arrived while the retry was pending, it is
            // preserved instead of creating an avoidable hole in verified mileage.
            submit(session.token)
        }
        // An aged-out trigger may retry an existing batch, but may not be added
        // as a newly captured live fix to another batch.
        if (!freshValidFix) return
        val point = JSONObject().put("lat", location.latitude).put("lon", location.longitude)
            .put("accuracy", location.accuracy.toDouble()).put("speed", location.speed.toDouble())
            .put("time", location.time).put("elapsed", location.elapsedRealtimeNanos / 1_000_000L).put("mock", false)
        val previous = points.lastOrNull()
        if (previous != null) {
            val elapsed = point.getLong("time") - previous.getLong("time")
            if (elapsed < 2_000L) return
            if (elapsed > 15_000L) points.clear()
        }
        points += point
        if (points.size < 2 || (points.size < 24 &&
            point.getLong("time") - points.first().getLong("time") < 60_000L)) return
        prepareEvidence()
        submit(session.token)
    }

    private fun prepareEvidence() {
        val challenge = requireNotNull(run)
        pendingEvidence = JSONObject().put("run", challenge.getString("run")).put("nonce", challenge.getString("nonce"))
            .put("sequence", challenge.getInt("sequence")).put("fixes", JSONArray(points)).toString()
    }

    private fun submit(token: String, finishing: Boolean = false) {
        val evidence = requireNotNull(pendingEvidence)
        val hash = Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(evidence.toByteArray(Charsets.UTF_8)),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val integrity = Tasks.await(requireNotNull(provider).request(
            StandardIntegrityManager.StandardIntegrityTokenRequest.builder().setRequestHash(hash).build()
        ), 10, TimeUnit.SECONDS).token()
        // Recheck consent/account after the asynchronous attestation, before uploading any GPS.
        if (!Prefs.isDriveVerificationEnabled(context) || Prefs.driveVerificationConsentVersion(context) != consentVersion ||
            AccountStore.load(context)?.token != token || (closed && !finishing)) { reset(); return }
        try {
            val result = AccountClient.submitVerifiedDrive(token, evidence, integrity)
            requireNotNull(run).put("sequence", result.getInt("sequence"))
            val anchor = points.last()
            points.clear()
            points += anchor
            pendingEvidence = null
        } catch (error: AccountClient.ApiException) {
            if (error.status == 403 || error.status == 409 || error.status == 422 || error.status == 401) reset()
            throw error
        }
    }

    private fun reset() {
        synchronized(queueLock) { pendingLocations.clear() }
        points.clear()
        pendingEvidence = null
        run = null
        provider = null
        accountToken = null
        consentVersion = -1L
    }

    fun close() {
        accepting = false
        // A pending cooldown must not delay the normal stop-time final submission.
        retryAfter = 0L
        drainTask?.cancel(false)
        drainTask = null
        busy.set(false)
        executor.execute {
            try {
                // Anything copied before close() remains live evidence, so drain it before the
                // final small batch instead of losing samples merely because tracking stopped.
                drainQueuedLocations()
                closed = true
                val token = accountToken
                if (token != null && provider != null && run != null && points.size >= 2 && Prefs.isDriveVerificationEnabled(context)) {
                    if (pendingEvidence == null) prepareEvidence()
                    submit(token, finishing = true)
                }
            } catch (_: Exception) { /* Personal history remains saved regardless of competition availability. */ }
            finally { reset() }
        }
        executor.shutdown()
    }

    companion object {
        internal fun canResumePreparedEvidence(hasPreparedEvidence: Boolean, freshValidFix: Boolean): Boolean =
            hasPreparedEvidence || freshValidFix

        private const val MAX_BUFFERED_FIXES = 32
        private const val MAX_BUFFERED_LOCATION_AGE_MS = 30_000L
        private const val RETRY_AFTER_FAILURE_MS = 30_000L
    }
}
