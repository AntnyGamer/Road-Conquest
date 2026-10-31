package com.roadconquest.app.map

import com.roadconquest.app.data.PlaceDiscovery
import com.roadconquest.app.data.PlaceKind
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.io.StringReader
import java.security.MessageDigest
import java.util.Locale
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], manifest = Config.NONE, shadows = [PosixAtomicFileShadow::class])
class PlaceOverlayTest {
    private fun assertCacheAccountingMatchesDisk(context: android.content.Context) {
        val files = listOf("place-overlays-v3", "place-overlays-v5-world-admin", "place-overlays-v4-states-full", "place-overlays-us-census-v1")
            .flatMap { File(context.filesDir, it).listFiles().orEmpty().toList() }
            .filter { it.isFile && it.name.endsWith(".json") }
        val owner = PlaceOverlayCache
        val count = owner.javaClass.getDeclaredField("cacheFiles").apply { isAccessible = true }.getInt(owner)
        val bytes = owner.javaClass.getDeclaredField("cacheBytes").apply { isAccessible = true }.getLong(owner)
        assertEquals("Budget file count must reflect actual retained files", files.size, count)
        assertEquals("Budget bytes must reflect actual retained files", files.sumOf { it.length() }, bytes)
    }

    @Test fun removingAnEmptyCorruptCacheFileRepairsTheBudgetCount() {
        val context = RuntimeEnvironment.getApplication()
        PlaceOverlayCache.clear(context)
        try {
            val neighbor = PlaceDiscovery(PlaceKind.STATE, "gb|neighbor", "Neighbor", "", "United Kingdom", 1L, 1.0, 1.0)
            val empty = neighbor.copy(key = "gb|empty", displayName = "Empty")
            val data = PlaceOverlayData(neighbor.key, neighbor.displayName, neighbor.kind, null, 1.0,
                """{"type":"Polygon","coordinates":[[[0,0],[2,0],[2,2],[0,2],[0,0]]]}""")
            assertTrue(PlaceOverlayCache.write(context, neighbor, data))
            val hash = MessageDigest.getInstance("SHA-256")
                .digest("${empty.kind.name}|${empty.key}".toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(Locale.US, it.toInt() and 0xff) }
            val file = File(File(context.filesDir, "place-overlays-v4-states-full"), "$hash.json")
            file.writeText("")
            PlaceOverlayCache.trimForTest(context, Long.MAX_VALUE, Int.MAX_VALUE)
            assertCacheAccountingMatchesDisk(context)
            assertFalse(PlaceOverlayCache.read(context, empty).cached)
            assertFalse(file.exists())
            assertCacheAccountingMatchesDisk(context)
            assertEquals(data.geometryJson, PlaceOverlayCache.read(context, neighbor).data?.geometryJson)
        } finally { PlaceOverlayCache.clear(context) }
    }

    @Test fun failedCacheDeletionDoesNotUnderstateTheRetainedStorageBudget() {
        val context = RuntimeEnvironment.getApplication()
        PlaceOverlayCache.clear(context)
        try {
            val place = PlaceDiscovery(PlaceKind.STATE, "gb|retained", "Retained", "", "United Kingdom", 1L, 1.0, 1.0)
            val data = PlaceOverlayData(place.key, place.displayName, place.kind, null, 1.0,
                """{"type":"Polygon","coordinates":[[[0,0],[2,0],[2,2],[0,2],[0,0]]]}""")
            assertTrue(PlaceOverlayCache.write(context, place, data))
            val target = File(context.filesDir, "place-overlays-v4-states-full").listFiles()!!.single { it.name.endsWith(".json") }
            val undeletable = object : File(target.path) { override fun delete(): Boolean = false }
            PlaceOverlayCache.javaClass.getDeclaredMethod("forgetCacheFile", File::class.java)
                .apply { isAccessible = true }.invoke(PlaceOverlayCache, undeletable)
            assertTrue(target.isFile)
            assertCacheAccountingMatchesDisk(context)
            assertEquals(data.geometryJson, PlaceOverlayCache.read(context, place).data?.geometryJson)
        } finally { PlaceOverlayCache.clear(context) }
    }

    @Test fun interruptedBoundaryCacheWritesDoNotLeavePermanentNewFiles() {
        val context = RuntimeEnvironment.getApplication()
        PlaceOverlayCache.clear(context)
        try {
            val place = PlaceDiscovery(PlaceKind.COUNTRY, "us", "United States", "", "", 1L, 40.0, -74.0)
            val polygon = """{"type":"Polygon","coordinates":[[[-75,39],[-73,39],[-73,41],[-75,41],[-75,39]]]}"""
            assertTrue(PlaceOverlayCache.write(context, place,
                PlaceOverlayData(place.key, place.displayName, place.kind, null, 100.0, polygon)))
            val directory = File(context.filesDir, "place-overlays-v3")
            val leftover = File(directory, "interrupted.json.new")
            leftover.writeText("abandoned write".repeat(500))
            val recovery = File(directory, "unrelated.json.bak")
            recovery.writeText("previous committed data")
            PlaceOverlayCache.trimForTest(context, Long.MAX_VALUE, Int.MAX_VALUE)
            assertFalse("An unfinished AtomicFile write is never a valid cache", leftover.exists())
            assertTrue("A .bak may be the last committed version", recovery.exists())
            assertEquals(polygon, PlaceOverlayCache.read(context, place).data?.geometryJson)
            assertCacheAccountingMatchesDisk(context)
        } finally { PlaceOverlayCache.clear(context) }
    }

