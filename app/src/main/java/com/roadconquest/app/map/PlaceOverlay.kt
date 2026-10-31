package com.roadconquest.app.map

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.AtomicFile
import com.roadconquest.app.BuildConfig
import com.roadconquest.app.data.PlaceDiscovery
import com.roadconquest.app.data.PlaceKind
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.Reader
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
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
    val areaSquareKilometers: Double,
    val completionPercent: Int? = null,
    val completionMedal: String? = null
)

data class PlaceOverlayData(
    val key: String,
    val name: String,
    val kind: PlaceKind,
    val population: Long?,
    val areaSquareKilometers: Double,
    val geometryJson: String,
    val completion: TownCompletionProgress? = null
) {
    fun featureJson(): JSONObject = featureJsonFromGeometry(JSONObject(geometryJson))

    /** Reuse a fresh 2D polygon while preserving the old parser's signed-zero normalization. */
    internal fun featureJson(geometry: JSONObject): JSONObject = featureJsonFromGeometry(
        if ("[-0," in geometryJson || ",-0]" in geometryJson) JSONObject(geometryJson) else geometry
    )

    private fun featureJsonFromGeometry(geometry: JSONObject): JSONObject = JSONObject()
        .put("type", "Feature")
        .put("properties", JSONObject()
            .put("overlay_key", key)
            .put("overlay_name", name)
            .put("overlay_kind", kind.name)
            .put("area_sq_km", areaSquareKilometers)
            .also { properties ->
                population?.let { properties.put("population", it) }
                completion?.let {
                    properties.put("completion_percent", it.percent)
                    properties.put("completion_medal", it.medal.label)
                }
            })
        .put("geometry", geometry)
}

/** Fill and raw outlines share a local parsed geometry; neither mutates its coordinate arrays. */
internal fun overlayFeatureCollections(data: List<PlaceOverlayData>): Pair<String, String> {
    val fills = JSONArray()
    val features = JSONArray()
    fun addRings(rings: JSONArray) {
        for (ringIndex in 0 until rings.length()) {
            val ring = rings.optJSONArray(ringIndex) ?: continue
            if (ring.length() < 2) continue
            features.put(
                JSONObject()
                    .put("type", "Feature")
                    .put("properties", JSONObject())
                    .put("geometry", JSONObject()
                        .put("type", "LineString")
                        .put("coordinates", ring))
            )
        }
    }
    for (overlay in data) {
        val geometry = JSONObject(overlay.geometryJson)
        fills.put(overlay.featureJson(geometry))
        val coordinates = geometry.optJSONArray("coordinates") ?: continue
        when (geometry.optString("type")) {
            "Polygon" -> addRings(coordinates)
            "MultiPolygon" -> {
                for (polygonIndex in 0 until coordinates.length()) {
                    coordinates.optJSONArray(polygonIndex)?.let(::addRings)
                }
            }
        }
    }
    return JSONObject().put("type", "FeatureCollection").put("features", fills).toString() to
        JSONObject().put("type", "FeatureCollection").put("features", features).toString()
}

/** A saved town name alone is not proof that its recorded GPS fix was inside its polygon.
 * Legacy entries can precede the current strict boundary verifier; never shade an
 * entire neighboring town merely because its name has a visited-place record.
 */
internal fun PlaceOverlayData.containsRecordedVisit(place: PlaceDiscovery): Boolean =
    key == place.key && kind == place.kind &&
        (kind != PlaceKind.TOWN ||
            (place.latitude.isFinite() && place.longitude.isFinite() &&
                runCatching {
                    PlaceGeometry.contains(JSONObject(geometryJson), place.latitude, place.longitude)
                }.getOrDefault(false)))

data class PlaceOverlayCacheResult(val cached: Boolean, val data: PlaceOverlayData?)

