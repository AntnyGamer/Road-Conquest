package com.roadconquest.app.export

import android.content.Context
import com.roadconquest.app.data.TrackingRepository
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Offline, cross-installation backup for GitHub as well as Play builds.
 * The whole ordinary Road Conquest ZIP is encrypted and authenticated with AES-256-GCM.
 * This format never grants verified/competitive credit when imported.
 */
object PassphraseBackup {
    private val MAGIC = "RCBACKUP1\n".toByteArray(Charsets.US_ASCII)
    private const val SALT_SIZE = 16
    private const val NONCE_SIZE = 12
    private const val TAG_SIZE = 16
    private const val PBKDF2_ROUNDS = 310_000
    private const val MIN_PASSWORD_LENGTH = 12
    private const val MAX_PASSWORD_LENGTH = 256
    private const val TEMP_PREFIX = "roadconquest-private-export-"

    class PassphraseRequiredException : IllegalArgumentException("This backup requires a passphrase.")

    fun isEncrypted(file: File): Boolean {
        if (file.length() < (MAGIC.size + SALT_SIZE + NONCE_SIZE + TAG_SIZE).toLong()) return false
        return file.inputStream().use { input ->
            val header = ByteArray(MAGIC.size)
            runCatching { input.readFully(header); header.contentEquals(MAGIC) }.getOrDefault(false)
        }
    }

    private fun key(password: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, PBKDF2_ROUNDS, 256)
        return try {
            val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            try { SecretKeySpec(bytes, "AES") } finally { bytes.fill(0) }
        } finally { spec.clearPassword() }
    }

    fun writePortable(context: Context, repository: TrackingRepository, output: OutputStream, password: CharArray) {
        require(password.size in MIN_PASSWORD_LENGTH..MAX_PASSWORD_LENGTH) {
            "Use a passphrase between 12 and 256 characters."
        }
        DataImporter.clearTemporaryFiles(context)
        val file = File.createTempFile(TEMP_PREFIX, ".zip", context.cacheDir)
        try {
            file.outputStream().use { DataExporter.writeZip(context, repository, it) }
            // The encrypted wrapper adds a 38-byte header and a 16-byte authentication tag.
            require(file.length() <= DataImporter.MAX_BYTES - MAGIC.size - SALT_SIZE - NONCE_SIZE - TAG_SIZE) {
                "Backup exceeds 512 MB"
            }
            file.inputStream().use { encrypt(it, output, password) }
        } finally { file.delete() }
    }

    internal fun encrypt(plain: InputStream, output: OutputStream, password: CharArray) {
        require(password.size in MIN_PASSWORD_LENGTH..MAX_PASSWORD_LENGTH) {
            "Use a passphrase between 12 and 256 characters."
        }
        val salt = ByteArray(SALT_SIZE).also(SecureRandom()::nextBytes)
        val nonce = ByteArray(NONCE_SIZE).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(password, salt), GCMParameterSpec(128, nonce))
        output.write(MAGIC)
        output.write(salt)
        output.write(nonce)
        // Closing the cipher stream writes the final GCM tag.
        CipherOutputStream(output, cipher).use { encrypted -> plain.copyTo(encrypted, 32 * 1024) }
    }

    internal fun decrypt(encrypted: File, destination: File, password: CharArray) {
        require(password.isNotEmpty() && password.size <= MAX_PASSWORD_LENGTH) { "Enter a valid backup passphrase." }
        require(encrypted.length() <= DataImporter.MAX_BYTES) { "Backup exceeds 512 MB" }
        encrypted.inputStream().buffered().use { input ->
            val header = ByteArray(MAGIC.size)
            val salt = ByteArray(SALT_SIZE)
            val nonce = ByteArray(NONCE_SIZE)
            input.readFully(header)
            require(header.contentEquals(MAGIC)) { "Not a private Road Conquest backup" }
            input.readFully(salt)
            input.readFully(nonce)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(password, salt), GCMParameterSpec(128, nonce))
            try {
                CipherInputStream(input, cipher).use { plain ->
                    destination.outputStream().use { output ->
                        val buffer = ByteArray(32 * 1024)
                        var size = 0L
                        while (true) {
                            val count = plain.read(buffer)
                            if (count < 0) break
                            size += count
                            require(size <= DataImporter.MAX_BYTES) { "Backup exceeds 512 MB" }
                            output.write(buffer, 0, count)
                        }
                    }
                }
            } catch (e: java.io.IOException) {
                destination.delete()
                throw IllegalArgumentException("Incorrect passphrase or altered backup.", e)
            }
        }
    }

    private fun InputStream.readFully(target: ByteArray) {
        var offset = 0
        while (offset < target.size) {
            val count = read(target, offset, target.size - offset)
            require(count > 0) { "Incomplete private backup" }
            offset += count
        }
    }

    fun clearTemporaryFiles(context: Context) {
        context.cacheDir.listFiles().orEmpty()
            .filter { it.name.startsWith(TEMP_PREFIX) }
            .forEach { it.delete() }
    }
}
