package com.roadconquest.app.map

import org.maplibre.geojson.FeatureCollection

/** UI-thread publication of immutable collections; remember only successful updates. */
internal class DistinctGeoJson(private val initial: FeatureCollection) {
    private var applied = initial

    fun publish(next: FeatureCollection, update: (FeatureCollection) -> Boolean): Boolean {
        if (next == applied || !update(next)) return false
        applied = next
        return true
    }

    /** A newly installed native source starts with the initial empty collection. */
    fun reset() { applied = initial }
}