object PlaceOverlayCache {
    // Leave valid state and country caches in place; only municipal
    // outlines need the stricter verification rules.
    private const val DIRECTORY = "place-overlays-v3"
    private const val TOWN_DIRECTORY = "place-overlays-v5-world-admin"
    private const val STATE_DIRECTORY = "place-overlays-v4-states-full"
    // All states use one Census cache, independent of the worldwide OSM cache.
    private const val US_OFFICIAL_DIRECTORY = "place-overlays-us-census-v1"
    // These formats were retired in Betas 9 and 37/38 and are never read now.
    private val retiredDirectories = listOf("place-overlays-v2", "place-overlays-v4-towns")
    private const val NEGATIVE_CACHE_MS = 15L * 60L * 1000L
    private const val HEX_DIGITS = "0123456789abcdef"
    private val generation = AtomicLong()
    private val contentRevision = AtomicLong()
    internal fun contentRevision(): Long = contentRevision.get()
    private val mutationLock = Any()
    private const val CACHE_BUDGET_BYTES = 128L * 1024L * 1024L
    private const val CACHE_BUDGET_FILES = 6_000
    private const val MAX_DECODED_CACHE_CHARS = 16 * 1024 * 1024
    private var initialTrimDone = false
    private var cacheBytes = 0L
    private var cacheFiles = 0

    // Boundary caches are regenerable; visited places, achievements and earned
    // rewards remain in SQLite. Keep recent polygons available offline while
    // preventing an unlimited accumulation of remote administrative GeoJSON.
    private fun trimCache(context: Context, keep: File? = null,
                          maxBytes: Long = CACHE_BUDGET_BYTES,
                          maxFiles: Int = CACHE_BUDGET_FILES) {
        val files = listOf(DIRECTORY, TOWN_DIRECTORY, STATE_DIRECTORY, US_OFFICIAL_DIRECTORY)
            .flatMap { name ->
                File(context.applicationContext.filesDir, name).listFiles().orEmpty()
                    .filter { entry ->
                        // AtomicFile's .new is an uncommitted write, never a valid cache.
                        // Under mutationLock no writer can still be using it.
                        if (entry.isFile && entry.name.endsWith(".json.new")) {
                            entry.delete()
                            false
                        } else entry.isFile && entry.name.endsWith(".json")
                    }
            }
        var bytes = files.sumOf { it.length() }
        var count = files.size
        if (bytes <= maxBytes && count <= maxFiles) {
            cacheBytes = bytes
            cacheFiles = count
            return
        }
        // The oldest cached outlines go first. The destination currently being
        // written stays readable, even if it alone exceeds the cache budget.
        for (candidate in oldestCacheFiles(files)) {
            if (bytes <= maxBytes && count <= maxFiles) break
            if (candidate == keep) continue
            val length = candidate.length()
            if (candidate.delete()) {
                bytes -= length
                count--
            }
        }
        cacheBytes = bytes
        cacheFiles = count
    }

    /** Snapshot timestamps under mutationLock instead of re-statting files during every comparison. */
    internal fun oldestCacheFiles(files: List<File>): List<File> {
        if (files.size < 2) return files
        data class Candidate(val file: File, val modified: Long)
        return files.map { Candidate(it, it.lastModified()) }
            .sortedWith(compareBy<Candidate> { it.modified }.thenBy { it.file.name })
            .map { it.file }
    }

    internal fun trimForTest(context: Context, maxBytes: Long, maxFiles: Int) =
        synchronized(mutationLock) { trimCache(context, maxBytes = maxBytes, maxFiles = maxFiles) }

    private fun initialTrim(context: Context) {
        if (!initialTrimDone) {
            clearRetiredCaches(context)
            trimCache(context)
            initialTrimDone = true
        }
    }

    private fun clearRetiredCaches(context: Context) {
        for (name in retiredDirectories) File(context.applicationContext.filesDir, name).deleteRecursively()
    }

