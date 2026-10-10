package com.roadconquest.app.map

import android.net.Uri
import com.roadconquest.app.BuildConfig
import com.roadconquest.app.data.PlaceDiscovery
import com.roadconquest.app.data.PlaceKind
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.cos

/**
 * New Jersey municipal overlays use ONE statewide topology, not Nominatim
 * search results assembled from unrelated OSM feature boundaries.
 *
 * Official source: NJOIT Office of GIS, Government Boundaries / Municipalities.
 * https://maps.nj.gov/arcgis/rest/services/Framework/Government_Boundaries/MapServer/2
 *
 * NJOGIS cautions that these polygons are reference data, not a land survey or
 * the legally determinative municipal boundary.
 */
internal object NewJerseyMunicipalBoundaries {
    private const val QUERY_URL =
        "https://maps.nj.gov/arcgis/rest/services/Framework/Government_Boundaries/MapServer/2/query"
    // Older builds saved the *center* of a sampling cell, up to ~1 km from
    // where the car actually was. Accept a properly named nearby municipality,
    // but never a same-named township on the other side of New Jersey.
    private const val MAX_SAVED_LOCATION_ERROR_METERS = 1_800.0
    private const val MAX_RESPONSE_CHARS = 16 * 1024 * 1024
    private const val METERS_PER_DEGREE = 111_195.0

    fun applies(place: PlaceDiscovery): Boolean =
        place.kind == PlaceKind.TOWN && normalize(place.parentName) == "new jersey" &&
            normalize(place.countryName) in setOf(
                "united states", "united states of america", "usa", "us"
            )

    fun isNewJerseyKey(key: String): Boolean =
        key.split('|').any { normalize(it) == "new jersey" }

