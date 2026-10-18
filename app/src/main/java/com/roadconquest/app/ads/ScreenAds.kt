package com.roadconquest.app.ads

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.Toast
import com.google.android.gms.ads.AdActivity
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import com.roadconquest.app.BuildConfig
import com.roadconquest.app.R
import com.roadconquest.app.billing.VipBilling
import com.roadconquest.app.billing.VipEntitlement
import com.roadconquest.app.progression.AdRewardBridge
import com.roadconquest.app.util.ForegroundSession
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean

/** Activity-owned ads: no requests from the map or tracking service; bounded UI-only retries. */
class ScreenAds(
    private val activity: Activity,
    private val root: View,
    private val rewardButton: Button? = null,
    private val onRewardSaved: () -> Unit = {}
) {
    private val footer = activity.findViewById<View>(R.id.adBannerFooter)
    private val bannerRow = activity.findViewById<View>(R.id.adBannerRow)
    private val container = activity.findViewById<FrameLayout>(R.id.adBannerContainer)
    private val privacyButton = activity.findViewById<Button>(R.id.adPrivacyButton)
    private val consent = UserMessagingPlatform.getConsentInformation(activity)
    private var active = false
    private var destroyed = false
    private var epoch = 0
    private var consentRequested = false
    private var consentComplete = false
    private var formPending = false
    private var formShowing = false
    private var sdkReady = false
    private var banner: AdView? = null
    private var bannerLayoutListener: View.OnLayoutChangeListener? = null
    private var rewarded: RewardedAd? = null
    private var rewardLoadedAt = 0L
    private var loadingReward = false
    private var showingReward = false
    private var privacyRevision = AdPrivacy.revision
    private var recoveryUsed = false
    private var vipChecked = false
    private val vipBilling = VipBilling(activity) { _, checked, _ ->
        if (checked) {
            vipChecked = true
            if (active && alive()) {
                if (VipEntitlement.isActive(activity)) suspendAdsForVip()
                else resumeAfterVipCheck()
            }
        }
    }
    private val vipListener: () -> Unit = {
        activity.runOnUiThread {
            if (alive() && active) {
                if (VipEntitlement.isActive(activity)) suspendAdsForVip()
                else if (vipChecked) resumeAfterVipCheck()
            }
        }
    }
    private val retryAds = Runnable { if (permitted()) enableAds() }

    init {
        VipEntitlement.addListener(vipListener)
        rewardButton?.setOnClickListener { loadOrShowReward() }
        privacyButton.setOnClickListener { showPrivacyOptions() }
    }

    fun onResume() {
        active = true
        recoveryUsed = false
        vipChecked = false
        root.removeCallbacks(retryAds)
        if (privacyRevision != AdPrivacy.revision) {
            privacyRevision = AdPrivacy.revision
            destroyBanner()
        }
        // Restore a prior Play purchase before initializing/loading ads. A
        // cached offline VIP entitlement also suppresses ads immediately.
        if (VipEntitlement.isActive(activity)) suspendAdsForVip()
        vipBilling.refresh(includeOffer = false)
    }

    private fun resumeAfterVipCheck() {
        if (!active || !alive() || VipEntitlement.isActive(activity)) return
        updateRewardButton()
        if (!consentRequested) {
            consentRequested = true
            consent.requestConsentInfoUpdate(activity, ConsentRequestParameters.Builder().build(), {
                if (!alive() || VipEntitlement.isActive(activity)) return@requestConsentInfoUpdate
                formPending = true
                presentConsentForm()
            }, {
                if (!alive() || VipEntitlement.isActive(activity)) return@requestConsentInfoUpdate
                consentComplete = true
                updatePrivacyButton()
                enableAds()
            })
        } else if (formPending) {
            presentConsentForm()
        } else {
            enableAds()
        }
    }

    private fun suspendAdsForVip() {
        epoch++
        root.removeCallbacks(retryAds)
        rewarded = null
        loadingReward = false
        destroyBanner()
        updateRewardButton()
        updatePrivacyButton()
    }

    fun onPause() {
        active = false
        root.removeCallbacks(retryAds)
        clearBannerLayoutListener()
        epoch++
        // A hidden screen must never show an ad that finishes loading later.
        rewarded = null
        loadingReward = false
        banner?.pause()
        updateRewardButton()
    }

    fun destroy() {
        destroyed = true
        onPause()
        VipEntitlement.removeListener(vipListener)
        vipBilling.close()
        destroyBanner()
    }

    private fun alive() = !destroyed && !activity.isDestroyed && !activity.isFinishing

    private fun permitted() = alive() && active && vipChecked &&
        !VipEntitlement.isActive(activity) && consentComplete && !formShowing && consent.canRequestAds()

    /** At most one foreground recovery attempt per screen visit; never retry while hidden. */
    private fun retryOnce() {
        if (!active || !alive() || recoveryUsed) return
        recoveryUsed = true
        root.postDelayed(retryAds, AD_RETRY_DELAY_MS)
    }

    private fun presentConsentForm() {
        if (!alive() || !active || formShowing || !formPending) return
        formPending = false
        formShowing = true
        UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { error ->
            completeConsentForm(error != null)
        }
    }

    private fun showPrivacyOptions() {
        if (!alive() || !active || formShowing || showingReward) return
        epoch++
        rewarded = null
        loadingReward = false
        consentComplete = false
        formShowing = true
        destroyBanner()
        updateRewardButton()
        UserMessagingPlatform.showPrivacyOptionsForm(activity) { error ->
            privacyRevision = ++AdPrivacy.revision
            completeConsentForm(error != null)
        }
    }

    private fun completeConsentForm(hadError: Boolean) {
        if (!alive()) return
        formShowing = false
        consentComplete = true
        if (hadError && active) toast(R.string.ad_consent_error)
        updatePrivacyButton()
        enableAds()
    }

    private fun updatePrivacyButton() {
        // Paying to remove ads must not remove access to existing ad privacy
        // choices for people who previously consented to personalized ads.
        privacyButton.visibility = if (consent.privacyOptionsRequirementStatus ==
            ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED) View.VISIBLE else View.GONE
        privacyButton.isEnabled = !formShowing && !showingReward
        updateFooter()
    }

    private fun updateFooter() {
        footer.visibility = if (bannerRow.visibility == View.VISIBLE || privacyButton.visibility == View.VISIBLE)
            View.VISIBLE else View.GONE
    }

    private fun enableAds() {
        updatePrivacyButton()
        updateRewardButton()
        if (!permitted()) {
            if (consentComplete && !consent.canRequestAds()) destroyBanner()
            return
        }
        banner?.resume()
        val current = epoch
        AdSdk.initialize(activity.application).whenComplete { _, error ->
            activity.runOnUiThread {
                if (!permitted() || current != epoch) return@runOnUiThread
                sdkReady = error == null
                if (sdkReady) root.post {
                    if (permitted() && current == epoch) createBanner()
                } else {
                    retryOnce()
                }
                updateRewardButton()
            }
        }
    }

    private fun clearBannerLayoutListener() {
        bannerLayoutListener?.let(root::removeOnLayoutChangeListener)
        bannerLayoutListener = null
    }

    private fun createBanner() {
        if (banner != null || !sdkReady || !permitted()) return
        val pixels = root.width - root.paddingLeft - root.paddingRight - footer.paddingLeft - footer.paddingRight
        val widthDp = (pixels / activity.resources.displayMetrics.density).toInt()
        if (widthDp <= 0) {
            // Cached SDK initialization may complete before a new Activity's first layout.
            // Wait for actual dimensions rather than silently skipping the banner.
            if (bannerLayoutListener == null) {
                val listener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                    val measuredPixels = root.width - root.paddingLeft - root.paddingRight -
                        footer.paddingLeft - footer.paddingRight
                    if ((measuredPixels / activity.resources.displayMetrics.density).toInt() > 0) {
                        clearBannerLayoutListener()
                        createBanner()
                    }
                }
                bannerLayoutListener = listener
                root.addOnLayoutChangeListener(listener)
            }
            return
        }
        clearBannerLayoutListener()
        val view = AdView(activity)
        banner = view
        view.adUnitId = BuildConfig.ADMOB_BANNER_ID
        view.setAdSize(AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(activity, widthDp))
        view.adListener = object : AdListener() {
            override fun onAdLoaded() {
                if (banner !== view || !alive()) return
                if (!active) view.pause()
                bannerRow.visibility = View.VISIBLE
                updateFooter()
            }

            override fun onAdFailedToLoad(error: LoadAdError) {
                if (banner !== view || !alive()) return
                // A failed first load must not leave a nonfunctional AdView blocking recovery.
                // Keep a successfully loaded banner if an SDK refresh fails later.
                if (bannerRow.visibility != View.VISIBLE) {
                    destroyBanner()
                    retryOnce()
                }
            }
        }
        container.addView(view, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER
        ))
        view.loadAd(AdRequest.Builder().build())
    }

    private fun destroyBanner() {
        val view = banner
        banner = null
        container.removeAllViews()
        view?.destroy()
        bannerRow.visibility = View.GONE
        updateFooter()
    }

    private fun updateRewardButton() {
        rewardButton?.apply {
            val adsAvailable = permitted() && sdkReady
            visibility = if (VipEntitlement.isActive(activity)) View.GONE else View.VISIBLE
            isEnabled = adsAvailable && !loadingReward && !showingReward
            text = when {
                showingReward -> activity.getString(R.string.ad_showing)
                loadingReward -> activity.getString(R.string.ad_loading)
                !adsAvailable -> activity.getString(R.string.ad_unavailable)
                rewarded != null -> activity.getString(R.string.ad_watch, rewarded!!.rewardItem.amount)
                else -> activity.getString(R.string.ad_load)
            }
        }
    }

    private fun loadOrShowReward() {
        if (!permitted() || !sdkReady || loadingReward || showingReward) return
        val loaded = rewarded
        // Google rewards expire after an hour; allow margin without running an expiry timer.
        if (loaded != null && SystemClock.elapsedRealtime() - rewardLoadedAt < 55L * 60L * 1000L) {
            showReward(loaded)
            return
        }
        rewarded = null
        loadingReward = true
        updateRewardButton()
        val current = epoch
        RewardedAd.load(activity, BuildConfig.ADMOB_REWARDED_ID, AdRequest.Builder().build(),
            object : RewardedAdLoadCallback() {
                override fun onAdLoaded(ad: RewardedAd) {
                    if (!permitted() || current != epoch) return
                    loadingReward = false
                    if (ad.rewardItem.amount > 0) {
                        rewarded = ad
                        rewardLoadedAt = SystemClock.elapsedRealtime()
                    } else toast(R.string.ad_no_fill)
                    updateRewardButton()
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    if (!permitted() || current != epoch) return
                    loadingReward = false
                    toast(R.string.ad_no_fill)
                    updateRewardButton()
                }
            })
    }

    private fun showReward(ad: RewardedAd) {
        val points = ad.rewardItem.amount.toLong()
        val bridge = AdRewardBridge(activity.applicationContext, points)
        val completed = AtomicBoolean(false)
        rewarded = null
        showingReward = true
        updateRewardButton()
        updatePrivacyButton()
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() { finishReward() }
            override fun onAdFailedToShowFullScreenContent(error: com.google.android.gms.ads.AdError) {
                finishReward()
                if (alive() && active) toast(R.string.ad_no_fill)
            }
        }
        ad.show(activity) {
            if (!completed.compareAndSet(false, true)) return@show
            // A confirmed reward must finish saving even if the screen closes immediately.
            Thread({
                val result = runCatching { bridge.onCompletedAd() }
                activity.runOnUiThread {
                    if (!alive()) return@runOnUiThread
                    result.fold(onSuccess = { saved ->
                        if (saved) {
                            if (active) Toast.makeText(activity, activity.getString(R.string.ad_reward_saved, points), Toast.LENGTH_SHORT).show()
                            onRewardSaved()
                        }
                    }, onFailure = {
                        Log.e("RoadConquest", "Could not save completed ad reward", it)
                        if (active) toast(R.string.ad_reward_failed)
                    })
                }
            }, "AdRewardSave").start()
        }
    }

    private fun finishReward() {
        showingReward = false
        if (alive()) {
            updateRewardButton()
            updatePrivacyButton()
        }
    }

    private fun toast(message: Int) = Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()

    companion object {
        private const val AD_RETRY_DELAY_MS = 60_000L
    }
}

