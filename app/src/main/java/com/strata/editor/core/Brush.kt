package com.strata.editor.core

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A brush tip.
 *
 * Position, size, angle, roundness and hardness describe the stamp; spacing says
 * how far the pointer must move before the next stamp is laid down; flow is how
 * much paint each stamp carries.
 */
class Brush(
    var size: Float = 32f,
    var hardness: Float = 0.8f,
    var roundness: Float = 1f,
    var angle: Float = 0f,
    var spacing: Float = 0.15f,
    var flow: Float = 1f,
) {
    fun copyOf(): Brush = Brush(size, hardness, roundness, angle, spacing, flow)

    companion object {
        /** Named presets, so a phone user gets useful brushes without a dialog. */
        data class Preset(val name: String, val size: Float, val hardness: Float, val roundness: Float, val spacing: Float, val flow: Float)

        val presets: List<Preset> = listOf(
            Preset("Hard round", 24f, 1f, 1f, 0.10f, 1f),
            Preset("Soft round", 64f, 0.25f, 1f, 0.12f, 0.85f),
            Preset("Airbrush", 120f, 0f, 1f, 0.06f, 0.25f),
            Preset("Pencil", 3f, 1f, 1f, 0.18f, 1f),
            Preset("Calligraphy", 40f, 0.9f, 0.25f, 0.08f, 1f),
            Preset("Chalk", 48f, 0.7f, 1f, 0.22f, 0.7f),
            Preset("Marker", 36f, 0.85f, 0.6f, 0.10f, 0.9f),
        )

        fun fromPreset(preset: Preset): Brush =
            Brush(preset.size, preset.hardness, preset.roundness, 0f, preset.spacing, preset.flow)
    }
}

/**
 * Painting operations.
 *
 * Every function here takes the same two extra arguments: the layer to write to,
 * and the selection (or null when the whole canvas may be written). Coverage from
 * the selection is applied per pixel, which is what makes painting respect a
 * marquee or a feathered lasso without any special-casing in the tools.
 */
class Painter(private val layer: Layer, private val selection: Selection? = null) {

    private val pixels: Pixmap = layer.pixels ?: throw IllegalArgumentException("Layer has no pixels to paint on")
    private val colourMask: Boolean = layer.kind == LayerKind.RASTER

    /** The source pixels for the clone stamp. */
    var cloneSource: Pixmap? = null
    var cloneOffsetX: Float = 0f
    var cloneOffsetY: Float = 0f

    private fun coverage(x: Int, y: Int): Float = if (selection == null) 1f else selection.coverageAt(x, y)

    /** Writes one pixel, honouring the selection and the brush's own alpha. */
    private fun blendPixel(x: Int, y: Int, colour: Int, alpha: Float, mode: BlendMode = BlendMode.NORMAL) {
        if (x < 0 || y < 0 || x >= pixels.width || y >= pixels.height) return
        val sel = coverage(x, y)
        if (sel <= 0f) return
        val a = alpha * sel
        if (a <= 0.001f) return
        val index = y * pixels.width + x
        val back = pixels.pixels[index]
        val source = Colors.withAlpha(colour, (Colors.alpha(colour) * a).toInt().coerceIn(0, 255))
        pixels.pixels[index] = if (mode == BlendMode.NORMAL) Colors.over(back, source) else Blend.apply(mode, back, source)
    }

