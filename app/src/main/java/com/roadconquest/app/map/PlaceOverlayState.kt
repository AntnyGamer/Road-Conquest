package com.roadconquest.app.map

import java.util.concurrent.atomic.AtomicReference

/** Exact immutable snapshots, shared between the overlay worker and UI publication. */
internal class OverlayPublication {
    private data class Applied(val data: List<PlaceOverlayData>, val cacheGeneration: Long)
    private val applied = AtomicReference<Applied?>()

    fun matches(data: List<PlaceOverlayData>, cacheGeneration: Long): Boolean {
        val previous = applied.get() ?: return false
        if (previous.cacheGeneration != cacheGeneration || previous.data != data) return false
        // Equal refreshed snapshots already own their strings. Release the old equal
        // payload, without letting a concurrent style/reset resurrect old state.
        return applied.compareAndSet(previous,
            if (previous.data === data) previous else Applied(data, cacheGeneration))
    }

    /** Called on the UI thread with a detached snapshot; remember only complete uploads. */
    fun publish(data: List<PlaceOverlayData>, cacheGeneration: Long, update: () -> Boolean): Boolean {
        if (matches(data, cacheGeneration) || !update()) return false
        applied.set(Applied(data, cacheGeneration))
        return true
    }

    fun reset() { applied.set(null) }
}

/** Worker-owned bounds, keyed by complete immutable data; cache unknown bounds too. */
internal class OverlayBoundsCache(
    private val readBounds: (String) -> OverlayViewport? = OverlayViewport::ofGeometry
) {
    private val bounds = HashMap<PlaceOverlayData, OverlayViewport?>()

    fun get(place: PlaceOverlayData): OverlayViewport? {
        if (bounds.containsKey(place)) return bounds[place]
        return readBounds(place.geometryJson).also { bounds[place] = it }
    }

    fun clear() { bounds.clear() }
}

/** Worker-owned cache for a complete visible town snapshot. Panning an unchanged
 * set must not repeat hundreds of coverage scans after the small JTS cache evicts
 * old shapes. Keep exact immutable inputs, never trust geometry hash equality.
 */
internal class TownCompletionSnapshotCache {
    private var grid: Pair<Long, Long>? = null
    private var boundaries = -1L
    private var input: List<PlaceOverlayData> = emptyList()
    private var output: List<PlaceOverlayData> = emptyList()

    fun decorate(data: List<PlaceOverlayData>, gridState: Pair<Long, Long>, boundaryRevision: Long,
                 calculate: (PlaceOverlayData) -> PlaceOverlayData): List<PlaceOverlayData> {
        if (grid == gridState && boundaries == boundaryRevision && input == data) return output
        val calculated = data.map(calculate)
        grid = gridState
        boundaries = boundaryRevision
        input = data
        output = calculated
        return calculated
    }
}
