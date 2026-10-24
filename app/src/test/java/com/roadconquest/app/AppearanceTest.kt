package com.roadconquest.app

import android.content.res.Configuration
import android.graphics.Color
import android.view.ContextThemeWrapper
import android.widget.Button
import android.widget.Switch
import com.roadconquest.app.map.MapMode
import com.roadconquest.app.map.PlaceOverlayMode
import com.roadconquest.app.util.Appearance
import com.roadconquest.app.util.Prefs
import com.roadconquest.app.util.UiTheme
import com.roadconquest.app.util.StatsText
import com.roadconquest.app.data.DataSummary
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.SQLiteMode
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
class AppearanceTest {
    @Before fun setup() {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("roadconquest_preferences", 0).edit().clear().commit()
    }

    @Test fun settingsCanReplayTheTourAndChangeExitConfirmationWithoutResettingProgress() {
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).create().start().resume()
        try {
            val activity = controller.get()
            Prefs.setTourHandled(activity, true)
            Prefs.setManualOnly(activity, true)
            val confirmation = activity.findViewById<Switch>(R.id.exitConfirmationSwitch)
            assertTrue(confirmation.isChecked)
            confirmation.performClick()
            assertFalse(Prefs.isExitConfirmationEnabled(activity))
            activity.findViewById<Button>(R.id.quickTourButton).performClick()
            val intent = shadowOf(activity).nextStartedActivity
            assertEquals(MainActivity::class.java.name, intent.component?.className)
            assertTrue(intent.getBooleanExtra(MainActivity.EXTRA_SHOW_TOUR, false))
            assertTrue(Prefs.isTourHandled(activity))
            assertTrue(Prefs.isManualOnly(activity))
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun mapAndSettingsStatsHaveClearDistinctCategoriesWithoutChangingTotals() {
        val context = ContextThemeWrapper(
            Appearance.wrap(RuntimeEnvironment.getApplication()), R.style.Theme_RoadConquest
        )
        val summary = DataSummary(0, 0, null, null, 3218.688, 12)
        val compact = StatsText.mapOverview(context, summary, 18)
        assertTrue(compact, compact.startsWith("Driving"))
        assertTrue(compact, compact.contains("2.0 mi"))
        assertTrue(compact, compact.contains("12 roads"))
        assertTrue(compact, compact.contains("Explore"))
        assertTrue(compact, compact.contains("18 tiles"))
        assertEquals("Compact map overview should have only two short lines", 2, compact.lines().size)
        val progression = com.roadconquest.app.data.ProgressionSnapshot(
            balance = 1240L, lifetimeEarned = 1240L, pointsSpent = 0L,
            rewardedRoads = 12L, towns = 3L, states = 2L, countries = 1L,
            adsWatched = 0L, lowestBatteryPercent = 101,
            purchasedItems = emptySet(), rewardedAchievements = emptySet(),
            tilesUnlocked = 18L
        )
        val details = StatsText.settingsOverview(context, summary, progression)
        assertTrue(details, details.indexOf("DRIVING") < details.indexOf("EXPLORATION"))
        assertTrue(details, details.indexOf("EXPLORATION") < details.indexOf("PROGRESSION"))
        assertTrue(details, details.contains("18 mile tiles"))
        assertTrue(details, details.contains("3 towns"))
        assertTrue(details, details.contains("1,240 points"))
    }

    @Test fun lightAndDarkResolveMatchingColorsAndSystemFollowsThePhone() {
        val app = RuntimeEnvironment.getApplication()
        for (theme in listOf(UiTheme.LIGHT, UiTheme.DARK)) {
            Prefs.setUiTheme(app, theme)
            val context = ContextThemeWrapper(Appearance.wrap(app), R.style.Theme_RoadConquest)
            assertEquals(theme == UiTheme.DARK, Appearance.isDark(context))
            val text = context.getColor(R.color.text_primary)
            val background = context.getColor(R.color.screen_bg)
            assertEquals(theme == UiTheme.DARK, Color.red(text) > Color.red(background))
            assertEquals("1.0 mi traveled\n1 road unlocked", StatsText.format(context, DataSummary(0, 0, null, null, 1609.344, 1)))
            assertEquals("0.0 mi traveled\n2 roads unlocked", StatsText.format(context, DataSummary(0, 0, null, null, 0.0, 2)))
        }
        Prefs.setUiTheme(app, UiTheme.SYSTEM)
        assertSame(app, Appearance.wrap(app))
    }

    @Config(sdk = [31, 33, 37], manifest = Config.NONE)
    @Test fun settingsSelectAndPersistTerrainThenLaunchAppNotificationSettings() {
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).create().start().resume()
        try {
            val activity = controller.get()
            assertEquals(
                "Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                activity.findViewById<android.widget.TextView>(R.id.appVersionText).text.toString()
            )
            val mapButton = activity.findViewById<Button>(R.id.mapStyleButton)
            mapButton.performClick()
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            dialog.listView.performItemClick(dialog.listView.getChildAt(1), 1, 1)
            assertEquals(MapMode.TERRAIN, Prefs.mapMode(activity))
            assertTrue(mapButton.text.toString().contains("Satellite"))
            activity.findViewById<Button>(R.id.notificationSettingsButton).performClick()
            val intent = shadowOf(activity).nextStartedActivity
            assertEquals(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS, intent.action)
            assertEquals(activity.packageName, intent.getStringExtra(android.provider.Settings.EXTRA_APP_PACKAGE))
            assertTrue(activity.assets.open("satellite-credits.txt").bufferedReader().use { it.readText() }.contains("Esri"))
            activity.findViewById<Button>(R.id.githubButton).performClick()
            val github = shadowOf(activity).nextStartedActivity
            assertEquals(android.content.Intent.ACTION_VIEW, github.action)
            assertEquals("https://github.com/AntnyGamer/RoadConquest-Beta", github.data.toString())
            assertTrue(github.hasCategory(android.content.Intent.CATEGORY_BROWSABLE))
            activity.findViewById<Button>(R.id.supportDeveloperButton).performClick()
            val support = shadowOf(activity).nextStartedActivity
            assertEquals(android.content.Intent.ACTION_VIEW, support.action)
            assertEquals("https://buymeacoffee.com/antnygamer", support.data.toString())
            assertTrue(support.hasCategory(android.content.Intent.CATEGORY_BROWSABLE))
            assertTrue(activity.assets.open("licenses/RoadConquest-AGPL-3.0.txt").bufferedReader().use { it.readText() }
                .contains("GNU AFFERO GENERAL PUBLIC LICENSE"))
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun privacyPageOpensFromSettingsWithoutAThirdDeleteButton() {
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).create().start().resume()
        try {
            val activity = controller.get()
            activity.findViewById<Button>(R.id.privacyPolicyButton).performClick()
            assertTrue(shadowOf(activity).nextStartedActivity.data.toString().endsWith("/privacy"))
            assertEquals("Delete local data", activity.findViewById<Button>(R.id.deleteDeviceDataButton).text.toString())
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun placeOverlayPreferenceDefaultsToNoneAndPersistsOneMode() {
        val app = RuntimeEnvironment.getApplication()
        assertEquals(PlaceOverlayMode.NONE, Prefs.placeOverlayMode(app))
        Prefs.setPlaceOverlayMode(app, PlaceOverlayMode.STATE)
        assertEquals(PlaceOverlayMode.STATE, Prefs.placeOverlayMode(app))
        Prefs.setPlaceOverlayMode(app, PlaceOverlayMode.TOWN)
        assertEquals(PlaceOverlayMode.TOWN, Prefs.placeOverlayMode(app))
    }

    @Test fun unknownPreferencesFallBackAndAppKeepsItsUpgradeIdentity() {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("roadconquest_preferences", 0).edit().putString("ui_theme", "bad").putString("map_mode", "bad").commit()
        assertEquals(UiTheme.SYSTEM, Prefs.uiTheme(app))
        assertEquals(MapMode.STREETS, Prefs.mapMode(app))
        assertEquals("Road Conquest", app.getString(R.string.app_name))
        assertEquals("com.roadconquest.app", app.packageName)
        assertFalse(app.getString(R.string.stats_grouped_initial).contains("GPS"))
    }

    @Test fun fogSwitchPersistsWithoutChangingTrackingModeOrStoppingTracking() {
        val app = RuntimeEnvironment.getApplication()
        assertTrue(Prefs.isFogEnabled(app))
        Prefs.setManualOnly(app, false)
        TrackingService::class.java.getDeclaredField("isRunning").apply { isAccessible = true }.setBoolean(null, true)
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).create().start().resume()
        try {
            val activity = controller.get()
            val toggle = activity.findViewById<Switch>(R.id.fogSwitch)
            assertTrue(toggle.isChecked)
            toggle.performClick()
            assertFalse(Prefs.isFogEnabled(activity))
            assertFalse(Prefs.isManualOnly(activity))
            assertTrue(TrackingService.isRunning)
            assertNull(shadowOf(app).nextStoppedService)
            toggle.performClick()
            assertTrue(Prefs.isFogEnabled(activity))
            assertTrue(TrackingService.isRunning)
            assertNull(shadowOf(app).nextStoppedService)
        } finally {
            controller.pause().stop().destroy()
            TrackingService::class.java.getDeclaredField("isRunning").apply { isAccessible = true }.setBoolean(null, false)
        }
    }
}
