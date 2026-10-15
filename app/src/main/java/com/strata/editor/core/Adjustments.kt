package com.strata.editor.core

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Tone and colour adjustments.
 *
 * An adjustment is a **description**, not a pixel edit: it lives on a layer, is
 * applied while compositing, and can be changed or thrown away at any time. That
 * is what makes an adjustment layer non-destructive.
 *
 * Every adjustment that can be expressed as a per-channel function compiles to a
 * 256-entry lookup table first, so applying it to a 12 MPix layer costs three
 * array lookups per pixel instead of any real maths.
 */
sealed class Adjustment {

    abstract val title: String

    /** Runs the adjustment over `pixels` in place, inside `region`. */
    abstract fun apply(pixels: Pixmap, region: Rect = pixels.bounds)

    /** A fresh copy with the same parameters, for history snapshots. */
    abstract fun copyOf(): Adjustment

    /** Adjustments that act on individual pixels can be compiled to a table. */
    open fun lookupTables(): Array<IntArray>? = null

    /** Modes that keep working through the table path but need per-pixel extras. */
    protected fun applyTables(pixels: Pixmap, region: Rect, tables: Array<IntArray>) {
        val r = region.intersect(pixels.bounds)
        if (r.isEmpty) return
        val tableR = tables[0]
        val tableG = tables[1]
        val tableB = tables[2]
        for (y in r.y until r.bottom) {
            val row = y * pixels.width
            for (x in r.x until r.right) {
                val index = row + x
                val color = pixels.pixels[index]
                val a = Colors.alpha(color)
                if (a == 0) continue
                pixels.pixels[index] = Colors.pack(
                    a,
                    tableR[Colors.red(color)],
                    tableG[Colors.green(color)],
                    tableB[Colors.blue(color)],
                )
            }
        }
    }

    /* ------------------------------------------------------------ tone */

    class BrightnessContrast(
        var brightness: Float = 0f,
        var contrast: Float = 0f,
        var useLegacy: Boolean = false,
    ) : Adjustment() {

        override val title: String get() = "Brightness/Contrast"

        override fun copyOf() = BrightnessContrast(brightness, contrast, useLegacy)

        override fun lookupTables(): Array<IntArray> {
            val table = IntArray(256)
            if (useLegacy) {
                val shift = brightness * 255f
                val c = contrast.coerceIn(-100f, 100f) / 100f
                val factor = if (c >= 0f) 1f / (1f - c).coerceAtLeast(0.01f) else 1f + c
                for (i in 0 until 256) {
                    table[i] = (((i + shift) - 128f) * factor + 128f).toInt().coerceIn(0, 255)
                }
            } else {
                val b = brightness.coerceIn(-150f, 150f) / 150f
                val c = contrast.coerceIn(-100f, 100f) / 100f
                for (i in 0 until 256) {
                    val v = i / 255f
                    val shifted = if (b >= 0f) v + (1f - v) * b else v * (1f + b)
                    val centred = shifted - 0.5f
                    val factor = if (c >= 0f) 1f / (1f - c * 0.99f) else 1f + c
                    table[i] = ((centred * factor + 0.5f) * 255f).toInt().coerceIn(0, 255)
                }
            }
            return arrayOf(table, table, table)
        }

        override fun apply(pixels: Pixmap, region: Rect) {
            val table = lookupTables()!![0]
            applyTables(pixels, region, arrayOf(table, table, table))
        }
    }

    class Exposure(var exposure: Float = 0f, var offset: Float = 0f, var gamma: Float = 1f) : Adjustment() {
        override val title: String get() = "Exposure"
        override fun copyOf() = Exposure(exposure, offset, gamma)

        override fun lookupTables(): Array<IntArray> {
            val table = IntArray(256)
            val gain = Math.pow(2.0, exposure.toDouble()).toFloat()
            val g = if (gamma <= 0.01f) 1f else 1f / gamma
            for (i in 0 until 256) {
                val v = (i / 255f * gain + offset).coerceIn(0f, 1f)
                table[i] = (Math.pow(v.toDouble(), g.toDouble()) * 255.0).toInt().coerceIn(0, 255)
            }
            return arrayOf(table, table, table)
        }

