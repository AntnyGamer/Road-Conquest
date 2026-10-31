package com.roadconquest.app.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.location.Location
import com.roadconquest.app.map.PlaceGeometry
import com.roadconquest.app.map.PlaceOverlayCache
import com.roadconquest.app.map.UnitedStatesMunicipalBoundaries
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

enum class PlaceKind(val points: Long) {
    TOWN(35L),
    STATE(150L),
    COUNTRY(500L)
}

data class PlaceDiscovery(
    val kind: PlaceKind,
    val key: String,
    val displayName: String,
    val parentName: String = "",
    val countryName: String = "",
    val visitedAt: Long,
    val latitude: Double,
    val longitude: Double
)

data class PendingPlaceCandidate(
    val cellX: Long,
    val cellY: Long,
    val latitude: Double,
    val longitude: Double,
    val visitedAt: Long,
    val attempts: Int,
    val weeklyEligible: Boolean = true
)

data class StartingLocation(
    val latitude: Double,
    val longitude: Double,
    val recordedAt: Long
)

data class PlaceVisitTimes(val firstVisitedAt: Long, val lastVisitedAt: Long)

data class ProgressionSnapshot(
    val balance: Long,
    val lifetimeEarned: Long,
    val pointsSpent: Long,
    val rewardedRoads: Long,
    val towns: Long,
    val states: Long,
    val countries: Long,
    val adsWatched: Long,
    val lowestBatteryPercent: Int,
    val purchasedItems: Set<String>,
    val rewardedAchievements: Set<String>,
    /** Unique one-mile fog tiles stored in explored_grid; separate from leaderboard cells. */
    val tilesUnlocked: Long = 0L,
    /** Actual previously awarded amounts; historical milestones may have different pricing. */
    val rewardedAchievementPoints: Map<String, Long> = emptyMap(),
    val bronzeTowns: Long = 0L,
    val silverTowns: Long = 0L,
    val goldTowns: Long = 0L,
    val exploredTowns: Long = towns,
    val exploredStates: Long = states,
    val exploredCountries: Long = countries
)

enum class PurchaseResult { PURCHASED, OWNED, INSUFFICIENT_POINTS }

internal data class TownCompletionUpdate(val tier: Int, val changed: Boolean)

class ProgressionRepository(context: Context) {
    private val appContext = context.applicationContext
    private val dbHelper = AppDatabase.get(appContext)
    private val historyGeneration = synchronized(dbHelper.historyLock) { dbHelper.historyGeneration }
    // Only accessed under dbHelper.historyLock. Reuse the one-element result
    // through bounded history backfills instead of allocating per duplicate fix.
    private val candidateDistanceScratch = FloatArray(1)

    fun isHistoryGenerationCurrent(expected: Long): Boolean =
        synchronized(dbHelper.historyLock) {
            historyGeneration == dbHelper.historyGeneration && expected == dbHelper.historyGeneration
        }

    fun syncRoadRewards(
        roadsUnlocked: Long,
        expectedHistoryGeneration: Long? = null
    ): Long = synchronized(dbHelper.historyLock) {
        val db = dbHelper.writableDatabase
        if (!isCurrentHistory(expectedHistoryGeneration)) return@synchronized counter(db, COUNTER_REWARDED_ROADS)
        val target = roadsUnlocked.coerceAtLeast(0L)
        db.beginTransaction()
        try {
            val previous = counter(db, COUNTER_REWARDED_ROADS)
            if (target > previous) {
                val delta = target - previous
                awardOnce(db, "roads:$previous-$target", delta * POINTS_PER_ROAD)
                putCounter(db, COUNTER_REWARDED_ROADS, target)
            }
            db.setTransactionSuccessful()
            target.coerceAtLeast(previous)
        } finally {
            db.endTransaction()
        }
    }

    fun awardAchievement(
        id: String,
        points: Long,
        expectedHistoryGeneration: Long? = null
    ): Boolean {
        require(points > 0L)
        return synchronized(dbHelper.historyLock) {
            if (!isCurrentHistory(expectedHistoryGeneration)) return@synchronized false
            val db = dbHelper.writableDatabase
            db.beginTransaction()
            try {
                val inserted = awardOnce(db, "achievement:$id", points)
                db.setTransactionSuccessful()
                inserted
            } finally {
                db.endTransaction()
            }
        }
    }

    fun recordPlace(discovery: PlaceDiscovery): Boolean = synchronized(dbHelper.historyLock) {
        if (!isCurrentHistory()) return@synchronized false
        val db = dbHelper.writableDatabase
        db.beginTransaction()
        try {
            val canonicalVisit = alignTownWithExistingPlace(db, discovery)
            val inserted = insertVisitedPlace(db, canonicalVisit)
            if (inserted) {
                awardOnce(
                    db,
                    "place:${canonicalVisit.kind.name.lowercase()}:${canonicalVisit.key}",
                    discovery.kind.points
                )
            }
            db.setTransactionSuccessful()
            inserted
        } finally {
            db.endTransaction()
        }
    }

    private fun validCandidateFix(location: Location): Boolean =
        !location.isMock && location.latitude.isFinite() && location.longitude.isFinite() &&
            location.latitude in -85.0..85.0 && location.longitude in -180.0..180.0

    /**
     * Save a sparse reverse-geocoding candidate locally. Background tracking never has to make
     * a geocoder/network request; candidates are resolved later while the app is foregrounded.
     */
    fun recordPlaceCandidate(location: Location): Boolean = synchronized(dbHelper.historyLock) {
        if (!isCurrentHistory()) return@synchronized false
        if (!validCandidateFix(location)) return@synchronized false
        val db = dbHelper.writableDatabase
        // Keep discoveries observed while the exact zero-point baseline is still resolving.
        // pendingPlaceCandidates() gates them behind the baseline, so they cannot earn points
        // early, but a short visit is not lost merely because reverse geocoding took minutes.
        insertPlaceCandidate(db, location.latitude, location.longitude,
            location.time.takeIf { it > 0L } ?: System.currentTimeMillis())
    }