    @Test fun townFillRequiresSavedFixInsideActualMunicipalPolygon() {
        val town = PlaceDiscovery(PlaceKind.TOWN, "us|new jersey|pennsauken",
            "Pennsauken Township", "New Jersey", "United States",
            visitedAt = 1L, latitude = 39.95, longitude = -75.03)
        val data = PlaceOverlayData(town.key, town.displayName, PlaceKind.TOWN, null, 12.0,
            """{"type":"Polygon","coordinates":[[[-75.1,39.9],[-75.0,39.9],[-75.0,40.0],[-75.1,40.0],[-75.1,39.9]]]}""")
        assertTrue(data.containsRecordedVisit(town))
        assertFalse(data.containsRecordedVisit(town.copy(latitude = 40.2)))
        assertFalse(data.copy(geometryJson = "invalid").containsRecordedVisit(town))
        assertFalse(data.copy(key = "us|new jersey|different-town").containsRecordedVisit(town))
        assertFalse(data.copy(kind = PlaceKind.STATE).containsRecordedVisit(town))
        // The same containment gate also applies to imported or non-US town histories.
        assertFalse(data.containsRecordedVisit(town.copy(key = "gb|england|pennsauken",
            latitude = 40.2)))
    }

    @Test fun parserKeepsPolygonPopulationAndComputesArea() {
        val place = PlaceDiscovery(
            PlaceKind.COUNTRY, "us", "United States",
            visitedAt = 1L, latitude = 39.5, longitude = -74.5
        )
        val body = """
            [{
              "name":"United States",
              "addresstype":"country",
              "address":{"country":"United States"},
              "extratags":{"population":"331,000,000"},
              "geojson":{"type":"Polygon","coordinates":[[
                [-75.0,39.0],[-74.0,39.0],[-74.0,40.0],[-75.0,40.0],[-75.0,39.0]
              ]]}
            }]
        """.trimIndent()

        val data = PlaceOverlayClient.parseResponse(place, body)
        assertNotNull(data)
        data!!
        assertEquals("United States", data.name)
        assertEquals(331_000_000L, data.population)
        assertTrue(data.areaSquareKilometers > 8_000.0)
        assertEquals("Polygon", JSONObject(data.geometryJson).getString("type"))
        // A matching but zero-area provider result must not mask a later valid polygon.
        val valid = JSONArray(body).getJSONObject(0)
        val collapsed = JSONObject(valid.toString()).put("geojson", JSONObject(
            """{"type":"Polygon","coordinates":[[[-75,39],[-75,39],[-75,39]]]}"""))
        assertNull(PlaceOverlayClient.parseResponse(place, JSONArray().put(collapsed).toString()))
        val recovered = PlaceOverlayClient.parseResponse(place, JSONArray().put(collapsed).put(valid).toString())
        assertNotNull(recovered)
        assertEquals(data.areaSquareKilometers, recovered!!.areaSquareKilometers, 0.0)
    }

    @Test fun malformedWorldwideResponseTriggersRetryButEmptyArrayDoesNot() {
        val place = PlaceDiscovery(
            PlaceKind.COUNTRY, "us", "United States",
            visitedAt = 1L, latitude = 39.5, longitude = -74.5
        )
        for (body in listOf("<html>Error</html>", "{\"error\":\"temporary\"}", "[broken")) {
            org.junit.Assert.assertThrows(IOException::class.java) {
                PlaceOverlayClient.parseResponse(place, body)
            }
        }
        assertNull(PlaceOverlayClient.parseResponse(place, "[]"))
    }

    @Test fun providerConfigReadIsStrictlyBounded() {
        assertEquals(
            "https://example.com",
            PlaceOverlayClient.readConfigEndpoint(StringReader("  https://example.com/  \nignored"))
        )
        org.junit.Assert.assertThrows(IOException::class.java) {
            PlaceOverlayClient.readConfigEndpoint(
                StringReader("x".repeat(2_049))
            )
        }
    }

    @Test fun detailedTownViewportIncludesIntersectingPolygonsAndSafeFallbacks() {
        val viewport = OverlayViewport.padded(-75.1, -74.9, 39.9, 40.1)!!
        val nearby = OverlayViewport.ofGeometry(
            """{"type":"Polygon","coordinates":[[
                [-75.05,39.95],[-74.95,39.95],[-74.95,40.05],[-75.05,39.95]
            ]]}"""
        )
        val distant = OverlayViewport.ofGeometry(
            """{"type":"Polygon","coordinates":[[
                [1.0,51.0],[1.1,51.0],[1.1,51.1],[1.0,51.0]
            ]]}"""
        )
        assertTrue(viewport.intersects(nearby))
        assertFalse(viewport.intersects(distant))
        assertTrue(viewport.intersects(OverlayViewport.ofGeometry("invalid")))
        // Out-of-world longitudes and dateline-crossing viewports are kept
        // unfiltered instead of causing missing towns or broken wrap edges.
        assertNull(OverlayViewport.padded(179.0, -179.0, 10.0, 11.0))
        assertNull(OverlayViewport.ofGeometry(
            """{"type":"Polygon","coordinates":[[
                [179.0,10.0],[181.0,10.0],[181.0,11.0],[179.0,10.0]
            ]]}"""
        ))
    }

    @Test fun providerRetryAfterAcceptsSecondsAndHttpDatesWithoutUnboundedDelay() {
        val now = java.time.Instant.parse("2026-10-15T12:00:00Z").toEpochMilli()
        assertEquals(60_000L, PlaceOverlayClient.retryAfterMillis("60", now))
        assertEquals(900_000L, PlaceOverlayClient.retryAfterMillis("99999", now))
        assertEquals(120_000L, PlaceOverlayClient.retryAfterMillis(
            "Thu, 15 Oct 2026 12:02:00 GMT", now
        ))
        assertEquals(0L, PlaceOverlayClient.retryAfterMillis(
            "Thu, 15 Oct 2026 11:59:00 GMT", now
        ))
        assertEquals(0L, PlaceOverlayClient.retryAfterMillis("invalid", now))
        assertEquals(0L, PlaceOverlayClient.retryAfterMillis(null, now))
    }

