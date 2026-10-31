package com.roadconquest.app

import android.os.Looper
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.roadconquest.app.data.AppDatabase
import com.roadconquest.app.achievements.AchievementCategory
import com.roadconquest.app.achievements.Achievements
import com.roadconquest.app.data.DataSummary
import com.roadconquest.app.data.TrackingRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
class AchievementsActivityTest {
    @Before fun resetHistory() {
        AppDatabase::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        TrackingRepository(RuntimeEnvironment.getApplication()).clearHistory()
    }

    @Test fun categoryButtonsFilterAchievementsAndKeepAdsSeparateFromExtra() {
        val controller = Robolectric.buildActivity(AchievementsActivity::class.java).create().start().resume()
        try {
            val activity = controller.get()
            val list = activity.findViewById<LinearLayout>(R.id.achievementsList)
            val all = Achievements.progress(DataSummary(0, 0, null, null))
            val categories = listOf(
                AchievementCategory.ROADS to R.id.achievementRoadsButton,
                AchievementCategory.TILES to R.id.achievementTilesButton,
                AchievementCategory.DISTANCE to R.id.achievementDistanceButton,
                AchievementCategory.PLACES to R.id.achievementPlacesButton,
                AchievementCategory.ADS to R.id.achievementAdsButton
            )
            val expectedRoadCount = all.count { it.category == AchievementCategory.ROADS }
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
            do {
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                if (list.childCount == expectedRoadCount) break
                Thread.sleep(10)
            } while (System.nanoTime() < deadline)
            for ((category, buttonId) in categories) {
                val expectedCount = all.count { it.category == category }
                activity.findViewById<Button>(buttonId).performClick()
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                assertEquals(expectedCount, list.childCount)
                assertEquals(expectedCount, all.count { it.category == category })
                assertTrue(!activity.findViewById<Button>(buttonId).isEnabled)
                var bars = 0
                for (index in 0 until list.childCount) {
                    val card = list.getChildAt(index) as ViewGroup
                    for (child in 0 until card.childCount) if (card.getChildAt(child) is ProgressBar) bars++
                }
                assertEquals(expectedCount, bars)
            }
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun returningToBadgesRefreshesBalanceAndKeepsCategory() {
        val controller = Robolectric.buildActivity(AchievementsActivity::class.java).create().start().resume()
        try {
            val activity = controller.get()
            activity.findViewById<Button>(R.id.achievementTilesButton).performClick()
            val repository = com.roadconquest.app.data.ProgressionRepository(activity)
            assertTrue(repository.awardAchievement("wallet-refresh-check", 222L))
            controller.pause().resume()
            val points = activity.findViewById<TextView>(R.id.achievementPointsText)
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
            while (!points.text.contains("222") && System.nanoTime() < deadline) {
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(10)
            }
            assertTrue("Wallet should refresh after resume", points.text.contains("222"))
            assertTrue(activity.findViewById<Button>(R.id.achievementTilesButton).isSelected)
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
