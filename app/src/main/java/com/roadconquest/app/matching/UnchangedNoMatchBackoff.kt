package com.roadconquest.app.matching

/**
 * Quota protection for valid, unchanged NoMatch responses. No GPS coordinates or
 * provider response bodies are retained. A changed GPS/context point resets the
 * delay immediately; unresolved fixes always remain in the persistent queue.
 * This tiny session-only cache avoids a database migration or stale permanent lockout.
 */
internal class UnchangedNoMatchBackoff {
    private data class Failure(val submitted: List<Long>, val pending: Set<Long>, val count: Int)
    private val failures = LinkedHashMap<Long, Failure>(64, 0.75f, true)

    fun delayAfterNoMatch(submitted: List<Long>, pending: Set<Long>, usualDelayMs: Long): Long {
        val key = pending.minOrNull() ?: return usualDelayMs
        val previous = failures[key]
        val count = if (previous?.submitted == submitted && previous.pending == pending) {
            (previous.count + 1).coerceAtMost(4)
        } else 1
        failures[key] = Failure(submitted.toList(), pending.toSet(), count)
        if (failures.size > 64) failures.remove(failures.keys.first())
        return maxOf(usualDelayMs, when (count) {
            1, 2 -> usualDelayMs
            3 -> 15 * 60_000L
            else -> 30 * 60_000L
        })
    }

    fun clear(pending: Set<Long>) {
        pending.minOrNull()?.let { failures.remove(it) }
    }
}
