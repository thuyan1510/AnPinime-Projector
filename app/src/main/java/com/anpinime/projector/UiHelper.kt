package com.anpinime.projector

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

object UiHelper {
    val COLOR_BG = Color.parseColor("#080A0F")
    val COLOR_SIDEBAR = Color.parseColor("#0F121B")
    val COLOR_CARD = Color.parseColor("#171B28")
    val COLOR_SURFACE_HIGH = Color.parseColor("#1F2538")
    val COLOR_BORDER = Color.parseColor("#1EFFFFFF")
    val COLOR_ACCENT = Color.parseColor("#9333EA")
    val COLOR_BLUE = Color.parseColor("#1D8CF8")
    val COLOR_YELLOW = Color.parseColor("#F5C518")
    val COLOR_RED = Color.parseColor("#E50914")
    val COLOR_TEXT_SECONDARY = Color.parseColor("#A8B0C2")

    fun isCompactPortrait(context: Context): Boolean =
        BuildConfig.IS_PHONE_FLAVOR ||
            context.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT

    fun dp(context: Context, value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            context.resources.displayMetrics
        ).toInt()

    fun setSourceLogo(view: ImageView, isMovie: Boolean) {
        val resource = if (isMovie) R.drawable.logo_movie else R.drawable.logo_anime
        val options = android.graphics.BitmapFactory.Options().apply {
            inJustDecodeBounds = true
            inScaled = false
        }
        android.graphics.BitmapFactory.decodeResource(view.resources, resource, options)
        val target = dp(view.context, 44).coerceAtLeast(1)
        options.inSampleSize = 1
        while (maxOf(options.outWidth, options.outHeight) / options.inSampleSize > target * 2) {
            options.inSampleSize *= 2
        }
        options.inJustDecodeBounds = false
        view.setImageBitmap(android.graphics.BitmapFactory.decodeResource(view.resources, resource, options))
    }

