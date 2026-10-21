package com.roadconquest.app

import android.view.View
import android.location.Location
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import com.roadconquest.app.account.AccountClient
import com.roadconquest.app.account.AccountStore
import com.roadconquest.app.data.TrackingRepository
import com.roadconquest.app.data.AppDatabase
import com.roadconquest.app.data.LocalDataReset
import com.roadconquest.app.util.Prefs
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.SQLiteMode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
class AccountActivityTest {
    @Before fun clearAccountState() {
        AccountClient.endpointOverrideForTests = ""
        RuntimeEnvironment.getApplication()
            .getSharedPreferences("roadconquest_account", 0)
            .edit().clear().commit()
        Prefs.setDriveVerificationEnabled(RuntimeEnvironment.getApplication(), false)
    }

    @Test fun disablingAndReenablingVerificationInvalidatesPreviouslyCollectedEvidence() {
        val context = RuntimeEnvironment.getApplication()
        val initial = Prefs.driveVerificationConsentVersion(context)
        Prefs.setDriveVerificationEnabled(context, true)
        assertTrue(Prefs.isDriveVerificationEnabled(context))
        val collecting = Prefs.driveVerificationConsentVersion(context)
        Prefs.setDriveVerificationEnabled(context, false)
        assertFalse(Prefs.isDriveVerificationEnabled(context))
        Prefs.setDriveVerificationEnabled(context, true)
        assertTrue(Prefs.driveVerificationConsentVersion(context) > collecting)
        assertTrue(collecting > initial)
    }

