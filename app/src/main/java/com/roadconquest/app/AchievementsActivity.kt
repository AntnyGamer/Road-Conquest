package com.roadconquest.app

import android.app.Activity
import com.roadconquest.app.ads.ScreenAds
import android.content.Context
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.roadconquest.app.achievements.AchievementCategory
import com.roadconquest.app.achievements.AchievementProgress
import com.roadconquest.app.achievements.Achievements
import com.roadconquest.app.billing.VipEntitlement
import com.roadconquest.app.data.TrackingRepository
import com.roadconquest.app.progression.ProgressionManager
import com.roadconquest.app.util.Appearance
import com.roadconquest.app.util.MeasurementUnits
import com.roadconquest.app.util.ForegroundSession
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class AchievementsActivity : Activity() {
    private lateinit var ads: ScreenAds
    private val executor = Executors.newSingleThreadExecutor()
    private var achievements: List<AchievementProgress> = emptyList()
    private var selectedCategory = AchievementCategory.ROADS
    private var loadGeneration = 0
    private val categoryButtons = linkedMapOf<AchievementCategory, Button>()

    private companion object { const val PROGRESS_MAX = 1000 }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(Appearance.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(Appearance.themeRes(this))
        super.onCreate(savedInstanceState)
        selectedCategory = savedInstanceState?.getString("achievement_category")?.let { saved ->
            AchievementCategory.entries.firstOrNull { it.name == saved }
        } ?: AchievementCategory.ROADS
        WindowCompat.enableEdgeToEdge(window)
        val lightSystemBars = !Appearance.isDark(this)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = lightSystemBars
            isAppearanceLightNavigationBars = lightSystemBars
        }
        setContentView(R.layout.activity_achievements)
        findViewById<Button>(R.id.backNavButton).setOnClickListener { finish() }
        ads = ScreenAds(this, findViewById(R.id.achievementsRoot))
        val root = findViewById<android.view.View>(R.id.achievementsRoot)
        val left = root.paddingLeft
        val top = root.paddingTop
        val right = root.paddingRight
        val bottom = root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val safe = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(left + safe.left, top + safe.top, right + safe.right, bottom + safe.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)

        val list = findViewById<LinearLayout>(R.id.achievementsList)
        categoryButtons.putAll(
            linkedMapOf(
                AchievementCategory.ROADS to findViewById(R.id.achievementRoadsButton),
                AchievementCategory.TILES to findViewById(R.id.achievementTilesButton),
                AchievementCategory.DISTANCE to findViewById(R.id.achievementDistanceButton),
                AchievementCategory.PLACES to findViewById(R.id.achievementPlacesButton),
                AchievementCategory.TOWNS to findViewById(R.id.achievementTownsButton),
                AchievementCategory.ADS to findViewById(R.id.achievementAdsButton),
                AchievementCategory.EXTRA to findViewById(R.id.achievementExtraButton)
            )
        )
        categoryButtons.forEach { (category, button) ->
            button.setOnClickListener {
                selectedCategory = category
                renderCategory(list)
            }
        }
        updateCategoryButtons()
    }

    /** Reload progress when returning after a drive, restore, or earned reward. */
    private fun refreshAchievements() {
        val expected = ++loadGeneration
        executor.execute {
            val result = runCatching {
                val summary = TrackingRepository(this).getSummary()
                val progression = ProgressionManager.sync(this, summary)
                progression to Achievements.progress(this, summary, ProgressionManager.metrics(progression))
            }
            runOnUiThread {
                if (isDestroyed || expected != loadGeneration) return@runOnUiThread
                val text = findViewById<TextView>(R.id.achievementsText)
                val points = findViewById<TextView>(R.id.achievementPointsText)
                val list = findViewById<LinearLayout>(R.id.achievementsList)
                result.fold(
                    onSuccess = { (progression, achievements) ->
                        points.text = String.format(Locale.getDefault(), "⚔ %,d points", progression.balance)
                        text.visibility = android.view.View.GONE
                        this.achievements = achievements
                        if (selectedCategory == AchievementCategory.EXTRA &&
                            achievements.none { it.category == AchievementCategory.EXTRA }) {
                            selectedCategory = AchievementCategory.ROADS
                        }
                        renderCategory(list)
                    },
                    onFailure = {
                        achievements = emptyList()
                        list.removeAllViews()
                        text.visibility = android.view.View.VISIBLE
                        text.text = "Achievements are temporarily unavailable."
                    }
                )
            }
        }
    }

    private fun renderCategory(container: LinearLayout) {
        container.removeAllViews()
        Achievements.visibleAchievements(achievements, VipEntitlement.isActive(this)).asSequence()
            .filter { it.category == selectedCategory }
            .sortedWith(compareBy<AchievementProgress> { it.unlocked }
                .thenByDescending { (it.progress / it.goal).coerceIn(0.0, 1.0) })
            .forEach { addAchievement(container, it) }
        updateCategoryButtons()
    }

    private fun updateCategoryButtons() {
        categoryButtons.forEach { (category, button) ->
            // Show the selected filter distinctly, without changing the existing disabled-selection behavior.
            val selected = category == selectedCategory
            button.isEnabled = !selected
            button.isSelected = selected
            button.setBackgroundResource(if (selected) R.drawable.selected_filter_background else R.drawable.settings_action_background)
            button.setTextColor(Appearance.color(this,
                if (selected) R.attr.rcOnAccent else R.attr.rcTextPrimary))
            // VIP has no rewarded-ad offers: preserve earned ad milestones,
            // but do not show an empty page of impossible locked challenges.
            button.visibility = if (
                (category == AchievementCategory.ADS && VipEntitlement.isActive(this) &&
                    achievements.none { it.category == AchievementCategory.ADS && it.unlocked }) ||
                (category == AchievementCategory.EXTRA &&
                    achievements.none { it.category == AchievementCategory.EXTRA })
            ) android.view.View.GONE else android.view.View.VISIBLE
        }
    }

    private fun addAchievement(container: LinearLayout, item: AchievementProgress) {
        val units = MeasurementUnits.current()
        val metricDistance = units.metric && item.unit == "mi"
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.panel_background)
            val padding = dp(16)
            setPadding(padding, padding, padding, padding)
        }
        card.addView(TextView(this).apply {
            val title = if (metricDistance && item.id == "miles_10000") "Distance Champion"
                else if (metricDistance && item.id == "miles_5000") "Distance Legend" else item.title
            text = (if (item.unlocked) "✓  " else "○  ") + title
            setTextColor(Appearance.color(this@AchievementsActivity, R.attr.rcTextPrimary))
            textSize = 17f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        card.addView(TextView(this).apply {
            val description = if (metricDistance) "Travel " + units.longDistance(
                item.goal * MeasurementUnits.METERS_PER_MILE) else item.description
            text = description + String.format(Locale.getDefault(), "  •  +%,d points", item.rewardPoints)
            setTextColor(Appearance.color(this@AchievementsActivity, R.attr.rcTextSecondary))
            textSize = 14f
            setPadding(0, dp(4), 0, dp(8))
        })
        card.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = PROGRESS_MAX
            progress = ((item.progress / item.goal).coerceIn(0.0, 1.0) * PROGRESS_MAX).roundToInt()
            isIndeterminate = false
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(18)))
        card.addView(TextView(this).apply {
            text = progressText(item)
            setTextColor(Appearance.color(this@AchievementsActivity, R.attr.rcTextSecondary))
            textSize = 13f
            setPadding(0, dp(6), 0, 0)
        })
        container.addView(card, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(12) })
    }

    private fun progressText(item: AchievementProgress): String = when (item.unit) {
        "mi" -> MeasurementUnits.current().distanceProgress(item.progress * MeasurementUnits.METERS_PER_MILE,
            item.goal * MeasurementUnits.METERS_PER_MILE)
        "complete" -> if (item.unlocked) "Completed" else "Not completed"
        else -> String.format(
            Locale.getDefault(), "%,.0f / %,.0f %s",
            item.progress.coerceAtMost(item.goal), item.goal, item.unit
        )
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("achievement_category", selectedCategory.name)
        super.onSaveInstanceState(outState)
    }

    override fun onStart() {
        super.onStart()
        ForegroundSession.app.onStart(this)
    }

    override fun onStop() {
        ForegroundSession.app.onStop(isChangingConfigurations)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        ads.onResume()
        // Returning after a VIP purchase/refund changes which ad-only
        // challenges are attainable; refresh without resetting progress.
        if (categoryButtons.isNotEmpty()) {
            if (selectedCategory == AchievementCategory.ADS &&
                VipEntitlement.isActive(this) && achievements.none {
                    it.category == AchievementCategory.ADS && it.unlocked
                }
            ) selectedCategory = AchievementCategory.ROADS
            renderCategory(findViewById(R.id.achievementsList))
            refreshAchievements()
        }
    }

    override fun onPause() {
        ads.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        loadGeneration++
        ads.destroy()
        executor.shutdownNow()
        super.onDestroy()
    }
}
