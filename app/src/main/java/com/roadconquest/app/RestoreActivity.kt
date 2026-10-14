package com.roadconquest.app

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.roadconquest.app.export.DataImporter
import com.roadconquest.app.util.Appearance
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

    override fun attachBaseContext(newBase: Context) { super.attachBaseContext(Appearance.wrap(newBase)) }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(Appearance.themeRes(this))
        super.onCreate(savedInstanceState)
        WindowCompat.enableEdgeToEdge(window)
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
            text = "YOUR DATA"
            textSize = 12f
            letterSpacing = 0.14f
            setTextColor(Appearance.color(this@RestoreActivity, R.attr.rcAccent))
        })
        root.addView(TextView(this).apply {
            text = "Restore your progress"
            textSize = 28f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Appearance.color(this@RestoreActivity, R.attr.rcTextPrimary))
            setPadding(0, (8 * dp).toInt(), 0, (12 * dp).toInt())
        })
        root.addView(TextView(this).apply {
            text = "Choose a Road Conquest export ZIP. Roads, mileage, fog, visit counts, discoveries, points and garage purchases are restored together.\n\nThe backup is checked before you confirm replacement. Your current progress is saved on this phone for recovery. Tracking and verified sharing stay off afterward.\n\nSign in to your account and restore VIP through Google Play separately. Imported history does not earn verified leaderboard credit."
            textSize = 15f
            setTextColor(Appearance.color(this@RestoreActivity, R.attr.rcTextSecondary))
            setBackgroundResource(R.drawable.settings_card_background)
            val inset = (16 * dp).toInt()
            setPadding(inset, inset, inset, inset)
        })
        choose = Button(this).apply {
            text = "Choose backup (.zip)"
            setOnClickListener {
                try {
                    startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                        putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
                    }, REQUEST_IMPORT)
                } catch (_: ActivityNotFoundException) { status.text = "Android's file picker is unavailable." }
            }
            root.addView(this)
        }
        recovery = Button(this, null, 0, R.style.RoadConquest_SettingsAction).apply {
            text = "Restore progress from before last import"
            isEnabled = DataImporter.previousBackup(this@RestoreActivity).isFile
            setOnClickListener { validate { DataImporter.previousBackup(applicationContext).inputStream() } }
            root.addView(this)
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

    private fun validate(open: () -> java.io.InputStream) {
        if (busy) return
        setBusy(true)
        status.text = "Checking backup…"
        worker.execute {
            val result = runCatching { open().use { DataImporter.prepare(applicationContext, it) } }
            runOnUiThread {
                if (isDestroyed || isFinishing) { result.getOrNull()?.close(); return@runOnUiThread }
                setBusy(false)
                result.fold({ backup ->
                    prepared?.close()
                    prepared = backup
                    status.text = "Backup validated."
                    val summary = backup.summary
                    val message = String.format(Locale.getDefault(),
                        "Backup: %d roads • %.2f mi • %d GPS fixes\nPoints: %d\nExported: %s\n\nReplace this phone's current progress? A recovery backup is saved before replacement. Tracking and verified sharing will be turned off.",
                        summary.roadsUnlockedCount, summary.distanceMeters / 1609.344, summary.trackPointCount,
                        backup.metadata.getLong("points_balance"), backup.metadata.getString("exported_at"))
                    AlertDialog.Builder(this).setTitle("Replace device progress?").setMessage(message)
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Restore") { _, _ ->
                            prepared = null
                            restore(backup)
                        }
                        .setOnDismissListener { if (prepared === backup) { prepared = null; backup.close() } }.show()
                }, { status.text = "Backup was not imported: ${it.message ?: "invalid or unreadable file"}" })
            }
        }
    }

    private fun restore(backup: DataImporter.Prepared) {
        setBusy(true)
        status.text = "Saving recovery backup and restoring progress…"
        worker.execute {
            val result = runCatching { backup.use { DataImporter.restore(applicationContext, it) } }
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                setBusy(false)
                result.fold({
                    android.widget.Toast.makeText(this, "Progress restored. Tracking is off.", android.widget.Toast.LENGTH_LONG).show()
                    // Rebuild map/repository owners after invalidating their history generation.
                    startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                }, { status.text = "Restore failed: ${it.message}. Existing progress was kept; tracking is off." })
            }
        }
    }

    override fun onStart() { super.onStart(); ForegroundSession.app.onStart() }
    override fun onStop() { ForegroundSession.app.onStop(isChangingConfigurations); super.onStop() }
    override fun onDestroy() { prepared?.close(); worker.shutdownNow(); super.onDestroy() }
    private companion object { const val REQUEST_IMPORT = 4001 }
}
