package com.photocc.app.core

import kotlin.math.roundToInt

/**
 * PSD writer.
 *
 * Always writes RGB / 8-bit (the editor's working space) and keeps every block
 * the reader preserved, so a file that goes through the app comes out openable
 * by Photoshop with its layer styles, masks, vector paths and metadata intact.
 *
 * Structure follows the Adobe specification:
 *   header · colour mode data · image resources · layer and mask info · image data
 * with RLE ("PackBits") compression for layer channels and the merged image —
 * the only compression Photoshop uses for composite data.
 */
object PsdWrite {

    class Options(
        /** RLE is the default; raw is offered for maximum compatibility. */
        var compression: Int = 1,
        var writeMerged: Boolean = true,
        /** PSB (large document) output; chosen automatically when a file is huge. */
        var large: Boolean? = null,
    )

    private class ChannelBlob(val id: Int, val payload: ByteArray, val compression: Int)

    private class Entry(
        val layer: Layer,
        /** 0 = normal layer, 1 = group header, 2 = group end marker. */
        val kind: Int,
    )

    fun write(doc: PsdDocument, options: Options = Options()): ByteArray {
        val large = options.large ?: (doc.width > 30000 || doc.height > 30000)
        val entries = fileEntries(doc.children)

        val channelData = Blob(1 shl 16)
        val records = Blob(1 shl 16)
        writeLayerRecords(doc, entries, records, channelData, options, large)

        val layerInfo = Blob(records.size + channelData.size + 64)
        // The layer count is a signed 16-bit value — negative means "the merged image
        // carries transparency", which is why it is not a 32-bit field.
        layerInfo.short(entries.size)
        layerInfo.append(records)
        layerInfo.append(channelData)

        val layerAndMask = Blob(layerInfo.size + 64)
        if (large) layerAndMask.long(layerInfo.size.toLong()) else layerAndMask.int(layerInfo.size)
        layerAndMask.append(layerInfo)
        // Global layer mask info, preserved verbatim.
        val globalMask = doc.globalLayerMaskRaw
        layerAndMask.int(globalMask.size)
        layerAndMask.raw(globalMask)

        val out = Blob(1 shl 20)
        // ---- header
        out.fourCc("8BPS")
        out.short(if (large) 2 else 1)
        out.zeros(6)
        out.short(4) // channels: R, G, B, A
        out.int(doc.height)
        out.int(doc.width)
        out.short(8) // bit depth
        out.short(PsdRead.COLOR_MODE_RGB)
        // ---- colour mode data (empty for RGB output)
        out.int(0)
        // ---- image resources, preserved verbatim
        val resources = doc.imageResourcesRaw
        out.int(resources.size)
        out.raw(resources)
        // ---- layer and mask info
        if (large) out.long(layerAndMask.size.toLong()) else out.int(layerAndMask.size)
        out.append(layerAndMask)
        // ---- merged image
        writeMergedImage(doc, out, options)

        doc.dirty = false
        return out.toByteArray()
    }

    /** Flattens the document and writes the merged image section. */
    fun writeMergedImage(doc: PsdDocument, out: Blob, options: Options) {
        if (!options.writeMerged) {
            out.short(0)
            return
        }
        val region = Rect(0, 0, doc.width, doc.height)
        val composite = IntArray(doc.width * doc.height)
        Render.flatten(doc, region, composite, background = 0)

        val count = doc.width * doc.height
        val channels = arrayOfNulls<ByteArray>(4)
        val red = ByteArray(count)
        val green = ByteArray(count)
        val blue = ByteArray(count)
        val alpha = ByteArray(count)
        for (i in 0 until count) {
            val value = composite[i]
            red[i] = Argb.r(value).toByte()
            green[i] = Argb.g(value).toByte()
            blue[i] = Argb.b(value).toByte()
            alpha[i] = Argb.a(value).toByte()
        }
        channels[0] = red
        channels[1] = green
        channels[2] = blue
        channels[3] = alpha

        val compression = if (options.compression == 1) 1 else 0
        out.short(compression)
        if (compression == 0) {
            for (channel in channels) if (channel != null) out.raw(channel)
            return
        }
        // RLE: *all* row-length tables first, then the packed rows, for every channel.
        val packed = ArrayList<ByteArray>(4)
        val tables = ArrayList<ByteArray>(4)
        for (channel in channels) {
            if (channel == null) continue
            val encoded = ChannelCodec.encodeRle(channel, doc.width, doc.height)
            val tableLength = doc.height * 2
            tables.add(encoded.copyOfRange(0, tableLength))
            packed.add(encoded.copyOfRange(tableLength, encoded.size))
        }
        for (table in tables) out.raw(table)
        for (rows in packed) out.raw(rows)
    }

    /* ------------------------------------------------------------------ record layout */

    /** Layers in file order (top-first) with folder markers, as PSD stores them. */
    private fun fileEntries(children: List<Layer>): List<Entry> {
        val out = ArrayList<Entry>(children.size + 4)
        for (i in children.indices.reversed()) {
            val layer = children[i]
            if (layer is GroupLayer && layer.children.isNotEmpty()) {
                // A folder is: end marker, its layers (top-first), then its header.
                out.add(Entry(layer, 2))
                out.addAll(fileEntries(layer.children))
                out.add(Entry(layer, 1))
            } else {
                out.add(Entry(layer, 0))
            }
        }
        return out
    }