    /**
     * Revisit persisted GPS fixes, including compressed-out straight-line samples,
     * from releases where coarse town sampling could miss a municipality. Process a bounded page
     * per foreground discovery pass, newest first, with a durable cursor: no data
     * migration, no guesses from a road's drawn geometry, and no duplicate rewards.
     */
    fun backfillPlaceCandidates(limit: Int = 3_000): Int = synchronized(dbHelper.historyLock) {
        require(limit in 1..10_000)
        if (!isCurrentHistory()) return@synchronized 0
        val db = dbHelper.writableDatabase
        // Do not recover places from a previous profile or an unresolved baseline.
        val baselineTime = startingLocation()?.recordedAt ?: return@synchronized 0
        if (!db.rawQuery("SELECT 1 FROM visited_places LIMIT 1", null).use { it.moveToFirst() })
            return@synchronized 0

        var inserted = 0
        var remaining = limit
        db.beginTransaction()
        try {
            // Older builds omitted some accepted straight-line fixes from track_points.
            // Recover those genuine saved positions too, using independent durable
            // cursors and the same total per-pass bound. Neither table implies a visit
            // until the normal municipality verifier checks the original coordinate.
            for ((table, counterKey) in listOf(
                "track_points" to COUNTER_PLACE_BACKFILL_NEXT_ID,
                "skipped_driving_fixes" to COUNTER_SKIPPED_PLACE_BACKFILL_NEXT_ID
            )) {
                if (remaining == 0) break
                val savedNextId = counterOrNull(db, counterKey)
                val nextId = savedNextId
                    ?: db.rawQuery("SELECT COALESCE(MAX(id), 0) + 1 FROM $table", null)
                        .use { it.moveToFirst(); it.getLong(0) }
                if (nextId <= 1L) {
                    // Don't rewrite a completed cursor on every remaining batch
                    // of the other GPS source.
                    if (savedNextId != 1L) putCounter(db, counterKey, 1L)
                    continue
                }
                var next = nextId
                var examined = 0
                db.rawQuery(
                    """SELECT id, latitude, longitude, timestamp_ms, weekly_eligible FROM $table
                       WHERE id < ? AND timestamp_ms >= ? AND accuracy_m BETWEEN 0.01 AND 25
                       ORDER BY id DESC LIMIT ?""",
                    arrayOf(nextId.toString(), baselineTime.toString(), remaining.toString())
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        examined++
                        next = cursor.getLong(0)
                        if (insertPlaceCandidate(db, cursor.getDouble(1), cursor.getDouble(2),
                                cursor.getLong(3), cursor.getInt(4) == 1)) inserted++
                    }
                }
                putCounter(db, counterKey, if (examined == 0) 1L else next)
                remaining -= examined
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        inserted
    }

    /** Continue scanning old GPS even if the last bounded page added no new cells. */
    fun hasUnscannedPlaceHistory(): Boolean = synchronized(dbHelper.historyLock) {
        if (!isCurrentHistory()) return@synchronized false
        val db = dbHelper.readableDatabase
        // Once both sources finish, skip the extra baseline and visited-place
        // queries on every foreground discovery pass.
        val completed = db.rawQuery(
            """SELECT COUNT(*) FROM progression_counters
               WHERE counter_key IN (?, ?) AND value = 1""",
            arrayOf(COUNTER_PLACE_BACKFILL_NEXT_ID, COUNTER_SKIPPED_PLACE_BACKFILL_NEXT_ID)
        ).use { it.moveToFirst() && it.getInt(0) == 2 }
        !completed && startingLocation() != null &&
            db.rawQuery("SELECT 1 FROM visited_places LIMIT 1", null).use { it.moveToFirst() }
    }

    /** V2 keys cannot collide with old 2 km keys while pending old visits resolve. */
    private fun insertPlaceCandidate(db: SQLiteDatabase, latitude: Double, longitude: Double, time: Long,
                                     weeklyEligible: Boolean = true): Boolean {
        if (!latitude.isFinite() || !longitude.isFinite() ||
            latitude !in -85.0..85.0 || longitude !in -180.0..180.0) return false
        val radius = 6_378_137.0
        val normalized = ((longitude + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
        val x = floor(radius * Math.toRadians(normalized) / PLACE_CANDIDATE_CELL_M).toLong() +
            PLACE_CANDIDATE_VERSION_OFFSET
        val y = floor(radius * ln(tan(PI / 4 + Math.toRadians(latitude) / 2)) /
            PLACE_CANDIDATE_CELL_M).toLong()
        val values = ContentValues().apply {
            put("cell_x", x)
            put("cell_y", y)
            // Resolve the ACTUAL driven fix, not a grid center across a town border.
            put("latitude", latitude)
            put("longitude", normalized)
            put("first_seen_at", time)
            put("attempts", 0)
            put("next_attempt_ms", 0)
            put("weekly_eligible", if (weeklyEligible) 1 else 0)
        }
        if (db.insertWithOnConflict("place_candidates", null, values, SQLiteDatabase.CONFLICT_IGNORE) != -1L) {
            return true
        }
        // Two different municipalities can lie in the same 150 m cell. Preserve a
        // second sufficiently separated actual fix without changing the coarse
        // sampling grid, database schema or existing saved candidates. Limit
        // unresolved candidates to two per cell to bound network/geocoder work.
        val first = db.query(
            "place_candidates", arrayOf("latitude", "longitude"),
            "cell_x = ? AND cell_y = ?",
            arrayOf(x.toString(), y.toString()), null, null, null, "1"
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getDouble(0) to cursor.getDouble(1) else null
        } ?: return false
        Location.distanceBetween(
            first.first, first.second, latitude, normalized, candidateDistanceScratch
        )
        if (candidateDistanceScratch[0] < SECONDARY_CANDIDATE_MIN_DISTANCE_M) return false
        values.put("cell_x", x + SECONDARY_CANDIDATE_OFFSET)
        return db.insertWithOnConflict(
            "place_candidates", null, values, SQLiteDatabase.CONFLICT_IGNORE
        ) != -1L
    }

    /**
     * Capture the exact first good live fix for a fresh/reset progression history. Ordinary
     * sparse place candidates never substitute for this zero-point starting location.
     */
    fun recordBaselineCandidate(location: Location): Boolean = synchronized(dbHelper.historyLock) {
        if (!isCurrentHistory()) return@synchronized false
        if (!validCandidateFix(location)) return@synchronized false
        val db = dbHelper.writableDatabase
        if (db.rawQuery("SELECT 1 FROM visited_places LIMIT 1", null).use { it.moveToFirst() }) {
            return@synchronized false
        }
        // The first exact live fix is authoritative even across service/activity restarts.
        // Never replace an unresolved sentinel or discard visits queued behind it.
        if (db.rawQuery(
                "SELECT 1 FROM place_candidates WHERE cell_x = ? AND cell_y = ? LIMIT 1",
                arrayOf(BASELINE_CANDIDATE_X.toString(), BASELINE_CANDIDATE_Y.toString())
            ).use { it.moveToFirst() }
        ) return@synchronized false
        // Anything queued before the first live/current fix predates the new profile.
        db.delete(
            "place_candidates",
            "NOT (cell_x = ? AND cell_y = ?)",
            arrayOf(BASELINE_CANDIDATE_X.toString(), BASELINE_CANDIDATE_Y.toString())
        )
        val values = ContentValues().apply {
            put("cell_x", BASELINE_CANDIDATE_X)
            put("cell_y", BASELINE_CANDIDATE_Y)
            put("latitude", location.latitude)
            put("longitude", location.longitude)
            put("first_seen_at", location.time.takeIf { it > 0L } ?: System.currentTimeMillis())
            put("attempts", 0)
            put("next_attempt_ms", 0)
        }
        db.insertWithOnConflict(
            "place_candidates",
            null,
            values,
            SQLiteDatabase.CONFLICT_IGNORE
        ) != -1L
    }

    private fun candidateFromCursor(cursor: android.database.Cursor) = PendingPlaceCandidate(
        cursor.getLong(0), cursor.getLong(1), cursor.getDouble(2),
        cursor.getDouble(3), cursor.getLong(4), cursor.getInt(5),
        cursor.getInt(cursor.getColumnIndexOrThrow("weekly_eligible")) == 1
    )

    fun pendingPlaceCandidates(
        limit: Int = 8,
        nowMillis: Long = System.currentTimeMillis()
    ): List<PendingPlaceCandidate> {
        require(limit in 1..50)
        val db = dbHelper.readableDatabase

        // Before the first live baseline exists, stale/sparse candidates cannot become the
        // starting place. Once the sentinel exists, it is an ordering barrier: ordinary
        // candidates can be recorded but remain hidden until zero-point resolution completes.
        val baseline = db.query(
            "place_candidates",
            arrayOf(
                "cell_x", "cell_y", "latitude", "longitude",
                "first_seen_at", "attempts", "next_attempt_ms", "weekly_eligible"
            ),
            "cell_x = ? AND cell_y = ?",
            arrayOf(BASELINE_CANDIDATE_X.toString(), BASELINE_CANDIDATE_Y.toString()),
            null,
            null,
            null,
            "1"
        ).use { cursor ->
            if (!cursor.moveToFirst()) null else candidateFromCursor(cursor) to cursor.getLong(6)
        }
        if (baseline != null) {
            return baseline.takeIf { it.second <= nowMillis }?.let { listOf(it.first) }.orEmpty()
        }
        // When a zero-point baseline is still pending, its row alone decides
        // eligibility. Avoid querying visited places until the baseline is gone.
        if (!db.rawQuery("SELECT 1 FROM visited_places LIMIT 1", null).use { it.moveToFirst() })
            return emptyList()

        val result = ArrayList<PendingPlaceCandidate>(limit)
        db.query(
            "place_candidates",
            arrayOf("cell_x", "cell_y", "latitude", "longitude", "first_seen_at", "attempts", "weekly_eligible"),
            "next_attempt_ms <= ? AND NOT (cell_x = ? AND cell_y = ?)",
            arrayOf(
                nowMillis.toString(),
                BASELINE_CANDIDATE_X.toString(),
                BASELINE_CANDIDATE_Y.toString()
            ),
            null,
            null,
            "attempts ASC, next_attempt_ms ASC, first_seen_at DESC, cell_x ASC, cell_y ASC",
            limit.toString()
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += candidateFromCursor(cursor)
            }
        }
        return result
    }

    /** Earliest retry, including an unresolved zero-point baseline that blocks other places. */
    fun nextPlaceCandidateAttemptMillis(): Long? = synchronized(dbHelper.historyLock) {
        if (!isCurrentHistory()) return@synchronized null
        // A baseline candidate blocks ordinary places even if its retry is in
        // the future. If none exists, ordinary candidates are eligible only
        // after a verified starting place has been recorded. One statement
        // replaces the separate baseline lookup, existence check, and MIN.
        dbHelper.readableDatabase.rawQuery(
            """SELECT COALESCE(
                 (SELECT next_attempt_ms FROM place_candidates
                   WHERE cell_x = ? AND cell_y = ? LIMIT 1),
                 (SELECT MIN(next_attempt_ms) FROM place_candidates
                   WHERE NOT (cell_x = ? AND cell_y = ?)
                     AND EXISTS (SELECT 1 FROM visited_places LIMIT 1))
               )""",
            arrayOf(
                BASELINE_CANDIDATE_X.toString(), BASELINE_CANDIDATE_Y.toString(),
                BASELINE_CANDIDATE_X.toString(), BASELINE_CANDIDATE_Y.toString()
            )
        ).use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null }
    }

    fun resolveCandidate(candidate: PendingPlaceCandidate, discoveries: List<PlaceDiscovery>): Int =
        synchronized(dbHelper.historyLock) {
            if (!isCurrentHistory()) return@synchronized 0
            val db = dbHelper.writableDatabase
            val baseline = candidate.cellX == BASELINE_CANDIDATE_X &&
                candidate.cellY == BASELINE_CANDIDATE_Y
            db.beginTransaction()
            try {
                var added = 0
                for (unverifiedAlias in discoveries) {
                    val discovery = alignTownWithExistingPlace(db, unverifiedAlias)
                    if (baseline) {
                        // The exact first live fix after install/reset defines the zero-point
                        // town/state/country. It is visible to overlays but earns no points.
                        insertVisitedPlace(db, discovery, candidate.weeklyEligible)
                        putCounter(db, baselineKey(discovery), 1L)
                        continue
                    }
                    val inserted = insertVisitedPlace(db, discovery, candidate.weeklyEligible)
                    if (inserted) {
                        awardOnce(
                            db,
                            "place:${discovery.kind.name.lowercase()}:${discovery.key}",
                            discovery.kind.points
                        )
                        added++
                    }
                }
                val completeBeforeRetry = baseline && baselineComplete(db)
                if (baseline && !completeBeforeRetry) {
                    // Do not retire the only durable starting fix when geocoding has
                    // produced no places at all. Without an initial visited place,
                    // ordinary candidates remain gated forever and the next GPS fix
                    // can incorrectly become a new zero-point baseline.
                    val hasResolvedBaselinePlace = db.rawQuery(
                        "SELECT 1 FROM visited_places LIMIT 1", null
                    ).use { it.moveToFirst() }
                    if (candidate.attempts + 1 >= BASELINE_PARTIAL_RESOLUTION_LIMIT &&
                        hasResolvedBaselinePlace
                    ) {
                        markMissingBaselineKinds(db)
                    } else {
                        scheduleCandidateRetry(db, candidate, System.currentTimeMillis())
                    }
                }
                // A complete baseline has no intervening writes to invalidate the
                // check. Incomplete paths still recheck after retry/marker writes.
                if (!baseline || completeBeforeRetry || baselineComplete(db)) {
                    db.delete(
                        "place_candidates",
                        "cell_x = ? AND cell_y = ?",
                        arrayOf(candidate.cellX.toString(), candidate.cellY.toString())
                    )
                }
                db.setTransactionSuccessful()
                added
            } finally {
                db.endTransaction()
            }
        }

    /** A partially resolved zero-point baseline can lack its municipality. */
    fun hasMissingStartingTown(): Boolean = synchronized(dbHelper.historyLock) {
        isCurrentHistory() &&
            counterOrNull(dbHelper.readableDatabase, baselineMissingKey(PlaceKind.TOWN)) == 1L
    }

    /**
     * Used only after a verified municipal polygon contains the ORIGINAL start fix.
     * Reconcile a late town identification with the zero-point baseline before
     * the ordinary candidate is processed; never award points for the start town.
     */
    fun recordVerifiedStartingTown(discovery: PlaceDiscovery, start: StartingLocation): Boolean =
        synchronized(dbHelper.historyLock) {
            if (!isCurrentHistory() || discovery.kind != PlaceKind.TOWN ||
                counterOrNull(dbHelper.readableDatabase, baselineMissingKey(PlaceKind.TOWN)) != 1L ||
                startingLocation() != start
            ) return@synchronized false
            val db = dbHelper.writableDatabase
            db.beginTransaction()
            try {
                val baselineDiscovery = alignTownWithExistingPlace(db, discovery.copy(
                    visitedAt = start.recordedAt,
                    latitude = start.latitude,
                    longitude = start.longitude
                ))
                val inserted = insertVisitedPlace(db, baselineDiscovery)
                if (inserted) {
                    putCounter(db, baselineKey(baselineDiscovery), 1L)
                    db.delete("progression_counters", "counter_key = ?",
                        arrayOf(baselineMissingKey(PlaceKind.TOWN)))
                }
                db.setTransactionSuccessful()
                inserted
            } finally {
                db.endTransaction()
            }
        }

    fun deferCandidate(candidate: PendingPlaceCandidate, nowMillis: Long = System.currentTimeMillis()) =
        synchronized(dbHelper.historyLock) {
            if (!isCurrentHistory()) return@synchronized
            scheduleCandidateRetry(dbHelper.writableDatabase, candidate, nowMillis)
        }

    fun recordBatteryPercent(percent: Int): Boolean = synchronized(dbHelper.historyLock) {
        if (!isCurrentHistory()) return@synchronized false
        if (percent !in 0..100) return@synchronized false
        val db = dbHelper.writableDatabase
        val previous = counterOrNull(db, COUNTER_LOWEST_BATTERY) ?: 101L
        if (percent >= previous) return@synchronized false
        putCounter(db, COUNTER_LOWEST_BATTERY, percent.toLong())
        true
    }

    /** Credit points and the completed-view counter together, once per SDK reward receipt. */
    fun recordCompletedAd(receiptId: String, points: Long): Boolean = synchronized(dbHelper.historyLock) {
        require(receiptId.isNotBlank() && points > 0L)
        if (!isCurrentHistory()) return@synchronized false
        val db = dbHelper.writableDatabase
        db.beginTransaction()
        try {
            val inserted = awardOnce(db, "ad:$receiptId", points)
            if (inserted) putCounter(db, COUNTER_ADS_WATCHED, counter(db, COUNTER_ADS_WATCHED) + 1L)
            db.setTransactionSuccessful()
            inserted
        } finally {
            db.endTransaction()
        }
    }

    fun purchase(itemId: String, cost: Long): PurchaseResult {
        require(itemId.isNotBlank() && cost >= 0L)
        return synchronized(dbHelper.historyLock) {
            check(isCurrentHistory()) { "Progress reset during purchase" }
            val db = dbHelper.writableDatabase
            db.beginTransaction()
            try {
                val owned = db.rawQuery(
                    "SELECT 1 FROM progression_purchases WHERE item_id = ? LIMIT 1",
                    arrayOf(itemId)
                ).use { it.moveToFirst() }
                if (owned) {
                    db.setTransactionSuccessful()
                    return@synchronized PurchaseResult.OWNED
                }
                if (currentBalance(db) < cost) {
                    db.setTransactionSuccessful()
                    return@synchronized PurchaseResult.INSUFFICIENT_POINTS
                }
                db.insertOrThrow(
                    "progression_purchases",
                    null,
                    ContentValues().apply {
                        put("item_id", itemId)
                        put("points_spent", cost)
                        put("purchased_at", System.currentTimeMillis())
                    }
                )
                db.setTransactionSuccessful()
                PurchaseResult.PURCHASED
            } finally {
                db.endTransaction()
            }
        }
    }

    fun isPurchased(itemId: String): Boolean = dbHelper.readableDatabase.rawQuery(
        "SELECT 1 FROM progression_purchases WHERE item_id = ? LIMIT 1",
        arrayOf(itemId)
    ).use { it.moveToFirst() }

    /** Durable earned town tiers use the existing backed-up counter ledger. */
    internal fun recordTownCompletion(key: String, tier: Int, expectedGeneration: Long): TownCompletionUpdate =
        synchronized(dbHelper.historyLock) {
            if (!isCurrentHistory(expectedGeneration)) return@synchronized TownCompletionUpdate(0, false)
            val db = dbHelper.writableDatabase
            val counterKey = "town_completion_v1:$key"
            val previous = counter(db, counterKey).toInt().coerceIn(0, 3)
            val earned = maxOf(previous, tier.coerceIn(0, 3))
            if (earned > previous) putCounter(db, counterKey, earned.toLong())
            TownCompletionUpdate(earned, earned > previous)
        }

    fun snapshot(): ProgressionSnapshot = synchronized(dbHelper.historyLock) {
        val db = dbHelper.readableDatabase
        val earned = db.rawQuery("SELECT COALESCE(SUM(points), 0) FROM progression_rewards", null)
            .use { check(it.moveToFirst()); it.getLong(0) }

        val rewardedAchievements = linkedSetOf<String>()
        val rewardedAchievementPoints = linkedMapOf<String, Long>()
        db.query(
            "progression_rewards",
            arrayOf("reward_key", "points"),
            "reward_key LIKE ?",
            arrayOf("achievement:%"),
            null, null, null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0).removePrefix("achievement:")
                rewardedAchievements += id
                rewardedAchievementPoints[id] = cursor.getLong(1)
            }
        }

