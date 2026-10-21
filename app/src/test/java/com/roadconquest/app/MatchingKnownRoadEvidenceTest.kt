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
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37], manifest = Config.NONE)
class MatchingKnownRoadEvidenceTest {
    private fun fixture(skipped: Boolean = true, retained: Int = 6, adjust: (Location) -> Unit = {}): Pair<TrackingRepository, List<Long>> {
        val repo = TrackingRepository(RuntimeEnvironment.getApplication())
        for (table in listOf("track_points", "skipped_driving_fixes", "road_visits", "roads"))
            repo.readableDatabase().execSQL("DELETE FROM $table")
        repo.upsertRoads(listOf(MatchedRoad("Verified highway", "[[-74,40],[-73.7,40]]", 100L, 200L, .99)))
        val ids = ArrayList<Long>()
        repeat(retained * 2 - 1) { i ->
            val fix = Location("gps").apply {
                latitude = 40.0; longitude = -74.0 + i * .00105
                accuracy = 3f; speed = 30f; bearing = 90f; time = 2_000_000L + i * 3_000L
            }
            if (i % 2 == 0) ids += repo.insertLocation(fix)
            else if (skipped) { adjust(fix); repo.recordSkippedDrivingFix(fix) }
        }
        return repo to ids
    }

    private fun visits(repo: TrackingRepository) = repo.loadMatchingWindow(100).let {
        KnownRoadReuse.find(repo, it.points, it.markableIds)
    }

    @Test fun originalIntermediateFixesCorroborateCompressedHighwayWithoutRelaxingStepLimit() {
        val (repo, ids) = fixture()
        val window = repo.loadMatchingWindow(100)
        assertTrue("Six-second highway steps exceed the existing 160-metre limit",
            KnownRoadReuse.identify(window.points, window.markableIds) { "verified" }.isEmpty())
        val before = repo.getSummary()
        val local = visits(repo)
        assertEquals("One continuous retained visit stays one database operation", 1, local.size)
        assertEquals(ids, local.flatMap { it.pointIds })
        assertEquals(ids.size, repo.completeKnownRoadVisits(local))
        assertTrue(repo.loadMatchingWindow(100).points.isEmpty())
        assertEquals(before.trackPointCount, repo.getSummary().trackPointCount)
        assertEquals(before.distanceMeters, repo.getSummary().distanceMeters, 0.0)
        assertEquals(1L, repo.getSummary().roadSegmentCount)
        repo.readableDatabase().rawQuery("SELECT COUNT(*) FROM skipped_driving_fixes", null).use {
            assertTrue(it.moveToFirst()); assertEquals(5, it.getInt(0))
        }
    }

    @Test fun noOriginalEvidenceStillRequiresOnlineMatching() {
        val (repo, _) = fixture(skipped = false)
        assertTrue(visits(repo).isEmpty())
    }

    @Test fun fewerThanFiveActualFixesAndSamplesOutsideTheWindowCannotAuthorizeReuse() {
        val (repo, _) = fixture()
        val points = repo.loadMatchingWindow(100).points.take(2)
        assertTrue(KnownRoadReuse.find(repo, points, points.map { it.id }.toSet()).isEmpty())
    }

    @Test fun originalAcceptedVisitsArePreservedEvenWithUnusableSkippedEvidence() {
        val (repo, ids) = fixture(adjust = { it.accuracy = 30f })
        ids.forEachIndexed { i, id ->
            repo.readableDatabase().execSQL("UPDATE track_points SET longitude = ?,speed_mps = 8 WHERE id = ?",
                arrayOf<Any>(-74.0 + i * .0001, id))
        }
        val window = repo.loadMatchingWindow(100)
        val original = KnownRoadReuse.identify(window.points, window.markableIds) {
            repo.findUnambiguousPreviouslyDrivenRoadNear(it.latitude, it.longitude)?.segmentId
        }
        assertEquals(ids, original.flatMap { it.pointIds })
        assertEquals(original, visits(repo))
    }

    @Test fun evidenceExpansionRefusesPausesClockChangesAndExcessIntermediateSamples() {
        val (repo, _) = fixture()
        val points = repo.loadMatchingWindow(100).points
        for (shift in listOf(60_000L, -60_000L)) {
            val changed = points.mapIndexed { i, p -> if (i >= 2) p.copy(timestampMillis = p.timestampMillis + shift) else p }
            assertSame(changed, repo.knownRoadEvidence(changed))
            assertTrue(KnownRoadReuse.find(repo, changed, changed.map { it.id }.toSet()).isEmpty())
        }
        for (second in listOf(1L, 2L, 4L, 5L)) repo.recordSkippedDrivingFix(Location("gps").apply {
            latitude = 40.0; longitude = -74.0 + second * .00035
            accuracy = 3f; speed = 30f; bearing = 90f; time = 2_000_000L + second * 1_000L
        })
        assertSame(points, repo.knownRoadEvidence(points))
        assertTrue(visits(repo).isEmpty())
    }

    @Test fun restartDoesNotTrustOldIntermediateFixesByTimestampAlone() {
        val (repo, _) = fixture()
        assertFalse(visits(repo).isEmpty())
        val restarted = TrackingRepository(RuntimeEnvironment.getApplication())
        assertTrue(visits(restarted).isEmpty())
    }

