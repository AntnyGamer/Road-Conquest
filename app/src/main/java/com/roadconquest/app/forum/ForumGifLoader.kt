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
    private class Ticket {
        val cancelled = AtomicBoolean(false)
        var work: Runnable? = null
    }
    private val main = Handler(Looper.getMainLooper())
    private var closed = false
    private var foreground = false
    private val views = java.util.Collections.newSetFromMap(java.util.WeakHashMap<ImageView, Boolean>())
    fun setForeground(value: Boolean) {
        foreground = value
        views.forEach { view ->
            (view.drawable as? AnimatedImageDrawable)?.let { if (value && view.isAttachedToWindow) it.start() else it.stop() }
        }
    }
    private val cache = object : LinkedHashMap<String, ByteArray>(4,0.75f,true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?) = size > 4
    }
    companion object {
        private val hosts = setOf("media.giphy.com","i.giphy.com","media.tenor.com")
        internal fun allowed(url: String): Boolean = runCatching {
            val uri = URI(url)
            uri.scheme == "https" && uri.host in hosts && uri.rawUserInfo == null &&
                uri.port in listOf(-1,443) && uri.fragment == null && uri.path.endsWith(".gif",ignoreCase = true)
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
        clear(view)
        if (!allowed(url) || !Regex("[a-f0-9]{64}").matches(expectedHash)) return
        if (closed) return
        val ticket = Ticket()
        view.tag = ticket
        views.add(view)
        val work = Runnable {
            val image = runCatching {
                check(!ticket.cancelled.get())
                val cached = synchronized(cache) { cache[expectedHash] }
                val bytes = cached ?: download(url,expectedHash,ticket).also { synchronized(cache) { cache[expectedHash] = it } }
                check(!ticket.cancelled.get())
                ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
                    require(info.size.width in 1..512 && info.size.height in 1..512)
                    decoder.setTargetSize(info.size.width,info.size.height)
                    decoder.isMutableRequired = false
                }
            }.getOrNull()
            main.post {
                if (!closed && !ticket.cancelled.get() && view.tag === ticket) {
                    view.setImageDrawable(image)
                    view.contentDescription = if (image == null) "GIF unavailable. Refresh to retry." else "Shared GIF"
                    if (foreground && view.isAttachedToWindow) (image as? AnimatedImageDrawable)?.start()
                }
            }
        }
        ticket.work = work
        workers.execute(work)
    }
    private fun download(url: String, expectedHash: String, ticket: Ticket): ByteArray {
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
            val actual = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            require(actual == expectedHash)
            return bytes
        } finally { connection.disconnect() }
    }
    fun close() {
        closed = true
        views.toList().forEach(::clear)
        workers.shutdownNow(); main.removeCallbacksAndMessages(null)
        synchronized(cache) { cache.clear() }
    }
}
