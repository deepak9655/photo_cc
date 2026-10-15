package com.strata.editor.ui

import android.graphics.Paint
import android.graphics.RectF
import com.strata.editor.core.BlendMode
import com.strata.editor.core.Colors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * A tiny immediate-mode widget set.
 *
 * Every frame, the panels draw their controls and register where they landed;
 * pointer events are handed to whatever was registered there. That is what keeps
 * the whole interface in one render loop with no view hierarchy to keep in sync —
 * and it means a slider under a finger behaves correctly even while the panel
 * behind it is being redrawn sixty times a second.
 */
class Ui(private val c2: Canvas2D, private val density: Float) {

    enum class Kind { BUTTON, SLIDER, TOGGLE, SCROLL, NONE }

    private class Widget(val id: String, val rect: RectF, val kind: Kind, val onValue: (Float) -> Unit)

    private val widgets = ArrayList<Widget>(64)
    private var next = ArrayList<Widget>(64)
    private var pointerX = 0f
    private var pointerY = 0f
    private var downX = 0f
    private var downY = 0f
    private var down = false
    private var dragging = false
    private var active: Widget? = null
    private var startValue = 0f
    /**
     * How far the panel content is scrolled. Widgets are drawn at logical
     * positions and registered there; a finger arrives in view coordinates, so the
     * hit test adds this back.
     */
    var viewportOffsetY: Float = 0f

    fun beginFrame() {
        next = ArrayList(widgets.size)
    }

    fun endFrame() {
        widgets.clear()
        widgets.addAll(next)
    }

    fun register(id: String, rect: RectF, kind: Kind, onValue: (Float) -> Unit) {
        next.add(Widget(id, RectF(rect), kind, onValue))
    }

    /** The lowest control drawn this frame: how tall the content really is. */
    fun contentBottom(): Float = next.maxOfOrNull { it.rect.bottom } ?: 0f

    /**
     * Feeds one pointer event to whatever was drawn in that spot last frame.
     * Phase: 0 = down, 1 = move, 2 = up.
     *
     * @return true when a control consumed it, so the canvas underneath does not
     *         also react to the same finger.
     */
    fun pointer(x: Float, y: Float, phase: Int): Boolean {
        when (phase) {
            0 -> {
                down = true
                dragging = false
                downX = x
                downY = y
                // Hit-test against the controls as they were drawn last frame: a
                // tap can arrive between two frames, and it still has to land.
                active = widgets.lastOrNull { it.rect.contains(x, y + viewportOffsetY) }
                return active != null
            }
            1 -> {
                if (!down) return false
                if (!dragging && (abs(x - downX) > 6f * density || abs(y - downY) > 6f * density)) dragging = true
                val widget = active ?: return false
                when (widget.kind) {
                    Kind.SLIDER -> {
                        val t = ((x - widget.rect.left) / max(1f, widget.rect.width())).coerceIn(0f, 1f)
                        widget.onValue(t)
                        return true
                    }
                    Kind.SCROLL -> {
                        widget.onValue(y - downY)
                        return true
                    }
                    else -> return dragging
                }
            }
            else -> {
                down = false
                val widget = active
                val wasDragging = dragging
                active = null
                dragging = false
                if (widget == null) return false
                if (!wasDragging && (widget.kind == Kind.BUTTON || widget.kind == Kind.TOGGLE)) widget.onValue(1f)
                return true
            }
        }
        return false
    }

    /** True when a control is under the pointer, so the view can show it as pressed. */
    fun isDown(id: String): Boolean = active?.id == id && dragging.not()

    fun isActive(id: String): Boolean = active?.id == id

    /* ------------------------------------------------------------- controls */

    fun button(
        id: String,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        label: String? = null,
        icon: Icon? = null,
        selected: Boolean = false,
        enabled: Boolean = true,
        tint: Int? = null,
        onClick: () -> Unit,
    ) {
        val rect = RectF(x, y, x + width, y + height)
        val colour = when {
            !enabled -> Palette.surface
            selected -> Palette.accent.copyAlpha(0x2E)
            else -> Palette.surfaceHigh
        }
        c2.card(x, y, x + width, y + height, colour, Palette.dp(10f))
        if (selected) c2.outline(x, y, x + width, y + height, Palette.accent, Palette.dp(10f), Palette.dp(1.5f))
        val contentTint = when {
            !enabled -> Palette.textFaint
            tint != null -> tint
            selected -> Palette.accent
            else -> Palette.textPrimary
        }
        if (icon != null && label != null) {
            val iconSize = min(height * 0.5f, Palette.dp(22f))
            val iconCx = x + width * 0.32f
            Icons.draw(c2, icon, iconCx, y + height / 2f, iconSize, contentTint, max(1.5f, density))
            c2.labelFitted(label, x + width * 0.52f, y + height / 2f + Palette.dp(5f), contentTint, Palette.sp(13f), width * 0.44f)
        } else if (icon != null) {
            Icons.draw(c2, icon, x + width / 2f, y + height / 2f, min(height * 0.52f, Palette.dp(24f)), contentTint, max(1.6f, density * 1.4f))
        } else if (label != null) {
            c2.labelFitted(label, x + width / 2f, y + height / 2f + Palette.dp(5f), contentTint, Palette.sp(13f), width - Palette.dp(12f), Paint.Align.CENTER)
        }
        register(id, rect, Kind.BUTTON) { if (enabled) onClick() }
    }

