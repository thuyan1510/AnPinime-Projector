package com.anpinime.projector

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
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
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject

@OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity() {

    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var resolveJob: Job? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private var movieTitle: String = ""
    private lateinit var currentEpisode: Episode
    private var allEpisodes: List<Episode> = emptyList()

    private var exoPlayer: ExoPlayer? = null
    private var trackSelector: DefaultTrackSelector? = null
    private var filmRelay: FilmMediaRelay? = null
    private var fallbackWebView: WebView? = null
    private var lastWebUrl: String = ""
    private var retryCount = 0
    private var currentQualityMode = 0 // 0 = Auto 1080p, 1 = 720p (Siêu mượt), 2 = 4K Gốc

    private lateinit var videoFrame: FrameLayout
    private lateinit var surfaceView: SurfaceView
    private lateinit var loadingOverlay: LinearLayout
    private lateinit var loadingText: TextView
    private lateinit var osdContainer: FrameLayout
    private lateinit var topTitleText: TextView
    private lateinit var topServerText: TextView
    private lateinit var timeCurrentText: TextView
    private lateinit var timeTotalText: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var playPauseBtn: TextView
    private lateinit var qualityBtn: TextView
    private lateinit var serverDrawer: HorizontalScrollView
    private lateinit var serverDrawerRow: LinearLayout
    private lateinit var episodeDrawer: HorizontalScrollView
    private lateinit var episodeDrawerRow: LinearLayout

    private var osdVisible = true

    private val hideOsdRunnable = Runnable {
        if (exoPlayer?.isPlaying == true || fallbackWebView != null) {
            hideOsd()
        }
    }

    private val progressRunnable = object : Runnable {
        override fun run() {
            updateProgressUi()
            mainHandler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WebViewHelper.registerActivity(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        ImageLoader.trimMemory()

        movieTitle = intent.getStringExtra("movie_title").orEmpty()
        val epRaw = intent.getStringExtra("episode_json").orEmpty()
        val allRaw = intent.getStringExtra("all_episodes_json").orEmpty()
        if (epRaw.isEmpty()) {
            finish()
            return
        }
        currentEpisode = Episode.fromJsonObject(JSONObject(epRaw))
        allEpisodes = Episode.fromJsonArray(allRaw).ifEmpty { listOf(currentEpisode) }

        buildUi()
        startEpisodePlayback(currentEpisode, resetPosition = true)
        mainHandler.post(progressRunnable)
    }

    override fun onResume() {
        super.onResume()
        WebViewHelper.registerActivity(this)
    }

    override fun onStop() {
        super.onStop()
        exoPlayer?.pause()
        fallbackWebView?.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacksAndMessages(null)
        resolveJob?.cancel()
        uiScope.cancel()
        releasePlayer()
        destroyWebPlayer()
    }

    private fun releasePlayer() {
        try {
            filmRelay?.stop()
        } catch (_: Throwable) {}
        filmRelay = null
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

    private fun destroyWebPlayer() {
        val wv = fallbackWebView ?: return
        fallbackWebView = null
        try {
            wv.stopLoading()
            (wv.parent as? ViewGroup)?.removeView(wv)
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

        if (event.action == KeyEvent.ACTION_DOWN) {
            scheduleHideOsd()

            // Media buttons on projector remote work whether OSD is visible or hidden
            when (keyCode) {
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                    togglePlayPause()
                    showOsd()
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PLAY -> {
                    exoPlayer?.play()
                    showOsd()
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                    exoPlayer?.pause()
                    showOsd()
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                    seekRelative(10_000L)
                    showOsd(focusPlayButton = false)
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_REWIND -> {
                    seekRelative(-10_000L)
                    showOsd(focusPlayButton = false)
                    return true
                }
            }

            if (!osdVisible) {
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        seekRelative(-10_000L)
                        showOsd(focusPlayButton = false)
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        seekRelative(10_000L)
                        showOsd(focusPlayButton = false)
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_CENTER,
                    KeyEvent.KEYCODE_ENTER,
                    KeyEvent.KEYCODE_NUMPAD_ENTER -> {
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
                    if (exoPlayer?.isPlaying == true || fallbackWebView != null) {
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
    // UI CONSTRUCTION (Pure Hardware SurfaceView + Animated GIF Loader + OSD)
    // =========================================================================

    private fun buildUi() {
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setOnClickListener {
                if (osdVisible) hideOsd() else showOsd(focusPlayButton = true)
            }
        }

        videoFrame = FrameLayout(this).apply {
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
        videoFrame.addView(surfaceView)
        root.addView(videoFrame)

        // Loading / Status Overlay with iOS Animated Loading GIF
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
        loadingOverlay.addView(UiHelper.createGifView(this, currentEpisode.source.isMovie, sizeDp = 92))
        loadingText = TextView(this).apply {
            text = "Đang kết nối luồng phát…"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, UiHelper.dp(context, 10), 0, 0)
        }
        loadingOverlay.addView(loadingText)
        root.addView(loadingOverlay)

        // OSD Controls Container
        osdContainer = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // Top Bar
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(UiHelper.dp(context, 24), UiHelper.dp(context, 18), UiHelper.dp(context, 24), UiHelper.dp(context, 28))
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.parseColor("#E6000000"), Color.TRANSPARENT)
            )
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
            )
        }

        topTitleText = TextView(this).apply {
            text = movieTitle
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            typeface = Typeface.DEFAULT_BOLD
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
        }
        topServerText = TextView(this).apply {
            text = "${currentEpisode.title} • ${currentEpisode.season}"
            setTextColor(UiHelper.COLOR_YELLOW)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, UiHelper.dp(context, 4), 0, 0)
        }
        topBar.addView(topTitleText)
        topBar.addView(topServerText)
        osdContainer.addView(topBar)

        // Bottom Control Panel
        val bottomPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(UiHelper.dp(context, 24), UiHelper.dp(context, 24), UiHelper.dp(context, 24), UiHelper.dp(context, 20))
            background = GradientDrawable(
                GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(Color.parseColor("#F208090E"), Color.parseColor("#B308090E"), Color.TRANSPARENT)
            )
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            )
        }

        // Server Drawer (Toggled when user clicks Đổi Server)
        serverDrawer = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = UiHelper.dp(context, 10) }
        }
        serverDrawerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        serverDrawer.addView(serverDrawerRow)
        bottomPanel.addView(serverDrawer)

        // Episode Drawer (Toggled when user clicks Chọn Tập)
        episodeDrawer = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = UiHelper.dp(context, 10) }
        }
        episodeDrawerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        episodeDrawer.addView(episodeDrawerRow)
        bottomPanel.addView(episodeDrawer)

        // Timeline Row
        val timelineRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = UiHelper.dp(context, 10) }
        }

        timeCurrentText = TextView(this).apply {
            text = "00:00"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT_BOLD
        }
        seekBar = SeekBar(this).apply {
            max = 1000
            isFocusable = false
            isFocusableInTouchMode = false
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = UiHelper.dp(context, 10)
                rightMargin = UiHelper.dp(context, 10)
            }
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        val dur = exoPlayer?.duration ?: 0L
                        if (dur > 0) {
                            exoPlayer?.seekTo(dur * progress / 1000L)
                        }
                    }
                }
                override fun onStartTrackingTouch(bar: SeekBar?) {}
                override fun onStopTrackingTouch(bar: SeekBar?) {}
            })
        }
        timeTotalText = TextView(this).apply {
            text = "00:00"
            setTextColor(UiHelper.COLOR_TEXT_SECONDARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT_BOLD
        }
        timelineRow.addView(timeCurrentText)
        timelineRow.addView(seekBar)
        timelineRow.addView(timeTotalText)
        bottomPanel.addView(timelineRow)

        // Remote Action Buttons Row
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        fun makeOsdBtn(label: String, bg: Int = UiHelper.COLOR_CARD, onClick: () -> Unit): TextView {
            val tv = TextView(this).apply {
                text = label
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                typeface = Typeface.DEFAULT_BOLD
                setSingleLine(true)
                setPadding(UiHelper.dp(context, 14), UiHelper.dp(context, 9), UiHelper.dp(context, 14), UiHelper.dp(context, 9))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { rightMargin = UiHelper.dp(context, 8) }
            }
            UiHelper.makeFocusableButton(
                view = tv,
                normalBgColor = bg,
                focusedBgColor = UiHelper.COLOR_ACCENT,
                focusedStrokeColor = Color.WHITE,
                radiusDp = 18
            ) {
                scheduleHideOsd()
                onClick()
            }
            return tv
        }

        playPauseBtn = makeOsdBtn("⏸ Tạm dừng", UiHelper.COLOR_RED) {
            togglePlayPause()
        }
        btnRow.addView(playPauseBtn)

        btnRow.addView(makeOsdBtn("⏪ -10s") { seekRelative(-10_000L) })
        btnRow.addView(makeOsdBtn("⏩ +10s") { seekRelative(10_000L) })

        btnRow.addView(makeOsdBtn("⏭ Tập tiếp") {
            playNextEpisode()
        })

        btnRow.addView(makeOsdBtn("🖥 Đổi Server") {
            episodeDrawer.visibility = View.GONE
            if (serverDrawer.visibility == View.VISIBLE) {
                serverDrawer.visibility = View.GONE
            } else {
                populateServerDrawer()
                serverDrawer.visibility = View.VISIBLE
            }
        })

        btnRow.addView(makeOsdBtn("📋 Chọn Tập") {
            serverDrawer.visibility = View.GONE
            if (episodeDrawer.visibility == View.VISIBLE) {
                episodeDrawer.visibility = View.GONE
            } else {
                populateEpisodeDrawer()
                episodeDrawer.visibility = View.VISIBLE
            }
        })

        qualityBtn = makeOsdBtn("⚙ Chất lượng: 1080p") {
            cycleQualityMode()
        }
        btnRow.addView(qualityBtn)

        btnRow.addView(makeOsdBtn("✕ Thoát", Color.parseColor("#381E24")) {
            finish()
        })

        bottomPanel.addView(btnRow)
        osdContainer.addView(bottomPanel)
        root.addView(osdContainer)

        setContentView(root)
        playPauseBtn.requestFocus()
        scheduleHideOsd()
    }

    private fun populateServerDrawer() {
        serverDrawerRow.removeAllViews()
        val seasons = ArrayList<String>()
        for (ep in allEpisodes) {
            if (!seasons.contains(ep.season)) seasons.add(ep.season)
        }
        for (sv in seasons) {
            val isCurrent = sv == currentEpisode.season
            val matchedEp = allEpisodes.firstOrNull { it.season == sv && it.number == currentEpisode.number }
                ?: allEpisodes.firstOrNull { it.season == sv } ?: continue
            val badge = matchedEp.qualityBadge
            val chip = TextView(this).apply {
                text = if (badge.isNotBlank()) "🖥 $sv [$badge]" else "🖥 $sv"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(UiHelper.dp(context, 14), UiHelper.dp(context, 8), UiHelper.dp(context, 14), UiHelper.dp(context, 8))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { rightMargin = UiHelper.dp(context, 8) }
            }
            UiHelper.makeFocusableButton(
                view = chip,
                normalBgColor = if (isCurrent) UiHelper.COLOR_BLUE else UiHelper.COLOR_CARD,
                focusedBgColor = UiHelper.COLOR_ACCENT,
                focusedStrokeColor = Color.WHITE,
                radiusDp = 16
            ) {
                serverDrawer.visibility = View.GONE
                val resumePos = exoPlayer?.currentPosition ?: 0L
                startEpisodePlayback(matchedEp, resetPosition = false, initialPositionMs = resumePos)
            }
            serverDrawerRow.addView(chip)
        }
    }

    private fun populateEpisodeDrawer() {
        episodeDrawerRow.removeAllViews()
        val sameServerEps = allEpisodes.filter { it.season == currentEpisode.season }
        for (ep in sameServerEps) {
            val isCurrent = ep.number == currentEpisode.number && ep.title == currentEpisode.title
            val chip = TextView(this).apply {
                text = ep.title
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(UiHelper.dp(context, 14), UiHelper.dp(context, 8), UiHelper.dp(context, 14), UiHelper.dp(context, 8))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { rightMargin = UiHelper.dp(context, 8) }
            }
            UiHelper.makeFocusableButton(
                view = chip,
                normalBgColor = if (isCurrent) UiHelper.COLOR_RED else UiHelper.COLOR_CARD,
                focusedBgColor = UiHelper.COLOR_ACCENT,
                focusedStrokeColor = Color.WHITE,
                radiusDp = 16
            ) {
                episodeDrawer.visibility = View.GONE
                startEpisodePlayback(ep, resetPosition = true)
            }
            episodeDrawerRow.addView(chip)
        }
    }

    // =========================================================================
    // EXOPLAYER & WEB FALLBACK INITIALIZATION (Low-RAM Projector Tuned)
    // =========================================================================

    private fun startEpisodePlayback(
        episode: Episode,
        resetPosition: Boolean,
        initialPositionMs: Long = 0L
    ) {
        currentEpisode = episode
        retryCount = 0
        topServerText.text = "${episode.title} • ${episode.season}"
        loadingOverlay.visibility = View.VISIBLE
        loadingText.text = "Đang tải ${episode.title} (${episode.season})…"
        destroyWebPlayer()
        surfaceView.visibility = View.VISIBLE

        resolveJob?.cancel()
        resolveJob = uiScope.launch {
            try {
                val resolved = CatalogService.resolvePlayback(episode)
                lastWebUrl = resolved.webUrl.ifBlank { episode.url }
                if (resolved.isWeb) {
                    startWebFallback(resolved.url, resolved.headers["Referer"] ?: episode.url)
                    return@launch
                }
                val playUrl = if (resolved.isF4kWrapped) {
                    filmRelay?.stop()
                    val relay = FilmMediaRelay(resolved.headers)
                    filmRelay = relay
                    relay.start(resolved.url)
                } else {
                    filmRelay?.stop()
                    filmRelay = null
                    resolved.url
                }
                prepareExoPlayer(
                    streamUrl = playUrl,
                    headers = if (resolved.isF4kWrapped) emptyMap() else resolved.headers,
                    seekMs = if (resetPosition) 0L else initialPositionMs
                )
            } catch (t: Throwable) {
                if (!trySwitchToBackupServer(0L)) {
                    if (episode.url.startsWith("http", ignoreCase = true) && !episode.source.isMovie) {
                        startWebFallback(episode.url, CatalogService.animeBaseUrl)
                    } else {
                        loadingOverlay.visibility = View.VISIBLE
                        loadingText.text = "Lỗi tải luồng: ${t.message}\nBấm phím Lên/Xuống -> Đổi Server khác."
                        showOsd(focusPlayButton = true)
                    }
                }
            }
        }
    }

    private fun prepareExoPlayer(
        streamUrl: String,
        headers: Map<String, String>,
        seekMs: Long
    ) {
        releasePlayer()
        destroyWebPlayer()
        surfaceView.visibility = View.VISIBLE

        val selector = DefaultTrackSelector(this).apply {
            parameters = buildUponParameters()
                .apply {
                    when (currentQualityMode) {
                        0 -> setMaxVideoSize(1920, 1080).setMaxVideoBitrate(8_500_000)
                        1 -> setMaxVideoSize(1280, 720).setMaxVideoBitrate(3_500_000)
                        else -> clearVideoSizeConstraints().setMaxVideoBitrate(Int.MAX_VALUE)
                    }
                }
                .build()
        }
        trackSelector = selector

        // Strict 12MB RAM buffer ceiling so 1GB/2GB projector never swaps or hangs
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                10_000, // minBufferMs
                24_000, // maxBufferMs
                2_000,  // bufferForPlaybackMs
                4_000   // bufferForPlaybackAfterRebufferMs
            )
            .setTargetBufferBytes(12 * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()

        val renderersFactory = DefaultRenderersFactory(this)
            .setEnableDecoderFallback(true)

        val mergedHeaders = HashMap(headers)
        WebViewHelper.getCookieHeader(streamUrl)?.let { mergedHeaders["Cookie"] = it }

        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(CatalogService.USER_AGENT)
            .setConnectTimeoutMs(12_000)
            .setReadTimeoutMs(18_000)
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(mergedHeaders)

        val player = ExoPlayer.Builder(this, renderersFactory)
            .setTrackSelector(selector)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(DefaultMediaSourceFactory(httpFactory))
            .build()

        player.videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT
        player.setVideoSurfaceView(surfaceView)

        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_BUFFERING -> {
                        loadingOverlay.visibility = View.VISIBLE
                        loadingText.text = "Đang nạp bộ đệm…"
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
                playPauseBtn.text = if (isPlaying) "⏸ Tạm dừng" else "▶ Phát tiếp"
                if (isPlaying) {
                    scheduleHideOsd()
                }
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                adjustSurfaceAspectRatio(videoSize.width, videoSize.height)
            }

            override fun onPlayerError(error: PlaybackException) {
                val lastPos = player.currentPosition.coerceAtLeast(0L)
                if (retryCount < 1) {
                    retryCount++
                    loadingOverlay.visibility = View.VISIBLE
                    loadingText.text = "Đang tự động khôi phục kết nối…"
                    player.prepare()
                    if (lastPos > 0L) player.seekTo(lastPos)
                    player.play()
                } else {
                    // Always post server switch to mainHandler so we never release ExoPlayer inside its own listener
                    mainHandler.post {
                        if (!trySwitchToBackupServer(lastPos)) {
                            if (lastWebUrl.startsWith("http", ignoreCase = true) &&
                                (lastWebUrl.contains("embed", ignoreCase = true) ||
                                    lastWebUrl.contains("player", ignoreCase = true) ||
                                    !currentEpisode.source.isMovie)
                            ) {
                                startWebFallback(lastWebUrl, currentEpisode.url)
                            } else {
                                loadingOverlay.visibility = View.VISIBLE
                                loadingText.text = "Máy chủ hiện tại bị gián đoạn.\nHãy chọn [Đổi Server] ở thanh bên dưới."
                                showOsd(focusPlayButton = true)
                            }
                        }
                    }
                }
            }
        })

        exoPlayer = player

        // Explicitly set MimeTypes.APPLICATION_M3U8 for all HLS streams (including /api/content/stream/hls)
        val cleanPath = streamUrl.substringBefore('?').lowercase()
        val isDirectContainer = cleanPath.endsWith(".mp4") ||
            cleanPath.endsWith(".mkv") ||
            cleanPath.endsWith(".mov") ||
            cleanPath.endsWith(".webm")

        val mediaItem = MediaItem.Builder()
            .setUri(Uri.parse(streamUrl))
            .apply {
                if (!isDirectContainer) {
                    setMimeType(MimeTypes.APPLICATION_M3U8)
                }
            }
            .build()

        player.setMediaItem(mediaItem)
        player.prepare()
        if (seekMs > 0L) {
            player.seekTo(seekMs)
        }
        player.playWhenReady = true
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun startWebFallback(webUrl: String, referer: String) {
        releasePlayer()
        destroyWebPlayer()
        surfaceView.visibility = View.GONE
        loadingOverlay.visibility = View.VISIBLE
        loadingText.text = "Đang mở trình phát dự phòng…"

        val wv = WebView(this).apply {
            setBackgroundColor(Color.BLACK)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
            )
            isFocusable = false
            isFocusableInTouchMode = false
        }
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(wv, true)

        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            userAgentString = CatalogService.USER_AGENT
        }

        wv.addJavascriptInterface(object {
            @JavascriptInterface
            fun onVideoFound(streamUrl: String, refUrl: String) {
                mainHandler.post {
                    if (streamUrl.startsWith("http", ignoreCase = true) && exoPlayer == null) {
                        val origin = try {
                            val u = java.net.URL(refUrl.ifBlank { webUrl })
                            "${u.protocol}://${u.host}"
                        } catch (_: Throwable) {
                            ""
                        }
                        val hdrs = HashMap<String, String>()
                        hdrs["User-Agent"] = CatalogService.USER_AGENT
                        if (origin.isNotBlank()) {
                            hdrs["Referer"] = "$origin/"
                            hdrs["Origin"] = origin
                        }
                        prepareExoPlayer(streamUrl, hdrs, 0L)
                    }
                }
            }

            @JavascriptInterface
            fun onPlayerReady() {
                mainHandler.post {
                    loadingOverlay.visibility = View.GONE
                    scheduleHideOsd()
                }
            }
        }, "AnPinimeWebBridge")

        val fallbackIsolateScript = """
            (function(){
              if(window.__anpinimeInstalled) return;
              window.__anpinimeInstalled = true;
              var reported = {};
              function observe(u) {
                try {
                  var full = new URL(String(u), location.href).href;
                  if (/\.m3u8(?:[?#]|$)/i.test(full) && !/(?:doubleclick|popads|\/ads?\/)/i.test(full) && !reported[full]) {
                    reported[full] = true;
                    AnPinimeWebBridge.onVideoFound(full, location.href);
                  }
                } catch(e){}
              }
              var origFetch = window.fetch;
              if (origFetch) window.fetch = function() {
                var a = arguments[0]; observe(a && a.url || a);
                return origFetch.apply(this, arguments);
              };
              var origOpen = XMLHttpRequest.prototype.open;
              XMLHttpRequest.prototype.open = function(m, u) {
                observe(u);
                return origOpen.apply(this, arguments);
              };
              function imp(el, k, v) { if(el) el.style.setProperty(k, v, 'important'); }
              function isolate(el) {
                if (!el) return;
                var n = el;
                while (n && n !== document.body) {
                  var p = n.parentElement; if (!p) break;
                  Array.prototype.forEach.call(p.children, function(s){ if (s !== n) imp(s, 'display', 'none'); });
                  imp(p, 'display', 'block'); imp(p, 'width', '100%'); imp(p, 'height', '100%');
                  imp(p, 'margin', '0'); imp(p, 'padding', '0');
                  n = p;
                }
                imp(document.documentElement, 'background', '#000'); imp(document.documentElement, 'overflow', 'hidden');
                imp(document.body, 'background', '#000'); imp(document.body, 'margin', '0'); imp(document.body, 'overflow', 'hidden');
                imp(el, 'position', 'fixed'); imp(el, 'inset', '0'); imp(el, 'width', '100vw'); imp(el, 'height', '100vh');
                imp(el, 'z-index', '2147483647'); imp(el, 'background', '#000');
                AnPinimeWebBridge.onPlayerReady();
              }
              function scan() {
                var v = document.querySelector('video');
                if (v) {
                  var s = v.currentSrc || v.src || '';
                  if (/^https?:/i.test(s)) observe(s);
                  isolate(v);
                  if (v.paused && !v.__userPaused) { var p = v.play(); if (p && p.catch) p.catch(function(){}); }
                  return;
                }
                var sel = ['#media-player','.player-container','.watching_player','#player','.jwplayer','iframe[src]'];
                for (var i=0; i<sel.length; i++) {
                  var el = document.querySelector(sel[i]);
                  if (el) { isolate(el); break; }
                }
              }
              setInterval(scan, 700);
              scan();
            })();
        """.trimIndent()

        wv.webChromeClient = object : WebChromeClient() {
            override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                result?.confirm()
                return true
            }
        }

        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                view?.evaluateJavascript(fallbackIsolateScript, null)
                mainHandler.postDelayed({
                    if (fallbackWebView === view) {
                        loadingOverlay.visibility = View.GONE
                    }
                }, 2500L)
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val u = request?.url?.toString().orEmpty()
                if (u.contains("doubleclick") || u.contains("popads") || u.contains("googlesyndication")) {
                    return true
                }
                return false
            }
        }

        fallbackWebView = wv
        videoFrame.addView(wv, 0)
        wv.loadUrl(webUrl, mapOf("Referer" to referer, "User-Agent" to CatalogService.USER_AGENT))
    }

    private fun trySwitchToBackupServer(resumePosMs: Long): Boolean {
        val seasons = ArrayList<String>()
        for (ep in allEpisodes) {
            if (!seasons.contains(ep.season)) seasons.add(ep.season)
        }
        val currentIdx = seasons.indexOf(currentEpisode.season)
        if (currentIdx >= 0 && currentIdx + 1 < seasons.size) {
            val nextSeason = seasons[currentIdx + 1]
            val backupEp = allEpisodes.firstOrNull { it.season == nextSeason && it.number == currentEpisode.number }
                ?: allEpisodes.firstOrNull { it.season == nextSeason }
            if (backupEp != null) {
                startEpisodePlayback(backupEp, resetPosition = false, initialPositionMs = resumePosMs)
                return true
            }
        }
        return false
    }

    private fun adjustSurfaceAspectRatio(videoW: Int, videoH: Int) {
        if (videoW <= 0 || videoH <= 0) return
        val parentW = videoFrame.width
        val parentH = videoFrame.height
        if (parentW <= 0 || parentH <= 0) return

        val videoRatio = videoW.toFloat() / videoH.toFloat()
        val screenRatio = parentW.toFloat() / parentH.toFloat()
        val lp = surfaceView.layoutParams as FrameLayout.LayoutParams
        if (videoRatio > screenRatio) {
            lp.width = parentW
            lp.height = (parentW / videoRatio).toInt()
        } else {
            lp.height = parentH
            lp.width = (parentH * videoRatio).toInt()
        }
        lp.gravity = Gravity.CENTER
        surfaceView.layoutParams = lp
    }

    private fun cycleQualityMode() {
        currentQualityMode = (currentQualityMode + 1) % 3
        val label = when (currentQualityMode) {
            0 -> "⚙ Chất lượng: 1080p"
            1 -> "⚙ Chất lượng: 720p (Mượt)"
            else -> "⚙ Chất lượng: 4K Gốc"
        }
        qualityBtn.text = label
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
        fallbackWebView?.evaluateJavascript(
            "(function(){var v=document.querySelector('video');if(v){if(v.paused){v.__userPaused=false;v.play();}else{v.__userPaused=true;v.pause();}}})();",
            null
        )
    }

    private fun seekRelative(deltaMs: Long) {
        val player = exoPlayer
        if (player != null) {
            val dur = player.duration.takeIf { it > 0 } ?: return
            val target = (player.currentPosition + deltaMs).coerceIn(0L, dur)
            player.seekTo(target)
            updateProgressUi()
            return
        }
        val deltaSec = deltaMs / 1000.0
        fallbackWebView?.evaluateJavascript(
            "(function(){var v=document.querySelector('video');if(v&&isFinite(v.duration)){v.currentTime=Math.max(0,Math.min(v.duration,v.currentTime+($deltaSec)));}})();",
            null
        )
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
        serverDrawer.visibility = View.GONE
        episodeDrawer.visibility = View.GONE
        osdContainer.visibility = View.GONE
    }

    private fun scheduleHideOsd() {
        mainHandler.removeCallbacks(hideOsdRunnable)
        mainHandler.postDelayed(hideOsdRunnable, 4800L)
    }

    private fun updateProgressUi() {
        val player = exoPlayer ?: return
        val pos = player.currentPosition.coerceAtLeast(0L)
        val dur = player.duration.takeIf { it > 0 } ?: 0L
        timeCurrentText.text = formatTime(pos)
        timeTotalText.text = formatTime(dur)
        if (dur > 0L) {
            seekBar.progress = ((pos * 1000L) / dur).toInt().coerceIn(0, 1000)
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
