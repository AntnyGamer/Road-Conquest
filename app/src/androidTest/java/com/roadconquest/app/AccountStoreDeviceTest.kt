package com.roadconquest.app

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.widget.Switch
import android.widget.TextView
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.roadconquest.app.account.AccountStore
import com.roadconquest.app.account.AccountClient
import com.roadconquest.app.account.AccountOnboarding
import com.roadconquest.app.util.Prefs
import com.roadconquest.app.util.FeatureFlags
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccountStoreDeviceTest {
    @Test fun verificationVisibilityAndOptOutRespectUnavailableService() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        AccountStore.save(context, AccountStore.Session("OfflineDriver", "test-only-expired-token", true))
        Prefs.setDriveVerificationEnabled(context, true)
        AccountClient.endpointOverrideForTests = "https://127.0.0.1:1"
        try {
            ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
                if (FeatureFlags.LEADERBOARDS_VISIBLE) {
                    val deadline = SystemClock.elapsedRealtime() + 10_000L
                    var checkFailed = false
                    while (!checkFailed && SystemClock.elapsedRealtime() < deadline) {
                        scenario.onActivity { activity ->
                            checkFailed = activity.findViewById<TextView>(R.id.verificationStatusText).text.toString()
                                .contains("Could not check verified scoring.")
                        }
                        if (!checkFailed) SystemClock.sleep(50)
                    }
                    assertTrue("Status-check failure is displayed without claiming scoring is unavailable", checkFailed)
                } else {
                    scenario.onActivity { activity ->
                        for (id in listOf(R.id.verifyDrivesSwitch, R.id.leaderboardsButton, R.id.verificationStatusText)) {
                            assertEquals("Hidden competition has no active UI", View.GONE, activity.findViewById<View>(id).visibility)
                        }
                    }
                }
                scenario.onActivity { activity ->
                    Prefs.setAccountPromptShown(context, false)
                    assertNull("Saved accounts skip the first-launch invitation", AccountOnboarding.showIfNeeded(activity) { fail("Already signed in") })
                    assertTrue(Prefs.isAccountPromptShown(context))
                    val control = activity.findViewById<Switch>(R.id.verifyDrivesSwitch)
                    assertTrue("Availability must not block opt-out", control.isEnabled)
                    assertTrue(control.isChecked)
                    control.performClick()
                    assertFalse(Prefs.isDriveVerificationEnabled(context))
                }
            }
        } finally {
            AccountClient.endpointOverrideForTests = null
            Prefs.setDriveVerificationEnabled(context, false)
            AccountStore.clear(context)
        }
    }

    @Test fun signupCompletionAfterRotationIsPersistedAndShownByNewActivity() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        AccountStore.clear(context)
        val session = AccountStore.Session("RotatedDriver", "device-test-rotation-token", false)
        val generation = AccountStore.beginAuthentication()
        try {
            ActivityScenario.launch(AccountActivity::class.java).use { scenario ->
                scenario.recreate()
                // Model the network worker completing after the original screen has gone.
                assertTrue(AccountStore.saveIfLatest(context, generation, session))
                assertEquals(session, AccountStore.load(context))
                context.sendBroadcast(Intent("com.roadconquest.app.ACCOUNT_SESSION_CHANGED")
                    .setPackage(context.packageName))
                // sendBroadcast is asynchronous: an idle main looper does not prove
                // the system has delivered this app-local broadcast.
                val deadline = SystemClock.elapsedRealtime() + 5_000L
                var signedInShown = false
                do {
                    scenario.onActivity { activity ->
                        signedInShown = activity.findViewById<View>(R.id.accountSignedInGroup).visibility == View.VISIBLE &&
                            activity.findViewById<TextView>(R.id.accountSignedInText).text.toString() == "Signed in as RotatedDriver"
                    }
                    if (!signedInShown) SystemClock.sleep(50)
                } while (!signedInShown && SystemClock.elapsedRealtime() < deadline)
                assertTrue("Rotation survivor must display the completed sign-in", signedInShown)
            }
            val obsolete = AccountStore.beginAuthentication()
            AccountStore.clear(context)
            assertFalse("A sign-out must invalidate an unfinished authentication",
                AccountStore.saveIfLatest(context, obsolete, session))
            assertNull(AccountStore.load(context))
        } finally {
            AccountStore.clear(context)
        }
    }

    @Test fun sessionTokenIsEncryptedByAndroidKeystoreAndRoundTrips() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        AccountStore.clear(context)
        val session = AccountStore.Session(
            username = "DriverOne",
            token = "test-session-token-that-must-never-be-stored-in-plaintext",
            leaderboardVisible = false
        )

        try {
            AccountStore.save(context, session)
            val prefs = context.getSharedPreferences("roadconquest_account", Context.MODE_PRIVATE)
            val firstCiphertext = requireNotNull(prefs.getString("token", null))
            assertFalse(firstCiphertext.contains(session.token))
            assertEquals(session, AccountStore.load(context))

            repeat(20) {
                assertEquals(session, AccountStore.refreshIfUnchanged(context, session,
                    session.username, session.leaderboardVisible))
            }
            assertEquals("Unchanged profile refreshes must not rewrite encrypted preferences",
                firstCiphertext, prefs.getString("token", null))

            assertNull(AccountStore.updateIfToken(context, "an-old-session-token") { it.copy(username = "WrongDriver") })
            assertEquals(session, AccountStore.load(context))
            val renamed = AccountStore.updateIfToken(context, session.token) { it.copy(username = "NewDriver") }
            assertEquals("NewDriver", renamed?.username)
            assertEquals(session.token, AccountStore.load(context)?.token)

            AccountStore.save(context, session)
            val secondCiphertext = requireNotNull(prefs.getString("token", null))
            assertNotEquals("AES-GCM must use a fresh IV for every save", firstCiphertext, secondCiphertext)
            assertEquals(session, AccountStore.load(context))

            val unicode = session.copy(token = "session-驾驶🚙é")
            AccountStore.save(context, unicode)
            assertEquals(unicode, AccountStore.load(context))
            val corrupted = android.util.Base64.decode(requireNotNull(prefs.getString("token", null)), android.util.Base64.NO_WRAP)
            corrupted[corrupted.lastIndex] = (corrupted.last().toInt() xor 1).toByte()
            prefs.edit().putString("token", android.util.Base64.encodeToString(corrupted, android.util.Base64.NO_WRAP)).commit()
            Prefs.setDriveVerificationEnabled(context, true)
            assertNull("Unauthenticated plaintext must not become a session", AccountStore.load(context))
            assertFalse(Prefs.isDriveVerificationEnabled(context))
            assertFalse(prefs.contains("token"))
        } finally {
            AccountStore.clear(context)
        }
    }
}
