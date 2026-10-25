package com.roadconquest.app.forum

import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import java.io.ByteArrayOutputStream
import java.net.URI
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.HttpsURLConnection

/** Bounded CDN media, verified against the exact bytes the server moderated. No account token is sent. */
class ForumGifLoader {
    private val workers = Executors.newFixedThreadPool(2) as ThreadPoolExecutor
    private class Ticket(val url: String, val hash: String) {
        val cancelled = AtomicBoolean(false)
        var work: Runnable? = null
        // Updated only on the UI thread, along with view binding.
        var finished = false
        var successful = false
    }
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var closed = false
    private var foreground = false
    private val views = java.util.Collections.newSetFromMap(java.util.WeakHashMap<ImageView, Boolean>())
    fun setForeground(value: Boolean) {
        foreground = value
        views.forEach { view ->
            (view.drawable as? AnimatedImageDrawable)?.let { if (value && view.isAttachedToWindow) it.start() else it.stop() }
        }
    }
    private val cache = GifByteCache()
    companion object {
        private val hosts = setOf("media.giphy.com","i.giphy.com","media.tenor.com")
        private const val HEX_DIGITS = "0123456789abcdef"
        internal fun parseHash(value: String): ByteArray? {
            if (value.length != 64) return null
            val bytes = ByteArray(32)
            for (index in bytes.indices) {
                val high = HEX_DIGITS.indexOf(value[index * 2])
                val low = HEX_DIGITS.indexOf(value[index * 2 + 1])
                if (high < 0 || low < 0) return null
                bytes[index] = ((high shl 4) or low).toByte()
            }
            return bytes
        }
        internal fun allowed(url: String): Boolean = runCatching {
            val uri = URI(url)
            uri.scheme == "https" && uri.host in hosts && uri.rawUserInfo == null &&
                (uri.port == -1 || uri.port == 443) && uri.fragment == null && uri.path.endsWith(".gif",ignoreCase = true)
        }.getOrDefault(false)
    }
    fun clear(view: ImageView) {
        (view.tag as? Ticket)?.let { ticket ->
            ticket.cancelled.set(true)
            ticket.work?.let { workers.remove(it) }
        }
        views.remove(view)
        (view.drawable as? AnimatedImageDrawable)?.stop()
        view.tag = null
        view.setImageDrawable(null)
    }
    fun load(view: ImageView, url: String, expectedHash: String) {
        val previous = view.tag as? Ticket
        // Adapter rebinds should not restart a live download or replay a
        // successful decode. Failed loads still retry when rebound.
        if (previous != null && !previous.cancelled.get() && previous.url == url &&
            previous.hash == expectedHash && (!previous.finished || previous.successful)) return
        clear(view)
        if (!allowed(url)) return
        val expectedDigest = parseHash(expectedHash) ?: return
        if (closed) return
        val ticket = Ticket(url,expectedHash)
        view.tag = ticket
        views.add(view)
        val work = Runnable {
            val image = runCatching {
                check(!ticket.cancelled.get())
                val bytes = cache.getOrLoad(expectedHash) { download(url,expectedDigest,ticket) }
                check(!ticket.cancelled.get())
                ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
                    require(info.size.width in 1..512 && info.size.height in 1..512)
                    decoder.setTargetSize(info.size.width,info.size.height)
                    decoder.isMutableRequired = false
                }
            }.getOrNull()
            main.post {
                if (!closed && !ticket.cancelled.get() && view.tag === ticket) {
                    ticket.finished = true
                    ticket.successful = image != null
                    view.setImageDrawable(image)
                    view.contentDescription = if (image == null) "GIF unavailable. Refresh to retry." else "Shared GIF"
                    if (foreground && view.isAttachedToWindow) (image as? AnimatedImageDrawable)?.start()
                }
            }
        }
        ticket.work = work
        workers.execute(work)
    }
    private fun download(url: String, expectedDigest: ByteArray, ticket: Ticket): ByteArray {
        val connection = URI(url).toURL().openConnection() as HttpsURLConnection
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 8000
        connection.readTimeout = 8000
        connection.setRequestProperty("Accept","image/gif")
        try {
            require(connection.responseCode == 200 && connection.contentType?.lowercase()?.contains("image/gif") == true)
            val output = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(4096)
                while (true) {
                    check(!ticket.cancelled.get())
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= 2 * 1024 * 1024)
                    output.write(buffer,0,count)
                }
            }
            val bytes = output.toByteArray()
            require(MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(bytes),expectedDigest))
            return bytes
        } finally { connection.disconnect() }
    }
    fun close() {
        closed = true
        views.toList().forEach(::clear)
        workers.shutdownNow(); main.removeCallbacksAndMessages(null)
        cache.clear()
    }
}

// A single GIF may be visible in multiple rows or views. Use a per-digest
// monitor to avoid duplicate CDN downloads without serializing different GIFs.
// Keep the monitor until every waiting caller leaves, including after failure
// or cancellation, so an unsuccessful first download can be retried normally.
internal class GifByteCache {
    private class Gate {
        val monitor = Any()
        var users = 0
    }
    private val values = object : LinkedHashMap<String, ByteArray>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?) = size > 4
    }
    private val gates = HashMap<String, Gate>()
    private var generation = 0L

    fun getOrLoad(digest: String, download: () -> ByteArray): ByteArray {
        val (gate, expectedGeneration) = synchronized(values) {
            gates.getOrPut(digest) { Gate() }.also { it.users++ } to generation
        }
        try {
            return synchronized(gate.monitor) {
                synchronized(values) { values[digest]?.takeIf { generation == expectedGeneration } }
                    ?: download().also { bytes ->
                        synchronized(values) {
                            // A closed screen must not be repopulated by late downloads.
                            if (generation == expectedGeneration) values[digest] = bytes
                        }
                    }
            }
        } finally {
            synchronized(values) {
                if (--gate.users == 0) gates.remove(digest)
            }
        }
    }

    fun clear() = synchronized(values) { generation++; values.clear() }
}
