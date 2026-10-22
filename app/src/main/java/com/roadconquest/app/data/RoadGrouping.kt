package com.roadconquest.app.data

import org.json.JSONArray
import java.text.Normalizer
import java.util.Locale
import kotlin.math.*

/**
 * Groups locally saved matched fragments into user-facing road identities.
 *
 * The local counter aims at human-perceived road identities rather than raw matcher fragments:
 * signed route refs are strongest, continuous street names are next, and unnamed fragments use
 * a deliberately tight topology rule. Ref-aware tolerance also joins nearby divided carriageways.
 * This groups identities only; it never invents geometry across an unrecorded gap.
 */
internal object RoadGrouping {
    const val JOIN_TOLERANCE_M = 75.0
    const val REF_JOIN_TOLERANCE_M = 120.0
    private const val UNNAMED_JOIN_TOLERANCE_M = 25.0
    private const val EARTH_RADIUS_M = 6_371_008.8
    private const val METERS_PER_DEGREE = 111_320.0
    private val WHITESPACE_RE = Regex("\\s+")
    private val NON_ALNUM_RE = Regex("[^\\p{L}\\p{N}]")

    data class Road(
        val segmentId: String,
        val name: String,
        val reference: String = "",
        val geometryJson: String,
        val minLatitude: Double,
        val maxLatitude: Double,
        val minLongitude: Double,
        val maxLongitude: Double,
        val groupId: String? = null
    ) {
        val nameKey: String = normalizeName(name)
        val referenceKeys: Set<String> = normalizeReferences(reference)
        val coordinates: DoubleArray? by lazy(LazyThreadSafetyMode.NONE) { parseCoordinates(geometryJson) }
    }

    fun normalizeName(name: String): String {
        if (name.isEmpty()) return ""
        return Normalizer.normalize(name, Normalizer.Form.NFKC).trim().lowercase(Locale.ROOT)
            .replace(WHITESPACE_RE, " ").let { if (it == "unnamed road") "" else it }
    }

    /**
     * OSRM refs can contain several concurrent route numbers separated by semicolons.
     * Removing punctuation makes common formatting variants (I-295 / I 295) compare equally
     * without applying risky language-specific street-name abbreviation rules.
     */
    fun normalizeReferences(reference: String): Set<String> {
        // Unnamed/unsigned fragments are common. An empty reference always
        // normalized to the empty set; skip split and Unicode normalization.
        if (reference.isEmpty()) return emptySet()
        return reference.splitToSequence(';')
            .map {
                Normalizer.normalize(it.trim(), Normalizer.Form.NFKC)
                    .uppercase(Locale.ROOT)
                    .replace(NON_ALNUM_RE, "")
            }
            .filter { it.isNotEmpty() }
            .toCollection(linkedSetOf())
    }

    fun isUnnamed(nameKey: String): Boolean =
        nameKey.isEmpty() || nameKey == "unnamed road"

    /**
     * Any shared signed route ref is strong local identity evidence. The repository narrows
     * multiplexed refs to the overlap supported by each connected continuation, preventing a
     * shared US-1/US-9 section from transitively merging the routes after they diverge.
     */
    fun sharedReference(first: Road, second: Road): Boolean =
        first.referenceKeys.isNotEmpty() && second.referenceKeys.isNotEmpty() &&
            first.referenceKeys.any(second.referenceKeys::contains)

    fun connected(first: Road, second: Road): Boolean {
        val sharedRef = sharedReference(first, second)
        if (!sharedRef && first.nameKey != second.nameKey) return false
        // Two unrelated unnamed fragments still need the tight access-road seam rule.
        if (!sharedRef && isUnnamed(first.nameKey) != isUnnamed(second.nameKey)) return false
        val tolerance = when {
            sharedRef -> REF_JOIN_TOLERANCE_M
            isUnnamed(first.nameKey) -> UNNAMED_JOIN_TOLERANCE_M
            else -> JOIN_TOLERANCE_M
        }
        if (!boundsCanTouch(first, second, tolerance)) return false
        val a = first.coordinates ?: return false
        val b = second.coordinates ?: return false
        if (a.size < 4 || b.size < 4) return false
        return endpointToPolylineMeters(a[0], a[1], b) <= tolerance ||
            endpointToPolylineMeters(a[a.size - 2], a[a.size - 1], b) <= tolerance ||
            endpointToPolylineMeters(b[0], b[1], a) <= tolerance ||
            endpointToPolylineMeters(b[b.size - 2], b[b.size - 1], a) <= tolerance
    }