    /**
     * Lays down one brush stamp.
     *
     * The falloff is a smoothstep between the hard core (`hardness`) and the rim,
     * so hardness 1 gives a crisp disc, hardness 0 a soft radial gradient.
     */
    fun stamp(
        cx: Float,
        cy: Float,
        brush: Brush,
        colour: Int,
        erase: Boolean = false,
        flowScale: Float = 1f,
        blendMode: BlendMode = BlendMode.NORMAL,
    ) {
        val radius = max(0.5f, brush.size / 2f)
        val angle = Math.toRadians(brush.angle.toDouble())
        val cosA = cos(angle).toFloat()
        val sinA = sin(angle).toFloat()
        val minX = max(0, (cx - radius).toInt() - 1)
        val maxX = min(pixels.width - 1, (cx + radius).toInt() + 1)
        val minY = max(0, (cy - radius).toInt() - 1)
        val maxY = min(pixels.height - 1, (cy + radius).toInt() + 1)
        val hardness = brush.hardness.coerceIn(0f, 1f)
        val roundness = brush.roundness.coerceIn(0.05f, 1f)
        val flow = (brush.flow * flowScale).coerceIn(0f, 1f)

        for (y in minY..maxY) {
            for (x in minX..maxX) {
                val dx = x + 0.5f - cx
                val dy = y + 0.5f - cy
                // Rotate into the tip's own frame, then squash for a non-round tip.
                val rx = (dx * cosA + dy * sinA) / radius
                val ry = (-dx * sinA + dy * cosA) / (radius * roundness)
                val distance = sqrt((rx * rx + ry * ry).toDouble()).toFloat()
                if (distance >= 1f) continue
                val falloff = if (hardness >= 0.999f) {
                    1f
                } else {
                    val edge = 1f - hardness
                    val t = ((1f - distance) / edge).coerceIn(0f, 1f)
                    t * t * (3f - 2f * t)
                }
                var alpha = falloff * flow
                if (alpha <= 0.002f) continue
                if (erase) {
                    erasePixel(x, y, alpha)
                } else {
                    blendPixel(x, y, colour, alpha, blendMode)
                }
            }
        }
    }

    private fun erasePixel(x: Int, y: Int, alpha: Float) {
        val sel = coverage(x, y) * alpha
        if (sel <= 0f) return
        val index = y * pixels.width + x
        val existing = pixels.pixels[index]
        val a = Colors.alpha(existing)
        if (a == 0) return
        val remaining = (a * (1f - sel)).toInt().coerceIn(0, 255)
        pixels.pixels[index] = Colors.withAlpha(existing, remaining)
    }

    /**
     * Paints a stroke segment: stamps along the line, one stamp every
     * `spacing * size` pixels, which is what keeps a fast flick from becoming a
     * dotted line.
     */
    fun stroke(
        x0: Float,
        y0: Float,
        x1: Float,
        y1: Float,
        brush: Brush,
        colour: Int,
        erase: Boolean = false,
        carry: Float = 0f,
        blendMode: BlendMode = BlendMode.NORMAL,
    ): Float {
        val dx = x1 - x0
        val dy = y1 - y0
        val length = sqrt((dx * dx + dy * dy).toDouble()).toFloat()
        val step = max(0.5f, brush.size * brush.spacing.coerceIn(0.01f, 2f))
        var travelled = carry
        while (travelled <= length) {
            val t = if (length <= 0f) 0f else travelled / length
            stamp(x0 + dx * t, y0 + dy * t, brush, colour, erase, 1f, blendMode)
            travelled += step
        }
        return travelled - length
    }

    /* ------------------------------------------------------------- fills */

    /**
     * Bucket fill: replaces the connected region around (x, y) whose colour is
     * within `tolerance` of the seed, or the whole layer when `contiguous` is off.
     */
    fun floodFill(x: Int, y: Int, colour: Int, tolerance: Int, contiguous: Boolean = true, antialias: Boolean = true): Rect {
        val seed = pixels[x, y]
        val limit = tolerance.coerceIn(0, 255)
        val limitSq = (limit * limit * 4).toLong()
        fun within(p: Int): Boolean {
            if (Colors.alpha(seed) == 0 && Colors.alpha(p) == 0) return true
            val dr = Colors.red(p) - Colors.red(seed)
            val dg = Colors.green(p) - Colors.green(seed)
            val db = Colors.blue(p) - Colors.blue(seed)
            val da = Colors.alpha(p) - Colors.alpha(seed)
            return (dr * dr + dg * dg + db * db + da * da).toLong() <= limitSq
        }

        var bounds = Rect.EMPTY
        if (!contiguous) {
            for (i in pixels.pixels.indices) {
                if (within(pixels.pixels[i])) {
                    val px = i % pixels.width
                    val py = i / pixels.width
                    blendPixel(px, py, colour, 1f)
                    bounds = bounds.union(Rect(px, py, 1, 1))
                }
            }
            return bounds
        }

        val visited = java.util.BitSet(pixels.size)
        val queue = IntArray(min(pixels.size, 1 shl 22))
        var head = 0
        var tail = 0
        val start = y * pixels.width + x
        queue[tail++] = start
        visited.set(start)
        while (head < tail) {
            val index = queue[head++]
            if (!within(pixels.pixels[index])) continue
            val px = index % pixels.width
            val py = index / pixels.width
            blendPixel(px, py, colour, 1f)
            bounds = bounds.union(Rect(px, py, 1, 1))
            fun push(nx: Int, ny: Int) {
                if (nx < 0 || ny < 0 || nx >= pixels.width || ny >= pixels.height) return
                val next = ny * pixels.width + nx
                if (visited.get(next)) return
                visited.set(next)
                if (tail < queue.size) queue[tail++] = next
            }
            push(px - 1, py); push(px + 1, py); push(px, py - 1); push(px, py + 1)
        }
        return bounds
    }

