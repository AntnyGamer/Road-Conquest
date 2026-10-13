package com.roadconquest.app.billing

import android.content.Context
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Cached entitlement for offline app launches. Only VipBilling writes this value,
 * using completed purchases reported by Google Play. A successful Play purchase
 * query also revokes the cache when a purchase was refunded or cancelled.
 *
 * This is tied to the owning Google Play account, not the optional Road Conquest
 * leaderboard account. Deleting local driving history does not delete purchases.
 */
object VipEntitlement {
    const val PRODUCT_ID = "vip_lifetime"
    private const val PREFERENCES = "roadconquest_play_entitlements"
    private const val VIP_KEY = "vip_lifetime_owned"
    private val listeners = CopyOnWriteArraySet<() -> Unit>()

    fun isActive(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getBoolean(VIP_KEY, false)

    fun isGoldIncluded(itemId: String): Boolean =
        itemId == "car_color_gold" || itemId == "road_color_gold" || itemId == "ui_gold"

    fun addListener(listener: () -> Unit) { listeners.add(listener) }
    fun removeListener(listener: () -> Unit) { listeners.remove(listener) }

    internal fun updateFromPlay(context: Context, owned: Boolean) {
        if (isActive(context) == owned) return
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit().putBoolean(VIP_KEY, owned).apply()
        listeners.forEach { it() }
    }
}
