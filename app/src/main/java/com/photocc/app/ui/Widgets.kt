package com.photocc.app.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * The design system: one palette, one spacing scale, one shape scale, and the
 * handful of controls the app is built from.
 *
 * Everything is drawn in code — no layout XML, no widget library — and every
 * interactive control is at least 48 dp, because this is a phone app used with a
 * thumb, often one-handed, often outdoors. Colours are deliberately warm
 * (amber accent on near-black) and *original*: nothing here is copied from
 * another editor's look.
 */
object Ui {

    /* ------------------------------------------------------------- palette */

    /** Deepest layer, behind the canvas. */
    const val BACKGROUND = 0xFF0F1115.toInt()

    /** Panels and bars. */
    const val SURFACE = 0xFF171A20.toInt()

    /** Raised controls (buttons, chips) sitting on a surface. */
    const val SURFACE_HIGH = 0xFF20242C.toInt()

    /** Pressed/selected fill. */
    const val SURFACE_MAX = 0xFF2A2F38.toInt()

    /** Hairline borders; 10% white reads as an edge on every screen. */
    const val OUTLINE = 0x1AFFFFFF

    const val TEXT = 0xFFF1F3F7.toInt()
    const val TEXT_DIM = 0xFF98A0AD.toInt()
    const val TEXT_FAINT = 0xFF6B7383.toInt()

    /** Brand accent: warm amber. */
    const val ACCENT = 0xFFFF9E3D.toInt()

    /** Accent at 22% — pill fills behind selected tools. */
    const val ACCENT_SOFT = 0x38FF9E3D.toInt()

    /** Ink used on top of the accent. */
    const val ON_ACCENT = 0xFF1A1206.toInt()

    const val INFO = 0xFF63C7FF.toInt()
    const val SUCCESS = 0xFF57D9A3.toInt()
    const val DANGER = 0xFFFF6B6B.toInt()

    /** Behind the canvas; the checkerboard shows through transparent pixels. */
    const val CANVAS_BACKGROUND = 0xFF0A0B0E.toInt()
    const val CHECKER_LIGHT = 0xFF3A3F49.toInt()
    const val CHECKER_DARK = 0xFF2E333C.toInt()

    /* --------------------------------------------------------------- scale */

    const val RADIUS_SM = 10f
    const val RADIUS_MD = 14f
    const val RADIUS_LG = 20f
    const val RADIUS_PILL = 999f

    /** Minimum touch target, in dp (Material's 48 dp rule). */
    const val TOUCH = 48

    fun dp(context: Context, value: Float): Int =
        (value * context.resources.displayMetrics.density).roundToInt()

    /** True when the screen is narrow enough to need compact chrome. */
    fun isCompact(context: Context): Boolean =
        context.resources.configuration.screenWidthDp < 400

    /* --------------------------------------------------------------- type */

