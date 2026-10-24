package com.roadconquest.app

import android.Manifest
import android.app.Activity
import com.roadconquest.app.ads.ScreenAds
import android.app.AlertDialog
import android.content.Context
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.roadconquest.app.account.AccountClient
import com.roadconquest.app.account.AccountStore
import com.roadconquest.app.data.LocalDataReset
import com.roadconquest.app.data.TrackingRepository
import com.roadconquest.app.export.DataExporter
import com.roadconquest.app.export.DataImporter
import com.roadconquest.app.util.Prefs
import com.roadconquest.app.util.Appearance
import com.roadconquest.app.util.UiTheme
import com.roadconquest.app.util.SystemSettingsNavigator
import com.roadconquest.app.util.StatsText
import com.roadconquest.app.util.ForegroundSession
import com.roadconquest.app.progression.ProgressionManager
import com.roadconquest.app.map.MapMode
import java.time.Instant
import java.util.concurrent.Executors
import android.util.Log

class SettingsActivity : Activity() {
    private fun setupOptionalNotifications() {
        val options = com.roadconquest.app.notifications.OptionalNotifications
        fun bind(id: Int, friends: Boolean) {
            findViewById<Switch>(id).apply {
                setOnCheckedChangeListener(null)
                isChecked = if (friends) options.friendsEnabled(this@SettingsActivity) else options.weeklyEnabled(this@SettingsActivity)
                setOnCheckedChangeListener { _, checked ->
                    if (friends) options.setFriendsEnabled(this@SettingsActivity, checked) else options.setWeeklyEnabled(this@SettingsActivity, checked)
                    if (checked && friends && AccountStore.load(this@SettingsActivity) == null) {
                        Toast.makeText(this@SettingsActivity, "Friend notifications will start after you sign in and allow notifications.", Toast.LENGTH_LONG).show()
                    }
                    if (checked && !options.permission(this@SettingsActivity)) {
                        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 9103)
                    }
                    setupOptionalNotifications()
                }
            }
        }
        bind(R.id.friendNotificationsSwitch, true)
        bind(R.id.weeklyRemindersSwitch, false)
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != 9103) return
        com.roadconquest.app.notifications.OptionalNotifications.initialize(this)
        setupOptionalNotifications()
    }
    private lateinit var ads: ScreenAds
    private var enteredForeground = false
    private var recreatingForAppearance = false
    override fun onStart() {
        super.onStart()
        enteredForeground = ForegroundSession.app.onStart()
    }

    override fun onStop() {
        ForegroundSession.app.onStop(isChangingConfigurations || recreatingForAppearance)
        super.onStop()
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(Appearance.wrap(newBase))
    }

    private lateinit var repository: TrackingRepository
    private lateinit var manualOnlySwitch: Switch
    private lateinit var summaryText: TextView
    private lateinit var accountSummaryText: TextView
    private lateinit var leaderboardPrivacySwitch: Switch
    private lateinit var deleteDeviceDataButton: Button
    private var renderingAccountPrivacy = false
    private var deletingDeviceData = false
    private val summaryExecutor = Executors.newSingleThreadExecutor()
    private val accountExecutor = Executors.newSingleThreadExecutor()
    private val dataExecutor = Executors.newSingleThreadExecutor()
    @Volatile private var summaryGeneration = 0
    private var appliedGoldUi = false

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(Appearance.themeRes(this))
        super.onCreate(savedInstanceState)
        appliedGoldUi = Prefs.isGoldUiEnabled(this)
        WindowCompat.enableEdgeToEdge(window)
        val lightSystemBars = !Appearance.isDark(this)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = lightSystemBars
            isAppearanceLightNavigationBars = lightSystemBars
        }
        setContentView(R.layout.activity_settings)
        findViewById<Button>(R.id.backNavButton).setOnClickListener { finish() }
        ads = ScreenAds(this, findViewById(R.id.settingsRoot))
        applySafeAreaInsets()
        setupAppearance()

        findViewById<Button>(R.id.quickTourButton).setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_SHOW_TOUR, true))
        }
        findViewById<Switch>(R.id.exitConfirmationSwitch).apply {
            isChecked = Prefs.isExitConfirmationEnabled(this@SettingsActivity)
            setOnCheckedChangeListener { _, checked ->
                Prefs.setExitConfirmationEnabled(this@SettingsActivity, checked)
            }
        }

        repository = TrackingRepository(this)
        manualOnlySwitch = findViewById(R.id.manualOnlySwitch)
        summaryText = findViewById(R.id.dataSummaryText)
        accountSummaryText = findViewById(R.id.accountSummaryText)
        leaderboardPrivacySwitch = findViewById(R.id.leaderboardPrivacySwitch)
        deleteDeviceDataButton = findViewById(R.id.deleteDeviceDataButton)
        deleteDeviceDataButton.isEnabled = false
        findViewById<TextView>(R.id.appVersionText).text =
            getString(R.string.app_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)

        manualOnlySwitch.isChecked = Prefs.isManualOnly(this)
        manualOnlySwitch.isEnabled = !Prefs.isDeviceDataDeletionPending(this)
        manualOnlySwitch.setOnCheckedChangeListener { _, checked ->
            Prefs.setManualOnly(this, checked)
            if (checked) {
                if (TrackingService.isRunning) stopService(Intent(this, TrackingService::class.java))
            } else {
                startAutomaticTrackingIfPossible()
            }
            refreshSummary()
        }

        findViewById<Button>(R.id.locationSettingsButton).setOnClickListener {
            SystemSettingsNavigator.open(this, SystemSettingsNavigator.Destination.LOCATION_PERMISSION)
        }
        findViewById<Button>(R.id.deviceLocationButton).setOnClickListener {
            SystemSettingsNavigator.open(this, SystemSettingsNavigator.Destination.DEVICE_LOCATION)
        }
        findViewById<Button>(R.id.notificationSettingsButton).setOnClickListener {
            SystemSettingsNavigator.open(this, SystemSettingsNavigator.Destination.NOTIFICATIONS)
        }

        findViewById<Button>(R.id.batterySettingsButton).setOnClickListener {
            openBatteryOptimizationSettings()
        }

        val destinations = listOf(
            R.id.accountButton to AccountActivity::class.java,
            R.id.communityButton to ForumActivity::class.java,
            R.id.achievementsButton to AchievementsActivity::class.java,
            R.id.shopButton to ShopActivity::class.java,
            R.id.tripHistoryButton to TripHistoryActivity::class.java,
            R.id.challengesButton to ChallengesActivity::class.java,
            R.id.importButton to RestoreActivity::class.java
        )
        for ((buttonId, activityClass) in destinations) {
            findViewById<Button>(buttonId).setOnClickListener {
                startActivity(Intent(this, activityClass))
            }
        }
        leaderboardPrivacySwitch.setOnCheckedChangeListener { _, visible ->
            if (!renderingAccountPrivacy) updateLeaderboardPrivacy(visible)
        }

        findViewById<Button>(R.id.privacyPolicyButton).setOnClickListener { openAccountPage("/privacy") }
        findViewById<Switch>(R.id.crashReportingSwitch).apply {
            isChecked = com.roadconquest.app.diagnostics.CrashReporting.enabled(this@SettingsActivity)
            setOnCheckedChangeListener { _, checked ->
                com.roadconquest.app.diagnostics.CrashReporting.setEnabled(this@SettingsActivity, checked)
            }
        }

        findViewById<Button>(R.id.githubButton).setOnClickListener {
            val url = getString(R.string.github_repository_url)
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)
            if (SystemSettingsNavigator.launchFirst(listOf(intent), ::startActivity) == null) {
                Toast.makeText(this, getString(R.string.github_unavailable, url), Toast.LENGTH_LONG).show()
            }
        }

        findViewById<Button>(R.id.supportDeveloperButton).setOnClickListener {
            val url = getString(R.string.support_developer_url)
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)
            if (SystemSettingsNavigator.launchFirst(listOf(intent), ::startActivity) == null) {
                Toast.makeText(this, getString(R.string.web_page_unavailable, url), Toast.LENGTH_LONG).show()
            }
        }

        findViewById<Button>(R.id.terrainCreditsButton).setOnClickListener {
            val credits = listOf("licenses/RoadConquest-AGPL-3.0.txt", "map-credits.txt", "satellite-credits.txt", "service-credits.txt").joinToString("\n\n") { asset ->
                assets.open(asset).bufferedReader().use { it.readText() }
            }
            AlertDialog.Builder(this).setTitle("Licenses and map credits").setMessage(credits)
                .setPositiveButton("Close", null).show()
        }

        deleteDeviceDataButton.setOnClickListener { showDeleteDeviceDataDialog() }

        findViewById<Button>(R.id.exportButton).setOnClickListener {
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/zip"
                putExtra(Intent.EXTRA_TITLE, "RoadConquest-data-${Instant.now().toString().take(10)}.zip")
            }
            try {
                startActivityForResult(intent, REQUEST_EXPORT)
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(this, "Android's file picker is unavailable on this device.", Toast.LENGTH_LONG).show()
            }
        }

    }

    override fun onResume() {
        super.onResume()
        ads.onResume()
        com.roadconquest.app.notifications.OptionalNotifications.initialize(this)
        setupOptionalNotifications()
        if (appliedGoldUi != Prefs.isGoldUiEnabled(this)) {
            recreate()
            return
        }
        if (enteredForeground && Prefs.shouldResumePausedTracking(this)) {
            Prefs.setTrackingPaused(this, false)
            startAutomaticTrackingIfPossible()
        }
        enteredForeground = false
        // Account settings can switch to manual tracking after deleting device history,
        // but never while an interrupted deletion is still pending.
        if (::manualOnlySwitch.isInitialized) {
            manualOnlySwitch.isChecked = Prefs.isManualOnly(this)
            manualOnlySwitch.isEnabled = !Prefs.isDeviceDataDeletionPending(this)
        }
        // If automatic mode was armed while system Location was off, Android 14+
        // could not legally start the location foreground service. Returning after enabling
        // Location is the first foreground-safe opportunity to start it.
        if (!Prefs.isManualOnly(this) && !Prefs.isDeviceDataDeletionPending(this) &&
            !Prefs.isTrackingPaused(this)
        ) {
            startAutomaticTrackingIfPossible()
        }
        if (::repository.isInitialized) refreshSummary()
        if (::accountSummaryText.isInitialized) refreshAccountControls()
    }

    @Deprecated("Used for a simple Storage Access Framework export on minSdk 31")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_EXPORT || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        Thread {
            val result = runCatching {
                contentResolver.openOutputStream(uri, "w")?.use { output ->
                    DataExporter.writeZip(this, repository, output)
                } ?: error("Could not open export destination")
            }
            var cleanupFailed = false
            if (result.isSuccess) {
                // A valid ZIP remains available even if the user reset progress
                // while exporting. Serialize only the backup-date update with
                // restore/deletion so a stale export cannot reappear after reset.
                synchronized(DataImporter.operationLock) {
                    if (repository.isHistoryCurrent() &&
                        !Prefs.isDeviceDataDeletionPending(this)
                    ) {
                        getSharedPreferences("roadconquest_export_history", MODE_PRIVATE)
                            .edit().putLong("last_completed_at", System.currentTimeMillis()).commit()
                    }
                }
            } else {
                // ACTION_CREATE_DOCUMENT creates the destination before writing.
                // Some SAF providers reject deletion; never silently imply that
                // a partial backup was removed if its cleanup did not succeed.
                cleanupFailed = !runCatching { contentResolver.delete(uri, null, null) > 0 }
                    .getOrDefault(false)
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                result.fold(
                    onSuccess = {
                        Toast.makeText(this, "Road Conquest data exported", Toast.LENGTH_SHORT).show()
                        refreshSummary()
                    },
                    onFailure = { error ->
                        val message = if (cleanupFailed) {
                            "Export failed. An incomplete backup may remain; delete it manually in Files."
                        } else "Export failed: ${error.message}"
                        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                    }
                )
            }
        }.start()
    }

    private fun applySafeAreaInsets() {
        val root = findViewById<View>(R.id.settingsRoot)
        val left = root.paddingLeft
        val top = root.paddingTop
        val right = root.paddingRight
        val bottom = root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val safe = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(left + safe.left, top + safe.top, right + safe.right, bottom + safe.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    private fun openAccountPage(path: String) {
        val url = AccountClient.publicPage(path)
        if (url == null) {
            Toast.makeText(this, "Road Conquest web pages are unavailable in this build.", Toast.LENGTH_LONG).show()
            return
        }
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)
        if (SystemSettingsNavigator.launchFirst(listOf(intent), ::startActivity) == null) {
            Toast.makeText(this, getString(R.string.web_page_unavailable, url), Toast.LENGTH_LONG).show()
        }
    }

    private fun openBatteryOptimizationSettings() {
        SystemSettingsNavigator.open(this, SystemSettingsNavigator.Destination.BATTERY)
    }

    private fun showDeleteDeviceDataDialog() {
        if (deletingDeviceData) return
        val density = resources.displayMetrics.density
        val fields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * density).toInt(), 0, (24 * density).toInt(), 0)
        }
        val confirmation = EditText(this).apply {
            hint = "Type the confirmation phrase exactly"
            isSingleLine = false
            maxLines = 3
            filters = arrayOf(android.text.InputFilter.LengthFilter(128))
            isSaveEnabled = false
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            contentDescription = "Delete local data confirmation phrase"
        }
        val error = TextView(this)
        fields.addView(confirmation)
        fields.addView(error)

        val dialog = AlertDialog.Builder(this)
            .setTitle("Delete local data?")
            .setMessage(
                "This permanently deletes saved Road Conquest progress on this phone, including trips, mileage, roads, explored places, points and cosmetics bought with points. Your cloud account and lifetime VIP purchase in Google Play remain; VIP can be restored through Google Play if needed. Tracking stops and verified-drive sharing turns off. Exported files must be deleted separately. Deleted progress cannot be recovered without an existing backup.\n\nType exactly:\n$DELETE_LOCAL_DATA_CONFIRMATION"
            )
            .setView(fields)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete local data", null)
            .create()

        dialog.setOnDismissListener { confirmation.text.clear() }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (confirmation.text.toString() != DELETE_LOCAL_DATA_CONFIRMATION) {
                    error.text = "Type the confirmation phrase exactly, including the period."
                    return@setOnClickListener
                }

                dialog.dismiss()
                deletingDeviceData = true
                deleteDeviceDataButton.isEnabled = false
                findViewById<Switch>(R.id.verifyDrivesSwitch).isEnabled = false
                dataExecutor.execute {
                    val result = runCatching {
                        // Persist the pending marker before local cleanup. If the process dies
                        // after confirmation, startup safely finishes the local wipe.
                        LocalDataReset.stopTracking(applicationContext)
                        LocalDataReset.clearStoppedData(applicationContext)
                    }
                    runOnUiThread {
                        if (isDestroyed) return@runOnUiThread
                        deletingDeviceData = false
                        manualOnlySwitch.isChecked = Prefs.isManualOnly(this)
                        manualOnlySwitch.isEnabled = !Prefs.isDeviceDataDeletionPending(this)
                        deleteDeviceDataButton.isEnabled =
                            !deletingDeviceData && !Prefs.isDeviceDataDeletionPending(this)
                        result.fold(
                            onSuccess = {
                                Toast.makeText(
                                    this,
                                    "All saved Road Conquest data on this phone deleted. Your account was kept. Tracking is off.",
                                    Toast.LENGTH_LONG
                                ).show()
                            },
                            onFailure = { failure ->
                                val message = if (Prefs.isDeviceDataDeletionPending(this)) {
                                    "Device cleanup will finish automatically when Road Conquest opens again."
                                } else {
                                    failure.message ?: "Could not delete local data. No data was deleted."
                                }
                                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                            }
                        )
                        refreshSummary()
                        refreshAccountControls()
                    }
                }
            }
        }
        dialog.show()
    }

    private fun setupAppearance() {
        findViewById<Switch>(R.id.fogSwitch).apply {
            isChecked = Prefs.isFogEnabled(this@SettingsActivity)
            setOnCheckedChangeListener { _, enabled -> Prefs.setFogEnabled(this@SettingsActivity, enabled) }
        }
        val mapButton = findViewById<Button>(R.id.mapStyleButton)
        mapButton.text = getString(R.string.map_style_label, Prefs.mapMode(this).label)
        mapButton.setOnClickListener {
            val modes = MapMode.entries
            AlertDialog.Builder(this).setTitle("Map style")
                .setSingleChoiceItems(modes.map { it.label }.toTypedArray(), modes.indexOf(Prefs.mapMode(this))) { dialog, index ->
                    Prefs.setMapMode(this, modes[index])
                    mapButton.text = getString(R.string.map_style_label, modes[index].label)
                    dialog.dismiss()
                }.setNegativeButton("Cancel", null).show()
        }
        val themeButton = findViewById<Button>(R.id.uiThemeButton)
        themeButton.text = getString(R.string.ui_theme_label, Prefs.uiTheme(this).label)
        themeButton.setOnClickListener {
            val themes = UiTheme.entries
            AlertDialog.Builder(this).setTitle("Appearance")
                .setSingleChoiceItems(themes.map { it.label }.toTypedArray(), themes.indexOf(Prefs.uiTheme(this))) { dialog, index ->
                    val changed = Prefs.uiTheme(this) != themes[index]
                    Prefs.setUiTheme(this, themes[index])
                    dialog.dismiss()
                    if (changed) {
                        recreatingForAppearance = true
                        recreate()
                    }
                }.setNegativeButton("Cancel", null).show()
        }
    }

    private fun refreshAccountControls() {
        val session = AccountStore.load(this)
        val accountConfigured = AccountClient.isConfigured()
        val verifySwitch = findViewById<Switch>(R.id.verifyDrivesSwitch)
        val verificationStatus = findViewById<TextView>(R.id.verificationStatusText)
        val leaderboardsButton = findViewById<Button>(R.id.leaderboardsButton)
        verifySwitch.setOnCheckedChangeListener(null)
        verifySwitch.isChecked = Prefs.isDriveVerificationEnabled(this)
        verifySwitch.isEnabled = session != null && accountConfigured &&
            !deletingDeviceData && !Prefs.isDeviceDataDeletionPending(this)
        verifySwitch.setOnCheckedChangeListener { _, enabled ->
            Prefs.setDriveVerificationEnabled(this, enabled)
        }
        val competitionVisible = com.roadconquest.app.util.FeatureFlags.LEADERBOARDS_VISIBLE
        val competitionVisibility = if (competitionVisible) View.VISIBLE else View.GONE
        leaderboardsButton.visibility = competitionVisibility
        leaderboardPrivacySwitch.visibility = competitionVisibility
        verifySwitch.visibility = competitionVisibility
        verificationStatus.visibility = competitionVisibility
        leaderboardsButton.setOnClickListener { startActivity(Intent(this, LeaderboardActivity::class.java)) }
        verificationStatus.text = if (accountConfigured) {
            "Checking verified scoring…"
        } else {
            "Verified scoring is unavailable."
        }
        if (accountConfigured && competitionVisible) {
            accountExecutor.execute {
                val status = runCatching { AccountClient.competition() }
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    verificationStatus.text = status.fold(
                        onSuccess = {
                            if (it.optBoolean("available")) "Verified scoring is available."
                            else "Verified scoring is unavailable."
                        },
                        onFailure = { "Could not check verified scoring." }
                    )
                }
            }
        }
        renderingAccountPrivacy = true
        accountSummaryText.text = when {
            session == null && !accountConfigured -> "Accounts are unavailable in this build."
            session == null -> "Not signed in."
            else -> "Signed in as " + session.username
        }
        leaderboardPrivacySwitch.isChecked = session?.leaderboardVisible == true
        leaderboardPrivacySwitch.isEnabled = session != null && accountConfigured
        deleteDeviceDataButton.isEnabled =
            !deletingDeviceData && !Prefs.isDeviceDataDeletionPending(this)
        renderingAccountPrivacy = false

        if (session == null || !accountConfigured) return
        accountExecutor.execute {
            val result = runCatching { AccountClient.me(session.token) }
            runOnUiThread {
                if (isDestroyed || AccountStore.load(this)?.token != session.token) return@runOnUiThread
                result.fold(
                    onSuccess = { account ->
                        val updated = AccountStore.refreshIfUnchanged(
                            this, session, account.username, account.leaderboardVisible
                        ) ?: return@runOnUiThread
                        renderingAccountPrivacy = true
                        accountSummaryText.text = "Signed in as " + updated.username
                        leaderboardPrivacySwitch.isChecked = updated.leaderboardVisible
                        leaderboardPrivacySwitch.isEnabled = true
                        renderingAccountPrivacy = false
                    },
                    onFailure = { error ->
                        if (error is AccountClient.ApiException && error.status == 401) {
                            AccountStore.clear(this)
                            verifySwitch.isChecked = false
                            verifySwitch.isEnabled = false
                            renderingAccountPrivacy = true
                            accountSummaryText.text = "Session expired. Open Account / sign in."
                            leaderboardPrivacySwitch.isChecked = false
                            leaderboardPrivacySwitch.isEnabled = false
                            deleteDeviceDataButton.isEnabled =
                                !deletingDeviceData && !Prefs.isDeviceDataDeletionPending(this)
                            renderingAccountPrivacy = false
                        }
                    }
                )
            }
        }
    }

    private fun updateLeaderboardPrivacy(visible: Boolean) {
        val session = AccountStore.load(this) ?: return
        leaderboardPrivacySwitch.isEnabled = false
        accountExecutor.execute {
            val result = runCatching { AccountClient.setLeaderboardVisible(session.token, visible) }
            runOnUiThread {
                if (isDestroyed || AccountStore.load(this)?.token != session.token) return@runOnUiThread
                result.fold(
                    onSuccess = { saved ->
                        AccountStore.updateIfToken(this, session.token) { current ->
                            current.copy(leaderboardVisible = saved)
                        } ?: return@runOnUiThread
                        renderingAccountPrivacy = true
                        leaderboardPrivacySwitch.isChecked = saved
                        renderingAccountPrivacy = false
                        leaderboardPrivacySwitch.isEnabled = true
                        Toast.makeText(this, "Leaderboard privacy saved.", Toast.LENGTH_SHORT).show()
                    },
                    onFailure = { error ->
                        renderingAccountPrivacy = true
                        leaderboardPrivacySwitch.isChecked = AccountStore.load(this)?.leaderboardVisible == true
                        renderingAccountPrivacy = false
                        leaderboardPrivacySwitch.isEnabled = true
                        Toast.makeText(
                            this,
                            error.message ?: "Could not save leaderboard privacy.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                )
            }
        }
    }

    private fun startAutomaticTrackingIfPossible() {
        if (Prefs.isDeviceDataDeletionPending(this) || Prefs.isTrackingPaused(this) ||
            TrackingService.isRunning ||
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            !getSystemService(LocationManager::class.java).isLocationEnabled
        ) return

        runCatching {
            ContextCompat.startForegroundService(this, Intent(this, TrackingService::class.java))
        }.onFailure {
            Toast.makeText(this, "Open Road Conquest and verify location permissions to start tracking.", Toast.LENGTH_LONG).show()
        }
    }

    private fun refreshSummary() {
        val generation = ++summaryGeneration
        val mode = if (Prefs.isManualOnly(this)) "Manual" else "Always"
        val precise = if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) "Granted" else "Missing"
        val background = if (checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED) "Granted" else "Missing"
        summaryExecutor.execute {
            if (generation != summaryGeneration) return@execute
            val result = runCatching {
                val summary = repository.getSummary()
                summary to ProgressionManager.sync(this, summary)
            }
            runOnUiThread {
                if (isDestroyed || generation != summaryGeneration) return@runOnUiThread
                result.fold(
                    onSuccess = { (summary, progression) ->
                        summaryText.text = buildString {
                            append(StatsText.settingsOverview(this@SettingsActivity, summary, progression))
                            append("\n\nTRACKING\n")
                            append("Mode: $mode\n")
                            append("Precise location: $precise\n")
                            append("Background permission: $background")
                            val lastExport = getSharedPreferences("roadconquest_export_history", MODE_PRIVATE)
                                .getLong("last_completed_at", 0L)
                            append("\n\nDATA & BACKUP\n")
                            append("Last successful progress export: ")
                            append(if (lastExport <= 0L) "Never (consider exporting a backup)"
                                else java.text.DateFormat.getDateTimeInstance().format(java.util.Date(lastExport)))
                        }
                    },
                    onFailure = { error ->
                        Log.e("RoadConquest", "Could not load settings summary", error)
                        summaryText.text = getString(R.string.saved_data_unavailable)
                    }
                )
            }
        }
    }

    override fun onPause() {
        ads.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        ads.destroy()
        summaryGeneration++
        summaryExecutor.shutdownNow()
        // Confirmed account/privacy writes must finish even if Settings closes immediately.
        // UI callbacks already ignore a destroyed Activity.
        accountExecutor.shutdown()
        // A confirmed privacy deletion must finish even if the Settings screen closes.
        // UI callbacks already ignore a destroyed Activity.
        dataExecutor.shutdown()
        super.onDestroy()
    }

    companion object {
        internal const val DELETE_LOCAL_DATA_CONFIRMATION =
            "I confirm I want to delete all of my local data."
        private const val REQUEST_EXPORT = 200
    }
}
