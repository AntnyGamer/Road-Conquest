package com.roadconquest.app.map

import android.net.Uri
import com.roadconquest.app.BuildConfig
import com.roadconquest.app.data.PlaceDiscovery
import com.roadconquest.app.data.PlaceKind
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.cos
import kotlin.math.hypot

/**
 * Official nationwide U.S. Census TIGERweb administrative geography.
 *
 * All states, INCLUDING NEW JERSEY, use the same Census incorporated-place
 * (layer 4) and county-subdivision (layer 1) features and identical checks.
 * Incorporated cities/boroughs are in layer 4; townships and New England
 * towns usually appear in layer 1. There are no state-specific providers.
 *
 * A Census Designated Place (CDP) is NOT an incorporated municipality and
 * must not create a green polygon simply because its name matches a postal town.
 */
internal object UnitedStatesMunicipalBoundaries {
    private const val BASE =
        "https://tigerweb.geo.census.gov/arcgis/rest/services/" +
            "TIGERweb/Places_CouSub_ConCity_SubMCD/MapServer"
    private const val MAX_CHARS = 16 * 1024 * 1024
    private const val MAX_SAVED_ERROR_METERS = 1_800.0
    private val WHITESPACE_RE = Regex("\\s+")

    private val stateFips = mapOf(
        "alabama" to "01", "alaska" to "02", "arizona" to "04",
        "arkansas" to "05", "california" to "06", "colorado" to "08",
        "connecticut" to "09", "delaware" to "10", "district of columbia" to "11",
        "florida" to "12", "georgia" to "13", "hawaii" to "15",
        "idaho" to "16", "illinois" to "17", "indiana" to "18",
        "iowa" to "19", "kansas" to "20", "kentucky" to "21",
        "louisiana" to "22", "maine" to "23", "maryland" to "24",
        "massachusetts" to "25", "michigan" to "26", "minnesota" to "27",
        "mississippi" to "28", "missouri" to "29", "montana" to "30",
        "nebraska" to "31", "nevada" to "32", "new hampshire" to "33",
        "new jersey" to "34", "new mexico" to "35", "new york" to "36",
        "north carolina" to "37", "north dakota" to "38", "ohio" to "39",
        "oklahoma" to "40", "oregon" to "41", "pennsylvania" to "42",
        "rhode island" to "44", "south carolina" to "45", "south dakota" to "46",
        "tennessee" to "47", "texas" to "48", "utah" to "49",
        "vermont" to "50", "virginia" to "51", "washington" to "53",
        "west virginia" to "54", "wisconsin" to "55", "wyoming" to "56",
        "puerto rico" to "72"
    )

    private val US_NAMES = setOf(
        "us", "usa", "united states", "united states of america"
    )

    fun isUnitedStatesKey(key: String): Boolean =
        key.substringBefore('|').trim().lowercase(Locale.ROOT) in US_NAMES

    fun applies(place: PlaceDiscovery): Boolean =
        place.kind == PlaceKind.TOWN &&
            stateCode(place.countryName, place.parentName) != null

    /** State lookup shared by all U.S. municipality discovery and rendering. */
    internal fun stateCode(countryName: String, stateName: String): String? {
        if (countryName.trim().lowercase(Locale.ROOT) !in US_NAMES) return null
        return stateFips[stateName.trim().lowercase(Locale.ROOT)]
    }

