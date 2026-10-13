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

/**
 * Resolve the municipality containing a genuine recorded GPS fix.
 * Incorporated places take precedence over consolidated cities, then county subdivisions throughout
 * the United States; no state-specific or postal-name heuristics are used.
 */
internal object UnitedStatesMunicipalityAtPoint {
    data class Result(val place: PlaceDiscovery, val overlay: PlaceOverlayData)

    private const val BASE =
        "https://tigerweb.geo.census.gov/arcgis/rest/services/" +
            "TIGERweb/Places_CouSub_ConCity_SubMCD/MapServer"
    private const val MAX_RESPONSE_CHARS = 16 * 1024 * 1024
    private val WHITESPACE_RE = Regex("\\s+")

    fun fetch(seed: PlaceDiscovery): Result? {
        val state = UnitedStatesMunicipalBoundaries.stateCode(
            seed.countryName, seed.parentName
        ) ?: return null
        require(seed.kind == PlaceKind.TOWN)
        if (!seed.latitude.isFinite() || !seed.longitude.isFinite() ||
            seed.latitude !in -90.0..90.0 || seed.longitude !in -180.0..180.0
        ) return null

        for (layer in listOf(4, 3, 1)) {
            val request = Uri.parse("$BASE/$layer/query").buildUpon()
                .appendQueryParameter("where", "STATE = '$state'")
                .appendQueryParameter("geometry", "${seed.longitude},${seed.latitude}")
                .appendQueryParameter("geometryType", "esriGeometryPoint")
                .appendQueryParameter("inSR", "4326")
                .appendQueryParameter("spatialRel", "esriSpatialRelIntersects")
                .appendQueryParameter("outFields",
                    "BASENAME,NAME,STATE,GEOID,FUNCSTAT,AREALAND,AREAWATER")
                .appendQueryParameter("returnGeometry", "true")
                .appendQueryParameter("outSR", "4326")
                .appendQueryParameter("resultRecordCount", "20")
                .appendQueryParameter("f", "geojson")
                .build().toString()
            val connection = (URL(request).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8_000
                readTimeout = 20_000
                setRequestProperty("User-Agent",
                    "RoadConquest/${BuildConfig.VERSION_NAME} (+https://github.com/AntnyGamer/RoadConquest-Beta)")
                setRequestProperty("Accept", "application/geo+json, application/json")
            }
            val body = try {
                if (connection.responseCode !in 200..299) {
                    throw IOException("Census municipality service HTTP ${connection.responseCode}")
                }
                connection.inputStream.bufferedReader().use { reader ->
                    val builder = StringBuilder()
                    val buffer = CharArray(8192)
                    while (true) {
                        val length = reader.read(buffer)
                        if (length < 0) break
                        if (builder.length + length > MAX_RESPONSE_CHARS) {
                            throw IOException("Census municipality response too large")
                        }
                        builder.append(buffer, 0, length)
                    }
                    builder.toString()
                }
            } finally {
                connection.disconnect()
            }
            parseResponse(seed, body, layer)?.let { return it }
        }
        return null
    }

    /** Parsing is independently testable without accessing the network. */
    internal fun parseResponse(seed: PlaceDiscovery, body: String, layer: Int): Result? {
        if (layer !in setOf(1, 3, 4)) return null
        val state = UnitedStatesMunicipalBoundaries.stateCode(
            seed.countryName, seed.parentName
        ) ?: return null
        val doc = try {
            JSONObject(body)
        } catch (e: org.json.JSONException) {
            // A malformed/HTML response from the service is a retryable provider
            // failure, not a reason to abort the foreground discovery worker.
            throw IOException("Invalid Census municipality response", e)
        }
        if (doc.has("error")) throw IOException("Census municipality service error")
        if (doc.optString("type") != "FeatureCollection") {
            throw IOException("Unexpected municipality GeoJSON")
        }
        val features = doc.optJSONArray("features")
            ?: throw IOException("Census municipality GeoJSON lacks a features array")
        for (index in 0 until features.length()) {
            val feature = features.optJSONObject(index) ?: continue
            val properties = feature.optJSONObject("properties") ?: continue
            if (properties.optString("STATE") != state) continue
            // County-subdivision statistics do not constitute towns.
            if (layer == 1 && properties.optString("FUNCSTAT") != "A") continue
            val geometry = feature.optJSONObject("geometry") ?: continue
            if (geometry.optString("type") !in setOf("Polygon", "MultiPolygon") ||
                !PlaceGeometry.contains(geometry, seed.latitude, seed.longitude)
            ) continue
            val name = properties.optString("NAME").trim()
                .ifBlank { properties.optString("BASENAME").trim() }
            if (name.isBlank()) continue
            val officialArea = properties.optDouble("AREALAND", Double.NaN) +
                properties.optDouble("AREAWATER", 0.0)
            val areaSqKm = if (officialArea.isFinite() && officialArea > 0.0) {
                officialArea / 1_000_000.0
            } else PlaceOverlayClient.areaSquareKilometers(geometry)
            if (!areaSqKm.isFinite() || areaSqKm <= 0.0) continue
            val canonical = name.trim().lowercase(Locale.ROOT)
                .replace(WHITESPACE_RE, " ")
            // Names repeat within a state (including same-named county subdivisions).
            // The Census layer + GEOID identify the actual polygon, not its label.
            // Preserve name keys as a fallback for older service responses.
            val geoid = properties.optString("GEOID").trim()
            val validGeoid = geoid.length == (if (layer == 1) 10 else 7) &&
                geoid.startsWith(state) && geoid.all(Char::isDigit)
            val identity = if (validGeoid) "census-$layer-$geoid" else canonical
            val key = seed.key.substringBeforeLast('|') + "|" + identity
            val place = seed.copy(key = key, displayName = name)
            return Result(
                place,
                PlaceOverlayData(key, name, PlaceKind.TOWN, null, areaSqKm, geometry.toString())
            )
        }
        return null
    }
}
