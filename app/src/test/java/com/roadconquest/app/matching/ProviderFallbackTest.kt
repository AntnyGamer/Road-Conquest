package com.roadconquest.app.matching

import android.app.Application
import com.roadconquest.app.data.TrackPoint
import com.roadconquest.app.services.ProviderHealth
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37], manifest = Config.NONE, application = Application::class)
class ProviderFallbackTest {
    private val points = listOf(
        TrackPoint(1, 47.567028, 10.694703, 3f, 8f, 0f, Instant.parse("2026-01-01T07:09:34Z").toEpochMilli(), false),
        TrackPoint(2, 47.567783, 10.6950319, 3f, 8f, 0f, Instant.parse("2026-01-01T07:09:45Z").toEpochMilli(), false)
    )
    private val example get() = javaClass.getResource("/geoapify-public-example.json")!!.readText()
    private class Fake(var response: MatchResult? = null, override var unavailable: Boolean = false, var cooldown: Long = 0) : RoadMatcher {
        var calls = 0
        override fun match(points: List<TrackPoint>): MatchResult? { calls++; return response }
        override fun rateLimitRemainingMs() = cooldown
    }
    @Test fun unavailableProvidersFallThroughInOrderAndPrimaryRecovers() {
        val primary = Fake(unavailable = true)
        val secondary = Fake(MatchResult(emptyList(), emptyMap()))
        val matcher = FallbackRoadMatcher(listOf(primary, secondary))
        assertNotNull(matcher.match(points))
        assertEquals(1, primary.calls); assertEquals(1, secondary.calls)
        primary.cooldown = 1000
        assertNotNull(matcher.match(points))
        assertEquals(1, primary.calls); assertEquals(2, secondary.calls)
        primary.cooldown = 0; primary.unavailable = false; primary.response = MatchResult(emptyList(), emptyMap())
        assertNotNull(matcher.match(points))
        assertEquals(2, primary.calls); assertEquals(2, secondary.calls)
    }
    @Test fun validAmbiguityCannotChooseAnEasierProvider() {
        val primary = Fake()
        val secondary = Fake(MatchResult(emptyList(), emptyMap()))
        assertNull(FallbackRoadMatcher(listOf(primary, secondary)).match(points))
        assertEquals(0, secondary.calls)
        primary.response = MatchResult(emptyList(), emptyMap())
        assertNotNull(FallbackRoadMatcher(listOf(primary, secondary)).match(points))
        assertEquals(0, secondary.calls)
    }
    @Test fun allExhaustedProvidersKeepTheTracePendingUntilEarliestRecovery() {
        val matcher = FallbackRoadMatcher(listOf(Fake(cooldown = 2000), Fake(cooldown = 9000)))
        assertNull(matcher.match(points)); assertTrue(matcher.unavailable)
        assertEquals(2000L, matcher.rateLimitRemainingMs())
        assertNull(FallbackRoadMatcher(emptyList()).match(points))
    }
    @Test fun smallerFallbackGetsOnePrefixAndCannotResolveUnsubmittedFixes() {
        val primary = Fake(unavailable = true)
        var submitted = emptyList<TrackPoint>()
        val fallback = object : RoadMatcher {
            override val maxPoints = 25
            override val unavailable = false
            override fun rateLimitRemainingMs() = 0L
            override fun match(points: List<TrackPoint>): MatchResult {
                submitted = points
                return MatchResult(emptyList(), points.associate { it.id to 0.9 })
            }
        }
        val matcher = FallbackRoadMatcher(listOf(primary, fallback))
        val input = List(200) { points.first().copy(id = it + 1L) }
        assertEquals(200, matcher.maxPoints)
        val result = requireNotNull(matcher.match(input))
        assertEquals(input.take(25), submitted)
        assertEquals((1L..25L).toSet(), result.matchedPointConfidences.keys)
        primary.cooldown = 1000L
        assertEquals(25, matcher.maxPoints)
        primary.cooldown = 0L
        assertEquals(200, matcher.maxPoints)
    }
    @Test fun realGeoapifyPublicExamplePassesExistingGeometryAndSnapValidation() {
        val result = requireNotNull(GeoapifyMatcher("https://maps.example").parse(example, points))
        assertTrue(result.roads.isNotEmpty())
        assertEquals(setOf(1L, 2L), result.matchedPointConfidences.keys)
        assertTrue(result.roads.all { it.name == "Morisse" })
        assertTrue(result.roads.all { it.countTowardsRoads })
    }
    @Test fun unmatchedTailCannotEraseEarlierVerifiedLegs() {
        val root = JSONObject(example)
        val tail = points.last().copy(id = 3L, longitude = 10.696)
        root.getJSONArray("features").getJSONObject(0).getJSONObject("properties")
            .getJSONArray("waypoints").put(JSONObject()
                .put("original_index", 2)
                .put("original_location", org.json.JSONArray().put(tail.longitude).put(tail.latitude))
                .put("match_type", "unmatched"))
        val result = requireNotNull(GeoapifyMatcher("https://maps.example").parse(root.toString(), points + tail))
        assertTrue(result.roads.isNotEmpty())
        assertEquals(setOf(1L, 2L), result.matchedPointConfidences.keys)
    }

