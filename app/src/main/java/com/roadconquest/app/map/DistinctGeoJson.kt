package com.roadconquest.app.map

/** UI-thread publication of immutable GeoJSON values; remember only successful updates. */
internal class DistinctGeoJson<T>(private val initial: T) {
    private var applied = initial

    fun publish(next: T, update: (T) -> Boolean): Boolean {
        if (next == applied) {
            // The current snapshot already owns this equal immutable value. Drop the
            // old payload reference rather than retaining two equal JSON/route objects.
            applied = next
            return false
        }
        if (!update(next)) return false
        applied = next
        return true
    }

    /** Remember the exact data used to construct a newly installed native source. */
    fun reset(installed: T = initial) { applied = installed }
}