    /** Fills a rectangle, respecting the selection. */
    fun fillRect(rect: Rect, colour: Int, opacity: Float = 1f) {
        val r = rect.clampTo(pixels.width, pixels.height)
        for (y in r.y until r.bottom) {
            for (x in r.x until r.right) blendPixel(x, y, colour, opacity)
        }
    }

    /**
     * A gradient from `rect` across the current selection.
     *
     * Linear and radial, with a choice of the classic colour ramps; dithered with a
     * tiny amount of noise so a smooth ramp does not band on an 8-bit display.
     */
    fun gradient(
        rect: Rect,
        from: Int,
        to: Int,
        type: GradientType,
        shape: GradientShape,
        midpoint: Float = 0.5f,
        reverse: Boolean = false,
        dither: Boolean = true,
        seed: Long = 12345L,
    ) {
        val r = rect.clampTo(pixels.width, pixels.height)
        if (r.isEmpty) return
        val random = java.util.Random(seed)
        val cx = r.x + r.w / 2f
        val cy = r.y + r.h / 2f
        val maxDistance = when {
            r.w == 0 || r.h == 0 -> 1f
            else -> sqrt((r.w * r.w + r.h * r.h).toDouble()).toFloat() / 2f
        }
        val m = midpoint.coerceIn(0.05f, 0.95f)
        for (y in r.y until r.bottom) {
            for (x in r.x until r.right) {
                val t = when (shape) {
                    GradientShape.RADIAL -> {
                        val dx = (x + 0.5f - cx) / maxDistance
                        val dy = (y + 0.5f - cy) / maxDistance
                        sqrt((dx * dx + dy * dy).toDouble()).toFloat().coerceIn(0f, 1f)
                    }
                    else -> when (type) {
                        GradientType.HORIZONTAL -> (x + 0.5f - r.x) / r.w
                        GradientType.VERTICAL -> (y + 0.5f - r.y) / r.h
                        GradientType.DIAGONAL_DOWN -> ((x - r.x) + (y - r.y)) / (r.w + r.h).toFloat()
                        GradientType.DIAGONAL_UP -> ((x - r.x) + (r.bottom - y)) / (r.w + r.h).toFloat()
                        else -> (x + 0.5f - r.x) / r.w
                    }
                }
                val raw = t.coerceIn(0f, 1f)
                // Midpoint shifts where the two colours meet.
                val position = if (raw <= m) {
                    (raw / m) * 0.5f
                } else {
                    0.5f + ((raw - m) / (1f - m)) * 0.5f
                }
                val eased = smootherStep(position)
                val jitter = if (dither) (random.nextFloat() - 0.5f) / 255f else 0f
                val f = (if (reverse) 1f - eased else eased) + jitter
                blendPixel(x, y, Colors.lerp(from, to, f.coerceIn(0f, 1f)), 1f)
            }
        }
    }

    private fun smootherStep(t: Float): Float = t * t * t * (t * (t * 6f - 15f) + 10f)

    /* ------------------------------------------------------------ shapes */

    fun line(x0: Float, y0: Float, x1: Float, y1: Float, colour: Int, width: Float, hardEdge: Boolean = true) {
        val brush = Brush(size = width, hardness = if (hardEdge) 1f else 0.6f, spacing = 0.08f)
        stroke(x0, y0, x1, y1, brush, colour)
    }

