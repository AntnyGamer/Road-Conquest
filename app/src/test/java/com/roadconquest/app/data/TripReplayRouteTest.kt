package com.roadconquest.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TripReplayRouteTest {
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
