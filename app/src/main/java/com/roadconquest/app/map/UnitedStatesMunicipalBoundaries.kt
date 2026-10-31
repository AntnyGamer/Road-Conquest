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
 * (layer 4), consolidated-city (layer 3), and county-subdivision (layer 1) features.
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
    private val OFFICIAL_CENSUS_ID_RE = Regex("""\|census-(1|3|4)-(\d+)$""")
    // State files published by Census for the CONCITY layer (2025 vintage).
    // Other states have no consolidated-city polygons, so do not make an
    // otherwise empty additional network request for every driven town.
    private val CONSOLIDATED_CITY_STATES = setOf("09", "13", "18", "20", "21", "30", "47")

    internal fun hasConsolidatedCities(stateFips: String): Boolean =
        stateFips in CONSOLIDATED_CITY_STATES
    private val MUNICIPAL_SUFFIXES = listOf(
        " township" to "township", " twp" to "township",
        " borough" to "borough", " boro" to "borough",
        " city" to "city", " village" to "village", " town" to "town"
    )
    private val MUNICIPAL_PREFIXES = listOf(
        "township of " to "township", "borough of " to "borough",
        "city of " to "city", "village of " to "village", "town of " to "town"
    )

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

    // Android reverse-geocoders may provide USPS abbreviations rather than full
    // state names. Normalize BOTH spellings before constructing visit keys or
    // calling Census, so a service/provider change cannot create two identities.
    private val stateNamesByPostal = mapOf(
        "al" to "Alabama", "ak" to "Alaska", "az" to "Arizona",
        "ar" to "Arkansas", "ca" to "California", "co" to "Colorado",
        "ct" to "Connecticut", "de" to "Delaware", "dc" to "District of Columbia",
        "fl" to "Florida", "ga" to "Georgia", "hi" to "Hawaii",
        "id" to "Idaho", "il" to "Illinois", "in" to "Indiana",
        "ia" to "Iowa", "ks" to "Kansas", "ky" to "Kentucky",
        "la" to "Louisiana", "me" to "Maine", "md" to "Maryland",
        "ma" to "Massachusetts", "mi" to "Michigan", "mn" to "Minnesota",
        "ms" to "Mississippi", "mo" to "Missouri", "mt" to "Montana",
        "ne" to "Nebraska", "nv" to "Nevada", "nh" to "New Hampshire",
        "nj" to "New Jersey", "nm" to "New Mexico", "ny" to "New York",
        "nc" to "North Carolina", "nd" to "North Dakota", "oh" to "Ohio",
        "ok" to "Oklahoma", "or" to "Oregon", "pa" to "Pennsylvania",
        "ri" to "Rhode Island", "sc" to "South Carolina",
        "sd" to "South Dakota", "tn" to "Tennessee", "tx" to "Texas",
        "ut" to "Utah", "vt" to "Vermont", "va" to "Virginia",
        "wa" to "Washington", "wv" to "West Virginia", "wi" to "Wisconsin",
        "wy" to "Wyoming", "pr" to "Puerto Rico"
    )
    private val officialStateNameByLowercase =
        stateNamesByPostal.values.associateBy { it.lowercase(Locale.ROOT) }
    private val officialStateNameByFips =
        stateFips.entries.associate { (name, fips) ->
            fips to (officialStateNameByLowercase[name] ?: name)
        }

    /** Translate an official Census coordinate lookup into our canonical state identity. */
    internal fun stateNameFromFips(fips: String): String? = officialStateNameByFips[fips]
    private val postalCodeByOfficialStateName =
        stateNamesByPostal.entries.associate { (postal, name) -> name.lowercase(Locale.ROOT) to postal }

    private val US_NAMES = setOf(
        "us", "usa", "united states", "united states of america"
    )

    internal fun isUnitedStatesCountry(value: String): Boolean =
        value.trim().lowercase(Locale.ROOT) in US_NAMES

    fun isUnitedStatesKey(key: String): Boolean =
        isUnitedStatesCountry(key.substringBefore('|'))

    fun applies(place: PlaceDiscovery): Boolean =
        place.kind == PlaceKind.TOWN &&
            stateCode(place.countryName, place.parentName) != null

    /** State lookup shared by all U.S. municipality discovery and rendering. */
    internal fun normalizedStateName(countryName: String, stateName: String): String {
        val trimmed = stateName.trim()
        if (countryName.trim().lowercase(Locale.ROOT) !in US_NAMES) return trimmed
        val lower = trimmed.lowercase(Locale.ROOT)
        return stateNamesByPostal[lower] ?: officialStateNameByLowercase[lower] ?: trimmed
    }

    internal fun postalCode(countryName: String, stateName: String): String? =
        postalCodeByOfficialStateName[
            normalizedStateName(countryName, stateName).lowercase(Locale.ROOT)
        ]?.takeIf { stateCode(countryName, stateName) != null }

    internal fun stateCode(countryName: String, stateName: String): String? {
        if (countryName.trim().lowercase(Locale.ROOT) !in US_NAMES) return null
        return stateFips[normalizedStateName(countryName, stateName).lowercase(Locale.ROOT)]
    }

    /**
     * Layer selection is conservative. A township should not be replaced by
     * an overlapping incorporated city. When a city has no county subdivision
     * status, it should not be replaced by a similarly named township.
     */
    fun fetch(place: PlaceDiscovery): PlaceOverlayData? {
        require(applies(place))
        val normalized = municipalityIdentity(place.displayName)
        val state = stateCode(place.countryName, place.parentName) ?: return null
        val officialIdentity = OFFICIAL_CENSUS_ID_RE.find(place.key)
        // Names may change after the original Census GEOID was discovered.
        // An authoritative saved identity must not depend on the old label.
        if (officialIdentity == null && normalized.first.length !in 2..90) return null
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
        val officialLayer = officialIdentity?.groupValues?.get(1)?.toIntOrNull()
        // A saved official GEOID is already an exact identifier, so don't fetch
        // every nearby Census polygon and discard all but one on the client.
        // Legacy name-only visits must retain the broader search.
        val where = municipalityWhereClause(state, officialIdentity?.groupValues?.get(2))
        val layerOrder = if (officialLayer != null) listOf(officialLayer) else if (normalized.second == "township" ||
            normalized.second == "town" &&
            place.parentName.trim().lowercase(Locale.ROOT) in setOf(
                "connecticut", "massachusetts", "maine", "new hampshire",
                "rhode island", "vermont", "new york", "wisconsin"
            )
        ) listOf(1, 4, 3) else listOf(4, 3, 1)
        val relevantLayers = if (officialLayer == null && !hasConsolidatedCities(state)) {
            layerOrder.filterNot { it == 3 }
        } else layerOrder
        for (layer in relevantLayers) {
            val query = Uri.parse("$BASE/$layer/query").buildUpon()
                .appendQueryParameter("where", where)
            // For legacy name-only records, the nearby envelope prevents
            // thousands of same-state results. For a verified Census GEOID the
            // indexed identity is already unique: do not also require the
            // originally visited GPS point to remain near a subsequently
            // revised municipal boundary.
            if (officialLayer == null) {
                query.appendQueryParameter("geometry", envelope)
                    .appendQueryParameter("geometryType", "esriGeometryEnvelope")
                    .appendQueryParameter("inSR", "4326")
                    .appendQueryParameter("spatialRel", "esriSpatialRelIntersects")
            }
            val url = query
                .appendQueryParameter("outFields",
                    "BASENAME,NAME,STATE,GEOID,LSADC,FUNCSTAT,AREALAND,AREAWATER")
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
                    "RoadConquest/${BuildConfig.VERSION_NAME} (+https://github.com/AntnyGamer/Road-Conquest)")
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

    internal fun municipalityWhereClause(stateFips: String, officialGeoid: String?): String =
        if (officialGeoid != null && officialGeoid.all(Char::isDigit)) {
            "STATE = '$stateFips' AND GEOID = '$officialGeoid'"
        } else "STATE = '$stateFips'"

    internal fun parseResponse(place: PlaceDiscovery, body: String, layer: Int): PlaceOverlayData? {
        if (!applies(place) || layer !in setOf(1, 3, 4)) return null
        val doc = JSONObject(body)
        if (doc.has("error")) throw IOException("Census municipal service error")
        if (doc.optString("type") != "FeatureCollection") {
            throw IOException("Census service did not return GeoJSON")
        }
        val features = doc.optJSONArray("features")
            ?: throw IOException("Census boundary GeoJSON lacks a features array")
        val wanted = municipalityIdentity(place.displayName)
        val state = stateCode(place.countryName, place.parentName) ?: return null
        // New verified discovery keys bind overlays to a single Census polygon.
        // Legacy name-only keys retain the existing name-and-position fallback.
        val officialIdentity = OFFICIAL_CENSUS_ID_RE.find(place.key)
        val requestedLayer = officialIdentity?.groupValues?.get(1)?.toIntOrNull()
        val requestedGeoid = officialIdentity?.groupValues?.get(2)
        var selected: PlaceOverlayData? = null
        var distance = Double.POSITIVE_INFINITY
        for (i in 0 until features.length()) {
            val feature = features.optJSONObject(i) ?: continue
            val props = feature.optJSONObject("properties") ?: continue
            if (props.optString("STATE") != state) continue
            if (requestedLayer != null &&
                (requestedLayer != layer || props.optString("GEOID") != requestedGeoid)
            ) continue
            val geometry = feature.optJSONObject("geometry") ?: continue
            if (geometry.optString("type") !in setOf("Polygon", "MultiPolygon")) continue
            // Exact official IDs already verify both Census layer and GEOID above.
            // The display name may have changed between Census vintages; a name,
            // municipal suffix or positional heuristic must not reject this same
            // identified polygon. Keep the strict legacy-name checks below for
            // records without a trustworthy official ID.
            val nearest = if (requestedLayer != null) 0.0 else {
                val actual = municipalityIdentity(
                    props.optString("NAME").ifBlank { props.optString("BASENAME") }
                )
                val bare = municipalityIdentity(props.optString("BASENAME"))
                if (actual.first != wanted.first && bare.first != wanted.first) continue
                val actualType = actual.second
                if (wanted.second != null && actualType != null && wanted.second != actualType) continue
                if (wanted.second == "township" && layer != 1) continue
                if (wanted.second == "city" && layer !in setOf(3, 4)) continue
                if (wanted.second in setOf("borough", "village") && layer != 4) continue
                distanceToPolygonMeters(geometry, place.latitude, place.longitude)
            }
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
        val latitudeCos = cos(Math.toRadians(latitude))
        var closest = Double.POSITIVE_INFINITY
        fun x(point: org.json.JSONArray) =
            (point.optDouble(0, Double.NaN) - longitude) * latitudeCos * scale
        fun y(point: org.json.JSONArray) =
            (point.optDouble(1, Double.NaN) - latitude) * scale
        fun ringDistance(rings: org.json.JSONArray) {
            for (r in 0 until rings.length()) {
                val ring = rings.optJSONArray(r) ?: continue
                if (ring.length() < 2) continue
                val previous = ring.optJSONArray(ring.length() - 1) ?: continue
                var ax = x(previous)
                var ay = y(previous)
                for (i in 0 until ring.length()) {
                    val current = ring.optJSONArray(i) ?: continue
                    val bx = x(current)
                    val by = y(current)
                    if (ax.isFinite() && ay.isFinite() && bx.isFinite() && by.isFinite()) {
                        val dx = bx - ax; val dy = by - ay
                        val lengthSquared = dx * dx + dy * dy
                        val t = if (lengthSquared <= 0.0) 0.0 else
                            (-(ax * dx + ay * dy) / lengthSquared).coerceIn(0.0, 1.0)
                        closest = minOf(closest, hypot(ax + t * dx, ay + t * dy))
                    }
                    ax = bx
                    ay = by
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
        for ((suffix, canonical) in MUNICIPAL_SUFFIXES) {
            if (name.endsWith(suffix)) {
                name = name.removeSuffix(suffix)
                type = canonical
                break
            }
        }
        for ((prefix, canonical) in MUNICIPAL_PREFIXES) {
            if (name.startsWith(prefix)) {
                name = name.removePrefix(prefix)
                type = canonical
                break
            }
        }
        return name to type
    }
}
