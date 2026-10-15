package com.strata.editor.ui

import android.graphics.Path
import android.graphics.RectF

/**
 * The icon set, drawn with paths rather than bitmaps.
 *
 * Every glyph is built on a 24×24 grid and scaled to whatever size is asked for,
 * so one definition serves the tool rail, the layer rows and the panels, and
 * nothing gets blurry on a high-density screen. The shapes are deliberately plain
 * geometry — a brush is its tip and its handle, a mask is a filled circle cut by
 * an empty one — which keeps the app's own look consistent and, more to the
 * point, keeps it original.
 */
enum class Icon {
    BRUSH, ERASER, FILL, GRADIENT, TEXT, SHAPE, SELECT, LASSO, WAND,
    CROP, MOVE, TRANSFORM, CLONE, RETOUCH, SMOOTH,
    LAYERS, ADJUST, FILTER, MASK, EFFECTS,
    EYE, EYE_OFF, PLUS, MINUS, TRASH, COPY, FOLDER, GROUP, MORE,
    UNDO, REDO, SAVE, EXPORT, OPEN, CHECK, CLOSE, UP, DOWN,
    COLOUR, SWAP, PICKER, ZOOM, HAND, HISTORY, SLIDERS, RESET, LOCK, LINK,
}

object Icons {

