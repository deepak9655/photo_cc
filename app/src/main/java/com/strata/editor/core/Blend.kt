package com.strata.editor.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The blend modes, with the same meaning they have in every other layer-based
 * editor, implemented from the published compositing formulas.
 *
 * `PASS_THROUGH` is not a real blend: it is what a group does when it should not
 * be isolated from the layers underneath it.
 */
enum class BlendMode(val label: String, val separable: Boolean = true) {
    NORMAL("Normal"),
    DISSOLVE("Dissolve"),
    DARKEN("Darken"),
    MULTIPLY("Multiply"),
    COLOR_BURN("Color Burn"),
    LINEAR_BURN("Linear Burn"),
    DARKER_COLOR("Darker Color"),
    LIGHTEN("Lighten"),
    SCREEN("Screen"),
    COLOR_DODGE("Color Dodge"),
    LINEAR_DODGE("Linear Dodge (Add)"),
    LIGHTER_COLOR("Lighter Color"),
    OVERLAY("Overlay"),
    SOFT_LIGHT("Soft Light"),
    HARD_LIGHT("Hard Light"),
    VIVID_LIGHT("Vivid Light"),
    LINEAR_LIGHT("Linear Light"),
    PIN_LIGHT("Pin Light"),
    HARD_MIX("Hard Mix"),
    DIFFERENCE("Difference"),
    EXCLUSION("Exclusion"),
    SUBTRACT("Subtract"),
    DIVIDE("Divide"),
    HUE("Hue"),
    SATURATION("Saturation"),
    COLOR("Color"),
    LUMINOSITY("Luminosity"),
    PASS_THROUGH("Pass Through"),
    ;

    val isPassThrough: Boolean get() = this == PASS_THROUGH
    val isNonSeparable: Boolean get() = this == HUE || this == SATURATION || this == COLOR || this == LUMINOSITY
}

/**
 * Blends `source` (a straight-alpha colour) onto `backdrop`.
 *
 * This is the general Porter-Duff source-over form extended with a blend
 * function: `Cr = (1 - as)·Cb + as·[(1 - ab)·Cs + ab·B(Cb, Cs)]`, and
 * `ar = as + ab·(1 - as)`.
 */
object Blend {

    fun apply(mode: BlendMode, backdrop: Int, source: Int): Int {
        val asr = Colors.alpha(source)
        if (asr == 0) return backdrop
        if (asr == 255 && mode == BlendMode.NORMAL) return source
        val abr = Colors.alpha(backdrop)

        // Fast path: nothing underneath, so the blend result is the source.
        if (abr == 0 && !mode.isNonSeparable) return source
        if (abr == 0 && mode.isNonSeparable) return source

        val af = asr / 255f
        val abf = abr / 255f
        val outAlpha = asr + (abr * (255 - asr) + 127) / 255
        if (outAlpha == 0) return 0
        val afOut = outAlpha / 255f

        val cs = floatArrayOf(Colors.red(source) / 255f, Colors.green(source) / 255f, Colors.blue(source) / 255f)
        val cb = floatArrayOf(Colors.red(backdrop) / 255f, Colors.green(backdrop) / 255f, Colors.blue(backdrop) / 255f)

        val blended = if (mode.isNonSeparable) nonSeparable(mode, cb, cs) else {
            floatArrayOf(
                separable(mode, cb[0], cs[0]),
                separable(mode, cb[1], cs[1]),
                separable(mode, cb[2], cs[2]),
            )
        }

        val out = FloatArray(3)
        for (i in 0..2) {
            val mixed = (1f - abf) * cs[i] + abf * blended[i]
            out[i] = ((1f - af) * cb[i] + af * mixed) / afOut
        }
        return Colors.pack(
            outAlpha,
            (out[0] * 255f + 0.5f).toInt().coerceIn(0, 255),
            (out[1] * 255f + 0.5f).toInt().coerceIn(0, 255),
            (out[2] * 255f + 0.5f).toInt().coerceIn(0, 255),
        )
    }

