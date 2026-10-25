package com.roadconquest.app.matching

/** Exact provider responses only. Callers still validate geometry against their current point IDs. */
internal class ExactMatchResponseCache(private val now: () -> Long = System::currentTimeMillis) {
    private data class Entry(val body: String, val storedAt: Long)
    private val entries = LinkedHashMap<String, Entry>(4, 0.75f, true)
    private var retainedChars = 0
    val isEmpty: Boolean get() = entries.isEmpty()

    fun get(request: String): String? {
        val entry = entries[request] ?: return null
        if (now() - entry.storedAt in 0L..900_000L) return entry.body
        entries.remove(request)
        retainedChars -= request.length + entry.body.length
        return null
    }

    fun put(request: String, body: String) {
        // Keep the old 256k response limit and at most one gateway-sized request
        // worth of extra text in total, even when retaining several small traces.
        if (body.length > 256_000 || request.length > 32_768) return
        entries.remove(request)?.let { retainedChars -= request.length + it.body.length }
        entries[request] = Entry(body, now())
        retainedChars += request.length + body.length
        while (entries.size > 4 || retainedChars > 256_000 + 32_768) {
            val oldest = entries.entries.iterator()
            val entry = oldest.next()
            retainedChars -= entry.key.length + entry.value.body.length
            oldest.remove()
        }
    }
}
