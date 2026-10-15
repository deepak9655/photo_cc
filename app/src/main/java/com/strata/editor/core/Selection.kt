package com.strata.editor.core

import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * The active selection, stored as an 8-bit coverage mask over the document.
 *
 * A mask (rather than a shape) is what makes a selection composable: a marquee, a
 * lasso and the magic wand all produce one, "add/subtract/intersect" are simple
 * arithmetic on it, feathering is a blur, and every tool just asks
 * [coverageAt] how strongly it may write a pixel.
 */
class Selection(val width: Int, val height: Int) {

    /** 0 = outside the selection, 255 = fully selected. */
    val coverage: ByteArray = ByteArray(width * height)

    /** Cached rectangle around the selected pixels; recomputed when marked dirty. */
    private var cachedBounds: Rect? = null
    private var dirtyBounds = true

    val isActive: Boolean get() = !isEmpty

    val isEmpty: Boolean get() = bounds.isEmpty

    /** Tight bounds of everything with any coverage at all. */
    val bounds: Rect
        get() {
            if (dirtyBounds) {
                var minX = width
                var minY = height
                var maxX = -1
                var maxY = -1
                for (y in 0 until height) {
                    val row = y * width
                    for (x in 0 until width) {
                        if (coverage[row + x].toInt() != 0) {
                            if (x < minX) minX = x
                            if (x > maxX) maxX = x
                            if (y < minY) minY = y
                            if (y > maxY) maxY = y
                        }
                    }
                }
                cachedBounds = if (maxX < 0) Rect.EMPTY else Rect(minX, minY, maxX - minX + 1, maxY - minY + 1)
                dirtyBounds = false
            }
            return cachedBounds ?: Rect.EMPTY
        }

    fun invalidate() {
        dirtyBounds = true
    }

    fun clear() {
        java.util.Arrays.fill(coverage, 0)
        invalidate()
    }

    fun selectAll() {
        java.util.Arrays.fill(coverage, 255.toByte())
        invalidate()
    }

    fun value(x: Int, y: Int): Int =
        if (x < 0 || y < 0 || x >= width || y >= height) 0 else coverage[y * width + x].toInt() and 0xFF

    /** Selection coverage as a 0..1 weight, or 1 when no selection is active. */
    fun coverageAt(x: Int, y: Int): Float {
        if (!isActive) return 1f
        return value(x, y) / 255f
    }

    fun coverageAtPrecise(fx: Float, fy: Float): Float {
        if (!isActive) return 1f
        return coverageAt(fx.toInt(), fy.toInt()) / 255f
    }

    fun copyOf(): Selection {
        val out = Selection(width, height)
        System.arraycopy(coverage, 0, out.coverage, 0, coverage.size)
        return out
    }

    fun replaceWith(other: Selection) {
        System.arraycopy(other.coverage, 0, coverage, 0, coverage.size)
        invalidate()
    }

    /* ------------------------------------------------------------- ops */

    enum class Op { REPLACE, ADD, SUBTRACT, INTERSECT }

    fun combine(other: Selection, op: Op) {
        for (i in coverage.indices) {
            val a = coverage[i].toInt() and 0xFF
            val b = other.coverage[i].toInt() and 0xFF
            val value = when (op) {
                Op.REPLACE -> b
                Op.ADD -> max(a, b)
                Op.SUBTRACT -> (a * (255 - b)) / 255
                Op.INTERSECT -> (a * b) / 255
            }
            coverage[i] = value.toByte()
        }
        invalidate()
    }

    fun invert() {
        for (i in coverage.indices) coverage[i] = (255 - (coverage[i].toInt() and 0xFF)).toByte()
        invalidate()
    }

    /** Softens the edge of the selection, in document pixels. */
    fun feather(radius: Float) {
        if (radius <= 0.1f) return
        val sizes = IntArray(3) { max(1, (radius / 1.5f).toInt()) }
        val temp = ByteArray(coverage.size)
        val source = coverage.copyOf()
        for (size in sizes) {
            boxBlurBytes(source, temp, size)
            System.arraycopy(temp, 0, source, 0, source.size)
        }
        System.arraycopy(source, 0, coverage, 0, coverage.size)
        invalidate()
    }

    /** Grows (+) or shrinks (−) the selection by a few pixels. */
    /**
     * The selection outline as horizontal runs: `[x, y, length]` triples where the
     * coverage changes. The view draws these as marching ants — a run-length walk
     * costs one pass over the mask, and the result is cached until the selection
     * changes, so a full-screen selection is still cheap to draw every frame.
     */
    fun outlinePoints(): IntArray {
        if (isEmpty) return IntArray(0)
        val runs = ArrayList<Int>(256)
        for (y in 0 until height) {
            val row = y * width
            var x = 0
            while (x < width) {
                if (coverage[row + x].toInt() == 0) {
                    x++
                    continue
                }
                val start = x
                while (x < width && coverage[row + x].toInt() != 0) x++
                val above = if (y == 0) false else rowHasEdge(row - width, start, x)
                val below = if (y == height - 1) false else rowHasEdge(row + width, start, x)
                if (above || below) {
                    runs.add(start)
                    runs.add(y)
                    runs.add(x - start)
                }
            }
        }
        return runs.toIntArray()
    }

