package com.roadconquest.app.map

import android.content.Context
import android.net.Uri
import com.roadconquest.app.data.PlaceKind
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Population is separate from the latest Census boundary layer: TIGERweb's
 * contemporary municipality geometry deliberately has no population attribute.
 * The 2020 Census snapshot is queried only when the user opens a place card.
 * Its geometry is NEVER used to determine visited boundaries or score places.
 */
internal object PlacePopulationLookup {
    data class Value(val count: Long, val source: String)
    internal data class Request(val url: String, val state: String? = null, val geoid: String? = null,
                                val countryCode: String? = null)

    private val officialGeoid = Regex("""[|]census-(1|3|4)-([0-9]+)$""")
    private val cached = ConcurrentHashMap<String, Value>()
    private val cacheLock = Any()
    // Register waiting callers before entering a per-place monitor. Removing
    // the monitor when the first caller returns would let a third caller create
    // another lock while the second caller still waits or runs.
    private class LookupLock {
        val monitor = Any()
        var users = 0
    }
    private val lookupRegistryLock = Any()
    private val activeLookups = HashMap<String, LookupLock>()

    internal fun <T> withLookupLock(identity: String, task: () -> T): T {
        val guard = synchronized(lookupRegistryLock) {
            activeLookups.getOrPut(identity) { LookupLock() }.also { it.users++ }
        }
        try {
            return synchronized(guard.monitor) { task() }
        } finally {
            synchronized(lookupRegistryLock) {
                if (--guard.users == 0) activeLookups.remove(identity)
            }
        }
    }
    @Volatile private var cacheGeneration = 0L
    internal fun generation(): Long = cacheGeneration
    private const val CENSUS_CACHE = "roadconquest_census2020_population"
    private fun key(info: PlaceOverlayInfo) = info.kind.name + "|" + info.key

    // Census 2020 counts are fixed historical figures. Never persist dynamic
    // country counts or failed/ambiguous responses.
    internal fun savedCensus(context: Context, info: PlaceOverlayInfo): Value? {
        if (!isUnitedStates(info) || requestFor(info) == null) return null
        val value = context.applicationContext.getSharedPreferences(CENSUS_CACHE, Context.MODE_PRIVATE)
            .getLong(key(info), -1L)
        return value.takeIf { it >= 0L }?.let { Value(it, "2020 Census") }
    }

    internal fun rememberCensus(
        context: Context, info: PlaceOverlayInfo, value: Value,
        startedAtGeneration: Long = generation()
    ) = synchronized(cacheLock) {
        // A request started before Delete all data must not restore old entries.
        if (startedAtGeneration != cacheGeneration || value.source != "2020 Census" ||
            !isUnitedStates(info) || requestFor(info) == null) return@synchronized
        context.applicationContext.getSharedPreferences(CENSUS_CACHE, Context.MODE_PRIVATE)
            .edit().putLong(key(info), value.count).apply()
    }

    fun clearCache(context: Context) = synchronized(cacheLock) {
        cacheGeneration++
        cached.clear()
        check(context.applicationContext.getSharedPreferences(CENSUS_CACHE, Context.MODE_PRIVATE)
            .edit().clear().commit()) { "Could not clear cached Census population data" }
    }
    private const val CENSUS_BASE = "https://tigerweb.geo.census.gov/arcgis/rest/services/Census2020"
    private const val MAX_RESPONSE_CHARS = 8_192
    // The 2020 decennial Census US total, including the 50 states and D.C.
    private const val US_2020_POPULATION = 331_449_281L

    internal fun canLookup(info: PlaceOverlayInfo): Boolean =
        (isUnitedStates(info) && info.kind == PlaceKind.COUNTRY) || requestFor(info) != null

    private fun isUnitedStates(info: PlaceOverlayInfo): Boolean =
        info.key == "us" || info.key.startsWith("us|")

    internal fun requestFor(info: PlaceOverlayInfo): Request? {
        if (isUnitedStates(info) && info.kind == PlaceKind.STATE) {
            val state = UnitedStatesMunicipalBoundaries.stateCode("United States", info.name) ?: return null
            return censusRequest("State_County", 0, state, state)
        }
        if (isUnitedStates(info) && info.kind == PlaceKind.TOWN) {
            val match = officialGeoid.find(info.key) ?: return null
            val layer = match.groupValues[1].toInt()
            val geoid = match.groupValues[2]
            val state = geoid.take(2)
            val parent = info.key.split('|').getOrNull(1) ?: return null
            if ((layer == 1 && geoid.length != 10) ||
                (layer != 1 && geoid.length != 7) ||
                UnitedStatesMunicipalBoundaries.stateCode("United States", parent) != state
            ) return null
            return censusRequest("Places_CouSub_ConCity_SubMCD", layer, state, geoid)
        }
        if (info.kind == PlaceKind.COUNTRY && !isUnitedStates(info)) {
            // The discovery key is the geocoder's country code. Never do a fuzzy
            // name search that could attach another country's population.
            val code = info.key.uppercase(Locale.ROOT).takeIf {
                it.length in 2..3 && it.all(Char::isLetter)
            } ?: return null
            return Request(
                "https://restcountries.com/v3.1/alpha/" + code +
                    "?fields=population,cca2,cca3",
                countryCode = code
            )
        }
        // No authoritative, stable municipality ID for this place.
        return null
    }

