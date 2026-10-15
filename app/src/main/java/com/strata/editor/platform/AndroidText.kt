package com.strata.editor.platform

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import java.lang.reflect.Array as ReflectArray
import com.strata.editor.core.Colors
import com.strata.editor.core.LayerRenderer
import com.strata.editor.core.Pixmap
import com.strata.editor.core.Rect
import com.strata.editor.core.TextAlign
import com.strata.editor.core.TextContent
import com.strata.editor.core.TextRasterizer

/**
 * Draws text layers with `android.graphics`, which is the only thing on the phone
 * that knows about fonts, hinting and the user's language.
 *
 * The contract with the engine: rasterise gives back a bitmap covering `region`
 * (document coordinates) with the text placed at `layout`, so the compositor can
 * blend it straight in without knowing anything about fonts.
 */
class AndroidTextRasterizer(private val availableFamilies: List<String> = DEFAULT_FAMILIES) : TextRasterizer {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG or Paint.LINEAR_TEXT_FLAG)

    override fun rasterize(content: TextContent, layout: Rect, region: Rect): Pixmap? {
        if (region.w <= 0 || region.h <= 0) return null
        val bitmap = try {
            Bitmap.createBitmap(region.w, region.h, Bitmap.Config.ARGB_8888)
        } catch (error: OutOfMemoryError) {
            return null
        }
        if (content.text.isEmpty()) {
            bitmap.recycle()
            return Pixmap(region.w, region.h)
        }
        val canvas = Canvas(bitmap)
        configure(content)
        val colour = content.color
        paint.color = android.graphics.Color.argb(
            Colors.alpha(colour), Colors.red(colour), Colors.green(colour), Colors.blue(colour),
        )
        val left = (layout.x - region.x).toFloat()
        val top = (layout.y - region.y).toFloat()
        val lineHeight = lineSpacing(content)
        var baseline = top - paint.fontMetrics.ascent
        for (line in content.text.split('\n')) {
            val width = paint.measureText(line)
            val offset = when (content.alignment) {
                TextAlign.CENTER -> (layout.w - width) / 2f
                TextAlign.RIGHT -> layout.w - width
                TextAlign.LEFT -> 0f
            }
            canvas.drawText(line, left + offset, baseline, paint)
            baseline += lineHeight
        }
        val pixmap = Pixmap(region.w, region.h)
        bitmap.getPixels(pixmap.pixels, 0, region.w, 0, 0, region.w, region.h)
        bitmap.recycle()
        return pixmap
    }

    override fun measure(content: TextContent): Rect {
        configure(content)
        val metrics = paint.fontMetrics
        var width = 0f
        var lines = 0
        for (line in content.text.split('\n')) {
            width = maxOf(width, paint.measureText(line.ifEmpty { " " }))
            lines++
        }
        val height = (metrics.descent - metrics.ascent) * lines + lineSpacing(content) * (lines - 1).coerceAtLeast(0)
        return Rect(0, 0, maxOf(1, width.toInt() + 2), maxOf(1, height.toInt() + 2))
    }

    override fun families(): List<String> = availableFamilies

    private fun configure(content: TextContent) {
        paint.textSize = content.fontSize.coerceIn(1f, 2048f)
        paint.typeface = Typeface.create(familyOf(content), styleOf(content))
        paint.textSkewX = 0f
        paint.letterSpacing = content.tracking.coerceIn(-0.5f, 2f)
        paint.isFakeBoldText = false
    }

    private fun lineSpacing(content: TextContent): Float =
        (content.fontSize * content.leading.coerceIn(0.5f, 4f)).coerceAtLeast(1f)

    private fun styleOf(content: TextContent): Int {
        var style = Typeface.NORMAL
        if (content.bold) style = style or Typeface.BOLD
        if (content.italic) style = style or Typeface.ITALIC
        return style
    }

    private fun familyOf(content: TextContent): String {
        val wanted = content.fontFamily.ifBlank { return "sans-serif" }
        val match = availableFamilies.firstOrNull { it.equals(wanted, ignoreCase = true) }
        return match ?: "sans-serif"
    }

    companion object {
        val DEFAULT_FAMILIES = listOf(
            "sans-serif", "sans-serif-light", "sans-serif-thin", "sans-serif-medium",
            "sans-serif-condensed", "sans-serif-black", "serif", "serif-monospace",
            "monospace", "casual", "cursive", "sans-serif-smallcaps",
        )

        /**
         * Adds the phone's own font families to the list the editor offers, so a
         * user can pick a font the device actually has.
         */
        fun systemFamilies(): List<String> {
            val names = LinkedHashSet<String>(DEFAULT_FAMILIES)
            try {
                val typefaceClass = Class.forName("android.graphics.Typeface")
                val systemFonts = Class.forName("android.graphics.fonts.SystemFonts")
                if (android.os.Build.VERSION.SDK_INT < 29) return names.toList()
                val fonts = systemFonts.getMethod("getAvailableFonts").invoke(null) ?: return names.toList()
                val count = ReflectArray.getLength(fonts)
                for (i in 0 until count) {
                    val font = ReflectArray.get(fonts, i)
                    val family = runCatching { font.javaClass.getMethod("getFamily").invoke(font) }.getOrNull() ?: continue
                    val name = runCatching { family.javaClass.getMethod("getName").invoke(family) as? String }.getOrNull()
                    if (!name.isNullOrBlank()) names.add(name)
                }
                val fromTypefaces = runCatching {
                    typefaceClass.getDeclaredField("sSystemFontMap").apply { isAccessible = true }.get(null) as? Map<*, *>
                }.getOrNull()
                fromTypefaces?.keys?.forEach { key -> (key as? String)?.let { names.add(it) } }
            } catch (error: Throwable) {
                // Any missing internal is fine: the built-in list is enough.
            }
            return names.toList()
        }

        fun install(): AndroidTextRasterizer {
            val rasterizer = AndroidTextRasterizer(systemFamilies())
            LayerRenderer.textRasterizer = rasterizer
            return rasterizer
        }
    }
}
