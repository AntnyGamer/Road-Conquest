package com.roadconquest.app.account

import android.content.Context
import android.util.Base64
import com.roadconquest.app.util.Prefs
import java.io.InputStream
import java.io.OutputStream
import java.security.*
import java.security.cert.Certificate
import java.util.Collections
import java.util.Date
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37], manifest = Config.NONE)
@ConscryptMode(ConscryptMode.Mode.OFF)
class AccountStoreDecryptionTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private var originalProvider: Provider? = null

    @Before fun installFixtureKeyStore() {
        originalProvider = Security.getProvider("AndroidKeyStore")
        Security.removeProvider("AndroidKeyStore")
        Security.insertProviderAt(object : Provider("AndroidKeyStore", 1.0, "Local session fixture") {
            init {
                put("KeyStore.AndroidKeyStore", FixtureKeyStore::class.java.name)
            }
        }, 1)
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        assertEquals(key, store.getKey("roadconquest_account_session_v1", null))
    }

    @After fun restoreProvider() {
        Security.removeProvider("AndroidKeyStore")
        originalProvider?.let { Security.addProvider(it) }
    }

    private fun encoded(token: String, secret: Key = key): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding", "SunJCE")
        cipher.init(Cipher.ENCRYPT_MODE, secret, GCMParameterSpec(128, ByteArray(12) { (it + 3).toByte() }))
        return cipher.iv + cipher.doFinal(token.toByteArray(Charsets.UTF_8))
    }

    private fun saveFixture(bytes: ByteArray) {
        context.getSharedPreferences("roadconquest_account", Context.MODE_PRIVATE).edit()
            .putString("username", "FixtureDriver")
            .putString("token", Base64.encodeToString(bytes, Base64.NO_WRAP))
            .putBoolean("leaderboard_visible", true).commit()
    }

    @Test fun savedTokensRoundTripIncludingEmptyUnicodeAndLongValues() {
        for (token in listOf("", "ascii-session-token", "驾驶🚙\u0000é", "x".repeat(4096))) {
            val bytes = encoded(token)
            saveFixture(bytes)
            assertEquals(AccountStore.Session("FixtureDriver", token, true), AccountStore.load(context))
        }
    }

    @Test fun corruptIvTagTruncationAndWrongKeyStillClearSessionAndRevokeConsent() {
        val valid = encoded("secret-token")
        val invalid = listOf(
            valid.copyOf().apply { this[0] = (this[0].toInt() xor 1).toByte() },
            valid.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() },
            encoded("secret-token", SecretKeySpec(ByteArray(32) { 99 }, "AES"))
        ) + (0 until 29).map { valid.copyOf(it) }
        for (bytes in invalid) {
            saveFixture(bytes)
            Prefs.setDriveVerificationEnabled(context, true)
            assertNull(AccountStore.load(context))
            assertFalse(Prefs.isDriveVerificationEnabled(context))
            assertFalse(context.getSharedPreferences("roadconquest_account", Context.MODE_PRIVATE).contains("token"))
        }
    }

    // Android key storage is replaced only in this local JVM fixture. Encryption,
    // authentication and decryption are real SunJCE AES-GCM operations; device CI
    // separately exercises the production Android Keystore provider.
    class FixtureKeyStore : KeyStoreSpi() {
        override fun engineLoad(stream: InputStream?, password: CharArray?) = Unit
        override fun engineGetKey(alias: String?, password: CharArray?): Key = key
        override fun engineGetCertificateChain(alias: String?): Array<Certificate>? = null
        override fun engineGetCertificate(alias: String?): Certificate? = null
        override fun engineGetCreationDate(alias: String?): Date = Date(0)
        override fun engineSetKeyEntry(alias: String?, key: Key?, password: CharArray?, chain: Array<out Certificate>?) = throw UnsupportedOperationException()
        override fun engineSetKeyEntry(alias: String?, key: ByteArray?, chain: Array<out Certificate>?) = throw UnsupportedOperationException()
        override fun engineSetCertificateEntry(alias: String?, cert: Certificate?) = throw UnsupportedOperationException()
        override fun engineDeleteEntry(alias: String?) = throw UnsupportedOperationException()
        override fun engineAliases() = Collections.enumeration(listOf("roadconquest_account_session_v1"))
        override fun engineContainsAlias(alias: String?) = true
        override fun engineSize() = 1
        override fun engineIsKeyEntry(alias: String?) = true
        override fun engineIsCertificateEntry(alias: String?) = false
        override fun engineGetCertificateAlias(cert: Certificate?): String? = null
        override fun engineStore(stream: OutputStream?, password: CharArray?) = Unit
    }

    companion object {
        private val key = SecretKeySpec(ByteArray(32) { (it + 1).toByte() }, "AES")
    }
}
