package com.roadconquest.app.matching

import android.app.Application
import com.roadconquest.app.data.TrackPoint
import com.roadconquest.app.services.ProviderHealth
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37], manifest = Config.NONE, application = Application::class)
class MatchingResponseReuseTest {
    private fun points(trace: Int) = List(2) { i ->
        TrackPoint(i + 1L, 40.0 + trace * 0.001, -74.0 + i * 0.0001,
            3f, 8f, 0f, 1_767_225_600_000L + i * 10_000L, false)
    }

    @Test fun alternatingUnchangedTracesSpendFourCallsInsteadOfTwelveForEachProvider() {
        for (primary in listOf(true, false)) MockWebServer().use { server ->
            server.start()
            val body = if (primary) """{"type":"FeatureCollection","features":[]}"""
                else """{"code":"NoMatch"}"""
            repeat(4) { server.enqueue(MockResponse().setBody(body)) }
            val matcher: RoadMatcher = if (primary) GeoapifyMatcher(server.url("/").toString(), ProviderHealth())
                else LocationIqMatcher(server.url("/").toString(), ProviderHealth())
            repeat(3) { round ->
                repeat(4) { trace ->
                    assertTrue(requireNotNull(matcher.match(points(trace))).explicitNoMatch)
                    assertEquals(round == 0, matcher.lastCallUsedNetwork)
                }
            }
            assertEquals(4, server.requestCount)
            // A different serialized GPS fix must still reach the provider.
            server.enqueue(MockResponse().setBody(body))
            assertNotNull(matcher.match(points(0).map { it.copy(timestampMillis = it.timestampMillis + 1000) }))
            assertEquals(5, server.requestCount)
        }
    }

    @Test fun locationIqAccuracyChangesNeverReuseADifferentRequest() {
        MockWebServer().use { server ->
            server.start()
            repeat(2) { server.enqueue(MockResponse().setBody("""{"code":"NoMatch"}""")) }
            val matcher = LocationIqMatcher(server.url("/").toString(), ProviderHealth())
            assertNotNull(matcher.match(points(0)))
            assertNotNull(matcher.match(points(0).map { it.copy(accuracyMeters = 4f) }))
            assertEquals(2, server.requestCount)
            assertNotNull(matcher.cachedMatch(points(0)))
            assertFalse(matcher.lastCallUsedNetwork)
        }
    }
}
