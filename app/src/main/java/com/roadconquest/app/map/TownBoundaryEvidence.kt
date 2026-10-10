package com.roadconquest.app.map

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.roadconquest.app.data.AppDatabase
import com.roadconquest.app.data.PlaceDiscovery
import com.roadconquest.app.data.PlaceKind
import org.json.JSONObject

/**
 * An old visited-place coordinate may be the CENTER of a 1-mile sampling cell,
 * not where a car actually was. Never verify a municipal boundary with that
 * approximate center while accurate GPS history exists for the visit.
 *
 * This read-only check does not remove discoveries or alter awarded points.
 */
internal object TownBoundaryEvidence {
    private const val VISIT_WINDOW_MS = 30_000L
    private const val MAX_EVIDENCE_FIXES = 120

    fun supports(context: Context, place: PlaceDiscovery, geometry: JSONObject): Boolean =
        supports(AppDatabase.get(context.applicationContext).readableDatabase, place, geometry)

    internal fun supports(db: SQLiteDatabase, place: PlaceDiscovery, geometry: JSONObject): Boolean {
        if (place.kind != PlaceKind.TOWN) return true
        val visits = linkedSetOf(place.visitedAt)
        // A genuine later revisit can confirm a municipality whose old first
        // discovery came from a coarse sampling grid.
        db.rawQuery(
            "SELECT value FROM progression_counters WHERE counter_key = ?",
            arrayOf("last_visit:town:${place.key}")
        ).use { cursor -> if (cursor.moveToFirst()) visits += cursor.getLong(0) }

        var observedFixes = false
        for (visitedAt in visits) {
            db.rawQuery(
                """SELECT latitude, longitude FROM track_points
                   WHERE timestamp_ms BETWEEN ? AND ?
                     AND accuracy_m BETWEEN 0.01 AND 25
                   ORDER BY ABS(timestamp_ms - ?) LIMIT ?""",
                arrayOf(
                    (visitedAt - VISIT_WINDOW_MS).coerceAtLeast(0L).toString(),
                    (visitedAt + VISIT_WINDOW_MS).toString(),
                    visitedAt.toString(),
                    MAX_EVIDENCE_FIXES.toString()
                )
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    observedFixes = true
                    if (PlaceGeometry.contains(geometry, cursor.getDouble(0), cursor.getDouble(1))) {
                        // A short borough crossing can contain only one accurate fix.
                        return true
                    }
                }
            }
        }
        if (observedFixes) return false
        // The original exact zero point may predate all track samples.
        return PlaceGeometry.contains(geometry, place.latitude, place.longitude)
    }
}
