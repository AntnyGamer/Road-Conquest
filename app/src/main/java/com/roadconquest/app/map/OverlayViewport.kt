package com.roadconquest.app.map

import org.json.JSONArray
import org.json.JSONObject

/**
 * Conservative viewport prefilter for town overlays. A missing/odd envelope
 * means "include", never silently hide a discovered town or its border.
 * Longitudes outside the ordinary world interval are kept unfiltered so
 * dateline/world-wrap polygons cannot be accidentally clipped.
 */
internal data class OverlayViewport(
    val west: Double, val east: Double, val south: Double, val north: Double
) {
    init { require(west <= east && south <= north) }

    internal fun intersects(shape: OverlayViewport?): Boolean =
        shape == null ||
            (shape.east >= west && shape.west <= east &&
                shape.north >= south && shape.south <= north)

    companion object {
        /** Padding absorbs camera movement before the next idle callback. */
        internal fun padded(
            west: Double, east: Double, south: Double, north: Double
        ): OverlayViewport? {
            if (!west.isFinite() || !east.isFinite() || !south.isFinite() || !north.isFinite() ||
                west > east || south > north || east - west > 180.0 ||
                north - south > 120.0
            ) return null
            val longitudePad = (east - west).coerceAtLeast(0.2) * 1.5
            val latitudePad = (north - south).coerceAtLeast(0.2) * 1.5
            val paddedWest = west - longitudePad
            val paddedEast = east + longitudePad
            if (paddedWest < -180.0 || paddedEast > 180.0) return null
            return OverlayViewport(
                paddedWest, paddedEast,
                (south - latitudePad).coerceAtLeast(-90.0),
                (north + latitudePad).coerceAtMost(90.0)
            )
        }

        internal fun ofGeometry(geometryJson: String): OverlayViewport? = runCatching {
            val geometry = JSONObject(geometryJson)
            val type = geometry.optString("type")
            if (type != "Polygon" && type != "MultiPolygon") return@runCatching null
            val coordinates = geometry.optJSONArray("coordinates") ?: return@runCatching null
            var west = Double.POSITIVE_INFINITY
            var east = Double.NEGATIVE_INFINITY
            var south = Double.POSITIVE_INFINITY
            var north = Double.NEGATIVE_INFINITY
            fun walk(nodes: JSONArray, level: Int) {
                if (level > 4) return
                if (nodes.length() >= 2 && nodes.opt(0) is Number && nodes.opt(1) is Number) {
                    val lon = nodes.optDouble(0)
                    val lat = nodes.optDouble(1)
                    if (lon.isFinite() && lat.isFinite()) {
                        west = minOf(west, lon)
                        east = maxOf(east, lon)
                        south = minOf(south, lat)
                        north = maxOf(north, lat)
                    }
                } else {
                    for (i in 0 until nodes.length()) {
                        nodes.optJSONArray(i)?.let { walk(it, level + 1) }
                    }
                }
            }
            walk(coordinates, 0)
            if (!west.isFinite() || !east.isFinite() || !south.isFinite() || !north.isFinite() ||
                west < -180.0 || east > 180.0 || south < -90.0 || north > 90.0
            ) null else OverlayViewport(west, east, south, north)
        }.getOrNull()
    }
}