    private fun writeCacheFile(context: Context, target: File, json: JSONObject): Boolean {
        initialTrim(context)
        val existed = target.isFile
        val oldBytes = if (existed) target.length() else 0L
        val file = AtomicFile(target)
        var stream: java.io.FileOutputStream? = null
        return try {
            stream = file.startWrite()
            // Stream gzip into the existing crash-safe AtomicFile rather than
            // allocating separate full JSON, compressed and copied byte arrays.
            // finish() writes the gzip trailer without closing AtomicFile's stream.
            val gzip = GZIPOutputStream(stream)
            val writer = gzip.bufferedWriter(Charsets.UTF_8)
            writer.write(json.toString())
            writer.flush()
            gzip.finish()
            file.finishWrite(stream)
            contentRevision.incrementAndGet()
            cacheBytes += target.length() - oldBytes
            if (!existed) cacheFiles++
            // Counting writes avoids walking thousands of cached files after
            // every new town; full disk scans happen only at start or near cap.
            if (cacheBytes > CACHE_BUDGET_BYTES || cacheFiles > CACHE_BUDGET_FILES) {
                trimCache(context, keep = target)
            }
            true
        } catch (_: Exception) {
            if (stream != null) file.failWrite(stream)
            false
        }
    }

    private fun forgetCacheFile(file: File) {
        val existed = file.isFile
        val bytes = if (existed) file.length() else 0L
        AtomicFile(file).delete()
        // Count empty corrupt files too, and retain charges when deletion failed.
        if (existed && !file.isFile && initialTrimDone) {
            cacheBytes = (cacheBytes - bytes).coerceAtLeast(0L)
            cacheFiles = (cacheFiles - 1).coerceAtLeast(0)
        }
    }

    private fun readCacheJson(target: File): JSONObject {
        AtomicFile(target).openRead().use { raw ->
            val input = raw.buffered()
            input.mark(2)
            val gzip = input.read() == 0x1f && input.read() == 0x8b
            input.reset()
            val decoded = if (gzip) GZIPInputStream(input) else input
            decoded.bufferedReader().use { reader ->
                val buffer = CharArray(8192)
                val json = StringBuilder()
                while (true) {
                    val read = reader.read(buffer)
                    if (read < 0) break
                    if (json.length + read > MAX_DECODED_CACHE_CHARS) {
                        throw IOException("Boundary cache exceeds decoded size limit")
                    }
                    json.append(buffer, 0, read)
                }
                return JSONObject(json.toString())
            }
        }
    }

    fun generation(): Long = generation.get()

    /** The former state cache shared a directory with still-valid country polygons. */
    private fun reclaimOldStateCache(context: Context, place: PlaceDiscovery, target: File) {
        if (place.kind != PlaceKind.STATE) return
        val obsolete = File(File(context.applicationContext.filesDir, DIRECTORY), target.name)
        if (obsolete.isFile || File(obsolete.path + ".bak").isFile ||
            File(obsolete.path + ".new").isFile) forgetCacheFile(obsolete)
    }

    fun read(context: Context, place: PlaceDiscovery): PlaceOverlayCacheResult =
        synchronized(mutationLock) {
            initialTrim(context)
            val target = file(context, place)
            reclaimOldStateCache(context, place, target)
            readCacheFile(target, place)
        }

    /** Census municipalities for all 50 states are cached separately from OSM. */
    fun readOfficialUs(context: Context, place: PlaceDiscovery): PlaceOverlayCacheResult =
        synchronized(mutationLock) {
            initialTrim(context)
            readCacheFile(officialUsFile(context, place), place)
        }

    fun writeOfficialUs(
        context: Context, place: PlaceDiscovery, data: PlaceOverlayData?,
        expectedGeneration: Long? = null
    ): Boolean = synchronized(mutationLock) {
        if (expectedGeneration != null && expectedGeneration != generation.get()) return@synchronized false
        val target = officialUsFile(context, place)
        // A slow overlay request may finish after authoritative point discovery
        // has cached its polygon. An older negative result must not erase it.
        if (data == null && readCacheFile(target, place).data != null) {
            return@synchronized true
        }
        val directory = target.parentFile ?: return@synchronized false
        if (!directory.exists() && !directory.mkdirs()) return@synchronized false
        writeCacheFile(context, target, overlayCacheJson(data))
    }

