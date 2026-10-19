package com.roadconquest.app

import android.location.Location
import com.roadconquest.app.data.TrackPoint
import com.roadconquest.app.data.TrackingRepository
import com.roadconquest.app.matching.FallbackRoadMatcher
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
class MatchingFailureScopeTest {
    @Test fun failedBackupDefersOnlyItsActualPrefixAndPreservesRemotePacing() {
        val controller = Robolectric.buildService(TrackingService::class.java)
        val service = controller.get()
        val repo = TrackingRepository(RuntimeEnvironment.getApplication()).apply {
            readableDatabase().execSQL("DELETE FROM track_points")
            readableDatabase().execSQL("DELETE FROM roads")
        }
        val ids = (0 until 100).map { i -> repo.insertLocation(Location("gps").apply {
            latitude = 40.0; longitude = -74.0 + i * 0.00005
            accuracy = 5f; speed = 8f; time = 1_000_000L + i * 3_000L
        }) }
        // Three previously resolved approach fixes are context, not new work.
        repo.markMatched(ids.take(3))
        val primaryInputs = mutableListOf<List<Long>>()
        val backupInputs = mutableListOf<List<Long>>()
        val primary = object : RoadMatcher {
            override var unavailable = false
            override fun rateLimitRemainingMs() = if (unavailable) 60_000L else 0L
            override fun match(points: List<TrackPoint>): MatchResult? {
                primaryInputs += points.map { it.id }
                unavailable = true
                return null
            }
        }
        val backup = object : RoadMatcher {
            override val maxPoints = 25
            override val unavailable = false
            override fun rateLimitRemainingMs() = 0L
            override fun match(points: List<TrackPoint>): MatchResult? {
                backupInputs += points.map { it.id }
                return null // The available backup rejected this prefix's geometry.
            }
        }
        fun field(name: String) = TrackingService::class.java.getDeclaredField(name).apply { isAccessible = true }
        ShadowSystemClock.advanceBy(Duration.ofMinutes(10))
        field("repository").set(service, repo)
        field("matcher").set(service, FallbackRoadMatcher(listOf(primary, backup)))
        field("ready").setBoolean(service, true)
        val executor = field("matchingExecutor").get(service) as ScheduledExecutorService
        try {
            TrackingService::class.java.getDeclaredMethod("maybeRunMatching", Boolean::class.javaPrimitiveType)
                .apply { isAccessible = true }.invoke(service, true)
            executor.submit {}.get(10, TimeUnit.SECONDS)
            assertEquals(listOf(ids), primaryInputs)
            assertEquals(listOf(ids.take(25)), backupInputs)
            val eligible = repo.readableDatabase().rawQuery(
                "SELECT id FROM track_points WHERE matched = 0 AND next_match_attempt_ms = 0 ORDER BY id", null
            ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getLong(0)) } }
            assertEquals("Unsubmitted backup fixes must remain eligible", ids.drop(25), eligible)
            assertEquals(100L, repo.getSummary().trackPointCount)
            assertEquals(0L, repo.getSummary().roadSegmentCount)
            assertEquals(25, field("lastRemotePointLimit").getInt(service))
            val now = android.os.SystemClock.elapsedRealtime()
            assertTrue("The normal backup request gate still applies",
                TrackingService.remainingRemoteMatchingDelayMs(now, field("lastMatchAttempt").getLong(service), 25) > 0)
        } finally {
            field("ready").setBoolean(service, false)
            controller.destroy()
        }
    }
}
