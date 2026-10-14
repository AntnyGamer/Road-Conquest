package com.roadconquest.app.achievements

import android.content.Context
import com.roadconquest.app.data.DataSummary
import com.roadconquest.app.data.ProgressionRepository

data class AchievementMetrics(
    val towns: Long = 0L,
    val states: Long = 0L,
    val countries: Long = 0L,
    val adsWatched: Long = 0L,
    val lowestBatteryPercent: Int = 101
)

enum class AchievementCategory(val label: String) {
    ROADS("Road Conquest"),
    DISTANCE("Mileage"),
    PLACES("Exploration"),
    ADS("Ad Rewards"),
    EXTRA("Bonus")
}

data class AchievementProgress(
    val id: String,
    val title: String,
    val description: String,
    val category: AchievementCategory,
    val progress: Double,
    val goal: Double,
    val unit: String,
    val rewardPoints: Long
) {
    val unlocked: Boolean get() = progress >= goal
}

object Achievements {
    private const val PREFS = "roadconquest_achievements"
    private const val KEY_INITIALIZED = "announcements_initialized"
    private const val KEY_ANNOUNCED = "announced_ids"
    private const val KEY_MAX_ROADS = "max_roads_seen"
    private const val METERS_PER_MILE = 1609.344
    private val stateLock = Any()

    /**
     * VIP removes rewarded ads; do not display unattainable ad-only challenges
     * as outstanding goals. Previously completed challenges and their awards
     * remain visible and owned. Never mint points just because VIP was bought.
     */
    fun visibleAchievements(items: List<AchievementProgress>, vip: Boolean): List<AchievementProgress> =
        if (!vip) items else items.filter {
            it.category != AchievementCategory.ADS || it.unlocked
        }

    fun progress(summary: DataSummary, metrics: AchievementMetrics = AchievementMetrics()): List<AchievementProgress> {
        val miles = summary.distanceMeters / METERS_PER_MILE
        val roads = summary.roadsUnlockedCount.toDouble()
        val battery5 = if (metrics.lowestBatteryPercent <= 5) 1.0 else 0.0
        val battery1 = if (metrics.lowestBatteryPercent <= 1) 1.0 else 0.0
        return listOf(
            AchievementProgress("roads_10", "Newbie Explorer", "Unlock 10 roads", AchievementCategory.ROADS, roads, 10.0, "roads", 100),
            AchievementProgress("roads_100", "Casual Explorer", "Unlock 100 roads", AchievementCategory.ROADS, roads, 100.0, "roads", 350),
            AchievementProgress("roads_1000", "Road Conquerer", "Unlock 1,000 roads", AchievementCategory.ROADS, roads, 1000.0, "roads", 1_500),
            AchievementProgress("miles_10", "Beginner Driver", "Travel 10 miles", AchievementCategory.DISTANCE, miles, 10.0, "mi", 100),
            AchievementProgress("miles_100", "Average Driver", "Travel 100 miles", AchievementCategory.DISTANCE, miles, 100.0, "mi", 400),
            AchievementProgress("miles_1000", "Expert Driver", "Travel 1,000 miles", AchievementCategory.DISTANCE, miles, 1000.0, "mi", 2_000),

            AchievementProgress("towns_5", "Town Hopper", "Visit 5 different towns", AchievementCategory.PLACES, metrics.towns.toDouble(), 5.0, "towns", 250),
            AchievementProgress("towns_25", "Local Explorer", "Visit 25 different towns", AchievementCategory.PLACES, metrics.towns.toDouble(), 25.0, "towns", 600),
            AchievementProgress("towns_100", "Town Collector", "Visit 100 different towns", AchievementCategory.PLACES, metrics.towns.toDouble(), 100.0, "towns", 1_800),
            AchievementProgress("states_3", "State Hopper", "Visit 3 different states or regions", AchievementCategory.PLACES, metrics.states.toDouble(), 3.0, "states", 500),
            AchievementProgress("states_10", "State Explorer", "Visit 10 different states or regions", AchievementCategory.PLACES, metrics.states.toDouble(), 10.0, "states", 1_200),
            AchievementProgress("states_25", "State Collector", "Visit 25 different states or regions", AchievementCategory.PLACES, metrics.states.toDouble(), 25.0, "states", 2_500),
            AchievementProgress("countries_2", "Border Crosser", "Visit 2 different countries", AchievementCategory.PLACES, metrics.countries.toDouble(), 2.0, "countries", 750),
            AchievementProgress("countries_5", "World Traveler", "Visit 5 different countries", AchievementCategory.PLACES, metrics.countries.toDouble(), 5.0, "countries", 1_800),
            AchievementProgress("countries_10", "Globe Conquerer", "Visit 10 different countries", AchievementCategory.PLACES, metrics.countries.toDouble(), 10.0, "countries", 4_000),

            AchievementProgress("battery_5", "Running on Fumes", "Use Road Conquest with 5% battery or less", AchievementCategory.EXTRA, battery5, 1.0, "complete", 300),
            AchievementProgress("battery_1", "Last Percent", "Use Road Conquest with 1% battery or less", AchievementCategory.EXTRA, battery1, 1.0, "complete", 1_000),

            AchievementProgress("ads_5", "Ad Starter", "Watch 5 ads", AchievementCategory.ADS, metrics.adsWatched.toDouble(), 5.0, "ads", 100),
            AchievementProgress("ads_10", "Ad Regular", "Watch 10 ads", AchievementCategory.ADS, metrics.adsWatched.toDouble(), 10.0, "ads", 200),
            AchievementProgress("ads_25", "Ad Supporter", "Watch 25 ads", AchievementCategory.ADS, metrics.adsWatched.toDouble(), 25.0, "ads", 500),
            AchievementProgress("ads_50", "Ad Veteran", "Watch 50 ads", AchievementCategory.ADS, metrics.adsWatched.toDouble(), 50.0, "ads", 1_000),
            AchievementProgress("ads_100", "Ad Legend", "Watch 100 ads", AchievementCategory.ADS, metrics.adsWatched.toDouble(), 100.0, "ads", 2_500)
        )
    }

