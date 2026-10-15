package com.strata.editor.core

/**
 * Renders a document into a pixel buffer.
 *
 * The compositor walks the layer stack from the bottom up, drawing each layer
 * into an accumulation buffer layer by layer, but only inside the rectangle being
 * rendered — a phone never has enough memory to hold several full-canvas
 * buffers, so callers pass the region they need and huge documents are rendered
 * in bands.
 *
 * Groups are handled properly: a group that is not pass-through gets its own
 * buffer, its children are composited into it, and the finished group is blended
 * into the backdrop once. Pass-through groups simply paint their children
 * straight into the backdrop, which is what "pass through" means.
 */
object Compositor {

    /** Pixels per band when rendering something too big to hold at once. */
    const val BAND_PIXELS = 2_000_000

    /**
     * Renders `region` of `doc` into `out`, which holds exactly `region.w * region.h`
     * pixels. `background` is painted first (0 for transparent).
     */
    fun render(doc: Document, out: Pixmap, background: Int = 0, region: Rect = doc.bounds) {
        val target = region.clampTo(doc.width, doc.height)
        if (target.isEmpty) return
        if (out.width != target.w || out.height != target.h) {
            throw IllegalArgumentException(
                "Output buffer ${out.width}x${out.height} does not match region ${target.w}x${target.h}",
            )
        }
        out.fill(background)
        compose(doc.layers, out, target, 0, 0)
    }

    /**
     * Composites bands of a document into an output buffer of arbitrary size,
     * box-filtering as it goes.
     *
     * This is the path the on-screen canvas uses: the document may be 100 MPix
     * while the screen is 2 MPix, and the intermediate buffers stay small no
     * matter how big the document is.
     */
    fun renderScaled(
        doc: Document,
        out: Pixmap,
        outRegion: Rect,
        background: Int = 0,
        quality: Int = 1,
    ): Boolean {
        if (outRegion.isEmpty) return false
        val perOutputPixel = (doc.width.toDouble() * doc.height) / (outRegion.w.toDouble() * outRegion.h)
        if (perOutputPixel <= 1.0 && quality == 1) {
            // Zoomed in: one band, pixel-exact, no filtering needed.
            val region = outRegion.clampTo(doc.width, doc.height)
            if (region.isEmpty) return false
            out.fill(background)
            compose(doc.layers, out, region, region.x, region.y)
            return true
        }
        return renderBanded(doc, out, outRegion, background, quality)
    }

