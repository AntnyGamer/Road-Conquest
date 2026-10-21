package com.roadconquest.app

import android.location.Location
import com.roadconquest.app.data.TrackPoint
import com.roadconquest.app.data.TrackingRepository
import com.roadconquest.app.matching.FallbackRoadMatcher
import com.roadconquest.app.matching.LocationIqMatcher
import com.roadconquest.app.matching.MatchResult
import com.roadconquest.app.matching.RoadMatcher
import com.roadconquest.app.services.ProviderHealth
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
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
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37], manifest = Config.NONE)
class MatchingFallbackWindowTest {
    private fun field(name: String) = TrackingService::class.java.getDeclaredField(name).apply { isAccessible = true }

    private fun repository(pending: IntRange): Pair<TrackingRepository, List<Long>> {
        val repo = TrackingRepository(RuntimeEnvironment.getApplication()).apply {
            readableDatabase().execSQL("DELETE FROM track_points")
            readableDatabase().execSQL("DELETE FROM roads")
        }
        val ids = (0 until 100).map { i -> repo.insertLocation(Location("gps").apply {
            latitude = 40.0; longitude = -74.0 + i * .0001
            accuracy = 8f; speed = 8f; bearing = 90f; time = 1_000_000L + i * 3_000L
        }) }
        repo.markMatched(ids.filterIndexed { i, _ -> i !in pending })
        return repo to ids
    }

    private class Primary : RoadMatcher {
        var calls = 0
        override var unavailable = false
        override fun rateLimitRemainingMs() = if (unavailable) 60_000L else 0L
        override fun match(points: List<TrackPoint>): MatchResult? { calls++; unavailable = true; return null }
    }

    /** Every mock provider leg still passes the real backup parser and geometric gates. */
    private fun response(request: RecordedRequest): String {
        val waypoints = JSONObject(request.body.readUtf8()).getJSONArray("waypoints")
        val traces = JSONArray()
        val legs = JSONArray()
        for (i in 0 until waypoints.length()) {
            val coordinate = waypoints.getJSONObject(i).getJSONArray("location")
            traces.put(JSONObject().put("matchings_index", 0).put("waypoint_index", i)
                .put("alternatives_count", 0).put("location", coordinate))
            if (i > 0) legs.put(JSONObject().put("steps", JSONArray().put(JSONObject()
                .put("name", "Fixture road").put("distance", 8.52)
                .put("geometry", JSONObject().put("type", "LineString").put("coordinates", JSONArray()
                    .put(waypoints.getJSONObject(i - 1).getJSONArray("location")).put(coordinate))))))
        }
        return JSONObject().put("code", "Ok").put("tracepoints", traces).put("matchings", JSONArray()
            .put(JSONObject().put("confidence", .99).put("legs", legs))).toString()
    }

    private fun withService(repo: TrackingRepository, matcher: RoadMatcher, freshGate: Boolean = false,
                            check: (TrackingService) -> Unit) {
        val controller = Robolectric.buildService(TrackingService::class.java)
        val service = controller.get()
        ShadowSystemClock.advanceBy(Duration.ofMinutes(10))
        field("repository").set(service, repo)
        field("matcher").set(service, matcher)
        field("ready").setBoolean(service, true)
        if (freshGate) field("lastMatchAttempt").setLong(service, android.os.SystemClock.elapsedRealtime())
        val executor = field("matchingExecutor").get(service) as ScheduledExecutorService
        try {
            TrackingService::class.java.getDeclaredMethod("maybeRunMatching", Boolean::class.javaPrimitiveType)
                .apply { isAccessible = true }.invoke(service, true)
            executor.submit {}.get(10, TimeUnit.SECONDS)
            check(service)
        } finally {
            field("ready").setBoolean(service, false)
            controller.destroy()
        }
    }

