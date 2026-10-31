package com.roadconquest.app.export

import android.content.Context
import com.roadconquest.app.data.TrackingRepository
import com.roadconquest.app.util.Prefs
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipFile

/**
 * Human-readable, password-free archive. It is not authenticated, so it is
 * deliberately export-only and cannot be used to restore Road Conquest data.
 */
internal object ExportOnlyBackup {
    private const val MARKER = "RoadConquestExportOnly/v1"

    fun write(context: Context, repository: TrackingRepository, output: OutputStream) {
        DataExporter.writeZip(context, repository, output, zipComment = MARKER)
        check(repository.isHistoryCurrent() && !Prefs.isDeviceDataDeletionPending(context)) {
            "Device progress changed while exporting. Export again."
        }
    }

    fun isExportOnly(file: File): Boolean = runCatching {
        ZipFile(file).use { it.comment == MARKER }
    }.getOrDefault(false)
}
