package com.roadconquest.app.matching

import android.app.Application
import android.location.Location
import com.roadconquest.app.data.TrackPoint
import com.roadconquest.app.data.TrackingRepository
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], manifest = Config.NONE, application = Application::class)
class GeoapifyAsyncBacklogTest {
    private lateinit var context: Application
    private lateinit var repository: TrackingRepository
    private val server = MockWebServer()

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("mapmatching-async-v1", 0).edit().clear().commit()
        repository = TrackingRepository(context)
        repository.readableDatabase().execSQL("DELETE FROM track_points")
        repository.readableDatabase().execSQL("DELETE FROM roads")
        repository.readableDatabase().execSQL("DELETE FROM road_visits")
        server.start()
    }

    @After fun teardown() { server.shutdown() }

    private fun seedOldContinuousHistory(count: Int = 1_200) {
        val start = System.currentTimeMillis() - 2 * 60 * 60_000L
        repeat(count) { index ->
            assertTrue(repository.insertLocation(Location("gps").apply {
                latitude = 40.0
                longitude = -74.0 + index * 0.00004
                accuracy = 5f
                speed = 5f
                time = start + index * 3_000L
            }) > 0)
        }
    }

    @Test fun fingerprintDetectsChangedCoordinatesAndTimesNotOnlyIds() {
        val point = TrackPoint(1L, 40.0, -74.0, 5f, 5f, 0f, 1000L, false)
        val original = GeoapifyAsyncBacklog.fingerprint(listOf(point))
        assertNotEquals(original, GeoapifyAsyncBacklog.fingerprint(listOf(point.copy(longitude = -74.0001))))
        assertNotEquals(original, GeoapifyAsyncBacklog.fingerprint(listOf(point.copy(timestampMillis = 2000L))))
        assertEquals(original, GeoapifyAsyncBacklog.fingerprint(listOf(point.copy(matched = true))))
    }

    @Test fun onlyLargeOldBacklogSubmitsOneDiscountedJobAndPersistsItsExactIds() {
        seedOldContinuousHistory()
        server.enqueue(MockResponse().setResponseCode(202).setHeader("Content-Type", "application/json")
            .setBody("""{"id":"${"a".repeat(32)}","token":"${"b".repeat(64)}","status":"pending"}"""))
        val gateway = GeoapifyAsyncBacklog(context, server.url("/").toString().trimEnd('/'))
        val due = gateway.tick(repository)
        assertNotNull("Eight large old windows should qualify", due)
        assertTrue(requireNotNull(due) >= 60 * 60_000L - 10_000L)
        val request = server.takeRequest()
        assertEquals("/v1/batch/mapmatching", request.path)
        val submitted = JSONObject(request.body.readUtf8())
        assertEquals(0.5, submitted.getDouble("priority"), 0.0)
        assertEquals(8, submitted.getJSONArray("inputs").length())
        assertTrue((0 until 8).all {
            val size = submitted.getJSONArray("inputs").getJSONObject(it)
                .getJSONArray("waypoints").length()
            size in 120..150
        })
        val prefs = context.getSharedPreferences("mapmatching-async-v1", 0)
        assertTrue(requireNotNull(prefs.getString("job", null)).contains("fingerprint"))
        // No network request or duplicate charge until the saved poll deadline.
        assertNotNull(GeoapifyAsyncBacklog(context, server.url("/").toString().trimEnd('/'))
            .tick(repository))
        assertEquals(1, server.requestCount)
    }

    @Test fun liveGpsAndSmallBacklogsNeverUseSlowBatchMatching() {
        seedOldContinuousHistory(250)
        val gateway = GeoapifyAsyncBacklog(context, server.url("/").toString().trimEnd('/'))
        assertNull(gateway.tick(repository))
        assertEquals(0, server.requestCount)
        assertNull(GeoapifyAsyncBacklog(context, "").tick(repository))
    }
}
