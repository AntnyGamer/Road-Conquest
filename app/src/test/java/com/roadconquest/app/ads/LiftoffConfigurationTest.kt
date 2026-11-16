package com.roadconquest.app.ads

import com.roadconquest.app.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** No SDK initialization or ad requests in local unit tests. */
class LiftoffConfigurationTest {
    @Test fun requiresAllThreePlacementIdentifiers() {
        assertFalse(hasAdConfiguration("", "", ""))
        assertFalse(hasAdConfiguration("app", "", "rewarded"))
        assertFalse(hasAdConfiguration("app", "banner", ""))
        assertTrue(hasAdConfiguration("app", "banner", "rewarded"))
    }

    @Test fun rewardIsPositiveAndLinkageUsesDirectSdk() {
        assertTrue(hasAdConfiguration(BuildConfig.LIFTOFF_APP_ID,
            BuildConfig.LIFTOFF_BANNER_ID, BuildConfig.LIFTOFF_REWARDED_ID))
        assertTrue(BuildConfig.LIFTOFF_REWARD_POINTS in 1..1000)
        val loader = javaClass.classLoader
        assertNotNull(loader.loadClass("com.vungle.ads.VungleAds"))
        assertNotNull(loader.loadClass("com.vungle.ads.RewardedAd"))
        assertNotNull(loader.loadClass("com.vungle.ads.VungleBannerView"))
        assertNotNull(loader.loadClass("com.vungle.ads.VunglePrivacySettings"))
        assertEquals(hasAdConfiguration(BuildConfig.LIFTOFF_APP_ID,
            BuildConfig.LIFTOFF_BANNER_ID, BuildConfig.LIFTOFF_REWARDED_ID),
            LiftoffConfig.isConfigured())
    }
}
