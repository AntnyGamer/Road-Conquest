package com.roadconquest.app.data

import com.roadconquest.app.achievements.Achievements
import com.roadconquest.app.progression.ProgressionManager
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37], manifest = Config.NONE)
class TownMedalProgressionTest {
    private val context get() = RuntimeEnvironment.getApplication()
    @Before fun reset() {
        AppDatabase::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        TrackingRepository(context).clearHistory()
        Achievements.reset(context)
    }
    @Test fun townTiersAreDistinctDurableAndAwardTheirAchievementsOnlyOnce() {
        val repository = ProgressionRepository(context)
        val summary = TrackingRepository(context).getSummary()
        assertEquals(3, repository.recordTownCompletion("town-a", 3, summary.historyGeneration))
        assertEquals(3, repository.recordTownCompletion("town-a", 1, summary.historyGeneration))
        repository.recordTownCompletion("town-b", 1, summary.historyGeneration)
        var snapshot = ProgressionRepository(context).snapshot()
        assertEquals(2L, snapshot.bronzeTowns)
        assertEquals(1L, snapshot.silverTowns)
        assertEquals(1L, snapshot.goldTowns)
        snapshot = ProgressionManager.sync(context, summary)
        assertTrue(snapshot.rewardedAchievements.containsAll(listOf("town_bronze_1", "town_silver_1", "town_gold_1")))
        assertEquals(snapshot.balance, ProgressionManager.sync(context, summary).balance)
        TrackingRepository(context).clearHistory()
        assertEquals(0, repository.recordTownCompletion("stale-town", 3, summary.historyGeneration))
        assertEquals(0L, ProgressionRepository(context).snapshot().goldTowns)
    }
    @Test fun allAchievementIdsStayUniqueAndExistingAwardsRemainOwned() {
        val items = Achievements.progress(DataSummary(0,0,null,null))
        assertEquals(items.size, items.map { it.id }.toSet().size)
        assertEquals(10, items.count { it.id.startsWith("town_") })
        val preserved = Achievements.progress(DataSummary(0,0,null,null),
            com.roadconquest.app.achievements.AchievementMetrics(
                rewardedAchievements = setOf("roads_100"),
                rewardedAchievementPoints = mapOf("roads_100" to 123L)))
            .first { it.id == "roads_100" }
        assertTrue(preserved.unlocked)
        assertEquals(123L, preserved.rewardPoints)
    }
}
