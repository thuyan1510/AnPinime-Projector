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
import android.webkit.WebResourceResponse
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
import androidx.media3.common.Tracks
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.text.CueGroup
import androidx.media3.ui.SubtitleView
import androidx.appcompat.app.AlertDialog
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.DefaultHlsExtractorFactory
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import java.net.URLEncoder

@OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity() {

    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var resolveJob: Job? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val attemptedStreamUrls = HashSet<String>()

    private var movieTitle: String = ""
    private lateinit var currentEpisode: Episode
    private var allEpisodes: List<Episode> = emptyList()

    private var exoPlayer: ExoPlayer? = null
    private var preferSoftwareAudio = false
    private var lastNativeUrl = ""
    private var lastNativeHeaders: Map<String, String> = emptyMap()
    private var trackSelector: DefaultTrackSelector? = null
    private var filmRelay: FilmMediaRelay? = null
    private var fallbackWebView: WebView? = null
    private var lastWebUrl: String = ""
    private var retryCount = 0
    private var currentQualityMode = 0 // 0 = Auto 1080p, 1 = 720p (Siêu mượt), 2 = 4K Gốc

    private lateinit var videoFrame: FrameLayout
    private lateinit var surfaceView: SurfaceView
    private lateinit var subtitleView: SubtitleView
    private var scrubbing = false
    private var seekGestureKey = -1
    private var playbackGeneration = 0
    private var webPositionMs = 0L
    private var webDurationMs = 0L
    private var webPaused = true
    private var openingFrame = false
    private val visitedFrames = HashSet<String>()
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
    private var selectionDialog: AlertDialog? = null

    private val hideOsdRunnable = Runnable {
        if (selectionDialog == null && !scrubbing && serverDrawer.visibility != View.VISIBLE && episodeDrawer.visibility != View.VISIBLE &&
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WebViewHelper.registerActivity(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Keep small cached posters for an instant return to the catalog.

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
        startEpisodePlayback(currentEpisode, resetPosition = true)
        mainHandler.post(progressRunnable)
    }

    override fun onResume() {
        super.onResume()
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
        uiScope.cancel()
        selectionDialog?.dismiss()
        releasePlayer()
        destroyWebPlayer()
    }

    private fun releasePlayer(stopRelay: Boolean = true) {
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
        if (event.action == KeyEvent.ACTION_UP && keyCode == seekGestureKey) {
            seekGestureKey = -1
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN &&
            (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) &&
            (!osdVisible || seekGestureKey == keyCode || seekBar.hasFocus())) {
            seekGestureKey = keyCode
            val step = if (event.repeatCount >= 8) 30_000L else 10_000L
            seekRelative(if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -step else step)
            showOsd()
            seekBar.requestFocus()
            return true
        }

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
        subtitleView = SubtitleView(this).apply {
            setUserDefaultStyle()
            setUserDefaultTextSize()
            isFocusable = false
            layoutParams = FrameLayout.LayoutParams(-1, -1)
        }
        videoFrame.addView(subtitleView)
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
        loadingOverlay.addView(UiHelper.createLoadingIndicator(this, sizeDp = 92))
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
            isFocusable = true
            isFocusableInTouchMode = true
            contentDescription = "Thời lượng: trái/phải tua 10 giây, giữ để tua nhanh"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = UiHelper.dp(context, 10)
                rightMargin = UiHelper.dp(context, 10)
            }
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        val dur = exoPlayer?.duration ?: webDurationMs
                        if (dur > 0) {
                            exoPlayer?.seekTo(dur * progress / 1000L)
                            if (fallbackWebView != null) sendWebCommand("seek", dur * progress / 1_000_000.0)
                        }
                    }
                }
                override fun onStartTrackingTouch(bar: SeekBar?) {
                    scrubbing = true
                    mainHandler.removeCallbacks(hideOsdRunnable)
                }
                override fun onStopTrackingTouch(bar: SeekBar?) {
                    scrubbing = false
                    scheduleHideOsd()
                }
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

        fun seekButton(label: String, delta: Long): TextView = makeOsdBtn(label) { seekRelative(delta) }.apply {
            var lastRepeatAt = 0L
            setOnKeyListener { _, code, event ->
                if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                    if (event.action == KeyEvent.ACTION_DOWN && (event.repeatCount == 0 || event.eventTime - lastRepeatAt >= 200L)) {
                        lastRepeatAt = event.eventTime
                        performClick()
                    }
                    true
                } else false
            }
        }
        btnRow.addView(seekButton("⏪ -10s", -10_000L))
        btnRow.addView(seekButton("⏩ +10s", 10_000L))

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
                serverDrawerRow.getChildAt(0)?.requestFocus()
            }
        })

        btnRow.addView(makeOsdBtn("📋 Chọn Tập") {
            serverDrawer.visibility = View.GONE
            if (episodeDrawer.visibility == View.VISIBLE) {
                episodeDrawer.visibility = View.GONE
            } else {
                populateEpisodeDrawer()
                episodeDrawer.visibility = View.VISIBLE
                episodeDrawerRow.getChildAt(0)?.requestFocus()
            }
        })

        qualityBtn = makeOsdBtn("⚙ Tối đa 1080p") {
            showQualityPicker()
        }
        btnRow.addView(qualityBtn)
        btnRow.addView(makeOsdBtn("Âm thanh") { showTrackPicker(C.TRACK_TYPE_AUDIO) })
        btnRow.addView(makeOsdBtn("Phụ đề") { showTrackPicker(C.TRACK_TYPE_TEXT) })
        btnRow.addView(makeOsdBtn("Sửa mất tiếng") {
            showSelection("Bộ giải mã âm thanh", listOf("Thiết bị (mặc định)", "FFmpeg (thử khi mất tiếng)"),
                if (preferSoftwareAudio) 1 else 0) { index ->
                val player = exoPlayer ?: return@showSelection
                val position = player.currentPosition
                preferSoftwareAudio = index == 1
                prepareExoPlayer(lastNativeUrl, lastNativeHeaders, position)
            }
        })

        btnRow.addView(makeOsdBtn("✕ Thoát", Color.parseColor("#381E24")) {
            finish()
        })

        bottomPanel.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(btnRow)
        })
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
                val resumePos = exoPlayer?.currentPosition ?: webPositionMs
                startEpisodePlayback(matchedEp, resetPosition = matchedEp.number != currentEpisode.number, initialPositionMs = resumePos)
                playPauseBtn.requestFocus()
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
        val generation = ++playbackGeneration
        releasePlayer()
        preferSoftwareAudio = false
        currentEpisode = episode
        visitedFrames.clear()
        webPositionMs = 0L
        webDurationMs = 0L
        retryCount = 0
        attemptedStreamUrls.clear()
        topServerText.text = "${episode.title} • ${episode.season}"
        loadingOverlay.visibility = View.VISIBLE
        loadingText.text = "Đang tải ${episode.title} (${episode.season})…"
        destroyWebPlayer()
        surfaceView.visibility = View.VISIBLE

        resolveJob?.cancel()
        resolveJob = uiScope.launch {
            try {
                val resolved = CatalogService.resolvePlayback(episode)
                if (generation != playbackGeneration) return@launch
                lastWebUrl = resolved.webUrl.ifBlank { episode.url }
                attemptedStreamUrls.add(resolved.url)
                if (resolved.isWeb) {
                    startWebFallback(resolved.url, resolved.headers["Referer"] ?: episode.url)
                    return@launch
                }
                val needsRelay = resolved.isF4kWrapped ||
                    resolved.url.contains("/stream/hls", ignoreCase = true) ||
                    (resolved.url.substringBefore('?').lowercase().endsWith(".m3u8") && resolved.url.contains("film4k", ignoreCase = true))
                val playUrl = if (needsRelay) {
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
                    headers = if (needsRelay) emptyMap() else resolved.headers,
                    seekMs = if (resetPosition) 0L else initialPositionMs
                )
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                if (generation != playbackGeneration) return@launch
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
        // The newly created relay belongs to this stream, not the old player.
        releasePlayer(stopRelay = false)
        lastNativeUrl = streamUrl
        lastNativeHeaders = headers
        destroyWebPlayer()
        surfaceView.visibility = View.VISIBLE

        val selector = DefaultTrackSelector(this).apply {
            parameters = buildUponParameters()
                .setPreferredAudioLanguages("vi", "en")
                .setPreferredAudioMimeTypes(MimeTypes.AUDIO_AAC, MimeTypes.AUDIO_MPEG)
                .setMaxAudioChannelCount(2)
                .setPreferredTextLanguages("vi", "en")
                .setSelectUndeterminedTextLanguage(true)
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

        // Target 12MB of media buffering; total process memory is higher.
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

        val renderersFactory = ProjectorRenderersFactory(this)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(
                if (preferSoftwareAudio) DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
                else DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
            )

        attemptedStreamUrls.add(streamUrl)

        val mergedHeaders = HashMap(headers)
        WebViewHelper.getCookieHeader(streamUrl)?.let { mergedHeaders["Cookie"] = it }
        lastNativeHeaders = mergedHeaders

        val ua = headers["User-Agent"] ?: CatalogService.USER_AGENT
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(ua)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(mergedHeaders)

        val cleanLower = streamUrl.lowercase()
        val isHls = cleanLower.contains(".m3u8") ||
            cleanLower.contains("/hls/") ||
            cleanLower.contains("/stream/hls") ||
            cleanLower.contains("format=m3u8") ||
            cleanLower.contains("m3u8")

        val mediaItem = MediaItem.Builder()
            .setUri(Uri.parse(streamUrl))
            .apply {
                if (isHls) {
                    setMimeType(MimeTypes.APPLICATION_M3U8)
                }
            }
            .build()

        val mediaSource = if (isHls) {
            val hlsExtractorFactory = DefaultHlsExtractorFactory(
                DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
                    DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS,
                true
            )
            HlsMediaSource.Factory(httpFactory)
                .setExtractorFactory(hlsExtractorFactory)
                .setAllowChunklessPreparation(false)
                .createMediaSource(mediaItem)
        } else {
            ProgressiveMediaSource.Factory(httpFactory, DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(true))
                .createMediaSource(mediaItem)
        }

        val player = ExoPlayer.Builder(this, renderersFactory)
            .setTrackSelector(selector)
            .setLoadControl(loadControl)
            .build()

        player.setAudioAttributes(androidx.media3.common.AudioAttributes.DEFAULT, true)
        player.setHandleAudioBecomingNoisy(true)

        player.videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT
        player.setVideoSurfaceView(surfaceView)

        player.addListener(object : Player.Listener {
            override fun onCues(cueGroup: CueGroup) {
                if (exoPlayer === player) subtitleView.setCues(cueGroup.cues)
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
                if (exoPlayer !== player) return
                playPauseBtn.text = if (isPlaying) "⏸ Tạm dừng" else "▶ Phát tiếp"
                if (isPlaying) {
                    scheduleHideOsd()
                }
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
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
                            currentEpisode.source == ContentSource.PHIM4K && streamUrl.startsWith("http", ignoreCase = true) ->
                                try { "https://player.phimapi.com/player/?url=" + URLEncoder.encode(streamUrl, "UTF-8") } catch (_: Throwable) { "" }
                            else -> ""
                        }

                        if (fallbackUrl.isNotBlank()) {
                            startWebFallback(fallbackUrl, currentEpisode.url)
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
            visibility = View.INVISIBLE
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
            )
            isFocusable = false
            isFocusableInTouchMode = false
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
            userAgentString = CatalogService.USER_AGENT
        }

        wv.addJavascriptInterface(object {
            @JavascriptInterface
            fun onVideoFound(streamUrl: String, refUrl: String) {
                mainHandler.post {
                    if (fallbackWebView !== wv) return@post
                    if (streamUrl.startsWith("http", ignoreCase = true) && exoPlayer == null) {
                        if (attemptedStreamUrls.contains(streamUrl)) {
                            return@post
                        }
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
                    if (fallbackWebView !== wv) return@post
                    wv.visibility = View.VISIBLE
                    loadingOverlay.visibility = View.GONE
                    scheduleHideOsd()
                }
            }

            @JavascriptInterface
            fun onFrameFound(url: String, referer: String) {
                mainHandler.post {
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
                    webPositionMs = (position * 1000).toLong().coerceAtLeast(0)
                    webDurationMs = (duration * 1000).toLong().coerceAtLeast(0)
                    webPaused = paused
                    playPauseBtn.text = if (paused) "▶ Phát tiếp" else "⏸ Tạm dừng"
                    updateProgressUi()
                }
            }
        }, "AnPinimeWebBridge")

        val fallbackIsolateScript = """
            (function(){
              if(window.__anpinimeInstalled) return;
              window.__anpinimeInstalled = true;
              var hidden = [
                '.Adv', '.ad-center', '.ads', '.advertisement', '[class*="ads"]', '[class*="banner"]', '[id*="ads"]',
                'iframe[src*="ads"]', '.popup', '.modal-backdrop', '.header-ads-pc', '.Adv.ad-center-header',
                'header', 'footer', '.footer', '.header', '.sidebar', '.breadcrumb', '.comments', '.film-info',
                '.heading-film', '.list-episode-box', '.server-list', '.film-thumb', '.film-desc', '.film-buttons',
                '.category-heading', '.block-film', 'nav', '.navbar', '.menu'
              ];
              var reportedFrames = {};
              var activeVideo = null;
              var detectedStreams = [], reportedSources = {};
              function sendVideo(url, ref) {
                try { AnPinimeWebBridge.onVideoFound(url, ref); } catch(e){}
              }
              function sendReady() {
                try { AnPinimeWebBridge.onPlayerReady(); } catch(e){}
              }
              function observeStream(url){
                try {
                  url = new URL(String(url), location.href).href;
                  if (/\.(?:m3u8|mp4)(?:[?#]|$)/i.test(url) && !/(?:doubleclick|popads|\/ads?\/)/i.test(url) && detectedStreams.indexOf(url) < 0) {
                    detectedStreams.push(url);
                    sendVideo(url, location.href);
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
              function reportSource(v){
                var s = mediaURL(v);
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
                return /just a moment|xác minh an toàn|xác minh bảo mật|verify you are human|checking your browser/i.test(document.title) || !!document.querySelector('#challenge-form');
              }
              window.addEventListener('message', function(event){
                var c = event.data; if (!c || c.type !== 'AnPinimeCommand') return;
                var v = activeVideo || document.querySelector('video');
                if (v) {
                  try {
                    switch(c.name){
                      case 'play': v.__aaUserPaused = false; tryPlay(v); break;
                      case 'pause': v.__aaUserPaused = true; v.pause(); break;
                      case 'seekBy': if (isFinite(v.duration)) v.currentTime = Math.max(0, Math.min(v.currentTime + Number(c.value), v.duration)); break;
                      case 'seek': if (isFinite(v.duration)) v.currentTime = Math.max(0, Math.min(Number(c.value)||0, v.duration)); break;
                      case 'rate': v.playbackRate = Math.max(0.5, Math.min(Number(c.value)||1, 2)); break;
                      case 'volume': v.volume = Math.max(0, Math.min(Number(c.value)||0, 1)); break;
                      case 'fit': important(v, 'object-fit', c.value === 1 ? 'cover' : 'contain'); break;
                    }
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
                  v.addEventListener('playing', function(){ reportSource(v); sendReady(); });
                  ['timeupdate','durationchange','pause','playing'].forEach(function(name){
                    v.addEventListener(name, function(){
                      try { AnPinimeWebBridge.onProgress(v.currentTime || 0, isFinite(v.duration) ? v.duration : 0, v.paused); } catch(e){}
                    });
                  });
                  tryPlay(v);
                }
                if (v.readyState >= 2) sendReady();
                return true;
              }
              function usefulFrame(frame){
                var src = frame.getAttribute('src') || '';
                if (!src || /^(?:about:blank|javascript:)/i.test(src) || /(?:doubleclick|googlesyndication|facebook|popads|adservice|\/ads?[\/.?])/i.test(src)) return false;
                var rect = frame.getBoundingClientRect();
                return rect.width >= 240 && rect.height >= 120;
              }
              function focus(){
                if (!window.document || !document.documentElement) return false;
                if (challenge()) return false;
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

        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(wv, fallbackIsolateScript, setOf("*"))
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
                if (request?.method == "GET" && Regex("(?i)\\.(m3u8|mp4)([?#]|$)").containsMatchIn(url) &&
                    !url.contains("/ads/", true)) {
                    val requestHeaders = request?.requestHeaders.orEmpty()
                    mainHandler.post {
                        if (fallbackWebView === wv && url !in attemptedStreamUrls && exoPlayer == null) {
                            val headers = requestHeaders.filterKeys { it.equals("Referer", true) || it.equals("Origin", true) || it.equals("User-Agent", true) }
                            prepareExoPlayer(url, headers, webPositionMs)
                        }
                    }
                }
                return null
            }
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                view?.evaluateJavascript(fallbackIsolateScript, null)
                mainHandler.postDelayed({
                    if (fallbackWebView === view && view?.visibility != View.VISIBLE) {
                        loadingText.text = "Chưa mở được video. Hãy thử server khác hoặc kiểm tra Android System WebView."
                        showOsd(true)
                    }
                }, 20_000L)
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
        videoFrame.addView(wv, 0)
        wv.loadUrl(webUrl, mapOf("Referer" to referer, "User-Agent" to CatalogService.USER_AGENT))
    }

    private fun trySwitchToBackupServer(resumePosMs: Long): Boolean {
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

    private fun showSelection(title: String, labels: List<String>, selected: Int, choose: (Int) -> Unit) {
        selectionDialog?.dismiss()
        mainHandler.removeCallbacks(hideOsdRunnable)
        selectionDialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setSingleChoiceItems(labels.toTypedArray(), selected) { dialog, index ->
                choose(index)
                dialog.dismiss()
            }
            .setNegativeButton("Đóng", null)
            .setOnDismissListener { selectionDialog = null; scheduleHideOsd() }
            .create()
        selectionDialog?.show()
        selectionDialog?.listView?.apply {
            setSelection(selected.coerceAtLeast(0))
            requestFocus()
        }
    }

    private fun showPlayerMessage(title: String, message: String) {
        selectionDialog?.dismiss()
        mainHandler.removeCallbacks(hideOsdRunnable)
        selectionDialog = AlertDialog.Builder(this).setTitle(title).setMessage(message)
            .setPositiveButton("Đóng", null)
            .setOnDismissListener { selectionDialog = null; scheduleHideOsd() }.create()
        selectionDialog?.show()
    }

    private fun showQualityPicker() {
        if (exoPlayer == null) {
            showPlayerMessage("Chất lượng", "Chưa có luồng native. Trình phát web hiện chưa hỗ trợ chọn chất lượng bằng app.")
            return
        }
        showSelection("Giới hạn chất lượng (không chuyển mã)",
            listOf("Tối đa 1080p", "Tối đa 720p (nhẹ)", "Tự động — không giới hạn"), currentQualityMode) { index ->
            currentQualityMode = index
            applyQualityMode()
        }
    }

    private fun applyQualityMode() {
        val label = when (currentQualityMode) {
            0 -> "⚙ Tối đa 1080p"
            1 -> "⚙ Tối đa 720p"
            else -> "⚙ Chất lượng: Tự động"
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

    private fun startCompatibilityPlayback(position: Long) {
        if (lastNativeUrl.isBlank()) return
        if (preferSoftwareAudio) {
            loadingText.text = "Không giải mã được luồng phát. Hãy chọn server khác."
            loadingOverlay.visibility = View.VISIBLE
            showOsd(true)
            return
        }
        preferSoftwareAudio = true
        prepareExoPlayer(lastNativeUrl, lastNativeHeaders, position)
    }

    private fun showTrackPicker(type: Int) {
        val title = if (type == C.TRACK_TYPE_TEXT) "Phụ đề" else "Âm thanh"
        val player = exoPlayer
        if (player == null) {
            showPlayerMessage(title, "Chưa có danh sách track. Hãy chờ video phát bằng trình phát native.")
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
                labels.add(listOfNotNull(format.label, format.language, format.sampleMimeType,
                    format.channelCount.takeIf { it > 0 }?.let { "$it kênh" }).joinToString(" • ")
                    .ifBlank { "Track ${options.size}" })
            }
        }
        if (options.isEmpty()) {
            showPlayerMessage(title, if (type == C.TRACK_TYPE_TEXT)
                "Nguồn chưa cung cấp phụ đề rời được hỗ trợ. Phụ đề ghi sẵn trong hình không thể đổi hoặc tắt."
                else "Chưa tìm thấy track âm thanh được hỗ trợ. Hãy chờ tải xong hoặc thử bộ giải mã FFmpeg.")
            return
        }
        showSelection(title, labels, selected) { index ->
                val builder = player.trackSelectionParameters.buildUpon().clearOverridesOfType(type)
                    .setTrackTypeDisabled(type, type == C.TRACK_TYPE_TEXT && index == 0)
                if (index > 0) {
                    val (group, track) = options[index - 1]
                    builder.setOverrideForType(TrackSelectionOverride(group, track))
                }
                player.trackSelectionParameters = builder.build()
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
        sendWebCommand(if (webPaused) "play" else "pause")
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
        sendWebCommand("seekBy", deltaMs / 1000.0)
    }

    private fun sendWebCommand(name: String, value: Double = 0.0) {
        val command = JSONObject().put("type", "AnPinimeCommand").put("name", name).put("value", value)
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
        serverDrawer.visibility = View.GONE
        episodeDrawer.visibility = View.GONE
        osdContainer.visibility = View.GONE
    }

    private fun scheduleHideOsd() {
        mainHandler.removeCallbacks(hideOsdRunnable)
        if (selectionDialog == null && !scrubbing && serverDrawer.visibility != View.VISIBLE && episodeDrawer.visibility != View.VISIBLE) {
            mainHandler.postDelayed(hideOsdRunnable, 6000L)
        }
    }

    private fun updateProgressUi() {
        val player = exoPlayer
        val pos = player?.currentPosition?.coerceAtLeast(0L) ?: webPositionMs
        val dur = player?.duration?.takeIf { it > 0 } ?: webDurationMs
        timeCurrentText.text = formatTime(pos)
        timeTotalText.text = formatTime(dur)
        if (dur > 0L && !scrubbing) {
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
