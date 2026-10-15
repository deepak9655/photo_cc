package com.photocc.app.platform

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import com.photocc.app.core.Argb
import com.photocc.app.core.Rect
import com.photocc.app.core.TextData
import com.photocc.app.core.TextEngine
import com.photocc.app.core.TextLayer
import com.photocc.app.core.TextMeasurer
import com.photocc.app.core.TextRasterizer

/**
 * Text on Android.
 *
 * The engine never touches `android.graphics`: measuring and rasterising go
 * through the two hooks in `TextEngine`, and this file is the Android side of
 * that seam. The JVM self-test installs an AWT implementation instead, so the
 * same layout code is exercised on both.
 *
 * Everything here is deliberately allocation-light: one `Paint` is reused for
 * measurement, and rasterising goes straight into the caller's pixel buffer
 * (no intermediate `Bitmap` per line, no per-frame font lookups).
 */
object AndroidText {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private val typefaceCache = HashMap<String, Typeface>(16)

    /** Installs the Android measurer/rasteriser. Call once, from the Activity. */
    fun install() {
        TextEngine.measurer = Measurer
        TextEngine.rasterizer = Rasterizer
    }

    /* ------------------------------------------------------------------ fonts */

    private fun typeface(style: TextData): Typeface {
        val bold = style.fauxBold
        val italic = style.fauxItalic
        val key = "${style.fontFamily}|$bold|$italic"
        typefaceCache[key]?.let { return it }
        val base = try {
            Typeface.create(style.fontFamily, Typeface.NORMAL)
        } catch (_: Exception) {
            Typeface.SANS_SERIF
        }
        // Ask for a real bold/italic cut first: the OEM either has it or silently
        // substitutes, which is what a desktop viewer does for a missing font too.
        var tf = base
        tf = if (bold || italic) {
            Typeface.create(base, (if (bold) Typeface.BOLD else 0) or (if (italic) Typeface.ITALIC else 0))
        } else {
            base
        }
        if (tf == Typeface.DEFAULT) tf = Typeface.SANS_SERIF
        typefaceCache[key] = tf
        return tf
    }

    /** Letter spacing: PSD tracking is 1/1000 em, `Paint` wants em. */
    private fun applyTracking(paint: Paint, style: TextData) {
        paint.letterSpacing = style.tracking / 1000f
    }

    private fun prepare(paint: Paint, style: TextData): Paint {
        paint.reset()
        paint.isAntiAlias = true
        paint.typeface = typeface(style)
        paint.textSize = style.size
        applyTracking(paint, style)
        return paint
    }

    /* ---------------------------------------------------------------- measurer */

    private object Measurer : TextMeasurer {
        override fun width(text: String, style: TextData): Float {
            prepare(paint, style)
            return paint.measureText(text)
        }

        override fun ascent(style: TextData): Float {
            prepare(paint, style)
            return -paint.fontMetrics.ascent
        }

        override fun descent(style: TextData): Float {
            prepare(paint, style)
            return paint.fontMetrics.descent
        }
    }

    /* -------------------------------------------------------------- rasteriser */

    private object Rasterizer : TextRasterizer {

        override fun rasterize(layer: TextLayer, rect: Rect, out: IntArray) {
            if (rect.w <= 0 || rect.h <= 0 || out.size < rect.w * rect.h) return
            java.util.Arrays.fill(out, 0, rect.w * rect.h, 0)
            val style = layer.text
            val geometry = com.photocc.app.core.TextGeometry.measure(layer)
            if (geometry.lines.isEmpty()) return

            prepare(paint, style)
            paint.color = style.color
            if (style.underline) paint.isUnderlineText = true

            val bitmap = Bitmap.createBitmap(rect.w, rect.h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            // A soft shadow is a normal PSD effect and cheap to approximate while
            // the text is being edited; the file itself keeps the original style.
            if (layer.shadow.enabled) {
                val shadow = Paint(paint)
                shadow.color = layer.shadow.color
                shadow.setShadowLayer(layer.shadow.blur, layer.shadow.dx, layer.shadow.dy, layer.shadow.color)
                var baseline = geometry.firstBaseline
                for (line in geometry.lines) {
                    if (line.text.isNotEmpty()) {
                        canvas.drawText(line.text, lineLeft(line.width, geometry, style) - rect.x, baseline - rect.y, shadow)
                    }
                    baseline += geometry.lineHeight
                }
            }
            var baseline = geometry.firstBaseline
            for (line in geometry.lines) {
                if (line.text.isNotEmpty()) {
                    canvas.drawText(line.text, lineLeft(line.width, geometry, style) - rect.x, baseline - rect.y, paint)
                }
                baseline += geometry.lineHeight
            }
            bitmap.getPixels(out, 0, rect.w, 0, 0, rect.w, rect.h)
            bitmap.recycle()
        }

        /** Alignment inside the text block the layer had when it was imported. */
        private fun lineLeft(lineWidth: Float, geometry: com.photocc.app.core.TextGeometry, style: TextData): Float {
            val boxWidth = if (style.align == TextData.Align.LEFT) return geometry.x else geometry.width
            return when (style.align) {
                TextData.Align.LEFT -> geometry.x
                TextData.Align.CENTER -> geometry.x + (boxWidth - lineWidth) / 2f
                TextData.Align.RIGHT -> geometry.x + (boxWidth - lineWidth)
            }
        }
    }

    /* --------------------------------------------------------------- utilities */

    /** Straight-alpha ARGB pixels for a document region, ready for a `Bitmap`. */
    fun toBitmap(pixels: IntArray, width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }

    fun eyeDropperPixel(pixels: IntArray, index: Int): Int =
        if (index in pixels.indices) pixels[index] else 0

    /** `0xAARRGGBB` from a swatch, for the colour picker. */
    fun swatch(a: Int, r: Int, g: Int, b: Int): Int = Argb.pack(a, r, g, b)
}
