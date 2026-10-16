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
        if (features.length() == 1) return parseFeature(features.getJSONObject(0), points)

        // Multiple features are safe to salvage only when each explicitly identifies
        // disjoint, original GPS fixes. Overlapping hypotheses stay ambiguous.
        val claimed = HashSet<Int>()
        val roads = ArrayList<com.roadconquest.app.data.MatchedRoad>()
        val resolved = HashMap<Long, Double>()
        var parsed = false
        for (i in 0 until features.length()) {
            val feature = features.optJSONObject(i) ?: return null
            val source = feature.optJSONObject("properties")?.optJSONArray("waypoints") ?: return null
            val waypoints = JSONArray().apply { repeat(points.size) { put(JSONObject.NULL) } }
            val local = HashSet<Int>()
            for (j in 0 until source.length()) {
                val waypoint = source.optJSONObject(j) ?: continue
                if (waypoint.optString("match_type") != "matched") continue
                val index = waypoint.optInt("original_index", -1)
                val original = waypoint.optJSONArray("original_location")
                if (index !in points.indices || original == null || !valid(original) ||
                    abs(original.getDouble(0) - points[index].longitude) >= 0.000001 ||
                    abs(original.getDouble(1) - points[index].latitude) >= 0.000001 ||
                    !local.add(index) || !claimed.add(index)
                ) return null
                waypoints.put(index, waypoint)
            }
            // A malformed sibling's geometry is never used to join independent roads.
            val result = runCatching { parseFeature(feature, points, waypoints) }.getOrNull() ?: continue
            parsed = true
            roads.addAll(result.roads)
            resolved.putAll(result.matchedPointConfidences)
        }
        return if (parsed) MatchResult(roads, resolved) else null
    }

    private fun parseFeature(
        feature: JSONObject, points: List<TrackPoint>, suppliedWaypoints: JSONArray? = null
    ): MatchResult? {
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
        // Malformed steps must not erase sound siblings or become artificial joins.
        // Only sections fully covered by validated provider steps can be saved.
        val stepStarts = IntArray(steps.length()) { -1 }
        val stepEnds = IntArray(steps.length()) { -1 }
        val stepNames = Array(steps.length()) { "" }
        val stepCountable = BooleanArray(steps.length())
        val stepValid = BooleanArray(steps.length())
        for (i in stepValid.indices) {
            val step = steps.optJSONObject(i) ?: continue
            val from = step.optInt("from_index", -1)
            val to = step.optInt("to_index", -1)
            if (from !in 0 until line.length() || to !in from until line.length()) continue
            stepStarts[i] = from
            stepEnds[i] = to
            stepNames[i] = step.optString("name")
            stepCountable[i] = step.optString("road_class") in COUNTABLE_ROAD_CLASSES
            stepValid[i] = true
        }
        val damagedSteps = stepValid.any { !it }
        val waypoints = suppliedWaypoints ?: props.getJSONArray("waypoints")
        if (waypoints.length() != points.size) return null
        val positions = DoubleArray(points.size)
        val locations = arrayOfNulls<JSONArray>(points.size)
        val waypointSteps = IntArray(points.size) { -1 }
        for (index in points.indices) {
            val waypoint = waypoints.optJSONObject(index) ?: continue
            val original = waypoint.optJSONArray("original_location")
            // A malformed waypoint is a HOLE, never evidence for either neighboring leg.
            if (waypoint.optInt("original_index", -1) != index || original == null ||
                !valid(original) ||
                abs(original.getDouble(0) - points[index].longitude) >= 0.000001 ||
                abs(original.getDouble(1) - points[index].latitude) >= 0.000001 ||
                waypoint.optString("match_type") != "matched" ||
                waypoint.optInt("leg_index", -1) != 0
            ) continue
            val location = waypoint.optJSONArray("location") ?: continue
            val stepIndex = waypoint.optInt("step_index", -1)
            if (!valid(location) || stepIndex !in stepValid.indices || !stepValid[stepIndex]) continue
            val projected = position(line, location, stepStarts[stepIndex], stepEnds[stepIndex])
                ?: continue
            positions[index] = projected
            locations[index] = location
            waypointSteps[index] = stepIndex
        }
        val matchings = JSONArray()
        val tracepoints = JSONArray().apply { repeat(points.size) { put(JSONObject.NULL) } }
        val blockedBridgeEndIds = HashSet<Long>()
        var index = 0
        while (index < points.size) {
            if (locations[index] == null) { index++; continue }
            val first = index++
            while (index < points.size && locations[index] != null && positions[index] > positions[index - 1]) index++
            if (index - first < 2) continue
            var blockStart = first
            var blockLegs = JSONArray()
            fun flushBlock(lastPoint: Int) {
                if (blockLegs.length() == 0) return
                val group = matchings.length()
                matchings.put(JSONObject().put("confidence", RoadGeometryValidator.MIN_ACCEPTABLE_CONFIDENCE)
                    .put("legs", blockLegs))
                for (p in blockStart..lastPoint) {
                    tracepoints.put(p, JSONObject().put("matchings_index", group)
                        .put("waypoint_index", p - blockStart).put("alternatives_count", 0)
                        .put("location", locations[p]))
                }
                blockLegs = JSONArray()
            }
            for (i in first until index - 1) {
                val pieces = JSONArray()
                val fromStep = waypointSteps[i]
                val toStep = waypointSteps[i + 1]
                if (damagedSteps && (fromStep > toStep ||
                    (fromStep..toStep).any { !stepValid[it] })) {
                    blockedBridgeEndIds += points[i + 1].id
                    flushBlock(i)
                    blockStart = i + 1
                    continue
                }
                var covered = positions[i]
                for (s in stepValid.indices) {
                    if (!stepValid[s]) continue
                    val from = maxOf(positions[i], stepStarts[s].toDouble())
                    val to = minOf(positions[i + 1], stepEnds[s].toDouble())
                    if (to - from < 0.000001) continue
                    if (from > covered + 0.000001) break
                    covered = maxOf(covered, to)
                    val coordinates = JSONArray().put(at(line, from))
                    for (v in (floor(from).toInt() + 1)..ceil(to).toInt()) {
                        if (v.toDouble() < to) coordinates.put(line.getJSONArray(v))
                    }
                    coordinates.put(at(line, to))
                    var distance = 0.0
                    var previousCoordinate = coordinates.getJSONArray(0)
                    for (vertex in 1 until coordinates.length()) {
                        val nextCoordinate = coordinates.getJSONArray(vertex)
                        distance += meters(previousCoordinate, nextCoordinate)
                        previousCoordinate = nextCoordinate
                    }
                    pieces.put(JSONObject().put("name", stepNames[s]).put("distance", distance)
                        .put("geometry", JSONObject().put("type", "LineString").put("coordinates", coordinates))
                        .put("maneuver", JSONObject().put("type", if (stepCountable[s]) "turn" else "ramp")))
                }
                if (pieces.length() == 0 ||
                    (damagedSteps && covered < positions[i + 1] - 0.000001)) {
                    blockedBridgeEndIds += points[i + 1].id
                    flushBlock(i)
                    blockStart = i + 1
                    continue
                }
                blockLegs.put(JSONObject().put("steps", pieces))
            }
            flushBlock(index - 1)
        }
        if (matchings.length() == 0) return if (damagedSteps) null
            else MatchResult(emptyList(), emptyMap())
        val normalized = JSONObject().put("code", "Ok").put("tracepoints", tracepoints)
            .put("matchings", matchings)
        return validator.parse(normalized.toString(), points, blockedBridgeEndIds)
    }

    private fun position(line: JSONArray, point: JSONArray, start: Int, end: Int): Double? {
        // A zero-length provider step can still locate a genuine waypoint right
        // on a junction. It establishes a POSITION, never invented road geometry.
        if (start == end) {
            return start.toDouble().takeIf { meters(point, line.getJSONArray(start)) < 3.0 }
        }
        var best: Double? = null; var distance = 3.0
        val longitude = point.getDouble(0)
        val latitude = point.getDouble(1)
        val scale = cos(Math.toRadians(latitude))
        for (i in start until end) {
            val a = line.getJSONArray(i); val b = line.getJSONArray(i + 1)
            // Work in the SHORT wrapped longitude interval. Otherwise a genuine
            // segment crossing +/-180 degrees looks nearly 360 degrees long and
            // loses its waypoint even though the provider supplied valid geometry.
            val dx = shortLongitudeDelta(b.getDouble(0) - a.getDouble(0)) * scale
            val dy = b.getDouble(1) - a.getDouble(1)
            val length = dx * dx + dy * dy
            if (length <= 0) continue
            val fromA = shortLongitudeDelta(longitude - a.getDouble(0)) * scale
            val t = ((fromA * dx + (latitude - a.getDouble(1)) * dy) / length).coerceIn(0.0, 1.0)
            val error = meters(point, at(line, i + t))
            if (error < distance) { distance = error; best = i + t }
        }
        return best
    }
    private fun at(line: JSONArray, position: Double): JSONArray {
        val index = floor(position).toInt().coerceAtMost(line.length() - 2)
        val t = position - index
        val a = line.getJSONArray(index); val b = line.getJSONArray(index + 1)
        val startLongitude = a.getDouble(0)
        val delta = shortLongitudeDelta(b.getDouble(0) - startLongitude)
        val longitude = startLongitude + delta * t
        return JSONArray().put(when {
            longitude > 180.0 -> longitude - 360.0
            longitude < -180.0 -> longitude + 360.0
            else -> longitude
        }).put(a.getDouble(1) + (b.getDouble(1) - a.getDouble(1)) * t)
    }
    private fun shortLongitudeDelta(delta: Double): Double = when {
        delta > 180.0 -> delta - 360.0
        delta < -180.0 -> delta + 360.0
        else -> delta
    }
    private fun valid(p: JSONArray) = p.length() == 2 && p.optDouble(0).isFinite() && p.optDouble(1).isFinite() &&
        abs(p.optDouble(0)) <= 180 && abs(p.optDouble(1)) <= 90
    private fun meters(a: JSONArray, b: JSONArray): Double {
        val lat1 = Math.toRadians(a.getDouble(1)); val lat2 = Math.toRadians(b.getDouble(1))
        val d = sin((lat2 - lat1) / 2).pow(2) + cos(lat1) * cos(lat2) * sin(Math.toRadians(b.getDouble(0) - a.getDouble(0)) / 2).pow(2)
        return 12_742_000 * asin(sqrt(d.coerceIn(0.0, 1.0)))
    }
}
