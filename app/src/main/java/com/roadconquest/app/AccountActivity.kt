package com.roadconquest.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.text.method.PasswordTransformationMethod
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.roadconquest.app.account.AccountClient
import com.roadconquest.app.account.AccountStore
import com.roadconquest.app.util.Appearance
import com.roadconquest.app.util.ForegroundSession
import java.util.concurrent.Executors

class AccountActivity : Activity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var statusText: TextView
    private lateinit var authGroup: View
    private lateinit var signedInGroup: View
    private lateinit var usernameInput: EditText
    private lateinit var passwordInput: EditText
    private lateinit var confirmPasswordInput: EditText
    private lateinit var loginButton: Button
    private lateinit var signupButton: Button
    private lateinit var signedInText: TextView
    private lateinit var leaderboardSwitch: Switch
    private lateinit var logoutButton: Button
    private lateinit var changeUsernameButton: Button
    private lateinit var deleteAccountButton: Button
    private var rendering = false
    private var busy = false
    private val sessionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_SESSION_CHANGED) return
            val session = AccountStore.load(this@AccountActivity)
            render(session)
            statusText.text = when {
                session != null -> "Signed in."
                intent.getBooleanExtra(EXTRA_AUTHENTICATION_FAILED, false) -> "Account request failed. Try again."
                else -> "Signed out."
            }
            // A completed onboarding sign-in must still return to the map when
            // the original activity was destroyed by a configuration change.
            if (session != null && this@AccountActivity.intent.getBooleanExtra(EXTRA_ONBOARDING, false)) finish()
        }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(Appearance.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(Appearance.themeRes(this))
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        WindowCompat.enableEdgeToEdge(window)
        val lightSystemBars = !Appearance.isDark(this)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = lightSystemBars
            isAppearanceLightNavigationBars = lightSystemBars
        }
        setContentView(R.layout.activity_account)
        findViewById<Button>(R.id.backNavButton).setOnClickListener { finish() }
        applySafeAreaInsets()

        statusText = findViewById(R.id.accountStatusText)
        authGroup = findViewById(R.id.accountAuthGroup)
        signedInGroup = findViewById(R.id.accountSignedInGroup)
        usernameInput = findViewById(R.id.accountUsername)
        passwordInput = findViewById(R.id.accountPassword)
        confirmPasswordInput = findViewById(R.id.accountConfirmPassword)
        setupPasswordEye(passwordInput, findViewById(R.id.accountPasswordVisibility))
        setupPasswordEye(confirmPasswordInput, findViewById(R.id.accountConfirmPasswordVisibility))
        loginButton = findViewById(R.id.accountLoginButton)
        signupButton = findViewById(R.id.accountSignupButton)
        signedInText = findViewById(R.id.accountSignedInText)
        leaderboardSwitch = findViewById(R.id.accountLeaderboardSwitch)
        val competitionVisibility = if (com.roadconquest.app.util.FeatureFlags.LEADERBOARDS_VISIBLE) View.VISIBLE else View.GONE
        leaderboardSwitch.visibility = competitionVisibility
        findViewById<View>(R.id.accountLeaderboardInfo).visibility = competitionVisibility
        findViewById<View>(R.id.accountLeaderboardPrivacyHelp).visibility = competitionVisibility
        logoutButton = findViewById(R.id.accountLogoutButton)
        changeUsernameButton = findViewById(R.id.accountChangeUsernameButton)
        deleteAccountButton = findViewById(R.id.accountDeleteButton)

        loginButton.setOnClickListener { authenticate(signup = false) }
        signupButton.setOnClickListener { authenticate(signup = true) }
        logoutButton.setOnClickListener { logout() }
        changeUsernameButton.setOnClickListener { showUsernameDialog() }
        deleteAccountButton.setOnClickListener { showDeleteAccountDialog() }
        leaderboardSwitch.setOnCheckedChangeListener { _, visible ->
            if (!rendering) updateLeaderboardPrivacy(visible)
        }

        val session = AccountStore.load(this)
        render(session)
        if (!AccountClient.isConfigured()) {
            statusText.text = "Account service is not configured in this build."
            setAuthEnabled(false)
        } else if (session != null) {
            refreshAccount(session)
        } else if (AccountStore.isAuthenticating()) {
            statusText.text = "Account request in progress…"
        }
    }

    private fun applySafeAreaInsets() {
        val root = findViewById<View>(R.id.accountRoot)
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

    private fun authenticate(signup: Boolean) {
        val username = usernameInput.text.toString().trim()
        val password = passwordInput.text.toString()
        if (!USERNAME.matches(username)) {
            statusText.text = "Username must be 3-24 letters, numbers, or underscores."
            return
        }
        val passwordLength = password.codePointCount(0, password.length)
        if (passwordLength !in 8..128) {
            statusText.text = "Password must be 8-128 characters."
            return
        }
        if (signup && password != confirmPasswordInput.text.toString()) {
            statusText.text = "Passwords do not match."
            return
        }

        setAuthEnabled(false)
        statusText.text = if (signup) "Creating account…" else "Signing in…"
        val generation = AccountStore.beginAuthentication()
        val app = applicationContext
        executor.execute {
            // Durable account state is saved by the worker, not by an Activity that may
            // already have been destroyed by a configuration change.
            val result = runCatching {
                val account = if (signup) AccountClient.signup(username, password)
                    else AccountClient.login(username, password)
                val session = AccountStore.Session(
                    account.username, requireNotNull(account.token), account.leaderboardVisible
                )
                if (AccountStore.saveIfLatest(app, generation, session)) {
                    // UI refresh and optional notifications cannot invalidate an already
                    // durably saved sign-in if either optional action fails.
                    runCatching {
                        app.sendBroadcast(Intent(ACTION_SESSION_CHANGED).setPackage(app.packageName))
                    }
                    runCatching { com.roadconquest.app.notifications.OptionalNotifications.initialize(app) }
                    session
                } else null
            }
            // A rotated screen has no original UI callback. Release its disabled
            // form after a failed request, without invalidating a newer login.
            if (result.isFailure && AccountStore.finishAuthentication(generation)) {
                runCatching {
                    app.sendBroadcast(Intent(ACTION_SESSION_CHANGED)
                        .setPackage(app.packageName)
                        .putExtra(EXTRA_AUTHENTICATION_FAILED, true))
                }
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                result.fold(
                    onSuccess = { session ->
                        if (session != null && AccountStore.load(this)?.token == session.token) {
                            passwordInput.text.clear()
                            confirmPasswordInput.text.clear()
                            statusText.text = "Signed in."
                            render(session)
                            if (intent.getBooleanExtra(EXTRA_ONBOARDING, false)) finish()
                        } else {
                            statusText.text = "A newer account action replaced this sign-in."
                            render(AccountStore.load(this))
                        }
                    },
                    onFailure = {
                        statusText.text = it.message ?: "Account request failed."
                        // A newer request from another account screen may still be running.
                        setAuthEnabled(!AccountStore.isAuthenticating())
                    }
                )
            }
        }
    }

    private fun refreshAccount(session: AccountStore.Session) {
        statusText.text = "Checking account…"
        executor.execute {
            val result = runCatching { AccountClient.me(session.token) }
            runOnUiThread {
                if (isDestroyed || AccountStore.load(this)?.token != session.token) return@runOnUiThread
                result.fold(
                    onSuccess = { account ->
                        val updated = AccountStore.refreshIfUnchanged(
                            this, session, account.username, account.leaderboardVisible
                        ) ?: return@runOnUiThread
                        statusText.text = "Signed in."
                        render(updated)
                    },
                    onFailure = { error ->
                        if (error is AccountClient.ApiException && error.status == 401) {
                            AccountStore.clear(this)
                            statusText.text = "Your session expired. Sign in again."
                            render(null)
                        } else {
                            statusText.text = "Could not refresh account. Your saved sign-in is unchanged."
                        }
                    }
                )
            }
        }
    }

    private fun updateLeaderboardPrivacy(visible: Boolean) {
        val session = AccountStore.load(this) ?: return
        leaderboardSwitch.isEnabled = false
        executor.execute {
            val result = runCatching { AccountClient.setLeaderboardVisible(session.token, visible) }
            runOnUiThread {
                if (isDestroyed || AccountStore.load(this)?.token != session.token) return@runOnUiThread
                result.fold(
                    onSuccess = { saved ->
                        AccountStore.updateIfToken(this, session.token) { it.copy(leaderboardVisible = saved) }
                        rendering = true
                        leaderboardSwitch.isChecked = saved
                        rendering = false
                        leaderboardSwitch.isEnabled = !busy
                        statusText.text = "Leaderboard privacy saved."
                    },
                    onFailure = { error ->
                        rendering = true
                        leaderboardSwitch.isChecked = AccountStore.load(this)?.leaderboardVisible == true
                        rendering = false
                        leaderboardSwitch.isEnabled = !busy
                        statusText.text = error.message ?: "Could not save leaderboard privacy."
                    }
                )
            }
        }
    }

    private fun logout() {
        val session = AccountStore.load(this)
        AccountStore.clear(this)
        render(null)
        statusText.text = "Signed out."
        if (session != null && AccountClient.isConfigured()) {
            executor.execute { runCatching { AccountClient.logout(session.token) } }
        }
    }

    private fun dialogFields(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val padding = (20 * resources.displayMetrics.density).toInt()
        setPadding(padding, padding / 2, padding, 0)
    }

    private fun passwordField(): EditText = EditText(this).apply {
        hint = "Current password"
        inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        isSingleLine = true
        isSaveEnabled = false
        filters = arrayOf(android.text.InputFilter.LengthFilter(128))
    }

    private fun showUsernameDialog() {
        if (busy) return
        val session = AccountStore.load(this) ?: return
        val fields = dialogFields()
        val username = EditText(this).apply {
            hint = "New username"
            isSingleLine = true
            filters = arrayOf(android.text.InputFilter.LengthFilter(24))
            setText(session.username)
        }
        val password = passwordField()
        val error = TextView(this)
        fields.addView(username)
        fields.addView(password, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (10 * resources.displayMetrics.density).toInt() })
        fields.addView(error)
        val dialog = AlertDialog.Builder(this).setTitle("Change username").setView(fields)
            .setNegativeButton("Cancel", null).setPositiveButton("Save", null).create()
        dialog.setOnDismissListener { password.text.clear() }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = username.text.toString().trim()
                val currentPassword = password.text.toString()
                if (!USERNAME.matches(name) || currentPassword.isEmpty()) {
                    error.text = "Enter 3-24 letters, numbers or underscores and your current password."
                    return@setOnClickListener
                }
                dialog.dismiss()
                setBusy(true)
                statusText.text = "Changing username…"
                executor.execute {
                    val result = runCatching { AccountClient.changeUsername(session.token, name, currentPassword) }
                    result.onSuccess { account ->
                        AccountStore.updateIfToken(applicationContext, session.token) {
                            // Changing a username must not overwrite a newer privacy
                            // toggle from Settings while this request was in flight.
                            it.copy(username = account.username)
                        }
                    }
                    runOnUiThread {
                        if (isDestroyed) return@runOnUiThread
                        setBusy(false)
                        render(AccountStore.load(this))
                        statusText.text = result.fold({ "Username changed." }, { it.message ?: "Could not change username." })
                    }
                }
            }
        }
        dialog.show()
    }

    private fun showDeleteAccountDialog() {
        if (busy) return
        val session = AccountStore.load(this) ?: return
        val fields = dialogFields()
        val password = passwordField()
        val error = TextView(this)
        fields.addView(password); fields.addView(error)
        val dialog = AlertDialog.Builder(this).setTitle("Delete account?").setView(fields)
            .setMessage("This permanently deletes your cloud account, sessions, friendships, cloud driving records and forum data, including posts and Forum Plus access tied to this account. Driving history saved on this phone stays. To remove both, also use Settings → Data and privacy → Delete local data. That option does not delete your cloud account.")
            .setNegativeButton("Cancel", null).setPositiveButton("Delete account", null).create()
        dialog.setOnDismissListener { password.text.clear() }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val currentPassword = password.text.toString()
                if (currentPassword.isEmpty()) {
                    error.text = "Enter your current password to delete your account."
                    return@setOnClickListener
                }
                dialog.dismiss()
                setBusy(true)
                statusText.text = "Deleting account…"
                executor.execute {
                    val result = runCatching {
                        AccountClient.deleteAccount(session.token, currentPassword)
                        synchronized(AccountStore) {
                            if (AccountStore.load(applicationContext)?.token == session.token) {
                                AccountStore.clear(applicationContext)
                            }
                        }
                    }
                    runOnUiThread {
                        if (isDestroyed) return@runOnUiThread
                        setBusy(false)
                        render(AccountStore.load(this))
                        statusText.text = result.fold(
                            { "Account deleted. Saved device data was kept." },
                            { it.message ?: "Could not delete account. Your saved data is unchanged." }
                        )
                    }
                }
            }
        }
        dialog.show()
    }

    private fun setBusy(value: Boolean) {
        busy = value
        val configured = AccountClient.isConfigured()
        logoutButton.isEnabled = !value
        changeUsernameButton.isEnabled = !value && configured
        deleteAccountButton.isEnabled = !value && configured
        leaderboardSwitch.isEnabled = !value && configured
        setAuthEnabled(!value && configured)
    }

    private fun render(session: AccountStore.Session?) {
        rendering = true
        authGroup.visibility = if (session == null) View.VISIBLE else View.GONE
        signedInGroup.visibility = if (session == null) View.GONE else View.VISIBLE
        if (session != null) {
            signedInText.text = "Signed in as " + session.username
            leaderboardSwitch.isChecked = session.leaderboardVisible
            leaderboardSwitch.isEnabled = AccountClient.isConfigured()
        }
        rendering = false
        setBusy(busy)
        // After rotation the network worker still owns the original request;
        // do not allow a duplicate signup to supersede its valid response.
        if (session == null && AccountStore.isAuthenticating()) setAuthEnabled(false)
    }

    private fun setupPasswordEye(input: EditText, button: ImageButton) {
        button.setOnClickListener {
            val start = input.selectionStart
            val end = input.selectionEnd
            val showing = input.transformationMethod == null
            input.transformationMethod = if (showing) PasswordTransformationMethod.getInstance() else null
            button.setImageResource(if (showing) R.drawable.ic_visibility else R.drawable.ic_visibility_off)
            button.contentDescription = if (showing) "Show password" else "Hide password"
            if (start >= 0 && end >= 0) input.setSelection(start, end)
        }
    }

    private fun setAuthEnabled(enabled: Boolean) {
        usernameInput.isEnabled = enabled
        passwordInput.isEnabled = enabled
        confirmPasswordInput.isEnabled = enabled
        loginButton.isEnabled = enabled
        signupButton.isEnabled = enabled
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(this, sessionReceiver, IntentFilter(ACTION_SESSION_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED)
        // Covers a response saved between onCreate and registering this observer.
        val session = AccountStore.load(this)
        // A pending sign-in must not be re-enabled simply because this screen resumed.
        // Still refresh a completed sign-in or a previously displayed signed-in screen.
        if (session != null || signedInGroup.visibility == View.VISIBLE) render(session)
        // Also cover a successful login saved just before this receiver registered.
        if (session != null && intent.getBooleanExtra(EXTRA_ONBOARDING, false)) finish()
        ForegroundSession.app.onStart()
    }

    override fun onStop() {
        unregisterReceiver(sessionReceiver)
        passwordInput.text.clear()
        confirmPasswordInput.text.clear()
        ForegroundSession.app.onStop(isChangingConfigurations)
        super.onStop()
    }

    override fun onDestroy() {
        // Finish an authorized deletion even if the activity closes during the request.
        executor.shutdown()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_ONBOARDING = "com.roadconquest.app.ACCOUNT_ONBOARDING"
        private val USERNAME = Regex("[A-Za-z0-9_]{3,24}")
        private const val ACTION_SESSION_CHANGED = "com.roadconquest.app.ACCOUNT_SESSION_CHANGED"
        private const val EXTRA_AUTHENTICATION_FAILED = "com.roadconquest.app.AUTHENTICATION_FAILED"
    }
}