    private val MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    fun label(
        context: Context,
        text: String,
        size: Float = 13.5f,
        color: Int = TEXT,
        medium: Boolean = false,
        mono: Boolean = false,
    ): TextView = TextView(context).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setTextColor(color)
        isSingleLine = true
        gravity = Gravity.CENTER_VERTICAL
        typeface = when {
            mono -> Typeface.MONOSPACE
            medium -> MEDIUM
            else -> Typeface.SANS_SERIF
        }
        includeFontPadding = false
    }

    /** Small uppercase caption used above groups of controls. */
    fun sectionLabel(context: Context, text: String): TextView =
        label(context, text.uppercase(), 10.5f, TEXT_FAINT, medium = true).apply {
            letterSpacing = 0.12f
        }

    /* ----------------------------------------------------------- containers */

    fun column(context: Context, gap: Int = 0): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        if (gap > 0) setPadding(gap, gap, gap, gap)
    }

    fun row(context: Context, gap: Int = 0): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        if (gap > 0) setPadding(gap, gap, gap, gap)
    }

    /** A rounded panel: surface fill, hairline border. */
    fun card(context: Context, padding: Int = dp(context, 14f), radius: Float = RADIUS_LG): LinearLayout =
        column(context).apply {
            setPadding(padding, padding, padding, padding)
            background = roundedStroke(SURFACE, OUTLINE, 1f, radius)
        }

    /** The floating bar look used by the top bar and the tool dock. */
    fun floatingBar(context: Context, radius: Float = RADIUS_PILL): LinearLayout =
        row(context).apply {
            background = roundedStroke(SURFACE_HIGH, OUTLINE, 1f, radius)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                elevation = dp(context, 6f).toFloat()
            }
        }

    fun spacer(context: Context, weight: Float = 1f): View = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(0, 1, weight)
    }

    fun gapView(context: Context, width: Int = 0, height: Int = 0): View = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(width, height)
    }

    fun divider(context: Context, vertical: Boolean = false): View = View(context).apply {
        layoutParams = if (vertical) {
            LinearLayout.LayoutParams(dp(context, 1f), LinearLayout.LayoutParams.MATCH_PARENT)
        } else {
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(context, 1f))
        }
        setBackgroundColor(OUTLINE)
    }

    /* ------------------------------------------------------------ backgrounds */

    fun rounded(color: Int, radius: Float = RADIUS_MD): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
    }

    fun roundedStroke(
        color: Int,
        strokeColor: Int,
        strokeWidth: Float,
        radius: Float = RADIUS_MD,
    ): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        setStroke(strokeWidth.roundToInt().coerceAtLeast(1), strokeColor)
        cornerRadius = radius
    }

    /** Pressed-state ripple over a rounded shape; falls back to the plain shape. */
    private fun ripple(context: Context, normal: Int, radius: Float, rippleColor: Int = 0x33FFFFFF): android.graphics.drawable.Drawable {
        val content = roundedStroke(normal, OUTLINE, 1f, radius)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            RippleDrawable(ColorStateList.valueOf(rippleColor), content, null)
        } else {
            content
        }
    }

    /* --------------------------------------------------------------- buttons */

    /**
     * Icon button: 48 dp by default, rounded-square fill so it reads as a button
     * rather than as loose artwork on the bar.
     */
    fun iconButton(
        context: Context,
        glyph: Glyph,
        tint: Int = TEXT,
        sizeDp: Int = TOUCH,
        onClick: () -> Unit,
    ): ImageButton {
        val size = dp(context, sizeDp.toFloat())
        return ImageButton(context).apply {
            layoutParams = LinearLayout.LayoutParams(size, size)
            val pad = dp(context, sizeDp * 0.26f)
            setPadding(pad, pad, pad, pad)
            setImageDrawable(GlyphDrawable(glyph, tint))
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = ripple(context, Color.TRANSPARENT, RADIUS_SM)
            setOnClickListener { onClick() }
        }
    }

    /** Filled, clearly-tappable label button (the primary action in a dialog). */
    fun textButton(
        context: Context,
        text: String,
        accent: Boolean = false,
        glyph: Glyph? = null,
        onClick: () -> Unit,
    ): TextView {
        val button = label(context, text, 14f, if (accent) ON_ACCENT else TEXT, medium = true)
        button.gravity = Gravity.CENTER
        val padH = dp(context, 18f)
        val padV = dp(context, 12f)
        button.setPadding(padH, padV, padH, padV)
        button.minHeight = dp(context, TOUCH.toFloat())
        button.background = ripple(context, if (accent) ACCENT else SURFACE_HIGH, RADIUS_MD)
        if (glyph != null) {
            button.setCompoundDrawablesWithIntrinsicBounds(GlyphDrawable(glyph, if (accent) ON_ACCENT else TEXT), null, null, null)
            button.compoundDrawablePadding = dp(context, 8f)
        }
        button.setOnClickListener { onClick() }
        return button
    }

    /**
     * A wide menu row: icon, title, optional subtitle, chevron-free. Used by the
     * empty state and the sheets — the phone equivalent of a desktop menu.
     */
    fun menuRow(
        context: Context,
        glyph: Glyph,
        title: String,
        subtitle: String? = null,
        accent: Boolean = false,
        onClick: () -> Unit,
    ): LinearLayout {
        val iconBg = if (accent) ACCENT_SOFT else SURFACE_MAX
        val iconTint = if (accent) ACCENT else TEXT
        return row(context, dp(context, 14f)).apply {
            setPadding(dp(context, 14f), dp(context, 10f), dp(context, 14f), dp(context, 10f))
            minimumHeight = dp(context, 60f)
            background = ripple(context, if (accent) SURFACE_HIGH else Color.TRANSPARENT, RADIUS_MD)
            val icon = ImageView(context).apply {
                val box = dp(context, 40f)
                layoutParams = LinearLayout.LayoutParams(box, box)
                setPadding(dp(context, 9f), dp(context, 9f), dp(context, 9f), dp(context, 9f))
                setImageDrawable(GlyphDrawable(glyph, iconTint))
                background = rounded(iconBg, RADIUS_SM)
            }
            addView(icon)
            val texts = column(context).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                addView(label(context, title, 15f, TEXT, medium = true))
                if (subtitle != null) {
                    addView(label(context, subtitle, 12f, TEXT_DIM).apply { isSingleLine = false; maxLines = 2 })
                }
            }
            addView(texts)
            setOnClickListener { onClick() }
        }
    }

    /** Small selectable chip, used for presets and segmented choices. */
    fun chip(context: Context, text: String, selected: Boolean = false, onClick: () -> Unit): TextView {
        val chip = label(context, text, 12.5f, if (selected) ON_ACCENT else TEXT_DIM, medium = true)
        chip.gravity = Gravity.CENTER
        val padH = dp(context, 12f)
        chip.setPadding(padH, dp(context, 9f), padH, dp(context, 9f))
        chip.background = ripple(context, if (selected) ACCENT else SURFACE_MAX, RADIUS_PILL)
        chip.setOnClickListener { onClick() }
        return chip
    }

    /** Themed slider (the platform one is nearly invisible on a dark surface). */
    fun slider(context: Context, from: Int, to: Int, value: Int, onChange: (Int) -> Unit): SeekBar =
        SeekBar(context).apply {
            max = to - from
            progress = value - from
            progressTintList = ColorStateList.valueOf(ACCENT)
            progressBackgroundTintList = ColorStateList.valueOf(SURFACE_MAX)
            thumbTintList = ColorStateList.valueOf(TEXT)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) = onChange(from + progress)
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }

    /**
     * A labelled slider with a live value readout — the workhorse of the tool
     * options strip. Returns the row; use [SliderRow.set] to update it.
     */
    class SliderRow(context: Context, labelText: String, from: Int, to: Int, value: Int, private val format: (Int) -> String, onChange: (Int) -> Unit) :
        LinearLayout(context) {

        private val readout = label(context, format(value), 12f, TEXT, medium = true, mono = true)
        private val bar = slider(context, from, to, value) { v ->
            readout.text = format(v)
            onChange(v)
        }

        init {
            orientation = VERTICAL
            minimumWidth = dp(context, 150f)
            val head = row(context).apply {
                addView(label(context, labelText, 11f, TEXT_DIM, medium = true))
                addView(spacer(context))
                addView(readout)
            }
            addView(head)
            addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(context, 34f)))
        }

        fun set(value: Int) {
            bar.progress = value - bar.min + 0
            readout.text = format(value)
        }
    }

    /* ------------------------------------------------------------- feedback */

    /**
     * A snackbar: floating, rounded, above the dock, auto-dismissing. Far less
     * jarring than a system toast, and it can't be mistaken for a system message.
     */
    fun snack(root: FrameLayout, context: Context, message: String, glyph: Glyph? = null) {
        val existing = root.findViewWithTag<View>("snack")
        if (existing != null) root.removeView(existing)
        val bar = row(context, dp(context, 10f)).apply {
            tag = "snack"
            setPadding(dp(context, 14f), dp(context, 11f), dp(context, 18f), dp(context, 11f))
            background = roundedStroke(SURFACE_MAX, OUTLINE, 1f, RADIUS_PILL)
            elevation = dp(context, 8f).toFloat()
            if (glyph != null) {
                addView(ImageView(context).apply {
                    val box = dp(context, 18f)
                    layoutParams = LinearLayout.LayoutParams(box, box)
                    setImageDrawable(GlyphDrawable(glyph, ACCENT))
                })
            }
            addView(label(context, message, 13f, TEXT).apply { isSingleLine = false; maxLines = 2 })
        }
        val params = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(context, 132f)
            marginStart = dp(context, 16f)
            marginEnd = dp(context, 16f)
        }
        root.addView(bar, params)
        bar.alpha = 0f
        bar.translationY = dp(context, 12f).toFloat()
        bar.animate().alpha(1f).translationY(0f).setDuration(120).start()
        bar.postDelayed({
            bar.animate().alpha(0f).translationY(dp(context, 8f).toFloat()).setDuration(160).withEndAction {
                root.removeView(bar)
            }.start()
        }, 2200)
    }

    /** Lays a view out as a floating overlay in the given corner of a FrameLayout. */
    fun place(view: View, gravity: Int, margin: Int = 0, top: Int = 0, bottom: Int = 0, start: Int = 0, end: Int = 0) {
        view.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            this.gravity = gravity
            marginStart = start
            marginEnd = end
            topMargin = top
            bottomMargin = bottom
        }
    }

    /** Vertical pill of stacked icon buttons, used for the zoom controls. */
    fun stack(context: Context, vararg children: View): LinearLayout = column(context).apply {
        background = roundedStroke(SURFACE_HIGH, OUTLINE, 1f, RADIUS_PILL)
        children.forEach { addView(it) }
    }

    fun marginParams(view: View, start: Int = 0, top: Int = 0, end: Int = 0, bottom: Int = 0): ViewGroup.MarginLayoutParams {
        val params = view.layoutParams as? ViewGroup.MarginLayoutParams ?: LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        params.marginStart = start
        params.topMargin = top
        params.marginEnd = end
        params.bottomMargin = bottom
        view.layoutParams = params
        return params
    }
}
