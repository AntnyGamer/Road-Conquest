package com.roadconquest.app.billing

import com.roadconquest.app.progression.Cosmetics
import com.roadconquest.app.progression.ShopCatalog
import com.roadconquest.app.util.Prefs
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
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

            for (id in listOf("car_color_gold", "road_color_gold", "ui_gold")) {
                val item = ShopCatalog.items.single { it.id == id }
                assertTrue("VIP can equip $id without spending points", Cosmetics.equip(context, item))
            }
            assertEquals("gold", Prefs.carColor(context))
            assertEquals("gold", Prefs.roadColor(context))
            assertTrue(Prefs.isGoldUiEnabled(context))

            // A successful Play refund query revokes only gold items not purchased
            // with local points; the rest of the local progression remains intact.
            VipEntitlement.updateFromPlay(context, false)
            assertFalse(VipEntitlement.isActive(context))
            assertEquals("blue", Prefs.carColor(context))
            assertEquals("blue", Prefs.roadColor(context))
            assertFalse(Prefs.isGoldUiEnabled(context))
        } finally {
            VipEntitlement.updateFromPlay(context, false)
        }
    }
    @Test fun refundDoesNotRemoveGoldItemsAlreadyBoughtWithPoints() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val repo = com.roadconquest.app.data.ProgressionRepository(context)
        repo.clearProgression()
        Prefs.resetCosmetics(context)
        VipEntitlement.updateFromPlay(context, false)
        try {
            for (id in listOf("car_color_gold", "road_color_gold", "ui_gold")) {
                assertEquals(
                    com.roadconquest.app.data.PurchaseResult.PURCHASED,
                    repo.purchase(id, 0L)
                )
            }
            VipEntitlement.updateFromPlay(context, true)
            for (id in listOf("car_color_gold", "road_color_gold", "ui_gold")) {
                assertTrue(Cosmetics.equip(context, ShopCatalog.items.single { it.id == id }))
            }
            VipEntitlement.updateFromPlay(context, false)
            assertEquals("gold", Prefs.carColor(context))
            assertEquals("gold", Prefs.roadColor(context))
            assertTrue(Prefs.isGoldUiEnabled(context))
        } finally {
            Prefs.resetCosmetics(context)
            VipEntitlement.updateFromPlay(context, false)
            repo.clearProgression()
        }
    }


}
