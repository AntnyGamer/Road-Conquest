package com.roadconquest.app.export

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.roadconquest.app.TrackingService
import com.roadconquest.app.achievements.Achievements
import com.roadconquest.app.data.AppDatabase
import com.roadconquest.app.data.DataSummary
import com.roadconquest.app.data.TrackingRepository
import com.roadconquest.app.map.FogGrid
import com.roadconquest.app.map.MapMode
import com.roadconquest.app.map.PlaceOverlayCache
import com.roadconquest.app.progression.Cosmetics
import com.roadconquest.app.progression.ShopCatalog
import com.roadconquest.app.util.Prefs
import com.roadconquest.app.util.UiTheme
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.zip.CRC32
import java.util.zip.ZipFile

/** Imports only data into our own schema. Never executes SQL or opens a database from a ZIP. */
object DataImporter {
    private const val MAX_BYTES = 512L * 1024 * 1024
    private const val PREFIX = "roadconquest-import-"
    internal val operationLock = Any()
    private var temporaryFilesInitialized = false
    private val tables = listOf("track_points", "roads", "road_visits", "explored_places", "explored_grid",
        "visited_places", "place_candidates", "progression_rewards", "progression_purchases", "progression_counters")

    class Prepared internal constructor(
        internal val file: File,
        val metadata: JSONObject,
        val summary: DataSummary,
        internal val generation: Long
    ) : Closeable {
        override fun close() { SQLiteDatabase.deleteDatabase(file) }
    }

    fun previousBackup(context: Context) = File(context.applicationContext.filesDir, "before-restore.zip")

    /** Call only on process startup, before an import screen can be active. */
    @Synchronized
    fun clearTemporaryFiles(context: Context) {
        if (temporaryFilesInitialized) return
        temporaryFilesInitialized = true
        context.cacheDir.listFiles().orEmpty().filter { it.name.startsWith(PREFIX) }.forEach {
            if (it.name.endsWith(".db")) SQLiteDatabase.deleteDatabase(it) else it.delete()
        }
    }

