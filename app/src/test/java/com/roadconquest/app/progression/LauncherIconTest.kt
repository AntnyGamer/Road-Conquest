package com.roadconquest.app.progression

import android.content.ComponentName
import android.content.pm.PackageManager
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 33, 37])
class LauncherIconTest {
    @Test fun goldAndRegularSwitchKeepExactlyOneLauncherEnabled() {
        val app = RuntimeEnvironment.getApplication()
        val manager = app.packageManager
        val regular = ComponentName(app, app.packageName + ".DefaultLauncher")
        val gold = ComponentName(app, app.packageName + ".GoldLauncher")
        val originalRegular = manager.getComponentEnabledSetting(regular)
        val originalGold = manager.getComponentEnabledSetting(gold)
        val enabled = PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        val default = PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
        fun isEnabled(component: ComponentName, manifestEnabled: Boolean) =
            when (manager.getComponentEnabledSetting(component)) {
                enabled -> true
                default -> manifestEnabled
                else -> false
            }
        try {
            for (goldTheme in listOf(true, true, false, false, true, false)) {
                LauncherIcon.apply(app, goldTheme)
                assertEquals(!goldTheme, isEnabled(regular, true))
                assertEquals(goldTheme, isEnabled(gold, false))
            }
        } finally {
            manager.setComponentEnabledSetting(regular, originalRegular, PackageManager.DONT_KILL_APP)
            manager.setComponentEnabledSetting(gold, originalGold, PackageManager.DONT_KILL_APP)
        }
    }
}