private object AdPrivacy { var revision = 0 }

private object AdSdk {
    private var initialization: CompletableFuture<Unit>? = null
    private var lifecycleRegistered = false

    @Synchronized fun initialize(application: Application): CompletableFuture<Unit> {
        initialization?.let { return it }
        // Register once across failed initialization attempts, or AdActivity transitions
        // would be counted multiple times and change automatic-tracking lifecycle behavior.
        if (!lifecycleRegistered) {
            application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
                override fun onActivityStarted(activity: Activity) { if (activity is AdActivity) ForegroundSession.app.onStart() }
                override fun onActivityStopped(activity: Activity) { if (activity is AdActivity) ForegroundSession.app.onStop(activity.isChangingConfigurations) }
                override fun onActivityCreated(activity: Activity, state: Bundle?) {}
                override fun onActivityResumed(activity: Activity) {}
                override fun onActivityPaused(activity: Activity) {}
                override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}
                override fun onActivityDestroyed(activity: Activity) {}
            })
            lifecycleRegistered = true
        }
        val future = CompletableFuture<Unit>()
        initialization = future
        Thread({
            try {
                MobileAds.initialize(application) { future.complete(Unit) }
            } catch (error: Exception) {
                synchronized(this) {
                    if (initialization === future) initialization = null
                }
                future.completeExceptionally(error)
            }
        }, "AdSdkInit").start()
        return future
    }
}
