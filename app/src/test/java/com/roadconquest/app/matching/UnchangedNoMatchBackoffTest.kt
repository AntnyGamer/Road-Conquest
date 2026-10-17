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
