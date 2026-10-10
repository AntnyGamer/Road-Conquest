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
            return parse(body.toString(), latitude, longitude)
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

    fun asOverlay(place: PlaceDiscovery, municipality: Municipality): PlaceOverlayData? {
        if (!equivalent(place.displayName, municipality.officialName)) return null
        return PlaceOverlayData(
            place.key, municipality.officialName, place.kind, municipality.population,
            municipality.areaSquareKilometers, municipality.geometryJson
        )
    }
}
