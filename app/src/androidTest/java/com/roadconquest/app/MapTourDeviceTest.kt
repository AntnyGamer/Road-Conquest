package com.roadconquest.app

import android.Manifest
import android.app.Dialog
import android.os.Build
import android.os.SystemClock
import android.view.KeyEvent
import android.widget.Button
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.roadconquest.app.onboarding.MapTour
import com.roadconquest.app.util.Prefs
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Covers the actual Activity lifecycle and system Back dispatch on the CI device matrix. */
@RunWith(AndroidJUnit4::class)
class MapTourDeviceTest {
    @get:Rule val permissions: GrantPermissionRule = GrantPermissionRule.grant(*buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray())

    @Test fun tourSurvivesRotationAndSystemBackReturnsToThePreviousStepThenShowsExitChoice() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        Prefs.setTourHandled(context, false)
        Prefs.setManualOnly(context, true)
        Prefs.setAccountPromptShown(context, true)
        Prefs.setBackgroundPromptShown(context, true)
        Prefs.setExitConfirmationEnabled(context, true)
        fun tour(activity: MainActivity): MapTour {
            val root = activity.findViewById<FrameLayout>(R.id.root)
            return root.getChildAt(root.childCount - 1) as MapTour
        }
        fun pressSystemBack(scenario: ActivityScenario<MainActivity>) {
            // RESUMED does not guarantee window focus after recreation. Injecting Back
            // before the new window receives focus can send it to the outgoing window.
            val deadline = SystemClock.elapsedRealtime() + 5_000L
            var focused = false
            do {
                scenario.onActivity { focused = it.hasWindowFocus() }
                if (!focused) SystemClock.sleep(25)
            } while (!focused && SystemClock.elapsedRealtime() < deadline)
            assertTrue("The map window must receive focus before sending system Back", focused)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    tour(activity).apply {
                        findViewById<Button>(R.id.tourDemoButton).performClick()
                        repeat(2) { findViewById<Button>(R.id.tourNextButton).performClick() }
                    }
                }
                scenario.recreate()
                scenario.onActivity { activity ->
                    assertEquals(2, tour(activity).step)
                    assertTrue(tour(activity).previewRevealed)
                    assertFalse(Prefs.isTourHandled(activity))
                }
                // Send a real Back key to the foreground window. An accessibility global action can
                // report success even when the system consumes it instead of dispatching Back.
                pressSystemBack(scenario)
                val deadline = SystemClock.elapsedRealtime() + 5_000L
                var returned = false
                do {
                    scenario.onActivity { returned = tour(it).step == 1 }
                    if (!returned) SystemClock.sleep(25)
                } while (!returned && SystemClock.elapsedRealtime() < deadline)
                assertTrue("System Back must navigate within the guide on every supported Android version", returned)
                scenario.onActivity { activity ->
                    tour(activity).findViewById<Button>(R.id.tourSkipButton).performClick()
                    assertTrue(Prefs.isTourHandled(activity))
                }
                // The same foreground Back-key dispatch also verifies the exit prompt.
                pressSystemBack(scenario)
                val exitDeadline = SystemClock.elapsedRealtime() + 5_000L
                var prompted = false
                do {
                    scenario.onActivity { activity ->
                        val dialog = MainActivity::class.java.getDeclaredField("exitPrompt")
                            .apply { isAccessible = true }.get(activity) as? Dialog
                        prompted = dialog?.isShowing == true
                        if (prompted) dialog!!.findViewById<Button>(R.id.exitStayButton).performClick()
                    }
                    if (!prompted) SystemClock.sleep(25)
                } while (!prompted && SystemClock.elapsedRealtime() < exitDeadline)
                assertTrue("System Back from the map must show the enabled exit confirmation", prompted)
            }
        } finally {
            Prefs.setTourHandled(context, true)
        }
    }
}
