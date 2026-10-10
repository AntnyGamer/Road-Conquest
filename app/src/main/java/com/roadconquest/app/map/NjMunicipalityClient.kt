package com.roadconquest.app.map

import android.net.Uri
import com.roadconquest.app.data.PlaceDiscovery
import com.roadconquest.app.data.PlaceKind
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * New Jersey's own Office of GIS municipal boundary layer. Unlike a postal
 * geocoder result or a text search, this is a point-in-polygon query against
 * the state's municipality dataset: counties, CDPs and neighborhoods cannot
 * be returned as a municipality. Other states keep the existing OSM source.
 *
 * https://maps.nj.gov/arcgis/rest/services/Framework/Government_Boundaries/MapServer/2
 * These are reference GIS boundaries, not legal surveying documents.
 */
internal object NjMunicipalityClient {
    private const val SERVICE =
        "https://maps.nj.gov/arcgis/rest/services/Framework/Government_Boundaries/MapServer/2/query"
    private const val MAX_RESPONSE_CHARS = 12 * 1024 * 1024
    private val rateLock = Any()
    private var lastCallMillis = 0L
    // Frequent fixes on the same drive must NOT download the same town outline
    // every ~50 meters. Full geometry is kept only for a small bounded set.
    private val boundaryCache = object : LinkedHashMap<String, Municipality>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Municipality>): Boolean =
            size > 24
    }
    private val cacheLock = Any()

    private fun cachedAt(latitude: Double, longitude: Double): Municipality? =
        synchronized(cacheLock) {
            val snapshot = boundaryCache.entries.toList().asReversed()
            for ((key, municipality) in snapshot) {
                val contains = runCatching {
                    PlaceGeometry.contains(
                        JSONObject(municipality.geometryJson), latitude, longitude
                    )
                }.getOrDefault(false)
                if (contains) {
                    return@synchronized boundaryCache[key]
                }
            }
            null
        }

    data class Municipality(
        val officialName: String,
        val geometryJson: String,
        val areaSquareKilometers: Double,
        val population: Long?
    )

    fun applies(place: PlaceDiscovery): Boolean =
        place.kind == PlaceKind.TOWN &&
            place.parentName.trim().equals("New Jersey", ignoreCase = true) &&
            (place.countryName.trim().equals("United States", ignoreCase = true) ||
                place.countryName.trim().equals("USA", ignoreCase = true))

    /** Obtain the actual municipality containing this GPS fix, not a nearby postal town. */
    @Throws(IOException::class)
    fun at(latitude: Double, longitude: Double): Municipality? {
        if (!latitude.isFinite() || !longitude.isFinite() ||
            latitude !in -90.0..90.0 || longitude !in -180.0..180.0
        ) return null
        cachedAt(latitude, longitude)?.let { return it }
        synchronized(rateLock) {
            val now = android.os.SystemClock.elapsedRealtime()
            val waitMs = 800L - (now - lastCallMillis)
            if (lastCallMillis > 0 && waitMs > 0) Thread.sleep(waitMs)
            lastCallMillis = android.os.SystemClock.elapsedRealtime()
        }
        val request = Uri.parse(SERVICE).buildUpon()
            .appendQueryParameter("f", "geojson")
            .appendQueryParameter("where", "1=1")
            .appendQueryParameter("geometry", "$longitude,$latitude")
            .appendQueryParameter("geometryType", "esriGeometryPoint")
            .appendQueryParameter("inSR", "4326")
            .appendQueryParameter("spatialRel", "esriSpatialRelIntersects")
            .appendQueryParameter("outFields", "NAME,MUN,MUN_LABEL,MUN_TYPE,SQ_MILES,POP2020")
            .appendQueryParameter("outSR", "4326")
            .appendQueryParameter("returnGeometry", "true")
            .build()
            .toString()
        val connection = URL(request).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 8_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("User-Agent",
                "RoadConquest/municipality-verification (+https://github.com/AntnyGamer/RoadConquest-Beta)")
            connection.setRequestProperty("Accept", "application/geo+json, application/json")
            if (connection.responseCode !in 200..299) {
                throw IOException("NJ municipal boundary service returned HTTP ${connection.responseCode}")
            }
            val body = StringBuilder()
            connection.inputStream.bufferedReader().use { stream ->
                val buffer = CharArray(8192)
                while (true) {
                    val size = stream.read(buffer)
                    if (size < 0) break
                    if (size + body.length > MAX_RESPONSE_CHARS) {
                        throw IOException("Municipal polygon response is too large")
                    }
                    body.append(buffer, 0, size)
                }
            }
            return parse(body.toString(), latitude, longitude)?.also { municipality ->
                synchronized(cacheLock) {
                    boundaryCache["${municipality.officialName}|${latitude}|${longitude}"] = municipality
                }
            }
        } catch (error: org.json.JSONException) {
            throw IOException("Malformed NJ municipality response", error)
        } finally {
            connection.disconnect()
        }
    }

    /** Do not accept a geographic match without an actual polygon containing our fix. */
    internal fun parse(body: String, latitude: Double, longitude: Double): Municipality? {
        val document = JSONObject(body)
        if (document.has("error")) throw IOException("NJ municipality service error")
        if (document.optString("type") != "FeatureCollection") {
            throw IOException("NJ municipality service did not return GeoJSON")
        }
        val features = document.optJSONArray("features") ?: return null
        for (i in 0 until features.length()) {
            val feature = features.optJSONObject(i) ?: continue
            val props = feature.optJSONObject("properties") ?: continue
            val geometry = feature.optJSONObject("geometry") ?: continue
            if (!PlaceGeometry.contains(geometry, latitude, longitude)) continue
            val name = sequenceOf("NAME", "MUN_LABEL", "MUN")
                .map { props.optString(it).trim() }
                .firstOrNull(String::isNotBlank) ?: continue
            val areaMiles = props.optDouble("SQ_MILES", Double.NaN)
            val areaKm = if (areaMiles.isFinite() && areaMiles > 0) areaMiles * 2.589988110336
                else PlaceOverlayClient.areaSquareKilometers(geometry)
            if (!areaKm.isFinite() || areaKm <= 0.0) continue
            val pop = props.optLong("POP2020", 0L).takeIf { it > 0 }
            return Municipality(name, geometry.toString(), areaKm, pop)
        }
        return null
    }

    /** Legal municipal type distinguishes Gloucester CITY from Gloucester TOWNSHIP. */
    internal fun equivalent(first: String, second: String): Boolean {
        val a = identity(first)
        val b = identity(second)
        return a.first.isNotBlank() && a.first == b.first &&
            (a.second == null || b.second == null || a.second == b.second)
    }

    private fun identity(value: String): Pair<String, String?> {
        var text = value.trim().lowercase(Locale.ROOT)
            .replace(Regex("\\s+"), " ").removePrefix("the ")
        var kind: String? = null
        repeat(3) {
            for (candidate in listOf("township", "borough", "city", "town", "village", "municipality")) {
                val prefix = "${candidate} of "
                val suffix = " $candidate"
                if (text.startsWith(prefix) || text.endsWith(suffix)) {
                    if (kind != null && kind != candidate) return value.lowercase(Locale.ROOT) to null
                    text = if (text.startsWith(prefix)) text.removePrefix(prefix)
                        else text.removeSuffix(suffix)
                    kind = candidate
                }
            }
        }
        return text to kind
    }

    /**
     * The overlay is a MUNICIPAL OUTLINE lookup, not a visit detector.
     * Retrieve the actual NJ municipal geometry by its official name. Using a
     * single stored visit coordinate can leave a blank hole if an older app
     * saved an approximate grid-cell center just across a municipal line.
     *
     * A 5 km GIS search around that older coordinate reduces ambiguous names.
     * Never use a postal locality polygon or infer geometry from visited roads.
     */
    @Throws(IOException::class)
    fun forOverlay(
        place: PlaceDiscovery,
        gpsEvidence: List<Pair<Double, Double>> = emptyList()
    ): Municipality? {
        if (!applies(place)) return null
        val coreName = identity(place.displayName).first
        if (coreName.length !in 2..80) return null
        // SQL-escape literal input and keep a bounded nearby official layer query.
        val likeName = coreName.uppercase(Locale.ROOT)
            .replace("'", "''").replace("%", "").replace("_", "")
        val where = "UPPER(NAME) LIKE '%$likeName%' OR UPPER(MUN_LABEL) LIKE '%$likeName%'"
        val request = android.net.Uri.parse(SERVICE).buildUpon()
            .appendQueryParameter("f", "geojson")
            .appendQueryParameter("where", "($where)")
            .appendQueryParameter("geometry", "${place.longitude},${place.latitude}")
            .appendQueryParameter("geometryType", "esriGeometryPoint")
            .appendQueryParameter("inSR", "4326")
            .appendQueryParameter("spatialRel", "esriSpatialRelIntersects")
            .appendQueryParameter("distance", "5000")
            .appendQueryParameter("units", "esriSRUnit_Meter")
            .appendQueryParameter("outFields", "NAME,MUN,MUN_LABEL,MUN_TYPE,COUNTY,SQ_MILES,POP2020")
            .appendQueryParameter("outSR", "4326")
            .appendQueryParameter("returnGeometry", "true")
            .build().toString()

        val body = requestBody(request)
        return selectByOfficialName(body, place, gpsEvidence)
    }

    internal fun selectByOfficialName(
        body: String,
        place: PlaceDiscovery,
        gpsEvidence: List<Pair<Double, Double>> = emptyList()
    ): Municipality? {
        val document = JSONObject(body)
        if (document.has("error")) throw IOException("NJ GIS query failed")
        if (document.optString("type") != "FeatureCollection") {
            throw IOException("Expected municipality FeatureCollection")
        }
        val candidates = document.optJSONArray("features") ?: return null
        val matches = ArrayList<Municipality>()
        for (i in 0 until candidates.length()) {
            val feature = candidates.optJSONObject(i) ?: continue
            val properties = feature.optJSONObject("properties") ?: continue
            val geometry = feature.optJSONObject("geometry") ?: continue
            if (geometry.optString("type") !in listOf("Polygon", "MultiPolygon")) continue
            val officialName = sequenceOf("NAME", "MUN_LABEL")
                .map { properties.optString(it).trim() }
                .firstOrNull(String::isNotBlank) ?: continue
            if (!equivalent(officialName, place.displayName)) continue
            val areaMiles = properties.optDouble("SQ_MILES", Double.NaN)
            val areaKm = if (areaMiles.isFinite() && areaMiles > 0)
                areaMiles * 2.589988110336
            else PlaceOverlayClient.areaSquareKilometers(geometry)
            if (!areaKm.isFinite() || areaKm <= 0.0) continue
            matches += Municipality(
                officialName, geometry.toString(), areaKm,
                properties.optLong("POP2020", 0L).takeIf { it > 0 }
            )
        }
        if (matches.isEmpty()) return null
        // Several New Jersey counties contain identically named municipalities.
        // Prefer a polygon supported by original GPS evidence, otherwise
        // don't guess between multiple official municipalities.
        val anchors = gpsEvidence + (place.latitude to place.longitude)
        return matches.firstOrNull { municipality ->
            val polygon = JSONObject(municipality.geometryJson)
            anchors.any { (lat, lon) -> PlaceGeometry.contains(polygon, lat, lon) }
        } ?: matches.singleOrNull()
    }

    private fun requestBody(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 8_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("Accept", "application/geo+json, application/json")
            connection.setRequestProperty(
                "User-Agent",
                "RoadConquest/municipality-boundaries (+https://github.com/AntnyGamer/RoadConquest-Beta)"
            )
            if (connection.responseCode !in 200..299) {
                throw IOException("NJ GIS returned HTTP ${connection.responseCode}")
            }
            val response = StringBuilder()
            connection.inputStream.bufferedReader().use { stream ->
                val chars = CharArray(8192)
                while (true) {
                    val n = stream.read(chars)
                    if (n < 0) break
                    if (response.length + n > MAX_RESPONSE_CHARS) {
                        throw IOException("NJ polygon response is too large")
                    }
                    response.append(chars, 0, n)
                }
            }
            return response.toString()
        } catch (e: org.json.JSONException) {
            throw IOException("Malformed NJ GIS response", e)
        } finally {
            connection.disconnect()
        }
    }

    fun asOverlay(place: PlaceDiscovery, municipality: Municipality): PlaceOverlayData? {
        if (!equivalent(place.displayName, municipality.officialName)) return null
        return PlaceOverlayData(
            place.key, municipality.officialName, place.kind, municipality.population,
            municipality.areaSquareKilometers, municipality.geometryJson,
            authoritativeNjBoundary = true
        )
    }
}
