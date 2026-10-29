package com.roadconquest.app

import org.junit.Assert.assertEquals
import org.junit.Test

class ExportFailureMessageTest {
    @Test fun cleanupFailureDoesNotHideOriginalExportError() {
        val cause = IllegalStateException("Protected backups are unavailable. Try again when the backup service is ready.")
        assertEquals(
            "Protected backups are unavailable. Try again when the backup service is ready.\n\n" +
                "An incomplete backup may remain. Delete it manually in Files.",
            exportFailureDetails(cause, cleanupFailed = true)
        )
    }

    @Test fun originalFailureStillShowsWhenCleanupSucceeds() {
        assertEquals("App/device verification failed.",
            exportFailureDetails(IllegalStateException("App/device verification failed."), cleanupFailed = false))
    }

    @Test fun missingFailureMessageHasUsefulFallback() {
        assertEquals("Unknown error (IllegalStateException)",
            exportFailureDetails(IllegalStateException(), cleanupFailed = false))
    }
}
