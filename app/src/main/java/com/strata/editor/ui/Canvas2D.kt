package com.strata.editor.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import com.strata.editor.core.Colors

/**
 * Small drawing helpers, so the panels and the canvas are painted with the same
 * vocabulary: rounded cards, pill buttons, hairlines, and a checkerboard.
 *
 * Everything here takes and returns plain pixel values — the editor view paints in
 * device pixels and converts from dp once, at the edges.
 */
class Canvas2D(canvas: Canvas) {

    private var canvas: Canvas = canvas

    /** Points the helpers at another canvas, so one instance can serve every frame. */
    fun retarget(target: Canvas) {
        canvas = target
    }


    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { isSubpixelText = true }
    private val rect = RectF()

    fun card(left: Float, top: Float, right: Float, bottom: Float, colour: Int, radius: Float) {
        fill.color = colour
        fill.shader = null
        rect.set(left, top, right, bottom)
        canvas.drawRoundRect(rect, radius, radius, fill)
    }

    fun card(left: Float, top: Float, right: Float, bottom: Float, colour: Int, radius: Float, strokeColour: Int, strokeWidth: Float) {
        card(left, top, right, bottom, colour, radius)
        outline(left, top, right, bottom, strokeColour, radius, strokeWidth)
    }

    fun outline(left: Float, top: Float, right: Float, bottom: Float, colour: Int, radius: Float, width: Float) {
        stroke.color = colour
        stroke.strokeWidth = width
        stroke.pathEffect = null
        rect.set(left + width / 2, top + width / 2, right - width / 2, bottom - width / 2)
        canvas.drawRoundRect(rect, radius, radius, stroke)
    }

    fun line(x1: Float, y1: Float, x2: Float, y2: Float, colour: Int, width: Float = 1f) {
        stroke.color = colour
        stroke.strokeWidth = width
        stroke.pathEffect = null
        canvas.drawLine(x1, y1, x2, y2, stroke)
    }

    fun dashedRect(left: Float, top: Float, right: Float, bottom: Float, colour: Int, width: Float, dash: Float) {
        stroke.color = colour
        stroke.strokeWidth = width
        stroke.pathEffect = DashPathEffect(floatArrayOf(dash, dash), 0f)
        rect.set(left, top, right, bottom)
        canvas.drawRect(rect, stroke)
        stroke.pathEffect = null
    }

    fun label(value: String, x: Float, y: Float, colour: Int, size: Float, align: Paint.Align = Paint.Align.LEFT, bold: Boolean = false) {
        text.color = colour
        text.textSize = size
        text.textAlign = align
        text.isFakeBoldText = bold
        canvas.drawText(value, x, y, text)
    }

    /**
     * Draws text fitted to a width: tap targets are sized for fingers, not for
     * whatever a font decides to do, so long names are shortened rather than
     * allowed to run under the next control.
     */
    fun labelFitted(value: String, x: Float, y: Float, colour: Int, size: Float, maxWidth: Float, align: Paint.Align = Paint.Align.LEFT) {
        text.color = colour
        text.textSize = size
        text.textAlign = align
        text.isFakeBoldText = false
        var shown = value
        if (text.measureText(shown) > maxWidth) {
            var end = shown.length
            while (end > 1 && text.measureText(shown.substring(0, end) + "…") > maxWidth) end--
            shown = shown.substring(0, end) + "…"
        }
        canvas.drawText(shown, x, y, text)
    }

    fun alpha(value: Float) {
        val current = fill.alpha
        fill.alpha = (value * 255).toInt().coerceIn(0, 255)
        stroke.alpha = fill.alpha
        text.alpha = fill.alpha
        restoredAlpha = current
    }

    private var restoredAlpha = 255

    fun restoreAlpha() {
        fill.alpha = restoredAlpha
        stroke.alpha = restoredAlpha
        text.alpha = restoredAlpha
    }

    fun save() = canvas.save()

    fun restore() = canvas.restore()

    fun clip(left: Float, top: Float, right: Float, bottom: Float, radius: Float = 0f) {
        rect.set(left, top, right, bottom)
        if (radius <= 0f) canvas.clipRect(rect) else canvas.clipPath(Path().apply { addRoundRect(rect, radius, radius, Path.Direction.CW) })
    }

    fun translate(dx: Float, dy: Float) = canvas.translate(dx, dy)

    /** The transparent-canvas backdrop: two greys, never pure black. */
    fun checkerboard(left: Float, top: Float, right: Float, bottom: Float, cell: Float) {
        fill.shader = null
        fill.color = Palette.checker
        canvas.drawRect(left, top, right, bottom, fill)
        fill.color = Palette.checkerAlt
        var y = top
        var row = 0
        while (y < bottom) {
            var x = left + if (row % 2 == 0) 0f else cell
            while (x < right) {
                canvas.drawRect(x, y, minOf(x + cell, right), minOf(y + cell, bottom), fill)
                x += cell * 2
            }
            y += cell
            row++
        }
    }

    /** Draws a bitmap into a rounded box, for layer thumbnails. */
    fun bitmap(image: android.graphics.Bitmap, left: Float, top: Float, right: Float, bottom: Float, radius: Float) {
        if (image.isRecycled || image.width <= 0 || image.height <= 0) return
        val saved = save()
        clip(left, top, right, bottom, radius)
        fill.shader = null
        fill.color = Palette.checker
        rect.set(left, top, right, bottom)
        canvas.drawRect(rect, fill)
        imagePaint.isFilterBitmap = true
        imagePaint.alpha = 255
        imagePaint.colorFilter = null
        val src = android.graphics.Rect(0, 0, image.width, image.height)
        val dst = RectF(left, top, right, bottom)
        canvas.drawBitmap(image, src, dst, imagePaint)
        restore()
        saved.hashCode()
    }

    private val imagePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    fun gradientBar(left: Float, top: Float, right: Float, bottom: Float, from: Int, to: Int) {
        fill.shader = LinearGradient(left, top, right, top, from, to, Shader.TileMode.CLAMP)
        canvas.drawRect(left, top, right, bottom, fill)
        fill.shader = null
    }

    fun circle(x: Float, y: Float, radius: Float, colour: Int) {
        fill.color = colour
        fill.shader = null
        canvas.drawCircle(x, y, radius, fill)
    }

    fun ring(x: Float, y: Float, radius: Float, colour: Int, width: Float) {
        stroke.color = colour
        stroke.strokeWidth = width
        stroke.pathEffect = null
        canvas.drawCircle(x, y, radius, stroke)
    }

    fun path(path: Path, colour: Int, width: Float = 0f, fillPath: Boolean = true) {
        if (fillPath) {
            fill.color = colour
            fill.shader = null
            canvas.drawPath(path, fill)
        }
        if (width > 0f) {
            stroke.color = colour
            stroke.strokeWidth = width
            stroke.pathEffect = null
            canvas.drawPath(path, stroke)
        }
    }

    /** A colour swatch with a light border, so dark colours stay visible. */
    fun swatch(left: Float, top: Float, right: Float, bottom: Float, colour: Int, radius: Float, selected: Boolean) {
        if (Colors.alpha(colour) < 255) {
            checkerboard(left, top, right, bottom, maxOf(4f, (right - left) / 6f))
        }
        fill.color = colour
        fill.shader = null
        rect.set(left, top, right, bottom)
        canvas.drawRoundRect(rect, radius, radius, fill)
        outline(left, top, right, bottom, if (selected) Palette.accent else 0x33FFFFFF.toInt(), radius, if (selected) Palette.dp(2f) else 1f)
    }
}
