package com.roadconquest.app.account

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37], manifest = Config.NONE)
class AccountAuthenticationGenerationTest {
    @Test fun olderFailedResponseCannotReleaseNewerPendingAuthentication() {
        val previous = AccountStore.beginAuthentication()
        val current = AccountStore.beginAuthentication()
        assertFalse(AccountStore.finishAuthentication(previous))
        assertTrue(AccountStore.isAuthenticating())
        assertTrue(AccountStore.finishAuthentication(current))
        assertFalse(AccountStore.isAuthenticating())
    }

    @Test fun oldFailureCannotOverwriteLaterAccountActions() {
        val obsolete = AccountStore.beginAuthentication()
        val latest = AccountStore.beginAuthentication()
        assertFalse(AccountStore.isCurrentAuthentication(obsolete))
        assertTrue(AccountStore.isCurrentAuthentication(latest))
        assertFalse(AccountStore.finishAuthentication(obsolete))
        assertTrue(AccountStore.finishAuthentication(latest))
        // The latest failure is still displayable after its pending state was released.
        assertTrue(AccountStore.isCurrentAuthentication(latest))
        AccountStore.clear(RuntimeEnvironment.getApplication())
        assertFalse(AccountStore.isCurrentAuthentication(latest))
    }

    @Test fun olderSuccessfulResponseCannotReplaceNewerAuthentication() {
        val staleRequest = AccountStore.beginAuthentication()
        AccountStore.beginAuthentication()
        // Must reject the old response before any encryption or preference write.
        assertFalse(AccountStore.saveIfLatest(
            RuntimeEnvironment.getApplication(), staleRequest,
            AccountStore.Session("old_user", "stale-token", false)
        ))
    }
}
