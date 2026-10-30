package com.roadconquest.app

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
