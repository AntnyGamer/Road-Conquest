package com.roadconquest.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class AppDatabase private constructor(context: Context) :
    SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    internal val historyLock = Any()
    @Volatile internal var historyGeneration = 0L

    // Summary reads happen frequently while the UI is open. Keep exact in-memory aggregates
    // between writes so large driving histories do not need to be rescanned just to redraw stats.
    // Any caller that asks for the raw database disables this cache because it may mutate tables
    // outside TrackingRepository's controlled write paths (tests/debug tooling do this).
    internal data class TrackSummaryCache(
        val pointCount: Long,
        val firstTrackAt: Long?,
        val lastTrackAt: Long?,
        val distanceMeters: Double
    )
    internal data class RoadSummaryCache(
        val segmentCount: Long,
        val unlockedCount: Long
    )
    internal data class LastTrackPointCache(
        val present: Boolean,
        val id: Long = 0L,
        val latitude: Double = 0.0,
        val longitude: Double = 0.0,
        val timestamp: Long = 0L
    )
    internal var trackSummaryCache: TrackSummaryCache? = null
    internal var roadSummaryCache: RoadSummaryCache? = null
    internal var lastTrackPointCache: LastTrackPointCache? = null
    internal var summaryCachingEnabled = true

    internal fun invalidateRoadSummary() {
        roadSummaryCache = null
    }

    internal fun applyTrackSummaryDelta(
        count: Long,
        firstTimestamp: Long?,
        lastTimestamp: Long?,
        distanceMeters: Double
    ) {
        if (!summaryCachingEnabled || count <= 0L) return
        val current = trackSummaryCache ?: return
        val first = when {
            current.firstTrackAt == null -> firstTimestamp
            firstTimestamp == null -> current.firstTrackAt
            else -> minOf(current.firstTrackAt, firstTimestamp)
        }
        val last = when {
            current.lastTrackAt == null -> lastTimestamp
            lastTimestamp == null -> current.lastTrackAt
            else -> maxOf(current.lastTrackAt, lastTimestamp)
        }
        trackSummaryCache = current.copy(
            pointCount = current.pointCount + count,
            firstTrackAt = first,
            lastTrackAt = last,
            distanceMeters = current.distanceMeters + distanceMeters
        )
    }

    internal fun resetSummaryCaches() {
        if (!summaryCachingEnabled) {
            trackSummaryCache = null
            roadSummaryCache = null
            lastTrackPointCache = null
            return
        }
        trackSummaryCache = TrackSummaryCache(0L, null, null, 0.0)
        roadSummaryCache = RoadSummaryCache(0L, 0L)
        lastTrackPointCache = LastTrackPointCache(present = false)
    }

    internal fun disableSummaryCaching() {
        summaryCachingEnabled = false
        trackSummaryCache = null
        roadSummaryCache = null
        lastTrackPointCache = null
    }

    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE track_points (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                latitude REAL NOT NULL,
                longitude REAL NOT NULL,
                accuracy_m REAL NOT NULL,
                speed_mps REAL NOT NULL,
                bearing_deg REAL NOT NULL,
                timestamp_ms INTEGER NOT NULL,
                matched INTEGER NOT NULL DEFAULT 0,
                next_match_attempt_ms INTEGER NOT NULL DEFAULT 0,
                distance_m REAL NOT NULL DEFAULT 0,
                weekly_eligible INTEGER NOT NULL DEFAULT 1 CHECK(weekly_eligible IN (0,1))
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_track_time ON track_points(timestamp_ms)")
        db.execSQL("CREATE INDEX idx_track_bounds ON track_points(latitude, longitude)")
        db.execSQL("CREATE INDEX idx_track_match_queue ON track_points(matched, next_match_attempt_ms, id)")
        createDiscardedDrivingFixesTable(db)

        db.execSQL(
            """
            CREATE TABLE roads (
                segment_id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                geometry_json TEXT NOT NULL,
                first_unlocked_at INTEGER NOT NULL,
                last_driven_at INTEGER NOT NULL,
                drive_count INTEGER NOT NULL DEFAULT 1,
                drive_count_exact INTEGER NOT NULL DEFAULT 0,
                road_group_id TEXT NOT NULL DEFAULT '',
                min_lat REAL NOT NULL,
                max_lat REAL NOT NULL,
                min_lon REAL NOT NULL,
                max_lon REAL NOT NULL,
                weekly_eligible INTEGER NOT NULL DEFAULT 1 CHECK(weekly_eligible IN (0,1))
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_roads_last_driven ON roads(last_driven_at)")
        db.execSQL("CREATE INDEX idx_roads_bounds ON roads(min_lat, max_lat, min_lon, max_lon)")
        db.execSQL("CREATE INDEX idx_roads_group ON roads(road_group_id)")

        db.execSQL(
            "CREATE TABLE road_visits (" +
                "segment_id TEXT NOT NULL, started_at INTEGER NOT NULL, ended_at INTEGER NOT NULL, " +
                "PRIMARY KEY (segment_id, started_at, ended_at), " +
                "FOREIGN KEY (segment_id) REFERENCES roads(segment_id) ON DELETE CASCADE)"
        )
        // The composite PRIMARY KEY already creates the covering index used for visit lookups.

        db.execSQL(
            "CREATE TABLE explored_places (" +
                "cell_x INTEGER NOT NULL, cell_y INTEGER NOT NULL, latitude REAL NOT NULL, " +
                "longitude REAL NOT NULL, PRIMARY KEY (cell_x, cell_y))"
        )
        db.execSQL("CREATE INDEX idx_explored_bounds ON explored_places(latitude, longitude)")
        createExplorationGridTable(db)
        createProgressionTables(db)
    }

    private fun createProgressionTables(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS visited_places (
                kind TEXT NOT NULL,
                place_key TEXT NOT NULL,
                display_name TEXT NOT NULL,
                parent_name TEXT NOT NULL DEFAULT '',
                country_name TEXT NOT NULL DEFAULT '',
                first_visited_at INTEGER NOT NULL,
                latitude REAL NOT NULL,
                longitude REAL NOT NULL,
                weekly_eligible INTEGER NOT NULL DEFAULT 1 CHECK(weekly_eligible IN (0,1)),
                PRIMARY KEY (kind, place_key)
            )""".trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_visited_places_kind ON visited_places(kind, first_visited_at)")
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS place_candidates (
                cell_x INTEGER NOT NULL,
                cell_y INTEGER NOT NULL,
                latitude REAL NOT NULL,
                longitude REAL NOT NULL,
                first_seen_at INTEGER NOT NULL,
                attempts INTEGER NOT NULL DEFAULT 0,
                next_attempt_ms INTEGER NOT NULL DEFAULT 0,
                weekly_eligible INTEGER NOT NULL DEFAULT 1 CHECK(weekly_eligible IN (0,1)),
                PRIMARY KEY (cell_x, cell_y)
            )""".trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_place_candidates_retry ON place_candidates(next_attempt_ms)")
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS progression_rewards (
                reward_key TEXT PRIMARY KEY,
                points INTEGER NOT NULL CHECK(points > 0),
                awarded_at INTEGER NOT NULL
            )""".trimIndent()
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS progression_purchases (
                item_id TEXT PRIMARY KEY,
                points_spent INTEGER NOT NULL CHECK(points_spent >= 0),
                purchased_at INTEGER NOT NULL
            )""".trimIndent()
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS progression_counters (
                counter_key TEXT PRIMARY KEY,
                value INTEGER NOT NULL
            )""".trimIndent()
        )
    }


    private fun createExplorationGridTable(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS explored_grid (" +
                "grid_row INTEGER NOT NULL, grid_col INTEGER NOT NULL, latitude REAL NOT NULL, " +
                "longitude REAL NOT NULL, first_unlocked_at INTEGER NOT NULL DEFAULT 0, " +
                "PRIMARY KEY (grid_row, grid_col))"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_explored_grid_bounds ON explored_grid(latitude, longitude)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_explored_grid_first_unlocked ON explored_grid(first_unlocked_at)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        error("Unsupported database upgrade $oldVersion -> $newVersion")
    }

    private fun createDiscardedDrivingFixesTable(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS skipped_driving_fixes (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                latitude REAL NOT NULL,
                longitude REAL NOT NULL,
                accuracy_m REAL NOT NULL,
                speed_mps REAL NOT NULL,
                bearing_deg REAL NOT NULL,
                timestamp_ms INTEGER NOT NULL,
                weekly_eligible INTEGER NOT NULL DEFAULT 1 CHECK(weekly_eligible IN (0,1))
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_skipped_driving_time ON skipped_driving_fixes(timestamp_ms)")
    }

    companion object {
        private const val DB_NAME = "roadconquest.db"
        private const val DB_VERSION = 16

        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: AppDatabase(context.applicationContext).also { instance = it }
        }
    }
}
