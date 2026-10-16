package com.roadconquest.app.account

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.roadconquest.app.util.Prefs
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object AccountStore {
    data class Session(
        val username: String,
        val token: String,
        val leaderboardVisible: Boolean
    ) {
        override fun toString(): String =
            "Session(username=$username, token=<redacted>, leaderboardVisible=$leaderboardVisible)"
    }

    private const val PREFS = "roadconquest_account"
    private const val KEY_ALIAS = "roadconquest_account_session_v1"
    private const val KEY_USERNAME = "username"
    private const val KEY_TOKEN = "token"
    private const val KEY_LEADERBOARD_VISIBLE = "leaderboard_visible"

    @Synchronized
    fun load(context: Context): Session? {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val username = prefs.getString(KEY_USERNAME, null) ?: return null
        val encoded = prefs.getString(KEY_TOKEN, null) ?: return null
        return runCatching {
            val bytes = Base64.decode(encoded, Base64.NO_WRAP)
            require(bytes.size > 12)
            val iv = bytes.copyOfRange(0, 12)
            val ciphertext = bytes.copyOfRange(12, bytes.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            Session(
                username,
                cipher.doFinal(ciphertext).toString(Charsets.UTF_8),
                prefs.getBoolean(KEY_LEADERBOARD_VISIBLE, false)
            )
        }.getOrElse {
            clear(context)
            null
        }
    }

    @Synchronized
    fun save(context: Context, session: Session) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val ciphertext = cipher.doFinal(session.token.toByteArray(Charsets.UTF_8))
        val encoded = Base64.encodeToString(cipher.iv + ciphertext, Base64.NO_WRAP)
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_USERNAME, session.username)
            .putString(KEY_TOKEN, encoded)
            .putBoolean(KEY_LEADERBOARD_VISIBLE, session.leaderboardVisible)
            .apply()
    }

    @Synchronized
    fun clear(context: Context) {
        com.roadconquest.app.notifications.OptionalNotifications.disableLocal(context)
        // Disable precise-GPS sharing first, then durably remove the local bearer session.
        // Both changes must survive an abrupt process death immediately after logout/deletion.
        Prefs.setDriveVerificationEnabled(context, false)
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Synchronized
    fun updateIfToken(context: Context, token: String, update: (Session) -> Session): Session? {
        val current = load(context) ?: return null
        if (current.token != token) return null
        return update(current).also { save(context, it) }
    }

    /**
     * A /me response may arrive after a newer local username/privacy update.
     * Apply server fields only if nobody changed that field since the request.
     * Never write back the caller's stale whole-session snapshot.
     */
    fun refreshIfUnchanged(
        context: Context, requested: Session, serverUsername: String,
        serverLeaderboardVisible: Boolean
    ): Session? = updateIfToken(context, requested.token) { current ->
        mergeServerRefresh(current, requested, serverUsername, serverLeaderboardVisible)
    }

    internal fun mergeServerRefresh(
        current: Session, requested: Session, serverUsername: String,
        serverLeaderboardVisible: Boolean
    ): Session = current.copy(
        username = if (current.username == requested.username) serverUsername else current.username,
        leaderboardVisible = if (current.leaderboardVisible == requested.leaderboardVisible)
            serverLeaderboardVisible else current.leaderboardVisible
    )

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
        }.generateKey()
    }
}
