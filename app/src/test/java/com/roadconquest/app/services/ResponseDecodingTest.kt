package com.roadconquest.app.services

import android.app.Application
import com.roadconquest.app.BuildConfig
import com.roadconquest.app.account.AccountClient
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.lang.management.ManagementFactory
import java.net.URL
import java.net.URLConnection
import java.net.URLStreamHandler
import java.security.cert.Certificate
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.HttpsURLConnection
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37], manifest = Config.NONE, application = Application::class)
class ResponseDecodingTest {
    // URL's factory belongs to the host JVM, shared by the SDK sandboxes. Store
    // only JDK types there. Gradle forks every test class. Only Robolectric's
    // Maven SDK downloads may use HTTPS outside the registered local replies.
    @Suppress("UNCHECKED_CAST")
    private fun replies(): ConcurrentHashMap<String, URLConnection> =
        System.getProperties()[REPLIES] as ConcurrentHashMap<String, URLConnection>

    @Before fun setup() {
        synchronized(System.getProperties()) {
            if (System.getProperties()[REPLIES] == null) {
                // A URL created before installing the factory retains the JDK
                // handler. Same-protocol relative URLs inherit that handler,
                // allowing a not-yet-cached SDK to download between sandboxes.
                val dependencyOrigin = URL("https://repo.maven.apache.org/")
                System.getProperties()[REPLIES] = ConcurrentHashMap<String, URLConnection>()
                URL.setURLStreamHandlerFactory { protocol ->
                    if (protocol != "https") null else object : URLStreamHandler() {
                        override fun openConnection(url: URL): URLConnection {
                            @Suppress("UNCHECKED_CAST")
                            val values = System.getProperties()[REPLIES] as ConcurrentHashMap<String, URLConnection>
                            return values[url.toString()] ?: if (url.host in
                                setOf("repo.maven.apache.org", "repo1.maven.org")) {
                                URL(dependencyOrigin, url.toString()).openConnection()
                            } else throw IOException("Unexpected fixture URL: $url")
                        }
                    }
                }
            }
        }
        replies().clear()
        AccountClient.endpointOverrideForTests = "https://response.invalid"
        resetConfig()
    }

    @After fun cleanup() {
        awaitConfig()
        AccountClient.endpointOverrideForTests = null
        replies().clear()
    }