        override fun apply(pixels: Pixmap, region: Rect) {
            val t = lookupTables()!![0]
            applyTables(pixels, region, arrayOf(t, t, t))
        }
    }

    class Levels(
        var inputBlack: Int = 0,
        var inputWhite: Int = 255,
        var gamma: Float = 1f,
        var outputBlack: Int = 0,
        var outputWhite: Int = 255,
        var channel: Int = -1,
    ) : Adjustment() {

        override val title: String get() = "Levels"
        override fun copyOf() = Levels(inputBlack, inputWhite, gamma, outputBlack, outputWhite, channel)

        fun table(): IntArray {
            val table = IntArray(256)
            val inB = inputBlack.coerceIn(0, 254)
            val inW = inputWhite.coerceIn(inB + 1, 255)
            val outB = outputBlack.coerceIn(0, 255)
            val outW = outputWhite.coerceIn(0, 255)
            val g = if (gamma <= 0.01f) 1f else 1f / gamma
            for (i in 0 until 256) {
                var v = (i - inB).toFloat() / (inW - inB)
                v = v.coerceIn(0f, 1f)
                v = Math.pow(v.toDouble(), g.toDouble()).toFloat()
                table[i] = (outB + v * (outW - outB)).toInt().coerceIn(0, 255)
            }
            return table
        }

        override fun lookupTables(): Array<IntArray> {
            val table = table()
            return when (channel) {
                0 -> arrayOf(table, IDENTITY, IDENTITY)
                1 -> arrayOf(IDENTITY, table, IDENTITY)
                2 -> arrayOf(IDENTITY, IDENTITY, table)
                else -> arrayOf(table, table, table)
            }
        }

        override fun apply(pixels: Pixmap, region: Rect) = applyTables(pixels, region, lookupTables()!!)
    }

    /** A curve through control points, evaluated by monotone cubic interpolation. */
    class Curves private constructor(
        var points: Array<FloatArray>,
        var channel: Int,
    ) : Adjustment() {

        override val title: String get() = "Curves"
        override fun copyOf() = Curves(Array(points.size) { points[it].copyOf() }, channel)

        override fun lookupTables(): Array<IntArray> {
            val table = buildTable(points)
            return when (channel) {
                0 -> arrayOf(table, IDENTITY, IDENTITY)
                1 -> arrayOf(IDENTITY, table, IDENTITY)
                2 -> arrayOf(IDENTITY, IDENTITY, table)
                else -> arrayOf(table, table, table)
            }
        }

        override fun apply(pixels: Pixmap, region: Rect) = applyTables(pixels, region, lookupTables()!!)

        companion object {
            /** Points as (input 0..255, output 0..255) pairs, sorted by input. */
            fun of(points: List<FloatArray>, channel: Int = -1): Curves =
                Curves(points.toTypedArray(), channel)

            fun linear(channel: Int = -1): Curves =
                Curves(arrayOf(floatArrayOf(0f, 0f), floatArrayOf(255f, 255f)), channel)

            /**
             * Evaluates the curve with monotone cubic (Fritsch–Carlson) interpolation.
             *
             * A straight line through two points stays a straight line — which is the
             * property a "linear" curve must have — and adding points never makes the
             * curve wander back on itself.
             */
            fun buildTable(points: Array<FloatArray>): IntArray {
                val table = IntArray(256)
                val pts = points.sortedBy { it[0] }
                if (pts.size < 2) {
                    for (i in 0 until 256) table[i] = i
                    return table
                }
                val n = pts.size
                val xs = FloatArray(n) { pts[it][0] }
                val ys = FloatArray(n) { pts[it][1] }
                val slopes = FloatArray(n - 1)
                for (i in 0 until n - 1) {
                    slopes[i] = (ys[i + 1] - ys[i]) / (xs[i + 1] - xs[i]).coerceAtLeast(1e-4f)
                }
                val tangents = FloatArray(n)
                tangents[0] = slopes[0]
                tangents[n - 1] = slopes[n - 2]
                for (i in 1 until n - 1) {
                    if (slopes[i - 1] * slopes[i] <= 0f) {
                        tangents[i] = 0f
                    } else {
                        val w1 = 2f * (xs[i + 1] - xs[i]) + (xs[i] - xs[i - 1])
                        val w2 = (xs[i + 1] - xs[i]) + 2f * (xs[i] - xs[i - 1])
                        tangents[i] = (w1 + w2) / (w1 / slopes[i - 1] + w2 / slopes[i])
                    }
                }
                for (i in 0 until 256) {
                    val x = i.toFloat()
                    val value = when {
                        x <= xs[0] -> ys[0]
                        x >= xs[n - 1] -> ys[n - 1]
                        else -> {
                            var segment = 0
                            while (segment < n - 2 && x > xs[segment + 1]) segment++
                            val h = (xs[segment + 1] - xs[segment]).coerceAtLeast(1e-4f)
                            val t = (x - xs[segment]) / h
                            val t2 = t * t
                            val t3 = t2 * t
                            val h00 = 2f * t3 - 3f * t2 + 1f
                            val h10 = t3 - 2f * t2 + t
                            val h01 = -2f * t3 + 3f * t2
                            val h11 = t3 - t2
                            h00 * ys[segment] + h10 * h * tangents[segment] + h01 * ys[segment + 1] + h11 * h * tangents[segment + 1]
                        }
                    }
                    table[i] = value.toInt().coerceIn(0, 255)
                }
                return table
            }
        }
    }

