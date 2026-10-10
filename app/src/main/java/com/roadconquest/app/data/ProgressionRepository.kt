package com.roadconquest.app.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.location.Location
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sinh
import kotlin.math.tan

enum class PlaceKind(val points: Long) {
    TOWN(100L),
    STATE(500L),
    COUNTRY(1_500L)
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
    val attempts: Int
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
    val rewardedAchievements: Set<String>
)

enum class PurchaseResult { PURCHASED, OWNED, INSUFFICIENT_POINTS }

class ProgressionRepository(context: Context) {
    private val dbHelper = AppDatabase.get(context.applicationContext)
    private val historyGeneration = synchronized(dbHelper.historyLock) { dbHelper.historyGeneration }

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
            val inserted = insertVisitedPlace(db, discovery)
            if (inserted) {
                awardOnce(
                    db,
                    "place:${discovery.kind.name.lowercase()}:${discovery.key}",
                    discovery.kind.points
                )
            }
            db.setTransactionSuccessful()
            inserted
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Save a sparse reverse-geocoding candidate locally. Background tracking never has to make
     * a geocoder/network request; candidates are resolved later while the app is foregrounded.
     */
    fun recordPlaceCandidate(location: Location): Boolean = synchronized(dbHelper.historyLock) {
        if (!isCurrentHistory()) return@synchronized false
        if (location.isMock || !location.latitude.isFinite() || !location.longitude.isFinite() ||
            location.latitude !in -85.0..85.0 || location.longitude !in -180.0..180.0
        ) return@synchronized false
        val db = dbHelper.writableDatabase
        // Keep discoveries observed while the exact zero-point baseline is still resolving.
        // pendingPlaceCandidates() gates them behind the baseline, so they cannot earn points
        // early, but a short visit is not lost merely because reverse geocoding took minutes.
        insertPlaceCandidate(db, location.latitude, location.longitude,
            location.time.takeIf { it > 0L } ?: System.currentTimeMillis())
    }

    /**
     * Revisit accepted, persisted GPS fixes from releases where 2 km/center-based
     * town sampling could miss an actual driven township. Process a bounded page
     * per foreground discovery pass, newest first, with a durable cursor: no data
     * migration, no guesses from a road's drawn geometry, and no duplicate rewards.
     */
    fun backfillPlaceCandidates(limit: Int = 3_000): Int = synchronized(dbHelper.historyLock) {
        require(limit in 1..10_000)
        if (!isCurrentHistory()) return@synchronized 0
        val db = dbHelper.writableDatabase
        // Replaying fixes from BEFORE a fresh/reset progression baseline would silently
        // award already-erased towns. Only visits after this profile's exact live start count.
        val baselineTime = startingLocation()?.recordedAt ?: return@synchronized 0
        if (!db.rawQuery("SELECT 1 FROM visited_places LIMIT 1", null).use { it.moveToFirst() }) {
            // Wait until the zero-point places are resolved before queuing old GPS fixes.
            return@synchronized 0
        }
        val nextId = counterOrNull(db, COUNTER_PLACE_BACKFILL_NEXT_ID)
            ?: db.rawQuery("SELECT COALESCE(MAX(id), 0) + 1 FROM track_points", null)
                .use { it.moveToFirst(); it.getLong(0) }
        if (nextId <= 1L) return@synchronized 0
        var inserted = 0
        var next = nextId
        var examined = 0
        db.beginTransaction()
        try {
            db.rawQuery(
                """SELECT id, latitude, longitude, timestamp_ms FROM track_points
                   WHERE id < ? AND timestamp_ms >= ? AND accuracy_m BETWEEN 0.01 AND 25
                   ORDER BY id DESC LIMIT ?""",
                arrayOf(nextId.toString(), baselineTime.toString(), limit.toString())
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    examined++
                    next = cursor.getLong(0)
                    if (insertPlaceCandidate(db, cursor.getDouble(1), cursor.getDouble(2),
                            cursor.getLong(3))) inserted++
                }
            }
            // No eligible rows remain in the old history; stop scanning on later resumes.
            putCounter(db, COUNTER_PLACE_BACKFILL_NEXT_ID, if (examined == 0) 1L else next)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        inserted
    }

