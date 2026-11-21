package com.anpinime.projector

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager

/**
 * Phim4K-inspired obsidian-crimson UI builders shared across Portrait Mobile
 * and Landscape TV/Projector layouts without touching source parsing logic.
 */
object Phim4KUi {

    fun accentFor(source: ContentSource): Int =
        if (source.isMovie) UiHelper.COLOR_RED else UiHelper.COLOR_BLUE

    fun gridSpanCount(context: Context): Int =
        if (UiHelper.isCompactPortrait(context)) 3 else 6

    fun createGridPosterCard(context: Context, spanCount: Int): FrameLayout {
        val density = context.resources.displayMetrics.density.coerceAtLeast(1f)
        val screenWidthDp = (context.resources.displayMetrics.widthPixels / density).toInt()
        val horizontalPaddingDp = if (UiHelper.isCompactPortrait(context)) 28 else 48
        val cellWidthDp = ((screenWidthDp - horizontalPaddingDp) / spanCount.coerceAtLeast(1)).coerceIn(94, 160)
        val imageHeightDp = (cellWidthDp * 1.42f).toInt().coerceIn(134, 224)

        val card = UiHelper.createPosterCard(context, widthDp = cellWidthDp, heightDp = imageHeightDp)
        card.layoutParams = ViewGroup.MarginLayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            UiHelper.dp(context, imageHeightDp + 44)
        ).apply {
            leftMargin = UiHelper.dp(context, 3)
            rightMargin = UiHelper.dp(context, 3)
            bottomMargin = UiHelper.dp(context, 8)
        }
        return card
    }

    fun buildQualityPillText(anime: Anime): String {
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
            !anime.source.isMovie -> "Vietsub HD"
            else -> "Full HD (PĐ)"
        }
    }

    fun createMobileTopBar(
        context: Context,
        currentSource: ContentSource,
        onLogoCreated: (ImageView) -> Unit,
        onSourceTextCreated: (TextView) -> Unit,
        onOpenSources: () -> Unit,
        onOpenSearch: () -> Unit
    ): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(UiHelper.COLOR_BG)
            setPadding(UiHelper.dp(context, 16), 0, UiHelper.dp(context, 14), 0)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, UiHelper.dp(context, 62))
        }
        // MainActivity keeps the source image up to date for its TV header.
        onLogoCreated(ImageView(context))
        val brand = TextView(context).apply {
            val word = android.text.SpannableString("AnPinime")
            word.setSpan(android.text.style.ForegroundColorSpan(UiHelper.COLOR_RED), 0, 4, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            word.setSpan(android.text.style.ForegroundColorSpan(Color.WHITE), 4, word.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            text = word
            textSize = 21f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        row.addView(brand)
        val source = TextView(context).apply {
            text = "${currentSource.subtitle} ▾"
            setTextColor(Color.WHITE)
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(UiHelper.dp(context, 11), UiHelper.dp(context, 9), UiHelper.dp(context, 11), UiHelper.dp(context, 9))
            layoutParams = LinearLayout.LayoutParams(UiHelper.dp(context, 125), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                rightMargin = UiHelper.dp(context, 8)
            }
        }
        UiHelper.makeFocusableButton(source, UiHelper.COLOR_CARD, UiHelper.COLOR_RED, Color.WHITE, 999) { onOpenSources() }
        onSourceTextCreated(source)
        row.addView(source)
        val search = TextView(context).apply {
            text = "⌕"
            textSize = 27f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            contentDescription = "Tìm kiếm"
            layoutParams = LinearLayout.LayoutParams(UiHelper.dp(context, 40), UiHelper.dp(context, 40))
        }
        UiHelper.makeFocusableButton(search, Color.TRANSPARENT, UiHelper.COLOR_CARD, Color.WHITE, 999) { onOpenSearch() }
        row.addView(search)
        return row
    }

    fun createMobileBottomNav(
        context: Context,
        currentTab: String,
        currentSource: ContentSource,
        onSelectTab: (String) -> Unit,
        onOpenSources: () -> Unit
    ): LinearLayout {
        val barWrap = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(UiHelper.COLOR_SIDEBAR)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        barWrap.addView(View(context).apply {
            setBackgroundColor(UiHelper.COLOR_BORDER)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, UiHelper.dp(context, 1))
        })

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(UiHelper.dp(context, 6), UiHelper.dp(context, 5), UiHelper.dp(context, 6), UiHelper.dp(context, 7))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                UiHelper.dp(context, 58)
            )
        }

        data class NavItem(val key: String, val icon: Int, val label: String)
        val navItems = listOf(
            NavItem("home", R.drawable.nav_home, "Trang chủ"),
            NavItem("search", R.drawable.nav_search, "Tìm kiếm"),
            NavItem("browse", R.drawable.nav_grid, "Thể loại"),
            NavItem("sources", R.drawable.nav_tv, "Nguồn")
        )

        val accent = accentFor(currentSource)
        for (item in navItems) {
            val active = item.key == currentTab
            val btn = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                setPadding(UiHelper.dp(context, 4), UiHelper.dp(context, 4), UiHelper.dp(context, 4), UiHelper.dp(context, 4))
                background = if (active) {
                    UiHelper.roundedBg(
                        Color.argb(46, Color.red(accent), Color.green(accent), Color.blue(accent)),
                        12,
                        context,
                        Color.argb(120, Color.red(accent), Color.green(accent), Color.blue(accent)),
                        1
                    )
                } else null
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                    leftMargin = UiHelper.dp(context, 2)
                    rightMargin = UiHelper.dp(context, 2)
                }
                setOnClickListener {
                    if (item.key == "sources") onOpenSources() else onSelectTab(item.key)
                }
            }

            btn.addView(ImageView(context).apply {
                setImageResource(item.icon)
                setColorFilter(if (active) accent else UiHelper.COLOR_TEXT_SECONDARY)
                layoutParams = LinearLayout.LayoutParams(UiHelper.dp(context, 21), UiHelper.dp(context, 21))
            })
            btn.addView(TextView(context).apply {
                text = item.label
                setTextColor(if (active) Color.WHITE else UiHelper.COLOR_TEXT_SECONDARY)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
                typeface = if (active) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                setSingleLine(true)
                ellipsize = TextUtils.TruncateAt.END
                gravity = Gravity.CENTER
            })
            row.addView(btn)
        }
        barWrap.addView(row)
        return barWrap
    }

    fun showMobileSourceSheet(
        context: Context,
        currentSource: ContentSource,
        onSwitchSource: (ContentSource) -> Unit,
        onRefresh: () -> Unit,
        onCheckUpdate: () -> Unit
    ) {
        val scroll = ScrollView(context).apply {
            setBackgroundColor(UiHelper.COLOR_SIDEBAR)
        }
        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(UiHelper.dp(context, 18), UiHelper.dp(context, 18), UiHelper.dp(context, 18), UiHelper.dp(context, 18))
        }

        col.addView(TextView(context).apply {
            text = "CHỌN NGUỒN PHIM & CÀI ĐẶT"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, UiHelper.dp(context, 12))
        })

        var dialogRef: AlertDialog? = null

        val sourceEntries = listOf(
            ContentSource.FILM4K to "🎬  Nguồn 1 · Tú Nam (Phim bộ & Lẻ Full HD)",
            ContentSource.AETHER to "⚡  Nguồn 2 · Thúy An (Phim 4K · Sub Việt)",
            ContentSource.KKPHIM to "🍿  Nguồn 3 · Cốm (Kho phim đa dạng)",
            ContentSource.MIRURO to "🌸  Nguồn 4 · Anime Miruro (1080p · Sub Việt & Dịch AI)"
        )

        for ((src, label) in sourceEntries) {
            val selected = src == currentSource
            val accent = accentFor(src)
            val card = TextView(context).apply {
                text = if (selected) "✓  $label" else label
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(UiHelper.dp(context, 14), UiHelper.dp(context, 12), UiHelper.dp(context, 14), UiHelper.dp(context, 12))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = UiHelper.dp(context, 8) }
            }
            UiHelper.makeFocusableButton(
                view = card,
                normalBgColor = if (selected) Color.argb(64, Color.red(accent), Color.green(accent), Color.blue(accent)) else UiHelper.COLOR_CARD,
                focusedBgColor = accent,
                focusedStrokeColor = Color.WHITE,
                radiusDp = 12,
                scaleOnFocus = 1.02f
            ) {
                dialogRef?.dismiss()
                onSwitchSource(src)
            }
            col.addView(card)
        }

        col.addView(TextView(context).apply {
            text = "TIỆN ÍCH"
            setTextColor(UiHelper.COLOR_TEXT_SECONDARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(UiHelper.dp(context, 2), UiHelper.dp(context, 8), 0, UiHelper.dp(context, 8))
        })

        val utilityActions = listOf(
            "🔄  Làm mới trang chủ" to onRefresh,
            "🚀  Kiểm tra cập nhật (${BuildConfig.VERSION_NAME})" to onCheckUpdate
        )
        for ((label, action) in utilityActions) {
            val btn = TextView(context).apply {
                text = label
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(UiHelper.dp(context, 14), UiHelper.dp(context, 11), UiHelper.dp(context, 14), UiHelper.dp(context, 11))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = UiHelper.dp(context, 7) }
            }
            UiHelper.makeFocusableButton(
                view = btn,
                normalBgColor = UiHelper.COLOR_CARD,
                focusedBgColor = UiHelper.COLOR_SURFACE_HIGH,
                focusedStrokeColor = Color.WHITE,
                radiusDp = 12,
                scaleOnFocus = 1.02f
            ) {
                dialogRef?.dismiss()
                action()
            }
            col.addView(btn)
        }

        scroll.addView(col)
        dialogRef = AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setView(scroll)
            .setNegativeButton("Đóng", null)
            .create()
        dialogRef.show()
    }

    fun createFeaturedHero(
        context: Context,
        featuredItems: List<Anime>,
        featuredIndex: Int,
        isCompact: Boolean,
        onOpenDetail: (Anime) -> Unit,
        onNextFeatured: () -> Unit
    ): View {
        val safeIdx = featuredIndex.coerceIn(0, featuredItems.size - 1)
        val current = featuredItems[safeIdx]
        val accent = accentFor(current.source)

        val heroFrame = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                UiHelper.dp(context, if (isCompact) 380 else 330)
            ).apply {
                bottomMargin = UiHelper.dp(context, if (isCompact) 14 else 16)
            }
            background = UiHelper.roundedBg(UiHelper.COLOR_CARD, 18, context, Color.parseColor("#38FF2A36"), 1)
            outlineProvider = ViewOutlineProvider.BACKGROUND
            clipToOutline = true
        }

        val backdropImage = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            alpha = 0.74f
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        ImageLoader.load(backdropImage, current.bannerUrl ?: current.imageUrl, maxWidth = 720, maxHeight = 420)
        heroFrame.addView(backdropImage)

        val gradientOverlay = View(context).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(
                    Color.parseColor("#24060608"),
                    Color.parseColor("#99060608"),
                    Color.parseColor("#F8060608")
                )
            )
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        heroFrame.addView(gradientOverlay)

        val outerCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                UiHelper.dp(context, if (isCompact) 14 else 22),
                UiHelper.dp(context, if (isCompact) 12 else 14),
                UiHelper.dp(context, if (isCompact) 14 else 22),
                UiHelper.dp(context, if (isCompact) 14 else 14)
            )
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // Top tag + slide indicator
        val topTagRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        topTagRow.addView(
            UiHelper.createBadge(
                context,
                "🔥 ĐỀ CỬ HÔM NAY",
                Color.argb(215, Color.red(accent), Color.green(accent), Color.blue(accent)),
                Color.WHITE,
                radiusDp = 999
            )
        )
        topTagRow.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        })
        if (featuredItems.size > 1 && isCompact) {
            val slideBtn = UiHelper.createBadge(
                context,
                "${safeIdx + 1}/${featuredItems.size}  ❯",
                Color.parseColor("#99141216"),
                Color.WHITE,
                radiusDp = 999
            ).apply {
                isClickable = true
                setOnClickListener { onNextFeatured() }
            }
            topTagRow.addView(slideBtn)
        }
        outerCol.addView(topTagRow)

        outerCol.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(1, 0, 1f)
        })

        val contentRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM or Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val infoStack = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val titleView = TextView(context).apply {
            text = current.title
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (isCompact) 28f else 32f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.START
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        infoStack.addView(titleView)

        val subText = current.subtitle.ifBlank { current.info }
        if (subText.isNotBlank() && !isCompact) {
            infoStack.addView(TextView(context).apply {
                text = subText
                setTextColor(Color.parseColor("#D0D5E4"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                gravity = Gravity.START
                setSingleLine(true)
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, UiHelper.dp(context, 4), 0, 0)
            })
        }

        val badgeRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START
            setPadding(0, UiHelper.dp(context, if (isCompact) 6 else 8), 0, UiHelper.dp(context, if (isCompact) 9 else 11))
        }

        val yearMatch = Regex("""\b(19|20)\d{2}\b""").find(current.info)?.value
        if (yearMatch != null) badgeRow.addView(
            UiHelper.createBadge(context, yearMatch, Color.parseColor("#2BFFFFFF"), Color.WHITE).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { rightMargin = UiHelper.dp(context, 6) }
            }
        )

        if (current.score.isNotBlank()) {
            badgeRow.addView(
                UiHelper.createBadge(context, "★ ${current.score}", UiHelper.COLOR_YELLOW, Color.BLACK).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { rightMargin = UiHelper.dp(context, 6) }
                }
            )
        }

        badgeRow.addView(
            UiHelper.createBadge(context, buildQualityPillText(current), UiHelper.COLOR_BLUE, Color.WHITE)
        )
        infoStack.addView(badgeRow)

        if (current.summary.isNotBlank()) {
            infoStack.addView(TextView(context).apply {
                text = current.summary
                setTextColor(UiHelper.COLOR_TEXT_SECONDARY)
                textSize = 12f
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, 0, 0, UiHelper.dp(context, 12))
            })
        }

        val actionRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START
        }

        val detailBtn = TextView(context).apply {
            text = "▶  Xem ngay"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (isCompact) 13f else 14f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(
                UiHelper.dp(context, if (isCompact) 16 else 24),
                UiHelper.dp(context, if (isCompact) 8 else 10),
                UiHelper.dp(context, if (isCompact) 16 else 24),
                UiHelper.dp(context, if (isCompact) 8 else 10)
            )
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { rightMargin = UiHelper.dp(context, 8) }
        }
        UiHelper.makeFocusableButton(
            view = detailBtn,
            normalBgColor = UiHelper.COLOR_RED,
            focusedBgColor = Color.parseColor("#FF2E38"),
            focusedStrokeColor = Color.WHITE,
            radiusDp = 999,
            scaleOnFocus = 1.05f
        ) {
            onOpenDetail(current)
        }
        actionRow.addView(detailBtn)

        if (featuredItems.size > 1) {
            val nextHeroBtn = TextView(context).apply {
                text = if (isCompact) "Tiếp ❯" else "Đề cử tiếp (${safeIdx + 1}/${featuredItems.size}) ❯"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, if (isCompact) 12.5f else 13f)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(
                    UiHelper.dp(context, if (isCompact) 13 else 18),
                    UiHelper.dp(context, if (isCompact) 8 else 10),
                    UiHelper.dp(context, if (isCompact) 13 else 18),
                    UiHelper.dp(context, if (isCompact) 8 else 10)
                )
            }
            UiHelper.makeFocusableButton(
                view = nextHeroBtn,
                normalBgColor = Color.parseColor("#2AFFFFFF"),
                focusedBgColor = Color.parseColor("#4DFFFFFF"),
                focusedStrokeColor = Color.WHITE,
                radiusDp = 999,
                scaleOnFocus = 1.04f
            ) {
                onNextFeatured()
            }
            actionRow.addView(nextHeroBtn)
        }

        infoStack.addView(actionRow)
        contentRow.addView(infoStack)
        outerCol.addView(contentRow)
        heroFrame.addView(outerCol)
        return heroFrame
    }

    fun createQuickCategoryStrip(
        context: Context,
        sections: List<AnimeSection>,
        currentSource: ContentSource,
        onOpenCategory: (AnimeCategory?) -> Unit
    ): View? {
        val cats = sections.mapNotNull { sec ->
            sec.categoryPath?.takeIf { it.isNotBlank() }?.let { path ->
                AnimeCategory(title = sec.title, path = path)
            }
        }
        if (cats.isEmpty()) return null

        val hScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = UiHelper.dp(context, 14)
            }
        }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val allChip = TextView(context).apply {
            text = "📂 Tất cả thể loại"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
            setSingleLine(true)
            setPadding(UiHelper.dp(context, 13), UiHelper.dp(context, 7), UiHelper.dp(context, 13), UiHelper.dp(context, 7))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { rightMargin = UiHelper.dp(context, 8) }
        }
        UiHelper.makeFocusableButton(
            view = allChip,
            normalBgColor = accentFor(currentSource),
            focusedBgColor = Color.parseColor("#FF2E38"),
            focusedStrokeColor = Color.WHITE,
            radiusDp = 999
        ) {
            onOpenCategory(null)
        }
        row.addView(allChip)

        for (cat in cats.distinctBy { it.path }.take(8)) {
            val chip = TextView(context).apply {
                text = cat.title
                setTextColor(Color.parseColor("#E5E7EB"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                typeface = Typeface.DEFAULT_BOLD
                setSingleLine(true)
                setPadding(UiHelper.dp(context, 13), UiHelper.dp(context, 7), UiHelper.dp(context, 13), UiHelper.dp(context, 7))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { rightMargin = UiHelper.dp(context, 8) }
            }
            UiHelper.makeFocusableButton(
                view = chip,
                normalBgColor = UiHelper.COLOR_CARD,
                focusedBgColor = UiHelper.COLOR_SURFACE_HIGH,
                focusedStrokeColor = Color.WHITE,
                radiusDp = 999
            ) {
                onOpenCategory(cat)
            }
            row.addView(chip)
        }

        hScroll.addView(row)
        return hScroll
    }
}
