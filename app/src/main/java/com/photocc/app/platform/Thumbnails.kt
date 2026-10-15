package com.photocc.app.platform

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.photocc.app.core.Argb
import com.photocc.app.core.GroupLayer
import com.photocc.app.core.Layer
import com.photocc.app.core.RasterLayer
import com.photocc.app.core.Rect
import com.photocc.app.core.TextLayer
import com.photocc.app.core.layerBounds
import kotlin.math.max
import kotlin.math.min

/**
 * Layer thumbnails.
 *
 * A phone list of 30 layers must not allocate 30 full-size buffers, so a
 * thumbnail is sampled *directly* from the layer: a few hundred pixel reads for
 * a raster layer, and a scaled-down text render for a type layer. Nothing here
 * touches the document.
 */
object Thumbnails {

    private const val MAX_PIXELS = 96

    fun of(layer: Layer, size: Int = MAX_PIXELS): Bitmap? {
        val bounds = layerBounds(layer) ?: return null
        if (bounds.w <= 0 || bounds.h <= 0) return null
        val scale = min(size.toFloat() / bounds.w, size.toFloat() / bounds.h)
        val width = max(1, (bounds.w * scale).toInt())
        val height = max(1, (bounds.h * scale).toInt())
        val out = IntArray(width * height)

        when (layer) {
            is RasterLayer -> sampleRaster(layer, bounds, out, width, height)
            is TextLayer -> sampleText(layer, bounds, out, width, height)
            is GroupLayer -> sampleGroup(layer, bounds, out, width, height)
            else -> return null
        }

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(out, 0, width, 0, 0, width, height)
        return bitmap
    }

    /** Nearest-neighbour read: `width * height` lookups, no temporary buffers. */
    private fun sampleRaster(layer: RasterLayer, bounds: Rect, out: IntArray, width: Int, height: Int) {
        val pixels = layer.pixels ?: return
        val mask = layer.mask?.takeIf { it.bounds.w > 0 && it.bounds.h > 0 }
        for (y in 0 until height) {
            val sourceY = (y + 0.5f) * bounds.h / height
            val py = (bounds.y + sourceY).toInt().coerceIn(bounds.y, bounds.bottom - 1)
            for (x in 0 until width) {
                val sourceX = (x + 0.5f) * bounds.w / width
                val px = (bounds.x + sourceX).toInt().coerceIn(bounds.x, bounds.right - 1)
                val index = (py - layer.y) * pixels.width + (px - layer.x)
                var color = if (index in pixels.data.indices) pixels.data[index] else 0
                if (mask != null) {
                    val mx = px - mask.bounds.x
                    val my = py - mask.bounds.y
                    if (mx in 0 until mask.bounds.w && my in 0 until mask.bounds.h) {
                        val coverage = mask.data[my * mask.bounds.w + mx].toInt() and 0xFF
                        color = Argb.pack(Argb.a(color) * coverage / 255, Argb.r(color), Argb.g(color), Argb.b(color))
                    }
                }
                out[y * width + x] = color
            }
        }
    }

    /** Text is re-rendered small instead of sampled, because a text layer has no pixels. */
    private fun sampleText(layer: TextLayer, bounds: Rect, out: IntArray, width: Int, height: Int) {
        val rasterizer = com.photocc.app.core.TextEngine.rasterizer ?: return
        val scale = width.toFloat() / bounds.w
        val tiny = TextLayer(x = 0f, y = 0f, text = layer.text.copyOf())
        tiny.text.size = (layer.text.size * scale).coerceAtLeast(4f)
        tiny.text.tracking = layer.text.tracking
        val scratch = IntArray(width * height)
        val rect = Rect(0, 0, width, height)
        rasterizer.rasterize(tiny, rect, scratch)
        System.arraycopy(scratch, 0, out, 0, out.size)
    }

    /** Groups get a flat preview: children composited into the thumbnail only. */
    private fun sampleGroup(layer: GroupLayer, bounds: Rect, out: IntArray, width: Int, height: Int) {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        val scale = width.toFloat() / bounds.w
        for (child in layer.children) {
            if (!child.visible) continue
            val childBitmap = of(child, max(16, (max(bounds.w, bounds.h) * scale).toInt())) ?: continue
            val childBounds = layerBounds(child) ?: continue
            val left = (childBounds.x - bounds.x) * scale
            val top = (childBounds.y - bounds.y) * scale
            paint.alpha = (child.opacity * 255).toInt().coerceIn(0, 255)
            canvas.drawBitmap(
                childBitmap,
                null,
                android.graphics.RectF(left, top, left + childBounds.w * scale, top + childBounds.h * scale),
                paint,
            )
            childBitmap.recycle()
        }
        bitmap.getPixels(out, 0, width, 0, 0, width, height)
        bitmap.recycle()
    }

    /** Background for a layer row: checkerboard so transparency is readable. */
    fun checkerPaint(cell: Int = 8): Bitmap {
        val bitmap = Bitmap.createBitmap(cell * 2, cell * 2, Bitmap.Config.ARGB_8888)
        val dark = Color.rgb(48, 50, 56)
        val light = Color.rgb(64, 66, 73)
        val canvas = Canvas(bitmap)
        val paint = Paint()
        paint.color = dark
        canvas.drawRect(0f, 0f, cell * 2f, cell * 2f, paint)
        paint.color = light
        canvas.drawRect(0f, 0f, cell.toFloat(), cell.toFloat(), paint)
        canvas.drawRect(cell.toFloat(), cell.toFloat(), cell * 2f, cell * 2f, paint)
        return bitmap
    }
}
