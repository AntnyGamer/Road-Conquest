package com.roadconquest.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TripReplayRouteTest {
    // Frozen pre-index implementation: compare every prefix, including backwards seeks.
    private fun reference(points: List<SavedTripPoint>, visibleIndex: Int): List<IntRange> {
        if (points.size < 2 || visibleIndex < 1) return emptyList()
        val end = visibleIndex.coerceAtMost(points.lastIndex)
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

    @Test fun preparedRangesMatchEverySmallGapCombinationAndPrefix() {
        for (size in 0..9) {
            for (mask in 0 until (1 shl size)) {
                val points = (0 until size).map { i ->
                    SavedTripPoint(40.0, i.toDouble(), i.toLong(), mask and (1 shl i) != 0)
                }
                val prepared = TripReplayRoute.prepare(points)
                for (end in (size + 2) downTo -1) {
                    assertEquals("size=$size mask=$mask end=$end",
                        reference(points, end), prepared.visibleRanges(end))
                }
            }
        }
    }

    @Test fun preparedLongReplayDoesNotRescanPointsOnEveryTick() {
        var reads = 0
        val points = object : AbstractList<SavedTripPoint>() {
            override val size = 5000
            override fun get(index: Int): SavedTripPoint {
                reads++
                return SavedTripPoint(40.0, index * 0.0001, index.toLong(), index % 997 == 0)
            }
        }
        val prepared = TripReplayRoute.prepare(points)
        assertEquals(4999, reads)
        reads = 0
        for (end in 0 until 5000) prepared.visibleRanges(end)
        for (end in 4999 downTo 0) prepared.visibleRanges(end)
        assertEquals("Seeking should use indexed breaks without accessing coordinates again", 0, reads)
        assertEquals(reference(points, 4999), prepared.visibleRanges(4999))
    }

    private val route = (0..5).map { index ->
        SavedTripPoint(40.0, index.toDouble(), index.toLong(), breakBefore = index == 3)
    }

    @Test fun revealsOnlyRoadBehindMarkerAndPreservesBreaks() {
        assertTrue(TripReplayRoute.visibleRanges(route, 0).isEmpty())
        assertEquals(listOf(0..1), TripReplayRoute.visibleRanges(route, 1))
        assertEquals(listOf(0..2), TripReplayRoute.visibleRanges(route, 2))
        // A single point immediately after a gap must not create a bridge.
        assertEquals(listOf(0..2), TripReplayRoute.visibleRanges(route, 3))
        assertEquals(listOf(0..2, 3..4), TripReplayRoute.visibleRanges(route, 4))
        assertEquals(listOf(0..2, 3..5), TripReplayRoute.visibleRanges(route, 5))
    }

    @Test fun scrubbingBackwardRemovesFutureRoute() {
        assertEquals(listOf(0..2, 3..5), TripReplayRoute.visibleRanges(route, 99))
        assertEquals(listOf(0..1), TripReplayRoute.visibleRanges(route, 1))
        assertTrue(TripReplayRoute.visibleRanges(route, -1).isEmpty())
        assertTrue(TripReplayRoute.visibleRanges(emptyList(), 100).isEmpty())
    }

    @Test fun consecutiveBreaksNeverCreateSpuriousBridges() {
        val separated = (0..6).map { i ->
            SavedTripPoint(40.0, i.toDouble(), i.toLong(), breakBefore = i in setOf(1, 2, 5))
        }
        assertEquals(listOf(2..4, 5..6), TripReplayRoute.visibleRanges(separated, 6))
        assertEquals(listOf(2..4), TripReplayRoute.visibleRanges(separated, 5))
        assertEquals(listOf(2..3), TripReplayRoute.visibleRanges(separated, 3))
        assertTrue(TripReplayRoute.visibleRanges(separated.take(2), 1).isEmpty())
    }

    @Test fun firstRecordedPointAfterBreakStartsANewLine() {
        val short = listOf(
            SavedTripPoint(0.0, 0.0, 0L),
            SavedTripPoint(0.0, 1.0, 1L, breakBefore = true),
            SavedTripPoint(0.0, 2.0, 2L)
        )
        assertEquals(listOf(1..2), TripReplayRoute.visibleRanges(short, 2))
    }
}