    private fun boundsCanTouch(first: Road, second: Road, tolerance: Double): Boolean {
        val latitudePad = tolerance / METERS_PER_DEGREE
        if (first.maxLatitude + latitudePad < second.minLatitude ||
            second.maxLatitude + latitudePad < first.minLatitude
        ) return false

        val overlapSouth = max(first.minLatitude, second.minLatitude)
        val overlapNorth = min(first.maxLatitude, second.maxLatitude)
        val latitude = (overlapSouth + overlapNorth) / 2.0
        val longitudePad = tolerance /
            (METERS_PER_DEGREE * cos(Math.toRadians(latitude.coerceIn(-89.0, 89.0))).coerceAtLeast(0.01))

        fun span(road: Road): Double =
            if (road.maxLongitude >= road.minLongitude) {
                road.maxLongitude - road.minLongitude
            } else {
                road.maxLongitude - road.minLongitude + 360.0
            }

        fun wrap(value: Double): Double =
            ((value + 180.0) % 360.0 + 360.0) % 360.0 - 180.0

        val firstSpan = span(first)
        val secondSpan = span(second)
        if (firstSpan >= 360.0 - 1e-9 || secondSpan >= 360.0 - 1e-9) return true
        val firstCenter = wrap(first.minLongitude + firstSpan / 2.0)
        val secondCenter = wrap(second.minLongitude + secondSpan / 2.0)
        val centerDistance = abs(wrap(secondCenter - firstCenter))
        return centerDistance <= (firstSpan + secondSpan) / 2.0 + longitudePad
    }

    private fun parseCoordinates(json: String): DoubleArray? {
        val array = runCatching { JSONArray(json) }.getOrNull() ?: return null
        if (array.length() < 2) return null
        val output = DoubleArray(array.length() * 2)
        for (index in 0 until array.length()) {
            val coordinate = array.optJSONArray(index) ?: return null
            if (coordinate.length() < 2) return null
            val longitude = coordinate.optDouble(0, Double.NaN)
            val latitude = coordinate.optDouble(1, Double.NaN)
            if (!latitude.isFinite() || !longitude.isFinite() ||
                latitude !in -90.0..90.0 || longitude !in -180.0..180.0
            ) return null
            output[index * 2] = latitude
            output[index * 2 + 1] = longitude
        }
        return output
    }

    private fun endpointToPolylineMeters(
        latitude: Double,
        longitude: Double,
        coordinates: DoubleArray
    ): Double {
        val latitudeRadians = Math.toRadians(latitude)
        val cosLatitude = cos(latitudeRadians).coerceAtLeast(0.01)
        fun x(otherLongitude: Double): Double {
            val delta = ((otherLongitude - longitude + 540.0) % 360.0) - 180.0
            return EARTH_RADIUS_M * Math.toRadians(delta) * cosLatitude
        }
        fun y(otherLatitude: Double): Double =
            EARTH_RADIUS_M * Math.toRadians(otherLatitude - latitude)

        var best = Double.POSITIVE_INFINITY
        // Neighboring polyline segments share a vertex. Project it once rather
        // than repeating wrap, radians and trigonometry for each adjoining edge.
        var ax = x(coordinates[1])
        var ay = y(coordinates[0])
        var index = 2
        while (index + 1 < coordinates.size) {
            val bx = x(coordinates[index + 1])
            val by = y(coordinates[index])
            val dx = bx - ax
            val dy = by - ay
            val lengthSquared = dx * dx + dy * dy
            val t = if (lengthSquared == 0.0) 0.0
                else (-(ax * dx + ay * dy) / lengthSquared).coerceIn(0.0, 1.0)
            best = min(best, hypot(ax + t * dx, ay + t * dy))
            ax = bx
            ay = by
            index += 2
        }
        return best
    }
}
