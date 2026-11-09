package com.roadconquest.app.ads

import com.roadconquest.app.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/** Check local ad SDK linkage without sending any ad requests or collecting consent. */
class AdMobConfigurationTest {
    @Test fun debugBuildUsesOnlyGoogleSampleAdUnits() {
        assertEquals("ca-app-pub-3940256099942544/9214589741", BuildConfig.ADMOB_BANNER_ID)
        assertEquals("ca-app-pub-3940256099942544/5224354917", BuildConfig.ADMOB_REWARDED_ID)
    }

    @Test fun mobileAdsAndUserMessagingSdkAreBothPackaged() {
        // loadClass does not initialize Android services or request ads during a JVM test.
        val loader = javaClass.classLoader
        assertNotNull(loader.loadClass("com.google.android.gms.ads.MobileAds"))
        assertNotNull(loader.loadClass("com.google.android.gms.ads.rewarded.RewardedAd"))
        assertNotNull(loader.loadClass("com.google.android.ump.UserMessagingPlatform"))
        assertNotNull(loader.loadClass("com.google.android.ump.ConsentInformation"))
    }
}
