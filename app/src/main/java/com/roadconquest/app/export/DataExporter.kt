package com.roadconquest.app.export

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.roadconquest.app.BuildConfig
import com.roadconquest.app.data.DataSummary
import com.roadconquest.app.data.TrackingRepository
import com.roadconquest.app.util.Prefs
import org.json.JSONObject
import java.io.OutputStream
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object DataExporter {
    private const val SNAPSHOT_PREFIX = "roadconquest-export-"
    private const val SNAPSHOT_SUFFIX = ".db"
    private val snapshotLock = Any()

    /** Restore takes this before historyLock, matching the ordinary export lock order. */
    internal fun <T> withSnapshotLock(block: () -> T): T = synchronized(snapshotLock) { block() }

    fun writeZip(context: Context, repository: TrackingRepository, output: OutputStream): Unit =
        synchronized(snapshotLock) {
            // A killed process cannot execute the previous export's finally block. Remove any
            // orphaned private SQLite snapshot before copying current history into a new one.
            clearTemporarySnapshotsLocked(context)
            val snapshotFile = File.createTempFile(SNAPSHOT_PREFIX, SNAPSHOT_SUFFIX, context.cacheDir)
            try {
                SQLiteDatabase.openOrCreateDatabase(snapshotFile, null).use { snapshot ->
                    repository.copyExportSnapshot(snapshot)
                    val summary = TrackingRepository.summaryOf(snapshot)
                    BoundedZipOutputStream(output.buffered()).use { zip ->
                        val points = writeTrackPoints(snapshot, zip)
                        writeSkippedDrivingFixes(snapshot, zip)
                        val roadCount = writeRoads(snapshot, zip)
                        val roadVisitCount = writeRoadVisits(snapshot, zip)
                        val exploredCount = writeExploredPlaces(snapshot, zip)
                        val gridCount = writeExploredGrid(snapshot, zip)
                        val progression = writeProgression(snapshot, zip)
                        writeMetadata(context, summary.copy(trackPointCount = points.trackPointCount,
                            roadSegmentCount = roadCount, firstTrackAt = points.firstTrackAt, lastTrackAt = points.lastTrackAt),
                            exploredCount, gridCount, roadVisitCount, progression, zip)
                    }
                }
            } finally {
                SQLiteDatabase.deleteDatabase(snapshotFile)
            }
        }

    /** Enforce restore's expanded-byte budget while streaming, before compressing oversized data. */
    internal class BoundedZipOutputStream(output: OutputStream, private val expandedLimit: Long = DataImporter.MAX_BYTES) :
        ZipOutputStream(output) {
        private var expandedBytes = 0L

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            if (length.toLong() > expandedLimit - expandedBytes) throw IOException("Expanded backup exceeds 512 MB")
            super.write(buffer, offset, length)
            expandedBytes += length
        }
    }

    /** Delete private snapshots left behind by an interrupted export, including SQLite sidecars. */
    fun clearTemporarySnapshots(context: Context) = synchronized(snapshotLock) {
        clearTemporarySnapshotsLocked(context)
    }

    private fun clearTemporarySnapshotsLocked(context: Context) {
        val cache = context.applicationContext.cacheDir
        val snapshots = cache.listFiles().orEmpty()
        snapshots.filter { it.name.startsWith(SNAPSHOT_PREFIX) && it.name.endsWith(SNAPSHOT_SUFFIX) }
            .forEach { SQLiteDatabase.deleteDatabase(it) }
        // deleteDatabase removes normal -wal/-shm files with the base DB. Sweep by prefix too
        // in case process death happened between sidecar creation and the base-file flush.
        snapshots.filter { it.name.startsWith(SNAPSHOT_PREFIX) }.forEach { it.delete() }
    }

    /** Write each CSV with the same buffered flush and ZIP entry boundaries as before. */
    private inline fun writeCsvEntry(zip: ZipOutputStream, filename: String, write: (java.io.BufferedWriter) -> Unit) {
        zip.putNextEntry(ZipEntry(filename))
        zip.writer(Charsets.UTF_8).buffered().let { writer ->
            write(writer)
            writer.flush()
        }
        zip.closeEntry()
    }

    private fun writeProgressionRow(writer: java.io.BufferedWriter, key: String, amount: Long, at: Long) {
        writer.write(CsvUtil.escape(key))
        writer.write(','.code)
        writer.write(CsvUtil.escape(amount.toString()))
        writer.write(','.code)
        writer.write(CsvUtil.escape(Instant.ofEpochMilli(at).toString()))
        writer.write("\n")
    }

    private fun writeMetadata(
        context: Context,
        summary: DataSummary,
        exploredCount: Long,
        gridCount: Long,
        roadVisitCount: Long,
        progression: ProgressionExportSummary,
        zip: ZipOutputStream
    ) {
        val metadata = JSONObject()
            .put("app", "Road Conquest")
            .put("app_version", BuildConfig.VERSION_NAME)
            .put("app_version_code", BuildConfig.VERSION_CODE)
            .put("schema_version", 12)
            .put("explored_place_count", exploredCount)
            .put("explored_mile_grid_count", gridCount)
            .put("road_visit_count", roadVisitCount)
            .put("points_balance", progression.balance)
            .put("lifetime_points_earned", progression.earned)
            .put("points_spent", progression.spent)
            .put("towns_visited", progression.towns)
            .put("states_regions_visited", progression.states)
            .put("countries_visited", progression.countries)
            .put("ads_watched", progression.adsWatched)
            .put("lowest_battery_percent", progression.lowestBatteryPercent ?: JSONObject.NULL)
            .put("pending_place_candidates", progression.pendingCandidates)
            .put("car_style", Prefs.carStyle(context))
            .put("car_color", Prefs.carColor(context))
            .put("road_color", Prefs.roadColor(context))
            .put("gold_ui_enabled", Prefs.isGoldUiEnabled(context))
            .put("exported_at", Instant.now().toString())
            .put("manual_only", Prefs.isManualOnly(context))
            .put("map_mode", Prefs.mapMode(context).name)
            .put("ui_theme", Prefs.uiTheme(context).name)
            .put("fog_enabled", Prefs.isFogEnabled(context))
            .put("distance_meters", summary.distanceMeters)
            .put("roads_unlocked_count", summary.roadsUnlockedCount)
            .put("track_point_count", summary.trackPointCount)
            .put("road_segment_count", summary.roadSegmentCount)
            .put("first_track_at", summary.firstTrackAt?.let { Instant.ofEpochMilli(it).toString() } ?: JSONObject.NULL)
            .put("last_track_at", summary.lastTrackAt?.let { Instant.ofEpochMilli(it).toString() } ?: JSONObject.NULL)

        zip.putNextEntry(ZipEntry("metadata.json"))
        zip.write(metadata.toString(2).toByteArray())
        zip.closeEntry()
    }

    private fun writeTrackPoints(database: SQLiteDatabase, zip: ZipOutputStream): DataSummary {
        var count = 0L
        var firstTime: Long? = null
        var lastTime: Long? = null
        writeCsvEntry(zip, "track_points.csv") { writer ->
            writer.write("id,latitude,longitude,accuracy_m,speed_mps,bearing_deg,timestamp_utc,matched,next_match_attempt_utc,distance_m\n")
            database.query(
                "track_points",
                arrayOf("id", "latitude", "longitude", "accuracy_m", "speed_mps", "bearing_deg", "timestamp_ms", "matched", "next_match_attempt_ms", "distance_m"),
                null, null, null, null,
                "timestamp_ms ASC"
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val timestamp = cursor.getLong(6)
                    if (firstTime == null) firstTime = timestamp
                    lastTime = timestamp
                    count++
                    writer.write(cursor.getLong(0).toString())
                    writer.write(','.code)
                    writer.write(cursor.getDouble(1).toString())
                    writer.write(','.code)
                    writer.write(cursor.getDouble(2).toString())
                    writer.write(','.code)
                    writer.write(cursor.getFloat(3).toString())
                    writer.write(','.code)
                    writer.write(cursor.getFloat(4).toString())
                    writer.write(','.code)
                    writer.write(cursor.getFloat(5).toString())
                    writer.write(','.code)
                    writer.write(Instant.ofEpochMilli(timestamp).toString())
                    writer.write(','.code)
                    writer.write(if (cursor.getInt(7) != 0) "true" else "false")
                    writer.write(','.code)
                    val retryAt = cursor.getLong(8)
                    if (retryAt > 0L) writer.write(Instant.ofEpochMilli(retryAt).toString())
                    writer.write(','.code)
                    writer.write(cursor.getDouble(9).toString())
                    writer.write("\n")
                }
            }
        }
        return DataSummary(count, 0, firstTime, lastTime)
    }

    /** Offline GPS evidence is included in explicit user exports, never network requests. */
    private fun writeSkippedDrivingFixes(database: SQLiteDatabase, zip: ZipOutputStream) {
        writeCsvEntry(zip, "skipped_driving_fixes.csv") { writer ->
            writer.write("id,latitude,longitude,accuracy_m,speed_mps,bearing_deg,timestamp_utc\n")
            database.query(
                "skipped_driving_fixes",
                arrayOf("id", "latitude", "longitude", "accuracy_m", "speed_mps", "bearing_deg", "timestamp_ms"),
                null, null, null, null, "timestamp_ms ASC, id ASC"
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    writer.write(cursor.getLong(0).toString())
                    writer.write(','.code)
                    writer.write(cursor.getDouble(1).toString())
                    writer.write(','.code)
                    writer.write(cursor.getDouble(2).toString())
                    writer.write(','.code)
                    writer.write(cursor.getFloat(3).toString())
                    writer.write(','.code)
                    writer.write(cursor.getFloat(4).toString())
                    writer.write(','.code)
                    writer.write(cursor.getFloat(5).toString())
                    writer.write(','.code)
                    writer.write(Instant.ofEpochMilli(cursor.getLong(6)).toString())
                    writer.write("\n")
                }
            }
        }
    }

    private fun writeExploredPlaces(database: SQLiteDatabase, zip: ZipOutputStream): Long {
        var count = 0L
        writeCsvEntry(zip, "explored_places.csv") { writer ->
            writer.write("latitude,longitude\n")
            database.query("explored_places", arrayOf("latitude", "longitude"),
                null, null, null, null, "cell_x,cell_y").use { cursor ->
                while (cursor.moveToNext()) {
                    writer.write("${cursor.getDouble(0)},${cursor.getDouble(1)}\n")
                    count++
                }
            }
        }
        return count
    }

    private fun writeExploredGrid(database: SQLiteDatabase, zip: ZipOutputStream): Long {
        var count = 0L
        writeCsvEntry(zip, "explored_grid.csv") { writer ->
            writer.write("row,column,latitude,longitude,first_unlocked_utc\n")
            database.query("explored_grid", arrayOf("grid_row", "grid_col", "latitude", "longitude", "first_unlocked_at"),
                null, null, null, null, "grid_row,grid_col").use { cursor ->
                while (cursor.moveToNext()) {
                    val discoveredAt = cursor.getLong(4).takeIf { it > 0L }
                    ?.let { Instant.ofEpochMilli(it).toString() }.orEmpty()
                    writer.write("${cursor.getInt(0)},${cursor.getInt(1)},${cursor.getDouble(2)},${cursor.getDouble(3)},$discoveredAt\n")
                    count++
                }
            }
        }
        return count
    }

    private fun writeRoadVisits(database: SQLiteDatabase, zip: ZipOutputStream): Long {
        var count = 0L
        writeCsvEntry(zip, "road_visits.csv") { writer ->
            writer.write("segment_id,started_at_utc,ended_at_utc\n")
            database.query(
                "road_visits",
                arrayOf("segment_id", "started_at", "ended_at"),
                null, null, null, null,
                "started_at ASC"
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    count++
                    val fields = listOf(
                        cursor.getString(0),
                        Instant.ofEpochMilli(cursor.getLong(1)).toString(),
                        Instant.ofEpochMilli(cursor.getLong(2)).toString()
                    )
                    CsvUtil.writeRow(writer, fields)
                }
            }
        }
        return count
    }

    private data class ProgressionExportSummary(
        val earned: Long,
        val spent: Long,
        val balance: Long,
        val towns: Long,
        val states: Long,
        val countries: Long,
        val adsWatched: Long,
        val lowestBatteryPercent: Int?,
        val pendingCandidates: Long
    )

    private fun writeProgression(database: SQLiteDatabase, zip: ZipOutputStream): ProgressionExportSummary {
        writeCsvEntry(zip, "visited_places.csv") { writer ->
            writer.write("kind,place_key,display_name,parent_name,country_name,first_visited_utc,latitude,longitude\n")
            database.query(
                "visited_places",
                arrayOf("kind", "place_key", "display_name", "parent_name", "country_name",
                    "first_visited_at", "latitude", "longitude"),
                null, null, null, null, "first_visited_at ASC, kind, place_key"
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val fields = listOf(
                        cursor.getString(0), cursor.getString(1), cursor.getString(2),
                        cursor.getString(3), cursor.getString(4),
                        Instant.ofEpochMilli(cursor.getLong(5)).toString(),
                        cursor.getDouble(6).toString(), cursor.getDouble(7).toString()
                    )
                    CsvUtil.writeRow(writer, fields)
                }
            }
        }

        // The zero-point starter places remain in visited_places.csv for overlays/history, but
        // discovery totals match the app: only places that actually earned a place reward count.
        val rewardedCounts = mutableMapOf<String, Long>()
        database.rawQuery(
            """SELECT v.kind, COUNT(*)
               FROM visited_places v
               JOIN progression_rewards r
                 ON r.reward_key = 'place:' || lower(v.kind) || ':' || v.place_key
               GROUP BY v.kind""",
            null
        ).use { cursor ->
            while (cursor.moveToNext()) rewardedCounts[cursor.getString(0)] = cursor.getLong(1)
        }
        val towns = rewardedCounts["TOWN"] ?: 0L
        val states = rewardedCounts["STATE"] ?: 0L
        val countries = rewardedCounts["COUNTRY"] ?: 0L

        var pending = 0L
        writeCsvEntry(zip, "place_candidates.csv") { writer ->
            writer.write("cell_x,cell_y,latitude,longitude,first_seen_utc,attempts,next_attempt_utc\n")
            database.query(
                "place_candidates",
                arrayOf("cell_x", "cell_y", "latitude", "longitude", "first_seen_at", "attempts", "next_attempt_ms"),
                null, null, null, null, "first_seen_at ASC"
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    pending++
                    val retry = cursor.getLong(6)
                    val fields = listOf(
                        cursor.getLong(0).toString(), cursor.getLong(1).toString(),
                        cursor.getDouble(2).toString(), cursor.getDouble(3).toString(),
                        Instant.ofEpochMilli(cursor.getLong(4)).toString(),
                        cursor.getInt(5).toString(),
                        if (retry > 0L) Instant.ofEpochMilli(retry).toString() else ""
                    )
                    CsvUtil.writeRow(writer, fields)
                }
            }
        }

        var earned = 0L
        writeCsvEntry(zip, "progression_rewards.csv") { writer ->
            writer.write("reward_key,points,awarded_at_utc\n")
            database.query(
                "progression_rewards",
                arrayOf("reward_key", "points", "awarded_at"),
                null, null, null, null, "awarded_at ASC, reward_key"
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    earned += cursor.getLong(1)
                    writeProgressionRow(writer, cursor.getString(0), cursor.getLong(1), cursor.getLong(2))
                }
            }
        }

        var spent = 0L
        writeCsvEntry(zip, "progression_purchases.csv") { writer ->
            writer.write("item_id,points_spent,purchased_at_utc\n")
            database.query(
                "progression_purchases",
                arrayOf("item_id", "points_spent", "purchased_at"),
                null, null, null, null, "purchased_at ASC, item_id"
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    spent += cursor.getLong(1)
                    writeProgressionRow(writer, cursor.getString(0), cursor.getLong(1), cursor.getLong(2))
                }
            }
        }

        var ads = 0L
        var lowestBattery: Int? = null
        writeCsvEntry(zip, "progression_counters.csv") { writer ->
            writer.write("counter_key,value\n")
            database.query(
                "progression_counters",
                arrayOf("counter_key", "value"),
                null, null, null, null, "counter_key"
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val key = cursor.getString(0)
                    val value = cursor.getLong(1)
                    if (key == "ads_watched") ads = value
                    if (key == "lowest_battery_percent") lowestBattery = value.toInt()
                    writer.write(CsvUtil.escape(key) + "," + value + "\n")
                }
            }
        }

        return ProgressionExportSummary(
            earned = earned,
            spent = spent,
            balance = (earned - spent).coerceAtLeast(0L),
            towns = towns,
            states = states,
            countries = countries,
            adsWatched = ads,
            lowestBatteryPercent = lowestBattery,
            pendingCandidates = pending
        )
    }

    private fun writeRoads(database: SQLiteDatabase, zip: ZipOutputStream): Long {
        var count = 0L
        writeCsvEntry(zip, "roads.csv") { writer ->
            writer.write("segment_id,road_group_id,name,first_unlocked_utc,last_driven_utc,times_driven,times_driven_exact,coordinates_json\n")
            database.query(
                "roads",
                arrayOf("segment_id", "road_group_id", "name", "first_unlocked_at", "last_driven_at", "drive_count", "drive_count_exact", "geometry_json"),
                null, null, null, null,
                "first_unlocked_at ASC"
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    count++
                    val segmentId = cursor.getString(0)
                    val fields = listOf(
                        segmentId,
                        cursor.getString(1).ifBlank { segmentId },
                        cursor.getString(2),
                        Instant.ofEpochMilli(cursor.getLong(3)).toString(),
                        Instant.ofEpochMilli(cursor.getLong(4)).toString(),
                        cursor.getInt(5).toString(),
                        (cursor.getInt(6) != 0).toString(),
                        cursor.getString(7)
                    )
                    CsvUtil.writeRow(writer, fields)
                }
            }
        }
        return count
    }

}
