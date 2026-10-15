package com.photocc.app.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Compositing.
 *
 * Rendering happens in **document-space rectangles**, never full documents: the
 * UI asks for the tile it is about to draw, so a brush stroke on a 4000×3000
 * file only touches the pixels it changed. Pixel maths is straight-alpha ARGB on
 * `IntArray`s — what PSD stores and what a canvas wants — so no conversion
 * happens anywhere in the pipeline.
 *
 * Blend modes follow the PDF 1.7 / W3C compositing formulas, which is what
 * Photoshop documents implement.
 */
object Render {

    /** Reusable scratch buffers keyed by size, so painting allocates nothing per frame. */
    private val scratch = HashMap<Int, IntArray>(4)

    fun borrow(size: Int): IntArray {
        val buffer = scratch[size]
        if (buffer != null) return buffer
        val created = IntArray(size)
        if (scratch.size >= 4) scratch.clear()
        scratch[size] = created
        return created
    }

    /** Flattens every visible layer of `doc` within `region` into `out`. */
    fun flatten(doc: PsdDocument, region: Rect, out: IntArray, background: Int = 0) {
        java.util.Arrays.fill(out, 0, region.w * region.h, background)
        composite(doc.children, region, out)
    }

    /** Composites a list of layers (bottom-first) into a region-sized buffer. */
    fun composite(layers: List<Layer>, region: Rect, dst: IntArray) {
        val size = region.w * region.h
        for (layer in layers) {
            if (!layer.visible || layer.opacity <= 0f) continue
            if (layer is GroupLayer) {
                val isolated = layer.opacity < 0.999f ||
                    (layer.blend != BlendMode.PASS_THROUGH && layer.blend != BlendMode.NORMAL)
                if (!isolated) {
                    composite(layer.children, region, dst)
                } else {
                    val buffer = borrow(size)
                    java.util.Arrays.fill(buffer, 0, size, 0)
                    composite(layer.children, region, buffer)
                    blendInto(dst, buffer, layer.opacity, layer.blend, region)
                }
                continue
            }
            val mask = (layer as? RasterLayer)?.mask?.takeIf { !it.disabled }
            val buffer = borrow(size)
            java.util.Arrays.fill(buffer, 0, size, 0)
            renderLayerInto(layer, region, buffer)
            if (mask != null) applyMask(buffer, region, mask)
            blendInto(
                dst = dst,
                src = buffer,
                opacity = layer.opacity,
                mode = layer.blend,
                region = region,
                clipToBackdrop = layer.rawClipping == 1,
            )
        }
    }

    /** Draws just this layer's own pixels (no blending against anything). */
    fun renderLayerInto(layer: Layer, region: Rect, out: IntArray) {
        when (layer) {
            is RasterLayer -> copyRaster(layer, region, out)
            is TextLayer -> TextEngine.rasterizer?.rasterize(layer, region, out)
            is GroupLayer -> composite(layer.children, region, out)
        }
    }

    private fun copyRaster(layer: RasterLayer, region: Rect, out: IntArray) {
        val pixels = layer.pixels ?: return
        val overlap = Rect(layer.x, layer.y, pixels.width, pixels.height).intersect(region)
        if (overlap.isEmpty) return
        for (y in overlap.y until overlap.bottom) {
            val srcRow = (y - layer.y) * pixels.width - layer.x
            val outRow = (y - region.y) * region.w - region.x
            for (x in overlap.x until overlap.right) {
                val value = pixels.data[srcRow + x]
                if (value != 0) out[outRow + x] = value
            }
        }
    }

    private fun applyMask(out: IntArray, region: Rect, mask: MaskInfo) {
        if (mask.data.size < mask.bounds.w * mask.bounds.h) return
        val overlap = mask.bounds.intersect(region)
        if (overlap.isEmpty) return
        for (y in overlap.y until overlap.bottom) {
            val maskRow = (y - mask.bounds.y) * mask.bounds.w - mask.bounds.x
            val outRow = (y - region.y) * region.w - region.x
            for (x in overlap.x until overlap.right) {
                val index = outRow + x
                val value = out[index]
                if (value == 0) continue
                val maskValue = mask.data[maskRow + x].toInt() and 0xFF
                when {
                    maskValue == 255 -> Unit
                    maskValue == 0 -> out[index] = 0
                    else -> {
                        val alpha = ((value ushr 24) and 0xFF) * maskValue / 255
                        out[index] = (value and 0x00FFFFFF) or (alpha shl 24)
                    }
                }
            }
        }
    }

