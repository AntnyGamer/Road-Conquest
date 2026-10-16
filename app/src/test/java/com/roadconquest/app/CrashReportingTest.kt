package com.roadconquest.app

import com.google.firebase.FirebaseApp
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.roadconquest.app.diagnostics.CrashReporting
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class CrashReportingTest {
    @Test fun realClientConfigurationAndConsentReachTheSdkAndCanBeRevoked() {
        val context = RuntimeEnvironment.getApplication()
        CrashReporting.initialize(context)
        assertFalse(CrashReporting.enabled(context))
        assertTrue(FirebaseApp.getApps(context).isEmpty())
        try {
            CrashReporting.setEnabled(context, true)
            assertEquals("roadconquest-9f0eb", FirebaseApp.getInstance().options.projectId)
            assertTrue(FirebaseCrashlytics.getInstance().isCrashlyticsCollectionEnabled)
        } finally { CrashReporting.clear(context) }
        assertFalse(CrashReporting.enabled(context))
        assertFalse(FirebaseCrashlytics.getInstance().isCrashlyticsCollectionEnabled)
    }
}
