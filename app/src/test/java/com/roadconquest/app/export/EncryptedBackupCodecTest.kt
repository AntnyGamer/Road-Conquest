package com.roadconquest.app.export

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.crypto.AEADBadTagException

class EncryptedBackupCodecTest {
    private val key = ByteArray(32) { it.toByte() }

    private fun encrypt(bytes: ByteArray): ByteArray =
        ByteArrayOutputStream().also { output ->
            EncryptedBackupCodec.Writer(output, "v2", key).use { it.write(bytes) }
        }.toByteArray()

    private fun decrypt(bytes: ByteArray, secret: ByteArray = key): ByteArray {
        val input = ByteArrayInputStream(bytes)
        val header = EncryptedBackupCodec.readHeader(input)
        assertEquals("v2", header.id)
        return ByteArrayOutputStream().also { output ->
            EncryptedBackupCodec.decrypt(input, output, secret, header, 512L * 1024 * 1024)
        }.toByteArray()
    }

    @Test fun emptyOneAndMultiRecordFilesRoundTrip() {
        for (size in listOf(0, 1, 32768, 32769, 131087)) {
            val data = ByteArray(size) { ((it * 37) xor (it ushr 4)).toByte() }
            assertArrayEquals(data, decrypt(encrypt(data)))
        }
    }

    @Test fun changedCiphertextAndHeaderAreRejected() {
        val original = encrypt(ByteArray(70000) { it.toByte() })
        val ciphertext = original.copyOf().apply { this[40] = (this[40].toInt() xor 1).toByte() }
        assertThrows(AEADBadTagException::class.java) { decrypt(ciphertext) }
        val header = original.copyOf().apply {
            this[EncryptedBackupCodec.magic.size + 1] = 'x'.code.toByte()
        }
        // A modified authenticated key ID is rejected even with the original key.
        assertThrows(AEADBadTagException::class.java) {
            val input = ByteArrayInputStream(header)
            val parsed = EncryptedBackupCodec.readHeader(input)
            EncryptedBackupCodec.decrypt(input, ByteArrayOutputStream(), key, parsed, 512L * 1024 * 1024)
        }
    }

    @Test fun wrongKeyTruncationAndTrailingBytesAreRejected() {
        val original = encrypt(ByteArray(70000) { it.toByte() })
        assertThrows(AEADBadTagException::class.java) {
            decrypt(original, ByteArray(32) { 7 })
        }
        assertThrows(Exception::class.java) { decrypt(original.copyOf(original.size - 16)) }
        assertThrows(IllegalArgumentException::class.java) { decrypt(original + byteArrayOf(1)) }
    }

    @Test fun removedOrReorderedRecordIsRejected() {
        val original = encrypt(ByteArray(70000) { it.toByte() })
        val firstRecord = EncryptedBackupCodec.magic.size + 1 + 2 + 12
        val firstFrameBytes = 4 + 32768 + 16
        val missing = original.copyOfRange(0, firstRecord) +
            original.copyOfRange(firstRecord + firstFrameBytes, original.size)
        assertThrows(AEADBadTagException::class.java) { decrypt(missing) }
    }

    @Test fun oversizedPlaintextIsRejected() {
        val original = encrypt(ByteArray(33000))
        assertThrows(IllegalArgumentException::class.java) {
            val input = ByteArrayInputStream(original)
            val header = EncryptedBackupCodec.readHeader(input)
            EncryptedBackupCodec.decrypt(input, ByteArrayOutputStream(), key, header, 32000)
        }
    }
}