    class Invert : Adjustment() {
        override val title: String get() = "Invert"
        override fun copyOf() = Invert()
        override fun lookupTables(): Array<IntArray> {
            val table = IntArray(256) { 255 - it }
            return arrayOf(table, table, table)
        }

        override fun apply(pixels: Pixmap, region: Rect) = applyTables(pixels, region, lookupTables()!!)
    }

    class Posterize(var levels: Int = 8) : Adjustment() {
        override val title: String get() = "Posterize"
        override fun copyOf() = Posterize(levels)

        override fun lookupTables(): Array<IntArray> {
            val n = levels.coerceIn(2, 255)
            val table = IntArray(256) { i ->
                val step = 255f / (n - 1)
                ((Math.round(i / step) * step).toInt()).coerceIn(0, 255)
            }
            return arrayOf(table, table, table)
        }

        override fun apply(pixels: Pixmap, region: Rect) = applyTables(pixels, region, lookupTables()!!)
    }

    class Threshold(var level: Int = 128) : Adjustment() {
        override val title: String get() = "Threshold"
        override fun copyOf() = Threshold(level)

        override fun apply(pixels: Pixmap, region: Rect) {
            val r = region.intersect(pixels.bounds)
            val limit = level.coerceIn(0, 255)
            for (y in r.y until r.bottom) {
                val row = y * pixels.width
                for (x in r.x until r.right) {
                    val index = row + x
                    val color = pixels.pixels[index]
                    if (Colors.alpha(color) == 0) continue
                    val lum = Colors.luminance(color)
                    val value = if (lum >= limit) 255 else 0
                    pixels.pixels[index] = Colors.pack(Colors.alpha(color), value, value, value)
                }
            }
        }
    }

    class Vibrance(var vibrance: Float = 0f, var saturation: Float = 0f) : Adjustment() {
        override val title: String get() = "Vibrance"
        override fun copyOf() = Vibrance(vibrance, saturation)

