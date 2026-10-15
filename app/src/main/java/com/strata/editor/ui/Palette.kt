package com.strata.editor.ui

import android.content.Context
import android.util.TypedValue
import com.strata.editor.core.Colors

/**
 * The look of the app, in one place.
 *
 * Graphite for the chrome and one accent for anything the user is touching: the
 * picture is the only colourful thing on screen, and every control has to be
 * readable in daylight on a phone held at arm's length. Sizes are in density
 * independent pixels and converted once, so nothing drifts on a dense screen.
 */
object Palette {

    val background = 0xFF14161A.toInt()
    val surface = 0xFF1C1F24.toInt()
    val surfaceHigh = 0xFF262A31.toInt()
    val surfaceMax = 0xFF32373F.toInt()
    val textPrimary = 0xFFF2F4F8.toInt()
    val textDim = 0xFF9BA3AF.toInt()
    val textFaint = 0x66F2F4F8.toInt()
    val accent = 0xFF7C6CFF.toInt()
    val accentSoft = 0x337C6CFF.toInt()
    val danger = 0xFFFF6B6B.toInt()
    val success = 0xFF4ADE80.toInt()
    val scrim = 0x99000000.toInt()
    val shadow = 0x66000000.toInt()

    /** The checkerboard behind transparent pixels — quiet, but unmistakable. */
    val checker = 0xFF2A2E35.toInt()
    val checkerAlt = 0xFF23262C.toInt()

    var density = 1f
        private set

    /** Smallest touch target that is comfortable on a phone, in pixels. */
    val touch: Float get() = 48f * density
    val railHeight: Float get() = 60f * density
    val topBarHeight: Float get() = 52f * density
    val radius: Float get() = 12f * density

    fun install(context: Context) {
        density = context.resources.displayMetrics.density
    }

    fun dp(value: Float): Float = value * density

    fun sp(value: Float): Float = value * density

    /** Turns a colour into a soft halo, used behind the active control. */
    fun glow(colour: Int, alpha: Int = 0x33): Int = Colors.pack(alpha, Colors.red(colour), Colors.green(colour), Colors.blue(colour))

    fun themes(context: Context): Int {
        val value = TypedValue()
        context.theme.resolveAttribute(android.R.attr.colorAccent, value, true)
        return if (value.type == TypedValue.TYPE_INT_COLOR_ARGB8 || value.type == TypedValue.TYPE_INT_COLOR_RGB8) value.data else accent
    }
}
