package com.roadconquest.app.export

import android.content.Context
import com.roadconquest.app.account.AccountClient
import com.roadconquest.app.account.AccountStore
import com.roadconquest.app.data.TrackingRepository
import com.roadconquest.app.util.Prefs
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * A single portable, confidential, tamper-detecting backup for both app signing channels.
 * The account service derives a per-account key; no key or GPS archive is stored in this ZIP.
 * Restore requires the same active Road Conquest account and access to the service.
 */
object AccountBackup {
    private val MAGIC = "RCACCOUNT1\n".toByteArray(Charsets.US_ASCII)
    private const val NONCE_LENGTH = 12
    private const val TAG_LENGTH = 16

    private fun sessionKey(context: Context): Pair<String, ByteArray> {
        val token = AccountStore.load(context)?.token
            ?: error("Sign in to your Road Conquest account to create or restore a backup.")
        return token to AccountClient.backupEncryptionKey(token)
    }

    fun isEncrypted(file: File): Boolean {
        if (file.length() < MAGIC.size + NONCE_LENGTH + TAG_LENGTH) return false
        return file.inputStream().use { input ->
            val prefix = ByteArray(MAGIC.size)
            input.read(prefix) == prefix.size && prefix.contentEquals(MAGIC)
        }
    }

    private fun cipher(mode: Int, key: ByteArray, nonce: ByteArray): Cipher =
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_LENGTH * 8, nonce))
        }

    fun writePortable(context: Context, repository: TrackingRepository, output: OutputStream) {
        val (token, key) = sessionKey(context)
        try {
            val nonce = ByteArray(NONCE_LENGTH).also(SecureRandom()::nextBytes)
            output.write(MAGIC)
            output.write(nonce)
            CipherOutputStream(output, cipher(Cipher.ENCRYPT_MODE, key, nonce)).use { encrypted ->
                val limit = DataImporter.MAX_BYTES - MAGIC.size - NONCE_LENGTH - TAG_LENGTH
                var written = 0L
                val bounded = object : OutputStream() {
                    override fun write(value: Int) {
                        if (written == limit) throw java.io.IOException("Backup exceeds 512 MB")
                        encrypted.write(value)
                        written++
                    }
                    override fun write(buffer: ByteArray, offset: Int, length: Int) {
                        if (length.toLong() > limit - written) throw java.io.IOException("Backup exceeds 512 MB")
                        encrypted.write(buffer, offset, length)
                        written += length
                    }
                    override fun flush() = encrypted.flush()
                    // DataExporter closes its ZIP stream; keep GCM open until its final tag is written.
                    override fun close() = flush()
                }
                DataExporter.writeZip(context, repository, bounded)
            }
            check(repository.isHistoryCurrent() && !Prefs.isDeviceDataDeletionPending(context) &&
                AccountStore.load(context)?.token == token) {
                "Progress or account changed during backup. Export again."
            }
        } finally { key.fill(0) }
    }

    fun decrypt(context: Context, encrypted: File, destination: File) {
        require(encrypted.length() <= DataImporter.MAX_BYTES) { "Backup exceeds 512 MB" }
        val (token, key) = sessionKey(context)
        try {
            encrypted.inputStream().buffered().use { source ->
                val prefix = ByteArray(MAGIC.size)
                val nonce = ByteArray(NONCE_LENGTH)
                source.readFully(prefix)
                require(prefix.contentEquals(MAGIC)) { "Not a Road Conquest account backup" }
                source.readFully(nonce)
                CipherInputStream(object : FilterInputStream(source) {
                    override fun read(): Int {
                        check(!Thread.currentThread().isInterrupted) { "Restore cancelled" }
                        return super.read()
                    }
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        check(!Thread.currentThread().isInterrupted) { "Restore cancelled" }
                        return super.read(b, off, len)
                    }
                }, cipher(Cipher.DECRYPT_MODE, key, nonce)).use { decrypted ->
                    destination.outputStream().use { output ->
                        val buffer = ByteArray(32 * 1024)
                        var written = 0L
                        while (true) {
                            check(!Thread.currentThread().isInterrupted) { "Restore cancelled" }
                            val count = decrypted.read(buffer)
                            if (count < 0) break
                            written += count
                            require(written <= DataImporter.MAX_BYTES) { "Backup exceeds 512 MB" }
                            output.write(buffer, 0, count)
                        }
                    }
                }
            }
            check(AccountStore.load(context)?.token == token) {
                "Account changed while checking the backup. Try again."
            }
        } catch (error: Exception) {
            destination.delete()
            if (error is java.io.IOException && (
                error.cause is javax.crypto.AEADBadTagException ||
                error.cause is javax.crypto.BadPaddingException ||
                error.cause is javax.crypto.IllegalBlockSizeException
            )) throw IllegalArgumentException(
                "Backup authentication failed. Use the original file and the same account.", error)
            throw error
        } finally { key.fill(0) }
    }

    private fun InputStream.readFully(bytes: ByteArray) {
        var count = 0
        while (count < bytes.size) {
            val read = read(bytes, count, bytes.size - count)
            require(read > 0) { "Incomplete backup" }
            count += read
        }
    }
}
