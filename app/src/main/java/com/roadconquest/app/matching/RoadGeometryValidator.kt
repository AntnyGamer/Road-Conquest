package com.roadconquest.app.matching

import com.roadconquest.app.data.MatchedRoad
import com.roadconquest.app.data.RoadGrouping
import com.roadconquest.app.data.TrackPoint
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

data class MatchResult(
    val roads: List<MatchedRoad>,
    val matchedPointConfidences: Map<Long, Double>
)

/** Independent geometric plausibility checks shared by all current and future matchers. */
class RoadGeometryValidator {
    internal fun parse(json: String, points: List<TrackPoint>): MatchResult? {
        val root = JSONObject(json)
        if (root.optString("code") != "Ok") return null
        val matchings = root.optJSONArray("matchings") ?: return null
        if (matchings.length() == 0) return null

        val confidences = DoubleArray(matchings.length()) { index ->
            matchings.getJSONObject(index).optDouble("confidence", 0.0)
                .takeIf { it.isFinite() && it in 0.0..1.0 } ?: 0.0
        }
        val tracepoints = root.optJSONArray("tracepoints") ?: return null
        if (tracepoints.length() != points.size) return null
        val traces = Array(matchings.length()) { mutableListOf<Trace>() }
        for (i in points.indices) {
            val tracepoint = tracepoints.optJSONObject(i) ?: continue
            val matchingIndex = tracepoint.optInt("matchings_index", -1)
            val waypoint = tracepoint.optInt("waypoint_index", -1)
            val alternatives = tracepoint.optInt("alternatives_count", -1)
            val location = tracepoint.optJSONArray("location") ?: return null
            if (matchingIndex !in traces.indices || waypoint < 0 || alternatives < 0 ||
                !validCoordinate(location)
            ) return null
            traces[matchingIndex] += Trace(i, waypoint, alternatives, location)
        }

        val roads = mutableListOf<MatchedRoad>()
        val resolved = mutableMapOf<Long, Double>()
        val blockedBridgeEndIds = HashSet<Long>()
        var rejectedLeg = false
        for (m in traces.indices) {
            val trace = traces[m]
            if (trace.size < 2) continue
            val legs = matchings.getJSONObject(m).optJSONArray("legs") ?: return null
            // All input fixes are requested as waypoints. Reject shifted, duplicated or missing
            // leg indices rather than assigning another fix's geometry and completion state.
            if (legs.length() != trace.size - 1 || trace.indices.any { trace[it].waypoint != it }) return null
            // Alternative candidates at an incremental trace endpoint still need more evidence.
            // Inside a confident trace, fixes on both sides already disambiguate the route;
            // discarding those legs creates holes at ordinary turns and junctions.
            fun reliable(index: Int): Boolean = trace[index].alternatives == 0 ||
                (index > 0 && index < trace.lastIndex &&
                    confidences[m] >= MIN_ACCEPTABLE_CONFIDENCE)
            var first = 0
            while (first < trace.size) {
                while (first < trace.size && !reliable(first)) first++
                if (first >= trace.size) break
                var last = first
                while (last + 1 < trace.size && reliable(last + 1)) last++
                if (last > first) {
                    var blockStart = first
                    var blockRoads = mutableListOf<MatchedRoad>()

                    fun flushBlock(endLegExclusive: Int) {
                        if (blockRoads.isEmpty() || endLegExclusive <= blockStart) return
                        roads += blockRoads
                        for (t in blockStart..endLegExclusive) {
                            val input = trace[t].input
                            // The first fix after any withheld interval stays pending so a later
                            // retry can resolve the missing incoming segment with better context.
                            if (t == blockStart && input != 0) continue
                            resolved[points[input].id] = confidences[m]
                        }
                        blockRoads = mutableListOf()
                    }

                    for (l in first until last) {
                        val legRoads = try {
                            parseLeg(legs.getJSONObject(l), trace[l], trace[l + 1], points, confidences[m])
                        } catch (_: IllegalArgumentException) {
                            rejectedLeg = true
                            emptyList()
                        }
                        if (legRoads.isEmpty()) {
                            // One locally implausible provider leg should stay pending, but must not
                            // turn every valid sibling leg in the batch into a 5-minute failure.
                            // Also remember this exact interval so the seam-repair fallback cannot
                            // immediately synthesize a connector across geometry we just rejected.
                            blockedBridgeEndIds += points[trace[l + 1].input].id
                            flushBlock(l)
                            blockStart = l + 1
                            continue
                        }
                        for (road in legRoads) appendRoad(blockRoads, road)
                    }
                    flushBlock(last)
                }
                first = last + 1
            }
        }
        // A split provider trace can leave a tiny missing segment even with confident, consecutive
        // GPS fixes on both sides. Support a tightly bounded cross-road junction seam as well
        // as the existing same-road repair; never fabricate long or rejected connections.
        bridgeConfidentTraceSplits(roads, resolved, points, blockedBridgeEndIds)

        // Salvage valid sibling legs, but preserve the longer failure backoff when every
        // candidate leg failed local plausibility checks. That avoids hammering the matcher every
        // ten seconds on a persistently bad nearby-road candidate with no trustworthy geometry.
        if (roads.isEmpty() && rejectedLeg) return null

        // A valid but ambiguous response is a partial match, not a network failure. It should
        // retry soon with more context and must not save provisional blue lines or road credit.
        return MatchResult(roads, resolved)
    }

