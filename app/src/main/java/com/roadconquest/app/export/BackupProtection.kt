package com.roadconquest.app.export

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Authenticate the current on-device pre-restore recovery ZIP with a private HMAC key. */
object BackupProtection {
    private const val MAX_COMMENT = 4096
    private const val KEY_FILE = "backup-recovery.key"
    private val localKeyLock = Any()

    internal fun message(hash: String) = "RoadConquestBackup/v1\n$hash".toByteArray(Charsets.UTF_8)
    internal fun encode(bytes: ByteArray): String = Base64.encodeToString(bytes,
        Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    private fun decode(text: String) = Base64.decode(text, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    /** The private pre-restore copy remains available without network access. */
    internal fun sealRecovery(context: Context, file: File) {
        val hash = digest(file, endOfZip(file))
        attach(file, JSONObject().put("version", 1).put("kind", "local-hmac")
            .put("signature", encode(localMac(context, hash, createKey = true))))
    }

    fun verify(context: Context, file: File) {
        val end = endOfZip(file)
        require(end.commentLength in 1..MAX_COMMENT) {
            "This backup is unsigned and cannot be authenticated. Export a protected backup with the current app."
        }
        val comment = ByteArray(end.commentLength)
        RandomAccessFile(file, "r").use { it.seek(end.offset + 22); it.readFully(comment) }
        val seal = JSONObject(Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(comment)).toString())
        val hash = digest(file, end)
        require(seal.optInt("version") == 1) { "Unsupported backup signature" }
        require(seal.optString("kind") == "local-hmac") { "Backup has no trusted signature" }
        require(MessageDigest.isEqual(decode(seal.getString("signature")),
            localMac(context, hash, createKey = false))) { "Recovery backup was altered" }
    }

    private fun localMac(context: Context, hash: String, createKey: Boolean): ByteArray {
        // Private, excluded from Android backup, and never included in exported ZIPs.
        // Root access can already rewrite the local database and is outside this boundary.
        val key = synchronized(localKeyLock) {
            val file = File(context.noBackupFilesDir, KEY_FILE)
            if (!file.isFile && createKey) {
                val temporary = File.createTempFile("backup-key-", ".tmp", context.noBackupFilesDir)
                try {
                    java.io.FileOutputStream(temporary).use {
                        it.write(ByteArray(32).also(SecureRandom()::nextBytes)); it.fd.sync()
                    }
                    java.nio.file.Files.move(temporary.toPath(), file.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE)
                } finally { temporary.delete() }
            }
            require(file.isFile && file.length() == 32L) { "Recovery backup belongs to a different app installation" }
            file.readBytes()
        }
        return Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(key, "HmacSHA256")); doFinal(message(hash)) }
    }

    internal data class ZipEnd(val offset: Long, val commentLength: Int)

    internal fun endOfZip(file: File): ZipEnd = RandomAccessFile(file, "r").use { input ->
        require(input.length() >= 22) { "Incomplete backup ZIP" }
        val tail = ByteArray(minOf(input.length(), 65557L).toInt())
        input.seek(input.length() - tail.size); input.readFully(tail)
        for (i in tail.size - 22 downTo 0) {
            if (tail[i] != 0x50.toByte() || tail[i + 1] != 0x4b.toByte() || tail[i + 2] != 5.toByte() || tail[i + 3] != 6.toByte()) continue
            val size = (tail[i + 20].toInt() and 255) or ((tail[i + 21].toInt() and 255) shl 8)
            if (i + 22 + size == tail.size) return@use ZipEnd(input.length() - tail.size + i, size)
        }
        error("Incomplete backup ZIP")
    }

    internal fun digest(file: File, end: ZipEnd): String {
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(32 * 1024)
            var remaining = end.offset + 20
            while (remaining > 0) {
                check(!Thread.currentThread().isInterrupted) { "Backup check cancelled" }
                val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                check(count > 0) { "Incomplete backup ZIP" }
                hash.update(buffer, 0, count); remaining -= count
            }
        }
        // Sign the original ZIP with a zero-length comment. Only the signature itself is excluded.
        hash.update(byteArrayOf(0, 0))
        return encode(hash.digest())
    }

    internal fun attach(file: File, seal: JSONObject, maxBytes: Long = DataImporter.MAX_BYTES) {
        val end = endOfZip(file)
        require(end.commentLength == 0) { "Backup is already signed" }
        val comment = seal.toString().toByteArray(Charsets.UTF_8)
        require(comment.size in 1..MAX_COMMENT)
        require(file.length() <= maxBytes - comment.size) { "Signed backup exceeds 512 MB" }
        RandomAccessFile(file, "rw").use {
            it.seek(end.offset + 20); it.write(comment.size and 255); it.write(comment.size shr 8)
            it.write(comment); it.fd.sync()
        }
    }

    internal fun clearRecoveryKey(context: Context) = synchronized(localKeyLock) {
        val file = File(context.noBackupFilesDir, KEY_FILE)
        check(!file.exists() || file.delete()) { "Could not remove recovery key" }
    }
}
