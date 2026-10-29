package com.roadconquest.app.export

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37], manifest = Config.NONE)
class PassphraseBackupTest {
    private val password = "correct horse battery staple".toCharArray()

    private fun privateFile(contents: ByteArray): File = File.createTempFile("encrypted-test-", ".rcbackup",
        RuntimeEnvironment.getApplication<android.content.Context>().cacheDir).apply { writeBytes(contents) }

    @Test fun crossInstallPortableRoundTripAuthenticatesAllBytes() {
        val plain = ByteArray(40_000) { (it % 251).toByte() }
        val output = ByteArrayOutputStream()
        PassphraseBackup.encrypt(ByteArrayInputStream(plain), output, password)
        val first = privateFile(output.toByteArray())
        val restored = File.createTempFile("decrypted-test-", ".zip")
        try {
            assertTrue(PassphraseBackup.isEncrypted(first))
            PassphraseBackup.decrypt(first, restored, password)
            assertArrayEquals(plain, restored.readBytes())
        } finally { first.delete(); restored.delete() }
    }

    @Test fun changingCiphertextOrWrongPasswordFailsAuthentication() {
        val output = ByteArrayOutputStream()
        PassphraseBackup.encrypt(ByteArrayInputStream("private history".toByteArray()), output, password)
        val bytes = output.toByteArray()
        val file = privateFile(bytes)
        val restored = File.createTempFile("decrypted-test-", ".zip")
        try {
            try {
                PassphraseBackup.decrypt(file, restored, "another 12 character password".toCharArray())
                fail("Wrong passphrase was accepted")
            } catch (_: IllegalArgumentException) { }
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
            file.writeBytes(bytes)
            try {
                PassphraseBackup.decrypt(file, restored, password)
                fail("Tampered backup was accepted")
            } catch (_: IllegalArgumentException) { }
        } finally { file.delete(); restored.delete() }
    }

    @Test fun rejectsWeakPassphrasesBeforeWriting() {
        val output = ByteArrayOutputStream()
        try {
            PassphraseBackup.encrypt(ByteArrayInputStream(byteArrayOf(1)), output, "short".toCharArray())
            fail("Short passphrase was accepted")
        } catch (_: IllegalArgumentException) { }
        assertEquals(0, output.size())
    }
}
