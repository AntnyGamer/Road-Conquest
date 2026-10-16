package com.roadconquest.app.matching

import com.roadconquest.app.data.TrackPoint
import com.roadconquest.app.services.ProviderHealth
import com.roadconquest.app.services.readBounded
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import kotlin.math.*

/** Geoapify's contract is independent of the network provider. No API key is embedded in Android. */
class GeoapifyMatcher(
    private val origin: String,
    private val health: ProviderHealth = ProviderHealth.shared
) : RoadMatcher {
    companion object {
        const val MAX_MATCH_POINTS = 200
        private val COUNTABLE_ROAD_CLASSES = setOf(
            "motorway", "trunk", "primary", "secondary", "tertiary",
            "residential", "unclassified", "living_street", "service"
        )
    }
    override var unavailable = false
        private set
    private val providerId = "matching:$origin"
    private val validator = RoadGeometryValidator()
    private var cachedRequest: String? = null
    private var cachedBody: String? = null
    private var cachedAt = 0L
    override fun rateLimitRemainingMs(): Long = health.remaining(providerId)

    @Synchronized override fun match(points: List<TrackPoint>): MatchResult? {
        unavailable = false
        if (points.size !in 2..MAX_MATCH_POINTS) return null
        val payload = JSONObject().put("mode", "drive").put("waypoints", JSONArray().apply {
            var previous = Long.MIN_VALUE
            points.forEach { point ->
                val time = if (previous == Long.MIN_VALUE) point.timestampMillis else maxOf(point.timestampMillis, previous + 1000L)
                previous = time
                put(JSONObject().put("location", JSONArray().put(point.longitude).put(point.latitude))
                    .put("timestamp", Instant.ofEpochMilli(time).toString()))
            }
        }).toString()
        if (cachedRequest == payload && System.currentTimeMillis() - cachedAt in 0L..60_000L) {
            return runCatching { parse(requireNotNull(cachedBody), points) }.getOrNull()
        }
        if (rateLimitRemainingMs() > 0) { unavailable = true; return null }
        val payloadBytes = payload.toByteArray(Charsets.UTF_8)
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(origin.trimEnd('/') + "/v1/mapmatching").openConnection() as HttpURLConnection).apply {
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
            val bytes = connection.inputStream.use { readBounded(it, 4 * 1024 * 1024) }
            val body = bytes.toString(Charsets.UTF_8)
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
        val root = JSONObject(body)
        require(root.getString("type") == "FeatureCollection")
        val features = root.getJSONArray("features")
        if (features.length() == 0) return MatchResult(emptyList(), emptyMap())
        // Disconnected/multiple hypotheses require more GPS evidence, not a guessed join.
        if (features.length() != 1) return null
        val feature = features.getJSONObject(0)
        val geometry = feature.getJSONObject("geometry")
        val props = feature.getJSONObject("properties")
        if (props.optString("mode") != "drive" || geometry.optString("type") != "MultiLineString") return null
        val lines = geometry.getJSONArray("coordinates")
        val legs = props.getJSONArray("legs")
        if (lines.length() != 1 || legs.length() != 1) return null
        val line = lines.getJSONArray(0)
        if (line.length() !in 2..20_000) return null
        for (i in 0 until line.length()) if (!valid(line.getJSONArray(i))) return null
        val steps = legs.getJSONObject(0).getJSONArray("steps")
        if (steps.length() == 0) return null
        // Parse provider step metadata just once, not for every GPS leg.
        val stepObjects = Array(steps.length()) { steps.getJSONObject(it) }
        val stepStarts = IntArray(stepObjects.size)
        val stepEnds = IntArray(stepObjects.size)
        val stepNames = Array(stepObjects.size) { stepObjects[it].optString("name") }
        val stepCountable = BooleanArray(stepObjects.size) {
            stepObjects[it].optString("road_class") in COUNTABLE_ROAD_CLASSES
        }
        for (i in stepObjects.indices) {
            val from = stepObjects[i].getInt("from_index")
            val to = stepObjects[i].getInt("to_index")
            if (from !in 0 until line.length() || to !in from until line.length()) return null
            stepStarts[i] = from
            stepEnds[i] = to
        }
        val waypoints = props.getJSONArray("waypoints")
        if (waypoints.length() != points.size) return null
        val positions = DoubleArray(points.size)
        val locations = arrayOfNulls<JSONArray>(points.size)
        for (index in points.indices) {
            val waypoint = waypoints.getJSONObject(index)
            require(waypoint.getInt("original_index") == index)
            val original = waypoint.getJSONArray("original_location")
            require(valid(original) && abs(original.getDouble(0) - points[index].longitude) < 0.000001 &&
                abs(original.getDouble(1) - points[index].latitude) < 0.000001)
            // Neither interpolated nor unmatched points establish a driven road.
            // Keep them pending and salvage only independent, continuous matched legs.
            if (waypoint.optString("match_type") != "matched") continue
            if (waypoint.optInt("leg_index", -1) != 0) return null
            val location = waypoint.getJSONArray("location")
            if (!valid(location)) return null
            val stepIndex = waypoint.optInt("step_index", -1)
            if (stepIndex !in 0 until steps.length()) return null
            positions[index] = position(line, location, stepStarts[stepIndex], stepEnds[stepIndex]) ?: return null
            locations[index] = location
        }
        val matchings = JSONArray()
        val tracepoints = JSONArray().apply { repeat(points.size) { put(JSONObject.NULL) } }
        var index = 0
        while (index < points.size) {
            if (locations[index] == null) { index++; continue }
            val first = index++
            while (index < points.size && locations[index] != null && positions[index] > positions[index - 1]) index++
            if (index - first < 2) continue
            val normalizedLegs = JSONArray()
            var complete = true
            for (i in first until index - 1) {
                val pieces = JSONArray()
                for (s in stepObjects.indices) {
                    val from = maxOf(positions[i], stepStarts[s].toDouble())
                    val to = minOf(positions[i + 1], stepEnds[s].toDouble())
                    if (to - from < 0.000001) continue
                    val coordinates = JSONArray().put(at(line, from))
                    for (v in (floor(from).toInt() + 1)..ceil(to).toInt()) {
                        if (v.toDouble() < to) coordinates.put(line.getJSONArray(v))
                    }
                    coordinates.put(at(line, to))
                    val distance = (0 until coordinates.length() - 1).sumOf {
                        meters(coordinates.getJSONArray(it), coordinates.getJSONArray(it + 1))
                    }
                    // Names and road classes were validated once for every route step.
                    pieces.put(JSONObject().put("name", stepNames[s]).put("distance", distance)
                        .put("geometry", JSONObject().put("type", "LineString").put("coordinates", coordinates))
                        .put("maneuver", JSONObject().put("type", if (stepCountable[s]) "turn" else "ramp")))
                }
                if (pieces.length() == 0) { complete = false; break }
                normalizedLegs.put(JSONObject().put("steps", pieces))
            }
            if (!complete) continue
            val group = matchings.length()
            matchings.put(JSONObject().put("confidence", RoadGeometryValidator.MIN_ACCEPTABLE_CONFIDENCE)
                .put("legs", normalizedLegs))
            for (i in first until index) {
                tracepoints.put(i, JSONObject().put("matchings_index", group)
                    .put("waypoint_index", i - first).put("alternatives_count", 0)
                    .put("location", locations[i]))
            }
        }
        if (matchings.length() == 0) return MatchResult(emptyList(), emptyMap())
        val normalized = JSONObject().put("code", "Ok").put("tracepoints", tracepoints)
            .put("matchings", matchings)
        return validator.parse(normalized.toString(), points)
    }

    private fun position(line: JSONArray, point: JSONArray, start: Int, end: Int): Double? {
        var best: Double? = null; var distance = 3.0
        val longitude = point.getDouble(0)
        val latitude = point.getDouble(1)
        val scale = cos(Math.toRadians(latitude))
        for (i in start until end) {
            val a = line.getJSONArray(i); val b = line.getJSONArray(i + 1)
            val dx = (b.getDouble(0) - a.getDouble(0)) * scale; val dy = b.getDouble(1) - a.getDouble(1)
            val length = dx * dx + dy * dy
            if (length <= 0) continue
            val t = (((longitude - a.getDouble(0)) * scale * dx + (latitude - a.getDouble(1)) * dy) / length).coerceIn(0.0, 1.0)
            val error = meters(point, at(line, i + t))
            if (error < distance) { distance = error; best = i + t }
        }
        return best
    }
    private fun at(line: JSONArray, position: Double): JSONArray {
        val index = floor(position).toInt().coerceAtMost(line.length() - 2)
        val t = position - index
        val a = line.getJSONArray(index); val b = line.getJSONArray(index + 1)
        return JSONArray().put(a.getDouble(0) + (b.getDouble(0) - a.getDouble(0)) * t)
            .put(a.getDouble(1) + (b.getDouble(1) - a.getDouble(1)) * t)
    }
    private fun valid(p: JSONArray) = p.length() == 2 && p.optDouble(0).isFinite() && p.optDouble(1).isFinite() &&
        abs(p.optDouble(0)) <= 180 && abs(p.optDouble(1)) <= 90
    private fun meters(a: JSONArray, b: JSONArray): Double {
        val lat1 = Math.toRadians(a.getDouble(1)); val lat2 = Math.toRadians(b.getDouble(1))
        val d = sin((lat2 - lat1) / 2).pow(2) + cos(lat1) * cos(lat2) * sin(Math.toRadians(b.getDouble(0) - a.getDouble(0)) / 2).pow(2)
        return 12_742_000 * asin(sqrt(d.coerceIn(0.0, 1.0)))
    }
}
