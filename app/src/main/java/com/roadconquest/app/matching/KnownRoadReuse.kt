package com.roadconquest.app.matching

import com.roadconquest.app.data.TrackPoint
import com.roadconquest.app.data.TrackingRepository
import org.json.JSONArray
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
        if (points.size < 2 || markableIds.isEmpty() || repository.getSummary().roadSegmentCount == 0L)
            return emptyList()
        val readGeometry = KnownRoadGeometryCache()::read
        val nearby = { fix: TrackPoint ->
            repository.findUnambiguousPreviouslyDrivenRoadNear(
                fix.latitude, fix.longitude, geometryReader = readGeometry
            )?.segmentId
        }
        val original = identify(points, markableIds, nearby)
        val remaining = markableIds - original.flatMap { it.pointIds }.toSet()
        if (remaining.isEmpty()) return original
        val evidence = repository.knownRoadEvidence(points)
        if (evidence.size == points.size) return withExtraApproach(repository, points, remaining, original, nearby)
        // The repository proves consecutive retained IDs and bounded original
        // samples before expansion. Give that actual sequence local ordinals so
        // identify retains every existing five-fix/edge/quality/ambiguity gate.
        // Negative source IDs are context only; only real pending IDs map back.
        val ordered = evidence.mapIndexed { index, fix -> fix.copy(id = index + 1L) }
        val pending = evidence.indices.filter { evidence[it].id in remaining }
            .mapTo(HashSet()) { it + 1L }
        val corroborated = ArrayList<KnownRoadVisit>()
        for (visit in identify(ordered, pending, nearby)) {
            val mapped = visit.copy(pointIds = visit.pointIds.map { evidence[(it - 1L).toInt()].id })
            val previous = corroborated.lastOrNull()
            // Context-only samples separate ordinal IDs but do not split one
            // consecutive retained visit into many database updates.
            if (previous?.segmentId == mapped.segmentId && previous.pointIds.last() + 1L == mapped.pointIds.first()) {
                corroborated[corroborated.lastIndex] = previous.copy(
                    pointIds = previous.pointIds + mapped.pointIds, lastTimestamp = mapped.lastTimestamp)
            } else corroborated += mapped
        }
        return withExtraApproach(repository, points, remaining, original + corroborated, nearby)
    }

    private fun withExtraApproach(
        repository: TrackingRepository, points: List<TrackPoint>, pendingIds: Set<Long>,
        visits: List<KnownRoadVisit>, nearby: (TrackPoint) -> String?
    ): List<KnownRoadVisit> {
        if (points.size != 4) return visits
        val remaining = pendingIds - visits.flatMap { it.pointIds }.toSet()
        if (remaining.isEmpty()) return visits
        val context = repository.knownRoadApproach(points)
        return if (context.size == points.size) visits else visits + identify(context, remaining, nearby)
    }

    /** Require a sustained, directionally consistent pass over one previously verified segment. */
    internal fun identify(
        points: List<TrackPoint>,
        markableIds: Set<Long>,
        closestKnownSegment: (TrackPoint) -> String?
    ): List<KnownRoadVisit> {
        if (points.size < 5 || markableIds.isEmpty()) return emptyList()
        // Only a continuous five-fix driving window can establish a verified
        // re-drive. Skip expensive nearby-road SQLite/geometry lookups for points
        // that cannot belong to one, without loosening any acceptance criteria.
        val eligible = BooleanArray(points.size) { i ->
            val p = points[i]
            p.accuracyMeters in 0.01f..5f && p.speedMps in 2.2f..40f &&
                p.latitude.isFinite() && p.longitude.isFinite() &&
                p.latitude in -90.0..90.0 && p.longitude in -180.0..180.0
        }
        if (eligible.count { it } < 5) return emptyList()
        val validEdge = BooleanArray(points.lastIndex) { i ->
            eligible[i] && eligible[i + 1] &&
                continuousAndHeadingAgrees(points[i], points[i + 1])
        }
        val possible = BooleanArray(points.size)
        for (start in 0..points.size - 5) {
            if ((start until start + 4).any { !validEdge[it] } ||
                (start until start + 5).none { !points[it].matched && points[it].id in markableIds }
            ) continue
            for (i in start until start + 5) possible[i] = true
        }
        if (possible.none { it }) return emptyList()
        val nearby = points.mapIndexed { i, point ->
            if (!possible[i]) null else closestKnownSegment(point)
        }
        val accepted = sortedMapOf<Int, String>()
        // Parallel roads and junctions are too ambiguous for three-fix proximity.
        // Require five directionally compatible fixes covering a meaningful span.
        for (i in 2 until points.size - 2) {
            val segment = nearby[i] ?: continue
            val window = (i - 2)..(i + 2)
            if (window.any { nearby[it] != segment }) continue
            if ((i - 2 until i + 2).any { !validEdge[it] }) continue
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

    private fun continuousAndHeadingAgrees(a: TrackPoint, b: TrackPoint): Boolean {
        val elapsed = b.timestampMillis - a.timestampMillis
        if (b.id != a.id + 1 || elapsed !in 1..12_000L) return false
        val longitude = ((b.longitude - a.longitude + 540.0) % 360.0) - 180.0
        val east = Math.toRadians(longitude) * cos(Math.toRadians((a.latitude + b.latitude) / 2.0))
        val north = Math.toRadians(b.latitude - a.latitude)
        val distance = hypot(east * 6_371_008.8, north * 6_371_008.8)
        if (distance !in 2.0..160.0 || distance / (elapsed / 1000.0) > 40.0) return false
        val course = (Math.toDegrees(kotlin.math.atan2(east, north)) + 360.0) % 360.0
        val bearing = a.bearingDegrees.toDouble()
        if (!bearing.isFinite() || bearing !in 0.0..360.0) return false
        val delta = kotlin.math.abs(((bearing - course + 540.0) % 360.0) - 180.0)
        return delta <= 25.0
    }
}

/** One small read-only geometry per find() call; every fix still queries current candidate roads. */
internal class KnownRoadGeometryCache {
    private var json: String? = null
    private var coordinates: JSONArray? = null

    fun read(next: String): JSONArray? {
        if (next == json) return coordinates
        val parsed = runCatching { JSONArray(next) }.getOrNull()
        // Large or diverse road sets retain the original parsing path rather than
        // holding many geometries. Equality includes every character, not just an ID.
        if (next.length <= 16_384) {
            json = next
            coordinates = parsed
        }
        return parsed
    }
}
