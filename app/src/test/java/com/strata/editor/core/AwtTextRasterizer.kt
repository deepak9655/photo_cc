package com.strata.editor.core

import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.font.FontRenderContext
import java.awt.font.TextLayout
import java.awt.image.BufferedImage

/**
 * A [TextRasterizer] for the JVM tests, drawing with AWT.
 *
 * The phone uses `android.graphics` with the same contract, so a text layer that
 * measures correctly here measures correctly there.
 */
object AwtTextRasterizer : TextRasterizer {

    /** Registers this rasteriser. Called explicitly, for the same reason as above. */
    @JvmStatic
    fun install(): AwtTextRasterizer {
        LayerRenderer.textRasterizer = this
        return this
    }

    override fun rasterize(content: TextContent, layout: Rect, region: Rect): Pixmap? {
        if (region.isEmpty || content.text.isEmpty()) return Pixmap(region.w, region.h)
        val image = BufferedImage(region.w, region.h, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        graphics.font = font(content)
        graphics.color = java.awt.Color(
            Colors.red(content.color), Colors.green(content.color), Colors.blue(content.color), Colors.alpha(content.color),
        )
        val x = (layout.x - region.x).toFloat()
        val y = (layout.y - region.y + baseline(content)).toFloat()
        for ((index, line) in content.text.split('\n').withIndex()) {
            val width = graphics.fontMetrics.stringWidth(line)
            val offset = when (content.alignment) {
                TextAlign.CENTER -> (layout.w - width) / 2f
                TextAlign.RIGHT -> (layout.w - width).toFloat()
                else -> 0f
            }
            graphics.drawString(line, x + offset, y + index * content.fontSize * content.leading.coerceIn(0.5f, 4f))
        }
        graphics.dispose()
        val pixels = IntArray(region.w * region.h)
        image.getRGB(0, 0, region.w, region.h, pixels, 0, region.w)
        return Pixmap(region.w, region.h, pixels)
    }

    override fun measure(content: TextContent): Rect {
        val font = font(content)
        val context = FontRenderContext(null, true, true)
        var width = 0f
        var height = 0f
        for (line in content.text.split('\n')) {
            val layout = TextLayout(line.ifEmpty { " " }, font, context)
            width = maxOf(width, layout.bounds.width.toFloat())
            height += (layout.ascent + layout.descent + layout.leading).toFloat() * content.leading.coerceIn(0.5f, 4f)
        }
        return Rect(0, 0, maxOf(1, width.toInt() + 2), maxOf(1, height.toInt() + 2))
    }

    override fun families(): List<String> =
        java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames.toList()

    private fun font(content: TextContent): Font {
        val style = (if (content.bold) Font.BOLD else 0) or (if (content.italic) Font.ITALIC else 0)
        val size = content.fontSize.coerceIn(4f, 512f)
        val available = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames
        val family = when {
            content.fontFamily.isBlank() -> Font.SANS_SERIF
            available.any { it.equals(content.fontFamily, ignoreCase = true) } -> content.fontFamily
            else -> Font.SANS_SERIF
        }
        return Font(family, style, size.toInt().coerceAtLeast(1))
    }

    /** Distance from the top of the text box to the first baseline. */
    private fun baseline(content: TextContent): Float {
        val metrics = java.awt.Canvas().getFontMetrics(font(content))
        return metrics.ascent.toFloat()
    }
}
