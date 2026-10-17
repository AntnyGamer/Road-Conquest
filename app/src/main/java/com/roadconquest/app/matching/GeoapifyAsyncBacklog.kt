package com.roadconquest.app.matching

import android.content.Context
import com.roadconquest.app.data.TrackPoint
import com.roadconquest.app.data.TrackingRepository
import com.roadconquest.app.services.readBounded
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.time.Instant

/**
 * Optional backlog-only batch matching. Live drives never wait for an async job.
 * Job metadata contains only IDs/hashes; GPS remains in the existing local database.
 * The server owns the API key and accepts batches only when they save credits.
 */
internal class GeoapifyAsyncBacklog(
    context: Context, private val origin: String, private val onRoadsUpdated: () -> Unit = {}
) {
    private val prefs = context.getSharedPreferences("mapmatching-async-v1", Context.MODE_PRIVATE)
    private val parser = GeoapifyMatcher(origin)
    private var validatedJobId: String? = null

    private data class Window(val ids: List<Long>, val markableIds: Set<Long>, val fingerprint: String) {
        fun toJson() = JSONObject().put("ids", JSONArray(ids)).put("pending", JSONArray(markableIds.toList()))
            .put("fingerprint", fingerprint)

        companion object {
            fun fromJson(obj: JSONObject): Window {
                fun longs(name: String): List<Long> {
                    val array = obj.getJSONArray(name)
                    return (0 until array.length()).map { array.getLong(it) }
                }
                val ids = longs("ids")
                val pending = longs("pending").toSet()
                require(ids.size in 120..200 && ids.size == ids.toSet().size &&
                    pending.isNotEmpty() && ids.containsAll(pending))
                val fingerprint = obj.getString("fingerprint")
                require(fingerprint.matches(Regex("[0-9a-f]{64}")))
                return Window(ids, pending, fingerprint)
            }
        }
    }

    private data class Job(
        val id: String, val token: String, val submittedAt: Long,
        val nextPollAt: Long, val windows: List<Window>, val pollCount: Int = 0
    ) {
        fun toJson(): String = JSONObject().put("id", id).put("token", token)
            .put("submittedAt", submittedAt).put("nextPollAt", nextPollAt)
            .put("pollCount", pollCount)
            .put("windows", JSONArray().apply { windows.forEach { put(it.toJson()) } }).toString()

        companion object {
            fun fromJson(text: String): Job {
                val obj = JSONObject(text)
                val id = obj.getString("id")
                val token = obj.getString("token")
                require(id.matches(Regex("[0-9a-fA-F]{32}")) && token.matches(Regex("[0-9a-f]{64}")))
                val windowsJson = obj.getJSONArray("windows")
                require(windowsJson.length() in 8..12)
                val windows = (0 until windowsJson.length()).map {
                    Window.fromJson(windowsJson.getJSONObject(it))
                }
                val ids = windows.flatMap { it.markableIds }
                require(ids.size == ids.toSet().size)
                return Job(id, token, obj.getLong("submittedAt"), obj.getLong("nextPollAt"), windows,
                    obj.optInt("pollCount", 0).coerceIn(0, MAX_PAID_POLLS))
            }
        }
    }

    private data class ResponseData(val code: Int, val json: JSONObject)

    /**
     * Execute on TrackingService's existing serial matching executor. Returns the
     * next poll delay when an accepted async job needs attention, or null to
     * continue normal matching. Every failure leaves GPS pending for sync retry.
     */
    fun tick(repository: TrackingRepository, newestLiveFixAt: Long = 0L): Long? {
        if (origin.isBlank() || !repository.isHistoryCurrent()) return null
        val now = System.currentTimeMillis()
        val old = prefs.getString("job", null)
        if (old != null) {
            val job = runCatching { Job.fromJson(old) }.getOrNull()
            if (job == null) {
                prefs.edit().remove("job").remove("deferred").apply()
                return null // Previously deferred GPS expires independently in SQLite.
            }
            // Verify all original GPS samples once per process before any
            // deferral. Import/clear may have replaced the local history while a
            // remote job was pending; never freeze an unrelated new journey.
            if (validatedJobId != job.id) {
                val sameHistory = job.windows.all { window ->
                    val points = repository.savedMatchingPoints(window.ids)
                    points.size == window.ids.size && fingerprint(points) == window.fingerprint
                }
                if (!sameHistory) {
                    prefs.edit().remove("job").remove("deferred").apply()
                    return null
                }
                validatedJobId = job.id
            }
            if (now - job.submittedAt >= MAX_JOB_AGE_MS) {
                release(repository, job)
                return null
            }
            // A crash can happen after saving job metadata but before deferring
            // its GPS. Repeat that one durable step at startup only if needed.
            if (!prefs.getBoolean("deferred", false)) {
                repository.deferMatching(job.windows.flatMap { it.markableIds }, job.submittedAt + MAX_JOB_AGE_MS)
                prefs.edit().putBoolean("deferred", true).apply()
            }
            if (now < job.nextPollAt) return job.nextPollAt - now
            // Status lookups each cost one full credit. A perpetually pending
            // job must never consume unlimited polls and negate the discount.
            if (job.pollCount >= MAX_PAID_POLLS) {
                release(repository, job)
                return null
            }
            val answer = request("GET", "/v1/batch/mapmatching?id=" + job.id, null, job.token)
            if (answer?.code == 200 && answer.json.optJSONArray("results") != null &&
                answer.json.optString("id") == job.id) {
                applyResults(repository, job, answer.json.getJSONArray("results"))
                release(repository, job)
                return null
            }
            val nextPoll = now + POLL_INTERVAL_MS
            prefs.edit().putString("job", job.copy(
                nextPollAt = nextPoll, pollCount = job.pollCount + 1
            ).toJson()).apply()
            return POLL_INTERVAL_MS
        }
        // Never delay a currently moving user's road unlocks. Old saved history
        // is eligible only once it is at least 30 minutes behind live GPS.
        if (newestLiveFixAt > now - OLD_TRACE_AGE_MS || now < prefs.getLong("nextTry", 0L))
            return null
        prefs.edit().putLong("nextTry", now + ATTEMPT_INTERVAL_MS).apply()
        if (!enabledByGateway(now)) return null
        val cap = repository.lastPointIdBefore(now - OLD_TRACE_AGE_MS) ?: return null
        val windows = ArrayList<TrackingRepository.MatchingWindow>(BATCH_WINDOWS)
        var highestId = cap
        repeat(BATCH_WINDOWS) {
            val window = repository.loadMatchingWindow(limit = POINTS_PER_WINDOW, maxPendingId = highestId)
            // Avoid paying for tiny holes or segments that are already locally known.
            if (window.points.size < MIN_POINTS_PER_WINDOW || window.markableIds.size < MIN_POINTS_PER_WINDOW ||
                window.points.any { it.timestampMillis > now - OLD_TRACE_AGE_MS } ||
                KnownRoadReuse.find(repository, window.points, window.markableIds).isNotEmpty())
                return null
            windows += window
            highestId = (window.markableIds.minOrNull() ?: return null) - 1
        }
        if (!repository.isHistoryCurrent()) return null
        val requestJson = JSONObject().put("priority", 0.5).put("inputs", JSONArray().apply {
            windows.forEach { window ->
                put(JSONObject().put("waypoints", JSONArray().apply {
                    var previous = Long.MIN_VALUE
                    window.points.forEach { point ->
                        val time = if (previous == Long.MIN_VALUE) point.timestampMillis
                            else maxOf(point.timestampMillis, previous + 1000L)
                        previous = time
                        put(JSONObject().put("location", JSONArray().put(point.longitude).put(point.latitude))
                            .put("timestamp", Instant.ofEpochMilli(time).toString()))
                    }
                }))
            }
        })
        val answer = request("POST", "/v1/batch/mapmatching", requestJson.toString()) ?: return null
        if (answer.code !in listOf(200, 202)) return null
        val id = answer.json.optString("id")
        val token = answer.json.optString("token")
        if (!id.matches(Regex("[0-9a-fA-F]{32}")) || !token.matches(Regex("[0-9a-f]{64}")))
            return null
        // Serialize metadata first, then defer the exact pending IDs. If the
        // process dies between these operations, startup re-defers the job.
        val job = Job(id, token, now, now + FIRST_POLL_MS,
            windows.map { Window(it.points.map(TrackPoint::id), it.markableIds, fingerprint(it.points)) })
        if (!prefs.edit().putString("job", job.toJson()).putBoolean("deferred", false).commit())
            return null
        val pending = job.windows.flatMap { it.markableIds }
        repository.deferMatching(pending, now + MAX_JOB_AGE_MS)
        prefs.edit().putBoolean("deferred", true).apply()
        if (answer.code == 200 && answer.json.optJSONArray("results") != null) {
            applyResults(repository, job, answer.json.getJSONArray("results"))
            release(repository, job)
            return null
        }
        return FIRST_POLL_MS
    }

    private fun applyResults(repository: TrackingRepository, job: Job, results: JSONArray) {
        if (results.length() != job.windows.size || !repository.isHistoryCurrent()) return
        // Never trust result order alone; the gateway assigned numeric input IDs.
        for (i in job.windows.indices) {
            val window = job.windows[i]
            val entry = results.optJSONObject(i) ?: continue
            if (entry.optString("id") != i.toString()) continue
            val points = repository.savedMatchingPoints(window.ids)
            if (points.size != window.ids.size || fingerprint(points) != window.fingerprint ||
                points.any { it.id in window.markableIds && it.matched }) continue
            val data = entry.optJSONObject("result") ?: continue
            val parsed = runCatching { parser.parse(data.toString(), points) }.getOrNull() ?: continue
            val roads = parsed.roads.filter { it.confidence >= RoadGeometryValidator.MIN_ACCEPTABLE_CONFIDENCE }
            val ids = parsed.matchedPointConfidences.filter { (id, confidence) ->
                id in window.markableIds && confidence >= RoadGeometryValidator.MIN_ACCEPTABLE_CONFIDENCE
            }.keys
            if (roads.isNotEmpty() && ids.isNotEmpty()) {
                repository.completeMatch(roads, ids.toList())
                onRoadsUpdated()
            }
        }
    }

    private fun release(repository: TrackingRepository, job: Job) {
        // Whether the job succeeded, expired or returned partial matches,
        // unresolved samples become eligible for the unchanged sync fallback.
        prefs.edit().remove("job").remove("deferred").apply()
        repository.deferMatching(job.windows.flatMap { it.markableIds }, 0L)
    }

    /** Skip database scans and uploads when the server has not enabled the batch feature. */
    private fun enabledByGateway(now: Long): Boolean {
        if (now < prefs.getLong("featureExpiresAt", 0L))
            return prefs.getBoolean("featureEnabled", false)
        val enabled = request("GET", "/v1/config", null)?.json?.optBoolean("asyncBatchEnabled", false) == true
        prefs.edit().putBoolean("featureEnabled", enabled)
            .putLong("featureExpiresAt", now + if (enabled) 60 * 60_000L else 24 * 60 * 60_000L)
            .apply()
        return enabled
    }

    private fun request(method: String, path: String, body: String?, token: String? = null): ResponseData? {
        var connection: HttpURLConnection? = null
        return try {
            val endpoint = URL(origin.trimEnd('/') + path)
            connection = (endpoint.openConnection() as HttpURLConnection).apply {
                requestMethod = method
                instanceFollowRedirects = false
                connectTimeout = 8_000
                readTimeout = 25_000
                setRequestProperty("Accept", "application/json")
                if (token != null) setRequestProperty("X-Batch-Token", token)
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    setFixedLengthStreamingMode(body.toByteArray(Charsets.UTF_8).size)
                }
            }
            if (body != null) connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            if (code !in listOf(200, 202)) return null
            val data = connection.inputStream.use { readBounded(it, 12 * 1024 * 1024) }
            ResponseData(code, JSONObject(String(data, Charsets.UTF_8)))
        } catch (_: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    companion object {
        private const val BATCH_WINDOWS = 8
        private const val POINTS_PER_WINDOW = 150
        private const val MIN_POINTS_PER_WINDOW = 120
        private const val OLD_TRACE_AGE_MS = 30 * 60_000L
        private const val ATTEMPT_INTERVAL_MS = 2 * 60 * 60_000L
        private const val FIRST_POLL_MS = 3 * 60 * 60_000L
        private const val POLL_INTERVAL_MS = 7 * 60 * 60_000L
        private const val MAX_PAID_POLLS = 2
        private const val MAX_JOB_AGE_MS = 23 * 60 * 60_000L

        internal fun fingerprint(points: List<TrackPoint>): String {
            val md = MessageDigest.getInstance("SHA-256")
            for (point in points) {
                md.update(("${point.id}|${point.latitude}|${point.longitude}|${point.timestampMillis}\n")
                    .toByteArray(Charsets.UTF_8))
            }
            return md.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }
    }
}
