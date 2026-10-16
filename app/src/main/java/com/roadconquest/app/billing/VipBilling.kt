package com.roadconquest.app.billing

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams

/**
 * Activity-scoped Play Billing connection: restores a non-consumable VIP product,
 * checks completed vs pending purchases, and acknowledges purchases promptly.
 * Purchase details and price are taken from the store, never assumed from UI text.
 */
class VipBilling(
    context: Context,
    private val onUpdate: (Offer?, Boolean, String?) -> Unit
) {
    data class Offer(val price: String)

    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private var closed = false
    private var connecting = false
    private var ownershipChecked = false
    private var offer: Offer? = null
    private var message: String? = null
    private val waiters = mutableListOf<() -> Unit>()

    private val client: BillingClient = BillingClient.newBuilder(appContext)
        .setListener { result, purchases ->
            if (closed) return@setListener
            when (result.responseCode) {
                BillingClient.BillingResponseCode.OK -> {
                    purchases.orEmpty()
                        .filter { VipEntitlement.PRODUCT_ID in it.products &&
                            it.purchaseState == Purchase.PurchaseState.PURCHASED }
                        .forEach {
                            // A granted Gold UI can immediately recreate ShopActivity,
                            // which closes this activity's BillingClient. Start Play
                            // acknowledgement before notifying entitlement listeners.
                            acknowledge(it)
                            VipEntitlement.updateFromPlay(appContext, true)
                        }
                    if (purchases.orEmpty().any {
                        VipEntitlement.PRODUCT_ID in it.products &&
                            it.purchaseState == Purchase.PurchaseState.PENDING
                    }) message = "VIP payment pending. Benefits unlock after payment completes."
                    queryOwnership()
                }
                BillingClient.BillingResponseCode.USER_CANCELED -> {
                    message = null
                    notifyStatus()
                }
                BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> {
                    // The Play account may have bought VIP on another device
                    // before this screen refreshed its local entitlement.
                    queryOwnership()
                }
                else -> {
                    message = "Google Play could not complete this purchase."
                    notifyStatus()
                }
            }
        }
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder().enableOneTimeProducts().build()
        )
        .enableAutoServiceReconnection()
        .build()

    fun refresh(includeOffer: Boolean = true) {
        ownershipChecked = false
        notifyStatus()
        connect {
            queryOwnership()
            // Ad-supported screens only need ownership; fetching localized
            // purchase offers on every navigation has no purpose there.
            if (includeOffer) queryOffer()
        }
    }

    fun purchase(activity: Activity) {
        if (closed) return
        if (VipEntitlement.isActive(appContext)) {
            message = "VIP is already owned."
            notifyStatus()
            return
        }
        connect {
            // Never launch a flow using stale cached ProductDetails.
            val product = QueryProductDetailsParams.Product.newBuilder()
                .setProductId(VipEntitlement.PRODUCT_ID)
                .setProductType(BillingClient.ProductType.INAPP).build()
            client.queryProductDetailsAsync(
                QueryProductDetailsParams.newBuilder().setProductList(listOf(product)).build()
            ) { result, response ->
                if (closed || activity.isDestroyed || activity.isFinishing) return@queryProductDetailsAsync
                val details = if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    response.productDetailsList.firstOrNull { it.productId == VipEntitlement.PRODUCT_ID }
                } else null
                val buy = details?.let(::permanentBuyOffer)
                val offerToken = buy?.offerToken
                if (details == null || offerToken.isNullOrBlank()) {
                    message = "VIP is not available yet. Activate vip_lifetime in Google Play Console."
                    notifyStatus()
                    return@queryProductDetailsAsync
                }
                val params = BillingFlowParams.ProductDetailsParams.newBuilder()
                    .setProductDetails(details).setOfferToken(offerToken).build()
                // The purchase UI must always be launched from Android's main thread,
                // even if a Play Store implementation delivers detail callbacks elsewhere.
                handler.post {
                    if (!closed && !activity.isDestroyed && !activity.isFinishing) {
                        val launch = client.launchBillingFlow(
                            activity, BillingFlowParams.newBuilder()
                                .setProductDetailsParamsList(listOf(params)).build()
                        )
                        when (launch.responseCode) {
                            BillingClient.BillingResponseCode.OK -> Unit
                            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED ->
                                queryOwnership()
                            else -> {
                                message = "Google Play could not open the purchase screen."
                                notifyStatus()
                            }
                        }
                    }
                }
            }
        }
    }

    private fun connect(ready: () -> Unit) {
        if (closed) return
        if (client.isReady) { ready(); return }
        waiters.add(ready)
        if (connecting) return
        connecting = true
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingServiceDisconnected() {
                if (closed) return
                connecting = false
                // A setup attempt can disconnect without delivering a successful
                // setup callback. Do not retain stale activity callbacks or leave
                // non-VIP ad screens blocked indefinitely waiting for Play.
                waiters.clear()
                ownershipChecked = true
                message = "Google Play connection interrupted. Reopen this screen to retry."
                notifyStatus()
            }

            override fun onBillingSetupFinished(result: BillingResult) {
                if (closed) return
                connecting = false
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    val callbacks = waiters.toList()
                    waiters.clear()
                    callbacks.forEach { it() }
                } else {
                    waiters.clear()
                    ownershipChecked = true
                    message = "Google Play purchases are unavailable on this device."
                    notifyStatus()
                }
            }
        })
    }

    private fun queryOwnership() {
        if (closed || !client.isReady) return
        val revisionAtRequest = VipEntitlement.beginOwnershipQuery()
        client.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.INAPP).build()
        ) { result, purchases ->
            if (closed) return@queryPurchasesAsync
            ownershipChecked = true
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                val vipPurchases = purchases.filter { VipEntitlement.PRODUCT_ID in it.products }
                val owned = vipPurchases.any { it.purchaseState == Purchase.PurchaseState.PURCHASED }
                // A purchase callback or another screen may have confirmed a
                // newer state while this async query was in flight. In that
                // case don't let this potentially stale result revoke/grant VIP.
                // Start pending acknowledgements before a new entitlement causes
                // the current screen to recreate and close its Billing connection.
                vipPurchases.filter { it.purchaseState == Purchase.PurchaseState.PURCHASED }
                    .forEach(::acknowledge)
                VipEntitlement.updateIfCurrent(appContext, owned, revisionAtRequest)
                message = if (vipPurchases.any { it.purchaseState == Purchase.PurchaseState.PENDING } &&
                    !owned) "VIP payment pending. Benefits unlock after payment completes." else null
            } else {
                // Network/Play outage is not proof of a refund. Keep the last
                // confirmed entitlement until a *successful* purchase query.
                message = "Google Play could not check past purchases."
            }
            notifyStatus()
        }
    }

    /**
     * Billing 9 can list rentals, preorders and permanent offers together.
     * Older Play configurations may only expose the backwards-compatible
     * single-offer field. Both the displayed price and purchase must use the
     * same eligible, non-rental, non-preorder option.
     */
    private fun permanentBuyOffer(details: ProductDetails): ProductDetails.OneTimePurchaseOfferDetails? {
        fun permanent(offer: ProductDetails.OneTimePurchaseOfferDetails) =
            offer.rentalDetails == null && offer.preorderDetails == null &&
                !offer.offerToken.isNullOrBlank()
        val eligibleOffers = details.oneTimePurchaseOfferDetailsList
        // An available list is authoritative, even when it contains only
        // rentals or preorders. Fall back only for older single-offer Play
        // configurations that do not return a list at all.
        return if (eligibleOffers != null) eligibleOffers.firstOrNull(::permanent)
        else details.oneTimePurchaseOfferDetails?.takeIf(::permanent)
    }

    private fun queryOffer() {
        if (closed || !client.isReady) return
        val product = QueryProductDetailsParams.Product.newBuilder()
            .setProductId(VipEntitlement.PRODUCT_ID)
            .setProductType(BillingClient.ProductType.INAPP).build()
        client.queryProductDetailsAsync(
            QueryProductDetailsParams.newBuilder().setProductList(listOf(product)).build()
        ) { result, response ->
            if (closed) return@queryProductDetailsAsync
            offer = if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                response.productDetailsList.firstOrNull { it.productId == VipEntitlement.PRODUCT_ID }
                    ?.let(::permanentBuyOffer)?.let { Offer(it.formattedPrice) }
            } else null
            notifyStatus()
        }
    }

    private fun acknowledge(purchase: Purchase) {
        if (purchase.isAcknowledged) return
        client.acknowledgePurchase(
            AcknowledgePurchaseParams.newBuilder()
                .setPurchaseToken(purchase.purchaseToken).build()
        ) { _ ->
            // On transient failure, queryOwnership() retries on the next resume.
            // The purchased entitlement is never gated on a network ACK.
        }
    }

    private fun notifyStatus() {
        val currentOffer = offer
        val checked = ownershipChecked
        val status = message
        handler.post { if (!closed) onUpdate(currentOffer, checked, status) }
    }

    fun close() {
        closed = true
        waiters.clear()
        handler.removeCallbacksAndMessages(null)
        client.endConnection()
    }
}