    private data class Trace(val input: Int, val waypoint: Int, val alternatives: Int, val location: JSONArray)

    private fun parseLeg(
        leg: JSONObject, start: Trace, end: Trace, points: List<TrackPoint>, confidence: Double
    ): List<MatchedRoad> {
        val steps = requireNotNull(leg.optJSONArray("steps")) { "Missing matched leg steps" }
        val parsed = ArrayList<StepGeometry>(steps.length())
        var previous = start.location
        for (s in 0 until steps.length()) {
            val step = steps.getJSONObject(s)
            val distance = step.optDouble("distance", Double.NaN)
            require(distance.isFinite() && distance >= 0.0) { "Invalid step distance" }
            val geometry = step.optJSONObject("geometry")
            val coordinates = geometry?.optJSONArray("coordinates")
            if (distance == 0.0 && (coordinates == null || coordinates.length() < 2)) continue
            require(geometry?.optString("type") == "LineString" && coordinates != null &&
                coordinates.length() >= 2 && (0 until coordinates.length()).all {
                    coordinates.optJSONArray(it)?.let(::validCoordinate) == true
                }
            ) { "Incomplete matched leg geometry" }
            require(coordinateDistanceMeters(previous, coordinates.getJSONArray(0)) <= JOIN_TOLERANCE_M) {
                "Disconnected matched steps"
            }
            val connected = connectStart(previous, coordinates)
            val geometryMeters = lineLengthMeters(connected)
            if (geometryMeters >= MIN_GEOMETRY_LENGTH_M) {
                val rawName = step.optString("name").trim()
                val reference = step.optString("ref").trim()
                val rotaryName = step.optString("rotary_name").trim()
                val maneuverType = step.optJSONObject("maneuver")
                    ?.optString("type")
                    ?.trim()
                    ?.lowercase(Locale.ROOT)
                    .orEmpty()
                val countTowardsRoads = when (maneuverType) {
                    "ramp", "on ramp", "off ramp" -> false
                    "roundabout", "roundabout turn", "rotary" ->
                        rawName.isNotBlank() || rotaryName.isNotBlank() || reference.isNotBlank()
                    else -> true
                }
                parsed += StepGeometry(
                    name = rawName.ifBlank { rotaryName.ifBlank { reference.ifBlank { "Unnamed road" } } },
                    reference = reference,
                    countTowardsRoads = countTowardsRoads,
                    coordinates = connected,
                    meters = geometryMeters
                )
            }
            previous = connected.getJSONArray(connected.length() - 1)
        }
        require(coordinateDistanceMeters(previous, end.location) <= JOIN_TOLERANCE_M) {
            "Matched geometry misses its endpoint"
        }
        if (parsed.isNotEmpty()) {
            val lastIndex = parsed.lastIndex
            val last = parsed[lastIndex]
            val connected = connectEnd(last.coordinates, end.location)
            parsed[lastIndex] = last.copy(coordinates = connected, meters = lineLengthMeters(connected))
        }

        val total = parsed.sumOf { it.meters }
        val from = points[start.input].timestampMillis
        val duration = (points[end.input].timestampMillis - from).coerceAtLeast(0L)
        val rawStart = JSONArray().put(points[start.input].longitude).put(points[start.input].latitude)
        val rawEnd = JSONArray().put(points[end.input].longitude).put(points[end.input].latitude)
        val uncertainty = points[start.input].accuracyMeters + points[end.input].accuracyMeters
        val rawMeters = pointDistanceMeters(points[start.input], points[end.input])
        val inferredSpeedMps = if (duration > 0L && rawMeters.isFinite() && rawMeters > uncertainty) {
            // With no stored speed, Road Conquest only accepted this pair as driving when the
            // displacement beat the two fixes' combined uncertainty. Preserve that same evidence
            // requirement before using displacement to relax the low-speed snap gate.
            rawMeters / (duration / 1_000.0)
        } else {
            0.0
        }
        require(coordinateDistanceMeters(rawStart, start.location) <=
            snapToleranceMeters(points[start.input], inferredSpeedMps) &&
            coordinateDistanceMeters(rawEnd, end.location) <=
            snapToleranceMeters(points[end.input], inferredSpeedMps)) {
            "Matched road is too far from the recorded drive"
        }
        require(total <= coordinateDistanceMeters(rawStart, rawEnd) * 3 + uncertainty * 2 + 30 &&
            (duration == 0L || total <= duration / 1000.0 * 100 + uncertainty)) {
            "Matched route contains an unsupported detour"
        }
        var before = 0.0
        return buildList(parsed.size) {
            for (step in parsed) {
                val firstTime = from + (duration * if (total > 0.0) before / total else 0.0).roundToLong()
                before += step.meters
                val lastTime = from + (duration * if (total > 0.0) before / total else 0.0).roundToLong()
                add(MatchedRoad(
                    name = step.name,
                    coordinatesJson = step.coordinates.toString(),
                    firstTimestamp = firstTime,
                    lastTimestamp = lastTime,
                    confidence = confidence,
                    reference = step.reference,
                    countTowardsRoads = step.countTowardsRoads
                ))
            }
        }
    }

