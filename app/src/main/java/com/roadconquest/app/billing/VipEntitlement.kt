package com.roadconquest.app.billing

import android.content.Context
import com.roadconquest.app.data.ProgressionRepository
import com.roadconquest.app.progression.LauncherIcon
import com.roadconquest.app.util.Prefs
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
    @Volatile private var ownershipRevision = 0L

    /** Discard Play queries started before another client confirmed a change. */
    internal fun revision(): Long = ownershipRevision

    fun isActive(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getBoolean(VIP_KEY, false)

    fun isGoldIncluded(itemId: String): Boolean =
        itemId == "car_color_gold" || itemId == "road_color_gold" || itemId == "ui_gold"

    fun addListener(listener: () -> Unit) { listeners.add(listener) }
    fun removeListener(listener: () -> Unit) { listeners.remove(listener) }

    @Synchronized internal fun updateFromPlay(context: Context, owned: Boolean) {
        if (isActive(context) == owned) return
        // Update the in-process generation before notifying listeners so a
        // competing older Play query cannot undo an already confirmed change.
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit().putBoolean(VIP_KEY, owned).apply()
        ownershipRevision++
        if (!owned) {
            // An authoritative Play refund/revocation removes VIP-only equipped
            // colors and themes. Never remove a gold item purchased with points.
            val repo = ProgressionRepository(context)
            fun earned(itemId: String) = runCatching { repo.isPurchased(itemId) }.getOrDefault(false)
            if (Prefs.carColor(context) == "gold" && !earned("car_color_gold")) {
                Prefs.setCarColor(context, "blue")
            }
            if (Prefs.roadColor(context) == "gold" && !earned("road_color_gold")) {
                Prefs.setRoadColor(context, "blue")
            }
            if (Prefs.isGoldUiEnabled(context) && !earned("ui_gold")) {
                Prefs.setGoldUiEnabled(context, false)
                LauncherIcon.apply(context, false)
            }
        }
        listeners.forEach { it() }
    }
}
