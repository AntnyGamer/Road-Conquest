package com.roadconquest.app

import android.Manifest
import android.graphics.Color
import android.os.Build
import android.os.SystemClock
import android.view.TextureView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.roadconquest.app.data.PlaceKind
import com.roadconquest.app.map.MapRenderer
import com.roadconquest.app.map.PlaceOverlayData
import com.roadconquest.app.map.PlaceOverlayMode
import com.roadconquest.app.map.TownOverlayPartition
import com.roadconquest.app.util.Prefs
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.sources.GeoJsonSource
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class TownBorderRenderingDeviceTest {
    @org.junit.Before fun skipFirstUseTour() {
        Prefs.setTourHandled(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext, true)
    }

    @get:Rule val permissions: GrantPermissionRule = GrantPermissionRule.grant(*buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray())

    @Test fun denseSharedSurveyEdgeRendersWithoutGapsAtMultipleZooms() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        Prefs.setManualOnly(app, true)
        Prefs.setAccountPromptShown(app, true)
        Prefs.setBackgroundPromptShown(app, true)
        Prefs.setPlaceOverlayMode(app, PlaceOverlayMode.NONE)
        Prefs.setFogEnabled(app, false)
        fun town(west: Boolean): PlaceOverlayData {
            val edge = JSONArray()
            for (i in 0..1000) edge.put(JSONArray().put(-74.0).put(40.0 + i * 0.0001))
            val outer = if (west) -74.1 else -73.9
            edge.put(JSONArray().put(outer).put(40.1))
            edge.put(JSONArray().put(outer).put(40.0))
            edge.put(edge.getJSONArray(0))
            return PlaceOverlayData("$west", "$west", PlaceKind.TOWN, null, 1.0,
                JSONObject().put("type", "Polygon").put("coordinates", JSONArray().put(edge)).toString())
        }
        val borders = TownOverlayPartition.authoritativeOutlines(listOf(town(true), town(false)))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var map: MapLibreMap
            lateinit var view: MapView
            val initialReady = CountDownLatch(1)
            val ready = CountDownLatch(1)
            scenario.onActivity { activity ->
                MainActivity::class.java.getDeclaredMethod("stopPreviewLocation")
                    .apply { isAccessible = true }.invoke(activity)
                view = activity.findViewById(R.id.mapView)
                view.getMapAsync { loaded ->
                    map = loaded
                    map.getStyle { initialReady.countDown() }
                }
            }
            assertTrue(initialReady.await(30, TimeUnit.SECONDS))
            scenario.onActivity { activity ->
                val renderer = MainActivity::class.java.getDeclaredField("renderer")
                    .apply { isAccessible = true }.get(activity) as MapRenderer
                renderer.clearCurrentLocation()
                map.setStyle(Style.Builder().fromJson(
                        """{"version":8,"sources":{},"layers":[{"id":"white","type":"background","paint":{"background-color":"#ffffff"}}]}"""
                )) { style ->
                    MapRenderer::class.java.getDeclaredMethod("installPlaceOverlayLayers", Style::class.java)
                        .apply { isAccessible = true }.invoke(renderer, style)
                    (style.getSource("roadconquest-town-overlay-boundaries") as GeoJsonSource).setGeoJson(borders)
                    ready.countDown()
                }
            }
            assertTrue(ready.await(30, TimeUnit.SECONDS))
            for (zoom in listOf(8.0, 10.0, 12.0)) {
                scenario.onActivity {
                    map.moveCamera(CameraUpdateFactory.newCameraPosition(
                        org.maplibre.android.camera.CameraPosition.Builder()
                            .target(LatLng(40.05, -74.0)).zoom(zoom).bearing(0.0).tilt(0.0).build()))
                }
                val deadline = SystemClock.elapsedRealtime() + 15_000L
                var continuous = false
                while (!continuous && SystemClock.elapsedRealtime() < deadline) {
                    scenario.onActivity {
                        if (kotlin.math.abs(map.cameraPosition.zoom - zoom) < 0.01) {
                            val north = map.projection.toScreenLocation(LatLng(40.09, -74.0))
                            val south = map.projection.toScreenLocation(LatLng(40.01, -74.0))
                            (view.renderView as TextureView).bitmap?.let { bitmap ->
                                val top = maxOf(20, north.y.roundToInt())
                                val bottom = minOf(bitmap.height - 21, south.y.roundToInt())
                                val x = north.x.roundToInt().coerceIn(2, bitmap.width - 3)
                                continuous = bottom - top >= 10 && (top..bottom).all { y ->
                                    (x - 2..x + 2).any { column ->
                                        val pixel = bitmap.getPixel(column, y)
                                        Color.red(pixel) < 130 && Color.green(pixel) > Color.red(pixel) * 1.2
                                    }
                                }
                                bitmap.recycle()
                            }
                        }
                    }
                    if (!continuous) SystemClock.sleep(100)
                }
                assertTrue("Shared town border must stay continuous at zoom $zoom", continuous)
            }
        }
    }
}