    private fun appendRoad(roads: MutableList<MatchedRoad>, road: MatchedRoad) {
        val previous = roads.lastOrNull()
        if (previous == null || !sameMergeIdentity(previous, road)) {
            roads += road
            return
        }
        val previousCoordinates = JSONArray(previous.coordinatesJson)
        val coordinates = JSONArray(road.coordinatesJson)
        if (coordinateDistanceMeters(
                previousCoordinates.getJSONArray(previousCoordinates.length() - 1),
                coordinates.getJSONArray(0)
            ) > JOIN_TOLERANCE_M
        ) {
            roads += road
            return
        }

        val merged = JSONArray()
        for (i in 0 until previousCoordinates.length()) merged.put(previousCoordinates.getJSONArray(i))
        val previousEnd = previousCoordinates.getJSONArray(previousCoordinates.length() - 1)
        val currentStart = coordinates.getJSONArray(0)
        val firstToCopy = if (coordinateDistanceMeters(previousEnd, currentStart) <= DUPLICATE_POINT_TOLERANCE_M) 1 else 0
        for (i in firstToCopy until coordinates.length()) merged.put(coordinates.getJSONArray(i))
        roads[roads.lastIndex] = MatchedRoad(
            name = previous.name,
            coordinatesJson = merged.toString(),
            firstTimestamp = previous.firstTimestamp,
            lastTimestamp = road.lastTimestamp,
            confidence = minOf(previous.confidence, road.confidence),
            reference = previous.reference,
            countTowardsRoads = previous.countTowardsRoads
        )
    }

    private fun sameMergeIdentity(a: MatchedRoad, b: MatchedRoad): Boolean {
        val first = RoadGrouping.normalizeName(a.name)
        if (first.isEmpty() || first != RoadGrouping.normalizeName(b.name) ||
            a.countTowardsRoads != b.countTowardsRoads
        ) return false
        return RoadGrouping.normalizeReferences(a.reference) ==
            RoadGrouping.normalizeReferences(b.reference)
    }

