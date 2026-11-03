package com.roadconquest.app

import android.app.Application
import android.location.Location
import com.roadconquest.app.account.AccountClient
import com.roadconquest.app.account.AccountStore
import com.roadconquest.app.account.AccountStoreDecryptionTest
import com.roadconquest.app.data.TrackingRepository
import com.roadconquest.app.export.AccountBackup
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URL
import java.net.URLConnection
import java.net.URLStreamHandler
import java.security.Provider
import java.security.Security
import java.security.cert.Certificate
import javax.net.ssl.HttpsURLConnection

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], manifest = Config.NONE, application = Application::class)
class SecureBackupDocumentTest {
    @Test fun providerCloseMustFinishBeforeHistoryAndAccountValidation() {
        val app = RuntimeEnvironment.getApplication()
        val originalProvider = Security.getProvider("AndroidKeyStore")
        Security.removeProvider("AndroidKeyStore")
        Security.insertProviderAt(object : Provider("AndroidKeyStore", 1.0, "Review session fixture") {
            init { put("KeyStore.AndroidKeyStore", AccountStoreDecryptionTest.FixtureKeyStore::class.java.name) }
        }, 1)
        val dependencyOrigin = URL("https://repo.maven.apache.org/")
        URL.setURLStreamHandlerFactory { protocol ->
            if (protocol != "https") null else object : URLStreamHandler() {
                override fun openConnection(url: URL): URLConnection {
                    if (url.host in setOf("repo.maven.apache.org", "repo1.maven.org"))
                        return URL(dependencyOrigin, url.toString()).openConnection()
                    if (url.toString() != "https://review.invalid/v1/backups/account-key")
                        throw IOException("Unexpected review URL: $url")
                    return object : HttpsURLConnection(url) {
                        override fun getResponseCode() = 200
                        override fun getInputStream() = ByteArrayInputStream(
                            "{\"key_id\":\"review\",\"key\":\"${java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 42 })}\"}".toByteArray())
                        override fun connect() {}
                        override fun disconnect() {}
                        override fun usingProxy() = false
                        override fun getCipherSuite() = "fixture"
                        override fun getLocalCertificates(): Array<Certificate>? = null
                        override fun getServerCertificates(): Array<Certificate> = emptyArray()
                    }
                }
            }
        }
        AccountClient.endpointOverrideForTests = "https://review.invalid"
        try {
            val actions = listOf<Pair<String, () -> Unit>>(
                "unchanged" to {},
                "reset" to { TrackingRepository(app).clearHistory() },
                "signout" to { AccountStore.clear(app) },
                "switch" to { AccountStore.save(app, AccountStore.Session("OtherDriver", "other-session", false)) },
                "deletion" to { com.roadconquest.app.util.Prefs.setDeviceDataDeletionPending(app, true) }
            )
            for ((name, duringClose) in actions) {
                com.roadconquest.app.util.Prefs.setDeviceDataDeletionPending(app, false)
                AccountStore.save(app, AccountStore.Session("ReviewDriver", "review-session", false))
                TrackingRepository(app).clearHistory()
                val repository = TrackingRepository(app)
                repository.insertLocation(Location("gps").apply {
                    latitude = 40.0; longitude = -74.0; accuracy = 5f; time = 1000L
                })
                var closed = false
                val destination = object : ByteArrayOutputStream() {
                    override fun close() {
                        if (!closed) { closed = true; duringClose() }
                        super.close()
                    }
                }
                val result = runCatching { AccountBackup.writeDocument(app, repository) { destination } }
                assertTrue(closed)
                assertEquals("Provider close: $name", name != "unchanged", result.isFailure)
                assertTrue("Actual encrypted backup was written", destination.size() > 100)
            }
        } finally {
            com.roadconquest.app.util.Prefs.setDeviceDataDeletionPending(app, false)
            AccountClient.endpointOverrideForTests = null
            Security.removeProvider("AndroidKeyStore")
            originalProvider?.let { Security.addProvider(it) }
        }
    }
}
