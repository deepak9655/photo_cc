package com.strata.editor.core

/**
 * Rasterises the contents of layers that have no pixels of their own.
 *
 *  * **Solid colour** — fills the layer's bounds with one colour.
 *  * **Adjustment** — rewrites the backdrop it receives, which is what makes an
 *    adjustment layer non-destructive: the pixels below it are read, adjusted and
 *    handed back, and the original pixels are untouched.
 *  * **Text** — delegated to [TextRasterizer], which the platform implements.
 */
object LayerRenderer {

    /** Platform text drawing, installed by the Android build or the test harness. */
    var textRasterizer: TextRasterizer? = null

    fun render(layer: Layer, region: Rect, backdrop: Pixmap?): Pixmap? {
        layer.solidColor?.let { color ->
            val bounds = layer.areaBounds() ?: return null
            val out = Pixmap(region.w, region.h)
            val target = bounds.intersect(region)
            if (target.isEmpty) return out
            for (y in target.y until target.bottom) {
                val row = (y - region.y) * out.width
                java.util.Arrays.fill(out.pixels, row + (target.x - region.x), row + (target.right - region.x), color)
            }
            return out
        }

        layer.adjustment?.let { adjustment ->
            // Adjustments need what is underneath them; without a backdrop there is
            // nothing to adjust, and the layer contributes nothing.
            val source = backdrop ?: return null
            val out = source.copy()
            adjustment.apply(out, out.bounds)
            return out
        }

        layer.text?.let { text ->
            val rasterizer = textRasterizer ?: return null
            val layout = layoutBounds(layer)
            return rasterizer.rasterize(text, layout, region)
        }

        return null
    }

    /** The rectangle a non-raster layer occupies in document space. */
    fun areaBounds(layer: Layer): Rect? = layer.areaBounds()

    /**
     * Where a text layer sits. The layer keeps its own layout rectangle; when a
     * file did not carry one, the text is placed at the layer's position and a
     * sensible box is derived from what it measures.
     */
    fun layoutBounds(layer: Layer): Rect {
        val layout = layer.textLayout
        if (!layout.isEmpty) return layout
        val text = layer.text
        val measured = if (text != null) textRasterizer?.measure(text) else null
        val width = measured?.w?.takeIf { it > 0 } ?: maxOf(1, layer.contentWidth)
        val height = measured?.h?.takeIf { it > 0 } ?: maxOf(1, layer.contentHeight)
        return Rect(layer.x, layer.y, width, height)
    }
}