    private class Reply(url: URL, private val status: Int, private val bytes: ByteArray?,
                        private val chunk: Int = 4096, private val failRead: Boolean = false) : HttpsURLConnection(url) {
        var closed = false
        var disconnected = false
        var readBytes = 0
        val sent = ByteArrayOutputStream()
        private fun input(): InputStream = object : ByteArrayInputStream(bytes ?: byteArrayOf()) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (failRead) throw IOException("Fixture read failure")
                return super.read(buffer, offset, minOf(length, chunk)).also { if (it > 0) readBytes += it }
            }
            override fun close() { closed = true; super.close() }
        }
        override fun getResponseCode() = status
        override fun getInputStream() = input()
        override fun getErrorStream() = if (bytes == null) null else input()
        override fun getOutputStream() = sent
        override fun disconnect() { disconnected = true }
        override fun connect() {}
        override fun usingProxy() = false
        override fun getCipherSuite() = "fixture"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
    }

    private fun account(bytes: ByteArray?, status: Int = 200, chunk: Int = 4096, failRead: Boolean = false): Reply {
        val url = URL("https://response.invalid/v1/me")
        return Reply(url, status, bytes, chunk, failRead).also { replies()[url.toString()] = it }
    }

    @Test fun accountRepliesKeepUnicodeReplacementAndBothByteBoundaries() {
        val prefix = "{\"username\":\"".toByteArray()
        val suffix = "\",\"leaderboard_visible\":true}".toByteArray()
        val samples = listOf("驾驶🚙é".toByteArray(), byteArrayOf(0xc0.toByte(), 0xaf.toByte()),
            byteArrayOf(0xed.toByte(), 0xa0.toByte(), 0x80.toByte()), byteArrayOf(0xf0.toByte(), 0x90.toByte(), 0x80.toByte()))
        for (sample in samples) for (chunk in listOf(1, 2, 7, 4096)) {
            val reply = account(prefix + sample + suffix, chunk = chunk)
            assertEquals(sample.toString(Charsets.UTF_8), AccountClient.me("fixture").username)
            assertTrue(reply.closed && reply.disconnected)
            assertFalse(reply.instanceFollowRedirects)
            assertEquals(10_000, reply.readTimeout)
        }
        val json = "{\"username\":\"limit\"}"
        val limit = account(json.padEnd(65_536).toByteArray())
        assertEquals("limit", AccountClient.me("fixture").username)
        assertEquals(65_536, limit.readBytes)
        val oversized = account(json.padEnd(65_537).toByteArray())
        val error = assertThrows(AccountClient.ApiException::class.java) { AccountClient.me("fixture") }
        assertEquals("Account server response was too large.", error.message)
        assertTrue(oversized.closed && oversized.disconnected)
    }

    @Test fun accountErrorsAndReadFailuresKeepStatusAndCleanup() {
        val rejected = account("{\"error\":\"等待🚙\"}".toByteArray(), 429, 1)
        val error = assertThrows(AccountClient.ApiException::class.java) { AccountClient.me("fixture") }
        assertEquals(429, error.status)
        assertEquals("等待🚙", error.message)
        assertTrue(rejected.closed && rejected.disconnected)
        for (bytes in listOf(null, byteArrayOf(), "invalid".toByteArray())) {
            val reply = account(bytes, 503)
            val invalid = assertThrows(AccountClient.ApiException::class.java) { AccountClient.me("fixture") }
            assertEquals(503, invalid.status)
            assertEquals("Account server returned an invalid response.", invalid.message)
            assertTrue(reply.disconnected)
        }
        val failed = account(byteArrayOf(1), failRead = true)
        assertThrows(IOException::class.java) { AccountClient.me("fixture") }
        assertTrue(failed.closed && failed.disconnected)
    }

    private fun field(name: String) = ServiceConfig::class.java.getDeclaredField(name).apply { isAccessible = true }
    private fun awaitConfig() {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (field("refreshing").getBoolean(null) && System.nanoTime() < deadline) Thread.sleep(5)
        assertFalse("Config fixture did not finish", field("refreshing").getBoolean(null))
    }
    private fun resetConfig() {
        awaitConfig()
        field("nextAttempt").setLong(null, 0L)
        field("values").set(null, emptyMap<String, Boolean>())
        RuntimeEnvironment.getApplication().getSharedPreferences("roadconquest_service_config", 0).edit().clear().commit()
    }
    private fun config(bytes: ByteArray, chunk: Int = 1024, status: Int = 200): Reply {
        resetConfig()
        val url = URL(BuildConfig.MAP_GATEWAY_URL.trimEnd('/') + "/v1/config")
        val reply = Reply(url, status, bytes, chunk)
        replies()[url.toString()] = reply
        ServiceConfig.refresh(RuntimeEnvironment.getApplication())
        awaitConfig()
        assertTrue(reply.disconnected)
        return reply
    }

    @Test fun configRepliesKeepRawTextByteCapAndOfflineDefaults() {
        val json = "{\"version\":1,\"providers\":{\"openfreemap\":false},\"label\":\"驾驶🚙\"}"
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("roadconquest_service_config", 0)
        for (chunk in listOf(1, 3, 1024)) {
            val reply = config(json.toByteArray(), chunk)
            assertTrue(reply.closed)
            assertEquals(json, prefs.getString("json", null))
            assertFalse(ServiceConfig.enabled("openfreemap"))
        }
        val exact = json + " ".repeat(4096 - json.toByteArray().size)
        assertEquals(4096, config(exact.toByteArray()).readBytes)
        assertEquals(exact, prefs.getString("json", null))
        for (body in listOf(exact + " ", "invalid", "{\"version\":2,\"providers\":{}}")) {
            assertTrue(config(body.toByteArray()).closed)
            assertNull(prefs.getString("json", null))
            assertTrue(ServiceConfig.enabled("openfreemap"))
        }
        assertEquals(0, config(json.toByteArray(), status = 503).readBytes)
        assertTrue(ServiceConfig.enabled("openfreemap"))
    }

    @Test fun accountResponseAllocationFixture() {
        val bean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        bean.isThreadAllocatedMemoryEnabled = true
        for (size in listOf(128, 4096, 65_536)) {
            val body = "{\"username\":\"" + "x".repeat(size - 15) + "\"}"
            assertEquals(size, body.toByteArray().size)
            account(body.toByteArray())
            repeat(50) { AccountClient.me("fixture") }
            val rounds = LongArray(3) {
                val start = bean.getThreadAllocatedBytes(Thread.currentThread().id)
                repeat(20) { assertEquals(size - 15, AccountClient.me("fixture").username.length) }
                bean.getThreadAllocatedBytes(Thread.currentThread().id) - start
            }
            println("RESPONSE_ALLOCATION sdk=${RuntimeEnvironment.getApiLevel()} bytes=$size calls=20 rounds=${rounds.contentToString()}")
        }
    }

    companion object { private const val REPLIES = "roadconquest.test.responseDecoding.replies" }
}
