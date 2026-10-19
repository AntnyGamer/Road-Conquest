package com.roadconquest.app.map

import org.junit.Assert.*
import org.junit.Test
import org.maplibre.geojson.FeatureCollection

class DistinctGeoJsonTest {
    private fun route(lon: Double = -74.0, property: Int = 1) = FeatureCollection.fromJson(
        """{"type":"FeatureCollection","features":[{"type":"Feature","properties":{"value":$property},"geometry":{"type":"LineString","coordinates":[[$lon,40],[-73.99,40.01]]}}]}""")
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
}