    /**
     * The banded path: for each output row band, render the document rows it needs
     * at full resolution, then box-filter down. Peak memory is one band of
     * document pixels plus the output buffer.
     */
    private fun renderBanded(doc: Document, out: Pixmap, outRegion: Rect, background: Int, quality: Int): Boolean {
        val scaleX = outRegion.w.toDouble() / doc.width
        val scaleY = outRegion.h.toDouble() / doc.height
        if (scaleX <= 0 || scaleY <= 0) return false

        // How many output rows fit in the band budget.
        val docRowsPerBand = ((BAND_PIXELS.toDouble() / doc.width).toInt()).coerceAtLeast(1)
        val outRowsPerBand = ((docRowsPerBand * scaleY).toInt()).coerceAtLeast(1)

        val band = IntArray(doc.width * docRowsPerBand)
        val bandPixmap = Pixmap(doc.width, docRowsPerBand, band)
        val rowTmp = IntArray(outRegion.w)

        var outRow = 0
        while (outRow < outRegion.h) {
            val rows = minOf(outRowsPerBand, outRegion.h - outRow)
            val docY0 = (outRow / scaleY).toInt()
            val docY1 = minOf(doc.height, (((outRow + rows) / scaleY).toInt()) + 1)
            val docRows = docY1 - docY0
            if (docRows <= 0) break
            val usable = minOf(docRows, docRowsPerBand)
            val region = Rect(0, docY0, doc.width, usable)

            val bandView = if (usable == docRowsPerBand) bandPixmap else Pixmap(doc.width, usable, band)
            compose(doc.layers, bandView, region, 0, 0, background)

            for (i in 0 until rows) {
                val y = outRow + i
                // Map this output row to the band rows it covers.
                val srcY0 = ((y / scaleY).toInt() - docY0).coerceIn(0, usable - 1)
                val srcY1 = ((((y + 1) / scaleY).toInt()) - docY0).coerceAtMost(usable)
                val spanY = (srcY1 - srcY0).coerceAtLeast(1)
                for (ox in 0 until outRegion.w) {
                    val srcX0 = (ox / scaleX).toInt().coerceIn(0, doc.width - 1)
                    val srcX1 = (((ox + 1) / scaleX).toInt()).coerceAtMost(doc.width)
                    val spanX = (srcX1 - srcX0).coerceAtLeast(1)
                    var a = 0L; var r = 0L; var g = 0L; var b = 0L
                    var count = 0
                    for (sy in srcY0 until minOf(srcY0 + spanY, usable)) {
                        val row = sy * doc.width
                        for (sx in srcX0 until srcX0 + spanX) {
                            val p = band[row + sx]
                            val pa = Colors.alpha(p)
                            a += pa
                            r += Colors.red(p) * pa
                            g += Colors.green(p) * pa
                            b += Colors.blue(p) * pa
                            count++
                        }
                    }
                    rowTmp[ox] = if (count == 0 || a == 0L) {
                        if (count == 0) background else 0
                    } else {
                        Colors.pack(
                            (a / count).toInt().coerceIn(0, 255),
                            (r / a).toInt().coerceIn(0, 255),
                            (g / a).toInt().coerceIn(0, 255),
                            (b / a).toInt().coerceIn(0, 255),
                        )
                    }
                }
                System.arraycopy(rowTmp, 0, out.pixels, y * out.width, outRegion.w)
            }
            outRow += rows
        }
        return true
    }

    /**
     * Composites `layers` into `dst`, where `dst` is `region` of the document
     * mapped to `dst`'s own coordinates. `docOffsetX/Y` say where the region sits.
     */
    fun compose(
        layers: List<Layer>,
        dst: Pixmap,
        region: Rect,
        docOffsetX: Int,
        docOffsetY: Int,
        background: Int = 0,
    ) {
        fun paint(items: List<Layer>) {
            for (layer in items) {
                if (!layer.visible || layer.opacity <= 0f) continue
                when {
                    layer.isGroup -> {
                        // A pass-through group hands its children straight to the
                        // backdrop; anything else is composited on its own first.
                        if (layer.blend.isPassThrough) paint(layer.children)
                        else paintGroup(layer, dst, region, docOffsetX, docOffsetY)
                    }
                    layer.adjustment != null -> applyAdjustmentLayer(layer, dst, region)
                    else -> paintLayer(layer, dst, region, docOffsetX, docOffsetY)
                }
            }
        }
        paint(layers)
    }

