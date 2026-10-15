package com.strata.editor.platform

import android.content.Context
import android.graphics.Typeface
import com.strata.editor.core.ImageIo
import com.strata.editor.core.LayerRenderer

/**
 * Wires the engine to the device. Called once, from the activity, before any
 * document is loaded — until it runs, the engine has no text drawing and no way to
 * read a JPEG, and has to say so rather than crash.
 */
object Platform {

    var textRasterizer: AndroidTextRasterizer? = null
        private set

    var imageCodec: AndroidImageCodec? = null
        private set

    var fonts: List<String> = AndroidTextRasterizer.DEFAULT_FAMILIES
        private set

    fun install(context: Context) {
        val codec = AndroidImageCodec()
        ImageIo.codec = codec
        imageCodec = codec
        val rasterizer = AndroidTextRasterizer.install()
        textRasterizer = rasterizer
        fonts = rasterizer.families()
        // Touch the typeface list once, so the first text layer does not pay for it.
        Typeface.create("sans-serif", Typeface.NORMAL)
        context.cacheDir
    }
}
