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

    fun supports(context: Context, place: PlaceDiscovery, geometryJson: String): Boolean =
        supports(AppDatabase.get(context.applicationContext).readableDatabase, place, geometryJson)

    internal fun supports(db: SQLiteDatabase, place: PlaceDiscovery, geometryJson: String): Boolean {
        if (place.kind != PlaceKind.TOWN) return true
        val polygon = runCatching { JSONObject(geometryJson) }.getOrNull() ?: return false
        var observedFixes = false
        var containedFixes = 0
        db.rawQuery(
            """SELECT latitude, longitude FROM track_points
               WHERE timestamp_ms BETWEEN ? AND ?
                 AND accuracy_m BETWEEN 0.01 AND 25
               ORDER BY ABS(timestamp_ms - ?) LIMIT ?""",
            arrayOf(
                (place.visitedAt - VISIT_WINDOW_MS).toString(),
                (place.visitedAt + VISIT_WINDOW_MS).toString(),
                place.visitedAt.toString(),
                MAX_EVIDENCE_FIXES.toString()
            )
        ).use { cursor ->
            while (cursor.moveToNext()) {
                observedFixes = true
                if (PlaceGeometry.contains(polygon, cursor.getDouble(0), cursor.getDouble(1))) {
                    containedFixes++
                    // A single accepted accurate driving point is sufficient for
                    // short borough crossings; don't impose an arbitrary distance
                    // threshold that would miss a legitimate visited municipality.
                    return true
                }
            }
        }
        if (observedFixes) return false
        // Zero-point baseline and very old exports may have no retained GPS
        // samples at the original visit time. Only then use its original point.
        return PlaceGeometry.contains(polygon, place.latitude, place.longitude)
    }
}
