package com.roadconquest.app

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.util.AttributeSet
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.roadconquest.app.onboarding.MapTour
import com.roadconquest.app.util.Prefs
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37], qualifiers = "mdpi", manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MapTourTest {
    @Before fun reset() {
        RuntimeEnvironment.getApplication().getSharedPreferences("roadconquest_preferences", 0).edit().clear().commit()
    }

    private fun host(activity: Activity, night: Boolean = false, fontScale: Float = 1f): FrameLayout {
        val configuration = Configuration(activity.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            this.fontScale = fontScale
        }
        val context = ContextThemeWrapper(activity.createConfigurationContext(configuration), R.style.Theme_RoadConquest)
        val inflater = LayoutInflater.from(context).cloneInContext(context)
        // Use the real map controls/layout while replacing only MapLibre's native GPU surface.
        inflater.factory2 = object : LayoutInflater.Factory2 {
            override fun onCreateView(parent: View?, name: String, context: Context, attrs: AttributeSet): View? =
                if (name == "org.maplibre.android.maps.MapView") View(context).apply { setBackgroundColor(Color.rgb(31, 44, 65)) } else null
            override fun onCreateView(name: String, context: Context, attrs: AttributeSet): View? =
                onCreateView(null, name, context, attrs)
        }
        return (inflater.inflate(R.layout.activity_main, null) as FrameLayout).also(activity::setContentView)
    }

    private fun layout(host: FrameLayout, width: Int = 360, height: Int = 800) {
        host.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        host.layout(0, 0, width, height)
    }

    @Test fun demoIsOfflineAndSkipRestoresAccessibilityWithoutRepeatingCompletion() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get()
            val root = host(activity)
            val map = root.getChildAt(0)
            val originalAccessibility = map.importantForAccessibility
            val before = activity.getSharedPreferences("roadconquest_preferences", 0).all.toMap()
            var finished = 0
            val tour = MapTour(root) { finished++ }
            layout(root)
            tour.findViewById<Button>(R.id.tourDemoButton).performClick()
            assertTrue(tour.previewRevealed)
            assertEquals(before, activity.getSharedPreferences("roadconquest_preferences", 0).all)
            assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS, map.importantForAccessibility)
            var focus: View? = tour.focusSearch(tour.findViewById(R.id.tourNextButton), View.FOCUS_FORWARD)
            while (focus != null && focus !== tour) focus = focus.parent as? View
            assertSame("Keyboard focus must stay inside the modal tour", tour, focus)
            assertEquals("The demo must not create saved-progress databases", 0, activity.databaseList().size)
            val skip = tour.findViewById<Button>(R.id.tourSkipButton)
            skip.performClick()
            skip.performClick()
            assertEquals(1, finished)
            assertTrue(Prefs.isTourHandled(activity))
            assertNull(tour.parent)
            assertEquals(originalAccessibility, map.importantForAccessibility)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun recreationStateAndBackKeepPositionUntilAnExplicitChoice() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get()
            val root = host(activity)
            val first = MapTour(root) { fail("Destruction must not finish onboarding") }
            first.findViewById<Button>(R.id.tourDemoButton).performClick()
            repeat(3) { first.findViewById<Button>(R.id.tourNextButton).performClick() }
            val savedStep = first.step
            val savedPreview = first.previewRevealed
            first.dismiss()
            assertFalse(Prefs.isTourHandled(activity))
            var finished = 0
            val restored = MapTour(root, savedStep, savedPreview) { finished++ }
            assertEquals(activity.getString(R.string.tour_progress_title), restored.findViewById<TextView>(R.id.tourTitle).text)
            assertTrue(restored.previewRevealed)
            restored.back()
            assertEquals(2, restored.step)
            assertFalse(Prefs.isTourHandled(activity))
            repeat(3) { restored.findViewById<Button>(R.id.tourNextButton).performClick() }
            restored.findViewById<Button>(R.id.tourNextButton).performClick()
            assertEquals(1, finished)
            assertTrue(Prefs.isTourHandled(activity))
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun backAtTheIntroductionDoesNotSkipTheTourOrStartSetup() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get()
            val root = host(activity)
            val tour = MapTour(root) { fail("Back must not choose Skip or start setup") }
            assertFalse(tour.back())
            assertEquals(0, tour.step)
            assertFalse(Prefs.isTourHandled(activity))
            assertSame(root, tour.parent)
            tour.dismiss()
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun restoredLandscapeTourKeepsTheHighlightedNavigationClearOnItsFirstLayout() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val root = host(controller.get())
            val tour = MapTour(root, initialStep = 3) {}
            ViewCompat.dispatchApplyWindowInsets(tour, WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(0, 24, 0, 24)).build())
            layout(root, 800, 360)
            val target = Rect()
            val navigation = root.findViewById<View>(R.id.exploreNav)
            navigation.getDrawingRect(target)
            root.offsetDescendantRectToMyCoords(navigation, target)
            val card = Rect()
            tour.getChildAt(0).getDrawingRect(card)
            tour.offsetDescendantRectToMyCoords(tour.getChildAt(0), card)
            assertFalse("The first frame after restoring must keep the highlighted controls visible",
                Rect.intersects(target, card))
            tour.dismiss()
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun themesInsetsLandscapeAndLargeTextKeepNavigationOnScreen() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            for (night in listOf(false, true)) for (largeText in listOf(false, true)) {
                val root = host(controller.get(), night, if (largeText) 2f else 1f)
                for ((width, height) in listOf(360 to 800, 800 to 360)) {
                    val tour = MapTour(root) {}
                    ViewCompat.dispatchApplyWindowInsets(tour, WindowInsetsCompat.Builder()
                        .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(0, 24, 0, 24)).build())
                    repeat(6) { step ->
                        layout(root, width, height)
                        val next = tour.findViewById<Button>(R.id.tourNextButton)
                        val bounds = Rect()
                        next.getDrawingRect(bounds)
                        root.offsetDescendantRectToMyCoords(next, bounds)
                        assertTrue("Step $step navigation stays below status bar", bounds.top >= 24)
                        assertTrue("Step $step navigation stays above gesture bar", bounds.bottom <= height - 24)
                        assertTrue("Actions remain tappable at font scale $largeText", next.height >= 48)
                        if (!largeText) {
                            // Internal visual QA artifacts are picked up by the existing CI report upload.
                            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                            root.draw(Canvas(bitmap))
                            val output = File("build/reports/map-tour/${if (night) "dark" else "light"}-$width-step$step.png")
                            output.parentFile?.mkdirs()
                            output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                            bitmap.recycle()
                        }
                        if (step < 5) next.performClick()
                    }
                    tour.dismiss()
                }
            }
        } finally { controller.pause().stop().destroy() }
    }
}
