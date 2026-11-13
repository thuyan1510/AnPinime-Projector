package com.anpinime.projector

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.res.ColorStateList
import java.io.File
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Layout
import android.text.TextUtils
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.Tracks
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.ui.SubtitleView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.hls.DefaultHlsExtractorFactory
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.source.SingleSampleMediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import android.provider.OpenableColumns
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.net.URLEncoder
import java.util.Locale

@OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity() {

    companion object {
        private val aetherJsCache = ConcurrentHashMap<String, String>()
        private val aetherHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(12, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build()
        }
    }

    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var resolveJob: Job? = null
    private var translateSubJob: Job? = null
    private var subPrewarmJob: Job? = null
    private val VI_CUE_UNSET = "\u0000__UNSET__"
    @Volatile private var viLiveSubActive = false
    @Volatile private var viTranslationCompleted = false
    @Volatile private var viLiveCues: List<TimedSubtitleCue> = emptyList()
    private val viTranslatedMap = ConcurrentHashMap<Int, String>()
    @Volatile private var viLastRenderedCueText: String? = VI_CUE_UNSET
    @Volatile private var cachedSubKey: String = ""
    @Volatile private var cachedSubRawText: String = ""
    @Volatile private var translatedEpisodeKey: String = ""
    @Volatile private var selectedSourceSubUrl: String = ""
    @Volatile private var allowNativeWebHandoff: Boolean = true
    private val mainHandler = Handler(Looper.getMainLooper())
    private val attemptedStreamUrls = HashSet<String>()
    private val triedAetherScrapers = HashSet<String>()

    private var movieTitle: String = ""
    private lateinit var currentEpisode: Episode
    private var allEpisodes: List<Episode> = emptyList()

    private var exoPlayer: ExoPlayer? = null
    private var preferSoftwareAudio = false
    private var lastNativeUrl = ""
    private var lastNativeHeaders: Map<String, String> = emptyMap()
    private var lastMediaKind: StreamMediaKind = StreamMediaKind.UNKNOWN
    private var lastSubtitles: List<ExternalSubtitleTrack> = emptyList()
    private var subtitleScaleMode = 2 // 0 = Nhỏ (18sp), 1 = Vừa (22sp), 2 = Lớn (26sp - Chuẩn máy chiếu), 3 = Rất lớn (32sp)
    private var trackSelector: DefaultTrackSelector? = null
    private var filmRelay: FilmMediaRelay? = null
    private var fallbackWebView: WebView? = null
    private var lastWebUrl: String = ""
    private var retryCount = 0
    private var currentQualityMode = 0 // 0 = Auto 1080p, 1 = 720p (Siêu mượt), 2 = 4K Gốc
    private var currentPlaybackSpeed = 1.0f

    private data class WebTrackItem(val id: String, val label: String, val selected: Boolean)
    private var webAudioTracks: List<WebTrackItem> = emptyList()
    private var webSubtitleTracks: List<WebTrackItem> = emptyList()
    private var webQualities: List<WebTrackItem> = emptyList()
    private var aetherMenuOpen = false
    private var aetherStatusNote: String = ""

    data class AetherProvider(
        val id: String,
        val name: String,
        val description: String = ""
    )

    private var currentAetherProviderId: String = "auto"
    private var availableAetherProviders: List<AetherProvider> = AetherSourceResolver.SERVERS.map {
        AetherProvider(it.id, it.name, it.description)
    }

    private lateinit var videoFrame: FrameLayout
    private lateinit var headlessWebContainer: FrameLayout
    private lateinit var surfaceView: SurfaceView
    private lateinit var subtitleView: SubtitleView
    private var scrubbing = false
    private var pendingSeekTargetMs: Long = -1L
    private var consecutiveSeekKeyCount: Int = 0
    private var lastSeekInputAtMs: Long = 0L
    private var seekGestureKey = -1
    private var playbackGeneration = 0
    private var webPositionMs = 0L
    private var webDurationMs = 0L
    private var webPaused = true
    private var openingFrame = false
    private val visitedFrames = HashSet<String>()
    private lateinit var loadingOverlay: LinearLayout
    private lateinit var loadingProviderBadge: TextView
    private lateinit var loadingText: TextView
    private lateinit var loadingProgressBar: ProgressBar
    private lateinit var loadingHintText: TextView
    private lateinit var osdContainer: FrameLayout
    private lateinit var topTitleText: TextView
    private lateinit var topServerText: TextView
    private lateinit var topTranslateBadge: TextView
    private lateinit var topQualityBadge: TextView
    private lateinit var controlHintText: TextView
    private lateinit var seekPreviewBadge: TextView
    private lateinit var gestureFeedbackPill: TextView
    private lateinit var timeCurrentText: TextView
    private lateinit var timeTotalText: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var playPauseBtn: ImageView
    private lateinit var rewindBtn: ImageView
    private lateinit var forwardBtn: ImageView
    private lateinit var nextEpBtn: ImageView
    private lateinit var episodeBtn: ImageView
    private lateinit var serverBtn: ImageView
    private lateinit var subtitleBtn: ImageView
    private lateinit var aetherTranslateBtn: ImageView
    private lateinit var audioBtn: ImageView
    private lateinit var aspectBtn: ImageView
    private lateinit var settingsBtn: ImageView
    private lateinit var sidePanelBackdrop: View
    private lateinit var sidePanelContainer: LinearLayout
    private lateinit var panelTitleText: TextView
    private lateinit var panelCountBadge: TextView
    private lateinit var panelSwitchServerBtn: TextView
    private lateinit var episodeDrawer: ScrollView
    private var pendingSelectSubtitleUrl: String = ""
    private val pickLocalSubtitleLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            importLocalSubtitleUri(uri)
        } else {
            scheduleHideOsd()
        }
    }
    private lateinit var episodeDrawerRow: LinearLayout

    private var currentAspectMode = Phim4KPlayerUi.AspectMode.FIT
    private var lastVideoW = 0
    private var lastVideoH = 0
    private var sidePanelVisible = false
    private var currentFocusedHint: String = ""
    private var osdVisible = true
    private var selectionDialog: Dialog? = null

    private fun isAetherEpisode(ep: Episode = currentEpisode): Boolean {
        return ep.source == ContentSource.AETHER || ep.playbackKind.startsWith("aether", ignoreCase = true)
    }

    private val commitPendingSeekRunnable = Runnable {
        commitPendingSeek()
    }

    private val hideGestureFeedbackRunnable = Runnable {
        if (::gestureFeedbackPill.isInitialized) {
            gestureFeedbackPill.animate().alpha(0f).setDuration(220).withEndAction {
                gestureFeedbackPill.visibility = View.GONE
            }.start()
        }
    }

    private val hideOsdRunnable = Runnable {
        if (selectionDialog == null && !scrubbing && pendingSeekTargetMs < 0L &&
            !sidePanelVisible &&
            (exoPlayer?.isPlaying == true || fallbackWebView != null)) {
            hideOsd()
        }
    }

    private val progressRunnable = object : Runnable {
        override fun run() {
            updateProgressUi()
            mainHandler.postDelayed(this, 1000L)
        }
    }

    private val subtitleTickRunnable = object : Runnable {
        override fun run() {
            if (!viLiveSubActive) return
            val player = exoPlayer
            if (player != null) {
                renderLiveSubtitleAt(player.currentPosition.coerceAtLeast(0L))
            }
            mainHandler.postDelayed(this, 80L)
        }
    }

    private fun renderLiveSubtitleAt(positionMs: Long, force: Boolean = false) {
        if (!viLiveSubActive || !::subtitleView.isInitialized) return
        val cueText = SubtitleTranslator.findActiveCueText(
            cues = viLiveCues,
            translatedMap = viTranslatedMap,
            positionMs = positionMs,
            fallbackToOriginal = viTranslationCompleted
        )
        if (force || cueText != viLastRenderedCueText) {
            viLastRenderedCueText = cueText
            if (cueText.isNullOrBlank()) {
                subtitleView.setCues(emptyList())
            } else {
                subtitleView.setCues(
                    listOf(
                        Cue.Builder()
                            .setText(cueText)
                            .setTextAlignment(Layout.Alignment.ALIGN_CENTER)
                            .setLineAnchor(Cue.ANCHOR_TYPE_END)
                            .setPosition(0.5f)
                            .setPositionAnchor(Cue.ANCHOR_TYPE_MIDDLE)
                            .setSize(0.92f)
                            .build()
                    )
                )
            }
        }
    }

    private fun stopLiveViSubtitles(clearView: Boolean = true) {
        viLiveSubActive = false
        viLastRenderedCueText = VI_CUE_UNSET
        mainHandler.removeCallbacks(subtitleTickRunnable)
        exoPlayer?.let { player ->
            player.trackSelectionParameters = player.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .build()
        }
        if (clearView && ::subtitleView.isInitialized) {
            subtitleView.setCues(emptyList())
        }
        if (::aetherTranslateBtn.isInitialized) {
            Phim4KPlayerUi.styleActiveIconButton(aetherTranslateBtn, this, isActive = false)
        }
        updateTranslateStatusUi(0, 0, completed = false)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CatalogService.init(this)
        WebViewHelper.registerActivity(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        applyImmersiveFullscreen()

        movieTitle = intent.getStringExtra("movie_title").orEmpty()
        val epRaw = intent.getStringExtra("episode_json").orEmpty()
        val allRaw = intent.getStringExtra("all_episodes_json").orEmpty()
        if (epRaw.isEmpty()) {
            finish()
            return
        }
        currentEpisode = Episode.fromJsonObject(JSONObject(epRaw))
        allEpisodes = Episode.fromJsonArray(allRaw)
            .filter { it.source == currentEpisode.source }.ifEmpty { listOf(currentEpisode) }

        buildUi()
        applySubtitleScale()
        startEpisodePlayback(currentEpisode, resetPosition = true)
        mainHandler.post(progressRunnable)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersiveFullscreen()
    }

    @Suppress("DEPRECATION")
    private fun applyImmersiveFullscreen() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_FULLSCREEN
            )
    }

    override fun onResume() {
        super.onResume()
        applyImmersiveFullscreen()
        WebViewHelper.registerActivity(this)
        fallbackWebView?.onResume()
    }

    override fun onStop() {
        super.onStop()
        exoPlayer?.pause()
        sendWebCommand("pause")
        fallbackWebView?.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacksAndMessages(null)
        resolveJob?.cancel()
        translateSubJob?.cancel()
        uiScope.cancel()
        selectionDialog?.dismiss()
        releasePlayer()
        destroyWebPlayer()
    }

    private fun releasePlayer(stopRelay: Boolean = true) {
        mainHandler.removeCallbacks(commitPendingSeekRunnable)
        subPrewarmJob?.cancel()
        translateSubJob?.cancel()
        viTranslationCompleted = false
        stopLiveViSubtitles(clearView = true)
        viLiveCues = emptyList()
        viTranslatedMap.clear()
        translatedEpisodeKey = ""
        cachedSubKey = ""
        cachedSubRawText = ""
        pendingSeekTargetMs = -1L
        consecutiveSeekKeyCount = 0
        try {
            if (stopRelay) filmRelay?.stop()
        } catch (_: Throwable) {}
        if (stopRelay) filmRelay = null
        if (::subtitleView.isInitialized) subtitleView.setCues(emptyList())
        val oldPlayer = exoPlayer
        exoPlayer = null
        trackSelector = null
        if (oldPlayer != null) {
            try {
                oldPlayer.clearVideoSurfaceView(surfaceView)
            } catch (_: Throwable) {}
            try {
                oldPlayer.release()
            } catch (_: Throwable) {}
        }
    }

    private fun destroyWebPlayer(force: Boolean = true) {
        if (!force && isAetherEpisode() && fallbackWebView != null) {
            // Keep the hidden WebView running in background for AI subtitle translation and fast failover
            fallbackWebView?.visibility = View.GONE
            fallbackWebView?.evaluateJavascript(
                "(function(){try{document.querySelectorAll('video,audio').forEach(function(m){m.pause();m.muted=true;m.removeAttribute('src');m.load();});}catch(e){}})();",
                null
            )
            return
        }
        aetherMenuOpen = false
        val wv = fallbackWebView ?: return
        fallbackWebView = null
        webAudioTracks = emptyList()
        webSubtitleTracks = emptyList()
        webQualities = emptyList()
        try {
            wv.evaluateJavascript(
                "(function(){try{document.querySelectorAll('video,audio').forEach(function(m){m.pause();m.removeAttribute('src');m.load();});}catch(e){}})();",
                null
            )
            wv.stopLoading()
            wv.loadUrl("about:blank")
            wv.clearHistory()
            (wv.parent as? ViewGroup)?.removeView(wv)
            wv.removeAllViews()
            wv.destroy()
        } catch (_: Throwable) {}
    }

    // =========================================================================
    // REMOTE CONTROL D-PAD & SYSTEM KEY HANDLING (100% Projector Safe)
    // =========================================================================

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode

        // Never intercept system power, volume, or home keys so projector remote always works
        if (keyCode == KeyEvent.KEYCODE_POWER ||
            keyCode == KeyEvent.KEYCODE_TV_POWER ||
            keyCode == KeyEvent.KEYCODE_SLEEP ||
            keyCode == KeyEvent.KEYCODE_WAKEUP ||
            keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
            keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
            keyCode == KeyEvent.KEYCODE_VOLUME_MUTE ||
            keyCode == KeyEvent.KEYCODE_HOME
        ) {
            return super.dispatchKeyEvent(event)
        }

        // When Aether's native overlay menu is open inside WebView, route D-Pad & Back to it
        if (aetherMenuOpen && fallbackWebView != null && selectionDialog == null) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        fallbackWebView?.evaluateJavascript(
                            "window.__anpinimeAetherMenuMove && window.__anpinimeAetherMenuMove(-1);",
                            null
                        )
                    }
                    return true
                }
                KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        fallbackWebView?.evaluateJavascript(
                            "window.__anpinimeAetherMenuMove && window.__anpinimeAetherMenuMove(1);",
                            null
                        )
                    }
                    return true
                }
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                        fallbackWebView?.evaluateJavascript(
                            "window.__anpinimeAetherMenuActivate && window.__anpinimeAetherMenuActivate();",
                            null
                        )
                    }
                    return true
                }
                KeyEvent.KEYCODE_BACK,
                KeyEvent.KEYCODE_ESCAPE -> {
                    if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                        fallbackWebView?.evaluateJavascript(
                            "window.__anpinimeAetherMenuBack && window.__anpinimeAetherMenuBack();",
                            null
                        )
                    }
                    return true
                }
            }
        }

        if (event.action == KeyEvent.ACTION_UP && keyCode == seekGestureKey) {
            seekGestureKey = -1
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN &&
            (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) &&
            (!osdVisible || seekGestureKey == keyCode || seekBar.hasFocus())) {
            seekGestureKey = keyCode
            val step = computeAcceleratedSeekStep(event.repeatCount)
            seekRelative(if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -step else step)
            showOsd()
            seekBar.requestFocus()
            return true
        }

        if (event.action == KeyEvent.ACTION_DOWN) {
            scheduleHideOsd()

            // If user presses OK/Enter while a seek target is pending on the seekbar, commit immediately!
            if ((keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                    keyCode == KeyEvent.KEYCODE_ENTER ||
                    keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) &&
                pendingSeekTargetMs >= 0L
            ) {
                commitPendingSeek()
                return true
            }

            // Media buttons on projector remote work whether OSD is visible or hidden
            when (keyCode) {
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                    if (event.repeatCount > 0) return true
                    togglePlayPause()
                    showOsd()
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PLAY -> {
                    exoPlayer?.play()
                    sendWebCommand("play")
                    showOsd()
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                    exoPlayer?.pause()
                    sendWebCommand("pause")
                    showOsd()
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                    val step = computeAcceleratedSeekStep(event.repeatCount)
                    seekRelative(step)
                    showOsd(focusPlayButton = false)
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_REWIND -> {
                    val step = computeAcceleratedSeekStep(event.repeatCount)
                    seekRelative(-step)
                    showOsd(focusPlayButton = false)
                    return true
                }
            }

            if (!osdVisible) {
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        val step = computeAcceleratedSeekStep(event.repeatCount)
                        seekRelative(-step)
                        showOsd(focusPlayButton = false)
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        val step = computeAcceleratedSeekStep(event.repeatCount)
                        seekRelative(step)
                        showOsd(focusPlayButton = false)
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_CENTER,
                    KeyEvent.KEYCODE_ENTER,
                    KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                        if (event.repeatCount > 0) return true
                        togglePlayPause()
                        showOsd(focusPlayButton = true)
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_UP,
                    KeyEvent.KEYCODE_DPAD_DOWN,
                    KeyEvent.KEYCODE_MENU -> {
                        showOsd(focusPlayButton = true)
                        return true
                    }
                    KeyEvent.KEYCODE_BACK,
                    KeyEvent.KEYCODE_ESCAPE -> {
                        finish()
                        return true
                    }
                }
            } else {
                if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) {
                    if (sidePanelVisible) {
                        closeSidePanel()
                    } else if (exoPlayer?.isPlaying == true || fallbackWebView != null) {
                        hideOsd()
                    } else {
                        finish()
                    }
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    // =========================================================================
    // UI CONSTRUCTION (Pure Hardware SurfaceView + Phim4K Glass HUD & Drawer)
    // =========================================================================

    @SuppressLint("ClickableViewAccessibility")
    private fun buildUi() {
        val compact = Phim4KPlayerUi.isCompactPhoneScreen(this) || BuildConfig.IS_PHONE_FLAVOR
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        videoFrame = FrameLayout(this).apply {
            clipChildren = true
            clipToPadding = true
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
            )
        }

        surfaceView = SurfaceView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
            )
            isFocusable = false
            isFocusableInTouchMode = false
        }
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                exoPlayer?.setVideoSurfaceView(surfaceView)
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) {}
        })
        videoFrame.addView(surfaceView)
        subtitleView = SubtitleView(this).apply {
            setUserDefaultStyle()
            setUserDefaultTextSize()
            isFocusable = false
            layoutParams = FrameLayout.LayoutParams(-1, -1)
        }
        videoFrame.addView(subtitleView)
        root.addView(videoFrame)

        // Headless container for background WebView scraper, completely isolated from videoFrame
        headlessWebContainer = FrameLayout(this).apply {
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(1, 1, Gravity.TOP or Gravity.START)
            isFocusable = false
            isFocusableInTouchMode = false
        }
        root.addView(headlessWebContainer)

        // Loading / Status Overlay with iOS Animated Loading GIF & Real-Time Scraping Animation
        loadingOverlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(UiHelper.dp(context, 24), UiHelper.dp(context, 18), UiHelper.dp(context, 24), UiHelper.dp(context, 18))
            background = UiHelper.roundedBg(Color.parseColor("#D908090E"), 16, context, Color.parseColor("#26FFFFFF"), 1)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        }
        loadingOverlay.addView(UiHelper.createLoadingIndicator(this, sizeDp = 88))

        loadingProviderBadge = TextView(this).apply {
            text = "⚡ Đang kiểm tra: Tự động"
            setTextColor(Color.parseColor("#38BDF8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(UiHelper.dp(context, 14), UiHelper.dp(context, 5), UiHelper.dp(context, 14), UiHelper.dp(context, 5))
            background = UiHelper.roundedBg(Color.parseColor("#2638BDF8"), 14, context, Color.parseColor("#4D38BDF8"), 1)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = UiHelper.dp(context, 10)
                bottomMargin = UiHelper.dp(context, 4)
            }
            visibility = View.GONE
        }
        loadingOverlay.addView(loadingProviderBadge)

        loadingText = TextView(this).apply {
            text = "Đang kết nối luồng phát…"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, UiHelper.dp(context, 6), 0, 0)
        }
        loadingOverlay.addView(loadingText)

        loadingProgressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            layoutParams = LinearLayout.LayoutParams(
                UiHelper.dp(context, 260),
                UiHelper.dp(context, 6)
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = UiHelper.dp(context, 10)
                bottomMargin = UiHelper.dp(context, 6)
            }
            visibility = View.GONE
        }
        loadingOverlay.addView(loadingProgressBar)

        loadingHintText = TextView(this).apply {
            text = "💡 Có thể bấm [Đổi Server] trên remote để chọn nguồn tải khác"
            setTextColor(Color.parseColor("#99FFFFFF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            gravity = Gravity.CENTER
            setPadding(0, UiHelper.dp(context, 4), 0, 0)
            visibility = View.GONE
        }
        loadingOverlay.addView(loadingHintText)

        root.addView(loadingOverlay)

        // Transparent Touch Gesture Layer (Phone tap OSD / double-tap +/-10s & play/pause, skipped by TV D-pad)
        val gestureDetector = GestureDetector(
            this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent): Boolean = true

                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    if (sidePanelVisible) {
                        closeSidePanel()
                        return true
                    }
                    if (osdVisible) hideOsd() else showOsd(focusPlayButton = false)
                    return true
                }

                override fun onDoubleTap(e: MotionEvent): Boolean {
                    if (sidePanelVisible) return false
                    val w = videoFrame.width.coerceAtLeast(1)
                    val ratioX = e.x / w.toFloat()
                    when {
                        ratioX < 0.35f -> {
                            seekRelative(-10_000L)
                            showGestureFeedback("⏪  -10 giây", Gravity.START or Gravity.CENTER_VERTICAL)
                        }
                        ratioX > 0.65f -> {
                            seekRelative(10_000L)
                            showGestureFeedback("+10 giây  ⏩", Gravity.END or Gravity.CENTER_VERTICAL)
                        }
                        else -> {
                            val willPause = isCurrentlyPlaying()
                            togglePlayPause()
                            showGestureFeedback(
                                if (willPause) "⏸  Tạm dừng" else "▶  Đang phát",
                                Gravity.CENTER
                            )
                        }
                    }
                    return true
                }
            }
        )
        val gestureLayer = View(this).apply {
            isFocusable = false
            isFocusableInTouchMode = false
            isClickable = true
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setOnTouchListener { _, event -> gestureDetector.onTouchEvent(event) }
        }
        root.addView(gestureLayer)

        // OSD Controls Container (Phim4K Layout: Top Bar + Scrubber Above Centered Icon Row)
        val hPad = Phim4KPlayerUi.dp(this, if (compact) 18 else 28)
        val vPadTop = Phim4KPlayerUi.dp(this, if (compact) 12 else 18)
        val vPadBottom = Phim4KPlayerUi.dp(this, if (compact) 10 else 18)

        osdContainer = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // Top Bar (Back + Title/Meta + Status Badges)
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(hPad, vPadTop, hPad, Phim4KPlayerUi.dp(context, 24))
            background = Phim4KPlayerUi.createTopBottomScrim(isTop = true)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
            )
        }

        val backBtn = Phim4KPlayerUi.createControlIconButton(
            context = this,
            iconResId = R.drawable.ic_p4k_back,
            sizeDp = if (compact) 36 else 42,
            paddingDp = if (compact) 8 else 10,
            contentDescriptionText = "Quay lại"
        ) { hasFocus ->
            if (hasFocus) updateFocusedHint("Quay lại trang thông tin phim")
        }.apply {
            setOnClickListener { finish() }
        }
        topBar.addView(backBtn)

        val topTitleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = Phim4KPlayerUi.dp(context, 12)
                marginEnd = Phim4KPlayerUi.dp(context, 10)
            }
        }
        topTitleText = TextView(this).apply {
            text = movieTitle
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (compact) 16f else 19f)
            typeface = Typeface.DEFAULT_BOLD
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
        }
        topServerText = TextView(this).apply {
            text = "${currentEpisode.title} • ${currentEpisode.season}"
            setTextColor(0xFFB8C7D9.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (compact) 11.5f else 13f)
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, Phim4KPlayerUi.dp(context, 2), 0, 0)
        }
        topTitleCol.addView(topTitleText)
        topTitleCol.addView(topServerText)
        topBar.addView(topTitleCol)

        val topBadgesRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        topTranslateBadge = TextView(this).apply {
            visibility = View.GONE
            setTextColor(0xFFE1F5FE.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(Phim4KPlayerUi.dp(context, 10), Phim4KPlayerUi.dp(context, 5), Phim4KPlayerUi.dp(context, 10), Phim4KPlayerUi.dp(context, 5))
            background = Phim4KPlayerUi.createGlassPillDrawable(context, fillColor = 0x404FC3F7, strokeColor = 0x994FC3F7.toInt(), cornerDp = 14)
            isFocusable = false
            isClickable = true
            setOnClickListener { triggerAetherVietnameseTranslation() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = Phim4KPlayerUi.dp(context, 8) }
        }
        topBadgesRow.addView(topTranslateBadge)

        topQualityBadge = TextView(this).apply {
            text = "Auto · 1.0x"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(Phim4KPlayerUi.dp(context, 10), Phim4KPlayerUi.dp(context, 5), Phim4KPlayerUi.dp(context, 10), Phim4KPlayerUi.dp(context, 5))
            background = Phim4KPlayerUi.createGlassPillDrawable(context, fillColor = 0x7305060A, strokeColor = 0x33FFFFFF, cornerDp = 14)
            isFocusable = false
            isClickable = true
            setOnClickListener { showSettingsMenu() }
        }
        topBadgesRow.addView(topQualityBadge)
        topBar.addView(topBadgesRow)
        osdContainer.addView(topBar)

        // Bottom Control Panel (Phim4K Order: Hint -> Time Row -> SeekBar -> Centered Icon Row)
        val bottomPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(hPad, Phim4KPlayerUi.dp(context, 28), hPad, vPadBottom)
            background = Phim4KPlayerUi.createTopBottomScrim(isTop = false)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            )
        }

        controlHintText = TextView(this).apply {
            text = "Chạm video để ẩn/hiện · Chạm đúp trái/phải tua 10s"
            setTextColor(0xFFA6D8FA.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (compact) 11f else 12f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = Phim4KPlayerUi.dp(context, 6) }
        }
        bottomPanel.addView(controlHintText)

        // Time Row ABOVE SeekBar (Phim4K exo_position / exo_duration style)
        val timeRow = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = Phim4KPlayerUi.dp(context, 2) }
        }
        timeCurrentText = TextView(this).apply {
            text = "00:00"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (compact) 11.5f else 12.5f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(Phim4KPlayerUi.dp(context, 9), Phim4KPlayerUi.dp(context, 3), Phim4KPlayerUi.dp(context, 9), Phim4KPlayerUi.dp(context, 3))
            background = Phim4KPlayerUi.createGlassPillDrawable(context, cornerDp = 10)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.START or Gravity.CENTER_VERTICAL
            )
        }
        seekPreviewBadge = TextView(this).apply {
            visibility = View.GONE
            setTextColor(0xFF06131C.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(Phim4KPlayerUi.dp(context, 10), Phim4KPlayerUi.dp(context, 3), Phim4KPlayerUi.dp(context, 10), Phim4KPlayerUi.dp(context, 3))
            background = Phim4KPlayerUi.createGlassPillDrawable(
                context,
                fillColor = Phim4KPlayerUi.COLOR_ACCENT_CYAN,
                strokeColor = Color.TRANSPARENT,
                cornerDp = 12
            )
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        }
        timeTotalText = TextView(this).apply {
            text = "00:00"
            setTextColor(0xFFD6E2F0.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (compact) 11.5f else 12.5f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(Phim4KPlayerUi.dp(context, 9), Phim4KPlayerUi.dp(context, 3), Phim4KPlayerUi.dp(context, 9), Phim4KPlayerUi.dp(context, 3))
            background = Phim4KPlayerUi.createGlassPillDrawable(context, cornerDp = 10)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.END or Gravity.CENTER_VERTICAL
            )
        }
        timeRow.addView(timeCurrentText)
        timeRow.addView(seekPreviewBadge)
        timeRow.addView(timeTotalText)
        bottomPanel.addView(timeRow)

        val playPauseId = View.generateViewId()
        val seekBarId = View.generateViewId()

        seekBar = SeekBar(this).apply {
            id = seekBarId
            max = 1000
            isFocusable = true
            isFocusableInTouchMode = false
            splitTrack = false
            progressDrawable = Phim4KPlayerUi.createSeekBarProgressDrawable(this@PlayerActivity)
            thumb = Phim4KPlayerUi.createSeekBarThumbDrawable(this@PlayerActivity)
            nextFocusDownId = playPauseId
            contentDescription = "Thanh thời lượng: trái/phải tua nhanh"
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                Phim4KPlayerUi.dp(context, 24)
            ).apply {
                bottomMargin = Phim4KPlayerUi.dp(context, if (compact) 6 else 10)
            }
            setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) updateFocusedHint("Thanh tua thời lượng · Bấm Trái / Phải để tua")
            }
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val dur = (exoPlayer?.duration?.takeIf { it > 0 } ?: webDurationMs)
                    if (dur > 0L) {
                        val targetMs = (dur * progress.toLong() / 1000L).coerceIn(0L, dur)
                        pendingSeekTargetMs = targetMs
                        timeCurrentText.text = formatTime(targetMs)
                        if (::seekPreviewBadge.isInitialized) {
                            seekPreviewBadge.text = "⏩  ${formatTime(targetMs)}"
                            seekPreviewBadge.visibility = View.VISIBLE
                        }
                        if (!scrubbing) {
                            mainHandler.removeCallbacks(commitPendingSeekRunnable)
                            mainHandler.postDelayed(commitPendingSeekRunnable, 600L)
                        }
                    }
                }
                override fun onStartTrackingTouch(bar: SeekBar?) {
                    scrubbing = true
                    mainHandler.removeCallbacks(commitPendingSeekRunnable)
                    mainHandler.removeCallbacks(hideOsdRunnable)
                }
                override fun onStopTrackingTouch(bar: SeekBar?) {
                    scrubbing = false
                    mainHandler.removeCallbacks(commitPendingSeekRunnable)
                    commitPendingSeek()
                    scheduleHideOsd()
                }
            })
        }
        bottomPanel.addView(seekBar)

        // Centered Phim4K Single-Row Icon Bar
        val btnSize = if (compact) 38 else 44
        val playBtnSize = if (compact) 46 else 54
        val iconPad = if (compact) 9 else 11
        val playIconPad = if (compact) 11 else 13
        val gapPx = Phim4KPlayerUi.dp(this, if (compact) 7 else 10)

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(Phim4KPlayerUi.dp(context, 4), Phim4KPlayerUi.dp(context, 2), Phim4KPlayerUi.dp(context, 4), Phim4KPlayerUi.dp(context, 2))
        }

        fun makeP4kIconBtn(
            iconResId: Int,
            hintTitle: String,
            isCenter: Boolean = false,
            extraHint: () -> String = { "" },
            onClick: () -> Unit
        ): ImageView {
            val sz = if (isCenter) playBtnSize else btnSize
            val pad = if (isCenter) playIconPad else iconPad
            return Phim4KPlayerUi.createControlIconButton(
                context = this,
                iconResId = iconResId,
                sizeDp = sz,
                paddingDp = pad,
                contentDescriptionText = hintTitle,
                isPrimaryCenter = isCenter
            ) { hasFocus ->
                if (hasFocus) {
                    scheduleHideOsd()
                    val detail = extraHint()
                    updateFocusedHint(if (detail.isNotBlank()) "$hintTitle · $detail" else hintTitle)
                }
            }.apply {
                nextFocusUpId = seekBarId
                (layoutParams as LinearLayout.LayoutParams).marginEnd = gapPx
                setOnClickListener {
                    scheduleHideOsd()
                    onClick()
                }
            }
        }

        fun attachSeekKeyRepeat(btn: ImageView, baseDelta: Long) {
            var lastRepeatAt = 0L
            btn.setOnKeyListener { _, code, event ->
                if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                    if (event.action == KeyEvent.ACTION_DOWN && (event.repeatCount == 0 || event.eventTime - lastRepeatAt >= 150L)) {
                        lastRepeatAt = event.eventTime
                        val step = computeAcceleratedSeekStep(event.repeatCount).coerceAtLeast(kotlin.math.abs(baseDelta))
                        seekRelative(if (baseDelta < 0) -step else step)
                    }
                    true
                } else false
            }
        }

        rewindBtn = makeP4kIconBtn(R.drawable.ic_p4k_rewind, "Tua lùi 10s") {
            seekRelative(-computeAcceleratedSeekStep(0).coerceAtLeast(10_000L))
        }.also { attachSeekKeyRepeat(it, -10_000L) }
        btnRow.addView(rewindBtn)

        playPauseBtn = makeP4kIconBtn(R.drawable.ic_p4k_pause, "Phát / Tạm dừng", isCenter = true) {
            togglePlayPause()
        }.apply { id = playPauseId }
        btnRow.addView(playPauseBtn)

        forwardBtn = makeP4kIconBtn(R.drawable.ic_p4k_forward, "Tua tới 10s") {
            seekRelative(computeAcceleratedSeekStep(0).coerceAtLeast(10_000L))
        }.also { attachSeekKeyRepeat(it, 10_000L) }
        btnRow.addView(forwardBtn)

        nextEpBtn = makeP4kIconBtn(R.drawable.ic_p4k_next, "Tập tiếp theo") {
            playNextEpisode()
        }
        btnRow.addView(nextEpBtn)

        episodeBtn = makeP4kIconBtn(
            R.drawable.ic_p4k_playlist,
            "Danh sách tập",
            extraHint = { "${allEpisodes.count { it.season == currentEpisode.season }} tập" }
        ) {
            openSidePanel()
        }
        btnRow.addView(episodeBtn)

        serverBtn = makeP4kIconBtn(
            R.drawable.ic_p4k_server,
            "Đổi nguồn phát / Server",
            extraHint = { currentEpisode.season }
        ) {
            showServerPicker()
        }
        btnRow.addView(serverBtn)

        subtitleBtn = makeP4kIconBtn(
            R.drawable.ic_p4k_subtitles,
            "Phụ đề",
            extraHint = { if (viLiveSubActive) "Tiếng Việt (AI)" else "Chọn hoặc tắt phụ đề" }
        ) {
            showTrackPicker(C.TRACK_TYPE_TEXT)
        }
        btnRow.addView(subtitleBtn)

        aetherTranslateBtn = makeP4kIconBtn(
            R.drawable.ic_p4k_translate,
            "Dịch Sub Việt (AI)",
            extraHint = { if (viLiveSubActive) "Đang bật" else "Tức thì bằng AI" }
        ) {
            triggerAetherVietnameseTranslation()
        }
        btnRow.addView(aetherTranslateBtn)

        audioBtn = makeP4kIconBtn(R.drawable.ic_p4k_audio, "Âm thanh") {
            showTrackPicker(C.TRACK_TYPE_AUDIO)
        }
        btnRow.addView(audioBtn)

        aspectBtn = makeP4kIconBtn(
            R.drawable.ic_p4k_aspect,
            "Tỉ lệ màn hình",
            extraHint = { currentAspectMode.label }
        ) {
            cycleAspectMode()
        }
        btnRow.addView(aspectBtn)

        settingsBtn = makeP4kIconBtn(
            R.drawable.ic_p4k_settings,
            "Cài đặt phát",
            extraHint = { "Chất lượng · Tốc độ · Cỡ Sub · Audio" }
        ) {
            showSettingsMenu()
        }.apply {
            (layoutParams as LinearLayout.LayoutParams).marginEnd = 0
        }
        btnRow.addView(settingsBtn)

        val btnScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            isFillViewport = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            addView(btnRow)
        }
        bottomPanel.addView(btnScroll)
        osdContainer.addView(bottomPanel)
        root.addView(osdContainer)

        // Floating Gesture / Action Feedback Pill (above OSD)
        gestureFeedbackPill = TextView(this).apply {
            visibility = View.GONE
            alpha = 0f
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (compact) 14f else 16f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(Phim4KPlayerUi.dp(context, 16), Phim4KPlayerUi.dp(context, 9), Phim4KPlayerUi.dp(context, 16), Phim4KPlayerUi.dp(context, 9))
            background = Phim4KPlayerUi.createGlassPillDrawable(context, fillColor = 0xCC0B0D12.toInt(), strokeColor = 0x4DFFFFFF, cornerDp = 22)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            ).apply {
                marginStart = Phim4KPlayerUi.dp(context, 36)
                marginEnd = Phim4KPlayerUi.dp(context, 36)
            }
        }
        root.addView(gestureFeedbackPill)

        // Phim4K Right-Side Glass Sliding Drawer Backdrop + Panel
        sidePanelBackdrop = View(this).apply {
            visibility = View.GONE
            setBackgroundColor(0x80000000.toInt())
            isClickable = true
            isFocusable = false
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setOnClickListener { closeSidePanel() }
        }
        root.addView(sidePanelBackdrop)

        val dm = resources.displayMetrics
        val panelWidth = if (compact) {
            minOf(Phim4KPlayerUi.dp(this, 340), (dm.widthPixels * 0.48f).toInt().coerceAtLeast(Phim4KPlayerUi.dp(this, 270)))
        } else {
            minOf(Phim4KPlayerUi.dp(this, 440), (dm.widthPixels * 0.42f).toInt())
        }
        sidePanelContainer = LinearLayout(this).apply {
            visibility = View.GONE
            orientation = LinearLayout.VERTICAL
            translationX = panelWidth.toFloat()
            background = Phim4KPlayerUi.createGlassPanelDrawable(context)
            setPadding(Phim4KPlayerUi.dp(context, 16), Phim4KPlayerUi.dp(context, 16), Phim4KPlayerUi.dp(context, 16), Phim4KPlayerUi.dp(context, 16))
            isClickable = true
            layoutParams = FrameLayout.LayoutParams(
                panelWidth,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.END
            )
        }

        val panelHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, Phim4KPlayerUi.dp(context, 10))
        }
        val panelHeaderTextCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        panelTitleText = TextView(this).apply {
            text = "Danh sách tập"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (compact) 16f else 18f)
            typeface = Typeface.DEFAULT_BOLD
        }
        panelCountBadge = TextView(this).apply {
            text = ""
            setTextColor(Phim4KPlayerUi.COLOR_ACCENT_CYAN)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
        }
        panelHeaderTextCol.addView(panelTitleText)
        panelHeaderTextCol.addView(panelCountBadge)
        panelHeader.addView(panelHeaderTextCol)

        panelSwitchServerBtn = Phim4KPlayerUi.createGlassChip(this, "Đổi nguồn", isSelected = false) {
            closeSidePanel()
            showServerPicker()
        }.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = Phim4KPlayerUi.dp(context, 8) }
        }
        panelHeader.addView(panelSwitchServerBtn)

        val panelCloseBtn = Phim4KPlayerUi.createControlIconButton(
            context = this,
            iconResId = R.drawable.ic_p4k_close,
            sizeDp = 34,
            paddingDp = 8,
            contentDescriptionText = "Đóng bảng"
        ).apply {
            setOnClickListener { closeSidePanel() }
        }
        panelHeader.addView(panelCloseBtn)
        sidePanelContainer.addView(panelHeader)

        sidePanelContainer.addView(View(this).apply {
            setBackgroundColor(0x26FFFFFF)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, maxOf(1, Phim4KPlayerUi.dp(context, 1))).apply {
                bottomMargin = Phim4KPlayerUi.dp(context, 10)
            }
        })

        episodeDrawer = ScrollView(this).apply {
            isVerticalScrollBarEnabled = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        episodeDrawerRow = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        episodeDrawer.addView(episodeDrawerRow)
        sidePanelContainer.addView(episodeDrawer)

        root.addView(sidePanelContainer)

        setContentView(root)
        updateSourceSpecificOsdButtons()
        updateTopStatusBadges()
        playPauseBtn.requestFocus()
        scheduleHideOsd()
    }

    private fun isCurrentlyPlaying(): Boolean {
        val player = exoPlayer
        return if (player != null) player.isPlaying else !webPaused
    }

    private fun updateFocusedHint(hint: String) {
        currentFocusedHint = hint
        if (::controlHintText.isInitialized) {
            controlHintText.text = hint
        }
    }

    private fun updatePlayPauseIcon(isPlaying: Boolean) {
        if (!::playPauseBtn.isInitialized) return
        playPauseBtn.setImageResource(if (isPlaying) R.drawable.ic_p4k_pause else R.drawable.ic_p4k_play)
        playPauseBtn.contentDescription = if (isPlaying) "Tạm dừng" else "Phát tiếp"
    }

    private fun updateTranslateStatusUi(done: Int, total: Int, completed: Boolean) {
        if (::aetherTranslateBtn.isInitialized) {
            Phim4KPlayerUi.styleActiveIconButton(aetherTranslateBtn, this, isActive = viLiveSubActive)
        }
        if (::subtitleBtn.isInitialized) {
            Phim4KPlayerUi.styleActiveIconButton(subtitleBtn, this, isActive = viLiveSubActive)
        }
        if (::topTranslateBadge.isInitialized) {
            if (!viLiveSubActive && done == 0 && total == 0) {
                topTranslateBadge.visibility = View.GONE
            } else {
                topTranslateBadge.visibility = View.VISIBLE
                topTranslateBadge.text = when {
                    total <= 0 -> "⚡ Đang tải phụ đề…"
                    completed || done >= total -> "✓ Việt AI ($done/$total)"
                    else -> "⚡ Sub Việt ($done/$total)"
                }
            }
        }
    }

    private fun updateTopStatusBadges() {
        if (!::topQualityBadge.isInitialized) return
        val qLabel = if (exoPlayer == null && fallbackWebView != null) {
            webQualities.firstOrNull { it.selected }?.label ?: "Auto"
        } else {
            when (currentQualityMode) {
                0 -> "1080p"
                1 -> "720p"
                else -> "Auto"
            }
        }
        val speedLabel = String.format(Locale.US, "%.2gx", currentPlaybackSpeed)
        topQualityBadge.text = "$qLabel · $speedLabel · ${currentAspectMode.label}"
    }

    private fun showGestureFeedback(text: String, gravity: Int = Gravity.CENTER) {
        if (!::gestureFeedbackPill.isInitialized) return
        mainHandler.removeCallbacks(hideGestureFeedbackRunnable)
        val lp = gestureFeedbackPill.layoutParams as FrameLayout.LayoutParams
        lp.gravity = gravity
        gestureFeedbackPill.layoutParams = lp
        gestureFeedbackPill.text = text
        gestureFeedbackPill.visibility = View.VISIBLE
        gestureFeedbackPill.animate().cancel()
        gestureFeedbackPill.alpha = 1f
        mainHandler.postDelayed(hideGestureFeedbackRunnable, 850L)
    }

    private fun openSidePanel() {
        if (!::sidePanelContainer.isInitialized) return
        mainHandler.removeCallbacks(hideOsdRunnable)
        val epFocusView = populateEpisodeDrawer()
        val sameServerCount = allEpisodes.count { it.season == currentEpisode.season }
        panelCountBadge.text = "$sameServerCount tập • ${currentEpisode.season}"

        sidePanelVisible = true
        sidePanelBackdrop.visibility = View.VISIBLE
        sidePanelContainer.visibility = View.VISIBLE
        sidePanelContainer.animate().translationX(0f).setDuration(210).start()

        sidePanelContainer.post {
            val target = epFocusView ?: episodeDrawerRow.getChildAt(0)
            if (target != null) {
                episodeDrawer.post {
                    val offset = (target.top - episodeDrawer.height / 3).coerceAtLeast(0)
                    episodeDrawer.scrollTo(0, offset)
                    target.requestFocus()
                }
            } else if (::panelSwitchServerBtn.isInitialized) {
                panelSwitchServerBtn.requestFocus()
            }
        }
    }

    private fun closeSidePanel() {
        if (!::sidePanelContainer.isInitialized) return
        sidePanelVisible = false
        sidePanelBackdrop.visibility = View.GONE
        val slideOutX = sidePanelContainer.width.takeIf { it > 0 }?.toFloat()
            ?: Phim4KPlayerUi.dp(this, 440).toFloat()
        sidePanelContainer.animate().translationX(slideOutX).setDuration(180).withEndAction {
            if (!sidePanelVisible) sidePanelContainer.visibility = View.GONE
        }.start()
        if (osdVisible) {
            playPauseBtn.requestFocus()
            scheduleHideOsd()
        }
    }

    private fun updateSourceSpecificOsdButtons() {
        if (!::aetherTranslateBtn.isInitialized) return
        val aether = isAetherEpisode()
        aetherTranslateBtn.visibility = if (aether) View.VISIBLE else View.GONE
        val sameServer = allEpisodes.filter { it.season == currentEpisode.season }
        val idx = sameServer.indexOfFirst { it.number == currentEpisode.number && it.title == currentEpisode.title }
        if (::nextEpBtn.isInitialized) {
            nextEpBtn.visibility = if (idx >= 0 && idx + 1 < sameServer.size) View.VISIBLE else View.GONE
        }
        updateTopStatusBadges()
    }

    private fun showServerPicker() {
        if (isAetherEpisode()) {
            val providers = availableAetherProviders
            if (providers.isEmpty()) {
                showPlayerMessage("Nguồn phát", "Chưa tìm thấy nguồn phát khả dụng cho phim này.")
                return
            }
            val labels = providers.mapIndexed { idx, provider ->
                if (provider.id == "auto") {
                    "⚡ ${provider.name} (Auto) · Khuyên dùng"
                } else {
                    val desc = provider.description.takeIf { it.isNotBlank() }?.let { " — $it" }.orEmpty()
                    "Server ${idx + 1} · ${provider.name}$desc"
                }
            }
            val selected = providers.indexOfFirst { it.id.equals(currentAetherProviderId, ignoreCase = true) }
                .coerceAtLeast(0)
            showSelection("Chọn nguồn phát / Server", labels, selected) { idx ->
                val chosen = providers.getOrNull(idx) ?: return@showSelection
                selectAetherProvider(chosen.id, chosen.name)
            }
            return
        }

        val seasons = ArrayList<String>()
        for (ep in allEpisodes) {
            if (ep.season.isNotBlank() && !seasons.contains(ep.season)) seasons.add(ep.season)
        }
        if (seasons.isEmpty()) {
            showPlayerMessage("Nguồn phát", "Nguồn hiện tại: ${currentEpisode.season.ifBlank { "Mặc định" }}")
            return
        }
        val labels = ArrayList<String>(seasons.size)
        val matchedEps = ArrayList<Episode>(seasons.size)
        var selected = 0
        seasons.forEachIndexed { idx, sv ->
            if (sv == currentEpisode.season) selected = idx
            val matched = allEpisodes.firstOrNull { it.season == sv && it.number == currentEpisode.number }
                ?: allEpisodes.firstOrNull { it.season == sv }!!
            val count = allEpisodes.count { it.season == sv }
            val badge = matched.qualityBadge.takeIf { it.isNotBlank() }?.let { " • $it" }.orEmpty()
            labels.add("$sv$badge · $count tập")
            matchedEps.add(matched)
        }
        showSelection("Chọn nguồn phát / Server", labels, selected) { idx ->
            val matchedEp = matchedEps.getOrNull(idx) ?: return@showSelection
            val resumePos = exoPlayer?.currentPosition ?: webPositionMs
            startEpisodePlayback(
                matchedEp,
                resetPosition = matchedEp.number != currentEpisode.number,
                initialPositionMs = resumePos
            )
        }
    }

    private fun populateEpisodeDrawer(): View? {
        episodeDrawerRow.removeAllViews()
        var activeRow: View? = null
        val sameServerEps = allEpisodes.filter { it.season == currentEpisode.season }
        for (ep in sameServerEps) {
            val isCurrent = ep.number == currentEpisode.number && ep.title == currentEpisode.title
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = Phim4KPlayerUi.createGlassCardDrawable(this@PlayerActivity, isActive = isCurrent, cornerDp = 10)
                setPadding(
                    Phim4KPlayerUi.dp(this@PlayerActivity, 14),
                    Phim4KPlayerUi.dp(this@PlayerActivity, 11),
                    Phim4KPlayerUi.dp(this@PlayerActivity, 14),
                    Phim4KPlayerUi.dp(this@PlayerActivity, 11)
                )
                isFocusable = true
                isClickable = true
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = Phim4KPlayerUi.dp(this@PlayerActivity, 8) }
                setOnFocusChangeListener { v, hasFocus ->
                    val s = if (hasFocus) 1.015f else 1.0f
                    v.animate().scaleX(s).scaleY(s).setDuration(80).start()
                }
                setOnClickListener {
                    closeSidePanel()
                    startEpisodePlayback(ep, resetPosition = true)
                }
            }

            val titleCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            titleCol.addView(TextView(this).apply {
                text = ep.title
                textSize = 14f
                typeface = Typeface.create("sans-serif-medium", if (isCurrent) Typeface.BOLD else Typeface.NORMAL)
                setTextColor(if (isCurrent) Phim4KPlayerUi.COLOR_ACCENT_CYAN else Color.WHITE)
                setSingleLine(true)
                ellipsize = TextUtils.TruncateAt.END
            })
            val subMeta = buildString {
                if (ep.season.isNotBlank()) append(ep.season)
                if (ep.qualityBadge.isNotBlank()) {
                    if (isNotEmpty()) append(" • ")
                    append(ep.qualityBadge)
                }
            }
            if (subMeta.isNotEmpty()) {
                titleCol.addView(TextView(this).apply {
                    text = subMeta
                    textSize = 11.5f
                    setTextColor(0xFF9FB0C4.toInt())
                    setSingleLine(true)
                    ellipsize = TextUtils.TruncateAt.END
                })
            }
            card.addView(titleCol)

            if (isCurrent) {
                card.addView(TextView(this).apply {
                    text = "ĐANG PHÁT"
                    textSize = 10f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(0xFF06131C.toInt())
                    setPadding(
                        Phim4KPlayerUi.dp(this@PlayerActivity, 8),
                        Phim4KPlayerUi.dp(this@PlayerActivity, 3),
                        Phim4KPlayerUi.dp(this@PlayerActivity, 8),
                        Phim4KPlayerUi.dp(this@PlayerActivity, 3)
                    )
                    background = Phim4KPlayerUi.createGlassPillDrawable(
                        this@PlayerActivity,
                        fillColor = Phim4KPlayerUi.COLOR_ACCENT_CYAN,
                        strokeColor = Color.TRANSPARENT,
                        cornerDp = 10
                    )
                })
                activeRow = card
            }
            episodeDrawerRow.addView(card)
        }
        return activeRow
    }

    private fun selectAetherProvider(
        providerId: String,
        providerName: String,
        resetTried: Boolean = true
    ) {
        currentAetherProviderId = providerId
        if (resetTried) triedAetherScrapers.clear()
        if (providerId != "auto") {
            triedAetherScrapers.add(providerId.lowercase(Locale.US))
        }
        val resumePos = exoPlayer?.currentPosition ?: webPositionMs
        releasePlayer(stopRelay = false)
        destroyWebPlayer(force = true)

        updateScrapingOverlay(providerName, providerId, "Đang chuyển sang nguồn $providerName…", 0)

        val targetEp = currentEpisode.copy(
            episodeKey = providerId,
            season = "Server · $providerName",
            serverName = providerName
        )
        startEpisodePlayback(targetEp, resetPosition = false, initialPositionMs = resumePos)
    }

    private fun handleAetherSourcesLoaded(sourcesJson: String) {
        // Keep availableAetherProviders aligned with AetherSourceResolver.SERVERS
    }

    private fun updateScrapingOverlay(
        providerName: String,
        providerId: String,
        statusText: String,
        percentage: Int
    ) {
        if (exoPlayer != null) return
        loadingOverlay.visibility = View.VISIBLE
        loadingProviderBadge.visibility = View.VISIBLE
        val displayName = providerName.ifBlank { providerId }
        loadingProviderBadge.text = "⚡ Đang kiểm tra: $displayName"
        loadingText.text = if (statusText.isNotBlank()) statusText else "Đang quét nguồn $displayName…"
        loadingProgressBar.visibility = View.VISIBLE
        if (percentage > 0) {
            loadingProgressBar.isIndeterminate = false
            loadingProgressBar.progress = percentage
        } else {
            loadingProgressBar.isIndeterminate = true
        }
        loadingHintText.visibility = View.VISIBLE
    }

    // =========================================================================
    // EXOPLAYER & WEB FALLBACK INITIALIZATION (Low-RAM Projector Tuned)
    // =========================================================================

    private fun startEpisodePlayback(
        episode: Episode,
        resetPosition: Boolean,
        initialPositionMs: Long = 0L
    ) {
        val generation = ++playbackGeneration
        val prevSlug = if (::currentEpisode.isInitialized) currentEpisode.slug else ""
        if (episode.slug != prevSlug || resetPosition) {
            triedAetherScrapers.clear()
        }
        translateSubJob?.cancel()
        releasePlayer()
        preferSoftwareAudio = false
        currentEpisode = episode
        visitedFrames.clear()
        webPositionMs = 0L
        webDurationMs = 0L
        retryCount = 0
        aetherStatusNote = ""
        attemptedStreamUrls.clear()
        topServerText.text = "${episode.title} • ${episode.season}"
        loadingOverlay.visibility = View.VISIBLE
        loadingText.text = "Đang tải ${episode.title} (${episode.season})…"
        destroyWebPlayer()
        updateSourceSpecificOsdButtons()
        surfaceView.visibility = View.VISIBLE
        if (::subtitleView.isInitialized) subtitleView.visibility = View.VISIBLE

        resolveJob?.cancel()
        resolveJob = uiScope.launch {
            try {
                val aetherMode = isAetherEpisode(episode)
                if (aetherMode) {
                    loadingProviderBadge.visibility = View.VISIBLE
                    loadingProviderBadge.text = "⚡ Đang khởi tạo máy chủ…"
                    loadingProgressBar.visibility = View.VISIBLE
                    loadingProgressBar.isIndeterminate = true
                    loadingHintText.visibility = View.GONE
                } else {
                    loadingProviderBadge.visibility = View.GONE
                }

                val activeServerId = if (currentAetherProviderId.isNotBlank() && currentAetherProviderId != "auto") {
                    currentAetherProviderId
                } else {
                    episode.episodeKey.takeIf { it.isNotBlank() && it != "auto" } ?: "auto"
                }
                if (activeServerId != "auto") {
                    triedAetherScrapers.add(activeServerId.lowercase(Locale.US))
                }

                val resolved = withContext(Dispatchers.IO) {
                    CatalogService.resolvePlayback(
                        episode = episode,
                        preferredServerId = activeServerId,
                        onProgress = { sourceName, current, total ->
                            uiScope.launch {
                                if (generation != playbackGeneration) return@launch
                                loadingProviderBadge.visibility = View.VISIBLE
                                loadingProviderBadge.text = "⚡ Đang kiểm tra: $sourceName ($current/$total)"
                                loadingText.text = "Đang quét nguồn $sourceName…"
                                loadingProgressBar.visibility = View.VISIBLE
                            }
                        }
                    )
                }
                if (generation != playbackGeneration) return@launch
                if (resolved.providerId.isNotBlank()) {
                    triedAetherScrapers.add(resolved.providerId.lowercase(Locale.US))
                    val pName = AetherSourceResolver.SERVERS.firstOrNull {
                        it.id.equals(resolved.providerId, ignoreCase = true)
                    }?.name ?: resolved.providerId
                    topServerText.text = "${episode.title} • Nguồn $pName"
                }
                lastWebUrl = resolved.webUrl.ifBlank { episode.url }
                attemptedStreamUrls.add(resolved.url)
                if (resolved.isWeb && !aetherMode) {
                    val resumeSeekMs = if (resetPosition) 0L else initialPositionMs
                    startWebFallback(resolved.url, resolved.headers["Referer"] ?: episode.url, resumeSeekMs)
                    return@launch
                }
                val needsRelay = resolved.isF4kWrapped ||
                    resolved.url.contains("/stream/hls", ignoreCase = true) ||
                    resolved.url.contains("streamc.xyz", ignoreCase = true) ||
                    (resolved.url.substringBefore('?').lowercase().endsWith(".m3u8") && resolved.url.contains("film4k", ignoreCase = true))
                val hlsHint = resolved.mediaKind == StreamMediaKind.HLS ||
                    resolved.url.contains(".m3u8", true) ||
                    resolved.url.contains("streamc.xyz", true)
                val ua = resolved.headers["User-Agent"] ?: CatalogService.USER_AGENT
                val playUrl = if (needsRelay) {
                    filmRelay?.stop()
                    val relay = FilmMediaRelay(resolved.headers, ua)
                    filmRelay = relay
                    withContext(Dispatchers.IO) {
                        relay.start(resolved.url, isHlsHint = hlsHint)
                    }
                } else {
                    filmRelay?.stop()
                    filmRelay = null
                    resolved.url
                }
                // For MKV / VIP streams, prefer FFmpeg audio decoder so 5.1 AC3/EAC3/DTS/FLAC/TrueHD never fails silently
                if (resolved.mediaKind == StreamMediaKind.MKV || playUrl.lowercase().contains(".mkv")) {
                    preferSoftwareAudio = true
                }
                prepareExoPlayer(
                    streamUrl = playUrl,
                    headers = if (needsRelay) emptyMap() else resolved.headers,
                    seekMs = if (resetPosition) 0L else initialPositionMs,
                    mediaKind = resolved.mediaKind,
                    subtitles = resolved.subtitles
                )
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                if (generation != playbackGeneration) return@launch
                if (!trySwitchToBackupServer(0L)) {
                    if (!isAetherEpisode(episode) && episode.url.startsWith("http", ignoreCase = true) && !episode.source.isMovie) {
                        startWebFallback(episode.url, CatalogService.currentAnimeBaseUrl())
                    } else {
                        loadingOverlay.visibility = View.VISIBLE
                        loadingText.text = "Không tải được luồng phát: ${t.message ?: "Nguồn tạm thời không phản hồi"}\nBấm phím Lên/Xuống -> Đổi Server khác."
                        showOsd(focusPlayButton = true)
                    }
                }
            }
        }
    }

    private fun prepareExoPlayer(
        streamUrl: String,
        headers: Map<String, String>,
        seekMs: Long,
        mediaKind: StreamMediaKind = StreamMediaKind.UNKNOWN,
        subtitles: List<ExternalSubtitleTrack> = emptyList()
    ) {
        releasePlayer(stopRelay = false)
        if (translatedEpisodeKey.isNotEmpty() && !translatedEpisodeKey.startsWith("${currentEpisode.slug}|")) {
            translatedEpisodeKey = ""
            selectedSourceSubUrl = ""
            viLiveCues = emptyList()
            viTranslatedMap.clear()
            stopLiveViSubtitles(clearView = true)
        }
        lastNativeUrl = streamUrl
        lastNativeHeaders = headers
        lastMediaKind = mediaKind
        lastSubtitles = subtitles
        destroyWebPlayer(force = false)
        surfaceView.visibility = View.VISIBLE
        if (::subtitleView.isInitialized) {
            subtitleView.visibility = View.VISIBLE
            applySubtitleScale()
        }
        prewarmSourceSubtitle(subtitles, streamUrl, headers)

        val selector = DefaultTrackSelector(this).apply {
            parameters = buildUponParameters()
                .setPreferredAudioLanguages("vi", "vie", "vn", "en", "eng", "ja", "jpn", "ko", "kor", "zh", "chi")
                .setPreferredTextLanguages("vi", "vie", "vn", "en", "eng")
                .setSelectUndeterminedTextLanguage(true)
                .setExceedAudioConstraintsIfNecessary(true)
                .setExceedRendererCapabilitiesIfNecessary(true)
                .apply {
                    when (currentQualityMode) {
                        0 -> setMaxVideoSize(1920, 1080).setMaxVideoBitrate(12_000_000)
                        1 -> setMaxVideoSize(1280, 720).setMaxVideoBitrate(3_500_000)
                        else -> clearVideoSizeConstraints().setMaxVideoBitrate(Int.MAX_VALUE)
                    }
                }
                .build()
        }
        trackSelector = selector

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                15_000, // minBufferMs (prevents 3s stalls on 1080p HLS segments)
                45_000, // maxBufferMs
                2_500,  // bufferForPlaybackMs
                4_000   // bufferForPlaybackAfterRebufferMs
            )
            .setTargetBufferBytes(32 * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val renderersFactory = ProjectorRenderersFactory(this)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(
                if (preferSoftwareAudio || mediaKind == StreamMediaKind.MKV) {
                    DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
                } else {
                    DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
                }
            )

        attemptedStreamUrls.add(streamUrl)

        val mergedHeaders = HashMap(headers)
        WebViewHelper.getCookieHeader(streamUrl)?.let { mergedHeaders["Cookie"] = it }
        lastNativeHeaders = mergedHeaders

        val ua = headers["User-Agent"] ?: AetherSourceResolver.USER_AGENT
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(ua)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(25_000)
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(mergedHeaders)

        // External & local subtitle sources use OkHttp + cookie jar + charset/SRT normalization
        val subMimeMap = HashMap<String, String>(subtitles.size)
        val hasPendingSelect = pendingSelectSubtitleUrl.isNotBlank() &&
            subtitles.any { it.url == pendingSelectSubtitleUrl }
        val firstVietIdx = subtitles.indexOfFirst {
            it.language.equals("vi", true) || it.label.contains("Việt", true)
        }
        val firstEngIdx = subtitles.indexOfFirst {
            it.language.equals("en", true) || it.label.contains("English", true) || it.label.contains("Anh", true)
        }
        val subtitleConfigs = subtitles.mapIndexed { idx, sub ->
            val subMime = sub.mimeType ?: SubtitleSupport.detectSubtitleFormat(sub.url)
            subMimeMap[sub.url] = subMime
            val isViet = sub.language.lowercase().startsWith("vi") ||
                sub.label.lowercase().contains("việt") ||
                sub.label.lowercase().contains("viet")
            val isEng = sub.language.lowercase().startsWith("en") ||
                sub.label.lowercase().contains("english")
            val flags = when {
                hasPendingSelect && sub.url == pendingSelectSubtitleUrl -> {
                    C.SELECTION_FLAG_DEFAULT or C.SELECTION_FLAG_FORCED or C.SELECTION_FLAG_AUTOSELECT
                }
                !hasPendingSelect && firstVietIdx >= 0 && idx == firstVietIdx -> {
                    C.SELECTION_FLAG_DEFAULT or C.SELECTION_FLAG_AUTOSELECT
                }
                !hasPendingSelect && firstVietIdx < 0 && firstEngIdx >= 0 && idx == firstEngIdx -> {
                    C.SELECTION_FLAG_DEFAULT or C.SELECTION_FLAG_AUTOSELECT
                }
                !hasPendingSelect && (isViet || isEng) -> C.SELECTION_FLAG_AUTOSELECT
                else -> 0
            }
            MediaItem.SubtitleConfiguration.Builder(Uri.parse(sub.url))
                .setId(sub.url)
                .setMimeType(subMime)
                .setLanguage(sub.language)
                .setLabel(sub.label)
                .setSelectionFlags(flags)
                .build()
        }
        if (hasPendingSelect) {
            pendingSelectSubtitleUrl = ""
        }
        val subDataSourceFactory = SubtitleSupport.createExoSubtitleDataSourceFactory(
            context = this,
            baseClient = aetherHttpClient,
            mimeByUrl = subMimeMap
        )

        val cleanLower = streamUrl.lowercase()
        val effectiveKind = if (mediaKind != StreamMediaKind.UNKNOWN) {
            mediaKind
        } else {
            when {
                cleanLower.contains(".m3u8") ||
                    cleanLower.contains("/hls/") ||
                    cleanLower.contains("/stream/hls") ||
                    cleanLower.contains("format=m3u8") ||
                    cleanLower.contains("m3u8") -> StreamMediaKind.HLS
                cleanLower.contains(".mpd") -> StreamMediaKind.DASH
                cleanLower.contains(".mkv") -> StreamMediaKind.MKV
                else -> StreamMediaKind.MP4
            }
        }

        val mediaItem = MediaItem.Builder()
            .setUri(Uri.parse(streamUrl))
            .apply {
                when (effectiveKind) {
                    StreamMediaKind.HLS -> setMimeType(MimeTypes.APPLICATION_M3U8)
                    StreamMediaKind.DASH -> setMimeType(MimeTypes.APPLICATION_MPD)
                    StreamMediaKind.MKV -> setMimeType(MimeTypes.VIDEO_MATROSKA)
                    else -> {}
                }
            }
            .build()

        val aetherStream = isAetherEpisode()
        val smoothHlsStream = aetherStream || currentEpisode.source == ContentSource.FILM4K || streamUrl.contains("127.0.0.1")
        val videoMediaSource: MediaSource = when (effectiveKind) {
            StreamMediaKind.HLS -> {
                val hlsExtractorFactory = if (smoothHlsStream) {
                    DefaultHlsExtractorFactory(0, true)
                } else {
                    DefaultHlsExtractorFactory(
                        DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
                            DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS,
                        true
                    )
                }
                HlsMediaSource.Factory(httpFactory)
                    .setExtractorFactory(hlsExtractorFactory)
                    .setAllowChunklessPreparation(smoothHlsStream)
                    .createMediaSource(mediaItem)
            }
            StreamMediaKind.DASH -> {
                DashMediaSource.Factory(httpFactory).createMediaSource(mediaItem)
            }
            else -> {
                ProgressiveMediaSource.Factory(
                    httpFactory,
                    DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(true)
                ).createMediaSource(mediaItem)
            }
        }

        val mediaSource: MediaSource = if (subtitleConfigs.isEmpty()) {
            videoMediaSource
        } else {
            val singleSampleFactory = SingleSampleMediaSource.Factory(subDataSourceFactory)
                .setTreatLoadErrorsAsEndOfStream(true)
            val allSources = ArrayList<MediaSource>(1 + subtitleConfigs.size)
            allSources.add(videoMediaSource)
            for (cfg in subtitleConfigs) {
                allSources.add(singleSampleFactory.createMediaSource(cfg, C.TIME_UNSET))
            }
            MergingMediaSource(*allSources.toTypedArray())
        }

        val player = ExoPlayer.Builder(this, renderersFactory)
            .setTrackSelector(selector)
            .setLoadControl(loadControl)
            .build()

        // Fast keyframe seeking for projector hardware decoder
        player.setSeekParameters(SeekParameters.CLOSEST_SYNC)
        player.setAudioAttributes(androidx.media3.common.AudioAttributes.DEFAULT, true)
        player.setHandleAudioBecomingNoisy(true)

        player.videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT
        player.setVideoSurfaceView(surfaceView)

        player.addListener(object : Player.Listener {
            override fun onCues(cueGroup: CueGroup) {
                if (exoPlayer === player && !viLiveSubActive) subtitleView.setCues(cueGroup.cues)
            }

            override fun onTracksChanged(tracks: Tracks) {
                if (exoPlayer !== player) return
                if (tracks.groups.any { it.type == C.TRACK_TYPE_AUDIO } &&
                    tracks.groups.none { it.type == C.TRACK_TYPE_AUDIO && it.isSupported }) {
                    mainHandler.post {
                        if (exoPlayer === player) startCompatibilityPlayback(player.currentPosition)
                    }
                }
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (exoPlayer !== player) return
                when (state) {
                    Player.STATE_BUFFERING -> {
                        if (!scrubbing && pendingSeekTargetMs < 0L) {
                            loadingOverlay.visibility = View.VISIBLE
                            loadingText.text = "Đang nạp bộ đệm…"
                        }
                    }
                    Player.STATE_READY -> {
                        loadingOverlay.visibility = View.GONE
                        retryCount = 0
                        updateProgressUi()
                        scheduleHideOsd()
                    }
                    Player.STATE_ENDED -> {
                        loadingOverlay.visibility = View.GONE
                        playNextEpisode()
                    }
                    Player.STATE_IDLE -> {}
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (exoPlayer !== player) return
                updatePlayPauseIcon(isPlaying)
                if (isPlaying) {
                    scheduleHideOsd()
                }
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                lastVideoW = videoSize.width
                lastVideoH = videoSize.height
                adjustSurfaceAspectRatio(videoSize.width, videoSize.height)
            }

            override fun onPlayerError(error: PlaybackException) {
                if (exoPlayer !== player) return
                val lastPos = player.currentPosition.coerceAtLeast(0L)
                if (error.errorCode in 5000..5999) {
                    mainHandler.post { if (exoPlayer === player) startCompatibilityPlayback(lastPos) }
                    return
                }
                if (error.errorCode in 4000..4999) {
                    mainHandler.post {
                        if (exoPlayer !== player) return@post
                        if (!trySwitchToBackupServer(lastPos)) {
                            loadingText.text = "Máy không giải mã được luồng này. Hãy chọn server khác."
                            loadingOverlay.visibility = View.VISIBLE
                            showOsd(true)
                        }
                    }
                    return
                }
                if (retryCount < 1) {
                    retryCount++
                    loadingOverlay.visibility = View.VISIBLE
                    loadingText.text = "Đang tự động khôi phục kết nối…"
                    player.prepare()
                    if (lastPos > 0L) player.seekTo(lastPos)
                    player.play()
                } else {
                    mainHandler.post {
                        if (exoPlayer !== player) return@post
                        if (isAetherEpisode()) {
                            if (!trySwitchToBackupServer(lastPos)) {
                                loadingOverlay.visibility = View.VISIBLE
                                loadingText.text = "Các máy chủ Aether tạm thời gián đoạn.\nHãy chọn [Đổi Server] ở thanh bên dưới."
                                showOsd(focusPlayButton = true)
                            }
                            return@post
                        }
                        if (currentEpisode.source.isMovie) {
                            if (!trySwitchToBackupServer(lastPos)) startCompatibilityPlayback(lastPos)
                            return@post
                        }
                        val fallbackUrl = when {
                            lastWebUrl.startsWith("http", ignoreCase = true) &&
                                (lastWebUrl.contains("embed", ignoreCase = true) ||
                                    lastWebUrl.contains("player", ignoreCase = true) ||
                                    !currentEpisode.source.isMovie) -> lastWebUrl
                            currentEpisode.url.contains("embed", ignoreCase = true) ||
                                currentEpisode.url.contains("player", ignoreCase = true) -> currentEpisode.url
                            else -> ""
                        }

                        if (fallbackUrl.isNotBlank()) {
                            startWebFallback(fallbackUrl, currentEpisode.url, initialSeekMs = lastPos, allowNativeHandoff = false)
                        } else if (!trySwitchToBackupServer(lastPos)) {
                            loadingOverlay.visibility = View.VISIBLE
                            loadingText.text = "Máy chủ hiện tại bị gián đoạn.\nHãy chọn [Đổi Server] ở thanh bên dưới."
                            showOsd(focusPlayButton = true)
                        }
                    }
                }
            }
        })

        exoPlayer = player
        player.setMediaSource(mediaSource)
        player.prepare()
        if (seekMs > 0L) {
            player.seekTo(seekMs)
        }
        if (currentPlaybackSpeed != 1.0f) {
            player.setPlaybackSpeed(currentPlaybackSpeed)
        }
        player.playWhenReady = true
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun startWebFallback(
        webUrl: String,
        referer: String,
        initialSeekMs: Long = 0L,
        allowNativeHandoff: Boolean = true
    ) {
        releasePlayer()
        destroyWebPlayer()
        val aetherMode = isAetherEpisode()
        allowNativeWebHandoff = allowNativeHandoff
        // NEVER set surfaceView to GONE - keep it visible so its native Surface is preserved and never black-screens!
        surfaceView.visibility = View.VISIBLE
        if (::subtitleView.isInitialized) subtitleView.visibility = View.VISIBLE
        loadingOverlay.visibility = View.VISIBLE

        if (aetherMode) {
            val compat = WebViewHelper.checkAetherWebViewCompatibility(this)
            if (!compat.isSupported) {
                loadingText.text = compat.message
                showOsd(focusPlayButton = true)
                showPlayerMessage("WebView không tương thích", compat.message)
                return
            }
            val activeP = availableAetherProviders.firstOrNull { it.id == currentAetherProviderId }?.name ?: "Tự động"
            loadingProviderBadge.visibility = View.VISIBLE
            loadingProviderBadge.text = "⚡ Đang kiểm tra: $activeP"
            loadingText.text = "Đang tìm kiếm luồng phát tốt nhất (${currentEpisode.title})…"
            loadingProgressBar.visibility = View.VISIBLE
            loadingProgressBar.isIndeterminate = true
            loadingHintText.visibility = View.VISIBLE
        } else {
            loadingProviderBadge.visibility = View.GONE
            loadingProgressBar.visibility = View.GONE
            loadingHintText.visibility = View.GONE
            loadingText.text = "Đang mở trình phát dự phòng…"
        }

        val wv = try {
            WebView(this).apply {
                setBackgroundColor(Color.TRANSPARENT)
                setLayerType(View.LAYER_TYPE_HARDWARE, null)
                visibility = View.INVISIBLE
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    Gravity.CENTER
                )
                isFocusable = false
                isFocusableInTouchMode = false
            }
        } catch (t: Throwable) {
            loadingText.text = "Không thể khởi tạo Android System WebView: ${t.message}"
            showOsd(focusPlayButton = true)
            return
        }
        openingFrame = false
        visitedFrames.add(webUrl)
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(wv, true)

        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            userAgentString = if (aetherMode) CatalogService.USER_AGENT else CatalogService.animeUserAgent()
            allowContentAccess = true
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
        }

        wv.addJavascriptInterface(object {
            @JavascriptInterface
            fun onVideoFound(streamUrl: String, refUrl: String) {
                mainHandler.post {
                    if (fallbackWebView !== wv) return@post
                    if (!allowNativeWebHandoff) return@post
                    if (streamUrl.startsWith("http", ignoreCase = true) && exoPlayer == null) {
                        if (attemptedStreamUrls.contains(streamUrl)) {
                            return@post
                        }
                        attemptedStreamUrls.add(streamUrl)
                        val origin = try {
                            val u = java.net.URL(refUrl.ifBlank { webUrl })
                            "${u.protocol}://${u.host}"
                        } catch (_: Throwable) {
                            ""
                        }
                        val hdrs = HashMap<String, String>()
                        hdrs["User-Agent"] = CatalogService.animeUserAgent()
                        if (origin.isNotBlank()) {
                            hdrs["Referer"] = "$origin/"
                            hdrs["Origin"] = origin
                        }
                        val cookieHeader = WebViewHelper.getCookieHeader(streamUrl)
                            ?: WebViewHelper.getCookieHeader(refUrl.ifBlank { webUrl })
                        if (!cookieHeader.isNullOrBlank()) {
                            hdrs["Cookie"] = cookieHeader
                        }
                        val effectiveKind = when {
                            streamUrl.contains(".m3u8", ignoreCase = true) -> StreamMediaKind.HLS
                            streamUrl.contains(".mpd", ignoreCase = true) -> StreamMediaKind.DASH
                            streamUrl.contains(".mp4", ignoreCase = true) -> StreamMediaKind.MP4
                            streamUrl.contains(".mkv", ignoreCase = true) -> StreamMediaKind.MKV
                            else -> StreamMediaKind.UNKNOWN
                        }
                        prepareExoPlayer(streamUrl, hdrs, webPositionMs, effectiveKind, lastSubtitles)
                    }
                }
            }

            @JavascriptInterface
            fun onAetherSourcesLoaded(sourcesJson: String) {
                mainHandler.post {
                    if (fallbackWebView !== wv) return@post
                    handleAetherSourcesLoaded(sourcesJson)
                }
            }

            @JavascriptInterface
            fun onAetherScrapingProgress(providerName: String, providerId: String, statusText: String, percentage: Int) {
                mainHandler.post {
                    if (fallbackWebView !== wv) return@post
                    if (exoPlayer != null) return@post
                    updateScrapingOverlay(providerName, providerId, statusText, percentage)
                }
            }

            @JavascriptInterface
            fun onAetherResolved(streamUrl: String, streamType: String, headersJson: String, captionsJson: String, positionSec: Float) {
                handleAetherResolved(streamUrl, streamType, headersJson, captionsJson, positionSec, wv)
            }

            @JavascriptInterface
            fun onAetherResolved(streamUrl: String, headersJson: String, captionsJson: String, positionSec: Float) {
                handleAetherResolved(streamUrl, "", headersJson, captionsJson, positionSec, wv)
            }

            @JavascriptInterface
            fun onAetherSubtitlesUpdated(captionsJson: String) {
                mainHandler.post {
                    if (fallbackWebView !== wv) return@post
                    val subs = mutableListOf<ExternalSubtitleTrack>()
                    try {
                        if (captionsJson.isNotBlank() && captionsJson != "[]") {
                            val arr = JSONArray(captionsJson)
                            for (i in 0 until arr.length()) {
                                val c = arr.optJSONObject(i) ?: continue
                                val cUrl = c.optString("url")
                                if (cUrl.isBlank()) continue
                                val cLang = c.optString("language", "vi")
                                val cLabel = c.optString("label").ifBlank {
                                    if (cLang.startsWith("vi", ignoreCase = true)) "Tiếng Việt"
                                    else if (cLang.startsWith("en", ignoreCase = true)) "Tiếng Anh"
                                    else cLang.uppercase()
                                }
                                val mime = when {
                                    cUrl.contains(".vtt", ignoreCase = true) -> MimeTypes.TEXT_VTT
                                    cUrl.contains(".ass", ignoreCase = true) || cUrl.contains(".ssa", ignoreCase = true) -> MimeTypes.TEXT_SSA
                                    else -> MimeTypes.APPLICATION_SUBRIP
                                }
                                subs.add(ExternalSubtitleTrack(url = cUrl, language = cLang, label = cLabel, mimeType = mime))
                            }
                        }
                    } catch (_: Throwable) {}
                    if (subs.isEmpty()) return@post

                    val hadVi = lastSubtitles.any { it.language.startsWith("vi", ignoreCase = true) }
                    val newVi = subs.firstOrNull { it.language.startsWith("vi", ignoreCase = true) }
                    val merged = lastSubtitles.toMutableList()
                    var added = 0
                    for (s in subs) {
                        if (merged.none { it.url == s.url }) {
                            merged.add(s)
                            added++
                        }
                    }
                    if (added > 0) {
                        lastSubtitles = merged
                        if (!hadVi && newVi != null && exoPlayer != null) {
                            val pos = exoPlayer?.currentPosition ?: 0L
                            prepareExoPlayer(lastNativeUrl, lastNativeHeaders, pos, lastMediaKind, merged)
                            showPlayerMessage("Phụ đề", "Đã tự động tải phụ đề Tiếng Việt (${newVi.label})")
                        }
                    }
                }
            }

            @JavascriptInterface
            fun onAetherTranslatedSubtitle(srtText: String, lang: String, label: String) {
                mainHandler.post {
                    if (fallbackWebView !== wv) return@post
                    if (srtText.isBlank()) return@post
                    try {
                        val subFile = File(cacheDir, "aether_translated_${System.currentTimeMillis()}.srt")
                        subFile.writeText(srtText)
                        val translatedTrack = ExternalSubtitleTrack(
                            url = Uri.fromFile(subFile).toString(),
                            language = lang.ifBlank { "vi" },
                            label = label.ifBlank { "🇻🇳 Tiếng Việt (Bản dịch AI)" },
                            mimeType = MimeTypes.APPLICATION_SUBRIP
                        )
                        val updated = mutableListOf(translatedTrack)
                        updated.addAll(lastSubtitles.filterNot { it.label.contains("Bản dịch AI") })
                        lastSubtitles = updated

                        val player = exoPlayer
                        if (player != null) {
                            val curPos = player.currentPosition
                            prepareExoPlayer(lastNativeUrl, lastNativeHeaders, curPos, lastMediaKind, updated)
                            showPlayerMessage("Phụ đề", "Đã bật phụ đề Tiếng Việt (Bản dịch AI)!")
                        }
                    } catch (t: Throwable) {
                        android.util.Log.e("PlayerActivity", "Failed to save translated subtitle", t)
                    }
                }
            }

            @JavascriptInterface
            fun onPlayerReady() {
                mainHandler.post {
                    if (fallbackWebView !== wv) return@post
                    wv.visibility = View.VISIBLE
                    loadingOverlay.visibility = View.GONE
                    applySubtitleScale()
                    if (currentPlaybackSpeed != 1.0f) {
                        sendWebCommand("rate", currentPlaybackSpeed.toDouble())
                    }
                    scheduleHideOsd()
                }
            }

            @JavascriptInterface
            fun onChallengeRequired() {
                mainHandler.post {
                    if (fallbackWebView !== wv) return@post
                    if (exoPlayer != null) return@post
                    loadingOverlay.visibility = View.GONE
                    wv.visibility = View.VISIBLE
                    wv.isFocusable = true
                    wv.isFocusableInTouchMode = true
                    wv.requestFocus()
                }
            }

            @JavascriptInterface
            fun onAetherScrapeFailed() {
                mainHandler.post {
                    if (fallbackWebView !== wv) return@post
                    if (!trySwitchToBackupServer(webPositionMs)) {
                        loadingOverlay.visibility = View.VISIBLE
                        loadingText.text = "Aether đã dò hết các máy chủ nhưng chưa tìm thấy luồng phát cho phim này.\nHãy thử lại hoặc chọn phim/nguồn khác."
                        showOsd(focusPlayButton = true)
                    }
                }
            }

            @JavascriptInterface
            fun onFrameFound(url: String, referer: String) {
                mainHandler.post {
                    if (aetherMode) return@post
                    if (fallbackWebView !== wv || openingFrame || visitedFrames.size >= 4 ||
                        url in visitedFrames || !url.startsWith("https://")) return@post
                    openingFrame = true
                    startWebFallback(url, referer)
                }
            }

            @JavascriptInterface
            fun onProgress(position: Double, duration: Double, paused: Boolean) {
                if (!position.isFinite() || !duration.isFinite()) return
                mainHandler.post {
                    if (fallbackWebView !== wv) return@post
                    if (duration > 0.5 && position > 0.0 && loadingOverlay.visibility == View.VISIBLE) {
                        wv.visibility = View.VISIBLE
                        loadingOverlay.visibility = View.GONE
                    }
                    webPositionMs = (position * 1000).toLong().coerceAtLeast(0)
                    webDurationMs = (duration * 1000).toLong().coerceAtLeast(0)
                    webPaused = paused
                    updatePlayPauseIcon(!paused)
                    updateProgressUi()
                }
            }

            @JavascriptInterface
            fun onAetherMenuState(isOpen: Boolean) {
                mainHandler.post {
                    if (fallbackWebView !== wv) return@post
                    val wasOpen = aetherMenuOpen
                    aetherMenuOpen = isOpen
                    if (isOpen) {
                        wv.visibility = View.VISIBLE
                        loadingOverlay.visibility = View.GONE
                        hideOsd()
                    } else if (wasOpen) {
                        showOsd(focusPlayButton = true)
                    }
                }
            }

            @JavascriptInterface
            fun onAetherStatus(statusText: String) {
                mainHandler.post {
                    if (fallbackWebView !== wv) return@post
                    aetherStatusNote = statusText.trim()
                    if (aetherStatusNote.isNotEmpty()) {
                        if (loadingOverlay.visibility == View.VISIBLE) {
                            loadingText.text = aetherStatusNote
                        }
                        val baseLabel = "${currentEpisode.title} • ${currentEpisode.season}"
                        topServerText.text = "$baseLabel • $aetherStatusNote"
                    }
                }
            }

            @JavascriptInterface
            fun onMediaState(stateJson: String) {
                mainHandler.post {
                    if (fallbackWebView !== wv) return@post
                    try {
                        val root = JSONObject(stateJson)
                        val pos = root.optDouble("time", 0.0)
                        val dur = root.optDouble("duration", 0.0)
                        val paused = root.optBoolean("paused", true)
                        if (pos.isFinite() && dur.isFinite()) {
                            if (dur > 0.5 && wv.visibility != View.VISIBLE) {
                                wv.visibility = View.VISIBLE
                                loadingOverlay.visibility = View.GONE
                            }
                            webPositionMs = (pos * 1000).toLong().coerceAtLeast(0L)
                            webDurationMs = (dur * 1000).toLong().coerceAtLeast(0L)
                            webPaused = paused
                            updatePlayPauseIcon(!paused)
                            updateProgressUi()
                        }
                        val tracksObj = root.optJSONObject("tracks")
                        if (tracksObj != null) {
                            val audioArr = tracksObj.optJSONArray("audio")
                            if (audioArr != null) {
                                val list = ArrayList<WebTrackItem>()
                                val curAid = tracksObj.optString("audioID")
                                for (i in 0 until audioArr.length()) {
                                    val t = audioArr.optJSONObject(i) ?: continue
                                    val tid = t.optString("id")
                                    val title = t.optString("title").ifEmpty { "Âm thanh ${i + 1}" }
                                    list.add(WebTrackItem(tid, title, tid == curAid))
                                }
                                webAudioTracks = list
                            }
                            val subArr = tracksObj.optJSONArray("subtitles")
                            if (subArr != null) {
                                val list = ArrayList<WebTrackItem>()
                                val curSid = tracksObj.optString("subtitleID")
                                for (i in 0 until subArr.length()) {
                                    val t = subArr.optJSONObject(i) ?: continue
                                    val tid = t.optString("id")
                                    val title = t.optString("title").ifEmpty { if (tid == "off") "Tắt" else "Phụ đề ${i + 1}" }
                                    list.add(WebTrackItem(tid, title, tid == curSid))
                                }
                                webSubtitleTracks = list
                            }
                            val qualArr = tracksObj.optJSONArray("qualities")
                            if (qualArr != null) {
                                val list = ArrayList<WebTrackItem>()
                                val curQid = tracksObj.optString("qualityID")
                                for (i in 0 until qualArr.length()) {
                                    val q = qualArr.optJSONObject(i) ?: continue
                                    val qid = q.optString("id")
                                    val title = q.optString("title").ifEmpty { qid }
                                    list.add(WebTrackItem(qid, title, qid == curQid))
                                }
                                webQualities = list
                                if (aetherMode) {
                                    updateSourceSpecificOsdButtons()
                                }
                            }
                        }
                    } catch (_: Throwable) {}
                }
            }
        }, "AnPinimeWebBridge")

        val fallbackIsolateScript = """
            (function(){
              if(window.__anpinimeInstalled) return;
              window.__anpinimeInstalled = true;
              var streamC = /(^|\.)streamc\.xyz$/i.test(location.hostname);
              function wrapJW(factory){
                if(!streamC || typeof factory !== 'function' || factory.__anpinimeWrapped) return factory;
                var wrapped = new Proxy(factory, {
                  apply: function(target, receiver, args){
                    var api = Reflect.apply(target, receiver, args);
                    if(api && typeof api.setup === 'function' && !api.__anpinimeSetup){
                      var setup = api.setup;
                      api.setup = function(config){
                        var clean = Object.assign({}, config, {autostart: true});
                        delete clean.advertising;
                        return setup.call(this, clean);
                      };
                      api.__anpinimeSetup = true;
                    }
                    return api;
                  }
                });
                Object.defineProperty(wrapped, '__anpinimeWrapped', {value: true});
                return wrapped;
              }
              if(streamC){
                var currentJW = wrapJW(window.jwplayer), descriptor = Object.getOwnPropertyDescriptor(window, 'jwplayer');
                if(!descriptor || descriptor.configurable){
                  Object.defineProperty(window, 'jwplayer', {
                    configurable: true,
                    get: function(){ return currentJW; },
                    set: function(v){ currentJW = wrapJW(v); }
                  });
                } else if(typeof window.jwplayer === 'function'){
                  window.jwplayer = wrapJW(window.jwplayer);
                }
              }
              function needsWebsiteDecoder(){
                return streamC && window.streamAccess && window.streamAccess.shouldUseSealedLoader && window.streamAccess.shouldUseSealedLoader() === true;
              }
              var hidden = [
                '.Adv', '.ad-center', '.ads', '.advertisement', '[class*="ads"]', '[class*="banner"]', '[id*="ads"]',
                'iframe[src*="ads"]', '.popup', '.modal-backdrop', '.header-ads-pc', '.Adv.ad-center-header',
                'header', 'footer', '.footer', '.header', '.sidebar', '.breadcrumb', '.comments', '.film-info',
                '.heading-film', '.list-episode-box', '.server-list', '.film-thumb', '.film-desc', '.film-buttons',
                '.category-heading', '.block-film', 'nav', '.navbar', '.menu'
              ];
              var adSelectors = '.ad,.ads,.advertisement,.video-ad,.ima-ad-container,.jw-ad,.jw-flag-ads,.vjs-ad-playing,[data-ad="true"]';
              function adURL(s){
                return /(?:doubleclick|googlesyndication|popads|adservice|vast|\/ads?[\/.?]|[?&](?:ad|preroll)=)/i.test(s || '');
              }
              function playerAPI(v){
                try{
                  if(typeof window.jwplayer !== 'function') return null;
                  var container = v && v.closest('.jwplayer');
                  var api = container ? window.jwplayer(container.id) : window.jwplayer();
                  if(!api || typeof api.getPlaylistItem !== 'function') return null;
                  if(!api.__aaAdEvents && api.on){
                    api.__aaAdEvents = true;
                    ['adPlay','adImpression','adStarted'].forEach(function(e){ api.on(e, function(){ api.__aaAdActive = true; }); });
                    ['adBreakEnd','adComplete','adSkipped','adError'].forEach(function(e){ api.on(e, function(){ api.__aaAdActive = false; }); });
                  }
                  return api;
                }catch(e){ return null; }
              }
              function contentSources(api){
                try{
                  var item = api && api.getPlaylistItem(); if(!item) return [];
                  return (item.sources||[]).map(function(x){ return x.file||x.src; }).concat(item.file||[])
                    .filter(function(x){ return typeof x === 'string' && x && !adURL(x); })
                    .map(function(x){ return new URL(x, location.href).href; });
                }catch(e){ return []; }
              }
              function isAdvertisement(v){
                if(!v || v.closest(adSelectors) || adURL(mediaURL(v))) return true;
                var api = playerAPI(v);
                return !!(api && api.__aaAdActive);
              }
              var reportedFrames = {};
              var activeVideo = null;
              var detectedStreams = [], reportedSources = {};
              function hasBlobVideoElement(){
                var videos = document.querySelectorAll('video');
                for (var i = 0; i < videos.length; i++) {
                  var s = videos[i].currentSrc || videos[i].getAttribute('src') || '';
                  if (/^blob:/i.test(s)) return true;
                }
                return false;
              }
              function sendVideo(url, ref) {
                try { AnPinimeWebBridge.onVideoFound(url, ref); } catch(e){}
              }
              function sendReady() {
                try { AnPinimeWebBridge.onPlayerReady(); } catch(e){}
              }
              function observeStream(url){
                try {
                  url = new URL(String(url), location.href).href;
                  if (/\.(?:m3u8|mp4)(?:[?#]|$)/i.test(url) && !adURL(url) && detectedStreams.indexOf(url) < 0) {
                    detectedStreams.push(url);
                    if (!needsWebsiteDecoder() && !hasBlobVideoElement()) sendVideo(url, location.href);
                  }
                } catch(e){}
              }
              var originalFetch = window.fetch;
              if (originalFetch) window.fetch = function(){
                var input = arguments[0];
                observeStream(input && input.url || input);
                return originalFetch.apply(this, arguments);
              };
              var originalOpen = XMLHttpRequest.prototype.open;
              XMLHttpRequest.prototype.open = function(method, url){
                observeStream(url);
                return originalOpen.apply(this, arguments);
              };
              function mediaURL(v){
                var s = v.currentSrc || v.getAttribute('src') || '';
                if (!s) { var src = v.querySelector('source[src]'); if (src) s = src.getAttribute('src') || ''; }
                try { return s ? new URL(s, location.href).href : ''; } catch(e) { return ''; }
              }
              function contentURL(v){
                var s = mediaURL(v), api = playerAPI(v), sources = contentSources(api);
                if(api){
                  if(!sources.length) return '';
                  if(sources.indexOf(s) >= 0) return s;
                  return sources.find(function(x){ return /\.m3u8(?:[?#]|$)/i.test(x); }) || sources[0];
                }
                if(isAdvertisement(v)) return '';
                return /^https?:/i.test(s) && !adURL(s) ? s : '';
              }
              function reportSource(v){
                if(needsWebsiteDecoder() || hasBlobVideoElement()) return;
                var s = contentURL(v);
                if (!/^https?:/i.test(s)) s = detectedStreams[detectedStreams.length - 1] || '';
                if (/^https?:/i.test(s) && !reportedSources[s]){
                  reportedSources[s] = true;
                  sendVideo(s, location.href);
                }
              }
              function tryPlay(v){
                if (!v || v.__aaUserPaused || !v.paused) return;
                var p = v.play(); if (p && p.catch) p.catch(function(){});
              }
              function important(el, key, value){
                if (el && (el.style.getPropertyValue(key) !== value || el.style.getPropertyPriority(key) !== 'important')) {
                  el.style.setProperty(key, value, 'important');
                }
              }
              function hideAccessNotices(){
                var candidates = document.querySelectorAll('p,span,small,a,.alert,.notice,.message,.warning,[class*="notice"],[class*="warning"],[class*="notify"]');
                candidates.forEach(function(el){
                  var text = String(el.innerText || el.textContent || '').toLowerCase();
                  var notice = text.indexOf('nếu bạn không thể truy cập animevietsub') >= 0 || text.indexOf('neu ban khong the truy cap animevietsub') >= 0;
                  if (notice && text.length < 500) important(el, 'display', 'none');
                });
              }
              function challenge(){
                return /just a moment|xác minh an toàn|xác minh bảo mật|đang kiểm tra|verify you are human|checking your browser/i.test(document.title)
                  || !!document.querySelector('#challenge-form, #cf-please-wait, .captcha-placeholder, .cf-turnstile')
                  || !!(window._cf_chl_opt);
              }
              function notifyChallengeIfNeeded(){
                if (challenge()) {
                  try { AnPinimeWebBridge.onChallengeRequired(); } catch(e){}
                  return true;
                }
                return false;
              }
              function trackState(v){
                var api = playerAPI(v), audio = [], subs = [{id: 'off', title: 'Tắt'}], aid = '', sid = 'off';
                try {
                  if (api && api.getAudioTracks) {
                    audio = api.getAudioTracks().map(function(t, i){ return {id: String(i), title: t.name||t.label||t.language||('Âm thanh ' + (i+1))}; });
                    aid = String(api.getCurrentAudioTrack());
                  } else if (v.audioTracks) {
                    Array.from(v.audioTracks).forEach(function(t, i){ audio.push({id: String(i), title: t.label||t.language||('Âm thanh ' + (i+1))}); if (t.enabled) aid = String(i); });
                  }
                  if (api && api.getCaptionsList) {
                    api.getCaptionsList().forEach(function(t, i){ if (i > 0) subs.push({id: String(i), title: t.label||t.language||('Phụ đề ' + i)}); });
                    var selected = api.getCurrentCaptions(); if (selected > 0) sid = String(selected);
                  } else if (v.textTracks) {
                    Array.from(v.textTracks).forEach(function(t, i){ if (t.kind === 'metadata' || t.kind === 'chapters') return; subs.push({id: String(i), title: t.label||t.language||('Phụ đề ' + (i+1))}); if (t.mode === 'showing') sid = String(i); });
                  }
                } catch(e){}
                return {audio: audio, subtitles: subs, audioID: aid, subtitleID: sid};
              }
              function chooseTrack(v, kind, id){
                var api = playerAPI(v), index = Number(id);
                if (kind === 'audio') {
                  if (api && api.setCurrentAudioTrack) api.setCurrentAudioTrack(index);
                  else if (v.audioTracks) Array.from(v.audioTracks).forEach(function(t, i){ t.enabled = (i === index); });
                } else if (kind === 'subtitles') {
                  if (api && api.setCurrentCaptions) api.setCurrentCaptions(id === 'off' ? 0 : index);
                  else if (v.textTracks) Array.from(v.textTracks).forEach(function(t, i){ if (t.kind !== 'metadata' && t.kind !== 'chapters') t.mode = (id !== 'off' && i === index) ? 'showing' : 'disabled'; });
                }
              }
              function styleSubtitles(v, s){
                if (!s) return;
                var font = s.font || 'Arial';
                var size = Math.max(18, Math.min(80, Number(s.size) || 38));
                var css = document.getElementById('anpinime-sub-style');
                if (!css) { css = document.createElement('style'); css.id = 'anpinime-sub-style'; document.head.appendChild(css); }
                css.textContent = 'video::cue{font-family:"' + font + '";font-size:' + size + 'px;color:#ffffffff;background-color:rgba(0,0,0,0.65);text-shadow:1px 1px 2px #000;}' +
                  '.jw-captions{bottom:24px!important;text-align:center!important;}.jw-text-track-cue{font-family:"' + font + '"!important;font-size:' + size + 'px!important;color:#fff!important;background-color:rgba(0,0,0,0.65)!important;}';
                var api = playerAPI(v);
                if (api && api.setCaptions) api.setCaptions({fontFamily: font, fontSize: size, color: '#ffffff', backgroundColor: '#000000'});
              }
              function state(){
                var v = activeVideo; if (!v || isAdvertisement(v)) return;
                reportSource(v);
                try {
                  AnPinimeWebBridge.onMediaState(JSON.stringify({
                    time: isFinite(v.currentTime) ? v.currentTime : 0,
                    duration: isFinite(v.duration) ? v.duration : 0,
                    paused: v.paused,
                    buffering: !v.paused && v.readyState < 3,
                    tracks: trackState(v)
                  }));
                } catch(e){}
              }
              window.addEventListener('message', function(event){
                var c = event.data; if (!c || c.type !== 'AnPinimeCommand') return;
                var v = activeVideo || document.querySelector('video');
                if (v && isAdvertisement(v)) v = null;
                if (v) {
                  try {
                    switch(c.name){
                      case 'track': chooseTrack(v, c.kind, c.id); break;
                      case 'subtitleStyle': styleSubtitles(v, c.style); break;
                      case 'play': v.__aaUserPaused = false; tryPlay(v); break;
                      case 'pause': v.__aaUserPaused = true; v.pause(); break;
                      case 'seek': if (isFinite(v.duration)) v.currentTime = Math.max(0, Math.min(Number(c.value)||0, v.duration)); break;
                      case 'rate': v.playbackRate = Math.max(0.5, Math.min(Number(c.value)||1, 2)); break;
                      case 'volume': v.volume = Math.max(0, Math.min(Number(c.value)||0, 1)); break;
                      case 'fit': important(v, 'object-fit', c.value === 1 ? 'cover' : 'contain'); break;
                    }
                    state();
                  } catch(e){}
                }
                document.querySelectorAll('iframe').forEach(function(f){
                  try { f.contentWindow.postMessage(c, '*'); } catch(e){}
                });
              });
              function hideChrome(){
                hidden.forEach(function(s){
                  try { document.querySelectorAll(s).forEach(function(e){ important(e, 'display', 'none'); }); } catch(e){}
                });
                hideAccessNotices();
                important(document.documentElement, 'margin', '0'); important(document.documentElement, 'padding', '0');
                important(document.documentElement, 'background', '#000'); important(document.documentElement, 'overflow', 'hidden');
                important(document.body, 'margin', '0'); important(document.body, 'padding', '0');
                important(document.body, 'background', '#000'); important(document.body, 'overflow', 'hidden');
              }
              function isolate(el){
                if (!el) return false;
                hideChrome();
                var node = el;
                while(node && node !== document.body){
                  var parent = node.parentElement;
                  if (!parent) break;
                  Array.prototype.forEach.call(parent.children, function(sibling){
                    if (sibling !== node) important(sibling, 'display', 'none');
                  });
                  important(parent, 'display', 'block'); important(parent, 'visibility', 'visible'); important(parent, 'opacity', '1');
                  important(parent, 'position', 'static'); important(parent, 'width', '100%'); important(parent, 'height', '100%');
                  important(parent, 'margin', '0'); important(parent, 'padding', '0'); important(parent, 'transform', 'none');
                  node = parent;
                }
                important(el, 'display', 'block'); important(el, 'visibility', 'visible'); important(el, 'opacity', '1');
                important(el, 'position', 'fixed'); important(el, 'inset', '0'); important(el, 'width', '100vw');
                important(el, 'height', '100vh'); important(el, 'max-width', 'none'); important(el, 'max-height', 'none');
                important(el, 'margin', '0'); important(el, 'padding', '0'); important(el, 'z-index', '2147483647');
                important(el, 'background', '#000');
                return true;
              }
              function watch(v){
                if (!v) return false;
                activeVideo = v;
                isolate(v);
                important(v, 'object-fit', 'contain');
                v.setAttribute('playsinline', '');
                v.controls = false;
                reportSource(v);
                if (!v.__aa){
                  v.__aa = true;
                  ['loadedmetadata', 'canplay'].forEach(function(name){ v.addEventListener(name, function(){ tryPlay(v); }); });
                  v.addEventListener('playing', function(){ reportSource(v); sendReady(); state(); });
                  ['timeupdate','durationchange','pause','playing'].forEach(function(name){
                    v.addEventListener(name, function(){
                      try { AnPinimeWebBridge.onProgress(v.currentTime || 0, isFinite(v.duration) ? v.duration : 0, v.paused); } catch(e){}
                      state();
                    });
                  });
                  tryPlay(v);
                }
                if (v.readyState >= 2) sendReady();
                return true;
              }
              function usefulFrame(frame){
                var src = frame.getAttribute('src') || '';
                if (!src || /^(?:about:blank|javascript:)/i.test(src) || adURL(src)) return false;
                var rect = frame.getBoundingClientRect();
                return rect.width >= 240 && rect.height >= 120;
              }
              function focus(){
                if (!window.document || !document.documentElement) return false;
                if (notifyChallengeIfNeeded()) return false;
                hideChrome();
                var videos = document.querySelectorAll('video');
                if (videos.length) return watch(videos[0]);
                var selectors = ['#media-player', '.player-container', '.watching_player', '#player', '.jwplayer', '[class*="video-player"]', '[class*="player-wrap"]'];
                var player = null;
                for (var i = 0; i < selectors.length && !player; i++) {
                  try { player = document.querySelector(selectors[i]); } catch(e){}
                }
                var frames = Array.prototype.filter.call(document.querySelectorAll('iframe[src]'), usefulFrame);
                frames.sort(function(a, b){
                  var ar = a.getBoundingClientRect(), br = b.getBoundingClientRect();
                  return (br.width * br.height) - (ar.width * ar.height);
                });
                var frame = frames[0];
                if (frame) {
                  isolate(frame);
                  if (window === window.top) {
                    try {
                      var url = new URL(frame.getAttribute('src'), location.href).href;
                      if (!reportedFrames[url]) {
                        reportedFrames[url] = true;
                        AnPinimeWebBridge.onFrameFound(url, location.href);
                      }
                    } catch(e){}
                  }
                  return true;
                }
                return player ? isolate(player) : false;
              }
              function install(){
                if (!document.documentElement) { setTimeout(install, 20); return; }
                focus();
                new MutationObserver(function(){ focus(); }).observe(document.documentElement, {childList: true, subtree: true});
                var tries = 0, timer = setInterval(function(){ focus(); if (++tries > 120) clearInterval(timer); }, 500);
                setTimeout(function(){
                  if (activeVideo || challenge()) return;
                  var b = document.querySelector('.jw-icon-playback,.play-btn,[class*="play-button"],button[aria-label*="Play"],button[title*="Play"]');
                  if (b) b.click();
                  focus();
                }, 1800);
              }
              if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', install, {once: true});
              else install();
            })();
        """.trimIndent()

        val activeScript = if (aetherMode) {
            buildAetherBridgeScript(initialSeekMs / 1000.0)
        } else {
            fallbackIsolateScript
        }

        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(wv, activeScript, setOf("*"))
        }
        wv.webChromeClient = object : WebChromeClient() {
            override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                result?.confirm()
                return true
            }
        }

        wv.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val url = request?.url?.toString().orEmpty()
                val path = request?.url?.path.orEmpty()
                // Inject Aether polyfills & bridge synchronously inside /config.js before any ES module executes
                if (aetherMode && (path == "/config.js" || path.endsWith("/config.js"))) {
                    val configAndBridge = """
                        window.__CONFIG__ = {
                          VITE_CORS_PROXY_URL: "",
                          VITE_TMDB_READ_API_KEY: "",
                          VITE_DMCA_EMAIL: null,
                          VITE_NORMAL_ROUTER: true,
                          VITE_BACKEND_URL: null,
                          VITE_DISALLOWED_IDS: "",
                          VITE_SIMKL_CLIENT_ID: "",
                          VITE_SIMKL_CLIENT_SECRET: ""
                        };
                    """.trimIndent() + "\n;\n" + activeScript
                    return WebResourceResponse(
                        "application/javascript",
                        "UTF-8",
                        java.io.ByteArrayInputStream(configAndBridge.toByteArray(Charsets.UTF_8))
                    )
                }

                // Patch Aether index bundle to expose player, subtitle & preference stores directly to window
                if (aetherMode && Regex("""assets/index-[^/]+\.js""").containsMatchIn(url)) {
                    val patchedJs = aetherJsCache[url] ?: run {
                        try {
                            val req = Request.Builder()
                                .url(url)
                                .header("User-Agent", CatalogService.USER_AGENT)
                                .build()
                            val resp = aetherHttpClient.newCall(req).execute()
                            if (resp.isSuccessful) {
                                val body = resp.body?.string().orEmpty()
                                if (body.isNotBlank()) {
                                    val exportHook = """
                                        ;try {
                                            window.__anpinimePlayerStore = I;
                                            window.__anpinimeSubStore = ve;
                                            window.__anpinimePrefStore = ne;
                                            if (typeof Cs === 'function') window.__anpinimeCs = Cs;
                                            if (typeof Ss === 'function') window.__anpinimeSs = Ss;
                                            if (typeof g0 === 'function') window.__anpinimeScrapeAndPlay = g0;

                                            function reportAetherSources() {
                                                try {
                                                    if (typeof Ss === 'function' && window.AnPinimeWebBridge && AnPinimeWebBridge.onAetherSourcesLoaded) {
                                                        var sList = Ss().filter(function(x){ return x.type === 'source'; });
                                                        if (sList && sList.length > 0) {
                                                            AnPinimeWebBridge.onAetherSourcesLoaded(JSON.stringify(sList));
                                                            return true;
                                                        }
                                                    }
                                                } catch(e){}
                                                return false;
                                            }
                                            if (!reportAetherSources()) {
                                                var sTimer = setInterval(function() {
                                                    if (reportAetherSources()) clearInterval(sTimer);
                                                }, 500);
                                                setTimeout(function(){ clearInterval(sTimer); }, 15000);
                                            }

                                            if (typeof pa === 'function') {
                                                var _realPa = pa;
                                                pa = function() {
                                                    var inst = _realPa.apply(this, arguments);
                                                    if (inst && !inst.__anpinimeWrapped) {
                                                        inst.__anpinimeWrapped = true;
                                                        var origRunSource = inst.runSourceScraper;
                                                        inst.runSourceScraper = async function(opts) {
                                                            var res = await origRunSource.apply(this, arguments);
                                                            if (res && res.embeds && res.embeds.length > 1 && !res.stream) {
                                                                try {
                                                                    var emb = res.embeds[0];
                                                                    var embRes = await inst.runEmbedScraper({ id: emb.embedId, url: emb.url });
                                                                    if (embRes && embRes.stream) {
                                                                        res.stream = embRes.stream;
                                                                    }
                                                                } catch(e){}
                                                            }
                                                            return res;
                                                        };
                                                    }
                                                    return inst;
                                                };
                                            }

                                            if (ne && ne.getState) {
                                                var prefSt = ne.getState();
                                                if (prefSt.setEnableThumbnails) prefSt.setEnableThumbnails(false);
                                                if (prefSt.setEnableLowPerformanceMode) prefSt.setEnableLowPerformanceMode(true);
                                                if (prefSt.setEnableLastSuccessfulSource) prefSt.setEnableLastSuccessfulSource(true);
                                            }
                                        } catch(e) {
                                            console.error('AnPinime store injection error:', e);
                                        }
                                    """.trimIndent()
                                    val patched = body + "\n" + exportHook
                                    aetherJsCache[url] = patched
                                    patched
                                } else null
                            } else null
                        } catch (t: Throwable) {
                            android.util.Log.e("PlayerActivity", "Failed to fetch and patch Aether index.js", t)
                            null
                        }
                    }
                    if (patchedJs != null) {
                        return WebResourceResponse(
                            "application/javascript",
                            "UTF-8",
                            java.io.ByteArrayInputStream(patchedJs.toByteArray(Charsets.UTF_8))
                        )
                    }
                }

                // Fallback stream interception for ExoPlayer (Aether mode only; AnimeVietsub uses bridge/decoder)
                if (aetherMode && allowNativeWebHandoff && request?.method == "GET" &&
                    Regex("(?i)\\.(m3u8|mp4)([?#]|$)").containsMatchIn(url) &&
                    !url.contains("/ads/", true)) {
                    val requestHeaders = request.requestHeaders.orEmpty()
                    mainHandler.post {
                        if (fallbackWebView === wv && url !in attemptedStreamUrls && exoPlayer == null) {
                            val headers = requestHeaders.filterKeys { it.equals("Referer", true) || it.equals("Origin", true) || it.equals("User-Agent", true) }
                            val effectiveKind = when {
                                url.contains(".m3u8", ignoreCase = true) -> StreamMediaKind.HLS
                                url.contains(".mp4", ignoreCase = true) -> StreamMediaKind.MP4
                                else -> StreamMediaKind.UNKNOWN
                            }
                            prepareExoPlayer(url, headers, webPositionMs, effectiveKind, lastSubtitles)
                        }
                    }
                }
                return null
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                view?.evaluateJavascript(activeScript, null)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                view?.evaluateJavascript(activeScript, null)
                val timeoutMs = if (aetherMode) 25_000L else 22_000L
                mainHandler.postDelayed({
                    val notStarted = if (aetherMode) (exoPlayer == null && webDurationMs <= 0L) else view?.visibility != View.VISIBLE
                    if (fallbackWebView === view && notStarted) {
                        if (trySwitchToBackupServer(webPositionMs)) {
                            return@postDelayed
                        }
                        loadingOverlay.visibility = View.VISIBLE
                        loadingText.text = if (aetherMode) {
                            "Không thể tải nguồn phát Aether hoặc hết thời gian chờ. Hãy đổi tên miền dự phòng trong mục Máy chủ."
                        } else {
                            "Chưa mở được video. Hãy thử server khác hoặc kiểm tra Android System WebView."
                        }
                        showOsd(true)
                    }
                }, timeoutMs)
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                if (request != null && !request.isForMainFrame) {
                    return false
                }
                val u = request?.url?.toString().orEmpty()
                if (u.contains("doubleclick") || u.contains("popads") || u.contains("googlesyndication")) {
                    return true
                }
                return false
            }
        }

        fallbackWebView = wv
        headlessWebContainer.removeAllViews()
        headlessWebContainer.addView(wv)
        if (aetherMode) {
            wv.loadUrl(webUrl)
        } else if (referer.isNotBlank()) {
            wv.loadUrl(webUrl, mapOf("Referer" to referer))
        } else {
            wv.loadUrl(webUrl)
        }
    }

    private fun handleAetherResolved(
        streamUrl: String,
        streamType: String,
        headersJson: String,
        captionsJson: String,
        positionSec: Float,
        wv: WebView
    ) {
        mainHandler.post {
            if (fallbackWebView !== wv) return@post
            if (isFinishing || isDestroyed) return@post
            if (streamUrl.isBlank()) return@post
            if (exoPlayer != null && lastNativeUrl == streamUrl) return@post

            android.util.Log.i("PlayerActivity", "Aether stream extracted: $streamUrl (type: $streamType)")

            // Stop background WebView video playback
            wv.evaluateJavascript(
                "(function(){try{document.querySelectorAll('video,audio').forEach(function(m){m.pause();m.muted=true;m.removeAttribute('src');});}catch(e){}})();",
                null
            )
            wv.visibility = View.GONE
            loadingOverlay.visibility = View.GONE
            loadingProviderBadge.visibility = View.GONE
            loadingProgressBar.visibility = View.GONE
            loadingHintText.visibility = View.GONE

            val hdrs = HashMap<String, String>()
            hdrs["User-Agent"] = CatalogService.USER_AGENT
            try {
                if (headersJson.isNotBlank() && headersJson != "{}") {
                    val hObj = JSONObject(headersJson)
                    val it = hObj.keys()
                    while (it.hasNext()) {
                        val k = it.next()
                        val v = hObj.optString(k)
                        if (v.isNotBlank()) hdrs[k] = v
                    }
                }
            } catch (_: Throwable) {}
            if (!hdrs.containsKey("Referer") && currentEpisode.refererUrl.isNotBlank()) {
                hdrs["Referer"] = currentEpisode.refererUrl
            }

            val subs = mutableListOf<ExternalSubtitleTrack>()
            try {
                if (captionsJson.isNotBlank() && captionsJson != "[]") {
                    val arr = JSONArray(captionsJson)
                    for (i in 0 until arr.length()) {
                        val c = arr.optJSONObject(i) ?: continue
                        val cUrl = c.optString("url")
                        if (cUrl.isBlank()) continue
                        val cLang = c.optString("language", "vi")
                        val cLabel = c.optString("label").ifBlank {
                            if (cLang.startsWith("vi", ignoreCase = true)) "Tiếng Việt"
                            else if (cLang.startsWith("en", ignoreCase = true)) "Tiếng Anh"
                            else cLang.uppercase()
                        }
                        val mime = when {
                            cUrl.contains(".vtt", ignoreCase = true) -> MimeTypes.TEXT_VTT
                            cUrl.contains(".ass", ignoreCase = true) || cUrl.contains(".ssa", ignoreCase = true) -> MimeTypes.TEXT_SSA
                            else -> MimeTypes.APPLICATION_SUBRIP
                        }
                        subs.add(ExternalSubtitleTrack(url = cUrl, language = cLang, label = cLabel, mimeType = mime))
                    }
                }
            } catch (_: Throwable) {}

            subs.sortWith { a, b ->
                val aVi = a.language.startsWith("vi", ignoreCase = true)
                val bVi = b.language.startsWith("vi", ignoreCase = true)
                val aEn = a.language.startsWith("en", ignoreCase = true)
                val bEn = b.language.startsWith("en", ignoreCase = true)
                when {
                    aVi && !bVi -> -1
                    !aVi && bVi -> 1
                    aEn && !bEn -> -1
                    !aEn && bEn -> 1
                    else -> a.label.compareTo(b.label, ignoreCase = true)
                }
            }

            val cleanLower = streamUrl.lowercase()
            val effectiveKind = when {
                streamType.equals("hls", ignoreCase = true) -> StreamMediaKind.HLS
                streamType.equals("file", ignoreCase = true) -> {
                    when {
                        cleanLower.contains(".mkv") -> StreamMediaKind.MKV
                        else -> StreamMediaKind.MP4
                    }
                }
                cleanLower.contains(".m3u8") || cleanLower.contains("/hls/") -> StreamMediaKind.HLS
                cleanLower.contains(".mpd") -> StreamMediaKind.DASH
                cleanLower.contains(".mkv") -> StreamMediaKind.MKV
                cleanLower.contains(".mp4") -> StreamMediaKind.MP4
                else -> StreamMediaKind.HLS // Default Aether streams to HLS
            }

            val startSeekMs = (positionSec * 1000).toLong().coerceAtLeast(0L).let {
                if (it > 0L) it else webPositionMs.coerceAtLeast(0L)
            }

            prepareExoPlayer(streamUrl, hdrs, startSeekMs, effectiveKind, subs)
            updateSourceSpecificOsdButtons()
        }
    }

    private fun buildAetherBridgeScript(initialSeekSec: Double): String {
        return """
            (function(){
              if (window.__anpinimeAetherInstalled) return;
              window.__anpinimeAetherInstalled = true;

              // 1. Essential ES2021-ES2023 Polyfills for Projector Android System WebView (Chrome 80-97)
              try {
                if (typeof Object.hasOwn !== 'function') {
                  Object.hasOwn = function(obj, prop) {
                    return Object.prototype.hasOwnProperty.call(Object(obj), prop);
                  };
                }
                if (typeof window.structuredClone !== 'function') {
                  window.structuredClone = function(val) {
                    if (val === undefined || val === null || typeof val !== 'object') return val;
                    try { return JSON.parse(JSON.stringify(val)); } catch(e) { return val; }
                  };
                }
                if (typeof Array.prototype.at !== 'function') {
                  Array.prototype.at = function(n) {
                    n = Math.trunc(n) || 0;
                    if (n < 0) n += this.length;
                    if (n < 0 || n >= this.length) return undefined;
                    return this[n];
                  };
                }
                if (typeof String.prototype.at !== 'function') {
                  String.prototype.at = function(n) {
                    n = Math.trunc(n) || 0;
                    if (n < 0) n += this.length;
                    if (n < 0 || n >= this.length) return undefined;
                    return this.charAt(n);
                  };
                }
                if (typeof String.prototype.replaceAll !== 'function') {
                  String.prototype.replaceAll = function(str, newStr) {
                    if (Object.prototype.toString.call(str).toLowerCase() === '[object regexp]') {
                      return this.replace(str, newStr);
                    }
                    return this.split(String(str)).join(String(newStr));
                  };
                }
                if (typeof window.crypto === 'object' && typeof window.crypto.randomUUID !== 'function') {
                  window.crypto.randomUUID = function() {
                    return '10000000-1000-4000-8000-100000000000'.replace(/[018]/g, function(c) {
                      var r = (window.crypto.getRandomValues ? window.crypto.getRandomValues(new Uint8Array(1))[0] : Math.floor(Math.random() * 256));
                      return (c ^ (r & (15 >> (c / 4)))).toString(16);
                    });
                  };
                }
                // Bypass Aether's WebAudio createMediaElementSource booster so <video> audio never stalls on suspended AudioContext
                Object.defineProperty(HTMLMediaElement.prototype, '_hasAudioSource', {
                  value: true,
                  writable: true,
                  configurable: true
                });

                // Neutralize secondary <video> elements (such as Aether's seekbar thumbnail generator class o0)
                // Projector SoC MediaCodec hardware decoders allow only 1 concurrent session; a second video steals the decoder!
                var _origCreateElement = document.createElement.bind(document);
                document.createElement = function(tagName, options) {
                  var el = _origCreateElement(tagName, options);
                  if (String(tagName).toLowerCase() === 'video') {
                    setTimeout(function() {
                      if (el.id !== 'video-element') {
                        try {
                          el.pause();
                          el.removeAttribute('src');
                          el.src = '';
                          el.load = function(){};
                          el.play = function(){ return Promise.resolve(); };
                        } catch(e){}
                      }
                    }, 0);
                  }
                  return el;
                };
              } catch(e){}

              // 2. Seed valid Zustand persist state ({ state: {...}, version: 0 }) for Subtitles, Preferences & Onboarding
              try {
                var subKey = '__MW::subtitles';
                var rawSub = localStorage.getItem(subKey);
                var parsedSub = null;
                try { parsedSub = rawSub ? JSON.parse(rawSub) : null; } catch(e){}
                if (!parsedSub || typeof parsedSub !== 'object' || !parsedSub.state) {
                  localStorage.setItem(subKey, JSON.stringify({
                    state: {
                      enabled: true,
                      lastSelectedLanguage: 'vi',
                      autoSelectSubtitles: true,
                      preferredLanguages: ['vi', 'en'],
                      styling: {
                        color: '#ffffff',
                        backgroundOpacity: 0.5,
                        size: 1.2,
                        backgroundBlur: 0,
                        backgroundBlurEnabled: false,
                        bold: true,
                        boldWeight: 700,
                        verticalPosition: 1,
                        fontStyle: 'default',
                        borderThickness: 1,
                        fontFamily: 'sans-serif',
                        lineSpacing: 1.5
                      }
                    },
                    version: 0
                  }));
                }

                var prefKey = '__MW::preferences';
                var rawPref = localStorage.getItem(prefKey);
                var parsedPref = null;
                try { parsedPref = rawPref ? JSON.parse(rawPref) : null; } catch(e){}
                var prefState = (parsedPref && parsedPref.state) || {};
                prefState.enableThumbnails = false;
                prefState.enableLowPerformanceMode = true;
                prefState.videoBrightness = 100;
                prefState.videoContrast = 100;
                prefState.videoSaturation = 100;
                prefState.enableLastSuccessfulSource = true;
                prefState.enableSourceOrder = true;
                var provenSources = ['showbox', 'febbox', 'vidsrc', 'smashystream', 'insertunit'];
                var existingOrder = Array.isArray(prefState.sourceOrder) ? prefState.sourceOrder : [];
                var mergedOrder = provenSources.slice();
                existingOrder.forEach(function(s){ if (mergedOrder.indexOf(s) < 0) mergedOrder.push(s); });
                prefState.sourceOrder = mergedOrder;
                localStorage.setItem(prefKey, JSON.stringify({
                  state: prefState,
                  version: 0
                }));

                localStorage.setItem('__MW::onboarding', JSON.stringify({
                  state: { completed: true },
                  version: 0
                }));
              } catch(e){}

              var initialSeekTarget = ${initialSeekSec};
              var initialSeekApplied = initialSeekTarget <= 2;
              var autoSubProcessed = false;
              var autoTranslateTriggered = false;
              var scrapeFailReported = false;
              var activeVideo = null;
              var menuFocusIndex = 0;

              function injectAetherStyles(){
                if (!document.head || document.getElementById('anpinime-aether-style')) return;
                var st = document.createElement('style');
                st.id = 'anpinime-aether-style';
                st.textContent =
                  'html, body { background: #000 !important; overflow: hidden !important; margin: 0 !important; padding: 0 !important; user-select: none !important; }' +
                  '#root { width: 100vw !important; height: 100vh !important; margin: 0 !important; padding: 0 !important; background: transparent !important; }' +
                  '#video-element, video { display: block !important; visibility: visible !important; opacity: 1 !important; filter: none !important; -webkit-filter: none !important; background: transparent !important; background-color: transparent !important; position: fixed !important; inset: 0 !important; width: 100vw !important; height: 100vh !important; object-fit: contain !important; z-index: 10 !important; }' +
                  'div.text-white.absolute.w-full.flex.flex-col.items-center { bottom: 2.2rem !important; z-index: 50 !important; opacity: 1 !important; display: flex !important; visibility: visible !important; }' +
                  'a[href="/"], a[href*="/browse"], div.bg-gradient-to-b, div.bg-gradient-to-t, [class*="bg-gradient-"], div.pointer-events-auto.z-10, div[class*="grid-cols-[1fr,auto]"], div.absolute.right-4, div.pointer-events-none > div.absolute.inset-0.flex.space-x-6, .video-controls { opacity: 0 !important; pointer-events: none !important; }' +
                  'body[data-anpinime-menu-open="true"] .popout-wrapper { opacity: 1 !important; pointer-events: auto !important; z-index: 999999 !important; }' +
                  'body:not([data-anpinime-menu-open="true"]) .popout-wrapper { opacity: 0 !important; pointer-events: none !important; }';
                document.head.appendChild(st);
              }

              function stripVideoFilter(vid){
                if (!vid) return;
                try {
                  vid.style.setProperty('filter', 'none', 'important');
                  vid.style.setProperty('-webkit-filter', 'none', 'important');
                  vid.style.setProperty('background', 'transparent', 'important');
                  vid.style.setProperty('background-color', 'transparent', 'important');
                  vid.style.setProperty('display', 'block', 'important');
                  vid.style.setProperty('visibility', 'visible', 'important');
                  vid.style.setProperty('opacity', '1', 'important');
                  vid.classList.remove('bg-black');
                  vid.classList.remove('opacity-0');
                } catch(e){}
              }

              var cachedPlayerGetState = null;
              var cachedSubGetState = null;
              var cachedSelectCaptionById = null;
              var moduleImportInFlight = false;

              function tryResolveAetherModuleStores(){
                if (window.__anpinimePlayerStore && window.__anpinimeSubStore) return;
                if (typeof cachedPlayerGetState === 'function' && typeof cachedSubGetState === 'function') return;
                if (moduleImportInFlight) return;
                var scriptEl = document.querySelector('script[type="module"][src*="index-"]') ||
                               document.querySelector('script[type="module"][src]');
                if (!scriptEl || !scriptEl.src) return;
                moduleImportInFlight = true;
                import(scriptEl.src).then(function(mod){
                  if (!mod) { moduleImportInFlight = false; return; }
                  Object.keys(mod).forEach(function(k){
                    try {
                      var exp = mod[k];
                      if (typeof exp === 'function' &&
                          typeof exp.getState === 'function' &&
                          typeof exp.setState === 'function' &&
                          typeof exp.subscribe === 'function') {
                        var st = exp.getState();
                        if (st && typeof st === 'object') {
                          if (!cachedPlayerGetState && 'captionList' in st && typeof st.translateCaption === 'function') {
                            cachedPlayerGetState = exp.getState;
                          } else if (!cachedSubGetState && 'preferredLanguages' in st && typeof st.updateStyling === 'function') {
                            cachedSubGetState = exp.getState;
                          }
                        }
                      }
                    } catch(e){}
                  });
                  if (!cachedPlayerGetState) moduleImportInFlight = false;
                }).catch(function(){
                  moduleImportInFlight = false;
                });
              }

              function findSelectCaptionCallback(){
                if (typeof cachedSelectCaptionById === 'function') return cachedSelectCaptionById;
                var rootEl = document.getElementById('root');
                if (!rootEl) return null;
                var fiberKey = Object.keys(rootEl).find(function(k){
                  return k.indexOf('__reactContainer') === 0 || k.indexOf('_reactRootContainer') === 0;
                });
                if (!fiberKey) return null;
                var rootFiber = rootEl[fiberKey];
                if (rootFiber && rootFiber._internalRoot && rootFiber._internalRoot.current) {
                  rootFiber = rootFiber._internalRoot.current;
                }
                var queue = [rootFiber];
                var visited = 0;
                while (queue.length > 0 && visited < 3000) {
                  var f = queue.shift();
                  if (!f) continue;
                  visited++;
                  var hook = f.memoizedState;
                  var hGuard = 0;
                  while (hook && hGuard < 40) {
                    hGuard++;
                    var ms = hook.memoizedState;
                    // CRITICAL: Never invoke deps[0]() on React hooks! Only inspect the memoized callback reference.
                    if (Array.isArray(ms) && ms.length === 2 && typeof ms[0] === 'function' && Array.isArray(ms[1]) && ms[1].length === 13) {
                      cachedSelectCaptionById = ms[0];
                      return cachedSelectCaptionById;
                    }
                    hook = hook.next;
                  }
                  if (f.child) queue.push(f.child);
                  if (f.sibling) queue.push(f.sibling);
                }
                return null;
              }

              function findAetherStores(){
                var pStore = window.__anpinimePlayerStore;
                var sStore = window.__anpinimeSubStore;
                if (!pStore || !sStore) {
                  tryResolveAetherModuleStores();
                }
                var playerState = null;
                var subState = null;
                try {
                  if (pStore && typeof pStore.getState === 'function') {
                    playerState = pStore.getState();
                  } else if (typeof cachedPlayerGetState === 'function') {
                    playerState = cachedPlayerGetState();
                  }
                  if (sStore && typeof sStore.getState === 'function') {
                    subState = sStore.getState();
                  } else if (typeof cachedSubGetState === 'function') {
                    subState = cachedSubGetState();
                  }
                } catch(e){}
                if (!playerState && !subState) return null;
                var selectCaptionById = (playerState && playerState.status === 'playing') ? findSelectCaptionCallback() : cachedSelectCaptionById;
                return {
                  playerStore: playerState,
                  subStore: subState,
                  rawPlayerStore: pStore,
                  rawSubStore: sStore,
                  selectCaptionById: selectCaptionById
                };
              }

              function langLabel(code){
                var c = String(code || '').toLowerCase();
                var map = {
                  vi: 'Tiếng Việt', vie: 'Tiếng Việt',
                  en: 'Tiếng Anh', eng: 'Tiếng Anh',
                  ja: 'Tiếng Nhật', jpn: 'Tiếng Nhật',
                  ko: 'Tiếng Hàn', kor: 'Tiếng Hàn',
                  zh: 'Tiếng Trung', zho: 'Tiếng Trung',
                  fr: 'Tiếng Pháp', es: 'Tiếng Tây Ban Nha',
                  de: 'Tiếng Đức', th: 'Tiếng Thái', ru: 'Tiếng Nga'
                };
                return map[c] || (c ? c.toUpperCase() : 'Phụ đề');
              }

              function selectSubById(stores, id){
                if (!stores || !stores.playerStore) return false;
                var ps = stores.playerStore;
                var subState = stores.subStore;
                if (id === 'off') {
                  try {
                    if (ps.clearTranslateTask) ps.clearTranslateTask();
                    if (ps.setCaption) ps.setCaption(null);
                    if (subState && subState.setLanguage) subState.setLanguage(null);
                    if (subState && subState.setLastSelectedCaptionId) subState.setLastSelectedCaptionId(null);
                    try { AnPinimeWebBridge.onAetherStatus('Đã tắt phụ đề'); } catch(e){}
                  } catch(e){}
                  return true;
                }
                if (typeof stores.selectCaptionById === 'function') {
                  try {
                    stores.selectCaptionById(id);
                    return true;
                  } catch(e){}
                }
                var item = (ps.captionList || []).find(function(x){ return String(x.id) === String(id); });
                if (item) {
                  try {
                    if (subState && subState.setLanguage) subState.setLanguage(item.language);
                    if (subState && subState.setLastSelectedCaptionId) subState.setLastSelectedCaptionId(item.id);
                    if (subState && subState.setSource) subState.setSource(item.source || null);
                  } catch(e){}
                  if (item.url && ps.setCaption) {
                    fetch(item.url).then(function(r){ return r.text(); }).then(function(txt){
                      ps.setCaption({ id: item.id, language: item.language || 'en', url: item.url, srtData: txt });
                      try { AnPinimeWebBridge.onAetherStatus('Đã chọn phụ đề ' + langLabel(item.language)); } catch(e){}
                    }).catch(function(){
                      ps.setCaption({ id: item.id, language: item.language || 'en', url: item.url, srtData: '' });
                    });
                    return true;
                  } else if (ps.setCaption) {
                    ps.setCaption(item);
                    return true;
                  }
                }
                return false;
              }

              function translateSpecificCapToVi(ps, targetCap){
                if (!ps || !targetCap || typeof ps.translateCaption !== 'function') return false;
                try {
                  if (ps.clearTranslateTask) ps.clearTranslateTask();
                  ps.translateCaption(targetCap, 'vi');
                  try {
                    AnPinimeWebBridge.onAetherStatus('Đang dịch phụ đề (' + langLabel(targetCap.language) + ') sang Tiếng Việt...');
                  } catch(e){}
                  return true;
                } catch(e){
                  return false;
                }
              }

              function triggerTranslateToVi(manual){
                var stores = findAetherStores();
                if (stores && stores.playerStore) {
                  var ps = stores.playerStore;
                  var list = ps.captionList || [];
                  var selected = ps.caption && ps.caption.selected;
                  var task = ps.caption && ps.caption.translateTask;
                  if (task && task.targetLanguage === 'vi' && !task.error) {
                    if (task.done && task.translatedCaption && ps.setCaption) {
                      ps.setCaption(task.translatedCaption);
                    }
                    if (manual) {
                      try { AnPinimeWebBridge.onAetherStatus(task.done ? 'Đã bật phụ đề dịch Tiếng Việt' : 'Đang dịch phụ đề sang Tiếng Việt...'); } catch(e){}
                    }
                    return true;
                  }
                  var matchedFromList = selected ? list.find(function(x){ return String(x.id) === String(selected.id); }) : null;
                  var targetCap = matchedFromList || list.find(function(x){ return /^en/i.test(x.language || ''); }) || list[0] || selected;
                  if (targetCap) {
                    return translateSpecificCapToVi(ps, targetCap);
                  }
                  if (typeof ps.addExternalSubtitles === 'function' && !ps.isLoadingExternalSubtitles) {
                    try { ps.addExternalSubtitles(); } catch(e){}
                  }
                }
                if (manual) {
                  try { AnPinimeWebBridge.onAetherStatus('Đang tìm phụ đề từ OpenSubtitles để dịch sang Tiếng Việt...'); } catch(e){}
                }
                return false;
              }

              function syncTranslatedCaptionState(ps){
                if (!ps || !ps.caption) return;
                var task = ps.caption.translateTask;
                if (task && task.targetLanguage === 'vi' && task.done && task.translatedCaption) {
                  if (task.translatedCaption.srtData && !task.__anpinimeReported) {
                    task.__anpinimeReported = true;
                    try {
                      AnPinimeWebBridge.onAetherTranslatedSubtitle(
                        task.translatedCaption.srtData,
                        'vi',
                        '🇻🇳 Tiếng Việt (Bản dịch AI)'
                      );
                    } catch(e){}
                  }
                  var cur = ps.caption.selected;
                  if (!cur || String(cur.id) !== String(task.translatedCaption.id)) {
                    try {
                      ps.setCaption(task.translatedCaption);
                      AnPinimeWebBridge.onAetherStatus('Đã hoàn tất dịch phụ đề Tiếng Việt');
                    } catch(e){}
                  }
                }
              }

              var aetherResolvedSent = false;
              var aetherResolutionWaitCycles = 0;
              var lastReportedSubCount = 0;

              function extractAetherStreamData(ps, v) {
                var streamUrl = '';
                var streamType = '';
                var headers = {};
                var qualities = {};
                if (ps && ps.source) {
                  var src = ps.source;
                  if (src.headers && typeof src.headers === 'object') {
                    Object.assign(headers, src.headers);
                  }
                  if (src.type === 'hls' && src.url) {
                    streamUrl = src.url;
                    streamType = 'hls';
                  } else if (src.type === 'file' && src.qualities && typeof src.qualities === 'object') {
                    qualities = src.qualities;
                    streamType = 'file';
                    var qKeys = Object.keys(src.qualities);
                    var preferredOrder = ['1080', '720', '480', '360', 'unknown'];
                    var bestKey = preferredOrder.find(function(k){ return qKeys.indexOf(k) >= 0; }) || qKeys[0];
                    if (bestKey && src.qualities[bestKey] && src.qualities[bestKey].url) {
                      streamUrl = src.qualities[bestKey].url;
                    }
                  }
                }
                if (!streamUrl && v) {
                  var cur = v.currentSrc || v.getAttribute('src') || '';
                  if (cur && !/^blob:/i.test(cur) && !adURL(cur)) {
                    streamUrl = cur;
                    if (/\.m3u8([?#]|$)/i.test(cur)) streamType = 'hls';
                  }
                }
                if (!streamUrl && detectedStreams && detectedStreams.length) {
                  streamUrl = detectedStreams[detectedStreams.length - 1];
                  if (/\.m3u8([?#]|$)/i.test(streamUrl)) streamType = 'hls';
                }
                return { url: streamUrl, type: streamType, headers: headers, qualities: qualities };
              }

              function extractAetherCaptionList(ps) {
                var list = [];
                if (!ps || !Array.isArray(ps.captionList)) return list;
                ps.captionList.forEach(function(c, idx) {
                  if (!c || !c.url) return;
                  var lang = String(c.language || 'vi').toLowerCase();
                  var isVi = /^vi/i.test(lang);
                  var isEn = /^en/i.test(lang);
                  var label = (isVi ? '🇻🇳 Tiếng Việt' : (isEn ? '🇬🇧 Tiếng Anh' : langLabel(lang))) +
                              (c.opensubtitles ? ' (OpenSubtitles #' + (idx + 1) + ')' : ' #' + (idx + 1));
                  list.push({
                    id: String(c.id || idx),
                    language: lang,
                    label: label,
                    url: c.url
                  });
                });
                return list;
              }

              function checkAetherStreamResolution(ps, v) {
                var streamData = extractAetherStreamData(ps, v);
                if (!streamData.url) return;

                var captions = extractAetherCaptionList(ps);

                if (!aetherResolvedSent) {
                  var hasVi = captions.some(function(c){ return /^vi/i.test(c.language); });
                  var readyToLaunch = hasVi || (!ps.isLoadingExternalSubtitles && captions.length > 0) || aetherResolutionWaitCycles >= 3;
                  if (!readyToLaunch) {
                    aetherResolutionWaitCycles++;
                    return;
                  }
                  aetherResolvedSent = true;
                  lastReportedSubCount = captions.length;
                  try {
                    AnPinimeWebBridge.onAetherResolved(
                      streamData.url,
                      streamData.type || '',
                      JSON.stringify(streamData.headers || {}),
                      JSON.stringify(captions),
                      (v && isFinite(v.currentTime) ? v.currentTime : 0) || initialSeekTarget || 0
                    );
                  } catch(e){}
                } else if (captions.length !== lastReportedSubCount) {
                  lastReportedSubCount = captions.length;
                  try {
                    AnPinimeWebBridge.onAetherSubtitlesUpdated(JSON.stringify(captions));
                  } catch(e){}
                }
              }

              function observeScrapingActivity(){
                try {
                  var activeSourceEl = document.querySelector('div[data-source-id] [class*="text-white"], .transition-opacity[style*="opacity: 1"] p, [class*="scraping"] p, div.font-bold.text-white');
                  var card = document.querySelector('div[data-source-id]');
                  var srcId = card ? card.getAttribute('data-source-id') : '';
                  var sName = '';
                  if (srcId && typeof window.__anpinimeSs === 'function') {
                    var sObj = window.__anpinimeSs().find(function(x){ return x.id === srcId; });
                    if (sObj && sObj.name) sName = sObj.name;
                  }
                  if (!sName && activeSourceEl && activeSourceEl.textContent) {
                    sName = activeSourceEl.textContent.trim();
                  }
                  if (sName && sName.length > 1 && sName.length < 50 && sName.indexOf('Finding') < 0 && sName.indexOf('Verifying') < 0) {
                    if (window.AnPinimeWebBridge && AnPinimeWebBridge.onAetherScrapingProgress) {
                      AnPinimeWebBridge.onAetherScrapingProgress(sName, srcId || sName, 'Đang kiểm tra: ' + sName + '...', 0);
                    }
                  }
                } catch(e){}
              }

              function checkAutoSubtitles(){
                var stores = findAetherStores();
                if (!stores || !stores.playerStore) return;
                var ps = stores.playerStore;
                if (ps.status === 'resume' && typeof ps.setStatus === 'function') {
                  try { ps.setStatus('scraping'); } catch(e){}
                  return;
                }
                if (ps.status === 'scraping' || ps.status === 'indexing') {
                  observeScrapingActivity();
                  return;
                }
                if (ps.status === 'scrapeNotFound') {
                  if (!scrapeFailReported) {
                    scrapeFailReported = true;
                    try { AnPinimeWebBridge.onAetherScrapeFailed(); } catch(e){}
                  }
                  return;
                }
                if (ps.status !== 'playing') {
                  return;
                }
                checkAetherStreamResolution(ps, activeVideo || document.getElementById('video-element') || document.querySelector('video'));
                if (document.body && !document.body.classList.contains('anpinime-playing')) {
                  document.body.classList.add('anpinime-playing');
                }
                try {
                  if (ps.interface && ps.interface.hovering !== 'not_hovering' && typeof ps.updateInterfaceHovering === 'function') {
                    ps.updateInterfaceHovering('not_hovering');
                  }
                } catch(e){}
                syncTranslatedCaptionState(ps);

                var list = ps.captionList || [];
                if (!list.length) {
                  if (typeof ps.addExternalSubtitles === 'function' && !ps.isLoadingExternalSubtitles) {
                    try { ps.addExternalSubtitles(); } catch(e){}
                  }
                  return;
                }
                if (ps.isLoadingExternalSubtitles) return;

                var selected = ps.caption && ps.caption.selected;
                var task = ps.caption && ps.caption.translateTask;
                var viSub = list.find(function(x){ return /^vi/i.test(x.language || ''); });

                if (viSub) {
                  if (!autoSubProcessed) {
                    autoSubProcessed = true;
                    if (!selected || !/^vi/i.test(selected.language || '')) {
                      selectSubById(stores, viSub.id);
                      try { AnPinimeWebBridge.onAetherStatus('Đã tự chọn phụ đề Tiếng Việt'); } catch(e){}
                    }
                  }
                  return;
                }

                var enSub = list.find(function(x){ return /^en/i.test(x.language || ''); }) || list[0];
                if (!autoSubProcessed && enSub) {
                  autoSubProcessed = true;
                  if (!selected) {
                    selectSubById(stores, enSub.id);
                  }
                  if (!autoTranslateTriggered && !task) {
                    autoTranslateTriggered = true;
                    translateSpecificCapToVi(ps, enSub);
                  }
                }
              }

              function buildTrackState(v){
                var stores = findAetherStores();
                var ps = stores && stores.playerStore;
                var audio = [];
                var subs = [
                  { id: 'off', title: '🚫 Tắt phụ đề' },
                  { id: '__aether_translate_vi__', title: '🇻🇳 Tự động dịch sang Tiếng Việt (Công cụ dịch Aether)' }
                ];
                var qualities = [];
                var aid = '';
                var sid = 'off';
                var qid = '';

                try {
                  if (ps && Array.isArray(ps.audioTracks) && ps.audioTracks.length) {
                    ps.audioTracks.forEach(function(t){
                      var id = String(t.id);
                      audio.push({ id: id, title: (t.label || langLabel(t.language)) + (t.language ? ' (' + t.language.toUpperCase() + ')' : '') });
                    });
                    if (ps.currentAudioTrack && ps.currentAudioTrack.id != null) {
                      aid = String(ps.currentAudioTrack.id);
                    }
                  } else if (v && v.audioTracks) {
                    Array.from(v.audioTracks).forEach(function(t, i){
                      audio.push({ id: String(i), title: t.label || langLabel(t.language) || ('Âm thanh ' + (i + 1)) });
                      if (t.enabled) aid = String(i);
                    });
                  }

                  if (ps && Array.isArray(ps.captionList) && ps.captionList.length) {
                    var selectedId = ps.caption && ps.caption.selected ? String(ps.caption.selected.id) : 'off';
                    var task = ps.caption && ps.caption.translateTask;
                    if (selectedId && selectedId !== 'off') sid = selectedId;

                    var sorted = ps.captionList.slice().sort(function(a, b){
                      var la = String(a.language || '').toLowerCase();
                      var lb = String(b.language || '').toLowerCase();
                      var wa = la === 'vi' ? 0 : (la === 'en' ? 1 : 2);
                      var wb = lb === 'vi' ? 0 : (lb === 'en' ? 1 : 2);
                      if (wa !== wb) return wa - wb;
                      return 0;
                    });

                    // 1. Native Vietnamese subtitles first
                    sorted.forEach(function(c, idx){
                      if (!/^vi/i.test(c.language || '')) return;
                      var cid = String(c.id);
                      var srcTag = c.opensubtitles ? 'OpenSubtitles' : (c.source || 'Aether');
                      subs.push({ id: cid, title: '🇻🇳 Tiếng Việt (Gốc) • ' + srcTag + ' #' + (idx + 1) });
                    });

                    // 2. Direct "Translate this subtitle to Vietnamese" options for foreign subtitles
                    sorted.slice(0, 10).forEach(function(c, idx){
                      if (/^vi/i.test(c.language || '')) return;
                      var cid = String(c.id);
                      var srcTag = c.opensubtitles ? 'OpenSubtitles' : (c.source || 'Aether');
                      var trId = '__translate__:' + cid;
                      var isTranslatingThis = task && task.targetCaption && String(task.targetCaption.id) === cid && task.targetLanguage === 'vi';
                      var statusSuffix = isTranslatingThis ? (task.done ? ' [Đang bật bản dịch]' : ' [Đang dịch...]') : '';
                      if (isTranslatingThis) {
                        sid = trId;
                      }
                      subs.push({
                        id: trId,
                        title: '🌐 [Dịch sang Tiếng Việt] từ ' + langLabel(c.language) + ' • ' + srcTag + ' #' + (idx + 1) + statusSuffix
                      });
                    });

                    // 3. Original foreign subtitles
                    sorted.forEach(function(c, idx){
                      if (/^vi/i.test(c.language || '')) return;
                      var cid = String(c.id);
                      var srcTag = c.opensubtitles ? 'OpenSubtitles' : (c.source || 'Aether');
                      subs.push({ id: cid, title: langLabel(c.language) + ' (Gốc) • ' + srcTag + ' #' + (idx + 1) });
                    });
                  }

                  if (ps && Array.isArray(ps.qualities) && ps.qualities.length) {
                    ps.qualities.forEach(function(q){
                      var qStr = String(q);
                      qualities.push({ id: qStr, title: qStr === 'unknown' ? 'Mặc định' : (qStr + 'p') });
                    });
                    if (ps.currentQuality != null) {
                      qid = String(ps.currentQuality);
                    }
                  }
                } catch(e){}

                return { audio: audio, subtitles: subs, qualities: qualities, audioID: aid, subtitleID: sid, qualityID: qid };
              }

              function handleTrackCommand(v, kind, id){
                var stores = findAetherStores();
                var ps = stores && stores.playerStore;
                if (kind === 'subtitles') {
                  if (id === '__aether_translate_vi__') {
                    triggerTranslateToVi(true);
                    return;
                  }
                  if (String(id).indexOf('__translate__:') === 0) {
                    var targetId = String(id).substring('__translate__:'.length);
                    var targetCap = ps && (ps.captionList || []).find(function(x){ return String(x.id) === targetId; });
                    if (targetCap) {
                      translateSpecificCapToVi(ps, targetCap);
                    } else {
                      triggerTranslateToVi(true);
                    }
                    return;
                  }
                  selectSubById(stores, id);
                } else if (kind === 'audio') {
                  if (ps && ps.display && typeof ps.display.changeAudioTrack === 'function') {
                    ps.display.changeAudioTrack(id);
                  } else if (v && v.audioTracks) {
                    var idx = Number(id);
                    Array.from(v.audioTracks).forEach(function(t, i){ t.enabled = (i === idx); });
                  }
                } else if (kind === 'quality') {
                  if (!ps) return;
                  if (id === 'auto') {
                    if (typeof ps.enableAutomaticQuality === 'function') ps.enableAutomaticQuality();
                  } else if (typeof ps.switchQuality === 'function') {
                    var qMatch = (ps.qualities || []).find(function(q){ return String(q) === String(id); });
                    ps.switchQuality(qMatch != null ? qMatch : id);
                  }
                }
              }

              function applySubtitleStyle(s){
                if (!s) return;
                var sp = Number(s.size) || 26;
                var scale = sp <= 18 ? 0.85 : (sp <= 22 ? 1.0 : (sp >= 32 ? 1.45 : 1.2));
                var stores = findAetherStores();
                if (stores && stores.subStore && typeof stores.subStore.updateStyling === 'function') {
                  try { stores.subStore.updateStyling({ size: scale }); } catch(e){}
                }
              }

              function sendState(){
                var v = activeVideo || document.querySelector('video');
                checkAutoSubtitles();
                try {
                  AnPinimeWebBridge.onMediaState(JSON.stringify({
                    time: v && isFinite(v.currentTime) ? v.currentTime : 0,
                    duration: v && isFinite(v.duration) ? v.duration : 0,
                    paused: v ? v.paused : true,
                    buffering: v ? (!v.paused && v.readyState < 3) : true,
                    rate: v ? (v.playbackRate || 1.0) : 1.0,
                    tracks: buildTrackState(v)
                  }));
                } catch(e){}
              }

              function bindVideo(v){
                if (!v) return false;
                activeVideo = v;
                v.setAttribute('playsinline', '');
                stripVideoFilter(v);
                if (!v.__anpinimeAetherBound) {
                  v.__anpinimeAetherBound = true;
                  try {
                    var fObs = new MutationObserver(function(){ stripVideoFilter(v); });
                    fObs.observe(v, { attributes: true, attributeFilter: ['style', 'class'] });
                  } catch(e){}
                  v.addEventListener('loadedmetadata', function(){
                    stripVideoFilter(v);
                    if (!initialSeekApplied && initialSeekTarget > 2 && isFinite(v.duration) && v.duration > initialSeekTarget) {
                      initialSeekApplied = true;
                      v.currentTime = initialSeekTarget;
                    }
                    if (v.paused && !v.__aaUserPaused) {
                      var p = v.play(); if (p && p.catch) p.catch(function(){});
                    }
                  });
                  v.addEventListener('playing', function(){
                    stripVideoFilter(v);
                    if (!initialSeekApplied && initialSeekTarget > 2 && isFinite(v.duration) && v.duration > initialSeekTarget) {
                      initialSeekApplied = true;
                      v.currentTime = initialSeekTarget;
                    }
                    try { AnPinimeWebBridge.onPlayerReady(); } catch(e){}
                    sendState();
                  });
                  ['timeupdate', 'durationchange', 'pause', 'ratechange'].forEach(function(ev){
                    v.addEventListener(ev, function(){
                      stripVideoFilter(v);
                      try { AnPinimeWebBridge.onProgress(v.currentTime || 0, isFinite(v.duration) ? v.duration : 0, v.paused); } catch(e){}
                      sendState();
                    });
                  });
                }
                if (v.readyState >= 2) {
                  if (v.paused && !v.__aaUserPaused) {
                    var p = v.play(); if (p && p.catch) p.catch(function(){});
                  }
                  if (isFinite(v.duration) && v.duration > 0) {
                    try { AnPinimeWebBridge.onPlayerReady(); } catch(e){}
                    sendState();
                  }
                }
                return true;
              }

              window.addEventListener('message', function(event){
                var c = event.data;
                if (!c || c.type !== 'AnPinimeCommand') return;
                var v = activeVideo || document.querySelector('video');
                try {
                  switch(c.name){
                    case 'play':
                      if (v) { v.__aaUserPaused = false; var p = v.play(); if (p && p.catch) p.catch(function(){}); }
                      break;
                    case 'pause':
                      if (v) { v.__aaUserPaused = true; v.pause(); }
                      break;
                    case 'seek':
                      if (v && isFinite(v.duration)) {
                        v.currentTime = Math.max(0, Math.min(Number(c.value) || 0, v.duration));
                      }
                      break;
                    case 'rate':
                      if (v) { v.playbackRate = Math.max(0.25, Math.min(Number(c.value) || 1.0, 2.0)); }
                      break;
                    case 'volume':
                      if (v) { v.volume = Math.max(0, Math.min(Number(c.value) || 1.0, 1.0)); }
                      break;
                    case 'fit':
                      if (v) { v.style.setProperty('object-fit', c.value === 1 ? 'cover' : 'contain', 'important'); }
                      break;
                    case 'track':
                      handleTrackCommand(v, c.kind, c.id);
                      break;
                    case 'subtitleStyle':
                      applySubtitleStyle(c.style);
                      break;
                    case 'translateVi':
                      triggerTranslateToVi(true);
                      break;
                  }
                  sendState();
                } catch(e){}
              });

              function getMenuContainer(){
                return document.querySelector('.popout-location .popout-wrapper') ||
                       document.querySelector('[data-floating-ui-portal]') ||
                       document.querySelector('.popout-location');
              }

              function getMenuFocusables(){
                var container = getMenuContainer();
                if (!container) return [];
                var nodes = Array.from(container.querySelectorAll('button, a, input, select, [tabindex="0"], [role="button"]'));
                return nodes.filter(function(el){
                  var r = el.getBoundingClientRect();
                  return r.width > 8 && r.height > 8 && window.getComputedStyle(el).visibility !== 'hidden' && window.getComputedStyle(el).display !== 'none';
                });
              }

              function highlightMenuFocusable(idx){
                var list = getMenuFocusables();
                document.querySelectorAll('[data-anpinime-focus]').forEach(function(el){ el.removeAttribute('data-anpinime-focus'); });
                if (!list.length) return null;
                menuFocusIndex = ((idx % list.length) + list.length) % list.length;
                var target = list[menuFocusIndex];
                target.setAttribute('data-anpinime-focus', '1');
                try { target.focus(); target.scrollIntoView({ block: 'nearest', inline: 'nearest' }); } catch(e){}
                return target;
              }

              window.__anpinimeOpenAetherMenu = function(route){
                try {
                  var buttons = Array.from(document.querySelectorAll('button, [tabindex="0"]'));
                  var settingsBtn = buttons.find(function(b){
                    var html = b.innerHTML || '';
                    return html.indexOf('M19.14 12.94') >= 0 || html.indexOf('M20 4H4c-1.1') >= 0 || (b.className && String(b.className).indexOf('text-2xl') >= 0);
                  });
                  if (!getMenuFocusables().length) {
                    var bottomBtns = buttons.filter(function(b){
                      var r = b.getBoundingClientRect();
                      return r.top > window.innerHeight * 0.65 && r.width > 20 && r.height > 20;
                    });
                    var targetBtn = bottomBtns[bottomBtns.length - 2] || bottomBtns[bottomBtns.length - 1] || settingsBtn;
                    if (targetBtn) targetBtn.click();
                  }
                  setTimeout(function(){
                    var items = getMenuFocusables();
                    if (items.length > 0) {
                      document.body.setAttribute('data-anpinime-menu-open', 'true');
                      highlightMenuFocusable(0);
                      try { AnPinimeWebBridge.onAetherMenuState(true); } catch(e){}
                    } else {
                      document.body.removeAttribute('data-anpinime-menu-open');
                      try {
                        AnPinimeWebBridge.onAetherMenuState(false);
                        AnPinimeWebBridge.onAetherStatus('Đang chờ thanh công cụ Aether...');
                      } catch(e){}
                    }
                  }, 250);
                } catch(e){}
              };

              window.__anpinimeAetherMenuMove = function(dir){
                var list = getMenuFocusables();
                if (!list.length) {
                  try { AnPinimeWebBridge.onAetherMenuState(false); } catch(e){}
                  return;
                }
                if (dir === 1 || dir === 'down' || dir === 'right') highlightMenuFocusable(menuFocusIndex + 1);
                else if (dir === -1 || dir === 'up' || dir === 'left') highlightMenuFocusable(menuFocusIndex - 1);
              };

              window.__anpinimeAetherMenuActivate = function(){
                var list = getMenuFocusables();
                if (!list.length) {
                  try { AnPinimeWebBridge.onAetherMenuState(false); } catch(e){}
                  return;
                }
                var current = list[menuFocusIndex] || list[0];
                if (current) {
                  current.click();
                  setTimeout(function(){
                    var updated = getMenuFocusables();
                    if (updated.length) highlightMenuFocusable(0);
                    sendState();
                  }, 220);
                }
              };

              window.__anpinimeAetherMenuBack = function(){
                var container = getMenuContainer();
                if (container) {
                  var backCandidate = container.querySelector('button svg, [tabindex="0"] svg');
                  var firstClickable = getMenuFocusables()[0];
                  if (firstClickable && firstClickable.getBoundingClientRect().width < 48 && firstClickable.querySelector('svg')) {
                    firstClickable.click();
                    setTimeout(function(){
                      if (getMenuFocusables().length) {
                        highlightMenuFocusable(0);
                      } else {
                        document.body.removeAttribute('data-anpinime-menu-open');
                        try { AnPinimeWebBridge.onAetherMenuState(false); } catch(e){}
                      }
                    }, 200);
                    return;
                  }
                }
                document.body.removeAttribute('data-anpinime-menu-open');
                document.querySelectorAll('[data-anpinime-focus]').forEach(function(el){ el.removeAttribute('data-anpinime-focus'); });
                var backdrop = document.querySelector('video') || document.body;
                if (backdrop) {
                  try { backdrop.click(); } catch(e){}
                }
                try { AnPinimeWebBridge.onAetherMenuState(false); } catch(e){}
              };

              function tick(){
                injectAetherStyles();
                var v = document.getElementById('video-element') || document.querySelector('video');
                if (v) {
                  activeVideo = v;
                  stripVideoFilter(v);
                  bindVideo(v);
                }
                var stores = findAetherStores();
                if (stores && stores.rawPlayerStore && !stores.rawPlayerStore.__anpinimeSubscribed) {
                  stores.rawPlayerStore.__anpinimeSubscribed = true;
                  try {
                    stores.rawPlayerStore.subscribe(function(st) {
                      if (!st) return;
                      if (st.status === 'playing') {
                        checkAetherStreamResolution(st, activeVideo || document.getElementById('video-element') || document.querySelector('video'));
                      }
                      syncTranslatedCaptionState(st);
                    });
                  } catch(e){}
                }
                checkAutoSubtitles();
                sendState();
              }

              tick();
              setInterval(tick, 500);
            })();
        """.trimIndent()
    }

    private fun trySwitchToBackupServer(resumePosMs: Long): Boolean {
        if (isAetherEpisode()) {
            val nextServer = AetherSourceResolver.SERVERS.firstOrNull { srv ->
                srv.id != "auto" && !triedAetherScrapers.contains(srv.id.lowercase(Locale.US))
            } ?: return false
            triedAetherScrapers.add(nextServer.id.lowercase(Locale.US))
            val savedTried = HashSet(triedAetherScrapers)
            currentAetherProviderId = nextServer.id
            val resumePos = exoPlayer?.currentPosition?.takeIf { it > 0L } ?: resumePosMs
            releasePlayer()
            destroyWebPlayer(force = true)
            updateScrapingOverlay(nextServer.name, nextServer.id, "Đang chuyển sang nguồn ${nextServer.name}…", 0)
            val targetEp = currentEpisode.copy(
                episodeKey = nextServer.id,
                season = "Server · ${nextServer.name}",
                serverName = nextServer.name
            )
            triedAetherScrapers.clear()
            triedAetherScrapers.addAll(savedTried)
            startEpisodePlayback(targetEp, resetPosition = false, initialPositionMs = resumePos)
            return true
        }
        // Film4K's "Phần" denotes real seasons, not interchangeable servers.
        if (currentEpisode.source == ContentSource.FILM4K && currentEpisode.seasonNum > 0) return false
        val seasons = ArrayList<String>()
        for (ep in allEpisodes) {
            if (!seasons.contains(ep.season)) seasons.add(ep.season)
        }
        val currentIdx = seasons.indexOf(currentEpisode.season)
        if (currentIdx >= 0) {
            val backupEp = seasons.drop(currentIdx + 1).firstNotNullOfOrNull { nextSeason ->
                allEpisodes.firstOrNull { it.season == nextSeason && it.number == currentEpisode.number }
            }
            if (backupEp != null) {
                startEpisodePlayback(backupEp, resetPosition = false, initialPositionMs = resumePosMs)
                return true
            }
        }
        return false
    }

    private fun cycleAspectMode() {
        val modes = Phim4KPlayerUi.AspectMode.values()
        currentAspectMode = modes[(currentAspectMode.ordinal + 1) % modes.size]
        if (lastVideoW > 0 && lastVideoH > 0) {
            adjustSurfaceAspectRatio(lastVideoW, lastVideoH)
        }
        updateTopStatusBadges()
        updateFocusedHint("Tỉ lệ màn hình · ${currentAspectMode.label}")
        showGestureFeedback("📐  ${currentAspectMode.label}", Gravity.CENTER)
    }

    private fun showAspectModePicker() {
        val modes = Phim4KPlayerUi.AspectMode.values()
        showSelection("Tỉ lệ màn hình", modes.map { it.label }, currentAspectMode.ordinal) { idx ->
            currentAspectMode = modes.getOrElse(idx) { Phim4KPlayerUi.AspectMode.FIT }
            if (lastVideoW > 0 && lastVideoH > 0) {
                adjustSurfaceAspectRatio(lastVideoW, lastVideoH)
            }
            updateTopStatusBadges()
            showGestureFeedback("📐  ${currentAspectMode.label}", Gravity.CENTER)
        }
    }

    private fun showSettingsMenu() {
        val qLabel = if (exoPlayer == null && fallbackWebView != null) {
            webQualities.firstOrNull { it.selected }?.label ?: "Tự động"
        } else {
            when (currentQualityMode) {
                0 -> "1080p"
                1 -> "720p"
                else -> "Auto"
            }
        }
        val speedLabel = String.format(Locale.US, "%.2gx", currentPlaybackSpeed)
        val sizeLabel = when (subtitleScaleMode) {
            0 -> "Nhỏ (18sp)"
            1 -> "Vừa (22sp)"
            3 -> "Rất lớn (32sp)"
            else -> "Lớn (26sp)"
        }
        val decoderLabel = if (preferSoftwareAudio) "FFmpeg Đa kênh" else "Tự động / Thiết bị"

        val items = mutableListOf(
            "Chất lượng video · $qLabel",
            "Tốc độ phát · $speedLabel",
            "Cỡ chữ phụ đề · $sizeLabel",
            "Tỉ lệ khung hình · ${currentAspectMode.label}",
            "Bộ giải mã Audio · $decoderLabel"
        )
        if (fallbackWebView != null && isAetherEpisode()) {
            items.add("Menu gốc trình phát Web (Aether)")
        }

        showSelection("Cài đặt phát", items, -1) { index ->
            when (index) {
                0 -> showQualityPicker()
                1 -> showPlaybackSpeedPicker()
                2 -> showSubtitleSizePicker()
                3 -> showAspectModePicker()
                4 -> showSelection(
                    "Bộ giải mã âm thanh (Hỗ trợ 5.1 / EAC3 / DTS / AAC)",
                    listOf("Tự động / Thiết bị", "FFmpeg Đa kênh (Khuyên dùng cho 4K/MKV VIP)"),
                    if (preferSoftwareAudio) 1 else 0
                ) { decIdx ->
                    exoPlayer?.let { player ->
                        val position = player.currentPosition
                        preferSoftwareAudio = decIdx == 1
                        prepareExoPlayer(lastNativeUrl, lastNativeHeaders, position, lastMediaKind, lastSubtitles)
                    }
                }
                5 -> openAetherNativeMenu("/captionsOverlay")
            }
        }
    }

    private fun adjustSurfaceAspectRatio(videoW: Int, videoH: Int) {
        if (videoW <= 0 || videoH <= 0) return
        lastVideoW = videoW
        lastVideoH = videoH
        val parentW = videoFrame.width
        val parentH = videoFrame.height
        if (parentW <= 0 || parentH <= 0) return

        val videoRatio = videoW.toFloat() / videoH.toFloat()
        val screenRatio = parentW.toFloat() / parentH.toFloat()
        val lp = surfaceView.layoutParams as FrameLayout.LayoutParams
        when (currentAspectMode) {
            Phim4KPlayerUi.AspectMode.FIT -> {
                if (videoRatio > screenRatio) {
                    lp.width = parentW
                    lp.height = (parentW / videoRatio).toInt()
                } else {
                    lp.height = parentH
                    lp.width = (parentH * videoRatio).toInt()
                }
            }
            Phim4KPlayerUi.AspectMode.ZOOM -> {
                if (videoRatio > screenRatio) {
                    lp.height = parentH
                    lp.width = (parentH * videoRatio).toInt()
                } else {
                    lp.width = parentW
                    lp.height = (parentW / videoRatio).toInt()
                }
            }
            Phim4KPlayerUi.AspectMode.FILL -> {
                lp.width = parentW
                lp.height = parentH
            }
            Phim4KPlayerUi.AspectMode.CINEMA_ZOOM -> {
                if (videoRatio > screenRatio) {
                    lp.width = (parentW * 1.12f).toInt()
                    lp.height = ((parentW / videoRatio) * 1.12f).toInt()
                } else {
                    lp.height = (parentH * 1.12f).toInt()
                    lp.width = ((parentH * videoRatio) * 1.12f).toInt()
                }
            }
            Phim4KPlayerUi.AspectMode.FIXED_WIDTH -> {
                lp.width = parentW
                lp.height = (parentW / videoRatio).toInt()
            }
        }
        lp.gravity = Gravity.CENTER
        surfaceView.layoutParams = lp
    }

    private fun showSelection(title: String, labels: List<String>, selected: Int, choose: (Int) -> Unit) {
        selectionDialog?.dismiss()
        mainHandler.removeCallbacks(hideOsdRunnable)
        selectionDialog = Phim4KPlayerUi.showGlassListDialog(
            activity = this,
            title = title,
            items = labels,
            checkedIndex = selected,
            onDismiss = {
                selectionDialog = null
                scheduleHideOsd()
            }
        ) { index ->
            choose(index)
        }
    }

    private fun showPlayerMessage(title: String, message: String) {
        selectionDialog?.dismiss()
        mainHandler.removeCallbacks(hideOsdRunnable)
        selectionDialog = Phim4KPlayerUi.showGlassMessageDialog(
            activity = this,
            title = title,
            message = message,
            onDismiss = {
                selectionDialog = null
                scheduleHideOsd()
            }
        )
    }

    private fun showQualityPicker() {
        if (exoPlayer == null) {
            if (fallbackWebView != null && webQualities.isNotEmpty()) {
                val options = listOf(WebTrackItem("auto", "Tự động (Auto)", webQualities.none { it.selected })) + webQualities
                val labels = options.map { it.label }
                val selected = options.indexOfFirst { it.selected }.coerceAtLeast(0)
                showSelection("Chất lượng video (Aether)", labels, selected) { idx ->
                    val chosen = options.getOrNull(idx) ?: return@showSelection
                    sendWebTrackCommand("quality", chosen.id)
                    updateTopStatusBadges()
                }
                return
            }
            if (currentEpisode.source == ContentSource.AETHER && fallbackWebView != null) {
                showPlayerMessage("Chất lượng video", "Nguồn phát Aether hiện tại đang tự động tối ưu độ phân giải cao nhất (Auto FHD).")
                return
            }
            showPlayerMessage("Chất lượng", "Chưa có luồng native. Trình phát web hiện chưa hỗ trợ chọn chất lượng bằng app.")
            return
        }
        showSelection("Giới hạn chất lượng (không chuyển mã)",
            listOf("Tối đa 1080p", "Tối đa 720p (nhẹ)", "Tự động — không giới hạn"), currentQualityMode) { index ->
            currentQualityMode = index
            applyQualityMode()
        }
    }

    private fun showPlaybackSpeedPicker() {
        val speeds = listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
        val labels = listOf(
            "0.75x (Chậm)",
            "1.0x (Chuẩn)",
            "1.25x (Nhanh vừa)",
            "1.5x (Nhanh)",
            "2.0x (Rất nhanh)"
        )
        val selected = speeds.indexOfFirst { kotlin.math.abs(it - currentPlaybackSpeed) < 0.05f }.coerceAtLeast(1)
        showSelection("Tốc độ phát", labels, selected) { idx ->
            val chosenSpeed = speeds.getOrElse(idx) { 1.0f }
            currentPlaybackSpeed = chosenSpeed
            updateTopStatusBadges()
            exoPlayer?.setPlaybackParameters(androidx.media3.common.PlaybackParameters(chosenSpeed))
            sendWebCommand("rate", chosenSpeed.toDouble())
        }
    }

    private fun findActivePlayerSourceTrack(player: ExoPlayer?): ExternalSubtitleTrack? {
        if (player != null) {
            for (group in player.currentTracks.groups) {
                if (group.type != C.TRACK_TYPE_TEXT) continue
                for (i in 0 until group.length) {
                    if (!group.isTrackSelected(i)) continue
                    val fmt = group.getTrackFormat(i)
                    val byId = fmt.id?.let { idUrl ->
                        lastSubtitles.firstOrNull { it.url == idUrl && !it.url.startsWith("file://") }
                    }
                    if (byId != null) return byId
                    val fmtLang = fmt.language.orEmpty()
                    val fmtLabel = fmt.label.orEmpty()
                    if (fmtLang.isNotBlank() || fmtLabel.isNotBlank()) {
                        val byMeta = lastSubtitles.firstOrNull { sub ->
                            !sub.url.startsWith("file://") &&
                                (fmtLang.isBlank() || sub.language.equals(fmtLang, ignoreCase = true)) &&
                                (fmtLabel.isBlank() || sub.label.equals(fmtLabel, ignoreCase = true))
                        }
                        if (byMeta != null) return byMeta
                    }
                }
            }
        }
        val preferred = selectedSourceSubUrl
        if (preferred.isNotBlank()) {
            lastSubtitles.firstOrNull { it.url == preferred && !it.url.startsWith("file://") }
                ?.let { return it }
        }
        return null
    }

    private fun getSelectedTextLanguage(player: ExoPlayer?): String? {
        if (player == null) return null
        for (group in player.currentTracks.groups) {
            if (group.type != C.TRACK_TYPE_TEXT) continue
            for (i in 0 until group.length) {
                if (group.isTrackSelected(i)) {
                    return group.getTrackFormat(i).language?.takeIf { it.isNotBlank() }
                }
            }
        }
        return null
    }

    private fun selectSubtitleSourceTrack(
        subtitles: List<ExternalSubtitleTrack>,
        preferredUrl: String? = selectedSourceSubUrl
    ): ExternalSubtitleTrack? {
        if (!preferredUrl.isNullOrBlank()) {
            subtitles.firstOrNull {
                it.url == preferredUrl && !it.url.startsWith("file://")
            }?.let { return it }
        }
        return subtitles.firstOrNull {
            !it.url.startsWith("file://") && it.language.lowercase(Locale.US).startsWith("en")
        } ?: subtitles.firstOrNull {
            !it.url.startsWith("file://") && !it.language.lowercase(Locale.US).startsWith("vi")
        } ?: subtitles.firstOrNull { !it.url.startsWith("file://") }
        ?: subtitles.firstOrNull()
    }

    private fun selectSubtitleSourceUrl(
        subtitles: List<ExternalSubtitleTrack>,
        preferredUrl: String? = selectedSourceSubUrl
    ): String? = selectSubtitleSourceTrack(subtitles, preferredUrl)?.url

    private fun prewarmSourceSubtitle(
        subtitles: List<ExternalSubtitleTrack>,
        streamUrl: String,
        headers: Map<String, String>,
        preferredUrl: String? = selectedSourceSubUrl
    ) {
        val chosen = selectSubtitleSourceTrack(subtitles, preferredUrl)
        val subKey = chosen?.url ?: streamUrl
        if (subKey.isBlank() || (subKey == cachedSubKey && cachedSubRawText.isNotBlank())) return
        subPrewarmJob?.cancel()
        subPrewarmJob = uiScope.launch(Dispatchers.IO) {
            val raw = fetchSourceSubtitleRaw(subtitles, streamUrl, headers, chosen?.url, chosen?.language)
            if (!raw.isNullOrBlank()) {
                cachedSubKey = subKey
                cachedSubRawText = raw
            }
        }
    }

    private fun fetchSourceSubtitleRaw(
        subtitles: List<ExternalSubtitleTrack>,
        streamUrl: String,
        headers: Map<String, String>,
        preferredUrl: String? = selectedSourceSubUrl,
        preferredLang: String? = null
    ): String? {
        val candidate = selectSubtitleSourceTrack(subtitles, preferredUrl)
        val subKey = candidate?.url ?: "${streamUrl}|${preferredLang.orEmpty()}"
        if (subKey == cachedSubKey && cachedSubRawText.isNotBlank()) return cachedSubRawText
        val text = if (candidate != null) {
            fetchTextUrl(
                candidate.url,
                mapOf(
                    "User-Agent" to AetherSourceResolver.USER_AGENT,
                    "X-User-Agent" to AetherSourceResolver.OPENSUBTITLES_UA
                )
            )
        } else {
            fetchEmbeddedHlsVtt(streamUrl, headers, preferredLang)
        }
        if (!text.isNullOrBlank()) {
            cachedSubKey = subKey
            cachedSubRawText = text
        }
        return text
    }

    private fun triggerAetherVietnameseTranslation() {
        val player = exoPlayer
        if (player == null) {
            if (fallbackWebView != null) {
                sendWebCommand("translateVi")
                android.widget.Toast.makeText(this, "🌐 Đang kích hoạt dịch phụ đề Việt tức thì…", android.widget.Toast.LENGTH_SHORT).show()
                return
            }
            showPlayerMessage("Dịch phụ đề", "Hãy đợi video bắt đầu phát rồi thử dịch lại.")
            return
        }

        if (viLiveSubActive) {
            stopLiveViSubtitles(clearView = true)
            showGestureFeedback("Phụ đề AI: Tắt", Gravity.CENTER)
            return
        }

        val activeTrack = findActivePlayerSourceTrack(player)
        val activeLang = getSelectedTextLanguage(player)
        val localPreferredUrl = selectedSourceSubUrl.takeIf { it.contains("/local_subs/") }
        val targetSubUrl = localPreferredUrl ?: activeTrack?.url ?: selectSubtitleSourceUrl(lastSubtitles, selectedSourceSubUrl)
        val sourceKey = targetSubUrl ?: "${lastNativeUrl}|${activeLang.orEmpty()}"
        val epKey = "${currentEpisode.slug}|$sourceKey"

        val isMemoryCacheComplete = viTranslatedMap.size >= (viLiveCues.size * 0.90).toInt().coerceAtLeast(1)
        if (translatedEpisodeKey == epKey && viLiveCues.isNotEmpty() && isMemoryCacheComplete) {
            viTranslationCompleted = true
            player.trackSelectionParameters = player.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .build()
            if (::subtitleView.isInitialized) subtitleView.setCues(emptyList())
            viLiveSubActive = true
            viLastRenderedCueText = VI_CUE_UNSET
            updateTranslateStatusUi(viTranslatedMap.size, viLiveCues.size, completed = true)
            renderLiveSubtitleAt(player.currentPosition.coerceAtLeast(0L), force = true)
            mainHandler.removeCallbacks(subtitleTickRunnable)
            mainHandler.post(subtitleTickRunnable)
            showGestureFeedback("✓ Phụ đề Việt (AI)", Gravity.CENTER)
            return
        }

        if (translateSubJob?.isActive == true) {
            showGestureFeedback("⚡ Đang dịch phụ đề nền…", Gravity.CENTER)
            return
        }

        val currentPos = player.currentPosition.coerceAtLeast(0L)
        viTranslationCompleted = false
        if (::topTranslateBadge.isInitialized) {
            topTranslateBadge.visibility = View.VISIBLE
            topTranslateBadge.text = "⚡ Đang tải phụ đề…"
        }
        showGestureFeedback("⚡ Đang dịch sub Việt tại vị trí đang xem…", Gravity.CENTER)

        translateSubJob = uiScope.launch {
            try {
                val rawText = withContext(Dispatchers.IO) {
                    if (localPreferredUrl != null) {
                        fetchTextUrl(localPreferredUrl, emptyMap())
                    } else {
                        fetchSourceSubtitleRaw(
                            subtitles = lastSubtitles,
                            streamUrl = lastNativeUrl,
                            headers = lastNativeHeaders,
                            preferredUrl = targetSubUrl,
                            preferredLang = activeLang
                        )
                    }
                }
                if (rawText.isNullOrBlank()) {
                    updateTranslateStatusUi(0, 0, completed = false)
                    showPlayerMessage("Dịch phụ đề", "Không tìm thấy phụ đề nguồn (SRT/VTT/ASS) để dịch sang Tiếng Việt.")
                    return@launch
                }
                val parsedCues = withContext(Dispatchers.Default) {
                    val detectedFmt = SubtitleSupport.detectSubtitleFormat(targetSubUrl ?: "", rawText)
                    val normalizedForParse = if (detectedFmt == MimeTypes.TEXT_SSA) {
                        rawText
                    } else {
                        SubtitleSupport.normalizeToSubRip(rawText)
                    }
                    SubtitleTranslator.parseTimedCues(normalizedForParse).ifEmpty {
                        SubtitleTranslator.parseTimedCues(rawText)
                    }
                }
                if (parsedCues.isEmpty()) {
                    updateTranslateStatusUi(0, 0, completed = false)
                    showPlayerMessage("Dịch phụ đề", "Tệp phụ đề không có dòng thoại hợp lệ để dịch.")
                    return@launch
                }

                viLiveCues = parsedCues
                viTranslatedMap.clear()
                translatedEpisodeKey = epKey
                viLastRenderedCueText = VI_CUE_UNSET

                withContext(Dispatchers.IO) {
                    val outDir = File(cacheDir, "aether_subs").apply { mkdirs() }
                    val cacheHash = SubtitleTranslator.computeSubCacheKey(sourceKey, parsedCues.size, "vi")
                    val transCacheFile = File(outDir, "cache_v2_$cacheHash.json")
                    val seedMap = SubtitleTranslator.loadCachedTranslations(transCacheFile, parsedCues.size)
                    if (seedMap.isNotEmpty()) {
                        viTranslatedMap.putAll(seedMap)
                    }

                    val allTranslated = SubtitleTranslator.translateAllProgressiveFast(
                        cues = parsedCues,
                        currentPositionMs = currentPos,
                        workers = 3,
                        batchSize = 80,
                        seedTranslations = seedMap,
                        translateBatchFn = { batch ->
                            SubtitleTranslator.translateBatchViaHtml(
                                texts = batch,
                                client = aetherHttpClient,
                                targetLang = "vi",
                                userAgent = AetherSourceResolver.USER_AGENT
                            )
                        },
                        onWaveReady = { waveMap ->
                            viTranslatedMap.putAll(waveMap)
                            mainHandler.post {
                                val p = exoPlayer ?: return@post
                                viLiveSubActive = true
                                p.trackSelectionParameters = p.trackSelectionParameters
                                    .buildUpon()
                                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                                    .build()
                                viLastRenderedCueText = VI_CUE_UNSET
                                updateTranslateStatusUi(viTranslatedMap.size, parsedCues.size, completed = false)
                                renderLiveSubtitleAt(p.currentPosition.coerceAtLeast(0L), force = true)
                                mainHandler.removeCallbacks(subtitleTickRunnable)
                                mainHandler.post(subtitleTickRunnable)
                            }
                        },
                        onBatchTranslated = { batchMap ->
                            viTranslatedMap.putAll(batchMap)
                            mainHandler.post {
                                val p = exoPlayer ?: return@post
                                if (viLiveSubActive) {
                                    renderLiveSubtitleAt(p.currentPosition.coerceAtLeast(0L))
                                }
                            }
                        },
                        onBatchUpdated = { done, total ->
                            mainHandler.post {
                                if (viLiveSubActive && done < total) {
                                    updateTranslateStatusUi(viTranslatedMap.size.coerceAtLeast(done), total, completed = false)
                                }
                            }
                        }
                    )
                    viTranslatedMap.putAll(allTranslated)
                    viTranslationCompleted = true

                    if (viTranslatedMap.isEmpty()) {
                        mainHandler.post {
                            stopLiveViSubtitles(clearView = true)
                            translatedEpisodeKey = ""
                            showPlayerMessage("Dịch phụ đề", "Không thể kết nối máy chủ dịch lúc này. Vui lòng thử lại.")
                        }
                        return@withContext
                    }

                    SubtitleTranslator.saveCachedTranslations(transCacheFile, parsedCues.size, viTranslatedMap)
                    val outFile = File(outDir, "vi_v2_$cacheHash.vtt")
                    outFile.writeText(SubtitleTranslator.buildVtt(parsedCues, viTranslatedMap), Charsets.UTF_8)

                    val newTrack = ExternalSubtitleTrack(
                        url = Uri.fromFile(outFile).toString(),
                        language = "vi",
                        label = "🇻🇳 Tiếng Việt (AI Dịch)",
                        mimeType = MimeTypes.TEXT_VTT
                    )
                    mainHandler.post {
                        lastSubtitles = listOf(newTrack) + lastSubtitles.filterNot { it.url == newTrack.url }
                        if (viLiveSubActive) {
                            updateTranslateStatusUi(viTranslatedMap.size, parsedCues.size, completed = true)
                            viLastRenderedCueText = VI_CUE_UNSET
                            exoPlayer?.let { renderLiveSubtitleAt(it.currentPosition.coerceAtLeast(0L), force = true) }
                            mainHandler.removeCallbacks(subtitleTickRunnable)
                            mainHandler.post(subtitleTickRunnable)
                        }
                    }
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                stopLiveViSubtitles(clearView = true)
                showPlayerMessage("Dịch phụ đề", "Không thể dịch phụ đề lúc này: ${t.message ?: "Lỗi mạng"}")
            }
        }
    }

    private fun fetchTextUrl(url: String, headers: Map<String, String>): String? {
        return SubtitleSupport.readTextUriOrHttp(this, url, headers, aetherHttpClient)
    }

    private fun fetchEmbeddedHlsVtt(
        masterUrl: String,
        headers: Map<String, String>,
        preferredLang: String? = null
    ): String? {
        if (!masterUrl.contains(".m3u8", ignoreCase = true) && !masterUrl.contains("m3u8", ignoreCase = true)) {
            return null
        }
        val masterText = fetchTextUrl(masterUrl, headers) ?: return null
        val subLines = masterText.lineSequence()
            .filter { it.contains("TYPE=SUBTITLES", ignoreCase = true) && it.contains("URI=", ignoreCase = true) }
            .toList()
        if (subLines.isEmpty()) return null

        val langAttrRegex = Regex("""LANGUAGE="([^"]+)"""", RegexOption.IGNORE_CASE)
        val uriAttrRegex = Regex("""URI="([^"]+)"""", RegexOption.IGNORE_CASE)
        val candidates = subLines.mapNotNull { line ->
            val u = uriAttrRegex.find(line)?.groupValues?.get(1) ?: return@mapNotNull null
            val l = langAttrRegex.find(line)?.groupValues?.get(1).orEmpty().lowercase(Locale.US)
            l to u
        }
        if (candidates.isEmpty()) return null

        val normPref = preferredLang?.trim()?.lowercase(Locale.US).orEmpty()
        val chosenUri = (if (normPref.isNotBlank() && !normPref.startsWith("vi")) {
            candidates.firstOrNull { it.first == normPref || it.first.startsWith(normPref) }
        } else null)
            ?: candidates.firstOrNull { it.first.startsWith("en") }
            ?: candidates.firstOrNull { !it.first.startsWith("vi") }
            ?: candidates.first()

        val subPlUrl = java.net.URI(masterUrl).resolve(chosenUri.second).toString()
        val subPlText = fetchTextUrl(subPlUrl, headers) ?: return null
        if (!subPlText.contains("#EXTINF", ignoreCase = true) && subPlText.contains("-->")) {
            return subPlText
        }
        val basePlUri = java.net.URI(subPlUrl)
        val segUrls = subPlText.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { basePlUri.resolve(it).toString() }
            .distinct()
            .take(300)
            .toList()
        if (segUrls.isEmpty()) return null

        val results = arrayOfNulls<String>(segUrls.size)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(4.coerceAtMost(segUrls.size))
        try {
            val futures = segUrls.mapIndexed { i, segUrl ->
                pool.submit {
                    results[i] = fetchTextUrl(segUrl, headers)
                }
            }
            for (f in futures) {
                runCatching { f.get() }
            }
        } finally {
            pool.shutdownNow()
        }
        val merged = results.asSequence()
            .filterNotNull()
            .filter { it.isNotBlank() }
            .joinToString("\n\n")
        return merged.takeIf { it.isNotBlank() }
    }

    private fun parseSubtitleCues(rawText: String): List<Pair<String, String>> {
        val clean = rawText.removePrefix("\uFEFF").replace("\r\n", "\n").replace("\r", "\n")
        val blocks = clean.split(Regex("""\n{2,}"""))
        val tsRegex = Regex("""^((?:\d{1,2}:)?\d{2}:\d{2}[.,]\d{3})\s*-->\s*((?:\d{1,2}:)?\d{2}:\d{2}[.,]\d{3}.*)$""")
        val cues = ArrayList<Pair<String, String>>()
        for (block in blocks) {
            val lines = block.trim().lines()
            if (lines.isEmpty()) continue
            val tsIdx = lines.indexOfFirst { tsRegex.containsMatchIn(it.trim()) }
            if (tsIdx < 0 || tsIdx + 1 >= lines.size) continue
            val match = tsRegex.find(lines[tsIdx].trim()) ?: continue
            val start = match.groupValues[1].replace(',', '.')
            val end = match.groupValues[2].replace(',', '.')
            val normalizedStart = if (start.count { it == ':' } == 1) "00:$start" else start
            val normalizedEnd = if (end.substringBefore(' ').count { it == ':' } == 1) "00:$end" else end
            val text = lines.drop(tsIdx + 1).joinToString("\n")
                .replace(Regex("""<[^>]+>"""), "")
                .trim()
            if (text.isNotEmpty()) {
                cues.add("$normalizedStart --> $normalizedEnd" to text)
            }
        }
        return cues
    }

    private fun translateCuesToVietnamese(cues: List<Pair<String, String>>): List<Pair<String, String>> {
        val result = ArrayList<Pair<String, String>>(cues.size)
        val batchSize = 25
        var i = 0
        while (i < cues.size) {
            val end = (i + batchSize).coerceAtMost(cues.size)
            val batch = cues.subList(i, end)
            val joined = batch.joinToString("\n@@\n") { it.second.replace("@@", "@") }
            val translated = translateBatchViaGtx(joined)
            val parts = translated?.split(Regex("""\s*@@\s*"""))
            if (parts != null && parts.size == batch.size) {
                for (j in batch.indices) {
                    result.add(batch[j].first to parts[j].trim().ifEmpty { batch[j].second })
                }
            } else {
                // Split smaller if Google merged a separator
                for (item in batch) {
                    val single = translateBatchViaGtx(item.second)?.trim()?.ifEmpty { item.second } ?: item.second
                    result.add(item.first to single)
                }
            }
            i = end
        }
        return result
    }

    private fun translateBatchViaGtx(text: String): String? {
        return runCatching {
            val body = FormBody.Builder()
                .add("q", text)
                .build()
            val req = Request.Builder()
                .url("https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=vi&dt=t")
                .post(body)
                .header("User-Agent", AetherSourceResolver.USER_AGENT)
                .build()
            aetherHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching null
                val raw = resp.body?.string().orEmpty()
                val outer = JSONArray(raw)
                val segs = outer.optJSONArray(0) ?: return@runCatching null
                val sb = StringBuilder()
                for (k in 0 until segs.length()) {
                    val seg = segs.optJSONArray(k) ?: continue
                    val piece = seg.optString(0)
                    if (piece.isNotEmpty() && piece != "null") sb.append(piece)
                }
                sb.toString()
            }
        }.getOrNull()
    }

    private fun openAetherNativeMenu(route: String) {
        val wv = fallbackWebView
        if (wv == null) {
            showPlayerMessage("Menu Aether", "Trình phát Aether chưa sẵn sàng.")
            return
        }
        hideOsd()
        wv.visibility = View.VISIBLE
        wv.requestFocus()
        wv.evaluateJavascript("window.__anpinimeOpenAetherMenu && window.__anpinimeOpenAetherMenu('$route');", null)
    }

    private fun applyQualityMode() {
        updateTopStatusBadges()
        trackSelector?.let { selector ->
            selector.parameters = selector.buildUponParameters()
                .apply {
                    when (currentQualityMode) {
                        0 -> setMaxVideoSize(1920, 1080).setMaxVideoBitrate(8_500_000)
                        1 -> setMaxVideoSize(1280, 720).setMaxVideoBitrate(3_500_000)
                        else -> clearVideoSizeConstraints().setMaxVideoBitrate(Int.MAX_VALUE)
                    }
                }
                .build()
        }
    }

    private fun startCompatibilityPlayback(position: Long) {
        if (lastNativeUrl.isBlank()) return
        if (preferSoftwareAudio) {
            loadingText.text = "Không giải mã được luồng phát. Hãy chọn server khác."
            loadingOverlay.visibility = View.VISIBLE
            showOsd(true)
            return
        }
        preferSoftwareAudio = true
        prepareExoPlayer(lastNativeUrl, lastNativeHeaders, position, lastMediaKind, lastSubtitles)
    }

    private fun showTrackPicker(type: Int) {
        val title = if (type == C.TRACK_TYPE_TEXT) "Phụ đề" else "Âm thanh"
        val player = exoPlayer
        if (player == null) {
            if (fallbackWebView != null) {
                if (type == C.TRACK_TYPE_AUDIO) {
                    if (webAudioTracks.isEmpty()) {
                        showPlayerMessage("Âm thanh", "Nguồn phát hiện tại chỉ có 1 luồng âm thanh mặc định.")
                        return
                    }
                    val labels = webAudioTracks.map { it.label }
                    val selected = webAudioTracks.indexOfFirst { it.selected }.coerceAtLeast(0)
                    showSelection("Âm thanh", labels, selected) { idx ->
                        val chosen = webAudioTracks.getOrNull(idx) ?: return@showSelection
                        sendWebTrackCommand("audio", chosen.id)
                    }
                } else {
                    val baseSubs = webSubtitleTracks.ifEmpty {
                        if (isAetherEpisode()) {
                            listOf(
                                WebTrackItem("off", "🚫 Tắt phụ đề", false),
                                WebTrackItem("__aether_translate_vi__", "🇻🇳 Tự động dịch sang Tiếng Việt (Công cụ dịch Aether)", true)
                            )
                        } else emptyList()
                    }
                    val effectiveSubs = baseSubs + WebTrackItem(
                        "__local_sub_picker__",
                        "📂 Chọn file phụ đề (.srt / .vtt / .ass) từ máy",
                        false
                    )
                    val labels = effectiveSubs.map { it.label }
                    val selected = effectiveSubs.indexOfFirst { it.selected }.coerceAtLeast(0)
                    showSelection("Phụ đề & Dịch Tiếng Việt", labels, selected) { idx ->
                        val chosen = effectiveSubs.getOrNull(idx) ?: return@showSelection
                        if (chosen.id == "__local_sub_picker__") {
                            launchLocalSubtitlePicker()
                        } else {
                            if (viLiveSubActive) stopLiveViSubtitles(clearView = true)
                            sendWebTrackCommand("subtitles", chosen.id)
                        }
                    }
                }
            } else {
                showPlayerMessage(title, "Chưa có danh sách track. Hãy chờ video phát.")
            }
            return
        }
        val options = mutableListOf<Pair<androidx.media3.common.TrackGroup, Int>>()
        val labels = mutableListOf(if (type == C.TRACK_TYPE_TEXT) "Tắt phụ đề" else "Tự động")
        var selected = 0
        for (group in player.currentTracks.groups.filter { it.type == type }) {
            for (index in 0 until group.length) {
                if (!group.isTrackSupported(index)) continue
                val format = group.getTrackFormat(index)
                options.add(group.mediaTrackGroup to index)
                if (group.isTrackSelected(index)) selected = options.size
                val details = mutableListOf<String>()
                format.label?.takeIf { it.isNotBlank() }?.let { details.add(it) }
                val langName = format.language?.takeIf { it.isNotBlank() }?.let { formatLanguageName(it) }
                if (langName != null && !details.contains(langName)) {
                    details.add(langName)
                }
                if (type == C.TRACK_TYPE_AUDIO) {
                    when (format.channelCount) {
                        6 -> details.add("5.1 kênh")
                        8 -> details.add("7.1 kênh")
                        2 -> details.add("Stereo")
                        1 -> details.add("Mono")
                        in 3..5, in 7..7 -> details.add("${format.channelCount} kênh")
                    }
                    format.sampleMimeType?.substringAfterLast('/')?.uppercase()?.let { details.add(it) }
                } else {
                    SubtitleSupport.formatTrackBadgeLabel(format.sampleMimeType)?.let { details.add(it) }
                }
                labels.add(details.joinToString(" • ").ifBlank { "Track ${options.size}" })
            }
        }
        val hasAetherTranslate = type == C.TRACK_TYPE_TEXT && (isAetherEpisode() || lastSubtitles.isNotEmpty())
        val aiTranslateIdx = if (hasAetherTranslate) {
            val idx = labels.size
            labels.add(if (viLiveSubActive) "✓ 🇻🇳 Tiếng Việt (AI - Đang bật)" else "🌐 [Dịch sang Tiếng Việt tức thì (AI)]")
            if (viLiveSubActive) selected = idx
            idx
        } else -1
        val localPickerIdx = if (type == C.TRACK_TYPE_TEXT) {
            val idx = labels.size
            labels.add("📂 Chọn file phụ đề (.srt / .vtt / .ass) từ máy")
            idx
        } else -1
        if (options.isEmpty() && aiTranslateIdx < 0 && localPickerIdx < 0) {
            showPlayerMessage(
                title,
                "Chưa tìm thấy track âm thanh được hỗ trợ. Hãy thử đổi bộ giải mã âm thanh hoặc server khác."
            )
            return
        }
        showSelection(title, labels, selected) { index ->
            if (localPickerIdx >= 0 && index == localPickerIdx) {
                launchLocalSubtitlePicker()
                return@showSelection
            }
            if (aiTranslateIdx >= 0 && index == aiTranslateIdx) {
                if (viLiveSubActive) {
                    showGestureFeedback("✓ Phụ đề Việt AI đang bật", Gravity.CENTER)
                } else {
                    triggerAetherVietnameseTranslation()
                }
                return@showSelection
            }
            if (type == C.TRACK_TYPE_TEXT) {
                if (viLiveSubActive) {
                    stopLiveViSubtitles(clearView = true)
                }
                if (index == 0) {
                    selectedSourceSubUrl = ""
                }
            }
            val builder = player.trackSelectionParameters.buildUpon()
                .clearOverridesOfType(type)
                .setTrackTypeDisabled(type, type == C.TRACK_TYPE_TEXT && index == 0)
            if (index > 0 && index <= options.size) {
                val (group, track) = options[index - 1]
                builder.setOverrideForType(TrackSelectionOverride(group, track))
                if (type == C.TRACK_TYPE_TEXT) {
                    val fmt = group.getFormat(track)
                    val matched = fmt.id?.takeIf { !it.contains("/aether_subs/") }?.let { idUrl ->
                        lastSubtitles.firstOrNull { it.url == idUrl }
                    } ?: lastSubtitles.firstOrNull { sub ->
                        !sub.url.contains("/aether_subs/") &&
                            (fmt.language.isNullOrBlank() || sub.language.equals(fmt.language, true)) &&
                            (fmt.label.isNullOrBlank() || sub.label.equals(fmt.label, true))
                    }
                    if (matched != null && !matched.url.contains("/aether_subs/")) {
                        selectedSourceSubUrl = matched.url
                        prewarmSourceSubtitle(lastSubtitles, lastNativeUrl, lastNativeHeaders, matched.url)
                    }
                }
            }
            player.trackSelectionParameters = builder.build()
        }
    }

    private fun launchLocalSubtitlePicker() {
        mainHandler.removeCallbacks(hideOsdRunnable)
        runCatching {
            pickLocalSubtitleLauncher.launch(arrayOf("*/*"))
        }.onFailure {
            showPlayerMessage(
                "Chọn phụ đề",
                "Thiết bị không hỗ trợ trình chọn tệp hệ thống (SAF)."
            )
        }
    }

    private fun importLocalSubtitleUri(uri: Uri) {
        runCatching {
            contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        uiScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                val displayName = SubtitleSupport.queryDisplayName(this@PlayerActivity, uri)
                val rawBytes = SubtitleSupport.fetchRawBytes(this@PlayerActivity, uri.toString(), emptyMap(), aetherHttpClient)
                    ?: return@withContext null
                val decoded = SubtitleSupport.decodeSubtitleBytes(rawBytes)
                if (decoded.isBlank() ||
                    (!decoded.contains("-->") && !decoded.contains("Dialogue:", ignoreCase = true))
                ) {
                    return@withContext Triple(displayName, "", "")
                }
                val detectedMime = SubtitleSupport.detectSubtitleFormat(displayName, decoded)
                val outDir = File(cacheDir, "local_subs").apply { mkdirs() }
                val ext = when (detectedMime) {
                    MimeTypes.TEXT_VTT -> "vtt"
                    MimeTypes.TEXT_SSA -> "ass"
                    else -> "srt"
                }
                val cleanText = when (detectedMime) {
                    MimeTypes.TEXT_SSA -> decoded
                    MimeTypes.TEXT_VTT -> SubtitleSupport.normalizeToWebVtt(decoded)
                    else -> SubtitleSupport.normalizeToSubRip(decoded)
                }
                val safeKey = SubtitleTranslator.computeSubCacheKey(uri.toString() + displayName, cleanText.length, "local")
                val outFile = File(outDir, "sub_$safeKey.$ext")
                outFile.writeText(cleanText, Charsets.UTF_8)
                Triple(displayName, Uri.fromFile(outFile).toString(), detectedMime to cleanText)
            }

            if (loaded == null) {
                showPlayerMessage("Phụ đề cục bộ", "Không đọc được tệp phụ đề đã chọn.")
                return@launch
            }
            val displayName = loaded.first
            val fileUrl = loaded.second
            val metaPair = loaded.third
            if (fileUrl.isBlank() || metaPair !is Pair<*, *>) {
                showPlayerMessage("Phụ đề cục bộ", "Tệp \"$displayName\" không đúng định dạng phụ đề (.srt / .vtt / .ass).")
                return@launch
            }
            val detectedMime = metaPair.first as String
            val cleanText = metaPair.second as String

            val newTrack = ExternalSubtitleTrack(
                url = fileUrl,
                language = "vi",
                label = "📁 $displayName",
                mimeType = detectedMime
            )
            lastSubtitles = listOf(newTrack) + lastSubtitles.filterNot { it.url == fileUrl }
            selectedSourceSubUrl = fileUrl
            pendingSelectSubtitleUrl = fileUrl

            if (exoPlayer != null && lastNativeUrl.isNotBlank()) {
                stopLiveViSubtitles(clearView = true)
                val resumePos = exoPlayer?.currentPosition?.coerceAtLeast(0L) ?: 0L
                prepareExoPlayer(lastNativeUrl, lastNativeHeaders, resumePos, lastMediaKind, lastSubtitles)
                showGestureFeedback("✓ Đã nạp phụ đề: $displayName", Gravity.CENTER)
            } else {
                val cues = SubtitleTranslator.parseTimedCues(
                    if (detectedMime == MimeTypes.TEXT_SSA) cleanText else SubtitleSupport.normalizeToSubRip(cleanText)
                )
                if (cues.isEmpty()) {
                    showPlayerMessage("Phụ đề cục bộ", "Không tìm thấy dòng thoại hợp lệ trong \"$displayName\".")
                    return@launch
                }
                viLiveCues = cues
                viTranslatedMap.clear()
                cues.forEachIndexed { idx, cue -> viTranslatedMap[idx] = cue.text }
                translatedEpisodeKey = "${currentEpisode.slug}|local:$fileUrl"
                viTranslationCompleted = true
                viLiveSubActive = true
                viLastRenderedCueText = VI_CUE_UNSET
                renderLiveSubtitleAt(webPositionMs.coerceAtLeast(0L), force = true)
                mainHandler.removeCallbacks(subtitleTickRunnable)
                mainHandler.post(subtitleTickRunnable)
                showGestureFeedback("✓ Đã nạp phụ đề: $displayName", Gravity.CENTER)
            }
        }
    }

    private fun formatLanguageName(code: String): String {
        return when (code.lowercase().trim()) {
            "vi", "vie", "vietnamese" -> "Tiếng Việt"
            "en", "eng", "english" -> "Tiếng Anh"
            "ja", "jpn", "japanese" -> "Tiếng Nhật"
            "ko", "kor", "korean" -> "Tiếng Hàn"
            "zh", "zho", "chi", "chinese" -> "Tiếng Trung"
            "th", "tha", "thai" -> "Tiếng Thái"
            "fr", "fra", "fre", "french" -> "Tiếng Pháp"
            "de", "deu", "ger", "german" -> "Tiếng Đức"
            "es", "spa", "spanish" -> "Tiếng Tây Ban Nha"
            "ru", "rus", "russian" -> "Tiếng Nga"
            "pt", "por", "portuguese" -> "Tiếng Bồ Đào Nha"
            "it", "ita", "italian" -> "Tiếng Ý"
            "ar", "ara", "arabic" -> "Tiếng Ả Rập"
            "id", "ind", "indonesian" -> "Tiếng Indonesia"
            "tr", "tur", "turkish" -> "Tiếng Thổ Nhĩ Kỳ"
            "nl", "nld", "dut", "dutch" -> "Tiếng Hà Lan"
            "pl", "pol", "polish" -> "Tiếng Ba Lan"
            "hi", "hin", "hindi" -> "Tiếng Hindi"
            "uk", "ukr", "ukrainian" -> "Tiếng Ukraine"
            else -> code
        }
    }

    private fun showSubtitleSizePicker() {
        val options = listOf(
            "Nhỏ (18sp)",
            "Vừa (22sp)",
            "Lớn (26sp — Chuẩn máy chiếu KAW K670Pro)",
            "Rất lớn (32sp — Dành cho màn chiếu lớn)"
        )
        showSelection("Cỡ chữ phụ đề", options, subtitleScaleMode) { index ->
            subtitleScaleMode = index
            applySubtitleScale()
        }
    }

    private fun applySubtitleScale() {
        if (!::subtitleView.isInitialized) return
        val spSize = when (subtitleScaleMode) {
            0 -> 18f
            1 -> 22f
            3 -> 32f
            else -> 26f
        }
        subtitleView.setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, spSize)
        val styleObj = JSONObject().put("size", spSize.toInt())
        val command = JSONObject().put("type", "AnPinimeCommand").put("name", "subtitleStyle").put("style", styleObj)
        fallbackWebView?.evaluateJavascript("window.postMessage($command, '*');", null)
    }

    private fun togglePlayPause() {
        val player = exoPlayer
        if (player != null) {
            if (player.isPlaying) {
                player.pause()
            } else {
                player.play()
            }
            return
        }
        sendWebCommand(if (webPaused) "play" else "pause")
    }

    private fun computeAcceleratedSeekStep(repeatCount: Int): Long {
        return when {
            repeatCount >= 12 -> 120_000L
            repeatCount >= 6 -> 60_000L
            repeatCount >= 2 -> 30_000L
            else -> 10_000L
        }
    }

    private fun seekRelative(deltaMs: Long) {
        val player = exoPlayer
        if (player != null) {
            val dur = player.duration.takeIf { it > 0 } ?: return
            val basePos = if (pendingSeekTargetMs >= 0L) pendingSeekTargetMs else player.currentPosition
            val target = (basePos + deltaMs).coerceIn(0L, dur)
            pendingSeekTargetMs = target
            timeCurrentText.text = formatTime(target)
            if (::seekPreviewBadge.isInitialized) {
                seekPreviewBadge.text = "${if (deltaMs >= 0) "+" else "-"}${kotlin.math.abs(deltaMs) / 1000L}s  →  ${formatTime(target)}"
                seekPreviewBadge.visibility = View.VISIBLE
            }
            seekBar.progress = ((target * 1000L) / dur).toInt().coerceIn(0, 1000)
            mainHandler.removeCallbacks(commitPendingSeekRunnable)
            mainHandler.postDelayed(commitPendingSeekRunnable, 600L)
            return
        }
        val dur = webDurationMs.takeIf { it > 0 } ?: return
        val basePos = if (pendingSeekTargetMs >= 0L) pendingSeekTargetMs else webPositionMs
        val target = (basePos + deltaMs).coerceIn(0L, dur)
        pendingSeekTargetMs = target
        timeCurrentText.text = formatTime(target)
        if (::seekPreviewBadge.isInitialized) {
            seekPreviewBadge.text = "${if (deltaMs >= 0) "+" else "-"}${kotlin.math.abs(deltaMs) / 1000L}s  →  ${formatTime(target)}"
            seekPreviewBadge.visibility = View.VISIBLE
        }
        seekBar.progress = ((target * 1000L) / dur).toInt().coerceIn(0, 1000)
        mainHandler.removeCallbacks(commitPendingSeekRunnable)
        mainHandler.postDelayed(commitPendingSeekRunnable, 600L)
    }

    private fun commitPendingSeek() {
        val target = pendingSeekTargetMs
        pendingSeekTargetMs = -1L
        mainHandler.removeCallbacks(commitPendingSeekRunnable)
        if (::seekPreviewBadge.isInitialized) {
            seekPreviewBadge.visibility = View.GONE
        }
        if (target < 0L) return
        val player = exoPlayer
        if (player != null) {
            player.seekTo(target)
            updateProgressUi()
            return
        }
        sendWebCommand("seek", target / 1000.0)
        webPositionMs = target
        updateProgressUi()
    }

    private fun sendWebCommand(name: String, value: Double = 0.0) {
        val command = JSONObject().put("type", "AnPinimeCommand").put("name", name).put("value", value)
        fallbackWebView?.evaluateJavascript("window.postMessage($command, '*');", null)
    }

    private fun sendWebTrackCommand(kind: String, id: String) {
        val command = JSONObject()
            .put("type", "AnPinimeCommand")
            .put("name", "track")
            .put("kind", kind)
            .put("id", id)
        fallbackWebView?.evaluateJavascript("window.postMessage($command, '*');", null)
    }

    private fun playNextEpisode() {
        val sameServer = allEpisodes.filter { it.season == currentEpisode.season }
        val idx = sameServer.indexOfFirst { it.number == currentEpisode.number && it.title == currentEpisode.title }
        if (idx >= 0 && idx + 1 < sameServer.size) {
            startEpisodePlayback(sameServer[idx + 1], resetPosition = true)
        }
    }

    private fun showOsd(focusPlayButton: Boolean = false) {
        osdVisible = true
        osdContainer.visibility = View.VISIBLE
        if (focusPlayButton) {
            playPauseBtn.requestFocus()
        }
        scheduleHideOsd()
    }

    private fun hideOsd() {
        osdVisible = false
        if (sidePanelVisible) {
            sidePanelVisible = false
            sidePanelBackdrop.visibility = View.GONE
            sidePanelContainer.visibility = View.GONE
        }
        osdContainer.visibility = View.GONE
    }

    private fun scheduleHideOsd() {
        mainHandler.removeCallbacks(hideOsdRunnable)
        if (selectionDialog == null && !scrubbing && !sidePanelVisible) {
            mainHandler.postDelayed(hideOsdRunnable, 6000L)
        }
    }

    private fun updateProgressUi() {
        val player = exoPlayer
        val dur = player?.duration?.takeIf { it > 0 } ?: webDurationMs
        val pos = if (pendingSeekTargetMs >= 0L) {
            pendingSeekTargetMs
        } else {
            player?.currentPosition?.coerceAtLeast(0L) ?: webPositionMs
        }
        timeCurrentText.text = formatTime(pos)
        timeTotalText.text = formatTime(dur)
        if (dur > 0L && !scrubbing && pendingSeekTargetMs < 0L) {
            seekBar.progress = ((pos * 1000L) / dur).toInt().coerceIn(0, 1000)
            val buf = player?.bufferedPosition?.coerceAtLeast(pos) ?: pos
            seekBar.secondaryProgress = ((buf * 1000L) / dur).toInt().coerceIn(0, 1000)
        }
    }

    private fun formatTime(ms: Long): String {
        val totalSec = (ms / 1000L).coerceAtLeast(0L)
        val h = totalSec / 3600L
        val m = (totalSec % 3600L) / 60L
        val s = totalSec % 60L
        return if (h > 0L) {
            String.format("%d:%02d:%02d", h, m, s)
        } else {
            String.format("%02d:%02d", m, s)
        }
    }
}