        override fun apply(pixels: Pixmap, region: Rect) {
            val r = region.intersect(pixels.bounds)
            val satFactor = 1f + saturation / 100f
            val vib = vibrance.coerceIn(-100f, 100f) / 100f
            val hsv = FloatArray(3)
            for (y in r.y until r.bottom) {
                val row = y * pixels.width
                for (x in r.x until r.right) {
                    val index = row + x
                    val color = pixels.pixels[index]
                    if (Colors.alpha(color) == 0) continue
                    Colors.toHsv(color, hsv)
                    // Vibrance leans hardest on the least saturated pixels.
                    val boost = 1f + vib * (1f - hsv[1]) * 1.4f
                    val s = (hsv[1] * satFactor * boost).coerceIn(0f, 1f)
                    val out = FloatArray(3)
                    Colors.fromHsv(hsv[0], s, hsv[2], out)
                    pixels.pixels[index] = Colors.pack(
                        Colors.alpha(color),
                        (out[0] * 255f).toInt().coerceIn(0, 255),
                        (out[1] * 255f).toInt().coerceIn(0, 255),
                        (out[2] * 255f).toInt().coerceIn(0, 255),
                    )
                }
            }
        }
    }

    class HueSaturation(
        var hue: Float = 0f,
        var saturation: Float = 0f,
        var lightness: Float = 0f,
        var colorize: Boolean = false,
        var colorizeHue: Float = 0f,
        var colorizeSaturation: Float = 0.5f,
    ) : Adjustment() {

        override val title: String get() = "Hue/Saturation"
        override fun copyOf() = HueSaturation(hue, saturation, lightness, colorize, colorizeHue, colorizeSaturation)

        override fun apply(pixels: Pixmap, region: Rect) {
            val r = region.intersect(pixels.bounds)
            val hueShift = hue.coerceIn(-180f, 180f)
            val satFactor = 1f + saturation.coerceIn(-100f, 100f) / 100f
            val lightShift = lightness.coerceIn(-100f, 100f) / 100f
            val hsv = FloatArray(3)
            val rgb = FloatArray(3)
            for (y in r.y until r.bottom) {
                val row = y * pixels.width
                for (x in r.x until r.right) {
                    val index = row + x
                    val color = pixels.pixels[index]
                    if (Colors.alpha(color) == 0) continue
                    if (colorize) {
                        Colors.fromHsv(colorizeHue, colorizeSaturation, Colors.luminanceF(color), rgb)
                        pixels.pixels[index] = Colors.pack(
                            Colors.alpha(color),
                            (rgb[0] * 255f).toInt().coerceIn(0, 255),
                            (rgb[1] * 255f).toInt().coerceIn(0, 255),
                            (rgb[2] * 255f).toInt().coerceIn(0, 255),
                        )
                        continue
                    }
                    Colors.toHsv(color, hsv)
                    val v = (hsv[2] + lightShift).coerceIn(0f, 1f)
                    Colors.fromHsv(hsv[0] + hueShift, (hsv[1] * satFactor).coerceIn(0f, 1f), v, rgb)
                    pixels.pixels[index] = Colors.pack(
                        Colors.alpha(color),
                        (rgb[0] * 255f).toInt().coerceIn(0, 255),
                        (rgb[1] * 255f).toInt().coerceIn(0, 255),
                        (rgb[2] * 255f).toInt().coerceIn(0, 255),
                    )
                }
            }
        }
    }

