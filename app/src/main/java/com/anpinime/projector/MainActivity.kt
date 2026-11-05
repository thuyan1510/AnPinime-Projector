package com.anpinime.projector

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.lang.ref.WeakReference

class MainActivity : AppCompatActivity() {

    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var loadJob: Job? = null

    private var currentSource: ContentSource = ContentSource.FILM4K
    private var currentTab: String = "home" // "home", "search", "browse", "schedule"
    private var homeSections: List<AnimeSection> = emptyList()
    private var featuredIndex: Int = 0
    private var activeCategory: AnimeCategory? = null
    private var browseItems: MutableList<Anime> = ArrayList()
    private var browsePage: Int = 1

    private lateinit var logoImage: ImageView
    private lateinit var sourceSubtitleText: TextView
    private lateinit var contentContainer: FrameLayout
    private lateinit var sidebarOverlay: FrameLayout
    private lateinit var sidebarPanel: LinearLayout
    private lateinit var topMenuBadgeText: TextView
    private val sidebarButtons = HashMap<String, TextView>()
    private var isSidebarOpen = false
    private var activeSidebarKey: String = "tab_home"
    private var lastContentFocusRef: WeakReference<View>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CatalogService.init(this)
        WebViewHelper.registerActivity(this)

        val prefs = getSharedPreferences("anpinime_projector", Context.MODE_PRIVATE)
        currentSource = ContentSource.fromId(prefs.getString("source", ContentSource.FILM4K.id))

