package com.anpinime.projector

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.net.URL
import kotlin.coroutines.resume

object WebViewHelper {

    @Volatile
    var userAgent: String =
        "Mozilla/5.0 (Linux; Android 12; Pixel 7 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
        private set

    private var activityRef: WeakReference<Activity>? = null
    private var appContext: Context? = null
    private val mutex = Mutex()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun registerActivity(activity: Activity) {
        activityRef = WeakReference(activity)
        appContext = activity.applicationContext
        try {
            val rawUa = WebSettings.getDefaultUserAgent(activity)
            if (!rawUa.isNullOrBlank() && !rawUa.contains("K670", ignoreCase = true)) {
                // Clean "; wv" and "Version/4.0" so Cloudflare treats WebView & HttpURLConnection identically as Chrome
                userAgent = rawUa
                    .replace("; wv", "")
                    .replace(Regex("""Version/\d+\.\d+\s*"""), "")
                    .trim()
            }
        } catch (_: Throwable) {}
    }

    fun getCookieHeader(url: String): String? {
        return try {
            CookieManager.getInstance().getCookie(url)?.takeIf { it.isNotBlank() }
        } catch (_: Throwable) {
            null
        }
    }

    fun saveSetCookieHeaders(url: String, headers: Map<String, List<String>>) {
        try {
            val cm = CookieManager.getInstance()
            cm.setAcceptCookie(true)
            for ((k, values) in headers) {
                if (k != null && k.equals("Set-Cookie", ignoreCase = true)) {
                    for (v in values) {
                        cm.setCookie(url, v)
                    }
                }
            }
            cm.flush()
        } catch (_: Throwable) {}
    }

    fun containsVerification(html: String): Boolean {
        val lower = html.lowercase()
        val title = Regex("""(?is)<title[^>]*>(.*?)</title>""").find(lower)?.groupValues?.get(1).orEmpty()
        val challengeTitles = listOf(
            "just a moment",
            "xác minh an toàn",
            "xác minh bảo mật",
            "attention required",
            "verify you are human",
            "checking your browser"
        )
        val hasChallengeTitle = challengeTitles.any { title.contains(it) }
        val hasContent = Regex("""class=["'][^"']*(?:tpost|ml-item|film-item|movie-item|btn-episode)[^"']*["']""")
            .containsMatchIn(lower) || lower.contains("filminfo.filmid")
        if (hasContent && !hasChallengeTitle) return false
        return hasChallengeTitle ||
            lower.contains("id=\"challenge-form\"") ||
            lower.contains("id='challenge-form'") ||
            lower.contains("cf-turnstile") ||
            lower.contains("g-recaptcha") ||
            lower.contains("h-captcha")
    }