    /** A normalized response with full matched evidence, constructed without a provider call. */
    private fun fixtureLine(
        line: JSONArray, steps: JSONArray, snapped: List<JSONArray>, stepIndices: List<Int>,
        timestamps: List<Long>
    ): Pair<String, List<TrackPoint>> {
        val points = snapped.mapIndexed { i, p ->
            TrackPoint(i + 1L, p.getDouble(1), p.getDouble(0), 3f, 8f, 0f, timestamps[i], false)
        }
        val waypoints = JSONArray()
        for (i in points.indices) {
            waypoints.put(JSONObject()
                .put("original_index", i).put("original_location", snapped[i])
                .put("location", snapped[i]).put("match_type", "matched")
                .put("leg_index", 0).put("step_index", stepIndices[i]))
        }
        val root = JSONObject().put("type", "FeatureCollection").put("features", JSONArray().put(
            JSONObject().put("geometry", JSONObject().put("type", "MultiLineString")
                .put("coordinates", JSONArray().put(line)))
                .put("properties", JSONObject().put("mode", "drive")
                    .put("legs", JSONArray().put(JSONObject().put("steps", steps)))
                    .put("waypoints", waypoints))
        ))
        return root.toString() to points
    }

    @Test fun dateLineRoadUsesShortWrappedProjectionWithoutMissingMatchedSegments() {
        val a = org.json.JSONArray().put(179.999).put(0.0)
        val b = org.json.JSONArray().put(-179.999).put(0.0)
        val snapped = listOf(org.json.JSONArray().put(179.9994).put(0.0),
            org.json.JSONArray().put(-179.9994).put(0.0))
        val steps = JSONArray().put(JSONObject().put("from_index", 0).put("to_index", 1)
            .put("name", "Date Line Road").put("road_class", "residential"))
        val (json, samples) = fixtureLine(JSONArray().put(a).put(b), steps, snapped, listOf(0, 0),
            listOf(1_000_000L, 1_015_000L))
        val result = requireNotNull(GeoapifyMatcher("https://maps.example").parse(json, samples))
        assertEquals(setOf(1L, 2L), result.matchedPointConfidences.keys)
        assertTrue(result.roads.isNotEmpty())
        // Short real crossing, never a fabricated 40,000-km detour through Greenwich.
        val geometry = JSONArray(result.roads.first().coordinatesJson)
        assertTrue(geometry.length() >= 2)
        assertTrue((0 until geometry.length()).all { i ->
            kotlin.math.abs(geometry.getJSONArray(i).getDouble(0)) > 179.99
        })
    }

    @Test fun zeroLengthJunctionStepDoesNotDiscardValidApproachAndExit() {
        val coords = JSONArray().put(JSONArray().put(0.0).put(0.0))
            .put(JSONArray().put(0.001).put(0.0))
            .put(JSONArray().put(0.001).put(0.001))
        val steps = JSONArray()
            .put(JSONObject().put("from_index", 0).put("to_index", 1)
                .put("name", "Approach").put("road_class", "residential"))
            .put(JSONObject().put("from_index", 1).put("to_index", 1)
                .put("name", "Junction").put("road_class", "residential"))
            .put(JSONObject().put("from_index", 1).put("to_index", 2)
                .put("name", "Exit").put("road_class", "residential"))
        val snapped = listOf(JSONArray().put(0.0002).put(0.0),
            JSONArray().put(0.001).put(0.0),
            JSONArray().put(0.001).put(0.0008))
        val (json, samples) = fixtureLine(coords, steps, snapped, listOf(0, 1, 2),
            listOf(1_000_000L, 1_010_000L, 1_020_000L))
        val matcher = GeoapifyMatcher("https://maps.example")
        val result = requireNotNull(matcher.parse(json, samples))
        assertEquals(setOf(1L, 2L, 3L), result.matchedPointConfidences.keys)
        assertTrue(result.roads.any { it.name == "Approach" })
        assertTrue(result.roads.any { it.name == "Exit" })

        // A point more than 3 meters away from that degenerate step is NOT
        // proof of a junction; it must stay pending rather than draw a false turn.
        val invalid = JSONObject(json)
        invalid.getJSONArray("features").getJSONObject(0).getJSONObject("properties")
            .getJSONArray("waypoints").getJSONObject(1)
            .put("location", JSONArray().put(0.0011).put(0.0))
        val rejected = runCatching { matcher.parse(invalid.toString(), samples) }.getOrNull()
        assertTrue(rejected == null || rejected.matchedPointConfidences.isEmpty())
    }

