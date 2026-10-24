package com.roadconquest.app

import android.app.Activity
import android.widget.Button
import android.widget.CheckBox
import android.widget.TextView
import com.roadconquest.app.onboarding.ExitConfirmation
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class ExitConfirmationTest {
    @Before fun reset() {
        RuntimeEnvironment.getApplication().getSharedPreferences("roadconquest_preferences", 0).edit().clear().commit()
    }

    @Test fun stayAndCancelKeepBothTrackingAndConfirmationChoices() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get().apply { setTheme(R.style.Theme_RoadConquest) }
            Prefs.setManualOnly(activity, true)
            Prefs.setTrackingPaused(activity, true)
            for (cancel in listOf(false, true)) {
                val dialog = ExitConfirmation.show(activity, true) { fail("Stay must not leave the map") }
                assertEquals(activity.getString(R.string.exit_tracking_active), dialog.findViewById<TextView>(R.id.exitTrackingStatus).text)
                ExitConfirmation.updateTracking(dialog, false)
                assertEquals(activity.getString(R.string.exit_tracking_off), dialog.findViewById<TextView>(R.id.exitTrackingStatus).text)
                dialog.findViewById<CheckBox>(R.id.exitDontAsk).isChecked = true
                if (cancel) dialog.cancel() else dialog.findViewById<Button>(R.id.exitStayButton).performClick()
                assertTrue(Prefs.isExitConfirmationEnabled(activity))
                assertTrue(Prefs.isTrackingPaused(activity))
                assertTrue(Prefs.isManualOnly(activity))
            }
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun leavingWithDontAskAgainPersistsOnlyThePromptPreference() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get().apply { setTheme(R.style.Theme_RoadConquest) }
            var leaves = 0
            val dialog = ExitConfirmation.show(activity, false) { leaves++ }
            assertEquals(activity.getString(R.string.exit_tracking_off), dialog.findViewById<TextView>(R.id.exitTrackingStatus).text)
            dialog.findViewById<CheckBox>(R.id.exitDontAsk).isChecked = true
            dialog.findViewById<Button>(R.id.exitLeaveButton).performClick()
            dialog.findViewById<Button>(R.id.exitLeaveButton).performClick()
            assertFalse(dialog.isShowing)
            assertEquals(1, leaves)
            assertFalse(Prefs.isExitConfirmationEnabled(activity))
            assertFalse(Prefs.isManualOnly(activity))
            assertFalse(Prefs.isTrackingPaused(activity))
            Prefs.setExitConfirmationEnabled(activity, true)
            assertTrue(Prefs.isExitConfirmationEnabled(activity))
        } finally { controller.pause().stop().destroy() }
    }
}
