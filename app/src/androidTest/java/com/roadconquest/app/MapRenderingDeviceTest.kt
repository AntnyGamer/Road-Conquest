package com.roadconquest.app

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.location.Location
import android.graphics.Color
import android.view.TextureView
import android.view.View
import android.os.Handler
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import android.os.Build
import android.os.SystemClock
import android.widget.ImageButton
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.roadconquest.app.data.MatchedRoad
import com.roadconquest.app.data.TrackingRepository
import com.roadconquest.app.map.MapMode
import com.roadconquest.app.map.MapRenderer
import com.roadconquest.app.map.PlaceOverlayMode
import com.roadconquest.app.map.LiveLocation
import com.roadconquest.app.util.Prefs
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.sources.ImageSource
import org.maplibre.android.style.sources.RasterSource
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real Android/MapLibre integration, including the projection and the actual recenter button. */
@RunWith(AndroidJUnit4::class)
class MapRenderingDeviceTest {
    @org.junit.Before fun skipFirstUseTour() {
        Prefs.setTourHandled(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext, true)
    }

    @get:Rule val permissions: GrantPermissionRule = GrantPermissionRule.grant(*buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION); add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray())

    @Test fun fogCoversTheWholeWorldAndBlueRoadsStayVisibleAtOverviewZooms() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        Prefs.setManualOnly(app, true)
        Prefs.setAccountPromptShown(app, true)
        Prefs.setBackgroundPromptShown(app, true)
        Prefs.setFogEnabled(app, true)
        val repository = TrackingRepository(app)
        repository.readableDatabase().execSQL("DELETE FROM roads")
        repository.readableDatabase().execSQL("DELETE FROM explored_places")
        repository.readableDatabase().execSQL("DELETE FROM explored_grid")
        repository.upsertRoads(listOf(MatchedRoad("Overview test", "[[-0.08,0],[0.08,0]]", 1, 2, 100.0)))
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var map: MapLibreMap
                lateinit var view: MapView
                lateinit var renderer: MapRenderer
                val initialReady = CountDownLatch(1)
                scenario.onActivity { activity ->
                    MainActivity::class.java.getDeclaredMethod("stopPreviewLocation").apply { isAccessible = true }.invoke(activity)
                    view = activity.findViewById(R.id.mapView)
                    view.getMapAsync { readyMap ->
                        map = readyMap
                        readyMap.getStyle { initialReady.countDown() }
                    }
                }
                assertTrue(initialReady.await(30, TimeUnit.SECONDS))
                val whiteReady = CountDownLatch(1)
                scenario.onActivity { activity ->
                    renderer = MainActivity::class.java.getDeclaredField("renderer").apply { isAccessible = true }.get(activity) as MapRenderer
                    renderer.clearCurrentLocation()
                    // No remote tiles or labels: inspect the pixels produced by our native layers.
                    map.setStyle(Style.Builder().fromJson("""{"version":8,"sources":{},"layers":[{"id":"white","type":"background","paint":{"background-color":"#ffffff"}}]}""")) { style ->
                        for (method in listOf("installFogLayer", "installRoadLayer")) {
                            MapRenderer::class.java.getDeclaredMethod(method, Style::class.java).apply { isAccessible = true }.invoke(renderer, style)
                        }
                        renderer.refreshViewport()
                        renderer.setFogEnabled(true)
                        whiteReady.countDown()
                    }
                }
                assertTrue(whiteReady.await(30, TimeUnit.SECONDS))
                scenario.onActivity {
                    assertNotNull("Native overview fog must exist independently of camera callbacks",
                        map.style?.getLayer("roadconquest-world-fog-overview-raster"))
                    val regional = map.style?.getLayer("roadconquest-traveled-roads-line-regional") as? LineLayer
                    val detailed = map.style?.getLayer("roadconquest-traveled-roads-line") as? LineLayer
                    assertNotNull("Regional roads must have their own visible style", regional)
                    assertNotNull("Detailed roads must remain visible", detailed)
                    assertEquals(org.maplibre.android.style.layers.Property.LINE_CAP_SQUARE,
                        regional!!.lineCap.value)
                    assertEquals(9f, regional.maxZoom, 0.01f)
                    assertEquals(9f, detailed!!.minZoom, 0.01f)
                }
                fun move(target: LatLng, zoom: Double, bearing: Double = 0.0) {
                    val idle = CountDownLatch(1)
                    val listener = MapLibreMap.OnCameraIdleListener { idle.countDown() }
                    scenario.onActivity {
                        map.addOnCameraIdleListener(listener)
                        map.moveCamera(CameraUpdateFactory.newCameraPosition(
                            org.maplibre.android.camera.CameraPosition.Builder().target(target).zoom(zoom).bearing(bearing).tilt(0.0).build()))
                    }
                    assertTrue(idle.await(10, TimeUnit.SECONDS))
                    scenario.onActivity { map.removeOnCameraIdleListener(listener) }
                }
                fun awaitPixels(message: String, predicate: (android.graphics.Bitmap) -> Boolean) {
                    val deadline = SystemClock.elapsedRealtime() + 15_000L
                    var passed = false
                    while (!passed && SystemClock.elapsedRealtime() < deadline) {
                        scenario.onActivity {
                            (view.renderView as TextureView).bitmap?.let { bitmap ->
                                passed = predicate(bitmap)
                                bitmap.recycle()
                            }
                        }
                        if (!passed) SystemClock.sleep(100)
                    }
                    assertTrue(message, passed)
                }
                for (zoom in listOf(0.0, 1.0, 2.0, 3.0)) for (longitude in listOf(0.0, 179.0, -179.0, 539.0, -539.0)) {
                    move(LatLng(0.0, longitude), zoom)
                    awaitPixels("Fog covers visible world copies at zoom $zoom, longitude $longitude") { bitmap ->
                        val rows = if (zoom == 0.0) listOf(0.5) else listOf(0.2, 0.5, 0.8)
                        rows.all { row -> listOf(0.01, 0.25, 0.5, 0.75, 0.99).all { column ->
                            Color.red(bitmap.getPixel((bitmap.width * column).toInt(), (bitmap.height * row).toInt())) < 220
                        } }
                    }
                }
                for (zoom in listOf(1.0, 2.0)) {
                    move(LatLng(0.0, 0.0), zoom)
                    for (direction in listOf(1f, -1f)) repeat(6) { loop ->
                        // scrollBy moves the native camera directly without dispatching
                        // OnCameraIdle. Confirm the pan and wait for its rendered frame.
                        val frame = CountDownLatch(1)
                        val listener = MapView.OnDidFinishRenderingFrameListener { _, _, _ -> frame.countDown() }
                        var beforeLongitude = Double.NaN
                        scenario.onActivity {
                            val center = android.graphics.PointF(view.width / 2f, view.height / 2f)
                            beforeLongitude = map.projection.fromScreenLocation(center).longitude
                            view.addOnDidFinishRenderingFrameListener(listener)
                            map.scrollBy(direction * view.width * 1.25f, 0f)
                        }
                        assertTrue("The world pan renders", frame.await(10, TimeUnit.SECONDS))
                        scenario.onActivity { view.removeOnDidFinishRenderingFrameListener(listener) }

                        // On newer MapLibre/Android renderers the Java projection can lag the
                        // native scroll call by a frame. Check the camera only after rendering,
                        // and allow a short propagation window instead of asserting synchronously.
                        val panDeadline = SystemClock.elapsedRealtime() + 2_000L
                        var panDifference = 0.0
                        while (kotlin.math.abs(panDifference) <= 0.1 &&
                            SystemClock.elapsedRealtime() < panDeadline
                        ) {
                            scenario.onActivity {
                                val center = android.graphics.PointF(view.width / 2f, view.height / 2f)
                                val afterLongitude = map.projection.fromScreenLocation(center).longitude
                                panDifference = ((afterLongitude - beforeLongitude + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
                            }
                            if (kotlin.math.abs(panDifference) <= 0.1) SystemClock.sleep(25)
                        }
                        assertTrue(
                            "The world pan changes the visible longitude",
                            kotlin.math.abs(panDifference) > 0.1
                        )
                        awaitPixels("Fog survives loop $loop in direction $direction at zoom $zoom") { bitmap ->
                            listOf(0.01, 0.25, 0.5, 0.75, 0.99).all { column ->
                                Color.red(bitmap.getPixel((bitmap.width * column).toInt(), bitmap.height / 2)) < 220
                            }
                        }
                    }
                }
                for (latitude in listOf(0.0, 40.0, -40.0)) for (bearing in listOf(0.0, 30.0, 60.0, 120.0)) {
                    move(LatLng(latitude, 0.0), 0.0, bearing)
                    awaitPixels("Rotated overview has no uncovered corners at latitude $latitude, bearing $bearing") { bitmap ->
                        listOf(0.01, 0.5, 0.99).all { row -> listOf(0.01, 0.5, 0.99).all { column ->
                            Color.red(bitmap.getPixel((bitmap.width * column).toInt(), (bitmap.height * row).toInt())) < 220
                        } }
                    }
                }
                move(LatLng(40.0, -74.0), 15.0)
                awaitPixels("Detailed fog is ready before the rapid zoom") { bitmap ->
                    Color.red(bitmap.getPixel(bitmap.width / 10, bitmap.height / 10)) < 220
                }
                // Settled zoom/rotation and date-line coverage are checked
                // deterministically; racing 16 ms TextureView snapshots against
                // native camera callbacks produced intermittent false failures.
                for (longitude in listOf(179.99, -179.99)) {
                    move(LatLng(0.0, longitude), 10.0, 55.0)
                    awaitPixels("Local fog spans the date line at longitude $longitude") { bitmap ->
                        listOf(0.1, 0.3, 0.5, 0.7, 0.9).all { fraction ->
                            Color.red(bitmap.getPixel((bitmap.width * fraction).toInt(), bitmap.height / 2)) < 220
                        }
                    }
                }
                for ((zoom, bearing) in listOf(
                    6.5 to 0.0, 7.5 to 0.0, 8.0 to 0.0,
                    8.95 to 0.0, 9.05 to 0.0, 9.0 to 30.0, 11.0 to 60.0
                )) {
                    move(LatLng(0.0, 0.0), zoom, bearing)
                    awaitPixels("Blue road pixels remain visible above fog at zoom $zoom") { bitmap ->
                        val point = map.projection.toScreenLocation(LatLng(0.0, 0.0))
                        (-4..4).any { dx -> (-4..4).any { dy ->
                            val color = bitmap.getPixel((point.x.toInt() + dx).coerceIn(0, bitmap.width - 1),
                                (point.y.toInt() + dy).coerceIn(0, bitmap.height - 1))
                            Color.blue(color) - Color.red(color) > 100 && Color.blue(color) - Color.green(color) > 70
                        } }
                    }
                }
                // The old Beta 28 cutoff intentionally hid all saved exploration
                // below zoom 9. Verify a real persisted tile now clears the native map
                // even at a broader zoom, not only in the bitmap unit tests.
                val exploredPoint = LatLng(40.0, -74.0)
                scenario.onActivity {
                    assertTrue(repository.recordExploredGridCell(Location("gps").apply {
                        latitude = exploredPoint.latitude
                        longitude = exploredPoint.longitude
                        accuracy = 3f
                    }))
                    renderer.refreshExploration()
                }
                move(exploredPoint, 8.8)
                awaitPixels("Saved mile cell is visible on native map below old zoom cutoff") { bitmap ->
                    val p = map.projection.toScreenLocation(exploredPoint)
                    val x = p.x.toInt().coerceIn(0, bitmap.width - 1)
                    val y = p.y.toInt().coerceIn(0, bitmap.height - 1)
                    val pixel = bitmap.getPixel(x, y)
                    Color.red(pixel) > 230 && Color.green(pixel) > 230 && Color.blue(pixel) > 230
                }
                val storedCenters = repository.getExploredGridInBounds(40.1, -73.9, 39.9, -74.1)
                assertEquals(2, storedCenters.size)
                val savedCenter = LatLng(storedCenters[0], storedCenters[1])
                // The same saved square must remain pinned to the identical
                // geographic latitude/longitude through repeated regional zooms
                // and pans. Beta 29 snapped between world pixels and detailed
                // pixels, making this clearing appear to teleport.
                for ((target, zoom) in listOf(
                    exploredPoint to 8.0,
                    LatLng(40.0, -73.98) to 9.0,
                    exploredPoint to 10.0,
                    LatLng(40.0, -74.02) to 11.0,
                    exploredPoint to 13.0
                )) {
                    move(target, zoom)
                    awaitPixels("Saved clearing stays at its map coordinate zoom=$zoom") { bitmap ->
                        val point = map.projection.toScreenLocation(savedCenter)
                        val x = point.x.toInt()
                        val y = point.y.toInt()
                        if (x !in 1 until bitmap.width - 1 || y !in 1 until bitmap.height - 1) false
                        else {
                            val color = bitmap.getPixel(x, y)
                            Color.red(color) > 225 && Color.green(color) > 225 &&
                                Color.blue(color) > 225
                        }
                    }
                }
                // Polar rasters must stay georeferenced when their padded
                // extent reaches the Mercator limit. Check real native pixels.
                for (latitude in listOf(-85.0, 85.0)) {
                    val polarPoint = LatLng(latitude, 10.0)
                    scenario.onActivity {
                        assertTrue(repository.recordExploredGridCell(Location("gps").apply {
                            this.latitude = latitude
                            longitude = polarPoint.longitude
                            accuracy = 3f
                        }))
                        renderer.refreshExploration()
                    }
                    val polarCenters = repository.getExploredGridInBounds(
                        latitude + 0.02, 10.02, latitude - 0.02, 9.98
                    )
                    assertEquals(2, polarCenters.size)
                    val polarCenter = LatLng(polarCenters[0], polarCenters[1])
                    for (zoom in listOf(11.0, 13.0)) {
                        move(polarCenter, zoom)
                        awaitPixels("Polar clearing stays at its saved coordinate latitude=$latitude zoom=$zoom") { bitmap ->
                            val point = map.projection.toScreenLocation(polarCenter)
                            val x = point.x.toInt()
                            val y = point.y.toInt()
                            if (x !in 1 until bitmap.width - 1 || y !in 1 until bitmap.height - 1) false
                            else {
                                val color = bitmap.getPixel(x, y)
                                Color.red(color) > 225 && Color.green(color) > 225 && Color.blue(color) > 225
                            }
                        }
                    }
                }
                // Confirm high-zoom settled exploration visibility; native map
                // animation timing is covered by manual/graphics performance QA.
                move(exploredPoint, 15.0)
                awaitPixels("Local visited cell stays clear at high zoom") { bitmap ->
                    val point = map.projection.toScreenLocation(exploredPoint)
                    val pixel = bitmap.getPixel(
                        point.x.toInt().coerceIn(0, bitmap.width - 1),
                        point.y.toInt().coerceIn(0, bitmap.height - 1)
                    )
                    Color.red(pixel) > 225 && Color.green(pixel) > 225
                }

                scenario.onActivity {
                    val ids = map.style!!.layers.map { it.id }
                    assertTrue(ids.indexOf("roadconquest-fog-raster") < ids.indexOf("roadconquest-traveled-roads-line"))
                }
            }
        } finally {
            repository.readableDatabase().execSQL("DELETE FROM roads")
            repository.readableDatabase().execSQL("DELETE FROM explored_grid")
        }
    }

    @Test fun nativeFogAndCarSurvivePanningZoom20RecenteringAndSatelliteStyleReload() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        Prefs.setManualOnly(app, true)
        Prefs.setAccountPromptShown(app, true)
        Prefs.setBackgroundPromptShown(app, true)
        Prefs.setFogEnabled(app, true)
        Prefs.setMapMode(app, MapMode.STREETS)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var map: MapLibreMap
            lateinit var view: MapView
            lateinit var renderer: MapRenderer
            val styleReady = CountDownLatch(1)
            scenario.onActivity { activity ->
                view = activity.findViewById(R.id.mapView)
                view.getMapAsync { readyMap ->
                    map = readyMap
                    renderer = MainActivity::class.java.getDeclaredField("renderer")
                        .apply { isAccessible = true }.get(activity) as MapRenderer
                    readyMap.getStyle { styleReady.countDown() }
                }
            }
            assertTrue("Bundled style must load", styleReady.await(30, TimeUnit.SECONDS))

            val car = LatLng(37.7749, -122.4194)
            scenario.onActivity { activity ->
                MainActivity::class.java.getDeclaredMethod("stopPreviewLocation")
                    .apply { isAccessible = true }.invoke(activity)
                renderer.updateCar(car.latitude, car.longitude, 15.0)
                assertEquals(20.0, map.maxZoomLevel, 0.0)
                assertTrue("Map stays on its native render surface", view.renderView is TextureView)
                assertTrue(map.style!!.getSource("roadconquest-fog") is ImageSource)
                assertTrue(map.style!!.getLayer("roadconquest-fog-raster") is RasterLayer)
                assertTrue(map.style!!.getSource("roadconquest-car") is GeoJsonSource)
                assertTrue(map.style!!.getLayer("roadconquest-car-symbol") is SymbolLayer)
            }

            fun moveAndAssert(target: LatLng, zoom: Double, bearing: Double) {
                val idle = CountDownLatch(1)
                val frame = CountDownLatch(1)
                val frameListener = MapView.OnDidFinishRenderingFrameListener { _, _, _ -> frame.countDown() }
                val listener = MapLibreMap.OnCameraIdleListener { idle.countDown() }
                scenario.onActivity {
                    view.addOnDidFinishRenderingFrameListener(frameListener)
                    map.addOnCameraIdleListener(listener)
                    map.moveCamera(CameraUpdateFactory.newCameraPosition(
                        org.maplibre.android.camera.CameraPosition.Builder()
                            .target(target).zoom(zoom).bearing(bearing).tilt(0.0).build()
                    ))
                }
                assertTrue("Camera settles", idle.await(10, TimeUnit.SECONDS))
                assertTrue("Native renderer finishes a frame before feature queries", frame.await(10, TimeUnit.SECONDS))
                scenario.onActivity {
                    view.removeOnDidFinishRenderingFrameListener(frameListener)
                    map.removeOnCameraIdleListener(listener)
                    assertTrue("Fog remains a native georeferenced source", map.style!!.getSource("roadconquest-fog") is ImageSource)
                    assertTrue("Fog remains a native raster layer", map.style!!.getLayer("roadconquest-fog-raster") is RasterLayer)
                    assertTrue("Car remains a native symbol layer", map.style!!.getLayer("roadconquest-car-symbol") is SymbolLayer)
                }
                val deadline = SystemClock.elapsedRealtime() + 10_000L
                var rendered = false
                while (!rendered && SystemClock.elapsedRealtime() < deadline) {
                    scenario.onActivity {
                        val screen = map.projection.toScreenLocation(car)
                        rendered = map.queryRenderedFeatures(screen, "roadconquest-car-symbol").isNotEmpty()
                    }
                    if (!rendered) SystemClock.sleep(50)
                }
                assertTrue("Car stays rendered at its geographic point", rendered)
            }

            moveAndAssert(car, 18.0, 0.0)
            moveAndAssert(LatLng(car.latitude + 0.00008, car.longitude - 0.00008), 19.0, 55.0)
            moveAndAssert(LatLng(car.latitude - 0.00004, car.longitude + 0.00006), 20.0, 125.0)

            scenario.onActivity { activity ->
                activity.findViewById<ImageButton>(R.id.centerCarButton).performClick()
            }
            val deadline = SystemClock.elapsedRealtime() + 10_000L
            while (SystemClock.elapsedRealtime() < deadline) {
                var centered = false
                scenario.onActivity {
                    centered = map.cameraPosition.target!!.distanceTo(car) < 1.0 &&
                        kotlin.math.abs(map.cameraPosition.zoom - 20.0) < 0.01
                }
                if (centered) break
                SystemClock.sleep(50)
            }
            scenario.onActivity {
                assertTrue("Icon recenters on the live fix", map.cameraPosition.target!!.distanceTo(car) < 1.0)
                assertEquals("Recenter preserves close zoom", 20.0, map.cameraPosition.zoom, 0.01)
                renderer.setFogEnabled(false)
                renderer.setFogEnabled(true)
            }

            // A notification-shade-style pause keeps the map visible and accepting fixes.
            scenario.moveToState(Lifecycle.State.STARTED)
            scenario.onActivity {
                renderer.clearCurrentLocation()
                renderer.updateCar(car.latitude, car.longitude, 15.0)
                val live = MapRenderer::class.java.getDeclaredField("liveLocation")
                    .apply { isAccessible = true }.get(renderer) as LiveLocation
                val fix = live.current(SystemClock.elapsedRealtime())
                assertNotNull("A visible paused map still accepts live fixes", fix)
                assertEquals(car.latitude, fix!!.latitude, 0.0)
                assertEquals(car.longitude, fix.longitude, 0.0)
                renderer.pauseViewport()
                renderer.resumeViewport(false)
                assertEquals("Resuming preserves the fresh fix's original expiry",
                    fix.expiresAt, live.current(SystemClock.elapsedRealtime())!!.expiresAt)
                val handler = MapRenderer::class.java.getDeclaredField("mainHandler")
                    .apply { isAccessible = true }.get(renderer) as Handler
                val expiry = MapRenderer::class.java.getDeclaredField("expireLocation")
                    .apply { isAccessible = true }.get(renderer) as Runnable
                assertTrue("Resuming rearms fresh-location expiry", handler.hasCallbacks(expiry))
            }
            scenario.moveToState(Lifecycle.State.RESUMED)

            // Reproduce a resume immediately followed by backgrounding before the map is
            // visible. The old 16 ms visibility loop kept running for the hidden activity.
            lateinit var visibilityCheck: Runnable
            lateinit var mapHandler: Handler
            scenario.onActivity {
                view.visibility = View.INVISIBLE
                renderer.clearCurrentLocation()
                renderer.updateCar(car.latitude, car.longitude, 15.0, LiveLocation.MAX_AGE_MS - 100L)
                renderer.resumeViewport(false)
                visibilityCheck = MapRenderer::class.java.getDeclaredField("resumeVisibilityCheck")
                    .apply { isAccessible = true }.get(renderer) as Runnable
                mapHandler = MapRenderer::class.java.getDeclaredField("mainHandler")
                    .apply { isAccessible = true }.get(renderer) as Handler
            }
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.onActivity {
                assertFalse("Hidden-map visibility polling is cancelled", mapHandler.hasCallbacks(visibilityCheck))
                for (name in listOf("renderFog", "expirePendingRoutes", "expireLocation")) {
                    val callback = MapRenderer::class.java.getDeclaredField(name)
                        .apply { isAccessible = true }.get(renderer) as Runnable
                    assertFalse("Paused map does not keep its $name timer", mapHandler.hasCallbacks(callback))
                }
            }
            SystemClock.sleep(150L)
            scenario.onActivity {
                assertFalse("A fix still expires while the map is paused", renderer.centerOnCar())
                view.visibility = View.VISIBLE
            }
            scenario.moveToState(Lifecycle.State.RESUMED)
            scenario.onActivity { activity ->
                MainActivity::class.java.getDeclaredMethod("stopPreviewLocation")
                    .apply { isAccessible = true }.invoke(activity)
                renderer.updateCar(car.latitude, car.longitude, 15.0)
            }
            moveAndAssert(LatLng(car.latitude + 0.00004, car.longitude), 19.5, 65.0)

            // getStyle() can immediately return the previous STREET style before the
            // asynchronous satellite reload finishes. Wait for a different Style
            // object with the new overlay layers installed.
            var previousStyle: Style? = null
            scenario.onActivity {
                previousStyle = map.style
                renderer.setMapMode(MapMode.TERRAIN)
            }
            val terrainDeadline = SystemClock.elapsedRealtime() + 30_000L
            var terrainReady = false
            while (!terrainReady && SystemClock.elapsedRealtime() < terrainDeadline) {
                scenario.onActivity {
                    val style = map.style
                    terrainReady = style != null && style !== previousStyle &&
                        style.getSource("roadconquest-car") is GeoJsonSource &&
                        style.getSource("roadconquest-fog") is ImageSource
                }
                if (!terrainReady) SystemClock.sleep(50)
            }
            assertTrue("Terrain style reloads and restores Road Conquest overlays", terrainReady)
            scenario.onActivity {
                // An outage may select a documented backup style instead of the
                // satellite raster; that must not make the overlay test fail.
                val provider = MapRenderer::class.java.getDeclaredField("baseProvider")
                    .apply { isAccessible = true }.get(renderer) as String
                if (provider == "openfreemap") {
                    assertTrue("Available satellite provider uses imagery",
                        map.style!!.getSource("satellite") is RasterSource)
                } else {
                    assertTrue("Terrain must select a recognized backup when imagery is unavailable",
                        provider in setOf("openfreemap-streets", "geoapify-tiles", "local"))
                }
                assertTrue("Fog source is recreated after style reload", map.style!!.getSource("roadconquest-fog") is ImageSource)
                assertTrue("Fog layer is recreated after style reload", map.style!!.getLayer("roadconquest-fog-raster") is RasterLayer)
                assertTrue("Car source is recreated after style reload", map.style!!.getSource("roadconquest-car") is GeoJsonSource)
                assertTrue("Car layer is recreated after style reload", map.style!!.getLayer("roadconquest-car-symbol") is SymbolLayer)
                assertTrue(map.style!!.getLayer("roadconquest-country-overlays-fill") is FillLayer)
                assertTrue(map.style!!.getLayer("roadconquest-state-overlays-fill") is FillLayer)
                assertTrue(map.style!!.getLayer("roadconquest-town-overlays-fill") is FillLayer)
                assertTrue(map.style!!.getLayer("roadconquest-country-overlays-outline") is LineLayer)
                assertTrue(map.style!!.getLayer("roadconquest-state-overlays-outline") is LineLayer)
                assertTrue(map.style!!.getLayer("roadconquest-town-overlays-outline") is LineLayer)
                assertTrue(map.style!!.getSource("roadconquest-country-overlay-boundaries") is GeoJsonSource)
                assertTrue(map.style!!.getSource("roadconquest-state-overlay-boundaries") is GeoJsonSource)
                assertTrue(map.style!!.getSource("roadconquest-town-overlay-boundaries") is GeoJsonSource)
                val ids = map.style!!.layers.map { it.id }
                assertTrue(ids.indexOf("roadconquest-country-overlays-fill") < ids.indexOf("roadconquest-country-overlays-outline"))
                assertTrue(ids.indexOf("roadconquest-country-overlays-outline") < ids.indexOf("roadconquest-state-overlays-fill"))
                assertTrue(ids.indexOf("roadconquest-state-overlays-fill") < ids.indexOf("roadconquest-state-overlays-outline"))
                assertTrue(ids.indexOf("roadconquest-state-overlays-outline") < ids.indexOf("roadconquest-town-overlays-fill"))
                assertTrue(ids.indexOf("roadconquest-town-overlays-fill") < ids.indexOf("roadconquest-town-overlays-outline"))
                assertTrue(ids.indexOf("roadconquest-town-overlays-outline") < ids.indexOf("roadconquest-world-fog-raster"))
                assertTrue(ids.indexOf("roadconquest-town-overlays-outline") < ids.indexOf("roadconquest-fog-raster"))
            }
        }
    }

    @Test fun traveledRoadLayerStaysAtItsGeographicPointAcrossCameraChanges() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        Prefs.setManualOnly(app, true)
        Prefs.setAccountPromptShown(app, true)
        Prefs.setBackgroundPromptShown(app, true)
        Prefs.setFogEnabled(app, true)
        Prefs.setMapMode(app, MapMode.STREETS)
        // This projection fixture owns its GeoJSON source. A previous test's
        // saved town-overlay mode must not start a loader that replaces it.
        Prefs.setPlaceOverlayMode(app, PlaceOverlayMode.NONE)
        val roadPoint = LatLng(37.7749, -122.4191)
        TrackingRepository(app).apply {
            readableDatabase().execSQL("DELETE FROM roads")
            upsertRoads(listOf(MatchedRoad(
                "Projection test",
                "[[-122.4198,37.7749],[-122.4187,37.7749]]",
                1L, 2L, 100.0
            )))
        }

        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var map: MapLibreMap
            lateinit var view: MapView
            val ready = CountDownLatch(1)
            scenario.onActivity { activity ->
                MainActivity::class.java.getDeclaredMethod("stopPreviewLocation").apply { isAccessible = true }.invoke(activity)
                view = activity.findViewById(R.id.mapView)
                view.getMapAsync { readyMap ->
                    map = readyMap
                    readyMap.getStyle { ready.countDown() }
                }
            }
            assertTrue("Bundled style must load", ready.await(30, TimeUnit.SECONDS))
            scenario.onActivity {
                (map.style!!.getSource("roadconquest-town-overlays") as GeoJsonSource).setGeoJson(
                    """{"type":"FeatureCollection","features":[{"type":"Feature","properties":{"overlay_name":"Projection Town","overlay_kind":"TOWN","area_sq_km":1.0},"geometry":{"type":"Polygon","coordinates":[[[-122.4194,37.7747],[-122.4188,37.7747],[-122.4188,37.7751],[-122.4194,37.7751],[-122.4194,37.7747]]]}}]}"""
                )
            }

            fun moveAndAssert(target: LatLng, zoom: Double, bearing: Double): android.graphics.PointF {
                val idle = CountDownLatch(1)
                val frame = CountDownLatch(1)
                val frameListener = MapView.OnDidFinishRenderingFrameListener { _, _, _ -> frame.countDown() }
                val idleListener = MapLibreMap.OnCameraIdleListener { idle.countDown() }
                scenario.onActivity {
                    view.addOnDidFinishRenderingFrameListener(frameListener)
                    map.addOnCameraIdleListener(idleListener)
                    map.moveCamera(CameraUpdateFactory.newCameraPosition(
                        org.maplibre.android.camera.CameraPosition.Builder()
                            .target(target).zoom(zoom).bearing(bearing).tilt(0.0).build()))
                }
                assertTrue("Camera settles", idle.await(10, TimeUnit.SECONDS))
                assertTrue("Native renderer finishes a frame before feature queries", frame.await(10, TimeUnit.SECONDS))
                scenario.onActivity {
                    map.removeOnCameraIdleListener(idleListener)
                    view.removeOnDidFinishRenderingFrameListener(frameListener)
                }

                val deadline = SystemClock.elapsedRealtime() + 10_000L
                var screen = android.graphics.PointF()
                var found = false
                var overlayFound = false
                // Separate native sources finish loading independently. A road
                // frame alone is not proof that the polygon has reached the renderer.
                while ((!found || !overlayFound) && SystemClock.elapsedRealtime() < deadline) {
                    scenario.onActivity {
                        screen = map.projection.toScreenLocation(roadPoint)
                        assertTrue("Projection fixture remains inside the map viewport",
                            screen.x >= 0f && screen.x < view.width && screen.y >= 0f && screen.y < view.height)
                        found = map.queryRenderedFeatures(
                            screen,
                            "roadconquest-traveled-roads-line"
                        ).isNotEmpty()
                        overlayFound = map.queryRenderedFeatures(
                            screen,
                            "roadconquest-town-overlays-fill"
                        ).isNotEmpty()
                    }
                    if (!found || !overlayFound) SystemClock.sleep(50)
                }
                assertTrue("Traveled road stays rendered at its projected geographic point", found)
                assertTrue("Place overlay stays anchored to its geographic polygon", overlayFound)
                return screen
            }

            val first = moveAndAssert(LatLng(37.7749, -122.4194), 18.0, 0.0)
            // At zoom 20 the old ~40 m offset could move the point offscreen.
            // Query actual rendered geometry inside the viewport at every zoom.
            val second = moveAndAssert(LatLng(37.77493, -122.41916), 20.0, 55.0)
            val third = moveAndAssert(LatLng(37.77475, -122.4190), 19.0, 125.0)
            assertTrue("Camera changes actually move the tested road on screen",
                kotlin.math.hypot((first.x - second.x).toDouble(), (first.y - second.y).toDouble()) > 20.0)
            assertTrue("A second camera change also moves the tested road on screen",
                kotlin.math.hypot((second.x - third.x).toDouble(), (second.y - third.y).toDouble()) > 20.0)
            }
        } finally {
            TrackingRepository(app).readableDatabase().execSQL("DELETE FROM roads")
        }
    }

    @Test fun exploreNearbyRendersLocalVisitedAndUnvisitedNumberedTiles() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        Prefs.setManualOnly(app, true)
        Prefs.setAccountPromptShown(app, true)
        Prefs.setBackgroundPromptShown(app, true)
        val repository = TrackingRepository(app)
        repository.readableDatabase().execSQL("DELETE FROM explored_grid")
        val visited = Location("gps").apply {
            latitude = 40.0
            longitude = -74.0
            accuracy = 5f
            time = System.currentTimeMillis()
        }
        assertTrue(repository.recordExploredGridCell(visited))
        val cellCenter = repository.readableDatabase().rawQuery(
            "SELECT latitude, longitude FROM explored_grid LIMIT 1", null
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            LatLng(cursor.getDouble(0), cursor.getDouble(1))
        }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val styleReady = CountDownLatch(1)
                scenario.onActivity { activity ->
                    MainActivity::class.java.getDeclaredMethod("stopPreviewLocation")
                        .apply { isAccessible = true }.invoke(activity)
                    activity.findViewById<MapView>(R.id.mapView).getMapAsync { map ->
                        map.getStyle { styleReady.countDown() }
                    }
                }
                assertTrue("Native map style initializes", styleReady.await(30, TimeUnit.SECONDS))
                val readyDeadline = SystemClock.elapsedRealtime() + 10_000L
                var overlayLayerReady = false
                while (!overlayLayerReady && SystemClock.elapsedRealtime() < readyDeadline) {
                    scenario.onActivity { activity ->
                        val map = MainActivity::class.java.getDeclaredField("activeMap")
                            .apply { isAccessible = true }.get(activity) as? MapLibreMap
                        overlayLayerReady = map?.style?.getLayer("roadconquest-nearby-visited-fill") != null
                    }
                    if (!overlayLayerReady) SystemClock.sleep(50)
                }
                assertTrue("Renderer installs the nearby map layers", overlayLayerReady)
                val nearbyReady = CountDownLatch(1)
                var failed = false
                lateinit var renderer: MapRenderer
                scenario.onActivity { activity ->
                    renderer = MainActivity::class.java.getDeclaredField("renderer")
                        .apply { isAccessible = true }.get(activity) as MapRenderer
                    renderer.showNearbyExploration(40.0, -74.0,
                        onShown = { explored, total ->
                            if (explored != 1 || total != 25) failed = true
                            nearbyReady.countDown()
                        },
                        onError = { failed = true; nearbyReady.countDown() }
                    )
                }
                assertTrue("The local grid query completes", nearbyReady.await(30, TimeUnit.SECONDS))
                assertFalse("Correct local tile count with no network", failed)
                // A source existing in the style is not enough: ensure its fill is
                // actually rendered at the visited GPS cell after fitting the camera.
                val renderDeadline = SystemClock.elapsedRealtime() + 10_000L
                var tileVisible = false
                while (!tileVisible && SystemClock.elapsedRealtime() < renderDeadline) {
                    scenario.onActivity { activity ->
                        val map = MainActivity::class.java.getDeclaredField("activeMap")
                            .apply { isAccessible = true }.get(activity) as MapLibreMap
                        val screen = map.projection.toScreenLocation(LatLng(40.0, -74.0))
                        tileVisible = map.queryRenderedFeatures(
                            screen, "roadconquest-nearby-visited-fill"
                        ).isNotEmpty()
                    }
                    if (!tileVisible) SystemClock.sleep(100)
                }
                assertTrue("A visited tile must really draw on the native map", tileVisible)
                val markerDeadline = SystemClock.elapsedRealtime() + 10_000L
                var markerVisible = false
                while (!markerVisible && SystemClock.elapsedRealtime() < markerDeadline) {
                    scenario.onActivity { activity ->
                        val map = MainActivity::class.java.getDeclaredField("activeMap")
                            .apply { isAccessible = true }.get(activity) as MapLibreMap
                        markerVisible = map.queryRenderedFeatures(
                            map.projection.toScreenLocation(cellCenter),
                            "roadconquest-nearby-number-labels"
                        ).isNotEmpty()
                    }
                    if (!markerVisible) SystemClock.sleep(100)
                }
                assertTrue("Number marker must render at the visited tile center", markerVisible)
                // More than a single central marker should be usable: verify
                // every nearby tile center fits between the floating controls
                // and the bottom sheet on the default portrait device.
                scenario.onActivity { activity ->
                    val map = MainActivity::class.java.getDeclaredField("activeMap")
                        .apply { isAccessible = true }.get(activity) as MapLibreMap
                    val view = activity.findViewById<MapView>(R.id.mapView)
                    val panelTop = activity.findViewById<View>(R.id.controlPanel).top
                    val topControlsBottom = maxOf(
                        activity.findViewById<View>(R.id.overlayButton).bottom,
                        activity.findViewById<View>(R.id.pointsText).bottom
                    )
                    val sourceCenter = com.roadconquest.app.map.FogGrid.cell(40.0, -74.0)!!
                    val centers = com.roadconquest.app.map.FogGrid.neighbors(sourceCenter)
                    assertEquals(25, centers.size)
                    for (cell in centers) {
                        val (lat, lon) = com.roadconquest.app.map.FogGrid.center(cell)
                        val pixel = map.projection.toScreenLocation(LatLng(lat, lon))
                        assertTrue("Nearby marker at $cell stays inside unobstructed map: $pixel",
                            pixel.x >= 0f && pixel.x <= view.width &&
                            pixel.y >= topControlsBottom && pixel.y <= panelTop)
                    }
                }
                scenario.onActivity { activity ->
                    assertTrue(renderer.isNearbyExplorationVisible())
                    val map = activity.findViewById<MapView>(R.id.mapView)
                    val ready = MainActivity::class.java.getDeclaredField("activeMap")
                        .apply { isAccessible = true }.get(activity) as MapLibreMap
                    val style = ready.style!!
                    assertNotNull(style.getLayer("roadconquest-nearby-visited-fill"))
                    assertNotNull(style.getLayer("roadconquest-nearby-unvisited-fill"))
                    assertNotNull(style.getLayer("roadconquest-nearby-number-labels"))
                    assertNotNull(style.getSource("roadconquest-nearby-numbers"))
                    assertTrue("The map remains sized", map.width > 0 && map.height > 0)
                    renderer.hideNearbyExploration()
                    assertFalse(renderer.isNearbyExplorationVisible())
                }
            }
        } finally {
            repository.readableDatabase().execSQL("DELETE FROM explored_grid")
        }
    }

    @Test fun notificationStopRemainsStoppedUntilTheAppIsReopened() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        Prefs.setManualOnly(app, true)
        Prefs.setAccountPromptShown(app, true)
        Prefs.setBackgroundPromptShown(app, true)
        Prefs.setTrackingPaused(app, false)
        val manager = app.getSystemService(NotificationManager::class.java)
        fun awaitState(message: String, predicate: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + 10_000L
            while (SystemClock.elapsedRealtime() < deadline && !predicate()) SystemClock.sleep(50)
            assertTrue("$message (paused=${Prefs.isTrackingPaused(app)}, running=${TrackingService.isRunning}, " +
                "notifications=${manager.activeNotifications.map { it.id }})", predicate())
        }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { ContextCompat.startForegroundService(it, Intent(it, TrackingService::class.java)) }
                awaitState("Tracking notification appears") {
                    TrackingService.isRunning && manager.activeNotifications.any { it.id == 4101 }
                }
                manager.activeNotifications.single { it.id == 4101 }.notification.actions.single().actionIntent.send()
                awaitState("Stop action removes the service and notification") {
                    Prefs.isTrackingPaused(app) && !TrackingService.isRunning &&
                        manager.activeNotifications.none { it.id == 4101 }
                }
                scenario.moveToState(Lifecycle.State.STARTED)
                scenario.moveToState(Lifecycle.State.RESUMED)
                assertTrue("Shade-style pause/resume does not restart tracking", Prefs.isTrackingPaused(app))
                assertFalse(TrackingService.isRunning)
                scenario.recreate()
                assertTrue("Activity recreation does not undo a stop", Prefs.isTrackingPaused(app))
                assertFalse(TrackingService.isRunning)
                scenario.moveToState(Lifecycle.State.CREATED)
                scenario.moveToState(Lifecycle.State.RESUMED)
                awaitState("Reopening resumes the previous tracking session, including manual mode") {
                    !Prefs.isTrackingPaused(app) && TrackingService.isRunning
                }
            }
        } finally {
            app.stopService(Intent(app, TrackingService::class.java))
            Prefs.setTrackingPaused(app, false)
        }
    }
}
