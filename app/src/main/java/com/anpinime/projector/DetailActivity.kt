package com.anpinime.projector

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class DetailActivity : AppCompatActivity() {

    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var anime: Anime
    private var detail: AnimeDetail? = null
    private var selectedSeason: String = ""
    private lateinit var rootContainer: FrameLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val rawJson = intent.getStringExtra("anime_json")
        if (rawJson.isNullOrBlank()) {
            finish()
            return
        }
        anime = Anime.fromJson(rawJson)

        rootContainer = FrameLayout(this).apply {
            setBackgroundColor(UiHelper.COLOR_BG)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        setContentView(rootContainer)

        loadDetail()
    }

    override fun onDestroy() {
        super.onDestroy()
        uiScope.cancel()
    }

    private fun loadDetail() {
        rootContainer.removeAllViews()
        val loadingBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        loadingBox.addView(ProgressBar(this))
        loadingBox.addView(
            TextView(this).apply {
                text = "Đang tải thông tin phim & danh sách máy chủ…"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, UiHelper.dp(context, 14), 0, 0)
            }
        )
        rootContainer.addView(loadingBox)

        uiScope.launch {
            try {
                val loaded = CatalogService.loadDetail(anime)
                detail = loaded
                val seasons = orderedSeasons(loaded.episodes)
                if (selectedSeason.isBlank() || !seasons.contains(selectedSeason)) {
                    selectedSeason = seasons.firstOrNull().orEmpty()
                }
                renderDetail(loaded)
            } catch (t: Throwable) {
                renderError(t.message ?: "Không tải được chi tiết phim.")
            }
        }
    }

    private fun orderedSeasons(episodes: List<Episode>): List<String> {
        val list = ArrayList<String>()
        for (ep in episodes) {
            if (!list.contains(ep.season)) list.add(ep.season)
        }
        return list
    }

    private fun renderError(msg: String) {
        rootContainer.removeAllViews()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        val errText = TextView(this).apply {
            text = msg
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(0, 0, 0, UiHelper.dp(context, 16))
        }
        val retryBtn = TextView(this).apply {
            text = "Thử lại"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(UiHelper.dp(context, 24), UiHelper.dp(context, 12), UiHelper.dp(context, 24), UiHelper.dp(context, 12))
        }
        UiHelper.makeFocusableButton(retryBtn, normalBgColor = UiHelper.COLOR_RED, radiusDp = 20) {
            loadDetail()
        }
        col.addView(errText)
        col.addView(retryBtn)
        rootContainer.addView(col)
        retryBtn.requestFocus()
    }

    private fun renderDetail(data: AnimeDetail) {
        rootContainer.removeAllViews()
        val seasons = orderedSeasons(data.episodes)
        val activeSeason = if (selectedSeason.isNotBlank() && seasons.contains(selectedSeason)) {
            selectedSeason
        } else {
            seasons.firstOrNull().orEmpty()
        }
        val visibleEpisodes = data.episodes.filter { it.season == activeSeason }

        val mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(UiHelper.dp(context, 22), UiHelper.dp(context, 18), UiHelper.dp(context, 22), UiHelper.dp(context, 18))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // LEFT COLUMN: Poster + Back Button
        val leftCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                UiHelper.dp(context, 210),
                ViewGroup.LayoutParams.MATCH_PARENT
            ).apply {
                rightMargin = UiHelper.dp(context, 24)
            }
        }

        val backBtn = TextView(this).apply {
            text = "❮  Quay lại"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(UiHelper.dp(context, 14), UiHelper.dp(context, 9), UiHelper.dp(context, 14), UiHelper.dp(context, 9))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = UiHelper.dp(context, 14) }
        }
        UiHelper.makeFocusableButton(backBtn, normalBgColor = UiHelper.COLOR_CARD, radiusDp = 10) {
            finish()
        }
        leftCol.addView(backBtn)

        val posterImg = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = UiHelper.roundedBg(UiHelper.COLOR_CARD, 12, context, Color.parseColor("#33FFFFFF"), 1)
            layoutParams = LinearLayout.LayoutParams(
                UiHelper.dp(context, 190),
                UiHelper.dp(context, 276)
            )
        }
        ImageLoader.load(posterImg, data.posterUrl ?: anime.imageUrl, maxWidth = 260, maxHeight = 380)
        leftCol.addView(posterImg)

        if (seasons.isNotEmpty()) {
            val svCountBadge = UiHelper.createBadge(
                this,
                "${seasons.size} Máy chủ khả dụng",
                UiHelper.COLOR_ACCENT,
                Color.WHITE
            ).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = UiHelper.dp(context, 12) }
            }
            leftCol.addView(svCountBadge)
        }

        // RIGHT COLUMN: Title, Info, Summary, Play Now, Server Bar, Episode Grid
        val rightCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        }

        val titleView = TextView(this).apply {
            text = data.title
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        rightCol.addView(titleView)

        if (data.info.isNotBlank()) {
            val infoView = TextView(this).apply {
                text = data.info
                setTextColor(UiHelper.COLOR_YELLOW)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                typeface = Typeface.DEFAULT_BOLD
                setSingleLine(true)
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, UiHelper.dp(context, 4), 0, UiHelper.dp(context, 6))
            }
            rightCol.addView(infoView)
        }

        val summaryView = TextView(this).apply {
            text = data.summary
            setTextColor(UiHelper.COLOR_TEXT_SECONDARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, 0, 0, UiHelper.dp(context, 12))
        }
        rightCol.addView(summaryView)

        // Play Action Row
        val playRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = UiHelper.dp(context, 14) }
        }

        val firstEp = visibleEpisodes.firstOrNull() ?: data.episodes.firstOrNull()
        val playNowBtn = TextView(this).apply {
            text = if (firstEp != null) "▶  XEM PHIM NGAY (${firstEp.title} · $activeSeason)" else "Chưa có tập phát"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(UiHelper.dp(context, 26), UiHelper.dp(context, 12), UiHelper.dp(context, 26), UiHelper.dp(context, 12))
        }
        if (firstEp != null) {
            UiHelper.makeFocusableButton(
                view = playNowBtn,
                normalBgColor = UiHelper.COLOR_RED,
                focusedBgColor = Color.parseColor("#FF5252"),
                focusedStrokeColor = Color.WHITE,
                radiusDp = 24,
                scaleOnFocus = 1.05f
            ) {
                startPlayer(firstEp, data.episodes)
            }
        }
        playRow.addView(playNowBtn)
        rightCol.addView(playRow)

        // Server Selector Bar
        if (seasons.size > 1) {
            val svTitle = TextView(this).apply {
                text = "Chọn Máy Chủ (Server):"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, 0, 0, UiHelper.dp(context, 6))
            }
            rightCol.addView(svTitle)

            val svScroll = HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = UiHelper.dp(context, 12) }
            }
            val svRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            for (season in seasons) {
                val isSelected = season == activeSeason
                val badge = data.episodes.firstOrNull { it.season == season }?.qualityBadge.orEmpty()
                val labelText = if (badge.isNotBlank()) "🖥 $season [$badge]" else "🖥 $season"
                val svChip = TextView(this).apply {
                    text = labelText
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
                    view = svChip,
                    normalBgColor = if (isSelected) UiHelper.COLOR_ACCENT else UiHelper.COLOR_CARD,
                    focusedBgColor = Color.parseColor("#C084FC"),
                    focusedStrokeColor = Color.WHITE,
                    radiusDp = 18
                ) {
                    selectedSeason = season
                    renderDetail(data)
                }
                svRow.addView(svChip)
            }
            svScroll.addView(svRow)
            rightCol.addView(svScroll)
        }

        // Episodes Grid
        val epHeader = TextView(this).apply {
            text = "Danh sách tập (${visibleEpisodes.size} tập):"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, UiHelper.dp(context, 8))
        }
        rightCol.addView(epHeader)

        val epGrid = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(context, 6)
            clipToPadding = false
            setHasFixedSize(true)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            adapter = EpisodeAdapter(visibleEpisodes) { chosenEp ->
                startPlayer(chosenEp, data.episodes)
            }
        }
        rightCol.addView(epGrid)

        mainLayout.addView(leftCol)
        mainLayout.addView(rightCol)
        rootContainer.addView(mainLayout)

        // Focus Play button immediately so pressing OK on the remote plays right away
        playNowBtn.requestFocus()
    }

    private fun startPlayer(chosenEpisode: Episode, allEpisodes: List<Episode>) {
        // Free image cache before starting video playback so projector has maximum RAM
        ImageLoader.trimMemory()
        val intent = Intent(this, PlayerActivity::class.java).apply {
            putExtra("movie_title", detail?.title ?: anime.title)
            putExtra("episode_json", chosenEpisode.toJsonObject().toString())
            putExtra("all_episodes_json", Episode.toJsonArray(allEpisodes))
        }
        startActivity(intent)
    }

    private class EpisodeAdapter(
        private val episodes: List<Episode>,
        private val onClick: (Episode) -> Unit
    ) : RecyclerView.Adapter<EpisodeAdapter.Holder>() {

        class Holder(val btn: TextView) : RecyclerView.ViewHolder(btn)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val ctx = parent.context
            val tv = TextView(ctx).apply {
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setSingleLine(true)
                ellipsize = TextUtils.TruncateAt.END
                setPadding(UiHelper.dp(ctx, 8), UiHelper.dp(ctx, 10), UiHelper.dp(ctx, 8), UiHelper.dp(ctx, 10))
                layoutParams = ViewGroup.MarginLayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    rightMargin = UiHelper.dp(ctx, 8)
                    bottomMargin = UiHelper.dp(ctx, 8)
                }
            }
            return Holder(tv)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val ep = episodes[position]
            holder.btn.text = ep.title
            UiHelper.makeFocusableButton(
                view = holder.btn,
                normalBgColor = UiHelper.COLOR_CARD,
                focusedBgColor = UiHelper.COLOR_RED,
                focusedStrokeColor = Color.WHITE,
                radiusDp = 8,
                scaleOnFocus = 1.05f
            ) {
                onClick(ep)
            }
        }

        override fun getItemCount(): Int = episodes.size
    }
}
