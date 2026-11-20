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
    const val MIN_AETHER_CHROME_MAJOR = 80

    data class WebViewCompatibility(
        val isSupported: Boolean,
        val chromeMajorVersion: Int,
        val message: String = ""
    )

    fun parseChromeMajorVersion(userAgentString: String?): Int {
        if (userAgentString.isNullOrBlank()) return 0
        val match = Regex("""Chrome/(\d+)""", RegexOption.IGNORE_CASE).find(userAgentString)
        return match?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
    }

    fun checkAetherWebViewCompatibility(context: Context): WebViewCompatibility {
        val rawUa = try {
            WebSettings.getDefaultUserAgent(context)
        } catch (_: Throwable) {
            null
        }
        if (rawUa.isNullOrBlank()) {
            return WebViewCompatibility(
                isSupported = false,
                chromeMajorVersion = 0,
                message = "Không tìm thấy Android System WebView trên thiết bị để chạy trình phát Thúy An.\nVui lòng cài đặt/bật Android System WebView hoặc đổi sang Tú Nam / Cốm."
            )
        }
        val major = parseChromeMajorVersion(rawUa)
        if (major in 1 until MIN_AETHER_CHROME_MAJOR) {
            return WebViewCompatibility(
                isSupported = false,
                chromeMajorVersion = major,
                message = "WebView hệ thống trên máy chiếu quá cũ (Chrome $major, cần >= $MIN_AETHER_CHROME_MAJOR) nên không tương thích với JavaScript của Thúy An.\nVui lòng cập nhật Android System WebView hoặc đổi sang Tú Nam / Cốm."
            )
        }
        return WebViewCompatibility(
            isSupported = true,
            chromeMajorVersion = major,
            message = ""
        )
    }

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

    private fun initUserAgent(context: Context) {
        try {
            val rawUa = WebSettings.getDefaultUserAgent(context)
            if (!rawUa.isNullOrBlank()) {
                // Clean "; wv" and "Version/4.0" so Cloudflare treats WebView & OkHttp identically as Chrome Mobile
                userAgent = rawUa
                    .replace("; wv", "")
                    .replace(Regex("""Version/\d+\.\d+\s*"""), "")
                    .trim()
            }
        } catch (_: Throwable) {}
    }

    private fun setMultiCookies(cm: CookieManager, url: String, rawCookieString: String) {
        for (token in rawCookieString.split(";")) {
            val trimmed = token.trim()
            if (trimmed.isNotBlank() && trimmed.contains("=")) {
                val key = trimmed.substringBefore("=").trim().lowercase()
                if (key !in setOf("path", "domain", "expires", "max-age", "secure", "httponly", "samesite")) {
                    cm.setCookie(url, "$trimmed; Path=/")
                }
            }
        }
    }

    fun init(context: Context) {
        val app = context.applicationContext
        appContext = app
        initCookies(app)
        initUserAgent(app)
    }

    fun registerActivity(activity: Activity) {
        activityRef = WeakReference(activity)
        appContext = activity.applicationContext
        initCookies(activity.applicationContext)
        initUserAgent(activity)
    }

    fun persistOkHttpCookie(url: String, cookiePair: String) {
        if (cookiePair.isBlank()) return
        try {
            val cm = CookieManager.getInstance()
            cm.setAcceptCookie(true)
            cm.setCookie(url, cookiePair)
            cm.flush()
            val merged = cm.getCookie(url)
            if (!merged.isNullOrBlank()) {
                persistCookie(url, merged)
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
                    setMultiCookies(cm, url, cookie)
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
            "đang kiểm tra",
            "attention required",
            "verify you are human",
            "checking your browser",
            "one more step",
            "ddos-guard",
            "access denied",
            "security check",
            "403 forbidden"
        )
        val hasChallengeTitle = challengeTitles.any { title.contains(it) }
        // Match iOS HTMLParser.containsVerification: a normal AnimeVietsub page can contain comment/login
        // captcha scripts (.cf-turnstile / .captcha-placeholder) without asking for site verification.
        val hasContent = Regex("""class=["'][^"']*(?:tpost|ml-item|film-item|movie-item|btn-episode|movielist)[^"']*["']""")
            .containsMatchIn(lower) ||
            lower.contains("filminfo.filmid") ||
            lower.contains("__sveltekit_") ||
            lower.contains("href=\"/watch/") ||
            (Regex("""(?is)<h1\b[^>]*>""").containsMatchIn(lower) && lower.contains("og:image") &&
                (lower.contains("xem phim") || lower.contains("tập")))
        if (hasContent && !hasChallengeTitle) return false
        return hasChallengeTitle ||
            lower.contains("id=\"challenge-form\"") ||
            lower.contains("id='challenge-form'") ||
            lower.contains("_cf_chl_opt") ||
            lower.contains("/cdn-cgi/challenge-platform/") ||
            lower.contains("challenge-error-text")
    }

    /**
     * Loads a URL inside a real Android WebView to follow redirects (e.g. bit.ly -> animevietsub.best),
     * pass Cloudflare JS/Turnstile verification, sync cf_clearance cookies to CookieManager,
     * and return (html, finalUrl).
     */
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun fetchHtml(
        targetUrl: String,
        timeoutMs: Long = 28_000L,
        waitForEpisodes: Boolean = false
    ): Pair<String, String> = mutex.withLock {
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
                var lastEpCount = -1
                var lastEpChangeAt = System.currentTimeMillis()
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

                    val stealthChromeJs = """
                        (function() {
                          try {
                            Object.defineProperty(navigator, 'webdriver', { get: function() { return false; }, configurable: true });
                            if (!window.chrome) { window.chrome = { runtime: {}, app: {} }; }
                            if (navigator.userAgentData && navigator.userAgentData.brands) {
                              var cleanBrands = navigator.userAgentData.brands.map(function(b) {
                                return {
                                  brand: (b.brand === 'Android WebView') ? 'Google Chrome' : b.brand,
                                  version: b.version
                                };
                              });
                              Object.defineProperty(navigator.userAgentData, 'brands', { get: function() { return cleanBrands; } });
                            }
                          } catch(e) {}
                        })();
                    """.trimIndent()

                    wv.webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                            super.onPageStarted(view, url, favicon)
                            view?.evaluateJavascript(stealthChromeJs, null)
                        }

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
                                host.contains("miruro") ||
                                host.contains("anivault") ||
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
                            var sel = 'iframe[src*="challenges.cloudflare.com"],iframe[src*="turnstile"],iframe[src*="recaptcha"],iframe[src*="hcaptcha"],#challenge-stage input[type="checkbox"]';
                            var interactive = Array.prototype.some.call(document.querySelectorAll(sel), visible);
                            var title = document.title || '';
                            var hasRealContent = !!document.querySelector('.TPost, .ml-item, .film-item, .movie-item, .btn-episode, .MovieList, .list-episode, [data-hash], a[href*="/watch/"]') ||
                              !!(window.filmInfo && window.filmInfo.filmID);
                            var isChallengeTitle = /xác minh an toàn|xác minh bảo mật|just a moment|attention required|verify you are human|checking your browser/i.test(title);
                            var challengeDom = !hasRealContent && (
                              isChallengeTitle ||
                              !!(window._cf_chl_opt) ||
                              !!document.querySelector('#challenge-stage, #challenge-form, #cf-please-wait, .challenge-card')
                            );
                            var epCount = document.querySelectorAll('.list-episode a, .list_episode a, .episodes a, .episode-list a, a.btn-episode, [data-hash], [data-play]').length;
                            var html = '';
                            if (document.documentElement) {
                              html = document.documentElement.outerHTML
                                .replace(/<style[\s\S]*?<\/style>/gi, '')
                                .replace(/data:image\/[^"'\s)]+/gi, '')
                                .replace(/data:font\/[^"'\s)]+/gi, '');
                            }
                            return JSON.stringify({
                              href: window.location.href || '',
                              title: title,
                              interactive: interactive && !hasRealContent,
                              challengeDom: challengeDom,
                              epCount: epCount,
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
                            val effectiveTimeout = if (verifyDialog != null) maxOf(timeoutMs, 180_000L) else timeoutMs
                            if (elapsed > effectiveTimeout) {
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
                                            val challengeDom = obj.optBoolean("challengeDom", false)
                                            val epCount = obj.optInt("epCount", 0)
                                            if (epCount != lastEpCount) {
                                                lastEpCount = epCount
                                                lastEpChangeAt = System.currentTimeMillis()
                                            }
                                            val isBitly = href.contains("bit.ly", ignoreCase = true)
                                            val challengeActive = if (html.isNotBlank()) {
                                                containsVerification(html)
                                            } else {
                                                challengeDom
                                            }

                                            if (!isBitly && html.length > 400 && !challengeActive) {
                                                val hasMarkers = Regex(
                                                    """(?is)(?:TPost|ml-item|film-item|movie-item|btn-episode|filmInfo\.filmID|Description|MovieList|__sveltekit_|/watch/)"""
                                                ).containsMatchIn(html)
                                                val epStable = !waitForEpisodes ||
                                                    (lastEpCount > 0 && (System.currentTimeMillis() - lastEpChangeAt) >= 600L) ||
                                                    elapsed > 4500L
                                                if (epStable && (hasMarkers || elapsed > 2500L)) {
                                                    cleanupAndResume(html, href)
                                                    return@evaluateJavascript
                                                }
                                            }

                                            // Like iOS VerificationView.swift: keep silent background verification hidden;
                                            // only surface the WebView in-place (without detaching from Window) if interactive challenge remains after 8s.
                                            val needsChallengeUi = challengeActive && interactive && elapsed > 8000L
                                            if (needsChallengeUi && verifyDialog == null && activity != null && !activity.isFinishing) {
                                                val holder = hiddenHolder
                                                if (holder != null) {
                                                    // Keep WebView in the same Window so Turnstile iframe never resets on detach!
                                                    holder.setBackgroundColor(UiHelper.COLOR_SIDEBAR)
                                                    holder.isClickable = true
                                                    holder.isFocusable = true
                                                    val headerRow = LinearLayout(activity).apply {
                                                        orientation = LinearLayout.HORIZONTAL
                                                        gravity = Gravity.CENTER_VERTICAL
                                                        setBackgroundColor(UiHelper.COLOR_SIDEBAR)
                                                        setPadding(
                                                            UiHelper.dp(activity, 16),
                                                            UiHelper.dp(activity, 10),
                                                            UiHelper.dp(activity, 16),
                                                            UiHelper.dp(activity, 10)
                                                        )
                                                        layoutParams = FrameLayout.LayoutParams(
                                                            ViewGroup.LayoutParams.MATCH_PARENT,
                                                            UiHelper.dp(activity, 52),
                                                            Gravity.TOP
                                                        )
                                                    }
                                                    val titleTv = TextView(activity).apply {
                                                        text = "Xác minh kết nối ${runCatching { URL(href).host }.getOrDefault("Miruro")}…"
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
                                                    (wv.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                                                        lp.topMargin = UiHelper.dp(activity, 52)
                                                        wv.layoutParams = lp
                                                    }
                                                    holder.addView(headerRow)
                                                    holder.bringToFront()
                                                    wv.isFocusable = true
                                                    wv.isFocusableInTouchMode = true
                                                    wv.requestFocus()
                                                    // Use dummy non-shown Dialog marker so effectiveTimeout extends to 180s and we only surface once
                                                    verifyDialog = Dialog(activity)
                                                }
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
                        fetch('/ajax/player?v=2019a', {method:'POST', headers:{'X-Requested-With':'XMLHttpRequest'},
                          body:form, credentials:'include'})
                          .then(function(response){
                            if(!response.ok) return fetch('/ajax/player', {method:'POST', headers:{'X-Requested-With':'XMLHttpRequest'}, body:form, credentials:'include'});
                            return response;
                          })
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

    private var warmOriginWebView: WebView? = null
    private var warmOriginBase: String = ""
    private var warmOriginReady: Boolean = false
    private val warmPendingCallbacks = java.util.concurrent.ConcurrentHashMap<String, (Map<String, String>) -> Unit>()
    private val warmReqSeq = java.util.concurrent.atomic.AtomicInteger(0)

    private fun destroyWarmOriginWebView() {
        warmOriginReady = false
        warmOriginBase = ""
        val old = warmOriginWebView
        warmOriginWebView = null
        try {
            old?.stopLoading()
            (old?.parent as? ViewGroup)?.removeView(old)
            old?.destroy()
        } catch (_: Throwable) {}
    }

    /**
     * Executes a single same-origin GET fetch inside a warm reusable Android WebView using synced cookies.
     */
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun fetchTextInWebView(
        originUrl: String,
        targetPathOrUrl: String,
        timeoutMs: Long = 14_000L
    ): String? {
        val batch = fetchTextsBatchInWebView(originUrl, listOf(targetPathOrUrl), timeoutMs)
        return batch[targetPathOrUrl]
    }

    /**
     * Executes multiple same-origin GET fetches in parallel (Promise.all over HTTP/2) inside a
     * warm reusable Android WebView, avoiding repeated WebView creation/destruction overhead.
     */
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun fetchTextsBatchInWebView(
        originUrl: String,
        paths: List<String>,
        timeoutMs: Long = 16_000L
    ): Map<String, String> = mutex.withLock {
        if (paths.isEmpty()) return@withLock emptyMap()
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val ctx = appContext ?: activityRef?.get()?.applicationContext
                if (ctx == null) {
                    if (cont.isActive) cont.resume(emptyMap())
                    return@suspendCancellableCoroutine
                }

                var finished = false
                val baseOrigin = try {
                    val u = URL(originUrl)
                    "${u.protocol}://${u.host}/"
                } catch (_: Throwable) {
                    originUrl.trimEnd('/') + "/"
                }
                val reqId = "r${warmReqSeq.incrementAndGet()}"

                fun finish(result: Map<String, String>, invalidateWarm: Boolean = false) {
                    if (finished) return
                    finished = true
                    warmPendingCallbacks.remove(reqId)
                    mainHandler.removeCallbacksAndMessages(cont)
                    if (invalidateWarm) {
                        destroyWarmOriginWebView()
                    }
                    try {
                        val cm = CookieManager.getInstance()
                        cm.flush()
                        val cookie = cm.getCookie(baseOrigin)
                        if (!cookie.isNullOrBlank()) {
                            persistCookie(baseOrigin, cookie)
                        }
                    } catch (_: Throwable) {}
                    if (cont.isActive) {
                        cont.resume(result)
                    }
                }

                try {
                    val needInit = warmOriginWebView == null || !warmOriginReady ||
                        !warmOriginBase.equals(baseOrigin, ignoreCase = true)
                    if (needInit) {
                        destroyWarmOriginWebView()
                        val wv = WebView(ctx)
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
                            fun onBatchResult(id: String, jsonPayload: String) {
                                val parsed = LinkedHashMap<String, String>()
                                try {
                                    val obj = JSONObject(jsonPayload)
                                    val keys = obj.keys()
                                    while (keys.hasNext()) {
                                        val k = keys.next()
                                        val v = obj.optString(k)
                                        if (v.isNotBlank()) parsed[k] = v
                                    }
                                } catch (_: Throwable) {}
                                mainHandler.post {
                                    warmPendingCallbacks.remove(id)?.invoke(parsed)
                                }
                            }
                        }, "AnPinimeBatchBridge")

                        val shellHtml = """
                            <!doctype html><html><head><meta name="viewport" content="width=device-width"></head><body><script>
                            window.__anpinimeRunBatch = function(reqId, pathsB64) {
                              try {
                                var paths = JSON.parse(atob(pathsB64));
                                var out = {};
                                Promise.all(paths.map(function(p) {
                                  return fetch(p, {
                                    method: 'GET',
                                    credentials: 'include',
                                    headers: { 'Accept': 'application/json, text/sveltekit-data, */*' }
                                  }).then(function(r) {
                                    if (!r.ok) return '';
                                    return r.text();
                                  }).then(function(txt) {
                                    if (txt && !/just a moment|cf-browser-verification|_cf_chl_opt/i.test(txt.slice(0, 600))) {
                                      out[p] = txt;
                                    }
                                  }).catch(function() {});
                                })).then(function() {
                                  AnPinimeBatchBridge.onBatchResult(reqId, JSON.stringify(out));
                                }).catch(function() {
                                  AnPinimeBatchBridge.onBatchResult(reqId, '{}');
                                });
                              } catch (e) {
                                AnPinimeBatchBridge.onBatchResult(reqId, '{}');
                              }
                            };
                            </script></body></html>
                        """.trimIndent()

                        warmOriginWebView = wv
                        warmOriginBase = baseOrigin
                        warmOriginReady = false
                        wv.webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                warmOriginReady = true
                            }
                        }
                        wv.loadDataWithBaseURL(baseOrigin, shellHtml, "text/html", "UTF-8", baseOrigin)
                    }

                    val pathsJson = org.json.JSONArray(paths).toString()
                    val pathsB64 = Base64.encodeToString(pathsJson.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                    warmPendingCallbacks[reqId] = { result -> finish(result, invalidateWarm = false) }

                    fun dispatchJs(attempt: Int) {
                        if (finished) return
                        val wv = warmOriginWebView
                        if (wv == null) {
                            finish(emptyMap(), invalidateWarm = true)
                            return
                        }
                        if (!warmOriginReady && attempt < 25) {
                            mainHandler.postAtTime({ dispatchJs(attempt + 1) }, cont, SystemClock.uptimeMillis() + 60L)
                            return
                        }
                        wv.evaluateJavascript("window.__anpinimeRunBatch && window.__anpinimeRunBatch('$reqId', '$pathsB64');", null)
                    }

                    dispatchJs(0)

                    mainHandler.postAtTime({
                        finish(emptyMap(), invalidateWarm = true)
                    }, cont, SystemClock.uptimeMillis() + timeoutMs)

                    cont.invokeOnCancellation {
                        mainHandler.post { finish(emptyMap(), invalidateWarm = false) }
                    }
                } catch (_: Throwable) {
                    finish(emptyMap(), invalidateWarm = true)
                }
            }
        }
    }
}
