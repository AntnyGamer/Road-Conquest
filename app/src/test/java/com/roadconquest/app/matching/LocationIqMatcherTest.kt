package com.roadconquest.app.matching

import android.app.Application
import com.roadconquest.app.data.TrackPoint
import com.roadconquest.app.services.ProviderHealth
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37], manifest = Config.NONE, application = Application::class)
class LocationIqMatcherTest {
    private val example get() = javaClass.getResource("/locationiq-public-example.json")!!.readText()
    private val points: List<TrackPoint> get() {
        val input = JSONObject(javaClass.getResource("/locationiq-public-input.json")!!.readText())
        val coords = input.getJSONArray("points")
        return (0 until coords.length()).map { i -> TrackPoint(i + 1L,
            coords.getJSONArray(i).getDouble(1), coords.getJSONArray(i).getDouble(0),
            3f, 8f, 0f, input.getJSONArray("times").getLong(i) * 1000, false) }
    }

    @Test fun livePublicFixtureUsesProviderConfidenceAndWithholdsAmbiguousEndpoint() {
        val result = requireNotNull(LocationIqMatcher("https://maps.example").parse(example, points))
        assertTrue(result.roads.isNotEmpty())
        assertTrue(result.roads.all { it.name == "Morisse" && it.confidence > 0.97 && it.countTowardsRoads })
        assertEquals(setOf(1L, 2L, 3L, 4L), result.matchedPointConfidences.keys)
        assertFalse("Ambiguous endpoint must stay queued", 5L in result.matchedPointConfidences)
    }

    @Test fun parsedResponseMatchesStringPathWithoutMutatingProviderEvidence() {
        val validator = RoadGeometryValidator()
        val root = JSONObject(example)
        val original = root.toString()
        val blocked = setOf(points[2].id)
        assertEquals(validator.parse(original, points, blocked), validator.parse(root, points, blocked))
        assertEquals(original, root.toString())
        assertEquals(validator.parse(original, points), validator.parse(root, points))
        assertEquals(original, root.toString())
        root.getJSONArray("tracepoints").getJSONObject(1).put("waypoint_index", 12)
        assertEquals(validator.parse(root.toString(), points), validator.parse(root, points))
    }

    @Test fun distantGeometryAndMalformedPointMappingCannotEarnCredit() {
        val matcher = LocationIqMatcher("https://maps.example")
        val far = matcher.parse(example, points.map { it.copy(longitude = it.longitude + 0.01) })
        assertTrue(far == null || far.roads.isEmpty())
        val shifted = JSONObject(example)
        shifted.getJSONArray("tracepoints").getJSONObject(1).put("waypoint_index", 12)
        assertNull(matcher.parse(shifted.toString(), points))
        val weak = JSONObject(example)
        weak.getJSONArray("matchings").getJSONObject(0).put("confidence", 0.1)
        val result = matcher.parse(weak.toString(), points)
        assertTrue(result == null || result.roads.none { it.confidence >= RoadGeometryValidator.MIN_ACCEPTABLE_CONFIDENCE })
    }

    @Test fun fallbackQuotaIsIndependentOfPrimaryAndRetryAfterPreventsAnotherCall() {
        MockWebServer().use { server ->
            server.start()
            var now = 1000L
            val health = ProviderHealth { now }
            val origin = server.url("/").toString()
            val primary = GeoapifyMatcher(origin, health)
            val fallback = LocationIqMatcher(origin, health)
            server.enqueue(MockResponse().setResponseCode(429).addHeader("Retry-After", "3600"))
            assertNull(fallback.match(points))
            assertTrue(fallback.unavailable)
            assertEquals(3_600_000L, fallback.rateLimitRemainingMs())
            assertEquals(0L, primary.rateLimitRemainingMs())
            assertNull(fallback.match(points))
            assertEquals(1, server.requestCount)
            now += 3_600_001L
            server.enqueue(MockResponse().setBody(example))
            assertTrue(requireNotNull(fallback.match(points)).roads.isNotEmpty())
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun exactTwoMinuteOldResponseDoesNotConsumeAnotherLocationIqRequest() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(example))
            server.enqueue(MockResponse().setBody(example))
            server.start()
            val matcher = LocationIqMatcher(server.url("/").toString(), ProviderHealth())
            assertNotNull(matcher.match(points))
            val storedAt = LocationIqMatcher::class.java.getDeclaredField("cachedAt").apply { isAccessible = true }
            storedAt.setLong(matcher, System.currentTimeMillis() - 2 * 60_000L)
            assertNotNull(matcher.match(points))
            assertEquals(1, server.requestCount)
            storedAt.setLong(matcher, System.currentTimeMillis() - 16 * 60_000L)
            assertNotNull(matcher.match(points))
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun phonePostsOnlyToGatewayAndCachedResponseReparsesCurrentPointIds() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody(example))
            val matcher = LocationIqMatcher(server.url("/").toString(), ProviderHealth())
            assertNotNull(matcher.match(points))
            val remapped = requireNotNull(matcher.match(points.map { it.copy(id = it.id + 20) }))
            assertEquals(setOf(21L, 22L, 23L, 24L), remapped.matchedPointConfidences.keys)
            assertEquals(1, server.requestCount)
            val sent = server.takeRequest()
            assertEquals("POST", sent.method)
            assertEquals("/v1/locationiq/mapmatching", sent.path)
            val payload = JSONObject(sent.body.readUtf8())
            assertEquals(3.0, payload.getJSONArray("waypoints").getJSONObject(0).getDouble("accuracyMeters"), 0.0)
            assertFalse(payload.has("key"))
            assertNull(sent.getHeader("Authorization"))
        }
    }

    @Test fun validNoMatchIsNotAnOutageAndOversizedDirectInputDoesNotSpend() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("""{"code":"NoMatch"}"""))
            val matcher = LocationIqMatcher(server.url("/").toString(), ProviderHealth())
            assertTrue(requireNotNull(matcher.match(points)).roads.isEmpty())
            assertFalse(matcher.unavailable)
            assertEquals(0L, matcher.rateLimitRemainingMs())
            assertNull(matcher.match(List(26) { points.first().copy(id = it + 1L) }))
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun realAdaptersFailOverOnPrimaryQuotaThenRestorePrimaryAfterCooldown() {
        MockWebServer().use { server ->
            server.start()
            var now = 1000L
            val health = ProviderHealth { now }
            val origin = server.url("/").toString()
            val matcher = FallbackRoadMatcher(listOf(GeoapifyMatcher(origin, health), LocationIqMatcher(origin, health)))
            server.enqueue(MockResponse().setResponseCode(429).addHeader("Retry-After", "120"))
            server.enqueue(MockResponse().setBody(example))
            assertTrue(requireNotNull(matcher.match(points)).roads.isNotEmpty())
            assertEquals("/v1/mapmatching", server.takeRequest().path)
            assertEquals("/v1/locationiq/mapmatching", server.takeRequest().path)
            assertEquals(25, matcher.maxPoints)
            assertNotNull(matcher.match(points)) // fallback's short cache; no extra calls
            assertEquals(2, server.requestCount)
            now += 120_001L
            server.enqueue(MockResponse().setBody("""{"type":"FeatureCollection","features":[]}"""))
            assertTrue(requireNotNull(matcher.match(points)).roads.isEmpty())
            assertEquals("/v1/mapmatching", server.takeRequest().path)
            assertEquals(200, matcher.maxPoints)
            assertEquals(3, server.requestCount)
        }
    }
}