    /** V2 keys cannot collide with old 2 km keys while pending old visits resolve. */
    private fun insertPlaceCandidate(db: SQLiteDatabase, latitude: Double, longitude: Double, time: Long): Boolean {
        if (!latitude.isFinite() || !longitude.isFinite() ||
            latitude !in -85.0..85.0 || longitude !in -180.0..180.0) return false
        val radius = 6_378_137.0
        val normalized = ((longitude + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
        val x = floor(radius * Math.toRadians(normalized) / PLACE_CANDIDATE_CELL_M).toLong() +
            PLACE_CANDIDATE_VERSION_OFFSET
        val y = floor(radius * ln(tan(PI / 4 + Math.toRadians(latitude) / 2)) /
            PLACE_CANDIDATE_CELL_M).toLong()
        return db.insertWithOnConflict(
            "place_candidates", null,
            ContentValues().apply {
                put("cell_x", x)
                put("cell_y", y)
                // Reverse-geocode the ACTUAL driven fix, not an arbitrary 2 km
                // grid center which may sit across the municipal boundary.
                put("latitude", latitude)
                put("longitude", normalized)
                put("first_seen_at", time)
                put("attempts", 0)
                put("next_attempt_ms", 0)
            },
            SQLiteDatabase.CONFLICT_IGNORE
        ) != -1L
    }

    /**
     * Capture the exact first good live fix for a fresh/reset progression history. Ordinary
     * sparse place candidates never substitute for this zero-point starting location.
     */
    fun recordBaselineCandidate(location: Location): Boolean = synchronized(dbHelper.historyLock) {
        if (!isCurrentHistory()) return@synchronized false
        if (location.isMock || !location.latitude.isFinite() || !location.longitude.isFinite() ||
            location.latitude !in -85.0..85.0 || location.longitude !in -180.0..180.0
        ) return@synchronized false
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

    fun pendingPlaceCandidates(
        limit: Int = 8,
        nowMillis: Long = System.currentTimeMillis()
    ): List<PendingPlaceCandidate> {
        require(limit in 1..50)
        val db = dbHelper.readableDatabase

        // Before the first live baseline exists, stale/sparse candidates cannot become the
        // starting place. Once the sentinel exists, it is an ordering barrier: ordinary
        // candidates can be recorded but remain hidden until zero-point resolution completes.
        val hasKnownPlace = db.rawQuery(
            "SELECT 1 FROM visited_places LIMIT 1",
            null
        ).use { it.moveToFirst() }
        val baseline = db.query(
            "place_candidates",
            arrayOf(
                "cell_x", "cell_y", "latitude", "longitude",
                "first_seen_at", "attempts", "next_attempt_ms"
            ),
            "cell_x = ? AND cell_y = ?",
            arrayOf(BASELINE_CANDIDATE_X.toString(), BASELINE_CANDIDATE_Y.toString()),
            null,
            null,
            null,
            "1"
        ).use { cursor ->
            if (!cursor.moveToFirst()) null else PendingPlaceCandidate(
                cursor.getLong(0),
                cursor.getLong(1),
                cursor.getDouble(2),
                cursor.getDouble(3),
                cursor.getLong(4),
                cursor.getInt(5)
            ) to cursor.getLong(6)
        }
        if (baseline != null) {
            return baseline.takeIf { it.second <= nowMillis }?.let { listOf(it.first) }.orEmpty()
        }
        if (!hasKnownPlace) return emptyList()

        val result = ArrayList<PendingPlaceCandidate>(limit)
        db.query(
            "place_candidates",
            arrayOf("cell_x", "cell_y", "latitude", "longitude", "first_seen_at", "attempts"),
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
                result += PendingPlaceCandidate(
                    cursor.getLong(0),
                    cursor.getLong(1),
                    cursor.getDouble(2),
                    cursor.getDouble(3),
                    cursor.getLong(4),
                    cursor.getInt(5)
                )
            }
        }
        return result
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
                for (discovery in discoveries) {
                    if (baseline) {
                        // The exact first live fix after install/reset defines the zero-point
                        // town/state/country. It is visible to overlays but earns no points.
                        insertVisitedPlace(db, discovery)
                        putCounter(db, baselineKey(discovery), 1L)
                        continue
                    }
                    val inserted = insertVisitedPlace(db, discovery)
                    if (inserted) {
                        awardOnce(
                            db,
                            "place:${discovery.kind.name.lowercase()}:${discovery.key}",
                            discovery.kind.points
                        )
                        added++
                    }
                }
                if (baseline && !baselineComplete(db)) {
                    if (candidate.attempts + 1 >= BASELINE_PARTIAL_RESOLUTION_LIMIT) {
                        markMissingBaselineKinds(db)
                    } else {
                        scheduleCandidateRetry(db, candidate, System.currentTimeMillis())
                    }
                }
                if (!baseline || baselineComplete(db)) {
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

    fun snapshot(): ProgressionSnapshot = synchronized(dbHelper.historyLock) {
        val db = dbHelper.readableDatabase
        val earned = db.rawQuery("SELECT COALESCE(SUM(points), 0) FROM progression_rewards", null)
            .use { check(it.moveToFirst()); it.getLong(0) }

        val rewardedAchievements = linkedSetOf<String>()
        db.query(
            "progression_rewards",
            arrayOf("reward_key"),
            "reward_key LIKE ?",
            arrayOf("achievement:%"),
            null, null, null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                rewardedAchievements += cursor.getString(0).removePrefix("achievement:")
            }
        }

        val counts = mutableMapOf<PlaceKind, Long>()
        // Baseline places stay in visited_places for overlays, but only rewarded discoveries
        // count toward progress totals and achievements.
        db.rawQuery(
            """SELECT v.kind, COUNT(*)
               FROM visited_places v
               JOIN progression_rewards r
                 ON r.reward_key = 'place:' || lower(v.kind) || ':' || v.place_key
               GROUP BY v.kind""",
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                PlaceKind.entries.firstOrNull { it.name == cursor.getString(0) }?.let {
                    counts[it] = cursor.getLong(1)
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

        ProgressionSnapshot(
            balance = (earned - spent).coerceAtLeast(0L),
            lifetimeEarned = earned,
            pointsSpent = spent,
            rewardedRoads = counters[COUNTER_REWARDED_ROADS] ?: 0L,
            towns = counts[PlaceKind.TOWN] ?: 0L,
            states = counts[PlaceKind.STATE] ?: 0L,
            countries = counts[PlaceKind.COUNTRY] ?: 0L,
            adsWatched = counters[COUNTER_ADS_WATCHED] ?: 0L,
            lowestBatteryPercent = (counters[COUNTER_LOWEST_BATTERY] ?: 101L)
                .toInt().coerceIn(0, 101),
            purchasedItems = purchases,
            rewardedAchievements = rewardedAchievements
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

    private fun insertVisitedPlace(db: SQLiteDatabase, discovery: PlaceDiscovery): Boolean {
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
                    },
                    "kind = ? AND place_key = ? AND first_visited_at > ?",
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
        PlaceKind.entries.all { kind ->
            db.rawQuery(
                "SELECT 1 FROM progression_counters WHERE counter_key LIKE ? OR counter_key = ? LIMIT 1",
                arrayOf(
                    "baseline:${kind.name.lowercase()}:%",
                    baselineMissingKey(kind)
                )
            ).use { it.moveToFirst() }
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
        val earned = db.rawQuery("SELECT COALESCE(SUM(points), 0) FROM progression_rewards", null)
            .use { check(it.moveToFirst()); it.getLong(0) }
        val spent = db.rawQuery("SELECT COALESCE(SUM(points_spent), 0) FROM progression_purchases", null)
            .use { check(it.moveToFirst()); it.getLong(0) }
        return earned - spent
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
        const val POINTS_PER_ROAD = 5L
        // 150 m real-fix cells preserve short municipal crossings missed by older 400 m sampling.
        // Retain the old cells separately; their pending lookups remain valid after an update.
        private const val PLACE_CANDIDATE_CELL_M = 150.0
        private const val PLACE_CANDIDATE_VERSION_OFFSET = 2_000_000_000L
        private const val COUNTER_PLACE_BACKFILL_NEXT_ID = "place_backfill_next_track_id_v3"
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
