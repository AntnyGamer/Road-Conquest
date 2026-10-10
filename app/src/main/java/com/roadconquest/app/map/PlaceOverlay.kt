package com.roadconquest.app.map

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import com.roadconquest.app.BuildConfig
import com.roadconquest.app.data.PlaceDiscovery
import com.roadconquest.app.data.PlaceKind
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.Reader
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

enum class PlaceOverlayMode(val kind: PlaceKind?, val label: String) {
    NONE(null, "None"),
    COUNTRY(PlaceKind.COUNTRY, "Countries"),
    STATE(PlaceKind.STATE, "States and regions"),
    TOWN(PlaceKind.TOWN, "Towns")
}

data class PlaceOverlayInfo(
    val key: String,
    val name: String,
    val kind: PlaceKind,
    val population: Long?,
    val areaSquareKilometers: Double
)

data class PlaceOverlayData(
    val key: String,
    val name: String,
    val kind: PlaceKind,
    val population: Long?,
    val areaSquareKilometers: Double,
    val geometryJson: String
) {
    fun featureJson(): JSONObject = JSONObject()
        .put("type", "Feature")
        .put("properties", JSONObject()
            .put("overlay_key", key)
            .put("overlay_name", name)
            .put("overlay_kind", kind.name)
            .put("area_sq_km", areaSquareKilometers)
            .also { properties -> population?.let { properties.put("population", it) } })
        .put("geometry", JSONObject(geometryJson))
}

data class PlaceOverlayCacheResult(val cached: Boolean, val data: PlaceOverlayData?)

object PlaceOverlayCache {
    // Leave valid state and country caches in place; only municipal
    // outlines need the stricter verification rules.
    private const val DIRECTORY = "place-overlays-v3"
    private const val TOWN_DIRECTORY = "place-overlays-v5-world-admin"
    // All states use one Census cache, independent of the worldwide OSM cache.
    private const val US_OFFICIAL_DIRECTORY = "place-overlays-us-census-v1"
    private const val NEGATIVE_CACHE_MS = 15L * 60L * 1000L
    private const val HEX_DIGITS = "0123456789abcdef"
    private val generation = AtomicLong()
    private val mutationLock = Any()

    fun generation(): Long = generation.get()

    fun read(context: Context, place: PlaceDiscovery): PlaceOverlayCacheResult =
        readCacheFile(file(context, place), place)

    /** Census municipalities for all 50 states are cached separately from OSM. */
    fun readOfficialUs(context: Context, place: PlaceDiscovery): PlaceOverlayCacheResult =
        readCacheFile(officialUsFile(context, place), place)

    fun writeOfficialUs(
        context: Context, place: PlaceDiscovery, data: PlaceOverlayData?,
        expectedGeneration: Long? = null
    ): Boolean = synchronized(mutationLock) {
        if (expectedGeneration != null && expectedGeneration != generation.get()) return@synchronized false
        val target = officialUsFile(context, place)
        val directory = target.parentFile ?: return@synchronized false
        if (!directory.exists() && !directory.mkdirs()) return@synchronized false
        val json = JSONObject()
            .put("found", data != null)
            .put("fetched_at", System.currentTimeMillis())
        if (data != null) {
            json.put("name", data.name).put("kind", data.kind.name)
                .put("population", data.population ?: JSONObject.NULL)
                .put("area_sq_km", data.areaSquareKilometers)
                .put("geometry", JSONObject(data.geometryJson))
        }
        runCatching { target.writeText(json.toString()) }.isSuccess
    }

    private fun officialUsFile(context: Context, place: PlaceDiscovery): File {
        val path = file(context, place)
        return File(File(context.applicationContext.filesDir, US_OFFICIAL_DIRECTORY), path.name)
    }

    private fun readCacheFile(file: File, place: PlaceDiscovery): PlaceOverlayCacheResult {
        if (!file.isFile) return PlaceOverlayCacheResult(false, null)
        return runCatching {
            val json = JSONObject(file.readText())
            if (!json.optBoolean("found", false)) {
                val fetchedAt = json.optLong("fetched_at", 0L)
                if (fetchedAt <= 0L || System.currentTimeMillis() - fetchedAt > NEGATIVE_CACHE_MS) {
                    file.delete()
                    PlaceOverlayCacheResult(false, null)
                } else PlaceOverlayCacheResult(true, null)
            } else PlaceOverlayCacheResult(
                true,
                PlaceOverlayData(
                    key = place.key, name = json.getString("name"),
                    kind = PlaceKind.valueOf(json.getString("kind")),
                    population = if (json.isNull("population")) null else json.getLong("population"),
                    areaSquareKilometers = json.getDouble("area_sq_km"),
                    geometryJson = json.getJSONObject("geometry").toString()
                )
            )
        }.getOrElse {
            file.delete()
            PlaceOverlayCacheResult(false, null)
        }
    }

