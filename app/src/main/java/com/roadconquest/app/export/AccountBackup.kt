package com.roadconquest.app.export

import android.content.Context
import com.roadconquest.app.account.AccountClient
import com.roadconquest.app.account.AccountStore
import com.roadconquest.app.data.TrackingRepository
import com.roadconquest.app.util.Prefs
import java.io.File
import java.io.OutputStream

/** Portable account-bound encrypted backups for Play and GitHub installations. */
object AccountBackup {
    private data class SessionKey(val token: String, val id: String, val bytes: ByteArray)

    private fun sessionKey(context: Context, keyId: String? = null): SessionKey {
        val token = AccountStore.load(context)?.token
            ?: error("Sign in to your Road Conquest account to create or restore a backup.")
        val (id, bytes) = AccountClient.backupEncryptionKey(token, keyId)
        return SessionKey(token, id, bytes)
    }

    fun isEncrypted(file: File): Boolean {
        if (file.length() < EncryptedBackupCodec.magic.size + 1 + 1 + 12 + 4 + 16) return false
        return file.inputStream().use { source ->
            val prefix = ByteArray(EncryptedBackupCodec.magic.size)
            java.io.DataInputStream(source).readFully(prefix)
            prefix.contentEquals(EncryptedBackupCodec.magic)
        }
    }

    fun writePortable(context: Context, repository: TrackingRepository, output: OutputStream) {
        val session = sessionKey(context)
        try {
            var written = 0L
            val bounded = object : OutputStream() {
                override fun write(value: Int) {
                    check(written < DataImporter.MAX_BYTES) { "Backup exceeds 512 MB." }
                    output.write(value)
                    written++
                }
                override fun write(buffer: ByteArray, offset: Int, length: Int) {
                    require(length.toLong() <= DataImporter.MAX_BYTES - written) { "Backup exceeds 512 MB." }
                    output.write(buffer, offset, length)
                    written += length
                }
                override fun flush() = output.flush()
                override fun close() = output.close()
            }
            EncryptedBackupCodec.Writer(bounded, session.id, session.bytes).use { encrypted ->
                DataExporter.writeZip(context, repository, object : OutputStream() {
                    override fun write(value: Int) = encrypted.write(value)
                    override fun write(buffer: ByteArray, offset: Int, length: Int) =
                        encrypted.write(buffer, offset, length)
                    override fun flush() = encrypted.flush()
                    // The ZIP exporter closes its stream; GCM's authenticated EOF
                    // must be written only after the ZIP stream has finished.
                    override fun close() = encrypted.flush()
                })
            }
            check(repository.isHistoryCurrent() && !Prefs.isDeviceDataDeletionPending(context) &&
                AccountStore.load(context)?.token == session.token) {
                "Progress or account changed during backup. Export again."
            }
        } finally { session.bytes.fill(0) }
    }

    fun decrypt(context: Context, encrypted: File, destination: File) {
        require(encrypted.length() <= DataImporter.MAX_BYTES) { "Backup exceeds 512 MB." }
        var key: ByteArray? = null
        try {
            encrypted.inputStream().buffered().use { source ->
                val header = EncryptedBackupCodec.readHeader(source)
                val session = sessionKey(context, header.id)
                key = session.bytes
                destination.outputStream().use { output ->
                    EncryptedBackupCodec.decrypt(source, output, session.bytes, header, DataImporter.MAX_BYTES)
                }
                check(AccountStore.load(context)?.token == session.token) {
                    "Account changed while checking the backup. Try again."
                }
            }
        } catch (error: Exception) {
            destination.delete()
            if (error is javax.crypto.AEADBadTagException) throw IllegalArgumentException(
                "Backup authentication failed. Use the original file and the same account.", error)
            throw error
        } finally { key?.fill(0) }
    }
}
