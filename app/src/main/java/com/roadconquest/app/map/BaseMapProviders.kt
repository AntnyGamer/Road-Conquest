package com.roadconquest.app.map

import android.content.Context
import com.roadconquest.app.BuildConfig
import com.roadconquest.app.services.ProviderHealth
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.module.http.HttpRequestUtil
import java.io.IOException
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.TimeUnit

/** OpenFreeMap -> Geoapify raster -> saved progress on a local background. */
object BaseMapProviders {
    private val health = ProviderHealth.shared
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private var installed = false
    @Synchronized fun install() {
        if (installed) return
        installed = true
        HttpRequestUtil.setOkHttpClient(OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(false).followSslRedirects(false)
            .addInterceptor { chain ->
                val request = chain.request()
                val provider = when (request.url.host) {
                    "tiles.openfreemap.org" -> "openfreemap"
                    "services.arcgisonline.com" -> "esri-imagery"
                    else -> if (BuildConfig.MAP_GATEWAY_URL.isNotBlank() &&
                        request.url.host == java.net.URI(BuildConfig.MAP_GATEWAY_URL).host) "geoapify-tiles" else null
                }
                try {
                    val response = chain.proceed(request)
                    if (provider != null && (response.code in listOf(401, 403, 429) || response.code >= 500 || response.code in 300..399)) {
                        health.failed(provider, response.code, response.header("Retry-After"))
                        listeners.forEach { it() }
                    }
                    response
                } catch (error: IOException) {
                    if (provider != null && !chain.call().isCanceled()) {
                        health.failed(provider)
                        listeners.forEach { it() }
                    }
                    throw error
                }
            }.build())
    }
    fun addListener(listener: () -> Unit) { listeners.add(listener) }
    fun removeListener(listener: () -> Unit) { listeners.remove(listener) }
    fun preferred(mode: MapMode): String = when {
        health.remaining("openfreemap") == 0L && mode == MapMode.TERRAIN && health.remaining("esri-imagery") > 0 -> "openfreemap-streets"
        health.remaining("openfreemap") == 0L -> "openfreemap"
        BuildConfig.MAP_GATEWAY_URL.isNotBlank() && health.remaining("geoapify-tiles") == 0L -> "geoapify-tiles"
        else -> "local"
    }
    fun retryInMs(): Long = listOf("openfreemap", "geoapify-tiles", "esri-imagery").map { health.remaining(it) }
        .filter { it > 0 }.minOrNull() ?: 30_000L
    fun style(context: Context, mode: MapMode, provider: String): String {
        if (provider.startsWith("openfreemap")) {
            val root = JSONObject(context.assets.open(
                if (provider == "openfreemap-streets") MapMode.STREETS.styleAsset else mode.styleAsset
            ).bufferedReader().use { it.readText() })
            val source = root.getJSONObject("sources").getJSONObject("openmaptiles")
            val extra = (if (BuildConfig.MAP_GATEWAY_URL.isNotBlank()) " | <a href=\"https://www.geoapify.com/\">Geoapify</a>" else "") +
                " | <a href=\"https://www.geoboundaries.org/\">geoBoundaries</a>"
            source.put("attribution", source.optString("attribution") + extra)
            return root.toString()
        }
        return fallbackStyle(mode, BuildConfig.MAP_GATEWAY_URL.takeIf { provider == "geoapify-tiles" })
    }
    internal fun fallbackStyle(mode: MapMode, gateway: String?): String {
        val sources = JSONObject()
        val layers = JSONArray().put(JSONObject().put("id", "background").put("type", "background")
            .put("paint", JSONObject().put("background-color", if (mode == MapMode.NIGHT) "#15202b" else "#e5e7eb")))
        if (!gateway.isNullOrBlank()) {
            val style = when (mode) { MapMode.NIGHT -> "dark-matter"; MapMode.MINIMAL -> "positron"; else -> "osm-liberty" }
            sources.put("backup-map", JSONObject().put("type", "raster").put("tileSize", 256).put("maxzoom", 19)
                .put("tiles", JSONArray().put(gateway.trimEnd('/') + "/v1/tile/$style/{z}/{x}/{y}.png"))
                .put("attribution", "<a href=\"https://www.geoapify.com/\">Geoapify</a> | <a href=\"https://www.openstreetmap.org/copyright\">OpenStreetMap contributors</a> | <a href=\"https://www.geoboundaries.org/\">geoBoundaries</a>"))
            layers.put(JSONObject().put("id", "backup-map").put("type", "raster").put("source", "backup-map"))
        }
        return JSONObject().put("version", 8).put("sources", sources).put("layers", layers).toString()
    }
}
