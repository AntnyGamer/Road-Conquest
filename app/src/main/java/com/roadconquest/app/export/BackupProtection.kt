package com.roadconquest.app.export

import android.content.Context
import android.util.Base64
import com.google.android.gms.tasks.Tasks
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.StandardIntegrityManager
import com.roadconquest.app.account.AccountClient
import com.roadconquest.app.data.TrackingRepository
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Authenticate exact ZIP bytes, including metadata. No signing secret is shipped in the APK. */
object BackupProtection {
    private const val MAX_COMMENT = 4096
    private const val TEMP_PREFIX = "roadconquest-protected-"
    private const val KEY_FILE = "backup-recovery.key"
    private val localKeyLock = Any()

    internal fun message(hash: String) = "RoadConquestBackup/v1\n$hash".toByteArray(Charsets.UTF_8)
    internal fun encode(bytes: ByteArray): String = Base64.encodeToString(bytes,
        Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    private fun decode(text: String) = Base64.decode(text, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    /** Only the digest and Play attestation leave the phone, never the backup or GPS history. */
    fun writePortable(context: Context, repository: TrackingRepository, output: OutputStream) {
        val status = AccountClient.backupKeys()
        check(status.optBoolean("available")) { "Protected backups are unavailable. Try again when the backup service is ready." }
        cacheKeys(context, status)
        val file = File.createTempFile(TEMP_PREFIX, ".zip", context.cacheDir)
        try {
            file.outputStream().use { DataExporter.writeZip(context, repository, it) }
            check(file.length() <= 512L * 1024 * 1024) { "Backup exceeds 512 MB" }
            val hash = digest(file, endOfZip(file))
            val requestHash = encode(MessageDigest.getInstance("SHA-256").digest(message(hash)))
            // Attestation and the signing request run after releasing the history/snapshot locks.
            val provider = Tasks.await(IntegrityManagerFactory.createStandard(context).prepareIntegrityToken(
                StandardIntegrityManager.PrepareIntegrityTokenRequest.builder()
                    .setCloudProjectNumber(status.getString("cloud_project").toLong()).build()
            ), 15, TimeUnit.SECONDS)
            val token = Tasks.await(provider.request(StandardIntegrityManager.StandardIntegrityTokenRequest.builder()
                .setRequestHash(requestHash).build()), 15, TimeUnit.SECONDS).token()
            val seal = AccountClient.signBackup(hash, token)
            verifyServerSeal(context, hash, seal)
            check(repository.isHistoryCurrent() && !com.roadconquest.app.util.Prefs.isDeviceDataDeletionPending(context)) {
                "Device progress changed while exporting. Export again."
            }
            attach(file, seal)
            file.inputStream().use { it.copyTo(output) }
        } finally { file.delete() }
    }

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
        when (seal.optString("kind")) {
            "server-p256" -> verifyServerSeal(context, hash, seal)
            "local-hmac" -> require(MessageDigest.isEqual(decode(seal.getString("signature")),
                localMac(context, hash, createKey = false))) { "Recovery backup was altered" }
            else -> error("Backup has no trusted signature")
        }
    }

    private fun verifyServerSeal(context: Context, hash: String, seal: JSONObject) {
        require(seal.optInt("version") == 1 && seal.optString("kind") == "server-p256") { "Invalid backup signature" }
        val id = seal.getString("key_id")
        require(id.matches(Regex("[A-Za-z0-9_-]{43}"))) { "Invalid backup signing key" }
        val prefs = context.getSharedPreferences("roadconquest_backup_keys", Context.MODE_PRIVATE)
        var key = prefs.getString(id, null)
        if (key == null) {
            // Keys are trusted only from our HTTPS service, never from the selected ZIP.
            cacheKeys(context, AccountClient.backupKeys())
            key = prefs.getString(id, null)
        }
        require(key != null) { "Backup signing key is unavailable. Connect to the internet and try again." }
        verifySignature(hash, seal.getString("signature"), decode(key))
    }

    internal fun verifySignature(hash: String, signature: String, spki: ByteArray) {
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(spki))
        require(Signature.getInstance("SHA256withECDSA").run {
            initVerify(key); update(message(hash)); verify(decode(signature))
        }) { "Backup was altered or its signature is invalid" }
    }

    private fun cacheKeys(context: Context, status: JSONObject) {
        val keys = status.getJSONArray("keys")
        require(keys.length() <= 8) { "Invalid backup key response" }
        val edit = context.getSharedPreferences("roadconquest_backup_keys", Context.MODE_PRIVATE).edit()
        for (i in 0 until keys.length()) {
            val key = keys.getJSONObject(i)
            val spki = key.getString("spki")
            require(spki.length <= 512 && encode(MessageDigest.getInstance("SHA-256").digest(decode(spki))) == key.getString("id")) {
                "Invalid backup key response"
            }
            edit.putString(key.getString("id"), spki)
        }
        check(edit.commit()) { "Could not save backup verification keys" }
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

    internal fun attach(file: File, seal: JSONObject) {
        val end = endOfZip(file)
        require(end.commentLength == 0) { "Backup is already signed" }
        val comment = seal.toString().toByteArray(Charsets.UTF_8)
        require(comment.size in 1..MAX_COMMENT)
        RandomAccessFile(file, "rw").use {
            it.seek(end.offset + 20); it.write(comment.size and 255); it.write(comment.size shr 8)
            it.write(comment); it.fd.sync()
        }
    }

    internal fun clearTemporaryFiles(context: Context) {
        context.cacheDir.listFiles().orEmpty().filter { it.name.startsWith(TEMP_PREFIX) }.forEach { it.delete() }
    }

    internal fun clearRecoveryKey(context: Context) = synchronized(localKeyLock) {
        val file = File(context.noBackupFilesDir, KEY_FILE)
        check(!file.exists() || file.delete()) { "Could not remove recovery key" }
    }
}
