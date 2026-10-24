package com.roadconquest.app.util

/** Check neighboring values without copying the input or constructing a list of pairs. */
internal inline fun <T> Iterable<T>.allAdjacent(predicate: (T, T) -> Boolean): Boolean {
    val values = iterator()
    if (!values.hasNext()) return true
    var previous = values.next()
    while (values.hasNext()) {
        val current = values.next()
        if (!predicate(previous, current)) return false
        previous = current
    }
    return true
}
