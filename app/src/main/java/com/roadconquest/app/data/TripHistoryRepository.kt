package com.roadconquest.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.roadconquest.app.billing.VipEntitlement
import java.io.OutputStream
import java.io.BufferedWriter
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

    /** Read an independent WAL snapshot so long trip scans do not block new GPS writes. */
    private fun <T> withHistorySnapshot(block: (SQLiteDatabase, Long) -> T): T {
        val (path, generation) = synchronized(helper.historyLock) {
            helper.readableDatabase.path to helper.historyGeneration
        }
        return SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READONLY)
            .use { snapshot -> block(snapshot, generation) }
    }

    /** Bounded recent history: free users see 100 drives; VIP can request up to 500. */
    fun recentTrips(limit: Int = 100): List<SavedTrip> = withHistorySnapshot { db, generation ->
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
        var hasDrivingSpeed = false
        fun addCurrent() {
            // Small walking/GPS-scatter groups used to fill the list with 0.0 mi
            // drives. Keep short genuine drives with credible speed evidence.
            // This only filters the list: all original fixes remain in the backup.
            if (count >= 2 && lastTime >= firstTime &&
                (distance >= 80.4672 || hasDrivingSpeed)) {
                output += SavedTrip(firstId, lastId, firstTime, lastTime, count, distance, generation)
            }
        }
        // SQLiteCursor counts the entire SQL result when first filling its window,
        // even if Kotlin stops after a few trips. Keyset pages bound that work.
        // These columns are immutable during tracking; new fixes append above the
        // first page. Restore/deletion still invalidate the generation below.
        var beforeId: Long? = null
        var pageSize = RECENT_TRIP_PAGE_SIZE
        while (output.size < limit) {
            var rows = 0
            val where = if (beforeId == null) "" else " WHERE id < ?"
            db.rawQuery(
                "SELECT id, timestamp_ms, distance_m, speed_mps, accuracy_m FROM track_points$where ORDER BY id DESC LIMIT $pageSize",
                beforeId?.let { arrayOf(it.toString()) }
            ).use { cursor ->
                while (cursor.moveToNext() && output.size < limit) {
                    val id = cursor.getLong(0)
                    beforeId = id
                    rows++
                    val time = cursor.getLong(1)
                    val meters = cursor.getDouble(2)
                    if (count != 0 && isTripBoundary(lastTime = firstTime, olderTime = time)) {
                        addCurrent()
                        count = 0
                        distance = 0.0
                        hasDrivingSpeed = false
                        if (output.size >= limit) break
                    }
                    if (count == 0) {
                        lastId = id
                        lastTime = time
                    }
                    firstId = id
                    firstTime = time
                    count++
                    if (cursor.getFloat(3) in 2.2f..100f && cursor.getFloat(4) in 0.01f..25f) hasDrivingSpeed = true
                    if (meters.isFinite() && meters > 0.0) distance += meters
                }
            }
            if (rows < pageSize) break
            // Long drives use larger bounded pages instead of hundreds of tiny cursors.
            pageSize = minOf(pageSize * 2, MAX_RECENT_TRIP_PAGE_SIZE)
        }
        if (output.size < limit) addCurrent()
        // A device-data reset can complete while this independent snapshot is
        // being read; never show a pre-reset trip under the new generation.
        if (generation == helper.historyGeneration) output else emptyList()
    }

    /** Cheap invalidation token for screens resuming after a restore or deletion. */
    fun currentHistoryGeneration(): Long = helper.historyGeneration

    /** Cheap generation check; protects outgoing shares after a device-data reset. */
    fun isCurrentTrip(trip: SavedTrip): Boolean =
        trip.historyGeneration == helper.historyGeneration

    /** Uses existing indexed history timestamps; never fabricates per-trip tile counts. */
    fun insights(trip: SavedTrip): SavedTripInsights = withHistorySnapshot { db, generation ->
        if (trip.historyGeneration != generation) return@withHistorySnapshot SavedTripInsights(0, 0, 0, 0)
        // One SQLite statement gives the four counters the same WAL snapshot,
        // even while road matching and place discovery update the live DB.
        // Historic tile unlock times (zero) are deliberately excluded.
        val started = trip.startedAt.toString()
        val ended = trip.endedAt.toString()
        val args = arrayOf(
            started, ended, started, ended, started, ended,
            trip.firstId.toString(), trip.lastId.toString()
        )
        val counts = db.rawQuery(
            """SELECT
                (SELECT COUNT(DISTINCT CASE WHEN road_group_id = '' THEN segment_id ELSE road_group_id END)
                   FROM roads WHERE first_unlocked_at BETWEEN ? AND ?
                   AND road_group_id NOT LIKE 'x2|%'),
                (SELECT COUNT(*) FROM visited_places v
                   WHERE kind = 'TOWN' AND first_visited_at BETWEEN ? AND ?
                   AND EXISTS (SELECT 1 FROM progression_rewards r
                     WHERE r.reward_key = 'place:town:' || v.place_key)),
                (SELECT COUNT(*) FROM explored_grid
                   WHERE first_unlocked_at BETWEEN ? AND ? AND first_unlocked_at > 0),
                (SELECT COUNT(*) FROM track_points
                   WHERE id BETWEEN ? AND ? AND matched = 0)""",
            args
        ).use { cursor ->
            check(cursor.moveToFirst())
            SavedTripInsights(cursor.getLong(0), cursor.getLong(1), cursor.getLong(2), cursor.getLong(3))
        }
        if (generation == helper.historyGeneration) counts else SavedTripInsights(0, 0, 0, 0)
    }

    /**
     * Explicit, user-initiated GPX export; no internet or RoadConquest server.
     * The default privacy mode omits two minutes from BOTH trip endpoints.
     * Entire small trips may yield no coordinates rather than exposing a home.
     */
    fun exportGpx(trip: SavedTrip, output: OutputStream, hideTripEnds: Boolean = true): Int {
        check(VipEntitlement.isActive(appContext)) { "GPX sharing requires lifetime VIP" }
        // Use the same independent WAL snapshot as trip browsing. Slow cloud
        // document writes must never hold the main GPS history lock.
        return withHistorySnapshot { snapshot, generation ->
            check(trip.historyGeneration == generation) { "Trip history changed. Select it again." }
            val endPadding = if (hideTripEnds) 120_000L else 0L
            val from = trip.startedAt + endPadding
            val until = trip.endedAt - endPadding
            val formatter = DateTimeFormatter.ISO_INSTANT
            var written = 0
            var previousTimestamp: Long? = null
            // Buffer the thousands of small XML writes before sending them to a
            // potentially slow Android cloud document provider.
            val writer = BufferedWriter(OutputStreamWriter(output, StandardCharsets.UTF_8), 32 * 1024)
            val pointLine = StringBuilder(160)
            writer.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            writer.write("<gpx version=\"1.1\" creator=\"Road Conquest\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n<trk><name>Road Conquest drive</name>\n")
            snapshot.rawQuery(
                "SELECT latitude, longitude, timestamp_ms FROM track_points WHERE id BETWEEN ? AND ? " +
                    "AND timestamp_ms BETWEEN ? AND ? ORDER BY id ASC",
                arrayOf(trip.firstId.toString(), trip.lastId.toString(), from.toString(), until.toString())
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
                    pointLine.setLength(0)
                    pointLine.append("<trkpt lat=\"").append(lat)
                        .append("\" lon=\"").append(lon).append("\"><time>")
                        .append(formatter.format(Instant.ofEpochMilli(timestamp)))
                        .append("</time></trkpt>\n")
                    writer.write(pointLine.toString())
                    previousTimestamp = timestamp
                    written++
                }
            }
            if (previousTimestamp != null) writer.write("</trkseg>\n")
            writer.write("</trk></gpx>\n")
            check(trip.historyGeneration == helper.historyGeneration) { "Trip history changed during export" }
            writer.flush()
            // A slow cloud provider may finish the flush after local history
            // was reset. Treat that as a failed export so the caller removes
            // the newly created stale route document.
            check(trip.historyGeneration == helper.historyGeneration) { "Trip history changed during export" }
            written
        }
    }

    /** Read a bounded route sample, always retaining the first and last points. */
    fun replay(trip: SavedTrip, maxPoints: Int = 2500): List<SavedTripPoint> =
        withHistorySnapshot { db, generation ->
            require(maxPoints in 2..5000)
            if (trip.historyGeneration != generation) return@withHistorySnapshot emptyList()
            val bounds = arrayOf(trip.firstId.toString(), trip.lastId.toString())
            // recentTrips() already counted these exact IDs under the same
            // history generation. New GPS rows append beyond lastId; a restore
            // invalidates generation above. Avoid recounting a long trip on replay.
            val stride = ceil(trip.pointCount.toDouble() / maxPoints).toInt().coerceAtLeast(1)
            val selected = ArrayList<SavedTripPoint>(minOf(maxPoints, trip.pointCount))
            var position = 0L
            var previousTime: Long? = null
            var gapSinceSample = false
            var lastLatitude = 0.0
            var lastLongitude = 0.0
            var lastTimestamp = 0L
            var lastWasSelected = false
            db.rawQuery(
                "SELECT latitude, longitude, timestamp_ms FROM track_points " +
                    "WHERE id BETWEEN ? AND ? ORDER BY id ASC", bounds
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val time = cursor.getLong(2)
                    previousTime?.let { if (time <= it || time - it > 30_000L) gapSinceSample = true }
                    lastLatitude = cursor.getDouble(0)
                    lastLongitude = cursor.getDouble(1)
                    lastTimestamp = time
                    lastWasSelected = position % stride == 0L && selected.size < maxPoints - 1
                    // An entire drive can contain thousands of raw fixes, but replay
                    // uses only the downsampled fixes and final endpoint. Allocate
                    // route-point objects only when one will actually be displayed.
                    if (lastWasSelected) {
                        selected += SavedTripPoint(lastLatitude, lastLongitude, time, gapSinceSample)
                        gapSinceSample = false
                    }
                    previousTime = time
                    position++
                }
            }
            // Preserve the exact terminal fix without bridging an unrecorded gap
            // in fixes skipped by the downsampler.
            if (position > 0L && !lastWasSelected) {
                val endpoint = SavedTripPoint(lastLatitude, lastLongitude, lastTimestamp, gapSinceSample)
                if (selected.lastOrNull() != endpoint) selected += endpoint
            }
            if (generation == helper.historyGeneration) selected else emptyList()
        }

    companion object {
        internal const val RECENT_TRIP_PAGE_SIZE = 512
        private const val MAX_RECENT_TRIP_PAGE_SIZE = 8192
        internal const val TRIP_GAP_MS = 15 * 60_000L
        internal fun isTripBoundary(lastTime: Long, olderTime: Long): Boolean =
            olderTime > lastTime || lastTime - olderTime >= TRIP_GAP_MS
    }
}
