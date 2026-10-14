package com.roadconquest.app

import android.app.Activity
import android.content.DialogInterface
import android.os.Looper
import android.widget.TextView
import com.roadconquest.app.account.AccountClient
import com.roadconquest.app.account.AccountOnboarding
import com.roadconquest.app.account.AccountStore
import com.roadconquest.app.util.Prefs
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class AccountOnboardingTest {
    @Before fun reset() {
        val app = RuntimeEnvironment.getApplication()
        AccountStore.clear(app)
        Prefs.setAccountPromptShown(app, false)
        AccountClient.endpointOverrideForTests = "https://127.0.0.1:1"
    }

    @Test fun firstInvitationOpensAccountCreationAndDoesNotRepeat() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get()
            val prompt = requireNotNull(AccountOnboarding.showIfNeeded(activity) { fail("Account choice should open the account screen") })
            assertTrue(prompt.isShowing)
            val message = prompt.findViewById<TextView>(android.R.id.message)?.text.toString()
            assertTrue(message.contains("add friends"))
            assertFalse(message.contains("leaderboards"))
            assertTrue(message.contains("continue without an account"))
            prompt.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(AccountActivity::class.java.name, shadowOf(activity).nextStartedActivity.component?.className)
            assertTrue(Prefs.isAccountPromptShown(activity))
            assertNull(AccountOnboarding.showIfNeeded(activity) { fail("Invitation already handled") })
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun skippingContinuesLocalExplorationAndNeverRepeats() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get()
            var continued = false
            val prompt = requireNotNull(AccountOnboarding.showIfNeeded(activity) { continued = true })
            prompt.getButton(DialogInterface.BUTTON_NEGATIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(continued)
            assertNull(shadowOf(activity).nextStartedActivity)
            assertNull(AccountOnboarding.showIfNeeded(activity) { fail("Must not nag on reopening") })
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun invitationDismissedByRecreationRemainsPendingUntilAChoice() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get()
            val prompt = requireNotNull(AccountOnboarding.showIfNeeded(activity) {})
            prompt.dismiss()
            assertFalse(Prefs.isAccountPromptShown(activity))
            requireNotNull(AccountOnboarding.showIfNeeded(activity) {}).cancel()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(Prefs.isAccountPromptShown(activity))
        } finally { controller.pause().stop().destroy() }
    }
}
