package com.roadconquest.app.matching

import com.roadconquest.app.data.TrackPoint
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37], manifest = Config.NONE)
class GeoapifyJunctionCoverageTest {
    private fun fixture(repeats: Int, latitude: Double, longitude: Double, fiveFixes: Boolean,
        junctionOffset: Double = 0.0, crossing: Boolean = false): Pair<String, List<TrackPoint>> {
        fun at(east: Double, north: Double = 0.0): JSONArray {
            val lon = longitude + east
            return JSONArray().put(if (lon > 180.0) lon - 360.0 else lon).put(latitude + north)
        }
        val line = JSONArray().put(at(-0.001))
        repeat(repeats) { i -> line.put(if (crossing && i % 2 == 1) JSONArray().put(-longitude).put(latitude) else at(0.0)) }
        line.put(at(0.0, 0.001))
        val steps = JSONArray()
            .put(JSONObject().put("from_index", 0).put("to_index", 1)
                .put("name", "Approach").put("road_class", "residential"))
            .put(JSONObject().put("from_index", 1).put("to_index", repeats)
                .put("name", "Junction").put("road_class", "residential"))
            .put(JSONObject().put("from_index", repeats).put("to_index", repeats + 1)
                .put("name", "Exit").put("road_class", "residential"))
        val coordinates = if (fiveFixes) listOf(at(-0.0009), at(-0.0007), at(0.0), at(0.0, 0.0006), at(0.0, 0.0009))
            else listOf(at(-0.0008), at(0.0), at(0.0, 0.0008))
        val points = coordinates.mapIndexed { i, p -> TrackPoint(i + 1L, p.getDouble(1), p.getDouble(0),
            3f, 8f, 0f, 1_000_000L + i * 10_000L, false) }
        val waypoints = JSONArray()
        coordinates.forEachIndexed { i, p ->
            val middle = i == coordinates.size / 2
            waypoints.put(JSONObject().put("original_index", i).put("original_location", p)
                .put("location", if (middle) at(0.0, junctionOffset) else p).put("match_type", "matched")
                .put("leg_index", 0).put("step_index", if (middle) 1 else if (i < coordinates.size / 2) 0 else 2))
        }
        return JSONObject().put("type", "FeatureCollection").put("features", JSONArray().put(
            JSONObject().put("geometry", JSONObject().put("type", "MultiLineString").put("coordinates", JSONArray().put(line)))
                .put("properties", JSONObject().put("mode", "drive").put("waypoints", waypoints)
                    .put("legs", JSONArray().put(JSONObject().put("steps", steps)))))).toString() to points
    }

    private fun parse(input: Pair<String, List<TrackPoint>>) =
        requireNotNull(GeoapifyMatcher("https://maps.example").parse(input.first, input.second))

    private fun assertSameCoverage(expected: MatchResult, actual: MatchResult) {
        assertEquals(expected.matchedPointConfidences, actual.matchedPointConfidences)
        assertEquals(expected.roads.map { it.name }, actual.roads.map { it.name })
        fun distinct(json: String): List<Pair<Double, Double>> {
            val array = JSONArray(json)
            val result = mutableListOf<Pair<Double, Double>>()
            for (i in 0 until array.length()) {
                val p = array.getJSONArray(i)
                val coordinate = p.getDouble(0) to p.getDouble(1)
                if (result.lastOrNull() != coordinate) result.add(coordinate)
            }
            return result
        }
        expected.roads.zip(actual.roads).forEach { (old, new) ->
            assertEquals(old.firstTimestamp, new.firstTimestamp)
            assertEquals(old.lastTimestamp, new.lastTimestamp)
            assertEquals(old.countTowardsRoads, new.countTowardsRoads)
            val a = distinct(old.coordinatesJson)
            val b = distinct(new.coordinatesJson)
            assertEquals(a.size, b.size)
            a.zip(b).forEach { (left, right) ->
                assertEquals(left.first, right.first, 1e-10)
                assertEquals(left.second, right.second, 1e-10)
            }
        }
    }

    @Test fun collapsedMultiEdgeJunctionKeepsTheCompleteApproachAndExit() {
        for (lat in listOf(0.0, 40.0, -40.0, 70.0)) for (five in listOf(false, true)) {
            val canonical = parse(fixture(1, lat, 10.0, five))
            assertEquals(listOf("Approach", "Exit"), canonical.roads.map { it.name })
            for (offset in listOf(0.0, 0.0000045)) {
                val expected = parse(fixture(1, lat, 10.0, five, offset))
                for (repeats in 2..8) assertSameCoverage(expected, parse(fixture(repeats, lat, 10.0, five, offset)))
            }
        }
    }