    /** Only polygons fetched using the strict municipal verification rules can earn town points. */
    fun readVerifiedForDiscovery(context: Context, place: PlaceDiscovery): PlaceOverlayData? {
        if (place.kind != PlaceKind.TOWN) return null
        val cached = read(context, place).data ?: return null
        val verified = runCatching {
            JSONObject(file(context, place).readText()).optBoolean("discovery_verified", false)
        }.getOrDefault(false)
        return cached.takeIf { verified }
    }

    fun write(
        context: Context,
        place: PlaceDiscovery,
        data: PlaceOverlayData?,
        expectedGeneration: Long? = null,
        verifiedForDiscovery: Boolean = false
    ): Boolean = synchronized(mutationLock) {
        if (expectedGeneration != null && generation.get() != expectedGeneration) return@synchronized false
        val directory = file(context, place).parentFile ?: return@synchronized false
        if (!directory.exists() && !directory.mkdirs()) return@synchronized false
        val json = JSONObject()
            .put("found", data != null)
            .put("fetched_at", System.currentTimeMillis())
        if (data != null) {
            if (verifiedForDiscovery && place.kind == PlaceKind.TOWN) {
                json.put("discovery_verified", true)
            }
            json.put("name", data.name)
                .put("kind", data.kind.name)
                .put("population", data.population ?: JSONObject.NULL)
                .put("area_sq_km", data.areaSquareKilometers)
                .put("geometry", JSONObject(data.geometryJson))
        }
        runCatching { file(context, place).writeText(json.toString()) }.isSuccess
    }

    fun clear(context: Context) = synchronized(mutationLock) {
        generation.incrementAndGet()
        File(context.applicationContext.filesDir, DIRECTORY).deleteRecursively()
        File(context.applicationContext.filesDir, TOWN_DIRECTORY).deleteRecursively()
        File(context.applicationContext.filesDir, US_OFFICIAL_DIRECTORY).deleteRecursively()
        Unit
    }

    private fun file(context: Context, place: PlaceDiscovery): File {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("${place.kind.name}|${place.key}".toByteArray(Charsets.UTF_8))
        // Preserve existing lowercase SHA-256 cache filenames without 32 Formatter calls.
        val hex = CharArray(digest.size * 2)
        for (i in digest.indices) {
            val byte = digest[i].toInt() and 0xff
            hex[i * 2] = HEX_DIGITS[byte ushr 4]
            hex[i * 2 + 1] = HEX_DIGITS[byte and 0x0f]
        }
        val directory = if (place.kind == PlaceKind.TOWN) TOWN_DIRECTORY else DIRECTORY
        return File(File(context.applicationContext.filesDir, directory), "${String(hex)}.json")
    }
}

