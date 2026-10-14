package com.roadconquest.app.data

import android.content.Context
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

class TripHistoryRepository(context: Context) {
    private val helper = AppDatabase.get(context.applicationContext)

    /** Stops after the most recent 100 completed groups instead of loading full GPS history. */
    fun recentTrips(limit: Int = 100): List<SavedTrip> = synchronized(helper.historyLock) {
        require(limit in 1..100)
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

    /** Read a bounded route sample, always retaining the first and last points. */
    fun replay(trip: SavedTrip, maxPoints: Int = 2500): List<SavedTripPoint> =
        synchronized(helper.historyLock) {
            require(maxPoints in 2..5000)
            if (trip.historyGeneration != helper.historyGeneration) return@synchronized emptyList()
            val db = helper.readableDatabase
            val bounds = arrayOf(trip.firstId.toString(), trip.lastId.toString())
            val count = db.rawQuery(
                "SELECT COUNT(*) FROM track_points WHERE id BETWEEN ? AND ?", bounds
            ).use { it.moveToFirst(); it.getLong(0) }
            val stride = ceil(count.toDouble() / maxPoints).toInt().coerceAtLeast(1)
            val selected = ArrayList<SavedTripPoint>(minOf(maxPoints, count.toInt()))
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
            last?.let { if (selected.lastOrNull()?.timestamp != it.timestamp) selected += it }
            selected
        }

    companion object {
        internal const val TRIP_GAP_MS = 15 * 60_000L
        internal fun isTripBoundary(lastTime: Long, olderTime: Long): Boolean =
            olderTime > lastTime || lastTime - olderTime >= TRIP_GAP_MS
    }
}