    /* ---------------------------------------------------------------- blending */

    /**
     * Composites `src` (region-sized, straight ARGB) onto `dst` with the given
     * layer opacity and blend mode.
     */
    fun blendInto(
        dst: IntArray,
        src: IntArray,
        opacity: Float,
        mode: BlendMode,
        region: Rect,
        /** Clipping layers (Photoshop's clipping masks) only show over their base layer. */
        clipToBackdrop: Boolean = false,
    ) {
        val opacityScale = opacity.coerceIn(0f, 1f)
        if (opacityScale <= 0f) return
        val count = region.w * region.h
        val limit = min(count, min(dst.size, src.size))
        val plain = mode == BlendMode.NORMAL || mode == BlendMode.PASS_THROUGH
        val dissolve = mode == BlendMode.DISSOLVE
        val opacityByte = (opacityScale * 255f + 0.5f).toInt()
        for (index in 0 until limit) {
            val source = src[index]
            val sourceAlpha = (source ushr 24) and 0xFF
            if (sourceAlpha == 0) continue
            var alpha = if (opacityByte >= 255) sourceAlpha else (sourceAlpha * opacityByte + 127) / 255
            if (clipToBackdrop) {
                // Approximates Photoshop's clipping mask: the base layer's coverage
                // masks everything clipped to it.
                val backdropAlpha = (dst[index] ushr 24) and 0xFF
                if (backdropAlpha == 0) continue
                alpha = alpha * backdropAlpha / 255
                if (alpha == 0) continue
            }
            if (dissolve) {
                // Photoshop dithers partly transparent pixels; a cheap deterministic
                // hash keeps strokes reproducible between frames.
                if (alpha < 255) {
                    var hash = index * 0x9E3779B1.toInt()
                    hash = hash xor (hash ushr 15)
                    if ((abs(hash) % 255) >= alpha) continue
                    alpha = 255
                }
            }
            if (alpha == 0) continue
            val backdrop = dst[index]
            dst[index] = if (plain) {
                Argb.over(backdrop, (source and 0x00FFFFFF) or (alpha shl 24), 255)
            } else {
                blendPixel(backdrop, source, alpha, mode)
            }
        }
    }

    /** One source-over pixel with a non-normal blend function. */
    fun blendPixel(backdrop: Int, source: Int, sourceAlpha: Int, mode: BlendMode): Int {
        val ba = (backdrop ushr 24) and 0xFF
        val sa = sourceAlpha
        if (sa == 0) return backdrop
        if (ba == 0) return (source and 0x00FFFFFF) or (sa shl 24)
        if (sa == 255 && ba == 255 && (mode == BlendMode.NORMAL || mode == BlendMode.PASS_THROUGH)) {
            return (source and 0x00FFFFFF) or 0xFF000000.toInt()
        }

        val sr = ((source ushr 16) and 0xFF) / 255f
        val sg = ((source ushr 8) and 0xFF) / 255f
        val sb = (source and 0xFF) / 255f
        val br = ((backdrop ushr 16) and 0xFF) / 255f
        val bg = ((backdrop ushr 8) and 0xFF) / 255f
        val bb = (backdrop and 0xFF) / 255f

        val alphaSource = sa / 255f
        val alphaBackdrop = ba / 255f
        val outAlpha = alphaSource + alphaBackdrop * (1f - alphaSource)
        if (outAlpha <= 0f) return 0

        val blended = blendTriple(br, bg, bb, sr, sg, sb, mode)
        val br2 = blended[0]
        val bg2 = blended[1]
        val bb2 = blended[2]

        fun channel(back: Float, blendValue: Float, srcChannel: Float): Float {
            val value = (1f - alphaSource) * alphaBackdrop * back +
                alphaSource * ((1f - alphaBackdrop) * srcChannel + alphaBackdrop * blendValue)
            return (value / outAlpha).coerceIn(0f, 1f)
        }

        return Argb.pack(
            (outAlpha * 255f + 0.5f).toInt().coerceIn(0, 255),
            (channel(br, br2, sr) * 255f + 0.5f).toInt().coerceIn(0, 255),
            (channel(bg, bg2, sg) * 255f + 0.5f).toInt().coerceIn(0, 255),
            (channel(bb, bb2, sb) * 255f + 0.5f).toInt().coerceIn(0, 255),
        )
    }