    private fun writeLayerRecords(
        doc: PsdDocument,
        entries: List<Entry>,
        records: Blob,
        channelData: Blob,
        options: Options,
        large: Boolean,
    ) {
        for (entry in entries) {
            val pixels = pixelsFor(entry)
            val bounds = boundsFor(entry, pixels)
            val channels = channelBlobsFor(doc, entry, pixels, options)
            val extra = extraDataFor(doc, entry, bounds, channels)

            records.int(bounds.y)
            records.int(bounds.x)
            records.int(bounds.bottom)
            records.int(bounds.right)
            records.short(channels.size)
            for (channel in channels) {
                records.short(channel.id)
                val length = channel.payload.size + 2
                if (large) {
                    records.int(0)
                    records.int(length)
                } else {
                    records.int(length)
                }
            }
            records.fourCc("8BIM")
            records.fourCc(blendKeyFor(entry))
            records.byte((entry.layer.opacity.coerceIn(0f, 1f) * 255f + 0.5f).toInt().coerceIn(0, 255))
            records.byte(entry.layer.rawClipping)
            records.byte(flagByteFor(entry.layer))
            records.byte(0)
            records.int(extra.size)
            records.append(extra)

            // Channel data follows all records, in the same order.
            for (channel in channels) {
                channelData.short(channel.compression)
                channelData.raw(channel.payload)
            }
        }
    }

    /**
     * Pixels to store for a record. Text layers are rasterised here (with the
     * same renderer the canvas uses) so every other application shows the text
     * without having to understand type layers.
     */
    private fun pixelsFor(entry: Entry): Pixels? {
        if (entry.kind != 0) return null
        return when (val layer = entry.layer) {
            is RasterLayer -> layer.pixels
            is TextLayer -> rasterizeText(layer)
            else -> null
        }
    }

    private fun rasterizeText(layer: TextLayer): Pixels? {
        val rasterizer = TextEngine.rasterizer ?: return null
        val bounds = layer.sourceBounds?.takeIf {
            !layer.textDirty && kotlin.math.abs(it.x - layer.x) < 1.5f && kotlin.math.abs(it.y - layer.y) < 1.5f
        } ?: TextGeometry.measure(layer).bounds
        if (bounds.w <= 0 || bounds.h <= 0) return null
        val pixels = Pixels.empty(bounds.w, bounds.h)
        rasterizer.rasterize(layer, bounds, pixels.data)
        return pixels
    }

    private fun boundsFor(entry: Entry, pixels: Pixels?): Rect {
        val layer = entry.layer
        if (entry.kind != 0) return Rect(0, 0, 0, 0)
        if (pixels != null && pixels.width > 0 && pixels.height > 0) {
            return Rect(layer.coordX(), layer.coordY(), pixels.width, pixels.height)
        }
        return when (layer) {
            is RasterLayer -> layer.preservedBounds ?: Rect(layer.x, layer.y, 0, 0)
            is TextLayer -> Rect(layer.x.toInt(), layer.y.toInt(), 0, 0)
            else -> Rect(layer.coordX(), layer.coordY(), 0, 0)
        }
    }

    private fun Layer.coordX(): Int = when (this) {
        is RasterLayer -> x
        is TextLayer -> x.toInt()
        else -> 0
    }

    private fun Layer.coordY(): Int = when (this) {
        is RasterLayer -> y
        is TextLayer -> y.toInt()
        else -> 0
    }

    private fun blendKeyFor(entry: Entry): String {
        val layer = entry.layer
        return when {
            entry.kind == 1 -> if (layer.blend == BlendMode.PASS_THROUGH) "norm" else layer.blend.key
            entry.kind == 2 -> "norm"
            layer.blend == BlendMode.PASS_THROUGH -> "norm"
            else -> layer.blend.key
        }
    }

    private fun flagByteFor(layer: Layer): Int {
        var flags = layer.rawFlags and 0x02.inv()
        if (!layer.visible) flags = flags or 0x02
        if (layer.locked) flags = flags or 0x01 or 0x08
        return flags and 0xFF
    }

    /* ------------------------------------------------------------------ channels */

