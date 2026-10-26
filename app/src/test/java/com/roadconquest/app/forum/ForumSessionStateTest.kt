package com.roadconquest.app.forum

import org.junit.Assert.*
import org.junit.Test

class ForumSessionStateTest {
    @Test fun aPurchaseCheckBelongsOnlyToItsOriginalAccount() {
        val state = ForumSessionState()
        assertFalse(state.switchTo("account-a"))
        assertFalse(state.claimRestore("account-a", false, false))
        assertTrue(state.claimRestore("account-a", true, false))
        assertFalse(state.claimRestore("account-a", true, false))
        assertFalse(state.switchTo("account-a"))

        assertTrue(state.switchTo("account-b"))
        assertFalse(state.claimRestore("account-a", true, false))
        assertTrue(state.claimRestore("account-b", true, false))
        assertFalse(state.claimRestore("account-b", true, false))

        assertTrue(state.switchTo(null))
        assertFalse(state.claimRestore(null, true, false))
        assertTrue(state.switchTo("account-a"))
        assertTrue(state.claimRestore("account-a", true, false))
    }

    @Test fun initialAttachPreservesRotationDraftAndPlusAccountsDoNotRestore() {
        val state = ForumSessionState()
        assertFalse(state.switchTo("signed-in-after-rotation"))
        assertFalse(state.claimRestore("signed-in-after-rotation", true, true))
        assertTrue(state.claimRestore("signed-in-after-rotation", true, false))
    }
}
