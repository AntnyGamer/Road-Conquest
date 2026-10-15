package com.roadconquest.app.data

import android.content.Context
import com.roadconquest.app.billing.VipEntitlement
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.format.DateTimeFormatter
import kotlin.math.ceil

/** Read-only trips derived from saved driving fixes; no database migration or tracking changes. */
data class SavedTrip(
    val firstId: Long,
    val lastId: Long,
    val startedAt: Long,
    val endedAt: Long,
    val pointCount: Int,
    val distanceMeters: Double,
    val historyGeneration: Long = -1L
)
data class SavedTripPoint(val latitude: Double, val longitude: Double, val timestamp: Long, val breakBefore: Boolean = false)

/** Snapshot of trip-scoped facts; later road matching may revise road counts. */
data class SavedTripInsights(val newRoadGroups: Long, val newTowns: Long, val newTiles: Long, val pendingFixes: Long)


class TripHistoryRepository(context: Context) {
    private val appContext = context.applicationContext
    private val helper = AppDatabase.get(context.applicationContext)

    /** Bounded recent history: free users see 100 drives; VIP can request up to 500. */
    fun recentTrips(limit: Int = 100): List<SavedTrip> = synchronized(helper.historyLock) {
        require(limit in 1..(if (VipEntitlement.isActive(appContext)) 500 else 100)) {
            "Extended trip history requires lifetime VIP"
        }
        val output = ArrayList<SavedTrip>(limit)
        var firstId = 0L
        var lastId = 0L
        var firstTime = 0L
        var lastTime = 0L
        var count = 0
        var distance = 0.0
        fun addCurrent() {
            if (count >= 2 && lastTime >= firstTime) {
                output += SavedTrip(firstId, lastId, firstTime, lastTime, count, distance, helper.historyGeneration)
            }
        }
        helper.readableDatabase.rawQuery(
            "SELECT id, timestamp_ms, distance_m FROM track_points ORDER BY id DESC", null
        ).use { cursor ->
            while (cursor.moveToNext() && output.size < limit) {
                val id = cursor.getLong(0)
                val time = cursor.getLong(1)
                val meters = cursor.getDouble(2)
                if (count != 0 && isTripBoundary(lastTime = firstTime, olderTime = time)) {
                    addCurrent()
                    count = 0
                    distance = 0.0
                    if (output.size >= limit) break
                }
                if (count == 0) {
                    lastId = id
                    lastTime = time
                }
                firstId = id
                firstTime = time
                count++
                if (meters.isFinite() && meters > 0.0) distance += meters
            }
        }
        if (output.size < limit) addCurrent()
        output
    }

    /** Cheap generation check; protects outgoing shares after a device-data reset. */
    fun isCurrentTrip(trip: SavedTrip): Boolean =
        trip.historyGeneration == helper.historyGeneration

    /** Uses existing indexed history timestamps; never fabricates per-trip tile counts. */
    fun insights(trip: SavedTrip): SavedTripInsights = synchronized(helper.historyLock) {
        if (trip.historyGeneration != helper.historyGeneration) return@synchronized SavedTripInsights(0, 0, 0, 0)
        val db = helper.readableDatabase
        val period = arrayOf(trip.startedAt.toString(), trip.endedAt.toString())
        val roads = db.rawQuery(
            """SELECT COUNT(DISTINCT CASE WHEN road_group_id = '' THEN segment_id ELSE road_group_id END)
               FROM roads WHERE first_unlocked_at BETWEEN ? AND ?
               AND road_group_id NOT LIKE 'x2|%'""", period
        ).use { it.moveToFirst(); it.getLong(0) }
        val towns = db.rawQuery(
            """SELECT COUNT(*) FROM visited_places v WHERE kind = 'TOWN'
               AND first_visited_at BETWEEN ? AND ?
               AND EXISTS (SELECT 1 FROM progression_rewards r
                 WHERE r.reward_key = 'place:town:' || v.place_key)""", period
        ).use { it.moveToFirst(); it.getLong(0) }
        // Unknown pre-upgrade discovery times are stored as zero and are
        // never retroactively credited to an arbitrary old trip.
        val tiles = db.rawQuery(
            "SELECT COUNT(*) FROM explored_grid WHERE first_unlocked_at BETWEEN ? AND ? AND first_unlocked_at > 0",
            period
        ).use { it.moveToFirst(); it.getLong(0) }
        val pending = db.rawQuery(
            "SELECT COUNT(*) FROM track_points WHERE id BETWEEN ? AND ? AND matched = 0",
            arrayOf(trip.firstId.toString(), trip.lastId.toString())
        ).use { it.moveToFirst(); it.getLong(0) }
        SavedTripInsights(roads, towns, tiles, pending)
    }

