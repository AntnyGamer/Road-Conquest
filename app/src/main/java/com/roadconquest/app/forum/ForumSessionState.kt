package com.roadconquest.app.forum

/**
 * Activity-local account scope. An ownership check is valid only for the session
 * that requested it. Initial attachment retains a draft restored after rotation.
 */
internal class ForumSessionState {
    private var initialized = false
    private var token: String? = null
    private var ownershipCheckedFor: String? = null

    /** True only for a real account transition, not the first Activity refresh. */
    fun switchTo(currentToken: String?): Boolean {
        if (!initialized) {
            initialized = true
            token = currentToken
            return false
        }
        if (token == currentToken) return false
        token = currentToken
        ownershipCheckedFor = null
        return true
    }

    /** Prevent repeat Play lookups, but never reuse one account's result for another. */
    fun claimRestore(currentToken: String?, purchasesAvailable: Boolean, hasPlus: Boolean?): Boolean {
        if (currentToken == null || token != currentToken || !purchasesAvailable ||
            hasPlus != false || ownershipCheckedFor == currentToken) return false
        ownershipCheckedFor = currentToken
        return true
    }
}
