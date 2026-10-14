package com.roadconquest.app.achievements

import com.roadconquest.app.data.DataSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37], manifest = Config.NONE)
class AchievementsTest {
    @Test fun milestonesReflectLocalRoadAndMileageProgress() {
        val summary = DataSummary(
            trackPointCount = 0,
            roadSegmentCount = 0,
            firstTrackAt = null,
            lastTrackAt = null,
            distanceMeters = 100.0 * 1609.344,
            roadsUnlockedCount = 100
        )
        val progress = Achievements.progress(summary)
        assertEquals(
            listOf("Newbie Explorer", "Casual Explorer", "Road Conquerer", "Beginner Driver", "Average Driver", "Expert Driver"),
            progress.take(6).map { it.title }
        )
        assertTrue(progress.size > 6)
        assertEquals(AchievementCategory.ROADS, progress.first { it.id == "roads_10" }.category)
        assertEquals(AchievementCategory.DISTANCE, progress.first { it.id == "miles_10" }.category)
        assertEquals(AchievementCategory.PLACES, progress.first { it.id == "towns_5" }.category)
        assertEquals(AchievementCategory.ADS, progress.first { it.id == "ads_5" }.category)
        assertEquals(AchievementCategory.EXTRA, progress.first { it.id == "battery_5" }.category)
        assertTrue(progress.first { it.id == "roads_10" }.unlocked)
        assertTrue(progress.first { it.id == "roads_100" }.unlocked)
        assertFalse(progress.first { it.id == "roads_1000" }.unlocked)
        assertTrue(progress.first { it.id == "miles_100" }.unlocked)
        assertFalse(progress.first { it.id == "miles_1000" }.unlocked)
    }

    @Test fun placeBatteryAndAdMilestonesUseTheirDedicatedMetrics() {
        val summary = DataSummary(0, 0, null, null, 0.0, 0)
        val metrics = AchievementMetrics(towns = 25, states = 3, countries = 2, adsWatched = 10, lowestBatteryPercent = 1)
        val progress = Achievements.progress(summary, metrics)
        assertTrue(progress.first { it.id == "towns_25" }.unlocked)
        assertTrue(progress.first { it.id == "states_3" }.unlocked)
        assertTrue(progress.first { it.id == "countries_2" }.unlocked)
        assertTrue(progress.first { it.id == "battery_5" }.unlocked)
        assertTrue(progress.first { it.id == "battery_1" }.unlocked)
        assertTrue(progress.first { it.id == "ads_5" }.unlocked)
        assertTrue(progress.first { it.id == "ads_10" }.unlocked)
        assertFalse(progress.first { it.id == "ads_25" }.unlocked)
    }

    @Test fun vipHidesOnlyLockedAdMilestonesWithoutGrantingPoints() {
        val items = Achievements.progress(
            DataSummary(0, 0, null, null, 0.0, 0),
            AchievementMetrics(adsWatched = 10)
        )
        val visible = Achievements.visibleAchievements(items, vip = true)
        assertTrue(visible.any { it.id == "ads_5" && it.unlocked })
        assertTrue(visible.any { it.id == "ads_10" && it.unlocked })
        assertFalse(visible.any { it.id == "ads_25" })
        assertTrue(visible.any { it.id == "roads_10" })
        assertEquals(items, Achievements.visibleAchievements(items, vip = false))
    }

    @Test fun correctedRoadGroupingCannotRelockAnEarnedMilestone() {
        val context = RuntimeEnvironment.getApplication()
        Achievements.reset(context)
        val reached = DataSummary(0, 0, null, null, 0.0, 10)
        assertTrue(Achievements.progress(context, reached).first { it.id == "roads_10" }.unlocked)

        val corrected = reached.copy(roadsUnlockedCount = 9)
        val progress = Achievements.progress(context, corrected)
        assertTrue(progress.first { it.id == "roads_10" }.unlocked)
        assertEquals(10.0, progress.first { it.id == "roads_10" }.progress, 0.0)

        Achievements.reset(context)
        assertFalse(Achievements.progress(context, corrected).first { it.id == "roads_10" }.unlocked)
    }

    @Test fun existingProgressIsBaselinedAndFutureMilestonesAnnounceOnce() {
        val context = RuntimeEnvironment.getApplication()
        Achievements.reset(context)
        val initial = DataSummary(0, 0, null, null, 9.0 * 1609.344, 9)
        assertTrue(Achievements.newlyUnlocked(context, initial).isEmpty())

        val crossed = DataSummary(0, 0, null, null, 10.0 * 1609.344, 10)
        assertEquals(setOf("roads_10", "miles_10"), Achievements.newlyUnlocked(context, crossed).map { it.id }.toSet())
        assertTrue(Achievements.newlyUnlocked(context, crossed).isEmpty())
    }
}