    /**
     * Explicit, user-initiated GPX export; no internet or RoadConquest server.
     * The default privacy mode omits two minutes from BOTH trip endpoints.
     * Entire small trips may yield no coordinates rather than exposing a home.
     */
    fun exportGpx(trip: SavedTrip, output: OutputStream, hideTripEnds: Boolean = true): Int {
        check(VipEntitlement.isActive(appContext)) { "GPX sharing requires lifetime VIP" }
        // Slow Google Drive / document-provider writes must never hold the main
        // GPS history lock. WAL allows this independent read-only connection to
        // observe a consistent drive snapshot without blocking new tracking.
        val databasePath = synchronized(helper.historyLock) {
            check(trip.historyGeneration == helper.historyGeneration) { "Trip history changed; select it again." }
            helper.readableDatabase.path
        }
        return android.database.sqlite.SQLiteDatabase.openDatabase(
            databasePath, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY
        ).use { snapshot ->
            val endPadding = if (hideTripEnds) 120_000L else 0L
            val from = trip.startedAt + endPadding
            val until = trip.endedAt - endPadding
            val formatter = DateTimeFormatter.ISO_INSTANT
            var written = 0
            var previousTimestamp: Long? = null
            val writer = OutputStreamWriter(output, StandardCharsets.UTF_8)
            writer.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            writer.write("<gpx version=\"1.1\" creator=\"Road Conquest\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n<trk><name>Road Conquest drive</name>\n")
            snapshot.rawQuery(
                "SELECT latitude, longitude, timestamp_ms FROM track_points WHERE id BETWEEN ? AND ? ORDER BY id ASC",
                arrayOf(trip.firstId.toString(), trip.lastId.toString())
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    // A reset or restore must cancel this old trip's export.
                    if (trip.historyGeneration != helper.historyGeneration) {
                        error("Trip history changed during export")
                    }
                    val timestamp = cursor.getLong(2)
                    if (timestamp < from || timestamp > until) continue
                    val lat = cursor.getDouble(0)
                    val lon = cursor.getDouble(1)
                    if (!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0) continue
                    if (previousTimestamp == null || timestamp <= previousTimestamp!! ||
                        timestamp - previousTimestamp!! > 30_000L) {
                        if (previousTimestamp != null) writer.write("</trkseg>\n")
                        writer.write("<trkseg>\n")
                    }
                    writer.write("<trkpt lat=\"$lat\" lon=\"$lon\"><time>${formatter.format(Instant.ofEpochMilli(timestamp))}</time></trkpt>\n")
                    previousTimestamp = timestamp
                    written++
                }
            }
            if (previousTimestamp != null) writer.write("</trkseg>\n")
            writer.write("</trk></gpx>\n")
            check(trip.historyGeneration == helper.historyGeneration) { "Trip history changed during export" }
            writer.flush()
            written
        }
    }

    /** Read a bounded route sample, always retaining the first and last points. */
    fun replay(trip: SavedTrip, maxPoints: Int = 2500): List<SavedTripPoint> =
        synchronized(helper.historyLock) {
            require(maxPoints in 2..5000)
            if (trip.historyGeneration != helper.historyGeneration) return@synchronized emptyList()
            val db = helper.readableDatabase
            val bounds = arrayOf(trip.firstId.toString(), trip.lastId.toString())
            // recentTrips() already counted these exact IDs under the same
            // history generation. New GPS rows append beyond lastId; a restore
            // invalidates generation above. Avoid recounting a long trip on replay.
            val stride = ceil(trip.pointCount.toDouble() / maxPoints).toInt().coerceAtLeast(1)
            val selected = ArrayList<SavedTripPoint>(minOf(maxPoints, trip.pointCount))
            var position = 0L
            var last: SavedTripPoint? = null
            var previousTime: Long? = null
            var gapSinceSample = false
            db.rawQuery(
                "SELECT latitude, longitude, timestamp_ms FROM track_points " +
                    "WHERE id BETWEEN ? AND ? ORDER BY id ASC", bounds
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val time = cursor.getLong(2)
                    previousTime?.let { if (time <= it || time - it > 30_000L) gapSinceSample = true }
                    val point = SavedTripPoint(cursor.getDouble(0), cursor.getDouble(1), time, gapSinceSample)
                    if (position % stride == 0L && selected.size < maxPoints - 1) {
                        selected += point
                        gapSinceSample = false
                    }
                    last = point
                    previousTime = time
                    position++
                }
            }
            // The terminal fix is always kept even if it falls between strides.
            // Carry any gap found in skipped fixes into its break flag so replay
            // never draws a straight line across an unrecorded stretch.
            last?.let {
                if (selected.lastOrNull() != it) {
                    selected += it.copy(breakBefore = it.breakBefore || gapSinceSample)
                }
            }
            selected
        }

    companion object {
        internal const val TRIP_GAP_MS = 15 * 60_000L
        internal fun isTripBoundary(lastTime: Long, olderTime: Long): Boolean =
            olderTime > lastTime || lastTime - olderTime >= TRIP_GAP_MS
    }
}