    @Test fun rejectedCollapsedJunctionPreservesIndependentlyVerifiedSides() {
        for (lat in listOf(0.0, 40.0, -40.0, 70.0)) for (repeats in 2..8) {
            val input = fixture(repeats, lat, 10.0, true, 0.0001)
            val missing = JSONObject(input.first)
            missing.getJSONArray("features").getJSONObject(0).getJSONObject("properties")
                .getJSONArray("waypoints").getJSONObject(2).put("match_type", "unmatched")
            val baseline = parse(missing.toString() to input.second)
            assertEquals(listOf("Approach", "Exit"), baseline.roads.map { it.name })
            assertEquals(baseline, parse(input))
            val short = parse(fixture(repeats, lat, 10.0, false, 0.0001))
            assertTrue(short.roads.isEmpty())
        }
    }

    @Test fun collapsedDateLineRepresentationsKeepBothDrivenRoads() {
        for (lat in listOf(0.0, 40.0, -40.0, 70.0)) for (repeats in 3..8) {
            val input = fixture(repeats, lat, 180.0, false, crossing = true)
            val result = parse(input)
            assertEquals(listOf("Approach", "Exit"), result.roads.map { it.name })
            assertEquals(input.second.map { it.id }.toSet(), result.matchedPointConfidences.keys)
        }
    }

    @Test fun variedTurnDirectionsRetainTheCanonicalCoverage() {
        val random = java.util.Random(812_704L)
        repeat(200) { case ->
            val latitude = listOf(0.0, 40.0, -40.0, 70.0)[case % 4]
            val heading = random.nextDouble() * 2 * Math.PI
            val turn = Math.toRadians(30.0 + random.nextDouble() * 120.0) * if (case % 2 == 0) 1 else -1
            val approachScale = 0.3 + random.nextDouble() * 0.7
            val exitScale = 0.3 + random.nextDouble() * 0.7
            val cosine = kotlin.math.cos(Math.toRadians(latitude))
            fun transform(coordinate: JSONArray): JSONArray {
                val x = (coordinate.getDouble(0) - 10.0) * cosine * approachScale
                val y = (coordinate.getDouble(1) - latitude) * exitScale
                return JSONArray().put(10.0 + (x * kotlin.math.cos(heading) + y * kotlin.math.cos(heading + turn)) / cosine)
                    .put(latitude + x * kotlin.math.sin(heading) + y * kotlin.math.sin(heading + turn))
            }
            fun rotated(repeats: Int): Pair<String, List<TrackPoint>> {
                val original = fixture(repeats, latitude, 10.0, case % 3 != 0)
                val response = JSONObject(original.first)
                val feature = response.getJSONArray("features").getJSONObject(0)
                val line = feature.getJSONObject("geometry").getJSONArray("coordinates").getJSONArray(0)
                for (i in 0 until line.length()) line.put(i, transform(line.getJSONArray(i)))
                val waypoints = feature.getJSONObject("properties").getJSONArray("waypoints")
                for (i in 0 until waypoints.length()) {
                    val point = waypoints.getJSONObject(i)
                    point.put("original_location", transform(point.getJSONArray("original_location")))
                    point.put("location", transform(point.getJSONArray("location")))
                }
                val fixes = original.second.map { point ->
                    val location = transform(JSONArray().put(point.longitude).put(point.latitude))
                    point.copy(longitude = location.getDouble(0), latitude = location.getDouble(1))
                }
                return response.toString() to fixes
            }
            val canonical = parse(rotated(1))
            assertEquals(listOf("Approach", "Exit"), canonical.roads.map { it.name })
            for (repeats in listOf(2, 3, 8)) assertSameCoverage(canonical, parse(rotated(repeats)))
        }
    }

    @Test fun aNonzeroLoopCannotBeTreatedAsACollapsedJunction() {
        val input = fixture(3, 40.0, 10.0, false)
        val response = JSONObject(input.first)
        response.getJSONArray("features").getJSONObject(0).getJSONObject("geometry")
            .getJSONArray("coordinates").getJSONArray(0).put(2, JSONArray().put(10.0002).put(40.0002))
        val waypoint = response.getJSONArray("features").getJSONObject(0).getJSONObject("properties")
            .getJSONArray("waypoints").getJSONObject(1)
        waypoint.put("location", JSONArray().put(10.00001).put(39.99999))
        val result = GeoapifyMatcher("https://maps.example").parse(response.toString(), input.second)
        assertTrue("A nearby point outside every real edge must stay pending", result == null || result.roads.isEmpty())
    }
}
