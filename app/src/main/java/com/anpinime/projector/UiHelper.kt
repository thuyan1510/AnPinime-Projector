package com.anpinime.projector

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

object UiHelper {
    val COLOR_BG = Color.parseColor("#08090E")
    val COLOR_SIDEBAR = Color.parseColor("#10121C")
    val COLOR_CARD = Color.parseColor("#171A26")
    val COLOR_ACCENT = Color.parseColor("#A855F7")
    val COLOR_BLUE = Color.parseColor("#1485F5")
    val COLOR_YELLOW = Color.parseColor("#F5C518")
    val COLOR_RED = Color.parseColor("#E53935")
    val COLOR_TEXT_SECONDARY = Color.parseColor("#B0B6C8")

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
        onClick: () -> Unit
    ) {
        val ctx = view.context
        view.isFocusable = true
        view.isFocusableInTouchMode = false
        view.isClickable = true
        view.background = roundedBg(normalBgColor, radiusDp, ctx, Color.parseColor("#26FFFFFF"), 1)

        view.setOnFocusChangeListener { v, hasFocus ->
            v.background = if (hasFocus) {
                roundedBg(focusedBgColor, radiusDp, ctx, focusedStrokeColor, 3)
            } else {
                roundedBg(normalBgColor, radiusDp, ctx, Color.parseColor("#26FFFFFF"), 1)
            }
            val targetScale = if (hasFocus) scaleOnFocus else 1.0f
            v.scaleX = targetScale
            v.scaleY = targetScale
            v.elevation = if (hasFocus) 8f else 0f
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
    fun createPosterCard(context: Context, widthDp: Int = 148, heightDp: Int = 212): FrameLayout {
        val cardWidth = dp(context, widthDp)
        val cardHeight = dp(context, heightDp)
        return FrameLayout(context).apply {
            layoutParams = ViewGroup.MarginLayoutParams(cardWidth, cardHeight + dp(context, 48)).apply {
                rightMargin = dp(context, 14)
                bottomMargin = dp(context, 10)
            }
            setPadding(dp(context, 5), dp(context, 5), dp(context, 5), dp(context, 5))

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
                    cardHeight - dp(context, 10)
                )
                background = roundedBg(COLOR_CARD, 8, context)
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

            val badgeText = TextView(context).apply {
                id = R.id.poster_badge
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                typeface = Typeface.DEFAULT_BOLD
                setSingleLine(true)
                ellipsize = TextUtils.TruncateAt.END
                setPadding(dp(context, 6), dp(context, 2), dp(context, 6), dp(context, 2))
                background = roundedBg(Color.parseColor("#D9E53935"), 5, context)
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
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                typeface = Typeface.DEFAULT_BOLD
                setSingleLine(true)
                setPadding(dp(context, 6), dp(context, 2), dp(context, 6), dp(context, 2))
                background = roundedBg(COLOR_YELLOW, 5, context)
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP or Gravity.END
                ).apply {
                    rightMargin = dp(context, 6)
                    topMargin = dp(context, 6)
                }
            }
            posterFrame.addView(scoreText)

            val titleView = TextView(context).apply {
                id = R.id.poster_title
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                typeface = Typeface.DEFAULT_BOLD
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                setPadding(dp(context, 2), dp(context, 6), dp(context, 2), 0)
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
        val badgeStr = listOf(anime.episodeText, anime.quality).filter { it.isNotBlank() }.joinToString(" · ")
        if (badgeStr.isNotBlank()) {
            badgeView.visibility = View.VISIBLE
            badgeView.text = badgeStr
        } else {
            badgeView.visibility = View.GONE
        }
        if (anime.score.isNotBlank()) {
            scoreView.visibility = View.VISIBLE
            scoreView.text = "★ ${anime.score}"
        } else {
            scoreView.visibility = View.GONE
        }

        ImageLoader.load(imageView, anime.imageUrl, maxWidth = 200, maxHeight = 290)
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