    /** Returns null, not a guessed Nominatim shape, if the NJ source has no matching town. */
    fun fetch(place: PlaceDiscovery): PlaceOverlayData? {
        require(applies(place))
        val base = municipalityIdentity(place.displayName).first
        if (base.length < 2 || base.length > 70) return null
        val safe = base.replace("'", "''")
        val where = "MUN LIKE '$safe%' OR NAME LIKE '$safe%'"
        val url = Uri.parse(QUERY_URL).buildUpon()
            .appendQueryParameter("where", where)
            .appendQueryParameter("outFields", "NAME,MUN,MUN_LABEL,MUN_TYPE,COUNTY,SQ_MILES,POP2020")
            .appendQueryParameter("returnGeometry", "true")
            .appendQueryParameter("outSR", "4326")
            .appendQueryParameter("resultRecordCount", "80")
            .appendQueryParameter("f", "geojson")
            .build().toString()
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 20_000
            instanceFollowRedirects = false
            requestMethod = "GET"
            setRequestProperty("User-Agent", "RoadConquest/${BuildConfig.VERSION_NAME} (+https://github.com/AntnyGamer/RoadConquest-Beta)")
            setRequestProperty("Accept", "application/geo+json, application/json")
        }
        return try {
            if (connection.responseCode !in 200..299) {
                throw IOException("NJ municipal boundary service returned HTTP ${connection.responseCode}")
            }
            val data = StringBuilder()
            connection.inputStream.bufferedReader().use { reader ->
                val buffer = CharArray(8192)
                while (true) {
                    val n = reader.read(buffer)
                    if (n < 0) break
                    if (data.length + n > MAX_RESPONSE_CHARS) throw IOException("NJ boundary response too large")
                    data.append(buffer, 0, n)
                }
            }
            parseResponse(place, data.toString())
        } finally {
            connection.disconnect()
        }
    }

    internal fun parseResponse(place: PlaceDiscovery, body: String): PlaceOverlayData? {
        if (!applies(place)) return null
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
        if (json.has("error") || json.optString("type") != "FeatureCollection") return null
        val features = json.optJSONArray("features") ?: return null
        val desired = municipalityIdentity(place.displayName)
        var winner: PlaceOverlayData? = null
        var bestDistance = Double.POSITIVE_INFINITY
        for (i in 0 until features.length()) {
            val item = features.optJSONObject(i) ?: continue
            val attributes = item.optJSONObject("properties") ?: continue
            val geometry = item.optJSONObject("geometry") ?: continue
            if (geometry.optString("type") !in setOf("Polygon", "MultiPolygon")) continue
            val officialName = attributes.optString("NAME").ifBlank {
                attributes.optString("MUN_LABEL")
            }
            if (!matches(desired, municipalityIdentity(officialName), attributes.optString("MUN_TYPE"))) continue
            val distance = distanceToPolygonMeters(
                geometry, place.latitude, place.longitude
            )
            if (!distance.isFinite() || distance > MAX_SAVED_LOCATION_ERROR_METERS ||
                distance >= bestDistance
            ) continue
            val squareMiles = attributes.optDouble("SQ_MILES", Double.NaN)
            val areaKm2 = if (squareMiles.isFinite() && squareMiles > 0) {
                squareMiles * 2.589988110336
            } else PlaceOverlayClient.areaSquareKilometers(geometry)
            if (!areaKm2.isFinite() || areaKm2 <= 0.0) continue
            val population = attributes.optLong("POP2020", 0L).takeIf { it > 0 }
            winner = PlaceOverlayData(
                key = place.key, name = place.displayName,
                kind = PlaceKind.TOWN,
                population = population,
                areaSquareKilometers = areaKm2,
                geometryJson = geometry.toString()
            )
            bestDistance = distance
        }
        return winner
    }

    private fun matches(
        requested: Pair<String, String?>,
        returned: Pair<String, String?>,
        rawType: String
    ): Boolean {
        if (requested.first != returned.first) return false
        val officialType = returned.second ?: kind(rawType)
        // If a saved name explicitly says Township/City/Borough, reject an
        // official feature whose type disagrees OR cannot be determined.
        return requested.second == null || requested.second == officialType
    }

    private fun municipalityIdentity(value: String): Pair<String, String?> {
        var name = normalize(value).removePrefix("the ")
        var type: String? = null
        repeat(3) {
            for ((token, fullType) in listOf(
                "township" to "township", "twp" to "township",
                "borough" to "borough", "boro" to "borough",
                "city" to "city", "town" to "town", "village" to "village"
            )) {
                val prefix = "$token of "
                val suffix = " $token"
                if (name.startsWith(prefix) || name.endsWith(suffix)) {
                    if (type != null && type != fullType) return normalize(value) to null
                    name = if (name.startsWith(prefix)) name.removePrefix(prefix) else name.removeSuffix(suffix)
                    type = fullType
                }
            }
        }
        return name to type
    }

    private fun kind(value: String): String? = when (normalize(value)) {
        "township", "twp" -> "township"
        "borough", "boro" -> "borough"
        "city" -> "city"
        "town" -> "town"
        "village" -> "village"
        else -> null
    }

    private fun normalize(value: String): String =
        value.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")

    /**
     * Nearest official polygon edge in meters, including hole boundaries. A point
     * inside the polygon has distance zero. No centroid or bounding-box guessing.
     */
    private fun distanceToPolygonMeters(polygon: JSONObject, latitude: Double, longitude: Double): Double {
        if (!latitude.isFinite() || !longitude.isFinite()) return Double.POSITIVE_INFINITY
        if (PlaceGeometry.contains(polygon, latitude, longitude)) return 0.0
        val polygons = polygon.optJSONArray("coordinates") ?: return Double.POSITIVE_INFINITY
        var closest = Double.POSITIVE_INFINITY
        fun distanceFromRings(rings: JSONArray) {
            for (r in 0 until rings.length()) {
                val ring = rings.optJSONArray(r) ?: continue
                if (ring.length() < 2) continue
                var previous = ring.optJSONArray(ring.length() - 1) ?: continue
                for (i in 0 until ring.length()) {
                    val current = ring.optJSONArray(i) ?: continue
                    fun x(p: JSONArray) =
                        (p.optDouble(0, Double.NaN) - longitude) *
                            cos(Math.toRadians(latitude)) * METERS_PER_DEGREE
                    fun y(p: JSONArray) =
                        (p.optDouble(1, Double.NaN) - latitude) * METERS_PER_DEGREE
                    val ax = x(previous); val ay = y(previous)
                    val bx = x(current); val by = y(current)
                    if (ax.isFinite() && ay.isFinite() && bx.isFinite() && by.isFinite()) {
                        val dx = bx - ax
                        val dy = by - ay
                        val lengthSquared = dx * dx + dy * dy
                        val t = if (lengthSquared <= 0.0) 0.0 else
                            (-(ax * dx + ay * dy) / lengthSquared).coerceIn(0.0, 1.0)
                        val distance = kotlin.math.hypot(ax + t * dx, ay + t * dy)
                        if (distance < closest) closest = distance
                    }
                    previous = current
                }
            }
        }
        when (polygon.optString("type")) {
            "Polygon" -> distanceFromRings(polygons)
            "MultiPolygon" -> for (i in 0 until polygons.length()) {
                polygons.optJSONArray(i)?.let(::distanceFromRings)
            }
        }
        return closest
    }
}