    /** `B(Cb, Cs)` for the separable modes; inputs and output are 0..1. */
    fun separable(mode: BlendMode, cb: Float, cs: Float): Float = when (mode) {
        BlendMode.NORMAL, BlendMode.PASS_THROUGH -> cs
        BlendMode.DISSOLVE -> cs
        BlendMode.MULTIPLY -> cb * cs
        BlendMode.SCREEN -> cb + cs - cb * cs
        BlendMode.OVERLAY -> hardLight(cs, cb)
        BlendMode.DARKEN -> min(cb, cs)
        BlendMode.LIGHTEN -> max(cb, cs)
        BlendMode.COLOR_DODGE -> colorDodge(cb, cs)
        BlendMode.COLOR_BURN -> colorBurn(cb, cs)
        BlendMode.LINEAR_BURN -> max(0f, cb + cs - 1f)
        BlendMode.LINEAR_DODGE -> min(1f, cb + cs)
        BlendMode.HARD_LIGHT -> hardLight(cb, cs)
        BlendMode.SOFT_LIGHT -> softLight(cb, cs)
        BlendMode.VIVID_LIGHT -> if (cs <= 0.5f) colorBurn(cb, 2f * cs) else colorDodge(cb, 2f * cs - 1f)
        BlendMode.LINEAR_LIGHT -> (cb + 2f * cs - 1f).coerceIn(0f, 1f)
        BlendMode.PIN_LIGHT -> if (cs < 0.5f) min(cb, 2f * cs) else max(cb, 2f * cs - 1f)
        BlendMode.HARD_MIX -> if (cb + cs >= 1f) 1f else 0f
        BlendMode.DIFFERENCE -> abs(cb - cs)
        BlendMode.EXCLUSION -> cb + cs - 2f * cb * cs
        BlendMode.SUBTRACT -> max(0f, cb - cs)
        BlendMode.DIVIDE -> if (cs <= 0f) 1f else min(1f, cb / cs)
        BlendMode.DARKER_COLOR -> if (cb <= cs) cb else cs
        BlendMode.LIGHTER_COLOR -> if (cb >= cs) cb else cs
        else -> cs
    }

    /** `B(Cb, Cs) = min(1, Cb / (1 - Cs))`, with the end points pinned. */
    private fun colorDodge(cb: Float, cs: Float): Float {
        if (cb <= 0f) return 0f
        if (cs >= 1f) return 1f
        return min(1f, cb / (1f - cs))
    }

    /** `B(Cb, Cs) = 1 - min(1, (1 - Cb) / Cs)`, with the end points pinned. */
    private fun colorBurn(cb: Float, cs: Float): Float {
        if (cb >= 1f) return 1f
        if (cs <= 0f) return 0f
        return 1f - min(1f, (1f - cb) / cs)
    }

    private fun hardLight(cb: Float, cs: Float): Float =
        if (cs <= 0.5f) 2f * cs * cb else 1f - 2f * (1f - cs) * (1f - cb)

    private fun softLight(cb: Float, cs: Float): Float {
        val d = if (cb <= 0.25f) ((16f * cb - 12f) * cb + 4f) * cb else kotlin.math.sqrt(cb)
        return if (cs <= 0.5f) {
            cb - (1f - 2f * cs) * cb * (1f - cb)
        } else {
            cb + (2f * cs - 1f) * (d - cb)
        }
    }

    /* ------------------------------------------------- non-separable modes */

    private fun nonSeparable(mode: BlendMode, cb: FloatArray, cs: FloatArray): FloatArray {
        val lumB = lum(cb)
        val lumS = lum(cs)
        return when (mode) {
            BlendMode.HUE -> setLum(setSat(cs.copyOf(), sat(cb)), lumB)
            BlendMode.SATURATION -> setLum(setSat(cb.copyOf(), sat(cs)), lumB)
            BlendMode.COLOR -> setLum(cs.copyOf(), lumB)
            BlendMode.LUMINOSITY -> setLum(cb.copyOf(), lumS)
            else -> cs
        }
    }

    private fun lum(c: FloatArray): Float = 0.3f * c[0] + 0.59f * c[1] + 0.11f * c[2]

    private fun sat(c: FloatArray): Float = max(c[0], max(c[1], c[2])) - min(c[0], min(c[1], c[2]))

    private fun setLum(c: FloatArray, l: Float): FloatArray {
        val d = l - lum(c)
        c[0] += d; c[1] += d; c[2] += d
        return clip(c)
    }

    private fun setSat(c: FloatArray, s: Float): FloatArray {
        val maxI = if (c[0] >= c[1] && c[0] >= c[2]) 0 else if (c[1] >= c[2]) 1 else 2
        val minI = if (c[0] <= c[1] && c[0] <= c[2]) 0 else if (c[1] <= c[2]) 1 else 2
        if (c[maxI] <= c[minI]) return floatArrayOf(0f, 0f, 0f)
        val mid = 3 - maxI - minI
        c[mid] = (c[mid] - c[minI]) * s / (c[maxI] - c[minI])
        c[maxI] = s
        c[minI] = 0f
        return c
    }

    private fun clip(c: FloatArray): FloatArray {
        val l = lum(c)
        val minC = min(c[0], min(c[1], c[2]))
        val maxC = max(c[0], max(c[1], c[2]))
        if (minC < 0f) {
            for (i in 0..2) c[i] = l + (c[i] - l) * l / (l - minC + 1e-6f)
        }
        if (maxC > 1f) {
            for (i in 0..2) c[i] = l + (c[i] - l) * (1f - l) / (maxC - l + 1e-6f)
        }
        return c
    }
}