    /** The blend function B(Cb, Cs) as a colour triple. */
    private fun blendTriple(
        br: Float, bg: Float, bb: Float,
        sr: Float, sg: Float, sb: Float,
        mode: BlendMode,
    ): FloatArray = when (mode) {
        BlendMode.HUE -> setLum(setSat(sr, sg, sb, sat(br, bg, bb)), lum(br, bg, bb))
        BlendMode.SATURATION -> setLum(setSat(br, bg, bb, sat(sr, sg, sb)), lum(br, bg, bb))
        BlendMode.COLOR -> setLum(floatArrayOf(sr, sg, sb), lum(br, bg, bb))
        BlendMode.LUMINOSITY -> setLum(floatArrayOf(br, bg, bb), lum(sr, sg, sb))
        BlendMode.DARKER_COLOR -> if (lum(br, bg, bb) <= lum(sr, sg, sb)) {
            floatArrayOf(br, bg, bb)
        } else {
            floatArrayOf(sr, sg, sb)
        }
        BlendMode.LIGHTER_COLOR -> if (lum(br, bg, bb) >= lum(sr, sg, sb)) {
            floatArrayOf(br, bg, bb)
        } else {
            floatArrayOf(sr, sg, sb)
        }
        else -> floatArrayOf(
            separable(br, sr, mode),
            separable(bg, sg, mode),
            separable(bb, sb, mode),
        )
    }

    private fun separable(cb: Float, cs: Float, mode: BlendMode): Float = when (mode) {
        BlendMode.NORMAL, BlendMode.PASS_THROUGH, BlendMode.DISSOLVE -> cs
        BlendMode.MULTIPLY -> cb * cs
        BlendMode.SCREEN -> cb + cs - cb * cs
        BlendMode.OVERLAY -> hardLight(cs, cb)
        BlendMode.DARKEN -> min(cb, cs)
        BlendMode.LIGHTEN -> max(cb, cs)
        BlendMode.COLOR_DODGE -> colorDodge(cb, cs)
        BlendMode.COLOR_BURN -> colorBurn(cb, cs)
        BlendMode.LINEAR_DODGE -> min(1f, cb + cs)
        BlendMode.LINEAR_BURN -> max(0f, cb + cs - 1f)
        BlendMode.HARD_LIGHT -> hardLight(cb, cs)
        BlendMode.SOFT_LIGHT -> softLight(cb, cs)
        BlendMode.VIVID_LIGHT -> vividLight(cb, cs)
        BlendMode.LINEAR_LIGHT -> (cb + 2f * cs - 1f).coerceIn(0f, 1f)
        BlendMode.PIN_LIGHT -> if (cs <= 0.5f) min(cb, 2f * cs) else max(cb, 2f * cs - 1f)
        BlendMode.HARD_MIX -> if (vividLight(cb, cs) >= 0.5f) 1f else 0f
        BlendMode.DIFFERENCE -> abs(cb - cs)
        BlendMode.EXCLUSION -> cb + cs - 2f * cb * cs
        BlendMode.SUBTRACT -> max(0f, cb - cs)
        BlendMode.DIVIDE -> if (cs <= 0f) 1f else min(1f, cb / cs)
        else -> cs
    }

    private fun hardLight(cb: Float, cs: Float): Float =
        if (cs <= 0.5f) 2f * cs * cb else 1f - 2f * (1f - cs) * (1f - cb)

    private fun vividLight(cb: Float, cs: Float): Float =
        if (cs <= 0.5f) colorBurn(cb, min(1f, 2f * cs)) else colorDodge(cb, 2f * cs - 1f)

    private fun colorDodge(cb: Float, cs: Float): Float = when {
        cb <= 0f -> 0f
        cs >= 1f -> 1f
        else -> min(1f, cb / (1f - cs))
    }

    private fun colorBurn(cb: Float, cs: Float): Float = when {
        cb >= 1f -> 1f
        cs <= 0f -> 0f
        else -> 1f - min(1f, (1f - cb) / cs)
    }

    private fun softLight(cb: Float, cs: Float): Float {
        if (cs <= 0.5f) return cb - (1f - 2f * cs) * cb * (1f - cb)
        val d = if (cb <= 0.25f) ((16f * cb - 12f) * cb + 4f) * cb else sqrt(cb)
        return cb + (2f * cs - 1f) * (d - cb)
    }

