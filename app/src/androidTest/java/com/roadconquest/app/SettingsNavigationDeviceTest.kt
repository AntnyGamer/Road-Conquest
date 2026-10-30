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
            val preview = Bitmap.createBitmap(440, 170, Bitmap.Config.ARGB_8888)
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