    private fun bridgeConfidentTraceSplits(
        roads: MutableList<MatchedRoad>,
        resolved: MutableMap<Long, Double>,
        points: List<TrackPoint>,
        blockedBridgeEndIds: Set<Long>
    ) {
        if (roads.size < 2 || points.size < 2) return
        val ordered = roads.sortedWith(compareBy<MatchedRoad> { it.firstTimestamp }.thenBy { it.lastTimestamp })
        val bridged = HashSet<Long>()
        for (index in 0 until ordered.lastIndex) {
            val left = ordered[index]
            val right = ordered[index + 1]
            // Unnamed roads have no trustworthy name identity. Only bridge a short,
            // nearly collinear seam with high confidence on both sides; never guess a turn.
            val unnamedBridge = left.countTowardsRoads && right.countTowardsRoads &&
                left.reference.isBlank() && right.reference.isBlank() &&
                RoadGrouping.normalizeName(left.name).isEmpty() &&
                RoadGrouping.normalizeName(right.name).isEmpty() &&
                left.confidence >= MIN_UNNAMED_BRIDGE_CONFIDENCE &&
                right.confidence >= MIN_UNNAMED_BRIDGE_CONFIDENCE
            // Different named roads also meet at actual intersections. provider can split the
            // short crossing between two confident matchings; allow only a very short bridge
            // with GPS position + course evidence and never award a new road for it.
            val crossRoadBridge = !sameMergeIdentity(left, right) && !unnamedBridge &&
                left.countTowardsRoads && right.countTowardsRoads &&
                left.confidence >= MIN_CROSS_ROAD_BRIDGE_CONFIDENCE &&
                right.confidence >= MIN_CROSS_ROAD_BRIDGE_CONFIDENCE
            if (left.confidence < MIN_ACCEPTABLE_CONFIDENCE ||
                right.confidence < MIN_ACCEPTABLE_CONFIDENCE ||
                (!sameMergeIdentity(left, right) && !unnamedBridge && !crossRoadBridge)
            ) continue

            val leftPoint = points.indexOfLast { it.timestampMillis == left.lastTimestamp }
            val rightPoint = points.indexOfFirst { it.timestampMillis == right.firstTimestamp }
            if (leftPoint < 0 || rightPoint != leftPoint + 1) continue
            val from = points[leftPoint]
            val to = points[rightPoint]
            if (to.id in blockedBridgeEndIds) continue
            val elapsed = to.timestampMillis - from.timestampMillis
            if (elapsed !in 1..MAX_SPLIT_BRIDGE_GAP_MS || to.id in bridged) continue

            val leftCoordinates = runCatching { JSONArray(left.coordinatesJson) }.getOrNull() ?: continue
            val rightCoordinates = runCatching { JSONArray(right.coordinatesJson) }.getOrNull() ?: continue
            if (leftCoordinates.length() < 2 || rightCoordinates.length() < 2) continue
            val start = leftCoordinates.getJSONArray(leftCoordinates.length() - 1)
            val end = rightCoordinates.getJSONArray(0)
            val connectorMeters = coordinateDistanceMeters(start, end)
            val rawMeters = pointDistanceMeters(from, to)
            val uncertainty = from.accuracyMeters + to.accuracyMeters
            val inferredSpeedMps = if (elapsed > 0L && rawMeters.isFinite() && rawMeters > uncertainty) {
                rawMeters / (elapsed / 1_000.0)
            } else {
                0.0
            }
            if (!connectorMeters.isFinite() || !rawMeters.isFinite() ||
                (crossRoadBridge && (elapsed > MAX_CROSS_ROAD_BRIDGE_GAP_MS ||
                    rawMeters > MAX_CROSS_ROAD_BRIDGE_RAW_M ||
                    connectorMeters > MAX_CROSS_ROAD_BRIDGE_M)) ||
                connectorMeters > MAX_SPLIT_BRIDGE_M ||
                connectorMeters > rawMeters * SPLIT_BRIDGE_DISTANCE_FACTOR + uncertainty + SPLIT_BRIDGE_DISTANCE_PAD_M ||
                coordinateDistanceMeters(
                    JSONArray().put(from.longitude).put(from.latitude), start
                ) > snapToleranceMeters(from, inferredSpeedMps) ||
                coordinateDistanceMeters(
                    JSONArray().put(to.longitude).put(to.latitude), end
                ) > snapToleranceMeters(to, inferredSpeedMps)
            ) continue

            if (connectorMeters <= DUPLICATE_POINT_TOLERANCE_M) {
                // Only consider this a zero-length seam when the raw fixes agree. Two fixes
                // far apart may snap to the same junction even though the car actually moved.
                if (rawMeters <= minOf(MAX_ZERO_SEAM_RAW_DISTANCE_M, maxOf(MIN_ZERO_SEAM_RAW_DISTANCE_M, uncertainty.toDouble()))) {
                    resolved[to.id] = minOf(left.confidence, right.confidence)
                    bridged.add(to.id)
                }
                continue
            }
            val rawBearing = initialBearingDegrees(from, to)
            val connectorBearing = coordinateBearingDegrees(start, end)
            if (bearingDifferenceDegrees(rawBearing, connectorBearing) > MAX_SPLIT_BRIDGE_BEARING_DIFFERENCE_DEGREES) {
                continue
            }
            if (unnamedBridge) {
                val before = leftCoordinates.getJSONArray(leftCoordinates.length() - 2)
                val after = rightCoordinates.getJSONArray(1)
                if (connectorMeters > MAX_UNNAMED_BRIDGE_M ||
                    coordinateDistanceMeters(before, start) < MIN_UNNAMED_DIRECTION_M ||
                    coordinateDistanceMeters(end, after) < MIN_UNNAMED_DIRECTION_M ||
                    bearingDifferenceDegrees(coordinateBearingDegrees(before, start), connectorBearing) >
                        MAX_UNNAMED_BRIDGE_BEARING_DEGREES ||
                    bearingDifferenceDegrees(connectorBearing, coordinateBearingDegrees(end, after)) >
                        MAX_UNNAMED_BRIDGE_BEARING_DEGREES
                ) continue
            }

            roads += MatchedRoad(
                name = left.name,
                coordinatesJson = JSONArray().put(copyCoordinate(start)).put(copyCoordinate(end)).toString(),
                firstTimestamp = from.timestampMillis,
                lastTimestamp = to.timestampMillis,
                confidence = minOf(left.confidence, right.confidence),
                reference = left.reference,
                // An inferred anonymous connector is display geometry, not evidence that
                // another independently countable road was unlocked.
                countTowardsRoads = left.countTowardsRoads && !unnamedBridge && !crossRoadBridge
            )
            resolved[to.id] = minOf(left.confidence, right.confidence)
            bridged.add(to.id)
        }
    }