    /**
     * Loads a URL inside a real Android WebView to follow redirects (e.g. bit.ly -> animevietsub.sale),
     * pass Cloudflare JS/Turnstile verification, sync cf_clearance cookies to CookieManager,
     * and return (html, finalUrl).
     */
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun fetchHtml(targetUrl: String, timeoutMs: Long = 28_000L): Pair<String, String> = mutex.withLock {
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val activity = activityRef?.get()?.takeIf { !it.isFinishing && !it.isDestroyed }
                val ctx = activity ?: appContext
                if (ctx == null) {
                    if (cont.isActive) cont.resume("" to targetUrl)
                    return@suspendCancellableCoroutine
                }

                var finished = false
                var webView: WebView? = null
                var hiddenHolder: FrameLayout? = null
                var verifyDialog: Dialog? = null
                val startTime = System.currentTimeMillis()

                fun cleanupAndResume(html: String, finalUrl: String) {
                    if (finished) return
                    finished = true
                    mainHandler.removeCallbacksAndMessages(cont)
                    try {
                        CookieManager.getInstance().flush()
                    } catch (_: Throwable) {}
                    try {
                        verifyDialog?.dismiss()
                    } catch (_: Throwable) {}
                    verifyDialog = null
                    try {
                        webView?.stopLoading()
                        (webView?.parent as? ViewGroup)?.removeView(webView)
                        webView?.destroy()
                    } catch (_: Throwable) {}
                    webView = null
                    try {
                        (hiddenHolder?.parent as? ViewGroup)?.removeView(hiddenHolder)
                    } catch (_: Throwable) {}
                    hiddenHolder = null
                    if (cont.isActive) {
                        cont.resume(html to finalUrl)
                    }
                }

                try {
                    val wv = WebView(ctx)
                    webView = wv
                    val cm = CookieManager.getInstance()
                    cm.setAcceptCookie(true)
                    cm.setAcceptThirdPartyCookies(wv, true)

                    wv.settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        databaseEnabled = true
                        loadsImagesAutomatically = true
                        mediaPlaybackRequiresUserGesture = false
                        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        userAgentString = userAgent
                    }

                    // Attach invisibly to Activity decorView with 390x720 internal layout size so
                    // Cloudflare Turnstile getBoundingClientRect() sees a valid viewport
                    if (activity != null) {
                        val decor = activity.window?.decorView as? ViewGroup
                        if (decor != null) {
                            val holder = FrameLayout(activity).apply {
                                alpha = 0.01f
                                isFocusable = false
                                isClickable = false
                                clipChildren = true
                                layoutParams = FrameLayout.LayoutParams(1, 1, Gravity.BOTTOM or Gravity.END)
                            }
                            holder.addView(
                                wv,
                                FrameLayout.LayoutParams(
                                    UiHelper.dp(activity, 390),
                                    UiHelper.dp(activity, 720)
                                )
                            )
                            decor.addView(holder)
                            hiddenHolder = holder
                        }
                    }

                    wv.webChromeClient = object : WebChromeClient() {
                        override fun onJsAlert(
                            view: WebView?,
                            url: String?,
                            message: String?,
                            result: JsResult?
                        ): Boolean {
                            result?.confirm()
                            return true
                        }
                    }

                    wv.webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): Boolean {
                            val reqUrl = request?.url?.toString().orEmpty()
                            if (reqUrl.isBlank()) return false
                            val host = request?.url?.host?.lowercase().orEmpty()
                            val allowed = host.contains("animevietsub") ||
                                host.contains("bit.ly") ||
                                host.contains("cloudflare")
                            return !allowed
                        }
                    }

                    val inspectJs = """
                        (function() {
                          try {
                            var visible = function(e) {
                              var r = e.getBoundingClientRect();
                              var s = window.getComputedStyle(e);
                              return r.width > 10 && r.height > 10 && s.display !== 'none' && s.visibility !== 'hidden';
                            };
                            var sel = 'iframe[src*="challenges.cloudflare.com"],iframe[src*="recaptcha"],iframe[src*="hcaptcha"],.cf-turnstile,.g-recaptcha,.h-captcha,#challenge-stage input[type="checkbox"]';
                            var interactive = Array.prototype.some.call(document.querySelectorAll(sel), visible);
                            var html = document.documentElement ? document.documentElement.outerHTML : '';
                            return JSON.stringify({
                              href: window.location.href || '',
                              title: document.title || '',
                              interactive: interactive,
                              html: html
                            });
                          } catch(e) {
                            return '';
                          }
                        })();
                    """.trimIndent()

                    fun schedulePoll(delayMs: Long) {
                        if (finished) return
                        mainHandler.postAtTime({
                            if (finished) return@postAtTime
                            val elapsed = System.currentTimeMillis() - startTime
                            if (elapsed > timeoutMs) {
                                val currentUrl = wv.url ?: targetUrl
                                cleanupAndResume("", currentUrl)
                                return@postAtTime
                            }

                            wv.evaluateJavascript(inspectJs) { rawResult ->
                                if (finished) return@evaluateJavascript
                                try {
                                    if (!rawResult.isNullOrBlank() && rawResult != "null" && rawResult != "\"\"") {
                                        val jsonStr = org.json.JSONTokener(rawResult).nextValue() as? String
                                        if (!jsonStr.isNullOrBlank()) {
                                            val obj = JSONObject(jsonStr)
                                            val href = obj.optString("href").ifBlank { wv.url ?: targetUrl }
                                            val html = obj.optString("html")
                                            val interactive = obj.optBoolean("interactive", false)
                                            val isBitly = href.contains("bit.ly", ignoreCase = true)

                                            if (!isBitly && html.length > 400 && !containsVerification(html)) {
                                                val hasMarkers = Regex(
                                                    """(?is)(?:TPost|ml-item|film-item|movie-item|btn-episode|filmInfo\.filmID|Description|MovieList)"""
                                                ).containsMatchIn(html)
                                                if (hasMarkers || elapsed > 3500L) {
                                                    cleanupAndResume(html, href)
                                                    return@evaluateJavascript
                                                }
                                            }

                                            // Only show verification dialog if an interactive challenge checkbox remains unsolved after 8.0s
                                            val needsChallengeUi = interactive && elapsed > 8000L
                                            if (needsChallengeUi && verifyDialog == null && activity != null && !activity.isFinishing) {
                                                wv.settings.loadsImagesAutomatically = true
                                                (wv.parent as? ViewGroup)?.removeView(wv)
                                                val dlg = Dialog(activity).apply {
                                                    requestWindowFeature(Window.FEATURE_NO_TITLE)
                                                    setCancelable(true)
                                                    setOnCancelListener {
                                                        cleanupAndResume("", wv.url ?: targetUrl)
                                                    }
                                                }
                                                val box = LinearLayout(activity).apply {
                                                    orientation = LinearLayout.VERTICAL
                                                    setBackgroundColor(UiHelper.COLOR_SIDEBAR)
                                                    setPadding(
                                                        UiHelper.dp(activity, 16),
                                                        UiHelper.dp(activity, 14),
                                                        UiHelper.dp(activity, 16),
                                                        UiHelper.dp(activity, 16)
                                                    )
                                                }
                                                val headerRow = LinearLayout(activity).apply {
                                                    orientation = LinearLayout.HORIZONTAL
                                                    gravity = Gravity.CENTER_VERTICAL
                                                    setPadding(0, 0, 0, UiHelper.dp(activity, 10))
                                                }
                                                val titleTv = TextView(activity).apply {
                                                    text = "Đang xác minh kết nối AnimeVietsub…"
                                                    setTextColor(Color.WHITE)
                                                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                                                    typeface = Typeface.DEFAULT_BOLD
                                                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                                                }
                                                val closeBtn = TextView(activity).apply {
                                                    text = "Đóng"
                                                    setTextColor(Color.WHITE)
                                                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                                                    typeface = Typeface.DEFAULT_BOLD
                                                    setPadding(
                                                        UiHelper.dp(activity, 14),
                                                        UiHelper.dp(activity, 6),
                                                        UiHelper.dp(activity, 14),
                                                        UiHelper.dp(activity, 6)
                                                    )
                                                }
                                                UiHelper.makeFocusableButton(closeBtn, normalBgColor = UiHelper.COLOR_RED, radiusDp = 12) {
                                                    cleanupAndResume("", wv.url ?: targetUrl)
                                                }
                                                headerRow.addView(titleTv)
                                                headerRow.addView(closeBtn)
                                                box.addView(headerRow)
                                                box.addView(
                                                    wv,
                                                    LinearLayout.LayoutParams(
                                                        UiHelper.dp(activity, 520),
                                                        UiHelper.dp(activity, 380)
                                                    )
                                                )
                                                dlg.setContentView(box)
                                                dlg.show()
                                                verifyDialog = dlg
                                                wv.requestFocus()
                                            }
                                        }
                                    }
                                } catch (_: Throwable) {}
                                schedulePoll(650L)
                            }
                        }, cont, android.os.SystemClock.uptimeMillis() + delayMs)
                    }

                    wv.loadUrl(targetUrl)
                    schedulePoll(750L)

                    cont.invokeOnCancellation {
                        mainHandler.post { cleanupAndResume("", targetUrl) }
                    }
                } catch (_: Throwable) {
                    cleanupAndResume("", targetUrl)
                }
            }
        }
    }

    /**
     * Executes the exact iOS PlayerView.swift directPlayerHTML inside a hidden WebView
     * on the AnimeVietsub origin so fetch('/ajax/player', {credentials:'include'})
     * uses the browser's exact Cloudflare cookies & TLS session.
     */
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun resolveAnimeAjaxInWebView(
        episodeUrl: String,
        filmId: String,
        hash: String,
        timeoutMs: Long = 11_000L
    ): ResolvedPlayback? = mutex.withLock {
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val activity = activityRef?.get()?.takeIf { !it.isFinishing && !it.isDestroyed }
                val ctx = activity ?: appContext
                if (ctx == null) {
                    if (cont.isActive) cont.resume(null)
                    return@suspendCancellableCoroutine
                }

                var finished = false
                var webView: WebView? = null
                val origin = try {
                    val u = URL(episodeUrl)
                    "${u.protocol}://${u.host}"
                } catch (_: Throwable) {
                    episodeUrl
                }

                fun cleanup(result: ResolvedPlayback?) {
                    if (finished) return
                    finished = true
                    mainHandler.removeCallbacksAndMessages(cont)
                    try {
                        webView?.stopLoading()
                        (webView?.parent as? ViewGroup)?.removeView(webView)
                        webView?.destroy()
                    } catch (_: Throwable) {}
                    webView = null
                    if (cont.isActive) {
                        cont.resume(result)
                    }
                }

                try {
                    val wv = WebView(ctx)
                    webView = wv
                    val cm = CookieManager.getInstance()
                    cm.setAcceptCookie(true)
                    cm.setAcceptThirdPartyCookies(wv, true)

                    wv.settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        userAgentString = userAgent
                        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    }

                    wv.addJavascriptInterface(object {
                        @JavascriptInterface
                        fun onResolved(kind: String, resolvedUrl: String) {
                            mainHandler.post {
                                if (resolvedUrl.startsWith("http", ignoreCase = true)) {
                                    cleanup(
                                        ResolvedPlayback(
                                            url = resolvedUrl,
                                            headers = mapOf(
                                                "Referer" to "$origin/",
                                                "Origin" to origin,
                                                "User-Agent" to userAgent
                                            ),
                                            isWeb = kind == "iframe",
                                            webUrl = episodeUrl
                                        )
                                    )
                                } else {
                                    cleanup(null)
                                }
                            }
                        }

                        @JavascriptInterface
                        fun onError(msg: String) {
                            mainHandler.post { cleanup(null) }
                        }
                    }, "AnPinimeBridge")

                    val hash64 = Base64.encodeToString(hash.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                    val film64 = Base64.encodeToString(filmId.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                    val key64 = Base64.encodeToString(
                        "dm_thang_suc_vat_get_link_an_dbt".toByteArray(Charsets.UTF_8),
                        Base64.NO_WRAP
                    )

                    val html = """
                        <!doctype html><html><head><meta name="viewport" content="width=device-width">
                        <script src="/js/pako.min.js"></script></head><body><script>
                        function bytes(s){return Uint8Array.from(atob(s),c=>c.charCodeAt(0));}
                        function source(data){
                          function item(v){return typeof v==='string'?v:(v&&(v.file||v.src||v.url));}
                          try {
                            var j=JSON.parse(data), first=j.sources&&j.sources[0], nested=j.player&&j.player.sources&&j.player.sources[0];
                            var direct=item(first)||item(nested)||item(j.data)||item(j.link)||item(j.file)||item(j.url);
                            if(direct)return {kind:/\.(?:m3u8|mp4)(?:$|[?#])/i.test(direct)?'video':'iframe',url:direct};
                          } catch(e) {}
                          var normalized=data.replace(/\\\//g,'/');
                          var m=normalized.match(/https?:\/\/[^\s"'<>]+\.(?:m3u8|mp4)(?:[^\s"'<>]*)?/i);
                          if(m)return {kind:'video',url:m[0]};
                          var u=normalized.match(/https?:\/\/[^\s"'<>]+/i);
                          return u&&{kind:'iframe',url:u[0]};
                        }
                        async function start(){
                          try {
                            if(typeof pako==='undefined'){
                              await new Promise((ok,no)=>{
                                var s=document.createElement('script');
                                s.src='https://cdnjs.cloudflare.com/ajax/libs/pako/2.1.0/pako.min.js';
                                s.onload=ok; s.onerror=no; document.head.appendChild(s);
                              });
                            }
                            var hash=atob('$hash64'), id=atob('$film64'), keyText=atob('$key64');
                            var digest=await crypto.subtle.digest('SHA-256',new TextEncoder().encode(keyText));
                            var key=await crypto.subtle.importKey('raw',digest,{name:'AES-CBC'},false,['decrypt']);
                            var form=new FormData(); form.append('link',hash); form.append('id',id);
                            var response=await fetch('/ajax/player',{method:'POST',headers:{'X-Requested-With':'XMLHttpRequest'},body:form,credentials:'include'});
                            if(!response.ok) throw Error('HTTP '+response.status);
                            var value=await response.text(), encrypted=value;
                            try { var json=JSON.parse(value); encrypted=json.data||json.link||json.html||value; } catch(e) {}
                            encrypted=encrypted.replace(/-/g,'+').replace(/_/g,'/'); while(encrypted.length%4)encrypted+='=';
                            var raw=bytes(encrypted);
                            var clear=await crypto.subtle.decrypt({name:'AES-CBC',iv:raw.slice(0,16)},key,raw.slice(16));
                            var text; try{text=pako.inflateRaw(new Uint8Array(clear),{to:'string'});}catch(e){text=new TextDecoder().decode(clear);}
                            var found=source(text); if(!found||!found.url) throw Error('No video');
                            var absolute=new URL(found.url,location.href).href;
                            AnPinimeBridge.onResolved(found.kind, absolute);
                          } catch(e) {
                            AnPinimeBridge.onError(String(e));
                          }
                        }
                        start();
                        </script></body></html>
                    """.trimIndent()

                    wv.loadDataWithBaseURL(episodeUrl, html, "text/html", "UTF-8", episodeUrl)

                    mainHandler.postAtTime({
                        cleanup(null)
                    }, cont, android.os.SystemClock.uptimeMillis() + timeoutMs)

                    cont.invokeOnCancellation {
                        mainHandler.post { cleanup(null) }
                    }
                } catch (_: Throwable) {
                    cleanup(null)
                }
            }
        }
    }
}
