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
        assertTrue(repository.recordTownCompletion("town-a", 3, summary.historyGeneration).changed)
        val unchanged = repository.recordTownCompletion("town-a", 1, summary.historyGeneration)
        assertEquals(3, unchanged.tier)
        assertFalse(unchanged.changed)
        repository.recordTownCompletion("town-b", 1, summary.historyGeneration)
        var snapshot = ProgressionRepository(context).snapshot()
        assertEquals(2L, snapshot.bronzeTowns)
        assertEquals(1L, snapshot.silverTowns)
        assertEquals(1L, snapshot.goldTowns)
        snapshot = ProgressionManager.sync(context, summary)
        assertTrue(snapshot.rewardedAchievements.containsAll(listOf("town_bronze_1", "town_silver_1", "town_gold_1")))
        assertEquals(snapshot.balance, ProgressionManager.sync(context, summary).balance)
        TrackingRepository(context).clearHistory()
        assertEquals(TownCompletionUpdate(0, false), repository.recordTownCompletion("stale-town", 3, summary.historyGeneration))
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

    @Test fun cachedTownCanEarnGoldWithoutOpeningTheTownOverlay() {
        val tracking = TrackingRepository(context)
        val progression = ProgressionRepository(context)
        val origin = com.roadconquest.app.map.FogGrid.cell(40.0, -74.0)!!
        val center = com.roadconquest.app.map.FogGrid.center(origin)
        val corners = com.roadconquest.app.map.FogGrid.corners(center.first, center.second)
        val place = PlaceDiscovery(PlaceKind.TOWN, "offline-town", "Offline Town", countryName = "Testland",
            visitedAt = 1_000_000L, latitude = center.first, longitude = center.second)
        progression.recordPlace(place)
        val geometry = """{"type":"Polygon","coordinates":[[[${corners[1]},${corners[4]}],[${corners[3]},${corners[4]}],[${corners[3]},${corners[0]}],[${corners[1]},${corners[0]}],[${corners[1]},${corners[4]}]]]}"""
        val data = com.roadconquest.app.map.PlaceOverlayData(place.key, place.displayName, place.kind, null, 1.0, geometry)
        assertTrue(com.roadconquest.app.map.PlaceOverlayCache.write(context, place, data, verifiedForDiscovery = true))
        tracking.recordExploredGridCell(android.location.Location("gps").apply {
            latitude = center.first; longitude = center.second; accuracy = 5f; time = 1_000_000L
        })
        com.roadconquest.app.util.Prefs.setPlaceOverlayMode(context, com.roadconquest.app.map.PlaceOverlayMode.NONE)
        com.roadconquest.app.map.TownCompletionTracker.reset()
        val snapshot = ProgressionManager.sync(context, tracking.getSummary())
        assertEquals(1L, snapshot.goldTowns)
        assertTrue("Town achievements should be rewarded without opening overlays", "town_gold_1" in snapshot.rewardedAchievements)
        val balance = snapshot.balance
        assertEquals(balance, ProgressionManager.sync(context, tracking.getSummary()).balance)

        // An idle backfill must resume for new towns, then again when a missing
        // boundary arrives without changing the town count or explored tiles.
        val laterPlace = place.copy(key = "later-offline-town", displayName = "Later Town")
        progression.recordPlace(laterPlace)
        assertEquals(1L, ProgressionManager.sync(context, tracking.getSummary()).goldTowns)
        assertTrue(com.roadconquest.app.map.PlaceOverlayCache.write(context, laterPlace,
            data.copy(key = laterPlace.key, name = laterPlace.displayName), verifiedForDiscovery = true))
        val refreshed = ProgressionManager.sync(context, tracking.getSummary())
        assertEquals(2L, refreshed.goldTowns)
        assertEquals(refreshed.balance, ProgressionManager.sync(context, tracking.getSummary()).balance)
    }
}