    class ColorBalance(
        var shadows: FloatArray = floatArrayOf(0f, 0f, 0f),
        var midtones: FloatArray = floatArrayOf(0f, 0f, 0f),
        var highlights: FloatArray = floatArrayOf(0f, 0f, 0f),
        var preserveLuminosity: Boolean = true,
    ) : Adjustment() {

        override val title: String get() = "Color Balance"
        override fun copyOf() = ColorBalance(shadows.copyOf(), midtones.copyOf(), highlights.copyOf(), preserveLuminosity)

        override fun apply(pixels: Pixmap, region: Rect) {
            val r = region.intersect(pixels.bounds)
            for (y in r.y until r.bottom) {
                val row = y * pixels.width
                for (x in r.x until r.right) {
                    val index = row + x
                    val color = pixels.pixels[index]
                    val a = Colors.alpha(color)
                    if (a == 0) continue
                    val lum = Colors.luminance(color) / 255f
                    // Rough band weights: shadows at the dark end, highlights at the bright end.
                    val wShadow = max(0f, 1f - lum * 2f)
                    val wHighlight = max(0f, lum * 2f - 1f)
                    val wMid = 1f - wShadow - wHighlight
                    val channels = floatArrayOf(Colors.red(color).toFloat(), Colors.green(color).toFloat(), Colors.blue(color).toFloat())
                    for (c in 0..2) {
                        val delta = shadows[c] * wShadow + midtones[c] * wMid + highlights[c] * wHighlight
                        channels[c] = (channels[c] + delta * 2.55f).coerceIn(0f, 255f)
                    }
                    val wasLum = Colors.luminance(color).toFloat()
                    var out = Colors.pack(a, channels[0].toInt(), channels[1].toInt(), channels[2].toInt())
                    if (preserveLuminosity) out = matchLuminance(out, wasLum)
                    pixels.pixels[index] = out
                }
            }
        }

        private fun matchLuminance(color: Int, targetLuminance: Float): Int {
            val current = Colors.luminance(color).toFloat()
            if (current <= 0.01f) return color
            val factor = targetLuminance / current
            return Colors.pack(
                Colors.alpha(color),
                (Colors.red(color) * factor).toInt().coerceIn(0, 255),
                (Colors.green(color) * factor).toInt().coerceIn(0, 255),
                (Colors.blue(color) * factor).toInt().coerceIn(0, 255),
            )
        }
    }

    class ChannelMixer(
        var red: FloatArray = floatArrayOf(100f, 0f, 0f),
        var green: FloatArray = floatArrayOf(0f, 100f, 0f),
        var blue: FloatArray = floatArrayOf(0f, 0f, 100f),
        var monochrome: Boolean = false,
    ) : Adjustment() {

        override val title: String get() = "Channel Mixer"
        override fun copyOf() = ChannelMixer(red.copyOf(), green.copyOf(), blue.copyOf(), monochrome)

        override fun apply(pixels: Pixmap, region: Rect) {
            val r = region.intersect(pixels.bounds)
            for (y in r.y until r.bottom) {
                val row = y * pixels.width
                for (x in r.x until r.right) {
                    val index = row + x
                    val color = pixels.pixels[index]
                    val a = Colors.alpha(color)
                    if (a == 0) continue
                    val rr = Colors.red(color).toFloat()
                    val gg = Colors.green(color).toFloat()
                    val bb = Colors.blue(color).toFloat()
                    val nr = (rr * red[0] + gg * red[1] + bb * red[2]) / 100f
                    val ng = (rr * green[0] + gg * green[1] + bb * green[2]) / 100f
                    val nb = (rr * blue[0] + gg * blue[1] + bb * blue[2]) / 100f
                    val out = if (monochrome) {
                        val l = nr.coerceIn(0f, 255f).toInt()
                        Colors.pack(a, l, l, l)
                    } else {
                        Colors.pack(
                            a,
                            nr.toInt().coerceIn(0, 255),
                            ng.toInt().coerceIn(0, 255),
                            nb.toInt().coerceIn(0, 255),
                        )
                    }
                    pixels.pixels[index] = out
                }
            }
        }
    }

    class PhotoFilter(var color: Int = 0xFFEC8A00.toInt(), var density: Float = 0.25f, var preserveLuminosity: Boolean = true) : Adjustment() {
        override val title: String get() = "Photo Filter"
        override fun copyOf() = PhotoFilter(color, density, preserveLuminosity)

        override fun apply(pixels: Pixmap, region: Rect) {
            val r = region.intersect(pixels.bounds)
            val d = density.coerceIn(0f, 1f)
            for (y in r.y until r.bottom) {
                val row = y * pixels.width
                for (x in r.x until r.right) {
                    val index = row + x
                    val color0 = pixels.pixels[index]
                    val a = Colors.alpha(color0)
                    if (a == 0) continue
                    val before = Colors.luminance(color0).toFloat()
                    var out = Colors.lerp(color0, Colors.withAlpha(color, a), d)
                    if (preserveLuminosity) {
                        val current = Colors.luminance(out).toFloat()
                        if (current > 0.01f) {
                            val factor = before / current
                            out = Colors.pack(
                                a,
                                (Colors.red(out) * factor).toInt().coerceIn(0, 255),
                                (Colors.green(out) * factor).toInt().coerceIn(0, 255),
                                (Colors.blue(out) * factor).toInt().coerceIn(0, 255),
                            )
                        }
                    }
                    pixels.pixels[index] = out
                }
            }
        }
    }

