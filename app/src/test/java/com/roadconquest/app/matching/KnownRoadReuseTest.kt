package com.roadconquest.app.matching

import com.roadconquest.app.data.TrackPoint
import org.junit.Assert.*
import org.junit.Test

class KnownRoadReuseTest {
    private fun trace(n: Int = 6) = (0 until n).map { i ->
        TrackPoint(i + 1L, 40.0, -74.0 + i * 0.0001, 3f, 9f, 90f,
            1_000_000L + i * 6_000L, false)
    }

    @Test fun continuousRedriveMatchesEntirePreviouslyKnownRoadWithoutAnApiCall() {
        val points = trace()
        val result = KnownRoadReuse.identify(points, points.map { it.id }.toSet()) { "verified-road" }
        assertEquals(1, result.size)
        assertEquals(points.map { it.id }, result.single().pointIds)
        assertEquals("verified-road", result.single().segmentId)
    }

    @Test fun newRoadsAndSingleNearRoadFixesRemainPending() {
        val points = trace()
        val result = KnownRoadReuse.identify(points, points.map { it.id }.toSet()) {
            if (it.id == 3L) "unlocked" else null
        }
        assertTrue(result.isEmpty())
        assertTrue(KnownRoadReuse.identify(points, points.map { it.id }.toSet()) { null }.isEmpty())
    }

    @Test fun discontinuousOrInaccurateTracesNeverInventLocalRoads() {
        val points = trace(3)
        assertTrue(KnownRoadReuse.identify(points.mapIndexed { i, p ->
            if (i == 1) p.copy(timestampMillis = p.timestampMillis + 20_000L) else p
        }, points.map { it.id }.toSet()) { "road" }.isEmpty())
        assertTrue(KnownRoadReuse.identify(points.mapIndexed { i, p ->
            if (i == 1) p.copy(accuracyMeters = 11f) else p
        }, points.map { it.id }.toSet()) { "road" }.isEmpty())
        assertTrue(KnownRoadReuse.identify(points.mapIndexed { i, p ->
            if (i == 1) p.copy(longitude = p.longitude + 0.1) else p
        }, points.map { it.id }.toSet()) { "road" }.isEmpty())
    }

    @Test fun localRevisitStillEnforcesContinuitySpeedAndHeadingTogether() {
        val valid = trace()
        val ids = valid.map { it.id }.toSet()
        fun accepted(points: List<TrackPoint>) =
            KnownRoadReuse.identify(points, ids) { "known" }.isNotEmpty()
        assertTrue(accepted(valid))
        assertFalse(accepted(valid.mapIndexed { i, p ->
            if (i == 3) p.copy(timestampMillis = p.timestampMillis + 12_001L) else p
        }))
        assertFalse(accepted(valid.map { it.copy(bearingDegrees = 270f) }))
        assertFalse(accepted(valid.map { it.copy(speedMps = 41f) }))
        assertFalse(accepted(valid.mapIndexed { i, p ->
            if (i == 3) p.copy(latitude = p.latitude + 0.01) else p
        }))
    }

    @Test fun nearbyRoadWithOpposingTravelDirectionIsNotConfirmedLocally() {
        val points = trace().map { it.copy(bearingDegrees = 270f) }
        assertTrue(KnownRoadReuse.identify(points, points.map { it.id }.toSet()) { "road" }.isEmpty())
    }

    @Test fun onlyThreeNearbyFixesCannotProveAnOfflineRevisit() {
        val points = trace(3)
        assertTrue(KnownRoadReuse.identify(points, points.map { it.id }.toSet()) { "road" }.isEmpty())
    }

    @Test fun impossibleRevisitWindowsDoNotQuerySavedRoadGeometries() {
        val points = trace(8).mapIndexed { i, p ->
            if (i % 2 == 0) p.copy(timestampMillis = p.timestampMillis + 20_000L) else p
        }
        var lookups = 0
        val result = KnownRoadReuse.identify(points, points.map { it.id }.toSet()) {
            lookups++
            "known-road"
        }
        assertTrue(result.isEmpty())
        assertEquals(0, lookups)
    }

    @Test fun fullyResolvedPointsDoNotQuerySavedRoadGeometries() {
        val points = trace().map { it.copy(matched = true) }
        var lookups = 0
        assertTrue(KnownRoadReuse.identify(points, points.map { it.id }.toSet()) {
            lookups++
            "known-road"
        }.isEmpty())
        assertEquals(0, lookups)
    }

    @Test fun partialKnownRoadsDoNotResolveUnrelatedSections() {
        val points = trace(8)
        val result = KnownRoadReuse.identify(points, points.map { it.id }.toSet()) {
            if (it.id < 5L) "road-a" else if (it.id > 6L) "road-b" else null
        }
        assertTrue(result.isEmpty()) // Fewer than five consistent fixes cannot prove the revisit.
    }
}
