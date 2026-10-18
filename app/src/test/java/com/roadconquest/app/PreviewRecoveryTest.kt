package com.roadconquest.app

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Intent
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.widget.Button
import android.widget.TextView
import com.roadconquest.app.data.TrackingRepository
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.SQLiteMode
import java.util.concurrent.ExecutorService

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
class PreviewRecoveryTest {
    @Config(sdk = [31, 33, 37], manifest = Config.NONE)
    @Test fun previewRecoversAcrossGpsFallbackAndMasterLocationSwitch() {
        val app = RuntimeEnvironment.getApplication()
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val manager = app.getSystemService(LocationManager::class.java)
        val shadow = Shadows.shadowOf(manager)
        shadow.removeProvider("fused")
        shadow.setLocationEnabled(true)
        shadow.setProviderEnabled("gps", true)
        shadow.setProviderEnabled("network", true)
        // Exercise the real foreground-preview callbacks without constructing MapLibre's
        // native GPU view, which cannot be rendered by the Windows unit-test runtime.
        val activity = Robolectric.buildActivity(MainActivity::class.java).get()
        fun field(name: String) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
        fun invoke(name: String) = MainActivity::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(activity)
        field("locationManager").set(activity, manager)
        field("repository").set(activity, TrackingRepository(app))
        field("statusText").set(activity, TextView(app))
        field("statsText").set(activity, TextView(app))
        field("enableButton").set(activity, Button(app))
        field("resumed").set(activity, true)
        val listener = field("previewLocationListener").get(activity) as LocationListener
        val receiver = field("locationReceiver").get(activity) as BroadcastReceiver
        try {
            invoke("startPreviewLocation")
            assertTrue(listener in shadow.getLocationUpdateListeners("gps"))
            assertTrue(field("previewLocationRegistered").getBoolean(activity))
            assertEquals(0f, shadow.getLegacyLocationRequests("gps").single().minUpdateDistanceMeters, 0f)
            shadow.setProviderEnabled("gps", false)
            listener.onProviderDisabled("gps")
            assertTrue(listener in shadow.getLocationUpdateListeners("network"))
            shadow.setProviderEnabled("gps", true)
            receiver.onReceive(activity, Intent(LocationManager.PROVIDERS_CHANGED_ACTION))
            assertTrue(listener in shadow.getLocationUpdateListeners("gps"))
            shadow.setLocationEnabled(false)
            receiver.onReceive(activity, Intent(LocationManager.MODE_CHANGED_ACTION))
            assertEquals("Location off", (field("statusText").get(activity) as TextView).text.toString())
            assertTrue(listener in shadow.getLocationUpdateListeners("gps"))
            shadow.setLocationEnabled(true)
            receiver.onReceive(activity, Intent(LocationManager.MODE_CHANGED_ACTION))
            assertTrue(listener in shadow.getLocationUpdateListeners("gps"))
        } finally {
            field("resumed").set(activity, false)
            invoke("stopPreviewLocation")
            assertFalse(field("previewLocationRegistered").getBoolean(activity))
            assertFalse(listener in shadow.getLocationUpdateListeners("gps"))
            assertFalse(listener in shadow.getLocationUpdateListeners("network"))
            // A second stop is the common tracking-broadcast case after preview is already off.
            // It must stay a no-op rather than re-registering or leaving stale listener state.
            invoke("stopPreviewLocation")
            assertFalse(field("previewLocationRegistered").getBoolean(activity))
            (field("summaryExecutor").get(activity) as ExecutorService).shutdownNow()
            (field("statsHandler").get(activity) as Handler).removeCallbacksAndMessages(null)
        }
    }
}