    /** A labelled slider; `showValue` prints the percentage at the right end. */
    fun slider(
        id: String,
        x: Float,
        y: Float,
        width: Float,
        label: String,
        value: Float,
        minValue: Float = 0f,
        maxValue: Float = 1f,
        showValue: Boolean = true,
        valueLabel: ((Float) -> String)? = null,
        onChange: (Float) -> Unit,
    ) {
        val height = Palette.dp(40f)
        val labelSize = Palette.sp(12f)
        val valueText = valueLabel?.invoke(value) ?: "${((value - minValue) / (maxValue - minValue) * 100).toInt()}%"
        c2.label(label, x, y + labelSize, Palette.textDim, labelSize)
        if (showValue) c2.label(valueText, x + width, y + labelSize, Palette.textPrimary, labelSize, Paint.Align.RIGHT)
        val trackY = y + Palette.dp(26f)
        val trackHeight = Palette.dp(6f)
        c2.card(x, trackY, x + width, trackY + trackHeight, Palette.surfaceMax, trackHeight / 2f)
        val t = ((value - minValue) / max(0.0001f, maxValue - minValue)).coerceIn(0f, 1f)
        c2.card(x, trackY, x + t * width, trackY + trackHeight, Palette.accent, trackHeight / 2f)
        val knobX = x + t * width
        c2.circle(knobX, trackY + trackHeight / 2f, Palette.dp(11f), Palette.textPrimary)
        val rect = RectF(x, y, x + width, trackY + trackHeight + Palette.dp(14f))
        register(id, rect, Kind.SLIDER) { position ->
            onChange(minValue + position * (maxValue - minValue))
        }
    }

    /** A row of small labelled chips; returns nothing, reports the chosen index. */
    fun <T> chips(
        idPrefix: String,
        x: Float,
        y: Float,
        width: Float,
        values: List<T>,
        labels: (T) -> String,
        selectedIndex: Int,
        rowHeight: Float = Palette.dp(38f),
        gap: Float = Palette.dp(6f),
        perRow: Int = 3,
        onSelect: (Int) -> Unit,
    ) {
        values.forEachIndexed { index, value ->
            val row = index / perRow
            val column = index % perRow
            val chipWidth = (width - gap * (perRow - 1)) / perRow
            val chipX = x + column * (chipWidth + gap)
            val chipY = y + row * (rowHeight + gap)
            button(
                "$idPrefix$index",
                chipX,
                chipY,
                chipWidth,
                rowHeight,
                labels(value),
                selected = index == selectedIndex,
                onClick = { onSelect(index) },
            )
        }
    }

    fun toggle(id: String, x: Float, y: Float, width: Float, height: Float, label: String, value: Boolean, onChange: (Boolean) -> Unit) {
        val rect = RectF(x, y, x + width, y + height)
        c2.card(x, y, x + width, y + height, Palette.surfaceHigh, Palette.dp(10f))
        c2.labelFitted(label, x + Palette.dp(12f), y + height / 2f + Palette.dp(5f), Palette.textPrimary, Palette.sp(13f), width - height - Palette.dp(20f))
        val trackWidth = Palette.dp(38f)
        val trackHeight = Palette.dp(22f)
        val trackX = x + width - trackWidth - Palette.dp(10f)
        val trackY = y + (height - trackHeight) / 2f
        c2.card(trackX, trackY, trackX + trackWidth, trackY + trackHeight, if (value) Palette.accent else Palette.surfaceMax, trackHeight / 2f)
        val knobRadius = trackHeight / 2f - Palette.dp(3f)
        val knobX = if (value) trackX + trackWidth - trackHeight / 2f else trackX + trackHeight / 2f
        c2.circle(knobX, trackY + trackHeight / 2f, knobRadius, Palette.textPrimary)
        register(id, rect, Kind.TOGGLE) { onChange(!value) }
    }

    fun section(title: String, x: Float, y: Float, width: Float): Float {
        c2.label(title.uppercase(), x, y + Palette.sp(11f), Palette.textDim, Palette.sp(11f), bold = true)
        return y + Palette.dp(20f)
    }

    fun divider(x: Float, y: Float, width: Float) {
        c2.line(x, y, x + width, y, 0x1FFFFFFF, 1f)
    }

    /** The blend-mode chips, ordered the way they are in the menus. */
    companion object {
        val blendChoices: List<BlendMode> = listOf(
            BlendMode.NORMAL, BlendMode.MULTIPLY, BlendMode.SCREEN, BlendMode.OVERLAY,
            BlendMode.DARKEN, BlendMode.LIGHTEN, BlendMode.COLOR_DODGE, BlendMode.COLOR_BURN,
            BlendMode.HARD_LIGHT, BlendMode.SOFT_LIGHT, BlendMode.DIFFERENCE, BlendMode.EXCLUSION,
            BlendMode.HUE, BlendMode.SATURATION, BlendMode.COLOR, BlendMode.LUMINOSITY,
            BlendMode.SUBTRACT, BlendMode.DIVIDE, BlendMode.LINEAR_DODGE, BlendMode.LINEAR_BURN,
            BlendMode.VIVID_LIGHT, BlendMode.LINEAR_LIGHT, BlendMode.PIN_LIGHT, BlendMode.HARD_MIX,
            BlendMode.DARKER_COLOR, BlendMode.LIGHTER_COLOR, BlendMode.DISSOLVE, BlendMode.PASS_THROUGH,
        )

        fun blendLabel(mode: BlendMode): String = mode.name.split('_').joinToString(" ") { word ->
            word.lowercase().replaceFirstChar { it.uppercase() }
        }
    }
}

/** Adds an alpha to a colour, for soft fills. */
internal fun Int.copyAlpha(alpha: Int): Int =
    Colors.pack(alpha, Colors.red(this), Colors.green(this), Colors.blue(this))