class PlaceOverlayClient(
    private val configUrl: String = BuildConfig.PLACE_OVERLAY_CONFIG_URL
) {
    private var resolvedEndpoint: String? = null

    fun fetch(place: PlaceDiscovery, requireContainment: Boolean = true): PlaceOverlayData? {
        val base = resolveEndpoint()
        throttle()
        val threshold = when (place.kind) {
            PlaceKind.COUNTRY -> "0.02"
            PlaceKind.STATE -> "0.005"
            PlaceKind.TOWN -> "0"
        }
        val url = Uri.parse("$base/search").buildUpon()
            .appendQueryParameter("format", "jsonv2")
            .appendQueryParameter("q", query(place))
            .appendQueryParameter("addressdetails", "1")
            .appendQueryParameter("extratags", "1")
            .appendQueryParameter("polygon_geojson", "1")
            .appendQueryParameter("polygon_threshold", threshold)
            .appendQueryParameter("limit", "8")
            .build()
            .toString()
        val connection = open(url, redirects = false, readTimeoutMs = 12_000)
        return try {
            val status = connection.responseCode
            if (status !in 200..299) throw IOException("Place overlay service returned HTTP $status")
            parseResponse(place, readLimited(connection), requireContainment)
        } finally {
            connection.disconnect()
        }
    }

    private fun resolveEndpoint(): String {
        resolvedEndpoint?.let { return it }
        val url = configUrl.trim()
        if (!url.startsWith("https://")) throw IOException("Place overlay config must use HTTPS")
        val value = try {
            val connection = open(url, redirects = true, readTimeoutMs = 5_000)
            try {
                val status = connection.responseCode
                if (status !in 200..299 || connection.url.protocol != "https") {
                    throw IOException("Place overlay config is unavailable")
                }
                connection.inputStream.bufferedReader().use(::readConfigEndpoint)
            } finally {
                connection.disconnect()
            }
        } catch (_: IOException) {
            // Do not make all overlays disappear just because the remote provider config is
            // temporarily unreachable. The configured file can still override this when online.
            DEFAULT_ENDPOINT
        }
        if (!value.startsWith("https://")) throw IOException("Place overlay service is disabled or invalid")
        resolvedEndpoint = value
        return value
    }

    private fun open(url: String, redirects: Boolean, readTimeoutMs: Int) =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = readTimeoutMs
            instanceFollowRedirects = redirects
            requestMethod = "GET"
            setRequestProperty(
                "User-Agent",
                "RoadConquest/${BuildConfig.VERSION_NAME} (+https://github.com/AntnyGamer/RoadConquest-Beta)"
            )
            setRequestProperty("Accept", "application/json, text/plain;q=0.9")
        }

    private fun throttle() {
        synchronized(rateLock) {
            val now = SystemClock.elapsedRealtime()
            if (lastRequestElapsed != Long.MIN_VALUE) {
                val delay = 1_100L - (now - lastRequestElapsed)
                if (delay > 0L) Thread.sleep(delay)
            }
            lastRequestElapsed = SystemClock.elapsedRealtime()
        }
    }

    private fun readLimited(connection: HttpURLConnection): String {
        val limit = 8 * 1024 * 1024
        val builder = StringBuilder()
        connection.inputStream.bufferedReader().use { reader ->
            val buffer = CharArray(8_192)
            while (true) {
                val count = reader.read(buffer)
                if (count < 0) break
                if (builder.length + count > limit) error("Place boundary response is too large")
                builder.append(buffer, 0, count)
            }
        }
        return builder.toString()
    }

    companion object {
        internal fun readConfigEndpoint(reader: Reader): String {
            val builder = StringBuilder()
            while (true) {
                val next = reader.read()
                if (next < 0 || next == '\n'.code || next == '\r'.code) break
                if (builder.length >= MAX_CONFIG_LENGTH) {
                    throw IOException("Place overlay config is invalid")
                }
                builder.append(next.toChar())
            }
            return builder.toString().trim().trimEnd('/')
        }

        private val rateLock = Any()
        private var lastRequestElapsed = Long.MIN_VALUE
        private const val MAX_CONFIG_LENGTH = 2_048
        private const val DEFAULT_ENDPOINT = "https://nominatim.openstreetmap.org"
        private const val EARTH_RADIUS_M = 6_371_008.8
        // These values are invariant across every Nominatim result. Reuse the compiled patterns
        // and immutable membership tables instead of rebuilding them for each candidate.
        private val WHITESPACE_RE = Regex("\\s+")
        private val POPULATION_RE = Regex("""\d[\d, .]*""")
        private val POLYGON_TYPES = setOf("Polygon", "MultiPolygon")
        private val STATE_ADDRESS_TYPES = setOf("state", "region", "state_district")
        private val LOCALITY_ADDRESS_KEYS = listOf(
            "city", "town", "village", "municipality", "township", "locality"
        )
        private val TOWN_ADDRESS_TYPES = setOf(
            "city", "town", "village", "municipality", "township", "locality", "administrative"
        )

        internal fun parseResponse(place: PlaceDiscovery, body: String, requireContainment: Boolean = true): PlaceOverlayData? {
            val results = runCatching { JSONArray(body) }.getOrNull() ?: return null
            var best: JSONObject? = null
            var bestScore = Int.MIN_VALUE
            for (i in 0 until results.length()) {
                val candidate = results.optJSONObject(i) ?: continue
                val geometry = candidate.optJSONObject("geojson") ?: continue
                if (geometry.optString("type") !in POLYGON_TYPES) continue
                // Ignore polygons for postal districts, neighborhoods, attractions,
                // or place labels. A town reward needs an administrative boundary.
                if (place.kind == PlaceKind.TOWN) {
                    val category = candidate.optString("category", candidate.optString("class"))
                    val type = candidate.optString("type")
                    // Address classification alone is not enough: a county can
                    // contain an identically named town and incorrectly fill it.
                    if (category != "boundary" || type != "administrative") continue
                    val adminLevel = candidate.optJSONObject("extratags")
                        ?.optString("admin_level")?.toIntOrNull()
                    val requestedCountry = canonical(place.countryName)
                    // Administrative levels vary worldwide. Constrain them by
                    // country so district/county/state polygons cannot masquerade
                    // as towns merely because a nearby locality shares a name.
                    val allowedLevels = when (requestedCountry) {
                        "united states", "united states of america", "usa" -> 8..8
                        "australia", "mexico", "new zealand" -> 6..10
                        "japan" -> 7..9
                        "united kingdom", "great britain", "uk" -> 8..11
                        "canada" -> 8..10
                        else -> 7..11
                    }
                    if (adminLevel != null && adminLevel !in allowedLevels) continue
                    // Native OSM boundary relations form the most consistent
                    // worldwide source. Some small places are ways rather than
                    // relations; permit those, but never a labeled POI node.
                    val osmType = candidate.optString("osm_type")
                    if (osmType.isNotBlank() && osmType !in setOf("relation", "way")) continue
                    // A boundary can contain the GPS point but still represent a
                    // different nearby town or county. Require the actual name
                    // and available parent jurisdiction to agree before rewarding.
                    if (!sameMunicipalityName(candidate.optString("name"), place.displayName)) continue
                    val address = candidate.optJSONObject("address") ?: continue
                    val state = canonical(address.optString("state"))
                    val country = canonical(address.optString("country"))
                    if (state.isNotBlank() && canonical(place.parentName).isNotBlank() &&
                        state != canonical(place.parentName)
                    ) continue
                    if (country.isNotBlank() && canonical(place.countryName).isNotBlank() &&
                        country != canonical(place.countryName)
                    ) continue
                }
                // A name or nearby postal address is not evidence of entering a town.
                // Require the GPS point to be inside this exact municipal polygon.
                if (place.kind == PlaceKind.TOWN && requireContainment &&
                    !PlaceGeometry.contains(geometry, place.latitude, place.longitude)
                ) continue
                val score = score(place, candidate)
                if (score > bestScore) {
                    best = candidate
                    bestScore = score
                }
            }
            val minimumScore = when (place.kind) {
                PlaceKind.COUNTRY -> 7
                PlaceKind.STATE -> 6
                PlaceKind.TOWN -> 5
            }
            val selected = best?.takeIf { bestScore >= minimumScore } ?: return null
            val geometry = selected.getJSONObject("geojson")
            val area = areaSquareKilometers(geometry)
            if (!area.isFinite() || area <= 0.0) return null
            val extra = selected.optJSONObject("extratags")
            return PlaceOverlayData(
                key = place.key,
                name = place.displayName,
                kind = place.kind,
                population = parsePopulation(extra?.optString("population")),
                areaSquareKilometers = area,
                geometryJson = geometry.toString()
            )
        }

        private fun query(place: PlaceDiscovery): String = when (place.kind) {
            PlaceKind.COUNTRY -> listOf(place.displayName)
            PlaceKind.STATE -> listOf(place.displayName, place.countryName)
            PlaceKind.TOWN -> listOf(place.displayName, place.parentName, place.countryName)
        }.filter { it.isNotBlank() }.joinToString(", ")

        private fun score(place: PlaceDiscovery, candidate: JSONObject): Int {
            val address = candidate.optJSONObject("address") ?: JSONObject()
            val expectedName = canonical(place.displayName)
            val expectedParent = canonical(place.parentName)
            val expectedCountry = canonical(place.countryName.ifBlank {
                if (place.kind == PlaceKind.COUNTRY) place.displayName else ""
            })
            val resultName = canonical(candidate.optString("name"))
            val addressType = canonical(candidate.optString("addresstype"))
            var score = if (resultName == expectedName) 3 else 0
            when (place.kind) {
                PlaceKind.COUNTRY -> {
                    if (canonical(address.optString("country")) == expectedName) score += 7
                    if (addressType == "country") score += 4
                }
                PlaceKind.STATE -> {
                    if (canonical(address.optString("state")) == expectedName) score += 7
                    if (expectedCountry.isNotBlank() &&
                        canonical(address.optString("country")) == expectedCountry) score += 2
                    if (addressType in STATE_ADDRESS_TYPES) score += 3
                }
                PlaceKind.TOWN -> {
                    val locality = LOCALITY_ADDRESS_KEYS.any {
                        canonical(address.optString(it)) == expectedName
                    }
                    if (locality) score += 7
                    if (expectedParent.isNotBlank() &&
                        canonical(address.optString("state")) == expectedParent) score += 2
                    if (expectedCountry.isNotBlank() &&
                        canonical(address.optString("country")) == expectedCountry) score += 1
                    if (addressType in TOWN_ADDRESS_TYPES) score += 3

                    // Names such as "Washington Township" are not unique even inside one
                    // state. Prefer the boundary whose Nominatim centroid is near the exact
                    // place where this town was discovered, rather than whichever duplicate
                    // happens to be returned first.
                    val candidateLatitude = candidate.optString("lat").toDoubleOrNull()
                    val candidateLongitude = candidate.optString("lon").toDoubleOrNull()
                    if (candidateLatitude != null && candidateLongitude != null &&
                        candidateLatitude in -90.0..90.0 && candidateLongitude in -180.0..180.0
                    ) {
                        val meters = distanceMeters(
                            place.latitude, place.longitude,
                            candidateLatitude, candidateLongitude
                        )
                        score += when {
                            meters <= 10_000.0 -> 8
                            meters <= 30_000.0 -> 5
                            meters <= 75_000.0 -> 2
                            else -> -8
                        }
                    }
                }
            }
            return score
        }

        private fun distanceMeters(
            latitude1: Double,
            longitude1: Double,
            latitude2: Double,
            longitude2: Double
        ): Double {
            val lat1 = Math.toRadians(latitude1)
            val lat2 = Math.toRadians(latitude2)
            val deltaLat = lat2 - lat1
            var deltaLon = Math.toRadians(longitude2 - longitude1)
            while (deltaLon > PI) deltaLon -= 2 * PI
            while (deltaLon < -PI) deltaLon += 2 * PI
            val a = kotlin.math.sin(deltaLat / 2).let { it * it } +
                kotlin.math.cos(lat1) * kotlin.math.cos(lat2) *
                kotlin.math.sin(deltaLon / 2).let { it * it }
            return EARTH_RADIUS_M * 2 * kotlin.math.asin(kotlin.math.sqrt(a.coerceIn(0.0, 1.0)))
        }

        private fun parsePopulation(value: String?): Long? {
            val match = value?.let { POPULATION_RE.find(it)?.value } ?: return null
            return match.filter(Char::isDigit).toLongOrNull()?.takeIf { it > 0L }
        }

        internal fun areaSquareKilometers(geometry: JSONObject): Double {
            val coordinates = geometry.optJSONArray("coordinates") ?: return 0.0
            val squareMeters = when (geometry.optString("type")) {
                "Polygon" -> polygonArea(coordinates)
                "MultiPolygon" -> {
                    var total = 0.0
                    for (i in 0 until coordinates.length()) {
                        total += polygonArea(coordinates.optJSONArray(i) ?: continue)
                    }
                    total
                }
                else -> 0.0
            }
            return squareMeters / 1_000_000.0
        }

        private fun polygonArea(rings: JSONArray): Double {
            if (rings.length() == 0) return 0.0
            var area = abs(ringArea(rings.optJSONArray(0) ?: return 0.0))
            for (i in 1 until rings.length()) area -= abs(ringArea(rings.optJSONArray(i) ?: continue))
            return area.coerceAtLeast(0.0)
        }

        private fun ringArea(ring: JSONArray): Double {
            if (ring.length() < 3) return 0.0
            var sum = 0.0
            for (i in 0 until ring.length()) {
                val a = ring.optJSONArray(i) ?: continue
                val b = ring.optJSONArray((i + 1) % ring.length()) ?: continue
                val lon1 = Math.toRadians(a.optDouble(0, Double.NaN))
                val lat1 = Math.toRadians(a.optDouble(1, Double.NaN))
                val lon2 = Math.toRadians(b.optDouble(0, Double.NaN))
                val lat2 = Math.toRadians(b.optDouble(1, Double.NaN))
                if (!lon1.isFinite() || !lat1.isFinite() || !lon2.isFinite() || !lat2.isFinite()) continue
                var delta = lon2 - lon1
                while (delta > PI) delta -= 2 * PI
                while (delta < -PI) delta += 2 * PI
                sum += delta * (2 + sin(lat1) + sin(lat2))
            }
            return sum * EARTH_RADIUS_M * EARTH_RADIUS_M / 2.0
        }

        private fun sameMunicipalityName(first: String, second: String): Boolean {
            val a = municipalityIdentity(first)
            val b = municipalityIdentity(second)
            // The legal municipal type is part of the identity, not decoration:
            // Gloucester City must NEVER match Gloucester Township. Bare names
            // can still match "Borough of Pine Hill" when the type is omitted.
            return a.first.isNotBlank() && a.first == b.first &&
                (a.second == null || b.second == null || a.second == b.second)
        }

        private fun municipalityIdentity(value: String): Pair<String, String?> {
            var name = canonical(value).removePrefix("the ")
            var legalType: String? = null
            // Some providers redundantly say "City of Gloucester City".
            // Strip equivalent prefix/suffix types, but never equate a CITY to
            // a TOWNSHIP or BOROUGH of the same base name.
            repeat(3) {
                for (kind in listOf("township", "borough", "municipality", "city", "town", "village")) {
                    val prefix = "${kind} of "
                    val suffix = " $kind"
                    if (name.startsWith(prefix) || name.endsWith(suffix)) {
                        if (legalType != null && legalType != kind) return canonical(value) to null
                        name = if (name.startsWith(prefix)) name.removePrefix(prefix) else name.removeSuffix(suffix)
                        legalType = kind
                    }
                }
            }
            return name to legalType
        }

        private fun canonical(value: String): String =
            value.trim().lowercase(Locale.ROOT).replace(WHITESPACE_RE, " ")
    }
}

