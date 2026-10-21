package com.roadconquest.app

import android.location.Location
import android.os.SystemClock
import com.roadconquest.app.data.TrackPoint
import com.roadconquest.app.data.TrackingRepository
import com.roadconquest.app.matching.FallbackRoadMatcher
import com.roadconquest.app.matching.GeoapifyMatcher
import com.roadconquest.app.matching.LocationIqMatcher
import com.roadconquest.app.services.ProviderHealth
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37], manifest = Config.NONE)
class MatchingCachedEligibilityTest {
    private val fixes = listOf(
        TrackPoint(1, 47.567028, 10.694703, 3f, 8f, 0f, Instant.parse("2026-01-01T07:09:34Z").toEpochMilli(), false),
        TrackPoint(2, 47.567783, 10.6950319, 3f, 8f, 0f, Instant.parse("2026-01-01T07:09:45Z").toEpochMilli(), false),
        TrackPoint(3, 47.568, 10.696, 3f, 8f, 0f, Instant.parse("2026-01-01T07:09:51Z").toEpochMilli(), false)
    )
    private val partial: String get() = JSONObject(javaClass.getResource("/geoapify-public-example.json")!!.readText()).apply {
        getJSONArray("features").getJSONObject(0).getJSONObject("properties").getJSONArray("waypoints")
            .put(JSONObject().put("original_index", 2).put("match_type", "unmatched")
                .put("original_location", JSONArray().put(fixes[2].longitude).put(fixes[2].latitude)))
    }.toString()

    private fun router(server: MockWebServer): FallbackRoadMatcher {
        server.enqueue(MockResponse().setBody(partial))
        server.enqueue(MockResponse().setBody("""{"code":"NoMatch"}"""))
        val origin = server.url("/").toString()
        val health = ProviderHealth()
        val primary = GeoapifyMatcher(origin, health)
        val answer = requireNotNull(primary.match(fixes))
        assertTrue(answer.roads.isNotEmpty())
        assertEquals(setOf(1L, 2L), answer.matchedPointConfidences.keys)
        health.failed("matching:$origin", 503, "60")
        return FallbackRoadMatcher(listOf(primary, LocationIqMatcher(origin, ProviderHealth())))
    }

    @Test fun exactValidatedCacheAvoidsAReadyBackupWhenOnlyDeferredContextIsUnresolved() {
        MockWebServer().use { server ->
            server.start()
            val matcher = router(server)
            val answer = requireNotNull(matcher.match(fixes, setOf(1L, 2L)))
            assertEquals(setOf(1L, 2L), answer.matchedPointConfidences.keys)
            assertFalse(answer.explicitNoMatch)
            assertFalse(matcher.lastCallUsedNetwork)
            assertEquals("No extra paid backup request is needed for already validated eligible work", 1, server.requestCount)
        }
    }

    @Test fun cachedPartialStillNeedsReadyBackupWhenTheUnresolvedFixBecomesEligible() {
        MockWebServer().use { server ->
            server.start()
            val matcher = router(server)
            assertTrue(requireNotNull(matcher.match(fixes, setOf(1L, 2L, 3L))).explicitNoMatch)
            assertTrue(matcher.lastCallUsedNetwork)
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun changedTraceCannotUseAnOtherwiseCompleteEligibleCache() {
        MockWebServer().use { server ->
            server.start()
            val matcher = router(server)
            assertTrue(requireNotNull(matcher.match(fixes.map { it.copy(timestampMillis = it.timestampMillis + 1000) },
                setOf(1L, 2L))).explicitNoMatch)
            assertTrue(matcher.lastCallUsedNetwork)
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun serviceResolvesOnlyEligibleCachedFixesDuringBothNetworkGates() = serviceCache(tailEligible = false)

    @Test fun serviceCannotBypassNetworkGatesWithAnUnresolvedEligibleFix() = serviceCache(tailEligible = true)

    private fun serviceCache(tailEligible: Boolean) {
        MockWebServer().use { server ->
            server.start()
            val matcher = router(server)
            val repo = TrackingRepository(RuntimeEnvironment.getApplication())
            for (table in listOf("track_points", "skipped_driving_fixes", "road_visits", "roads"))
                repo.readableDatabase().execSQL("DELETE FROM $table")
            val ids = fixes.map { fix -> repo.insertLocation(Location("gps").apply {
                latitude = fix.latitude; longitude = fix.longitude; accuracy = fix.accuracyMeters
                speed = fix.speedMps; bearing = fix.bearingDegrees; time = fix.timestampMillis
            }) }
            val deferredUntil = System.currentTimeMillis() + 120_000L
            if (!tailEligible) repo.deferMatching(listOf(ids.last()), deferredUntil)
            val before = repo.getSummary()
            assertEquals(ids, repo.loadMatchingWindow(100).points.map { it.id })
            ShadowSystemClock.advanceBy(Duration.ofMinutes(10))
            val controller = Robolectric.buildService(TrackingService::class.java)
            val service = controller.get()
            val previousAttempt = SystemClock.elapsedRealtime()
            field("repository").set(service, repo)
            field("matcher").set(service, matcher)
            field("lastMatchAttempt").setLong(service, previousAttempt)
            field("ready").setBoolean(service, true)
            val executor = field("matchingExecutor").get(service) as ScheduledExecutorService
            try {
                TrackingService::class.java.getDeclaredMethod("maybeRunMatching", Boolean::class.javaPrimitiveType)
                    .apply { isAccessible = true }.invoke(service, true)
                executor.submit {}.get(10, TimeUnit.SECONDS)
                assertEquals(1, server.requestCount)
                assertEquals(previousAttempt, field("lastMatchAttempt").getLong(service))
                assertEquals(before.trackPointCount, repo.getSummary().trackPointCount)
                assertEquals(before.distanceMeters, repo.getSummary().distanceMeters, 0.0)
                repo.readableDatabase().rawQuery("SELECT id,matched,next_match_attempt_ms FROM track_points ORDER BY id", null).use { cursor ->
                    for (i in ids.indices) {
                        assertTrue(cursor.moveToNext())
                        assertEquals(ids[i], cursor.getLong(0))
                        assertEquals(if (!tailEligible && i < 2) 1 else 0, cursor.getInt(1))
                        assertEquals(if (!tailEligible && i == 2) deferredUntil else 0L, cursor.getLong(2))
                    }
                }
                assertEquals(if (tailEligible) 0L else 1L, repo.getSummary().roadSegmentCount)
            } finally {
                field("ready").setBoolean(service, false)
                controller.destroy()
            }
        }
    }

    private fun field(name: String) = TrackingService::class.java.getDeclaredField(name).apply { isAccessible = true }
}
