package Kinetic_Eco.Tracker.ui.components

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.os.Build
import android.util.Log
import android.view.WindowManager
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import Kinetic_Eco.Tracker.R
import Kinetic_Eco.Tracker.services.EntitlementRepository
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.math.min

private const val TAG = "AdMobBanner"

/**
 * Max height (dp) for the inline adaptive banner. The SDK returns an ad no taller
 * than this and sizes it to the creative's natural height. Kept banner-sized
 * because this slot is pinned at the top of the screen (not in a scrolling feed).
 */
private const val INLINE_BANNER_MAX_HEIGHT_DP = 100

/** Google sample banner — reliably returns test ads (debuggable builds only). */
private const val TEST_BANNER_UNIT_ID = "ca-app-pub-3940256099942544/6300978111"

private suspend fun awaitMobileAdsInit(context: android.content.Context) =
    suspendCoroutine { cont ->
        MobileAds.initialize(context) { cont.resume(Unit) }
    }

/**
 * Adaptive banner width for [AdSize.getInlineAdaptiveBannerAdSize] — must be in **dp**,
 * not pixels. Passing px (e.g. 1078) makes the SDK compare to [Configuration.screenWidthDp] (e.g. 412) and
 * fail with "Ad size will not fit on screen" (INVALID_REQUEST).
 */
private fun bannerWidthDp(context: Context, configuration: Configuration): Int {
    val dm = context.resources.displayMetrics
    val screenDp = configuration.screenWidthDp.coerceAtLeast(320)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val boundsW = wm.currentWindowMetrics.bounds.width()
        if (boundsW > 0) {
            val windowDp = (boundsW / dm.density).toInt().coerceAtLeast(1)
            return min(screenDp, windowDp)
        }
    }
    return screenDp
}

/**
 * Inline adaptive banner. Debuggable APKs use [TEST_BANNER_UNIT_ID]; release uses [R.string.admob_banner_unit_id].
 * Waits for [MobileAds.initialize] before loading. Uses navigation bar insets + zIndex so the slot stays visible.
 * The container wraps the ad's natural height (capped at [INLINE_BANNER_MAX_HEIGHT_DP]) so no blank space is reserved.
 *
 * [dismissible]: when true, shows a close control that **hides this banner slot** until the user navigates
 * away (state is [rememberSaveable] across configuration change).
 *
 * [showAgainOnKey]: when this value changes (e.g. pass the current navigation route string), dismissal is cleared
 * so the banner appears again after a tab / destination change.
 *
 * This does not modify the ad creative; it only removes your app’s ad container.
 * Follow AdMob policies: do not obscure required ad elements or mimic system UI.
 *
 * Uses if/else (no early return) so composition slot order stays valid when sdkReady flips true.
 */
@Composable
fun AdMobBanner(
    modifier: Modifier = Modifier,
    dismissible: Boolean = true,
    showAgainOnKey: Any? = null
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    // Premium removes the banner entirely. Handled in the if/else chain below rather
    // than by an early return, per this function's slot-order convention.
    // Guarded here rather than only at the call site so a future caller cannot
    // reintroduce ads for subscribers by forgetting the check.
    val isPremium by EntitlementRepository.isPremium.collectAsStateWithLifecycle()
    // Gate on UMP consent — do not load until ConsentManager permits ad requests.
    val canRequestAds by ConsentManager.canRequestAds.collectAsStateWithLifecycle()
    var sdkReady by remember { mutableStateOf(false) }
    var dismissed by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(canRequestAds, isPremium) {
        // Keyed on isPremium so the Mobile Ads SDK is never even initialised for a
        // subscriber — initialising it starts network activity of its own.
        if (canRequestAds && !isPremium) {
            awaitMobileAdsInit(context.applicationContext)
            sdkReady = true
        }
    }
    LaunchedEffect(showAgainOnKey) {
        dismissed = false
    }

    if (isPremium) {
        // No AdView is constructed and no request is made. Merely hiding a loaded
        // banner would still spend the subscriber's data fetching an ad they never see.
        Spacer(Modifier.height(0.dp))
    } else if (dismissed) {
        Spacer(Modifier.height(0.dp))
    } else if (!canRequestAds || !sdkReady) {
        Spacer(Modifier.height(0.dp))
    } else {
        val isDebuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        val adUnitId = remember(isDebuggable) {
            if (isDebuggable) TEST_BANNER_UNIT_ID
            else context.getString(R.string.admob_banner_unit_id)
        }

        key(configuration.screenWidthDp, configuration.orientation, adUnitId) {
            val widthDp = remember(configuration, context) {
                bannerWidthDp(context, configuration)
            }
            val adSize = remember(widthDp) {
                AdSize.getInlineAdaptiveBannerAdSize(widthDp, INLINE_BANNER_MAX_HEIGHT_DP)
            }

            Box(
                modifier = modifier
                    .zIndex(100f)
                    .statusBarsPadding()
                    .fillMaxWidth()
                    .wrapContentHeight()
            ) {
                AndroidView(
                    factory = { ctx ->
                        AdView(ctx).apply {
                            layoutParams = FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT
                            )
                            setAdSize(adSize)
                            this.adUnitId = adUnitId
                            adListener = object : AdListener() {
                                override fun onAdLoaded() {
                                    Log.d(TAG, "onAdLoaded unit=$adUnitId")
                                }

                                override fun onAdImpression() {
                                    // Banner persists and auto-refreshes normally (no session cap).
                                    Log.d(TAG, "onAdImpression unit=$adUnitId")
                                }

                                override fun onAdFailedToLoad(error: LoadAdError) {
                                    Log.e(
                                        TAG,
                                        "onAdFailedToLoad code=${error.code} message=${error.message} " +
                                            "domain=${error.domain} unit=$adUnitId"
                                    )
                                }
                            }
                            loadAd(AdRequest.Builder().build())
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .wrapContentHeight(),
                    onRelease = { adView ->
                        adView.destroy()
                    }
                )
                if (dismissible) {
                    IconButton(
                        onClick = { dismissed = true },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 2.dp, end = 2.dp)
                            .zIndex(101f)
                            .size(40.dp),
                        colors = IconButtonDefaults.iconButtonColors(
                            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                            contentColor = MaterialTheme.colorScheme.onSurface
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = stringResource(R.string.close_banner_ad)
                        )
                    }
                }
            }
        }
    }
}
