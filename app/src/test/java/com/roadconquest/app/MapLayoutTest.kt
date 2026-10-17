package com.roadconquest.app

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
class MapLayoutTest {
    private class CountingTextView(context: Context) : TextView(context) {
        var layoutRequests = 0
        override fun requestLayout() { layoutRequests++; super.requestLayout() }
    }

    @Test fun bottomNavigationKeepsThreePrimaryDestinationsAndAccessibleGarageChip() {
        // Parse actual compiled layout without starting MapLibre's native GPU view.
        val context = Robolectric.buildActivity(MainActivity::class.java).get()
        val parser = context.resources.getLayout(R.layout.activity_main)
        val androidNs = "http://schemas.android.com/apk/res/android"
        val nav = mutableListOf<Int>()
        var garageChipFound = false
        try {
            while (parser.eventType != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == org.xmlpull.v1.XmlPullParser.START_TAG) {
                    val id = parser.getAttributeResourceValue(androidNs, "id", 0)
                    if (id in setOf(R.id.tripsNavButton, R.id.badgesNavButton, R.id.weeklyGoalsNavButton)) {
                        assertEquals("Navigation targets must be actual buttons", "Button", parser.name)
                        nav += id
                    }
                    if (id == R.id.pointsText) {
                        garageChipFound = parser.getAttributeBooleanValue(androidNs, "clickable", false)
                    }
                }
                parser.next()
            }
        } finally {
            parser.close()
        }
        assertEquals(listOf(R.id.tripsNavButton, R.id.badgesNavButton, R.id.weeklyGoalsNavButton), nav)
        assertTrue("The existing points chip must remain tappable for Garage", garageChipFound)
    }

    @Test fun homeScreenKeepsMapFullSizeAndAccessibleCompactProviderAttribution() {
        val context = Robolectric.buildActivity(MainActivity::class.java).get()
        val parser = context.resources.getLayout(R.layout.activity_main)
        val ns = "http://schemas.android.com/apk/res/android"
        val mapNs = "http://schemas.android.com/apk/res-auto"
        var foundMap = false
        var foundPanel = false
        var visibleCreditCount = 0
        try {
            while (parser.eventType != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == org.xmlpull.v1.XmlPullParser.START_TAG) {
                    when (parser.getAttributeResourceValue(ns, "id", 0)) {
                        R.id.mapView -> {
                            foundMap = true
                            assertEquals(-1, parser.getAttributeIntValue(ns, "layout_width", 0))
                            assertEquals(-1, parser.getAttributeIntValue(ns, "layout_height", 0))
                            assertNull("Let MapLibre use native device resolution",
                                parser.getAttributeValue(mapNs, "maplibre_pixelRatio"))
                        }
                        R.id.controlPanel -> foundPanel = true
                        R.id.locationIqCredit -> {
                            visibleCreditCount++
                            assertEquals(R.string.locationiq_credit,
                                parser.getAttributeResourceValue(ns, "text", 0))
                            val attributes = context.obtainStyledAttributes(parser,
                                intArrayOf(android.R.attr.minHeight))
                            try {
                                assertEquals(48f * context.resources.displayMetrics.density,
                                    attributes.getDimension(0, 0f), 0.01f)
                            } finally { attributes.recycle() }
                        }
                    }
                }
                parser.next()
            }
        } finally {
            parser.close()
        }
        assertTrue(foundMap)
        assertTrue(foundPanel)
        assertEquals("A single compact, clickable LocationIQ credit stays on the home screen",
            1, visibleCreditCount)
    }

    @Test fun identicalInsetsAndRepeatedLayoutsDoNotRescheduleTheStatsCard() {
        // Test the real safe-area listener without loading MapLibre's desktop-incompatible GPU view.
        val activity = Robolectric.buildActivity(MainActivity::class.java).get()
        activity.setTheme(R.style.Theme_RoadConquest)
        val root = FrameLayout(activity).apply { id = R.id.root }
        val stats = CountingTextView(activity).apply { text = "0.0 mi traveled\n0 roads unlocked"; maxWidth = 210 }
        val settings = ImageButton(activity)
        val center = ImageButton(activity)
        val overlay = ImageButton(activity)
        val points = CountingTextView(activity)
        val panel = View(activity)
        fun params(top: Int, gravity: Int) = FrameLayout.LayoutParams(52, 52, gravity).apply {
            topMargin = top; marginStart = 16; marginEnd = 16; bottomMargin = 16
        }
        root.addView(stats, params(16, Gravity.TOP or Gravity.START))
        root.addView(settings, params(16, Gravity.TOP or Gravity.END))
        root.addView(center, params(80, Gravity.TOP or Gravity.END))
        root.addView(overlay, params(144, Gravity.TOP or Gravity.END))
        root.addView(points, params(88, Gravity.TOP or Gravity.START))
        root.addView(panel, params(16, Gravity.BOTTOM).apply { bottomMargin = 0 })
        activity.setContentView(root)
        MainActivity::class.java.getDeclaredMethod(
            "applySafeAreaInsets",
            ImageButton::class.java,
            ImageButton::class.java,
            ImageButton::class.java,
            View::class.java,
            View::class.java,
            View::class.java
        ).apply { isAccessible = true }
            .invoke(activity, settings, center, overlay, panel, stats, points)
        val insets = WindowInsetsCompat.Builder().setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(7, 30, 11, 18)).build()
        ViewCompat.dispatchApplyWindowInsets(root, insets)
        repeat(3) {
            root.measure(View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1280, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 720, 1280)
        }
        val requests = stats.layoutRequests
        repeat(20) {
            ViewCompat.dispatchApplyWindowInsets(root, insets)
            root.measure(View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1280, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 720, 1280)
        }
        assertEquals("Unchanged values must not create a layout feedback loop", requests, stats.layoutRequests)
        assertEquals(46, (settings.layoutParams as FrameLayout.LayoutParams).topMargin)
        assertEquals(110, (center.layoutParams as FrameLayout.LayoutParams).topMargin)
        assertEquals(27, (center.layoutParams as FrameLayout.LayoutParams).marginEnd)
        assertEquals(174, (overlay.layoutParams as FrameLayout.LayoutParams).topMargin)
        assertEquals(27, (overlay.layoutParams as FrameLayout.LayoutParams).marginEnd)
        assertEquals(118, (points.layoutParams as FrameLayout.LayoutParams).topMargin)
        assertEquals(23, (points.layoutParams as FrameLayout.LayoutParams).marginStart)
        assertEquals(0, (panel.layoutParams as FrameLayout.LayoutParams).bottomMargin)
        assertEquals(18, panel.paddingBottom)
    }

    @Test fun expandingStatsKeepsPointsChipBelowIt() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).get()
        activity.setTheme(R.style.Theme_RoadConquest)
        val root = FrameLayout(activity).apply { id = R.id.root }
        val stats = TextView(activity).apply { maxWidth = 210; text = "Huge mileage and road totals" }
        val points = TextView(activity).apply { text = "⚔ 500 points" }
        val settings = ImageButton(activity)
        val center = ImageButton(activity)
        val overlay = ImageButton(activity)
        val panel = View(activity)
        fun add(view: View, width: Int, height: Int, gravity: Int, top: Int = 0) {
            root.addView(view, FrameLayout.LayoutParams(width, height, gravity).apply {
                topMargin = top
                marginStart = 16
                marginEnd = 16
                bottomMargin = 16
            })
        }
        add(stats, 210, 200, Gravity.TOP or Gravity.START, 16)
        add(points, 150, 48, Gravity.TOP or Gravity.START, 102)
        add(settings, 52, 52, Gravity.TOP or Gravity.END, 16)
        add(center, 52, 52, Gravity.TOP or Gravity.END, 80)
        add(overlay, 52, 52, Gravity.TOP or Gravity.END, 144)
        add(panel, 340, 160, Gravity.BOTTOM)
        activity.setContentView(root)
        MainActivity::class.java.getDeclaredMethod(
            "applySafeAreaInsets",
            ImageButton::class.java,
            ImageButton::class.java,
            ImageButton::class.java,
            View::class.java,
            View::class.java,
            View::class.java
        ).apply { isAccessible = true }
            .invoke(activity, settings, center, overlay, panel, stats, points)
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(0, 30, 0, 24)).build()
        ViewCompat.dispatchApplyWindowInsets(root, insets)
        repeat(4) {
            root.measure(View.MeasureSpec.makeMeasureSpec(420, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 420, 800)
        }
        assertTrue("Points chip must not overlap a taller statistics panel",
            points.top >= stats.bottom + (8 * activity.resources.displayMetrics.density).toInt())
    }
}
