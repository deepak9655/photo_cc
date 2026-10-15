package com.strata.editor.core

/**
 * Platform text drawing.
 *
 * The engine has no `android.graphics`, so text is rasterised through this
 * interface: the Android build supplies a canvas implementation, the desktop test
 * harness an AWT one. Both are handed the same [TextContent], so a document laid
 * out on the phone and one laid out in the tests agree about its size.
 */
interface TextRasterizer {
    /**
     * Renders `content`, positioned at `layout`, clipped to `region` — both in
     * document coordinates. The returned pixmap covers `region` exactly, so the
     * compositor can blend it straight in.
     */
    fun rasterize(content: TextContent, layout: Rect, region: Rect): Pixmap?

    /** The size the text occupies, in document pixels. */
    fun measure(content: TextContent): Rect

    /** Font families this device can actually draw. */
    fun families(): List<String> = emptyList()
}
