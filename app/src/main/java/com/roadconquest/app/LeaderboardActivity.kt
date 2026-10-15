package com.roadconquest.app

import android.app.Activity
import com.roadconquest.app.ads.ScreenAds
import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.Button
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
        val entries = findViewById<TextView>(R.id.rankingEntriesText)
        entries.text = "Loading verified scores…"
        executor.execute {
            val result = runCatching {
                val ranking = AccountClient.leaderboard()
                val session = AccountStore.load(this)
                val verificationAvailable = ranking.optBoolean("verification_available", true)
                val mine = if (verificationAvailable) session?.let { runCatching { AccountClient.verifiedTotals(it.token) }.getOrNull() } else null
                buildString {
                    if (!verificationAvailable) append("Verified scoring is not active yet. New drives cannot earn leaderboard credit until verification is available.\n\n")
                    if (mine != null) {
                        append(String.format(Locale.getDefault(), "Your verified score: %d cells explored\n", mine.getLong("cells")))
                        if (session?.leaderboardVisible != true) append("Your profile is hidden.\n")
                        append("\n")
                    }
                    val rows = ranking.getJSONArray("entries")
                    if (rows.length() == 0) append("No visible verified scores yet.")
                    for (i in 0 until rows.length()) {
                        val row = rows.getJSONObject(i)
                        append(String.format(Locale.getDefault(), "%d. %s\n%d cells explored\n\n",
                            row.getLong("rank"), row.getString("username"), row.getLong("cells")))
                    }
                }
            }
            runOnUiThread {
                if (!isDestroyed && current == generation) entries.text = result.getOrElse {
                    if (it is AccountClient.ApiException && it.status == 404) "Verified leaderboards are unavailable."
                    else "Could not load verified scores. Try Refresh."
                }
            }
        }
    }

    override fun onStop() {
        ForegroundSession.app.onStop(isChangingConfigurations)
        super.onStop()
    }

    override fun onPause() { if (::ads.isInitialized) ads.onPause(); super.onPause() }

    override fun onDestroy() { if (::ads.isInitialized) ads.destroy(); generation++; executor.shutdownNow(); super.onDestroy() }
}
