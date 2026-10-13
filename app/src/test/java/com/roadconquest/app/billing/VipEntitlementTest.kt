package com.roadconquest.app.billing

import com.roadconquest.app.util.Prefs
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], manifest = Config.NONE)
class VipEntitlementTest {
    @Test fun onlyGoldCosmeticsAreIncludedAndLocalDataDeletionDoesNotErasePlayOwnership() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        VipEntitlement.updateFromPlay(context, false)
        try {
            assertFalse(VipEntitlement.isActive(context))
            assertTrue(VipEntitlement.isGoldIncluded("car_color_gold"))
            assertTrue(VipEntitlement.isGoldIncluded("road_color_gold"))
            assertTrue(VipEntitlement.isGoldIncluded("ui_gold"))
            assertFalse(VipEntitlement.isGoldIncluded("car_style_racer"))
            assertFalse(VipEntitlement.isGoldIncluded("road_color_red"))

            VipEntitlement.updateFromPlay(context, true)
            assertTrue(VipEntitlement.isActive(context))
            Prefs.resetCosmetics(context)
            assertTrue("Deleting driving progress must not delete Play purchases",
                VipEntitlement.isActive(context))

            // An authoritative successful Play query can revoke refunded VIP.
            VipEntitlement.updateFromPlay(context, false)
            assertFalse(VipEntitlement.isActive(context))
        } finally {
            VipEntitlement.updateFromPlay(context, false)
        }
    }
}