    private fun rowHasEdge(rowStart: Int, from: Int, to: Int): Boolean {
        for (i in from until to) if (coverage[rowStart + i].toInt() == 0) return true
        return false
    }

    fun grow(amount: Int) {
        if (amount == 0) return
        val temp = ByteArray(coverage.size)
        val source = coverage.copyOf()
        if (amount > 0) {
            for (i in 0 until amount) {
                dilate(source, temp)
                System.arraycopy(temp, 0, source, 0, source.size)
            }
        } else {
            for (i in 0 until -amount) {
                erode(source, temp)
                System.arraycopy(temp, 0, source, 0, source.size)
            }
        }
        System.arraycopy(source, 0, coverage, 0, coverage.size)
        invalidate()
    }

    private fun dilate(source: ByteArray, target: ByteArray) {
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                var best = 0
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        val nx = x + dx
                        val ny = y + dy
                        if (nx < 0 || ny < 0 || nx >= width || ny >= height) continue
                        best = max(best, source[ny * width + nx].toInt() and 0xFF)
                    }
                }
                target[row + x] = best.toByte()
            }
        }
    }

    private fun erode(source: ByteArray, target: ByteArray) {
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                var worst = 255
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        val nx = x + dx
                        val ny = y + dy
                        if (nx < 0 || ny < 0 || nx >= width || ny >= height) {
                            worst = 0
                            continue
                        }
                        worst = min(worst, source[ny * width + nx].toInt() and 0xFF)
                    }
                }
                target[row + x] = worst.toByte()
            }
        }
    }

    private fun boxBlurBytes(source: ByteArray, target: ByteArray, radius: Int) {
        val window = radius * 2 + 1
        val line = IntArray(max(width, height))
        // horizontal
        for (y in 0 until height) {
            val row = y * width
            var sum = 0
            for (i in -radius..radius) sum += source[row + i.coerceIn(0, width - 1)].toInt() and 0xFF
            for (x in 0 until width) {
                line[x] = sum / window
                sum -= source[row + (x - radius).coerceIn(0, width - 1)].toInt() and 0xFF
                sum += source[row + (x + radius + 1).coerceIn(0, width - 1)].toInt() and 0xFF
            }
            for (x in 0 until width) target[row + x] = line[x].toByte()
        }
        // vertical
        val column = IntArray(height)
        for (x in 0 until width) {
            var sum = 0
            for (i in -radius..radius) sum += target[i.coerceIn(0, height - 1) * width + x].toInt() and 0xFF
            for (y in 0 until height) {
                column[y] = sum / window
                sum -= target[(y - radius).coerceIn(0, height - 1) * width + x].toInt() and 0xFF
                sum += target[(y + radius + 1).coerceIn(0, height - 1) * width + x].toInt() and 0xFF
            }
            for (y in 0 until height) target[y * width + x] = column[y].toByte()
        }
    }

    /* ------------------------------------------------------- constructors */

    companion object {

        fun empty(width: Int, height: Int) = Selection(width, height)

        fun all(width: Int, height: Int) = Selection(width, height).apply { selectAll() }

        fun rectangle(width: Int, height: Int, rect: Rect, feather: Float = 0f): Selection {
            val selection = Selection(width, height)
            val r = rect.clampTo(width, height)
            if (r.isEmpty) return selection
            for (y in r.y until r.bottom) {
                java.util.Arrays.fill(selection.coverage, y * width + r.x, y * width + r.right, 255.toByte())
            }
            selection.invalidate()
            if (feather > 0.1f) selection.feather(feather)
            return selection
        }

        fun ellipse(width: Int, height: Int, rect: Rect, feather: Float = 0f, antialias: Boolean = true): Selection {
            val selection = Selection(width, height)
            val r = rect.clampTo(width, height)
            if (r.isEmpty) return selection
            val cx = (r.x + r.right) / 2.0
            val cy = (r.y + r.bottom) / 2.0
            val rx = max(0.5, r.w / 2.0)
            val ry = max(0.5, r.h / 2.0)
            for (y in r.y until r.bottom) {
                for (x in r.x until r.right) {
                    val dx = (x + 0.5 - cx) / rx
                    val dy = (y + 0.5 - cy) / ry
                    val distance = dx * dx + dy * dy
                    val value = if (!antialias) {
                        if (distance <= 1.0) 255 else 0
                    } else {
                        // One-pixel ramp across the edge for a smooth marquee.
                        val edge = 1.0 + 1.0 / max(rx, ry)
                        if (distance <= 1.0) 255 else if (distance >= edge * edge) 0
                        else (255 * (edge * edge - distance) / (edge * edge - 1.0)).toInt().coerceIn(0, 255)
                    }
                    selection.coverage[y * width + x] = value.toByte()
                }
            }
            selection.invalidate()
            if (feather > 0.1f) selection.feather(feather)
            return selection
        }

        /** A freehand lasso: fills the polygon given by the point list. */
        fun polygon(width: Int, height: Int, points: FloatArray, feather: Float = 0f): Selection {
            val selection = Selection(width, height)
            val count = points.size / 2
            if (count < 3) return selection
            var minY = Float.MAX_VALUE
            var maxY = -Float.MAX_VALUE
            for (i in 0 until count) {
                minY = min(minY, points[i * 2 + 1])
                maxY = max(maxY, points[i * 2 + 1])
            }
            val y0 = max(0, minY.toInt())
            val y1 = min(height - 1, ceil(maxY).toInt())
            val crossings = FloatArray(count)
            for (y in y0..y1) {
                val scanY = y + 0.5f
                var found = 0
                for (i in 0 until count) {
                    val ax = points[i * 2]
                    val ay = points[i * 2 + 1]
                    val j = (i + 1) % count
                    val bx = points[j * 2]
                    val by = points[j * 2 + 1]
                    if ((ay <= scanY && by > scanY) || (by <= scanY && ay > scanY)) {
                        val t = (scanY - ay) / (by - ay)
                        crossings[found++] = ax + t * (bx - ax)
                    }
                }
                if (found < 2) continue
                val sorted = crossings.copyOf(found).sortedArray()
                var i = 0
                while (i + 1 < found) {
                    val from = sorted[i]
                    val to = sorted[i + 1]
                    val startX = max(0, ceil(from - 0.5f).toInt())
                    val endX = min(width - 1, (to - 0.5f).toInt())
                    if (endX >= startX) {
                        java.util.Arrays.fill(selection.coverage, y * width + startX, y * width + endX + 1, 255.toByte())
                    }
                    i += 2
                }
            }
            selection.invalidate()
            if (feather > 0.1f) selection.feather(feather)
            return selection
        }

        /**
         * The magic wand: flood-fills outward from (x, y) while the colour stays
         * within `tolerance`, and uses the result as the selection.
         */
        fun magicWand(
            pixels: Pixmap,
            x: Int,
            y: Int,
            tolerance: Int,
            contiguous: Boolean = true,
            feather: Float = 0f,
        ): Selection {
            val selection = Selection(pixels.width, pixels.height)
            val target = pixels[x, y]
            val limit = tolerance.coerceIn(0, 255)
            fun matches(color: Int): Boolean {
                if (Colors.alpha(target) == 0 && Colors.alpha(color) == 0) return true
                val dr = Colors.red(color) - Colors.red(target)
                val dg = Colors.green(color) - Colors.green(target)
                val db = Colors.blue(color) - Colors.blue(target)
                val da = Colors.alpha(color) - Colors.alpha(target)
                // Distance in RGBA, scaled so full white-ish tolerance selects broadly
                // but never the whole image unless asked.
                val distance = (kotlin.math.sqrt((dr * dr + dg * dg + db * db + da * da).toDouble()) / 2.0).toInt()
                return distance <= limit
            }
            if (!contiguous) {
                for (i in 0 until pixels.pixels.size) {
                    if (matches(pixels.pixels[i])) selection.coverage[i] = 255.toByte()
                }
            } else {
                val stack = IntArray(pixels.pixels.size.coerceAtMost(1 shl 20))
                var top = 0
                val visited = java.util.BitSet(pixels.pixels.size)
                stack[top++] = y * pixels.width + x
                visited.set(y * pixels.width + x)
                while (top > 0) {
                    val index = stack[--top]
                    if (!matches(pixels.pixels[index])) continue
                    selection.coverage[index] = 255.toByte()
                    val px = index % pixels.width
                    val py = index / pixels.width
                    if (px > 0 && !visited.get(index - 1)) { visited.set(index - 1); if (top < stack.size) stack[top++] = index - 1 }
                    if (px < pixels.width - 1 && !visited.get(index + 1)) { visited.set(index + 1); if (top < stack.size) stack[top++] = index + 1 }
                    if (py > 0 && !visited.get(index - pixels.width)) { visited.set(index - pixels.width); if (top < stack.size) stack[top++] = index - pixels.width }
                    if (py < pixels.height - 1 && !visited.get(index + pixels.width)) { visited.set(index + pixels.width); if (top < stack.size) stack[top++] = index + pixels.width }
                }
            }
            selection.invalidate()
            if (feather > 0.1f) selection.feather(feather)
            return selection
        }

        /** Marquee drawn by dragging a rectangle: hard-edged for the rectangular case. */
        fun lassoStroke(width: Int, height: Int, points: List<Pair<Float, Float>>, feather: Float): Selection {
            if (points.size < 3) return empty(width, height)
            val flat = FloatArray(points.size * 2)
            points.forEachIndexed { i, p -> flat[i * 2] = p.first; flat[i * 2 + 1] = p.second }
            return polygon(width, height, flat, feather)
        }
    }
}

/** Distance helper shared by the brush engine. */
internal fun distance(x0: Float, y0: Float, x1: Float, y1: Float): Float = hypot(x1 - x0, y1 - y0)
