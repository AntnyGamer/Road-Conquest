package com.roadconquest.app.export

import android.content.Context
import com.roadconquest.app.data.TrackingRepository
import com.roadconquest.app.util.Prefs
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.ZipFile

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37], manifest = Config.NONE)
class BackupProtectionTest {
    private lateinit var context: Context
    @Before fun reset() {
        context = RuntimeEnvironment.getApplication()
        TrackingRepository(context).clearHistory()
        Prefs.setDeviceDataDeletionPending(context, false)
    }

    private fun raw(): File = File.createTempFile("backup-test-", ".zip", context.cacheDir).also {
        it.outputStream().use { output -> DataExporter.writeZip(context, TrackingRepository(context), output) }
    }

    @Test fun passwordFreeExportIsReadableOfflineButCannotBeRestored() {
        val repository = TrackingRepository(context)
        repository.insertLocation(android.location.Location("gps").apply {
            latitude = 40.0
            longitude = -74.0
            accuracy = 5f
            time = 1_000_000L
        })
        val bytes = java.io.ByteArrayOutputStream().also { ExportOnlyBackup.write(context, repository, it) }
            .toByteArray()
        val file = File.createTempFile("export-only-", ".zip", context.cacheDir)
        try {
            file.writeBytes(bytes)
            assertTrue(ExportOnlyBackup.isExportOnly(file))
            ZipFile(file).use { zip ->
                assertNotNull(zip.getEntry("metadata.json"))
                assertNotNull(zip.getEntry("track_points.csv"))
            }
            val rejected = assertThrows(IllegalArgumentException::class.java) {
                bytes.inputStream().use { DataImporter.prepare(context, it).close() }
            }
            assertTrue(rejected.message.orEmpty().contains("export-only"))
            assertEquals(1L, repository.getSummary().trackPointCount)
        } finally { file.delete() }
    }

    @Test fun recoverySealIsOfflineInstallationBoundAndDetectsByteChanges() {
        val file = raw()
        try {
            BackupProtection.sealRecovery(context, file)
            BackupProtection.verify(context, file)
            val bytes = file.readBytes()
            RandomAccessFile(file, "rw").use { it.seek(40); it.write(bytes[40].toInt() xor 1) }
            assertThrows(IllegalArgumentException::class.java) { BackupProtection.verify(context, file) }
            file.writeBytes(bytes)
            File(context.noBackupFilesDir, "backup-recovery.key").delete()
            assertThrows(IllegalArgumentException::class.java) { BackupProtection.verify(context, file) }
        } finally { file.delete() }
    }

    @Test fun deletingLocalDataRemovesRecoverySecrets() {
        val file = raw()
        try {
            BackupProtection.sealRecovery(context, file)
            com.roadconquest.app.data.LocalDataReset.stopTracking(context)
            com.roadconquest.app.data.LocalDataReset.clearStoppedData(context)
            assertFalse(File(context.noBackupFilesDir, "backup-recovery.key").exists())
            assertThrows(IllegalArgumentException::class.java) { BackupProtection.verify(context, file) }
        } finally { file.delete() }
    }

}
