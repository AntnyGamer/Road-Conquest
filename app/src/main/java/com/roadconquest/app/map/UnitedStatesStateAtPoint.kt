package com.roadconquest.app.map

import android.net.Uri
import com.roadconquest.app.BuildConfig
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Reverse-geocode U.S. state identity from the authoritative Census state polygons
 * when Android's optional Geocoder provider is missing or temporarily unavailable.
 * Return no result for non-U.S. GPS fixes; never guess a country from coordinates.
 */
internal object UnitedStatesStateAtPoint {
    private const val BASE =
        "https://tigerweb.geo.census.gov/arcgis/rest/services/" +
            "TIGERweb/State_County/MapServer/0/query"

    fun fetch(latitude: Double, longitude: Double): String? {
        if (!latitude.isFinite() || !longitude.isFinite() ||
            latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return null
        val url = Uri.parse(BASE).buildUpon()
            .appendQueryParameter("where", "1=1")
            .appendQueryParameter("geometry", "$longitude,$latitude")
            .appendQueryParameter("geometryType", "esriGeometryPoint")
            .appendQueryParameter("inSR", "4326")
            .appendQueryParameter("spatialRel", "esriSpatialRelIntersects")
            .appendQueryParameter("outFields", "STATE,BASENAME")
            .appendQueryParameter("returnGeometry", "false")
            .appendQueryParameter("f", "json")
            .build().toString()
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 10_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent",
                "RoadConquest/${BuildConfig.VERSION_NAME} (+https://github.com/AntnyGamer/RoadConquest-Beta)")
        }
        return try {
            if (connection.responseCode !in 200..299) throw IOException("Census state query HTTP ${connection.responseCode}")
            val bytes = connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (output.size() + n > 64 * 1024) throw IOException("Census state response too large")
                    output.write(buffer, 0, n)
                }
                output.toString(Charsets.UTF_8.name())
            }
            parseResponse(bytes)
        } finally {
            connection.disconnect()
        }
    }

    internal fun parseResponse(json: String): String? {
        val root = try { JSONObject(json) } catch (e: org.json.JSONException) {
            throw IOException("Invalid Census state JSON", e)
        }
        if (root.has("error")) throw IOException("Census state service error")
        val entries = root.optJSONArray("features")
            ?: throw IOException("Census state response lacks features")
        for (i in 0 until entries.length()) {
            val attrs = entries.optJSONObject(i)?.optJSONObject("attributes") ?: continue
            val name = attrs.optString("BASENAME").trim()
            val code = attrs.optString("STATE").trim()
            if (code.length == 2 && name.isNotBlank() &&
                UnitedStatesMunicipalBoundaries.stateCode("United States", name) == code
            ) return UnitedStatesMunicipalBoundaries.normalizedStateName("United States", name)
        }
        return null
    }
}