    /* --- non-separable helpers (W3C compositing: Lum, ClipColor, SetLum, Sat, SetSat) --- */

    private fun lum(r: Float, g: Float, b: Float): Float = 0.3f * r + 0.59f * g + 0.11f * b

    private fun sat(r: Float, g: Float, b: Float): Float = max(r, max(g, b)) - min(r, min(g, b))

    private fun clipColor(r: Float, g: Float, b: Float): FloatArray {
        val l = lum(r, g, b)
        val n = min(r, min(g, b))
        val x = max(r, max(g, b))
        var outR = r
        var outG = g
        var outB = b
        if (n < 0f) {
            val denominator = l - n
            if (denominator > 0.0001f) {
                outR = l + (outR - l) * l / denominator
                outG = l + (outG - l) * l / denominator
                outB = l + (outB - l) * l / denominator
            } else {
                outR = l
                outG = l
                outB = l
            }
        }
        if (x > 1f) {
            val denominator = x - l
            if (denominator > 0.0001f) {
                outR = l + (outR - l) * (1f - l) / denominator
                outG = l + (outG - l) * (1f - l) / denominator
                outB = l + (outB - l) * (1f - l) / denominator
            } else {
                outR = l
                outG = l
                outB = l
            }
        }
        return floatArrayOf(outR.coerceIn(0f, 1f), outG.coerceIn(0f, 1f), outB.coerceIn(0f, 1f))
    }

    private fun setLum(color: FloatArray, l: Float): FloatArray {
        val d = l - lum(color[0], color[1], color[2])
        return clipColor(color[0] + d, color[1] + d, color[2] + d)
    }

    private fun setSat(color: FloatArray, s: Float): FloatArray {
        val out = floatArrayOf(color[0], color[1], color[2])
        var maxIndex = 0
        var minIndex = 0
        for (i in 1..2) {
            if (out[i] > out[maxIndex]) maxIndex = i
            if (out[i] < out[minIndex]) minIndex = i
        }
        val midIndex = 3 - maxIndex - minIndex
        if (maxIndex != minIndex && out[maxIndex] > out[minIndex]) {
            out[midIndex] = (out[midIndex] - out[minIndex]) * s / (out[maxIndex] - out[minIndex])
            out[maxIndex] = s
        } else {
            out[midIndex] = 0f
            out[maxIndex] = 0f
        }
        out[minIndex] = 0f
        return out
    }

    private fun setSat(r: Float, g: Float, b: Float, s: Float): FloatArray = setSat(floatArrayOf(r, g, b), s)

    /* ---------------------------------------------------------------- painting tools */

    /** Result of a paint call: the document-space area that changed. */
    private fun dirty(rect: Rect, region: Rect?): Rect = if (region == null) rect else rect.intersect(region)

    /**
     * Paints one soft round stamp into a raster layer. `x`/`y` are document
     * coordinates; the layer's own origin is taken care of here.
     */
    fun paintStamp(
        layer: RasterLayer,
        x: Float,
        y: Float,
        radius: Float,
        hardness: Float,
        color: Int,
        flow: Float,
    ): Rect? {
        val pixels = layer.pixels ?: return null
        val r = max(0.5f, radius)
        val bounds = Rect(layer.x, layer.y, pixels.width, pixels.height)
        val minX = max(bounds.x, (x - r - 1f).toInt())
        val maxX = min(bounds.right - 1, (x + r + 1f).toInt())
        val minY = max(bounds.y, (y - r - 1f).toInt())
        val maxY = min(bounds.bottom - 1, (y + r + 1f).toInt())
        if (minX > maxX || minY > maxY) return null
        val inner = (r * hardness.coerceIn(0f, 1f)).coerceAtLeast(0.75f)
        for (py in minY..maxY) {
            val dy = py + 0.5f - y
            val rowBase = (py - layer.y) * pixels.width - layer.x
            for (px in minX..maxX) {
                val dx = px + 0.5f - x
                val coverage = falloff(dx * dx + dy * dy, r, inner) * flow
                if (coverage <= 0.002f) continue
                val index = rowBase + px
                if (index < 0 || index >= pixels.data.size) continue
                pixels.data[index] = Argb.over(pixels.data[index], color, (coverage * Argb.a(color) + 0.5f).toInt())
            }
        }
        return Rect(minX, minY, maxX - minX + 1, maxY - minY + 1)
    }