    private fun coordinateBearingDegrees(a: JSONArray, b: JSONArray): Double {
        val lat1 = Math.toRadians(a.getDouble(1))
        val lat2 = Math.toRadians(b.getDouble(1))
        val deltaLongitude = Math.toRadians(((b.getDouble(0) - a.getDouble(0) + 540.0) % 360.0) - 180.0)
        val y = sin(deltaLongitude) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(deltaLongitude)
        return normalizeBearing(Math.toDegrees(atan2(y, x)))
    }

    private fun bearingDifferenceDegrees(a: Double, b: Double): Double =
        kotlin.math.abs(((a - b + 540.0) % 360.0) - 180.0)

    private fun supportedTurnGap(points: List<TrackPoint>): IntRange? {
        // A retry window may include another, unrelated unmatched fix immediately before
        // the turn. Check each contiguous pending island independently instead of treating
        // the first and last pending fix in the entire window as one giant gap.
        // Prefer the longest eligible turn when multiple gaps have confirmed anchors.
        var best: IntRange? = null
        var first = 0
        while (first < points.size) {
            if (points[first].matched) {
                first++
                continue
            }
            var last = first
            while (last + 1 < points.size && !points[last + 1].matched) last++
            if (isSupportedTurnGap(points, first, last) &&
                (best == null || last - first > best.last - best.first)
            ) best = first..last
            first = last + 1
        }
        return best
    }

    private fun isSupportedTurnGap(points: List<TrackPoint>, first: Int, last: Int): Boolean {
        val length = last - first + 1
        if (first < 1 || last >= points.lastIndex ||
            length > MAX_SUPPORTED_TURN_GAP_POINTS ||
            !points[first - 1].matched || !points[last + 1].matched
        ) return false

        if (length <= 3) {
            // Preserve the established two-independent-fix anchor evidence for
            // short gaps, which are far more common than extended missing turns.
            if (first < 2 || last >= points.size - 2) return false
            val approachFrom = points[first - 2]
            val approachTo = points[first - 1]
            val exitFrom = points[last + 1]
            val exitTo = points[last + 2]
            if (pointDistanceMeters(approachFrom, approachTo) < MIN_BEARING_EVIDENCE_DISTANCE_M ||
                pointDistanceMeters(exitFrom, exitTo) < MIN_BEARING_EVIDENCE_DISTANCE_M
            ) return false
            val change = bearingDifferenceDegrees(
                initialBearingDegrees(approachFrom, approachTo),
                initialBearingDegrees(exitFrom, exitTo)
            )
            if (change >= SHARP_TURN_DEGREES) return true
            // Some valid junction transitions bend only 25-44 degrees. The
            // independent GPS fixes on both sides still warrant a no-bearing
            // provider retry, but only across a short and accurate sampled gap.
            return change >= MODERATE_TURN_DEGREES &&
                (first - 1..last + 1).all { index ->
                    points[index].accuracyMeters.isFinite() &&
                        points[index].accuracyMeters in 0.01f..MAX_LONG_TURN_ACCURACY_M
                } &&
                (first - 1 until last + 1).all { index ->
                    val elapsed = points[index + 1].timestampMillis - points[index].timestampMillis
                    elapsed in 1..MAX_LONG_TURN_SAMPLE_GAP_MS &&
                        pointDistanceMeters(points[index], points[index + 1]) <= MAX_LONG_TURN_JUMP_M
                }
        }

        // Long holes require precise, continuous GPS and actual turn geometry.
        // Neither elapsed time nor a nearby road alone proves the driven path.
        if (points[last].timestampMillis - points[first].timestampMillis !in 1..MAX_LONG_TURN_GAP_MS ||
            (first - 1..last + 1).any { i ->
                !points[i].accuracyMeters.isFinite() ||
                    points[i].accuracyMeters > MAX_LONG_TURN_ACCURACY_M ||
                    points[i].accuracyMeters < 0f
            } ||
            (first - 1 until last + 1).any { i ->
                val elapsed = points[i + 1].timestampMillis - points[i].timestampMillis
                elapsed !in 1..MAX_LONG_TURN_SAMPLE_GAP_MS ||
                    pointDistanceMeters(points[i], points[i + 1]) > MAX_LONG_TURN_JUMP_M
            }
        ) return false

        val approachFrom = points[first - 1]
        val approachTo = points[first]
        val exitFrom = points[last]
        val exitTo = points[last + 1]
        val approachDistance = pointDistanceMeters(approachFrom, approachTo)
        val exitDistance = pointDistanceMeters(exitFrom, exitTo)
        val cornerCourse = (first..last).any { isSharpTurn(points, it) } ||
            (approachDistance >= MIN_BEARING_EVIDENCE_DISTANCE_M &&
                exitDistance >= MIN_BEARING_EVIDENCE_DISTANCE_M &&
                bearingDifferenceDegrees(
                    initialBearingDegrees(approachFrom, approachTo),
                    initialBearingDegrees(exitFrom, exitTo)
                ) >= SHARP_TURN_DEGREES)
        return cornerCourse
    }

