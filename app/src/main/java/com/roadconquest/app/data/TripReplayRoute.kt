package com.roadconquest.app.data

/** The route behind the replay marker, preserving breaks in the recording. */
object TripReplayRoute {
    /** Index only recording breaks once per loaded route, retaining every sampled fix. */
    fun prepare(points: List<SavedTripPoint>): Prepared {
        val segments = ArrayList<IntRange>()
        var start = 0
        for (index in 1 until points.size) {
            if (points[index].breakBefore) {
                if (index - start >= 2) segments += start until index
                start = index
            }
        }
        if (points.lastIndex - start >= 1) segments += start..points.lastIndex
        return Prepared(segments)
    }

    class Prepared internal constructor(private val segments: List<IntRange>) {
        /**
         * Every returned range has two or more recorded fixes. A breakBefore flag
         * starts a new segment rather than joining it to the preceding point.
         * Calling this with an earlier index also hides any future route.
         */
        fun visibleRanges(lastVisibleIndex: Int): List<IntRange> {
            if (lastVisibleIndex < 1) return emptyList()
            val ranges = ArrayList<IntRange>()
            for (segment in segments) {
                if (segment.first >= lastVisibleIndex) break
                ranges += segment.first..minOf(segment.last, lastVisibleIndex)
                if (segment.last >= lastVisibleIndex) break
            }
            return ranges
        }
    }
}
