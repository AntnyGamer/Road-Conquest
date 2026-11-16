package com.roadconquest.app.ads

import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.Toast
import com.roadconquest.app.BuildConfig
import com.roadconquest.app.R
import com.roadconquest.app.billing.VipBilling
import com.roadconquest.app.billing.VipEntitlement
import com.roadconquest.app.progression.AdRewardBridge
import com.roadconquest.app.util.ForegroundSession
import com.vungle.ads.AdConfig
import com.vungle.ads.BannerAdListener
import com.vungle.ads.BaseAd
import com.vungle.ads.InitializationListener
import com.vungle.ads.RewardedAd
import com.vungle.ads.RewardedAdListener
import com.vungle.ads.VungleAdSize
import com.vungle.ads.VungleAds
import com.vungle.ads.VungleBannerView
import com.vungle.ads.VungleError
import com.vungle.ads.VunglePrivacySettings
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean

/** Liftoff ads live only on visible UI screens, never in the driving or map services. */
class ScreenAds(
    private val activity: Activity,
    private val root: View,
    private val rewardButton: Button? = null,
    private val checkVipOwnership: Boolean = true,
    private val onRewardSaved: () -> Unit = {}
) {
    private val footer = activity.findViewById<View>(R.id.adBannerFooter)
    private val bannerRow = activity.findViewById<View>(R.id.adBannerRow)
    private val container = activity.findViewById<FrameLayout>(R.id.adBannerContainer)
    private val privacyButton = activity.findViewById<Button>(R.id.adPrivacyButton)
    private var active = false
    private var destroyed = false
    private var vipChecked = false
    private var sdkReady = false
    private var epoch = 0
    private var recoveryUsed = false
    private var banner: VungleBannerView? = null
    private var bannerLayoutListener: View.OnLayoutChangeListener? = null
    private var rewarded: RewardedAd? = null
    private var rewardedLoadedAt = 0L
    private var loadingReward = false
    private var showingReward = false
    private var consentDialog: AlertDialog? = null

    private val vipBilling = if (checkVipOwnership) VipBilling(activity) { _, checked, _ ->
        onVipOwnershipChecked(checked)
    } else null
    private val vipListener: () -> Unit = {
        activity.runOnUiThread {
            if (active && alive()) {
                if (VipEntitlement.isActive(activity)) suspendAdsForVip()
                else if (vipChecked) startAdsIfAllowed()
            }
        }
    }
    private val retryAds = Runnable {
        if (active && alive() && vipChecked && !VipEntitlement.isActive(activity)) startAdsIfAllowed()
    }

    init {
        VipEntitlement.addListener(vipListener)
        rewardButton?.setOnClickListener { loadOrShowReward() }
        privacyButton.setOnClickListener { showPrivacyOptions() }
    }

    fun onResume() {
        active = true
        vipChecked = false
        recoveryUsed = false
        root.removeCallbacks(retryAds)
        if (VipEntitlement.isActive(activity)) suspendAdsForVip()
        vipBilling?.refresh(includeOffer = false)
        updatePrivacyButton()
        updateRewardButton()
    }

    /** The Garage shares its purchase check with this ad manager. */
    fun onVipOwnershipChecked(checked: Boolean) {
        vipChecked = checked
        if (checked && active && alive()) {
            if (VipEntitlement.isActive(activity)) suspendAdsForVip()
            else startAdsIfAllowed()
        }
    }

    fun onPause() {
        active = false
        epoch++
        root.removeCallbacks(retryAds)
        consentDialog?.dismiss()
        consentDialog = null
        rewarded = null
        loadingReward = false
        destroyBanner()
        updateRewardButton()
    }

    fun destroy() {
        destroyed = true
        onPause()
        VipEntitlement.removeListener(vipListener)
        vipBilling?.close()
    }

    private fun alive(): Boolean = !destroyed && !activity.isDestroyed && !activity.isFinishing

    private fun permitted(): Boolean = active && alive() && vipChecked &&
        !VipEntitlement.isActive(activity) && LiftoffConfig.isConfigured() &&
        AdPrivacy.hasChoice(activity) && consentDialog == null

    private fun suspendAdsForVip() {
        epoch++
        root.removeCallbacks(retryAds)
        rewarded = null
        loadingReward = false
        sdkReady = false
        destroyBanner()
        updateRewardButton()
        updatePrivacyButton()
    }

    /** One bounded foreground retry per visit; no work from a hidden screen. */
    private fun retryOnce() {
        if (!active || !alive() || recoveryUsed || !AdPrivacy.hasChoice(activity)) return
        recoveryUsed = true
        root.postDelayed(retryAds, 60_000L)
    }

    private fun startAdsIfAllowed() {
        updatePrivacyButton()
        updateRewardButton()
        if (!active || !alive() || !vipChecked || VipEntitlement.isActive(activity) ||
            !LiftoffConfig.isConfigured()) return
        if (!AdPrivacy.hasChoice(activity)) {
            showPrivacyOptions()
            return
        }
        if (!permitted()) {
            destroyBanner()
            return
        }
        // Both network privacy flags must be established before SDK initialization.
        AdPrivacy.applyToSdk(activity)
        val current = epoch
        AdSdk.initialize(activity.application).whenComplete { _, error ->
            activity.runOnUiThread {
                if (!permitted() || current != epoch) return@runOnUiThread
                sdkReady = error == null
                if (sdkReady) {
                    root.post {
                        if (permitted() && current == epoch) createBanner()
                    }
                } else retryOnce()
                updateRewardButton()
            }
        }
    }

    private fun showPrivacyOptions() {
        if (!active || !alive() || showingReward || !LiftoffConfig.isConfigured() ||
            consentDialog != null) return
        val firstChoice = !AdPrivacy.hasChoice(activity)
        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.ad_privacy_options)
            .setMessage(R.string.ad_consent_description)
            .setPositiveButton(R.string.ad_allow_ads) { _, _ -> changeConsent(true) }
            .setNegativeButton(R.string.ad_reject_ads) { _, _ -> changeConsent(false) }
            .setCancelable(!firstChoice)
            .create()
        consentDialog = dialog
        dialog.setOnDismissListener {
            consentDialog = null
            updatePrivacyButton()
            if (AdPrivacy.hasChoice(activity) && active && alive()) startAdsIfAllowed()
        }
        dialog.show()
        updatePrivacyButton()
    }

    private fun changeConsent(allowed: Boolean) {
        // A choice change discards ads loaded with the previous privacy flags; earned rewards still save.
        AdPrivacy.save(activity, allowed)
        AdPrivacy.applyToSdk(activity)
        epoch++
        rewarded = null
        loadingReward = false
        sdkReady = false
        root.removeCallbacks(retryAds)
        destroyBanner()
        updateRewardButton()
    }

    private fun updatePrivacyButton() {
        privacyButton.visibility = if (LiftoffConfig.isConfigured()) View.VISIBLE else View.GONE
        privacyButton.isEnabled = active && !showingReward && consentDialog == null
        updateFooter()
    }

    private fun updateFooter() {
        footer.visibility = if (bannerRow.visibility == View.VISIBLE ||
            privacyButton.visibility == View.VISIBLE) View.VISIBLE else View.GONE
    }

    private fun clearBannerLayoutListener() {
        bannerLayoutListener?.let(root::removeOnLayoutChangeListener)
        bannerLayoutListener = null
    }

    private fun createBanner() {
        if (banner != null || !sdkReady || !permitted()) return
        val widthPx = root.width - root.paddingLeft - root.paddingRight -
            footer.paddingLeft - footer.paddingRight
        val widthDp = (widthPx / activity.resources.displayMetrics.density).toInt()
        if (widthDp <= 0) {
            if (bannerLayoutListener == null) {
                val listener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                    if (root.width > 0) {
                        clearBannerLayoutListener()
                        createBanner()
                    }
                }
                bannerLayoutListener = listener
                root.addOnLayoutChangeListener(listener)
            }
            return
        }
        // Vungle offers fixed 320x50 and 300x50 banners, not AdMob adaptive banners.
        if (widthDp < 300) return
        clearBannerLayoutListener()
        val size = if (widthDp >= 320) VungleAdSize.BANNER else VungleAdSize.BANNER_SHORT
        val view = VungleBannerView(activity, BuildConfig.LIFTOFF_BANNER_ID, size)
        banner = view
        view.adListener = object : BannerAdListener {
            override fun onAdLoaded(baseAd: BaseAd) {
                activity.runOnUiThread {
                    if (banner !== view || !permitted()) return@runOnUiThread
                    bannerRow.visibility = View.VISIBLE
                    updateFooter()
                }
            }
            override fun onAdFailedToLoad(baseAd: BaseAd, error: VungleError) {
                activity.runOnUiThread {
                    if (banner !== view) return@runOnUiThread
                    destroyBanner()
                    retryOnce()
                }
            }
            override fun onAdFailedToPlay(baseAd: BaseAd, error: VungleError) {
                activity.runOnUiThread {
                    if (banner !== view) return@runOnUiThread
                    destroyBanner()
                    retryOnce()
                }
            }
            override fun onAdStart(baseAd: BaseAd) {}
            override fun onAdEnd(baseAd: BaseAd) {}
            override fun onAdImpression(baseAd: BaseAd) {}
            override fun onAdClicked(baseAd: BaseAd) {}
            override fun onAdLeftApplication(baseAd: BaseAd) {}
        }
        container.addView(view, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER
        ))
        view.load()
    }

    private fun destroyBanner() {
        clearBannerLayoutListener()
        val view = banner
        banner = null
        container.removeAllViews()
        view?.finishAd()
        view?.adListener = null
        bannerRow.visibility = View.GONE
        updateFooter()
    }

    private fun updateRewardButton() {
        rewardButton?.apply {
            val enabled = permitted() && sdkReady
            visibility = if (VipEntitlement.isActive(activity) || !LiftoffConfig.isConfigured())
                View.GONE else View.VISIBLE
            isEnabled = enabled && !loadingReward && !showingReward
            text = when {
                showingReward -> activity.getString(R.string.ad_showing)
                loadingReward -> activity.getString(R.string.ad_loading)
                !enabled -> activity.getString(R.string.ad_unavailable)
                rewarded?.canPlayAd() == true -> activity.resources.getQuantityString(
                    R.plurals.ad_watch, BuildConfig.LIFTOFF_REWARD_POINTS, BuildConfig.LIFTOFF_REWARD_POINTS)
                else -> activity.getString(R.string.ad_load)
            }
        }
    }

    private fun loadOrShowReward() {
        if (!permitted() || !sdkReady || loadingReward || showingReward) return
        rewarded?.let { existing ->
            if (SystemClock.elapsedRealtime() - rewardedLoadedAt < 45L * 60L * 1000L &&
                existing.canPlayAd()) {
                showReward(existing)
                return
            }
        }
        rewarded = null
        loadingReward = true
        updateRewardButton()
        val current = epoch
        val ad = RewardedAd(activity, BuildConfig.LIFTOFF_REWARDED_ID, AdConfig())
        val awarded = AtomicBoolean(false)
        ad.adListener = object : RewardedAdListener {
            override fun onAdLoaded(baseAd: BaseAd) {
                activity.runOnUiThread {
                    if (!permitted() || current != epoch) return@runOnUiThread
                    loadingReward = false
                    rewarded = ad
                    rewardedLoadedAt = SystemClock.elapsedRealtime()
                    updateRewardButton()
                }
            }
            override fun onAdFailedToLoad(baseAd: BaseAd, error: VungleError) {
                activity.runOnUiThread {
                    if (current != epoch || !alive()) return@runOnUiThread
                    loadingReward = false
                    rewarded = null
                    if (active) toast(R.string.ad_no_fill)
                    updateRewardButton()
                }
            }
            override fun onAdFailedToPlay(baseAd: BaseAd, error: VungleError) {
                activity.runOnUiThread {
                    if (alive() && active) toast(R.string.ad_no_fill)
                    finishReward()
                }
            }
            override fun onAdRewarded(baseAd: BaseAd) {
                if (awarded.compareAndSet(false, true)) saveCompletedReward()
            }
            override fun onAdEnd(baseAd: BaseAd) {
                activity.runOnUiThread { finishReward() }
            }
            override fun onAdStart(baseAd: BaseAd) {}
            override fun onAdImpression(baseAd: BaseAd) {}
            override fun onAdClicked(baseAd: BaseAd) {}
            override fun onAdLeftApplication(baseAd: BaseAd) {}
        }
        ad.load()
    }

    private fun showReward(ad: RewardedAd) {
        rewarded = null
        if (!ad.canPlayAd() || !permitted()) {
            updateRewardButton()
            return
        }
        showingReward = true
        updateRewardButton()
        updatePrivacyButton()
        runCatching { ad.play() }.onFailure {
            Log.w("RoadConquest", "Rewarded ad could not start", it)
            finishReward()
        }
    }

    private fun saveCompletedReward() {
        val points = BuildConfig.LIFTOFF_REWARD_POINTS.toLong()
        val bridge = AdRewardBridge(activity.applicationContext, points)
        // A confirmed reward is saved even if the user leaves before the ad ends.
        Thread({
            val result = runCatching { bridge.onCompletedAd() }
            activity.runOnUiThread {
                if (!alive()) return@runOnUiThread
                result.fold(onSuccess = { saved ->
                    if (saved) {
                        if (active) Toast.makeText(activity, activity.resources.getQuantityString(
                            R.plurals.ad_reward_saved, points.toInt(), points), Toast.LENGTH_SHORT).show()
                        onRewardSaved()
                    }
                }, onFailure = {
                    Log.e("RoadConquest", "Could not save completed ad reward", it)
                    if (active) toast(R.string.ad_reward_failed)
                })
            }
        }, "AdRewardSave").start()
    }

    private fun finishReward() {
        showingReward = false
        if (alive()) {
            updateRewardButton()
            updatePrivacyButton()
        }
    }

    private fun toast(message: Int) = Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
}

