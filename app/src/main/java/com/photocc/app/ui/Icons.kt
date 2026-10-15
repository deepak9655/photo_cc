package com.photocc.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable

/**
 * Toolbar icons, drawn in code.
 *
 * Original artwork (no stock assets, nothing borrowed) and no PNG/vector files:
 * a glyph is a few path commands on a 24×24 grid, scaled to whatever the toolbar
 * asks for. That keeps the APK tiny and lets every icon follow the theme colour
 * and the enabled/disabled state.
 */
enum class Glyph {
    Brush, Eraser, Fill, EyeDropper, Move, Text, Crop, Layers, Group,
    Undo, Redo, Add, Trash, Duplicate, Merge, Flatten, Up, Down,
    Eye, EyeOff, Menu, Save, Open, Export, Info, Fit, Close, Check, Sliders, Image, New,
}

class GlyphDrawable(
    private val glyph: Glyph,
    color: Int = Color.WHITE,
) : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.9f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        this.color = color
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        this.color = color
    }
    private var path = Path()

    /** Kept out of the way of `Drawable.setTint` (API 21+) on purpose. */
    var glyphColor: Int
        get() = paint.color
        set(value) {
            paint.color = value
            fill.color = value
            invalidateSelf()
        }

    /** Icons are per-usage instances; this makes `GlyphDrawable(context, …)` read well. */
    @Suppress("UNUSED_PARAMETER")
    constructor(context: Context, glyph: Glyph, color: Int) : this(glyph, color)

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        fill.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        fill.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java", ReplaceWith("PixelFormat.TRANSLUCENT"))
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    override fun draw(canvas: Canvas) {
        val bounds = bounds
        val size = minOf(bounds.width(), bounds.height()).toFloat()
        if (size <= 0f) return
        canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        val scale = size / 24f
        canvas.scale(scale, scale)
        paint.strokeWidth = 1.9f
        build()
        canvas.drawPath(path, paint)
        if (glyph == Glyph.Brush || glyph == Glyph.Fill || glyph == Glyph.Eraser) drawAccents(canvas)
        canvas.restore()
    }

    private fun drawAccents(canvas: Canvas) {
        when (glyph) {
            Glyph.Brush -> canvas.drawCircle(16.5f, 7.5f, 1.6f, fill)
            Glyph.Eraser -> canvas.drawRect(RectF(4f, 15f, 8f, 19f), fill)
            Glyph.Fill -> canvas.drawRect(RectF(3.5f, 15.5f, 8.5f, 19.5f), fill)
            else -> Unit
        }
    }

    private fun line(x0: Float, y0: Float, x1: Float, y1: Float) {
        path.moveTo(x0, y0)
        path.lineTo(x1, y1)
    }

    private fun rect(x: Float, y: Float, w: Float, h: Float) {
        path.addRect(x, y, x + w, y + h, Path.Direction.CW)
    }

    private fun circle(cx: Float, cy: Float, r: Float) {
        path.addCircle(cx, cy, r, Path.Direction.CW)
    }

    private fun build() {
        path = Path()
        when (glyph) {
            Glyph.Brush -> {
                // A slanted brush with a rounded tip.
                path.moveTo(5f, 19f)
                path.lineTo(6.5f, 14.5f)
                path.lineTo(15f, 6f)
                path.lineTo(18f, 9f)
                path.lineTo(9.5f, 17.5f)
                path.close()
                line(15f, 6f, 18f, 9f)
            }

            Glyph.Eraser -> {
                path.moveTo(4f, 16f)
                path.lineTo(12f, 8f)
                path.lineTo(17f, 13f)
                path.lineTo(9f, 21f)
                path.lineTo(4f, 16f)
                path.close()
                line(4f, 20f, 19f, 20f)
            }

            Glyph.Fill -> {
                // Paint bucket: tilted bucket plus a drip.
                path.moveTo(4f, 12f)
                path.lineTo(11f, 5f)
                path.lineTo(18f, 12f)
                path.lineTo(11f, 19f)
                path.close()
                path.moveTo(19f, 13f)
                path.lineTo(20.5f, 16f)
            }

            Glyph.EyeDropper -> {
                line(5f, 19f, 13f, 11f)
                path.addRoundRect(RectF(12f, 5f, 18f, 11f), 2f, 2f, Path.Direction.CW)
                line(4f, 20f, 6f, 18f)
            }

            Glyph.Move -> {
                line(12f, 4f, 12f, 20f)
                line(4f, 12f, 20f, 12f)
                line(12f, 4f, 9f, 7f)
                line(12f, 4f, 15f, 7f)
                line(12f, 20f, 9f, 17f)
                line(12f, 20f, 15f, 17f)
                line(4f, 12f, 7f, 9f)
                line(4f, 12f, 7f, 15f)
                line(20f, 12f, 17f, 9f)
                line(20f, 12f, 17f, 15f)
            }

            Glyph.Text -> {
                line(5f, 6f, 19f, 6f)
                line(12f, 6f, 12f, 19f)
                line(9f, 19f, 15f, 19f)
            }

            Glyph.Crop -> {
                line(7f, 3f, 7f, 17f)
                line(7f, 17f, 21f, 17f)
                line(3f, 7f, 17f, 7f)
                line(17f, 7f, 17f, 21f)
            }

            Glyph.Layers, Glyph.Flatten -> {
                path.moveTo(12f, 3f)
                path.lineTo(21f, 8f)
                path.lineTo(12f, 13f)
                path.lineTo(3f, 8f)
                path.close()
                path.moveTo(3f, 12f)
                path.lineTo(12f, 17f)
                path.lineTo(21f, 12f)
                if (glyph == Glyph.Flatten) line(3f, 16f, 21f, 16f)
            }

            Glyph.Group -> {
                rect(3f, 3f, 18f, 18f)
                line(3f, 9f, 21f, 9f)
                line(9f, 9f, 9f, 21f)
            }

            Glyph.Undo, Glyph.Redo -> {
                val mirror = glyph == Glyph.Redo
                val x0 = if (mirror) 20f else 4f
                val x1 = if (mirror) 4f else 20f
                val control = if (mirror) 6f else 18f
                path.moveTo(x0, 17f)
                path.cubicTo(control, 17f, control, 6f, x1, 6f)
                line(x1, 6f, x1 + (if (mirror) 3f else -3f), 3.5f)
                line(x1, 6f, x1 + (if (mirror) 3f else -3f), 8.5f)
            }

            Glyph.Add, Glyph.New -> {
                line(12f, 5f, 12f, 19f)
                line(5f, 12f, 19f, 12f)
            }

            Glyph.Trash -> {
                line(5f, 7f, 19f, 7f)
                line(9f, 7f, 9f, 4.5f)
                line(15f, 7f, 15f, 4.5f)
                line(8f, 4.5f, 16f, 4.5f)
                path.moveTo(7f, 7f)
                path.lineTo(8f, 20f)
                path.lineTo(16f, 20f)
                path.lineTo(17f, 7f)
                line(11f, 10f, 11f, 17f)
                line(13.5f, 10f, 13.5f, 17f)
            }

            Glyph.Duplicate -> {
                rect(4f, 4f, 12f, 12f)
                rect(8f, 8f, 12f, 12f)
            }

            Glyph.Merge -> {
                line(12f, 4f, 12f, 13f)
                line(12f, 13f, 8f, 9.5f)
                line(12f, 13f, 16f, 9.5f)
                line(4f, 17f, 20f, 17f)
                line(4f, 20f, 20f, 20f)
            }

            Glyph.Up, Glyph.Down -> {
                val flip = glyph == Glyph.Down
                line(12f, if (flip) 20f else 4f, 12f, if (flip) 4f else 20f)
                val tip = if (flip) 4f else 20f
                line(12f, tip, 8.5f, tip + (if (flip) 3.5f else -3.5f))
                line(12f, tip, 15.5f, tip + (if (flip) 3.5f else -3.5f))
            }

            Glyph.Eye, Glyph.EyeOff -> {
                path.moveTo(3f, 12f)
                path.quadTo(12f, 4f, 21f, 12f)
                path.quadTo(12f, 20f, 3f, 12f)
                circle(12f, 12f, 2.6f)
                if (glyph == Glyph.EyeOff) line(5f, 19f, 19f, 5f)
            }

            Glyph.Menu -> {
                line(4f, 7f, 20f, 7f)
                line(4f, 12f, 20f, 12f)
                line(4f, 17f, 20f, 17f)
            }

            Glyph.Save -> {
                rect(4f, 4f, 16f, 16f)
                rect(8f, 4f, 8f, 6f)
                rect(7f, 13f, 10f, 7f)
            }

            Glyph.Open -> {
                path.moveTo(3f, 7f)
                path.lineTo(10f, 7f)
                path.lineTo(12f, 10f)
                path.lineTo(21f, 10f)
                path.lineTo(21f, 19f)
                path.lineTo(3f, 19f)
                path.close()
            }

            Glyph.Export -> {
                line(12f, 4f, 12f, 15f)
                line(12f, 4f, 8.5f, 7.5f)
                line(12f, 4f, 15.5f, 7.5f)
                line(5f, 15f, 5f, 20f)
                line(5f, 20f, 19f, 20f)
                line(19f, 15f, 19f, 20f)
            }

            Glyph.Info -> {
                circle(12f, 12f, 8.5f)
                line(12f, 11f, 12f, 17f)
                dot(12f, 8f)
            }

            Glyph.Fit -> {
                rect(4f, 4f, 16f, 16f)
                line(4f, 9f, 9f, 9f)
                line(9f, 9f, 9f, 4f)
                line(15f, 20f, 15f, 15f)
                line(15f, 15f, 20f, 15f)
            }

            Glyph.Close -> {
                line(6f, 6f, 18f, 18f)
                line(18f, 6f, 6f, 18f)
            }

            Glyph.Check -> {
                line(5f, 12f, 10f, 17f)
                line(10f, 17f, 19f, 7f)
            }

            Glyph.Sliders -> {
                line(4f, 8f, 20f, 8f)
                line(4f, 16f, 20f, 16f)
                circle(9f, 8f, 2.2f)
                circle(15f, 16f, 2.2f)
            }

            Glyph.Image -> {
                rect(4f, 5f, 16f, 14f)
                circle(9f, 10f, 1.8f)
                path.moveTo(5f, 17f)
                path.lineTo(11f, 12f)
                path.lineTo(19f, 17f)
            }
        }
    }

    /** A small dot (used by the info glyph). */
    private fun dot(x: Float, y: Float) {
        path.addCircle(x, y, 1.2f, Path.Direction.CW)
    }
}
