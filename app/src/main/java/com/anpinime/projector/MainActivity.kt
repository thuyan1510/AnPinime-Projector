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
import android.widget.ProgressBar
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

class MainActivity : AppCompatActivity() {

    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var loadJob: Job? = null

    private var currentSource: ContentSource = ContentSource.FILM4K
    private var currentTab: String = "home" // "home", "search", "browse"
    private var homeSections: List<AnimeSection> = emptyList()
    private var featuredIndex: Int = 0
    private var activeCategory: AnimeCategory? = null
    private var browseItems: MutableList<Anime> = ArrayList()
    private var browsePage: Int = 1

    private lateinit var logoImage: ImageView
    private lateinit var sourceSubtitleText: TextView
    private lateinit var contentContainer: FrameLayout
    private val sidebarButtons = HashMap<String, TextView>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("anpinime_projector", Context.MODE_PRIVATE)
        currentSource = ContentSource.fromId(prefs.getString("source", ContentSource.FILM4K.id))

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(UiHelper.COLOR_BG)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val sidebar = buildSidebar()
        contentContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        }

        root.addView(sidebar)
        root.addView(contentContainer)
        setContentView(root)

        updateSidebarState()
        loadHomeData()
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

    private fun buildSidebar(): View {
        val sidebar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(UiHelper.COLOR_SIDEBAR)
            setPadding(
                UiHelper.dp(context, 14),
                UiHelper.dp(context, 18),
                UiHelper.dp(context, 14),
                UiHelper.dp(context, 14)
            )
            layoutParams = LinearLayout.LayoutParams(
                UiHelper.dp(context, 215),
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val brandRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(UiHelper.dp(context, 4), 0, UiHelper.dp(context, 4), UiHelper.dp(context, 14))
        }

        logoImage = ImageView(this).apply {
            setImageResource(if (currentSource.isMovie) R.drawable.logo_movie else R.drawable.logo_anime)
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(UiHelper.dp(context, 44), UiHelper.dp(context, 44)).apply {
                rightMargin = UiHelper.dp(context, 10)
            }
        }

        val brandTextCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
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
                onClick = onClick
            )
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
            val firstCat = CatalogService.categoriesFor(currentSource).firstOrNull()
            openCategoryBrowse(firstCat)
        }

        val dividerLabel = TextView(this).apply {
            text = "CHỌN NGUỒN PHIM"
            setTextColor(UiHelper.COLOR_TEXT_SECONDARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(UiHelper.dp(context, 6), UiHelper.dp(context, 10), 0, UiHelper.dp(context, 6))
        }
        sidebar.addView(dividerLabel)

        addMenuButton("src_film4k", "🎬  Nguồn phim 1") {
            switchSource(ContentSource.FILM4K)
        }

        addMenuButton("src_phim4k", "⚡  Nguồn phim 2") {
            switchSource(ContentSource.PHIM4K)
        }

        addMenuButton("src_anime", "✨  AnimeVietsub") {
            switchSource(ContentSource.ANIME)
        }

        addMenuButton("refresh", "🔄  Làm mới") {
            loadHomeData()
        }

        return sidebar
    }

    private fun switchSource(newSource: ContentSource) {
        if (currentSource == newSource && homeSections.isNotEmpty() && currentTab == "home") return
        currentSource = newSource
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
        logoImage.setImageResource(if (currentSource.isMovie) R.drawable.logo_movie else R.drawable.logo_anime)
        sourceSubtitleText.text = currentSource.title

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
        styleBtn("src_film4k", currentSource == ContentSource.FILM4K)
        styleBtn("src_phim4k", currentSource == ContentSource.PHIM4K)
        styleBtn("src_anime", currentSource == ContentSource.ANIME)
    }

    private fun showLoading(message: String) {
        contentContainer.removeAllViews()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        val spinner = ProgressBar(this)
        val label = TextView(this).apply {
            text = message
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, UiHelper.dp(context, 14), 0, 0)
        }
        box.addView(spinner)
        box.addView(label)
        contentContainer.addView(box)
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
        showLoading("Đang tải ${targetSource.title}…")
        loadJob = uiScope.launch {
            try {
                val sections = CatalogService.loadHome(targetSource)
                if (targetSource != currentSource) return@launch
                homeSections = sections
                featuredIndex = 0
                renderHome()
            } catch (t: Throwable) {
                if (targetSource != currentSource) return@launch
                showError(t.message ?: "Không kết nối được máy chủ.") {
                    loadHomeData()
                }
            }
        }
    }

    // =========================================================================
    // HOME SCREEN (Phim4K Hero Banner + Horizontal Section Rows)
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
            setPadding(UiHelper.dp(context, 18), UiHelper.dp(context, 14), UiHelper.dp(context, 18), UiHelper.dp(context, 28))
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
                UiHelper.dp(context, 245)
            ).apply {
                bottomMargin = UiHelper.dp(context, 20)
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
            setPadding(UiHelper.dp(context, 22), UiHelper.dp(context, 16), UiHelper.dp(context, 22), UiHelper.dp(context, 16))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val posterCard = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = UiHelper.roundedBg(UiHelper.COLOR_SIDEBAR, 10, context, Color.parseColor("#40FFFFFF"), 1)
            layoutParams = LinearLayout.LayoutParams(
                UiHelper.dp(context, 136),
                UiHelper.dp(context, 198)
            ).apply {
                rightMargin = UiHelper.dp(context, 22)
            }
        }
        ImageLoader.load(posterCard, current.imageUrl, maxWidth = 220, maxHeight = 320)
        contentRow.addView(posterCard)

        val centerStack = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val titleView = TextView(this).apply {
            text = current.title
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 23f)
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

        // Action Buttons: White Capsule [Xem chi tiết] + [Đổi phim đề cử ❯]
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
            setItemViewCacheSize(4)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                UiHelper.dp(context, 262)
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
    // SEARCH SCREEN (With Remote D-Pad Quick Input + Keyboard)
    // =========================================================================

    private fun renderSearch() {
        contentContainer.removeAllViews()
        val mainCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(UiHelper.dp(context, 18), UiHelper.dp(context, 16), UiHelper.dp(context, 18), UiHelper.dp(context, 16))
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
            ).apply { bottomMargin = UiHelper.dp(context, 10) }
        }

        val input = EditText(this).apply {
            hint = "Nhập tên phim cần tìm trong ${currentSource.title}…"
            setHintTextColor(UiHelper.COLOR_TEXT_SECONDARY)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setPadding(UiHelper.dp(context, 16), UiHelper.dp(context, 12), UiHelper.dp(context, 16), UiHelper.dp(context, 12))
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
            setPadding(UiHelper.dp(context, 22), UiHelper.dp(context, 12), UiHelper.dp(context, 22), UiHelper.dp(context, 12))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { rightMargin = UiHelper.dp(context, 8) }
        }

        val clearBtn = TextView(this).apply {
            text = "Xoá"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(UiHelper.dp(context, 16), UiHelper.dp(context, 12), UiHelper.dp(context, 16), UiHelper.dp(context, 12))
        }

        val resultsFrame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        fun runSearch(query: String) {
            val clean = query.trim()
            if (clean.isEmpty()) return
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(input.windowToken, 0)

            resultsFrame.removeAllViews()
            val loading = ProgressBar(this).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER
                )
            }
            resultsFrame.addView(loading)

            loadJob?.cancel()
            loadJob = uiScope.launch {
                try {
                    val items = CatalogService.search(currentSource, clean)
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
                            layoutManager = GridLayoutManager(context, 5)
                            clipToPadding = false
                            setHasFixedSize(true)
                            layoutParams = FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                            adapter = PosterAdapter(items) { anime -> openDetail(anime) }
                        }
                        resultsFrame.addView(grid)
                    }
                } catch (t: Throwable) {
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

        input.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            ) {
                runSearch(input.text.toString())
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
            runSearch(input.text.toString())
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
            ).apply { bottomMargin = UiHelper.dp(context, 12) }
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

        mainCol.addView(resultsFrame)
        contentContainer.addView(mainCol)
        searchBtn.requestFocus()
    }

    // =========================================================================
    // CATEGORY BROWSE SCREEN (With Pagination)
    // =========================================================================

    private fun openCategoryBrowse(selectedCategory: AnimeCategory?) {
        val categories = CatalogService.categoriesFor(currentSource)
        val active = selectedCategory ?: categories.firstOrNull() ?: AnimeCategory("Mới cập nhật", "")
        activeCategory = active
        browsePage = 1
        browseItems.clear()

        contentContainer.removeAllViews()
        val mainCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(UiHelper.dp(context, 18), UiHelper.dp(context, 14), UiHelper.dp(context, 18), UiHelper.dp(context, 14))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // Top Horizontal Category Picker
        val catScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = UiHelper.dp(context, 12) }
        }
        val catRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        for (cat in categories) {
            val isSelected = cat.path == active.path && cat.title == active.title
            val chip = TextView(this).apply {
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

        // Header + Next Page Button
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = UiHelper.dp(context, 10) }
        }
        val titleLabel = TextView(this).apply {
            text = "${active.title} • Trang 1"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16.5f)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val nextPageBtn = TextView(this).apply {
            text = "Tải thêm phim (Trang tiếp) ❯"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(UiHelper.dp(context, 16), UiHelper.dp(context, 8), UiHelper.dp(context, 16), UiHelper.dp(context, 8))
        }
        headerRow.addView(titleLabel)
        headerRow.addView(nextPageBtn)
        mainCol.addView(headerRow)

        val gridContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        mainCol.addView(gridContainer)
        contentContainer.addView(mainCol)

        fun loadPage(pageToLoad: Int) {
            if (pageToLoad == 1) {
                gridContainer.removeAllViews()
                gridContainer.addView(
                    ProgressBar(this).apply {
                        layoutParams = FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            Gravity.CENTER
                        )
                    }
                )
            }
            loadJob?.cancel()
            loadJob = uiScope.launch {
                try {
                    val nextItems = CatalogService.loadList(currentSource, active.path, pageToLoad)
                    val existingUrls = browseItems.map { it.url }.toHashSet()
                    val unique = nextItems.filter { existingUrls.add(it.url) }
                    browseItems.addAll(unique)
                    browsePage = pageToLoad
                    titleLabel.text = "${active.title} • Trang $browsePage (${browseItems.size} phim)"

                    gridContainer.removeAllViews()
                    val grid = RecyclerView(this@MainActivity).apply {
                        layoutManager = GridLayoutManager(context, 5)
                        clipToPadding = false
                        setHasFixedSize(true)
                        layoutParams = FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        adapter = PosterAdapter(browseItems.toList()) { anime -> openDetail(anime) }
                    }
                    gridContainer.addView(grid)
                } catch (t: Throwable) {
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
                }
            }
        }

        UiHelper.makeFocusableButton(
            view = nextPageBtn,
            normalBgColor = UiHelper.COLOR_BLUE,
            focusedBgColor = Color.parseColor("#42A5F5"),
            focusedStrokeColor = Color.WHITE,
            radiusDp = 16
        ) {
            loadPage(browsePage + 1)
        }

        loadPage(1)
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
