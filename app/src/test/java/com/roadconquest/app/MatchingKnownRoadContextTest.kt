package com.roadconquest.app

import android.location.Location
import com.roadconquest.app.data.MatchedRoad
import com.roadconquest.app.data.TrackPoint
import com.roadconquest.app.data.TrackingRepository
import com.roadconquest.app.matching.KnownRoadReuse
import com.roadconquest.app.matching.MatchResult
import com.roadconquest.app.matching.RoadMatcher
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
class MatchingKnownRoadContextTest {
    private fun fix(i: Int) = Location("gps").apply {
        latitude = 40.0; longitude = -74.0 + i * .0006
        accuracy = 3f; speed = 8.5f; bearing = 90f; time = 2_000_000L + i * 6_000L
    }

    private fun fixture(): Pair<TrackingRepository, List<Long>> {
        val repo = TrackingRepository(RuntimeEnvironment.getApplication())
        for (table in listOf("track_points", "skipped_driving_fixes", "road_visits", "roads"))
            repo.readableDatabase().execSQL("DELETE FROM $table")
        repo.upsertRoads(listOf(MatchedRoad("Verified road", "[[-74,40],[-73.8,40]]", 100L, 200L, .99)))
        val ids = (0..4).map { repo.insertLocation(fix(it)) }
        repo.markMatched(ids.take(4))
        return repo to ids
    }

    private fun visits(repo: TrackingRepository) = repo.loadMatchingWindow(100).let {
        KnownRoadReuse.find(repo, it.points, it.markableIds)
    }

    @Test fun fifthAdjacentMatchedFixCanProveOnePendingKnownRoadFixWithoutChangingRemoteWindow() {
        val (repo, ids) = fixture()
        val original = repo.loadMatchingWindow(100)
        val before = repo.getSummary()
        assertEquals(ids.drop(1), original.points.map { it.id })
        assertEquals(setOf(ids.last()), original.markableIds)
        assertTrue(KnownRoadReuse.identify(original.points, original.markableIds) { "verified" }.isEmpty())
        val local = visits(repo)
        assertEquals(listOf(ids.last()), local.flatMap { it.pointIds })
        assertEquals("Local evidence lookup must not rewrite the online window", original, repo.loadMatchingWindow(100))
        assertEquals(1, repo.completeKnownRoadVisits(local))
        assertTrue(repo.loadMatchingWindow(100).points.isEmpty())
        assertEquals(before.trackPointCount, repo.getSummary().trackPointCount)
        assertEquals(before.distanceMeters, repo.getSummary().distanceMeters, 0.0)
        assertEquals(1L, repo.getSummary().roadSegmentCount)
    }

    @Test fun missingUnmatchedDeferredOrOldApproachCannotSupplyTheFifthFix() {
        for (mode in listOf("missing", "unmatched", "deferred", "old", "clock")) {
            val (repo, ids) = fixture()
            val db = repo.readableDatabase()
            when (mode) {
                "missing" -> db.execSQL("DELETE FROM track_points WHERE id=?", arrayOf(ids.first()))
                "unmatched" -> db.execSQL("UPDATE track_points SET matched=0 WHERE id=?", arrayOf(ids.first()))
                "deferred" -> {
                    db.execSQL("UPDATE track_points SET matched=0 WHERE id=?", arrayOf(ids.first()))
                    repo.deferMatching(listOf(ids.first()), System.currentTimeMillis() + 60_000L)
                }
                "old" -> db.execSQL("UPDATE track_points SET timestamp_ms=1900000 WHERE id=?", arrayOf(ids.first()))
                "clock" -> db.execSQL("UPDATE track_points SET timestamp_ms=2010000 WHERE id=?", arrayOf(ids.first()))
            }
            // Keep the original pending target/online context even if the older
            // unmatched point becomes eligible work in a different window.
            val points = ids.drop(1).mapIndexed { i, id ->
                val f = fix(i + 1)
                TrackPoint(id, f.latitude, f.longitude, f.accuracy, f.speed, f.bearing, f.time, i < 3)
            }
            assertTrue(mode, KnownRoadReuse.find(repo, points, setOf(ids.last())).isEmpty())
        }
    }

