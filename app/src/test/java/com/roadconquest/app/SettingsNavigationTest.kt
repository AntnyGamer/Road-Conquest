package com.roadconquest.app

import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
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
// Match the existing UI suites; actual native rendering is verified on devices.
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
class SettingsNavigationTest {
    @Test fun categoriesSearchAndBackKeepEveryControlReachable() {
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).create()
        try {
            val activity = controller.get()
            val home = activity.findViewById<LinearLayout>(R.id.settingsCategories)
            assertEquals(7, home.childCount)
            home.getChildAt(4).performClick()
            assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.backupSettingsCard).visibility)
            assertEquals(View.GONE, home.visibility)
            activity.findViewById<View>(R.id.backNavButton).performClick()
            assertFalse(activity.isFinishing)
            assertEquals(View.VISIBLE, home.visibility)
            val search = activity.findViewById<EditText>(R.id.settingsSearch)
            search.setText("dark")
            assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.mapSettingsCard).visibility)
            assertEquals(View.GONE, activity.findViewById<View>(R.id.backupSettingsCard).visibility)
            search.setText("not-a-real-setting")
            assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.settingsNoResults).visibility)
            activity.findViewById<View>(R.id.backNavButton).performClick()
            assertEquals("", search.text.toString())
            assertEquals(View.VISIBLE, home.visibility)
        } finally { controller.destroy() }
    }

    @Test fun selectedCategorySurvivesRecreation() {
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).create()
        val activity = controller.get()
        activity.findViewById<LinearLayout>(R.id.settingsCategories).getChildAt(4).performClick()
        val state = Bundle()
        controller.saveInstanceState(state).destroy()
        val restored = Robolectric.buildActivity(SettingsActivity::class.java).create(state)
        try {
            assertEquals(View.VISIBLE, restored.get().findViewById<View>(R.id.backupSettingsCard).visibility)
            assertEquals(View.GONE, restored.get().findViewById<View>(R.id.settingsCategories).visibility)
        } finally { restored.destroy() }
    }
}