    fun rectangle(rect: Rect, colour: Int, strokeWidth: Float, filled: Boolean) {
        if (filled) {
            fillRect(rect, colour)
            return
        }
        val w = max(1f, strokeWidth)
        fillRect(Rect(rect.x, rect.y, rect.w, w.toInt()), colour)
        fillRect(Rect(rect.x, rect.bottom - w.toInt(), rect.w, w.toInt()), colour)
        fillRect(Rect(rect.x, rect.y, w.toInt(), rect.h), colour)
        fillRect(Rect(rect.right - w.toInt(), rect.y, w.toInt(), rect.h), colour)
    }

    fun ellipse(rect: Rect, colour: Int, strokeWidth: Float, filled: Boolean) {
        val r = rect.clampTo(pixels.width, pixels.height)
        if (r.isEmpty) return
        val cx = r.x + r.w / 2f
        val cy = r.y + r.h / 2f
        val rx = r.w / 2f
        val ry = r.h / 2f
        val half = max(0.5f, strokeWidth / 2f)
        for (y in r.y until r.bottom) {
            for (x in r.x until r.right) {
                val dx = (x + 0.5f - cx) / rx
                val dy = (y + 0.5f - cy) / ry
                val distance = sqrt((dx * dx + dy * dy).toDouble()).toFloat()
                val thickness = half / min(rx, ry)
                val inside = distance <= 1f
                if (filled && inside) {
                    // Soft one-pixel edge so a filled ellipse is not jagged.
                    val edge = 1f - ((distance - (1f - thickness * 2f)) / (thickness * 2f)).coerceIn(0f, 1f)
                    blendPixel(x, y, colour, if (edge > 0f) edge.coerceAtLeast(0.15f) else 0f)
                } else if (!filled && abs(distance - 1f) <= thickness) {
                    val fade = 1f - (abs(distance - 1f) / thickness).coerceIn(0f, 1f)
                    blendPixel(x, y, colour, fade * 0.5f + 0.5f)
                }
            }
        }
    }

    /** An arbitrary polygon, filled with the even-odd rule. */
    fun polygon(points: FloatArray, colour: Int, strokeWidth: Float, filled: Boolean) {
        if (points.size < 6) return
        if (!filled) {
            var i = 0
            while (i + 3 < points.size) {
                line(points[i], points[i + 1], points[i + 2], points[i + 3], colour, strokeWidth)
                i += 2
            }
            line(points[points.size - 2], points[points.size - 1], points[0], points[1], colour, strokeWidth)
            return
        }
        val selection = Selection.polygon(pixels.width, pixels.height, points)
        for (y in 0 until pixels.height) {
            for (x in 0 until pixels.width) {
                val value = selection.value(x, y)
                if (value > 0) blendPixel(x, y, colour, value / 255f)
            }
        }
    }

    /* ---------------------------------------------------------- retouch */

    /** Clone stamp: copies from [cloneSource] with the current offset. */
    fun cloneStamp(cx: Float, cy: Float, brush: Brush, aligned: Boolean = true, flowScale: Float = 1f) {
        val source = cloneSource ?: return
        val radius = max(0.5f, brush.size / 2f)
        val minX = max(0, (cx - radius).toInt() - 1)
        val maxX = min(pixels.width - 1, (cx + radius).toInt() + 1)
        val minY = max(0, (cy - radius).toInt() - 1)
        val maxY = min(pixels.height - 1, (cy + radius).toInt() + 1)
        val hardness = brush.hardness.coerceIn(0f, 1f)
        val flow = (brush.flow * flowScale).coerceIn(0f, 1f)
        for (y in minY..maxY) {
            for (x in minX..maxX) {
                val dx = x + 0.5f - cx
                val dy = y + 0.5f - cy
                val distance = sqrt((dx * dx + dy * dy).toDouble()).toFloat() / radius
                if (distance >= 1f) continue
                val falloff = if (hardness >= 0.999f) 1f else {
                    val edge = 1f - hardness
                    val t = ((1f - distance) / edge).coerceIn(0f, 1f)
                    t * t * (3f - 2f * t)
                }
                val sx = (x + cloneOffsetX).toInt()
                val sy = (y + cloneOffsetY).toInt()
                if (sx < 0 || sy < 0 || sx >= source.width || sy >= source.height) continue
                val sampled = source.pixels[sy * source.width + sx]
                if (sampled == 0) continue
                blendPixel(x, y, sampled, falloff * flow)
            }
        }
    }