    // The normal and authoritative U.S. overlay caches share the same JSON format.
    private fun overlayCacheJson(data: PlaceOverlayData?, verified: Boolean = false): JSONObject {
        val json = JSONObject().put("found", data != null)
        // Only negative lookups expire. Successful polygon entries are kept by
        // the LRU budget and never read fetched_at, so do not persist that field.
        if (data == null) json.put("fetched_at", System.currentTimeMillis())
        else {
            if (verified) json.put("discovery_verified", true)
            json.put("name", data.name)
                .put("kind", data.kind.name)
                .put("area_sq_km", data.areaSquareKilometers)
                .put("geometry", JSONObject(data.geometryJson))
            // Missing and JSON-null population have the same read semantics.
            data.population?.let { json.put("population", it) }
        }
        return json
    }

    private fun officialUsFile(context: Context, place: PlaceDiscovery): File {
        val path = file(context, place)
        return File(File(context.applicationContext.filesDir, US_OFFICIAL_DIRECTORY), path.name)
    }

    private fun readCacheFile(
        file: File, place: PlaceDiscovery, verifiedOnly: Boolean = false
    ): PlaceOverlayCacheResult {
        if (!file.isFile && !File(file.path + ".bak").isFile) {
            return PlaceOverlayCacheResult(false, null)
        }
        return runCatching {
            val json = readCacheJson(file)
            // Metadata and geometry are stable, but an actively used polygon
            // should survive eviction over one not opened for several hours.
            if (System.currentTimeMillis() - file.lastModified() > 4L * 60L * 60L * 1000L) {
                file.setLastModified(System.currentTimeMillis())
            }
            if (!json.optBoolean("found", false)) {
                val fetchedAt = json.optLong("fetched_at", 0L)
                if (fetchedAt <= 0L || System.currentTimeMillis() - fetchedAt !in 0L..NEGATIVE_CACHE_MS) {
                    forgetCacheFile(file)
                    PlaceOverlayCacheResult(false, null)
                } else PlaceOverlayCacheResult(true, null)
            } else {
                // Parse even unverified entries so malformed cache files are still
                // deleted, exactly as before the single-read optimization.
                val data = PlaceOverlayData(
                    key = place.key, name = json.getString("name"),
                    kind = PlaceKind.valueOf(json.getString("kind")),
                    population = if (json.isNull("population")) null else json.getLong("population"),
                    areaSquareKilometers = json.getDouble("area_sq_km"),
                    geometryJson = json.getJSONObject("geometry").toString()
                )
                PlaceOverlayCacheResult(
                    true, data.takeUnless {
                        verifiedOnly && !json.optBoolean("discovery_verified", false)
                    }
                )
            }
        }.getOrElse {
            forgetCacheFile(file)
            PlaceOverlayCacheResult(false, null)
        }
    }

    /** Only polygons fetched using the strict municipal verification rules can earn town points. */
    fun readVerifiedForDiscovery(context: Context, place: PlaceDiscovery): PlaceOverlayData? {
        if (place.kind != PlaceKind.TOWN) return null
        return synchronized(mutationLock) {
            readCacheFile(file(context, place), place, verifiedOnly = true).data
        }
    }

    fun write(
        context: Context,
        place: PlaceDiscovery,
        data: PlaceOverlayData?,
        expectedGeneration: Long? = null,
        verifiedForDiscovery: Boolean = false
    ): Boolean = synchronized(mutationLock) {
        if (expectedGeneration != null && generation.get() != expectedGeneration) return@synchronized false
        val target = file(context, place)
        reclaimOldStateCache(context, place, target)
        // A generic refresh must not replace a verified town polygon. Ask
        // the cache reader to enforce that in its single decode; previously the
        // large gzip polygon was decompressed and parsed twice on this path.
        val preserveVerifiedTown = data != null && place.kind == PlaceKind.TOWN &&
            !verifiedForDiscovery
        val cached = readCacheFile(target, place, verifiedOnly = preserveVerifiedTown)
        // A negative response must also preserve any existing valid polygon.
        if (cached.data != null && (data == null || preserveVerifiedTown)) {
            return@synchronized true
        }
        val directory = target.parentFile ?: return@synchronized false
        if (!directory.exists() && !directory.mkdirs()) return@synchronized false
        writeCacheFile(context, target, overlayCacheJson(data, verifiedForDiscovery && place.kind == PlaceKind.TOWN))
    }

