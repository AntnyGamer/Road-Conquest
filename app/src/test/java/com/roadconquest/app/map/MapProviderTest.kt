package com.roadconquest.app.map

import android.app.Application
import com.roadconquest.app.data.PlaceDiscovery
import com.roadconquest.app.data.PlaceKind
import com.roadconquest.app.services.ProviderHealth
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37], manifest = Config.NONE, application = Application::class)
class MapProviderTest {
    @Test fun cooldownHonorsDatesSecondsAndPreferredProviderRecovery() {
        var now = 1_000_000L
        val health = ProviderHealth { now }
        health.failed("main", 429, "120")
        assertEquals(120_000L, health.remaining("main"))
        assertEquals(0L, health.remaining("backup"))
        now += 120_001L
        assertEquals(0L, health.remaining("main"))
        health.failed("main", 403)
        assertEquals(86_400_000L, health.remaining("main"))
    }
    @Test fun numericAndHttpDateRetryAfterHeadersKeepIdenticalCooldowns() {
        val now = 1_000_000L
        val health = ProviderHealth { now }
        val date = java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME.format(
            java.time.Instant.ofEpochMilli(now + 180_000L).atZone(java.time.ZoneOffset.UTC)
        )
        health.failed("seconds", 429, "120")
        health.failed("date", 429, date)
        health.failed("capped", 429, "999999")
        health.failed("invalid", 429, "not-a-date")
        assertEquals(120_000L, health.remaining("seconds"))
        assertEquals(180_000L, health.remaining("date"))
        assertEquals(86_400_000L, health.remaining("capped"))
        assertEquals(60_000L, health.remaining("invalid"))
    }

    @Test fun optionalOverviewTilesAndLabelsCannotDisableTheEntireVectorMap() {
        assertTrue(BaseMapProviders.isMapCriticalRequest("openfreemap", "/planet"))
        assertTrue(BaseMapProviders.isMapCriticalRequest("openfreemap", "/planet/release/2/1/1.pbf"))
        assertFalse(BaseMapProviders.isMapCriticalRequest("openfreemap", "/natural_earth/ne2sr/2/1/1.png"))
        assertFalse(BaseMapProviders.isMapCriticalRequest("openfreemap", "/fonts/Roboto/0-255.pbf"))
        assertFalse(BaseMapProviders.isMapCriticalRequest("openfreemap", "/sprites/ofm/sprite.json"))
        assertTrue(BaseMapProviders.isMapCriticalRequest("geoapify-tiles", "/v1/tile/osm-liberty/2/1/1.png"))
        assertFalse(BaseMapProviders.isMapCriticalRequest("geoapify-tiles", "/v1/config"))
        assertTrue(BaseMapProviders.isMapCriticalRequest("esri-imagery", "/ArcGIS/rest/services/World_Imagery/MapServer/tile/2/1/1"))
    }

    @Test fun isolatedCoreTileErrorsKeepPrimaryUntilRepeatedFailures() {
        var now = 1_000_000L
        val gate = MapFailureGate { now }
        assertFalse(gate.failed("openfreemap", 503))
        assertFalse(gate.failed("openfreemap"))
        gate.succeeded("openfreemap")
        assertFalse(gate.failed("openfreemap", 502))
        now += 16_000L // A separate burst cannot complete an older outage.
        assertFalse(gate.failed("openfreemap", 503))
        assertFalse(gate.failed("openfreemap"))
        assertTrue(gate.failed("openfreemap", 503))
        gate.succeeded("openfreemap")
        assertFalse(gate.failed("openfreemap"))
        assertTrue("Rate limits require immediate failover", gate.failed("openfreemap", 429))
        assertTrue("Authorization failures require immediate failover", gate.failed("geoapify-tiles", 403))
        assertFalse("One backup timeout should not blank the base map", gate.failed("geoapify-tiles"))
    }

    @Test fun configuredGatewayHostHandlesInvalidUrlsWithoutBreakingLocalMaps() {
        assertEquals("maps.example", BaseMapProviders.configuredGatewayHost("https://maps.example:443/"))
        assertEquals("maps.example", BaseMapProviders.configuredGatewayHost("HTTPS://maps.example/"))
        assertEquals("maps.example", BaseMapProviders.configuredGatewayHost("HTTPS://MAPS.EXAMPLE/"))
        assertEquals("10.0.2.2", BaseMapProviders.configuredGatewayHost("http://10.0.2.2:8787"))
        assertNull(BaseMapProviders.configuredGatewayHost(""))
        assertNull(BaseMapProviders.configuredGatewayHost("not a valid url"))
        assertNull(BaseMapProviders.configuredGatewayHost("https://bad host/"))
        assertNull(BaseMapProviders.configuredGatewayHost("ftp://maps.example"))
        assertNull(BaseMapProviders.configuredGatewayHost("https://user:password@maps.example"))
        assertNull(BaseMapProviders.configuredGatewayHost("https://maps.example?apiKey=secret"))
        assertNull(BaseMapProviders.configuredGatewayHost("https://maps.example#fragment"))
    }

