package com.roadconquest.app

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.roadconquest.app.billing.VipBilling
import com.roadconquest.app.billing.VipEntitlement
import com.roadconquest.app.data.ProgressionRepository
import com.roadconquest.app.data.ProgressionSnapshot
import com.roadconquest.app.data.PurchaseResult
import com.roadconquest.app.data.TrackingRepository
import com.roadconquest.app.progression.CosmeticType
import com.roadconquest.app.progression.Cosmetics
import com.roadconquest.app.progression.CarStyle
import com.roadconquest.app.progression.CarColor
import com.roadconquest.app.progression.RoadColor
import com.roadconquest.app.progression.ProgressionManager
import com.roadconquest.app.progression.ShopCatalog
import com.roadconquest.app.progression.ShopItem
import com.roadconquest.app.util.Appearance
import com.roadconquest.app.util.Prefs
import com.roadconquest.app.util.ForegroundSession
import com.roadconquest.app.ads.ScreenAds
import java.util.Locale
import java.util.concurrent.Executors

class ShopActivity : Activity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var pointsText: TextView
    private lateinit var progressText: TextView
    private lateinit var items: LinearLayout
    private lateinit var ads: ScreenAds
    private lateinit var vipBilling: VipBilling
    private var vipOffer: VipBilling.Offer? = null
    private var vipChecked = false
    private var vipMessage: String? = null
    private var lastSnapshot: ProgressionSnapshot? = null
    private var appliedGoldUi = false
    private var vipThemeRecreationRequested = false
    /** Avoid duplicate recreates when Shop and banner Billing clients report the same refund. */
    private fun refreshVipThemeIfNeeded(): Boolean {
        if (vipThemeRecreationRequested) return true
        if (appliedGoldUi != Prefs.isGoldUiEnabled(this)) {
            vipThemeRecreationRequested = true
            recreate()
            return true
        }
        return false
    }
    private val vipEntitlementListener: () -> Unit = {
        runOnUiThread {
            if (!isDestroyed && !isFinishing) {
                if (!refreshVipThemeIfNeeded()) lastSnapshot?.let(::render)
            }
        }
    }
    @Volatile private var generation = 0

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(Appearance.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        appliedGoldUi = Prefs.isGoldUiEnabled(this)
        setTheme(Appearance.themeRes(this))
        super.onCreate(savedInstanceState)
        WindowCompat.enableEdgeToEdge(window)
        val lightSystemBars = !Appearance.isDark(this)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = lightSystemBars
            isAppearanceLightNavigationBars = lightSystemBars
        }
        setContentView(R.layout.activity_shop)
        applyInsets()
        pointsText = findViewById(R.id.shopPointsText)
        progressText = findViewById(R.id.shopProgressText)
        items = findViewById(R.id.shopItems)
        VipEntitlement.addListener(vipEntitlementListener)
        vipBilling = VipBilling(this) { offer, checked, status ->
            vipOffer = offer
            vipChecked = checked
            vipMessage = status
            // A refund can revoke a VIP-only Golden UI while Garage is open.
            // Rebuild the theme immediately instead of showing stale gold
            // surfaces until the user leaves and reopens the screen.
            if (!refreshVipThemeIfNeeded()) lastSnapshot?.let(::render)
        }
        ads = ScreenAds(this, findViewById(R.id.shopRoot), findViewById(R.id.shopRewardButton), ::refresh)
    }

    override fun onStart() {
        super.onStart()
        ForegroundSession.app.onStart()
    }

    override fun onResume() {
        super.onResume()
        if (refreshVipThemeIfNeeded()) return
        if (::items.isInitialized) refresh()
        if (::vipBilling.isInitialized) vipBilling.refresh()
        if (::ads.isInitialized) ads.onResume()
    }

    private fun refresh() {
        val current = ++generation
        executor.execute {
            val result = runCatching {
                val summary = TrackingRepository(this).getSummary()
                ProgressionManager.sync(this, summary)
            }
            runOnUiThread {
                if (isDestroyed || current != generation) return@runOnUiThread
                result.fold(
                    onSuccess = ::render,
                    onFailure = {
                        pointsText.text = "Points unavailable"
                        progressText.text = "Could not load local progression."
                        items.removeAllViews()
                    }
                )
            }
        }
    }

    private fun render(snapshot: ProgressionSnapshot) {
        lastSnapshot = snapshot
        pointsText.text = String.format(Locale.getDefault(), "⚔ %,d points", snapshot.balance)
        progressText.text = String.format(
            Locale.getDefault(),
            "%,d roads • %,d tiles • %,d towns • %,d regions • %,d countries\n%,d lifetime points earned • %,d spent",
            snapshot.rewardedRoads,
            snapshot.tilesUnlocked,
            snapshot.towns,
            snapshot.states,
            snapshot.countries,
            snapshot.lifetimeEarned,
            snapshot.pointsSpent
        )
        items.removeAllViews()
        addVipCard()
        var previousType: CosmeticType? = null
        for (item in ShopCatalog.items) {
            if (item.type != previousType) {
                previousType = item.type
                addSectionTitle(sectionName(item.type))
            }
            addShopItem(item, snapshot)
        }
    }

    private fun addVipCard() {
        val vip = VipEntitlement.isActive(this)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.panel_background)
            elevation = dp(2).toFloat()
            val padding = dp(16)
            setPadding(padding, padding, padding, padding)
        }
        card.addView(TextView(this).apply {
            text = "VIP • Lifetime"
            textSize = 20f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Appearance.color(this@ShopActivity, R.attr.rcTextPrimary))
        })
        card.addView(TextView(this).apply {
            text = "One-time Google Play purchase. Remove banners and rewarded-ad offers, and unlock Gold car, Gold roads, and Golden Road Conquest UI without spending points. You can still choose other styles."
            textSize = 13f
            setTextColor(Appearance.color(this@ShopActivity, R.attr.rcTextSecondary))
            setPadding(0, dp(8), 0, dp(8))
        })
        val label = when {
            vip -> "VIP owned • Ad-free"
            vipOffer != null -> "Get VIP • ${vipOffer!!.price} once"
            !vipChecked -> "Checking Google Play…"
            else -> "VIP • $2.99 (US price, not available yet)"
        }
        card.addView(Button(this).apply {
            text = label
            isEnabled = !vip && vipChecked && vipOffer != null
            setOnClickListener { vipBilling.purchase(this@ShopActivity) }
        })
        if (!vip && (vipMessage != null || vipOffer == null)) {
            card.addView(TextView(this).apply {
                text = vipMessage ?: if (vipChecked) {
                    "Purchase unavailable until vip_lifetime is activated in Google Play Console."
                } else "Connecting to Google Play…"
                textSize = 12f
                setTextColor(Appearance.color(this@ShopActivity, R.attr.rcTextSecondary))
            })
        }
        items.addView(card, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(12) })
    }

    private fun addSectionTitle(title: String) {
        items.addView(TextView(this).apply {
            text = title
            setTextColor(Appearance.color(this@ShopActivity, R.attr.rcTextPrimary))
            textSize = 20f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(16), 0, dp(8))
        })
    }

    private fun addShopItem(item: ShopItem, snapshot: ProgressionSnapshot) {
        val owned = item.cost == 0L || item.id in snapshot.purchasedItems ||
            (VipEntitlement.isActive(this) && VipEntitlement.isGoldIncluded(item.id))
        val equipped = Cosmetics.isEquipped(this, item)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.panel_background)
            elevation = dp(2).toFloat()
            val padding = dp(16)
            setPadding(padding, padding, padding, padding)
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(previewFor(item), LinearLayout.LayoutParams(dp(60), dp(54)).apply {
            marginEnd = dp(12)
        })
        val labels = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        labels.addView(TextView(this).apply {
            text = item.name
            setTextColor(Appearance.color(this@ShopActivity, R.attr.rcTextPrimary))
            textSize = 17f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        labels.addView(TextView(this).apply {
            text = item.description
            setTextColor(Appearance.color(this@ShopActivity, R.attr.rcTextSecondary))
            textSize = 13f
            setPadding(0, dp(4), 0, 0)
        })
        header.addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(header, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        if (!owned && snapshot.balance < item.cost) {
            card.addView(TextView(this).apply {
                text = String.format(Locale.getDefault(), "%,d more points to unlock", item.cost - snapshot.balance)
                textSize = 12f
                setTextColor(Appearance.color(this@ShopActivity, R.attr.rcTextSecondary))
                setPadding(0, 0, 0, dp(8))
            })
        }
        card.addView(Button(this).apply {
            text = when {
                equipped -> "✓ Equipped"
                owned -> "Equip"
                else -> String.format(Locale.getDefault(), "Buy • %,d points", item.cost)
            }
            isEnabled = !equipped && (owned || snapshot.balance >= item.cost)
            setOnClickListener { onItemPressed(item, owned) }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        items.addView(card, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(10) })
    }

    private fun previewFor(item: ShopItem): View {
        val preview = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            contentDescription = "Preview: ${item.name}"
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        when (item.type) {
            CosmeticType.CAR_STYLE, CosmeticType.CAR_COLOR -> {
                val style = if (item.type == CosmeticType.CAR_STYLE) {
                    CarStyle.entries.firstOrNull { it.id == item.value } ?: Cosmetics.carStyle(this)
                } else Cosmetics.carStyle(this)
                val color = if (item.type == CosmeticType.CAR_COLOR) {
                    CarColor.entries.firstOrNull { it.id == item.value } ?: Cosmetics.carColor(this)
                } else Cosmetics.carColor(this)
                preview.addView(ImageView(this).apply {
                    setImageBitmap(Cosmetics.renderCarIcon(this@ShopActivity, style, color))
                    scaleType = ImageView.ScaleType.FIT_CENTER
                }, LinearLayout.LayoutParams(dp(52), dp(52)))
            }
            CosmeticType.ROAD_COLOR, CosmeticType.APP_THEME -> {
                val color = if (item.type == CosmeticType.ROAD_COLOR) {
                    RoadColor.entries.firstOrNull { it.id == item.value }?.argb
                        ?: Cosmetics.roadColor(this).argb
                } else if (item.value == "gold") 0xFFD9A406.toInt() else 0xFF235DDE.toInt()
                preview.addView(View(this).apply {
                    background = GradientDrawable().apply {
                        setColor(color)
                        cornerRadius = dp(8).toFloat()
                        setStroke(dp(1), Appearance.color(this@ShopActivity, R.attr.rcOutline))
                    }
                }, LinearLayout.LayoutParams(dp(52), dp(if (item.type == CosmeticType.ROAD_COLOR) 12 else 42)))
            }
        }
        return preview
    }

    private fun onItemPressed(item: ShopItem, alreadyOwned: Boolean) {
        if (alreadyOwned || (VipEntitlement.isActive(this) && VipEntitlement.isGoldIncluded(item.id))) {
            equipAndRefresh(item)
            return
        }
        val current = ++generation
        executor.execute {
            // A Google Play purchase can finish while a stale "Buy" card is
            // still visible. Check again before spending local points so VIP
            // gold benefits never accidentally charge the player.
            val result = runCatching {
                if (VipEntitlement.isActive(this) && VipEntitlement.isGoldIncluded(item.id)) {
                    PurchaseResult.OWNED
                } else ProgressionRepository(this).purchase(item.id, item.cost)
            }
            runOnUiThread {
                if (isDestroyed || current != generation) return@runOnUiThread
                result.fold(
                    onSuccess = { purchase ->
                        when (purchase) {
                            PurchaseResult.PURCHASED, PurchaseResult.OWNED -> equipAndRefresh(item)
                            PurchaseResult.INSUFFICIENT_POINTS -> {
                                Toast.makeText(this, "Not enough points yet.", Toast.LENGTH_SHORT).show()
                                refresh()
                            }
                        }
                    },
                    onFailure = {
                        Toast.makeText(this, "Could not complete purchase.", Toast.LENGTH_SHORT).show()
                        refresh()
                    }
                )
            }
        }
    }

    private fun equipAndRefresh(item: ShopItem) {
        if (!Cosmetics.equip(this, item)) {
            Toast.makeText(this, "Purchase this item first.", Toast.LENGTH_SHORT).show()
            refresh()
            return
        }
        Toast.makeText(this, item.name + " equipped", Toast.LENGTH_SHORT).show()
        if (item.type == CosmeticType.APP_THEME) recreate() else refresh()
    }

    private fun sectionName(type: CosmeticType): String = when (type) {
        CosmeticType.CAR_STYLE -> "Car icons"
        CosmeticType.CAR_COLOR -> "Car colors"
        CosmeticType.ROAD_COLOR -> "Road colors"
        CosmeticType.APP_THEME -> "App themes"
    }

    private fun applyInsets() {
        val root = findViewById<android.view.View>(R.id.shopRoot)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    override fun onStop() {
        ForegroundSession.app.onStop(isChangingConfigurations)
        super.onStop()
    }

    override fun onPause() {
        if (::ads.isInitialized) ads.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        generation++
        if (::ads.isInitialized) ads.destroy()
        VipEntitlement.removeListener(vipEntitlementListener)
        if (::vipBilling.isInitialized) vipBilling.close()
        // A purchase the user already confirmed is a local state mutation and should finish
        // even if the Garage screen closes immediately afterward. Stale UI callbacks are
        // rejected by generation/isDestroyed checks.
        executor.shutdown()
        super.onDestroy()
    }
}