    fun prepare(context: Context, input: InputStream): Prepared {
        clearTemporaryFiles(context)
        val helper = AppDatabase.get(context)
        val generation = synchronized(helper.historyLock) { helper.historyGeneration }
        val zipPath = File.createTempFile(PREFIX, ".zip", context.cacheDir)
        val stage = File.createTempFile(PREFIX, ".db", context.cacheDir)
        var success = false
        try {
            zipPath.outputStream().use { output ->
                val buffer = ByteArray(32 * 1024)
                var bytes = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    bytes += read
                    require(bytes <= MAX_BYTES) { "Backup exceeds 512 MB" }
                    check(!Thread.currentThread().isInterrupted) { "Import cancelled" }
                    output.write(buffer, 0, read)
                }
            }
            ZipFile(zipPath).use { zip ->
                val entries = zip.entries().toList()
                val expected = tables.map { "$it.csv" }.toSet() + "metadata.json"
                require(entries.size == expected.size && entries.map { it.name }.toSet() == expected &&
                    entries.none { it.isDirectory || it.size < 0 }) { "Not a complete Road Conquest backup" }
                var total = 0L
                fun readEntry(name: String, consume: (InputStream) -> Unit) {
                    val entry = zip.getEntry(name)
                    val crc = CRC32()
                    var size = 0L
                    val limit = if (name == "metadata.json") 64L * 1024 else MAX_BYTES
                    require(entry.size <= limit) { "Backup entry is too large" }
                    zip.getInputStream(entry).use { raw ->
                        val bounded = object : FilterInputStream(raw) {
                            override fun read(): Int {
                                val byte = super.read()
                                if (byte >= 0) account(byteArrayOf(byte.toByte()), 0, 1)
                                return byte
                            }
                            override fun read(b: ByteArray, off: Int, len: Int): Int {
                                val count = super.read(b, off, len)
                                if (count > 0) account(b, off, count)
                                return count
                            }
                            private fun account(b: ByteArray, off: Int, count: Int) {
                                size += count; total += count
                                require(size <= limit && total <= MAX_BYTES) { "Expanded backup exceeds 512 MB" }
                                check(!Thread.currentThread().isInterrupted) { "Import cancelled" }
                                crc.update(b, off, count)
                            }
                        }
                        consume(bounded)
                    }
                    require(size == entry.size && crc.value == entry.crc) { "Backup checksum failed" }
                }
                var metadata = JSONObject()
                readEntry("metadata.json") { metadata = JSONObject(Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(it.readBytes())).toString()) }
                require(metadata.getString("app") == "Road Conquest" && metadata.getInt("schema_version") in 9..11) {
                    "Unsupported backup format; use a current Road Conquest export"
                }
                val schema = metadata.getInt("schema_version")
                val exportedAt = Instant.parse(metadata.getString("exported_at")).toEpochMilli()
                require(exportedAt in 0..System.currentTimeMillis() + 86_400_000L) { "Invalid backup date" }
                SQLiteDatabase.openOrCreateDatabase(stage, null).use { db ->
                    helper.onCreate(db)
                    db.beginTransaction()
                    try {
                        for (table in tables) readEntry("$table.csv") { stream ->
                            val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                            val csv = CsvReader(java.io.InputStreamReader(stream, decoder).buffered())
                            val header = csv.next() ?: error("Missing $table header")
                            val spec = if (schema < 11 && table == "explored_grid")
                                legacyGridColumns else columns.getValue(table)
                            require(header == spec.map { it.first }) { "Invalid $table columns" }
                            var rows = 0L
                            while (true) {
                                val fields = csv.next() ?: break
                                require(fields.size == spec.size && ++rows <= 10_000_000) { "Invalid $table row" }
                                val row = ContentValues()
                                spec.forEachIndexed { index, (_, destination, type) ->
                                    val field = CsvUtil.unescape(fields[index], schema)
                                    when (type) {
                                        's' -> { require(field.length <= 4096); row.put(destination, field) }
                                        't' -> row.put(destination, if (field.isEmpty()) 0L else Instant.parse(field).toEpochMilli().also {
                                            require(it in 0..exportedAt + 86_400_000L) { "Invalid timestamp" }
                                        })
                                        'i' -> row.put(destination, field.toLong())
                                        'd' -> row.put(destination, field.toDouble().also { require(it.isFinite()) { "Invalid number" } })
                                        'b' -> row.put(destination, when (field) { "true" -> 1; "false" -> 0; else -> error("Invalid boolean") })
                                        'j' -> { require(field.length <= 1_000_000); row.put(destination, field) }
                                    }
                                }
                                validateRow(table, row)
                                db.insertOrThrow(table, null, row)
                            }
                        }
                        db.rawQuery("PRAGMA foreign_key_check", null).use { require(!it.moveToFirst()) { "Backup references missing roads" } }
                        val summary = TrackingRepository.summaryOf(db)
                        val counts = mapOf("track_point_count" to summary.trackPointCount, "road_segment_count" to summary.roadSegmentCount,
                            "roads_unlocked_count" to summary.roadsUnlockedCount, "explored_place_count" to count(db, "explored_places"),
                            "explored_mile_grid_count" to count(db, "explored_grid"), "road_visit_count" to count(db, "road_visits"),
                            "pending_place_candidates" to count(db, "place_candidates"))
                        counts.forEach { (key, value) -> require(metadata.getLong(key) == value) { "Backup $key does not match its data" } }
                        val earned = sum(db, "progression_rewards", "points")
                        val spent = sum(db, "progression_purchases", "points_spent")
                        require(spent <= earned && earned <= 1_000_000_000_000_000L &&
                            metadata.getLong("lifetime_points_earned") == earned && metadata.getLong("points_spent") == spent &&
                            metadata.getLong("points_balance") == earned - spent) { "Inconsistent backup points" }
                        require(kotlin.math.abs(summary.distanceMeters - metadata.getDouble("distance_meters")) <= 0.01) { "Inconsistent backup mileage" }
                        db.setTransactionSuccessful()
                        success = true
                        return Prepared(stage, metadata, summary, generation)
                    } finally { db.endTransaction() }
                }
            }
        } finally {
            zipPath.delete()
            if (!success) SQLiteDatabase.deleteDatabase(stage)
        }
    }

    /** All ten tables change in one transaction. Old asynchronous writers become no-ops. */
    fun restore(context: Context, prepared: Prepared) = synchronized(operationLock) {
        val app = context.applicationContext
        check(!Prefs.isDeviceDataDeletionPending(app)) { "Device data deletion is in progress" }
        val helper = AppDatabase.get(app)
        val temporaryBackup = File(app.filesDir, "before-restore.tmp.zip")
        Prefs.setManualOnly(app, true)
        Prefs.setTrackingPaused(app, false)
        Prefs.setDriveVerificationEnabled(app, false)
        app.stopService(Intent(app, TrackingService::class.java))
        DataExporter.withSnapshotLock { synchronized(helper.historyLock) {
            check(helper.historyGeneration == prepared.generation && !Prefs.isDeviceDataDeletionPending(app)) {
                "Device data changed; select the backup again"
            }
            try {
                temporaryBackup.outputStream().use { DataExporter.writeZip(app, TrackingRepository(app), it) }
                java.io.FileOutputStream(temporaryBackup, true).use { it.fd.sync() }
                // Both paths are in filesDir. Require an atomic replacement so
                // the old recovery copy survives any failed move. File.renameTo
                // cannot replace an existing target on Windows test hosts.
                Files.move(temporaryBackup.toPath(), previousBackup(app).toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } finally { temporaryBackup.delete() }
            val live = helper.writableDatabase
            SQLiteDatabase.openDatabase(prepared.file.path, null, SQLiteDatabase.OPEN_READONLY).use { source ->
                live.beginTransaction()
                try {
                    // Child rows first for deletion, parents first for insertion.
                    (listOf("road_visits") + tables.filter { it != "road_visits" }).forEach { live.delete(it, null, null) }
                    // This is a complete history replacement. Rebuild the GPS ID
                    // sequence from the imported rows so an old exhausted counter
                    // cannot prevent the user from recording their next drive.
                    live.delete("sqlite_sequence", "name = ?", arrayOf("track_points"))
                    for (table in tables) source.query(table, null, null, null, null, null, null).use { cursor ->
                        while (cursor.moveToNext()) {
                            check(!Thread.currentThread().isInterrupted) { "Restore cancelled" }
                            val values = ContentValues()
                            cursor.columnNames.forEachIndexed { i, column -> when (cursor.getType(i)) {
                                Cursor.FIELD_TYPE_INTEGER -> values.put(column, cursor.getLong(i))
                                Cursor.FIELD_TYPE_FLOAT -> values.put(column, cursor.getDouble(i))
                                Cursor.FIELD_TYPE_STRING -> values.put(column, cursor.getString(i))
                                else -> error("Unsupported backup value")
                            } }
                            live.insertOrThrow(table, null, values)
                        }
                    }
                    live.setTransactionSuccessful()
                } finally { live.endTransaction() }
            }
            helper.historyGeneration++
            helper.trackSummaryCache = null; helper.roadSummaryCache = null; helper.lastTrackPointCache = null
        } }
        // A cosmetic/icon failure after COMMIT must not claim that data was rolled back.
        // operationLock serializes reset/restore without holding historyLock while
        // taking Achievements' preference lock (its reads take these in reverse order).
        if (!Prefs.isDeviceDataDeletionPending(app) && helper.historyGeneration == prepared.generation + 1) {
            runCatching {
                Achievements.reset(app)
                PlaceOverlayCache.clear(app)
                Prefs.resetCosmetics(app)
                val meta = prepared.metadata
                MapMode.entries.firstOrNull { it.name == meta.optString("map_mode") }?.let { Prefs.setMapMode(app, it) }
                UiTheme.entries.firstOrNull { it.name == meta.optString("ui_theme") }?.let { Prefs.setUiTheme(app, it) }
                Prefs.setFogEnabled(app, meta.optBoolean("fog_enabled", true))
                val selected = setOf("car_style_${meta.optString("car_style")}", "car_color_${meta.optString("car_color")}",
                    "road_color_${meta.optString("road_color")}", "ui_standard", if (meta.optBoolean("gold_ui_enabled")) "ui_gold" else "ui_standard")
                ShopCatalog.items.filter { it.id in selected }.forEach { Cosmetics.equip(app, it) }
            }.onFailure { android.util.Log.w("RoadConquest", "Progress restored; appearance could not be fully restored", it) }
        }
    }

    private fun count(db: SQLiteDatabase, table: String) = db.rawQuery("SELECT COUNT(*) FROM $table", null).use { it.moveToFirst(); it.getLong(0) }
    private fun sum(db: SQLiteDatabase, table: String, column: String) = db.rawQuery("SELECT COALESCE(SUM($column),0) FROM $table", null).use { it.moveToFirst(); it.getLong(0) }

    private fun validateRow(table: String, v: ContentValues) {
        fun coordinates() {
            require(v.getAsDouble("latitude") in -90.0..90.0 && v.getAsDouble("longitude") in -180.0..180.0) { "Invalid location" }
        }
        fun nonempty(key: String) { require(!v.getAsString(key).isNullOrEmpty()) { "Missing $key" } }
        when (table) {
            "track_points" -> {
                coordinates()
                // Leave ample AUTOINCREMENT headroom for future driving fixes.
                require(v.getAsLong("id") in 1..1_000_000_000_000_000L && v.getAsDouble("accuracy_m") in 0.0..100_000.0 &&
                    v.getAsDouble("speed_mps") in 0.0..1000.0 && v.getAsDouble("bearing_deg") in 0.0..360.0 &&
                    v.getAsDouble("distance_m") in 0.0..10_000_000.0) { "Invalid driving fix" }
            }
            "roads" -> {
                nonempty("segment_id"); nonempty("road_group_id")
                require(v.getAsLong("last_driven_at") >= v.getAsLong("first_unlocked_at") && v.getAsLong("drive_count") in 1..Int.MAX_VALUE.toLong())
                val geometry = JSONArray(v.getAsString("geometry_json"))
                require(geometry.length() in 2..50_000) { "Invalid road geometry" }
                val longitudes = DoubleArray(geometry.length())
                var minLat = 90.0; var maxLat = -90.0
                for (i in 0 until geometry.length()) {
                    val point = geometry.getJSONArray(i)
                    require(point.length() == 2)
                    val lon = point.getDouble(0); val lat = point.getDouble(1)
                    require(lon.isFinite() && lat.isFinite() && lon in -180.0..180.0 && lat in -90.0..90.0)
                    longitudes[i] = lon; minLat = minOf(minLat, lat); maxLat = maxOf(maxLat, lat)
                }
                longitudes.sort()
                var gap = longitudes.first() + 360 - longitudes.last()
                var west = longitudes.first(); var east = longitudes.last()
                for (i in 0 until longitudes.lastIndex) if (longitudes[i + 1] - longitudes[i] > gap) {
                    gap = longitudes[i + 1] - longitudes[i]; west = longitudes[i + 1]; east = longitudes[i]
                }
                v.put("min_lat", minLat); v.put("max_lat", maxLat); v.put("min_lon", west); v.put("max_lon", east)
            }
            "road_visits" -> { nonempty("segment_id"); require(v.getAsLong("ended_at") >= v.getAsLong("started_at")) }
            "explored_places" -> {
                coordinates()
                val key = TrackingRepository.exploredCellKey(v.getAsDouble("latitude"), v.getAsDouble("longitude")) ?: error("Invalid fog cell")
                v.put("cell_x", key shr 32); v.put("cell_y", key.toInt().toLong())
            }
            "explored_grid" -> {
                coordinates()
                val cell = FogGrid.cell(v.getAsDouble("latitude"), v.getAsDouble("longitude")) ?: error("Invalid fog cell")
                require(cell.row.toLong() == v.getAsLong("grid_row") && cell.column.toLong() == v.getAsLong("grid_col"))
            }
            "visited_places" -> { coordinates(); nonempty("place_key"); nonempty("display_name"); require(v.getAsString("kind") in setOf("TOWN", "STATE", "COUNTRY")) }
            "place_candidates" -> { coordinates(); require(v.getAsLong("attempts") in 0..Int.MAX_VALUE.toLong()) }
            "progression_rewards" -> { nonempty("reward_key"); require(v.getAsLong("points") in 1..1_000_000_000_000L) }
            "progression_purchases" -> { nonempty("item_id"); require(v.getAsLong("points_spent") in 0..1_000_000_000_000L) }
            "progression_counters" -> {
                nonempty("counter_key")
                require(v.getAsLong("value") in 0..1_000_000_000_000_000L) { "Invalid progression counter" }
                if (v.getAsString("counter_key") == "lowest_battery_percent") {
                    require(v.getAsLong("value") in 0..100) { "Invalid battery counter" }
                }
            }
        }
    }

    private fun spec(vararg definitions: String) = definitions.map { definition ->
        val parts = definition.split(':'); Triple(parts[0], parts[1], parts[2].single())
    }
    private val legacyGridColumns = spec(
        "row:grid_row:i", "column:grid_col:i", "latitude:latitude:d", "longitude:longitude:d"
    )
    private val columns = mapOf(
        "track_points" to spec("id:id:i", "latitude:latitude:d", "longitude:longitude:d", "accuracy_m:accuracy_m:d", "speed_mps:speed_mps:d", "bearing_deg:bearing_deg:d",
            "timestamp_utc:timestamp_ms:t", "matched:matched:b", "next_match_attempt_utc:next_match_attempt_ms:t", "distance_m:distance_m:d"),
        "roads" to spec("segment_id:segment_id:s", "road_group_id:road_group_id:s", "name:name:s", "first_unlocked_utc:first_unlocked_at:t", "last_driven_utc:last_driven_at:t",
            "times_driven:drive_count:i", "times_driven_exact:drive_count_exact:b", "coordinates_json:geometry_json:j"),
        "road_visits" to spec("segment_id:segment_id:s", "started_at_utc:started_at:t", "ended_at_utc:ended_at:t"),
        "explored_places" to spec("latitude:latitude:d", "longitude:longitude:d"),
        "explored_grid" to spec("row:grid_row:i", "column:grid_col:i", "latitude:latitude:d", "longitude:longitude:d", "first_unlocked_utc:first_unlocked_at:t"),
        "visited_places" to spec("kind:kind:s", "place_key:place_key:s", "display_name:display_name:s", "parent_name:parent_name:s", "country_name:country_name:s", "first_visited_utc:first_visited_at:t", "latitude:latitude:d", "longitude:longitude:d"),
        "place_candidates" to spec("cell_x:cell_x:i", "cell_y:cell_y:i", "latitude:latitude:d", "longitude:longitude:d", "first_seen_utc:first_seen_at:t", "attempts:attempts:i", "next_attempt_utc:next_attempt_ms:t"),
        "progression_rewards" to spec("reward_key:reward_key:s", "points:points:i", "awarded_at_utc:awarded_at:t"),
        "progression_purchases" to spec("item_id:item_id:s", "points_spent:points_spent:i", "purchased_at_utc:purchased_at:t"),
        "progression_counters" to spec("counter_key:counter_key:s", "value:value:i")
    )
}
