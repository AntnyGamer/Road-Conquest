package com.roadconquest.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class TravelDistanceTest {
    @Test fun overflowedTimestampGapCannotCountAsDriving() {
        // Long subtraction would wrap to -1 and incorrectly pass the old
        // 30-second check, while the resulting negative speed appears valid.
        assertEquals(0.0, TravelDistance.between(
            40.0, -75.0, Long.MIN_VALUE, 40.001, -75.0, Long.MAX_VALUE), 0.0)
        assertEquals(0.0, TravelDistance.between(
            40.0, -75.0, 1_000L, 40.001, -75.0, Long.MAX_VALUE), 0.0)
    }

    @Test fun nonIncreasingTimestampsAreNotDriving() {
        assertEquals(0.0, TravelDistance.between(
            40.0, -75.0, 1_000L, 40.001, -75.0, 1_000L), 0.0)
        assertEquals(0.0, TravelDistance.between(
            40.0, -75.0, 2_000L, 40.001, -75.0, 1_000L), 0.0)
    }
}
