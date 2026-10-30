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
        RuntimeEnvironment.getApplication().cacheDir).apply { writeBytes(contents) }

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

    @Test fun actualGithubExportCanBeImportedWithoutPlayOrNetwork() {
        val context: android.content.Context = RuntimeEnvironment.getApplication()
        com.roadconquest.app.data.TrackingRepository(context).clearHistory()
        val repository = com.roadconquest.app.data.TrackingRepository(context)
        val fix = android.location.Location("gps").apply {
            latitude = 40.0; longitude = -74.0; time = 1_000_000L; accuracy = 5f
        }
        repository.insertLocation(fix)
        val out = ByteArrayOutputStream()
        PassphraseBackup.writePortable(context, repository, out, password)
        val data = out.toByteArray()
        val file = privateFile(data)
        try {
            assertTrue(PassphraseBackup.isEncrypted(file))
            assertThrows(PassphraseBackup.PassphraseRequiredException::class.java) {
                ByteArrayInputStream(data).use { DataImporter.prepare(context, it).close() }
            }
            ByteArrayInputStream(data).use {
                DataImporter.prepare(context, it, password).use { prepared ->
                    assertEquals(1L, prepared.summary.trackPointCount)
                }
            }
            assertEquals(1L, repository.getSummary().trackPointCount)
        } finally { file.delete() }
    }

    @Test fun privateExportDoesNotCreateAPlaintextIntermediateZip() {
        val context: android.content.Context = RuntimeEnvironment.getApplication()
        com.roadconquest.app.data.TrackingRepository(context).clearHistory()
        val repository = com.roadconquest.app.data.TrackingRepository(context)
        val bytes = ByteArrayOutputStream()
        var observedEncryptedWrite = false
        val monitored = object : java.io.OutputStream() {
            override fun write(value: Int) = bytes.write(value)
            override fun write(buffer: ByteArray, offset: Int, length: Int) {
                if (!observedEncryptedWrite && bytes.size() >= 38) {
                    observedEncryptedWrite = true
                    assertTrue("Never write a plaintext ZIP into the cache during encrypted export",
                        context.cacheDir.listFiles().orEmpty().none {
                            it.name.startsWith("roadconquest-private-export-")
                        })
                }
                bytes.write(buffer, offset, length)
            }
        }
        PassphraseBackup.writePortable(context, repository, monitored, password)
        assertTrue(observedEncryptedWrite)
        val backup = privateFile(bytes.toByteArray())
        val decoded = File.createTempFile("private-test-", ".zip")
        try {
            PassphraseBackup.decrypt(backup, decoded, password)
            java.util.zip.ZipFile(decoded).use { zip ->
                assertNotNull(zip.getEntry("track_points.csv"))
            }
        } finally { backup.delete(); decoded.delete() }
    }

    @Test fun encryptedExportRejectsConcurrentDeviceHistoryDeletion() {
        val context: android.content.Context = RuntimeEnvironment.getApplication()
        com.roadconquest.app.data.TrackingRepository(context).clearHistory()
        val repository = com.roadconquest.app.data.TrackingRepository(context)
        var deleted = false
        val sink = object : java.io.OutputStream() {
            override fun write(value: Int) {}
            override fun write(buffer: ByteArray, offset: Int, length: Int) {
                if (!deleted && length > 0) {
                    deleted = true
                    repository.clearHistory()
                }
            }
        }
        try {
            PassphraseBackup.writePortable(context, repository, sink, password)
            fail("History deleted during export must invalidate the backup")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message.orEmpty().contains("progress changed"))
        }
        assertTrue(deleted)
    }

    @Test fun cancelledPrivateRestoreStopsBeforePasswordDerivationOrWriting() {
        val output = ByteArrayOutputStream()
        PassphraseBackup.encrypt(ByteArrayInputStream("private history".toByteArray()), output, password)
        val file = privateFile(output.toByteArray())
        val restored = File(file.parentFile, "cancelled-restore.zip")
        try {
            Thread.currentThread().interrupt()
            val error = assertThrows(IllegalStateException::class.java) {
                PassphraseBackup.decrypt(file, restored, password)
            }
            assertEquals("Import cancelled", error.message)
            assertFalse(restored.exists())
        } finally {
            Thread.interrupted()
            file.delete(); restored.delete()
        }
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
