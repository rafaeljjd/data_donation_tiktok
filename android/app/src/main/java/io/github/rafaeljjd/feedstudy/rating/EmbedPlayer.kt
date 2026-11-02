package io.github.rafaeljjd.feedstudy.rating

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

const val DEFAULT_PAGE_HOST = "appassets.androidplatform.net"

/**
 * TikTok's embed player in a WebView, with no signed-in TikTok session. TikTok still receives
 * the device's IP address and the video ID, which the consent text must disclose.
 */
@Composable
fun EmbedPlayer(
    videoId: String,
    modifier: Modifier = Modifier,
    pageHost: String = DEFAULT_PAGE_HOST,
    onEvent: (PlayerEvent) -> Unit,
    onRawMessage: (String) -> Unit = {},
) {
    val currentOnEvent by rememberUpdatedState(onEvent)
    val currentOnRaw by rememberUpdatedState(onRawMessage)
    AndroidView(
        modifier = modifier,
        factory = { context ->
            createPlayerWebView(context, videoId, pageHost) { message ->
                currentOnRaw(message)
                PlayerEvents.parse(message)?.let { currentOnEvent(it) }
            }
        },
        onRelease = { it.destroy() },
    )
}

@SuppressLint("SetJavaScriptEnabled")
private fun createPlayerWebView(
    context: Context,
    videoId: String,
    pageHost: String,
    onMessage: (String) -> Unit,
): WebView {
    val pageOrigin = "https://$pageHost"
    val assets = WebViewAssetLoader.Builder()
        .setDomain(pageHost)
        .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
        .build()

    return WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.setSupportMultipleWindows(false)
        settings.allowFileAccess = false
        settings.allowContentAccess = false

        webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest,
            ): WebResourceResponse? = assets.shouldInterceptRequest(request.url)

            // The participant stays on the player: links out of it do not open.
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest,
            ): Boolean = request.isForMainFrame && request.url.host != pageHost
        }

        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            // Only our own page gets the bridge, not TikTok's frame inside it.
            WebViewCompat.addWebMessageListener(
                this,
                "FeedStudyBridge",
                setOf(pageOrigin),
            ) { _, message, _, _, _ ->
                message.data?.let(onMessage)
            }
        }
        loadUrl("$pageOrigin/assets/embed.html?id=${Uri.encode(videoId)}")
    }
}
