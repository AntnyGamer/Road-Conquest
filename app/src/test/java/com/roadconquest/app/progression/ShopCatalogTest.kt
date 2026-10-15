package com.roadconquest.app.progression

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37], manifest = Config.NONE)
class ShopCatalogTest {
    @Test fun shopHasReachableEarlyItemsAndMeaningfulLateGoals() {
        val items = ShopCatalog.items
        assertEquals(items.size, items.map { it.id }.distinct().size)
        assertTrue(items.all { it.cost >= 0L })
        assertTrue(items.any { it.cost in 250L..500L })
        assertTrue(items.any { it.cost >= 5_000L })
        assertTrue(items.filter { it.cost > 0L }.sumOf { it.cost } >= 25_000L)
        assertEquals(0L, items.first { it.id == "car_style_classic" }.cost)
        assertEquals(0L, items.first { it.id == "road_color_blue" }.cost)
        assertEquals(0L, items.first { it.id == "ui_standard" }.cost)
    }

    @Test fun allCosmeticsReferenceRealStylesAndColors() {
        ShopCatalog.items.forEach { item ->
            val valid = when (item.type) {
                CosmeticType.CAR_STYLE -> CarStyle.entries.any { it.id == item.value }
                CosmeticType.CAR_COLOR -> CarColor.entries.any { it.id == item.value }
                CosmeticType.ROAD_COLOR -> RoadColor.entries.any { it.id == item.value }
                CosmeticType.APP_THEME -> item.value == "standard" || item.value == "gold"
            }
            assertTrue("Invalid cosmetic target for ${item.id}", valid)
        }
    }
}
