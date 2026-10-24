package com.roadconquest.app.util

import android.content.Context
import com.roadconquest.app.map.MapMode
import com.roadconquest.app.map.PlaceOverlayMode

object Prefs {
    private const val FILE = "roadconquest_preferences"
    private const val KEY_MANUAL_ONLY = "manual_only"
    private const val KEY_EVER_STARTED = "ever_started"
    private const val KEY_BACKGROUND_PROMPT_SHOWN = "background_prompt_shown"
    private const val KEY_LOCATION_DISCLOSURE_SHOWN = "location_disclosure_shown"
    private const val KEY_FOG_ENABLED = "fog_enabled"
    private const val KEY_TRACKING_PAUSED = "tracking_paused_until_open"
    private const val KEY_ACCOUNT_PROMPT_SHOWN = "account_prompt_shown"
    private const val KEY_DEVICE_DATA_DELETION_PENDING = "device_data_deletion_pending"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun uiTheme(context: Context): UiTheme {
        val saved = prefs(context).getString("ui_theme", null)
        return UiTheme.entries.firstOrNull { it.name == saved } ?: UiTheme.SYSTEM
    }

    fun setUiTheme(context: Context, theme: UiTheme) {
        prefs(context).edit().putString("ui_theme", theme.name).apply()
    }

    fun mapMode(context: Context): MapMode {
        val saved = prefs(context).getString("map_mode", null)
        return MapMode.entries.firstOrNull { it.name == saved } ?: MapMode.STREETS
    }

    fun setMapMode(context: Context, mode: MapMode) {
        prefs(context).edit().putString("map_mode", mode.name).apply()
    }

    fun placeOverlayMode(context: Context): PlaceOverlayMode {
        val saved = prefs(context).getString("place_overlay_mode", null)
        return PlaceOverlayMode.entries.firstOrNull { it.name == saved } ?: PlaceOverlayMode.NONE
    }

    fun setPlaceOverlayMode(context: Context, mode: PlaceOverlayMode) {
        prefs(context).edit().putString("place_overlay_mode", mode.name).apply()
    }

