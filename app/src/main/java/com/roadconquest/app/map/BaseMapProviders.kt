package com.roadconquest.app.map

import android.content.Context
import com.roadconquest.app.BuildConfig
import com.roadconquest.app.services.ProviderHealth
import com.roadconquest.app.services.ServiceConfig
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
    // BuildConfig endpoint is immutable for an installed build. Avoid reparsing
    // its URI for every native map tile request.
    internal fun configuredGatewayHost(url: String): String? = url.takeIf { it.isNotBlank() }
        ?.let { runCatching {
            val uri = java.net.URI(it)
            uri.host?.takeIf { _ ->
                uri.scheme == "https" || uri.scheme == "http"
            }?.takeIf { uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null }
        }.getOrNull() }
    private val gatewayHost = configuredGatewayHost(BuildConfig.MAP_GATEWAY_URL)
    @Synchronized fun install() {
        if (installed) return
        installed = true
        ServiceConfig.addListener { listeners.forEach { it() } }
        HttpRequestUtil.setOkHttpClient(OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(false).followSslRedirects(false)
            .addInterceptor { chain ->
                val request = chain.request()
                val provider = when (request.url.host) {
                    "tiles.openfreemap.org" -> "openfreemap"
                    "services.arcgisonline.com" -> "esri-imagery"
                    else -> if (gatewayHost != null && request.url.host == gatewayHost) "geoapify-tiles" else null
                }
                // An intentionally disabled service did not fail a network
                // request: do not mark it unhealthy or prolong recovery.
                if (provider != null && !ServiceConfig.enabled(provider)) {
                    throw IOException("Map provider temporarily disabled")
                }
                try {
                    val response = chain.proceed(request)
                    val status = response.code
                    if (provider != null && (status == 401 || status == 403 || status == 429 ||
                            status >= 500 || status in 300..399)) {
                        health.failed(provider, status, response.header("Retry-After"))
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
    private fun available(provider: String) = ServiceConfig.enabled(provider) && health.remaining(provider) == 0L
    fun preferred(mode: MapMode): String = when {
        available("openfreemap") && mode == MapMode.TERRAIN && !available("esri-imagery") -> "openfreemap-streets"
        available("openfreemap") -> "openfreemap"
        gatewayHost != null && available("geoapify-tiles") -> "geoapify-tiles"
        else -> "local"
    }
    // No cooldown means there is nothing to retry. ServiceConfig listeners
    // independently handle remote provider enable/disable changes, so avoid
    // waking the map every 30 seconds while an intentionally disabled provider
    // remains unavailable.
    fun retryInMs(): Long? = listOf("openfreemap", "geoapify-tiles", "esri-imagery")
        .filter(ServiceConfig::enabled)
        .map { health.remaining(it) }.filter { it > 0 }.minOrNull()
    fun style(context: Context, mode: MapMode, provider: String): String {
        if (provider.startsWith("openfreemap")) {
            val root = JSONObject(context.assets.open(
                if (provider == "openfreemap-streets") MapMode.STREETS.styleAsset else mode.styleAsset
            ).bufferedReader().use { it.readText() })
            val source = root.getJSONObject("sources").getJSONObject("openmaptiles")
            val extra = (if (gatewayHost != null) " | <a href=\"https://www.geoapify.com/\">Geoapify</a>" else "") +
                " | <a href=\"https://www.geoboundaries.org/\">geoBoundaries</a>"
            source.put("attribution", source.optString("attribution") + extra)
            return root.toString()
        }
        return fallbackStyle(mode, BuildConfig.MAP_GATEWAY_URL.takeIf { provider == "geoapify-tiles" && gatewayHost != null })
    }
    internal fun fallbackStyle(mode: MapMode, gateway: String?): String {
        val sources = JSONObject()
        val layers = JSONArray().put(JSONObject().put("id", "background").put("type", "background")
            .put("paint", JSONObject().put("background-color", if (mode == MapMode.NIGHT) "#15202b" else "#e5e7eb")))
        if (!gateway.isNullOrBlank()) {
            val style = when (mode) { MapMode.NIGHT -> "dark-matter"; MapMode.MINIMAL -> "positron"; else -> "osm-liberty" }
            sources.put("backup-map", JSONObject().put("type", "raster").put("tileSize", 256).put("maxzoom", 19)
                .put("tiles", JSONArray().put(gateway.trimEnd('/') + "/v1/tile/$style/{z}/{x}/{y}.png"))
                .put("attribution", "<a href=\"https://www.geoapify.com/\">Powered by Geoapify</a> | <a href=\"https://www.openstreetmap.org/copyright\">© OpenStreetMap contributors</a> | <a href=\"https://openmaptiles.org/\">© OpenMapTiles</a> | <a href=\"https://www.geoboundaries.org/\">geoBoundaries</a>"))
            layers.put(JSONObject().put("id", "backup-map").put("type", "raster").put("source", "backup-map"))
        }
        return JSONObject().put("version", 8).put("sources", sources).put("layers", layers).toString()
    }
}