    fun progress(
        context: Context,
        summary: DataSummary,
        metrics: AchievementMetrics = AchievementMetrics()
    ): List<AchievementProgress> = synchronized(stateLock) {
        // A UI/background summary can outlive a device-data reset. Never let that stale
        // snapshot recreate the separate achievement preference cache after DB history moved on.
        if (!isCurrentSummary(context, summary)) return@synchronized progress(summary, metrics)
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val savedMax = prefs.getLong(KEY_MAX_ROADS, 0L)
        val highestRoads = maxOf(summary.roadsUnlockedCount, savedMax)
        if (highestRoads > savedMax) {
            prefs.edit().putLong(KEY_MAX_ROADS, highestRoads).apply()
        }
        progress(summary.copy(roadsUnlockedCount = highestRoads), metrics)
    }

    fun reset(context: Context) = synchronized(stateLock) {
        // Explicit device-data deletion must be durable before it returns.
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().clear().commit()
        Unit
    }

    fun newlyUnlocked(
        context: Context,
        summary: DataSummary,
        metrics: AchievementMetrics = AchievementMetrics()
    ): List<AchievementProgress> = synchronized(stateLock) {
        if (!isCurrentSummary(context, summary)) return@synchronized emptyList()
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // We already validated this summary under stateLock. Avoid calling progress(context,...),
        // which would repeat the database-generation check and preferences lookup.
        val savedMax = prefs.getLong(KEY_MAX_ROADS, 0L)
        val highestRoads = maxOf(summary.roadsUnlockedCount, savedMax)
        if (highestRoads > savedMax) {
            prefs.edit().putLong(KEY_MAX_ROADS, highestRoads).apply()
        }
        val current = progress(summary.copy(roadsUnlockedCount = highestRoads), metrics)
            .filter { it.unlocked }
        val announced = prefs.getStringSet(KEY_ANNOUNCED, emptySet()).orEmpty().toMutableSet()
        if (!prefs.getBoolean(KEY_INITIALIZED, false)) {
            announced += current.map { it.id }
            prefs.edit().putBoolean(KEY_INITIALIZED, true).putStringSet(KEY_ANNOUNCED, announced).apply()
            return@synchronized emptyList()
        }
        val fresh = current.filter { it.id !in announced }
        if (fresh.isNotEmpty()) {
            announced += fresh.map { it.id }
            prefs.edit().putStringSet(KEY_ANNOUNCED, announced).apply()
        }
        fresh
    }

    private fun isCurrentSummary(context: Context, summary: DataSummary): Boolean =
        summary.historyGeneration < 0L ||
            ProgressionRepository(context).isHistoryGenerationCurrent(summary.historyGeneration)
}
