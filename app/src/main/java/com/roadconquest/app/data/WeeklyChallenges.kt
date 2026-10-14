package com.roadconquest.app.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.TemporalAdjusters

data class WeeklyChallenge(val id: String, val title: String, val progress: Long, val target: Long,
    val reward: Long, val rewarded: Boolean)
data class ChallengeWeek(val startsAt: Long, val endsAt: Long, val challenges: List<WeeklyChallenge>)

/** Local goals reward once per UTC week; local history never becomes a verified score. */
object WeeklyChallenges {
    private data class CacheKey(val generation: Long, val week: Long, val roads: Long, val towns: Long)
    private var cachedKey: CacheKey? = null

    fun weekStart(now: Long): Long = Instant.ofEpochMilli(now).atZone(ZoneOffset.UTC).toLocalDate()
        .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    /** Invoked by normal progress refreshes; no timer, extra GPS, or periodic background work. */
    fun sync(context: Context, summary: DataSummary, progression: ProgressionSnapshot, now: Long = System.currentTimeMillis()): Boolean {
        val helper = AppDatabase.get(context)
        synchronized(helper.historyLock) {
            if (summary.historyGeneration != helper.historyGeneration) return false
            val key = CacheKey(helper.historyGeneration, weekStart(now), summary.roadsUnlockedCount, progression.towns)
            if (cachedKey == key) return false
            status(context, now)
            cachedKey = key
            return true
        }
    }

    fun status(context: Context, now: Long = System.currentTimeMillis()): ChallengeWeek {
        val helper = AppDatabase.get(context)
        return synchronized(helper.historyLock) {
            val start = weekStart(now)
            val end = start + 7 * 86_400_000L
            val db = helper.writableDatabase
            db.beginTransaction()
            try {
                // A new section of an OLD road does not turn it into a new weekly road.
                val roads = db.rawQuery(
                    """SELECT COUNT(*) FROM (
                      SELECT MIN(first_unlocked_at) AS first_seen FROM roads
                      WHERE road_group_id NOT LIKE 'x2|%'
                      GROUP BY CASE WHEN road_group_id = '' THEN segment_id ELSE road_group_id END
                      HAVING first_seen >= ? AND first_seen < ? AND first_seen <= ?
                    )""", arrayOf(start.toString(), end.toString(), now.toString())
                ).use { it.moveToFirst(); it.getLong(0) }
                val towns = db.rawQuery(
                    """SELECT COUNT(*) FROM visited_places v WHERE kind = 'TOWN'
                      AND first_visited_at >= ? AND first_visited_at < ? AND first_visited_at <= ?
                      AND EXISTS (SELECT 1 FROM progression_rewards r
                        WHERE r.reward_key = 'place:town:' || v.place_key)""",
                    arrayOf(start.toString(), end.toString(), now.toString())
                ).use { it.moveToFirst(); it.getLong(0) }
                val challenges = listOf(
                    WeeklyChallenge("roads", "Unlock 25 new roads", roads, 25, 250, false),
                    WeeklyChallenge("towns", "Explore 3 new towns", towns, 3, 300, false)
                ).map { challenge ->
                    val key = "weekly:$start:${challenge.id}"
                    if (challenge.progress >= challenge.target) {
                        db.insertWithOnConflict("progression_rewards", null, ContentValues().apply {
                            put("reward_key", key); put("points", challenge.reward); put("awarded_at", now)
                        }, SQLiteDatabase.CONFLICT_IGNORE)
                    }
                    challenge.copy(rewarded = db.rawQuery("SELECT 1 FROM progression_rewards WHERE reward_key = ?", arrayOf(key)).use { it.moveToFirst() })
                }
                db.setTransactionSuccessful()
                ChallengeWeek(start, end, challenges)
            } finally { db.endTransaction() }
        }
    }

}
