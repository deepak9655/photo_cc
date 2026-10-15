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
    /** Renders the text so its top-left corner is at the region's origin. */
    fun rasterize(content: TextContent, region: Rect): Pixmap?

    /** The size the text occupies, in document pixels. */
    fun measure(content: TextContent): Rect
}