    class Desaturate(var amount: Float = 1f) : Adjustment() {
        override val title: String get() = if (amount >= 1f) "Grayscale" else "Desaturate"
        override fun copyOf() = Desaturate(amount)

        override fun apply(pixels: Pixmap, region: Rect) {
            val r = region.intersect(pixels.bounds)
            val f = amount.coerceIn(0f, 1f)
            for (y in r.y until r.bottom) {
                val row = y * pixels.width
                for (x in r.x until r.right) {
                    val index = row + x
                    val color = pixels.pixels[index]
                    if (Colors.alpha(color) == 0) continue
                    val lum = Colors.luminance(color)
                    pixels.pixels[index] = Colors.pack(
                        Colors.alpha(color),
                        (Colors.red(color) + (lum - Colors.red(color)) * f).toInt().coerceIn(0, 255),
                        (Colors.green(color) + (lum - Colors.green(color)) * f).toInt().coerceIn(0, 255),
                        (Colors.blue(color) + (lum - Colors.blue(color)) * f).toInt().coerceIn(0, 255),
                    )
                }
            }
        }
    }

    /** Maps luminance through a gradient: the "Gradient Map" adjustment. */
    class GradientMap(var stops: Array<IntArray> = arrayOf(intArrayOf(0, 0), intArrayOf(255, -0x1))) : Adjustment() {
        override val title: String get() = "Gradient Map"
        override fun copyOf() = GradientMap(Array(stops.size) { stops[it].copyOf() })

        fun colorAt(position: Float): Int {
            val sorted = stops.sortedBy { it[0] }
            if (sorted.isEmpty()) return Colors.BLACK
            val p = (position.coerceIn(0f, 1f) * 255f)
            if (p <= sorted.first()[0]) return sorted.first()[1]
            if (p >= sorted.last()[0]) return sorted.last()[1]
            for (i in 0 until sorted.size - 1) {
                val a = sorted[i]
                val b = sorted[i + 1]
                if (p >= a[0] && p <= b[0]) {
                    val t = (p - a[0]) / (b[0] - a[0]).coerceAtLeast(1).toFloat()
                    return Colors.lerp(a[1], b[1], t)
                }
            }
            return sorted.last()[1]
        }

        override fun apply(pixels: Pixmap, region: Rect) {
            val r = region.intersect(pixels.bounds)
            val table = IntArray(256) { colorAt(it / 255f) }
            for (y in r.y until r.bottom) {
                val row = y * pixels.width
                for (x in r.x until r.right) {
                    val index = row + x
                    val color = pixels.pixels[index]
                    val a = Colors.alpha(color)
                    if (a == 0) continue
                    val mapped = table[Colors.luminance(color)]
                    pixels.pixels[index] = Colors.withAlpha(mapped, a)
                }
            }
        }
    }

    companion object {
        val IDENTITY = IntArray(256) { it }

        /** The adjustments offered in the "new adjustment layer" sheet. */
        fun catalog(): List<Pair<String, () -> Adjustment>> = listOf(
            "Brightness/Contrast" to { BrightnessContrast() },
            "Levels" to { Levels() },
            "Curves" to { Curves.linear() },
            "Exposure" to { Exposure() },
            "Vibrance" to { Vibrance() },
            "Hue/Saturation" to { HueSaturation() },
            "Color Balance" to { ColorBalance() },
            "Channel Mixer" to { ChannelMixer() },
            "Photo Filter" to { PhotoFilter() },
            "Gradient Map" to { GradientMap() },
            "Invert" to { Invert() },
            "Posterize" to { Posterize() },
            "Threshold" to { Threshold() },
            "Desaturate" to { Desaturate() },
        )
    }
}
