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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.viewinterop.AndroidView
import com.undal.syl.app.core.design.UndalTextButton
import com.undal.syl.app.core.theme.UndalDimens

/**
 * The Google-looking map view (PART K-3).
 *
 * This is Google Maps' public embed page inside a WebView: a real Google pin
 * view with no API key and no billing account, which is exactly the trick the
 * free-tier law requires. It is display-only — the customer never pans it,
 * because panning would need the paid SDK — so all interaction is disabled and
 * the parent scroll keeps working.
 *
 * When the embed cannot load (no data connection, or Google blocked on a
 * restricted network) the component says so in words and offers the osmdroid
 * picker instead of showing a blank grey box.
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
    val context = LocalContext.current
    var loadFailed by remember { mutableStateOf(false) }
    var webView by remember { mutableStateOf<WebView?>(null) }

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
                        settings.domStorageEnabled = false
                        settings.loadsImagesAutomatically = true
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
                            ): Boolean = true

                            override fun onPageFinished(view: WebView, url: String?) {
                                loadFailed = false
                            }

                            override fun onReceivedError(
                                view: WebView,
                                request: WebResourceRequest,
                                error: android.webkit.WebResourceError
                            ) {
                                if (request.isForMainFrame) loadFailed = true
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
                    text = "Open the offline map instead",
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