    /**
     * An adjustment layer rewrites the backdrop under it: the accumulated pixels
     * are copied, adjusted, and written back (through the layer's mask and opacity
     * when it has one, so an adjustment can be painted in).
     */
    private fun applyAdjustmentLayer(layer: Layer, dst: Pixmap, region: Rect) {
        val adjustment = layer.adjustment ?: return
        val adjusted = dst.copy()
        adjustment.apply(adjusted, adjusted.bounds)
        val alphaScale = (layer.opacity.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        if (alphaScale == 0) return
        val mask = layer.mask
        val area = layer.areaBounds()
        for (y in 0 until dst.height) {
            val docY = region.y + y
            for (x in 0 until dst.width) {
                val docX = region.x + x
                if (area != null && !area.contains(docX, docY)) continue
                var weight = alphaScale
                if (mask != null) {
                    val mx = docX - layer.x
                    val my = docY - layer.y
                    weight = weight * Colors.luminance(mask[mx, my]) / 255
                }
                if (weight == 0) continue
                val index = y * dst.width + x
                val target = adjusted.pixels[index]
                dst.pixels[index] = if (weight >= 255) target else Colors.lerp(dst.pixels[index], target, weight / 255f)
            }
        }
    }

    /** Composites a group into a scratch buffer, then blends that into `dst`. */
    private fun paintGroup(group: Layer, dst: Pixmap, region: Rect, docOffsetX: Int, docOffsetY: Int) {
        val scratch = Pixmap(region.w, region.h)
        compose(group.children, scratch, region, docOffsetX, docOffsetY, 0)
        blendBuffer(scratch, dst, group.opacity, group.blend)
    }

    /** Draws one layer: content, then mask, then blend into the accumulation buffer. */
    private fun paintLayer(layer: Layer, dst: Pixmap, region: Rect, docOffsetX: Int, docOffsetY: Int) {
        val pixmap = layer.pixels
        val source = pixmap ?: LayerRenderer.render(layer, region, dst) ?: return

        val alphaScale = (layer.opacity.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        val matrix = layer.transform
        val layerX = layer.x
        val layerY = layer.y

        // Fast path: no transform, integer offset — copy with clipping.
        if (matrix == null) {
            val target = Rect(layerX, layerY, source.width, source.height).intersect(region)
            if (target.isEmpty) return
            if (layer.blend == BlendMode.NORMAL && layer.mask == null && alphaScale == 255 && source === pixmap) {
                copyOpaque(source, dst, target, layerX, layerY, docOffsetX, docOffsetY)
                return
            }
            for (y in target.y until target.bottom) {
                val srcRow = (y - layerY) * source.width
                val dstRow = (y - docOffsetY) * dst.width + (target.x - docOffsetX)
                for (x in target.x until target.right) {
                    val sx = x - layerX
                    var src = source.pixels[srcRow + sx]
                    if (src == 0) continue
                    if (layer.mask != null) {
                        val maskIndex = (y - layerY) * layer.mask!!.width + sx
                        val m = if (maskIndex in layer.mask!!.pixels.indices) layer.mask!!.pixels[maskIndex] else 0
                        src = Colors.withAlpha(src, Colors.alpha(src) * Colors.luminance(m) / 255)
                    }
                    src = Colors.scaleAlpha(src, alphaScale)
                    val index = dstRow + (x - target.x)
                    dst.pixels[index] = if (layer.blend == BlendMode.NORMAL) Colors.over(dst.pixels[index], src) else Blend.apply(layer.blend, dst.pixels[index], src)
                }
            }
            return
        }

        // General path: walk the destination pixels this layer can cover and
        // inverse-map each one back into the layer.
        val inverse = matrix.invert() ?: return
        val layerBounds = matrix.mapRect(Rect(layerX, layerY, source.width, source.height))
        val target = layerBounds.intersect(region)
        if (target.isEmpty) return
        for (y in target.y until target.bottom) {
            val dstRow = (y - docOffsetY) * dst.width
            for (x in target.x until target.right) {
                val fx = inverse.mapX(x + 0.5f, y + 0.5f) - layerX
                val fy = inverse.mapY(x + 0.5f, y + 0.5f) - layerY
                if (fx < 0f || fy < 0f || fx >= source.width || fy >= source.height) continue
                var src = source.sampleBilinear(fx, fy)
                if (src == 0) continue
                if (layer.mask != null) {
                    val mx = (fx + layerX).toInt()
                    val my = (fy + layerY).toInt()
                    val m = layer.mask!![mx, my]
                    src = Colors.withAlpha(src, Colors.alpha(src) * Colors.luminance(m) / 255)
                }
                src = Colors.scaleAlpha(src, alphaScale)
                val index = dstRow + (x - docOffsetX)
                if (index in dst.pixels.indices) {
                    dst.pixels[index] = if (layer.blend == BlendMode.NORMAL) Colors.over(dst.pixels[index], src) else Blend.apply(layer.blend, dst.pixels[index], src)
                }
            }
        }
    }

    /** Plain source-over copy for the common case: no mask, full opacity, normal blend. */
    private fun copyOpaque(source: Pixmap, dst: Pixmap, target: Rect, layerX: Int, layerY: Int, docOffsetX: Int, docOffsetY: Int) {
        for (y in target.y until target.bottom) {
            var dstIndex = (y - docOffsetY) * dst.width + (target.x - docOffsetX)
            var srcIndex = (y - layerY) * source.width + (target.x - layerX)
            for (x in target.x until target.right) {
                val src = source.pixels[srcIndex]
                if (src != 0) {
                    val back = dst.pixels[dstIndex]
                    dst.pixels[dstIndex] = if (Colors.alpha(src) == 255) src else Colors.over(back, src)
                }
                srcIndex++
                dstIndex++
            }
        }
    }

    /** Blends a whole buffer (a rendered group) into `dst` with opacity and mode. */
    fun blendBuffer(source: Pixmap, dst: Pixmap, opacity: Float, mode: BlendMode) {
        val alphaScale = (opacity.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        if (alphaScale == 0) return
        val count = minOf(source.pixels.size, dst.pixels.size)
        for (i in 0 until count) {
            val src = source.pixels[i]
            if (src == 0) continue
            val scaled = Colors.scaleAlpha(src, alphaScale)
            dst.pixels[i] = if (mode == BlendMode.NORMAL || mode == BlendMode.PASS_THROUGH) {
                Colors.over(dst.pixels[i], scaled)
            } else {
                Blend.apply(mode, dst.pixels[i], scaled)
            }
        }
    }

    /**
     * Flattens a document into a single pixmap, in bands, so even a 200 MPix
     * document does not need 800 MB at once.
     */
    fun flatten(doc: Document, destination: Pixmap, background: Int = 0, region: Rect = doc.bounds) {
        val target = region.clampTo(doc.width, doc.height)
        if (target.isEmpty) return
        destination.fill(background)
        val rowsPerBand = (BAND_PIXELS / maxOf(1, target.w)).coerceAtLeast(1)
        var y = 0
        while (y < target.h) {
            val rows = minOf(rowsPerBand, target.h - y)
            val bandRegion = Rect(target.x, target.y + y, target.w, rows)
            val band = Pixmap(bandRegion.w, bandRegion.h)
            compose(doc.layers, band, bandRegion, bandRegion.x, bandRegion.y, background)
            for (row in 0 until rows) {
                System.arraycopy(
                    band.pixels,
                    row * bandRegion.w,
                    destination.pixels,
                    (y + row) * destination.width,
                    bandRegion.w,
                )
            }
            y += rows
        }
    }

    /** The visible result of one layer on a transparent backdrop, for thumbnails. */
    fun renderLayerOnly(layer: Layer, width: Int, height: Int): Pixmap {
        val out = Pixmap(width, height)
        val source = layer.pixels ?: LayerRenderer.render(layer, Rect(0, 0, width, height), null) ?: return out
        val scaleX = source.width.toFloat() / width
        val scaleY = source.height.toFloat() / height
        for (y in 0 until height) {
            for (x in 0 until width) {
                out.pixels[y * width + x] = source.sampleNearest(x * scaleX, y * scaleY)
            }
        }
        return out
    }

    /**
     * Flattens a document into a single raster layer — the "flatten image"
     * command. The result replaces the whole stack.
     */
    fun flattenToLayer(doc: Document): Layer {
        val buffer = Pixmap(doc.width, doc.height)
        flatten(doc, buffer, 0)
        val layer = Layer(name = "Background", kind = LayerKind.RASTER)
        layer.pixels = buffer
        return layer
    }
}
