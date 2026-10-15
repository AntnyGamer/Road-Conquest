package com.roadconquest.app

import android.app.Activity
import com.roadconquest.app.ads.ScreenAds
import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.roadconquest.app.account.AccountClient
import com.roadconquest.app.account.AccountStore
import com.roadconquest.app.util.Appearance
import com.roadconquest.app.util.ForegroundSession
import com.roadconquest.app.util.FeatureFlags
import java.util.Locale
import java.util.concurrent.Executors

class LeaderboardActivity : Activity() {
    private lateinit var ads: ScreenAds
    private val executor = Executors.newSingleThreadExecutor()
    private var generation = 0
    private data class RankedUser(val rank: Long, val username: String, val cells: Long)
    private data class RankingDisplay(val message: String, val myUsername: String?, val rows: List<RankedUser>)

    override fun attachBaseContext(newBase: Context) { super.attachBaseContext(Appearance.wrap(newBase)) }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(Appearance.themeRes(this))
        super.onCreate(savedInstanceState)
        if (!FeatureFlags.LEADERBOARDS_VISIBLE) {
            finish()
            return
        }
        WindowCompat.enableEdgeToEdge(window)
        val lightSystemBars = !Appearance.isDark(this)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = lightSystemBars
            isAppearanceLightNavigationBars = lightSystemBars
        }
        setContentView(R.layout.activity_leaderboard)
        findViewById<Button>(R.id.backNavButton).setOnClickListener { finish() }
        ads = ScreenAds(this, findViewById(R.id.leaderboardRoot))
        val root = findViewById<View>(R.id.leaderboardRoot)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)
        findViewById<Button>(R.id.refreshRankingButton).setOnClickListener { refresh() }
    }

    override fun onStart() {
        super.onStart()
        ForegroundSession.app.onStart()
    }

    override fun onResume() {
        super.onResume()
        if (::ads.isInitialized) { refresh(); ads.onResume() }
    }

    private fun refresh() {
        val current = ++generation
        val message = findViewById<TextView>(R.id.rankingEntriesText)
        val container = findViewById<LinearLayout>(R.id.rankingRows)
        message.text = "Loading verified scores…"
        container.removeAllViews()
        executor.execute {
            val result = runCatching {
                val ranking = AccountClient.leaderboard()
                val session = AccountStore.load(this)
                val verificationAvailable = ranking.optBoolean("verification_available", true)
                val mine = if (verificationAvailable) session?.let {
                    runCatching { AccountClient.verifiedTotals(it.token) }.getOrNull()
                } else null
                val header = buildString {
                    if (!verificationAvailable) append("Verified scoring is not active yet. New drives cannot earn leaderboard credit until verification is available.\n")
                    if (mine != null) {
                        append(String.format(Locale.getDefault(), "Your verified score: %,d cells explored\n", mine.getLong("cells")))
                        if (session?.leaderboardVisible != true) append("Your profile is hidden.\n")
                    }
                }.trim()
                val entries = ranking.getJSONArray("entries")
                val users = ArrayList<RankedUser>(entries.length())
                for (i in 0 until entries.length()) {
                    val row = entries.getJSONObject(i)
                    users.add(RankedUser(row.getLong("rank"), row.getString("username"), row.getLong("cells")))
                }
                RankingDisplay(header.ifEmpty { if (users.isEmpty()) "No visible verified scores yet." else "" },
                    session?.username, users)
            }
            runOnUiThread {
                if (isDestroyed || current != generation) return@runOnUiThread
                result.fold({ display ->
                    message.text = display.message
                    display.rows.forEach { user ->
                        val card = LinearLayout(this).apply {
                            orientation = LinearLayout.VERTICAL
                            setBackgroundResource(R.drawable.settings_card_background)
                            setPadding(dp(16), dp(12), dp(16), dp(12))
                        }
                        card.addView(TextView(this).apply {
                            text = "${user.rank}.  ${user.username}" +
                                if (user.username == display.myUsername) "  •  You" else ""
                            textSize = 17f
                            setTypeface(typeface, android.graphics.Typeface.BOLD)
                            setTextColor(Appearance.color(this@LeaderboardActivity, R.attr.rcTextPrimary))
                        })
                        card.addView(TextView(this).apply {
                            text = String.format(Locale.getDefault(), "%,d verified cells", user.cells)
                            textSize = 14f
                            setTextColor(Appearance.color(this@LeaderboardActivity, R.attr.rcTextSecondary))
                        })
                        container.addView(card, LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply { bottomMargin = dp(8) })
                    }
                }, { error ->
                    message.text = if (error is AccountClient.ApiException && error.status == 404)
                        "Verified leaderboards are unavailable."
                    else "Could not load verified scores. Try Refresh."
                })
            }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + .5f).toInt()

    override fun onStop() {
        ForegroundSession.app.onStop(isChangingConfigurations)
        super.onStop()
    }

    override fun onPause() { if (::ads.isInitialized) ads.onPause(); super.onPause() }

    override fun onDestroy() { if (::ads.isInitialized) ads.destroy(); generation++; executor.shutdownNow(); super.onDestroy() }
}