    private fun isSharpTurn(points: List<TrackPoint>, index: Int): Boolean {
        if (index == 0 || index == points.lastIndex) return false
        val incoming = points[index - 1]
        val point = points[index]
        val outgoing = points[index + 1]
        return pointDistanceMeters(incoming, point) >= MIN_BEARING_EVIDENCE_DISTANCE_M &&
            pointDistanceMeters(point, outgoing) >= MIN_BEARING_EVIDENCE_DISTANCE_M &&
            bearingDifferenceDegrees(
                initialBearingDegrees(incoming, point),
                initialBearingDegrees(point, outgoing)
            ) >= SHARP_TURN_DEGREES
    }

    internal fun buildBearingGuidance(points: List<TrackPoint>): String? {
        val values = points.indices.map { index ->
            val point = points[index]
            // A forward-only course at a junction describes the exit road, not necessarily
            // the incoming road. Do not forbid provider from considering the true turn here.
            if (isSharpTurn(points, index)) return@map ""
            val (from, to) = if (index < points.lastIndex) {
                point to points[index + 1]
            } else {
                points.getOrNull(index - 1)?.let { it to point } ?: return@map ""
            }
            val distance = pointDistanceMeters(from, to)
            val fromAccuracy = from.accuracyMeters.takeIf { it.isFinite() && it >= 0f } ?: MAX_MATCH_RADIUS_M
            val toAccuracy = to.accuracyMeters.takeIf { it.isFinite() && it >= 0f } ?: MAX_MATCH_RADIUS_M
            val minimumEvidenceDistance = maxOf(
                MIN_BEARING_EVIDENCE_DISTANCE_M,
                (fromAccuracy + toAccuracy) * BEARING_ACCURACY_DISTANCE_FACTOR
            )
            if (!distance.isFinite() || distance < minimumEvidenceDistance) return@map ""

            val elapsedMs = to.timestampMillis - from.timestampMillis
            val combinedAccuracy = fromAccuracy + toAccuracy
            val inferredSpeedMps = if (elapsedMs > 0L && distance > combinedAccuracy) {
                distance / (elapsedMs / 1_000.0)
            } else {
                0.0
            }
            val storedSpeed = point.speedMps.toDouble().takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
            val movementSpeedMps = if (storedSpeed > 0.0) storedSpeed else inferredSpeedMps
            if (movementSpeedMps < MIN_BEARING_GUIDANCE_SPEED_MPS) return@map ""

            val movementBearing = initialBearingDegrees(from, to)
            "${movementBearing.roundToInt() % 360},$BEARING_GUIDANCE_RANGE_DEGREES"
        }
        return values.takeIf { entries -> entries.any { it.isNotEmpty() } }?.joinToString(";")
    }

