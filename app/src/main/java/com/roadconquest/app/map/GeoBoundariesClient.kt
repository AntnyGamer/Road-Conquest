package com.roadconquest.app.map

import com.roadconquest.app.data.PlaceDiscovery
import com.roadconquest.app.data.PlaceKind
import com.roadconquest.app.services.ProviderHealth
import com.roadconquest.app.services.readBounded
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.Locale

/** gbOpen is a display backup for countries/regions, never a municipal reward authority. */
class GeoBoundariesClient(private val loadJson: (String, Int) -> JSONObject = ::readJson) {
    private val datasets = LinkedHashMap<String, Pair<Long, JSONObject>>()
    private val isoTwoLetters = Regex("[A-Z]{2}")
    @Synchronized fun fetch(place: PlaceDiscovery, requireContainment: Boolean): PlaceOverlayData? {
        if (place.kind == PlaceKind.TOWN || ProviderHealth.shared.remaining("geoboundaries") > 0) return null
        val country = place.key.substringBefore('|').uppercase(Locale.ROOT)
        if (!country.matches(isoTwoLetters)) return null
        val iso3 = runCatching { Locale.Builder().setRegion(country).build().isO3Country }.getOrNull() ?: return null
        val level = if (place.kind == PlaceKind.COUNTRY) "ADM0" else "ADM1"
        val key = "$iso3/$level"
        return try {
            val cached = datasets[key]?.takeIf { System.currentTimeMillis() - it.first in 0L..900_000L }?.second
            val data = cached ?: run {
                val metadata = loadJson("https://www.geoboundaries.org/api/current/gbOpen/$key/", 64 * 1024)
                require(metadata.getString("boundaryISO") == iso3 && metadata.getString("boundaryType") == level)
                val license = metadata.optString("boundaryLicense").lowercase(Locale.ROOT)
                if (license != "public domain" && license !in setOf("cc by 4.0", "creative commons attribution 4.0 international", "creative commons attribution 4.0 international (cc by 4.0)")) return null
                val download = metadata.getString("simplifiedGeometryGeoJSON")
                require(validDownload(download, iso3, level))
                loadJson(download, 4 * 1024 * 1024).also {
                    if (datasets.size >= 2) datasets.remove(datasets.keys.first())
                    datasets[key] = System.currentTimeMillis() to it
                }
            }
            parse(place, data, requireContainment)
        } catch (_: Exception) {
            ProviderHealth.shared.failed("geoboundaries")
            null
        }
    }
    companion object {
        internal fun validDownload(raw: String, iso3: String, level: String): Boolean = runCatching {
            val url = URI(raw)
            url.scheme == "https" && url.userInfo == null && url.port == -1 && url.rawQuery == null &&
                url.host in setOf("github.com", "raw.githubusercontent.com", "media.githubusercontent.com") &&
                url.path.startsWith("/wmgeolab/geoBoundaries/") && url.path.contains("/gbOpen/$iso3/$level/") &&
                url.path.endsWith(".geojson") && !url.path.contains("..")
        }.getOrDefault(false)
        internal fun parse(place: PlaceDiscovery, data: JSONObject, containment: Boolean): PlaceOverlayData? {
            if (place.kind == PlaceKind.TOWN || data.optString("type") != "FeatureCollection") return null
            val features = data.getJSONArray("features")
            val matches = (0 until features.length()).map { features.getJSONObject(it) }.filter { feature ->
                val name = feature.getJSONObject("properties").optString("shapeName").trim()
                (place.kind == PlaceKind.COUNTRY || name.equals(place.displayName.trim(), ignoreCase = true)) &&
                    feature.optJSONObject("geometry")?.let { geometry ->
                        geometry.optString("type") in setOf("Polygon", "MultiPolygon") &&
                            (!containment || PlaceGeometry.contains(geometry, place.latitude, place.longitude))
                    } == true
            }
            if (matches.size != 1) return null
            val geometry = matches.single().getJSONObject("geometry")
            val area = PlaceOverlayClient.areaSquareKilometers(geometry)
            if (!area.isFinite() || area <= 0) return null
            return PlaceOverlayData(place.key, place.displayName, place.kind, null, area, geometry.toString())
        }
        private fun readJson(raw: String, limit: Int): JSONObject {
            var url = raw
            repeat(4) {
                val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false; connectTimeout = 8000; readTimeout = 15000
                    setRequestProperty("User-Agent", "RoadConquest (+https://github.com/AntnyGamer/RoadConquest-Beta)")
                    setRequestProperty("Accept", "application/geo+json, application/json")
                }
                try {
                    if (connection.responseCode in 300..399) {
                        val next = URI(url).resolve(connection.getHeaderField("Location") ?: throw IOException("Missing redirect"))
                        require(next.scheme == "https" && next.userInfo == null && next.host in
                            setOf("github.com", "raw.githubusercontent.com", "media.githubusercontent.com") && next.path.contains("/wmgeolab/geoBoundaries/"))
                        url = next.toString()
                    } else {
                        if (connection.responseCode !in 200..299) throw IOException("Boundary service unavailable")
                        val bytes = connection.inputStream.use { readBounded(it, limit) }
                        return JSONObject(bytes.toString(Charsets.UTF_8))
                    }
                } finally { connection.disconnect() }
            }
            throw IOException("Too many boundary redirects")
        }
    }
}