internal object LiftoffConfig {
    fun isConfigured(): Boolean = hasAdConfiguration(
        BuildConfig.LIFTOFF_APP_ID, BuildConfig.LIFTOFF_BANNER_ID, BuildConfig.LIFTOFF_REWARDED_ID)
}

internal fun hasAdConfiguration(appId: String, bannerId: String, rewardedId: String): Boolean =
    appId.isNotBlank() && bannerId.isNotBlank() && rewardedId.isNotBlank()

private object AdPrivacy {
    private const val PREFS = "roadconquest_ad_privacy"
    private const val CONSENT_KEY = "allow_liftoff_ads_v1"
    private const val VERSION = "1.0"

    fun hasChoice(activity: Activity) = activity.getSharedPreferences(PREFS, 0).contains(CONSENT_KEY)
    fun allowsPersonalizedAds(activity: Activity) =
        activity.getSharedPreferences(PREFS, 0).getBoolean(CONSENT_KEY, false)

    fun save(activity: Activity, allowed: Boolean) {
        activity.getSharedPreferences(PREFS, 0).edit().putBoolean(CONSENT_KEY, allowed).apply()
    }

    fun applyToSdk(activity: Activity) {
        val allowed = allowsPersonalizedAds(activity)
        VunglePrivacySettings.setGDPRStatus(allowed, VERSION)
        VunglePrivacySettings.setCCPAStatus(allowed)
    }
}