    @Test fun verificationFlagIsReadWithTheCachedMunicipalPolygon() {
        val context = RuntimeEnvironment.getApplication()
        val place = PlaceDiscovery(
            PlaceKind.TOWN, "us|new jersey|verified-cache-test", "Verified Cache Town",
            "New Jersey", "United States", 1234L, 40.0, -74.0
        )
        val data = PlaceOverlayData(
            place.key, place.displayName, place.kind, null, 1.0,
            """{"type":"Polygon","coordinates":[[
                [-74.02,39.99],[-73.98,39.99],[-73.98,40.01],
                [-74.02,40.01],[-74.02,39.99]
            ]]}"""
        )
        PlaceOverlayCache.clear(context)
        try {
            assertTrue(PlaceOverlayCache.write(context, place, data))
            assertNotNull(PlaceOverlayCache.read(context, place).data)
            assertNull(PlaceOverlayCache.readVerifiedForDiscovery(context, place))

            assertTrue(PlaceOverlayCache.write(
                context, place, data, verifiedForDiscovery = true
            ))
            val verified = PlaceOverlayCache.readVerifiedForDiscovery(context, place)
            assertEquals(JSONObject(data.geometryJson).toString(), verified?.geometryJson)
        } finally {
            PlaceOverlayCache.clear(context)
        }
    }

    @Test fun lateNegativeCensusBoundaryResultCannotEraseVerifiedTownGeometry() {
        val context = RuntimeEnvironment.getApplication()
        val town = PlaceDiscovery(
            PlaceKind.TOWN, "us|new jersey|census-4-3401234", "Sample borough",
            "New Jersey", "United States", 1_000L, 39.9, -75.1
        )
        val geometry = """{"type":"Polygon","coordinates":[[
            [-75.2,39.8],[-75.0,39.8],[-75.0,40.0],
            [-75.2,40.0],[-75.2,39.8]
        ]]}"""
        val verified = PlaceOverlayData(town.key, town.displayName, town.kind, null, 3.0, geometry)
        PlaceOverlayCache.clear(context)
        try {
            val generation = PlaceOverlayCache.generation()
            // In production, an overlay fetch begins before discovery resolves
            // the town; its eventual null response must not replace this success.
            assertTrue(PlaceOverlayCache.writeOfficialUs(context, town, verified))
            assertTrue(PlaceOverlayCache.writeOfficialUs(context, town, null, generation))
            assertEquals(JSONObject(verified.geometryJson).toString(),
                PlaceOverlayCache.readOfficialUs(context, town).data?.geometryJson)
            // A negative response still caches normally if there was no polygon.
            val absent = town.copy(key = "us|new jersey|census-4-3405678")
            assertTrue(PlaceOverlayCache.writeOfficialUs(context, absent, null, generation))
            val negative = PlaceOverlayCache.readOfficialUs(context, absent)
            assertTrue(negative.cached)
            assertNull(negative.data)
        } finally {
            PlaceOverlayCache.clear(context)
        }
    }

    @Test fun staleWorldwideOverlayCannotDowngradeVerifiedDiscoveryPolygon() {
        val context = RuntimeEnvironment.getApplication()
        val town = PlaceDiscovery(
            PlaceKind.TOWN, "gb|region|verified-borough", "Verified borough",
            "Region", "United Kingdom", 1_000L, 51.5, -0.1
        )
        val verified = PlaceOverlayData(town.key, town.displayName, town.kind, null, 2.0,
            """{"type":"Polygon","coordinates":[[
                [-0.2,51.4],[0.0,51.4],[0.0,51.6],
                [-0.2,51.6],[-0.2,51.4]
            ]]}""")
        val unverified = verified.copy(areaSquareKilometers = 50.0)
        PlaceOverlayCache.clear(context)
        try {
            assertTrue(PlaceOverlayCache.write(
                context, town, verified, verifiedForDiscovery = true
            ))
            assertTrue(PlaceOverlayCache.write(context, town, unverified))
            assertTrue(PlaceOverlayCache.write(context, town, null))
            assertEquals(2.0,
                PlaceOverlayCache.readVerifiedForDiscovery(context, town)?.areaSquareKilometers
                    ?: -1.0, 0.0)
        } finally {
            PlaceOverlayCache.clear(context)
        }
    }

    @Test fun geoBoundariesParserRequiresExactlyOneGeometricallyValidMatch() {
        val place = PlaceDiscovery(PlaceKind.STATE, "gb|abc|test", "Example Region",
            visitedAt = 1L, latitude = 40.0, longitude = -74.0)
        val polygon = JSONObject().put("type", "Polygon").put("coordinates",
            org.json.JSONArray().put(org.json.JSONArray()
                .put(org.json.JSONArray().put(-75.0).put(39.0))
                .put(org.json.JSONArray().put(-73.0).put(39.0))
                .put(org.json.JSONArray().put(-73.0).put(41.0))
                .put(org.json.JSONArray().put(-75.0).put(41.0))
                .put(org.json.JSONArray().put(-75.0).put(39.0))))
        fun feature(name: String) = JSONObject().put("properties",
            JSONObject().put("shapeName", name)).put("geometry", polygon)
        val features = org.json.JSONArray().put(feature("Not This Region"))
            .put(feature("Example Region"))
        val body = JSONObject().put("type", "FeatureCollection").put("features", features)
        assertNotNull(GeoBoundariesClient.parse(place, body, true))
        features.put(feature("Example Region"))
        assertNull(GeoBoundariesClient.parse(place, body, true))
    }

