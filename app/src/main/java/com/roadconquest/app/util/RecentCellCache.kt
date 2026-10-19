package com.roadconquest.app.util

/** Bounded duplicate-write suppression; eviction never removes persisted exploration. */
internal class RecentCellCache(private val capacity: Int) {
    init { require(capacity > 0) }
    private val keys = LinkedHashMap<Long, Unit>(16, .75f, true)

    @Synchronized fun add(key: Long): Boolean {
        val added = keys.put(key, Unit) == null
        if (keys.size > capacity) keys.entries.iterator().run { next(); remove() }
        return added
    }

    /** Failed writes must allow a later fix to retry the same cell. */
    @Synchronized fun remove(key: Long) { keys.remove(key) }

    @get:Synchronized internal val size: Int get() = keys.size
}
