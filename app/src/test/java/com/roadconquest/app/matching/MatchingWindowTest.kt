package com.roadconquest.app.matching

import com.roadconquest.app.data.MatchedRoad
import com.roadconquest.app.data.TrackPoint
import org.junit.Assert.*
import org.junit.Test

class MatchingWindowTest {
    private fun points(size: Int) = MutableList(size) { i ->
        TrackPoint(i + 1L, 40.0, -74.0, 3f, 8f, 0f, i * 1000L, false)
    }

    private fun matcher(offset: Int, limit: Int) = object : RoadMatcher {
        override val lastUsedProviderPointOffset = offset
        override val lastUsedProviderPointLimit = limit
        override val unavailable = false
        override fun rateLimitRemainingMs() = 0L
        override fun match(points: List<TrackPoint>): MatchResult? = null
    }

    @Test fun providerWindowCopiesOnlySubmittedFixesAndRemainsIndependentOfTheQueue() {
        val source = points(1000)
        for ((offset, limit) in listOf(0 to 200, 46 to 25, 998 to 25, 1000 to 25,
            Int.MAX_VALUE to Int.MAX_VALUE, 0 to 0, 0 to Int.MAX_VALUE)) {
            val reads = ArrayList<Int>()
            val observed = object : AbstractList<TrackPoint>() {
                override val size get() = source.size
                override fun get(index: Int): TrackPoint { reads += index; return source[index] }
            }
            val selected = matcher(offset, limit).lastSubmittedPoints(observed)
            val expected = source.drop(offset).take(limit)
            assertEquals(expected, selected)
            val start = minOf(offset, source.size)
            assertEquals("Unsent fixes must not be copied", (start until start + expected.size).toList(), reads)
        }
        val snapshot = matcher(46, 25).lastSubmittedPoints(source)
        val ids = snapshot.map { it.id }
        source.clear()
        assertEquals(ids, snapshot.map { it.id })
        assertThrows(IllegalArgumentException::class.java) { matcher(-1, 25).lastSubmittedPoints(source) }
        assertThrows(IllegalArgumentException::class.java) { matcher(0, -1).lastSubmittedPoints(source) }
    }

    @Test fun completeCoverageStillRequiresEligibleWorkAndConfidenceForEveryPendingFix() {
        val points = points(3)
        val threshold = RoadGeometryValidator.MIN_ACCEPTABLE_CONFIDENCE
        val road = MatchedRoad("Road", "[]", 0L, 2000L, threshold)
        val complete = MatchResult(listOf(road), mapOf(1L to threshold, 2L to threshold))
        assertTrue(complete.completelyCoversPending(points, setOf(1L, 2L)))
        assertFalse(complete.completelyCoversPending(points))
        assertFalse(complete.completelyCoversPending(points, emptySet()))
        assertFalse(complete.completelyCoversPending(emptyList()))
        assertFalse(complete.completelyCoversPending(points.map { it.copy(matched = true) }))
        assertFalse(complete.copy(roads = emptyList()).completelyCoversPending(points, setOf(1L)))
        for (confidence in listOf(0.0, threshold - 0.001, Double.NaN)) {
            assertFalse(complete.copy(matchedPointConfidences = mapOf(1L to confidence))
                .completelyCoversPending(points, setOf(1L)))
            assertFalse(complete.copy(roads = listOf(road.copy(confidence = confidence)))
                .completelyCoversPending(points, setOf(1L)))
        }
    }
}
