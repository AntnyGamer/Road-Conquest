package com.roadconquest.app.map

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

    fun lookup(info: PlaceOverlayInfo): Value? {
        if (info.population != null) return Value(info.population, "Boundary provider")
        cached[info.kind.name + "|" + info.key]?.let { return it }
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
        if (value != null) cached.putIfAbsent(info.kind.name + "|" + info.key, value)
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
