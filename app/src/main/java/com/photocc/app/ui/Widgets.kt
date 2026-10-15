package com.photocc.app.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * Small view factory + theme helpers.
 *
 * The whole UI is built in code: no layout XML to inflate (faster start-up, no
 * reflection) and no dependency on any widget library. Everything is sized with
 * touch targets ≥ 44 dp, because this app is meant for a phone held in one hand.
 */
object Ui {

    const val ACCENT = 0xFFFFB74D.toInt()
    const val TEXT = 0xFFE8E8EA.toInt()
    const val TEXT_DIM = 0xFF9AA0A6.toInt()
    const val SURFACE = 0xFF23252B.toInt()
    const val SURFACE_HIGH = 0xFF2C2F36.toInt()
    const val BACKGROUND = 0xFF17181C.toInt()
    const val DANGER = 0xFFEF6C6C.toInt()

    fun dp(context: Context, value: Float): Int = (value * context.resources.displayMetrics.density).roundToInt()

    fun label(context: Context, text: String, size: Float = 13f, color: Int = TEXT): TextView =
        TextView(context).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            isSingleLine = true
            gravity = Gravity.CENTER_VERTICAL
        }

    fun column(context: Context, gap: Int = 0): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        if (gap > 0) setPadding(gap, gap, gap, gap)
    }

    fun row(context: Context, gap: Int = 6): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        if (gap > 0) setPadding(gap, gap, gap, gap)
    }

    fun rounded(color: Int, radius: Float = 14f): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
    }

    fun roundedStroke(color: Int, strokeColor: Int, strokeWidth: Float, radius: Float = 14f): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            setStroke(strokeWidth.roundToInt().coerceAtLeast(1), strokeColor)
            cornerRadius = radius
        }

    /** A 46 dp icon button; the icon is drawn in code (see [GlyphDrawable]). */
    fun iconButton(
        context: Context,
        glyph: Glyph,
        tint: Int = TEXT,
        sizeDp: Int = 46,
        onClick: () -> Unit,
    ): ImageButton {
        val button = ImageButton(context)
        val size = dp(context, sizeDp.toFloat())
        button.layoutParams = LinearLayout.LayoutParams(size, size)
        button.setImageDrawable(GlyphDrawable(glyph, tint))
        button.scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
        val pad = dp(context, 11f)
        button.setPadding(pad, pad, pad, pad)
        button.setBackgroundColor(Color.TRANSPARENT)
        button.setOnClickListener { onClick() }
        return button
    }

    fun textButton(context: Context, text: String, onClick: () -> Unit): TextView =
        label(context, text).apply {
            setPadding(dp(context, 14f), dp(context, 10f), dp(context, 14f), dp(context, 10f))
            background = rounded(SURFACE_HIGH, dp(context, 18f).toFloat())
            setOnClickListener { onClick() }
        }

    fun slider(context: Context, from: Int, to: Int, value: Int, onChange: (Int) -> Unit): SeekBar =
        SeekBar(context).apply {
            max = to - from
            progress = value - from
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    onChange(from + progress)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }

    fun divider(context: Context): View = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(context, 1f))
        setBackgroundColor(0x22FFFFFF)
    }

    fun spacer(context: Context, weight: Float = 1f): View = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(0, 1, weight)
    }
}
