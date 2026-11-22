package com.anpinime.projector

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject

/** Only the built-in interface can send native commands. Playback sites use separate WebViews. */
@SuppressLint("SetJavaScriptEnabled")
internal class V8WebView(
    context: Context,
    media: Boolean = false,
    receive: (JSONObject, (JSONObject) -> Unit) -> Unit
) : WebView(context) {
    private val origin = "https://appassets.androidplatform.net"

    init {
        val assets = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
            .build()
        setBackgroundColor(if (media) Color.TRANSPARENT else UiHelper.COLOR_BG)
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        if (!BuildConfig.IS_PHONE_FLAVOR) {
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.setSupportZoom(false)
            settings.textZoom = 100
            isHorizontalScrollBarEnabled = false
            isVerticalScrollBarEnabled = false
        }
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.mediaPlaybackRequiresUserGesture = false
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val uri = request.url
                if (uri.host == "appassets.androidplatform.net") return assets.shouldInterceptRequest(uri)
                return if (!request.isForMainFrame && uri.scheme in listOf("http", "https")) null
                else WebResourceResponse("text/plain", "utf-8", null)
            }
        }
        WebViewCompat.addWebMessageListener(this, "AnPinime", setOf(origin)) { _, message, _, mainFrame, reply ->
            if (mainFrame) {
                val request = runCatching { JSONObject(message.data.orEmpty()) }.getOrNull()
                    ?: return@addWebMessageListener
                receive(request) { result ->
                    runCatching { reply.postMessage(result.put("id", request.optInt("id")).toString()) }
                }
            }
        }
    }

    fun open(screen: String, intro: Boolean = false) {
        val page = Uri.parse("$origin/assets/v8/index.html").buildUpon()
            .appendQueryParameter("screen", screen)
            .appendQueryParameter("mode", if (BuildConfig.IS_PHONE_FLAVOR) "phone" else "tv")
            .appendQueryParameter("intro", if (intro) "1" else "0")
            .build()
        loadUrl(page.toString())
    }

    fun emit(event: String, value: JSONObject = JSONObject()) {
        evaluateJavascript("window.anpinimeEvent && window.anpinimeEvent(${JSONObject.quote(event)},$value)", null)
    }

    companion object {
        fun supported() = WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
    }
}
