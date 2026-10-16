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
    @Test fun backupAndLocalStylesDoNotDependOnTheFailedProvider() {
        for (mode in MapMode.entries) {
            val backup = BaseMapProviders.fallbackStyle(mode, "https://maps.example")
            assertTrue(JSONObject(backup).getJSONObject("sources").getJSONObject("backup-map")
                .getJSONArray("tiles").getString(0).startsWith("https://maps.example/v1/tile/"))
            assertFalse(backup.contains("openfreemap")); assertFalse(backup.contains("glyphs"))
            assertTrue(backup.contains("Geoapify"))
            val local = JSONObject(BaseMapProviders.fallbackStyle(mode, null))
            assertEquals(0, local.getJSONObject("sources").length())
            assertEquals("background", local.getJSONArray("layers").getJSONObject(0).getString("type"))
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
}