    /** Dodge (lighten), burn (darken) and sponge (saturate/desaturate). */
    fun retouch(cx: Float, cy: Float, brush: Brush, kind: RetouchKind, amount: Float = 0.5f) {
        val radius = max(0.5f, brush.size / 2f)
        val minX = max(0, (cx - radius).toInt() - 1)
        val maxX = min(pixels.width - 1, (cx + radius).toInt() + 1)
        val minY = max(0, (cy - radius).toInt() - 1)
        val maxY = min(pixels.height - 1, (cy + radius).toInt() + 1)
        val hardness = brush.hardness.coerceIn(0f, 1f)
        val strength = amount.coerceIn(0f, 1f) * brush.flow
        for (y in minY..maxY) {
            for (x in minX..maxX) {
                val dx = x + 0.5f - cx
                val dy = y + 0.5f - cy
                val distance = sqrt((dx * dx + dy * dy).toDouble()).toFloat() / radius
                if (distance >= 1f) continue
                val falloff = if (hardness >= 0.999f) 1f else {
                    val edge = 1f - hardness
                    val t = ((1f - distance) / edge).coerceIn(0f, 1f)
                    t * t * (3f - 2f * t)
                }
                val index = y * pixels.width + x
                val color = pixels.pixels[index]
                val a = Colors.alpha(color)
                if (a == 0) continue
                val sel = coverage(x, y)
                if (sel <= 0f) continue
                val f = falloff * strength * sel
                val out = when (kind) {
                    RetouchKind.DODGE -> Colors.pack(
                        a,
                        (Colors.red(color) + (255 - Colors.red(color)) * f).toInt(),
                        (Colors.green(color) + (255 - Colors.green(color)) * f).toInt(),
                        (Colors.blue(color) + (255 - Colors.blue(color)) * f).toInt(),
                    )
                    RetouchKind.BURN -> Colors.pack(
                        a,
                        (Colors.red(color) * (1f - f)).toInt(),
                        (Colors.green(color) * (1f - f)).toInt(),
                        (Colors.blue(color) * (1f - f)).toInt(),
                    )
                    RetouchKind.SPONGE_SATURATE, RetouchKind.SPONGE_DESATURATE -> {
                        val hsv = FloatArray(3)
                        Colors.toHsv(color, hsv)
                        val delta = if (kind == RetouchKind.SPONGE_SATURATE) f * 0.5f else -f * 0.5f
                        val saturation = (hsv[1] + delta).coerceIn(0f, 1f)
                        val rgb = FloatArray(3)
                        Colors.fromHsv(hsv[0], saturation, hsv[2], rgb)
                        Colors.pack(a, (rgb[0] * 255f).toInt(), (rgb[1] * 255f).toInt(), (rgb[2] * 255f).toInt())
                    }
                }
                pixels.pixels[index] = out
            }
        }
    }