private object AdSdk {
    private var initialization: CompletableFuture<Unit>? = null
    private var lifecycleRegistered = false

    @Synchronized fun initialize(application: Application): CompletableFuture<Unit> {
        initialization?.let { return it }
        val future = CompletableFuture<Unit>()
        initialization = future
        if (!lifecycleRegistered) {
            application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
                // Fullscreen SDK Activities must not look like app exits to auto tracking.
                private fun isAdActivity(activity: Activity) =
                    activity.javaClass.name.startsWith("com.vungle.")
                override fun onActivityStarted(activity: Activity) {
                    if (isAdActivity(activity)) ForegroundSession.app.onStart()
                }
                override fun onActivityStopped(activity: Activity) {
                    if (isAdActivity(activity)) ForegroundSession.app.onStop(activity.isChangingConfigurations)
                }
                override fun onActivityCreated(activity: Activity, state: Bundle?) {}
                override fun onActivityResumed(activity: Activity) {}
                override fun onActivityPaused(activity: Activity) {}
                override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}
                override fun onActivityDestroyed(activity: Activity) {}
            })
            lifecycleRegistered = true
        }
        try {
            VungleAds.init(application, BuildConfig.LIFTOFF_APP_ID, object : InitializationListener {
                override fun onSuccess() { future.complete(Unit) }
                override fun onError(vungleError: VungleError) {
                    fail(future, IllegalStateException(vungleError.localizedMessage))
                }
            })
        } catch (error: Exception) {
            fail(future, error)
        }
        return future
    }

    @Synchronized private fun fail(future: CompletableFuture<Unit>, error: Throwable) {
        if (initialization === future) initialization = null
        future.completeExceptionally(error)
    }
}
