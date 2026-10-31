package com.roadconquest.app.map

import com.roadconquest.app.data.PlaceKind
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37], manifest = Config.NONE)
class PlaceOverlayStateTest {
    private val first = """{"type":"Polygon","coordinates":[[[98.8106,-34.0223],[98.8206,-34.0223],[98.8206,-34.0123],[98.8106,-34.0123],[98.8106,-34.0223]]]}"""
    private val second = """{"type":"Polygon","coordinates":[[[-75.6948,55.7051],[-75.6848,55.7051],[-75.6848,55.7151],[-75.6948,55.7151],[-75.6948,55.7051]]]}"""
    private fun place(json: String = first) = PlaceOverlayData("same-key", "Town", PlaceKind.TOWN, null, 1.0, json)

    @Test fun unchangedLargeTownSnapshotsDoNotRepeatCoverageQueriesOnPan() {
        val cache = TownCompletionSnapshotCache()
        val data = List(1_000) { place().copy(key = "town-$it") }
        var calculations = 0
        val calculate: (PlaceOverlayData) -> PlaceOverlayData = { item ->
            calculations++
            item.copy(completion = TownCompletionProgress(50, TownMedal.SILVER, 40.0, -74.0))
        }
        val first = cache.decorate(data, 1L to 20L, 4L, calculate)
        assertEquals(1_000, calculations)
        repeat(10) { assertSame(first, cache.decorate(data.toList(), 1L to 20L, 4L, calculate)) }
        assertEquals("Ten unchanged pans must make no new coverage queries", 1_000, calculations)
        cache.decorate(data, 1L to 21L, 4L, calculate)
        assertEquals(2_000, calculations)
        cache.decorate(data, 2L to 21L, 4L, calculate)
        assertEquals(3_000, calculations)
        cache.decorate(data, 2L to 21L, 5L, calculate)
        assertEquals(4_000, calculations)
    }

    @Test fun coverageSnapshotCacheDoesNotConfuseSameHashDifferentBoundaries() {
        val cache = TownCompletionSnapshotCache()
        assertEquals(first.hashCode(), second.hashCode())
        var calculations = 0
        val calculate: (PlaceOverlayData) -> PlaceOverlayData = { calculations++; it }
        cache.decorate(listOf(place(first)), 1L to 20L, 1L, calculate)
        cache.decorate(listOf(place(second)), 1L to 20L, 1L, calculate)
        assertEquals(2, calculations)
    }

    @Test fun validDistantPolygonsWithTheSameHashMustPublishDifferentBoundaries() {
        assertEquals(997868208, first.hashCode())
        assertEquals(first.hashCode(), second.hashCode())
        assertNotEquals(first, second)
        val publisher = OverlayPublication()
        var sent = 0
        assertTrue(publisher.publish(listOf(place()), 1) { sent++; true })
        assertFalse(publisher.matches(listOf(place(second)), 1))
        assertTrue(publisher.publish(listOf(place(second)), 1) { sent++; true })
        assertEquals(2, sent)
    }

    @Test fun popupPropertyChangesAlsoPublishWithUnchangedGeometry() {
        val original = place()
        for (changed in listOf(original.copy(name = "New name"), original.copy(population = 123),
            original.copy(areaSquareKilometers = 1.000000001), original.copy(key = "new-key"),
            original.copy(kind = PlaceKind.STATE))) {
            val publisher = OverlayPublication()
            assertTrue(publisher.publish(listOf(original), 1) { true })
            assertFalse(publisher.matches(listOf(changed), 1))
            assertTrue(publisher.publish(listOf(changed), 1) { true })
        }
    }

    @Test fun missingSourcesAndFailedUpdatesRemainRetryable() {
        val publisher = OverlayPublication()
        val data = listOf(place())
        assertFalse(publisher.publish(data, 1) { false })
        assertFalse(publisher.matches(data, 1))
        assertThrows(IllegalStateException::class.java) { publisher.publish(data, 1) { error("native update failed") } }
        assertFalse(publisher.matches(data, 1))
        assertTrue(publisher.publish(data, 1) { true })
        assertFalse(publisher.publish(data, 1) { error("equal data must not upload") })
    }

    @Test fun orderingClearingStyleReplacementAndCacheResetRemainVisible() {
        val publisher = OverlayPublication()
        val data = listOf(place(), place(second).copy(key = "second"))
        assertTrue(publisher.publish(data, 1) { true })
        assertTrue(publisher.publish(data.reversed(), 1) { true })
        assertTrue(publisher.publish(emptyList(), 1) { true })
        assertFalse(publisher.publish(emptyList(), 1) { error("duplicate clear") })
        publisher.reset()
        assertFalse(publisher.matches(emptyList(), 1))
        assertTrue(publisher.publish(emptyList(), 1) { true })
        assertFalse(publisher.matches(emptyList(), 2))
        assertTrue(publisher.publish(emptyList(), 2) { true })
    }

    @Test fun equalRefreshesReleaseThePriorSnapshotPayload() {
        val publisher = OverlayPublication()
        val original = listOf(place())
        val refreshed = listOf(place(String(first.toCharArray())))
        assertTrue(publisher.publish(original, 1) { true })
        assertTrue(publisher.matches(refreshed, 1))
        val state = publisher.javaClass.getDeclaredField("applied").apply { isAccessible = true }
            .get(publisher) as AtomicReference<*>
        val applied = requireNotNull(state.get())
        assertSame(refreshed, applied.javaClass.getDeclaredField("data").apply { isAccessible = true }.get(applied))
    }

    @Test fun anEqualWorkerRefreshCannotResurrectPublicationAfterAStyleReset() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val block = AtomicBoolean(false)
        val value = place()
        val data = object : AbstractList<PlaceOverlayData>() {
            override val size = 1
            override fun get(index: Int) = value
            override fun equals(other: Any?): Boolean {
                if (block.get()) {
                    entered.countDown()
                    assertTrue(release.await(5, TimeUnit.SECONDS))
                }
                return super.equals(other)
            }
        }
        val publisher = OverlayPublication()
        assertTrue(publisher.publish(data, 1) { true })
        block.set(true)
        val answer = AtomicReference<Boolean>()
        val error = AtomicReference<Throwable>()
        val worker = Thread {
            try { answer.set(publisher.matches(listOf(value), 1)) }
            catch (failure: Throwable) { error.set(failure) }
        }
        worker.start()
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            publisher.reset()
        } finally { release.countDown(); worker.join(5_000) }
        assertFalse(worker.isAlive)
        assertNull(error.get())
        assertEquals(false, answer.get())
        assertFalse(publisher.matches(listOf(value), 1))
    }

    @Test fun viewportCacheNeverConfusesSameHashPolygonsAndRetainsUnknownBounds() {
        var reads = 0
        val cache = OverlayBoundsCache { reads++; OverlayViewport.ofGeometry(it) }
        val view = requireNotNull(OverlayViewport.ofGeometry(first))
        assertTrue(view.intersects(cache.get(place())))
        assertFalse(view.intersects(cache.get(place(second))))
        repeat(20) { assertFalse(view.intersects(cache.get(place(second)))) }
        assertEquals(2, reads)
        val odd = place("invalid")
        repeat(20) { assertTrue(view.intersects(cache.get(odd))) }
        assertEquals("Unknown bounds must be included without reparsing on every idle", 3, reads)
        cache.clear()
        assertTrue(view.intersects(cache.get(odd)))
        assertEquals(4, reads)
    }
}