    fun clear(context: Context) = synchronized(mutationLock) {
        generation.incrementAndGet()
        contentRevision.incrementAndGet()
        initialTrimDone = false
        cacheBytes = 0L
        cacheFiles = 0
        clearRetiredCaches(context)
        File(context.applicationContext.filesDir, DIRECTORY).deleteRecursively()
        File(context.applicationContext.filesDir, TOWN_DIRECTORY).deleteRecursively()
        File(context.applicationContext.filesDir, STATE_DIRECTORY).deleteRecursively()
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
        val directory = when (place.kind) {
            PlaceKind.TOWN -> TOWN_DIRECTORY
            PlaceKind.STATE -> STATE_DIRECTORY
            PlaceKind.COUNTRY -> DIRECTORY
        }
        return File(File(context.applicationContext.filesDir, directory), "${String(hex)}.json")
    }
}

class PlaceOverlayClient(
    private val configUrl: String = BuildConfig.PLACE_OVERLAY_CONFIG_URL
) {
    private var resolvedEndpoint: String? = null
    private val boundaryBackup = GeoBoundariesClient()

    fun fetch(place: PlaceDiscovery, requireContainment: Boolean = true): PlaceOverlayData? {
        val base = resolveEndpoint()
        return try {
            fetchPrimary(base, place, requireContainment) ?: boundaryBackup.fetch(place, requireContainment)
        } catch (error: IOException) {
            boundaryBackup.fetch(place, requireContainment) ?: throw error
        }
    }

    private fun fetchPrimary(base: String, place: PlaceDiscovery, requireContainment: Boolean): PlaceOverlayData? {
        throttle(base)
        val threshold = when (place.kind) {
            PlaceKind.COUNTRY -> "0.02"
            PlaceKind.STATE -> "0"
            PlaceKind.TOWN -> "0"
        }
        val url = Uri.parse("$base/search").buildUpon()
            .appendQueryParameter("format", "jsonv2")
            .appendQueryParameter("q", query(place))
            .appendQueryParameter("addressdetails", "1")
            .appendQueryParameter("extratags", "1")
            .appendQueryParameter("namedetails", "1")
            // Android's English geocoder and the OSM search should use the same
            // label language for countries, regions and municipality aliases.
            .appendQueryParameter("accept-language", "en")
            .appendQueryParameter("polygon_geojson", "1")
            .appendQueryParameter("polygon_threshold", threshold)
            .appendQueryParameter("limit", "8")
            .build()
            .toString()
        val connection = open(url, redirects = false, readTimeoutMs = 12_000)
        return try {
            val status = connection.responseCode
            if (status == 429 || status == 503) {
                noteProviderBackoff(base, status, connection.getHeaderField("Retry-After"))
            }
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
        val (value, fetchedConfig) = try {
            val connection = open(url, redirects = true, readTimeoutMs = 5_000)
            try {
                val status = connection.responseCode
                if (status !in 200..299 || connection.url.protocol != "https") {
                    throw IOException("Place overlay config is unavailable")
                }
                connection.inputStream.bufferedReader().use(::readConfigEndpoint) to true
            } finally {
                connection.disconnect()
            }
        } catch (_: IOException) {
            // Keep using the safe public fallback when the config service is down.
            // Do not pin that fallback for the whole batch: later requests should
            // be able to recover if the configured provider comes back online.
            DEFAULT_ENDPOINT to false
        }
        if (!value.startsWith("https://")) throw IOException("Place overlay service is disabled or invalid")
        if (fetchedConfig) resolvedEndpoint = value
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
                "RoadConquest/${BuildConfig.VERSION_NAME} (+https://github.com/AntnyGamer/Road-Conquest)"
            )
            setRequestProperty("Accept", "application/json, text/plain;q=0.9")
        }

    private fun throttle(base: String) {
        synchronized(rateLock) {
            val now = SystemClock.elapsedRealtime()
            // Respect an explicit provider rejection without sleeping a worker
            // for minutes. The saved place candidate is retried by the existing
            // persistent discovery queue.
            if (cooldownEndpoint == base && now < cooldownUntilElapsed) {
                throw IOException("Place overlay provider requested a later retry")
            }
            if (lastRequestElapsed != Long.MIN_VALUE) {
                val delay = 1_100L - (now - lastRequestElapsed)
                if (delay > 0L) Thread.sleep(delay)
            }
            lastRequestElapsed = SystemClock.elapsedRealtime()
        }
    }

    private fun noteProviderBackoff(base: String, status: Int, retryAfter: String?) {
        val delay = maxOf(
            if (status == 429) 60_000L else 30_000L,
            retryAfterMillis(retryAfter, System.currentTimeMillis())
        )
        synchronized(rateLock) {
            val until = SystemClock.elapsedRealtime() + delay
            if (cooldownEndpoint != base) {
                cooldownEndpoint = base
                cooldownUntilElapsed = until
            } else {
                cooldownUntilElapsed = maxOf(cooldownUntilElapsed, until)
            }
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
                if (builder.length + count > limit) throw IOException("Place boundary response is too large")
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
        private var cooldownEndpoint: String? = null
        private var cooldownUntilElapsed = 0L

        /** HTTP Retry-After is either seconds or an RFC 1123 date; cap local deferment. */
        internal fun retryAfterMillis(raw: String?, nowMillis: Long): Long {
            val value = raw?.trim().orEmpty()
            if (value.isEmpty()) return 0L
            value.toLongOrNull()?.let { return it.coerceIn(0L, 900L) * 1_000L }
            return runCatching {
                ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME)
                    .toInstant().toEpochMilli() - nowMillis
            }.getOrNull()?.coerceIn(0L, 900_000L) ?: 0L
        }

        private const val MAX_CONFIG_LENGTH = 2_048
        private const val DEFAULT_ENDPOINT = "https://nominatim.openstreetmap.org"
        private const val EARTH_RADIUS_M = 6_371_008.8
        // These values are invariant across every Nominatim result. Reuse the compiled patterns
        // and immutable membership tables instead of rebuilding them for each candidate.
        private val WHITESPACE_RE = Regex("\\s+")
        private val MUNICIPALITY_FORMS = listOf("township", "borough", "municipality", "city", "town", "village")
            .map { kind -> Triple(kind, "$kind of ", " $kind") }
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
            val results = try {
                JSONArray(body)
            } catch (error: org.json.JSONException) {
                // An HTML, truncated, or malformed 200 response is a provider failure,
                // not a verified absence of this boundary. Do not negative-cache it.
                throw IOException("Invalid place overlay response", error)
            }
            var best: JSONObject? = null
            var bestArea = 0.0
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
                    if (!sameMunicipalityName(candidate.optString("name"), place.displayName) &&
                        !englishMunicipalityAlias(candidate, place.displayName)) continue
                    val address = candidate.optJSONObject("address") ?: continue
                    val state = canonical(address.optString("state"))
                    val country = canonical(address.optString("country"))
                    val countryCode = canonical(address.optString("country_code"))
                    val expectedCode = canonical(place.key.substringBefore('|'))
                    val verifiedCountryCode = expectedCode.length == 2 && countryCode == expectedCode
                    if (countryCode.isNotBlank() && expectedCode.length == 2 &&
                        !verifiedCountryCode) continue
                    if (state.isNotBlank() && canonical(place.parentName).isNotBlank() &&
                        state != canonical(place.parentName)
                    ) continue
                    if (country.isNotBlank() && canonical(place.countryName).isNotBlank() &&
                        country != canonical(place.countryName) && !verifiedCountryCode
                    ) continue
                }
                // A name or nearby postal address is not evidence of entering a town.
                // Require the GPS point to be inside this exact municipal polygon.
                if (place.kind == PlaceKind.TOWN && requireContainment &&
                    !PlaceGeometry.contains(geometry, place.latitude, place.longitude)
                ) continue
                val score = score(place, candidate)
                if (score > bestScore) {
                    val area = areaSquareKilometers(geometry)
                    if (!area.isFinite() || area <= 0.0) continue
                    best = candidate
                    bestArea = area
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
            val extra = selected.optJSONObject("extratags")
            return PlaceOverlayData(
                key = place.key,
                name = place.displayName,
                kind = place.kind,
                population = parsePopulation(extra?.optString("population")),
                areaSquareKilometers = bestArea,
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
            var score = if (resultName == expectedName ||
                (place.kind == PlaceKind.TOWN && englishMunicipalityAlias(candidate, place.displayName))) 3 else 0
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
            // The end of each edge is the start of the next. Decode its
            // longitude/latitude and sine once, keeping the original edge order.
            val first = ring.optJSONArray(0)
            var lon1 = Math.toRadians(first?.optDouble(0, Double.NaN) ?: Double.NaN)
            var lat1 = Math.toRadians(first?.optDouble(1, Double.NaN) ?: Double.NaN)
            var sine1 = sin(lat1)
            for (i in 1..ring.length()) {
                val next = ring.optJSONArray(if (i == ring.length()) 0 else i)
                val lon2 = Math.toRadians(next?.optDouble(0, Double.NaN) ?: Double.NaN)
                val lat2 = Math.toRadians(next?.optDouble(1, Double.NaN) ?: Double.NaN)
                if (lon1.isFinite() && lat1.isFinite() && lon2.isFinite() && lat2.isFinite()) {
                    var delta = lon2 - lon1
                    while (delta > PI) delta -= 2 * PI
                    while (delta < -PI) delta += 2 * PI
                    sum += delta * (2 + sine1 + sin(lat2))
                }
                lon1 = lon2
                lat1 = lat2
                sine1 = sin(lat2)
            }
            return sum * EARTH_RADIUS_M * EARTH_RADIUS_M / 2.0
        }

        /** Match the Geocoder's English city name to the same OSM boundary's English aliases. */
        private fun englishMunicipalityAlias(candidate: JSONObject, expected: String): Boolean {
            val names = candidate.optJSONObject("namedetails") ?: return false
            return listOf("name:en", "official_name:en", "short_name:en").any {
                sameMunicipalityName(names.optString(it), expected)
            }
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
                for ((kind, prefix, suffix) in MUNICIPALITY_FORMS) {
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
        val last = ring.optJSONArray(ring.length() - 1) ?: return false
        var previousLongitude = last.optDouble(0, Double.NaN)
        var x1 = relativeLongitude(previousLongitude, longitude)
        var y1 = last.optDouble(1, Double.NaN)
        if (!x1.isFinite() || !y1.isFinite()) return false
        for (i in 0 until ring.length()) {
            val current = ring.optJSONArray(i) ?: return false
            val currentLongitude = current.optDouble(0, Double.NaN)
            // Unwrap adjacent vertices together. Wrapping each vertex around the
            // query point can turn a small polygon into its opposite-world image.
            val x2 = x1 + relativeLongitude(currentLongitude, previousLongitude)
            val y2 = current.optDouble(1, Double.NaN)
            if (!x2.isFinite() || !y2.isFinite()) return false
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
            // The next edge starts at this same vertex. No second JSON lookup
            // or date-line modulo is needed for its longitude and latitude.
            x1 = x2
            y1 = y2
            previousLongitude = currentLongitude
        }
        return inside
    }

    private fun relativeLongitude(lon: Double, anchor: Double): Double {
        if (!lon.isFinite()) return Double.NaN
        return ((lon - anchor + 540.0) % 360.0) - 180.0
    }
}
