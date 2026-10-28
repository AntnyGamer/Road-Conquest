package com.roadconquest.app.data

import android.content.Context
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
                fun evaluate(week: Long): List<WeeklyChallenge> {
                    val weekEnd = week + 7 * 86_400_000L
                    // Reuse the last-driven/group indexes and inspect this week's
                    // candidates rather than regrouping every historical road.
                    // Keep the same independently indexed count predicates but
                    // retrieve both values in one cursor/query per evaluated week.
                    val (roads, towns) = db.rawQuery(
                        """SELECT
                          (SELECT COUNT(DISTINCT CASE WHEN r.road_group_id = '' THEN r.segment_id ELSE r.road_group_id END)
                            FROM roads r WHERE r.last_driven_at >= ? AND r.first_unlocked_at >= ?
                            AND r.first_unlocked_at < ? AND r.first_unlocked_at <= ?
                            AND r.weekly_eligible=1
                            AND r.road_group_id NOT LIKE 'x2|%'
                            AND (r.road_group_id = '' OR NOT EXISTS (SELECT 1 FROM roads old
                              WHERE old.road_group_id = r.road_group_id
                                AND (old.first_unlocked_at < ? OR old.weekly_eligible=0)))),
                          (SELECT COUNT(*) FROM visited_places v WHERE kind = 'TOWN'
                            AND first_visited_at >= ? AND first_visited_at < ? AND first_visited_at <= ?
                            AND v.weekly_eligible=1
                            AND EXISTS (SELECT 1 FROM progression_rewards r
                              WHERE r.reward_key = 'place:town:' || v.place_key))""",
                        arrayOf(week.toString(), week.toString(), weekEnd.toString(), now.toString(),
                            week.toString(), week.toString(), weekEnd.toString(), now.toString())
                    ).use { cursor ->
                        check(cursor.moveToFirst())
                        cursor.getLong(0) to cursor.getLong(1)
                    }
                    // Preserve every challenge ID and payout. Batch the ownership
                    // lookup: one indexed SQLite query per week instead of one per goal.
                    val challenges = listOf(
                        WeeklyChallenge("roads_5", "Unlock 5 new roads", roads, 5, 75, false),
                        WeeklyChallenge("roads", "Unlock 25 new roads", roads, 25, 250, false),
                        WeeklyChallenge("towns_1", "Explore 1 new town", towns, 1, 75, false),
                        WeeklyChallenge("towns", "Explore 3 new towns", towns, 3, 300, false)
                    )
                    val keys = Array(challenges.size) { "weekly:$week:${challenges[it].id}" }
                    val rewarded = HashSet<String>(challenges.size)
                    db.rawQuery(
                        "SELECT reward_key FROM progression_rewards WHERE reward_key IN (?,?,?,?)",
                        keys
                    ).use { cursor ->
                        while (cursor.moveToNext()) rewarded.add(cursor.getString(0))
                    }
                    // Insert every newly qualified reward in one atomic SQL
                    // statement. IGNORE still prevents a second payout per key.
                    val earned = challenges.indices.filter {
                        challenges[it].progress >= challenges[it].target && keys[it] !in rewarded
                    }
                    if (earned.isNotEmpty()) {
                        val values = ArrayList<Any>(earned.size * 3)
                        earned.forEach { index ->
                            values.add(keys[index])
                            values.add(challenges[index].reward)
                            values.add(now)
                        }
                        db.execSQL(
                            "INSERT OR IGNORE INTO progression_rewards(reward_key,points,awarded_at) VALUES " +
                                List(earned.size) { "(?,?,?)" }.joinToString(","),
                            values.toTypedArray()
                        )
                        // The write transaction owns these keys; all inserted
                        // values meet the schema and IGNORE retains uniqueness.
                        earned.forEach { rewarded.add(keys[it]) }
                    }
                    return challenges.mapIndexed { index, challenge ->
                        challenge.copy(rewarded = keys[index] in rewarded)
                    }
                }
                val challenges = evaluate(start)
                // One-week grace for delayed matching/geocoding after Sunday drives.
                // Do not mint rewards for the entire imported historical archive.
                evaluate(start - 7 * 86_400_000L)
                db.setTransactionSuccessful()
                ChallengeWeek(start, end, challenges)
            } finally { db.endTransaction() }
        }
    }
}
