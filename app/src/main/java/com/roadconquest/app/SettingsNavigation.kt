package com.roadconquest.app

import android.app.Activity
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.text.SpannableString
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.StyleSpan
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.roadconquest.app.util.Appearance
import java.util.Locale

/** Category navigation changes visibility only: all existing controls retain their bindings. */
internal class SettingsNavigation(private val activity: Activity, state: Bundle?) {
    companion object { private val WORD_SEPARATOR = Regex("\\s+") }
    private data class Section(val id: Int, val title: String, val detail: String, val icon: Int, val keywords: String)
    private val sections = listOf(
        Section(R.id.trackingSettingsCard, "Tracking", "Location, battery and tracking behavior", R.drawable.ic_my_location, "gps automatic manual enable permission device exit confirmation screen awake display"),
        Section(R.id.mapSettingsCard, "Map & appearance", "Map styles, medals, following, distance units and fog", R.drawable.ic_map_day, "theme satellite night streets minimal phone medals badges follow car center miles kilometers units"),
        Section(R.id.accountSettingsCard, "Account & community", "Sign-in, profile and the community forum", R.drawable.ic_privacy, "login password username friends leaderboard verify scoring"),
        Section(R.id.privacySettingsCard, "Notifications & privacy", "Friend alerts, weekly reminders and privacy", R.drawable.ic_notifications, "crash reporting diagnostics policy data sharing posts"),
        Section(R.id.backupSettingsCard, "Backups & data", "Export, restore and manage saved progress", R.drawable.ic_export, "zip rcbackup encrypted passphrase import delete reset phone transfer"),
        Section(R.id.progressSettingsCard, "Your progress", "Achievements, town badges, trips and Garage", R.drawable.ic_nav_weekly, "statistics points shop cosmetics history goals friends bronze silver gold tiles"),
        Section(R.id.aboutSettingsCard, "Help & about", "Quick tour, app version, credits and support", R.drawable.ic_info, "github licenses developer coffee tutorial"))
    private val search = activity.findViewById<EditText>(R.id.settingsSearch)
    private val categories = activity.findViewById<LinearLayout>(R.id.settingsCategories)
    private val title = activity.findViewById<TextView>(R.id.settingsTitle)
    private val subtitle = activity.findViewById<TextView>(R.id.settingsSubtitle)
    private val scroll = activity.findViewById<ScrollView>(R.id.settingsScroll)
    private var selected = state?.getInt("settings_section", 0) ?: 0

    init {
        for (section in sections) {
            val button = Button(activity, null, 0, R.style.RoadConquest_SettingsAction).apply {
                text = SpannableString("${section.title}\n${section.detail}").apply {
                    setSpan(StyleSpan(android.graphics.Typeface.BOLD), 0, section.title.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    setSpan(AbsoluteSizeSpan(13, true), section.title.length + 1, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                textSize = 15f
                gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
                minHeight = dp(80)
                setPadding(dp(16), dp(12), dp(16), dp(12))
                setCompoundDrawablesRelativeWithIntrinsicBounds(section.icon, 0, R.drawable.ic_forward, 0)
                compoundDrawablePadding = dp(12)
                compoundDrawableTintList = android.content.res.ColorStateList.valueOf(Appearance.color(activity, R.attr.rcAccent))
                contentDescription = "${section.title}. ${section.detail}"
                tag = section.id
                setOnClickListener { selected = section.id; render(); scroll.smoothScrollTo(0, 0) }
            }
            categories.addView(button, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }
        // This helper restores query and category together; framework EditText restoration
        // must not fire a later watcher and unexpectedly return to the category list.
        search.isSaveEnabled = false
        search.setText(state?.getString("settings_query").orEmpty())
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { selected = 0; render() }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        render()
    }

    private fun render() {
        val query = search.text.toString().trim().lowercase(Locale.ROOT)
        val searching = query.isNotEmpty()
        val words = if (searching) query.split(WORD_SEPARATOR) else emptyList()
        val chosen = sections.firstOrNull { it.id == selected }
        val home = chosen == null && query.isEmpty()
        categories.visibility = if (home) View.VISIBLE else View.GONE
        var matches = 0
        for (section in sections) {
            val visible = if (searching) {
                val haystack = "${section.title} ${section.detail} ${section.keywords} ${contentText(activity.findViewById(section.id))}".lowercase(Locale.ROOT)
                words.all { it in haystack }
            } else section == chosen
            activity.findViewById<View>(section.id).visibility = if (visible) View.VISIBLE else View.GONE
            if (visible) matches++
        }
        title.text = when { searching -> "Search results"; chosen != null -> chosen.title; else -> "Settings" }
        subtitle.text = when {
            searching -> "Matching categories for “${search.text.toString().trim()}”"
            chosen != null -> chosen.detail
            else -> "Make Road Conquest yours. Choose a category or search below."
        }
        activity.findViewById<View>(R.id.settingsNoResults).visibility =
            if (!home && matches == 0) View.VISIBLE else View.GONE
    }

    fun back(): Boolean {
        if (search.text.isNotEmpty()) { search.text.clear(); selected = 0; render(); return true }
        if (selected != 0) { selected = 0; render(); scroll.smoothScrollTo(0, 0); return true }
        return false
    }

    fun save(state: Bundle) {
        state.putInt("settings_section", selected)
        state.putString("settings_query", search.text.toString())
    }

    private fun contentText(view: View): String = when (view) {
        is TextView -> view.text.toString()
        is android.view.ViewGroup -> (0 until view.childCount).joinToString(" ") { contentText(view.getChildAt(it)) }
        else -> ""
    }

    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density + 0.5f).toInt()
}
