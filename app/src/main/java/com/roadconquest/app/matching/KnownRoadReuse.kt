package com.roadconquest.app.matching

import com.roadconquest.app.data.TrackPoint
import com.roadconquest.app.data.TrackingRepository
import kotlin.math.cos
import kotlin.math.hypot

/** A visited road already has trusted geometry on the phone. No new road is discovered here. */
data class KnownRoadVisit(
    val segmentId: String,
    val pointIds: List<Long>,
    val firstTimestamp: Long,
    val lastTimestamp: Long
)

internal object KnownRoadReuse {
    fun find(repository: TrackingRepository, points: List<TrackPoint>, markableIds: Set<Long>): List<KnownRoadVisit> {
        if (points.size < 3 || markableIds.isEmpty() || repository.getSummary().roadSegmentCount == 0L)
            return emptyList()
        return identify(points, markableIds) { fix ->
            repository.findUnambiguousPreviouslyDrivenRoadNear(fix.latitude, fix.longitude)?.segmentId
        }
    }

    /** Require a sustained, directionally consistent pass over one previously verified segment. */
    internal fun identify(
        points: List<TrackPoint>,
        markableIds: Set<Long>,
        closestKnownSegment: (TrackPoint) -> String?
    ): List<KnownRoadVisit> {
        if (points.size < 5 || markableIds.isEmpty()) return emptyList()
        val nearby = points.map { point ->
            if (point.accuracyMeters !in 0.01f..5f || point.speedMps !in 2.2f..40f ||
                !point.latitude.isFinite() || !point.longitude.isFinite() ||
                point.latitude !in -90.0..90.0 || point.longitude !in -180.0..180.0
            ) null else closestKnownSegment(point)
        }
        val accepted = sortedMapOf<Int, String>()
        // Parallel roads and junctions are too ambiguous for three-fix proximity.
        // Require five directionally compatible fixes covering a meaningful span.
        for (i in 2 until points.size - 2) {
            val segment = nearby[i] ?: continue
            val window = (i - 2)..(i + 2)
            if (window.any { nearby[it] != segment }) continue
            if ((i - 2 until i + 2).any { !continuous(points[it], points[it + 1]) ||
                    !headingAgreesWithTravel(points[it], points[it + 1]) }) continue
            val first = points[i - 2]
            val last = points[i + 2]
            val lonDelta = ((last.longitude - first.longitude + 540.0) % 360.0) - 180.0
            val dx = Math.toRadians(lonDelta) * cos(Math.toRadians((first.latitude + last.latitude) / 2.0)) * 6_371_008.8
            val dy = Math.toRadians(last.latitude - first.latitude) * 6_371_008.8
            if (hypot(dx, dy) < 25.0) continue
            for (index in window) {
                if (!points[index].matched && points[index].id in markableIds)
                    accepted[index] = segment
            }
        }
        val visits = ArrayList<KnownRoadVisit>()
        var active: String? = null
        val indices = ArrayList<Int>()
        fun flush() {
            val road = active ?: return
            if (indices.isNotEmpty()) visits.add(KnownRoadVisit(
                road, indices.map { points[it].id },
                points[indices.first()].timestampMillis, points[indices.last()].timestampMillis))
            indices.clear()
        }
        for ((index, road) in accepted) {
            if (road != active || indices.lastOrNull()?.let { it + 1 != index } == true) {
                flush()
                active = road
            }
            indices.add(index)
        }
        flush()
        return visits
    }

    private fun headingAgreesWithTravel(a: TrackPoint, b: TrackPoint): Boolean {
        val lonDelta = ((b.longitude - a.longitude + 540.0) % 360.0) - 180.0
        val east = Math.toRadians(lonDelta) * cos(Math.toRadians((a.latitude + b.latitude) / 2.0))
        val north = Math.toRadians(b.latitude - a.latitude)
        val course = (Math.toDegrees(kotlin.math.atan2(east, north)) + 360.0) % 360.0
        val bearing = a.bearingDegrees.toDouble()
        if (!bearing.isFinite() || bearing !in 0.0..360.0) return false
        val delta = kotlin.math.abs(((bearing - course + 540.0) % 360.0) - 180.0)
        return delta <= 25.0
    }

    private fun continuous(a: TrackPoint, b: TrackPoint): Boolean {
        val elapsed = b.timestampMillis - a.timestampMillis
        if (b.id != a.id + 1 || elapsed !in 1..12_000L) return false
        val longitude = ((b.longitude - a.longitude + 540.0) % 360.0) - 180.0
        val dx = Math.toRadians(longitude) *
            cos(Math.toRadians((a.latitude + b.latitude) / 2.0)) * 6_371_008.8
        val dy = Math.toRadians(b.latitude - a.latitude) * 6_371_008.8
        val distance = hypot(dx, dy)
        return distance in 2.0..160.0 && distance / (elapsed / 1000.0) <= 40.0
    }
}
