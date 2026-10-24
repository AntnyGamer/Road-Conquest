package com.roadconquest.app.forum

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams

/** Store prices/ownership are discovery only. The account server verifies and acknowledges grants. */
class ForumBilling(context: Context, private val status: (String?, String?) -> Unit,
    private val purchased: (String) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private var closed = false
    private var connecting = false
    private val waiting = mutableListOf<() -> Unit>()
    private val client: BillingClient = BillingClient.newBuilder(context.applicationContext)
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .enableAutoServiceReconnection()
        .setListener { result, purchases ->
            main.post { if (!closed) when (result.responseCode) {
                BillingClient.BillingResponseCode.OK -> accept(purchases.orEmpty())
                BillingClient.BillingResponseCode.USER_CANCELED -> Unit
                BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> restore()
                else -> update(null,"Google Play could not complete the purchase.")
            } }
        }.build()

    private fun update(price: String?, message: String?) {
        main.post { if (!closed) status(price,message) }
    }
    private fun accept(purchases: List<Purchase>) {
        purchases.filter { ForumClient.PRODUCT_ID in it.products }.forEach {
            if (it.purchaseState == Purchase.PurchaseState.PURCHASED) {
                val token = it.purchaseToken
                main.post { if (!closed) purchased(token) }
            } else if (it.purchaseState == Purchase.PurchaseState.PENDING) {
                update(null,"Payment is pending. Extras unlock after Google Play confirms it.")
            }
        }
    }
    private fun connect(action: () -> Unit) {
        if (closed) return
        if (client.isReady) { action(); return }
        waiting.add(action)
        if (connecting) return
        connecting = true
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingServiceDisconnected() {
                main.post {
                connecting = false
                waiting.clear()
                update(null,"Google Play connection interrupted. Try again.")
                }
            }
            override fun onBillingSetupFinished(result: BillingResult) {
                main.post {
                if (closed) return@post
                connecting = false
                val actions = waiting.toList()
                waiting.clear()
                if (result.responseCode == BillingClient.BillingResponseCode.OK) actions.forEach { it() }
                else update(null,"Google Play purchases are unavailable right now.")
                }
            }
        })
    }
    private fun query() = QueryProductDetailsParams.newBuilder().setProductList(listOf(
        QueryProductDetailsParams.Product.newBuilder().setProductId(ForumClient.PRODUCT_ID)
            .setProductType(BillingClient.ProductType.INAPP).build())).build()

    private fun offer(details: ProductDetails): ProductDetails.OneTimePurchaseOfferDetails? {
        fun eligible(offer: ProductDetails.OneTimePurchaseOfferDetails) =
            offer.rentalDetails == null && offer.preorderDetails == null && !offer.offerToken.isNullOrBlank()
        return details.oneTimePurchaseOfferDetailsList?.firstOrNull(::eligible)
            ?: if (details.oneTimePurchaseOfferDetailsList == null) details.oneTimePurchaseOfferDetails?.takeIf(::eligible) else null
    }
    fun price() = connect {
        client.queryProductDetailsAsync(query()) { result, response ->
            if (!closed) {
                val price = if (result.responseCode == BillingClient.BillingResponseCode.OK)
                    response.productDetailsList.firstOrNull { it.productId == ForumClient.PRODUCT_ID }?.let(::offer)?.formattedPrice else null
                update(price,if (price == null) "Forum Plus is currently unavailable." else null)
            }
        }
    }
    fun restore(silent: Boolean = false): Unit = connect {
        client.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build()) { result, purchases ->
            if (!closed && result.responseCode == BillingClient.BillingResponseCode.OK) {
                if (purchases.none { ForumClient.PRODUCT_ID in it.products }) {
                    if (!silent) update(null,"No Forum Plus purchase was found for this Google Play account.")
                }
                else accept(purchases)
            } else if (!closed) update(null,"Google Play could not restore purchases right now.")
        }
    }
    fun buy(activity: Activity, accountBinding: String) = connect {
        // Query again at checkout: do not launch a stale offer or trust a hard-coded price.
        client.queryProductDetailsAsync(query()) { result, response ->
            val details = if (result.responseCode == BillingClient.BillingResponseCode.OK)
                response.productDetailsList.firstOrNull { it.productId == ForumClient.PRODUCT_ID } else null
            val option = details?.let(::offer)
            if (details == null || option == null) { update(null,"Forum Plus is currently unavailable."); return@queryProductDetailsAsync }
            main.post {
                if (!closed && !activity.isDestroyed && !activity.isFinishing) {
                    val product = BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(details)
                        .setOfferToken(requireNotNull(option.offerToken)).build()
                    val launched = client.launchBillingFlow(activity,BillingFlowParams.newBuilder()
                        .setObfuscatedAccountId(accountBinding).setProductDetailsParamsList(listOf(product)).build())
                    if (launched.responseCode == BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED) restore()
                    else if (launched.responseCode != BillingClient.BillingResponseCode.OK) update(null,"Google Play could not open checkout.")
                }
            }
        }
    }
    fun close() { closed = true; waiting.clear(); main.removeCallbacksAndMessages(null); client.endConnection() }
}
