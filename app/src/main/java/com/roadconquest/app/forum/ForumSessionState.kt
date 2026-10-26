package com.roadconquest.app.forum

import java.security.MessageDigest

/**
 * Activity-local account scope. An ownership check is valid only for the session
 * that requested it. Initial attachment retains a draft restored after rotation.
 */
internal class ForumSessionState {
    companion object {
        /** A saved draft belongs to a specific session without persisting its bearer token. */
        fun draftOwner(token: String?): String? = token?.let {
            MessageDigest.getInstance("SHA-256").digest(it.toByteArray(Charsets.UTF_8))
                .take(16).joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        }

        fun canRestoreDraft(savedOwner: String?, currentToken: String?): Boolean =
            savedOwner != null && savedOwner == draftOwner(currentToken)
    }
    private var initialized = false
    private var token: String? = null
    private var ownershipCheckedFor: String? = null
    private val pendingPurchases = mutableSetOf<Pair<String, String>>()

    /** Overlapping Play callbacks share one server check, never a cached ownership verdict. */
    fun claimPurchase(currentToken: String, purchaseToken: String): Boolean =
        token == currentToken && pendingPurchases.add(currentToken to purchaseToken)

    fun finishPurchase(currentToken: String, purchaseToken: String) {
        pendingPurchases.remove(currentToken to purchaseToken)
    }

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
