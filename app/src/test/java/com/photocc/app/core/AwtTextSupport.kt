package com.photocc.app.core

import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.font.FontRenderContext
import java.awt.font.LineMetrics
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import kotlin.math.roundToInt

/**
 * Desktop (AWT) text support for the engine self-test.
 *
 * On the phone this role is played by `ui/AndroidTextSupport.kt` using
 * `android.graphics.Paint` / `Typeface`; keeping both behind the same two
 * interfaces is what lets the whole engine — text layout included — be verified
 * on a plain JVM.
 */
object AwtTextSupport {

    private val cache = HashMap<String, Font>(32)

    fun install() {
        TextEngine.measurer = object : TextMeasurer {
            override fun width(text: String, style: TextData): Float =
                font(style).getStringBounds(text, FONT_CONTEXT).width.toFloat()

            override fun ascent(style: TextData): Float = metrics(font(style)).ascent

            override fun descent(style: TextData): Float = metrics(font(style)).descent
        }
        TextEngine.rasterizer = Rasterizer
    }

    private val FONT_CONTEXT = FontRenderContext(null, true, true)

    private fun metrics(font: Font): LineMetrics = font.getLineMetrics("Hg", FONT_CONTEXT)

    fun deviceFamily(psdFamily: String): String = when (psdFamily) {
        "sans-serif", "sans-serif-light", "sans-serif-condensed", "sans-serif-black", "sans-serif-medium" -> Font.SANS_SERIF
        "serif", "serif-monospace" -> if (psdFamily == "serif-monospace") Font.MONOSPACED else Font.SERIF
        "monospace" -> Font.MONOSPACED
        else -> Font.SANS_SERIF
    }

    fun font(style: TextData): Font {
        val resolved = FontMap.psdNameToDevice(style.psdFontName)
        val weight = if (style.fauxBold || resolved.weight >= 700) Font.BOLD else Font.PLAIN
        val slant = if (style.fauxItalic || resolved.italic) Font.ITALIC else Font.PLAIN
        val family = if (style.fontFamily.isBlank()) resolved.family else style.fontFamily
        val key = "$family|$weight|$slant|${style.size}"
        return cache.getOrPut(key) {
            Font(deviceFamily(family), weight or slant, style.size.roundToInt().coerceAtLeast(1))
        }
    }

    private object Rasterizer : TextRasterizer {
        override fun rasterize(layer: TextLayer, rect: Rect, out: IntArray) {
            if (rect.isEmpty) return
            val image = BufferedImage(rect.w, rect.h, BufferedImage.TYPE_INT_ARGB)
            val graphics: Graphics2D = image.createGraphics()
            try {
                graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
                graphics.font = font(layer.text)
                val geometry = TextGeometry.measure(layer)
                val color = layer.text.color
                graphics.color = Color(Argb.r(color), Argb.g(color), Argb.b(color), Argb.a(color))
                val baseline = layer.y + geometry.firstBaseline - rect.y
                var index = 0
                for (line in geometry.lines) {
                    val x = when (layer.text.align) {
                        TextData.Align.LEFT -> layer.x
                        TextData.Align.CENTER -> layer.x + (geometry.width - line.width) / 2f
                        TextData.Align.RIGHT -> layer.x + geometry.width - line.width
                    }
                    if (line.text.isNotEmpty()) {
                        graphics.drawString(line.text, x - rect.x, baseline + index * geometry.lineHeight)
                    }
                    index++
                }
            } finally {
                graphics.dispose()
            }
            val data = (image.raster.dataBuffer as DataBufferInt).data
            for (i in out.indices) out[i] = data[i]
        }
    }
}
