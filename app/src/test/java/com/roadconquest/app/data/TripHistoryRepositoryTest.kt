package com.roadconquest.app.data

import org.junit.Assert.*
import org.junit.Test

class TripHistoryRepositoryTest {
    @Test fun stoplightsStayInOneTripButLongParkedStopsSplit() {
        val gap = TripHistoryRepository.TRIP_GAP_MS
        assertFalse(TripHistoryRepository.isTripBoundary(1_000_000L, 1_000_000L - 120_000L))
        assertFalse(TripHistoryRepository.isTripBoundary(1_000_000L, 1_000_000L - gap + 1L))
        assertTrue(TripHistoryRepository.isTripBoundary(1_000_000L, 1_000_000L - gap))
    }

    @Test fun invalidChronologyNeverMergesUnrelatedSegments() {
        assertTrue(TripHistoryRepository.isTripBoundary(1000, 2000))
        assertFalse(TripHistoryRepository.isTripBoundary(1000, 1000))
    }
}
