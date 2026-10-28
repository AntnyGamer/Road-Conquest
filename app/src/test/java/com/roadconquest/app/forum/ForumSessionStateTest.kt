package com.roadconquest.app.forum

import org.junit.Assert.*
import org.junit.Test

class ForumSessionStateTest {
    @Test fun overlappingPurchaseCallbacksVerifyOnceButCompletionAllowsFreshRetries() {
        val state = ForumSessionState()
        state.switchTo("account-a")
        assertTrue(state.claimPurchase("account-a", "receipt"))
        repeat(10) { assertFalse(state.claimPurchase("account-a", "receipt")) }
        assertTrue(state.claimPurchase("account-a", "other-receipt"))
        state.finishPurchase("account-a", "receipt")
        assertTrue(state.claimPurchase("account-a", "receipt"))

        state.switchTo("account-b")
        assertFalse(state.claimPurchase("account-a", "new-receipt"))
        assertTrue(state.claimPurchase("account-b", "receipt"))
        state.finishPurchase("account-a", "receipt")
        assertFalse(state.claimPurchase("account-b", "receipt"))
        state.finishPurchase("account-b", "receipt")
        assertTrue(state.claimPurchase("account-b", "receipt"))
        state.switchTo(null)
        assertFalse(state.claimPurchase("account-b", "signed-out-receipt"))
    }

    @Test fun savedComposerDraftIsRestoredOnlyToTheSameSession() {
        val firstOwner = ForumSessionState.draftOwner("first-account-token")
        assertNotNull(firstOwner)
        assertNotEquals(firstOwner, "first-account-token")
        assertTrue(ForumSessionState.canRestoreDraft(firstOwner, "first-account-token"))
        assertFalse(ForumSessionState.canRestoreDraft(firstOwner, "second-account-token"))
        assertFalse(ForumSessionState.canRestoreDraft(firstOwner, null))
        assertFalse(ForumSessionState.canRestoreDraft(null, "first-account-token"))
        assertNull(ForumSessionState.draftOwner(null))
    }

    @Test fun draftOwnerKeepsTheExistingSha256PrefixIncludingLeadingZeroes() {
        for ((token, expected) in listOf(
            "" to "e3b0c44298fc1c149afbf4c8996fb924",
            "abc" to "ba7816bf8f01cfea414140de5dae2223",
            "é🚗" to "bf607ab6ebd291b9bf406aec5147f136",
            "session-29" to "001bc293ea681d91f09d9e0a179fc9ab"
        )) assertEquals(expected, ForumSessionState.draftOwner(token))
    }

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
