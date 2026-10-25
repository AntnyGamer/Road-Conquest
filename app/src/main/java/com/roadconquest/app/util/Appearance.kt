package com.roadconquest.app.util

import android.content.Context
import android.content.res.Configuration
import android.util.TypedValue
import androidx.annotation.AttrRes
import com.roadconquest.app.R

enum class UiTheme(val label: String) { SYSTEM("Use phone setting"), LIGHT("Light"), DARK("Dark") }

object Appearance {
    fun wrap(context: Context): Context {
        val selected = Prefs.uiTheme(context)
        val gold = Prefs.isGoldUiEnabled(context)
        if (selected == UiTheme.SYSTEM && !gold) return context
        val config = Configuration(context.resources.configuration)
        val night = when {
            gold -> Configuration.UI_MODE_NIGHT_NO
            selected == UiTheme.LIGHT -> Configuration.UI_MODE_NIGHT_NO
            selected == UiTheme.DARK -> Configuration.UI_MODE_NIGHT_YES
            else -> config.uiMode and Configuration.UI_MODE_NIGHT_MASK
        }
        config.uiMode = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
        return context.createConfigurationContext(config)
    }

    fun themeRes(context: Context): Int =
        if (Prefs.isGoldUiEnabled(context)) R.style.Theme_RoadConquest_Gold else R.style.Theme_RoadConquest

    fun color(context: Context, @AttrRes attribute: Int): Int {
        val value = TypedValue()
        check(context.theme.resolveAttribute(attribute, value, true))
        return if (value.resourceId != 0) context.getColor(value.resourceId) else value.data
    }

    fun mapSettingsIconColor(context: Context): Int = color(context, when (Prefs.uiTheme(context)) {
        UiTheme.LIGHT -> R.attr.rcTextPrimary
        UiTheme.DARK -> R.attr.rcAccent
        UiTheme.SYSTEM -> R.attr.rcTextSecondary
    })

    fun isDark(context: Context): Boolean =
        !Prefs.isGoldUiEnabled(context) &&
            context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
}
