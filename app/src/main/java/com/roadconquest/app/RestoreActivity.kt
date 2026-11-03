package com.roadconquest.app

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.roadconquest.app.export.DataImporter
import com.roadconquest.app.export.PassphraseBackup
import com.roadconquest.app.util.Appearance
import com.roadconquest.app.util.MeasurementUnits
import android.graphics.Typeface
import com.roadconquest.app.util.ForegroundSession
import java.util.Locale
import java.util.concurrent.Executors

class RestoreActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var status: TextView
    private lateinit var choose: Button
    private lateinit var recovery: Button
    private var prepared: DataImporter.Prepared? = null
    private var busy = false
    private var restoreStarted = false

    override fun attachBaseContext(newBase: Context) { super.attachBaseContext(Appearance.wrap(newBase)) }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(Appearance.themeRes(this))
        super.onCreate(savedInstanceState)
        WindowCompat.enableEdgeToEdge(window)
        // System bars sit on this screen's themed background, not the dark map fog.
        val lightSystemBars = !Appearance.isDark(this)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = lightSystemBars
            isAppearanceLightNavigationBars = lightSystemBars
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        setContentView(ScrollView(this).apply { addView(root) })
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val p = (20 * resources.displayMetrics.density).toInt()
            view.setPadding(p + safe.left, p + safe.top, p + safe.right, p + safe.bottom)
            insets
        }
        val dp = resources.displayMetrics.density
        root.addView(TextView(this).apply {
            text = getString(R.string.ui_restore_your_progress)
            isAccessibilityHeading = true
            textSize = 28f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Appearance.color(this@RestoreActivity, R.attr.rcTextPrimary))
            setPadding(0, 0, 0, (12 * dp).toInt())
        })
        root.addView(TextView(this).apply {
            text = getString(R.string.ui_choose_a_road_conquest_backup_to_restore_trips_roads_distance_fo)
            textSize = 15f
            setTextColor(Appearance.color(this@RestoreActivity, R.attr.rcTextSecondary))
            setBackgroundResource(R.drawable.settings_card_background)
            val inset = (16 * dp).toInt()
            setPadding(inset, inset, inset, inset)
        })
        choose = Button(this).apply {
            text = getString(R.string.ui_choose_backup_zip_or_rcbackup)
            setOnClickListener {
                try {
                    startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                        // Private .rcbackup files may be assigned provider-specific MIME types.
                        // Let the strict backup validator reject unrelated file types instead
                        // of hiding legitimate encrypted backups in Android's picker.
                    }, REQUEST_IMPORT)
                } catch (_: ActivityNotFoundException) { status.text = getString(R.string.ui_android_s_file_picker_is_unavailable) }
            }
            root.addView(this, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (14 * dp).toInt() })
        }
        recovery = Button(this, null, 0, R.style.RoadConquest_SettingsAction).apply {
            text = getString(R.string.ui_restore_progress_before_last_import)
            isEnabled = DataImporter.previousBackup(this@RestoreActivity).isFile
            setOnClickListener { validate { DataImporter.previousBackup(applicationContext).inputStream() } }
            root.addView(this, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (10 * dp).toInt() })
        }
        status = TextView(this).apply {
            textSize = 15f
            setTextColor(Appearance.color(this@RestoreActivity, R.attr.rcTextPrimary))
            setPadding(0, (12 * dp).toInt(), 0, (8 * dp).toInt())
            root.addView(this)
        }
    }

    @Deprecated("Storage Access Framework on minSdk 31")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_IMPORT || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        validate { contentResolver.openInputStream(uri) ?: error("Could not open backup") }
    }

    private fun setBusy(value: Boolean) {
        busy = value
        choose.isEnabled = !value
        recovery.isEnabled = !value && DataImporter.previousBackup(this).isFile
    }

    private fun promptPassphrase(open: () -> java.io.InputStream) {
        val field = EditText(this).apply {
            hint = getString(R.string.ui_backup_passphrase)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            val p = (16 * resources.displayMetrics.density).toInt()
            setPadding(p, p, p, p)
        }
        AlertDialog.Builder(this).setTitle(getString(R.string.ui_unlock_private_backup))
            .setMessage(getString(R.string.ui_enter_the_passphrase_used_when_this_backup_was_exported))
            .setView(field)
            .setNegativeButton(getString(R.string.ui_cancel), null)
            .setPositiveButton(getString(R.string.ui_unlock)) { _, _ ->
                val secret = field.text.toString().toCharArray()
                field.text.clear()
                validate(open, secret)
            }.show()
    }

    private fun validate(open: () -> java.io.InputStream) = validate(open, null)

    private fun validate(open: () -> java.io.InputStream, passphrase: CharArray?) {
        if (busy) { passphrase?.fill(0.toChar()); return }
        setBusy(true)
        status.text = getString(R.string.ui_checking_backup)
        worker.execute {
            val result = try {
                runCatching { open().use { DataImporter.prepare(applicationContext, it, passphrase) } }
            } finally { passphrase?.fill(0.toChar()) }
            runOnUiThread {
                if (isDestroyed || isFinishing) { result.getOrNull()?.close(); return@runOnUiThread }
                setBusy(false)
                result.fold({ backup ->
                    prepared?.close()
                    prepared = backup
                    status.text = getString(R.string.ui_backup_validated)
                    val summary = backup.summary
                    val message = String.format(Locale.getDefault(),
                        "Backup: %d roads • %s • %d GPS fixes\nPoints: %d\nExported: %s\n\nReplace this phone's current progress? A recovery backup is saved before replacement. Tracking and verified sharing will be turned off.",
                        summary.roadsUnlockedCount, MeasurementUnits.current().longDistance(summary.distanceMeters, decimals = 2), summary.trackPointCount,
                        backup.metadata.getLong("points_balance"), backup.metadata.getString("exported_at"))
                    AlertDialog.Builder(this).setTitle(getString(R.string.ui_replace_device_progress)).setMessage(message)
                        .setNegativeButton(getString(R.string.ui_cancel), null)
                        .setPositiveButton(getString(R.string.ui_restore)) { _, _ ->
                            prepared = null
                            restore(backup)
                        }
                        .setOnDismissListener { if (prepared === backup) { prepared = null; backup.close() } }.show()
                }, { error ->
                    if (error is PassphraseBackup.PassphraseRequiredException) promptPassphrase(open)
                    else status.text = getString(R.string.ui_backup_was_not_imported_1_s, error.message ?: "invalid or unreadable file")
                })
            }
        }
    }

    private fun restore(backup: DataImporter.Prepared) {
        restoreStarted = true
        setBusy(true)
        status.text = getString(R.string.ui_saving_recovery_backup_and_restoring_progress)
        worker.execute {
            val result = runCatching { backup.use { DataImporter.restore(applicationContext, it) } }
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                restoreStarted = false
                setBusy(false)
                result.fold({
                    android.widget.Toast.makeText(this, "Progress restored. Tracking is off.", android.widget.Toast.LENGTH_LONG).show()
                    // Rebuild map/repository owners after invalidating their history generation.
                    startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                }, { status.text = getString(R.string.ui_restore_failed_1_s_existing_progress_was_kept_check_tracking_set, it.message) })
            }
        }
    }

    override fun onStart() { super.onStart(); ForegroundSession.app.onStart(this) }
    override fun onStop() { ForegroundSession.app.onStop(isChangingConfigurations); super.onStop() }
    override fun onDestroy() {
        prepared?.close()
        // An explicitly confirmed restore must finish its atomic transaction even if
        // the UI rotates or closes. Unconfirmed validation remains cancellable.
        if (restoreStarted) worker.shutdown() else worker.shutdownNow()
        super.onDestroy()
    }
    private companion object { const val REQUEST_IMPORT = 4001 }
}