    private fun pointDistanceMeters(a: TrackPoint, b: TrackPoint): Double {
        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(b.latitude)
        val deltaLongitude = Math.toRadians(((b.longitude - a.longitude + 540.0) % 360.0) - 180.0)
        val x = deltaLongitude * cos((lat1 + lat2) / 2.0)
        val y = lat2 - lat1
        return EARTH_RADIUS_M * sqrt(x * x + y * y)
    }

    private fun snapToleranceMeters(point: TrackPoint, inferredSpeedMps: Double): Double {
        val accuracy = point.accuracyMeters.toDouble()
        val storedSpeed = point.speedMps.toDouble().takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
        // Stored track points use 0 m/s when Android did not provide speed. Only that
        // ambiguous zero sentinel may fall back to displacement-derived speed; a real positive
        // low-speed measurement stays authoritative so GPS jitter cannot loosen this gate.
        val inferredSpeed = inferredSpeedMps.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
        val movementSpeedMps = if (storedSpeed > 0.0) storedSpeed else inferredSpeed
        if (movementSpeedMps >= STRICT_SNAP_MAX_SPEED_MPS) {
            // Preserve the old allowance at normal driving speed. The false nearby-road sample in
            // the supplied export happened while slowing/stopping, so this keeps the protection
            // targeted instead of creating fresh highway or arterial gaps from normal GNSS drift.
            return accuracy * 2 + 10
        }
        return (accuracy + SNAP_TOLERANCE_EXTRA_M)
            .coerceIn(MIN_SNAP_TOLERANCE_M, MAX_SNAP_TOLERANCE_M)
    }

    private fun initialBearingDegrees(a: TrackPoint, b: TrackPoint): Double {
        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(b.latitude)
        val deltaLongitude = Math.toRadians(((b.longitude - a.longitude + 540.0) % 360.0) - 180.0)
        val y = sin(deltaLongitude) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(deltaLongitude)
        return normalizeBearing(Math.toDegrees(atan2(y, x)))
    }

    private fun normalizeBearing(value: Double): Double =
        ((value % 360.0) + 360.0) % 360.0

    private data class StepGeometry(
        val name: String,
        val reference: String,
        val countTowardsRoads: Boolean,
        val coordinates: JSONArray,
        val meters: Double
    )

    private fun connectStart(start: JSONArray, coordinates: JSONArray): JSONArray {
        val connected = JSONArray().put(copyCoordinate(start))
        val first = coordinates.getJSONArray(0)
        val from = if (coordinateDistanceMeters(start, first) <= DUPLICATE_POINT_TOLERANCE_M) 1 else 0
        for (i in from until coordinates.length()) connected.put(copyCoordinate(coordinates.getJSONArray(i)))
        return connected
    }

    private fun connectEnd(coordinates: JSONArray, end: JSONArray): JSONArray {
        val connected = JSONArray()
        val lastIndex = coordinates.length() - 1
        for (i in 0 until lastIndex) connected.put(copyCoordinate(coordinates.getJSONArray(i)))
        val last = coordinates.getJSONArray(lastIndex)
        if (coordinateDistanceMeters(last, end) > DUPLICATE_POINT_TOLERANCE_M) {
            connected.put(copyCoordinate(last))
        }
        connected.put(copyCoordinate(end))
        return connected
    }

    private fun lineLengthMeters(coordinates: JSONArray): Double {
        var meters = 0.0
        for (i in 0 until coordinates.length() - 1) {
            meters += coordinateDistanceMeters(coordinates.getJSONArray(i), coordinates.getJSONArray(i + 1))
        }
        return meters
    }

    private fun copyCoordinate(pair: JSONArray) =
        JSONArray().put(pair.getDouble(0)).put(pair.getDouble(1))

    private fun validCoordinate(pair: JSONArray): Boolean = pair.length() >= 2 &&
        pair.optDouble(0, Double.NaN) in -180.0..180.0 && pair.optDouble(1, Double.NaN) in -90.0..90.0

    private fun coordinateDistanceMeters(a: JSONArray, b: JSONArray): Double {
        val lon1 = a.optDouble(0, 0.0)
        val lat1 = Math.toRadians(a.optDouble(1, 0.0))
        val lon2 = b.optDouble(0, 0.0)
        val lat2 = Math.toRadians(b.optDouble(1, 0.0))
        val deltaLongitude = Math.toRadians(((lon2 - lon1 + 540.0) % 360.0) - 180.0)
        val x = deltaLongitude * cos((lat1 + lat2) / 2.0)
        val y = lat2 - lat1
        return EARTH_RADIUS_M * sqrt(x * x + y * y)
    }

