package com.strata.editor.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Colour arithmetic on 32-bit ARGB values, always **straight** (non-premultiplied)
 * alpha, matching what `android.graphics.Bitmap` gives us and what the PSD and PNG
 * formats store.
 */
object Colors {

    const val TRANSPARENT = 0
    const val BLACK = 0xFF000000.toInt()
    const val WHITE = -0x1

    fun alpha(color: Int): Int = (color ushr 24) and 0xFF
    fun red(color: Int): Int = (color ushr 16) and 0xFF
    fun green(color: Int): Int = (color ushr 8) and 0xFF
    fun blue(color: Int): Int = color and 0xFF

    fun pack(a: Int, r: Int, g: Int, b: Int): Int =
        ((a and 0xFF) shl 24) or ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)

    fun withAlpha(color: Int, a: Int): Int = (color and 0x00FFFFFF) or ((a and 0xFF) shl 24)

    fun rgb(r: Int, g: Int, b: Int): Int = pack(255, r, g, b)

    fun scaleAlpha(color: Int, factor: Int): Int {
        val a = alpha(color)
        if (a == 0 || factor >= 255) return color
        return withAlpha(color, (a * factor + 127) / 255)
    }

    /** Multiplies alpha by a 0..1 float. */
    fun scaleAlpha(color: Int, factor: Float): Int =
        if (factor >= 1f) color else scaleAlpha(color, (factor.coerceIn(0f, 1f) * 255f + 0.5f).toInt())

    /** Multiplies the colour channels by a 0..1 factor, leaving alpha alone. */
    fun scaleChannels(color: Int, factor: Float): Int {
        if (factor >= 1f) return color
        val f = factor.coerceAtLeast(0f)
        return pack(alpha(color), (red(color) * f).toInt(), (green(color) * f).toInt(), (blue(color) * f).toInt())
    }

    /** Straight-alpha source-over: `src` drawn on top of `dst`. */
    fun over(dst: Int, src: Int): Int {
        val sa = alpha(src)
        if (sa == 255) return src
        if (sa == 0) return dst
        val da = alpha(dst)
        if (da == 0) return src
        val outA = sa + da * (255 - sa) / 255
        if (outA == 0) return 0
        val sr = red(src); val sg = green(src); val sb = blue(src)
        val dr = red(dst); val dg = green(dst); val db = blue(dst)
        // out = (src * sa + dst * da * (1 - sa)) / outA
        val keep = da * (255 - sa) / 255
        val r = (sr * sa + dr * keep) / outA
        val g = (sg * sa + dg * keep) / outA
        val b = (sb * sa + db * keep) / outA
        return pack(outA, r, g, b)
    }

    /** Perceptual luminance, 0..255. */
    fun luminance(color: Int): Int =
        (0.299f * red(color) + 0.587f * green(color) + 0.114f * blue(color)).toInt().coerceIn(0, 255)

    fun luminanceF(color: Int): Float =
        (0.299f * red(color) + 0.587f * green(color) + 0.114f * blue(color)) / 255f

    fun lerp(from: Int, to: Int, t: Float): Int {
        val f = t.coerceIn(0f, 1f)
        val a = alpha(from) + ((alpha(to) - alpha(from)) * f).toInt()
        val r = red(from) + ((red(to) - red(from)) * f).toInt()
        val g = green(from) + ((green(to) - green(from)) * f).toInt()
        val b = blue(from) + ((blue(to) - blue(from)) * f).toInt()
        return pack(a, r, g, b)
    }

    /** Hue 0..360, saturation 0..1, value 0..1. */
    fun toHsv(color: Int, out: FloatArray) {
        val r = red(color) / 255f
        val g = green(color) / 255f
        val b = blue(color) / 255f
        val maxC = max(r, max(g, b))
        val minC = min(r, min(g, b))
        val delta = maxC - minC
        val hue = when {
            delta < 1e-6f -> 0f
            maxC == r -> 60f * (((g - b) / delta) % 6f)
            maxC == g -> 60f * (((b - r) / delta) + 2f)
            else -> 60f * (((r - g) / delta) + 4f)
        }
        out[0] = if (hue < 0f) hue + 360f else hue
        out[1] = if (maxC <= 0f) 0f else delta / maxC
        out[2] = maxC
    }

    fun fromHsv(hue: Float, saturation: Float, value: Float, out: FloatArray) {
        val h = ((hue % 360f) + 360f) % 360f
        val s = saturation.coerceIn(0f, 1f)
        val v = value.coerceIn(0f, 1f)
        val c = v * s
        val x = c * (1f - abs((h / 60f) % 2f - 1f))
        val m = v - c
        val (r1, g1, b1) = when {
            h < 60f -> Triple(c, x, 0f)
            h < 120f -> Triple(x, c, 0f)
            h < 180f -> Triple(0f, c, x)
            h < 240f -> Triple(0f, x, c)
            h < 300f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        out[0] = r1 + m
        out[1] = g1 + m
        out[2] = b1 + m
    }

    fun fromHsv(hue: Float, saturation: Float, value: Float): Int {
        val out = FloatArray(3)
        fromHsv(hue, saturation, value, out)
        return rgb((out[0] * 255f).toInt(), (out[1] * 255f).toInt(), (out[2] * 255f).toInt())
    }

    fun isOpaque(color: Int): Boolean = alpha(color) == 255
    fun isTransparent(color: Int): Boolean = alpha(color) == 0

    /** `#RRGGBB` or `#AARRGGBB`, or null when the text is not a colour. */
    fun parseHex(text: String): Int? {
        val cleaned = text.trim().removePrefix("#").replace(" ", "")
        if (cleaned.length != 6 && cleaned.length != 8) return null
        if (cleaned.any { it !in "0123456789abcdefABCDEF" }) return null
        val value = cleaned.toLongOrNull(16) ?: return null
        return if (cleaned.length == 6) (0xFF000000L or value).toInt() else value.toInt()
    }

    fun toHex(color: Int): String = String.format("%06X", color and 0xFFFFFF)

    fun toHexWithAlpha(color: Int): String = String.format("%08X", color)
}
