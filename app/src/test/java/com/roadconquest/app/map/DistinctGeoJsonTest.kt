package com.roadconquest.app.map

import org.junit.Assert.*
import org.junit.Test
import org.maplibre.geojson.FeatureCollection

class DistinctGeoJsonTest {
    private fun routeJson(lon: Double = -74.0, property: Int = 1) =
        """{"type":"FeatureCollection","features":[{"type":"Feature","properties":{"value":$property},"geometry":{"type":"LineString","coordinates":[[$lon,40],[-73.99,40.01]]}}]}"""
    private fun route(lon: Double = -74.0, property: Int = 1) = FeatureCollection.fromJson(routeJson(lon, property))
    private fun empty() = FeatureCollection.fromJson("""{"type":"FeatureCollection","features":[]}""")

    @Test fun identicalRetriesPublishOnceButEveryChangedCoordinateAndPropertyPublishes() {
        val publisher = DistinctGeoJson(empty())
        val sent = mutableListOf<FeatureCollection>()
        repeat(100) { publisher.publish(route()) { sent.add(it) } }
        assertEquals(1, sent.size)
        assertTrue(publisher.publish(route(-74.000000001)) { sent.add(it) })
        assertTrue(publisher.publish(route(-74.000000001, 2)) { sent.add(it) })
        assertEquals(3, sent.size)
        assertEquals(route(-74.000000001, 2), sent.last())
        repeat(10) { publisher.publish(empty()) { sent.add(it) } }
        assertEquals(4, sent.size)
        assertEquals(empty(), sent.last())
    }

    @Test fun missingSourcesAndFailedNativeUpdatesRemainRetryable() {
        val publisher = DistinctGeoJson(empty())
        assertFalse(publisher.publish(route()) { false })
        assertThrows(IllegalStateException::class.java) { publisher.publish(route()) { error("native update failed") } }
        var calls = 0
        assertTrue(publisher.publish(route()) { calls++; true })
        assertFalse(publisher.publish(route()) { calls++; true })
        assertEquals(1, calls)
    }

    @Test fun styleReplacementRepublishesTheSameRouteAndInitialEmptyIsAlreadyApplied() {
        val publisher = DistinctGeoJson(empty())
        var calls = 0
        assertFalse(publisher.publish(empty()) { calls++; true })
        assertTrue(publisher.publish(route()) { calls++; true })
        publisher.reset()
        assertTrue(publisher.publish(route()) { calls++; true })
        assertEquals(2, calls)
    }

    @Test fun exactNearbyJsonPublishesEachChangedSourceAndClearOnlyOnce() {
        val publishers = List(3) { DistinctGeoJson(empty().toJson()) }
        val sent = List(3) { mutableListOf<String>() }
        val initial = listOf(routeJson(), routeJson(property = 2), routeJson(property = 3))
        repeat(100) {
            initial.forEachIndexed { index, json ->
                // New String objects with the same contents also avoid native work.
                publishers[index].publish(String(json.toCharArray())) { sent[index].add(it) }
            }
        }
        assertEquals(listOf(1, 1, 1), sent.map { it.size })
        assertTrue(publishers[0].publish(routeJson(-74.000000001)) { sent[0].add(it) })
        assertTrue(publishers[1].publish(routeJson(property = 4)) { sent[1].add(it) })
        assertEquals(listOf(2, 2, 1), sent.map { it.size })
        repeat(10) {
            publishers.forEachIndexed { index, publisher ->
                publisher.publish(empty().toJson()) { sent[index].add(it) }
            }
        }
        assertEquals(listOf(3, 3, 2), sent.map { it.size })
        assertEquals(List(3) { empty().toJson() }, sent.map { it.last() })
    }

    @Test fun nearbyStyleSeedAndFailedUpdatesKeepExactStringRetryBehavior() {
        val publisher = DistinctGeoJson(empty().toJson())
        val installed = routeJson()
        publisher.reset(installed)
        var calls = 0
        assertFalse(publisher.publish(installed) { calls++; true })
        val next = routeJson(-74.000000001, 4)
        assertFalse(publisher.publish(next) { false })
        assertThrows(IllegalStateException::class.java) {
            publisher.publish(next) { error("native update failed") }
        }
        assertTrue(publisher.publish(next) { calls++; true })
        publisher.reset(installed)
        assertTrue(publisher.publish(next) { calls++; true })
        publisher.reset()
        assertFalse(publisher.publish(empty().toJson()) { calls++; true })
        assertEquals(2, calls)
    }

    @Test fun equalSnapshotsReleaseThePriorPayloadReferenceWithoutNativeWork() {
        val publisher = DistinctGeoJson("")
        val first = routeJson()
        val latest = String(first.toCharArray())
        var calls = 0
        assertTrue(publisher.publish(first) { calls++; true })
        assertFalse(publisher.publish(latest) { calls++; true })
        // Observe retained ownership directly instead of relying on nondeterministic GC.
        val retained = publisher.javaClass.getDeclaredField("applied").apply { isAccessible = true }
        assertSame(latest, retained.get(publisher))
        assertFalse(publisher.publish(routeJson(property = 8)) { false })
        assertSame(latest, retained.get(publisher))
        assertEquals(1, calls)
    }
}