    /**
     * Smudge, blur and sharpen share the same neighbourhood walk; `kind` decides how
     * the collected average is used.
     */
    fun neighbourhood(cx: Float, cy: Float, brush: Brush, kind: NeighbourhoodKind, strength: Float = 0.5f) {
        val radius = max(1f, brush.size / 2f)
        val tap = max(1, (radius * 0.5f).toInt()).coerceAtMost(6)
        val minX = max(0, (cx - radius).toInt())
        val maxX = min(pixels.width - 1, (cx + radius).toInt())
        val minY = max(0, (cy - radius).toInt())
        val maxY = min(pixels.height - 1, (cy + radius).toInt())
        val snapshot = when (kind) {
            NeighbourhoodKind.SMUDGE -> pixels.copy()
            else -> pixels.copy()
        }
        for (y in minY..maxY) {
            for (x in minX..maxX) {
                val dx = x + 0.5f - cx
                val dy = y + 0.5f - cy
                val distance = sqrt((dx * dx + dy * dy).toDouble()).toFloat() / radius
                if (distance >= 1f) continue
                val sel = coverage(x, y)
                if (sel <= 0f) continue
                val falloff = (1f - distance) * strength * sel
                var sa = 0L; var sr = 0L; var sg = 0L; var sb = 0L
                var count = 0
                for (ty in -tap..tap) {
                    for (tx in -tap..tap) {
                        val sx = (x + tx).coerceIn(0, pixels.width - 1)
                        val sy = (y + ty).coerceIn(0, pixels.height - 1)
                        val p = snapshot.pixels[sy * snapshot.width + sx]
                        val pa = Colors.alpha(p)
                        sa += pa; sr += Colors.red(p) * pa; sg += Colors.green(p) * pa; sb += Colors.blue(p) * pa
                        count++
                    }
                }
                if (count == 0 || sa == 0L) continue
                val average = Colors.pack(
                    (sa / count).toInt().coerceIn(0, 255),
                    (sr / sa).toInt().coerceIn(0, 255),
                    (sg / sa).toInt().coerceIn(0, 255),
                    (sb / sa).toInt().coerceIn(0, 255),
                )
                val index = y * pixels.width + x
                val current = pixels.pixels[index]
                pixels.pixels[index] = when (kind) {
                    NeighbourhoodKind.BLUR -> Colors.over(current, Colors.scaleAlpha(Colors.withAlpha(average, 255), falloff))
                    NeighbourhoodKind.SHARPEN -> {
                        // Push away from the local average: high-pass filtered.
                        val a = Colors.alpha(current)
                        val amount = falloff * 1.5f
                        Colors.pack(
                            a,
                            (Colors.red(current) + (Colors.red(current) - Colors.red(average)) * amount).toInt().coerceIn(0, 255),
                            (Colors.green(current) + (Colors.green(current) - Colors.green(average)) * amount).toInt().coerceIn(0, 255),
                            (Colors.blue(current) + (Colors.blue(current) - Colors.blue(average)) * amount).toInt().coerceIn(0, 255),
                        )
                    }
                    NeighbourhoodKind.SMUDGE -> Colors.lerp(current, average, falloff)
                }
            }
        }
    }

    enum class RetouchKind { DODGE, BURN, SPONGE_SATURATE, SPONGE_DESATURATE }

    enum class NeighbourhoodKind { BLUR, SHARPEN, SMUDGE }

    /* --------------------------------------------------- drawing helpers */

    /** Samples the layer under a point, for the eyedropper. */
    fun colourAt(x: Int, y: Int): Int = pixels[x, y]

    /** A small square of pixels, for the brush cursor's "current colour" hint. */
    fun averageAround(x: Int, y: Int, radius: Int): Int {
        var a = 0L; var r = 0L; var g = 0L; var b = 0L
        var count = 0
        for (yy in (y - radius)..(y + radius)) {
            for (xx in (x - radius)..(x + radius)) {
                val p = pixels[xx, yy]
                val pa = Colors.alpha(p)
                a += pa; r += Colors.red(p) * pa; g += Colors.green(p) * pa; b += Colors.blue(p) * pa
                count++
            }
        }
        if (count == 0 || a == 0L) return 0
        return Colors.pack((a / count).toInt(), (r / a).toInt(), (g / a).toInt(), (b / a).toInt())
    }
}

enum class GradientType { HORIZONTAL, VERTICAL, DIAGONAL_DOWN, DIAGONAL_UP, RADIAL }
enum class GradientShape { LINEAR, RADIAL }

/** The classic colour ramps, expressed as endpoint pairs (plus optional stops). */
object Gradients {

    data class Preset(val name: String, val from: Int, val to: Int, val stops: List<Triple<Float, Int, Int>> = emptyList())

    val presets: List<Preset> = listOf(
        Preset("Foreground → transparent", Colors.BLACK, 0),
        Preset("Black → white", Colors.BLACK, Colors.WHITE),
        Preset("White → black", Colors.WHITE, Colors.BLACK),
        Preset("Sunset", 0xFFFF8A3D.toInt(), 0xFF6A2C70.toInt()),
        Preset("Ocean", 0xFF1B6CA8.toInt(), 0xFF8BE8CB.toInt()),
        Preset("Copper", 0xFFB87333.toInt(), 0xFF3A2410.toInt()),
        Preset("Ice", 0xFFEAF6FF.toInt(), 0xFF6FA8DC.toInt()),
        Preset("Pastel rainbow", 0xFFFFB3BA.toInt(), 0xFFB5EAD7.toInt()),
        Preset("Steel", 0xFF9AA7B4.toInt(), 0xFF3B4652.toInt()),
        Preset("Fire", 0xFFFFF200.toInt(), 0xFFE01B24.toInt()),
    )
}
