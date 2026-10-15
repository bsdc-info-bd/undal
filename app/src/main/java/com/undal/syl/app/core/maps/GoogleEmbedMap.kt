package com.undal.syl.app.core.maps

import android.annotation.SuppressLint
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.viewinterop.AndroidView
import com.undal.syl.app.core.design.UndalTextButton
import com.undal.syl.app.core.theme.UndalDimens
import kotlinx.coroutines.delay

/**
 * The Google-looking map view (PART K-3).
 *
 * This is Google Maps' public embed page inside a WebView: a real Google pin view
 * with no API key and no billing account, which is what the free-tier law
 * requires. It is display-only — panning would need the paid SDK — so interaction
 * is disabled and the parent scroll keeps working.
 *
 * Three things this component has learned the hard way, all of which used to
 * produce the same grey box:
 *
 *   1. **The embed navigates.** `www.google.com/maps?output=embed` redirects
 *      through Google's own hosts while it boots. A `WebViewClient` that returns
 *      `true` from `shouldOverrideUrlLoading` for *every* URL — the reflex answer
 *      for "the page must not escape" — stops that boot dead and paints nothing.
 *      Google's hosts are allowed through; everything else is still blocked.
 *   2. **The embed needs DOM storage.** With `domStorageEnabled = false` its
 *      scripts throw on the first `localStorage` touch and the map never draws,
 *      while the page still reports a successful load.
 *   3. **"Loaded" is not "drawn".** A consent interstitial, a 403, a TLS refusal
 *      on a restricted network and a page that simply stalls all finish or never
 *      finish without ever calling `onReceivedError`. So the component keeps its
 *      own clock: HTTP errors, SSL errors and a timeout each count as a failure.
 *
 * On any of them it says so in words, gives the pin's coordinates — which are the
 * truth whatever the picture did — and offers the street map instead. It never
 * leaves a customer looking at an empty rectangle on the screen where their
 * address is supposed to be.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun UndalGoogleEmbedMap(
    latitude: Double,
    longitude: Double,
    modifier: Modifier = Modifier,
    height: Dp = UndalDimens.mapHeight,
    zoom: Int = 17,
    title: String = "Delivery point",
    onOpenOsmFallback: (() -> Unit)? = null
) {
    var loaded by remember { mutableStateOf(false) }
    var loadFailed by remember { mutableStateOf(false) }
    var webView by remember { mutableStateOf<WebView?>(null) }

    // One clock per pin: a page that has not finished by the deadline is treated
    // as a page that refused, because to the person looking at it that is exactly
    // what it is.
    LaunchedEffect(latitude, longitude, zoom) {
        loaded = false
        loadFailed = false
        delay(LOAD_TIMEOUT_MILLIS)
        if (!loaded) loadFailed = true
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(height)
                .clip(RoundedCornerShape(UndalDimens.radiusMd))
        ) {
            AndroidView(
                modifier = Modifier.fillMaxWidth().height(height),
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        // The embed's own scripts need this; without it the page
                        // loads and draws nothing.
                        settings.domStorageEnabled = true
                        settings.loadsImagesAutomatically = true
                        settings.useWideViewPort = true
                        settings.loadWithOverviewMode = true
                        settings.builtInZoomControls = false
                        settings.displayZoomControls = false
                        settings.setSupportZoom(false)
                        isVerticalScrollBarEnabled = false
                        isHorizontalScrollBarEnabled = false
                        // The page itself must never steal the parent scroll.
                        isNestedScrollingEnabled = false
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView,
                                request: WebResourceRequest
                            ): Boolean {
                                // Let the embed finish booting through Google's own
                                // hosts; block anything that would leave the map.
                                val host = request.url?.host?.lowercase().orEmpty()
                                return !GOOGLE_HOSTS.any { host == it || host.endsWith(".$it") }
                            }

                            override fun onPageFinished(view: WebView, url: String?) {
                                loaded = true
                                loadFailed = false
                            }

                            override fun onReceivedError(
                                view: WebView,
                                request: WebResourceRequest,
                                error: android.webkit.WebResourceError
                            ) {
                                if (request.isForMainFrame) loadFailed = true
                            }

                            override fun onReceivedHttpError(
                                view: WebView,
                                request: WebResourceRequest,
                                errorResponse: android.webkit.WebResourceResponse
                            ) {
                                // A 403 or a 429 from Google is the common case on a
                                // shared mobile IP, and it reports no error at all.
                                if (request.isForMainFrame) loadFailed = true
                            }

                            override fun onReceivedSslError(
                                view: WebView,
                                handler: android.webkit.SslErrorHandler?,
                                error: android.net.http.SslError?
                            ) {
                                // Never proceed past a TLS refusal: cancel, and show
                                // the street map instead.
                                handler?.cancel()
                                loadFailed = true
                            }
                        }
                        loadUrl(UndalMaps.googleEmbedUrl(latitude, longitude, zoom))
                        webView = this
                    }
                },
                update = { view ->
                    val target = UndalMaps.googleEmbedUrl(latitude, longitude, zoom)
                    if (view.url != target) view.loadUrl(target)
                }
            )
        }

        if (loadFailed) {
            Text(
                text = "The Google map view could not load on this connection. Your pin is still saved at " +
                    UndalLocation.latLngLabel(latitude, longitude) + ".",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = UndalDimens.xs)
            )
            if (onOpenOsmFallback != null) {
                UndalTextButton(
                    text = "Use the street map instead",
                    onClick = onOpenOsmFallback,
                    modifier = Modifier.padding(top = UndalDimens.xs)
                )
            }
        } else {
            Text(
                text = "$title · ${UndalLocation.latLngLabel(latitude, longitude)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = UndalDimens.xs)
            )
        }
    }

    DisposableEffect(webView) {
        onDispose {
            webView?.stopLoading()
            webView?.destroy()
            webView = null
        }
    }
}

/** How long the embed gets before the component stops waiting and says so. */
private const val LOAD_TIMEOUT_MILLIS = 9_000L

/** Hosts the embed is allowed to boot through. Everything else stays blocked. */
private val GOOGLE_HOSTS = listOf(
    "google.com",
    "www.google.com",
    "maps.google.com",
    "consent.google.com",
    "gstatic.com",
    "googleapis.com",
    "googleusercontent.com",
    "ggpht.com"
)
