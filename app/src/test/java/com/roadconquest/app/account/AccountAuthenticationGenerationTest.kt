package com.roadconquest.app.account

import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37], manifest = Config.NONE)
class AccountAuthenticationGenerationTest {
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
