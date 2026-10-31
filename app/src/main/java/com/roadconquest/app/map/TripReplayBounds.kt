package com.roadconquest.app.map

import com.roadconquest.app.data.SavedTripPoint
import org.maplibre.android.geometry.LatLngBounds

/** The smallest longitude interval containing a replay, including date-line trips. */
internal fun tripReplayBounds(points: List<SavedTripPoint>): LatLngBounds {
    require(points.isNotEmpty())
    val first = points.first()
    var south = first.latitude
    var north = first.latitude
    var west = first.longitude
    var east = first.longitude
    // Replays can contain many fixes. Collect all four extrema in one pass.
    for (point in points) {
        south = minOf(south, point.latitude)
        north = maxOf(north, point.latitude)
        west = minOf(west, point.longitude)
        east = maxOf(east, point.longitude)
    }
    if (east - west > 180.0) {
        val longitudes = DoubleArray(points.size) { points[it].longitude }.apply { sort() }
        var largestGap = west + 360.0 - east
        for (index in 0 until longitudes.lastIndex) {
            val gap = longitudes[index + 1] - longitudes[index]
            if (gap > largestGap) {
                largestGap = gap
                west = longitudes[index + 1]
                east = longitudes[index]
            }
        }
        // MapLibre accepts unwrapped longitudes, with west <= east. Using
        // 179..181 keeps the camera over the date line instead of Greenwich.
        if (east < west) east += 360.0
    }
    return LatLngBounds.from(north, east, south, west)
}
