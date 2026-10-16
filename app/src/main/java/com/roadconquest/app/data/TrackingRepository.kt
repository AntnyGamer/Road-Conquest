package com.roadconquest.app.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.location.Location
import android.database.Cursor
import android.os.Build
import com.roadconquest.app.util.LocationProviders
import org.json.JSONArray
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.*

import com.roadconquest.app.map.FogGrid

class TrackingRepository(context: Context) {
    private val dbHelper = AppDatabase.get(context)
    private val historyGeneration = synchronized(dbHelper.historyLock) { dbHelper.historyGeneration }

    internal fun isHistoryCurrent(): Boolean = historyGeneration == dbHelper.historyGeneration

    /** A visited cell reveals fog only; it never enters driving history or road matching. */
    @Synchronized
    fun recordExploredPlace(location: Location): Boolean {
        val cellKey = exploredCellKey(location.latitude, location.longitude) ?: return false
        return recordExploredPlace(location, cellKey)
    }

    /** Same write path when the tracking service has already computed the exact 50 m cell. */
    @Synchronized
    internal fun recordExploredPlace(location: Location, cellKey: Long): Boolean {
        synchronized(dbHelper.historyLock) {
            if (historyGeneration != dbHelper.historyGeneration) return false
            if (location.isMock || !location.hasAccuracy() || location.accuracy !in 0.01f..25f ||
                !location.latitude.isFinite() || !location.longitude.isFinite() ||
                location.latitude !in -90.0..90.0 || location.longitude !in -180.0..180.0
            ) return false
            val x = (cellKey shr 32).toInt().toLong()
            val y = cellKey.toInt().toLong()
            val values = ContentValues().apply {
                put("cell_x", x); put("cell_y", y)
                put("latitude", Math.toDegrees(atan(sinh((y + 0.5) * EXPLORED_CELL_SIZE_M / WEB_MERCATOR_RADIUS_M))))
                put("longitude", Math.toDegrees((x + 0.5) * EXPLORED_CELL_SIZE_M / WEB_MERCATOR_RADIUS_M).coerceIn(-180.0, 180.0))
            }
            return dbHelper.writableDatabase.insertWithOnConflict(
                "explored_places", null, values, SQLiteDatabase.CONFLICT_IGNORE
            ) != -1L
        }
    }

    @Synchronized
    fun getExploredPlacesInBounds(north: Double, east: Double, south: Double, west: Double): DoubleArray {
        val longitudeSelection = if (east >= west) "longitude BETWEEN ? AND ?" else "(longitude >= ? OR longitude <= ?)"
        dbHelper.readableDatabase.query("explored_places", arrayOf("latitude", "longitude"),
            "latitude BETWEEN ? AND ? AND $longitudeSelection",
            arrayOf(south.toString(), north.toString(), west.toString(), east.toString()), null, null, null).use { cursor ->
            val coordinates = DoubleArray(cursor.count * 2)
            var index = 0
            while (cursor.moveToNext()) {
                coordinates[index++] = cursor.getDouble(0)
                coordinates[index++] = cursor.getDouble(1)
            }
            return coordinates
        }
    }

    /**
     * Authoritative persisted mile-cell unlock. This is independent of 50 m legacy
     * exploration samples, road matching, achievements, distance and verified scoring.
     */
    @Synchronized
    fun recordExploredGridCell(location: Location): Boolean {
        if (location.isMock || !location.hasAccuracy() || location.accuracy !in 0.01f..25f) return false
        val tile = FogGrid.cell(location.latitude, location.longitude) ?: return false
        synchronized(dbHelper.historyLock) {
            if (historyGeneration != dbHelper.historyGeneration) return false
            val center = FogGrid.center(tile)
            return dbHelper.writableDatabase.insertWithOnConflict(
                "explored_grid", null, ContentValues().apply {
                    put("grid_row", tile.row)
                    put("grid_col", tile.column)
                    put("latitude", center.first)
                    put("longitude", center.second)
                    put("first_unlocked_at", location.time.takeIf { it > 0L }
                        ?: System.currentTimeMillis())
                }, SQLiteDatabase.CONFLICT_IGNORE
            ) != -1L
        }
    }

    /** One indexed read for the nearby 5×5 grid, including date-line wrapping. */
    fun nearbyExploration(latitude: Double, longitude: Double): Pair<Int, Int> =
        synchronized(dbHelper.historyLock) {
            val origin = FogGrid.cell(latitude, longitude) ?: return@synchronized 0 to 0
            val cells = FogGrid.neighbors(origin)
            // Neighbors form complete rows, even when latitude clips the first
            // or last row. Keep wrapped columns as discrete values rather than
            // using BETWEEN, which would incorrectly include most of the world.
            val columns = cells.takeWhile { it.row == cells.first().row }.map { it.column }
            val placeholders = List(columns.size) { "?" }.joinToString(",")
            val args = (listOf(cells.first().row, cells.last().row) + columns)
                .map(Int::toString).toTypedArray()
            val explored = dbHelper.readableDatabase.rawQuery(
                "SELECT COUNT(*) FROM explored_grid WHERE grid_row BETWEEN ? AND ? " +
                    "AND grid_col IN ($placeholders)", args
            ).use { cursor ->
                check(cursor.moveToFirst())
                cursor.getInt(0)
            }
            explored to cells.size
        }

    /**
     * A live tracking badge concerns recent driving, not a permanently unresolved
     * GPS corner from months ago. The existing timestamp index bounds this lookup
     * without an extra index, network request, or scanning the entire history.
     */
    fun hasUnmatchedDrivingFixes(nowMillis: Long = System.currentTimeMillis()): Boolean =
        synchronized(dbHelper.historyLock) {
            dbHelper.readableDatabase.rawQuery(
                "SELECT 1 FROM track_points WHERE timestamp_ms BETWEEN ? AND ? " +
                    "AND matched = 0 LIMIT 1",
                arrayOf((nowMillis - 60 * 60_000L).toString(), nowMillis.toString())
            ).use { it.moveToFirst() }
        }

    /** Read the authoritative mile cells (as centers) with a padded viewport query. */
    @Synchronized
    fun getExploredGridInBounds(north: Double, east: Double, south: Double, west: Double): DoubleArray {
        val longitudeSelection = if (east >= west) "longitude BETWEEN ? AND ?" else
            "(longitude >= ? OR longitude <= ?)"
        dbHelper.readableDatabase.query(
            "explored_grid", arrayOf("latitude", "longitude"),
            "latitude BETWEEN ? AND ? AND $longitudeSelection",
            arrayOf(south.toString(), north.toString(), west.toString(), east.toString()),
            null, null, null
        ).use { cursor ->
            val result = DoubleArray(cursor.count * 2)
            var offset = 0
            while (cursor.moveToNext()) {
                result[offset++] = cursor.getDouble(0)
                result[offset++] = cursor.getDouble(1)
            }
            return result
        }
    }

    /** Preserve accepted driving evidence omitted from the compressed online matching queue. */
    @Synchronized
    fun recordSkippedDrivingFix(location: Location) {
        synchronized(dbHelper.historyLock) {
            if (historyGeneration != dbHelper.historyGeneration) return
            val values = ContentValues().apply {
                put("latitude", location.latitude)
                put("longitude", location.longitude)
                put("accuracy_m", location.accuracy)
                put("speed_mps", LocationProviders.reliableMeasuredSpeed(location))
                put("bearing_deg", if (location.hasBearing() && location.bearing.isFinite()) location.bearing else 0f)
                put("timestamp_ms", location.time.takeIf { it > 0L } ?: System.currentTimeMillis())
            }
            dbHelper.writableDatabase.insertOrThrow("skipped_driving_fixes", null, values)
        }
    }

    @Synchronized
    fun insertLocation(location: Location): Long {
        synchronized(dbHelper.historyLock) {
            if (historyGeneration != dbHelper.historyGeneration) return -1L
            val db = dbHelper.writableDatabase
            val delta = TrackInsertDelta()
            var persistedPoint: LastPoint? = null
            db.beginTransaction()
            val id = try {
                val previous = loadLastPoint(db)
                insertLocationInTransaction(db, location, previous, delta).also {
                    persistedPoint = previous
                    db.setTransactionSuccessful()
                }
            } finally {
                db.endTransaction()
            }
            dbHelper.applyTrackSummaryDelta(
                delta.count,
                delta.firstTimestamp,
                delta.lastTimestamp,
                delta.distanceMeters
            )
            persistedPoint?.let(::cacheLastPoint)
            return id
        }
    }

    @Synchronized
    fun insertLocations(locations: List<Location>) {
        if (locations.isEmpty()) return
        synchronized(dbHelper.historyLock) {
            if (historyGeneration != dbHelper.historyGeneration) return
            val db = dbHelper.writableDatabase
            val delta = TrackInsertDelta()
            var persistedPoint: LastPoint? = null
            db.beginTransaction()
            try {
                val previous = loadLastPoint(db)
                locations.forEach { insertLocationInTransaction(db, it, previous, delta) }
                persistedPoint = previous
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            dbHelper.applyTrackSummaryDelta(
                delta.count,
                delta.firstTimestamp,
                delta.lastTimestamp,
                delta.distanceMeters
            )
            persistedPoint?.let(::cacheLastPoint)
        }
    }

    private fun loadLastPoint(db: SQLiteDatabase): LastPoint {
        if (dbHelper.summaryCachingEnabled) {
            dbHelper.lastTrackPointCache?.let { cached ->
                return LastPoint(
                    present = cached.present,
                    latitude = cached.latitude,
                    longitude = cached.longitude,
                    timestamp = cached.timestamp
                )
            }
        }
        val previous = LastPoint()
        db.query(
            "track_points", arrayOf("latitude", "longitude", "timestamp_ms"),
            null, null, null, null, "id DESC", "1"
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                previous.present = true
                previous.latitude = cursor.getDouble(0)
                previous.longitude = cursor.getDouble(1)
                previous.timestamp = cursor.getLong(2)
            }
        }
        if (dbHelper.summaryCachingEnabled) cacheLastPoint(previous)
        return previous
    }