    fun roundedBg(
        fillColor: Int,
        radiusDp: Int,
        context: Context,
        strokeColor: Int = Color.TRANSPARENT,
        strokeWidthDp: Int = 0
    ): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(context, radiusDp).toFloat()
        setColor(fillColor)
        if (strokeWidthDp > 0) {
            setStroke(dp(context, strokeWidthDp), strokeColor)
        }
    }

    /** Small platform spinner; no GIF decoding while buffering video. */
    fun createLoadingIndicator(context: Context, sizeDp: Int = 118): View =
        FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(context, sizeDp), dp(context, sizeDp)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
            addView(ProgressBar(context).apply {
                isIndeterminate = true
                indeterminateTintList = android.content.res.ColorStateList.valueOf(COLOR_ACCENT)
                layoutParams = FrameLayout.LayoutParams(dp(context, 40), dp(context, 40), Gravity.CENTER)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            })
        }

    fun createLoadingBox(
        context: Context,
        isMovie: Boolean,
        message: String,
        sizeDp: Int = 118
    ): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
            )
            addView(createLoadingIndicator(context, sizeDp))
            addView(
                TextView(context).apply {
                    text = message
                    setTextColor(Color.WHITE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    setPadding(0, dp(context, 14), 0, 0)
                }
            )
        }
    }

    /**
     * Makes any view 100% controllable with a TV/Projector Remote D-Pad (and mouse click).
     * Highlights clearly with a high-contrast border and slight scale on focus.
     */
    fun makeFocusableButton(
        view: View,
        normalBgColor: Int = COLOR_CARD,
        focusedBgColor: Int = Color.parseColor("#25293C"),
        focusedStrokeColor: Int = Color.WHITE,
        radiusDp: Int = 10,
        scaleOnFocus: Float = 1.04f,
        onFocusChange: ((Boolean) -> Unit)? = null,
        onClick: () -> Unit
    ) {
        val ctx = view.context
        view.isFocusable = true
        view.isFocusableInTouchMode = false
        view.isClickable = true
        val normalStroke = if (normalBgColor == Color.TRANSPARENT) Color.TRANSPARENT else COLOR_BORDER
        view.background = roundedBg(normalBgColor, radiusDp, ctx, normalStroke, if (normalStroke == Color.TRANSPARENT) 0 else 1)

        view.setOnFocusChangeListener { v, hasFocus ->
            v.background = if (hasFocus) {
                roundedBg(focusedBgColor, radiusDp, ctx, focusedStrokeColor, 2)
            } else {
                roundedBg(normalBgColor, radiusDp, ctx, normalStroke, if (normalStroke == Color.TRANSPARENT) 0 else 1)
            }
            val targetScale = if (hasFocus) scaleOnFocus else 1.0f
            v.scaleX = targetScale
            v.scaleY = targetScale
            v.elevation = if (hasFocus) 8f else 0f
            onFocusChange?.invoke(hasFocus)
        }

        view.setOnClickListener { onClick() }
        view.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                    keyCode == KeyEvent.KEYCODE_ENTER ||
                    keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) view.performClick()
                true
            } else {
                false
            }
        }
    }

    fun createBadge(
        context: Context,
        text: String,
        bgColor: Int,
        textColor: Int = Color.WHITE,
        radiusDp: Int = 999
    ): TextView = TextView(context).apply {
        this.text = text
        setTextColor(textColor)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        typeface = Typeface.DEFAULT_BOLD
        setSingleLine(true)
        setPadding(dp(context, 10), dp(context, 3), dp(context, 10), dp(context, 3))
        background = roundedBg(bgColor, radiusDp, context)
    }

    /**
     * Lightweight, zero-lag Movie/Anime poster card for RecyclerViews on projector.
     */
    fun createPosterCard(context: Context, widthDp: Int = 118, heightDp: Int = 168): FrameLayout {
        val cardWidth = dp(context, widthDp)
        val cardHeight = dp(context, heightDp)
        return FrameLayout(context).apply {
            layoutParams = ViewGroup.MarginLayoutParams(cardWidth, cardHeight + dp(context, 40)).apply {
                rightMargin = dp(context, 10)
                bottomMargin = dp(context, 8)
            }
            setPadding(dp(context, 4), dp(context, 4), dp(context, 4), dp(context, 4))

            val inner = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }

            val posterFrame = FrameLayout(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    cardHeight - dp(context, 8)
                )
                background = roundedBg(COLOR_CARD, 10, context, COLOR_BORDER, 1)
                outlineProvider = ViewOutlineProvider.BACKGROUND
                clipToOutline = true
            }

            val imageView = ImageView(context).apply {
                id = R.id.poster_image
                scaleType = ImageView.ScaleType.CENTER_CROP
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
            posterFrame.addView(imageView)

            val bottomScrim = View(context).apply {
                background = GradientDrawable(
                    GradientDrawable.Orientation.BOTTOM_TOP,
                    intArrayOf(
                        Color.parseColor("#E6080A0F"),
                        Color.parseColor("#4D080A0F"),
                        Color.TRANSPARENT
                    )
                )
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    (cardHeight / 2).coerceAtLeast(dp(context, 48)),
                    Gravity.BOTTOM
                )
            }
            posterFrame.addView(bottomScrim)

            val badgeText = TextView(context).apply {
                id = R.id.poster_badge
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f)
                typeface = Typeface.DEFAULT_BOLD
                setSingleLine(true)
                ellipsize = TextUtils.TruncateAt.END
                setPadding(dp(context, 6), dp(context, 2), dp(context, 6), dp(context, 2))
                background = roundedBg(Color.parseColor("#EBE50914"), 6, context)
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM or Gravity.START
                ).apply {
                    leftMargin = dp(context, 6)
                    bottomMargin = dp(context, 6)
                }
            }
            posterFrame.addView(badgeText)

            val scoreText = TextView(context).apply {
                id = R.id.poster_score
                setTextColor(Color.BLACK)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f)
                typeface = Typeface.DEFAULT_BOLD
                setSingleLine(true)
                setPadding(dp(context, 5), dp(context, 2), dp(context, 5), dp(context, 2))
                background = roundedBg(COLOR_YELLOW, 5, context)
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP or Gravity.END
                ).apply {
                    rightMargin = dp(context, 5)
                    topMargin = dp(context, 5)
                }
            }
            posterFrame.addView(scoreText)

            val titleView = TextView(context).apply {
                id = R.id.poster_title
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                typeface = Typeface.DEFAULT_BOLD
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                setPadding(dp(context, 2), dp(context, 5), dp(context, 2), 0)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }

            inner.addView(posterFrame)
            inner.addView(titleView)
            addView(inner)
        }
    }

    fun bindPosterCard(card: FrameLayout, anime: Anime, onClick: (Anime) -> Unit) {
        val imageView = card.findViewById<ImageView>(R.id.poster_image)
        val badgeView = card.findViewById<TextView>(R.id.poster_badge)
        val scoreView = card.findViewById<TextView>(R.id.poster_score)
        val titleView = card.findViewById<TextView>(R.id.poster_title)

        titleView.text = anime.title
        val badgeParts = listOf(anime.episodeText.trim(), anime.quality.trim())
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
        val badgeStr = badgeParts.joinToString(" · ")
        if (badgeStr.isNotBlank()) {
            badgeView.visibility = View.VISIBLE
            badgeView.text = badgeStr
            badgeView.background = roundedBg(
                if (anime.source.isMovie) Color.parseColor("#EBE50914") else Color.parseColor("#EB7C3AED"),
                6,
                card.context
            )
        } else {
            badgeView.visibility = View.GONE
        }
        val catLabel = anime.catalogLabel?.trim().orEmpty()
        if (anime.score.isNotBlank()) {
            scoreView.visibility = View.VISIBLE
            scoreView.text = "★ ${anime.score}"
            scoreView.setTextColor(Color.BLACK)
            scoreView.background = roundedBg(COLOR_YELLOW, 5, card.context)
        } else if (catLabel.isNotEmpty()) {
            scoreView.visibility = View.VISIBLE
            val shortCatalog = when {
                catLabel.contains("VIP", ignoreCase = true) -> "VIP"
                catLabel.contains("Free1", ignoreCase = true) -> "Free1"
                catLabel.contains("Free2", ignoreCase = true) -> "Free2"
                else -> catLabel
            }
            scoreView.text = shortCatalog
            val isVip = shortCatalog == "VIP"
            scoreView.setTextColor(if (isVip) Color.BLACK else Color.WHITE)
            scoreView.background = roundedBg(
                if (isVip) COLOR_YELLOW else Color.parseColor("#CC1485F5"),
                5,
                card.context
            )
        } else {
            scoreView.visibility = View.GONE
        }

        ImageLoader.load(imageView, anime.imageUrl, maxWidth = 240, maxHeight = 350)
        makeFocusableButton(
            view = card,
            normalBgColor = Color.TRANSPARENT,
            focusedBgColor = Color.parseColor("#2AFFFFFF"),
            focusedStrokeColor = COLOR_ACCENT,
            radiusDp = 10,
            scaleOnFocus = 1.06f
        ) {
            onClick(anime)
        }
    }
}