        val counts = mutableMapOf<PlaceKind, Long>()
        val explored = mutableMapOf<PlaceKind, Long>()
        // Include starting places in exploration, not points or achievements.
        db.rawQuery(
            """SELECT v.kind, COUNT(*), COUNT(r.reward_key)
               FROM visited_places v LEFT JOIN progression_rewards r
                 ON r.reward_key = 'place:' || lower(v.kind) || ':' || v.place_key
               GROUP BY v.kind""",
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                PlaceKind.entries.firstOrNull { it.name == cursor.getString(0) }?.let {
                    explored[it] = cursor.getLong(1)
                    counts[it] = cursor.getLong(2)
                }
            }
        }

        // Purchases are a small, bounded set. Read their IDs and prices in the same cursor
        // instead of scanning the table once for SUM() and again for the owned-item list.
        val purchases = linkedSetOf<String>()
        var spent = 0L
        db.query(
            "progression_purchases",
            arrayOf("item_id", "points_spent"),
            null, null, null, null,
            "purchased_at ASC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                purchases += cursor.getString(0)
                spent += cursor.getLong(1)
            }
        }

        // Fetch the three progression counters with one tiny indexed query instead of opening
        // three separate cursors every time the map/UI refreshes its progression snapshot.
        val counters = HashMap<String, Long>(3)
        db.query(
            "progression_counters",
            arrayOf("counter_key", "value"),
            "counter_key IN (?,?,?)",
            arrayOf(COUNTER_REWARDED_ROADS, COUNTER_ADS_WATCHED, COUNTER_LOWEST_BATTERY),
            null, null, null
        ).use { cursor ->
            while (cursor.moveToNext()) counters[cursor.getString(0)] = cursor.getLong(1)
        }

        // A single indexed SQLite count reads persisted mile tiles, not 50 m fog samples.
        // Counting here avoids a second location listener or a separate reward counter.
        val tilesUnlocked = db.rawQuery("SELECT COUNT(*) FROM explored_grid", null)
            .use { check(it.moveToFirst()); it.getLong(0) }

        val medals = db.rawQuery("""SELECT
            COALESCE(SUM(CASE WHEN value >= 1 THEN 1 ELSE 0 END),0),
            COALESCE(SUM(CASE WHEN value >= 2 THEN 1 ELSE 0 END),0),
            COALESCE(SUM(CASE WHEN value >= 3 THEN 1 ELSE 0 END),0)
            FROM progression_counters WHERE counter_key GLOB 'town_completion_v1:*'""", null)
            .use { check(it.moveToFirst()); longArrayOf(it.getLong(0), it.getLong(1), it.getLong(2)) }

        ProgressionSnapshot(
            bronzeTowns = medals[0], silverTowns = medals[1], goldTowns = medals[2],
            tilesUnlocked = tilesUnlocked,
            balance = (earned - spent).coerceAtLeast(0L),
            lifetimeEarned = earned,
            pointsSpent = spent,
            rewardedRoads = counters[COUNTER_REWARDED_ROADS] ?: 0L,
            towns = counts[PlaceKind.TOWN] ?: 0L,
            states = counts[PlaceKind.STATE] ?: 0L,
            countries = counts[PlaceKind.COUNTRY] ?: 0L,
            exploredTowns = explored[PlaceKind.TOWN] ?: 0L,
            exploredStates = explored[PlaceKind.STATE] ?: 0L,
            exploredCountries = explored[PlaceKind.COUNTRY] ?: 0L,
            adsWatched = counters[COUNTER_ADS_WATCHED] ?: 0L,
            lowestBatteryPercent = (counters[COUNTER_LOWEST_BATTERY] ?: 101L)
                .toInt().coerceIn(0, 101),
            purchasedItems = purchases,
            rewardedAchievements = rewardedAchievements,
            rewardedAchievementPoints = rewardedAchievementPoints
        )
    }

    /**
     * Returns the exact zero-point location captured after install/reset. While reverse
     * geocoding is still pending, the sentinel candidate is authoritative. Once resolved,
     * baseline-marked visited places retain the same original coordinates.
     */
    fun startingLocation(): StartingLocation? = synchronized(dbHelper.historyLock) {
        if (!isCurrentHistory()) return@synchronized null
        val db = dbHelper.readableDatabase
        db.query(
            "place_candidates",
            arrayOf("latitude", "longitude", "first_seen_at"),
            "cell_x = ? AND cell_y = ?",
            arrayOf(BASELINE_CANDIDATE_X.toString(), BASELINE_CANDIDATE_Y.toString()),
            null,
            null,
            null,
            "1"
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                return@synchronized StartingLocation(
                    cursor.getDouble(0),
                    cursor.getDouble(1),
                    cursor.getLong(2)
                )
            }
        }

        db.rawQuery(
            """SELECT v.latitude, v.longitude, v.first_visited_at
               FROM visited_places v
               WHERE EXISTS (
                   SELECT 1 FROM progression_counters c
                   WHERE c.counter_key = 'baseline:' || lower(v.kind) || ':' || v.place_key
                     AND c.value = 1
               )
               ORDER BY CASE v.kind WHEN 'TOWN' THEN 0 WHEN 'STATE' THEN 1 ELSE 2 END,
                        v.first_visited_at ASC
               LIMIT 1""",
            null
        ).use { cursor ->
            if (!cursor.moveToFirst()) null else StartingLocation(
                cursor.getDouble(0),
                cursor.getDouble(1),
                cursor.getLong(2)
            )
        }
    }

    fun visitedPlaces(kind: PlaceKind): List<PlaceDiscovery> {
        val result = ArrayList<PlaceDiscovery>()
        dbHelper.readableDatabase.query(
            "visited_places",
            arrayOf(
                "kind", "place_key", "display_name", "parent_name", "country_name",
                "first_visited_at", "latitude", "longitude"
            ),
            "kind = ?",
            arrayOf(kind.name),
            null,
            null,
            "first_visited_at ASC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += PlaceDiscovery(
                    kind = kind,
                    key = cursor.getString(1),
                    displayName = cursor.getString(2),
                    parentName = cursor.getString(3),
                    countryName = cursor.getString(4),
                    visitedAt = cursor.getLong(5),
                    latitude = cursor.getDouble(6),
                    longitude = cursor.getDouble(7)
                )
            }
        }
        return result
    }

    /**
     * First visit is stored with the place; latest resolved GPS visit is recorded
     * separately in existing counters to avoid a schema change or losing old data.
     * Legacy discoveries display the first timestamp for both values until revisited.
     */
    fun placeVisitTimes(kind: PlaceKind, key: String): PlaceVisitTimes? =
        synchronized(dbHelper.historyLock) {
            if (!isCurrentHistory()) return@synchronized null
            val db = dbHelper.readableDatabase
            db.query(
                "visited_places", arrayOf("first_visited_at"),
                "kind = ? AND place_key = ?", arrayOf(kind.name, key),
                null, null, null, "1"
            ).use { rows ->
                if (!rows.moveToFirst()) return@synchronized null
                val first = rows.getLong(0)
                val last = counterOrNull(db, placeLastVisitKey(kind, key)) ?: first
                PlaceVisitTimes(first, maxOf(first, last))
            }
        }

    fun clearProgression() = synchronized(dbHelper.historyLock) {
        val db = dbHelper.writableDatabase
        db.beginTransaction()
        try {
            for (table in listOf(
                "place_candidates",
                "visited_places",
                "progression_rewards",
                "progression_purchases",
                "progression_counters"
            )) db.delete(table, null, null)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Android's geocoder can use "Pine Hill" and "Pine Hill Borough"
     * for the same verified municipality. Reuse its stored key so that
     * changing place-name variants cannot award it twice.
     * Explicitly different municipal types are not treated as aliases.
     */
    private fun alignTownWithExistingPlace(db: SQLiteDatabase, discovery: PlaceDiscovery): PlaceDiscovery {
        // Preserve country rewards from geocoders that supplied only a country
        // name ("united states") before they started supplying ISO code "us".
        if (discovery.kind == PlaceKind.COUNTRY) {
            if (!UnitedStatesMunicipalBoundaries.isUnitedStatesCountry(discovery.key)) return discovery
            db.query("visited_places", arrayOf("place_key"),
                "kind = ? AND lower(place_key) IN ('us', 'usa', 'united states', 'united states of america')",
                arrayOf(PlaceKind.COUNTRY.name), null, null, null
            ).use { rows ->
                if (rows.moveToFirst()) return discovery.copy(key = rows.getString(0))
            }
            return discovery
        }
        // Older providers sometimes called the same state "NJ" and "New Jersey".
        // Keep the previously rewarded key instead of creating a duplicate state.
        if (discovery.kind == PlaceKind.STATE) {
            val stateFips = UnitedStatesMunicipalBoundaries.stateCode(
                discovery.countryName, discovery.displayName
            ) ?: return discovery
            db.query("visited_places", arrayOf("place_key", "display_name"),
                "kind = ? AND lower(country_name) IN ('us', 'usa', 'united states', 'united states of america')",
                arrayOf(PlaceKind.STATE.name),
                null, null, null
            ).use { existing ->
                while (existing.moveToNext()) {
                    if (UnitedStatesMunicipalBoundaries.stateCode(
                            discovery.countryName, existing.getString(1)
                        ) == stateFips
                    ) return discovery.copy(key = existing.getString(0))
                }
            }
            return discovery
        }
        if (discovery.kind != PlaceKind.TOWN) return discovery
        val proposed = municipalityIdentity(discovery.displayName)
        val officialNewKey = discovery.key.substringAfterLast('|').startsWith("census-")
        // New official IDs must never be reduced to the same name+nearby-fix
        // heuristic. For an old name-key visit, verify that its saved fix is
        // physically inside the newly fetched official polygon before reusing it.
        val officialPolygon by lazy(LazyThreadSafetyMode.NONE) {
            if (officialNewKey) {
                PlaceOverlayCache.readOfficialUs(appContext, discovery).data?.let {
                    runCatching { JSONObject(it.geometryJson) }.getOrNull()
                }
            } else null
        }
        val postal = UnitedStatesMunicipalBoundaries.postalCode(
            discovery.countryName, discovery.parentName
        )
        val parentName = UnitedStatesMunicipalBoundaries.normalizedStateName(
            discovery.countryName, discovery.parentName
        )
        // A legacy geocoder may also have labeled the country "USA" or "US".
        // Compare real U.S. municipality identities across those spelling changes.
        val us = UnitedStatesMunicipalBoundaries.isUnitedStatesCountry(discovery.countryName)
        val countryClause = if (us) {
            "lower(country_name) IN ('us', 'usa', 'united states', 'united states of america')"
        } else "country_name = ?"
        val townSelection = if (postal == null) "kind = ? AND parent_name = ? AND $countryClause"
            else "kind = ? AND (parent_name = ? COLLATE NOCASE OR parent_name = ? COLLATE NOCASE) AND $countryClause"
        val townArgs = buildList {
            add(PlaceKind.TOWN.name)
            add(if (postal == null) discovery.parentName else parentName)
            if (postal != null) add(postal)
            if (!us) add(discovery.countryName)
        }.toTypedArray()
        db.query(
            "visited_places",
            arrayOf("place_key", "display_name", "latitude", "longitude"),
            townSelection, townArgs, null, null, null
        ).use { rows ->
            val distances = FloatArray(1)
            while (rows.moveToNext()) {
                val existingKey = rows.getString(0)
                if (existingKey == discovery.key) return discovery
                val existingOfficial = existingKey.substringAfterLast('|').startsWith("census-")
                if (officialNewKey && existingOfficial) {
                    // An older geocoder may have persisted "us|nj|census-4-GEOID"
                    // where the current provider uses "us|new jersey|census-4-GEOID".
                    // The exact Census layer + GEOID is the same real municipality.
                    if (existingKey.substringAfterLast('|') == discovery.key.substringAfterLast('|')) {
                        return discovery.copy(key = existingKey)
                    }
                    continue
                }
                val existing = municipalityIdentity(rows.getString(1))
                if (existing.first != proposed.first ||
                    (existing.second.isNotEmpty() && proposed.second.isNotEmpty() &&
                        existing.second != proposed.second)
                ) continue
                if (officialNewKey && officialPolygon?.let {
                        PlaceGeometry.contains(it, rows.getDouble(2), rows.getDouble(3))
                    } != true
                ) continue
                Location.distanceBetween(
                    discovery.latitude, discovery.longitude,
                    rows.getDouble(2), rows.getDouble(3), distances
                )
                if (distances[0] <= 15_000f) {
                    return discovery.copy(key = rows.getString(0))
                }
            }
        }
        return discovery
    }

    private fun municipalityIdentity(value: String): Pair<String, String> {
        val text = value.trim().lowercase(java.util.Locale.ROOT)
            .replace(MUNICIPALITY_WHITESPACE_RE, " ").removePrefix("the ")
        for (kind in MUNICIPALITY_TYPES) {
            if (text.startsWith(kind) && text.startsWith(" of ", kind.length)) {
                return text.substring(kind.length + 4) to kind
            }
            val kindStart = text.length - kind.length
            if (kindStart > 0 && text[kindStart - 1] == ' ' && text.endsWith(kind)) {
                return text.substring(0, kindStart - 1) to kind
            }
        }
        return text to ""
    }

    private fun insertVisitedPlace(db: SQLiteDatabase, discovery: PlaceDiscovery, weeklyEligible: Boolean = true): Boolean {
        val inserted = db.insertWithOnConflict(
            "visited_places", null,
            ContentValues().apply {
                put("kind", discovery.kind.name)
                put("place_key", discovery.key)
                put("display_name", discovery.displayName)
                put("parent_name", discovery.parentName)
                put("country_name", discovery.countryName)
                put("first_visited_at", discovery.visitedAt)
                put("latitude", discovery.latitude)
                put("longitude", discovery.longitude)
                put("weekly_eligible", if (weeklyEligible) 1 else 0)
            },
            SQLiteDatabase.CONFLICT_IGNORE
        ) != -1L
        if (discovery.visitedAt > 0L) {
            // Older backfilled GPS samples can resolve out of order. Preserve the
            // earliest confirmed fix without altering reward or baseline counters.
            if (!inserted) {
                db.update(
                    "visited_places",
                    ContentValues().apply {
                        put("first_visited_at", discovery.visitedAt)
                        put("latitude", discovery.latitude)
                        put("longitude", discovery.longitude)
                        if (!weeklyEligible) put("weekly_eligible", 0)
                    },
                    "kind = ? AND place_key = ? AND first_visited_at " + (if (weeklyEligible) "> ?" else ">= ?"),
                    arrayOf(discovery.kind.name, discovery.key, discovery.visitedAt.toString())
                )
            }
            val key = placeLastVisitKey(discovery.kind, discovery.key)
            val previous = counterOrNull(db, key)
            if (previous == null || discovery.visitedAt > previous) {
                putCounter(db, key, discovery.visitedAt)
            }
        }
        return inserted
    }

    private fun placeLastVisitKey(kind: PlaceKind, key: String): String =
        "last_visit:${kind.name.lowercase()}:$key"

    private fun baselineKey(discovery: PlaceDiscovery): String =
        "baseline:${discovery.kind.name.lowercase()}:${discovery.key}"

    private fun baselineComplete(db: SQLiteDatabase): Boolean =
        db.rawQuery(BASELINE_COMPLETE_SQL, BASELINE_COMPLETE_ARGUMENTS).use {
            it.moveToFirst() && it.getInt(0) != 0
        }

    private fun markMissingBaselineKinds(db: SQLiteDatabase) {
        for (kind in PlaceKind.entries) {
            val resolved = db.rawQuery(
                "SELECT 1 FROM progression_counters WHERE counter_key LIKE ? LIMIT 1",
                arrayOf("baseline:${kind.name.lowercase()}:%")
            ).use { it.moveToFirst() }
            if (!resolved) putCounter(db, baselineMissingKey(kind), 1L)
        }
    }

    private fun baselineMissingKey(kind: PlaceKind): String =
        "baseline_missing:${kind.name.lowercase()}"

    private fun scheduleCandidateRetry(
        db: SQLiteDatabase,
        candidate: PendingPlaceCandidate,
        nowMillis: Long
    ) {
        val attempts = (candidate.attempts + 1).coerceAtMost(10)
        val baseline = candidate.cellX == BASELINE_CANDIDATE_X &&
            candidate.cellY == BASELINE_CANDIDATE_Y
        val delay = if (baseline) {
            (BASELINE_RETRY_MS * (1L shl (attempts - 1).coerceAtMost(4)))
                .coerceAtMost(BASELINE_RETRY_MAX_MS)
        } else {
            (30L * 60_000L * (1L shl attempts.coerceAtMost(5)))
                .coerceAtMost(24L * 60L * 60_000L)
        }
        db.update(
            "place_candidates",
            ContentValues().apply {
                put("attempts", attempts)
                put("next_attempt_ms", nowMillis + delay)
            },
            "cell_x = ? AND cell_y = ?",
            arrayOf(candidate.cellX.toString(), candidate.cellY.toString())
        )
    }

    private fun isCurrentHistory(expectedHistoryGeneration: Long? = null): Boolean =
        historyGeneration == dbHelper.historyGeneration &&
            (expectedHistoryGeneration == null || expectedHistoryGeneration == dbHelper.historyGeneration)

    private fun currentBalance(db: SQLiteDatabase): Long {
        return db.rawQuery(
            """SELECT
                (SELECT COALESCE(SUM(points), 0) FROM progression_rewards),
                (SELECT COALESCE(SUM(points_spent), 0) FROM progression_purchases)""",
            null
        ).use {
            check(it.moveToFirst())
            it.getLong(0) - it.getLong(1)
        }
    }

    private fun awardOnce(db: SQLiteDatabase, key: String, points: Long): Boolean =
        db.insertWithOnConflict(
            "progression_rewards",
            null,
            ContentValues().apply {
                put("reward_key", key)
                put("points", points)
                put("awarded_at", System.currentTimeMillis())
            },
            SQLiteDatabase.CONFLICT_IGNORE
        ) != -1L

    private fun counter(db: SQLiteDatabase, key: String): Long = counterOrNull(db, key) ?: 0L

    private fun counterOrNull(db: SQLiteDatabase, key: String): Long? =
        db.rawQuery(
            "SELECT value FROM progression_counters WHERE counter_key = ?",
            arrayOf(key)
        ).use { if (it.moveToFirst()) it.getLong(0) else null }

    private fun putCounter(db: SQLiteDatabase, key: String, value: Long) {
        db.insertWithOnConflict(
            "progression_counters",
            null,
            ContentValues().apply {
                put("counter_key", key)
                put("value", value)
            },
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    companion object {
        const val POINTS_PER_ROAD = 2L
        private val MUNICIPALITY_WHITESPACE_RE = Regex("\\s+")
        private val MUNICIPALITY_TYPES = listOf("township", "borough", "municipality", "village", "town", "city")
        // CASE preserves the original kind order and stops at the first missing
        // kind, while using one statement/cursor rather than one per kind.
        private val BASELINE_COMPLETE_SQL = "SELECT CASE " + PlaceKind.entries.joinToString(" ") {
            "WHEN NOT EXISTS (SELECT 1 FROM progression_counters WHERE counter_key LIKE ? OR counter_key = ? LIMIT 1) THEN 0"
        } + " ELSE 1 END"
        private val BASELINE_COMPLETE_ARGUMENTS = PlaceKind.entries.flatMap {
            listOf("baseline:${it.name.lowercase()}:%", "baseline_missing:${it.name.lowercase()}")
        }.toTypedArray()
        // 150 m real-fix cells preserve short municipal crossings missed by older 400 m sampling.
        // Retain the old cells separately; their pending lookups remain valid after an update.
        private const val PLACE_CANDIDATE_CELL_M = 150.0
        private const val PLACE_CANDIDATE_VERSION_OFFSET = 2_000_000_000L
        private const val SECONDARY_CANDIDATE_OFFSET = 1_000_000_000L
        private const val SECONDARY_CANDIDATE_MIN_DISTANCE_M = 45f
        private const val COUNTER_PLACE_BACKFILL_NEXT_ID = "place_backfill_next_track_id_v3"
        private const val COUNTER_SKIPPED_PLACE_BACKFILL_NEXT_ID = "place_backfill_next_skipped_id_v1"
        private const val BASELINE_RETRY_MS = 60_000L
        private const val BASELINE_RETRY_MAX_MS = 15 * 60_000L
        private const val BASELINE_PARTIAL_RESOLUTION_LIMIT = 3
        private const val BASELINE_CANDIDATE_X = Long.MIN_VALUE
        private const val BASELINE_CANDIDATE_Y = Long.MIN_VALUE
        private const val COUNTER_REWARDED_ROADS = "rewarded_roads"
        private const val COUNTER_ADS_WATCHED = "ads_watched"
        private const val COUNTER_LOWEST_BATTERY = "lowest_battery_percent"
    }
}