    private fun channelBlobsFor(
        doc: PsdDocument,
        entry: Entry,
        provided: Pixels?,
        options: Options,
    ): List<ChannelBlob> {
        val layer = entry.layer
        if (entry.kind != 0) return emptyList()
        val raster = layer as? RasterLayer
        val pixels = provided
        if (pixels == null || pixels.width <= 0 || pixels.height <= 0) {
            val maskOnly = raster?.mask
            if (maskOnly != null && maskOnly.bounds.w > 0 && maskOnly.bounds.h > 0 &&
                maskOnly.data.size >= maskOnly.bounds.w * maskOnly.bounds.h
            ) {
                return listOf(channel(-2, maskOnly.data, maskOnly.bounds.w, maskOnly.bounds.h, options))
            }
            return emptyList()
        }
        val count = pixels.width * pixels.height
        val red = ByteArray(count)
        val green = ByteArray(count)
        val blue = ByteArray(count)
        val alpha = ByteArray(count)
        var hasTransparency = false
        for (i in 0 until count) {
            val value = pixels.data[i]
            red[i] = Argb.r(value).toByte()
            green[i] = Argb.g(value).toByte()
            blue[i] = Argb.b(value).toByte()
            val a = Argb.a(value)
            alpha[i] = a.toByte()
            if (a != 255) hasTransparency = true
        }
        val background = !hasTransparency && raster != null && isBackgroundLayer(doc, raster)
        val channels = ArrayList<ChannelBlob>(4)
        if (!background) channels.add(channel(-1, alpha, pixels.width, pixels.height, options))
        channels.add(channel(0, red, pixels.width, pixels.height, options))
        channels.add(channel(1, green, pixels.width, pixels.height, options))
        channels.add(channel(2, blue, pixels.width, pixels.height, options))
        val mask = raster?.mask
        if (mask != null && mask.data.size >= mask.bounds.w * mask.bounds.h) {
            channels.add(channel(-2, mask.data, mask.bounds.w, mask.bounds.h, options, mask.bounds))
        }
        return channels
    }

    private fun channel(id: Int, data: ByteArray, width: Int, height: Int, options: Options, bounds: Rect? = null): ChannelBlob {
        if (options.compression == 1) {
            return ChannelBlob(id, ChannelCodec.encodeRle(data, width, height), 1)
        }
        return ChannelBlob(id, data, 0)
    }

    private fun isBackgroundLayer(doc: PsdDocument, layer: RasterLayer): Boolean =
        doc.children.firstOrNull() == layer &&
            layer.x == 0 && layer.y == 0 &&
            layer.pixels?.width == doc.width && layer.pixels?.height == doc.height &&
            layer.blend == BlendMode.NORMAL

    /* ------------------------------------------------------------------ extra data */

    private fun extraDataFor(doc: PsdDocument, entry: Entry, bounds: Rect, channels: List<ChannelBlob>): Blob {
        val layer = entry.layer
        val extra = Blob(512)

        // --- mask parameters
        val mask = (layer as? RasterLayer)?.mask
        if (mask != null && channels.any { it.id == -2 }) {
            val maskLength = 18
            extra.int(maskLength)
            extra.int(mask.bounds.y)
            extra.int(mask.bounds.x)
            extra.int(mask.bounds.bottom)
            extra.int(mask.bounds.right)
            extra.byte(mask.defaultColor)
            extra.byte(if (mask.disabled) 0x02 else 0x00)
        } else {
            extra.int(0)
        }
        // --- blending ranges
        extra.int(0)
        // --- layer name (pascal string, padded to 4)
        extra.pascalString(layer.name.take(120), 4)

        // --- additional layer information, in Photoshop's usual order
        if (entry.kind == 1 || entry.kind == 2) {
            writeBlock(extra, "lsct", sectionDivider(entry))
        }
        if (layer.name.any { it.code > 127 }) writeUnicodeName(extra, layer.name)
        layer.sourceId?.let { id ->
            val block = Blob(4)
            block.int(id)
            writeBlock(extra, "lyid", block.toByteArray())
        }
        if (layer is TextLayer) writeBlock(extra, "TySh", textShyBlock(layer))

        for (info in layer.extraInfo) {
            if (info.key == "lsct" || info.key == "luni" || info.key == "lyid" || info.key == "TySh") continue
            writeBlock(extra, info.key, info.data)
        }
        // `bounds` is written in the layer record itself; nothing more to do here.
        if (bounds.isEmpty && extra.size == 0) extra.int(0)
        return extra
    }

    private fun sectionDivider(entry: Entry): ByteArray {
        val block = Blob(16)
        when (entry.kind) {
            2 -> block.int(3)
            else -> {
                val group = entry.layer
                block.int(if (group is GroupLayer && !group.opened) 2 else 1)
                block.fourCc("8BIM")
                block.fourCc(group.blend.key)
                block.int(0)
            }
        }
        return block.toByteArray()
    }

    private fun writeUnicodeName(extra: Blob, name: String) {
        val block = Blob(name.length * 2 + 4)
        block.unicodeString(name)
        writeBlock(extra, "luni", block.toByteArray())
    }

    private fun textShyBlock(layer: TextLayer): ByteArray {
        val original = layer.originalTySh
        if (!layer.textDirty && original != null) return original
        return Desc.writeTextLayerShy(layer)
    }

    private fun writeBlock(out: Blob, key: String, data: ByteArray) {
        out.fourCc("8BIM")
        out.fourCc(key)
        out.int(data.size)
        out.raw(data)
    }

    /* ------------------------------------------------------------------ helpers */

    /** Name of the file to offer for a "save as" of a freshly created document. */
    fun defaultName(doc: PsdDocument): String =
        if (doc.outlineName.isNotBlank()) doc.outlineName else "photo_cc-edit.psd"

    fun roundOpacity(value: Float): Int = (value.coerceIn(0f, 1f) * 255f).roundToInt()
}