    private fun censusRequest(service: String, layer: Int, state: String, geoid: String): Request {
        val where = "STATE = '" + state + "' AND GEOID = '" + geoid + "'"
        val url = Uri.parse(CENSUS_BASE + "/" + service + "/MapServer/" + layer + "/query")
            .buildUpon()
            .appendQueryParameter("where", where)
            .appendQueryParameter("outFields", "STATE,GEOID,POP100")
            .appendQueryParameter("returnGeometry", "false")
            .appendQueryParameter("f", "json")
            .build().toString()
        return Request(url, state, geoid)
    }

    fun lookup(context: Context, info: PlaceOverlayInfo): Value? {
        if (info.population != null) return Value(info.population, "Boundary provider")
        // Re-check memory and persistent cache after waiting for an identical
        // in-flight lookup; the first success is shared without another request.
        val startedAtGeneration = generation()
        return withLookupLock("$startedAtGeneration:${key(info)}") {
            lookupCurrent(context, info, startedAtGeneration)
        }
    }

    private fun lookupCurrent(
        context: Context, info: PlaceOverlayInfo, startedAtGeneration: Long
    ): Value? {
        // A queued request from before Delete all data must not issue a new
        // provider request after the saved-data generation has changed.
        if (startedAtGeneration != cacheGeneration) return null
        cached[key(info)]?.let { return it }
        savedCensus(context, info)?.let { saved ->
            // A reset can occur between reading disk and populating the memory
            // cache. Coordinate with clearCache so old data cannot reappear.
            return synchronized(cacheLock) {
                if (startedAtGeneration != cacheGeneration) null
                else {
                    cached.putIfAbsent(key(info), saved)
                    saved
                }
            }
        }
        val value = if (isUnitedStates(info) && info.kind == PlaceKind.COUNTRY) {
            Value(US_2020_POPULATION, "2020 Census")
        } else {
            val request = requestFor(info) ?: return null
            val connection = (URL(request.url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 6_000
                readTimeout = 8_000
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "RoadConquest/1.0 (Android exploration app)")
            }
            try {
                if (connection.responseCode !in 200..299) {
                    throw IOException("Population service HTTP " + connection.responseCode)
                }
                val body = connection.inputStream.bufferedReader().use { reader ->
                    val data = StringBuilder()
                    val buffer = CharArray(2048)
                    while (true) {
                        val n = reader.read(buffer)
                        if (n < 0) break
                        if (data.length + n > MAX_RESPONSE_CHARS) throw IOException("Oversized population response")
                        data.append(buffer, 0, n)
                    }
                    data.toString()
                }
                parseResponse(request, body)
            } finally {
                connection.disconnect()
            }
        }
        if (value != null) synchronized(cacheLock) {
            if (startedAtGeneration == cacheGeneration) {
                cached.putIfAbsent(key(info), value)
                rememberCensus(context, info, value, startedAtGeneration)
            }
        }
        return value
    }

    internal fun parseResponse(request: Request, body: String): Value? {
        if (request.countryCode != null) {
            val doc = if (body.trimStart().startsWith("[")) {
                val entries = JSONArray(body)
                if (entries.length() != 1) return null
                entries.optJSONObject(0) ?: return null
            } else JSONObject(body)
            val code = request.countryCode
            if (code != doc.optString("cca2") && code != doc.optString("cca3")) return null
            val count = doc.optLong("population", -1)
            return count.takeIf { it >= 0 }?.let { Value(it, "REST Countries") }
        }

        val root = JSONObject(body)
        if (root.has("error")) throw IOException("Census population service error")
        val features = root.optJSONArray("features") ?: throw IOException("Malformed Census population response")
        if (features.length() != 1) return null
        val attributes = features.optJSONObject(0)?.optJSONObject("attributes") ?: return null
        if (attributes.optString("STATE") != request.state ||
            attributes.optString("GEOID") != request.geoid
        ) return null
        val count = attributes.optDouble("POP100", Double.NaN)
        return count.takeIf { it.isFinite() && it >= 0 && it <= Long.MAX_VALUE.toDouble() &&
            it == kotlin.math.floor(it) }?.let { Value(it.toLong(), "2020 Census") }
    }
}
