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
    @Test fun invalidGeoapifyCoordinatesNeverThrowOrUseNetwork() {
        MockWebServer().use { server ->
            server.start()
            val matcher = GeoapifyMatcher(server.url("/").toString(), ProviderHealth())
            for (bad in listOf(
                points[0].copy(latitude = Double.NaN),
                points[0].copy(latitude = 91.0),
                points[0].copy(longitude = Double.POSITIVE_INFINITY),
                points[0].copy(longitude = -181.0)
            )) {
                val input = listOf(bad, points[1])
                assertNull(matcher.cachedMatch(input))
                assertNull(matcher.match(input))
                assertFalse(matcher.lastCallUsedNetwork)
            }
            assertEquals(0, server.requestCount)
        }
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
    @Test fun incompleteCacheDuringPrimaryCooldownStillTriesReadyBackup() {
        val result = MatchResult(emptyList(), emptyMap())
        val backup = Fake(result)
        for (cache in listOf(
            MatchResult(emptyList(), emptyMap(), explicitNoMatch = true),
            requireNotNull(GeoapifyMatcher("https://unused.example").parse(example, points))
                .copy(matchedPointConfidences = mapOf(1L to 0.9))
        )) {
            val primary = object : RoadMatcher {
                override val unavailable = true
                override fun rateLimitRemainingMs() = 60_000L
                override fun cachedMatch(points: List<TrackPoint>) = cache
                override fun match(points: List<TrackPoint>): MatchResult? =
                    error("Cooldown must prevent primary network calls")
            }
            val router = FallbackRoadMatcher(listOf(primary, backup))
            assertEquals(result, router.match(points))
            assertFalse(router.unavailable)
        }
        assertEquals(2, backup.calls)
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
        // Primary remains technically ready despite its failed response.
        // Dynamic maxPoints may report 200, but only 25 points were submitted.
        assertEquals(200, matcher.maxPoints)
        assertEquals(25, matcher.lastUsedProviderPointLimit)
        primary.cooldown = 1000L
        assertEquals(25, matcher.maxPoints)
        primary.cooldown = 0L
        assertEquals(200, matcher.maxPoints)
    }
    @Test fun explicitNoMatchIsDistinctFromAmbiguousUnmatchedGeometry() {
        val primary = GeoapifyMatcher("https://maps.example")
        val backup = LocationIqMatcher("https://maps.example")
        assertTrue(requireNotNull(primary.parse("""{"type":"FeatureCollection","features":[]}""", points)).explicitNoMatch)
        assertTrue(requireNotNull(backup.parse("""{"code":"NoMatch"}""", points)).explicitNoMatch)
        assertTrue(requireNotNull(backup.parse("""{"code":"NoSegment"}""", points)).explicitNoMatch)
        // Geometry that has not met the strict confidence gates is NOT proof
        // that the provider cannot match this road later with fresh context.
        assertFalse(MatchResult(emptyList(), emptyMap()).explicitNoMatch)
    }

    @Test fun smallerFallbackTargetsEligibleFixesRatherThanDeferredContext() {
        val input = List(100) { points.first().copy(id = it + 1L, matched = it != 8 && it != 40) }
        val primary = Fake(unavailable = true)
        var submitted = emptyList<TrackPoint>()
        val backup = object : RoadMatcher {
            override val maxPoints = 25
            override val unavailable = false
            override fun rateLimitRemainingMs() = 0L
            override fun match(points: List<TrackPoint>): MatchResult {
                submitted = points
                return MatchResult(emptyList(), points.associate { it.id to .9 })
            }
        }
        val router = FallbackRoadMatcher(listOf(primary, backup))
        // The unmatched fix at index 8 is deferred context, not eligible work.
        val result = requireNotNull(router.match(input, setOf(41L)))
        assertEquals(input.subList(37, 62), submitted)
        assertEquals(submitted, router.lastSubmittedPoints(input))
        assertEquals(submitted.map { it.id }.toSet(), result.matchedPointConfidences.keys)
        assertEquals(37, router.lastUsedProviderPointOffset)
        assertEquals(25, router.lastNetworkProviderPointLimit)
        // When the usual prefix contains any eligible fix it must remain unchanged.
        requireNotNull(router.match(input, setOf(9L, 41L)))
        assertEquals(input.take(25), submitted)
        assertEquals(0, router.lastUsedProviderPointOffset)
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

    @Test fun malformedGeoapifyStepKeepsBothIndependentVerifiedSides() {
        fun at(lat: Double) = JSONArray().put(-74.0).put(lat)
        val line = JSONArray().put(at(40.0)).put(at(40.0001))
            .put(at(40.0002)).put(at(40.0003))
        val steps = JSONArray()
            .put(JSONObject().put("from_index", 0).put("to_index", 1)
                .put("name", "Approach").put("road_class", "residential"))
            .put("malformed middle step")
            .put(JSONObject().put("from_index", 2).put("to_index", 3)
                .put("name", "Exit").put("road_class", "residential"))
        val (json, fixes) = fixtureLine(line, steps, listOf(
            at(40.0), at(40.0001), at(40.0002), at(40.0003)
        ), listOf(0, 0, 2, 2), listOf(1_000_000L, 1_003_000L, 1_006_000L, 1_009_000L))
        val result = requireNotNull(GeoapifyMatcher("https://maps.example").parse(json, fixes))
        assertEquals(listOf("Approach", "Exit"), result.roads.map { it.name })
        assertEquals(setOf(1L, 2L, 4L), result.matchedPointConfidences.keys)
        assertFalse(3L in result.matchedPointConfidences)

        // Even two pieces of the SAME named road cannot bridge the rejected step.
        val root = JSONObject(json)
        val providerSteps = root.getJSONArray("features").getJSONObject(0)
            .getJSONObject("properties").getJSONArray("legs").getJSONObject(0)
            .getJSONArray("steps")
        providerSteps.getJSONObject(2).put("name", "Approach")
        val sameRoad = requireNotNull(GeoapifyMatcher("https://maps.example").parse(root.toString(), fixes))
        assertEquals(2, sameRoad.roads.size)
        assertEquals(setOf(1L, 2L, 4L), sameRoad.matchedPointConfidences.keys)

        // If the one remaining stretch is also corrupt, it cannot earn road credit.
        providerSteps.put(0, "bad start").put(2, "bad end")
        assertNull(GeoapifyMatcher("https://maps.example").parse(root.toString(), fixes))
    }

    @Test fun malformedGeoapifyWaypointCannotJoinAcrossAnUnmatchedMiddle() {
        fun at(lat: Double) = JSONArray().put(-74.0).put(lat)
        val coords = (0..4).map { at(40.0 + it * 0.0001) }
        val (json, fixes) = fixtureLine(
            JSONArray(coords), JSONArray().put(JSONObject()
                .put("from_index", 0).put("to_index", 4)
                .put("name", "Main Street").put("road_class", "residential")),
            coords, List(5) { 0 }, List(5) { 1_000_000L + it * 3_000L }
        )
        val root = JSONObject(json)
        root.getJSONArray("features").getJSONObject(0).getJSONObject("properties")
            .getJSONArray("waypoints").getJSONObject(2).put("location", "invalid")
        val result = requireNotNull(GeoapifyMatcher("https://maps.example").parse(root.toString(), fixes))
        assertEquals(2, result.roads.size)
        assertEquals(setOf(1L, 2L, 5L), result.matchedPointConfidences.keys)
        assertFalse(3L in result.matchedPointConfidences)
        assertFalse(4L in result.matchedPointConfidences)
    }

    @Test fun disjointGeoapifyFeaturesKeepVerifiedSegmentsWithoutAcceptingAlternatives() {
        fun at(lat: Double) = JSONArray().put(-74.0).put(lat)
        fun feature(a: Double, b: Double): Pair<JSONObject, List<TrackPoint>> {
            val first = at(a)
            val last = at(b)
            val (json, fixes) = fixtureLine(
                JSONArray().put(first).put(last),
                JSONArray().put(JSONObject().put("from_index", 0).put("to_index", 1)
                    .put("name", "Verified Road").put("road_class", "residential")),
                listOf(first, last), listOf(0, 0), listOf(1_000_000L, 1_003_000L)
            )
            return JSONObject(json).getJSONArray("features").getJSONObject(0) to fixes
        }
        val (left, firstFixes) = feature(40.0, 40.0001)
        val (right, laterFixes) = feature(40.0002, 40.0003)
        val waypoints = right.getJSONObject("properties").getJSONArray("waypoints")
        waypoints.getJSONObject(0).put("original_index", 2)
        waypoints.getJSONObject(1).put("original_index", 3)
        val allFixes = firstFixes + laterFixes.mapIndexed { i, p ->
            p.copy(id = i + 3L, timestampMillis = 1_006_000L + i * 3_000L)
        }
        fun response(second: JSONObject) = JSONObject().put("type", "FeatureCollection")
            .put("features", JSONArray().put(left).put(second)).toString()
        val matcher = GeoapifyMatcher("https://maps.example")
        val result = requireNotNull(matcher.parse(response(right), allFixes))
        assertEquals(2, result.roads.size)
        assertEquals(setOf(1L, 2L, 4L), result.matchedPointConfidences.keys)

        // A bad independent feature cannot erase the verified approach.
        right.put("geometry", JSONObject().put("type", "invalid"))
        val recovered = requireNotNull(matcher.parse(response(right), allFixes))
        assertEquals(setOf(1L, 2L), recovered.matchedPointConfidences.keys)

        // Two hypotheses claiming identical GPS fixes are ambiguous, not evidence.
        assertNull(matcher.parse(response(left), allFixes))
    }

    @Test fun multiLegGeoapifyResponseKeepsOnlyEachVerifiedLeg() {
        fun at(lat: Double) = JSONArray().put(-74.0).put(lat)
        val routePoints = (0..3).map { at(40.0 + it * 0.0001) }
        val fixes = routePoints.mapIndexed { i, p ->
            TrackPoint(i + 1L, p.getDouble(1), p.getDouble(0), 3f, 8f, 0f,
                1_000_000L + i * 3_000L, false)
        }
        fun leg(name: String) = JSONObject().put("steps", JSONArray().put(
            JSONObject().put("from_index", 0).put("to_index", 1)
                .put("name", name).put("road_class", "residential")
        ))
        val waypoints = JSONArray()
        routePoints.forEachIndexed { i, point ->
            waypoints.put(JSONObject()
                .put("original_index", i).put("original_location", point)
                .put("location", point).put("match_type", "matched")
                .put("leg_index", i / 2).put("step_index", 0))
        }
        val legs = JSONArray().put(leg("Approach")).put(leg("Exit"))
        val lines = JSONArray()
            .put(JSONArray().put(routePoints[0]).put(routePoints[1]))
            .put(JSONArray().put(routePoints[2]).put(routePoints[3]))
        val feature = JSONObject().put("properties", JSONObject()
            .put("mode", "drive").put("legs", legs).put("waypoints", waypoints))
            .put("geometry", JSONObject().put("type", "MultiLineString")
                .put("coordinates", lines))
        val root = JSONObject().put("type", "FeatureCollection")
            .put("features", JSONArray().put(feature))
        val matcher = GeoapifyMatcher("https://maps.example")
        val result = requireNotNull(matcher.parse(root.toString(), fixes))
        assertEquals(listOf("Approach", "Exit"), result.roads.map { it.name })
        assertEquals(setOf(1L, 2L, 4L), result.matchedPointConfidences.keys)
        assertFalse(3L in result.matchedPointConfidences)

        // One bad leg cannot erase a good sibling or earn a fabricated connector.
        legs.put(1, JSONObject().put("steps", JSONArray().put("invalid")))
        val salvaged = requireNotNull(matcher.parse(root.toString(), fixes))
        assertEquals(listOf("Approach"), salvaged.roads.map { it.name })
        assertEquals(setOf(1L, 2L), salvaged.matchedPointConfidences.keys)

        // Unpaired route lines/legs have no reliable geometry association.
        // Never guess which road the unmatched leg belongs to.
        lines.remove(1)
        assertNull(matcher.parse(root.toString(), fixes))
    }

    @Test fun namedPrimaryAndServiceOtherRoadsCountButMotorwayLinkRampsDoNot() {
        fun asClass(roadClass: String): MatchResult {
            val sample = JSONObject(example)
            val steps = sample.getJSONArray("features").getJSONObject(0).getJSONObject("properties")
                .getJSONArray("legs").getJSONObject(0).getJSONArray("steps")
            for (i in 0 until steps.length()) steps.getJSONObject(i).put("road_class", roadClass)
            return requireNotNull(GeoapifyMatcher("https://maps.example").parse(sample.toString(), points))
        }
        assertTrue(asClass("primary").roads.all { it.countTowardsRoads })
        assertTrue(asClass("service_other").roads.all { it.countTowardsRoads })
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
        // A response tied to different original GPS locations must earn no roads or fixes.
        // A valid but fully unmatched response may be represented as an empty MatchResult.
        val rejected = runCatching {
            GeoapifyMatcher("https://maps.example").parse(example, points.map { it.copy(longitude = -74.0) })
        }.getOrNull()
        assertTrue(rejected == null || (rejected.roads.isEmpty() && rejected.matchedPointConfidences.isEmpty()))
    }
    @Test fun staleGatewayPermitUsesBackupAndItsShortRetryWithoutTreatingOrdinary409AsAnOutage() {
        for (code in listOf("budget_conflict", "other_conflict")) {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setResponseCode(409).addHeader("Retry-After", "1")
                    .addHeader("X-RoadConquest-Error", code))
                server.start()
                val primary = GeoapifyMatcher(server.url("/").toString().trimEnd('/'), ProviderHealth { 1000L })
                val backup = Fake(MatchResult(emptyList(), emptyMap()))
                val router = FallbackRoadMatcher(listOf(primary, backup))
                router.match(points)
                assertEquals(code == "budget_conflict", primary.unavailable)
                assertEquals(if (code == "budget_conflict") 1000L else 0L, primary.rateLimitRemainingMs())
                assertEquals(if (code == "budget_conflict") 1 else 0, backup.calls)
            }
        }
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
    @Test fun exactTwoMinuteOldResponseDoesNotConsumeAnotherGeoapifyCredit() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(example))
            server.enqueue(MockResponse().setBody(example))
            server.start()
            val matcher = GeoapifyMatcher(server.url("/").toString(), ProviderHealth())
            assertNotNull(matcher.match(points))
            val storedAt = GeoapifyMatcher::class.java.getDeclaredField("cachedAt").apply { isAccessible = true }
            storedAt.setLong(matcher, System.currentTimeMillis() - 2 * 60_000L)
            assertNotNull(matcher.match(points))
            assertEquals(1, server.requestCount)
            storedAt.setLong(matcher, System.currentTimeMillis() - 16 * 60_000L)
            assertNotNull(matcher.match(points))
            assertEquals(2, server.requestCount)
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

    @Test fun completeCachedAnswerBypassesCooldownWithoutBackupOrNetworkAttempt() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(example)); server.start()
            val origin = server.url("/").toString()
            val health = ProviderHealth()
            val primary = GeoapifyMatcher(origin, health)
            val backup = Fake(MatchResult(emptyList(), emptyMap()))
            val router = FallbackRoadMatcher(listOf(primary, backup))
            val first = requireNotNull(router.match(points))
            assertTrue(router.lastCallUsedNetwork)
            health.failed("matching:$origin", 503, "60")
            val cached = requireNotNull(router.match(points))
            assertEquals(first, cached)
            assertFalse(router.lastCallUsedNetwork)
            assertEquals(0, backup.calls)
            assertEquals(1, server.requestCount)
            // Cache keys are exact; a changed trace cannot use that answer.
            assertNull(router.cachedMatch(points.map { it.copy(timestampMillis = it.timestampMillis + 1000) }))
        }
    }

    @Test fun cachePreflightCannotSuppressFreshEvidenceWithPartialOrNegativeResults() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"type":"FeatureCollection","features":[]}"""))
            server.start()
            val primary = GeoapifyMatcher(server.url("/").toString())
            assertNotNull(primary.match(points))
            assertFalse(requireNotNull(primary.cachedMatch(points)).completelyCoversPending(points))
            val backup = object : RoadMatcher {
                override val unavailable = false
                override fun rateLimitRemainingMs() = 0L
                override fun cachedMatch(points: List<TrackPoint>): MatchResult? =
                    error("A valid primary NoMatch must preserve provider precedence")
                override fun match(points: List<TrackPoint>): MatchResult? = error("Must not shop for an easier match")
            }
            val router = FallbackRoadMatcher(listOf(primary, backup))
            assertTrue(requireNotNull(router.cachedMatch(points)).explicitNoMatch)
            val good = requireNotNull(GeoapifyMatcher("https://unused.example").parse(example, points))
            assertTrue(good.completelyCoversPending(points))
            assertFalse(good.copy(matchedPointConfidences = mapOf(1L to 0.9)).completelyCoversPending(points))
            assertFalse(good.copy(matchedPointConfidences = mapOf(1L to 0.1, 2L to 0.9)).completelyCoversPending(points))
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun realPrimaryFailureStillCountsWhenBackupAnswerIsCached() {
        val primary = Fake(unavailable = true)
        val cachedBackup = object : RoadMatcher {
            override val maxPoints = 25
            override val unavailable = false
            override val lastCallUsedNetwork = false
            override fun rateLimitRemainingMs() = 0L
            override fun match(points: List<TrackPoint>) = MatchResult(emptyList(), emptyMap())
        }
        val router = FallbackRoadMatcher(listOf(primary, cachedBackup))
        assertNotNull(router.match(points))
        assertTrue(router.lastCallUsedNetwork)
        assertEquals(200, router.lastNetworkProviderPointLimit)
        assertEquals(25, router.lastUsedProviderPointLimit)
    }

    @Test fun queueRetryInstructionDoesNotCreateThirtySecondProviderOutage() {
        var now = 1000L
        val health = ProviderHealth { now }
        health.failed("queue", 503, "10", "queue_busy")
        health.failed("provider", 503, "10")
        assertEquals(10_000L, health.remaining("queue"))
        assertEquals(30_000L, health.remaining("provider"))
        now += 10_001
        assertEquals(0L, health.remaining("queue"))
        assertTrue(health.remaining("provider") > 0)
    }
}
