package com.roadconquest.app.map

import android.util.AtomicFile
import android.util.Log
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

/**
 * Android 12 AtomicFile.rename uses File.renameTo, whose replacement semantics
 * differ on Windows. Emulate Android's atomic replace for these filesystem tests;
 * keep real startWrite, sync, finishWrite, recovery, gzip and all cache assertions.
 * This shadow is test-only and scoped to the API-31 boundary-cache tests.
 */
@Implements(value = AtomicFile::class, minSdk = 31, maxSdk = 31)
class PosixAtomicFileShadow {
    companion object {
        @JvmStatic
        @Implementation
        fun rename(source: File, target: File) {
            if (target.isDirectory && !target.delete()) {
                Log.e("AtomicFile", "Failed to delete directory at atomic-file destination")
            }
            try {
                Files.move(source.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            } catch (error: IOException) {
                // Android's helper logs a failed rename rather than throwing it.
                Log.e("AtomicFile", "Failed to rename atomic file", error)
            }
        }
    }
}
