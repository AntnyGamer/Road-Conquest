package com.roadconquest.app.data

/** The route behind the replay marker, preserving breaks in the recording. */
object TripReplayRoute {
    /**
     * Every returned range has two or more recorded fixes. A breakBefore flag
     * starts a new segment rather than joining it to the preceding point.
     * Calling this with an earlier index also hides any future route.
     */
    fun visibleRanges(points: List<SavedTripPoint>, lastVisibleIndex: Int): List<IntRange> {
        if (points.size < 2 || lastVisibleIndex < 1) return emptyList()
        val end = lastVisibleIndex.coerceAtMost(points.lastIndex)
        val ranges = ArrayList<IntRange>()
        var start = 0
        for (index in 1..end) {
            if (points[index].breakBefore) {
                if (index - start >= 2) ranges += start until index
                start = index
            }
        }
        if (end - start >= 1) ranges += start..end
        return ranges
    }
}