        val root = FrameLayout(this).apply {
            setBackgroundColor(UiHelper.COLOR_BG)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // Main full-screen column (Top quick status bar + Full-width Content Container)
        val fullScreenCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val topHeaderBar = buildTopHeaderBar()
        contentContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        fullScreenCol.addView(topHeaderBar)
        fullScreenCol.addView(contentContainer)
        root.addView(fullScreenCol)

        // Left Edge Thin Hint Strip (4dp) so user visually knows left edge opens Sidebar
        val leftEdgeHint = View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(
                    Color.TRANSPARENT,
                    Color.parseColor("#66A855F7"),
                    Color.TRANSPARENT
                )
            )
            layoutParams = FrameLayout.LayoutParams(
                UiHelper.dp(this@MainActivity, 3),
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.START
            )
            setOnClickListener { openSidebar() }
        }
        root.addView(leftEdgeHint)

        // Auto-hiding Collapsible Sidebar Overlay (Hidden by default so movies use 100% width)
        sidebarOverlay = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#B3000000"))
            visibility = View.GONE
            isClickable = true
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setOnClickListener {
                closeSidebar(restoreFocus = true)
            }
        }
        sidebarPanel = buildSidebarPanel()
        sidebarOverlay.addView(sidebarPanel)
        root.addView(sidebarOverlay)

        setContentView(root)

        updateSidebarState()
        loadHomeData()

        uiScope.launch {
            AppUpdateManager.checkUpdate(this@MainActivity) { info ->
                if (!isFinishing && !isDestroyed) {
                    AppUpdateManager.showUpdateDialog(this@MainActivity, info)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        WebViewHelper.registerActivity(this)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_RUNNING_LOW) {
            ImageLoader.trimMemory()
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        ImageLoader.trimMemory()
    }

    override fun onDestroy() {
        super.onDestroy()
        uiScope.cancel()
    }

    // =========================================================================
    // REMOTE D-PAD LEFT-EDGE SIDEBAR TRIGGER
    // =========================================================================

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val keyCode = event.keyCode

            if (keyCode == KeyEvent.KEYCODE_MENU) {
                if (isSidebarOpen) closeSidebar(restoreFocus = true) else openSidebar()
                return true
            }

            if (isSidebarOpen) {
                if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ||
                    keyCode == KeyEvent.KEYCODE_BACK ||
                    keyCode == KeyEvent.KEYCODE_ESCAPE
                ) {
                    closeSidebar(restoreFocus = true)
                    return true
                }
            } else {
                if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) {
                    if (currentTab != "home") {
                        currentTab = "home"
                        activeSidebarKey = "tab_home"
                        updateSidebarState()
                        if (homeSections.isEmpty()) loadHomeData() else renderHome()
                        return true
                    } else {
                        // Khi ở màn hình home, ấn nút trở về sẽ mở menu
                        openSidebar()
                        return true
                    }
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                    val focused = currentFocus
                    // If focused on an EditText with cursor not at start, let cursor move left naturally
                    if (focused is EditText && focused.selectionStart > 0) {
                        return super.dispatchKeyEvent(event)
                    }
                    val nextLeft = focused?.focusSearch(View.FOCUS_LEFT)
                    if (focused == null || nextLeft == null || isDescendantOf(nextLeft, sidebarOverlay)) {
                        openSidebar()
                        return true
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun isDescendantOf(view: View?, container: ViewGroup): Boolean {
        var cur: View? = view
        while (cur != null) {
            if (cur === container) return true
            cur = cur.parent as? View
        }
        return false
    }

    private fun openSidebar() {
        if (isSidebarOpen) return
        val focused = currentFocus
        if (focused != null && !isDescendantOf(focused, sidebarOverlay)) {
            lastContentFocusRef = WeakReference(focused)
        }
        isSidebarOpen = true
        updateSidebarState()
        sidebarOverlay.visibility = View.VISIBLE
        val target = sidebarButtons[activeSidebarKey]
            ?: when (currentTab) {
                "search" -> sidebarButtons["tab_search"]
                "browse" -> sidebarButtons["tab_browse"]
                "schedule" -> sidebarButtons["tab_schedule"]
                else -> when (currentSource) {
                    ContentSource.FILM4K -> sidebarButtons["src_film4k"]
                    ContentSource.PHIM4K -> sidebarButtons["src_phim4k"]
                    ContentSource.KKPHIM -> sidebarButtons["src_kkphim"]
                    ContentSource.ANIME -> sidebarButtons["src_anime"]
                }
            }
            ?: sidebarButtons["tab_home"]
        target?.post {
            target.requestFocus()
        } ?: target?.requestFocus()
    }

    private fun closeSidebar(restoreFocus: Boolean) {
        if (!isSidebarOpen) return
        isSidebarOpen = false
        sidebarOverlay.visibility = View.GONE
        if (restoreFocus) {
            val prev = lastContentFocusRef?.get()
            if (prev != null && prev.isShown && prev.isFocusable) {
                prev.requestFocus()
            } else {
                contentContainer.requestFocus()
            }
        }
    }

    private fun buildTopHeaderBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(UiHelper.dp(context, 18), UiHelper.dp(context, 10), UiHelper.dp(context, 18), UiHelper.dp(context, 4))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val menuTriggerBtn = TextView(this).apply {
            text = "☰  Menu & Nguồn"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(UiHelper.dp(context, 14), UiHelper.dp(context, 6), UiHelper.dp(context, 14), UiHelper.dp(context, 6))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { rightMargin = UiHelper.dp(context, 12) }
        }
        UiHelper.makeFocusableButton(
            view = menuTriggerBtn,
            normalBgColor = UiHelper.COLOR_CARD,
            focusedBgColor = UiHelper.COLOR_ACCENT,
            focusedStrokeColor = Color.WHITE,
            radiusDp = 999
        ) {
            openSidebar()
        }
        bar.addView(menuTriggerBtn)

        topMenuBadgeText = TextView(this).apply {
            text = "${currentSource.title}  •  Ấn ◀ sang trái hết cỡ để mở thanh bên"
            setTextColor(UiHelper.COLOR_TEXT_SECONDARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        bar.addView(topMenuBadgeText)

        val quickSearchBtn = TextView(this).apply {
            text = "🔍 Tìm nhanh"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(UiHelper.dp(context, 12), UiHelper.dp(context, 6), UiHelper.dp(context, 12), UiHelper.dp(context, 6))
        }
        UiHelper.makeFocusableButton(
            view = quickSearchBtn,
            normalBgColor = UiHelper.COLOR_CARD,
            focusedBgColor = UiHelper.COLOR_ACCENT,
            focusedStrokeColor = Color.WHITE,
            radiusDp = 999
        ) {
            currentTab = "search"
            updateSidebarState()
            renderSearch()
        }
        bar.addView(quickSearchBtn)

        return bar
    }

    private fun buildSidebarPanel(): LinearLayout {
        val sidebar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(UiHelper.COLOR_SIDEBAR)
            isClickable = true
            setPadding(
                UiHelper.dp(context, 16),
                UiHelper.dp(context, 18),
                UiHelper.dp(context, 16),
                UiHelper.dp(context, 14)
            )
            layoutParams = FrameLayout.LayoutParams(
                UiHelper.dp(context, 240),
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.START
            )
        }

        val brandRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(UiHelper.dp(context, 4), 0, UiHelper.dp(context, 4), UiHelper.dp(context, 14))
        }

        logoImage = ImageView(this).apply {
            UiHelper.setSourceLogo(this, currentSource.isMovie)
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(UiHelper.dp(context, 44), UiHelper.dp(context, 44)).apply {
                rightMargin = UiHelper.dp(context, 10)
            }
        }

        val brandTextCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val appTitle = TextView(this).apply {
            text = "AnPinime TV"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            typeface = Typeface.DEFAULT_BOLD
        }

        sourceSubtitleText = TextView(this).apply {
            text = currentSource.title
            setTextColor(UiHelper.COLOR_ACCENT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
        }

        brandTextCol.addView(appTitle)
        brandTextCol.addView(sourceSubtitleText)
        brandRow.addView(logoImage)
        brandRow.addView(brandTextCol)
        sidebar.addView(brandRow)

        fun addMenuButton(key: String, label: String, onClick: () -> Unit) {
            val btn = TextView(this).apply {
                text = label
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
                typeface = Typeface.DEFAULT_BOLD
                setSingleLine(true)
                ellipsize = TextUtils.TruncateAt.END
                setPadding(
                    UiHelper.dp(context, 12),
                    UiHelper.dp(context, 10),
                    UiHelper.dp(context, 12),
                    UiHelper.dp(context, 10)
                )
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = UiHelper.dp(context, 7)
                }
            }
            sidebarButtons[key] = btn
            UiHelper.makeFocusableButton(
                view = btn,
                normalBgColor = UiHelper.COLOR_CARD,
                focusedBgColor = Color.parseColor("#352256"),
                focusedStrokeColor = Color.WHITE,
                radiusDp = 10,
                scaleOnFocus = 1.03f,
                onFocusChange = { hasFocus ->
                    if (hasFocus) activeSidebarKey = key
                }
            ) {
                activeSidebarKey = key
                closeSidebar(restoreFocus = false)
                onClick()
            }
            sidebar.addView(btn)
        }

        addMenuButton("tab_home", "🏠  Trang chủ") {
            currentTab = "home"
            updateSidebarState()
            if (homeSections.isEmpty()) loadHomeData() else renderHome()
        }

        addMenuButton("tab_search", "🔍  Tìm kiếm") {
            currentTab = "search"
            updateSidebarState()
            renderSearch()
        }

        addMenuButton("tab_browse", "📂  Thể loại") {
            currentTab = "browse"
            updateSidebarState()
            openCategoryBrowse(null)
        }

        addMenuButton("tab_schedule", "📅  Lịch chiếu Anime") {
            currentTab = "schedule"
            updateSidebarState()
            renderSchedule(DayOfWeek.today())
        }

        val dividerLabel = TextView(this).apply {
            text = "CHỌN NGUỒN PHIM"
            setTextColor(UiHelper.COLOR_TEXT_SECONDARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(UiHelper.dp(context, 6), UiHelper.dp(context, 10), 0, UiHelper.dp(context, 6))
        }
        sidebar.addView(dividerLabel)

        addMenuButton("src_film4k", "🎬  Nguồn 1 · NguonC") {
            switchSource(ContentSource.FILM4K)
        }

        addMenuButton("src_phim4k", "⚡  Nguồn 2 · Phim4K") {
            switchSource(ContentSource.PHIM4K)
        }

        addMenuButton("src_kkphim", "🍿  Nguồn 3 · KKPhim") {
            switchSource(ContentSource.KKPHIM)
        }

        addMenuButton("src_anime", "✨  AnimeVietsub") {
            switchSource(ContentSource.ANIME)
        }

        addMenuButton("settings_domain", "🌐  Tên miền Anime") {
            showAnimeDomainDialog()
        }

        addMenuButton("refresh", "🔄  Làm mới") {
            loadHomeData()
        }

        val hintTv = TextView(this).apply {
            text = "Mẹo: Ấn ▶ sang phải hoặc nút Quay lại để đóng thanh bên."
            setTextColor(UiHelper.COLOR_TEXT_SECONDARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(UiHelper.dp(context, 4), UiHelper.dp(context, 8), UiHelper.dp(context, 4), 0)
        }
        sidebar.addView(hintTv)

        return sidebar
    }

    private fun switchSource(newSource: ContentSource) {
        val srcKey = when (newSource) {
            ContentSource.FILM4K -> "src_film4k"
            ContentSource.PHIM4K -> "src_phim4k"
            ContentSource.KKPHIM -> "src_kkphim"
            ContentSource.ANIME -> "src_anime"
        }
        activeSidebarKey = srcKey
        if (currentSource == newSource && homeSections.isNotEmpty() && currentTab == "home") return
        currentSource = newSource
        loadJob?.cancel()
        browseItems.clear()
        browsePage = 1
        activeCategory = null
        currentTab = "home"
        featuredIndex = 0
        homeSections = emptyList()
        getSharedPreferences("anpinime_projector", Context.MODE_PRIVATE)
            .edit()
            .putString("source", newSource.id)
            .apply()
        updateSidebarState()
        loadHomeData()
    }

    private fun updateSidebarState() {
        UiHelper.setSourceLogo(logoImage, currentSource.isMovie)
        sourceSubtitleText.text = currentSource.subtitle
        val tabName = when (currentTab) {
            "search" -> "Tìm kiếm"
            "browse" -> "Thể loại"
            "schedule" -> "Lịch chiếu Anime"
            else -> "Trang chủ"
        }
        topMenuBadgeText.text = "${currentSource.subtitle} • $tabName  (Ấn ◀ sang trái hết cỡ để mở Menu)"

        fun styleBtn(key: String, active: Boolean) {
            val btn = sidebarButtons[key] ?: return
            val normalColor = if (active) Color.parseColor("#3B1E6D") else UiHelper.COLOR_CARD
            btn.setTextColor(if (active) Color.WHITE else Color.parseColor("#DCE0EE"))
            if (!btn.hasFocus()) {
                btn.background = UiHelper.roundedBg(
                    normalColor,
                    10,
                    this,
                    if (active) UiHelper.COLOR_ACCENT else Color.parseColor("#26FFFFFF"),
                    1
                )
            }
        }

        styleBtn("tab_home", currentTab == "home")
        styleBtn("tab_search", currentTab == "search")
        styleBtn("tab_browse", currentTab == "browse")
        styleBtn("tab_schedule", currentTab == "schedule")
        styleBtn("src_film4k", currentSource == ContentSource.FILM4K)
        styleBtn("src_phim4k", currentSource == ContentSource.PHIM4K)
        styleBtn("src_kkphim", currentSource == ContentSource.KKPHIM)
        styleBtn("src_anime", currentSource == ContentSource.ANIME)
    }

    private fun showLoading(message: String) {
        contentContainer.removeAllViews()
        contentContainer.addView(UiHelper.createLoadingBox(this, currentSource.isMovie, message))
    }

    private fun showError(message: String, onRetry: () -> Unit) {
        contentContainer.removeAllViews()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(UiHelper.dp(context, 32), UiHelper.dp(context, 32), UiHelper.dp(context, 32), UiHelper.dp(context, 32))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        val title = TextView(this).apply {
            text = "Chưa tải được dữ liệu"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = Typeface.DEFAULT_BOLD
        }
        val sub = TextView(this).apply {
            text = message
            setTextColor(UiHelper.COLOR_TEXT_SECONDARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            gravity = Gravity.CENTER
            setPadding(0, UiHelper.dp(context, 8), 0, UiHelper.dp(context, 18))
        }
        val retryBtn = TextView(this).apply {
            text = "Thử lại ngay"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(UiHelper.dp(context, 24), UiHelper.dp(context, 12), UiHelper.dp(context, 24), UiHelper.dp(context, 12))
        }
        UiHelper.makeFocusableButton(
            view = retryBtn,
            normalBgColor = UiHelper.COLOR_RED,
            focusedBgColor = Color.parseColor("#FF5252"),
            focusedStrokeColor = Color.WHITE,
            radiusDp = 24
        ) {
            onRetry()
        }
        box.addView(title)
        box.addView(sub)
        box.addView(retryBtn)
        contentContainer.addView(box)
        retryBtn.requestFocus()
    }

    private fun loadHomeData() {
        loadJob?.cancel()
        val targetSource = currentSource
        showLoading("Đang tải ${targetSource.subtitle}…")
        loadJob = uiScope.launch {
            try {
                val sections = withContext(Dispatchers.IO) {
                    CatalogService.loadHome(targetSource)
                }
                ensureActive()
                if (targetSource != currentSource || currentTab != "home") return@launch
                homeSections = SourceIsolation.sections(targetSource, sections)
                featuredIndex = 0
                renderHome()
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                if (targetSource != currentSource || currentTab != "home") return@launch
                showError(t.message ?: "Không kết nối được máy chủ.") {
                    loadHomeData()
                }
            }
        }
    }

    // =========================================================================
    // HOME SCREEN (Phim4K Hero Banner + Full-Width Horizontal Section Rows)
    // =========================================================================

    private fun renderHome() {
        contentContainer.removeAllViews()
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        val mainCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(UiHelper.dp(context, 18), UiHelper.dp(context, 8), UiHelper.dp(context, 18), UiHelper.dp(context, 28))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val featuredPool = homeSections.firstOrNull()?.items?.take(8).orEmpty()
        if (featuredPool.isNotEmpty()) {
            mainCol.addView(buildFeaturedHero(featuredPool))
        }

        for (section in homeSections) {
            mainCol.addView(buildSectionRow(section))
        }

        scroll.addView(mainCol)
        contentContainer.addView(scroll)
    }

    private fun buildFeaturedHero(featuredItems: List<Anime>): View {
        val current = featuredItems[featuredIndex.coerceIn(0, featuredItems.size - 1)]

        val heroFrame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                UiHelper.dp(context, 190)
            ).apply {
                bottomMargin = UiHelper.dp(context, 16)
            }
            background = UiHelper.roundedBg(UiHelper.COLOR_CARD, 16, context, Color.parseColor("#2AFFFFFF"), 1)
            clipChildren = true
        }

        val backdropImage = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            alpha = 0.42f
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        ImageLoader.load(backdropImage, current.bannerUrl ?: current.imageUrl, maxWidth = 640, maxHeight = 360)
        heroFrame.addView(backdropImage)

        val gradientOverlay = View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(
                    Color.parseColor("#4008090E"),
                    Color.parseColor("#B808090E"),
                    Color.parseColor("#F508090E")
                )
            ).apply {
                cornerRadius = UiHelper.dp(context, 16).toFloat()
            }
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        heroFrame.addView(gradientOverlay)

        // Centered Phim4K-style Hero Stack + Left Poster for 16:9 Projector
        val contentRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(UiHelper.dp(context, 22), UiHelper.dp(context, 14), UiHelper.dp(context, 22), UiHelper.dp(context, 14))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val posterCard = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = UiHelper.roundedBg(UiHelper.COLOR_SIDEBAR, 10, context, Color.parseColor("#40FFFFFF"), 1)
            layoutParams = LinearLayout.LayoutParams(
                UiHelper.dp(context, 104),
                UiHelper.dp(context, 152)
            ).apply {
                rightMargin = UiHelper.dp(context, 18)
            }
        }
        ImageLoader.load(posterCard, current.imageUrl, maxWidth = 180, maxHeight = 260)
        contentRow.addView(posterCard)

        val centerStack = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val titleView = TextView(this).apply {
            text = current.title
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        centerStack.addView(titleView)

        val subText = current.subtitle.ifBlank { current.info }
        if (subText.isNotBlank()) {
            val subView = TextView(this).apply {
                text = subText
                setTextColor(Color.parseColor("#C8CEE0"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
                gravity = Gravity.CENTER
                setSingleLine(true)
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, UiHelper.dp(context, 4), 0, 0)
            }
            centerStack.addView(subView)
        }

        // Phim4K 3-Pill Row: [Year] [IMDb 9.6] [Full HD (PĐ)]
        val badgeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, UiHelper.dp(context, 10), 0, UiHelper.dp(context, 12))
        }

        val yearMatch = Regex("""\b(19|20)\d{2}\b""").find(current.info)?.value
            ?: if (current.source.isMovie) "2026" else "Mới"
        val yearBadge = UiHelper.createBadge(
            this,
            yearMatch,
            Color.parseColor("#28FFFFFF"),
            Color.WHITE
        ).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { rightMargin = UiHelper.dp(context, 8) }
        }
        badgeRow.addView(yearBadge)

        if (current.score.isNotBlank()) {
            val imdbBadge = UiHelper.createBadge(
                this,
                "IMDb ${current.score}",
                UiHelper.COLOR_YELLOW,
                Color.BLACK
            ).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { rightMargin = UiHelper.dp(context, 8) }
            }
            badgeRow.addView(imdbBadge)
        }

        val qualLabel = buildQualityPillText(current)
        val qualBadge = UiHelper.createBadge(
            this,
            qualLabel,
            UiHelper.COLOR_BLUE,
            Color.WHITE
        )
        badgeRow.addView(qualBadge)
        centerStack.addView(badgeRow)

        // Action Buttons: White Capsule [Xem chi tiết] + [Đề cử tiếp ❯]
        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        val detailBtn = TextView(this).apply {
            text = "Xem chi tiết"
            setTextColor(Color.BLACK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(UiHelper.dp(context, 26), UiHelper.dp(context, 10), UiHelper.dp(context, 26), UiHelper.dp(context, 10))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { rightMargin = UiHelper.dp(context, 10) }
        }
        UiHelper.makeFocusableButton(
            view = detailBtn,
            normalBgColor = Color.WHITE,
            focusedBgColor = UiHelper.COLOR_YELLOW,
            focusedStrokeColor = Color.WHITE,
            radiusDp = 999,
            scaleOnFocus = 1.06f
        ) {
            openDetail(current)
        }
        actionRow.addView(detailBtn)

        if (featuredItems.size > 1) {
            val nextHeroBtn = TextView(this).apply {
                text = "Đề cử tiếp (${featuredIndex + 1}/${featuredItems.size}) ❯"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(UiHelper.dp(context, 18), UiHelper.dp(context, 10), UiHelper.dp(context, 18), UiHelper.dp(context, 10))
            }
            UiHelper.makeFocusableButton(
                view = nextHeroBtn,
                normalBgColor = Color.parseColor("#2AFFFFFF"),
                focusedBgColor = Color.parseColor("#44FFFFFF"),
                focusedStrokeColor = UiHelper.COLOR_ACCENT,
                radiusDp = 999,
                scaleOnFocus = 1.05f
            ) {
                featuredIndex = (featuredIndex + 1) % featuredItems.size
                renderHome()
            }
            actionRow.addView(nextHeroBtn)
        }

        centerStack.addView(actionRow)
        contentRow.addView(centerStack)
        heroFrame.addView(contentRow)
        return heroFrame
    }

    private fun buildQualityPillText(anime: Anime): String {
        val q = anime.quality.trim()
        val ep = anime.episodeText.trim()
        val normQ = when (q.uppercase()) {
            "FHD", "1080P", "FULLHD" -> "Full HD"
            else -> q
        }
        return when {
            normQ.isNotEmpty() && ep.isNotEmpty() && ep != "Phim lẻ" -> "$normQ • $ep"
            normQ.isNotEmpty() -> "$normQ (PĐ)"
            ep.isNotEmpty() -> ep
            else -> "Full HD (PĐ)"
        }
    }

    private fun buildSectionRow(section: AnimeSection): View {
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = UiHelper.dp(context, 18)
            }
        }

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(UiHelper.dp(context, 4), 0, UiHelper.dp(context, 4), UiHelper.dp(context, 8))
        }

        val titleView = TextView(this).apply {
            text = section.title
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17.5f)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        headerRow.addView(titleView)

        if (!section.categoryPath.isNullOrBlank()) {
            val moreBtn = TextView(this).apply {
                text = "Xem thêm ❯"
                setTextColor(UiHelper.COLOR_ACCENT)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(UiHelper.dp(context, 12), UiHelper.dp(context, 6), UiHelper.dp(context, 12), UiHelper.dp(context, 6))
            }
            UiHelper.makeFocusableButton(
                view = moreBtn,
                normalBgColor = UiHelper.COLOR_CARD,
                focusedBgColor = Color.parseColor("#352256"),
                focusedStrokeColor = UiHelper.COLOR_ACCENT,
                radiusDp = 14
            ) {
                currentTab = "browse"
                updateSidebarState()
                openCategoryBrowse(AnimeCategory(section.title, section.categoryPath))
            }
            headerRow.addView(moreBtn)
        }

        val recycler = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            clipToPadding = false
            setHasFixedSize(true)
            setItemViewCacheSize(5)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                UiHelper.dp(context, 216)
            )
            adapter = PosterAdapter(section.items) { anime ->
                openDetail(anime)
            }
        }

        wrap.addView(headerRow)
        wrap.addView(recycler)
        return wrap
    }

    // =========================================================================
    // SEARCH SCREEN (With Persistent Search History + Remote D-Pad Quick Input)
    // =========================================================================

    private fun loadSearchHistory(): List<String> {
        val prefs = getSharedPreferences("anpinime_projector", Context.MODE_PRIVATE)
        val raw = prefs.getString(SourceIsolation.historyKey(currentSource), null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val list = ArrayList<String>(arr.length())
            for (i in 0 until arr.length()) {
                val item = arr.optString(i).trim()
                if (item.isNotEmpty()) list.add(item)
            }
            list
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun saveSearchQuery(query: String) {
        val clean = query.trim()
        if (clean.isEmpty()) return
        val current = loadSearchHistory().toMutableList()
        current.removeAll { it.equals(clean, ignoreCase = true) }
        current.add(0, clean)
        val trimmed = current.take(15)
        val arr = JSONArray()
        trimmed.forEach { arr.put(it) }
        getSharedPreferences("anpinime_projector", Context.MODE_PRIVATE)
            .edit()
            .putString(SourceIsolation.historyKey(currentSource), arr.toString())
            .apply()
    }

    private fun clearSearchHistory() {
        getSharedPreferences("anpinime_projector", Context.MODE_PRIVATE)
            .edit()
            .remove(SourceIsolation.historyKey(currentSource))
            .apply()
    }

    private fun renderSearch() {
        loadJob?.cancel()
        val screenSource = currentSource
        contentContainer.removeAllViews()
        val mainCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(UiHelper.dp(context, 18), UiHelper.dp(context, 10), UiHelper.dp(context, 18), UiHelper.dp(context, 16))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val searchBarRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = UiHelper.dp(context, 8) }
        }

        val input = EditText(this).apply {
            hint = "Nhập tên phim cần tìm trong ${currentSource.title}…"
            setHintTextColor(UiHelper.COLOR_TEXT_SECONDARY)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setPadding(UiHelper.dp(context, 16), UiHelper.dp(context, 10), UiHelper.dp(context, 16), UiHelper.dp(context, 10))
            background = UiHelper.roundedBg(UiHelper.COLOR_CARD, 10, context, Color.parseColor("#40FFFFFF"), 1)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                rightMargin = UiHelper.dp(context, 10)
            }
        }

        val searchBtn = TextView(this).apply {
            text = "Tìm kiếm"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(UiHelper.dp(context, 22), UiHelper.dp(context, 11), UiHelper.dp(context, 22), UiHelper.dp(context, 11))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { rightMargin = UiHelper.dp(context, 8) }
        }

        val clearBtn = TextView(this).apply {
            text = "Xoá ô nhập"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(UiHelper.dp(context, 16), UiHelper.dp(context, 11), UiHelper.dp(context, 16), UiHelper.dp(context, 11))
        }

        val historyContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val resultsFrame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        fun refreshHistoryBar(onSelectHistory: (String) -> Unit) {
            historyContainer.removeAllViews()
            val history = loadSearchHistory()
            if (history.isEmpty()) return

            val rowWrap = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = UiHelper.dp(context, 10) }
            }

            val label = TextView(this).apply {
                text = "Lịch sử:"
                setTextColor(UiHelper.COLOR_TEXT_SECONDARY)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, 0, UiHelper.dp(context, 8), 0)
            }
            rowWrap.addView(label)

            val hScroll = HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val chipRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            for (term in history) {
                val chip = TextView(this).apply {
                    text = "🕒 $term"
                    setTextColor(Color.WHITE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                    typeface = Typeface.DEFAULT_BOLD
                    setSingleLine(true)
                    setPadding(UiHelper.dp(context, 12), UiHelper.dp(context, 6), UiHelper.dp(context, 12), UiHelper.dp(context, 6))
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { rightMargin = UiHelper.dp(context, 7) }
                }
                UiHelper.makeFocusableButton(
                    view = chip,
                    normalBgColor = Color.parseColor("#222638"),
                    focusedBgColor = UiHelper.COLOR_ACCENT,
                    focusedStrokeColor = Color.WHITE,
                    radiusDp = 16
                ) {
                    onSelectHistory(term)
                }
                chipRow.addView(chip)
            }
            hScroll.addView(chipRow)
            rowWrap.addView(hScroll)

            val clearHistoryBtn = TextView(this).apply {
                text = "🗑 Xoá lịch sử"
                setTextColor(Color.parseColor("#FFB4B4"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(UiHelper.dp(context, 10), UiHelper.dp(context, 6), UiHelper.dp(context, 10), UiHelper.dp(context, 6))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { leftMargin = UiHelper.dp(context, 6) }
            }
            UiHelper.makeFocusableButton(
                view = clearHistoryBtn,
                normalBgColor = Color.parseColor("#381E24"),
                focusedBgColor = UiHelper.COLOR_RED,
                focusedStrokeColor = Color.WHITE,
                radiusDp = 14
            ) {
                clearSearchHistory()
                refreshHistoryBar(onSelectHistory)
            }
            rowWrap.addView(clearHistoryBtn)
            historyContainer.addView(rowWrap)
        }

        var debounceSearchJob: Job? = null

        fun runSearch(query: String, autoFocusFirst: Boolean = true, saveToHistory: Boolean = true) {
            val clean = query.trim()
            if (clean.isEmpty()) return
            if (saveToHistory) {
                saveSearchQuery(clean)
                refreshHistoryBar { selectedTerm ->
                    input.setText(selectedTerm)
                    input.setSelection(selectedTerm.length)
                    runSearch(selectedTerm, autoFocusFirst = true, saveToHistory = true)
                }
            }

            if (autoFocusFirst) {
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.hideSoftInputFromWindow(input.windowToken, 0)
            }

            resultsFrame.removeAllViews()
            resultsFrame.addView(
                UiHelper.createLoadingBox(
                    this,
                    currentSource.isMovie,
                    "Đang tìm \"$clean\" trong ${currentSource.title}…",
                    sizeDp = 100
                )
            )

            loadJob?.cancel()
            loadJob = uiScope.launch {
                try {
                    val items = withContext(Dispatchers.IO) {
                        SourceIsolation.items(screenSource, CatalogService.search(screenSource, clean))
                    }
                    ensureActive()
                    if (currentSource != screenSource || currentTab != "search") return@launch
                    resultsFrame.removeAllViews()
                    if (items.isEmpty()) {
                        val emptyText = TextView(this@MainActivity).apply {
                            text = "Không tìm thấy kết quả cho \"$clean\""
                            setTextColor(UiHelper.COLOR_TEXT_SECONDARY)
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                            gravity = Gravity.CENTER
                            layoutParams = FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                        }
                        resultsFrame.addView(emptyText)
                    } else {
                        val grid = RecyclerView(this@MainActivity).apply {
                            layoutManager = GridLayoutManager(context, 6)
                            clipToPadding = false
                            setHasFixedSize(true)
                            layoutParams = FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                            adapter = PosterAdapter(items) { anime -> openDetail(anime) }
                        }
                        resultsFrame.addView(grid)

                        // Khi tìm kiếm: con trỏ đặt luôn ở phim đầu tiên tìm thấy
                        if (autoFocusFirst) {
                            grid.post {
                                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                                imm?.hideSoftInputFromWindow(input.windowToken, 0)
                                val first = grid.findViewHolderForAdapterPosition(0)?.itemView
                                    ?: grid.getChildAt(0)
                                first?.requestFocus()
                            }
                        }
                    }
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    if (currentSource != screenSource || currentTab != "search") return@launch
                    resultsFrame.removeAllViews()
                    val errText = TextView(this@MainActivity).apply {
                        text = "Lỗi tìm kiếm: ${t.message}"
                        setTextColor(UiHelper.COLOR_TEXT_SECONDARY)
                        gravity = Gravity.CENTER
                        layoutParams = FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    }
                    resultsFrame.addView(errText)
                }
            }
        }

        // Tự động tìm kiếm ngay trong lúc người dùng nhập tên phim (debounce 350ms)
        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val q = s?.toString()?.trim().orEmpty()
                debounceSearchJob?.cancel()
                if (q.length >= 2) {
                    debounceSearchJob = uiScope.launch {
                        kotlinx.coroutines.delay(350)
                        runSearch(q, autoFocusFirst = false, saveToHistory = false)
                    }
                }
            }
        })

        input.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            ) {
                debounceSearchJob?.cancel()
                runSearch(input.text.toString(), autoFocusFirst = true, saveToHistory = true)
                true
            } else false
        }

        UiHelper.makeFocusableButton(
            view = searchBtn,
            normalBgColor = UiHelper.COLOR_ACCENT,
            focusedBgColor = Color.parseColor("#C084FC"),
            focusedStrokeColor = Color.WHITE,
            radiusDp = 10
        ) {
            debounceSearchJob?.cancel()
            runSearch(input.text.toString(), autoFocusFirst = true, saveToHistory = true)
        }

        UiHelper.makeFocusableButton(
            view = clearBtn,
            normalBgColor = UiHelper.COLOR_CARD,
            focusedBgColor = Color.parseColor("#352256"),
            focusedStrokeColor = Color.WHITE,
            radiusDp = 10
        ) {
            input.setText("")
        }

        searchBarRow.addView(input)
        searchBarRow.addView(searchBtn)
        searchBarRow.addView(clearBtn)
        mainCol.addView(searchBarRow)

        // Remote D-Pad Quick Character & Keyword Bar for Projector Remote
        val quickRowScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = UiHelper.dp(context, 8) }
        }
        val quickRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val chars = "A B C D E G H I K L M N O P Q R S T U V X Y 0 1 2 3 4 5 6 7 8 9".split(" ")
        for (ch in chars) {
            val keyBtn = TextView(this).apply {
                text = ch
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(UiHelper.dp(context, 11), UiHelper.dp(context, 6), UiHelper.dp(context, 11), UiHelper.dp(context, 6))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { rightMargin = UiHelper.dp(context, 5) }
            }
            UiHelper.makeFocusableButton(
                view = keyBtn,
                normalBgColor = UiHelper.COLOR_CARD,
                focusedBgColor = UiHelper.COLOR_ACCENT,
                focusedStrokeColor = Color.WHITE,
                radiusDp = 8
            ) {
                input.append(ch.lowercase())
            }
            quickRow.addView(keyBtn)
        }
        val spaceBtn = TextView(this).apply {
            text = "Dấu cách"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(UiHelper.dp(context, 12), UiHelper.dp(context, 6), UiHelper.dp(context, 12), UiHelper.dp(context, 6))
        }
        UiHelper.makeFocusableButton(view = spaceBtn, normalBgColor = UiHelper.COLOR_CARD, radiusDp = 8) {
            input.append(" ")
        }
        quickRow.addView(spaceBtn)
        quickRowScroll.addView(quickRow)
        mainCol.addView(quickRowScroll)

        refreshHistoryBar { selectedTerm ->
            input.setText(selectedTerm)
            input.setSelection(selectedTerm.length)
            runSearch(selectedTerm)
        }
        mainCol.addView(historyContainer)
        mainCol.addView(resultsFrame)
        contentContainer.addView(mainCol)
        searchBtn.requestFocus()
    }

    // =========================================================================
    // CATEGORY BROWSE SCREEN (Grouped Filter + Full-Width 6-Column Grid)
    // =========================================================================

    private fun openCategoryBrowse(selectedCategory: AnimeCategory?) {
        loadJob?.cancel()
        val screenSource = currentSource
        showLoading("Đang tải danh mục ${screenSource.subtitle}…")

        loadJob = uiScope.launch {
            val categories = try {
                withContext(Dispatchers.IO) {
                    CatalogService.getCategories(screenSource)
                }
            } catch (_: Throwable) {
                emptyList()
            }
            ensureActive()
            if (currentSource != screenSource || currentTab != "browse") return@launch

            val active = selectedCategory ?: categories.firstOrNull() ?: AnimeCategory("Mới cập nhật", "")
            activeCategory = active
            browsePage = 1
            browseItems.clear()

            val groups = categories.map { it.group }.distinct()
            val activeGroup = active.group.takeIf { groups.contains(it) } ?: groups.firstOrNull().orEmpty()
            val groupCategories = if (activeGroup.isNotEmpty()) {
                categories.filter { it.group == activeGroup }
            } else {
                categories
            }

            contentContainer.removeAllViews()
            val mainCol = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(UiHelper.dp(context, 18), UiHelper.dp(context, 8), UiHelper.dp(context, 18), UiHelper.dp(context, 14))
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }

            // Row 1: Group Filter Tabs (Danh mục / Thể loại / Quốc gia / Năm / VIP / Free1)
            if (groups.size > 1) {
                val groupScroll = HorizontalScrollView(this@MainActivity).apply {
                    isHorizontalScrollBarEnabled = false
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = UiHelper.dp(context, 6) }
                }
                val groupRow = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                }
                for (grp in groups) {
                    val isGrpActive = grp == activeGroup
                    val grpBtn = TextView(this@MainActivity).apply {
                        text = grp
                        setTextColor(if (isGrpActive) Color.BLACK else Color.WHITE)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                        typeface = Typeface.DEFAULT_BOLD
                        setPadding(UiHelper.dp(context, 14), UiHelper.dp(context, 6), UiHelper.dp(context, 14), UiHelper.dp(context, 6))
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply { rightMargin = UiHelper.dp(context, 8) }
                    }
                    UiHelper.makeFocusableButton(
                        view = grpBtn,
                        normalBgColor = if (isGrpActive) UiHelper.COLOR_YELLOW else Color.parseColor("#222638"),
                        focusedBgColor = UiHelper.COLOR_ACCENT,
                        focusedStrokeColor = Color.WHITE,
                        radiusDp = 16
                    ) {
                        val firstInGroup = categories.firstOrNull { it.group == grp }
                        if (firstInGroup != null) openCategoryBrowse(firstInGroup)
                    }
                    groupRow.addView(grpBtn)
                }
                groupScroll.addView(groupRow)
                mainCol.addView(groupScroll)
            }

            // Row 2: Category Chips within active group
            val catScroll = HorizontalScrollView(this@MainActivity).apply {
                isHorizontalScrollBarEnabled = false
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = UiHelper.dp(context, 10) }
            }
            val catRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            for (cat in groupCategories) {
                val isSelected = cat.path == active.path && cat.title == active.title
                val chip = TextView(this@MainActivity).apply {
                    text = cat.title
                    setTextColor(Color.WHITE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    typeface = Typeface.DEFAULT_BOLD
                    setPadding(UiHelper.dp(context, 14), UiHelper.dp(context, 8), UiHelper.dp(context, 14), UiHelper.dp(context, 8))
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { rightMargin = UiHelper.dp(context, 8) }
                }
                UiHelper.makeFocusableButton(
                    view = chip,
                    normalBgColor = if (isSelected) UiHelper.COLOR_ACCENT else UiHelper.COLOR_CARD,
                    focusedBgColor = Color.parseColor("#C084FC"),
                    focusedStrokeColor = Color.WHITE,
                    radiusDp = 18
                ) {
                    openCategoryBrowse(cat)
                }
                catRow.addView(chip)
            }
            catScroll.addView(catRow)
            mainCol.addView(catScroll)

            // Header (Shows title + status, loads automatically on scroll without manual button)
            val headerRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = UiHelper.dp(context, 8) }
            }
            val titleLabel = TextView(this@MainActivity).apply {
                text = active.title
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16.5f)
                typeface = Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val statusLabel = TextView(this@MainActivity).apply {
                text = "Đang tải…"
                setTextColor(UiHelper.COLOR_TEXT_SECONDARY)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                typeface = Typeface.DEFAULT_BOLD
            }
            headerRow.addView(titleLabel)
            headerRow.addView(statusLabel)
            mainCol.addView(headerRow)

            val gridContainer = FrameLayout(this@MainActivity).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )
            }
            mainCol.addView(gridContainer)
            contentContainer.addView(mainCol)

            val items = ArrayList<Anime>()
            var isLoadingMore = false
            var hasMorePages = true
            var currentPage = 1
            var gridView: RecyclerView? = null
            var posterAdapter: PosterAdapter? = null

            fun loadPage(pageToLoad: Int) {
                if (isLoadingMore || (!hasMorePages && pageToLoad > 1)) return
                isLoadingMore = true
                if (pageToLoad == 1) {
                    items.clear()
                    statusLabel.text = "Đang tải…"
                    gridContainer.removeAllViews()
                    gridContainer.addView(
                        UiHelper.createLoadingBox(
                            this@MainActivity,
                            currentSource.isMovie,
                            "Đang tải danh mục ${active.title}…",
                            sizeDp = 100
                        )
                    )
                } else {
                    statusLabel.text = "Đang tải thêm trang $pageToLoad…"
                }

                loadJob?.cancel()
                loadJob = uiScope.launch {
                    try {
                        val nextItems = withContext(Dispatchers.IO) {
                            SourceIsolation.items(
                                screenSource,
                                CatalogService.loadList(screenSource, active.path, pageToLoad)
                            )
                        }
                        ensureActive()
                        if (currentSource != screenSource || currentTab != "browse" || activeCategory != active) return@launch
                        val existingUrls = items.map { it.url }.toHashSet()
                        val unique = nextItems.filter { existingUrls.add(it.url) }

                        if (nextItems.isEmpty() || (pageToLoad > 1 && unique.isEmpty())) {
                            hasMorePages = false
                            statusLabel.text = "${items.size} phim (Đã hết)"
                        } else {
                            val startPos = items.size
                            items.addAll(unique)
                            currentPage = pageToLoad
                            statusLabel.text = "${items.size} phim"

                            if (pageToLoad == 1 || gridView == null) {
                                gridContainer.removeAllViews()
                                val grid = RecyclerView(this@MainActivity).apply {
                                    layoutManager = GridLayoutManager(context, 6)
                                    clipToPadding = false
                                    setHasFixedSize(true)
                                    layoutParams = FrameLayout.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.MATCH_PARENT
                                    )
                                    val adp = PosterAdapter(items) { anime -> openDetail(anime) }
                                    posterAdapter = adp
                                    adapter = adp

                                    addOnScrollListener(object : RecyclerView.OnScrollListener() {
                                        override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                                            super.onScrolled(rv, dx, dy)
                                            val glm = rv.layoutManager as? GridLayoutManager ?: return
                                            val lastVis = glm.findLastVisibleItemPosition()
                                            val total = glm.itemCount
                                            if (!isLoadingMore && hasMorePages && total > 0 && lastVis >= total - 12) {
                                                loadPage(currentPage + 1)
                                            }
                                        }
                                    })
                                }
                                gridView = grid
                                gridContainer.addView(grid)
                            } else {
                                posterAdapter?.notifyItemRangeInserted(startPos, unique.size)
                            }
                        }
                    } catch (t: Throwable) {
                        if (t is CancellationException) throw t
                        if (currentSource != screenSource || currentTab != "browse" || activeCategory != active) return@launch
                        if (pageToLoad == 1) {
                            gridContainer.removeAllViews()
                            gridContainer.addView(
                                TextView(this@MainActivity).apply {
                                    text = "Không tải được danh mục: ${t.message}"
                                    setTextColor(UiHelper.COLOR_TEXT_SECONDARY)
                                    gravity = Gravity.CENTER
                                    layoutParams = FrameLayout.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.MATCH_PARENT
                                    )
                                }
                            )
                        }
                        statusLabel.text = "${items.size} phim"
                    } finally {
                        isLoadingMore = false
                    }
                }
            }

            loadPage(1)
        }
    }

    // =========================================================================
    // ANIME SCHEDULE & CUSTOM DOMAIN DIALOG
    // =========================================================================

    private fun renderSchedule(selectedDay: DayOfWeek) {
        loadJob?.cancel()
        showLoading("Đang tải lịch chiếu AnimeVietsub…")
        loadJob = uiScope.launch {
            val allItems = try {
                withContext(Dispatchers.IO) { CatalogService.loadSchedule() }
            } catch (_: Throwable) {
                emptyList()
            }
            ensureActive()
            if (currentTab != "schedule") return@launch

            contentContainer.removeAllViews()
            val mainCol = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(UiHelper.dp(context, 18), UiHelper.dp(context, 10), UiHelper.dp(context, 18), UiHelper.dp(context, 16))
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }

            val dayRowScroll = HorizontalScrollView(this@MainActivity).apply {
                isHorizontalScrollBarEnabled = false
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = UiHelper.dp(context, 12) }
            }
            val dayRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
            }

            for (day in DayOfWeek.entries) {
                val isSelected = day == selectedDay
                val chip = TextView(this@MainActivity).apply {
                    text = day.displayName
                    setTextColor(if (isSelected) Color.BLACK else Color.WHITE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    typeface = Typeface.DEFAULT_BOLD
                    setPadding(UiHelper.dp(context, 16), UiHelper.dp(context, 8), UiHelper.dp(context, 16), UiHelper.dp(context, 8))
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { rightMargin = UiHelper.dp(context, 8) }
                }
                UiHelper.makeFocusableButton(
                    view = chip,
                    normalBgColor = if (isSelected) UiHelper.COLOR_YELLOW else UiHelper.COLOR_CARD,
                    focusedBgColor = UiHelper.COLOR_ACCENT,
                    focusedStrokeColor = Color.WHITE,
                    radiusDp = 18
                ) {
                    renderSchedule(day)
                }
                dayRow.addView(chip)
            }
            dayRowScroll.addView(dayRow)
            mainCol.addView(dayRowScroll)

            val filtered = allItems.filter { it.dayOfWeek == selectedDay }.ifEmpty { allItems }
            val animeCards = filtered.map { item ->
                Anime(
                    title = item.title,
                    subtitle = listOf(item.episodeText, item.timeText).filter { it.isNotEmpty() }.joinToString(" • "),
                    url = item.url,
                    episodeText = item.episodeText,
                    quality = item.timeText.ifEmpty { item.dayOfWeek.rawValue },
                    source = ContentSource.ANIME
                )
            }

            if (animeCards.isEmpty()) {
                mainCol.addView(TextView(this@MainActivity).apply {
                    text = "Chưa có lịch chiếu cho ${selectedDay.displayName}."
                    setTextColor(UiHelper.COLOR_TEXT_SECONDARY)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        0,
                        1f
                    )
                })
            } else {
                val grid = RecyclerView(this@MainActivity).apply {
                    layoutManager = GridLayoutManager(context, 6)
                    clipToPadding = false
                    setHasFixedSize(true)
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        0,
                        1f
                    )
                    adapter = PosterAdapter(animeCards) { anime ->
                        currentSource = ContentSource.ANIME
                        updateSidebarState()
                        openDetail(anime)
                    }
                }
                mainCol.addView(grid)
            }

            contentContainer.addView(mainCol)
        }
    }

    private fun showAnimeDomainDialog() {
        val currentCustom = CatalogService.getCustomAnimeDomain(this).orEmpty()
        val activeDomain = CatalogService.currentAnimeBaseUrl()

        val input = EditText(this).apply {
            hint = "Để trống để tự động tìm qua bit.ly/animevietsubtv"
            setText(currentCustom)
            setTextColor(Color.WHITE)
            setHintTextColor(UiHelper.COLOR_TEXT_SECONDARY)
            setSingleLine(true)
            setPadding(UiHelper.dp(context, 16), UiHelper.dp(context, 12), UiHelper.dp(context, 16), UiHelper.dp(context, 12))
        }

        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Tên miền AnimeVietsub (Đang dùng: $activeDomain)")
            .setView(input)
            .setPositiveButton("Lưu & Áp dụng") { _, _ ->
                CatalogService.setCustomAnimeDomain(this, input.text.toString())
                if (currentSource == ContentSource.ANIME) {
                    loadHomeData()
                }
            }
            .setNeutralButton("Tự động dò lại") { _, _ ->
                CatalogService.setCustomAnimeDomain(this, null)
                uiScope.launch {
                    withContext(Dispatchers.IO) {
                        CatalogService.resolveAnimeDomain(forceRefresh = true)
                    }
                    if (currentSource == ContentSource.ANIME) {
                        loadHomeData()
                    }
                }
            }
            .setNegativeButton("Đóng", null)
            .show()
    }

    private fun openDetail(anime: Anime) {
        val intent = Intent(this, DetailActivity::class.java).apply {
            putExtra("anime_json", anime.toJson())
        }
        startActivity(intent)
    }

    private class PosterAdapter(
        private val items: List<Anime>,
        private val onClick: (Anime) -> Unit
    ) : RecyclerView.Adapter<PosterAdapter.Holder>() {

        class Holder(val card: FrameLayout) : RecyclerView.ViewHolder(card)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(UiHelper.createPosterCard(parent.context))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            UiHelper.bindPosterCard(holder.card, items[position], onClick)
        }

        override fun getItemCount(): Int = items.size
    }
}