    @Test fun samplesCapturedAfterAnotherRetainedPointCannotCorroborateAnEarlierInterval() {
        val (repo, _) = fixture(skipped = false)
        // Same positions/times as the happy fixture, but captured after its final
        // retained point: a clock rollback must not lend these fixes to that trip.
        for (i in 1..9 step 2) repo.recordSkippedDrivingFix(Location("gps").apply {
            latitude = 40.0; longitude = -74.0 + i * .00105
            accuracy = 3f; speed = 30f; bearing = 90f; time = 2_000_000L + i * 3_000L
        })
        assertTrue(visits(repo).isEmpty())
    }

    @Test fun captureAssociationsAreBoundedAndEvictionRequiresFreshProviderEvidence() {
        val (repo, _) = fixture()
        repeat(520) { i -> repo.recordSkippedDrivingFix(Location("gps").apply {
            latitude = 40.0; longitude = -73.989
            accuracy = 3f; speed = 30f; bearing = 90f; time = 2_030_001L + i
        }) }
        val anchors = TrackingRepository::class.java.getDeclaredField("skippedFixAnchors").apply { isAccessible = true }
            .get(repo) as Map<*, *>
        assertEquals(512, anchors.size)
        assertTrue(visits(repo).isEmpty())
    }

    @Test fun inaccurateOrWrongHeadingIntermediateFixesCannotAuthorizeLocalReuse() {
        for (adjust in listOf<(Location) -> Unit>({ it.accuracy = 6f }, { it.bearing = 270f },
                { it.speed = 41f }, { it.latitude += .0002 })) {
            val (repo, _) = fixture(adjust = adjust)
            assertTrue(visits(repo).isEmpty())
        }
    }

    @Test fun ambiguousParallelRoadsAndNewRoadsStayPending() {
        val (repo, _) = fixture()
        repo.upsertRoads(listOf(MatchedRoad("Parallel", "[[-74,40.00006],[-73.98,40.00006]]", 100L, 200L, .99)))
        assertTrue(visits(repo).isEmpty())
        repo.readableDatabase().execSQL("DELETE FROM roads")
        assertTrue(visits(repo).isEmpty())
    }

    @Test fun deletedFixesAndConflictingDuplicateTimesCannotBeBridgedByEvidence() {
        val (repo, ids) = fixture()
        repo.readableDatabase().execSQL("DELETE FROM track_points WHERE id = ?", arrayOf(ids[2]))
        val remaining = repo.loadMatchingWindow(100)
        // Check the original full span as well as the repository's bounded window.
        val points = ids.filter { it != ids[2] }.mapIndexed { i, id ->
            val original = if (i < 2) i else i + 1
            TrackPoint(id, 40.0, -74.0 + original * .0021, 3f, 30f, 90f, 2_000_000L + original * 6_000L, false)
        }
        assertTrue(KnownRoadReuse.find(repo, points, points.map { it.id }.toSet()).isEmpty())
        assertTrue(remaining.points.size < ids.size)
        val (other, _) = fixture()
        other.recordSkippedDrivingFix(Location("gps").apply {
            latitude = 40.1; longitude = -73.99895; accuracy = 3f; speed = 30f; bearing = 90f; time = 2_003_000L
        })
        assertTrue(visits(other).isEmpty())
    }

    @Test fun realServiceAvoidsPaidLookupAndNetworkClockForCorroboratedKnownHighway() {
        checkService(usableEvidence = false)
        checkService(usableEvidence = true)
    }

    private fun checkService(usableEvidence: Boolean) {
        val (repo, ids) = fixture(retained = 100)
        if (!usableEvidence) {
            // With capture associations unavailable, the production fallback is
            // precisely the original five-retained-fix acceptance path.
            (TrackingRepository::class.java.getDeclaredField("skippedFixAnchors").apply { isAccessible = true }
                .get(repo) as MutableMap<*, *>).clear()
        }
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMinutes(10))
        val controller = Robolectric.buildService(TrackingService::class.java)
        val service = controller.get()
        fun field(name: String) = TrackingService::class.java.getDeclaredField(name).apply { isAccessible = true }
        var calls = 0
        val matcher = object : RoadMatcher {
            override val unavailable = false
            override fun rateLimitRemainingMs() = 0L
            override fun match(points: List<TrackPoint>): MatchResult {
                calls++
                return MatchResult(emptyList(), emptyMap(), explicitNoMatch = true)
            }
        }
        field("repository").set(service, repo)
        field("matcher").set(service, matcher)
        field("ready").setBoolean(service, true)
        val executor = field("matchingExecutor").get(service) as ScheduledExecutorService
        try {
            TrackingService::class.java.getDeclaredMethod("maybeRunMatching", Boolean::class.javaPrimitiveType)
                .apply { isAccessible = true }.invoke(service, true)
            executor.submit {}.get(10, TimeUnit.SECONDS)
            assertEquals("Original evidence avoids a paid request required by the original retained-only path",
                if (usableEvidence) 0 else 1, calls)
            if (usableEvidence) {
                assertTrue(repo.loadMatchingWindow(100).points.isEmpty())
                assertEquals(0L, field("lastMatchAttempt").getLong(service))
            } else assertTrue(field("lastMatchAttempt").getLong(service) > 0L)
            assertEquals(1L, repo.getSummary().roadSegmentCount)
            assertEquals(100L, repo.getSummary().trackPointCount)
            repo.readableDatabase().rawQuery("SELECT COUNT(*) FROM track_points WHERE matched=1", null).use {
                assertTrue(it.moveToFirst()); assertEquals(if (usableEvidence) ids.size else 0, it.getInt(0))
            }
        } finally {
            field("ready").setBoolean(service, false)
            controller.destroy()
        }
    }
}
