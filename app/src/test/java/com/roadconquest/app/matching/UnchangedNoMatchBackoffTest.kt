package com.roadconquest.app.matching

import org.junit.Assert.assertEquals
import org.junit.Test

class UnchangedNoMatchBackoffTest {
    @Test fun unchangedFailuresEscalateOnlyAfterTwoOrdinaryRetries() {
        val policy = UnchangedNoMatchBackoff()
        val ids = listOf(1L, 2L, 3L)
        repeat(2) { assertEquals(10_000L, policy.delayAfterNoMatch(ids, setOf(2L), 10_000L)) }
        assertEquals(900_000L, policy.delayAfterNoMatch(ids, setOf(2L), 10_000L))
        repeat(3) { assertEquals(1_800_000L, policy.delayAfterNoMatch(ids, setOf(2L), 10_000L)) }
    }

    @Test fun ordinaryAndExtendedDelaysPreserveAllExistingThresholds() {
        for (usual in listOf(-1L, 0L, 10_000L, 900_000L, 3_000_000L)) {
            val policy = UnchangedNoMatchBackoff()
            val submitted = listOf(91L, 92L)
            val pending = setOf(92L)
            val expected = listOf(
                usual, usual,
                maxOf(usual, 900_000L),
                maxOf(usual, 1_800_000L),
                maxOf(usual, 1_800_000L)
            )
            expected.forEach { delay ->
                assertEquals("ordinary delay $usual", delay,
                    policy.delayAfterNoMatch(submitted, pending, usual))
            }
        }
    }

    @Test fun freshAdjacentGpsOrNewPendingEvidenceRestoresNormalRetry() {
        val policy = UnchangedNoMatchBackoff()
        repeat(4) { policy.delayAfterNoMatch(listOf(1L, 2L, 3L), setOf(2L), 10_000L) }
        assertEquals(10_000L, policy.delayAfterNoMatch(listOf(1L, 2L, 3L, 4L), setOf(2L), 10_000L))
        repeat(4) { policy.delayAfterNoMatch(listOf(1L, 2L, 3L), setOf(2L), 10_000L) }
        assertEquals(10_000L, policy.delayAfterNoMatch(listOf(1L, 2L, 3L), setOf(2L, 3L), 10_000L))
    }

    @Test fun acceptedEvidenceOrProviderOutageClearsRetryHistory() {
        val policy = UnchangedNoMatchBackoff()
        repeat(4) { policy.delayAfterNoMatch(listOf(1L, 2L), setOf(1L), 10_000L) }
        policy.clear(setOf(1L))
        assertEquals(10_000L, policy.delayAfterNoMatch(listOf(1L, 2L), setOf(1L), 10_000L))
    }

    @Test fun independentIslandsAndBoundedCacheNeverRetainUnboundedHistory() {
        val policy = UnchangedNoMatchBackoff()
        repeat(3) { policy.delayAfterNoMatch(listOf(1L, 2L), setOf(1L), 10_000L) }
        repeat(65) { index ->
            val id = index.toLong() + 10
            policy.delayAfterNoMatch(listOf(id), setOf(id), 10_000L)
        }
        assertEquals(10_000L, policy.delayAfterNoMatch(listOf(1L, 2L), setOf(1L), 10_000L))
    }
}
