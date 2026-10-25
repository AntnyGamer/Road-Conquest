package com.roadconquest.app.map

import org.junit.Assert.*
import org.junit.Test

class MotionModeTrackerTest {
    @Test fun walkingNeedsSustainedEvidenceAndStandingStillDoesNotChangeTheMode() {
        val motion = MotionModeTracker()
        for (time in 0L..9_000L step 3_000L) assertFalse(motion.observe(time, 1.4f, 4f))
        assertTrue(motion.observe(12_000L, 1.4f, 4f))
        assertTrue(motion.observe(15_000L, 0f, 4f))
        val stationary = MotionModeTracker()
        for (time in 0L..60_000L step 3_000L) assertFalse(stationary.observe(time, 0f, 4f))
    }

    @Test fun drivingRestoresTheCarButOneSpeedSpikeDoesNot() {
        val motion = MotionModeTracker()
        for (time in 0L..12_000L step 3_000L) motion.observe(time, 1.5f, 4f)
        assertTrue(motion.observe(15_000L, 9f, 4f))
        assertFalse(motion.observe(18_000L, 9f, 4f))
        // Slow turns and stoplights retain a recently confirmed car.
        for (time in 21_000L..60_000L step 3_000L) assertFalse(motion.observe(time, 1.4f, 4f))
        assertTrue(motion.observe(63_000L, 1.4f, 4f))
    }

    @Test fun poorMissingDuplicateAndOldEvidenceCannotSelectWalking() {
        val motion = MotionModeTracker()
        for (time in 0L..30_000L step 3_000L) motion.observe(time, 1.5f, 60f)
        assertFalse(motion.isWalking)
        for (time in 33_000L..60_000L step 3_000L) motion.observe(time, Float.NaN, 4f)
        assertFalse(motion.isWalking)
        motion.observe(63_000L, 1.5f, 4f)
        assertFalse(motion.observe(63_000L, 1.5f, 4f))
        assertFalse(motion.observe(60_000L, 1.5f, 4f))
        assertFalse(motion.observe(100_000L, 1.5f, 4f))
        assertTrue(motion.observe(112_000L, 1.5f, 4f))
        motion.reset()
        assertFalse(motion.isWalking)
    }
}
