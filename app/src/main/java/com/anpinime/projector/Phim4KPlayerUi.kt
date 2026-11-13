package com.anpinime.projector

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.DrawableRes

/**
 * Phim4K-inspired dark obsidian glass UI builders for PlayerActivity.
 * Optimized equally for Phone touch ergonomics and TV/Projector D-Pad navigation.
 */
object Phim4KPlayerUi {

    const val COLOR_PHIM4K_RED = 0xFFED1C24.toInt()
    const val COLOR_PHIM4K_RED_PRESSED = 0xFFC9181F.toInt()
    const val COLOR_PHIM4K_BLUE = 0xFF1976D2.toInt()
    const val COLOR_ACCENT_CYAN = 0xFF4FC3F7.toInt()
    const val COLOR_GLASS_DARK = 0xEB0B0D12.toInt()
    const val COLOR_GLASS_SURFACE = 0xF511151E.toInt()

    enum class AspectMode(val label: String) {
        FIT("Vừa màn hình"),
        ZOOM("Tràn viền (Crop)"),
        FILL("Kéo giãn full"),
        CINEMA_ZOOM("Phóng to 112%"),
        FIXED_WIDTH("Khớp chiều ngang")
    }

    fun dp(context: Context, value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            context.resources.displayMetrics
        ).toInt()
    }

    fun isCompactPhoneScreen(context: Context): Boolean {
        val dm = context.resources.displayMetrics
        val minSideDp = minOf(dm.widthPixels, dm.heightPixels) / dm.density
        return minSideDp < 540f
    }

    fun createTopBottomScrim(isTop: Boolean): GradientDrawable {
        val colors = if (isTop) {
            intArrayOf(0xD905060A.toInt(), 0x7305060A, Color.TRANSPARENT)
        } else {
            intArrayOf(Color.TRANSPARENT, 0x8005060A.toInt(), 0xEB05060A.toInt())
        }
        return GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, colors)
    }

    fun createGlassPillDrawable(
        context: Context,
        fillColor: Int = 0x8C05060A.toInt(),
        strokeColor: Int = 0x2EFFFFFF,
        cornerDp: Int = 18
    ): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(context, cornerDp).toFloat()
            setColor(fillColor)
            setStroke(maxOf(1, dp(context, 1)), strokeColor)
        }
    }

    fun createGlassPanelDrawable(context: Context): GradientDrawable {
        return GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(0xF506080D.toInt(), 0xF70B0D12.toInt(), 0xFA141821.toInt())
        ).apply {
            setStroke(maxOf(1, dp(context, 1)), 0x33FFFFFF)
        }
    }

    fun createCircleButtonDrawable(
        context: Context,
        isPrimaryCenter: Boolean = false,
        isFeatureActive: Boolean = false
    ): StateListDrawable {
        val strokeThin = maxOf(1, dp(context, 1))
        val strokeFocus = maxOf(2, dp(context, 2))

        fun oval(fill: Int, strokeW: Int, strokeCol: Int) = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(fill)
            if (strokeW > 0) setStroke(strokeW, strokeCol)
        }

        val normalFill = when {
            isPrimaryCenter -> 0x4DFFFFFF
            isFeatureActive -> 0x384FC3F7
            else -> 0x26FFFFFF
        }
        val normalStroke = when {
            isPrimaryCenter -> 0x80FFFFFF.toInt()
            isFeatureActive -> 0xB34FC3F7.toInt()
            else -> 0x33FFFFFF
        }

        val focusedDrawable = oval(COLOR_PHIM4K_RED, strokeFocus, Color.WHITE)
        val pressedDrawable = oval(COLOR_PHIM4K_RED_PRESSED, strokeFocus, Color.WHITE)
        val defaultDrawable = oval(normalFill, strokeThin, normalStroke)

        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), pressedDrawable)
            addState(intArrayOf(android.R.attr.state_focused), focusedDrawable)
            addState(intArrayOf(android.R.attr.state_selected), oval(0x474FC3F7, strokeFocus, COLOR_ACCENT_CYAN))
            addState(intArrayOf(), defaultDrawable)
        }
    }

    fun createControlIconButton(
        context: Context,
        @DrawableRes iconResId: Int,
        sizeDp: Int,
        paddingDp: Int,
        contentDescriptionText: String,
        isPrimaryCenter: Boolean = false,
        onFocused: ((Boolean) -> Unit)? = null
    ): ImageView {
        val sizePx = dp(context, sizeDp)
        val padPx = dp(context, paddingDp)
        return ImageView(context).apply {
            setImageResource(iconResId)
            imageTintList = ColorStateList.valueOf(Color.WHITE)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(padPx, padPx, padPx, padPx)
            background = createCircleButtonDrawable(context, isPrimaryCenter = isPrimaryCenter)
            contentDescription = contentDescriptionText
            isFocusable = true
            isClickable = true
            layoutParams = LinearLayout.LayoutParams(sizePx, sizePx)
            setOnFocusChangeListener { v, hasFocus ->
                val targetScale = if (hasFocus) 1.14f else 1.0f
                v.animate().scaleX(targetScale).scaleY(targetScale).setDuration(110).start()
                onFocused?.invoke(hasFocus)
            }
        }
    }

    fun styleActiveIconButton(button: ImageView, context: Context, isActive: Boolean, isPrimaryCenter: Boolean = false) {
        button.background = createCircleButtonDrawable(
            context = context,
            isPrimaryCenter = isPrimaryCenter,
            isFeatureActive = isActive
        )
        button.imageTintList = ColorStateList.valueOf(
            if (isActive) 0xFFE1F5FE.toInt() else Color.WHITE
        )
    }

    fun createSeekBarProgressDrawable(context: Context): Drawable {
        val radius = dp(context, 4).toFloat()
        fun bar(color: Int) = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(color)
        }

        val bg = bar(0x40FFFFFF)
        val secondary = ClipDrawable(bar(0x80E3F2FD.toInt()), Gravity.START, ClipDrawable.HORIZONTAL)
        val progress = ClipDrawable(bar(COLOR_PHIM4K_BLUE), Gravity.START, ClipDrawable.HORIZONTAL)

        val trackH = dp(context, 4)
        return LayerDrawable(arrayOf(bg, secondary, progress)).apply {
            setId(0, android.R.id.background)
            setId(1, android.R.id.secondaryProgress)
            setId(2, android.R.id.progress)
            for (i in 0..2) {
                setLayerHeight(i, trackH)
                setLayerGravity(i, Gravity.CENTER_VERTICAL)
            }
        }
    }

    fun createSeekBarThumbDrawable(context: Context): Drawable {
        fun dot(sizeDp: Int, fill: Int, strokeDp: Int, strokeCol: Int) = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setSize(dp(context, sizeDp), dp(context, sizeDp))
            setColor(fill)
            if (strokeDp > 0) setStroke(dp(context, strokeDp), strokeCol)
        }
        return StateListDrawable().apply {
            addState(
                intArrayOf(android.R.attr.state_pressed),
                dot(18, Color.WHITE, 2, COLOR_PHIM4K_RED)
            )
            addState(
                intArrayOf(android.R.attr.state_focused),
                dot(18, Color.WHITE, 2, COLOR_PHIM4K_RED)
            )
            addState(
                intArrayOf(),
                dot(13, Color.WHITE, 1, 0x66000000)
            )
        }
    }

    fun createGlassChipDrawable(context: Context, isSelected: Boolean): StateListDrawable {
        val radius = dp(context, 16).toFloat()
        val stroke = maxOf(1, dp(context, 1))
        val strokeFocus = maxOf(2, dp(context, 2))
        fun rect(fill: Int, sW: Int, sCol: Int) = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(fill)
            setStroke(sW, sCol)
        }
        val baseFill = if (isSelected) 0x334FC3F7 else 0x1EFFFFFF
        val baseStroke = if (isSelected) 0xCC4FC3F7.toInt() else 0x33FFFFFF
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), rect(0xFFE6EEF8.toInt(), strokeFocus, COLOR_ACCENT_CYAN))
            addState(intArrayOf(android.R.attr.state_focused), rect(0xFFF2FFFFFF.toInt(), strokeFocus, Color.WHITE))
            addState(intArrayOf(), rect(baseFill, stroke, baseStroke))
        }
    }

    fun createGlassCardDrawable(context: Context, isActive: Boolean, cornerDp: Int = 10): StateListDrawable {
        val radius = dp(context, cornerDp).toFloat()
        val stroke = maxOf(1, dp(context, 1))
        val strokeFocus = maxOf(2, dp(context, 2))
        fun rect(fill: Int, sW: Int, sCol: Int) = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(fill)
            setStroke(sW, sCol)
        }
        val baseFill = if (isActive) 0x2E4FC3F7 else 0x16FFFFFF
        val baseStroke = if (isActive) 0xAA4FC3F7.toInt() else 0x24FFFFFF
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), rect(0x47ED1C24, strokeFocus, Color.WHITE))
            addState(intArrayOf(android.R.attr.state_focused), rect(0x3BED1C24, strokeFocus, Color.WHITE))
            addState(intArrayOf(), rect(baseFill, stroke, baseStroke))
        }
    }

    fun createGlassChip(
        context: Context,
        label: String,
        isSelected: Boolean,
        onClick: () -> Unit
    ): TextView {
        val hPad = dp(context, 14)
        val vPad = dp(context, 7)
        return TextView(context).apply {
            text = label
            textSize = 12.5f
            typeface = Typeface.create("sans-serif-medium", if (isSelected) Typeface.BOLD else Typeface.NORMAL)
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(hPad, vPad, hPad, vPad)
            setTextColor(if (isSelected) COLOR_ACCENT_CYAN else Color.WHITE)
            background = createGlassChipDrawable(context, isSelected)
            isFocusable = true
            isClickable = true
            setOnFocusChangeListener { v, hasFocus ->
                val tv = v as TextView
                tv.setTextColor(
                    when {
                        hasFocus -> 0xFF0B0D12.toInt()
                        isSelected -> COLOR_ACCENT_CYAN
                        else -> Color.WHITE
                    }
                )
                val s = if (hasFocus) 1.05f else 1.0f
                v.animate().scaleX(s).scaleY(s).setDuration(90).start()
            }
            setOnClickListener { onClick() }
        }
    }

    fun showGlassListDialog(
        activity: Activity,
        title: String,
        items: List<String>,
        checkedIndex: Int = -1,
        subtitle: String? = null,
        onDismiss: () -> Unit = {},
        onSelect: (Int) -> Unit
    ): AlertDialog? {
        if (activity.isFinishing || activity.isDestroyed) return null

        val dm = activity.resources.displayMetrics
        val compact = isCompactPhoneScreen(activity)
        val dialogWidth = minOf(dp(activity, if (compact) 400 else 480), (dm.widthPixels * 0.86f).toInt())
        val maxListHeight = (dm.heightPixels * 0.62f).toInt()

        lateinit var dialog: AlertDialog

        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xFA121620.toInt(), 0xFA090B10.toInt())
            ).apply {
                cornerRadius = dp(activity, 16).toFloat()
                setStroke(maxOf(1, dp(activity, 1)), 0x3DFFFFFF)
            }
            setPadding(dp(activity, 18), dp(activity, 16), dp(activity, 18), dp(activity, 14))
            layoutParams = FrameLayout.LayoutParams(dialogWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val headerRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(activity, 2), 0, 0, dp(activity, 8))
        }
        val titleCol = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleCol.addView(TextView(activity).apply {
            text = title
            textSize = if (compact) 16f else 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        })
        if (!subtitle.isNullOrBlank()) {
            titleCol.addView(TextView(activity).apply {
                text = subtitle
                textSize = 12f
                setTextColor(0xFFB0C4DE.toInt())
                setPadding(0, dp(activity, 2), 0, 0)
            })
        }
        headerRow.addView(titleCol)

        val closeBtn = createControlIconButton(
            context = activity,
            iconResId = R.drawable.ic_p4k_close,
            sizeDp = 34,
            paddingDp = 8,
            contentDescriptionText = "Đóng"
        ).apply {
            setOnClickListener { dialog.dismiss() }
        }
        headerRow.addView(closeBtn)
        card.addView(headerRow)

        card.addView(View(activity).apply {
            setBackgroundColor(0x26FFFFFF)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, maxOf(1, dp(activity, 1))).apply {
                bottomMargin = dp(activity, 10)
            }
        })

        val scroll = ScrollView(activity).apply {
            isVerticalScrollBarEnabled = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        val listCol = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }

        var focusTarget: View? = null
        items.forEachIndexed { idx, rawLabel ->
            val isChecked = idx == checkedIndex || rawLabel.startsWith("✓")
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = createGlassCardDrawable(activity, isActive = isChecked, cornerDp = 10)
                setPadding(dp(activity, 14), dp(activity, 11), dp(activity, 14), dp(activity, 11))
                isFocusable = true
                isClickable = true
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(activity, 7)
                }
                setOnFocusChangeListener { v, hasFocus ->
                    val s = if (hasFocus) 1.015f else 1.0f
                    v.animate().scaleX(s).scaleY(s).setDuration(80).start()
                }
                setOnClickListener {
                    dialog.dismiss()
                    onSelect(idx)
                }
            }

            val rowText = TextView(activity).apply {
                text = rawLabel
                textSize = if (compact) 13.5f else 15f
                typeface = Typeface.create("sans-serif-medium", if (isChecked) Typeface.BOLD else Typeface.NORMAL)
                setTextColor(if (isChecked) COLOR_ACCENT_CYAN else Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            row.addView(rowText)

            if (isChecked) {
                row.addView(TextView(activity).apply {
                    text = "ĐANG CHỌN"
                    textSize = 10f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(0xFF06131C.toInt())
                    setPadding(dp(activity, 8), dp(activity, 3), dp(activity, 8), dp(activity, 3))
                    background = createGlassPillDrawable(activity, fillColor = COLOR_ACCENT_CYAN, strokeColor = Color.TRANSPARENT, cornerDp = 10)
                })
                if (focusTarget == null) focusTarget = row
            }
            listCol.addView(row)
        }
        scroll.addView(listCol)

        // Cap ScrollView height safely on phone/TV so close/header always stay visible
        val wrapper = FrameLayout(activity).apply {
            addView(scroll)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        scroll.post {
            if (scroll.height > maxListHeight) {
                scroll.layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    maxListHeight
                )
            }
            focusTarget?.let { target ->
                target.requestFocus()
                scroll.post {
                    val offset = (target.top - maxListHeight / 3).coerceAtLeast(0)
                    scroll.scrollTo(0, offset)
                }
            }
        }
        card.addView(wrapper)

        dialog = AlertDialog.Builder(activity)
            .setView(card)
            .create()
        dialog.setOnDismissListener { onDismiss() }
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.65f)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
        dialog.show()
        dialog.window?.setLayout(dialogWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
        return dialog
    }

    fun showGlassMessageDialog(
        activity: Activity,
        title: String,
        message: String,
        buttonLabel: String = "Đóng",
        onDismiss: () -> Unit = {}
    ): AlertDialog? {
        if (activity.isFinishing || activity.isDestroyed) return null

        val dm = activity.resources.displayMetrics
        val dialogWidth = minOf(dp(activity, 460), (dm.widthPixels * 0.86f).toInt())
        lateinit var dialog: AlertDialog

        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xFA121620.toInt(), 0xFA090B10.toInt())
            ).apply {
                cornerRadius = dp(activity, 16).toFloat()
                setStroke(maxOf(1, dp(activity, 1)), 0x3DFFFFFF)
            }
            setPadding(dp(activity, 20), dp(activity, 18), dp(activity, 20), dp(activity, 16))
        }

        card.addView(TextView(activity).apply {
            text = title
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, dp(activity, 8))
        })

        card.addView(TextView(activity).apply {
            text = message
            textSize = 14f
            setLineSpacing(dp(activity, 3).toFloat(), 1.0f)
            setTextColor(0xFFD6E2F0.toInt())
            setPadding(0, 0, 0, dp(activity, 16))
        })

        val okBtn = createGlassChip(activity, buttonLabel, isSelected = true) {
            dialog.dismiss()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.END
            }
            setPadding(dp(activity, 20), dp(activity, 9), dp(activity, 20), dp(activity, 9))
        }
        card.addView(okBtn)

        dialog = AlertDialog.Builder(activity)
            .setView(card)
            .create()
        dialog.setOnDismissListener { onDismiss() }
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.65f)
        }
        dialog.show()
        dialog.window?.setLayout(dialogWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
        okBtn.post { okBtn.requestFocus() }
        return dialog
    }
}