    @Test fun additionalContextMustPassAllExistingQualityAndGeometryChecks() {
        for (change in listOf("accuracy_m=6", "speed_mps=41", "bearing_deg=270", "latitude=40.01")) {
            val (repo, ids) = fixture()
            repo.readableDatabase().execSQL("UPDATE track_points SET $change WHERE id=?", arrayOf(ids.first()))
            assertTrue(change, visits(repo).isEmpty())
        }
        val (repo, _) = fixture()
        repo.upsertRoads(listOf(MatchedRoad("Parallel", "[[-74,40.00006],[-73.8,40.00006]]", 100L, 200L, .99)))
        assertTrue(visits(repo).isEmpty())
        repo.readableDatabase().execSQL("DELETE FROM roads")
        assertTrue(visits(repo).isEmpty())
    }

    private fun field(name: String) = TrackingService::class.java.getDeclaredField(name).apply { isAccessible = true }

    private fun withService(repo: TrackingRepository, matcher: RoadMatcher, action: (TrackingService, () -> Unit) -> Unit) {
        ShadowSystemClock.advanceBy(Duration.ofMinutes(10))
        val controller = Robolectric.buildService(TrackingService::class.java)
        val service = controller.get()
        field("repository").set(service, repo)
        field("matcher").set(service, matcher)
        field("ready").setBoolean(service, true)
        val executor = field("matchingExecutor").get(service) as ScheduledExecutorService
        val run = {
            TrackingService::class.java.getDeclaredMethod("maybeRunMatching", Boolean::class.javaPrimitiveType)
                .apply { isAccessible = true }.invoke(service, true)
            executor.submit {}.get(10, TimeUnit.SECONDS)
            Unit
        }
        try { action(service, run) } finally {
            field("ready").setBoolean(service, false)
            controller.destroy()
        }
    }

    private class Online : RoadMatcher {
        val requests = ArrayList<List<TrackPoint>>()
        override val unavailable = false
        override fun rateLimitRemainingMs() = 0L
        override fun match(points: List<TrackPoint>): MatchResult {
            requests += points
            return MatchResult(emptyList(), emptyMap(), explicitNoMatch = true)
        }
    }

    @Test fun streamOfOneHundredKnownRoadFixesNeedsNoPaidRequestOrExtraGps() {
        val (repo, _) = fixture()
        val online = Online()
        withService(repo, online) { service, run ->
            repeat(100) { i ->
                if (i > 0) repo.insertLocation(fix(i + 4))
                run()
                ShadowSystemClock.advanceBy(Duration.ofSeconds(6))
            }
            assertEquals(0, online.requests.size)
            assertTrue(repo.loadMatchingWindow(100).points.isEmpty())
            assertEquals(0L, field("lastMatchAttempt").getLong(service))
            assertEquals(104L, repo.getSummary().trackPointCount)
            assertEquals(1L, repo.getSummary().roadSegmentCount)
        }
    }

    @Test fun uncertainApproachStillSendsTheOriginalFourFixWindowToTheProvider() {
        val (repo, ids) = fixture()
        repo.readableDatabase().execSQL("UPDATE track_points SET accuracy_m=6 WHERE id=?", arrayOf(ids.first()))
        val original = repo.loadMatchingWindow(100)
        val online = Online()
        withService(repo, online) { _, run ->
            run()
            assertEquals(1, online.requests.size)
            assertEquals(original.points, online.requests.single())
            assertEquals(ids.drop(1), online.requests.single().map { it.id })
            assertEquals(5L, repo.getSummary().trackPointCount)
            assertEquals(1L, repo.getSummary().roadSegmentCount)
        }
    }
}
