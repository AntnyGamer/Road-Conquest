package com.roadconquest.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.roadconquest.app.map.TownCompletion
import com.roadconquest.app.map.TownCompletionProgress
import com.roadconquest.app.map.TownMedal
import com.roadconquest.app.util.Prefs
import com.roadconquest.app.util.UiTheme
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class SettingsNavigationDeviceTest {
    @get:org.junit.Rule val permissions: androidx.test.rule.GrantPermissionRule =
        androidx.test.rule.GrantPermissionRule.grant(*buildList {
            add(android.Manifest.permission.ACCESS_FINE_LOCATION)
            add(android.Manifest.permission.ACCESS_COARSE_LOCATION)
            if (android.os.Build.VERSION.SDK_INT >= 33) add(android.Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray())

    @Test fun completedTownRendersItsMedalAndPercentageAndClearsWithTheOverlay() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        com.roadconquest.app.data.TrackingRepository(context).clearHistory()
        val tracking = com.roadconquest.app.data.TrackingRepository(context)
        val progression = com.roadconquest.app.data.ProgressionRepository(context)
        com.roadconquest.app.map.PlaceOverlayCache.clear(context)
        val origin = com.roadconquest.app.map.FogGrid.cell(40.0, -74.0)!!
        val first = com.roadconquest.app.map.FogGrid.center(origin)
        val last = com.roadconquest.app.map.FogGrid.center(origin.copy(row = origin.row + 3, column = origin.column + 3))
        val sw = com.roadconquest.app.map.FogGrid.corners(first.first, first.second)
        val ne = com.roadconquest.app.map.FogGrid.corners(last.first, last.second)
        val center = org.maplibre.android.geometry.LatLng((sw[4] + ne[0]) / 2, (sw[1] + ne[3]) / 2)
        val place = com.roadconquest.app.data.PlaceDiscovery(com.roadconquest.app.data.PlaceKind.TOWN,
            "completion-device-town", "Completion Town", countryName = "Testland",
            visitedAt = System.currentTimeMillis(), latitude = first.first, longitude = first.second)
        progression.recordPlace(place)
        val geometry = """{"type":"Polygon","coordinates":[[[${sw[1]},${sw[4]}],[${ne[3]},${sw[4]}],[${ne[3]},${ne[0]}],[${sw[1]},${ne[0]}],[${sw[1]},${sw[4]}]]]}"""
        val overlay = com.roadconquest.app.map.PlaceOverlayData(place.key, place.displayName, place.kind, null, 40.0, geometry)
        assertTrue(com.roadconquest.app.map.PlaceOverlayCache.write(context, place, overlay, verifiedForDiscovery = true))
        for (row in 0..3) for (column in 0..3) {
            val point = com.roadconquest.app.map.FogGrid.center(origin.copy(row = origin.row + row, column = origin.column + column))
            tracking.recordExploredGridCell(android.location.Location("gps").apply {
                latitude = point.first; longitude = point.second; accuracy = 5f; time = System.currentTimeMillis()
            })
        }
        Prefs.setManualOnly(context, true)
        Prefs.setTourHandled(context, true)
        Prefs.setAccountPromptShown(context, true)
        Prefs.setBackgroundPromptShown(context, true)
        Prefs.setFogEnabled(context, false)
        Prefs.setMapMode(context, com.roadconquest.app.map.MapMode.STREETS)
        Prefs.setPlaceOverlayMode(context, com.roadconquest.app.map.PlaceOverlayMode.TOWN)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val ready = java.util.concurrent.CountDownLatch(1)
                lateinit var map: org.maplibre.android.maps.MapLibreMap
                lateinit var renderer: com.roadconquest.app.map.MapRenderer
                scenario.onActivity { activity ->
                    activity.findViewById<org.maplibre.android.maps.MapView>(R.id.mapView).getMapAsync { loaded ->
                        map = loaded
                        renderer = MainActivity::class.java.getDeclaredField("renderer").apply { isAccessible = true }
                            .get(activity) as com.roadconquest.app.map.MapRenderer
                        loaded.getStyle {
                            renderer.centerOnStartingLocation(center.latitude, center.longitude, force = true)
                            loaded.moveCamera(org.maplibre.android.camera.CameraUpdateFactory.newLatLngZoom(center, 11.0))
                            renderer.refreshPlaceOverlays()
                            ready.countDown()
                        }
                    }
                }
                assertTrue(ready.await(30, java.util.concurrent.TimeUnit.SECONDS))
                val deadline = SystemClock.elapsedRealtime() + 15_000
                var progress: Int? = null
                do {
                    scenario.onActivity { progress = renderer.overlayInfoAt(map.projection.toScreenLocation(center))?.completionPercent }
                    if (progress != 100) SystemClock.sleep(50)
                } while (progress != 100 && SystemClock.elapsedRealtime() < deadline)
                assertEquals(100, progress)
                val markerDeadline = SystemClock.elapsedRealtime() + 10_000
                var markerShown = false
                do {
                    scenario.onActivity {
                        markerShown = map.queryRenderedFeatures(map.projection.toScreenLocation(center),
                            "roadconquest-town-completion-badges").isNotEmpty()
                    }
                    if (!markerShown) SystemClock.sleep(50)
                } while (!markerShown && SystemClock.elapsedRealtime() < markerDeadline)
                val snapshotReady = java.util.concurrent.CountDownLatch(1)
                var goldPixels = 0
                scenario.onActivity {
                    val registered = map.style?.getImage("town-completion-GOLD-100")
                    assertNotNull("The current native style must own the medal artwork", registered)
                    registered?.let { save("registered-town-badge", it) }
                    val point = map.projection.toScreenLocation(center)
                    map.snapshot { bitmap ->
                        save("completed-town-render", bitmap)
                        for (y in maxOf(0, point.y.toInt() - 130)..minOf(bitmap.height - 1, point.y.toInt() + 130)) {
                            for (x in maxOf(0, point.x.toInt() - 130)..minOf(bitmap.width - 1, point.x.toInt() + 130)) {
                                val pixel = bitmap.getPixel(x, y)
                                if (Color.red(pixel) > 180 && Color.green(pixel) in 120..235 && Color.blue(pixel) < 150) goldPixels++
                            }
                        }
                        snapshotReady.countDown()
                    }
                }
                assertTrue(snapshotReady.await(15, java.util.concurrent.TimeUnit.SECONDS))
                capture("completed-town-map")
                assertTrue("The earned badge and percentage must actually render, not only exist in a layer", markerShown)
                assertTrue("The native map snapshot must contain the gold medal artwork", goldPixels > 10)
                scenario.onActivity {
                    assertNotNull(map.style?.getLayer("roadconquest-town-completion-badges"))
                    assertEquals("Gold", renderer.overlayInfoAt(map.projection.toScreenLocation(center))?.completionMedal)
                }
                scenario.onActivity {
                    renderer.setPlaceOverlayMode(com.roadconquest.app.map.PlaceOverlayMode.NONE)
                    assertNull(renderer.overlayInfoAt(map.projection.toScreenLocation(center)))
                }
            }
        } finally {
            Prefs.setPlaceOverlayMode(context, com.roadconquest.app.map.PlaceOverlayMode.NONE)
            Prefs.setFogEnabled(context, true)
            tracking.clearHistory()
            com.roadconquest.app.map.PlaceOverlayCache.clear(context)
        }
    }

    @Test fun settingsCategoriesSearchAndAppearanceRemainUsableOnDevice() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val previous = Prefs.uiTheme(context)
        Prefs.setManualOnly(context, true)
        try {
            for (theme in listOf(UiTheme.LIGHT, UiTheme.DARK)) {
                Prefs.setUiTheme(context, theme)
                ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
                    scenario.onActivity {
                        assertEquals(7, it.findViewById<LinearLayout>(R.id.settingsCategories).childCount)
                        assertFalse(it.findViewById<EditText>(R.id.settingsSearch).hasFocus())
                    }
                    capture("settings-${theme.name.lowercase()}")
                    scenario.onActivity {
                        it.findViewById<LinearLayout>(R.id.settingsCategories).getChildAt(4).performClick()
                        assertEquals(View.VISIBLE, it.findViewById<View>(R.id.backupSettingsCard).visibility)
                    }
                    capture("backups-${theme.name.lowercase()}")
                    scenario.onActivity {
                        it.findViewById<View>(R.id.backNavButton).performClick()
                        assertEquals(View.VISIBLE, it.findViewById<View>(R.id.settingsCategories).visibility)
                        it.findViewById<EditText>(R.id.settingsSearch).setText("dark")
                        assertEquals(View.VISIBLE, it.findViewById<View>(R.id.mapSettingsCard).visibility)
                    }
                    capture("search-${theme.name.lowercase()}")
                }
            }
            val preview = Bitmap.createBitmap(440, 170, Bitmap.Config.ARGB_8888).apply { density = android.util.DisplayMetrics.DENSITY_DEFAULT }
            val canvas = Canvas(preview)
            canvas.drawColor(Color.rgb(239, 244, 250))
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(20, 32, 57); textSize = 17f }
            for ((index, percent) in listOf(12, 25, 50, 100).withIndex()) {
                val progress = TownCompletionProgress(percent, TownCompletion.medalFor(percent), 0.0, 0.0)
                canvas.drawBitmap(TownCompletion.badge(progress), 12f + index * 110, 12f, null)
                canvas.drawText(if (index == 0) "Exploring" else TownMedal.entries[index].label, 12f + index * 110, 145f, paint)
            }
            save("town-badges", preview)
        } finally { Prefs.setUiTheme(context, previous) }
    }

    private fun capture(name: String) {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        SystemClock.sleep(250)
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { save(name, it) }
    }
    private fun save(name: String, bitmap: Bitmap) {
        val directory = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir") ?: return
        val output = File(directory, "$name.png")
        output.parentFile?.mkdirs()
        output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