    @Test fun failedPrimaryDoesNotWasteBackupOnAnAlreadyResolvedPrefix() {
        val (repo, _) = repository(49..50)
        val primary = Primary()
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse().setBody(response(request))
            }
            server.start()
            val backup = LocationIqMatcher(server.url("/").toString(), ProviderHealth())
            withService(repo, FallbackRoadMatcher(listOf(primary, backup))) { service ->
                assertEquals(1, primary.calls)
                assertEquals("A single useful backup request must resolve the pending island", 1, server.requestCount)
                assertTrue("The backup must receive the pending gap, not 25 matched approach fixes",
                    repo.loadMatchingWindow(100).points.isEmpty())
                assertEquals(100L, repo.getSummary().trackPointCount)
                assertTrue(repo.getSummary().roadSegmentCount > 0)
                assertEquals(25, field("lastRemotePointLimit").getInt(service))
                assertTrue(TrackingService.remainingRemoteMatchingDelayMs(android.os.SystemClock.elapsedRealtime(),
                    field("lastMatchAttempt").getLong(service), 25) > 0)
            }
        }
    }

    @Test fun shiftedBackupFailureDefersOnlySubmittedPendingFixes() {
        val (repo, ids) = repository(33..67)
        val inputs = mutableListOf<List<Long>>()
        val backup = object : RoadMatcher {
            override val maxPoints = 25
            override val unavailable = false
            override fun rateLimitRemainingMs() = 0L
            override fun match(points: List<TrackPoint>): MatchResult? { inputs += points.map { it.id }; return null }
        }
        withService(repo, FallbackRoadMatcher(listOf(Primary(), backup))) { service ->
            assertEquals(listOf(ids.subList(30, 55)), inputs)
            val eligible = repo.readableDatabase().rawQuery(
                "SELECT id FROM track_points WHERE matched = 0 AND next_match_attempt_ms = 0 ORDER BY id", null
            ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getLong(0)) } }
            assertEquals("The last 13 pending fixes were never sent", ids.subList(55, 68), eligible)
            assertEquals(100L, repo.getSummary().trackPointCount)
            assertEquals(0L, repo.getSummary().roadSegmentCount)
            assertEquals(25, field("lastRemotePointLimit").getInt(service))
        }
    }

    @Test fun completeShiftedCacheResolvesTheGapWithoutAnotherRequestOrMovingTheNetworkClock() =
        cachedShiftedAnswer(complete = true)

    @Test fun shiftedNoMatchCacheCannotBypassTheGateOrDeferUnsentFixes() =
        cachedShiftedAnswer(complete = false)

    private fun cachedShiftedAnswer(complete: Boolean) {
        val (repo, ids) = repository(49..50)
        val primary = Primary()
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse().setBody(
                    if (complete) response(request) else """{"code":"NoMatch"}""")
            }
            server.start()
            val backup = LocationIqMatcher(server.url("/").toString(), ProviderHealth())
            val original = repo.loadMatchingWindow(100).points
            assertEquals(100, original.size)
            // The middle gap starts at index 49, with three genuine approach fixes.
            assertNotNull(backup.match(original.subList(46, 71)))
            withService(repo, FallbackRoadMatcher(listOf(primary, backup)), freshGate = true) { service ->
                assertEquals(0, primary.calls)
                assertEquals("The prepared local answer must not make another HTTP call", 1, server.requestCount)
                assertEquals(android.os.SystemClock.elapsedRealtime(), field("lastMatchAttempt").getLong(service))
                if (complete) {
                    assertTrue(repo.loadMatchingWindow(100).points.isEmpty())
                    assertTrue(repo.getSummary().roadSegmentCount > 0)
                } else {
                    assertEquals(ids.subList(49, 51).toSet(), repo.loadMatchingWindow(100).markableIds)
                    assertNull(repo.nextDeferredMatchAttempt(System.currentTimeMillis()))
                    assertEquals(0L, repo.getSummary().roadSegmentCount)
                }
                assertEquals(100L, repo.getSummary().trackPointCount)
            }
        }
    }
}
