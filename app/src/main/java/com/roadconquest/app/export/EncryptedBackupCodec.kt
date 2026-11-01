package com.roadconquest.app.export

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Bounded, independently authenticated AES-GCM records. No CipherInputStream:
 * its documented error handling can hide failed integrity checks on some JVMs.
 * A mandatory authenticated end record catches truncated/reordered archives.
 */
internal object EncryptedBackupCodec {
    val magic = "RCACCOUNT2\n".toByteArray(Charsets.US_ASCII)
    private const val NONCE_SIZE = 12
    private const val TAG_SIZE = 16
    private const val RECORD_SIZE = 32 * 1024
    private val ID = Regex("[A-Za-z0-9_-]{1,32}")

    data class Header(val id: String, val authenticated: ByteArray, val nonce: ByteArray)

    fun readHeader(input: InputStream): Header {
        val source = DataInputStream(input)
        val prefix = ByteArray(magic.size).also(source::readFully)
        require(prefix.contentEquals(magic)) { "Not a secure Road Conquest backup." }
        val size = source.readUnsignedByte()
        require(size in 1..32) { "Invalid backup key identifier." }
        val idBytes = ByteArray(size).also(source::readFully)
        val id = idBytes.toString(Charsets.US_ASCII)
        require(ID.matches(id)) { "Invalid backup key identifier." }
        val nonce = ByteArray(NONCE_SIZE).also(source::readFully)
        return Header(id, magic + byteArrayOf(size.toByte()) + idBytes, nonce)
    }

    private fun crypt(mode: Int, key: ByteArray, header: Header, sequence: Int, length: Int): Cipher {
        val nonce = header.nonce.copyOf()
        for (i in 0..3) nonce[NONCE_SIZE - 1 - i] =
            (nonce[NONCE_SIZE - 1 - i].toInt() xor (sequence ushr (8 * i))).toByte()
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_SIZE * 8, nonce))
            updateAAD(header.authenticated)
            updateAAD(ByteBuffer.allocate(8).putInt(sequence).putInt(length).array())
        }
    }

    class Writer(private val destination: OutputStream, id: String, private val key: ByteArray) : OutputStream() {
        private val sink = DataOutputStream(destination)
        private val header: Header
        private val pending = ByteArray(RECORD_SIZE)
        private var used = 0
        private var sequence = 0
        private var finished = false

        init {
            require(ID.matches(id)) { "Invalid backup key identifier." }
            require(key.size == 32) { "Invalid backup key." }
            val idBytes = id.toByteArray(Charsets.US_ASCII)
            val nonce = ByteArray(NONCE_SIZE).also(SecureRandom()::nextBytes)
            header = Header(id, magic + byteArrayOf(idBytes.size.toByte()) + idBytes, nonce)
            sink.write(header.authenticated)
            sink.write(nonce)
        }

        override fun write(value: Int) {
            check(!finished) { "Backup is already complete." }
            pending[used++] = value.toByte()
            if (used == RECORD_SIZE) emit(used)
        }

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            check(!finished) { "Backup is already complete." }
            require(offset >= 0 && length >= 0 && offset <= buffer.size - length)
            var index = offset
            val end = offset + length
            while (index < end) {
                val count = minOf(end - index, RECORD_SIZE - used)
                buffer.copyInto(pending, used, index, index + count)
                used += count
                index += count
                if (used == RECORD_SIZE) emit(used)
            }
        }

        private fun emit(count: Int) {
            sink.writeInt(count)
            sink.write(crypt(Cipher.ENCRYPT_MODE, key, header, sequence++, count).doFinal(pending, 0, count))
            used = 0
        }

        override fun flush() = sink.flush()

        override fun close() {
            if (!finished) {
                if (used > 0) emit(used)
                emit(0) // authenticated EOF: required even for an empty archive
                finished = true
            }
            sink.close()
        }
    }

    fun decrypt(source: InputStream, destination: OutputStream, key: ByteArray, header: Header,
                maxBytes: Long) {
        require(key.size == 32) { "Invalid backup key." }
        val input = DataInputStream(source)
        var sequence = 0
        var written = 0L
        while (true) {
            check(!Thread.currentThread().isInterrupted) { "Restore cancelled." }
            val count = input.readInt() // EOF before the authenticated terminator is an error.
            require(count in 0..RECORD_SIZE) { "Invalid backup record size." }
            val encrypted = ByteArray(count + TAG_SIZE).also(input::readFully)
            val plaintext = crypt(Cipher.DECRYPT_MODE, key, header, sequence++, count).doFinal(encrypted)
            check(plaintext.size == count) { "Invalid decrypted backup record." }
            if (count == 0) {
                require(input.read() == -1) { "Unexpected data after backup end." }
                return
            }
            written += count
            require(written <= maxBytes) { "Backup exceeds 512 MB." }
            destination.write(plaintext)
        }
    }
}
