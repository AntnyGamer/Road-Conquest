package com.roadconquest.app.account

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import com.roadconquest.app.AccountActivity
import com.roadconquest.app.util.Prefs

/** A one-time invitation, without making local exploration depend on an account. */
object AccountOnboarding {
    fun showIfNeeded(activity: Activity, onContinue: () -> Unit): AlertDialog? {
        if (Prefs.isAccountPromptShown(activity) || !AccountClient.isConfigured()) return null
        if (AccountStore.load(activity) != null) {
            Prefs.setAccountPromptShown(activity, true)
            return null
        }
        fun chooseAccount() {
            Prefs.setAccountPromptShown(activity, true)
            activity.startActivity(Intent(activity, AccountActivity::class.java).putExtra(AccountActivity.EXTRA_ONBOARDING, true))
        }
        fun continueLocally() {
            Prefs.setAccountPromptShown(activity, true)
            onContinue()
        }
        return AlertDialog.Builder(activity)
            .setTitle("Create your Road Conquest account")
            .setMessage(if (com.roadconquest.app.util.FeatureFlags.LEADERBOARDS_VISIBLE)
                "Create an account or sign in to add friends and use verified leaderboards. You can continue without an account; your saved map stays on this device."
                else "Create an account or sign in to add friends. You can continue without an account; your saved map stays on this device.")
            .setPositiveButton("Create account") { _, _ -> chooseAccount() }
            .setNeutralButton("Sign in") { _, _ -> chooseAccount() }
            .setNegativeButton("Not now") { _, _ -> continueLocally() }
            .setOnCancelListener { continueLocally() }
            .show()
    }
}
