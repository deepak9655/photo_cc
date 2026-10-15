package com.strata.editor.core

/**
 * A raster buffer of non-premultiplied ARGB pixels.
 *
 * Deliberately plain: an `IntArray` and two dimensions, with bounds-checked
 * access so no operation can corrupt memory outside its own rectangle. Every
 * mutation in the app happens inside a recorded edit (see `History`), which is
 * what makes undo exact.
 */
class Pixmap(val width: Int, val height: Int, val pixels: IntArray) {

    init {
        require(width > 0 && height > 0) { "Pixmap must have a positive size, got ${width}x$height" }
        require(pixels.size >= width * height) { "Pixmap buffer is too small for ${width}x$height" }
    }

    constructor(width: Int, height: Int) : this(width, height, IntArray(width * height))

    val size: Int get() = width * height
    val bounds: Rect get() = Rect(0, 0, width, height)

    fun copy(): Pixmap = Pixmap(width, height, pixels.copyOf())

    operator fun get(x: Int, y: Int): Int =
        if (x >= 0 && y >= 0 && x < width && y < height) pixels[y * width + x] else 0

    operator fun set(x: Int, y: Int, value: Int) {
        if (x >= 0 && y >= 0 && x < width && y < height) pixels[y * width + x] = value
    }

    /** Unsafe-but-fast access, for loops that already clip their rectangle. */
    fun at(x: Int, y: Int): Int = pixels[y * width + x]

    fun fill(color: Int) {
        java.util.Arrays.fill(pixels, color)
    }

    fun clear() = fill(0)

    fun fillRect(rect: Rect, color: Int) {
        val r = rect.intersect(bounds)
        if (r.isEmpty) return
        for (y in r.y until r.bottom) {
            val row = y * width
            java.util.Arrays.fill(pixels, row + r.x, row + r.right, color)
        }
    }

    /** Writes the pixels of `other` at (dx, dy), clipped to this buffer. */
    fun blit(other: Pixmap, dx: Int, dy: Int, alpha: Int = 255) {
        val target = Rect(dx, dy, other.width, other.height).intersect(bounds)
        if (target.isEmpty) return
        for (y in target.y until target.bottom) {
            val sourceRow = (y - dy) * other.width
            val destRow = y * width
            for (x in target.x until target.right) {
                val src = other.pixels[sourceRow + (x - dx)]
                pixels[destRow + x] = if (alpha >= 255) src else Colors.scaleAlpha(src, alpha)
            }
        }
    }

    /** Copies a sub-rectangle out into a new buffer. */
    fun crop(rect: Rect): Pixmap {
        val r = rect.clampTo(width, height)
        if (r.isEmpty) throw IllegalArgumentException("Cannot crop to an empty rectangle")
        val out = Pixmap(r.w, r.h)
        for (y in 0 until r.h) {
            System.arraycopy(pixels, (r.y + y) * width + r.x, out.pixels, y * r.w, r.w)
        }
        return out
    }

    /** Nearest-neighbour sample, clamped at the edges. */
    fun sampleNearest(fx: Float, fy: Float): Int {
        val x = fx.toInt().coerceIn(0, width - 1)
        val y = fy.toInt().coerceIn(0, height - 1)
        return pixels[y * width + x]
    }

    /** Bilinear sample in straight-alpha space (alpha is interpolated too). */
    fun sampleBilinear(fx: Float, fy: Float): Int {
        val x0 = kotlin.math.floor(fx).toInt()
        val y0 = kotlin.math.floor(fy).toInt()
        val tx = fx - x0
        val ty = fy - y0
        val c00 = get(x0, y0)
        val c10 = get(x0 + 1, y0)
        val c01 = get(x0, y0 + 1)
        val c11 = get(x0 + 1, y0 + 1)
        fun lerp(channel: Int): Int {
            val top = ch(c00, channel) + (ch(c10, channel) - ch(c00, channel)) * tx
            val bottom = ch(c01, channel) + (ch(c11, channel) - ch(c01, channel)) * tx
            return (top + (bottom - top) * ty).toInt().coerceIn(0, 255)
        }
        return Colors.pack(lerp(3), lerp(0), lerp(1), lerp(2))
    }

    private fun ch(color: Int, channel: Int): Int = when (channel) {
        0 -> Colors.red(color)
        1 -> Colors.green(color)
        2 -> Colors.blue(color)
        else -> Colors.alpha(color)
    }

    /**
     * Box-filtered downscale into `out` (which may be a region of a bigger
     * buffer). Used by the canvas to show a huge document at screen size.
     */
    fun downsample(out: IntArray, outOffset: Int, outStride: Int, outW: Int, outH: Int, background: Int = 0) {
        if (outW <= 0 || outH <= 0) return
        val scaleX = width.toDouble() / outW
        val scaleY = height.toDouble() / outH
        for (oy in 0 until outH) {
            val sy0 = (oy * scaleY).toInt()
            val sy1 = maxOf(sy0 + 1, minOf(height, ((oy + 1) * scaleY).toInt()))
            for (ox in 0 until outW) {
                val sx0 = (ox * scaleX).toInt()
                val sx1 = maxOf(sx0 + 1, minOf(width, ((ox + 1) * scaleX).toInt()))
                var a = 0L; var r = 0L; var g = 0L; var b = 0L
                var count = 0
                for (y in sy0 until sy1) {
                    val row = y * width
                    for (x in sx0 until sx1) {
                        val p = pixels[row + x]
                        val pa = Colors.alpha(p)
                        // Average in premultiplied space so transparent pixels do not darken edges.
                        a += pa; r += Colors.red(p) * pa; g += Colors.green(p) * pa; b += Colors.blue(p) * pa
                        count++
                    }
                }
                val outIndex = outOffset + oy * outStride + ox
                if (count == 0 || a == 0L) {
                    out[outIndex] = if (count == 0) background else 0
                } else {
                    val alpha = (a / count).toInt().coerceIn(0, 255)
                    out[outIndex] = Colors.pack(
                        alpha,
                        (r / a).toInt().coerceIn(0, 255),
                        (g / a).toInt().coerceIn(0, 255),
                        (b / a).toInt().coerceIn(0, 255),
                    )
                }
            }
        }
    }

    /** Nearest-neighbour resize, for thumbnails where speed matters more. */
    fun scaledTo(w: Int, h: Int): Pixmap {
        val out = Pixmap(maxOf(1, w), maxOf(1, h))
        val sx = width.toFloat() / out.width
        val sy = height.toFloat() / out.height
        for (y in 0 until out.height) {
            val srcRow = (y * sy).toInt().coerceIn(0, height - 1) * width
            for (x in 0 until out.width) {
                out.pixels[y * out.width + x] = pixels[srcRow + (x * sx).toInt().coerceIn(0, width - 1)]
            }
        }
        return out
    }
}