/** Geographic containment for GeoJSON Polygon and MultiPolygon boundaries.
 * Coordinates are [longitude, latitude]. Interior holes must remain unexplored.
 */
internal object PlaceGeometry {
    fun contains(geometry: JSONObject, latitude: Double, longitude: Double): Boolean {
        if (!latitude.isFinite() || !longitude.isFinite() ||
            latitude !in -90.0..90.0 || longitude !in -180.0..180.0
        ) return false
        val coordinates = geometry.optJSONArray("coordinates") ?: return false
        return when (geometry.optString("type")) {
            "Polygon" -> polygonContains(coordinates, latitude, longitude)
            "MultiPolygon" -> (0 until coordinates.length()).any { i ->
                coordinates.optJSONArray(i)?.let { polygonContains(it, latitude, longitude) } == true
            }
            else -> false
        }
    }

    private fun polygonContains(rings: JSONArray, latitude: Double, longitude: Double): Boolean {
        val outer = rings.optJSONArray(0) ?: return false
        if (!ringContains(outer, latitude, longitude)) return false
        for (i in 1 until rings.length()) {
            val hole = rings.optJSONArray(i) ?: continue
            if (ringContains(hole, latitude, longitude)) return false
        }
        return true
    }

    private fun ringContains(ring: JSONArray, latitude: Double, longitude: Double): Boolean {
        if (ring.length() < 3) return false
        var inside = false
        var previous = ring.optJSONArray(ring.length() - 1) ?: return false
        for (i in 0 until ring.length()) {
            val current = ring.optJSONArray(i) ?: return false
            val y1 = previous.optDouble(1, Double.NaN)
            val y2 = current.optDouble(1, Double.NaN)
            val x1 = relativeLongitude(previous.optDouble(0, Double.NaN), longitude)
            val x2 = relativeLongitude(current.optDouble(0, Double.NaN), longitude)
            if (!x1.isFinite() || !x2.isFinite() || !y1.isFinite() || !y2.isFinite()) return false
            // Exact boundary points count as contained to avoid disappearing towns
            // when a recorded fix lands precisely on a shared boundary.
            val cross = x1 * (y2 - latitude) - x2 * (y1 - latitude)
            if (kotlin.math.abs(cross) < 1e-10 &&
                0.0 >= minOf(x1, x2) - 1e-10 &&
                0.0 <= maxOf(x1, x2) + 1e-10 &&
                latitude >= minOf(y1, y2) - 1e-10 &&
                latitude <= maxOf(y1, y2) + 1e-10
            ) return true
            if ((y1 > latitude) != (y2 > latitude)) {
                val crossingX = x1 + (x2 - x1) * ((latitude - y1) / (y2 - y1))
                if (crossingX > 0.0) inside = !inside
            }
            previous = current
        }
        return inside
    }

    private fun relativeLongitude(lon: Double, anchor: Double): Double {
        if (!lon.isFinite()) return Double.NaN
        return ((lon - anchor + 540.0) % 360.0) - 180.0
    }
}
