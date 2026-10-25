package com.anpinime.projector

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
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

    private const val PREFS_COOKIES = "anpinime_cookies"

    @Volatile
    var userAgent: String =
        "Mozilla/5.0 (Linux; Android 12; Pixel 7 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
        private set

    private var activityRef: WeakReference<Activity>? = null
    private var appContext: Context? = null
    private val mutex = Mutex()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var cookiesInitialized = false

    fun initCookies(context: Context) {
        if (cookiesInitialized) return
        cookiesInitialized = true
        try {
            val cm = CookieManager.getInstance()
            cm.setAcceptCookie(true)
            val prefs = context.getSharedPreferences(PREFS_COOKIES, Context.MODE_PRIVATE)
            val allCookies = prefs.all
            for ((url, cookieValue) in allCookies) {
                if (cookieValue is String && cookieValue.isNotBlank()) {
                    for (token in cookieValue.split(";")) {
                        val trimmed = token.trim()
                        if (trimmed.isNotBlank()) {
                            cm.setCookie(url, trimmed)
                        }
                    }
                }
            }
            cm.flush()
        } catch (_: Throwable) {}
    }

    fun persistCookie(url: String, cookieHeader: String) {
        val ctx = appContext ?: activityRef?.get()?.applicationContext ?: return
        try {
            val prefs = ctx.getSharedPreferences(PREFS_COOKIES, Context.MODE_PRIVATE)
            prefs.edit().putString(url, cookieHeader).apply()
        } catch (_: Throwable) {}
    }

    fun registerActivity(activity: Activity) {
        activityRef = WeakReference(activity)
        appContext = activity.applicationContext
        initCookies(activity.applicationContext)
        try {
            val rawUa = WebSettings.getDefaultUserAgent(activity)
            if (!rawUa.isNullOrBlank()) {
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
            val cm = CookieManager.getInstance()
            var cookie = cm.getCookie(url)?.takeIf { it.isNotBlank() }
            if (cookie.isNullOrBlank()) {
                val ctx = appContext ?: activityRef?.get()?.applicationContext
                val prefs = ctx?.getSharedPreferences(PREFS_COOKIES, Context.MODE_PRIVATE)
                cookie = prefs?.getString(url, null)?.takeIf { it.isNotBlank() }
                if (cookie != null) {
                    cm.setCookie(url, cookie)
                    cm.flush()
                }
            }
            cookie
        } catch (_: Throwable) {
            null
        }
    }

    fun saveSetCookieHeaders(url: String, headers: Map<String, List<String>>) {
        try {
            val cm = CookieManager.getInstance()
            cm.setAcceptCookie(true)
            var hasNew = false
            for ((k, values) in headers) {
                if (k != null && k.equals("Set-Cookie", ignoreCase = true)) {
                    for (v in values) {
                        cm.setCookie(url, v)
                        hasNew = true
                    }
                }
            }
            if (hasNew) {
                cm.flush()
                val current = cm.getCookie(url)
                if (!current.isNullOrBlank()) {
                    persistCookie(url, current)
                }
            }
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
                var hasDispatchedTouch = false
                var webView: WebView? = null
                var hiddenHolder: FrameLayout? = null
                var verifyDialog: Dialog? = null
                val startTime = System.currentTimeMillis()

                fun cleanupAndResume(html: String, finalUrl: String) {
                    if (finished) return
                    finished = true
                    mainHandler.removeCallbacksAndMessages(cont)
                    try {
                        val cm = CookieManager.getInstance()
                        cm.flush()
                        val cookie = cm.getCookie(finalUrl)
                        if (!cookie.isNullOrBlank()) {
                            persistCookie(finalUrl, cookie)
                            try {
                                val u = URL(finalUrl)
                                persistCookie("${u.protocol}://${u.host}/", cookie)
                            } catch (_: Throwable) {}
                        }
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

                    // Attach invisibly BEHIND the activity's opaque black UI with MATCH_PARENT size so
                    // Chromium renders at full 1080p/720p without throttling and Turnstile gets a valid visible viewport
                    if (activity != null) {
                        val decor = activity.window?.decorView as? ViewGroup
                        if (decor != null) {
                            val holder = FrameLayout(activity).apply {
                                alpha = 1.0f
                                visibility = View.VISIBLE
                                isFocusable = false
                                isClickable = false
                                layoutParams = FrameLayout.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT
                                )
                            }
                            holder.addView(
                                wv,
                                FrameLayout.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT
                                )
                            )
                            // Add at index 0 so it stays underneath all existing activity layouts
                            decor.addView(holder, 0)
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
                            // CRITICAL: Always allow sub-frames (Cloudflare Turnstile iframe, captchas, worker scripts)
                            if (request != null && !request.isForMainFrame) {
                                return false
                            }
                            val reqUrl = request?.url?.toString().orEmpty()
                            if (reqUrl.isBlank()) return false
                            val host = request?.url?.host?.lowercase().orEmpty()
                            val allowed = host == URL(targetUrl).host || host.contains("animevietsub") ||
                                host.contains("bit.ly") ||
                                host.contains("cloudflare") ||
                                host.contains("challenges.cloudflare.com")
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
                            var tf = document.querySelector('iframe[src*="challenges.cloudflare.com"],iframe[src*="turnstile"]');
                            var touchBox = null;
                            if (tf && visible(tf)) {
                              var r = tf.getBoundingClientRect();
                              touchBox = {
                                x: Math.round(r.left + Math.min(35, r.width / 2)),
                                y: Math.round(r.top + (r.height / 2))
                              };
                            }
                            var sel = 'iframe[src*="challenges.cloudflare.com"],iframe[src*="recaptcha"],iframe[src*="hcaptcha"],.cf-turnstile,.g-recaptcha,.h-captcha,#challenge-stage input[type="checkbox"]';
                            var interactive = Array.prototype.some.call(document.querySelectorAll(sel), visible);
                            var html = document.documentElement ? document.documentElement.outerHTML : '';
                            return JSON.stringify({
                              href: window.location.href || '',
                              title: document.title || '',
                              interactive: interactive,
                              touchBox: touchBox,
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

                                            // Auto-dispatch touch event to Cloudflare Turnstile checkbox after 1.2s
                                            val touchObj = obj.optJSONObject("touchBox")
                                            if (touchObj != null && !hasDispatchedTouch && elapsed > 1200L) {
                                                val tx = touchObj.optDouble("x", -1.0).toFloat()
                                                val ty = touchObj.optDouble("y", -1.0).toFloat()
                                                if (tx > 0f && ty > 0f) {
                                                    hasDispatchedTouch = true
                                                    val nowTime = SystemClock.uptimeMillis()
                                                    val downEv = MotionEvent.obtain(nowTime, nowTime, MotionEvent.ACTION_DOWN, tx, ty, 0)
                                                    val upEv = MotionEvent.obtain(nowTime, nowTime + 60L, MotionEvent.ACTION_UP, tx, ty, 0)
                                                    wv.dispatchTouchEvent(downEv)
                                                    wv.dispatchTouchEvent(upEv)
                                                    downEv.recycle()
                                                    upEv.recycle()
                                                }
                                            }

                                            if (!isBitly && html.length > 400 && !containsVerification(html)) {
                                                val hasMarkers = Regex(
                                                    """(?is)(?:TPost|ml-item|film-item|movie-item|btn-episode|filmInfo\.filmID|Description|MovieList)"""
                                                ).containsMatchIn(html)
                                                if (hasMarkers || elapsed > 3500L) {
                                                    cleanupAndResume(html, href)
                                                    return@evaluateJavascript
                                                }
                                            }

                                            // Only show verification dialog if an interactive challenge checkbox remains unsolved after 9.0s
                                            val needsChallengeUi = interactive && elapsed > 9000L
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
                                                    text = "Đang xác minh kết nối ${URL(targetUrl).host}…"
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
     * Fetches on the AnimeVietsub origin using browser cookies, then sends the payload
     * to the native AES/DEFLATE decoder. No WebCrypto or remote pako script is needed.
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
                        fun onPayload(payload: String) {
                            // JavascriptInterface callbacks run off the UI thread. Decode with
                            // Android crypto so older WebViews need neither WebCrypto nor pako.
                            val result = try {
                                CatalogService.decryptAnimePlayerPayload(payload, origin, episodeUrl)
                            } catch (_: Exception) { null }
                            mainHandler.post { cleanup(result) }
                        }

                        @JavascriptInterface
                        fun onError(msg: String) {
                            mainHandler.post { cleanup(null) }
                        }
                    }, "AnPinimeBridge")

                    val hash64 = Base64.encodeToString(hash.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                    val film64 = Base64.encodeToString(filmId.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

                    val html = """
                        <!doctype html><html><head><meta name="viewport" content="width=device-width">
                        </head><body><script>
                        var form = new FormData();
                        form.append('link', atob('$hash64')); form.append('id', atob('$film64'));
                        fetch('/ajax/player', {method:'POST', headers:{'X-Requested-With':'XMLHttpRequest'},
                          body:form, credentials:'include'})
                          .then(function(response){ if(!response.ok) throw Error('HTTP '+response.status); return response.text(); })
                          .then(function(payload){ AnPinimeBridge.onPayload(payload); })
                          .catch(function(error){ AnPinimeBridge.onError(String(error)); });
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
