package com.roadconquest.app.util

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.roadconquest.app.map.MapMode
import com.roadconquest.app.map.PlaceOverlayMode
import com.roadconquest.app.progression.CarColor
import com.roadconquest.app.progression.CarStyle
import com.roadconquest.app.progression.Cosmetics
import com.roadconquest.app.progression.RoadColor
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37], manifest = Config.NONE)
class PreferenceLookupTest {
    private class CountingContext(base: Context) : ContextWrapper(base) {
        var acquisitions = 0
        var stringReads = 0
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            acquisitions++
            val delegate = super.getSharedPreferences(name, mode)
            return object : SharedPreferences by delegate {
                override fun getString(key: String?, defValue: String?): String? {
                    stringReads++
                    return delegate.getString(key, defValue)
                }
            }
        }
    }

    private fun <T> checkLookup(key: String, values: List<Pair<String, T>>, fallback: T, read: (Context) -> T) {
        val base: Context = RuntimeEnvironment.getApplication()
        val preferences = base.getSharedPreferences("roadconquest_preferences", Context.MODE_PRIVATE)
        val context = CountingContext(base)
        // Every saved value, unknown values, and removal; each successive call must
        // immediately see edits rather than retaining a process-wide cache.
        for ((saved, expected) in values + listOf("unknown-value" to fallback, null to fallback) + values.reversed()) {
            preferences.edit().putString(key, saved).commit()
            context.acquisitions = 0
            context.stringReads = 0
            assertEquals("$key=$saved", expected, read(context))
            assertEquals("preference acquisitions for $key=$saved", 1, context.acquisitions)
            assertEquals("string reads for $key=$saved", 1, context.stringReads)
        }
    }

    @Test fun themeReadsOneCurrentValue() = checkLookup(
        "ui_theme", UiTheme.entries.map { it.name to it }, UiTheme.SYSTEM, Prefs::uiTheme)

    @Test fun mapModeReadsOneCurrentValue() = checkLookup(
        "map_mode", MapMode.entries.map { it.name to it }, MapMode.STREETS, Prefs::mapMode)

    @Test fun overlayModeReadsOneCurrentValue() = checkLookup(
        "place_overlay_mode", PlaceOverlayMode.entries.map { it.name to it }, PlaceOverlayMode.NONE, Prefs::placeOverlayMode)

    @Test fun carStyleReadsOneCurrentValue() = checkLookup(
        "car_style", CarStyle.entries.map { it.id to it }, CarStyle.CLASSIC, Cosmetics::carStyle)

    @Test fun carColorReadsOneCurrentValue() = checkLookup(
        "car_color", CarColor.entries.map { it.id to it }, CarColor.BLUE, Cosmetics::carColor)

    @Test fun roadColorReadsOneCurrentValue() = checkLookup(
        "road_color", RoadColor.entries.map { it.id to it }, RoadColor.BLUE, Cosmetics::roadColor)
}