    /** Erases with a soft stamp (lowers alpha, keeps colour). */
    fun eraseStamp(layer: RasterLayer, x: Float, y: Float, radius: Float, hardness: Float, flow: Float): Rect? {
        val pixels = layer.pixels ?: return null
        val r = max(0.5f, radius)
        val bounds = Rect(layer.x, layer.y, pixels.width, pixels.height)
        val minX = max(bounds.x, (x - r - 1f).toInt())
        val maxX = min(bounds.right - 1, (x + r + 1f).toInt())
        val minY = max(bounds.y, (y - r - 1f).toInt())
        val maxY = min(bounds.bottom - 1, (y + r + 1f).toInt())
        if (minX > maxX || minY > maxY) return null
        val inner = (r * hardness.coerceIn(0f, 1f)).coerceAtLeast(0.75f)
        for (py in minY..maxY) {
            val dy = py + 0.5f - y
            val rowBase = (py - layer.y) * pixels.width - layer.x
            for (px in minX..maxX) {
                val dx = px + 0.5f - x
                val coverage = (falloff(dx * dx + dy * dy, r, inner) * flow).coerceIn(0f, 1f)
                if (coverage <= 0.002f) continue
                val index = rowBase + px
                if (index < 0 || index >= pixels.data.size) continue
                val value = pixels.data[index]
                val alpha = (value ushr 24) and 0xFF
                val newAlpha = (alpha * (1f - coverage) + 0.5f).toInt().coerceIn(0, 255)
                pixels.data[index] = if (newAlpha == 0) 0 else (value and 0x00FFFFFF) or (newAlpha shl 24)
            }
        }
        return Rect(minX, minY, maxX - minX + 1, maxY - minY + 1)
    }

    /** Line of stamps so a dragged finger leaves an even stroke. */
    fun paintLine(
        layer: RasterLayer,
        fromX: Float,
        fromY: Float,
        toX: Float,
        toY: Float,
        radius: Float,
        hardness: Float,
        color: Int,
        flow: Float,
        erase: Boolean = false,
    ): Rect? {
        val dx = toX - fromX
        val dy = toY - fromY
        val distance = sqrt(dx * dx + dy * dy)
        val spacing = max(0.5f, radius * 0.25f)
        val steps = max(1, (distance / spacing).toInt())
        var changed: Rect? = null
        for (i in 0..steps) {
            val t = i.toFloat() / steps
            val rect = if (erase) {
                eraseStamp(layer, fromX + dx * t, fromY + dy * t, radius, hardness, flow)
            } else {
                paintStamp(layer, fromX + dx * t, fromY + dy * t, radius, hardness, color, flow)
            }
            if (rect != null) changed = if (changed == null) rect else changed.union(rect)
        }
        return changed
    }

    private fun falloff(distanceSquared: Float, radius: Float, inner: Float): Float {
        val distance = sqrt(distanceSquared)
        return when {
            distance <= inner -> 1f
            distance >= radius -> 0f
            else -> {
                val t = (radius - distance) / (radius - inner)
                t * t * (3f - 2f * t)
            }
        }
    }

    /** Fills a document-space rectangle inside a raster layer with a solid colour. */
    fun fillRect(layer: RasterLayer, region: Rect, color: Int): Rect? {
        val pixels = layer.pixels ?: return null
        val bounds = Rect(layer.x, layer.y, pixels.width, pixels.height)
        val overlap = region.intersect(bounds)
        if (overlap.isEmpty) return null
        for (y in overlap.y until overlap.bottom) {
            val rowBase = (y - layer.y) * pixels.width - layer.x
            for (x in overlap.x until overlap.right) {
                val index = rowBase + x
                if (index < 0 || index >= pixels.data.size) continue
                pixels.data[index] = Argb.over(pixels.data[index], color, 255)
            }
        }
        return overlap
    }

