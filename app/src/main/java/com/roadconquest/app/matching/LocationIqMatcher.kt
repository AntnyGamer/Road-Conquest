package com.roadconquest.app.matching

import com.roadconquest.app.data.TrackPoint
import com.roadconquest.app.services.ProviderHealth
import com.roadconquest.app.services.readBoundedUtf8
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant

/** LocationIQ's OSRM-compatible matching response, through the private-key gateway. */
class LocationIqMatcher(
    private val origin: String,
    private val health: ProviderHealth = ProviderHealth.shared,
    now: () -> Long = System::currentTimeMillis
) : RoadMatcher {
    companion object { const val MAX_MATCH_POINTS = 25 }
    override val maxPoints = MAX_MATCH_POINTS
    override var unavailable = false
        private set
    private val providerId = "matching:locationiq:$origin"
    private val validator = RoadGeometryValidator()
    override var lastCallUsedNetwork = false
        private set
    private val responses = ExactMatchResponseCache(now)
    // A cache miss is normally followed immediately by match() for the same GPS
    // window. Save its serialized request once, without retaining stale evidence.
    private var probedPoints: List<TrackPoint>? = null
    private var probedPayload: String? = null
    override fun rateLimitRemainingMs() = health.remaining(providerId)

    private fun validPoints(points: List<TrackPoint>): Boolean =
        points.size in 2..maxPoints && points.none {
            !it.latitude.isFinite() || it.latitude !in -90.0..90.0 ||
                !it.longitude.isFinite() || it.longitude !in -180.0..180.0 ||
                !it.accuracyMeters.isFinite() || it.accuracyMeters <= 0f || it.accuracyMeters > 1000f
        }

    private fun payload(points: List<TrackPoint>): String {
        return JSONObject().put("mode", "drive").put("waypoints", JSONArray().apply {
            var previous = Long.MIN_VALUE
            points.forEach { point ->
                val time = if (previous == Long.MIN_VALUE) point.timestampMillis else maxOf(point.timestampMillis, previous + 1000L)
                previous = time
                put(JSONObject().put("location", JSONArray().put(point.longitude).put(point.latitude))
                    .put("timestamp", Instant.ofEpochMilli(time).toString()).put("accuracyMeters", point.accuracyMeters))
            }
        }).toString()
    }

    @Synchronized override fun cachedMatch(points: List<TrackPoint>): MatchResult? {
        lastCallUsedNetwork = false
        probedPoints = null
        probedPayload = null
        if (responses.isEmpty || !validPoints(points)) return null
        val serialized = payload(points)
        val body = responses.get(serialized)
        if (body == null) {
            probedPoints = points.toList()
            probedPayload = serialized
            return null
        }
        return runCatching { parse(body, points) }.getOrNull()
    }

    @Synchronized override fun match(points: List<TrackPoint>): MatchResult? {
        checkMatchingCancellation()
        unavailable = false
        lastCallUsedNetwork = false
        if (!validPoints(points)) {
            probedPoints = null
            probedPayload = null
            return null
        }
        val payload = if (probedPoints == points) probedPayload ?: payload(points) else payload(points)
        probedPoints = null
        probedPayload = null
        responses.get(payload)?.let { body ->
            return runCatching { parse(body, points) }.getOrNull()
        }
        if (rateLimitRemainingMs() > 0L) { unavailable = true; return null }
        val payloadBytes = payload.toByteArray(Charsets.UTF_8)
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(origin.trimEnd('/') + "/v1/locationiq/mapmatching").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                instanceFollowRedirects = false
                connectTimeout = 8000
                // Gateway may queue for 10 s before its bounded 20 s provider request.
                readTimeout = 35_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
                setFixedLengthStreamingMode(payloadBytes.size)
            }
            lastCallUsedNetwork = true
            connection.outputStream.use { it.write(payloadBytes) }
            val status = connection.responseCode
            if (status !in 200..299) {
                val errorCode = connection.getHeaderField("X-RoadConquest-Error")
                unavailable = status == 401 || status == 403 || status == 429 ||
                    status >= 500 || status in 300..399 ||
                    (status == 409 && errorCode == "budget_conflict")
                if (unavailable) health.failed(providerId, status, connection.getHeaderField("Retry-After"),
                    errorCode)
                return null
            }
            val body = connection.inputStream.use { readBoundedUtf8(it, 4 * 1024 * 1024) }
            val result = parse(body, points)
            if (result != null) responses.put(payload, body)
            result
        } catch (error: Exception) {
            if (error is InterruptedException) {
                Thread.currentThread().interrupt()
                throw error
            }
            checkMatchingCancellation()
            unavailable = true
            health.failed(providerId)
            null
        } finally { connection?.disconnect() }
    }

    internal fun parse(body: String, points: List<TrackPoint>): MatchResult? {
        if (points.size !in 2..maxPoints) return null
        val root = JSONObject(body)
        val code = root.optString("code")
        if (code == "NoMatch" || code == "NoSegment") return MatchResult(emptyList(), emptyMap(), explicitNoMatch = true)
        // Provider confidence and ambiguous tracepoints pass through unchanged.
        // The same endpoint, detour, continuity, speed and snap gates still apply.
        return validator.parse(root, points)
    }
}
