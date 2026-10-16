package com.roadconquest.app

import android.location.Location
import android.os.SystemClock
import com.roadconquest.app.data.TrackingRepository
import com.roadconquest.app.account.VerifiedDriving
import com.roadconquest.app.map.OverlayRoads
import com.roadconquest.app.matching.RoadGeometryValidator
import com.roadconquest.app.util.Prefs
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37], manifest = Config.NONE)
class FogProgressTest {
    private fun fixtureMatcher(server: MockWebServer) = object : com.roadconquest.app.matching.RoadMatcher {
        override val unavailable = false
        override fun rateLimitRemainingMs() = 0L
        override fun match(points: List<com.roadconquest.app.data.TrackPoint>): com.roadconquest.app.matching.MatchResult? {
            val connection = server.url("/").toUrl().openConnection() as java.net.HttpURLConnection
            return try {
                RoadGeometryValidator().parse(connection.inputStream.bufferedReader().use { it.readText() }, points)
            } finally { connection.disconnect() }
        }
    }

    @Test fun drivingWithFogHiddenStillMatchesAndSavesRoadsForReenablingFog() {
        val app = RuntimeEnvironment.getApplication()
        val repo = TrackingRepository(app)
        repo.readableDatabase().execSQL("DELETE FROM track_points")
        repo.readableDatabase().execSQL("DELETE FROM roads")
        Prefs.setFogEnabled(app, false)
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"code":"Ok","tracepoints":[{"matchings_index":0,"waypoint_index":0,"alternatives_count":0,"location":[-74,40]},{"matchings_index":0,"waypoint_index":1,"alternatives_count":0,"location":[-74,40.0002]}],"matchings":[{"confidence":0.95,"legs":[{"steps":[{"name":"Test road","distance":22,"geometry":{"type":"LineString","coordinates":[[-74,40],[-74,40.0002]]}}]}]}]}"""))
            server.start()
            val controller = Robolectric.buildService(TrackingService::class.java)
            val service = controller.get()
            fun field(name: String) = TrackingService::class.java.getDeclaredField(name).apply { isAccessible = true }
            field("repository").set(service, repo)
            field("verifiedDriving").set(service, VerifiedDriving(app))
            field("matcher").set(service, fixtureMatcher(server))
            field("ready").setBoolean(service, true)
            try {
                ShadowSystemClock.advanceBy(Duration.ofSeconds(10))
                fun fix(lat: Double, stamp: Long) = Location("gps").apply {
                    latitude = lat; longitude = -74.0; accuracy = 5f; speed = 5f
                    time = stamp; elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                }
                service.onLocationChanged(fix(40.0, 1_000_000L))
                ShadowSystemClock.advanceBy(Duration.ofSeconds(3))
                service.onLocationChanged(fix(40.0002, 1_003_000L))
                (field("storageExecutor").get(service) as ExecutorService).submit {}.get(10, TimeUnit.SECONDS)
                (field("matchingExecutor").get(service) as ExecutorService).submit {}.get(10, TimeUnit.SECONDS)
                assertEquals(1, server.requestCount)
                assertEquals(2L, repo.getSummary().trackPointCount)
                assertEquals(1L, repo.getSummary().roadsUnlockedCount)
                assertTrue(repo.loadMatchingWindow().points.isEmpty())
                assertFalse(Prefs.isFogEnabled(app))
                Prefs.setFogEnabled(app, true)
                val reopened = TrackingRepository(app)
                assertEquals(repo.getSummary(), reopened.getSummary())
                val roads = reopened.getRoadsInBounds(40.001, -73.999, 39.999, -74.001)
                assertEquals(1, roads.size)
                val overlay = OverlayRoads.prepare(roads)
                assertArrayEquals(doubleArrayOf(40.0, -74.0, 40.0002, -74.0), overlay.coordinates, 0.0)
                assertArrayEquals(intArrayOf(0, 4), overlay.starts)
            } finally {
                field("ready").setBoolean(service, false)
                controller.destroy()
            }
        }
    }
}
