package com.rollage.game

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform

class AdManager(private val appContext: Context) {

    // ---- interstitial frequency caps (session-scoped) ----
    private val GAMEOVERS_PER_AD = 3
    private val INTERSTITIAL_MIN_GAP_MS = 90_000L
    private val REWARDED_SUPPRESS_MS = 75_000L   // never chase a rewarded ad with one

    @Volatile private var gameOvers = 0
    private var lastInterstitialAt = Long.MIN_VALUE / 2
    private var lastRewardedAt = Long.MIN_VALUE / 2

    private var interstitial: InterstitialAd? = null
    private var rewarded: RewardedAd? = null
    private var interAttempts = 0
    private var rewAttempts = 0

    private var consentInfo: ConsentInformation? = null
    private var sdkReady = false

    private var banner: AdView? = null
    private var bannerSize: AdSize? = null
    private var bannerLoaded = false
    @Volatile private var bannerWanted = false
    private var bannerContainer: ViewGroup? = null
    private var onBannerHeight: ((Int) -> Unit)? = null

    // ---------- consent + SDK init ----------
    fun bootstrap(activity: Activity) {
        val ci = UserMessagingPlatform.getConsentInfo(appContext)
        consentInfo = ci
        val proceed = { if (!sdkReady && ci.canRequestAds()) initAndLoad() }
        ci.requestConsentInfoUpdate(
            activity, ConsentRequestParameters.Builder().build(),
            { UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { proceed() } },
            { proceed() }          // offline / previously consented — still try
        )
        proceed()                  // repeat launches: consent already gathered
    }

    private fun initAndLoad() {
        if (sdkReady) return
        sdkReady = true
        MobileAds.initialize(appContext) {
            loadInterstitial(); loadRewarded()
            val b = banner
            if (b != null) b.loadAd(adRequest()) else ensureBanner()
        }
    }

    /** GDPR: EU/UK users must be able to re-open the privacy form. */
    val privacyOptionsRequired: Boolean
        get() = consentInfo?.privacyOptionsRequirementStatus ==
                ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED

    fun showPrivacyOptions(activity: Activity) {
        UserMessagingPlatform.showPrivacyOptionsForm(activity) { }
    }

    // ---------- interstitial ----------
    private fun loadInterstitial() {
        InterstitialAd.load(appContext, BuildConfig.AD_INTERSTITIAL, adRequest(),
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) {
                    interstitial = ad; interAttempts = 0
                }
                override fun onAdFailedToLoad(err: LoadAdError) {
                    interstitial = null
                    interAttempts++
                    retryAfterBackoff(interAttempts) { loadInterstitial() }
                }
            })
    }

    /** Caps checked here; onDone ALWAYS runs (shown or not), so UI flow never stalls. */
    fun maybeShowInterstitial(activity: Activity, onDone: () -> Unit) {
        val ad = interstitial
        val now = SystemClock.elapsedRealtime()
        val due = gameOvers >= GAMEOVERS_PER_AD &&
                now - lastInterstitialAt >= INTERSTITIAL_MIN_GAP_MS &&
                now - lastRewardedAt >= REWARDED_SUPPRESS_MS
        if (ad == null || !due) { onDone(); return }
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                interstitial = null; loadInterstitial(); onDone()
            }
            override fun onAdFailedToShowFullScreenContent(e: AdError) {
                interstitial = null; loadInterstitial(); onDone()
            }
        }
        gameOvers = 0
        lastInterstitialAt = now
        ad.show(activity)
    }

    fun reportGameOver() { gameOvers++ }

    // ---------- rewarded ----------
    private fun loadRewarded() {
        RewardedAd.load(appContext, BuildConfig.AD_REWARDED, adRequest(),
            object : RewardedAdLoadCallback() {
                override fun onAdLoaded(ad: RewardedAd) {
                    rewarded = ad; rewAttempts = 0
                }
                override fun onAdFailedToLoad(err: LoadAdError) {
                    rewarded = null
                    rewAttempts++
                    retryAfterBackoff(rewAttempts) { loadRewarded() }
                }
            })
    }

    /** Reward granted only via onUserEarnedReward — closing early grants nothing. */
    fun showRewarded(activity: Activity, onGranted: () -> Unit, onUnavailable: () -> Unit) {
        val ad = rewarded
        if (ad == null) { onUnavailable(); loadRewarded(); return }
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                rewarded = null; loadRewarded()
            }
            override fun onAdFailedToShowFullScreenContent(e: AdError) {
                rewarded = null; loadRewarded()
            }
        }
        rewarded = null                       // consumed — can't be shown twice
        ad.show(activity) {
            lastRewardedAt = SystemClock.elapsedRealtime()
            onGranted()
        }
    }

    private fun retryAfterBackoff(attempts: Int, load: () -> Unit) {
        val delay = 2000L shl attempts.coerceAtMost(5)    // 4 s -> 64 s
        Handler(Looper.getMainLooper()).postDelayed(load, delay)
    }

    // ---------- banner (menu + results only) ----------
    fun attachBanner(activity: Activity, container: ViewGroup, onHeight: (Int) -> Unit) {
        bannerContainer = container
        onBannerHeight = onHeight
        ensureBanner(activity)
    }

    private fun ensureBanner(activity: Activity? = null) {
        if (banner != null) return
        val act = activity
            ?: (bannerContainer?.context as? Activity)
            ?: return
        val den = act.resources.displayMetrics.density
        val widthDp = (((bannerContainer?.width.takeIf { it != 0 }
            ?: act.resources.displayMetrics.widthPixels).toFloat()) / den)
            .toInt().coerceIn(320, 640)
        val size = AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(act, widthDp)
        bannerSize = size
        val adView = AdView(act)
        adView.adUnitId = BuildConfig.AD_BANNER
        adView.adSize = size
        adView.adListener = object : AdListener() {
            override fun onAdLoaded() { bannerLoaded = true; applyBanner() }
            override fun onAdFailedToLoad(err: LoadAdError) {
                bannerLoaded = false; applyBanner()
                Handler(Looper.getMainLooper()).postDelayed({
                    banner?.loadAd(adRequest())
                }, 30_000)
            }
        }
        bannerContainer?.addView(adView, ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT)
        banner = adView
        if (sdkReady) adView.loadAd(adRequest())
        applyBanner()
    }

    fun setBannerVisible(wanted: Boolean) {
        bannerWanted = wanted
        applyBanner()
    }

    private fun applyBanner() {
        val b = banner ?: return
        val show = bannerWanted && bannerLoaded
        b.post { b.visibility = if (show) View.VISIBLE else View.GONE }
        val h = if (show) {
            b.height.takeIf { it > 0 }
                ?: bannerSize?.height?.let { dp ->
                    (dp * appContext.resources.displayMetrics.density).toInt() }
                ?: 0
        } else 0
        onBannerHeight?.invoke(h)
        if (show && b.height <= 0) {
            b.post { if (b.height > 0) onBannerHeight?.invoke(b.height) }
        }
    }

    // ---------- lifecycle ----------
    fun pauseBanner() { try { banner?.pause() } catch (_: Exception) {} }
    fun resumeBanner() { try { banner?.resume() } catch (_: Exception) {} }
    fun releaseBanner() {
        banner?.let { b ->
            try { (b.parent as? ViewGroup)?.removeView(b) } catch (_: Exception) {}
            try { b.destroy() } catch (_: Exception) {}
        }
        banner = null
    }

    private fun adRequest() = AdRequest.Builder().build()
}
