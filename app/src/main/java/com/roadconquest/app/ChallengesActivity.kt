package com.roadconquest.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputFilter
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.roadconquest.app.account.AccountClient
import com.roadconquest.app.account.AccountStore
import com.roadconquest.app.data.WeeklyChallenges
import com.roadconquest.app.util.Appearance
import com.roadconquest.app.util.ForegroundSession
import com.roadconquest.app.util.FeatureFlags
import java.time.Instant
import java.util.Locale
import java.util.concurrent.Executors

class ChallengesActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private var generation = 0
    private lateinit var local: TextView
    private lateinit var ranking: TextView
    private lateinit var friends: LinearLayout
    private lateinit var status: TextView
    private lateinit var invite: Button
    private var changingFriend = false

    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()

    override fun attachBaseContext(newBase: Context) { super.attachBaseContext(Appearance.wrap(newBase)) }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(Appearance.themeRes(this))
        super.onCreate(savedInstanceState)
        WindowCompat.enableEdgeToEdge(window)
        // System bars sit on this screen's themed background, not the dark map fog.
        val lightSystemBars = !Appearance.isDark(this)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = lightSystemBars
            isAppearanceLightNavigationBars = lightSystemBars
        }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        setContentView(ScrollView(this).apply { addView(root) })
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val p = (20 * resources.displayMetrics.density).toInt()
            view.setPadding(p + safe.left, p + safe.top, p + safe.right, p + safe.bottom)
            insets
        }
        fun label(text: String, size: Float = 16f) = TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(Appearance.color(this@ChallengesActivity,
                if (size >= 22f) R.attr.rcTextPrimary else R.attr.rcTextSecondary))
            if (size >= 22f) setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(8), 0, dp(8))
            root.addView(this)
        }
        label("YOUR WEEK", 12f).apply {
            setTextColor(Appearance.color(this@ChallengesActivity, R.attr.rcAccent))
            letterSpacing = 0.14f
        }
        label("Weekly challenges", 28f)
        label("Weeks run Monday to Monday at 00:00 UTC. Local goals use new road and town discoveries. Points are added automatically once per goal.")
        local = label("Loading this week's progress…").apply {
            textSize = 15f
            setTextColor(Appearance.color(this@ChallengesActivity, R.attr.rcTextPrimary))
            setBackgroundResource(R.drawable.settings_card_background)
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        label("Friends", 22f)
        label("Add friends by username. Both people must accept the friendship.")
        if (FeatureFlags.LEADERBOARDS_VISIBLE) {
            label("Compete on this week's newly verified exploration cells. Each cell counts once. Hidden profiles do not appear. Imported history never earns competitive credit.")
        }
        ranking = label("").apply {
            visibility = if (FeatureFlags.LEADERBOARDS_VISIBLE) View.VISIBLE else View.GONE
            setBackgroundResource(R.drawable.settings_card_background)
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        status = label("")
        invite = Button(this).apply {
            text = "Add friend by username"
            setOnClickListener {
                if (AccountStore.load(this@ChallengesActivity) == null) {
                    startActivity(Intent(this@ChallengesActivity, AccountActivity::class.java))
                } else {
                    val input = EditText(this@ChallengesActivity).apply {
                        hint = "Road Conquest username"; setSingleLine(); filters = arrayOf(InputFilter.LengthFilter(24))
                    }
                    AlertDialog.Builder(this@ChallengesActivity).setTitle("Send friend request").setView(input)
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Request") { _, _ -> changeFriend(input.text.toString().trim(), "request") }.show()
                }
            }
            root.addView(this, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(8) })
        }
        friends = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.settings_card_background)
            setPadding(dp(16), dp(10), dp(16), dp(16))
            root.addView(this, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        }
        root.addView(Button(this, null, 0, R.style.RoadConquest_SettingsAction).apply {
            text = "Refresh"
            setOnClickListener { refresh() }
        }, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(12) })
    }

    private fun refresh() {
        val expected = ++generation
        val session = AccountStore.load(this)
        worker.execute {
            val goals = runCatching {
                val week = WeeklyChallenges.status(applicationContext)
                buildString {
                    append("Resets ${Instant.ofEpochMilli(week.endsAt)}\n\n")
                    week.challenges.forEach {
                        append("${it.title}\n${it.progress.coerceAtMost(it.target)} / ${it.target} · ${it.reward} points")
                        append(if (it.rewarded) " · Reward earned\n\n" else "\n\n")
                    }
                }
            }
            runOnUiThread {
                if (!isDestroyed && expected == generation) local.text = goals.getOrElse { "Could not load local challenges." }
            }
            if (session == null) {
                runOnUiThread {
                    if (!isDestroyed && expected == generation) {
                        status.text = "Sign in to add friends."
                        ranking.text = if (FeatureFlags.LEADERBOARDS_VISIBLE) "Sign in to view verified weekly rankings." else ""
                        invite.text = "Sign in"; friends.removeAllViews()
                    }
                }
                return@execute
            }
            val friendList = runCatching { AccountClient.friends(session.token).getJSONArray("friends") }
            val standings = if (FeatureFlags.LEADERBOARDS_VISIBLE) runCatching { AccountClient.friendWeek(session.token) } else null
            runOnUiThread {
                if (isDestroyed || expected != generation || AccountStore.load(this)?.token != session.token) return@runOnUiThread
                invite.text = "Add friend by username"
                friends.removeAllViews()
                status.text = ""
                friendList.fold({ rows ->
                    for (i in 0 until rows.length()) {
                        val row = rows.getJSONObject(i)
                        val username = row.getString("username")
                        val relation = row.getString("status")
                        friends.addView(TextView(this).apply {
                            text = "$username · $relation"
                            textSize = 16f
                            setTextColor(Appearance.color(this@ChallengesActivity, R.attr.rcTextPrimary))
                            setPadding(0, dp(12), 0, dp(6))
                        })
                        if (relation == "incoming") friends.addView(Button(this).apply {
                            text = "Accept $username"; isEnabled = !changingFriend
                            setOnClickListener { changeFriend(username, "accept") }
                        })
                        friends.addView(Button(this).apply {
                            text = when (relation) { "accepted" -> "Remove $username"; "incoming" -> "Decline $username"; else -> "Cancel request to $username" }
                            isEnabled = !changingFriend
                            setOnClickListener {
                                AlertDialog.Builder(this@ChallengesActivity).setTitle(text).setMessage("End this friendship or request?")
                                    .setNegativeButton("Keep", null)
                                    .setPositiveButton("Remove") { _, _ -> changeFriend(username, "remove") }.show()
                            }
                        })
                    }
                    if (rows.length() == 0) friends.addView(TextView(this).apply { text = "No friends or requests yet." })
                }, { status.text = serviceError(it) })
                ranking.text = standings?.fold({ body ->
                    if (!body.optBoolean("available")) "Verified friend competitions are not active on the server yet. Local challenges still work."
                    else buildString {
                        append("New verified cells · week of ${body.getString("week_start")} UTC\n\n")
                        if (!session.leaderboardVisible) append("Your profile is hidden. Change leaderboard visibility in Settings to appear.\n\n")
                        val rows = body.getJSONArray("entries")
                        if (rows.length() == 0) append("No visible verified participants yet.")
                        for (i in 0 until rows.length()) {
                            val row = rows.getJSONObject(i)
                            append(String.format(Locale.getDefault(), "%d. %s · %d new cells\n",
                                row.getLong("rank"), row.getString("username"), row.getLong("cells")))
                        }
                    }
                }, { serviceError(it) }).orEmpty()
            }
        }
    }

    private fun serviceError(error: Throwable): String =
        if (error is AccountClient.ApiException && error.status == 401) "Session expired. Sign in again."
        else if (error is AccountClient.ApiException && error.status == 404) "Friends require the updated account server."
        else "Could not reach the friends service. Try Refresh."

    private fun changeFriend(username: String, action: String) {
        if (changingFriend) return
        if (!username.matches(Regex("[A-Za-z0-9_]{3,24}"))) { status.text = "Enter a valid 3–24 character username."; return }
        val session = AccountStore.load(this) ?: return
        changingFriend = true; invite.isEnabled = false
        status.text = "Updating friendship…"
        worker.execute {
            val result = runCatching { AccountClient.changeFriend(session.token, username, action) }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                changingFriend = false; invite.isEnabled = true
                if (AccountStore.load(this)?.token != session.token) { refresh(); return@runOnUiThread }
                status.text = result.fold({ "Friendship updated." }, { serviceError(it) })
                refresh()
            }
        }
    }

    override fun onStart() { super.onStart(); ForegroundSession.app.onStart() }
    override fun onResume() { super.onResume(); refresh() }
    override fun onStop() { ForegroundSession.app.onStop(isChangingConfigurations); super.onStop() }
    override fun onDestroy() { generation++; worker.shutdownNow(); super.onDestroy() }
}
