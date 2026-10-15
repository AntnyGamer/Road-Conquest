package com.roadconquest.app.data

import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37], manifest = Config.NONE)
class WeeklyChallengesTest {
    private lateinit var context: Context
    private val monday = Instant.parse("2026-10-12T00:00:00Z").toEpochMilli()
    @Before fun reset() { context = RuntimeEnvironment.getApplication(); TrackingRepository(context).clearHistory() }

    private fun road(id: String, group: String, time: Long) {
        TrackingRepository(context).readableDatabase().execSQL(
            "INSERT INTO roads(segment_id,name,geometry_json,first_unlocked_at,last_driven_at,road_group_id,min_lat,max_lat,min_lon,max_lon) VALUES (?, 'Road', '[[0,0],[0.001,0]]', ?, ?, ?, 0,0,0,0.001)",
            arrayOf(id, time, time, group))
    }

    @Test fun goalsCountOnlyNewRoadsAndRewardedNewTownsAndAwardOnce() {
        road("old", "shared", monday - 1)
        road("newPart", "shared", monday + 10)
        road("excluded", "x2|motorway", monday + 10)
        repeat(25) { road("r$it", "g$it", monday + 10) }
        val repository = ProgressionRepository(context)
        repeat(3) { repository.recordPlace(PlaceDiscovery(PlaceKind.TOWN, "town$it", "Town $it", visitedAt = monday + 10, latitude = 0.0, longitude = 0.0)) }
        TrackingRepository(context).readableDatabase().execSQL("INSERT INTO visited_places VALUES ('TOWN','start','Start','','',?,0,0)", arrayOf(monday + 10))
        val before = repository.snapshot().balance
        val week = WeeklyChallenges.status(context, monday + 100)
        assertEquals(listOf(25L, 25L, 3L, 3L), week.challenges.map { it.progress })
        assertTrue(week.challenges.all { it.rewarded })
        assertEquals(before + 700, repository.snapshot().balance)
        WeeklyChallenges.status(context, monday + 200)
        assertEquals(before + 700, repository.snapshot().balance)
        assertEquals(listOf(0L, 0L, 0L, 0L), WeeklyChallenges.status(context, week.endsAt).challenges.map { it.progress })
    }

    @Test fun starterGoalsRewardAnOccasionalExplorerWithoutGrantingStretchBonuses() {
        repeat(5) { road("short$it", "short-group$it", monday + 100) }
        val repository = ProgressionRepository(context)
        repository.recordPlace(PlaceDiscovery(
            PlaceKind.TOWN, "week-starter", "Starter town",
            visitedAt = monday + 100, latitude = 0.0, longitude = 0.0
        ))
        val startingBalance = repository.snapshot().balance
        val goals = WeeklyChallenges.status(context, monday + 500)
        assertEquals(listOf(5L, 5L, 1L, 1L), goals.challenges.map { it.progress })
        assertEquals(listOf(true, false, true, false), goals.challenges.map { it.rewarded })
        assertEquals(startingBalance + 150L, repository.snapshot().balance)
        WeeklyChallenges.status(context, monday + 600)
        assertEquals(startingBalance + 150L, repository.snapshot().balance)
    }

    @Test fun utcMondayBoundaryIsStableAcrossYearAndFutureDatesDoNotCount() {
        assertEquals(Instant.parse("2025-12-29T00:00:00Z").toEpochMilli(), WeeklyChallenges.weekStart(Instant.parse("2026-01-01T16:00:00Z").toEpochMilli()))
        road("future", "new", monday + 1000)
        assertEquals(0L, WeeklyChallenges.status(context, monday + 100).challenges.first().progress)
        assertEquals(monday, WeeklyChallenges.weekStart(monday + 7 * 86_400_000L - 1))
        assertEquals(monday + 7 * 86_400_000L, WeeklyChallenges.weekStart(monday + 7 * 86_400_000L))
    }

    @Test fun delayedDiscoveriesCanFinishPreviousWeekWithoutInflatingCurrentProgress() {
        repeat(25) { road("delayed$it", "g$it", monday - 1000) }
        val repository = ProgressionRepository(context)
        repeat(3) { repository.recordPlace(PlaceDiscovery(PlaceKind.TOWN, "late$it", "Late $it", visitedAt = monday - 1000, latitude = 0.0, longitude = 0.0)) }
        val before = repository.snapshot().balance
        assertEquals(listOf(0L, 0L, 0L, 0L), WeeklyChallenges.status(context, monday + 1000).challenges.map { it.progress })
        assertEquals(before + 700, repository.snapshot().balance)
        WeeklyChallenges.status(context, monday + 2000)
        assertEquals(before + 700, repository.snapshot().balance)
    }
}