    /**
     * Flood fill (the paint bucket). Scanline based so a full-canvas fill on a
     * phone does not blow the stack or allocate a queue of millions of pixels.
     */
    fun floodFill(layer: RasterLayer, startX: Int, startY: Int, color: Int, tolerance: Int): Rect? {
        val pixels = layer.pixels ?: return null
        val width = pixels.width
        val height = pixels.height
        val data = pixels.data
        val localX = startX - layer.x
        val localY = startY - layer.y
        if (localX < 0 || localY < 0 || localX >= width || localY >= height) return null
        val targetColor = data[localY * width + localX]
        val replacement = Argb.over(targetColor, color, 255)
        if (targetColor == replacement) return null

        var minX = localX
        var maxX = localX
        var minY = localY
        var maxY = localY
        val visited = java.util.BitSet(width * height)
        val stack = ArrayDeque<IntArray>()
        stack.addLast(intArrayOf(localX, localY))
        fun matches(index: Int): Boolean {
            val value = data[index]
            if (tolerance <= 0) return value == targetColor
            val dr = abs(Argb.r(value) - Argb.r(targetColor))
            val dg = abs(Argb.g(value) - Argb.g(targetColor))
            val db = abs(Argb.b(value) - Argb.b(targetColor))
            val da = abs(Argb.a(value) - Argb.a(targetColor))
            return max(max(dr, dg), max(db, da)) <= tolerance
        }
        while (stack.isNotEmpty()) {
            val point = stack.removeLast()
            val y = point[1]
            var x = point[0]
            if (visited.get(y * width + x)) continue
            // Walk left and right to the ends of the run.
            while (x > 0 && matches(y * width + x - 1) && !visited.get(y * width + x - 1)) x--
            var spanLeft = false
            var spanRight = false
            var cursor = x
            while (cursor < width && matches(y * width + cursor) && !visited.get(y * width + cursor)) {
                val index = y * width + cursor
                visited.set(index)
                data[index] = replacement
                if (cursor < minX) minX = cursor
                if (cursor > maxX) maxX = cursor
                if (y < minY) minY = y
                if (y > maxY) maxY = y
                if (y > 0) {
                    val up = (y - 1) * width + cursor
                    val upMatches = !visited.get(up) && matches(up)
                    if (upMatches && !spanLeft) {
                        stack.addLast(intArrayOf(cursor, y - 1))
                        spanLeft = true
                    } else if (!upMatches) {
                        spanLeft = false
                    }
                }
                if (y < height - 1) {
                    val down = (y + 1) * width + cursor
                    val downMatches = !visited.get(down) && matches(down)
                    if (downMatches && !spanRight) {
                        stack.addLast(intArrayOf(cursor, y + 1))
                        spanRight = true
                    } else if (!downMatches) {
                        spanRight = false
                    }
                }
                cursor++
            }
        }
        return Rect(layer.x + minX, layer.y + minY, maxX - minX + 1, maxY - minY + 1)
    }

    /** Draws a straight line (raster tool) between two document points. */
    fun drawLine(region: Rect, out: IntArray, fromX: Float, fromY: Float, toX: Float, toY: Float, color: Int, width: Float) {
        val steps = max(1, (max(abs(toX - fromX), abs(toY - fromY))).toInt())
        val radius = max(0.5f, width / 2f)
        for (i in 0..steps) {
            val t = i.toFloat() / steps
            val x = fromX + (toX - fromX) * t
            val y = fromY + (toY - fromY) * t
            val minX = max(region.x, (x - radius - 1f).toInt())
            val maxX = min(region.right - 1, (x + radius + 1f).toInt())
            val minY = max(region.y, (y - radius - 1f).toInt())
            val maxY = min(region.bottom - 1, (y + radius + 1f).toInt())
            for (py in minY..maxY) {
                val rowBase = (py - region.y) * region.w - region.x
                for (px in minX..maxX) {
                    val dx = px + 0.5f - x
                    val dy = py + 0.5f - y
                    val coverage = falloff(dx * dx + dy * dy, radius, radius * 0.6f)
                    if (coverage <= 0.01f) continue
                    val index = rowBase + px
                    if (index < 0 || index >= out.size) continue
                    out[index] = Argb.over(out[index], color, (coverage * 255f + 0.5f).toInt())
                }
            }
        }
    }

    /** Cheap difference used by the bucket's tolerance and by the magic wand. */
    fun colorDistance(a: Int, b: Int): Int {
        val dr = abs(Argb.r(a) - Argb.r(b))
        val dg = abs(Argb.g(a) - Argb.g(b))
        val db = abs(Argb.b(a) - Argb.b(b))
        val da = abs(Argb.a(a) - Argb.a(b))
        return max(max(dr, dg), max(db, da))
    }
}
