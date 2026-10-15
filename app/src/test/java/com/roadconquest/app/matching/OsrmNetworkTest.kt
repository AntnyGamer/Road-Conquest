package com.roadconquest.app.matching

import com.roadconquest.app.data.TrackPoint
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37], manifest = Config.NONE)
class OsrmNetworkTest {
    private val points = listOf(
        TrackPoint(1, 40.0, -74.0, 3f, 5f, 270f, 100_000, false),
        TrackPoint(2, 40.0, -74.001, 99f, 5f, 270f, 100_000, false)
    )
    private val valid = """{"code":"Ok","tracepoints":[{"matchings_index":0,"waypoint_index":0,"alternatives_count":0,"location":[-74,40]},{"matchings_index":0,"waypoint_index":1,"alternatives_count":0,"location":[-74.001,40]}],"matchings":[{"confidence":0.9,"legs":[{"steps":[{"name":"Rue cité","ref":"NJ 47","distance":100,"maneuver":{"type":"turn"},"geometry":{"type":"LineString","coordinates":[[-74,40],[-74.001,40]]}}]}]}]}"""

    @Test fun requestNormalizesTimesAndRadiusAndParsesUtf8RoadNames() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(valid))
            server.start()
            val result = requireNotNull(OsrmMatcher(server.url("/").toString().trimEnd('/')).match(points))
            assertEquals("Rue cité", result.roads.single().name)
            assertEquals("NJ 47", result.roads.single().reference)
            assertTrue(result.roads.single().countTowardsRoads)
            val request = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS)).requestUrl!!
            assertEquals("100;101", request.queryParameter("timestamps"))
            assertEquals("10;75", request.queryParameter("radiuses"))
            assertEquals("0;1", request.queryParameter("waypoints"))
            assertEquals("false", request.queryParameter("overview"))
            assertEquals("false", request.queryParameter("generate_hints"))
            assertEquals("false", request.queryParameter("tidy"))
            assertEquals("ignore", request.queryParameter("gaps"))
            assertEquals("270,65;270,65", request.queryParameter("bearings"))
        }
    }

    @Test fun fractionalAccuracyRadiusNeverRoundsBelowReportedUncertainty() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(valid))
            server.start()
            val fractional = listOf(
                points[0].copy(accuracyMeters = 10.1f),
                points[1].copy(accuracyMeters = 74.1f)
            )
            requireNotNull(OsrmMatcher(server.url("/").toString().trimEnd('/')).match(fractional))
            val request = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS)).requestUrl!!
            assertEquals("11;75", request.queryParameter("radiuses"))
        }
    }

    @Test fun storedMissingBearingSentinelCannotForceNorthboundMatching() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(valid))
            server.start()
            val noDeviceBearing = points.map { it.copy(bearingDegrees = 0f) }
            requireNotNull(OsrmMatcher(server.url("/").toString().trimEnd('/')).match(noDeviceBearing))
            val request = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS)).requestUrl!!
            assertEquals("270,65;270,65", request.queryParameter("bearings"))
        }
    }

    @Test fun missingStoredSpeedStillUsesClearMovementForBearingGuidance() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(valid))
            server.start()
            val movingWithoutMeasuredSpeed = listOf(
                points[0].copy(speedMps = 0f, timestampMillis = 100_000L),
                points[1].copy(speedMps = 0f, timestampMillis = 103_000L, accuracyMeters = 3f)
            )
            requireNotNull(
                OsrmMatcher(server.url("/").toString().trimEnd('/')).match(movingWithoutMeasuredSpeed)
            )
            val request = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS)).requestUrl!!
            assertEquals("270,65;270,65", request.queryParameter("bearings"))
        }
    }

    @Test fun missingSpeedWithinCombinedAccuracyDoesNotInventBearingGuidance() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(valid))
            server.start()
            val uncertain = listOf(
                points[0].copy(speedMps = 0f, accuracyMeters = 50f, timestampMillis = 100_000L),
                points[1].copy(speedMps = 0f, accuracyMeters = 50f, timestampMillis = 103_000L)
            )
            requireNotNull(OsrmMatcher(server.url("/").toString().trimEnd('/')).match(uncertain))
            val request = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS)).requestUrl!!
            assertNull(request.queryParameter("bearings"))
        }
    }

    @Test fun lowSpeedBearingDoesNotConstrainMatching() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(valid))
            server.start()
            val slow = listOf(
                points[0].copy(speedMps = 1f, timestampMillis = 100_000L),
                points[1].copy(speedMps = 1f, timestampMillis = 103_000L)
            )
            requireNotNull(OsrmMatcher(server.url("/").toString().trimEnd('/')).match(slow))
            val request = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS)).requestUrl!!
            assertNull(request.queryParameter("bearings"))
        }
    }

    @Test fun onAndOffRampsRemainMatchedButDoNotIncrementRoadCountMetadata() {
        MockWebServer().use { server ->
            val ramp = """{"code":"Ok","tracepoints":[{"matchings_index":0,"waypoint_index":0,"alternatives_count":0,"location":[-74,40]},{"matchings_index":0,"waypoint_index":1,"alternatives_count":0,"location":[-74.001,40]}],"matchings":[{"confidence":0.9,"legs":[{"steps":[{"name":"Exit 3 ramp","distance":100,"maneuver":{"type":"off ramp"},"geometry":{"type":"LineString","coordinates":[[-74,40],[-74.001,40]]}}]}]}]}"""
            server.enqueue(MockResponse().setBody(ramp))
            server.start()
            val road = requireNotNull(
                OsrmMatcher(server.url("/").toString().trimEnd('/')).match(points)
            ).roads.single()
            assertEquals("Exit 3 ramp", road.name)
            assertFalse(road.countTowardsRoads)
        }
    }

    @Test fun everyFixIsARequestedWaypointSoAmbiguousTailsCanBeWithheld() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"code":"NoMatch"}"""))
            server.start()
            OsrmMatcher(server.url("/").toString().trimEnd('/')).match(points + points.last().copy(id = 3))
            val request = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS)).requestUrl!!
            assertEquals("0;1;2", request.queryParameter("waypoints"))
        }
    }

    @Test fun rateLimitStopsFurtherRequestsUntilRetryWindowExpires() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(429).addHeader("Retry-After", "120"))
            server.enqueue(MockResponse().setBody("not json"))
            server.start()
            val endpoint = server.url("/").toString().trimEnd('/')
            val matcher = OsrmMatcher(endpoint)
            assertNull(matcher.match(points))
            assertTrue("Respect the server's two-minute retry window",
                matcher.rateLimitRemainingMs() in 110_000L..120_000L)
            assertNull(matcher.match(points))
            assertEquals("A throttled matcher must not send another request", 1, server.requestCount)

            // Invalid payloads are recoverable too, but shouldn't create a
            // global cooldown unrelated to the responding server's status.
            val other = OsrmMatcher(endpoint)
            assertNull(other.match(points))
            assertEquals(2, server.requestCount)
            assertEquals(0L, other.rateLimitRemainingMs())
        }
    }

    @Test fun temporarilyUnavailableProviderHonorsRetryAfterWithoutBursting() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503).addHeader("Retry-After", "90"))
            server.start()
            val matcher = OsrmMatcher(server.url("/").toString().trimEnd('/'))
            assertNull(matcher.match(points))
            assertTrue(matcher.rateLimitRemainingMs() in 80_000L..90_000L)
            assertNull(matcher.match(points))
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun retryAfterHttpDateDelaysAdditionalRequests() {
        MockWebServer().use { server ->
            val retryAt = java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC)
                .plusMinutes(3)
                .format(java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
            server.enqueue(MockResponse().setResponseCode(429).addHeader("Retry-After", retryAt))
            server.start()
            val matcher = OsrmMatcher(server.url("/").toString().trimEnd('/'))
            assertNull(matcher.match(points))
            assertTrue("Honor future HTTP-date retry limit",
                matcher.rateLimitRemainingMs() in 150_000L..180_000L)
            assertNull(matcher.match(points))
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun oversizedResponseIsRejected() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(" ".repeat(4 * 1024 * 1024 + 1) + valid))
            server.start()
            assertNull(OsrmMatcher(server.url("/").toString().trimEnd('/')).match(points))
        }
    }

    @Test fun incompletePositiveDistanceStepIsRejectedInsteadOfUnlockingPartialRoute() {
        MockWebServer().use { server ->
            val root = org.json.JSONObject(valid)
            root.getJSONArray("matchings").getJSONObject(0).getJSONArray("legs").getJSONObject(0)
                .getJSONArray("steps").put(org.json.JSONObject().put("distance", 100).put("name", "Missing geometry"))
            server.enqueue(MockResponse().setBody(root.toString()))
            server.start()
            assertNull(OsrmMatcher(server.url("/").toString().trimEnd('/')).match(points))
        }
    }

    @Test fun identicalSuccessfulWindowsReuseResponseWithoutChangingMatchedIds() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(valid))
            server.start()
            val matcher = OsrmMatcher(server.url("/").toString().trimEnd('/'))
            val first = requireNotNull(matcher.match(points))
            val sameFixesDifferentIds = points.map { it.copy(id = it.id + 100) }
            val second = requireNotNull(matcher.match(sameFixesDifferentIds))

            assertEquals(1, server.requestCount)
            assertEquals(first.roads, second.roads)
            assertEquals(
                first.matchedPointConfidences.values.toList(),
                second.matchedPointConfidences.values.toList()
            )
            assertEquals(sameFixesDifferentIds.map { it.id }.toSet(),
                second.matchedPointConfidences.keys)
        }
    }

    @Test fun differentMatchingParametersNeverUseStaleResponse() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(valid))
            server.enqueue(MockResponse().setBody(valid))
            server.start()
            val matcher = OsrmMatcher(server.url("/").toString().trimEnd('/'))
            requireNotNull(matcher.match(points))
            // Coordinates can be unchanged but timestamps affect OSRM's speed-based inference.
            requireNotNull(matcher.match(points.map { it.copy(timestampMillis = it.timestampMillis + 10_000L) }))
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun unsuccessfulMatchIsNotCached() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"code":"NoMatch"}"""))
            server.enqueue(MockResponse().setBody(valid))
            server.start()
            val matcher = OsrmMatcher(server.url("/").toString().trimEnd('/'))
            assertNull(matcher.match(points))
            assertNotNull(matcher.match(points))
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun okResponseWithNoUsableRoadsIsNeverCached() {
        MockWebServer().use { server ->
            // A valid OSRM response can have only outlier tracepoints. It must
            // not prevent a fresh lookup when the same window is retried.
            val ambiguous = """{"code":"Ok","tracepoints":[null,null],"matchings":[{"confidence":0.1,"legs":[]}]}"""
            server.enqueue(MockResponse().setBody(ambiguous))
            server.enqueue(MockResponse().setBody(valid))
            server.start()
            val matcher = OsrmMatcher(server.url("/").toString().trimEnd('/'))
            val first = requireNotNull(matcher.match(points))
            assertTrue(first.roads.isEmpty())
            assertTrue(first.matchedPointConfidences.isEmpty())
            assertTrue(requireNotNull(matcher.match(points)).roads.isNotEmpty())
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun oversizedTraceCannotBeSentToThePublicServer() {
        MockWebServer().use { server ->
            server.start()
            val tooMany = (1L..11L).map { points.first().copy(id = it) }
            assertNull(OsrmMatcher(server.url("/").toString().trimEnd('/')).match(tooMany))
            assertEquals(0, server.requestCount)
        }
    }
}
