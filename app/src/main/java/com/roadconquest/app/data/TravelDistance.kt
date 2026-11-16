package com.roadconquest.app.data

import android.location.Location

object TravelDistance {
    private val result = ThreadLocal.withInitial { FloatArray(1) }

    fun between(lat1: Double, lon1: Double, time1: Long, lat2: Double, lon2: Double, time2: Long): Double {
        if (!lat1.isFinite() || !lon1.isFinite() || !lat2.isFinite() || !lon2.isFinite() ||
            lat1 !in -90.0..90.0 || lat2 !in -90.0..90.0 || lon1 !in -180.0..180.0 || lon2 !in -180.0..180.0 ||
            time2 <= time1) return 0.0
        // A wrapped Long subtraction must not turn an impossible timestamp
        // gap into a negative duration (and therefore a negative speed).
        val elapsedMillis = time2 - time1
        if (elapsedMillis !in 1L..30_000L) return 0.0
        val distance = result.get()
        Location.distanceBetween(lat1, lon1, lat2, lon2, distance)
        val meters = distance[0].toDouble()
        return if (meters.isFinite() && meters / (elapsedMillis / 1000.0) <= 100.0) meters else 0.0
    }
}
