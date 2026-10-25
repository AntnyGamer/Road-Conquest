package com.roadconquest.app.matching

import org.junit.Assert.*
import org.junit.Test

class ExactMatchResponseCacheTest {
    @Test fun retainsFourExactResponsesAndEvictsTheLeastRecentlyUsed() {
        val cache = ExactMatchResponseCache { 1_000_000L }
        repeat(4) { cache.put("request$it", "body$it") }
        assertEquals("body0", cache.get("request0"))
        cache.put("request4", "body4")
        assertNull(cache.get("request1"))
        for (i in listOf(0, 2, 3, 4)) assertEquals("body$i", cache.get("request$i"))
        assertNull(cache.get("changed request0"))
    }

    @Test fun hitsDoNotExtendTheOriginalFifteenMinuteExpiryAndClockRollbackMisses() {
        var now = 1_000_000L
        val cache = ExactMatchResponseCache { now }
        cache.put("request", "body")
        now += 900_000L
        assertEquals("body", cache.get("request"))
        now++
        assertNull(cache.get("request"))
        cache.put("request", "fresh")
        now--
        assertNull(cache.get("request"))
    }

    @Test fun aggregateTextStaysBoundedAndOversizedResponsesDoNotEvictUsableEntries() {
        val cache = ExactMatchResponseCache { 0L }
        cache.put("first", "a".repeat(150_000))
        cache.put("second", "b".repeat(150_000))
        assertNull(cache.get("first"))
        assertEquals(150_000, cache.get("second")?.length)
        cache.put("too big", "x".repeat(256_001))
        cache.put("x".repeat(32_769), "small")
        assertNull(cache.get("too big"))
        assertEquals(150_000, cache.get("second")?.length)
        cache.put("second", "replacement")
        cache.put("largest", "x".repeat(256_000))
        assertEquals("replacement", cache.get("second"))
        assertEquals(256_000, cache.get("largest")?.length)
    }
}
