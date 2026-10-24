package com.roadconquest.app

import android.Manifest
import android.os.Build
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.roadconquest.app.data.MatchedRoad
import com.roadconquest.app.data.TrackingRepository
import com.roadconquest.app.map.MapMode
import com.roadconquest.app.map.MapRenderer
import com.roadconquest.app.map.OverlayRoads
import com.roadconquest.app.util.Prefs
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class RoadCacheDeviceTest {
    @org.junit.Before fun skipFirstUseTour() {
        Prefs.setTourHandled(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext, true)
    }

    @get:Rule val permissions: GrantPermissionRule = GrantPermissionRule.grant(*buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray())

    @Test fun returningToSameViewportReloadsRoadsChangedWhileReceiverWasStopped() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        Prefs.setManualOnly(app, true)
        Prefs.setAccountPromptShown(app, true)
        Prefs.setBackgroundPromptShown(app, true)
        Prefs.setFogEnabled(app, true)
        Prefs.setMapMode(app, MapMode.STREETS)
        val repository = TrackingRepository(app)
        repository.readableDatabase().execSQL("DELETE FROM roads")
        val center = LatLng(37.7750, -122.4194)
        repository.upsertRoads(listOf(MatchedRoad(
            "Before stop", "[[-122.4200,37.7749],[-122.4188,37.7749]]", 1L, 2L, 100.0
        )))

        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var map: MapLibreMap
                val ready = CountDownLatch(1)
                scenario.onActivity { activity ->
                    MainActivity::class.java.getDeclaredMethod("stopPreviewLocation")
                        .apply { isAccessible = true }.invoke(activity)
                    activity.findViewById<MapView>(R.id.mapView).getMapAsync { readyMap ->
                        map = readyMap
                        readyMap.getStyle { ready.countDown() }
                    }
                }
                assertTrue(ready.await(30, TimeUnit.SECONDS))
                // Prevent the emulator's preview location from exercising the app's intentional
                // one-time auto-center when the activity resumes. This test only measures cache
                // invalidation/reload in the same viewport.
                scenario.onActivity { activity ->
                    val renderer = MainActivity::class.java.getDeclaredField("renderer")
                        .apply { isAccessible = true }.get(activity) as MapRenderer
                    renderer.updateCar(center.latitude, center.longitude, 0.0)
                    renderer.clearCurrentLocation()
                    MainActivity::class.java.getDeclaredMethod("stopPreviewLocation")
                        .apply { isAccessible = true }.invoke(activity)
                }
                val idle = CountDownLatch(1)
                val listener = MapLibreMap.OnCameraIdleListener { idle.countDown() }
                scenario.onActivity {
                    map.addOnCameraIdleListener(listener)
                    map.moveCamera(CameraUpdateFactory.newLatLngZoom(center, 18.0))
                }
                assertTrue(idle.await(10, TimeUnit.SECONDS))
                scenario.onActivity { map.removeOnCameraIdleListener(listener) }

                fun awaitRoadCount(expected: Int, message: String) {
                    val deadline = SystemClock.elapsedRealtime() + 10_000L
                    var count = -1
                    while (count != expected && SystemClock.elapsedRealtime() < deadline) {
                        scenario.onActivity { activity ->
                            val renderer = MainActivity::class.java.getDeclaredField("renderer")
                                .apply { isAccessible = true }.get(activity) as? MapRenderer
                            count = renderer?.let {
                                MapRenderer::class.java.getDeclaredField("displayedRoads")
                                    .apply { isAccessible = true }.get(it) as OverlayRoads
                            }?.starts?.size?.minus(1) ?: -1
                        }
                        if (count != expected) SystemClock.sleep(50)
                    }
                    assertTrue("$message (loaded=$count)", count == expected)
                }

                awaitRoadCount(1, "Initial road cache loads")
                scenario.moveToState(Lifecycle.State.CREATED)
                repository.upsertRoads(listOf(MatchedRoad(
                    "While stopped", "[[-122.4200,37.7751],[-122.4188,37.7751]]", 3L, 4L, 100.0
                )))
                scenario.moveToState(Lifecycle.State.RESUMED)
                awaitRoadCount(2, "Resume reloads roads changed while receiver was stopped")
            }
        } finally {
            repository.readableDatabase().execSQL("DELETE FROM roads")
        }
    }
}