    /**
     * Draws an icon centred on (cx, cy), sized to `size` (the full box), stroked in
     * `colour`. The caller decides whether it is selected and passes the colour for
     * it, which is how the rail shows the active tool.
     */
    fun draw(into: Canvas2D, icon: Icon, cx: Float, cy: Float, size: Float, colour: Int, thickness: Float = size / 12f) {
        val saved = into.save()
        into.translate(cx - size / 2f, cy - size / 2f)
        val s = size / 24f
        val w = (thickness / s).coerceIn(1f, 4f)
        when (icon) {
            Icon.BRUSH -> {
                into.line(7 * s, 19 * s, 12 * s, 10 * s, colour, w * s)
                into.ring(15 * s, 7 * s, 4 * s, colour, w * s)
                into.line(5 * s, 21 * s, 9 * s, 17 * s, colour, w * s * 1.6f)
            }
            Icon.ERASER -> {
                into.outline(4 * s, 12 * s, 15 * s, 21 * s, colour, 2 * s, w * s)
                into.line(4 * s, 17 * s, 15 * s, 17 * s, colour, w * s)
                into.line(15 * s, 21 * s, 21 * s, 21 * s, colour, w * s)
                into.line(16 * s, 7 * s, 20 * s, 11 * s, colour, w * s)
            }
            Icon.FILL -> {
                val path = Path()
                path.moveTo(10 * s, 3 * s)
                path.lineTo(4 * s, 10 * s)
                path.lineTo(11 * s, 17 * s)
                path.lineTo(18 * s, 10 * s)
                path.close()
                into.path(path, colour, w * s, false)
                into.circle(19 * s, 17 * s, 2.6f * s, colour)
            }
            Icon.GRADIENT -> {
                into.outline(3 * s, 4 * s, 21 * s, 20 * s, colour, 1.5f * s, w * s)
                var i = 0
                while (i < 6) {
                    into.line(3 * s, (5 + i * 2.6f) * s, 21 * s, (5 + i * 2.6f) * s, colour, w * s * (1f - i / 6.5f))
                    i++
                }
            }
            Icon.TEXT -> {
                into.line(5 * s, 6 * s, 19 * s, 6 * s, colour, w * s * 1.2f)
                into.line(12 * s, 6 * s, 12 * s, 19 * s, colour, w * s * 1.2f)
                into.line(9 * s, 19 * s, 15 * s, 19 * s, colour, w * s)
            }
            Icon.SHAPE -> {
                into.outline(3 * s, 8 * s, 15 * s, 20 * s, colour, 2 * s, w * s)
                into.ring(16 * s, 9 * s, 5 * s, colour, w * s)
            }
            Icon.SELECT -> {
                into.dashedRect(3 * s, 4 * s, 21 * s, 20 * s, colour, w * s, 2.5f * s)
            }
            Icon.LASSO -> {
                into.ring(12 * s, 10 * s, 6 * s, colour, w * s)
                into.line(8 * s, 15 * s, 7 * s, 20 * s, colour, w * s)
                into.circle(7 * s, 21 * s, 2.2f * s, colour)
            }
            Icon.WAND -> {
                into.line(4 * s, 20 * s, 15 * s, 9 * s, colour, w * s * 1.3f)
                into.line(13 * s, 7 * s, 17 * s, 11 * s, colour, w * s)
                into.line(17 * s, 4 * s, 17 * s, 8 * s, colour, w * s)
                into.line(15 * s, 6 * s, 19 * s, 6 * s, colour, w * s)
            }
            Icon.CROP -> {
                into.line(7 * s, 3 * s, 7 * s, 17 * s, colour, w * s)
                into.line(7 * s, 17 * s, 21 * s, 17 * s, colour, w * s)
                into.line(3 * s, 7 * s, 17 * s, 7 * s, colour, w * s)
                into.line(17 * s, 7 * s, 17 * s, 21 * s, colour, w * s)
            }
            Icon.MOVE -> {
                into.line(12 * s, 3 * s, 12 * s, 21 * s, colour, w * s)
                into.line(3 * s, 12 * s, 21 * s, 12 * s, colour, w * s)
                arrow(into, 12 * s, 3 * s, 0f, -1f, s, colour, w)
                arrow(into, 12 * s, 21 * s, 0f, 1f, s, colour, w)
                arrow(into, 3 * s, 12 * s, -1f, 0f, s, colour, w)
                arrow(into, 21 * s, 12 * s, 1f, 0f, s, colour, w)
            }
            Icon.TRANSFORM -> {
                into.dashedRect(4 * s, 4 * s, 20 * s, 20 * s, colour, w * s, 2.5f * s)
                into.circle(4 * s, 4 * s, 2.4f * s, colour)
                into.circle(20 * s, 4 * s, 2.4f * s, colour)
                into.circle(4 * s, 20 * s, 2.4f * s, colour)
                into.circle(20 * s, 20 * s, 2.4f * s, colour)
            }
            Icon.CLONE -> {
                into.outline(8 * s, 4 * s, 16 * s, 12 * s, colour, 1.5f * s, w * s)
                into.line(12 * s, 12 * s, 12 * s, 20 * s, colour, w * s)
                into.line(9 * s, 17 * s, 15 * s, 17 * s, colour, w * s)
                into.line(6 * s, 20 * s, 18 * s, 20 * s, colour, w * s)
            }
            Icon.RETOUCH -> {
                into.ring(11 * s, 11 * s, 6 * s, colour, w * s)
                into.line(4 * s, 20 * s, 20 * s, 4 * s, colour, w * s)
            }
            Icon.SMOOTH -> {
                val path = Path()
                path.moveTo(3 * s, 15 * s)
                path.cubicTo(8 * s, 15 * s, 8 * s, 8 * s, 12 * s, 8 * s)
                path.cubicTo(16 * s, 8 * s, 16 * s, 15 * s, 21 * s, 15 * s)
                into.path(path, colour, w * s * 1.2f, false)
            }
            Icon.LAYERS -> {
                val a = Path()
                a.moveTo(12 * s, 3 * s); a.lineTo(21 * s, 8 * s); a.lineTo(12 * s, 13 * s); a.lineTo(3 * s, 8 * s); a.close()
                into.path(a, colour, w * s, false)
                into.line(4 * s, 13 * s, 12 * s, 18 * s, colour, w * s)
                into.line(12 * s, 18 * s, 20 * s, 13 * s, colour, w * s)
            }
            Icon.ADJUST -> {
                into.circle(12 * s, 12 * s, 8 * s, colour)
                into.save()
                into.clip(12 * s, 0f, 24 * s, 24 * s, 0f)
                into.circle(12 * s, 12 * s, 8 * s, Palette.surface)
                into.restore()
                into.ring(12 * s, 12 * s, 8 * s, colour, w * s)
            }
            Icon.FILTER -> {
                into.circle(9 * s, 9 * s, 5 * s, colour)
                into.ring(16 * s, 16 * s, 4 * s, colour, w * s)
                into.line(13 * s, 5 * s, 13 * s, 9 * s, colour, w * s)
                into.line(11 * s, 7 * s, 15 * s, 7 * s, colour, w * s)
            }
            Icon.MASK -> {
                into.circle(12 * s, 12 * s, 8 * s, colour)
                into.circle(16 * s, 12 * s, 8 * s, Palette.surface)
                into.ring(12 * s, 12 * s, 8 * s, colour, w * s)
            }
            Icon.EFFECTS -> {
                into.line(12 * s, 3 * s, 12 * s, 8 * s, colour, w * s)
                into.line(12 * s, 16 * s, 12 * s, 21 * s, colour, w * s)
                into.line(4.5f * s, 12 * s, 9 * s, 12 * s, colour, w * s)
                into.line(15 * s, 12 * s, 19.5f * s, 12 * s, colour, w * s)
                into.ring(12 * s, 12 * s, 3 * s, colour, w * s)
            }
            Icon.EYE -> {
                val path = Path()
                path.moveTo(2 * s, 12 * s)
                path.cubicTo(7 * s, 5 * s, 17 * s, 5 * s, 22 * s, 12 * s)
                path.cubicTo(17 * s, 19 * s, 7 * s, 19 * s, 2 * s, 12 * s)
                path.close()
                into.path(path, colour, w * s, false)
                into.circle(12 * s, 12 * s, 2.6f * s, colour)
            }
            Icon.EYE_OFF -> {
                val path = Path()
                path.moveTo(2 * s, 12 * s)
                path.cubicTo(7 * s, 6 * s, 17 * s, 6 * s, 22 * s, 12 * s)
                into.path(path, colour, w * s, false)
                into.line(4 * s, 20 * s, 20 * s, 4 * s, colour, w * s)
            }
            Icon.PLUS -> {
                into.line(12 * s, 5 * s, 12 * s, 19 * s, colour, w * s * 1.2f)
                into.line(5 * s, 12 * s, 19 * s, 12 * s, colour, w * s * 1.2f)
            }
            Icon.MINUS -> into.line(5 * s, 12 * s, 19 * s, 12 * s, colour, w * s * 1.2f)
            Icon.TRASH -> {
                into.line(5 * s, 7 * s, 19 * s, 7 * s, colour, w * s)
                into.outline(7 * s, 7 * s, 17 * s, 20 * s, colour, 1.5f * s, w * s)
                into.line(10 * s, 4 * s, 14 * s, 4 * s, colour, w * s)
                into.line(10 * s, 11 * s, 10 * s, 17 * s, colour, w * s * 0.8f)
                into.line(14 * s, 11 * s, 14 * s, 17 * s, colour, w * s * 0.8f)
            }
            Icon.COPY -> {
                into.outline(4 * s, 4 * s, 15 * s, 15 * s, colour, 2 * s, w * s)
                into.outline(9 * s, 9 * s, 20 * s, 20 * s, colour, 2 * s, w * s)
            }
            Icon.FOLDER -> {
                val path = Path()
                path.moveTo(3 * s, 7 * s)
                path.lineTo(10 * s, 7 * s)
                path.lineTo(12 * s, 9 * s)
                path.lineTo(21 * s, 9 * s)
                path.lineTo(21 * s, 19 * s)
                path.lineTo(3 * s, 19 * s)
                path.close()
                into.path(path, colour, w * s, false)
            }
            Icon.GROUP -> {
                into.outline(4 * s, 4 * s, 20 * s, 20 * s, colour, 2 * s, w * s * 0.8f)
                into.line(9 * s, 4 * s, 9 * s, 9 * s, colour, w * s * 0.8f)
                into.line(4 * s, 9 * s, 9 * s, 9 * s, colour, w * s * 0.8f)
            }
            Icon.MORE -> {
                into.circle(6 * s, 12 * s, 1.9f * s, colour)
                into.circle(12 * s, 12 * s, 1.9f * s, colour)
                into.circle(18 * s, 12 * s, 1.9f * s, colour)
            }
            Icon.UNDO -> {
                val path = Path()
                path.moveTo(4 * s, 12 * s)
                path.cubicTo(4 * s, 6 * s, 14 * s, 5 * s, 18 * s, 9 * s)
                path.cubicTo(21 * s, 12 * s, 20 * s, 18 * s, 15 * s, 20 * s)
                into.path(path, colour, w * s * 1.2f, false)
                into.line(4 * s, 12 * s, 4 * s, 6 * s, colour, w * s)
                into.line(4 * s, 12 * s, 10 * s, 12 * s, colour, w * s)
            }
            Icon.REDO -> {
                val path = Path()
                path.moveTo(20 * s, 12 * s)
                path.cubicTo(20 * s, 6 * s, 10 * s, 5 * s, 6 * s, 9 * s)
                path.cubicTo(3 * s, 12 * s, 4 * s, 18 * s, 9 * s, 20 * s)
                into.path(path, colour, w * s * 1.2f, false)
                into.line(20 * s, 12 * s, 20 * s, 6 * s, colour, w * s)
                into.line(20 * s, 12 * s, 14 * s, 12 * s, colour, w * s)
            }
            Icon.SAVE -> {
                val path = Path()
                path.moveTo(4 * s, 4 * s)
                path.lineTo(16 * s, 4 * s)
                path.lineTo(20 * s, 8 * s)
                path.lineTo(20 * s, 20 * s)
                path.lineTo(4 * s, 20 * s)
                path.close()
                into.path(path, colour, w * s, false)
                into.outline(8 * s, 4 * s, 15 * s, 10 * s, colour, 0f, w * s * 0.8f)
                into.outline(7 * s, 13 * s, 17 * s, 20 * s, colour, 0f, w * s * 0.8f)
            }
            Icon.EXPORT -> {
                into.outline(4 * s, 10 * s, 20 * s, 20 * s, colour, 2 * s, w * s * 0.9f)
                into.line(12 * s, 3 * s, 12 * s, 14 * s, colour, w * s)
                into.line(8 * s, 7 * s, 12 * s, 3 * s, colour, w * s)
                into.line(16 * s, 7 * s, 12 * s, 3 * s, colour, w * s)
            }
            Icon.OPEN -> {
                into.outline(4 * s, 6 * s, 20 * s, 20 * s, colour, 2 * s, w * s)
                into.line(12 * s, 13 * s, 12 * s, 3 * s, colour, w * s)
                into.line(8 * s, 7 * s, 12 * s, 3 * s, colour, w * s)
                into.line(16 * s, 7 * s, 12 * s, 3 * s, colour, w * s)
            }
            Icon.CHECK -> {
                into.line(5 * s, 13 * s, 10 * s, 18 * s, colour, w * s * 1.3f)
                into.line(10 * s, 18 * s, 19 * s, 6 * s, colour, w * s * 1.3f)
            }
            Icon.CLOSE -> {
                into.line(6 * s, 6 * s, 18 * s, 18 * s, colour, w * s * 1.2f)
                into.line(18 * s, 6 * s, 6 * s, 18 * s, colour, w * s * 1.2f)
            }
            Icon.UP -> {
                into.line(5 * s, 15 * s, 12 * s, 8 * s, colour, w * s * 1.2f)
                into.line(12 * s, 8 * s, 19 * s, 15 * s, colour, w * s * 1.2f)
            }
            Icon.DOWN -> {
                into.line(5 * s, 9 * s, 12 * s, 16 * s, colour, w * s * 1.2f)
                into.line(12 * s, 16 * s, 19 * s, 9 * s, colour, w * s * 1.2f)
            }
            Icon.COLOUR -> {
                into.ring(12 * s, 12 * s, 8 * s, colour, w * s)
                into.circle(12 * s, 12 * s, 4 * s, colour)
            }
            Icon.SWAP -> {
                into.line(4 * s, 9 * s, 17 * s, 9 * s, colour, w * s)
                into.line(14 * s, 5 * s, 18 * s, 9 * s, colour, w * s)
                into.line(20 * s, 15 * s, 7 * s, 15 * s, colour, w * s)
                into.line(10 * s, 19 * s, 6 * s, 15 * s, colour, w * s)
            }
            Icon.PICKER -> {
                into.line(4 * s, 20 * s, 13 * s, 11 * s, colour, w * s * 1.3f)
                into.ring(16 * s, 8 * s, 3.4f * s, colour, w * s)
            }
            Icon.ZOOM -> {
                into.ring(11 * s, 11 * s, 6 * s, colour, w * s)
                into.line(15.5f * s, 15.5f * s, 21 * s, 21 * s, colour, w * s * 1.2f)
                into.line(11 * s, 8 * s, 11 * s, 14 * s, colour, w * s * 0.8f)
                into.line(8 * s, 11 * s, 14 * s, 11 * s, colour, w * s * 0.8f)
            }
            Icon.HAND -> {
                into.outline(7 * s, 10 * s, 12 * s, 20 * s, colour, 2.5f * s, w * s)
                into.outline(12 * s, 8 * s, 17 * s, 20 * s, colour, 2.5f * s, w * s)
                into.line(8 * s, 10 * s, 8 * s, 5 * s, colour, w * s)
                into.line(17 * s, 12 * s, 17 * s, 6 * s, colour, w * s)
            }
            Icon.HISTORY -> {
                into.ring(12 * s, 12 * s, 8 * s, colour, w * s)
                into.line(12 * s, 7 * s, 12 * s, 12 * s, colour, w * s)
                into.line(12 * s, 12 * s, 16 * s, 14 * s, colour, w * s)
            }
            Icon.SLIDERS -> {
                into.line(4 * s, 7 * s, 20 * s, 7 * s, colour, w * s)
                into.line(4 * s, 12 * s, 20 * s, 12 * s, colour, w * s)
                into.line(4 * s, 17 * s, 20 * s, 17 * s, colour, w * s)
                into.circle(9 * s, 7 * s, 2.4f * s, colour)
                into.circle(15 * s, 12 * s, 2.4f * s, colour)
                into.circle(8 * s, 17 * s, 2.4f * s, colour)
            }
            Icon.RESET -> {
                val path = Path()
                path.moveTo(19 * s, 12 * s)
                path.cubicTo(19 * s, 16 * s, 16 * s, 19 * s, 12 * s, 19 * s)
                path.cubicTo(8 * s, 19 * s, 5 * s, 16 * s, 5 * s, 12 * s)
                path.cubicTo(5 * s, 8 * s, 8 * s, 5 * s, 12 * s, 5 * s)
                into.path(path, colour, w * s * 1.2f, false)
                into.line(19 * s, 5 * s, 19 * s, 10 * s, colour, w * s)
                into.line(19 * s, 10 * s, 14 * s, 10 * s, colour, w * s)
            }
            Icon.LOCK -> {
                into.outline(6 * s, 10 * s, 18 * s, 20 * s, colour, 2 * s, w * s)
                into.ring(12 * s, 9 * s, 3.5f * s, colour, w * s)
            }
            Icon.LINK -> {
                into.line(9 * s, 15 * s, 15 * s, 9 * s, colour, w * s)
                into.outline(5 * s, 13 * s, 12 * s, 19 * s, colour, 3 * s, w * s * 0.8f)
                into.outline(12 * s, 5 * s, 19 * s, 11 * s, colour, 3 * s, w * s * 0.8f)
            }
        }
        into.restore()
    }

    private fun arrow(into: Canvas2D, x: Float, y: Float, dx: Float, dy: Float, s: Float, colour: Int, w: Float) {
        val path = Path()
        val size = 3.2f * s
        if (dy != 0f) {
            path.moveTo(x, y)
            path.lineTo(x - size, y - dy * size)
            path.lineTo(x + size, y - dy * size)
        } else {
            path.moveTo(x, y)
            path.lineTo(x - dx * size, y - size)
            path.lineTo(x - dx * size, y + size)
        }
        path.close()
        into.path(path, colour, 0f, true)
    }

    /** The box an icon wants, for hit testing a rail button. */
    fun box(cx: Float, cy: Float, size: Float): RectF = RectF(cx - size / 2f, cy - size / 2f, cx + size / 2f, cy + size / 2f)
}
