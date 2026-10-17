package com.roadconquest.app.matching

import com.roadconquest.app.data.TrackPoint
import com.roadconquest.app.services.ProviderHealth
import com.roadconquest.app.services.readBounded
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant

/** LocationIQ's OSRM-compatible matching response, through the private-key gateway. */
class LocationIqMatcher(
    private val origin: String,
    private val health: ProviderHealth = ProviderHealth.shared
) : RoadMatcher {
    companion object { const val MAX_MATCH_POINTS = 25 }
    override val maxPoints = MAX_MATCH_POINTS
    override var unavailable = false
        private set
    private val providerId = "matching:locationiq:$origin"
    private val validator = RoadGeometryValidator()
    private var cachedRequest: String? = null
    private var cachedBody: String? = null
    private var cachedAt = 0L
    override fun rateLimitRemainingMs() = health.remaining(providerId)

    @Synchronized override fun match(points: List<TrackPoint>): MatchResult? {
        unavailable = false
        if (points.size !in 2..maxPoints || points.any {
                !it.latitude.isFinite() || it.latitude !in -90.0..90.0 ||
                !it.longitude.isFinite() || it.longitude !in -180.0..180.0 ||
                !it.accuracyMeters.isFinite() || it.accuracyMeters <= 0f || it.accuracyMeters > 1000f
            }) return null
        val payload = JSONObject().put("mode", "drive").put("waypoints", JSONArray().apply {
            var previous = Long.MIN_VALUE
            points.forEach { point ->
                val time = if (previous == Long.MIN_VALUE) point.timestampMillis else maxOf(point.timestampMillis, previous + 1000L)
                previous = time
                put(JSONObject().put("location", JSONArray().put(point.longitude).put(point.latitude))
                    .put("timestamp", Instant.ofEpochMilli(time).toString()).put("accuracyMeters", point.accuracyMeters))
            }
        }).toString()
        if (cachedRequest == payload && System.currentTimeMillis() - cachedAt in 0L..900_000L) {
            return runCatching { parse(requireNotNull(cachedBody), points) }.getOrNull()
        }
        if (rateLimitRemainingMs() > 0L) { unavailable = true; return null }
        val payloadBytes = payload.toByteArray(Charsets.UTF_8)
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(origin.trimEnd('/') + "/v1/locationiq/mapmatching").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                instanceFollowRedirects = false
                connectTimeout = 8000
                readTimeout = 20000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
                setFixedLengthStreamingMode(payloadBytes.size)
            }
            connection.outputStream.use { it.write(payloadBytes) }
            val status = connection.responseCode
            if (status !in 200..299) {
                unavailable = status in listOf(401, 403, 429) || status >= 500 || status in 300..399
                if (unavailable) health.failed(providerId, status, connection.getHeaderField("Retry-After"))
                return null
            }
            val body = connection.inputStream.use { readBounded(it, 4 * 1024 * 1024) }.toString(Charsets.UTF_8)
            val result = parse(body, points)
            if (body.length <= 256_000 && result != null) {
                cachedRequest = payload; cachedBody = body; cachedAt = System.currentTimeMillis()
            }
            result
        } catch (_: Exception) {
            unavailable = true
            health.failed(providerId)
            null
        } finally { connection?.disconnect() }
    }

    internal fun parse(body: String, points: List<TrackPoint>): MatchResult? {
        if (points.size !in 2..maxPoints) return null
        val root = JSONObject(body)
        if (root.optString("code") in setOf("NoMatch", "NoSegment")) return MatchResult(emptyList(), emptyMap(), explicitNoMatch = true)
        // Provider confidence and ambiguous tracepoints pass through unchanged.
        // The same endpoint, detour, continuity, speed and snap gates still apply.
        return validator.parse(root, points)
    }
}