    @Test fun largeBoundaryCacheStreamsGzipAndRoundTripsAllCoordinates() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val place = PlaceDiscovery(
            PlaceKind.TOWN, "gb|large-cache-stream-test", "Large Test Town",
            visitedAt = 1L, latitude = 40.0, longitude = -74.0
        )
        val coordinates = org.json.JSONArray()
        for (i in 0..3_000) coordinates.put(org.json.JSONArray()
            .put(-74.0 + (i % 17) * 0.00001).put(40.0 + i * 0.000001))
        val ring = org.json.JSONArray().put(coordinates)
        val geometry = JSONObject().put("type", "Polygon")
            .put("coordinates", ring).toString()
        val outline = PlaceOverlayData(place.key, place.displayName, place.kind,
            null, 1.0, geometry)
        PlaceOverlayCache.clear(context)
        try {
            assertTrue(PlaceOverlayCache.write(context, place, outline, verifiedForDiscovery = true))
            val cacheDir = File(context.filesDir, "place-overlays-v5-world-admin")
            val diskFile = cacheDir.listFiles()!!.single { it.name.endsWith(".json") }
            assertEquals(0x1f, diskFile.inputStream().use { it.read() })
            assertEquals(JSONObject(geometry).toString(),
                PlaceOverlayCache.readVerifiedForDiscovery(context, place)?.geometryJson)
        } finally { PlaceOverlayCache.clear(context) }
    }

    @Test fun successfulCacheOmitsOnlyUnusedMetadataAndRoundTripsUnknownPopulation() {
        val context = RuntimeEnvironment.getApplication()
        PlaceOverlayCache.clear(context)
        try {
            val place = PlaceDiscovery(PlaceKind.COUNTRY, "zz", "Example", "", "", 1L, 0.5, 0.5)
            val geometry = """{"type":"Polygon","coordinates":[[[0,0],[1,0],[1,1],[0,1],[0,0]]]}"""
            val data = PlaceOverlayData(place.key, place.displayName, place.kind, null, 1.0, geometry)
            assertTrue(PlaceOverlayCache.write(context, place, data))
            val file = File(context.filesDir, "place-overlays-v3").listFiles()!!.single { it.name.endsWith(".json") }
            fun saved() = java.util.zip.GZIPInputStream(file.inputStream()).bufferedReader().use {
                JSONObject(it.readText())
            }
            assertFalse(saved().has("population"))
            assertFalse(saved().has("fetched_at"))
            assertNull(PlaceOverlayCache.read(context, place).data?.population)
            assertTrue(PlaceOverlayCache.write(context, place, data.copy(population = 123)))
            assertEquals(123L, saved().getLong("population"))
            assertEquals(123L, PlaceOverlayCache.read(context, place).data?.population)
        } finally { PlaceOverlayCache.clear(context) }
    }

    @Test fun boundaryCacheIsCompressedAndStillReadsPreviousPlainJson() {
        val context = RuntimeEnvironment.getApplication()
        val place = PlaceDiscovery(
            PlaceKind.TOWN, "gb|test|compressed-cache", "Compressed Town",
            "Test region", "United Kingdom", 1L, 51.5, -0.1
        )
        val data = PlaceOverlayData(place.key, place.displayName, place.kind, 123L, 4.5,
            """{"type":"Polygon","coordinates":[[
                [-0.2,51.4],[0.0,51.4],[0.0,51.6],[-0.2,51.6],[-0.2,51.4]
            ]]}""")
        PlaceOverlayCache.clear(context)
        try {
            assertTrue(PlaceOverlayCache.write(context, place, data, verifiedForDiscovery = true))
            val cacheDir = File(context.filesDir, "place-overlays-v5-world-admin")
            val file = cacheDir.listFiles()!!.single { it.name.endsWith(".json") }
            val magic = file.inputStream().use { byteArrayOf(it.read().toByte(), it.read().toByte()) }
            assertEquals(0x1f, magic[0].toInt() and 0xff)
            assertEquals(0x8b, magic[1].toInt() and 0xff)
            val saved = java.util.zip.GZIPInputStream(file.inputStream()).bufferedReader().use {
                JSONObject(it.readText())
            }
            assertTrue(saved.getBoolean("found"))
            assertFalse("Successful polygons do not need the negative-cache expiry timestamp",
                saved.has("fetched_at"))
            assertEquals(JSONObject(data.geometryJson).toString(),
                PlaceOverlayCache.readVerifiedForDiscovery(context, place)?.geometryJson)

            // Older installations wrote plain JSON under this exact cache name.
            val old = JSONObject()
                .put("found", true).put("fetched_at", System.currentTimeMillis())
                .put("discovery_verified", true)
                .put("name", place.displayName).put("kind", place.kind.name)
                .put("population", 123).put("area_sq_km", data.areaSquareKilometers)
                .put("geometry", JSONObject(data.geometryJson))
            file.writeText(old.toString())
            assertEquals(JSONObject(data.geometryJson).toString(),
                PlaceOverlayCache.readVerifiedForDiscovery(context, place)?.geometryJson)
        } finally { PlaceOverlayCache.clear(context) }
    }

    @Test fun cacheBudgetEvictsTheOldestOutlineWithoutErasingPlaces() {
        val context = RuntimeEnvironment.getApplication()
        PlaceOverlayCache.clear(context)
        try {
            val geometry = """{"type":"Polygon","coordinates":[[
                [-0.2,51.4],[0.0,51.4],[0.0,51.6],[-0.2,51.6],[-0.2,51.4]
            ]]}"""
            val places = (1..3).map { n ->
                PlaceDiscovery(PlaceKind.TOWN, "gb|budget|town$n", "Town$n",
                    "Region", "United Kingdom", 1L, 51.5, -0.1)
            }
            places.forEach { place ->
                assertTrue(PlaceOverlayCache.write(context, place, PlaceOverlayData(
                    place.key, place.displayName, place.kind, null, 1.0, geometry
                )))
            }
            val dir = File(context.filesDir, "place-overlays-v5-world-admin")
            val files = dir.listFiles()!!.filter { it.name.endsWith(".json") }
                .sortedBy { it.name }
            assertEquals(3, files.size)
            files.forEachIndexed { i, file ->
                assertTrue(file.setLastModified(System.currentTimeMillis() - (3 - i) * 60_000L))
            }
            PlaceOverlayCache.trimForTest(context, Long.MAX_VALUE, 2)
            assertFalse(files[0].exists())
            assertTrue(files[1].exists())
            assertTrue(files[2].exists())
        } finally { PlaceOverlayCache.clear(context) }
    }

    @Test fun legacyV3CacheCannotReintroduceBadTownOutlines() {
        val context = RuntimeEnvironment.getApplication()
        val place = PlaceDiscovery(
            PlaceKind.TOWN, "us|new jersey|cache-compatibility", "Cached Town",
            "New Jersey", "United States", 1L, 40.0, -74.0
        )
        PlaceOverlayCache.clear(context)
        try {
            // Use the original formatter to create the name written by earlier releases.
            val oldHash = MessageDigest.getInstance("SHA-256")
                .digest("${place.kind.name}|${place.key}".toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(Locale.US, it.toInt() and 0xff) }
            val directory = File(context.filesDir, "place-overlays-v3")
            assertTrue(directory.mkdirs() || directory.isDirectory)
            File(directory, "$oldHash.json").writeText(
                JSONObject().put("found", false)
                    .put("fetched_at", System.currentTimeMillis()).toString()
            )
            val cached = PlaceOverlayCache.read(context, place)
            assertFalse(cached.cached)
            assertNull(cached.data)
        } finally {
            PlaceOverlayCache.clear(context)
        }
    }

    @Test fun futureDatedNegativeCacheCannotBlockBoundaryRetries() {
        val context = RuntimeEnvironment.getApplication()
        val place = PlaceDiscovery(PlaceKind.COUNTRY, "zz", "Example", "", "", 1L, 1.0, 1.0)
        PlaceOverlayCache.clear(context)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest("${place.kind.name}|${place.key}".toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(Locale.US, it.toInt() and 0xff) }
            val directory = File(context.filesDir, "place-overlays-v3")
            assertTrue(directory.mkdirs() || directory.isDirectory)
            val file = File(directory, "$digest.json")
            file.writeText(JSONObject().put("found", false)
                .put("fetched_at", System.currentTimeMillis() + 86_400_000L).toString())
            assertFalse(PlaceOverlayCache.read(context, place).cached)
            assertFalse(file.exists())
            assertTrue(PlaceOverlayCache.write(context, place, null))
            val negative = java.util.zip.GZIPInputStream(file.inputStream()).bufferedReader().use {
                JSONObject(it.readText())
            }
            assertFalse(negative.getBoolean("found"))
            assertTrue("Negative entries still require their expiry timestamp", negative.has("fetched_at"))
            assertTrue("Fresh negative cache still prevents duplicate requests",
                PlaceOverlayCache.read(context, place).cached)
        } finally { PlaceOverlayCache.clear(context) }
    }

    @Test fun staleFetchCannotRecreateOverlayCacheAfterDeletion() {
        val context = RuntimeEnvironment.getApplication()
        val place = PlaceDiscovery(
            PlaceKind.TOWN, "us|new jersey|old", "Old Town", "New Jersey", "United States",
            1L, 40.0, -74.5
        )
        PlaceOverlayCache.clear(context)
        val generation = PlaceOverlayCache.generation()
        PlaceOverlayCache.clear(context)

        assertFalse(
            PlaceOverlayCache.write(
                context,
                place,
                PlaceOverlayData(
                    place.key,
                    place.displayName,
                    place.kind,
                    1_000L,
                    1.0,
                    """{"type":"Polygon","coordinates":[[[-75,39],[-74,39],[-74,40],[-75,39]]]}"""
                ),
                generation
            )
        )
        assertFalse(PlaceOverlayCache.read(context, place).cached)
        // The foreground Census discovery worker must honor the same reset
        // generation as the map renderer. A pre-deletion network response is
        // not allowed to recreate the user's erased location-related cache.
        val official = place.copy(key = "us|new jersey|census-4-3401234")
        val boundary = PlaceOverlayData(
            official.key, official.displayName, official.kind, null, 1.0,
            """{"type":"Polygon","coordinates":[[
                [-75,39],[-74,39],[-74,40],[-75,39]
            ]]}"""
        )
        assertFalse(PlaceOverlayCache.writeOfficialUs(context, official, boundary, generation))
        assertFalse(PlaceOverlayCache.readOfficialUs(context, official).cached)
    }

    @Test fun sameNamedTownUsesTheBoundaryNearestTheDiscoveredLocation() {
        val place = PlaceDiscovery(
            PlaceKind.TOWN,
            "us|new jersey|washington township",
            "Washington Township",
            "New Jersey",
            "United States",
            1L,
            40.0000,
            -75.0000
        )
        val body = """
            [
              {
                "name":"Washington Township",
                "category":"boundary","type":"administrative",
                "lat":"41.0000",
                "lon":"-74.0000",
                "addresstype":"administrative",
                "address":{
                  "township":"Washington Township",
                  "state":"New Jersey",
                  "country":"United States"
                },
                "extratags":{"population":"111"},
                "geojson":{"type":"Polygon","coordinates":[[
                  [-74.01,40.99],[-73.99,40.99],[-73.99,41.01],[-74.01,41.01],[-74.01,40.99]
                ]]}
              },
              {
                "name":"Washington Township",
                "category":"boundary","type":"administrative",
                "lat":"40.0100",
                "lon":"-75.0100",
                "addresstype":"administrative",
                "address":{
                  "township":"Washington Township",
                  "state":"New Jersey",
                  "country":"United States"
                },
                "extratags":{"population":"222"},
                "geojson":{"type":"Polygon","coordinates":[[
                  [-75.02,40.00],[-75.00,40.00],[-75.00,40.02],[-75.02,40.02],[-75.02,40.00]
                ]]}
              }
            ]
        """.trimIndent()

        val data = PlaceOverlayClient.parseResponse(place, body)
        assertNotNull(data)
        assertEquals(222L, data!!.population)
    }

    @Test fun wrongAdministrativeResultIsRejected() {
        val place = PlaceDiscovery(
            PlaceKind.STATE, "us|new jersey", "New Jersey", "United States", "United States",
            1L, 40.0, -74.5
        )
        val body = """
            [{
              "name":"New Jersey",
              "addresstype":"shop",
              "address":{"country":"Canada","state":"Ontario"},
              "geojson":{"type":"Polygon","coordinates":[[
                [-75.0,39.0],[-74.0,39.0],[-74.0,40.0],[-75.0,40.0],[-75.0,39.0]
              ]]}
            }]
        """.trimIndent()
        assertEquals(null, PlaceOverlayClient.parseResponse(place, body))
    }
    @Test fun municipalityPolygonRejectsNearbyPostalTown() {
        val place = PlaceDiscovery(
            PlaceKind.TOWN, "us|new jersey|woodbury", "Woodbury",
            "New Jersey", "United States", 1L, 39.83, -75.11
        )
        val body = """
            [{
              "name":"Woodbury", "category":"boundary", "type":"administrative",
              "addresstype":"town",
              "address":{"town":"Woodbury","state":"New Jersey","country":"United States"},
              "lat":"39.84","lon":"-75.11",
              "geojson":{"type":"Polygon","coordinates":[[
                [-75.15,39.84],[-75.10,39.84],[-75.10,39.86],
                [-75.15,39.86],[-75.15,39.84]
              ]]}
            }]
        """.trimIndent()
        assertNull(PlaceOverlayClient.parseResponse(place, body))
        // An outside candidate may cache the correctly named administrative
        // boundary, but must NOT earn a visit until its own point is inside.
        val trustedShape = PlaceOverlayClient.parseResponse(place, body, requireContainment = false)
        assertNotNull(trustedShape)
        assertFalse(PlaceGeometry.contains(
            JSONObject(trustedShape!!.geometryJson), place.latitude, place.longitude
        ))
    }

    @Test fun neighborhoodPolygonNeverQualifiesForTownReward() {
        val place = PlaceDiscovery(
            PlaceKind.TOWN, "us|new jersey|fake", "Fake",
            "New Jersey", "United States", 1L, 39.5, -75.5
        )
        val body = """
            [{
              "name":"Fake","category":"place","type":"neighbourhood",
              "addresstype":"locality",
              "address":{"locality":"Fake","state":"New Jersey","country":"United States"},
              "lat":"39.5","lon":"-75.5",
              "geojson":{"type":"Polygon","coordinates":[[
                [-76,39],[-75,39],[-75,40],[-76,40],[-76,39]
              ]]}
            }]
        """.trimIndent()
        assertNull(PlaceOverlayClient.parseResponse(place, body))
    }

    @Test fun polygonAreaIsIndependentOfRingDirectionAndSubtractsHoles() {
        val outer = org.json.JSONArray("""[[-75,39],[-74,39],[-74,40],[-75,40]]""")
        val inner = org.json.JSONArray("""[[-74.75,39.25],[-74.25,39.25],[-74.25,39.75],[-74.75,39.75]]""")
        fun shape(outerRing: org.json.JSONArray, innerRing: org.json.JSONArray?) =
            JSONObject().put("type", "Polygon")
                .put("coordinates", org.json.JSONArray().put(outerRing).apply {
                    if (innerRing != null) put(innerRing)
                })
        fun reversed(ring: org.json.JSONArray) = org.json.JSONArray().apply {
            for (i in ring.length() - 1 downTo 0) put(ring.getJSONArray(i))
        }
        val whole = PlaceOverlayClient.areaSquareKilometers(shape(outer, null))
        val withHole = PlaceOverlayClient.areaSquareKilometers(shape(outer, inner))
        assertTrue(whole > withHole)
        assertTrue(withHole > 0.0)
        assertEquals(whole, PlaceOverlayClient.areaSquareKilometers(shape(reversed(outer), null)), 0.000001)
        assertEquals(withHole,
            PlaceOverlayClient.areaSquareKilometers(shape(reversed(outer), reversed(inner))), 0.000001)
    }

    @Test fun polygonContainmentHandlesHolesAndDisjointMunicipalities() {
        val geometry = JSONObject(
            """{"type":"MultiPolygon","coordinates":[
                [[[-75.2,39.8],[-75,39.8],[-75,40],[-75.2,40],[-75.2,39.8]],
                 [[-75.15,39.85],[-75.05,39.85],[-75.05,39.95],[-75.15,39.95],[-75.15,39.85]]],
                [[[-74.8,39.8],[-74.7,39.8],[-74.7,39.9],[-74.8,39.9],[-74.8,39.8]]]
            ]}"""
        )
        assertTrue(PlaceGeometry.contains(geometry, 39.82, -75.18))
        assertFalse(PlaceGeometry.contains(geometry, 39.90, -75.10))
        assertTrue(PlaceGeometry.contains(geometry, 39.85, -74.75))
        assertFalse(PlaceGeometry.contains(geometry, 39.85, -74.60))
    }

    @Test fun polygonContainmentKeepsDateLineWrappingAndBoundaryEdges() {
        val polygon = JSONObject("""{"type":"Polygon","coordinates":[[
            [179.5,10],[-179.5,10],[-179.5,11],[179.5,11],[179.5,10]
        ]]}""")
        assertTrue(PlaceGeometry.contains(polygon, 10.5, 179.9))
        assertTrue(PlaceGeometry.contains(polygon, 10.5, -179.9))
        assertTrue(PlaceGeometry.contains(polygon, 10.0, 179.9))
        assertFalse(PlaceGeometry.contains(polygon, 10.5, 178.0))
        assertFalse(PlaceGeometry.contains(polygon, 10.5, -178.0))
        assertFalse(PlaceGeometry.contains(polygon, 10.5, 0.0))
        assertFalse(PlaceGeometry.contains(polygon, 10.0, 0.0))
    }

    @Test fun polygonContainmentDoesNotMirrorBoundariesOnTheOppositeSideOfTheWorld() {
        fun wrap(longitude: Double) = ((longitude + 540.0) % 360.0) - 180.0
        for (west in listOf(-179.5, -75.0, 0.0, 90.0, 179.5)) {
            val ring = listOf(wrap(west) to 10.0, wrap(west + 1.0) to 10.0,
                wrap(west + 1.0) to 11.0, wrap(west) to 11.0)
            // Orientation and the ring's starting vertex cannot change containment.
            for (start in ring.indices) for (reversed in listOf(false, true)) {
                val vertices = (ring.drop(start) + ring.take(start)).let { if (reversed) it.reversed() else it }
                val coordinates = JSONArray().apply { (vertices + vertices.first()).forEach { (lon, lat) ->
                    put(JSONArray().put(lon).put(lat))
                } }
                val polygon = JSONObject().put("type", "Polygon")
                    .put("coordinates", JSONArray().put(coordinates))
                assertTrue(PlaceGeometry.contains(polygon, 10.5, wrap(west + 0.5)))
                assertFalse("Opposite longitude must remain outside: $west/$start/$reversed",
                    PlaceGeometry.contains(polygon, 10.5, wrap(west + 180.5)))
            }
        }
    }

    @Test fun municipalityBoundaryEdgeIsRecognized() {
        val geometry = JSONObject(
            """{"type":"Polygon","coordinates":[[
                [-75.0,40.0],[-74.0,40.0],[-74.0,41.0],[-75.0,41.0],[-75.0,40.0]
            ]]}"""
        )
        assertTrue(PlaceGeometry.contains(geometry, 40.0, -75.0))
        assertTrue(PlaceGeometry.contains(geometry, 40.5, -74.5))
        assertFalse(PlaceGeometry.contains(geometry, 39.99, -74.5))
    }

    @Test fun wrongNamedBoundaryCannotUnlockTownEvenIfItContainsGps() {
        val place = PlaceDiscovery(
            PlaceKind.TOWN, "us|new jersey|pine hill", "Pine Hill",
            "New Jersey", "United States", 1L, 39.79, -75.03
        )
        val body = """
            [{
              "name":"Gloucester County",
              "category":"boundary",
              "type":"administrative",
              "addresstype":"administrative",
              "address":{"locality":"Pine Hill","state":"New Jersey","country":"United States"},
              "lat":"39.79","lon":"-75.03",
              "geojson":{"type":"Polygon","coordinates":[[
                [-75.1,39.7],[-75.0,39.7],[-75.0,39.9],
                [-75.1,39.9],[-75.1,39.7]
              ]]}
            }]
        """.trimIndent()
        assertNull(PlaceOverlayClient.parseResponse(place, body))
    }

    @Test fun namedBoroughBoundaryContainsActualTownGps() {
        val place = PlaceDiscovery(
            PlaceKind.TOWN, "us|new jersey|pine hill", "Pine Hill",
            "New Jersey", "United States", 1L, 39.79, -75.03
        )
        val body = """
            [{
              "name":"Borough of Pine Hill",
              "category":"boundary",
              "type":"administrative",
              "addresstype":"administrative",
              "address":{"town":"Pine Hill","state":"New Jersey","country":"United States"},
              "lat":"39.79","lon":"-75.03",
              "geojson":{"type":"Polygon","coordinates":[[
                [-75.1,39.7],[-75.0,39.7],[-75.0,39.9],
                [-75.1,39.9],[-75.1,39.7]
              ]]}
            }]
        """.trimIndent()
        assertNotNull(PlaceOverlayClient.parseResponse(place, body))
    }

    @Test fun legacyOverlayCacheNeverAuthorizesNewTownRewards() {
        val context = RuntimeEnvironment.getApplication()
        val place = PlaceDiscovery(
            PlaceKind.TOWN, "us|new jersey|legacy", "Legacy Borough",
            "New Jersey", "United States", 1L, 39.5, -75.5
        )
        val polygon = PlaceOverlayData(
            place.key, place.displayName, place.kind, null, 1.0,
            """{"type":"Polygon","coordinates":[[
                [-75.6,39.4],[-75.4,39.4],[-75.4,39.6],[-75.6,39.6],[-75.6,39.4]
            ]]}"""
        )
        PlaceOverlayCache.clear(context)
        try {
            assertTrue(PlaceOverlayCache.write(context, place, polygon))
            assertNotNull(PlaceOverlayCache.read(context, place).data)
            assertNull(PlaceOverlayCache.readVerifiedForDiscovery(context, place))
            assertTrue(PlaceOverlayCache.write(
                context, place, polygon, verifiedForDiscovery = true
            ))
            assertNotNull(PlaceOverlayCache.readVerifiedForDiscovery(context, place))
        } finally {
            PlaceOverlayCache.clear(context)
        }
    }

    @Test fun townshipBoundaryNeverMatchesAdjacentCityWithSameBaseName() {
        val place = PlaceDiscovery(
            PlaceKind.TOWN, "us|new jersey|gloucester township",
            "Gloucester Township", "New Jersey", "United States",
            1L, 39.86, -75.10
        )
        val wrongCity = """
            [{
              "name":"Gloucester City",
              "category":"boundary","type":"administrative",
              "addresstype":"administrative",
              "address":{"city":"Gloucester City","state":"New Jersey","country":"United States"},
              "lat":"39.86","lon":"-75.10",
              "geojson":{"type":"Polygon","coordinates":[[
                [-75.2,39.8],[-75.0,39.8],[-75.0,39.9],[-75.2,39.9],[-75.2,39.8]
              ]]}
            }]
        """.trimIndent()
        assertNull(PlaceOverlayClient.parseResponse(place, wrongCity))
        assertNull(PlaceOverlayClient.parseResponse(place, wrongCity, requireContainment = false))
    }

    @Test fun townCannotUseCountyAdministrativeBoundary() {
        val place = PlaceDiscovery(
            PlaceKind.TOWN, "us|new jersey|gloucester",
            "Gloucester", "New Jersey", "United States",
            1L, 39.86, -75.10
        )
        val county = """
            [{
              "name":"Gloucester",
              "category":"boundary","type":"administrative",
              "addresstype":"administrative",
              "extratags":{"admin_level":"6"},
              "address":{"county":"Gloucester","state":"New Jersey","country":"United States"},
              "lat":"39.86","lon":"-75.10",
              "geojson":{"type":"Polygon","coordinates":[[
                [-75.2,39.8],[-75.0,39.8],[-75.0,39.9],[-75.2,39.9],[-75.2,39.8]
              ]]}
            }]
        """.trimIndent()
        assertNull(PlaceOverlayClient.parseResponse(place, county))
    }

    @Test fun boroughPrefixAndSuffixRemainEquivalent() {
        val place = PlaceDiscovery(
            PlaceKind.TOWN, "us|new jersey|pine hill borough",
            "Pine Hill Borough", "New Jersey", "United States",
            1L, 39.79, -75.03
        )
        val borough = """
            [{
              "name":"Borough of Pine Hill",
              "category":"boundary","type":"administrative",
              "addresstype":"administrative",
              "address":{"town":"Pine Hill","state":"New Jersey","country":"United States"},
              "lat":"39.79","lon":"-75.03",
              "geojson":{"type":"Polygon","coordinates":[[
                [-75.1,39.7],[-75.0,39.7],[-75.0,39.9],
                [-75.1,39.9],[-75.1,39.7]
              ]]}
            }]
        """.trimIndent()
        assertNotNull(PlaceOverlayClient.parseResponse(place, borough))
    }

    @Test fun redundantCityOfGloucesterCityNameStillMatchesGloucesterCity() {
        val place = PlaceDiscovery(
            PlaceKind.TOWN, "us|new jersey|gloucester city",
            "Gloucester City", "New Jersey", "United States",
            1L, 39.89, -75.11
        )
        val polygon = """
            [{
              "name":"City of Gloucester City",
              "category":"boundary","type":"administrative",
              "addresstype":"administrative",
              "address":{"city":"Gloucester City","state":"New Jersey","country":"United States"},
              "lat":"39.89","lon":"-75.11",
              "geojson":{"type":"Polygon","coordinates":[[
                [-75.2,39.8],[-75.0,39.8],[-75.0,39.9],[-75.2,39.9],[-75.2,39.8]
              ]]}
            }]
        """.trimIndent()
        assertNotNull(PlaceOverlayClient.parseResponse(place, polygon))
    }

    @Test fun localizedMunicipalNamesRequireEnglishAliasAndMatchingCountryCode() {
        val town = PlaceDiscovery(PlaceKind.TOWN, "de|bavaria|munich", "Munich",
            "Bavaria", "Germany", 1L, 48.137, 11.575)
        fun result(alias: String, countryCode: String) = """
          [{"name":"München","category":"boundary","type":"administrative",
            "osm_type":"relation","addresstype":"city",
            "lat":"48.137","lon":"11.575",
            "extratags":{"admin_level":"8"},
            "namedetails":{"name:en":"$alias"},
            "address":{"city":"München","state":"Bavaria",
                "country":"Deutschland","country_code":"$countryCode"},
            "geojson":{"type":"Polygon","coordinates":[[
                [11.56,48.12],[11.59,48.12],[11.59,48.15],
                [11.56,48.15],[11.56,48.12]
            ]]}}]
        """.trimIndent()
        assertNotNull(PlaceOverlayClient.parseResponse(town, result("Munich", "de")))
        assertNull(PlaceOverlayClient.parseResponse(town, result("Berlin", "de")))
        assertNull(PlaceOverlayClient.parseResponse(town, result("Munich", "fr")))
        assertNull(PlaceOverlayClient.parseResponse(town.copy(latitude = 49.0),
            result("Munich", "de")))
    }

    @Test fun worldwideTownOverlayAcceptsOnlyAppropriateAdministrativeLevel() {
        fun town(name: String, country: String) = PlaceDiscovery(
            PlaceKind.TOWN, "global|state|${name.lowercase()}", name,
            "Region", country, 1L, 21.0, -101.0
        )
        fun record(name: String, level: Int, relation: String = "relation") = """
            [{
              "name":"$name","category":"boundary","type":"administrative",
              "osm_type":"$relation",
              "addresstype":"administrative",
              "extratags":{"admin_level":"$level"},
              "address":{"state":"Region","country":"Mexico","town":"$name"},
              "lat":"21.0","lon":"-101.0",
              "geojson":{"type":"Polygon","coordinates":[[
                [-101.5,20.5],[-100.5,20.5],[-100.5,21.5],
                [-101.5,21.5],[-101.5,20.5]
              ]]}
            }]
        """.trimIndent()
        assertNotNull(PlaceOverlayClient.parseResponse(town("Guanajuato", "Mexico"),
            record("Guanajuato", 6)))
        assertNull(PlaceOverlayClient.parseResponse(town("Guanajuato", "Mexico"),
            record("Guanajuato", 4)))
        assertNull(PlaceOverlayClient.parseResponse(town("Guanajuato", "Mexico"),
            record("Guanajuato", 8, "node")))
    }

    @Test fun worldwideOsmDoesNotAcceptPostalNeighborhoodAsMunicipalBoundary() {
        val place = PlaceDiscovery(
            PlaceKind.TOWN, "gb|england|sample", "Sample",
            "England", "United Kingdom", 1L, 52.0, -1.0
        )
        val body = """
            [{"name":"Sample", "category":"place", "type":"neighbourhood",
              "addresstype":"locality","address":{"country":"United Kingdom"},
              "geojson":{"type":"Polygon","coordinates":[[
                [-1.5,51.5],[-0.5,51.5],[-0.5,52.5],
                [-1.5,52.5],[-1.5,51.5]
              ]]}}]
        """.trimIndent()
        assertNull(PlaceOverlayClient.parseResponse(place, body))
    }

}
