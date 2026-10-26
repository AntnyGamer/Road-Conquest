package com.roadconquest.app.services

import android.content.Context
import com.roadconquest.app.BuildConfig
import org.json.JSONObject
import java.net.URI
import javax.net.ssl.HttpsURLConnection
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors

/** Public, bounded provider switches; never stores credentials, location or scoring rules. */
object ServiceConfig {
    private val names = setOf("openfreemap", "esri-imagery", "geoapify-tiles", "geoapify-matching", "locationiq-matching")
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val executor = Executors.newSingleThreadExecutor()
    @Volatile private var values: Map<String, Boolean> = emptyMap()
    @Volatile private var refreshing = false
    private var nextAttempt = 0L
    fun enabled(provider: String) = values[provider] ?: true
    fun addListener(listener: () -> Unit) { listeners.add(listener) }
    internal fun parse(raw: String): Map<String, Boolean> {
        require(raw.length <= 4096)
        val root = JSONObject(raw)
        // optInt would silently accept a quoted "1" or truncate 1.5 to 1.
        // The gateway requires the actual numeric schema version.
        val version = root.opt("version") as? Number
        require(version?.toDouble() == 1.0)
        val providers = root.getJSONObject("providers")
        return names.associateWith { providers.opt(it) as? Boolean ?: true }
    }
    internal fun cachedValues(raw: String?, ageMs: Long): Map<String, Boolean> =
        if (ageMs in 0..86_400_000L) runCatching { parse(raw.orEmpty()) }.getOrDefault(emptyMap())
        else emptyMap()

    @Synchronized fun refresh(context: Context) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences("roadconquest_service_config", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val fetched = prefs.getLong("fetched", 0)
        val age = now - fetched
        val cached = cachedValues(prefs.getString("json", null), age)
        // Expired or corrupt remote controls revert to safe defaults. Notify
        // existing map renderers as well, or they may remain on a fallback
        // despite the preferred provider becoming available again.
        if (cached != values) {
            values = cached
            listeners.forEach { it() }
        }
        if (refreshing || now < nextAttempt || age in 0..21_600_000 || BuildConfig.MAP_GATEWAY_URL.isBlank()) return
        nextAttempt = now + 900_000
        refreshing = true
        executor.execute {
            try {
                val uri = URI(BuildConfig.MAP_GATEWAY_URL.trimEnd('/') + "/v1/config")
                require(uri.scheme == "https" && uri.userInfo == null)
                val connection = uri.toURL().openConnection() as HttpsURLConnection
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                try {
                    check(connection.responseCode == 200)
                    val raw = connection.inputStream.use { input ->
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(1024)
                        while (output.size() <= 4096) {
                            val count = input.read(buffer, 0, minOf(buffer.size, 4097 - output.size()))
                            if (count < 0) break
                            output.write(buffer, 0, count)
                        }
                        check(output.size() <= 4096)
                        output.toString(Charsets.UTF_8.name())
                    }
                    val updated = parse(raw)
                    prefs.edit().putString("json", raw).putLong("fetched", System.currentTimeMillis()).apply()
                    val changed = updated != values
                    values = updated
                    if (changed) listeners.forEach { it() }
                } finally { connection.disconnect() }
            } catch (_: Exception) { /* Offline defaults and saved map progress remain usable. */ }
            finally { refreshing = false }
        }
    }
}