    @Test fun namedPrimaryRoadsCountButMotorwayLinkRampsDoNot() {
        fun asClass(roadClass: String): MatchResult {
            val sample = JSONObject(example)
            val steps = sample.getJSONArray("features").getJSONObject(0).getJSONObject("properties")
                .getJSONArray("legs").getJSONObject(0).getJSONArray("steps")
            for (i in 0 until steps.length()) steps.getJSONObject(i).put("road_class", roadClass)
            return requireNotNull(GeoapifyMatcher("https://maps.example").parse(sample.toString(), points))
        }
        assertTrue(asClass("primary").roads.all { it.countTowardsRoads })
        assertTrue(asClass("motorway_link").roads.none { it.countTowardsRoads })
    }

    @Test fun verifiedUnnamedDrivingRoadsAlsoEarnRoadCredit() {
        val root = JSONObject(example)
        val steps = root.getJSONArray("features").getJSONObject(0).getJSONObject("properties")
            .getJSONArray("legs").getJSONObject(0).getJSONArray("steps")
        for (i in 0 until steps.length()) steps.getJSONObject(i).put("name", "")
        val result = requireNotNull(GeoapifyMatcher("https://maps.example").parse(root.toString(), points))
        assertTrue(result.roads.isNotEmpty())
        assertTrue(result.roads.all { it.countTowardsRoads })
    }

    @Test fun interpolatedFixOrChangedOriginalCannotEarnCredit() {
        for (field in listOf("match_type", "original_index", "leg_index")) {
            val root = JSONObject(example)
            root.getJSONArray("features").getJSONObject(0).getJSONObject("properties").getJSONArray("waypoints").getJSONObject(0)
                .put(field, if (field == "match_type") "interpolated" else 12)
            val result = runCatching { GeoapifyMatcher("https://maps.example").parse(root.toString(), points) }.getOrNull()
            assertTrue(result == null || result.matchedPointConfidences.isEmpty())
        }
        assertNull(runCatching { GeoapifyMatcher("https://maps.example").parse(example, points.map { it.copy(longitude = -74.0) }) }.getOrNull())
    }
    @Test fun quotaResponseHonorsRetryAfterAndSkipsProviderWhileCoolingDown() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(429).addHeader("Retry-After", "7200"))
            server.start()
            var now = 1000L
            val health = ProviderHealth { now }
            val primary = GeoapifyMatcher(server.url("/").toString().trimEnd('/'), health)
            val backup = Fake(MatchResult(emptyList(), emptyMap()))
            val matcher = FallbackRoadMatcher(listOf(primary, backup))
            assertNotNull(matcher.match(points)); assertTrue(primary.unavailable)
            assertEquals(7_200_000L, primary.rateLimitRemainingMs())
            assertNotNull(matcher.match(points)); assertEquals(1, server.requestCount)
            now += 7_200_001
            server.enqueue(MockResponse().setBody(example).addHeader("Content-Type", "application/json"))
            assertTrue(requireNotNull(matcher.match(points)).roads.isNotEmpty())
            assertEquals(2, server.requestCount)
            val sent = server.takeRequest()
            assertEquals("POST", sent.method)
            assertEquals("/v1/mapmatching", sent.path)
            assertFalse(sent.body.readUtf8().contains("apiKey"))
        }
    }
    @Test fun successfulResponseCacheReparsesWithCurrentIdsWithoutAnotherQuotaCharge() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(example)); server.start()
            val matcher = GeoapifyMatcher(server.url("/").toString(), ProviderHealth())
            assertNotNull(matcher.match(points))
            val remapped = requireNotNull(matcher.match(points.map { it.copy(id = it.id + 20) }))
            assertEquals(setOf(21L, 22L), remapped.matchedPointConfidences.keys)
            assertEquals(1, server.requestCount)
        }
    }
}
