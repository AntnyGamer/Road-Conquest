package com.roadconquest.app.export

import android.content.Context
import com.roadconquest.app.data.TrackingRepository
import com.roadconquest.app.util.Prefs
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.RandomAccessFile
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
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

    private fun sign(file: File): JSONObject {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val spki = pair.public.encoded
        val id = BackupProtection.encode(MessageDigest.getInstance("SHA-256").digest(spki))
        context.getSharedPreferences("roadconquest_backup_keys", Context.MODE_PRIVATE).edit()
            .putString(id, BackupProtection.encode(spki)).commit()
        val hash = BackupProtection.digest(file, BackupProtection.endOfZip(file))
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(pair.private); update(BackupProtection.message(hash)); sign()
        }
        return JSONObject().put("version", 1).put("kind", "server-p256").put("key_id", id)
            .put("signature", BackupProtection.encode(signature))
    }

    @Test fun signatureBytesMustFitTheRestoreLimitBeforeTheArchiveIsChanged() {
        val file = raw()
        try {
            val seal = sign(file)
            val original = file.readBytes()
            val completeSize = file.length() + seal.toString().toByteArray(Charsets.UTF_8).size
            assertThrows(IllegalArgumentException::class.java) {
                BackupProtection.attach(file, seal, maxBytes = completeSize - 1)
            }
            assertArrayEquals(original, file.readBytes())
            BackupProtection.attach(file, seal, maxBytes = completeSize)
            assertEquals(completeSize, file.length())
            BackupProtection.verify(context, file)
        } finally { file.delete() }
    }

    @Test fun firstExportCleansOrphansBeforeStartingNetworkOrCreatingAnActiveArchive() {
        val initialized = DataImporter::class.java.getDeclaredField("temporaryFilesInitialized").apply { isAccessible = true }
        val previousInitialized = initialized.getBoolean(null)
        val endpoint = com.roadconquest.app.account.AccountClient.endpointOverrideForTests
        val cacheOrphan = File(context.cacheDir, "roadconquest-protected-old.zip").apply { writeText("interrupted export") }
        val recoveryOrphan = File(context.filesDir, "before-restore.tmp.zip").apply { writeText("interrupted recovery") }
        try {
            initialized.setBoolean(null, false)
            com.roadconquest.app.account.AccountClient.endpointOverrideForTests = ""
            assertThrows(com.roadconquest.app.account.AccountClient.ApiException::class.java) {
                BackupProtection.writePortable(context, TrackingRepository(context), java.io.ByteArrayOutputStream())
            }
            assertTrue(initialized.getBoolean(null))
            assertFalse(cacheOrphan.exists())
            assertFalse(recoveryOrphan.exists())
        } finally {
            initialized.setBoolean(null, previousInitialized)
            com.roadconquest.app.account.AccountClient.endpointOverrideForTests = endpoint
            cacheOrphan.delete(); recoveryOrphan.delete()
        }
    }

    @Test fun authenticatedArchiveRestoresButRepackingValidEditedDataOrRemovingSealFails() {
        val file = raw()
        val changed = raw()
        try {
            val seal = sign(file)
            BackupProtection.attach(file, seal)
            file.inputStream().use { DataImporter.prepare(context, it).use { prepared ->
                assertEquals(0L, prepared.summary.trackPointCount)
            } }
            // Rebuild a valid ZIP with consistent counts and CRCs but edited appearance.
            val entries = ZipFile(file).use { zip -> zip.entries().toList().associate { entry ->
                entry.name to zip.getInputStream(entry).use { it.readBytes() }
            } }.toMutableMap()
            entries["metadata.json"] = JSONObject(String(entries.getValue("metadata.json"), Charsets.UTF_8))
                .put("gold_ui_enabled", true).toString().toByteArray()
            java.util.zip.ZipOutputStream(changed.outputStream()).use { zip -> entries.forEach { (name, bytes) ->
                zip.putNextEntry(java.util.zip.ZipEntry(name)); zip.write(bytes); zip.closeEntry()
            } }
            BackupProtection.attach(changed, seal)
            assertThrows(IllegalArgumentException::class.java) { changed.inputStream().use { DataImporter.prepare(context, it).close() } }
            val end = BackupProtection.endOfZip(file)
            RandomAccessFile(file, "rw").use { it.setLength(end.offset + 22); it.seek(end.offset + 20); it.write(0); it.write(0) }
            assertThrows(IllegalArgumentException::class.java) { file.inputStream().use { DataImporter.prepare(context, it).close() } }
            assertEquals(0L, TrackingRepository(context).getSummary().trackPointCount)
        } finally { file.delete(); changed.delete() }
    }

    @Test fun signedBackupsRestoreLegacyRoadsWithoutGroupIdsOrWeeklyCredit() {
        val db = TrackingRepository(context).readableDatabase()
        repeat(2) { index -> db.execSQL(
            """INSERT INTO roads(segment_id,name,geometry_json,first_unlocked_at,last_driven_at,
                min_lat,max_lat,min_lon,max_lon)
                VALUES (?,?,'[[-74,40],[-73.9,40]]',1000000,1005000,40,40,-74,-73.9)""",
            arrayOf("legacy-$index", "Legacy road $index")
        ) }
        val file = raw()
        try {
            BackupProtection.attach(file, sign(file))
            file.inputStream().use { input -> DataImporter.prepare(context, input).use { prepared ->
                assertEquals(2L, prepared.summary.roadsUnlockedCount)
                DataImporter.restore(context, prepared)
            } }
            val restored = TrackingRepository(context)
            assertEquals(2L, restored.getSummary().roadsUnlockedCount)
            restored.readableDatabase().rawQuery(
                "SELECT segment_id,road_group_id,weekly_eligible FROM roads ORDER BY segment_id", null
            ).use { cursor ->
                repeat(2) { index ->
                    assertTrue(cursor.moveToNext())
                    assertEquals("legacy-$index", cursor.getString(0))
                    assertEquals("", cursor.getString(1))
                    assertEquals(0, cursor.getInt(2))
                }
                assertFalse(cursor.moveToNext())
            }
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

    @Test fun deletingLocalDataRemovesRecoverySecretsAndInterruptedProtectedSnapshots() {
        val file = raw()
        try {
            BackupProtection.sealRecovery(context, file)
            val snapshot = File(context.cacheDir, "roadconquest-protected-orphan.zip").apply { writeText("private history") }
            com.roadconquest.app.data.LocalDataReset.stopTracking(context)
            com.roadconquest.app.data.LocalDataReset.clearStoppedData(context)
            assertFalse(snapshot.exists())
            assertFalse(File(context.noBackupFilesDir, "backup-recovery.key").exists())
            assertThrows(IllegalArgumentException::class.java) { BackupProtection.verify(context, file) }
        } finally { file.delete() }
    }

    @Test fun androidVerifiesTheSameServerGeneratedSignatureAsNode() {
        val fixture = javaClass.classLoader!!.getResourceAsStream("backup-signature.json")!!.use {
            JSONObject(it.bufferedReader().readText())
        }
        val spki = android.util.Base64.decode(fixture.getString("spki"), android.util.Base64.URL_SAFE)
        BackupProtection.verifySignature(fixture.getString("hash"), fixture.getString("signature"), spki)
        assertThrows(IllegalArgumentException::class.java) {
            BackupProtection.verifySignature("A".repeat(43), fixture.getString("signature"), spki)
        }
    }

    @Test fun schemaFifteenUpgradeKeepsHistoryAndNewLocalHistoryRemainsEligible() {
        android.database.sqlite.SQLiteDatabase.create(null).use { db ->
            val tables = listOf("track_points", "skipped_driving_fixes", "roads", "visited_places", "place_candidates")
            tables.forEach {
                db.execSQL("CREATE TABLE $it (id INTEGER PRIMARY KEY, history TEXT NOT NULL)")
                db.execSQL("INSERT INTO $it VALUES (1,'saved')")
            }
            com.roadconquest.app.data.AppDatabase.get(context).onUpgrade(db, 15, 16)
            tables.forEach { table -> db.rawQuery("SELECT history,weekly_eligible FROM $table", null).use {
                assertTrue(it.moveToFirst()); assertEquals("saved", it.getString(0)); assertEquals(1, it.getInt(1))
            } }
        }
    }
}
