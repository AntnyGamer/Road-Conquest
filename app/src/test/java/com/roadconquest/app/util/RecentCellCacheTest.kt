package com.roadconquest.app.util

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class RecentCellCacheTest {
    @Test fun reachingCapacityKeepsRecentCellsInsteadOfDiscardingEveryEntry() {
        val capacity = 4096
        val cache = RecentCellCache(capacity)
        val original = HashSet<Long>()
        var oldWrites = 0
        var newWrites = 0
        val fixes = (0L until capacity.toLong()).toList() + capacity.toLong() + (capacity - 100L until capacity.toLong())
        for (cell in fixes) {
            if (original.size >= capacity) original.clear()
            if (original.add(cell)) oldWrites++
            if (cache.add(cell)) newWrites++
            assertTrue(cache.size <= capacity)
        }
        assertEquals(capacity + 101, oldWrites)
        assertEquals(capacity + 1, newWrites)
        assertFalse(cache.add(capacity.toLong()))
        assertEquals(capacity, cache.size)
    }

    @Test fun recentAccessProtectsACellAndFailedWritesRemainRetryable() {
        val cache = RecentCellCache(2)
        assertTrue(cache.add(1)); assertTrue(cache.add(2))
        assertFalse(cache.add(1))
        assertTrue(cache.add(3)) // 2 is oldest, even though 1 was inserted first.
        assertFalse(cache.add(1))
        assertTrue(cache.add(2))
        cache.remove(2)
        assertTrue(cache.add(2))
        assertFalse(cache.add(2))
        assertEquals(2, cache.size)
    }

    @Test fun concurrentCallbacksAndWriteFailuresCannotGrowOrCorruptTheBound() {
        val cache = RecentCellCache(64)
        val workers = Executors.newFixedThreadPool(4)
        try {
            val futures = (0 until 4).map { worker -> workers.submit {
                repeat(5000) { i ->
                    val key = (i + worker * 17L) % 100
                    cache.add(key)
                    if (i % 7 == 0) cache.remove(key)
                }
            } }
            futures.forEach { it.get(10, TimeUnit.SECONDS) }
            assertTrue(cache.size in 1..64)
            cache.remove(1000)
            assertTrue(cache.add(1000)); assertFalse(cache.add(1000))
        } finally { workers.shutdownNow() }
    }
}