    companion object {
        // The public provider demo rejects larger traces with TooBig (verified 2026-10-03).
        const val MAX_MATCH_POINTS = 100
        // Strict in-memory cap: at most four compact, successful provider responses for
        // one minute. Changed coordinates/times/radii/bearings always bypass cache.
        private const val RESPONSE_CACHE_TTL_MS = 60_000L
        private const val MAX_CACHED_RESPONSES = 4
        private const val MAX_CACHED_RESPONSE_CHARS = 256 * 1024
        // A point with alternatives is only accepted when it is internal to a trace, so fixes
        // on both sides constrain the route. Keep this identical to TrackingService's road
        // acceptance threshold; otherwise a 0.45-0.79 contextual match can retry forever.
        internal const val MIN_ACCEPTABLE_CONFIDENCE = 0.45
        private const val MIN_MATCH_RADIUS_M = 10f
        private const val MAX_MATCH_RADIUS_M = 75f
        // Treat provider's radius as candidate discovery, not proof. Tighten permanent
        // geometry only while the vehicle is moving slowly enough for a nearby-road snap to be
        // especially plausible; normal driving keeps the previous distance allowance. Keep a
        // generous low-speed floor so this protection does not turn ordinary centerline/GNSS
        // offset into a permanent gap. The supplied false Lake Boulevard match was ~16 m away,
        // so an 11 m floor remains well below that observed false snap while retaining more
        // tolerance than the original 8 m floor for genuine low-speed centerline/GNSS offset.
        private const val STRICT_SNAP_MAX_SPEED_MPS = 4f
        private const val MIN_SNAP_TOLERANCE_M = 11.0
        private const val SNAP_TOLERANCE_EXTRA_M = 3.0
        private const val MAX_SNAP_TOLERANCE_M = 30.0
        // Junction seams are supported only by consecutive precise fixes, with a bounded
        // physical displacement and confirmed geometry on both named roads.
        private const val MIN_CROSS_ROAD_BRIDGE_CONFIDENCE = 0.70
        private const val MAX_CROSS_ROAD_BRIDGE_GAP_MS = 3_500L
        private const val MAX_CROSS_ROAD_BRIDGE_RAW_M = 18.0
        private const val MAX_CROSS_ROAD_BRIDGE_M = 30.0
        private const val MAX_SPLIT_BRIDGE_GAP_MS = 5_000L
        private const val MAX_SPLIT_BRIDGE_M = 80.0
        private const val SPLIT_BRIDGE_DISTANCE_FACTOR = 1.35
        private const val SPLIT_BRIDGE_DISTANCE_PAD_M = 8.0
        private const val MAX_SPLIT_BRIDGE_BEARING_DIFFERENCE_DEGREES = 30.0
        private const val MIN_UNNAMED_BRIDGE_CONFIDENCE = 0.8
        private const val MAX_UNNAMED_BRIDGE_M = 18.0
        private const val MIN_UNNAMED_DIRECTION_M = 5.0
        private const val MAX_UNNAMED_BRIDGE_BEARING_DEGREES = 20.0
        private const val MIN_ZERO_SEAM_RAW_DISTANCE_M = 3.0
        private const val MAX_ZERO_SEAM_RAW_DISTANCE_M = 10.0
        private const val MIN_BEARING_GUIDANCE_SPEED_MPS = 4f
        private const val MIN_BEARING_EVIDENCE_DISTANCE_M = 8.0
        private const val BEARING_ACCURACY_DISTANCE_FACTOR = 0.75
        // provider interprets this as a symmetric range around the supplied heading. Sixty-five
        // degrees is broad enough for ordinary curves while still excluding the opposite road.
        private const val BEARING_GUIDANCE_RANGE_DEGREES = 65
        private const val SHARP_TURN_DEGREES = 45.0
        private const val MODERATE_TURN_DEGREES = 25.0
        private const val MAX_SUPPORTED_TURN_GAP_POINTS = 8
        private const val MAX_LONG_TURN_GAP_MS = 30_000L
        private const val MAX_LONG_TURN_SAMPLE_GAP_MS = 15_000L
        private const val MAX_LONG_TURN_JUMP_M = 100.0
        private const val MAX_LONG_TURN_ACCURACY_M = 12f
        private const val MIN_ALTERNATE_SNAP_CONFIDENCE = 0.75
        private const val EARTH_RADIUS_M = 6_371_008.8
        private const val MIN_GEOMETRY_LENGTH_M = 0.001
        private const val DUPLICATE_POINT_TOLERANCE_M = 0.01
        private const val JOIN_TOLERANCE_M = 1.0
        private const val MAX_RESPONSE_BYTES = 4 * 1024 * 1024
    }
}