    private fun cacheLastPoint(point: LastPoint) {
        if (!dbHelper.summaryCachingEnabled) return
        dbHelper.lastTrackPointCache = AppDatabase.LastTrackPointCache(
            present = point.present,
            latitude = point.latitude,
            longitude = point.longitude,
            timestamp = point.timestamp
        )
    }

    private fun insertLocationInTransaction(
        db: SQLiteDatabase,
        location: Location,
        previous: LastPoint,
        delta: TrackInsertDelta
    ): Long {
        val timestamp = location.time.takeIf { it > 0L } ?: System.currentTimeMillis()
        // Restore the actual path length from locally kept intervening fixes.
        // These are never included in online matching or billed API requests.
        var distance = if (previous.present) TravelDistance.between(
            previous.latitude, previous.longitude, previous.timestamp,
            location.latitude, location.longitude, timestamp
        ) else 0.0
        if (previous.present && timestamp - previous.timestamp in 1..30_000L) {
            var prevLat = previous.latitude
            var prevLon = previous.longitude
            var prevTime = previous.timestamp
            var retained = false
            var sum = 0.0
            db.query(
                "skipped_driving_fixes",
                arrayOf("latitude", "longitude", "timestamp_ms"),
                "timestamp_ms > ? AND timestamp_ms < ?",
                arrayOf(previous.timestamp.toString(), timestamp.toString()),
                null, null, "timestamp_ms ASC, id ASC"
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val lat = cursor.getDouble(0)
                    val lon = cursor.getDouble(1)
                    val time = cursor.getLong(2)
                    sum += TravelDistance.between(prevLat, prevLon, prevTime, lat, lon, time)
                    prevLat = lat
                    prevLon = lon
                    prevTime = time
                    retained = true
                }
            }
            if (retained) {
                distance = sum + TravelDistance.between(
                    prevLat, prevLon, prevTime, location.latitude, location.longitude, timestamp
                )
            }
        }
        val values = ContentValues().apply {
            put("latitude", location.latitude)
            put("longitude", location.longitude)
            put("accuracy_m", location.accuracy)
            put("speed_mps", LocationProviders.reliableMeasuredSpeed(location))
            put("bearing_deg", if (location.hasBearing() && location.bearing.isFinite()) location.bearing else 0f)
            put("timestamp_ms", timestamp)
            put("matched", 0)
            put("distance_m", distance)
        }
        val id = db.insertOrThrow("track_points", null, values)
        previous.present = true
        previous.latitude = location.latitude
        previous.longitude = location.longitude
        previous.timestamp = timestamp
        delta.add(timestamp, distance)
        return id
    }

    private class TrackInsertDelta {
        var count = 0L
            private set
        var firstTimestamp: Long? = null
            private set
        var lastTimestamp: Long? = null
            private set
        var distanceMeters = 0.0
            private set

        fun add(timestamp: Long, distance: Double) {
            count++
            firstTimestamp = firstTimestamp?.let { minOf(it, timestamp) } ?: timestamp
            lastTimestamp = lastTimestamp?.let { maxOf(it, timestamp) } ?: timestamp
            distanceMeters += distance
        }
    }

    private class LastPoint(
        var present: Boolean = false,
        var latitude: Double = 0.0,
        var longitude: Double = 0.0,
        var timestamp: Long = 0L
    )

    data class MatchingWindow(
        val points: List<TrackPoint>,
        val markableIds: Set<Long>
    )

    /**
     * Returns the newest contiguous group of pending points with adjacent raw points as
     * unmarkable context. Live windows reserve three context slots. Short hole retries use
     * spare slots on both sides of a junction without increasing the request size limit.
     */
    @Synchronized
    fun loadMatchingWindow(
        limit: Int = 50,
        nowMillis: Long = System.currentTimeMillis(),
        maxPendingId: Long? = null,
        retryGapStartId: Long? = null
    ): MatchingWindow {
        val maxPoints = limit.coerceAtLeast(2)
        // A long previously missed turn may contain eight consecutive, accurate raw fixes.
        // The public OSRM demo accepts only ten points: use eight fixes and one genuine
        // context fix on either side, but ONLY when revisiting an old unresolved island.
        // Ordinary live batches retain three context slots and their existing behavior.
        val wideRetry = retryGapStartId != null && maxPoints in 10..16
        val anchorSlots = minOf(if (wideRetry) 2 else if (maxPoints >= 6) 3 else 2, maxPoints - 1)
        val pending = ArrayList<TrackPoint>(maxPoints - anchorSlots)
        val pendingSelection = buildString {
            append("matched = 0 AND next_match_attempt_ms <= ?")
            if (maxPendingId != null) append(" AND id <= ?")
            if (wideRetry) append(" AND id >= ? AND id <= ?")
        }
        val pendingArgs = buildList {
            add(nowMillis.toString())
            if (maxPendingId != null) add(maxPendingId.toString())
            if (wideRetry) {
                val start = requireNotNull(retryGapStartId)
                add(start.toString())
                add((start + (maxPoints - anchorSlots - 1)).toString())
            }
        }.toTypedArray()
        dbHelper.readableDatabase.query(
            "track_points",
            TRACK_COLUMNS,
            pendingSelection,
            pendingArgs,
            null,
            null,
            "id DESC",
            (maxPoints - anchorSlots).toString()
        ).use { cursor ->
            while (cursor.moveToNext()) pending += cursor.toTrackPoint()
        }
        if (pending.isEmpty()) return MatchingWindow(emptyList(), emptySet())

        // Work backward only through the newest continuous drive. A stale unmatched outlier
        // from an earlier trip must never make OSRM bridge a large time gap to a new drive.
        val newestFirst = ArrayList<TrackPoint>(pending.size)
        newestFirst += pending.first()
        for (i in 1 until pending.size) {
            val newer = newestFirst.last()
            val older = pending[i]
            // A gap in ids means an intervening raw point was already resolved. Do not omit that
            // guidance point and then ask OSRM to bridge across it; retry each unresolved island
            // separately so the matched neighbors become context on both sides.
            if (older.id + 1 != newer.id ||
                !isMatchingContinuation(older, newer, MATCH_CLUSTER_GAP_MS)
            ) break
            newestFirst += older
        }
        newestFirst.reverse()

        val markableIds = newestFirst.mapTo(LinkedHashSet(newestFirst.size)) { it.id }
        val earliest = newestFirst.first()
        val latest = newestFirst.last()

        // A short pending island can leave most of the ten-fix request unused. Collect bounded
        // context candidates on both sides, then share the spare slots between approach and
        // exit. Keeping only two exit fixes can end at another nearby intersection and leave
        // an otherwise supported turn below the confidence threshold indefinitely.
        val contextSlots = maxPoints - newestFirst.size
        val newerAnchorLimit = if (maxPoints >= 6) contextSlots
            else minOf(2, (anchorSlots - 1).coerceAtLeast(0))
        val newerAnchors = ArrayList<TrackPoint>(newerAnchorLimit)
        dbHelper.readableDatabase.query(
            "track_points",
            TRACK_COLUMNS,
            "id > ?",
            arrayOf(latest.id.toString()),
            null,
            null,
            "id ASC",
            newerAnchorLimit.toString()
        ).use { cursor ->
            var boundary = latest
            while (cursor.moveToNext()) {
                val anchor = cursor.toTrackPoint()
                if (boundary.id + 1 != anchor.id ||
                    !isMatchingContinuation(boundary, anchor, MATCH_ANCHOR_MAX_GAP_MS)
                ) break
                newerAnchors += anchor
                boundary = anchor
            }
        }

        // Live batches keep their existing three-anchor overlap and throughput. For retries,
        // collect enough incoming context to use slots left over by a short exit or vice versa.
        val olderSlots = if (newerAnchors.isNotEmpty()) {
            if (maxPoints >= 6) contextSlots else 1
        } else anchorSlots
        val olderAnchors = ArrayList<TrackPoint>(olderSlots)
        if (olderSlots > 0) {
            dbHelper.readableDatabase.query(
                "track_points",
                TRACK_COLUMNS,
                "id < ?",
                arrayOf(earliest.id.toString()),
                null,
                null,
                "id DESC",
                olderSlots.toString()
            ).use { cursor ->
                var boundary = earliest
                while (cursor.moveToNext()) {
                    val anchor = cursor.toTrackPoint()
                    if (anchor.id + 1 != boundary.id ||
                        !isMatchingContinuation(anchor, boundary, MATCH_ANCHOR_MAX_GAP_MS)
                    ) break
                    olderAnchors += anchor
                    boundary = anchor
                }
            }
            olderAnchors.reverse()
        }

        // Preserve at least one matched anchor on EACH side of a 7–8 fix retry
        // instead of spending both final request slots after the turn.
        val preferredNewerSlots = if (wideRetry && newestFirst.size >= 7) 1
            else if (maxPoints >= 6) maxOf(2, contextSlots / 2) else newerAnchorLimit
        var newerCount = minOf(newerAnchors.size, preferredNewerSlots)
        val olderCount = minOf(olderAnchors.size, contextSlots - newerCount)
        newerCount = minOf(newerAnchors.size, contextSlots - olderCount)
        return MatchingWindow(buildList {
            addAll(olderAnchors.takeLast(olderCount))
            addAll(newestFirst)
            addAll(newerAnchors.take(newerCount))
        }, markableIds)
    }

    /**
     * A finalization event (for example Android Location being switched off) is new information:
     * no additional GPS fixes are coming right now. Let deferred turn holes retry immediately
     * instead of leaving visible provisional gaps for a stale 30 s / 5 min backoff.
     */
    @Synchronized
    fun makePendingMatchingEligibleNow() {
        synchronized(dbHelper.historyLock) {
            if (historyGeneration != dbHelper.historyGeneration) return
            dbHelper.writableDatabase.update(
                "track_points",
                ContentValues().apply { put("next_match_attempt_ms", 0L) },
                "matched = 0 AND next_match_attempt_ms > 0",
                null
            )
        }
    }

    @Synchronized
    fun hasOlderEligiblePending(beforeId: Long, nowMillis: Long = System.currentTimeMillis()): Boolean =
        dbHelper.readableDatabase.rawQuery(
            "SELECT 1 FROM track_points " +
                "WHERE matched = 0 AND next_match_attempt_ms <= ? AND id < ? LIMIT 1",
            arrayOf(nowMillis.toString(), beforeId.toString())
        ).use { it.moveToFirst() }

    /**
     * Prefer the newest precise, bounded, fully anchored multi-fix gap for one
     * spare matching batch. Long genuine turns must not wait behind hundreds of
     * unrelated isolated unmatched fixes in an older driving history.
     *
     * This only orders existing pending work; it does not mark fixes matched,
     * loosen OSRM acceptance or increase the total number of network batches.
     * Scan a bounded sample, and keep oldest-first retries in the other slots.
     */
    @Synchronized
    fun newestEligibleAnchoredGapStartId(
        nowMillis: Long = System.currentTimeMillis(),
        beforeId: Long? = null
    ): Long? {
        val db = dbHelper.readableDatabase
        val eligible = ArrayList<TrackPoint>(PRIORITY_GAP_SCAN_LIMIT)
        db.query(
            "track_points",
            TRACK_COLUMNS,
            if (beforeId == null) {
                "matched = 0 AND next_match_attempt_ms <= ?"
            } else {
                "matched = 0 AND next_match_attempt_ms <= ? AND id < ?"
            },
            if (beforeId == null) {
                arrayOf(nowMillis.toString())
            } else {
                arrayOf(nowMillis.toString(), beforeId.toString())
            },
            null,
            null,
            "id DESC",
            PRIORITY_GAP_SCAN_LIMIT.toString()
        ).use { cursor ->
            while (cursor.moveToNext()) eligible += cursor.toTrackPoint()
        }

        var offset = 0
        while (offset < eligible.size) {
            var until = offset + 1
            while (until < eligible.size &&
                eligible[until].id + 1 == eligible[until - 1].id
            ) until++

            val size = until - offset
            if (size in PRIORITY_GAP_MIN_FIXES..PRIORITY_GAP_MAX_FIXES) {
                val run = eligible.subList(offset, until).asReversed()
                val first = run.first()
                val last = run.last()
                val continuous = last.timestampMillis - first.timestampMillis in 1..PRIORITY_GAP_MAX_MS &&
                    run.all { it.accuracyMeters.isFinite() &&
                        it.accuracyMeters in 0.01f..PRIORITY_GAP_MAX_ACCURACY_M
                    } &&
                    run.zipWithNext().all { (a, b) ->
                        isMatchingContinuation(a, b, MATCH_CLUSTER_GAP_MS)
                    }
                if (continuous && first.id > 0 && last.id < Long.MAX_VALUE) {
                    val anchorIds = listOf(first.id - 1, last.id + 1)
                    val anchors = HashMap<Long, TrackPoint>(2)
                    db.query(
                        "track_points",
                        TRACK_COLUMNS,
                        "id IN (?, ?)",
                        anchorIds.map(Long::toString).toTypedArray(),
                        null, null, null
                    ).use { cursor ->
                        while (cursor.moveToNext()) {
                            val point = cursor.toTrackPoint()
                            anchors[point.id] = point
                        }
                    }
                    val before = anchors[first.id - 1]
                    val after = anchors[last.id + 1]
                    if (before?.matched == true && after?.matched == true &&
                        before.accuracyMeters in 0.01f..PRIORITY_GAP_MAX_ACCURACY_M &&
                        after.accuracyMeters in 0.01f..PRIORITY_GAP_MAX_ACCURACY_M &&
                        isMatchingContinuation(before, first, MATCH_ANCHOR_MAX_GAP_MS) &&
                        isMatchingContinuation(last, after, MATCH_ANCHOR_MAX_GAP_MS)
                    ) return first.id
                }
            }
            offset = until
        }
        return null
    }

    /**
     * Return the oldest eligible hole for spare matching batches, including points without a
     * scheduled backoff. Otherwise old zero-deadline corners can starve behind newer retries.
     * The first batch still selects the newest pending driving fixes for low live latency.
     */
    @Synchronized
    fun oldestEligibleRetryId(nowMillis: Long = System.currentTimeMillis()): Long? =
        dbHelper.readableDatabase.rawQuery(
            "SELECT MIN(id) FROM track_points WHERE matched = 0 AND next_match_attempt_ms <= ?",
            arrayOf(nowMillis.toString())
        ).use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null }

    @Synchronized
    fun nextDeferredMatchAttempt(nowMillis: Long = System.currentTimeMillis()): Long? =
        dbHelper.readableDatabase.rawQuery(
            "SELECT MIN(next_match_attempt_ms) FROM track_points WHERE matched = 0 AND next_match_attempt_ms > ?",
            arrayOf(nowMillis.toString())
        ).use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null }

    /**
     * Atomically record a repeated visit to a saved road and resolve its precise GPS
     * fixes locally. No road geometries or new unlocked segments are ever created.
     */
    @Synchronized
    fun completeKnownRoadVisits(visits: List<com.roadconquest.app.matching.KnownRoadVisit>): Int =
        synchronized(dbHelper.historyLock) {
            if (historyGeneration != dbHelper.historyGeneration || visits.isEmpty()) return@synchronized 0
            val db = dbHelper.writableDatabase
            var resolved = 0
            db.beginTransaction()
            try {
                for (visit in visits) {
                    if (visit.pointIds.isEmpty() || visit.pointIds.size > 100) continue
                    val exists = db.rawQuery("SELECT 1 FROM roads WHERE segment_id = ? LIMIT 1",
                        arrayOf(visit.segmentId)).use { it.moveToFirst() }
                    if (!exists) continue
                    val ids = visit.pointIds.distinct()
                    val placeholders = ids.joinToString(",") { "?" }
                    val count = db.update(
                        "track_points",
                        ContentValues().apply { put("matched", 1); put("next_match_attempt_ms", 0) },
                        "matched = 0 AND id IN ($placeholders)",
                        ids.map(Long::toString).toTypedArray()
                    )
                    check(count == ids.size) { "Local match changed during validation" }
                    recordRoadVisit(db, visit.segmentId, visit.firstTimestamp, visit.lastTimestamp)
                    db.execSQL("UPDATE roads SET last_driven_at = MAX(last_driven_at, ?) WHERE segment_id = ?",
                        arrayOf(visit.lastTimestamp, visit.segmentId))
                    resolved += count
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            if (resolved > 0) dbHelper.invalidateRoadSummary()
            resolved
        }

    /** Geometry and point resolution must either both commit or both roll back. */
    @Synchronized
    fun completeMatch(roads: List<MatchedRoad>, ids: List<Long>) {
        synchronized(dbHelper.historyLock) {
            if (historyGeneration != dbHelper.historyGeneration) return
            require(roads.isNotEmpty() && ids.isNotEmpty())
            val db = dbHelper.writableDatabase
            db.beginTransaction()
            try {
                upsertRoads(roads)
                markMatched(ids)
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }

    @Synchronized
    fun markMatched(ids: List<Long>) {
        synchronized(dbHelper.historyLock) {
            if (historyGeneration != dbHelper.historyGeneration) return
            if (ids.isEmpty()) return
            val db = dbHelper.writableDatabase
            db.beginTransaction()
            try {
                val values = ContentValues().apply {
                    put("matched", 1)
                    put("next_match_attempt_ms", 0)
                }
                ids.chunked(400).forEach { chunk ->
                    val placeholders = chunk.joinToString(",") { "?" }
                    db.update(
                        "track_points",
                        values,
                        "id IN ($placeholders)",
                        chunk.map(Long::toString).toTypedArray()
                    )
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }

    @Synchronized
    fun deferMatching(ids: Collection<Long>, untilMillis: Long) {
        synchronized(dbHelper.historyLock) {
            if (historyGeneration != dbHelper.historyGeneration) return
            if (ids.isEmpty()) return
            val db = dbHelper.writableDatabase
            db.beginTransaction()
            try {
                val values = ContentValues().apply { put("next_match_attempt_ms", untilMillis) }
                ids.chunked(400).forEach { chunk ->
                    val placeholders = chunk.joinToString(",") { "?" }
                    db.update(
                        "track_points",
                        values,
                        "matched = 0 AND id IN ($placeholders)",
                        chunk.map(Long::toString).toTypedArray()
                    )
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }

    @Synchronized
    fun upsertRoads(roads: List<MatchedRoad>) {
        synchronized(dbHelper.historyLock) {
            if (historyGeneration != dbHelper.historyGeneration) return
            if (roads.isEmpty()) return
            val db = dbHelper.writableDatabase
            db.beginTransaction()
            try {
                roads.forEach { road ->
                    upsertRoadInTransaction(
                        db = db,
                        name = road.name,
                        reference = road.reference,
                        countTowardsRoads = road.countTowardsRoads,
                        geometryJson = road.coordinatesJson,
                        firstTimestamp = road.firstTimestamp,
                        lastTimestamp = road.lastTimestamp
                    )
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            dbHelper.invalidateRoadSummary()
        }
    }

    private fun upsertRoadInTransaction(
        db: SQLiteDatabase,
        name: String,
        reference: String,
        countTowardsRoads: Boolean,
        geometryJson: String,
        firstTimestamp: Long,
        lastTimestamp: Long
    ) {
        val coordinates = requireNotNull(runCatching { JSONArray(geometryJson) }.getOrNull()) { "Invalid road geometry JSON" }
        val bounds = requireNotNull(coordinateBounds(coordinates)) { "Invalid road coordinates" }
        val segmentId = roadSegmentId(db, name, coordinates)
        val existing = db.query(
            "roads",
            arrayOf("first_unlocked_at", "last_driven_at", "road_group_id"),
            "segment_id = ?",
            arrayOf(segmentId),
            null,
            null,
            null
        ).use { cursor ->
            if (!cursor.moveToFirst()) null
            else ExistingRoad(
                firstUnlockedAt = cursor.getLong(0),
                lastDrivenAt = cursor.getLong(1),
                groupId = cursor.getString(2)
            )
        }
        val previousGroup = existing?.groupId.orEmpty()
        val groupId = when {
            !countTowardsRoads && previousGroup.isNotBlank() && !isExcludedRoadGroup(previousGroup) ->
                previousGroup
            !countTowardsRoads -> excludedRoadGroupId(segmentId)
            else -> localRoadGroupId(
                db = db,
                segmentId = segmentId,
                name = name,
                reference = reference,
                geometryJson = geometryJson,
                bounds = bounds,
                existingGroupId = previousGroup.takeUnless(::isExcludedRoadGroup)
            )
        }

        val values = ContentValues().apply {
            put("segment_id", segmentId)
            put("name", name.ifBlank { "Unnamed road" })
            put("geometry_json", geometryJson)
            put("first_unlocked_at", min(existing?.firstUnlockedAt ?: firstTimestamp, firstTimestamp))
            put("last_driven_at", max(existing?.lastDrivenAt ?: lastTimestamp, lastTimestamp))
            put("road_group_id", groupId)
            put("min_lat", bounds.minLat)
            put("max_lat", bounds.maxLat)
            put("min_lon", bounds.minLon)
            put("max_lon", bounds.maxLon)
        }
        if (existing == null) {
            db.insertOrThrow("roads", null, values)
        } else {
            check(db.update("roads", values, "segment_id = ?", arrayOf(segmentId)) == 1)
        }
        recordRoadVisit(db, segmentId, firstTimestamp, lastTimestamp)
    }

    private fun localRoadGroupId(
        db: SQLiteDatabase,
        segmentId: String,
        name: String,
        reference: String,
        geometryJson: String,
        bounds: RoadBounds,
        existingGroupId: String?
    ): String {
        val current = RoadGrouping.Road(
            segmentId = segmentId,
            name = name,
            reference = reference,
            geometryJson = geometryJson,
            minLatitude = bounds.minLat,
            maxLatitude = bounds.maxLat,
            minLongitude = bounds.minLon,
            maxLongitude = bounds.maxLon,
            groupId = existingGroupId
        )
        val queryTolerance = maxOf(RoadGrouping.JOIN_TOLERANCE_M, RoadGrouping.REF_JOIN_TOLERANCE_M)
        val latitudePad = queryTolerance / METERS_PER_DEGREE
        val centerLatitude = (bounds.minLat + bounds.maxLat) / 2.0
        val longitudePad = queryTolerance /
            (METERS_PER_DEGREE * cos(Math.toRadians(centerLatitude)).coerceAtLeast(0.01))
        val longitudeSpan = if (bounds.maxLon >= bounds.minLon) {
            bounds.maxLon - bounds.minLon
        } else {
            bounds.maxLon - bounds.minLon + 360.0
        }
        fun wrap(value: Double) = ((value + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
        val querySpan = (longitudeSpan + 2 * longitudePad).coerceAtMost(360.0)
        val queryWest = if (querySpan >= 360.0) -180.0 else wrap(bounds.minLon - longitudePad)
        val queryEast = if (querySpan >= 360.0) 180.0 else wrap(bounds.maxLon + longitudePad)
        val longitudeSelection = when {
            querySpan >= 360.0 -> "1 = 1"
            queryEast >= queryWest ->
                "((min_lon <= max_lon AND max_lon >= ? AND min_lon <= ?) OR " +
                    "(min_lon > max_lon AND (min_lon <= ? OR max_lon >= ?)))"
            else -> "(min_lon > max_lon OR max_lon >= ? OR min_lon <= ?)"
        }
        val longitudeArgs = when {
            querySpan >= 360.0 -> emptyArray()
            queryEast >= queryWest -> arrayOf(
                queryWest.toString(), queryEast.toString(),
                queryEast.toString(), queryWest.toString()
            )
            else -> arrayOf(queryWest.toString(), queryEast.toString())
        }
        val groupIds = LinkedHashSet<String>()
        val ungroupedSegments = LinkedHashSet<String>()
        val canonicalSegments = linkedSetOf(segmentId)
        var referenceKeys = current.referenceKeys
        existingGroupId?.takeIf { it.isNotBlank() }?.let { group ->
            groupIds += group
            canonicalSegments += canonicalSegmentFromGroupId(group)
            referenceKeys = reconcileCurrentReferenceKeys(
                current.referenceKeys,
                referenceKeysFromGroupId(group)
            )
        }
        var identityRoad = current.copy(reference = referenceKeys.joinToString(";"))

        db.query(
            "roads",
            arrayOf(
                "segment_id", "name", "geometry_json", "min_lat", "max_lat",
                "min_lon", "max_lon", "road_group_id"
            ),
            "segment_id != ? AND max_lat >= ? AND min_lat <= ? AND $longitudeSelection",
            arrayOf(
                segmentId,
                (bounds.minLat - latitudePad).toString(),
                (bounds.maxLat + latitudePad).toString(),
                *longitudeArgs
            ),
            null,
            null,
            "segment_id ASC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val storedGroup = cursor.getString(7).orEmpty()
                if (isExcludedRoadGroup(storedGroup)) continue
                val candidate = RoadGrouping.Road(
                    segmentId = cursor.getString(0),
                    name = cursor.getString(1),
                    reference = referenceKeysFromGroupId(storedGroup).joinToString(";"),
                    geometryJson = cursor.getString(2),
                    minLatitude = cursor.getDouble(3),
                    maxLatitude = cursor.getDouble(4),
                    minLongitude = cursor.getDouble(5),
                    maxLongitude = cursor.getDouble(6),
                    groupId = storedGroup
                )
                if (!RoadGrouping.connected(identityRoad, candidate)) continue
                referenceKeys = mergeConnectedReferenceKeys(referenceKeys, candidate.referenceKeys)
                identityRoad = identityRoad.copy(reference = referenceKeys.joinToString(";"))
                if (storedGroup.isBlank()) {
                    ungroupedSegments += candidate.segmentId
                    canonicalSegments += candidate.segmentId
                } else {
                    groupIds += storedGroup
                    canonicalSegments += canonicalSegmentFromGroupId(storedGroup)
                }
            }
        }

        val identityPrefix = humanRoadIdentityPrefix(name, referenceKeys)
        val canonicalSegment = canonicalSegments.minOrNull() ?: segmentId
        val canonical = "$identityPrefix|$canonicalSegment"
        val values = ContentValues().apply { put("road_group_id", canonical) }
        for (group in groupIds) {
            if (group != canonical) {
                db.update("roads", values, "road_group_id = ?", arrayOf(group))
            }
        }
        for (candidateId in ungroupedSegments) {
            db.update("roads", values, "segment_id = ?", arrayOf(candidateId))
        }
        return canonical
    }

    private fun excludedRoadGroupId(segmentId: String): String = "$EXCLUDED_ROAD_GROUP_PREFIX$segmentId"

    private fun isExcludedRoadGroup(groupId: String): Boolean =
        groupId.startsWith(EXCLUDED_ROAD_GROUP_PREFIX)

    private fun canonicalSegmentFromGroupId(groupId: String): String {
        if (!groupId.startsWith(HUMAN_ROAD_GROUP_PREFIX)) return groupId
        val parts = groupId.split('|', limit = 4)
        return parts.getOrNull(3).orEmpty().ifBlank { groupId }
    }

    private fun referenceKeysFromGroupId(groupId: String): Set<String> {
        if (!groupId.startsWith(HUMAN_ROAD_GROUP_PREFIX)) return emptySet()
        val parts = groupId.split('|', limit = 4)
        if (parts.size != 4 || parts[1] != "r") return emptySet()
        return parts[2].split(',').filterTo(linkedSetOf()) { it.isNotBlank() }
    }

    private fun reconcileCurrentReferenceKeys(
        observed: Set<String>,
        stored: Set<String>
    ): Set<String> {
        if (observed.isEmpty()) return stored
        if (stored.isEmpty()) return observed
        val shared = observed intersect stored
        return if (shared.isNotEmpty()) shared else observed
    }

    private fun mergeConnectedReferenceKeys(
        current: Set<String>,
        candidate: Set<String>
    ): Set<String> {
        if (current.isEmpty()) return candidate
        if (candidate.isEmpty()) return current
        // Keep only route identity supported by both connected pieces. This prevents a
        // concurrent US-1/US-9 segment from transitively merging the separate routes after
        // they diverge. If refs disagree but the street name itself connected them, fall back
        // to the human street-name identity rather than inventing a route relationship.
        return current intersect candidate
    }

    private fun humanRoadIdentityPrefix(name: String, referenceKeys: Set<String>): String {
        if (referenceKeys.isNotEmpty()) {
            return HUMAN_ROAD_GROUP_PREFIX + "r|" + referenceKeys.sorted().joinToString(",")
        }
        val nameKey = RoadGrouping.normalizeName(name)
        if (nameKey.isNotEmpty()) {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(nameKey.toByteArray(Charsets.UTF_8))
            val shortHash = String(CharArray(16) { index ->
                val byte = digest[index / 2].toInt() and 0xff
                "0123456789abcdef"[if (index % 2 == 0) byte ushr 4 else byte and 0x0f]
            })
            return HUMAN_ROAD_GROUP_PREFIX + "n|$shortHash"
        }
        return HUMAN_ROAD_GROUP_PREFIX + "u|-"
    }

    private data class ExistingRoad(
        val firstUnlockedAt: Long,
        val lastDrivenAt: Long,
        val groupId: String?
    )

    /**
     * One road traversal can produce many overlapping or adjacent OSRM legs.
     * Coalesce those fragments, including a genuine pause at a traffic light.
     * Only count a quick return as a separate pass when accepted GPS fixes show
     * that the car actually left this road in between. This is an estimate, not
     * an exact trip counter: a GPS outage cannot prove an additional traversal.
     */
    private fun recordRoadVisit(
        db: SQLiteDatabase,
        segmentId: String,
        firstTimestamp: Long,
        lastTimestamp: Long
    ) {
        val startedAt = min(firstTimestamp, lastTimestamp)
        val endedAt = max(firstTimestamp, lastTimestamp)
        var mergedStart = startedAt
        var mergedEnd = endedAt
        val merged = HashSet<Pair<Long, Long>>()
        val candidates = ArrayList<Pair<Long, Long>>()
        val seen = HashSet<Pair<Long, Long>>()
        var scannedStart = startedAt
        var scannedEnd = endedAt

        // Use the composite segment/time index to avoid loading years of unrelated
        // visits. Expand only when a matched window grows beyond the original query.
        // A single fixed query misses a legacy fragment attached to the far end of
        // a long window when match batches are replayed out of order.
        fun loadCandidates() {
            db.rawQuery(
                "SELECT started_at, ended_at FROM road_visits " +
                    "WHERE segment_id = ? AND started_at <= ? AND ended_at >= ? ORDER BY started_at",
                arrayOf(
                    segmentId,
                    (scannedEnd + VISIT_CONTINUATION_MS).toString(),
                    (scannedStart - VISIT_CONTINUATION_MS).toString()
                )
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val window = cursor.getLong(0) to cursor.getLong(1)
                    if (seen.add(window)) candidates += window
                }
            }
        }

        loadCandidates()
        var changed: Boolean
        do {
            changed = false
            for ((start, end) in candidates) {
                if ((start to end) in merged) continue
                val overlaps = start <= mergedEnd && end >= mergedStart
                val gapStart = if (end < mergedStart) end else mergedEnd
                val gapEnd = if (end < mergedStart) mergedStart else start
                val close = gapEnd >= gapStart && gapEnd - gapStart <= VISIT_CONTINUATION_MS
                if (!overlaps && !(close && !hasConfirmedDeparture(
                        db, segmentId, gapStart, gapEnd
                    ))) continue
                merged += start to end
                mergedStart = min(mergedStart, start)
                mergedEnd = max(mergedEnd, end)
                changed = true
            }
            if (mergedStart < scannedStart || mergedEnd > scannedEnd) {
                scannedStart = mergedStart
                scannedEnd = mergedEnd
                loadCandidates()
                changed = true
            }
        } while (changed)

        merged.forEach { (start, end) ->
            db.delete(
                "road_visits",
                "segment_id = ? AND started_at = ? AND ended_at = ?",
                arrayOf(segmentId, start.toString(), end.toString())
            )
        }
        db.insertOrThrow(
            "road_visits",
            null,
            ContentValues().apply {
                put("segment_id", segmentId)
                put("started_at", mergedStart)
                put("ended_at", mergedEnd)
            }
        )
        val count = db.rawQuery(
            "SELECT COUNT(*) FROM road_visits WHERE segment_id = ?",
            arrayOf(segmentId)
        ).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getInt(0)
        }
        check(db.update(
            "roads",
            ContentValues().apply {
                put("drive_count", count)
                // Even verified matching cannot establish a mathematically exact number
                // of separate passes through a segment when GPS evidence is missing.
                put("drive_count_exact", 0)
            },
            "segment_id = ?",
            arrayOf(segmentId)
        ) == 1)
    }

    private fun hasConfirmedDeparture(
        db: SQLiteDatabase,
        segmentId: String,
        after: Long,
        before: Long
    ): Boolean {
        if (before <= after) return false
        val geometry = db.rawQuery(
            "SELECT geometry_json FROM roads WHERE segment_id = ?", arrayOf(segmentId)
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else return false }
        // The gap is bounded by VISIT_CONTINUATION_MS (two minutes). Scan
        // all accurate fixes in that indexed time range, stopping at the first
        // confirmed departure. A fixed first-120 cap can skip the only off-road
        // fix and merge two real traversals incorrectly.
        db.rawQuery(
            "SELECT latitude, longitude FROM track_points " +
                "WHERE timestamp_ms > ? AND timestamp_ms < ? AND accuracy_m BETWEEN 0.01 AND 25 " +
                "ORDER BY timestamp_ms",
            arrayOf(after.toString(), before.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                if (distanceToRoadMeters(cursor.getDouble(0), cursor.getDouble(1), geometry) >
                    VISIT_DEPARTURE_DISTANCE_M
                ) return true
            }
        }
        return false
    }

    /**
     * Reconcile legacy pre-Beta-32 matcher fragments when a user opens road details.
     * This is a read-only estimate: old visit rows and saved roads remain untouched,
     * while large histories incur the scan only for the single selected road.
     */
    @Synchronized
    fun estimatedRoadVisitCount(road: RoadRecord): Int {
        var count = 0
        var mergedEnd = Long.MIN_VALUE
        dbHelper.readableDatabase.query(
            "road_visits",
            arrayOf("started_at", "ended_at"),
            "segment_id = ?",
            arrayOf(road.segmentId),
            null,
            null,
            "started_at ASC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val startedAt = cursor.getLong(0)
                val endedAt = cursor.getLong(1)
                if (count == 0) {
                    count = 1
                    mergedEnd = endedAt
                } else if (startedAt <= mergedEnd ||
                    (startedAt - mergedEnd <= VISIT_CONTINUATION_MS &&
                        !hasConfirmedDeparture(dbHelper.readableDatabase, road.segmentId, mergedEnd, startedAt))
                ) {
                    mergedEnd = max(mergedEnd, endedAt)
                } else {
                    count++
                    mergedEnd = endedAt
                }
            }
        }
        // Early versions might lack individual visit windows; never replace a
        // recorded count with zero just because optional history is absent.
        return if (count == 0) road.timesDriven else count
    }

    /**
     * A displayed road is one connected road GROUP composed of many small OSRM
     * segments. Counting only the tapped segment makes virtually every road
     * appear to have been driven once, even when separate journeys used another
     * section of the same road.
     *
     * Merge time-overlapping and close-together matched fragments into driving
     * episodes. Read-only, works for existing histories without migrations,
     * and never alters road matching or stored visit intervals.
     */
    @Synchronized
    fun estimatedWholeRoadVisitCount(road: RoadRecord): Int {
        val db = dbHelper.readableDatabase
        val groupId = db.rawQuery(
            "SELECT road_group_id FROM roads WHERE segment_id = ?",
            arrayOf(road.segmentId)
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0).orEmpty() else ""
        }
        if (groupId.isBlank() || isExcludedRoadGroup(groupId)) {
            return estimatedRoadVisitCount(road)
        }
        var episodes = 0
        var lastEnd = Long.MIN_VALUE
        db.rawQuery(
            """SELECT v.started_at, v.ended_at
               FROM road_visits v
               INNER JOIN roads r ON v.segment_id = r.segment_id
               WHERE r.road_group_id = ?
               ORDER BY v.started_at, v.ended_at""",
            arrayOf(groupId)
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val start = cursor.getLong(0)
                val end = cursor.getLong(1)
                if (episodes == 0 || start > lastEnd &&
                    start - lastEnd > VISIT_CONTINUATION_MS
                ) {
                    episodes++
                }
                lastEnd = max(lastEnd, end)
            }
        }
        // Some legacy records have only a stored count but no visit windows.
        return maxOf(road.timesDriven, episodes)
    }

    /**
     * Extra observed visit intervals for display-only corner repair. On busy map views,
     * stay with the existing first/last repair instead of querying unbounded history.
     */
    @Synchronized
    fun getVisitWindowsForRoads(roads: List<RoadRecord>): Map<String, List<Pair<Long, Long>>> {
        if (roads.size !in 2..MAX_VISIT_REPAIR_ROADS) return emptyMap()
        val segmentIds = roads.map { it.segmentId }
        val placeholders = segmentIds.joinToString(",") { "?" }
        val visits = LinkedHashMap<String, MutableList<Pair<Long, Long>>>()
        dbHelper.readableDatabase.query(
            "road_visits",
            arrayOf("segment_id", "started_at", "ended_at"),
            "segment_id IN ($placeholders)",
            segmentIds.toTypedArray(),
            null, null, null,
            (MAX_VISIT_REPAIR_ROWS + 1).toString()
        ).use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.position >= MAX_VISIT_REPAIR_ROWS) return emptyMap()
                val segmentId = cursor.getString(0)
                visits.getOrPut(segmentId) { ArrayList(2) } +=
                    cursor.getLong(1) to cursor.getLong(2)
            }
        }
        return visits
    }

    data class RoadQueryResult(val roads: List<RoadRecord>, val complete: Boolean)

    data class PendingRouteResult(
        val roads: List<RoadRecord>,
        val oldestVisiblePendingTimestampMillis: Long?
    )

    /** Pending raw evidence can be time-bounded for display without deleting retryable fixes. */
    fun getPendingRouteInBounds(
        north: Double, east: Double, south: Double, west: Double,
        visibleSinceMillis: Long = Long.MIN_VALUE
    ): List<RoadRecord> = getPendingRouteInBoundsResult(
        north, east, south, west, visibleSinceMillis
    ).roads

    @Synchronized
    fun getPendingRouteInBoundsResult(
        north: Double, east: Double, south: Double, west: Double,
        visibleSinceMillis: Long = Long.MIN_VALUE
    ): PendingRouteResult {
        val latitudePad = PENDING_ROUTE_QUERY_PAD_M / METERS_PER_DEGREE
        val centerLatitude = ((north + south) / 2.0).coerceIn(-89.0, 89.0)
        val longitudePad = (PENDING_ROUTE_QUERY_PAD_M /
            (METERS_PER_DEGREE * cos(Math.toRadians(centerLatitude)).coerceAtLeast(0.01)))
            .coerceAtMost(180.0)
        val longitudeSpan = if (east >= west) east - west else east - west + 360.0
        val querySpan = (longitudeSpan + 2 * longitudePad).coerceAtMost(360.0)
        fun wrap(value: Double) = ((value + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
        val queryWest = if (querySpan >= 360.0) -180.0 else wrap(west - longitudePad)
        val queryEast = if (querySpan >= 360.0) 180.0 else wrap(east + longitudePad)
        val querySouth = (south - latitudePad).coerceAtLeast(-90.0)
        val queryNorth = (north + latitudePad).coerceAtMost(90.0)
        val sql = if (queryEast >= queryWest) PENDING_ROUTE_SQL_NORMAL else PENDING_ROUTE_SQL_WRAPPED
        val output = ArrayList<RoadRecord>()
        var oldestVisiblePendingTimestampMillis: Long? = null
        var previous: TrackPoint? = null
        var coordinates = JSONArray()
        fun finish() {
            if (coordinates.length() >= 2) {
                val bounds = requireNotNull(coordinateBounds(coordinates))
                output += RoadRecord("pending-${output.size}", "", coordinates.toString(), 0L, 0L,
                    bounds.minLat, bounds.maxLat, bounds.minLon, bounds.maxLon)
            }
            coordinates = JSONArray()
        }
        fun add(point: TrackPoint) { coordinates.put(JSONArray().put(point.longitude).put(point.latitude)) }
        fun noteVisiblePending(point: TrackPoint) {
            if (!point.matched && point.timestampMillis >= visibleSinceMillis) {
                oldestVisiblePendingTimestampMillis = minOf(
                    oldestVisiblePendingTimestampMillis ?: point.timestampMillis,
                    point.timestampMillis
                )
            }
        }
        dbHelper.readableDatabase.rawQuery(
            sql,
            arrayOf(
                visibleSinceMillis.toString(), querySouth.toString(), queryNorth.toString(),
                queryWest.toString(), queryEast.toString()
            )
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val point = cursor.toTrackPoint()
                val before = previous
                val continuous = before != null && before.id + 1 == point.id &&
                    (!before.matched || !point.matched) &&
                    before.accuracyMeters in 0f..50f && point.accuracyMeters in 0f..50f &&
                    isMatchingContinuation(before, point, MATCH_CLUSTER_GAP_MS)
                if (continuous) {
                    val start = requireNotNull(before)
                    if (coordinates.length() == 0) add(start)
                    add(point)
                    // A lone unmatched fix contributes no visible line and must not
                    // schedule needless expiry refreshes for unrelated nearby traces.
                    noteVisiblePending(start)
                    noteVisiblePending(point)
                } else finish()
                previous = point
            }
        }
        finish()
        return PendingRouteResult(output, oldestVisiblePendingTimestampMillis.takeIf { output.isNotEmpty() })
    }

    fun getRoadsInBounds(
        north: Double,
        east: Double,
        south: Double,
        west: Double,
        limit: Int = 20_000
    ): List<RoadRecord> = getRoadsInBoundsResult(north, east, south, west, limit).roads

    @Synchronized
    fun getRoadsInBoundsResult(
        north: Double,
        east: Double,
        south: Double,
        west: Double,
        limit: Int = 20_000
    ): RoadQueryResult {
        require(limit > 0)
        val result = ArrayList<RoadRecord>()
        val selection: String
        val args: Array<String>
        if (east >= west) {
            selection = "max_lat >= ? AND min_lat <= ? AND (" +
                "(min_lon <= max_lon AND max_lon >= ? AND min_lon <= ?) OR " +
                "(min_lon > max_lon AND (min_lon <= ? OR max_lon >= ?)))"
            args = arrayOf(
                south.toString(), north.toString(),
                west.toString(), east.toString(), east.toString(), west.toString()
            )
        } else {
            selection = "max_lat >= ? AND min_lat <= ? AND " +
                "(min_lon > max_lon OR max_lon >= ? OR min_lon <= ?)"
            args = arrayOf(south.toString(), north.toString(), west.toString(), east.toString())
        }
        val unlimited = limit == Int.MAX_VALUE
        dbHelper.readableDatabase.query(
            "roads",
            ROAD_COLUMNS,
            selection,
            args,
            null,
            null,
            if (unlimited) null else "last_driven_at DESC",
            if (unlimited) null else (limit + 1).toString()
        ).use { cursor ->
            while (cursor.moveToNext()) result += cursor.toRoadRecord()
        }
        val complete = unlimited || result.size <= limit
        if (!complete) result.removeAt(result.lastIndex)
        return RoadQueryResult(result, complete)
    }

    @Synchronized
    fun getSummary(): DataSummary = synchronized(dbHelper.historyLock) {
        val db = dbHelper.readableDatabase
        val track = if (dbHelper.summaryCachingEnabled) {
            dbHelper.trackSummaryCache ?: trackSummaryOf(db).also { dbHelper.trackSummaryCache = it }
        } else {
            trackSummaryOf(db)
        }
        val roads = if (dbHelper.summaryCachingEnabled) {
            dbHelper.roadSummaryCache ?: roadSummaryOf(db).also { dbHelper.roadSummaryCache = it }
        } else {
            roadSummaryOf(db)
        }
        DataSummary(
            track.pointCount,
            roads.segmentCount,
            track.firstTrackAt,
            track.lastTrackAt,
            track.distanceMeters,
            roads.unlockedCount,
            dbHelper.historyGeneration
        )
    }

    /** Copy history at one database revision, then release locks before slow ZIP I/O. */
    @Synchronized
    fun copyExportSnapshot(destination: SQLiteDatabase) {
        val source = dbHelper.readableDatabase
        if (Build.VERSION.SDK_INT >= 35) source.beginTransactionReadOnly() else source.beginTransactionNonExclusive()
        try {
            destination.beginTransaction()
            try {
                for (table in listOf(
                    "track_points", "skipped_driving_fixes", "roads", "road_visits", "explored_places", "explored_grid",
                    "visited_places", "place_candidates", "progression_rewards",
                    "progression_purchases", "progression_counters"
                )) {
                    val createSql = source.rawQuery("SELECT sql FROM sqlite_master WHERE type='table' AND name=?", arrayOf(table)).use {
                        check(it.moveToFirst()); it.getString(0)
                    }
                    destination.execSQL(createSql)
                    source.query(table, null, null, null, null, null, null).use { cursor ->
                        while (cursor.moveToNext()) {
                            val row = ContentValues(cursor.columnCount)
                            cursor.columnNames.forEachIndexed { index, name ->
                                when (cursor.getType(index)) {
                                    Cursor.FIELD_TYPE_NULL -> row.putNull(name)
                                    Cursor.FIELD_TYPE_INTEGER -> row.put(name, cursor.getLong(index))
                                    Cursor.FIELD_TYPE_FLOAT -> row.put(name, cursor.getDouble(index))
                                    Cursor.FIELD_TYPE_STRING -> row.put(name, cursor.getString(index))
                                    Cursor.FIELD_TYPE_BLOB -> row.put(name, cursor.getBlob(index))
                                }
                            }
                            destination.insertOrThrow(table, null, row)
                        }
                    }
                }
                destination.setTransactionSuccessful()
            } finally { destination.endTransaction() }
            source.setTransactionSuccessful()
        } finally { source.endTransaction() }
    }

    /** Queued pre-deletion writes become no-ops after the history generation changes. */
    @Synchronized
    fun clearHistory() {
        synchronized(dbHelper.historyLock) {
            val db = dbHelper.writableDatabase
            db.beginTransaction()
            try {
                db.delete("track_points", null, null)
                db.delete("skipped_driving_fixes", null, null)
                db.delete("road_visits", null, null)
                db.delete("roads", null, null)
                db.delete("explored_places", null, null)
                db.delete("explored_grid", null, null)
                db.delete("place_candidates", null, null)
                db.delete("visited_places", null, null)
                db.delete("progression_rewards", null, null)
                db.delete("progression_purchases", null, null)
                db.delete("progression_counters", null, null)
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            dbHelper.historyGeneration++
            dbHelper.resetSummaryCaches()
        }
    }

    fun readableDatabase(): SQLiteDatabase = synchronized(dbHelper.historyLock) {
        // Raw database access can mutate summary tables behind TrackingRepository's back.
        // Production code does not need this escape hatch; tests/debug tooling do.
        dbHelper.disableSummaryCaching()
        dbHelper.readableDatabase
    }

    private fun android.database.Cursor.toTrackPoint() = TrackPoint(
        id = getLong(0),
        latitude = getDouble(1),
        longitude = getDouble(2),
        accuracyMeters = getFloat(3),
        speedMps = getFloat(4),
        bearingDegrees = getFloat(5),
        timestampMillis = getLong(6),
        matched = getInt(7) != 0
    )

    private fun android.database.Cursor.toRoadRecord() = RoadRecord(
        segmentId = getString(0),
        name = getString(1),
        geometryJson = getString(2),
        firstUnlockedAt = getLong(3),
        lastDrivenAt = getLong(4),
        minLatitude = getDouble(5),
        maxLatitude = getDouble(6),
        minLongitude = getDouble(7),
        maxLongitude = getDouble(8),
        timesDriven = getInt(9),
        timesDrivenExact = getInt(10) != 0
    )

    /**
     * Fail closed when another saved road is nearby. An ambiguous junction or
     * parallel road must be verified online rather than credited as a local re-drive.
     */
    @Synchronized
    fun findUnambiguousPreviouslyDrivenRoadNear(
        latitude: Double, longitude: Double,
        snapRadiusMeters: Double = 3.0, exclusionRadiusMeters: Double = 12.0
    ): RoadRecord? {
        if (!latitude.isFinite() || !longitude.isFinite() ||
            latitude !in -90.0..90.0 || longitude !in -180.0..180.0 ||
            snapRadiusMeters <= 0.0 || exclusionRadiusMeters <= snapRadiusMeters
        ) return null
        val latitudePad = exclusionRadiusMeters / METERS_PER_DEGREE
        val longitudePad = (exclusionRadiusMeters /
            (METERS_PER_DEGREE * cos(Math.toRadians(latitude)).coerceAtLeast(0.01))).coerceAtMost(180.0)
        fun wrap(value: Double) = ((value + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
        val roads = getRoadsInBounds(
            (latitude + latitudePad).coerceAtMost(90.0),
            wrap(longitude + longitudePad),
            (latitude - latitudePad).coerceAtLeast(-90.0),
            wrap(longitude - longitudePad),
            Int.MAX_VALUE
        )
        var single: RoadRecord? = null
        for (road in roads) {
            val distance = distanceToRoadMeters(latitude, longitude, road.geometryJson)
            if (distance > exclusionRadiusMeters) continue
            if (single != null) return null
            single = road.takeIf { distance <= snapRadiusMeters }
        }
        return single
    }

    @Synchronized
    fun findRoadNear(latitude: Double, longitude: Double, radiusMeters: Double): RoadRecord? {
        if (!latitude.isFinite() || !longitude.isFinite() || radiusMeters <= 0.0) return null
        val latitudePad = radiusMeters / METERS_PER_DEGREE
        val longitudePad = (radiusMeters /
            (METERS_PER_DEGREE * cos(Math.toRadians(latitude)).coerceAtLeast(0.01))).coerceAtMost(180.0)
        fun wrap(value: Double) = ((value + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
        val candidates = getRoadsInBounds(
            (latitude + latitudePad).coerceAtMost(90.0),
            wrap(longitude + longitudePad),
            (latitude - latitudePad).coerceAtLeast(-90.0),
            wrap(longitude - longitudePad),
            Int.MAX_VALUE
        )
        var best: RoadRecord? = null
        var bestDistance = radiusMeters
        for (road in candidates) {
            val distance = distanceToRoadMeters(latitude, longitude, road.geometryJson)
            if (distance <= bestDistance) {
                bestDistance = distance
                best = road
            }
        }
        return best
    }

    fun roadLengthMeters(road: RoadRecord): Double {
        val coordinates = runCatching { JSONArray(road.geometryJson) }.getOrNull() ?: return 0.0
        var total = 0.0
        for (i in 1 until coordinates.length()) {
            val previous = coordinates.optJSONArray(i - 1) ?: continue
            val current = coordinates.optJSONArray(i) ?: continue
            val lon1 = previous.optDouble(0, Double.NaN)
            val lat1 = previous.optDouble(1, Double.NaN)
            val lon2 = current.optDouble(0, Double.NaN)
            val lat2 = current.optDouble(1, Double.NaN)
            if (lat1.isFinite() && lon1.isFinite() && lat2.isFinite() && lon2.isFinite()) {
                total += metersBetween(lat1, lon1, lat2, lon2)
            }
        }
        return total
    }

    private fun distanceToRoadMeters(latitude: Double, longitude: Double, geometryJson: String): Double {
        val coordinates = runCatching { JSONArray(geometryJson) }.getOrNull()
            ?: return Double.POSITIVE_INFINITY
        if (coordinates.length() < 2) return Double.POSITIVE_INFINITY
        val latitudeRadians = Math.toRadians(latitude)
        val cosLatitude = cos(latitudeRadians).coerceAtLeast(0.01)
        fun x(lon: Double): Double {
            val delta = ((lon - longitude + 540.0) % 360.0) - 180.0
            return EARTH_RADIUS_M * Math.toRadians(delta) * cosLatitude
        }
        fun y(lat: Double) = EARTH_RADIUS_M * Math.toRadians(lat - latitude)

        var best = Double.POSITIVE_INFINITY
        for (i in 1 until coordinates.length()) {
            val a = coordinates.optJSONArray(i - 1) ?: continue
            val b = coordinates.optJSONArray(i) ?: continue
            val ax = x(a.optDouble(0, Double.NaN))
            val ay = y(a.optDouble(1, Double.NaN))
            val bx = x(b.optDouble(0, Double.NaN))
            val by = y(b.optDouble(1, Double.NaN))
            if (!ax.isFinite() || !ay.isFinite() || !bx.isFinite() || !by.isFinite()) continue
            val dx = bx - ax
            val dy = by - ay
            val lengthSquared = dx * dx + dy * dy
            val t = if (lengthSquared == 0.0) 0.0
                else (-(ax * dx + ay * dy) / lengthSquared).coerceIn(0.0, 1.0)
            best = min(best, hypot(ax + t * dx, ay + t * dy))
        }
        return best
    }

    private fun metersBetween(
        latitude1: Double,
        longitude1: Double,
        latitude2: Double,
        longitude2: Double
    ): Double {
        val lat1 = Math.toRadians(latitude1)
        val lat2 = Math.toRadians(latitude2)
        val deltaLatitude = lat2 - lat1
        val deltaLongitude = Math.toRadians(((longitude2 - longitude1 + 540.0) % 360.0) - 180.0)
        val a = sin(deltaLatitude / 2).pow(2) +
            cos(lat1) * cos(lat2) * sin(deltaLongitude / 2).pow(2)
        return 2 * EARTH_RADIUS_M * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    private fun coordinateBounds(coordinates: JSONArray): RoadBounds? {
        if (coordinates.length() < 2) return null
        var minLat = Double.POSITIVE_INFINITY
        var maxLat = Double.NEGATIVE_INFINITY
        val longitudes = DoubleArray(coordinates.length())
        for (i in 0 until coordinates.length()) {
            val coordinate = coordinates.optJSONArray(i) ?: return null
            if (coordinate.length() < 2) return null
            val lon = coordinate.optDouble(0, Double.NaN)
            val lat = coordinate.optDouble(1, Double.NaN)
            if (!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
            minLat = min(minLat, lat)
            maxLat = max(maxLat, lat)
            longitudes[i] = lon
        }
        if (!minLat.isFinite()) return null

        // Use the smallest circular interval. min_lon > max_lon intentionally encodes
        // bounds that cross the International Date Line.
        longitudes.sort()
        var largestGap = longitudes.first() + 360.0 - longitudes.last()
        var gapStart = longitudes.last()
        var gapEnd = longitudes.first() + 360.0
        for (i in 0 until longitudes.lastIndex) {
            val gap = longitudes[i + 1] - longitudes[i]
            if (gap > largestGap) {
                largestGap = gap
                gapStart = longitudes[i]
                gapEnd = longitudes[i + 1]
            }
        }
        fun wrap(value: Double) = ((value + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
        return RoadBounds(minLat, maxLat, wrap(gapEnd), wrap(gapStart))
    }

    private fun roadSegmentId(db: SQLiteDatabase, name: String, coordinates: JSONArray): String {
        val primary = stableRoadSegmentId(name, coordinates)
        val storedGeometry = db.query(
            "roads",
            arrayOf("geometry_json"),
            "segment_id = ?",
            arrayOf(primary),
            null,
            null,
            null
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        val incomingKey = canonicalGeometryKey(coordinates)
        val normalizedName = name.trim().lowercase(Locale.US).ifBlank { "unnamed road" }
        if (storedGeometry == null) {
            // A geometry collision can outlive the primary row after later cleanup.
            // Reuse its deterministic alternate ID instead of recreating identical geometry
            // under the now-free primary ID.
            db.query(
                "roads",
                arrayOf("segment_id", "name", "geometry_json"),
                "segment_id LIKE 'g%'",
                null,
                null,
                null,
                null
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val existingName = cursor.getString(1).trim().lowercase(Locale.US)
                        .ifBlank { "unnamed road" }
                    if (existingName != normalizedName) continue
                    val existingKey = runCatching {
                        canonicalGeometryKey(JSONArray(cursor.getString(2)))
                    }.getOrNull()
                    if (existingKey == incomingKey) return cursor.getString(0)
                }
            }
            return primary
        }

        val storedKey = runCatching { canonicalGeometryKey(JSONArray(storedGeometry)) }.getOrNull()
        if (storedKey == incomingKey) return primary

        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$normalizedName|$incomingKey".toByteArray(Charsets.UTF_8))
        return "g" + String(CharArray(31) { i ->
            val byte = digest[i / 2].toInt() and 0xff
            "0123456789abcdef"[if (i % 2 == 0) byte ushr 4 else byte and 0x0f]
        })
    }

    private fun canonicalGeometryKey(coordinates: JSONArray): String {
        val rounded = ArrayList<String>(coordinates.length())
        for (i in 0 until coordinates.length()) {
            val coordinate = coordinates.getJSONArray(i)
            rounded += "%.5f,%.5f".format(
                Locale.US,
                coordinate.getDouble(0),
                coordinate.getDouble(1)
            )
        }
        val forward = rounded.joinToString(";")
        val reverse = rounded.asReversed().joinToString(";")
        return minOf(forward, reverse)
    }

    private fun stableRoadSegmentId(name: String, coordinates: JSONArray): String {
        fun roundedCoordinate(index: Int): String {
            val c = coordinates.getJSONArray(index)
            return "%.5f,%.5f".format(Locale.US, c.getDouble(0), c.getDouble(1))
        }

        val first = roundedCoordinate(0)
        val last = roundedCoordinate(coordinates.length() - 1)
        val endpoints = if (first <= last) "$first|$last" else "$last|$first"
        val normalizedName = name.trim().lowercase(Locale.US).ifBlank { "unnamed road" }
        val normalized = "$normalizedName|$endpoints"
        val digest = MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray(Charsets.UTF_8))
        return String(CharArray(24) { i ->
            val byte = digest[i / 2].toInt() and 0xff
            "0123456789abcdef"[if (i % 2 == 0) byte ushr 4 else byte and 0x0f]
        })
    }

    private data class RoadBounds(
        val minLat: Double,
        val maxLat: Double,
        val minLon: Double,
        val maxLon: Double
    )

    private fun isMatchingContinuation(older: TrackPoint, newer: TrackPoint, ordinaryGapMs: Long): Boolean {
        val gap = newer.timestampMillis - older.timestampMillis
        if (gap <= 0L || gap > MAX_STOP_CONTINUATION_MS) return false
        val distance = MATCH_DISTANCE_RESULT.get()
        Location.distanceBetween(older.latitude, older.longitude, newer.latitude, newer.longitude, distance)
        if (!distance[0].isFinite() || distance[0] > gap / 1_000f * MAX_MATCH_SPEED_MPS) return false
        if (gap <= ordinaryGapMs) return true
        if (distance[0] > MAX_STOP_GAP_DISTANCE_M) return false
        // Beyond the normal GPS window, proximity alone is not proof of continuity: a moving
        // car can disappear and later return near the same point. Stored 0 m/s can also mean
        // Android did not provide speed, so require the observed displacement across the gap to
        // be stop-like as well as having a low-speed endpoint before treating it as a pause.
        val inferredSpeedMps = distance[0] / (gap / 1_000f)
        return inferredSpeedMps < STOP_GAP_SPEED_MPS &&
            (older.speedMps < STOP_GAP_SPEED_MPS || newer.speedMps < STOP_GAP_SPEED_MPS)
    }

    companion object {
        /**
         * Stable 50 m Web-Mercator cell key shared by tracking and persistence. Keeping this
         * calculation outside SQLite lets the foreground service suppress repeat writes to a
         * cell it has already processed during the current tracking session.
         */
        internal fun exploredCellKey(latitude: Double, longitude: Double): Long? {
            if (!latitude.isFinite() || !longitude.isFinite() ||
                latitude !in -90.0..90.0 || longitude !in -180.0..180.0
            ) return null
            val wrappedLongitude = ((longitude + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
            val x = floor(
                WEB_MERCATOR_RADIUS_M * Math.toRadians(wrappedLongitude) / EXPLORED_CELL_SIZE_M
            ).toInt()
            val y = floor(
                WEB_MERCATOR_RADIUS_M *
                    ln(tan(PI / 4 + Math.toRadians(latitude.coerceIn(-85.05112878, 85.05112878)) / 2)) /
                    EXPLORED_CELL_SIZE_M
            ).toInt()
            return (x.toLong() shl 32) xor (y.toLong() and 0xffff_ffffL)
        }

        private fun trackSummaryOf(db: SQLiteDatabase): AppDatabase.TrackSummaryCache = db.rawQuery(
            "SELECT COUNT(*), MIN(timestamp_ms), MAX(timestamp_ms), COALESCE(SUM(distance_m), 0) FROM track_points",
            null
        ).use {
            check(it.moveToFirst())
            AppDatabase.TrackSummaryCache(
                pointCount = it.getLong(0),
                firstTrackAt = if (it.isNull(1)) null else it.getLong(1),
                lastTrackAt = if (it.isNull(2)) null else it.getLong(2),
                distanceMeters = it.getDouble(3)
            )
        }

        private fun roadSummaryOf(db: SQLiteDatabase): AppDatabase.RoadSummaryCache = db.rawQuery(
            """
            SELECT COUNT(*),
                COUNT(DISTINCT CASE
                    WHEN road_group_id LIKE 'x2|%' THEN NULL
                    WHEN road_group_id IS NULL OR road_group_id = '' THEN segment_id
                    ELSE road_group_id
                END)
            FROM roads
            """.trimIndent(),
            null
        ).use {
            check(it.moveToFirst())
            AppDatabase.RoadSummaryCache(
                segmentCount = it.getLong(0),
                unlockedCount = it.getLong(1)
            )
        }

        internal fun summaryOf(db: SQLiteDatabase): DataSummary {
            val track = trackSummaryOf(db)
            val roads = roadSummaryOf(db)
            return DataSummary(
                track.pointCount,
                roads.segmentCount,
                track.firstTrackAt,
                track.lastTrackAt,
                track.distanceMeters,
                roads.unlockedCount
            )
        }

        private const val HUMAN_ROAD_GROUP_PREFIX = "h2|"
        private const val EXCLUDED_ROAD_GROUP_PREFIX = "x2|"
        private const val MAX_VISIT_REPAIR_ROADS = 200
        private const val MAX_VISIT_REPAIR_ROWS = 2_000
        private const val MATCH_CLUSTER_GAP_MS = 30_000L
        private const val MATCH_ANCHOR_MAX_GAP_MS = 30_000L
        private const val MAX_STOP_CONTINUATION_MS = 15 * 60_000L
        private const val MAX_STOP_GAP_DISTANCE_M = 120f
        private const val STOP_GAP_SPEED_MPS = 2.2f
        private const val MAX_MATCH_SPEED_MPS = 100f
        // A drawable pending interval is at most 3 km (30 s at the hard speed cap).
        // This margin catches a line crossing a tiny viewport even if both endpoints are outside.
        private const val PENDING_ROUTE_QUERY_PAD_M = 3_100.0
        private val MATCH_DISTANCE_RESULT = ThreadLocal.withInitial { FloatArray(1) }

        private val TRACK_COLUMNS = arrayOf(
            "id",
            "latitude",
            "longitude",
            "accuracy_m",
            "speed_mps",
            "bearing_deg",
            "timestamp_ms",
            "matched"
        )

        // Two immutable statement shapes replace rebuilding this CTE and column list on
        // every tracking/map refresh. Binding values and query results remain identical.
        private val PENDING_ROUTE_SQL_NORMAL = pendingRouteSql("longitude BETWEEN ? AND ?")
        private val PENDING_ROUTE_SQL_WRAPPED = pendingRouteSql("(longitude >= ? OR longitude <= ?)")

        private fun pendingRouteSql(longitudeSelection: String) = """
            WITH pending(id) AS (
                SELECT id FROM track_points
                WHERE matched = 0 AND timestamp_ms >= ? AND latitude BETWEEN ? AND ? AND $longitudeSelection
            ),
            context(id) AS (
                SELECT id FROM pending
                UNION SELECT id - 1 FROM pending
                UNION SELECT id + 1 FROM pending
            )
            SELECT ${TRACK_COLUMNS.joinToString(",")}
            FROM track_points
            WHERE id IN (SELECT id FROM context)
            ORDER BY id ASC
        """.trimIndent()

        private val ROAD_COLUMNS = arrayOf(
            "segment_id",
            "name",
            "geometry_json",
            "first_unlocked_at",
            "last_driven_at",
            "min_lat",
            "max_lat",
            "min_lon",
            "max_lon",
            "drive_count",
            "drive_count_exact"
        )

        private const val EARTH_RADIUS_M = 6_371_008.8
        private const val WEB_MERCATOR_RADIUS_M = 6_378_137.0
        private const val PRIORITY_GAP_SCAN_LIMIT = 512
        private const val PRIORITY_GAP_MIN_FIXES = 4
        private const val PRIORITY_GAP_MAX_FIXES = 8
        private const val PRIORITY_GAP_MAX_MS = 30_000L
        private const val PRIORITY_GAP_MAX_ACCURACY_M = 12f
        private const val VISIT_CONTINUATION_MS = 120_000L
        private const val VISIT_DEPARTURE_DISTANCE_M = 75.0
        private const val EXPLORED_CELL_SIZE_M = 50.0
        private const val METERS_PER_DEGREE = 111_320.0
    }
}
