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
            repository.findRoadNear(fix.latitude, fix.longitude, 4.0)?.segmentId
        }
    }

    /** Require three consecutive precise driving fixes along one *already saved* segment. */
    internal fun identify(
        points: List<TrackPoint>,
        markableIds: Set<Long>,
        closestKnownSegment: (TrackPoint) -> String?
    ): List<KnownRoadVisit> {
        if (points.size < 3 || markableIds.isEmpty()) return emptyList()
        val nearby = points.map { point ->
            if (point.accuracyMeters !in 0.01f..5f || point.speedMps !in 2.2f..40f ||
                !point.latitude.isFinite() || !point.longitude.isFinite() ||
                point.latitude !in -90.0..90.0 || point.longitude !in -180.0..180.0
            ) null else closestKnownSegment(point)
        }
        val accepted = sortedMapOf<Int, String>()
        for (i in 1 until points.lastIndex) {
            val segment = nearby[i] ?: continue
            if (nearby[i - 1] != segment || nearby[i + 1] != segment ||
                !continuous(points[i - 1], points[i]) || !continuous(points[i], points[i + 1])
            ) continue
            for (index in (i - 1)..(i + 1)) {
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
