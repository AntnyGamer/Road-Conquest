package com.roadconquest.app.util

import android.location.Location
import android.location.LocationManager

object LocationProviders {
    /** Enabled providers in the exact preference order used for both preview and tracking. */
    fun available(locationManager: LocationManager): List<String> {
        val enabled = locationManager.getProviders(true)
        val fused = LocationManager.FUSED_PROVIDER
        return buildList(3) {
            if (enabled.contains(fused)) add(fused)
            if (enabled.contains(LocationManager.GPS_PROVIDER)) add(LocationManager.GPS_PROVIDER)
            if (enabled.contains(LocationManager.NETWORK_PROVIDER)) add(LocationManager.NETWORK_PROVIDER)
        }
    }

    /** Request both precise sources by default, with network only when neither registers. */
    fun registerHighAccuracy(locationManager: LocationManager, register: (String) -> Boolean) {
        val enabled = available(locationManager)
        var registered = false
        for (provider in enabled) {
            if (provider != LocationManager.NETWORK_PROVIDER && register(provider)) registered = true
        }
        if (!registered && LocationManager.NETWORK_PROVIDER in enabled) register(LocationManager.NETWORK_PROVIDER)
    }

    /** A baseline must come from a fix produced after the current location request began. */
    fun isFixSince(location: Location, requestElapsedNanos: Long, requestWallMillis: Long): Boolean {
        val fixElapsedNanos = location.elapsedRealtimeNanos
        return if (fixElapsedNanos > 0L && requestElapsedNanos > 0L) {
            fixElapsedNanos >= requestElapsedNanos
        } else {
            location.time > 0L && location.time >= requestWallMillis
        }
    }

    /**
     * Return measured speed only when Android supplies usable speed metadata. This is shared by
     * driving acceptance and persistence so the matcher never treats a speed value that the
     * driving filter already rejected as authoritative evidence.
     */
    fun reliableMeasuredSpeed(location: Location): Float = measuredSpeedOrNull(location) ?: 0f

    /** Keep a measured stop distinct from missing/unreliable speed metadata. */
    fun measuredSpeedOrNull(location: Location): Float? {
        if (!location.hasSpeed() || !location.speed.isFinite() || location.speed < 0f) return null
        if (location.hasSpeedAccuracy()) {
            val uncertainty = location.speedAccuracyMetersPerSecond
            if (!uncertainty.isFinite() || uncertainty > MAX_MEASURED_SPEED_ACCURACY_MPS) return null
        }
        return location.speed
    }

    /** Compare near-contemporaneous fixes by reported accuracy, without holding a moving car stale. */
    fun isBetterFix(candidate: Location, previous: Location?): Boolean {
        if (previous == null) return true
        val elapsed = if (candidate.elapsedRealtimeNanos > 0 && previous.elapsedRealtimeNanos > 0) {
            (candidate.elapsedRealtimeNanos - previous.elapsedRealtimeNanos) / 1_000_000L
        } else candidate.time - previous.time
        if (elapsed < 0) return false
        if (elapsed > 2_000L) return true
        if (!candidate.hasAccuracy()) return false
        if (!previous.hasAccuracy()) return true
        if (elapsed == 0L) return candidate.accuracy < previous.accuracy
        return candidate.provider == previous.provider || candidate.accuracy <= previous.accuracy
    }

    private const val MAX_MEASURED_SPEED_ACCURACY_MPS = 4f
}