    @Test fun olderSharingConsentCannotResumeUploadsUnderANewVerificationPolicy() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("roadconquest_preferences", 0).edit()
            .putBoolean("verify_drives", true).remove("verification_policy").commit()
        assertFalse(Prefs.isDriveVerificationEnabled(context))
        Prefs.setDriveVerificationEnabled(context, true)
        assertTrue(Prefs.isDriveVerificationEnabled(context))
        Prefs.setDriveVerificationEnabled(context, false)
        assertFalse(Prefs.isDriveVerificationEnabled(context))
    }

    @Test fun verificationCannotBeEnabledDuringPendingDeviceDataDeletion() {
        val context = RuntimeEnvironment.getApplication()
        Prefs.setDeviceDataDeletionPending(context, false)
        try {
            Prefs.setDriveVerificationEnabled(context, true)
            assertTrue(Prefs.isDriveVerificationEnabled(context))
            val before = Prefs.driveVerificationConsentVersion(context)
            Prefs.setDeviceDataDeletionPending(context, true)
            // Setting the durable deletion marker itself must revoke precise
            // location-sharing consent; no separate callback is required.
            assertFalse(Prefs.isDriveVerificationEnabled(context))
            val revokedAt = Prefs.driveVerificationConsentVersion(context)
            assertTrue(revokedAt > before)
            // A stale Settings UI or in-flight callback must not restore
            // consent after the local deletion marker becomes durable.
            Prefs.setDriveVerificationEnabled(context, true)
            assertFalse(Prefs.isDriveVerificationEnabled(context))
            assertTrue(Prefs.driveVerificationConsentVersion(context) > revokedAt)
            Prefs.setDeviceDataDeletionPending(context, false)
            assertFalse(Prefs.isDriveVerificationEnabled(context))
        } finally {
            Prefs.setDriveVerificationEnabled(context, false)
            Prefs.setDeviceDataDeletionPending(context, false)
        }
    }

    @Test fun clearingAnySessionAlsoClearsVerifiedDriveConsent() {
        val context = RuntimeEnvironment.getApplication()
        Prefs.setDriveVerificationEnabled(context, true)
        assertTrue(Prefs.isDriveVerificationEnabled(context))
        AccountStore.clear(context)
        assertFalse(Prefs.isDriveVerificationEnabled(context))
    }

    @Test fun delayedAccountRefreshDoesNotRevertNewerPrivacyOrUsernameChanges() {
        val before = AccountStore.Session("OldUser", "token", false)
        val newLocal = before.copy(username = "NewUser", leaderboardVisible = true)

        // A remote /me request started while both values were old. Before the
        // result arrives, the user updates their username and privacy setting.
        val merged = AccountStore.mergeServerRefresh(
            newLocal, before, "OldUser", false
        )
        assertEquals("NewUser", merged.username)
        assertTrue(merged.leaderboardVisible)
        assertEquals(before.token, merged.token)

        // When there were no competing edits, the refresh still updates both
        // fields exactly as it did before the concurrency fix.
        val refreshed = AccountStore.mergeServerRefresh(
            before, before, "ServerRenamed", true
        )
        assertEquals("ServerRenamed", refreshed.username)
        assertTrue(refreshed.leaderboardVisible)
    }

    @Test fun delayedRemoteFieldUpdatesPreserveOnlyTheLocallyModifiedField() {
        val before = AccountStore.Session("Driver", "token", false)
        val privacyChanged = AccountStore.mergeServerRefresh(
            before.copy(leaderboardVisible = true), before, "RemoteDriver", false
        )
        assertEquals("RemoteDriver", privacyChanged.username)
        assertTrue(privacyChanged.leaderboardVisible)

        val usernameChanged = AccountStore.mergeServerRefresh(
            before.copy(username = "LocalDriver"), before, "RemoteDriver", true
        )
        assertEquals("LocalDriver", usernameChanged.username)
        assertTrue(usernameChanged.leaderboardVisible)
    }

    @Test fun sessionStringNeverContainsBearerToken() {
        val token = "secret-bearer-token-that-must-not-be-logged"
        val text = AccountStore.Session("DriverOne", token, false).toString()
        assertFalse(text.contains(token))
        assertTrue(text.contains("<redacted>"))
    }

    @Test fun settingsKeepsAccountAndLocalChallengesWhileHidingCompetitiveControls() {
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).create().start().resume()
        try {
            val activity = controller.get()
            val privacy = activity.findViewById<Switch>(R.id.leaderboardPrivacySwitch)
            assertFalse(privacy.isChecked)
            assertFalse(privacy.isEnabled)
            assertFalse(activity.findViewById<Switch>(R.id.verifyDrivesSwitch).isChecked)
            assertFalse(activity.findViewById<Switch>(R.id.verifyDrivesSwitch).isEnabled)
            for (id in listOf(R.id.leaderboardsButton, R.id.leaderboardPrivacySwitch,
                R.id.verifyDrivesSwitch, R.id.verificationStatusText)) {
                assertEquals(View.GONE, activity.findViewById<View>(id).visibility)
            }
            assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.challengesButton).visibility)

            activity.findViewById<Button>(R.id.accountButton).performClick()
            val launched = org.robolectric.Shadows.shadowOf(activity).nextStartedActivity
            assertEquals(AccountActivity::class.java.name, launched.component?.className)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun hiddenLeaderboardDirectLaunchFinishesThroughTheWholeLifecycle() {
        val controller = Robolectric.buildActivity(LeaderboardActivity::class.java).create().start().resume()
        try {
            assertTrue(controller.get().isFinishing)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun accountHidesLeaderboardPrivacyAndItsHelp() {
        val controller = Robolectric.buildActivity(AccountActivity::class.java).create().start().resume()
        try {
            for (id in listOf(R.id.accountLeaderboardInfo, R.id.accountLeaderboardSwitch, R.id.accountLeaderboardPrivacyHelp)) {
                assertEquals(View.GONE, controller.get().findViewById<View>(id).visibility)
            }
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun challengesKeepLocalGoalsAndFriendsWithoutRankingControls() {
        val controller = Robolectric.buildActivity(ChallengesActivity::class.java).create()
        try {
            val texts = mutableListOf<String>()
            fun collect(view: View) {
                if (view.visibility != View.VISIBLE) return
                if (view is TextView) texts += view.text.toString()
                if (view is android.view.ViewGroup) for (index in 0 until view.childCount) collect(view.getChildAt(index))
            }
            val content = controller.get().findViewById<View>(android.R.id.content)
            collect(content)
            assertTrue(texts.contains("Weekly challenges"))
            assertTrue("Goals Refresh must stay accessible even with Friends collapsed",
                texts.contains("Refresh"))
            assertFalse("Friends are deliberately collapsed while leaderboards are disabled",
                texts.contains("Add friend by username"))
            val optionalFriends = mutableListOf<Button>()
            fun findOptionalFriends(view: View) {
                if (view is Button && view.text.toString() == "Manage friends") {
                    optionalFriends.add(view)
                }
                if (view is android.view.ViewGroup) for (index in 0 until view.childCount) {
                    findOptionalFriends(view.getChildAt(index))
                }
            }
            findOptionalFriends(content)
            assertEquals(1, optionalFriends.size)
            optionalFriends.single().performClick()
            texts.clear()
            collect(content)
            assertTrue(texts.contains("Add friend by username"))
            assertFalse(texts.contains("New roads"))
            assertFalse(texts.contains("Miles"))
            assertFalse(texts.any { it.contains("verified", ignoreCase = true) })
        } finally {
            controller.destroy()
        }
    }

    @Test fun resumingAFormDoesNotReenableAnUnfinishedSignIn() {
        AccountClient.endpointOverrideForTests = "https://example.com"
        val controller = Robolectric.buildActivity(AccountActivity::class.java).create().start().resume()
        try {
            val activity = controller.get()
            val signIn = activity.findViewById<Button>(R.id.accountLoginButton)
            val signUp = activity.findViewById<Button>(R.id.accountSignupButton)
            assertTrue(signIn.isEnabled)
            signIn.isEnabled = false
            signUp.isEnabled = false
            controller.pause().stop().start().resume()
            assertFalse(signIn.isEnabled)
            assertFalse(signUp.isEnabled)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun backArrowIsCenteredDrawableInsteadOfTextGlyph() {
        val controller = Robolectric.buildActivity(AccountActivity::class.java).create()
        try {
            val back = controller.get().findViewById<Button>(R.id.backNavButton)
            assertEquals("Back", back.text.toString())
            assertNotNull(back.compoundDrawablesRelative[0])
            assertEquals(android.view.Gravity.CENTER_VERTICAL,
                back.gravity and android.view.Gravity.VERTICAL_GRAVITY_MASK)
        } finally {
            controller.destroy()
        }
    }

    @Test fun accountFormHasSeparateFieldsAndActions() {
        val controller = Robolectric.buildActivity(AccountActivity::class.java).create()
        try {
            val activity = controller.get()
            val density = activity.resources.displayMetrics.density
            fun minDp(dp: Int) = (dp * density + 0.5f).toInt()
            fun topMargin(view: View) = (view.layoutParams as LinearLayout.LayoutParams).topMargin

            val passwordRow = activity.findViewById<View>(R.id.accountPassword).parent as View
            val confirmRow = activity.findViewById<View>(R.id.accountConfirmPassword).parent as View
            assertTrue(topMargin(passwordRow) >= minDp(10))
            assertTrue(topMargin(confirmRow) >= minDp(10))
            assertTrue(topMargin(activity.findViewById(R.id.accountSignupButton)) >= minDp(16))
            assertTrue(topMargin(activity.findViewById(R.id.accountLoginButton)) >= minDp(18))
            assertTrue(topMargin(activity.findViewById(R.id.accountLogoutButton)) >= minDp(18))
            assertTrue(topMargin(activity.findViewById(R.id.accountDeleteButton)) >= minDp(18))
        } finally {
            controller.destroy()
        }
    }

    @Test fun passwordEyesToggleVisibilityWithoutClearingText() {
        AccountClient.endpointOverrideForTests = "https://example.com"
        val controller = Robolectric.buildActivity(AccountActivity::class.java).create().start().resume()
        try {
            val activity = controller.get()
            val password = activity.findViewById<EditText>(R.id.accountPassword)
            password.setText("abcdefgh")
            password.setSelection(3)
            assertNotNull(password.transformationMethod)
            activity.findViewById<View>(R.id.accountPasswordVisibility).performClick()
            assertNull(password.transformationMethod)
            assertEquals("abcdefgh", password.text.toString())
            assertEquals(3, password.selectionStart)
            activity.findViewById<View>(R.id.accountPasswordVisibility).performClick()
            assertNotNull(password.transformationMethod)
            assertEquals("abcdefgh", password.text.toString())
            assertEquals(3, password.selectionStart)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun endpointConfigurationCacheTracksRuntimeOverrideExactly() {
        AccountClient.endpointOverrideForTests = "https://example.com"
        assertTrue(AccountClient.isConfigured())
        AccountClient.endpointOverrideForTests = ""
        assertFalse(AccountClient.isConfigured())
        AccountClient.endpointOverrideForTests = "http://example.com"
        assertFalse(AccountClient.isConfigured())
        AccountClient.endpointOverrideForTests = "https://example.org/base/"
        assertTrue(AccountClient.isConfigured())
    }

    @Test fun competitiveResponsesCannotSubstituteLegacyOrUnverifiedAccountScores() {
        AccountClient.requireVerifiedPolicy(org.json.JSONObject().put("verified", true).put("scoring_policy", 3).put("dataset", "fog-grid-v1"))
        for (body in listOf(org.json.JSONObject(),
            org.json.JSONObject().put("verified", false).put("scoring_policy", 3),
            org.json.JSONObject().put("verified", "true").put("scoring_policy", 3),
            org.json.JSONObject().put("verified", true).put("scoring_policy", 2),
            org.json.JSONObject().put("verified", true).put("scoring_policy", 3).put("dataset", "other"))) {
            try {
                AccountClient.requireVerifiedPolicy(body)
                fail("Competitive scores must have the hardened verified policy")
            } catch (_: AccountClient.ApiException) { }
        }
    }

    @Test fun liveAccountEndpointIsConfiguredByDefault() {
        AccountClient.endpointOverrideForTests = null
        try {
            assertTrue(AccountClient.isConfigured())
        } finally {
            AccountClient.endpointOverrideForTests = ""
        }
    }

    @Test fun unconfiguredEndpointDoesNotAcceptCredentialsOrExposeLeaderboardControls() {
        val controller = Robolectric.buildActivity(AccountActivity::class.java).create().start().resume()
        try {
            val activity = controller.get()
            assertTrue(
                activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
            )
            assertFalse(activity.findViewById<Button>(R.id.accountSignupButton).isEnabled)
            assertFalse(activity.findViewById<Button>(R.id.accountLoginButton).isEnabled)
            assertFalse(activity.findViewById<EditText>(R.id.accountPassword).isEnabled)
            assertEquals(View.GONE, activity.findViewById<View>(R.id.accountSignedInGroup).visibility)
            assertFalse(activity.findViewById<Switch>(R.id.accountLeaderboardSwitch).isChecked)
            assertTrue(
                activity.findViewById<TextView>(R.id.accountStatusText).text
                    .toString().contains("not configured", ignoreCase = true)
            )
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun deletingLocalDataRequiresTypedConfirmationButNotAnAccount() {
        val context = RuntimeEnvironment.getApplication()
        AccountClient.endpointOverrideForTests = "https://example.com"
        AccountStore.clear(context)
        Prefs.setDeviceDataDeletionPending(context, false)

        val controller = Robolectric.buildActivity(SettingsActivity::class.java).create().start().resume()
        try {
            val activity = controller.get()
            val control = activity.findViewById<Button>(R.id.deleteDeviceDataButton)
            assertEquals("Delete local data", control.text.toString())
            assertTrue("Local deletion must remain available while signed out", control.isEnabled)
            assertEquals(
                "I confirm I want to delete all of my local data.",
                SettingsActivity.DELETE_LOCAL_DATA_CONFIRMATION
            )
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun confirmedDeviceDeletionFinishesAfterSettingsCloses() {
        AppDatabase::class.java.getDeclaredField("instance").apply { isAccessible = true }
            .set(null, null)
        val context = RuntimeEnvironment.getApplication()
        val repository = TrackingRepository(context)
        repository.insertLocation(Location("gps").apply {
            latitude = 40.0; longitude = -74.0; accuracy = 5f; time = 1_000_000L
        })
        Prefs.setManualOnly(context, false)
        Prefs.setDriveVerificationEnabled(context, true)

        val controller = Robolectric.buildActivity(SettingsActivity::class.java).create().start().resume()
        val activity = controller.get()
        val executor = SettingsActivity::class.java.getDeclaredField("dataExecutor").let { field ->
            field.isAccessible = true
            field.get(activity) as ExecutorService
        }
        val blockerStarted = CountDownLatch(1)
        val releaseBlocker = CountDownLatch(1)
        executor.execute {
            blockerStarted.countDown()
            releaseBlocker.await(5, TimeUnit.SECONDS)
        }
        assertTrue(blockerStarted.await(5, TimeUnit.SECONDS))

        // Queue the same authorized deletion work the Settings confirmation uses, but do
        // it directly so this test measures executor teardown rather than Robolectric dialog timing.
        LocalDataReset.stopTracking(context)
        executor.execute { LocalDataReset.clearStoppedData(context) }

        // Queue a sentinel after the deletion task. Because dataExecutor is single-threaded,
        // the sentinel can only run after the authorized deletion has fully completed.
        val deletionFinished = CountDownLatch(1)
        executor.execute { deletionFinished.countDown() }

        // Close Settings while the authorized deletion is deliberately queued behind work.
        // Graceful executor shutdown must preserve the already-submitted deletion + sentinel.
        controller.pause().stop().destroy()
        assertTrue(executor.isShutdown)
        releaseBlocker.countDown()

        assertTrue(
            "Queued deletion finishes during graceful Settings teardown",
            deletionFinished.await(15, TimeUnit.SECONDS)
        )
        assertEquals(0L, repository.getSummary().trackPointCount)
        assertTrue(Prefs.isManualOnly(context))
        assertFalse(Prefs.isTrackingPaused(context))
        assertFalse(Prefs.isDeviceDataDeletionPending(context))
        assertFalse(Prefs.isDriveVerificationEnabled(context))
    }


    @Test fun deviceDeletionMarkerIsDurableBeforeCleanupAndBlocksResume() {
        AppDatabase::class.java.getDeclaredField("instance").apply { isAccessible = true }
            .set(null, null)
        val context = RuntimeEnvironment.getApplication()
        TrackingRepository(context).insertLocation(Location("gps").apply {
            latitude = 40.0; longitude = -74.0; accuracy = 5f; time = 1_000_000L
        })
        Prefs.setManualOnly(context, false)
        Prefs.setTrackingPaused(context, false)
        Prefs.setDriveVerificationEnabled(context, true)

        LocalDataReset.stopTracking(context)

        assertTrue(Prefs.isDeviceDataDeletionPending(context))
        assertTrue(Prefs.isManualOnly(context))
        assertTrue(Prefs.isTrackingPaused(context))
        assertFalse(Prefs.shouldResumePausedTracking(context))
        assertFalse(Prefs.isDriveVerificationEnabled(context))

        LocalDataReset.clearStoppedData(context)
        assertFalse(Prefs.isDeviceDataDeletionPending(context))
        assertFalse(Prefs.isTrackingPaused(context))
        assertTrue(Prefs.isManualOnly(context))
        assertEquals(0L, TrackingRepository(context).getSummary().trackPointCount)
    }

}
