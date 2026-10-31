package com.roadconquest.app.achievements

import android.content.Context
import com.roadconquest.app.data.DataSummary
import com.roadconquest.app.data.ProgressionRepository

data class AchievementMetrics(
    val towns: Long = 0L,
    val states: Long = 0L,
    val countries: Long = 0L,
    val adsWatched: Long = 0L,
    val lowestBatteryPercent: Int = 101,
    val rewardedRoads: Long = 0L,
    val tilesUnlocked: Long = 0L,
    val rewardedAchievements: Set<String> = emptySet(),
    val rewardedAchievementPoints: Map<String, Long> = emptyMap(),
    val bronzeTowns: Long = 0L,
    val silverTowns: Long = 0L,
    val goldTowns: Long = 0L
)

enum class AchievementCategory(val label: String) {
    ROADS("Road Conquest"),
    TILES("Map Tiles"),
    DISTANCE("Distance"),
    PLACES("Exploration"),
    TOWNS("Town Badges"),
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
        val roads = maxOf(summary.roadsUnlockedCount, metrics.rewardedRoads).toDouble()
        val battery5 = if (metrics.lowestBatteryPercent <= 5) 1.0 else 0.0
        val battery1 = if (metrics.lowestBatteryPercent <= 1) 1.0 else 0.0
        return listOf(
            AchievementProgress("roads_10", "Newbie Explorer", "Unlock 10 roads", AchievementCategory.ROADS, roads, 10.0, "roads", 50),
            AchievementProgress("roads_100", "Casual Explorer", "Unlock 100 roads", AchievementCategory.ROADS, roads, 100.0, "roads", 175),
            AchievementProgress("roads_250", "Road Pathfinder", "Unlock 250 roads", AchievementCategory.ROADS, roads, 250.0, "roads", 250),
            AchievementProgress("roads_1000", "Road Conquerer", "Unlock 1,000 roads", AchievementCategory.ROADS, roads, 1000.0, "roads", 750),
            AchievementProgress("roads_2500", "Road Master", "Unlock 2,500 roads", AchievementCategory.ROADS, roads, 2500.0, "roads", 1250),
            AchievementProgress("roads_5000", "Road Legend", "Unlock 5,000 roads", AchievementCategory.ROADS, roads, 5000.0, "roads", 2000),
            AchievementProgress("roads_10000", "Road Pioneer", "Unlock 10,000 roads", AchievementCategory.ROADS, roads, 10000.0, "roads", 3250),
            AchievementProgress("tiles_1", "First Square", "Explore your first map tile", AchievementCategory.TILES, metrics.tilesUnlocked.toDouble(), 1.0, "tiles", 40),
            AchievementProgress("tiles_10", "Square Scout", "Explore 10 unique map tiles", AchievementCategory.TILES, metrics.tilesUnlocked.toDouble(), 10.0, "tiles", 75),
            AchievementProgress("tiles_25", "Tile Tracker", "Explore 25 unique map tiles", AchievementCategory.TILES, metrics.tilesUnlocked.toDouble(), 25.0, "tiles", 125),
            AchievementProgress("tiles_50", "Grid Explorer", "Explore 50 unique map tiles", AchievementCategory.TILES, metrics.tilesUnlocked.toDouble(), 50.0, "tiles", 225),
            AchievementProgress("tiles_100", "Map Maker", "Explore 100 unique map tiles", AchievementCategory.TILES, metrics.tilesUnlocked.toDouble(), 100.0, "tiles", 375),
            AchievementProgress("tiles_250", "Territory Trekker", "Explore 250 unique map tiles", AchievementCategory.TILES, metrics.tilesUnlocked.toDouble(), 250.0, "tiles", 625),
            AchievementProgress("tiles_500", "Wide Explorer", "Explore 500 unique map tiles", AchievementCategory.TILES, metrics.tilesUnlocked.toDouble(), 500.0, "tiles", 1000),
            AchievementProgress("tiles_1000", "Master Cartographer", "Explore 1,000 unique map tiles", AchievementCategory.TILES, metrics.tilesUnlocked.toDouble(), 1000.0, "tiles", 1500),
            AchievementProgress("tiles_2500", "Territory Legend", "Explore 2,500 unique map tiles", AchievementCategory.TILES, metrics.tilesUnlocked.toDouble(), 2500.0, "tiles", 2500),
            AchievementProgress("tiles_5000", "World Mapper", "Explore 5,000 unique map tiles", AchievementCategory.TILES, metrics.tilesUnlocked.toDouble(), 5000.0, "tiles", 4000),
            AchievementProgress("miles_10", "Beginner Driver", "Travel 10 miles", AchievementCategory.DISTANCE, miles, 10.0, "mi", 50),
            AchievementProgress("miles_100", "Average Driver", "Travel 100 miles", AchievementCategory.DISTANCE, miles, 100.0, "mi", 200),
            AchievementProgress("miles_250", "Distance Explorer", "Travel 250 miles", AchievementCategory.DISTANCE, miles, 250.0, "mi", 325),
            AchievementProgress("miles_1000", "Expert Driver", "Travel 1,000 miles", AchievementCategory.DISTANCE, miles, 1000.0, "mi", 1000),

            AchievementProgress("miles_2500", "Long-Distance Driver", "Travel 2,500 miles", AchievementCategory.DISTANCE, miles, 2500.0, "mi", 1500),
            AchievementProgress("miles_5000", "Mileage Legend", "Travel 5,000 miles", AchievementCategory.DISTANCE, miles, 5000.0, "mi", 2250),
            AchievementProgress("miles_10000", "Ten Thousand Miles", "Travel 10,000 miles", AchievementCategory.DISTANCE, miles, 10000.0, "mi", 3250),

            AchievementProgress("towns_5", "Town Hopper", "Visit 5 different towns", AchievementCategory.PLACES, metrics.towns.toDouble(), 5.0, "towns", 125),
            AchievementProgress("towns_25", "Local Explorer", "Visit 25 different towns", AchievementCategory.PLACES, metrics.towns.toDouble(), 25.0, "towns", 300),
            AchievementProgress("towns_50", "Regional Explorer", "Visit 50 different towns", AchievementCategory.PLACES, metrics.towns.toDouble(), 50.0, "towns", 450),
            AchievementProgress("towns_100", "Town Collector", "Visit 100 different towns", AchievementCategory.PLACES, metrics.towns.toDouble(), 100.0, "towns", 900),
            AchievementProgress("towns_250", "Town Adventurer", "Visit 250 different towns", AchievementCategory.PLACES, metrics.towns.toDouble(), 250.0, "towns", 1500),
            AchievementProgress("towns_500", "Town Legend", "Visit 500 different towns", AchievementCategory.PLACES, metrics.towns.toDouble(), 500.0, "towns", 2500),
            AchievementProgress("states_3", "State Hopper", "Visit 3 different states or regions", AchievementCategory.PLACES, metrics.states.toDouble(), 3.0, "states", 250),
            AchievementProgress("states_10", "State Explorer", "Visit 10 different states or regions", AchievementCategory.PLACES, metrics.states.toDouble(), 10.0, "states", 600),
            AchievementProgress("states_25", "State Collector", "Visit 25 different states or regions", AchievementCategory.PLACES, metrics.states.toDouble(), 25.0, "states", 1250),
            AchievementProgress("states_50", "Across the States", "Visit 50 different states or regions", AchievementCategory.PLACES, metrics.states.toDouble(), 50.0, "states", 2250),
            AchievementProgress("countries_2", "Border Crosser", "Visit 2 different countries", AchievementCategory.PLACES, metrics.countries.toDouble(), 2.0, "countries", 375),
            AchievementProgress("countries_5", "World Traveler", "Visit 5 different countries", AchievementCategory.PLACES, metrics.countries.toDouble(), 5.0, "countries", 900),
            AchievementProgress("countries_10", "Globe Conquerer", "Visit 10 different countries", AchievementCategory.PLACES, metrics.countries.toDouble(), 10.0, "countries", 2000),

            AchievementProgress("countries_25", "Global Pathfinder", "Visit 25 different countries", AchievementCategory.PLACES, metrics.countries.toDouble(), 25.0, "countries", 3500),

            AchievementProgress("battery_5", "Running on Fumes", "Use Road Conquest with 5% battery or less", AchievementCategory.EXTRA, battery5, 1.0, "complete", 150),
            AchievementProgress("battery_1", "Last Percent", "Use Road Conquest with 1% battery or less", AchievementCategory.EXTRA, battery1, 1.0, "complete", 350),

            AchievementProgress("ads_5", "Ad Starter", "Watch 5 ads", AchievementCategory.ADS, metrics.adsWatched.toDouble(), 5.0, "ads", 50),
            AchievementProgress("ads_10", "Ad Regular", "Watch 10 ads", AchievementCategory.ADS, metrics.adsWatched.toDouble(), 10.0, "ads", 100),
            AchievementProgress("ads_25", "Ad Supporter", "Watch 25 ads", AchievementCategory.ADS, metrics.adsWatched.toDouble(), 25.0, "ads", 250),
            AchievementProgress("ads_50", "Ad Veteran", "Watch 50 ads", AchievementCategory.ADS, metrics.adsWatched.toDouble(), 50.0, "ads", 500),
            AchievementProgress("ads_100", "Ad Legend", "Watch 100 ads", AchievementCategory.ADS, metrics.adsWatched.toDouble(), 100.0, "ads", 1250),

            AchievementProgress("roads_25", "Neighborhood Scout", "Unlock 25 roads", AchievementCategory.ROADS, roads, 25.0, "roads", 75),
            AchievementProgress("roads_50", "Street Seeker", "Unlock 50 roads", AchievementCategory.ROADS, roads, 50.0, "roads", 115),
            AchievementProgress("roads_500", "Street Scholar", "Unlock 500 roads", AchievementCategory.ROADS, roads, 500.0, "roads", 425),
            AchievementProgress("roads_750", "Route Ranger", "Unlock 750 roads", AchievementCategory.ROADS, roads, 750.0, "roads", 550),
            AchievementProgress("roads_1500", "Beyond Familiar Roads", "Unlock 1,500 roads", AchievementCategory.ROADS, roads, 1500.0, "roads", 900),
            AchievementProgress("roads_25000", "Road Atlas", "Unlock 25,000 roads", AchievementCategory.ROADS, roads, 25000.0, "roads", 5000),
            AchievementProgress("tiles_5", "Beyond the Block", "Explore 5 unique map tiles", AchievementCategory.TILES, metrics.tilesUnlocked.toDouble(), 5.0, "tiles", 50),
            AchievementProgress("tiles_75", "Horizon Hunter", "Explore 75 unique map tiles", AchievementCategory.TILES, metrics.tilesUnlocked.toDouble(), 75.0, "tiles", 300),
            AchievementProgress("tiles_1500", "Open Horizons", "Explore 1,500 unique map tiles", AchievementCategory.TILES, metrics.tilesUnlocked.toDouble(), 1500.0, "tiles", 1875),
            AchievementProgress("tiles_10000", "Continental Cartographer", "Explore 10,000 unique map tiles", AchievementCategory.TILES, metrics.tilesUnlocked.toDouble(), 10000.0, "tiles", 6000),
            AchievementProgress("miles_25", "Getting Rolling", "Travel 25 miles", AchievementCategory.DISTANCE, miles, 25.0, "mi", 90),
            AchievementProgress("miles_50", "Finding Your Way", "Travel 50 miles", AchievementCategory.DISTANCE, miles, 50.0, "mi", 125),
            AchievementProgress("miles_500", "Cruising Along", "Travel 500 miles", AchievementCategory.DISTANCE, miles, 500.0, "mi", 500),
            AchievementProgress("miles_750", "Further Afield", "Travel 750 miles", AchievementCategory.DISTANCE, miles, 750.0, "mi", 700),
            AchievementProgress("miles_25000", "Around the Odometer", "Travel 25,000 miles", AchievementCategory.DISTANCE, miles, 25000.0, "mi", 5000),
            AchievementProgress("miles_50000", "Lifetime Adventurer", "Travel 50,000 miles", AchievementCategory.DISTANCE, miles, 50000.0, "mi", 7500),
            AchievementProgress("towns_10", "Town Trailblazer", "Visit 10 different towns", AchievementCategory.PLACES, metrics.towns.toDouble(), 10.0, "towns", 175),
            AchievementProgress("towns_1000", "A Thousand Welcome Signs", "Visit 1,000 different towns", AchievementCategory.PLACES, metrics.towns.toDouble(), 1000.0, "towns", 4000),
            AchievementProgress("states_5", "Regional Rambler", "Visit 5 different states or regions", AchievementCategory.PLACES, metrics.states.toDouble(), 5.0, "states", 375),
            AchievementProgress("countries_3", "Beyond Borders", "Visit 3 different countries", AchievementCategory.PLACES, metrics.countries.toDouble(), 3.0, "countries", 550),
            AchievementProgress("town_bronze_1", "Making Your Mark", "Earn bronze or better in 5 towns (25% explored each)", AchievementCategory.TOWNS, metrics.bronzeTowns.toDouble(), 5.0, "towns", 40),
            AchievementProgress("town_bronze_5", "Neighborhood Navigator", "Earn bronze or better in 20 towns", AchievementCategory.TOWNS, metrics.bronzeTowns.toDouble(), 20.0, "towns", 125),
            AchievementProgress("town_bronze_25", "Bronze Atlas", "Earn bronze or better in 75 towns", AchievementCategory.TOWNS, metrics.bronzeTowns.toDouble(), 75.0, "towns", 400),
            AchievementProgress("town_silver_1", "Halfway Home", "Earn silver or better in 3 towns (50% explored each)", AchievementCategory.TOWNS, metrics.silverTowns.toDouble(), 3.0, "towns", 75),
            AchievementProgress("town_silver_5", "Silver Surveyor", "Earn silver or better in 15 towns", AchievementCategory.TOWNS, metrics.silverTowns.toDouble(), 15.0, "towns", 200),
            AchievementProgress("town_silver_25", "Silver Atlas", "Earn silver or better in 50 towns", AchievementCategory.TOWNS, metrics.silverTowns.toDouble(), 50.0, "towns", 600),
            AchievementProgress("town_gold_1", "Town Conqueror", "Fully explore 2 towns (100% each)", AchievementCategory.TOWNS, metrics.goldTowns.toDouble(), 2.0, "towns", 150),
            AchievementProgress("town_gold_5", "Golden Collection", "Fully explore 8 towns", AchievementCategory.TOWNS, metrics.goldTowns.toDouble(), 8.0, "towns", 400),
            AchievementProgress("town_gold_10", "Golden Atlas", "Fully explore 20 towns", AchievementCategory.TOWNS, metrics.goldTowns.toDouble(), 20.0, "towns", 750),
            AchievementProgress("town_gold_25", "Master of Towns", "Fully explore 50 towns", AchievementCategory.TOWNS, metrics.goldTowns.toDouble(), 50.0, "towns", 1250),
        ).asSequence().filter { item ->
            // Retire low-battery challenges for NEW players: don't encourage
            // running a GPS tracker until a phone is about to power off.
            // Preserve the completed cards and exact historic payouts.
            (item.id != "battery_5" && item.id != "battery_1") ||
                item.id in metrics.rewardedAchievements
        }.map { item ->
            // Road/place deduplication can reduce current totals. The durable
            // reward ledger preserves earned milestones across full restores.
            if (item.id in metrics.rewardedAchievements) {
                val progress = maxOf(item.progress, item.goal)
                val points = metrics.rewardedAchievementPoints[item.id] ?: item.rewardPoints
                if (progress == item.progress && points == item.rewardPoints) item
                else item.copy(progress = progress, rewardPoints = points)
            } else item
        }.toList()
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
        val highestRoads = persistentRoadMaximum(prefs, summary.roadsUnlockedCount)
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
        val highestRoads = persistentRoadMaximum(prefs, summary.roadsUnlockedCount)
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

    private fun persistentRoadMaximum(prefs: android.content.SharedPreferences, unlocked: Long): Long {
        val savedMax = prefs.getLong(KEY_MAX_ROADS, 0L)
        val highest = maxOf(unlocked, savedMax)
        if (highest > savedMax) prefs.edit().putLong(KEY_MAX_ROADS, highest).apply()
        return highest
    }

    private fun isCurrentSummary(context: Context, summary: DataSummary): Boolean =
        summary.historyGeneration < 0L ||
            ProgressionRepository(context).isHistoryGenerationCurrent(summary.historyGeneration)
}