    fun isFogEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_FOG_ENABLED, true)

    fun setFogEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_FOG_ENABLED, enabled).apply()
    }

    fun isManualOnly(context: Context): Boolean = prefs(context).getBoolean(KEY_MANUAL_ONLY, false)

    fun setManualOnly(context: Context, value: Boolean) {
        // Tracking-mode changes must survive an abrupt process death.
        prefs(context).edit().putBoolean(KEY_MANUAL_ONLY, value).commit()
    }

    fun isTrackingPaused(context: Context): Boolean = prefs(context).getBoolean(KEY_TRACKING_PAUSED, false)

    fun isDeviceDataDeletionPending(context: Context): Boolean =
        prefs(context).getBoolean(KEY_DEVICE_DATA_DELETION_PENDING, false)

    @Synchronized
    fun setDeviceDataDeletionPending(context: Context, pending: Boolean) {
        val preferences = prefs(context)
        val editor = preferences.edit().putBoolean(KEY_DEVICE_DATA_DELETION_PENDING, pending)
        if (pending) {
            // Mark deletion and revoke precise-GPS consent in the SAME durable
            // transaction. Other consent toggles take this object lock, so an
            // opt-in that started before deletion cannot commit after the marker.
            editor.putBoolean("verify_drives", false)
                .putLong("verification_consent_version",
                    preferences.getLong("verification_consent_version", 0L) + 1L)
        }
        check(editor.commit()) { "Could not persist local deletion state" }
    }

    fun shouldResumePausedTracking(context: Context): Boolean =
        isTrackingPaused(context) && !isDeviceDataDeletionPending(context)

    fun setTrackingPaused(context: Context, paused: Boolean) {
        // Persist before stopping the service so a process restart cannot undo the stop.
        prefs(context).edit().putBoolean(KEY_TRACKING_PAUSED, paused).commit()
    }

    fun markEverStarted(context: Context) {
        prefs(context).edit().putBoolean(KEY_EVER_STARTED, true).apply()
    }

    fun hasEverStarted(context: Context): Boolean = prefs(context).getBoolean(KEY_EVER_STARTED, false)

    fun isDriveVerificationEnabled(context: Context): Boolean = prefs(context).let {
        it.getBoolean("verify_drives", false) && it.getInt("verification_policy", 0) == 3
    }

    @Synchronized
    fun setDriveVerificationEnabled(context: Context, enabled: Boolean) {
        // Deletion revokes consent. A Settings callback during an in-progress
        // wipe must never opt the account back into precise-GPS sharing.
        val permitted = enabled && !isDeviceDataDeletionPending(context)
        // Serialize consent updates so successive toggles cannot reuse the same
        // version and accidentally authorize already prepared location evidence.
        val preferences = prefs(context)
        preferences.edit().putBoolean("verify_drives", permitted)
            .putInt("verification_policy", if (permitted) 3 else 0)
            .putLong("verification_consent_version",
                preferences.getLong("verification_consent_version", 0L) + 1L).commit()
    }

    fun driveVerificationConsentVersion(context: Context): Long = prefs(context).getLong("verification_consent_version", 0L)

    fun isLocationDisclosureShown(context: Context): Boolean =
        prefs(context).getBoolean(KEY_LOCATION_DISCLOSURE_SHOWN, false)

    fun setLocationDisclosureShown(context: Context, shown: Boolean) {
        prefs(context).edit().putBoolean(KEY_LOCATION_DISCLOSURE_SHOWN, shown).apply()
    }

    fun isBackgroundPromptShown(context: Context): Boolean =
        prefs(context).getBoolean(KEY_BACKGROUND_PROMPT_SHOWN, false)

    fun setBackgroundPromptShown(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_BACKGROUND_PROMPT_SHOWN, value).apply()
    }

    fun isAccountPromptShown(context: Context): Boolean = prefs(context).getBoolean(KEY_ACCOUNT_PROMPT_SHOWN, false)

    fun setAccountPromptShown(context: Context, shown: Boolean) {
        prefs(context).edit().putBoolean(KEY_ACCOUNT_PROMPT_SHOWN, shown).apply()
    }

    fun isTourHandled(context: Context): Boolean = prefs(context).getBoolean("map_tour_handled", false)

    fun setTourHandled(context: Context, handled: Boolean) {
        prefs(context).edit().putBoolean("map_tour_handled", handled).apply()
    }

    fun isExitConfirmationEnabled(context: Context): Boolean = prefs(context).getBoolean("confirm_map_exit", true)

    fun setExitConfirmationEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("confirm_map_exit", enabled).apply()
    }

    fun carStyle(context: Context): String = prefs(context).getString("car_style", "classic") ?: "classic"
    fun setCarStyle(context: Context, value: String) {
        prefs(context).edit().putString("car_style", value).apply()
    }

    fun carColor(context: Context): String = prefs(context).getString("car_color", "blue") ?: "blue"
    fun setCarColor(context: Context, value: String) {
        prefs(context).edit().putString("car_color", value).apply()
    }

    fun roadColor(context: Context): String = prefs(context).getString("road_color", "blue") ?: "blue"
    fun setRoadColor(context: Context, value: String) {
        prefs(context).edit().putString("road_color", value).apply()
    }

    fun isGoldUiEnabled(context: Context): Boolean = prefs(context).getBoolean("gold_ui_enabled", false)
    fun setGoldUiEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("gold_ui_enabled", enabled).commit()
    }

    fun resetCosmetics(context: Context) {
        // Device-data deletion promises these local selections are gone when it returns.
        prefs(context).edit()
            .remove("car_style")
            .remove("car_color")
            .remove("road_color")
            .remove("gold_ui_enabled")
            .putString("place_overlay_mode", PlaceOverlayMode.NONE.name)
            .commit()
    }
}