    /**
     * Layer selection is conservative. A township should not be replaced by
     * an overlapping incorporated city. When a city has no county subdivision
     * status, it should not be replaced by a similarly named township.
     */
    fun fetch(place: PlaceDiscovery): PlaceOverlayData? {
        require(applies(place))
        val normalized = municipalityIdentity(place.displayName)
        val state = stateFips[place.parentName.trim().lowercase(Locale.ROOT)]
            ?: return null
        val name = normalized.first
        if (name.length !in 2..90) return null
        // Limit a query by the actual geographic neighborhood instead of
        // relying on case-sensitive DB string comparisons across whole states.
        // The saved first-visit position in older builds can be off by ~1km.
        val latitudePad = 2_500.0 / 111_195.0
        val longitudePad = latitudePad /
            kotlin.math.cos(Math.toRadians(place.latitude)).coerceAtLeast(0.05)
        val envelope = listOf(
            place.longitude - longitudePad, place.latitude - latitudePad,
            place.longitude + longitudePad, place.latitude + latitudePad
        ).joinToString(",")
        val where = "STATE = '$state'"
        val layerOrder = if (normalized.second == "township" ||
            normalized.second == "town" &&
            place.parentName.trim().lowercase(Locale.ROOT) in setOf(
                "connecticut", "massachusetts", "maine", "new hampshire",
                "rhode island", "vermont", "new york", "wisconsin"
            )
        ) listOf(1, 4) else listOf(4, 1)
        for (layer in layerOrder) {
            val url = Uri.parse("$BASE/$layer/query").buildUpon()
                .appendQueryParameter("where", where)
                .appendQueryParameter("geometry", envelope)
                .appendQueryParameter("geometryType", "esriGeometryEnvelope")
                .appendQueryParameter("inSR", "4326")
                .appendQueryParameter("spatialRel", "esriSpatialRelIntersects")
                .appendQueryParameter("outFields",
                    "BASENAME,NAME,STATE,LSADC,FUNCSTAT,AREALAND,AREAWATER")
                .appendQueryParameter("returnGeometry", "true")
                .appendQueryParameter("outSR", "4326")
                .appendQueryParameter("resultRecordCount", "100")
                .appendQueryParameter("f", "geojson")
                .build().toString()
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8_000
                readTimeout = 20_000
                requestMethod = "GET"
                setRequestProperty("User-Agent",
                    "RoadConquest/${BuildConfig.VERSION_NAME} (+https://github.com/AntnyGamer/RoadConquest-Beta)")
                setRequestProperty("Accept", "application/geo+json, application/json")
            }
            try {
                if (connection.responseCode !in 200..299) {
                    throw IOException("Census municipal service returned HTTP ${connection.responseCode}")
                }
                val response = StringBuilder()
                connection.inputStream.bufferedReader().use { reader ->
                    val chars = CharArray(8192)
                    while (true) {
                        val read = reader.read(chars)
                        if (read < 0) break
                        if (response.length + read > MAX_CHARS) {
                            throw IOException("Census municipality geometry exceeds response limit")
                        }
                        response.append(chars, 0, read)
                    }
                }
                parseResponse(place, response.toString(), layer)?.let { return it }
            } finally {
                connection.disconnect()
            }
        }
        return null
    }

    internal fun parseResponse(place: PlaceDiscovery, body: String, layer: Int): PlaceOverlayData? {
        if (!applies(place) || layer !in setOf(1, 4)) return null
        val doc = JSONObject(body)
        if (doc.has("error")) throw IOException("Census municipal service error")
        if (doc.optString("type") != "FeatureCollection") {
            throw IOException("Census service did not return GeoJSON")
        }
        val features = doc.optJSONArray("features") ?: return null
        val wanted = municipalityIdentity(place.displayName)
        val state = stateFips[place.parentName.trim().lowercase(Locale.ROOT)] ?: return null
        var selected: PlaceOverlayData? = null
        var distance = Double.POSITIVE_INFINITY
        for (i in 0 until features.length()) {
            val feature = features.optJSONObject(i) ?: continue
            val props = feature.optJSONObject("properties") ?: continue
            if (props.optString("STATE") != state) continue
            val geometry = feature.optJSONObject("geometry") ?: continue
            if (geometry.optString("type") !in setOf("Polygon", "MultiPolygon")) continue
            val actual = municipalityIdentity(
                props.optString("NAME").ifBlank { props.optString("BASENAME") }
            )
            val bare = municipalityIdentity(props.optString("BASENAME"))
            if (actual.first != wanted.first && bare.first != wanted.first) continue
            val actualType = actual.second
            // Basenames alone cannot prove a township is a city, or vice versa.
            if (wanted.second != null && actualType != null && wanted.second != actualType) continue
            if (wanted.second == "township" && layer != 1) continue
            if (wanted.second in setOf("city", "borough", "village") && layer != 4) continue
            val nearest = distanceToPolygonMeters(
                geometry, place.latitude, place.longitude
            )
            if (!nearest.isFinite() || nearest > MAX_SAVED_ERROR_METERS || nearest >= distance) {
                continue
            }
            val areaSqMeters = props.optDouble("AREALAND", Double.NaN) +
                props.optDouble("AREAWATER", 0.0)
            val areaSqKm = if (areaSqMeters.isFinite() && areaSqMeters > 0.0) {
                areaSqMeters / 1_000_000.0
            } else PlaceOverlayClient.areaSquareKilometers(geometry)
            if (!areaSqKm.isFinite() || areaSqKm <= 0.0) continue
            distance = nearest
            selected = PlaceOverlayData(
                place.key, place.displayName, place.kind, null, areaSqKm, geometry.toString()
            )
        }
        return selected
    }

    /**
     * Distance from a saved first-visit position to the actual Census polygon,
     * including holes. Early versions saved a rounded cell center rather than
     * the exact GPS fix, so a small positional tolerance is necessary.
     */
    private fun distanceToPolygonMeters(
        polygon: JSONObject, latitude: Double, longitude: Double
    ): Double {
        if (!latitude.isFinite() || !longitude.isFinite()) return Double.POSITIVE_INFINITY
        if (PlaceGeometry.contains(polygon, latitude, longitude)) return 0.0
        val polygons = polygon.optJSONArray("coordinates") ?: return Double.POSITIVE_INFINITY
        val scale = 111_195.0
        var closest = Double.POSITIVE_INFINITY
        fun ringDistance(rings: org.json.JSONArray) {
            for (r in 0 until rings.length()) {
                val ring = rings.optJSONArray(r) ?: continue
                if (ring.length() < 2) continue
                var previous = ring.optJSONArray(ring.length() - 1) ?: continue
                for (i in 0 until ring.length()) {
                    val current = ring.optJSONArray(i) ?: continue
                    fun x(point: org.json.JSONArray) =
                        (point.optDouble(0, Double.NaN) - longitude) *
                            cos(Math.toRadians(latitude)) * scale
                    fun y(point: org.json.JSONArray) =
                        (point.optDouble(1, Double.NaN) - latitude) * scale
                    val ax = x(previous); val ay = y(previous)
                    val bx = x(current); val by = y(current)
                    if (ax.isFinite() && ay.isFinite() && bx.isFinite() && by.isFinite()) {
                        val dx = bx - ax; val dy = by - ay
                        val lengthSquared = dx * dx + dy * dy
                        val t = if (lengthSquared <= 0.0) 0.0 else
                            (-(ax * dx + ay * dy) / lengthSquared).coerceIn(0.0, 1.0)
                        closest = minOf(closest, hypot(ax + t * dx, ay + t * dy))
                    }
                    previous = current
                }
            }
        }
        when (polygon.optString("type")) {
            "Polygon" -> ringDistance(polygons)
            "MultiPolygon" -> for (i in 0 until polygons.length()) {
                polygons.optJSONArray(i)?.let(::ringDistance)
            }
        }
        return closest
    }

    private fun municipalityIdentity(raw: String): Pair<String, String?> {
        var name = raw.lowercase(Locale.ROOT).trim()
            .replace(WHITESPACE_RE, " ").removePrefix("the ")
        var type: String? = null
        for ((suffix, canonical) in listOf(
            " township" to "township", " twp" to "township",
            " borough" to "borough", " boro" to "borough",
            " city" to "city", " village" to "village", " town" to "town"
        )) {
            if (name.endsWith(suffix)) {
                name = name.removeSuffix(suffix)
                type = canonical
                break
            }
        }
        for ((prefix, canonical) in listOf(
            "township of " to "township", "borough of " to "borough",
            "city of " to "city", "village of " to "village", "town of " to "town"
        )) {
            if (name.startsWith(prefix)) {
                name = name.removePrefix(prefix)
                type = canonical
                break
            }
        }
        return name to type
    }
}