    @Test fun backupAndLocalStylesDoNotDependOnTheFailedProvider() {
        for (mode in MapMode.entries) {
            val backup = BaseMapProviders.fallbackStyle(mode, "https://maps.example")
            assertTrue(JSONObject(backup).getJSONObject("sources").getJSONObject("backup-map")
                .getJSONArray("tiles").getString(0).startsWith("https://maps.example/v1/tile/"))
            assertFalse(backup.contains("openfreemap")); assertFalse(backup.contains("glyphs"))
            assertTrue(backup.contains("Powered by Geoapify"))
            assertTrue(backup.contains("© OpenStreetMap contributors"))
            assertTrue(backup.contains("© OpenMapTiles"))
            assertFalse("The only prominent link belongs on the home screen",
                backup.contains("locationiq.com"))
            val local = JSONObject(BaseMapProviders.fallbackStyle(mode, null))
            assertEquals(0, local.getJSONObject("sources").length())
            assertEquals("background", local.getJSONArray("layers").getJSONObject(0).getString("type"))
        }
    }
    @Test fun preferredMapDoesNotDuplicateTheVisibleLocationIqCredit() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        for (mode in MapMode.entries) {
            val style = BaseMapProviders.style(app, mode, "openfreemap")
            assertFalse(style.contains("locationiq.com"))
            assertTrue(style.contains("OpenStreetMap"))
        }
    }

    @Test fun boundaryBackupRequiresUniqueContainedExactRegionAndNeverAwardsTowns() {
        val data = JSONObject("""{"type":"FeatureCollection","features":[{"properties":{"shapeName":"Test Region"},"geometry":{"type":"Polygon","coordinates":[[[0,0],[2,0],[2,2],[0,2],[0,0]]]}}]}""")
        val state = PlaceDiscovery(PlaceKind.STATE,"gb|test region","Test Region","","United Kingdom",1L,1.0,1.0)
        assertNotNull(GeoBoundariesClient.parse(state, data, true))
        assertNull(GeoBoundariesClient.parse(state.copy(displayName="Wrong Region"), data, true))
        assertNull(GeoBoundariesClient.parse(state.copy(latitude=5.0), data, true))
        assertNull(GeoBoundariesClient.parse(state.copy(kind=PlaceKind.TOWN), data, true))
        data.getJSONArray("features").put(data.getJSONArray("features").getJSONObject(0))
        assertNull(GeoBoundariesClient.parse(state, data, true))
    }
    @Test fun boundaryDownloadCannotRedirectToArbitraryHostOrDataset() {
        assertTrue(GeoBoundariesClient.validDownload("https://github.com/wmgeolab/geoBoundaries/raw/abc/releaseData/gbOpen/USA/ADM1/file.geojson", "USA", "ADM1"))
        assertFalse(GeoBoundariesClient.validDownload("https://evil.example/wmgeolab/geoBoundaries/raw/abc/releaseData/gbOpen/USA/ADM1/file.geojson", "USA", "ADM1"))
        assertFalse(GeoBoundariesClient.validDownload("https://github.com/wmgeolab/geoBoundaries/raw/abc/releaseData/gbOpen/FRA/ADM1/file.geojson", "USA", "ADM1"))
    }
    @Test fun townsNeverCallBackupNetwork() {
        val client = GeoBoundariesClient { _, _ -> throw AssertionError("Municipal backup must not be queried") }
        assertNull(client.fetch(PlaceDiscovery(PlaceKind.TOWN,"us|nj|town","Town","New Jersey","United States",1L,40.0,-74.0),true))
    }

    @Test fun refreshingOneBoundaryDatasetKeepsTheOtherStillValidDataset() {
        var calls = 0
        val geometry = JSONObject("""{"type":"FeatureCollection","features":[{"properties":{"shapeName":"Test Region"},"geometry":{"type":"Polygon","coordinates":[[[0,0],[2,0],[2,2],[0,2],[0,0]]]}}]}""")
        val client = GeoBoundariesClient { url, _ ->
            calls++
            if (url.endsWith(".geojson")) geometry else {
                val country = if ("USA" in url) "USA" else "CAN"
                JSONObject().put("boundaryISO", country).put("boundaryType", "ADM1")
                    .put("boundaryLicense", "public domain").put("simplifiedGeometryGeoJSON",
                        "https://github.com/wmgeolab/geoBoundaries/raw/abc/releaseData/gbOpen/$country/ADM1/file.geojson")
            }
        }
        val first = PlaceDiscovery(PlaceKind.STATE, "us|test", "Test Region", "", "United States", 1L, 1.0, 1.0)
        val second = first.copy(key = "ca|test", countryName = "Canada")
        assertNotNull(client.fetch(first, true))
        assertNotNull(client.fetch(second, true))
        @Suppress("UNCHECKED_CAST")
        val cache = GeoBoundariesClient::class.java.getDeclaredField("datasets").apply { isAccessible = true }
            .get(client) as MutableMap<String, Pair<Long, JSONObject>>
        cache["CAN/ADM1"] = System.currentTimeMillis() - 16 * 60_000L to geometry
        assertNotNull(client.fetch(second, true))
        assertNotNull(client.fetch(first, true))
        assertEquals("Only the expired dataset needs metadata and geometry downloads", 6, calls)
        assertEquals(2, cache.size)
        assertNull(client.fetch(first.copy(latitude = 5.0), true))
        assertEquals(6, calls)
    }
}
