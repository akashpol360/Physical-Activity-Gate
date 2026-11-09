package com.physicalactivitygate

import android.app.Activity
import android.content.Context
import android.util.Log
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.gms.ads.*
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback

/**
 * AdMob integration for Physical Activity Gate.
 *
 * FREE users → banner always visible + interstitial on the two Save actions.
 * PRO users  → zero ads, IDs are never requested.
 *
 * TEST IDs are active right now so you can verify everything on your device
 * without risking policy violations. Replace them with your real unit IDs
 * from https://apps.admob.com before publishing.
 *
 * Real unit IDs to replace:
 *   BANNER_UNIT_ID      → your banner ad unit ID
 *   INTERSTITIAL_UNIT_ID → your interstitial ad unit ID
 */
object AdIds {
    // ---- TEST IDs (safe to use on any device before publishing) ----
    const val BANNER_UNIT_ID = "ca-app-pub-3940256099942544/6300978111"
    const val INTERSTITIAL_UNIT_ID = "ca-app-pub-3940256099942544/1033173712"

    // ---- PRODUCTION IDs (fill in before publishing, then swap the constants) ----
    // const val BANNER_UNIT_ID = "ca-app-pub-XXXXXXXXXXXXXXXX/NNNNNNNNNN"
    // const val INTERSTITIAL_UNIT_ID = "ca-app-pub-XXXXXXXXXXXXXXXX/NNNNNNNNNN"
}

/** Call once from Application.onCreate() or MainActivity.onCreate(). */
fun initAdMob(context: Context) {
    MobileAds.initialize(context) {
        Log.d("AdMob", "Initialized: ${it.adapterStatusMap}")
    }
}

/**
 * Interstitial helper. Create one instance per screen that needs it, keep
 * it in a `remember` block so it survives recompositions, and call [show]
 * at the right moment. Ads are loaded lazily; if the load hasn't finished
 * in time, [show] is a no-op rather than a crash.
 */
class InterstitialAdHelper(private val context: Context) {
    private var interstitialAd: InterstitialAd? = null
    private var isLoading = false

    fun load() {
        if (isLoading || interstitialAd != null) return
        isLoading = true
        val request = AdRequest.Builder().build()
        InterstitialAd.load(
            context,
            AdIds.INTERSTITIAL_UNIT_ID,
            request,
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) {
                    interstitialAd = ad
                    isLoading = false
                    ad.fullScreenContentCallback = object : FullScreenContentCallback() {
                        override fun onAdDismissedFullScreenContent() {
                            interstitialAd = null
                            // Pre-load the next one so it's ready for the
                            // next Save action without noticeable delay.
                            load()
                        }
                        override fun onAdFailedToShowFullScreenContent(e: AdError) {
                            interstitialAd = null
                            load()
                        }
                    }
                }
                override fun onAdFailedToLoad(e: LoadAdError) {
                    isLoading = false
                    interstitialAd = null
                    Log.w("AdMob", "Interstitial failed to load: ${e.message}")
                }
            }
        )
    }

    /** Shows the ad if one is ready, then invokes [afterAd] regardless.
     * If no ad is ready the action runs immediately without delay — the user
     * action (save) is never blocked by ad availability. */
    fun show(activity: Activity, afterAd: () -> Unit) {
        val ad = interstitialAd
        if (ad != null) {
            val originalCallback = ad.fullScreenContentCallback
            ad.fullScreenContentCallback = object : FullScreenContentCallback() {
                override fun onAdDismissedFullScreenContent() {
                    originalCallback?.onAdDismissedFullScreenContent()
                    afterAd()
                }
                override fun onAdFailedToShowFullScreenContent(e: AdError) {
                    originalCallback?.onAdFailedToShowFullScreenContent(e)
                    afterAd()
                }
            }
            ad.show(activity)
        } else {
            afterAd()
        }
    }
}

/**
 * AdMob Banner — shown at the bottom of every main screen for free users.
 * Pass [isPro] = true and nothing is rendered at all.
 *
 * Usage:
 *   BannerAd(isPro = settings.isProActive)
 */
@Composable
fun BannerAd(isPro: Boolean) {
    if (isPro) return  // Pro users see zero ads

    AndroidView(
        factory = { ctx ->
            AdView(ctx).apply {
                setAdSize(AdSize.BANNER)
                adUnitId = AdIds.BANNER_UNIT_ID
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                loadAd(AdRequest.Builder().build())
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .height(60.dp)
    )
}
