package com.strata.editor.core

import java.io.ByteArrayOutputStream

/**
 * Reading and writing PSD / PSB files.
 *
 * Read: 8- and 16-bit RGB, greyscale and indexed documents, layers, groups (section
 * dividers), layer masks, the 28 blend modes, clipping, text layers, and every
 * additional-info block — the ones that are understood are turned into editable
 * content, and the rest are copied out byte for byte and written back untouched, so
 * opening and saving somebody else's file never destroys what this editor cannot
 * draw.
 *
 * Write: 8-bit RGB with RLE channels, a genuine merged image, groups, masks and
 * text layers written back as `TySh` so Photoshop still lists them as editable
 * text.
 *
 * Two rules the format punishes you for forgetting:
 *  * layer records are stored **top-first**, so both directions of this file
 *    reverse the stack;
 *  * a channel's byte length sits in the record *before* the data it describes, so
 *    the lengths are patched in after the data has been encoded.
 *
 * Documents larger than the import budget are read at a reduced resolution instead
 * of failing to allocate.
 */
object PsdFile {

    const val SIGNATURE = "8BPS"
    const val MAX_PIXELS = 32_000_000

    class FormatException(message: String) : Exception(message)

    class ReadOptions(var maxPixels: Int = MAX_PIXELS)

    class WriteOptions(
        var writeMerged: Boolean = true,
        var background: Int = 0,
        /** PSB (64-bit) output. Chosen automatically for canvases over 30,000 px. */
        var large: Boolean? = null,
    )

    /* ======================================================================= read */

    fun read(data: ByteArray, fileName: String = "untitled.psd", options: ReadOptions = ReadOptions()): Document {
        val cursor = Cursor(data)
        val signature = cursor.fourCc()
        if (signature != SIGNATURE) throw FormatException("This is not a Photoshop file (it starts with “$signature”)")
        val version = cursor.u16()
        if (version != 1 && version != 2) throw FormatException("Unsupported PSD version $version")
        val large = version == 2
        cursor.skip(6)
        val channels = cursor.u16()
        val height = cursor.u32Int()
        val width = cursor.u32Int()
        val depth = cursor.u16()
        val colourMode = cursor.u16()
        if (width <= 0 || height <= 0) throw FormatException("The file describes a ${width}×${height} canvas")
        if (depth != 8 && depth != 16 && depth != 32) throw FormatException("Unsupported bit depth $depth")
        if (colourMode !in 0..9) throw FormatException("Unsupported colour mode $colourMode")

        val stride = strideFor(width, height, options.maxPixels)
        val document = Document((width + stride - 1) / stride, (height + stride - 1) / stride)
        document.fileName = fileName
        document.bitDepth = depth
        document.stride = stride
        document.sourceWidth = width
        document.sourceHeight = height
        document.sourceChannels = channels
        if (stride > 1) document.preserved += "opened at 1/$stride of the original $width×$height"
        if (depth == 16) document.preserved += "16-bit channels, reduced to 8-bit"
        if (depth == 32) document.preserved += "32-bit channels, reduced to 8-bit"
        if (colourMode == 1) document.preserved += "greyscale document"
        if (colourMode == 2) document.preserved += "indexed colour, converted to RGB"
        if (colourMode !in intArrayOf(1, 2, 3)) document.preserved += "colour mode $colourMode shown approximately"

        // Colour mode data (a palette for indexed files).
        val colorModeLength = cursor.u32Int()
        var palette: IntArray? = null
        if (colorModeLength > 0) {
            if (colourMode == 2) palette = readPalette(data, cursor.position, colorModeLength)
            cursor.skip(colorModeLength)
        }

        // Image resources: colour profile, resolution, guides, metadata — kept whole.
        val resourcesLength = cursor.u32Int()
        if (resourcesLength > 0) {
            document.resources = data.copyOfRange(cursor.position, minOf(cursor.position + resourcesLength, data.size))
            cursor.skip(resourcesLength)
        }

        val layerSectionLength = if (large) cursor.u64Int() else cursor.u32Int()
        val layerSectionStart = cursor.position
        if (layerSectionLength > 0 && layerSectionStart < data.size) {
            readLayerSection(data, Cursor(data, layerSectionStart), document, stride, palette, large, layerSectionLength)
        }
        cursor.position = layerSectionStart + layerSectionLength.coerceAtLeast(0)

        // The merged image section: the flattened preview the file carries.
        if (!cursor.atEnd() && document.layers.isEmpty()) {
            val merged = readMergedImage(data, cursor.position, width, height, channels, depth, stride, palette)
            if (merged != null) {
                val layer = Layer("Background")
                layer.pixels = merged
                layer.psdName = "Background"
                document.add(layer)
                document.preserved += "flattened file: imported as a single layer"
            }
        }

        document.dirty = false
        document.history.clear()
        return document
    }

    private fun strideFor(width: Int, height: Int, maxPixels: Int): Int {
        if (width.toLong() * height <= maxPixels) return 1
        var stride = 1
        while (width.toLong() * height / (stride.toLong() * stride) > maxPixels && stride < 32) stride++
        return stride
    }

    private fun readPalette(data: ByteArray, countOffset: Int, length: Int): IntArray? {
        if (length < 4 || countOffset + 3 > data.size) return null
        val cursor = Cursor(data, countOffset)
        val count = cursor.u32Int()
        if (count !in 1..4096) return null
        val palette = IntArray(count)
        for (i in 0 until count) {
            if (cursor.remaining() < 3) break
            palette[i] = Colors.rgb(cursor.u8(), cursor.u8(), cursor.u8())
        }
        return palette
    }

    /* ----------------------------------------------------------- layer section */

    private fun readLayerSection(
        data: ByteArray,
        cursor: Cursor,
        document: Document,
        stride: Int,
        palette: IntArray?,
        large: Boolean,
        limit: Int,
    ) {
        val layerInfoLength = if (large) cursor.u64Int() else cursor.u32Int()
        val layerInfoStart = cursor.position
        if (layerInfoLength > 0) {
            readLayerInfo(data, Cursor(data, layerInfoStart), document, stride, palette, large, layerInfoLength)
        }
        cursor.position = layerInfoStart + layerInfoLength.coerceAtLeast(0)

        // Global layer mask info: preserved verbatim.
        if (cursor.remaining() >= 4) {
            val maskLength = cursor.u32Int()
            if (maskLength > 0 && maskLength <= cursor.remaining()) document.globalMask = cursor.bytes(maskLength)
        }

        // Any further blocks at this level (linked layers, and so on): copied out.
        val preserved = ByteArrayOutputStream()
        while (cursor.remaining() >= 12) {
            val mark = cursor.position
            val signature = cursor.fourCc()
            if (signature != "8BIM" && signature != "8B64") {
                cursor.position = mark
                break
            }
            val key = cursor.fourCc()
            val length = if (large) cursor.u64Int() else cursor.u32Int()
            if (length < 0 || length.toLong() > cursor.remaining().toLong()) break
            preserved.write(signature.toByteArray(Charsets.US_ASCII))
            preserved.write(key.toByteArray(Charsets.US_ASCII))
            preserved.write(intBytes(length.toInt()))
            preserved.write(data, cursor.position, length.toInt())
            if (length % 2 == 1) preserved.write(0)
            cursor.skip(length.toInt() + (length % 2).toInt())
        }
        if (preserved.size() > 0) document.extraLayerInfo = preserved.toByteArray()
    }

    private class Record(
        val top: Int, val left: Int, val bottom: Int, val right: Int,
        val channelIds: IntArray, val channelLengths: IntArray,
        val blendKey: String, val opacity: Int, val clipping: Int, val flags: Int,
        val name: String, val blocks: Map<String, ByteArray>, val maskRect: Rect?,
    )

    private fun readLayerInfo(
        data: ByteArray,
        cursor: Cursor,
        document: Document,
        stride: Int,
        palette: IntArray?,
        large: Boolean,
        limit: Int,
    ) {
        val end = minOf(cursor.position + limit, data.size)
        val layerCount = cursor.i16()
        val count = kotlin.math.abs(layerCount)
        if (count > 8000) throw FormatException("The file claims $count layers, which cannot be right")

        val records = ArrayList<Record>(count)
        for (i in 0 until count) {
            if (cursor.position + 40 > end) throw FormatException("The layer records end early")
            val top = cursor.i32()
            val left = cursor.i32()
            val bottom = cursor.i32()
            val right = cursor.i32()
            if (bottom < top || right < left) throw FormatException("Layer $i has an inverted rectangle")
            val channelCount = cursor.u16()
            if (channelCount > 64) throw FormatException("Layer $i claims $channelCount channels")
            val ids = IntArray(channelCount)
            val lengths = IntArray(channelCount)
            for (c in 0 until channelCount) {
                ids[c] = cursor.i16()
                lengths[c] = if (large) cursor.u64Int() else cursor.u32Int()
            }
            val blendKey = cursor.fourCc()
            val opacity = cursor.u8()
            val clipping = cursor.u8()
            val flags = cursor.u8()
            cursor.u8()
            val extraLength = cursor.u32Int()
            val extraEnd = minOf(cursor.position + extraLength, end)
            var maskRect: Rect? = null
            val maskLength = cursor.u32Int()
            if (maskLength > 0) {
                val maskTop = cursor.i32()
                val maskLeft = cursor.i32()
                val maskBottom = cursor.i32()
                val maskRight = cursor.i32()
                cursor.u8(); cursor.u8(); cursor.u8(); cursor.u16()
                if (maskLength > 20) cursor.skip(maskLength - 20)
                val rect = Rect(maskLeft, maskTop, maskRight - maskLeft, maskBottom - maskTop)
                if (!rect.isEmpty) maskRect = rect
            }
            val rangesLength = cursor.u32Int()
            cursor.skip(rangesLength)
            val name = cursor.pascalString(4)
            val blocks = LinkedHashMap<String, ByteArray>()
            while (cursor.position + 12 <= extraEnd) {
                val mark = cursor.position
                val signature = cursor.fourCc()
                if (signature != "8BIM" && signature != "8B64") {
                    cursor.position = mark
                    break
                }
                val key = cursor.fourCc()
                val length = cursor.u32Int()
                if (length < 0 || cursor.position + length > data.size) break
                blocks[key] = cursor.bytes(length)
                if (length % 2 == 1) cursor.skip(1)
            }
            cursor.position = extraEnd
            records.add(Record(top, left, bottom, right, ids, lengths, blendKey, opacity, clipping, flags, name, blocks, maskRect))
        }
        val channelDataStart = cursor.position

        // ---- the tree, rebuilt from the group brackets.
        // Records run top-first, so each list is built top-first and reversed at the end.
        val stack = ArrayList<MutableList<Layer>>().apply { add(ArrayList()) }
        // Every record is walked for its channel data, including the end-of-group
        // markers: their placeholder channels still occupy bytes in the file.
        val ordered = ArrayList<Pair<Layer?, Record>>(records.size)
        for (record in records) {
            val divider = record.blocks["lsct"]?.let { readIntBlock(it) } ?: 0
            if (divider == 3) {
                if (stack.size > 1) stack.removeAt(stack.size - 1)
                ordered.add(null to record)
                continue
            }
            val layer = Layer(record.name.ifEmpty { "Layer ${ordered.size + 1}" })
            layer.visible = record.flags and 0x02 == 0
            layer.opacity = record.opacity / 255f
            layer.blend = blendModeFromPsd(record.blendKey)
            layer.clipping = record.clipping == 1
            layer.locked = record.flags and 0x01 != 0
            layer.x = record.left / stride
            layer.y = record.top / stride
            layer.psdName = record.name
            layer.psdSectionDivider = divider
            if (divider == 1 || divider == 2) {
                layer.kind = LayerKind.GROUP
                layer.expanded = divider == 1
            }
            record.blocks["luni"]?.let { block ->
                val decoded = decodeUnicodeBlock(block)
                if (decoded.isNotEmpty()) layer.name = decoded
            }
            record.blocks.forEach { (key, block) ->
                when (key) {
                    "lsct", "luni", "lyid" -> Unit
                    TextLayers.BLOCK_KEY -> {
                        val parsed = TextLayers.parse(block)
                        if (parsed != null) {
                            layer.kind = LayerKind.TEXT
                            layer.text = parsed.content
                            layer.textLayout = parsed.layout
                            layer.textTransform = parsed.transform
                            layer.psdTextBlock = block
                        } else {
                            layer.psdBlocks[key] = block
                        }
                    }
                    else -> layer.psdBlocks[key] = block
                }
            }
            record.maskRect?.let { layer.maskBounds = it }
            stack.last().add(layer)
            ordered.add(layer to record)
            if (divider == 1 || divider == 2) stack.add(layer.children)
        }
        run {
            val roots = stack.first()
            roots.forEach { reverseTree(it) }
            roots.reverse()
            document.layers.addAll(roots)
        }

        // ---- channel data, in the same order as the records.
        var position = channelDataStart
        for ((layer, record) in ordered) {
            val layerWidth = ((record.right - record.left) + stride - 1) / stride
            val layerHeight = ((record.bottom - record.top) + stride - 1) / stride
            val planes = HashMap<Int, IntArray>()
            for (c in record.channelIds.indices) {
                val length = record.channelLengths[c]
                if (length <= 0 || position + length > data.size) {
                    position += length.coerceAtLeast(0)
                    continue
                }
                val plane = decodeLayerChannel(data, position, length, record.right - record.left, record.bottom - record.top, stride, document.bitDepth)
                position += length
                if (plane != null) planes[record.channelIds[c]] = plane
            }
            if (layer != null && layer.kind != LayerKind.GROUP && planes[0] != null && layerWidth > 0 && layerHeight > 0) {
                val red = planes[0]!!
                val green = planes[1]
                val blue = planes[2]
                val alpha = planes[-1]
                val pixels = IntArray(layerWidth * layerHeight)
                for (i in pixels.indices) {
                    val r = red.getOrElse(i) { 0 }
                    val g = green?.getOrElse(i) { r } ?: r
                    val b = blue?.getOrElse(i) { r } ?: r
                    val a = alpha?.getOrElse(i) { 255 } ?: 255
                    pixels[i] = if (palette != null) {
                        Colors.withAlpha(palette.getOrElse(r) { Colors.BLACK }, a)
                    } else {
                        Colors.pack(a, r, g, b)
                    }
                }
                layer.pixels = Pixmap(layerWidth, layerHeight, pixels)
            }
            planes[-2]?.let { mask ->
                if (layer != null && layerWidth > 0 && layerHeight > 0) {
                    val maskPixels = IntArray(layerWidth * layerHeight)
                    for (i in maskPixels.indices) {
                        val value = mask.getOrElse(i) { 0 }
                        maskPixels[i] = Colors.pack(255, value, value, value)
                    }
                    layer.mask = Pixmap(layerWidth, layerHeight, maskPixels)
                }
            }
        }
    }

    private fun reverseTree(layer: Layer) {
        layer.children.forEach { reverseTree(it) }
        layer.children.reverse()
    }

    private fun readIntBlock(block: ByteArray): Int =
        if (block.size < 4) 0 else Cursor(block).u32Int()

    private fun decodeUnicodeBlock(block: ByteArray): String {
        if (block.size < 4) return ""
        val length = Cursor(block).u32Int()
        if (length !in 1..1024) return ""
        return String(block, 4, minOf(length * 2, block.size - 4), Charsets.UTF_16BE)
    }

    /* ------------------------------------------------------------- channel codecs */

    private fun decodeLayerChannel(
        data: ByteArray,
        offset: Int,
        length: Int,
        width: Int,
        height: Int,
        stride: Int,
        depth: Int,
    ): IntArray? {
        if (length < 2 || width <= 0 || height <= 0) return null
        val cursor = Cursor(data, offset)
        val compression = cursor.u16()
        // A channel whose declared size cannot possibly hold what the record claims
        // is a placeholder (an empty layer) or a broken record. Reading it anyway
        // would pull neighbouring bytes in as pixels.
        val bytesPerSample = (depth / 8).coerceAtLeast(1)
        val minimum = when (compression) {
            0 -> 2L + width.toLong() * height * bytesPerSample
            1 -> 2L + 2L * height
            else -> 3L
        }
        if (length < minimum) return null
        val consumed = decodeRows(data, cursor.position, width, height, stride, depth, compression, length - 2)
        return consumed?.first
    }

    /**
     * Decodes a channel step, sampling every `stride`-th pixel as it goes so a huge
     * plane never has to exist at full size. Returns the plane and the bytes used.
     */
    private fun decodeRows(
        data: ByteArray,
        offset: Int,
        width: Int,
        height: Int,
        stride: Int,
        depth: Int,
        compression: Int,
        available: Int,
    ): Pair<IntArray, Int>? {
        val bytesPerSample = depth / 8
        val outWidth = (width + stride - 1) / stride
        val outHeight = (height + stride - 1) / stride
        val out = IntArray(outWidth * outHeight)
        when (compression) {
            0 -> {
                val rowBytes = width * bytesPerSample
                val end = minOf(data.size, offset + available.coerceAtLeast(0))
                var position = offset
                for (y in 0 until height) {
                    if (position + rowBytes > end) break
                    if (y % stride == 0) {
                        for (x in 0 until width step stride) {
                            val index = position + x * bytesPerSample
                            val sample = if (bytesPerSample == 1) data[index].toInt() and 0xFF else (data[index].toInt() and 0xFF)
                            out[(y / stride) * outWidth + (x / stride)] = sample
                        }
                    }
                    position += rowBytes
                }
                return out to rowBytes * height
            }
            1 -> {
                val end = minOf(data.size, offset + available.coerceAtLeast(0))
                var tablePosition = offset
                if (tablePosition + height * 2 > end) return null
                val counts = IntArray(height)
                var total = height * 2
                for (y in 0 until height) {
                    counts[y] = ((data[tablePosition].toInt() and 0xFF) shl 8) or (data[tablePosition + 1].toInt() and 0xFF)
                    total += counts[y]
                    tablePosition += 2
                }
                var position = tablePosition
                val row = IntArray(width)
                for (y in 0 until height) {
                    val rowEnd = minOf(position + counts[y], end)
                    if (y % stride == 0) {
                        java.util.Arrays.fill(row, 0)
                        var x = 0
                        var p = position
                        while (x < width && p < rowEnd) {
                            val header = data[p++].toInt()
                            if (header >= 0) {
                                val run = header + 1
                                for (i in 0 until run) {
                                    if (p >= rowEnd) break
                                    val sample = data[p++].toInt() and 0xFF
                                    if (x < width) row[x++] = sample
                                }
                            } else if (header != -128) {
                                val run = 1 - header
                                if (p >= rowEnd) break
                                val value = data[p++].toInt() and 0xFF
                                for (i in 0 until run) if (x < width) row[x++] = value else x++
                            }
                        }
                        val target = (y / stride) * outWidth
                        for (i in 0 until outWidth) out[target + i] = row[i * stride]
                    }
                    position = rowEnd
                }
                return out to total
            }
            else -> {
                // ZIP variants are rare in files a phone meets; the compressed block
                // is inflated as a whole and then sampled.
                val inflated = inflate(data, offset, minOf(available, data.size - offset), width * height * bytesPerSample) ?: return null
                for (y in 0 until outHeight) {
                    for (x in 0 until outWidth) {
                        val sourceIndex = ((y * stride) * width + x * stride) * bytesPerSample
                        out[y * outWidth + x] = if (sourceIndex < inflated.size) inflated[sourceIndex].toInt() and 0xFF else 0
                    }
                }
                return out to available
            }
        }
    }

    private fun inflate(data: ByteArray, offset: Int, length: Int, expected: Int): ByteArray? {
        return try {
            val inflater = java.util.zip.Inflater()
            inflater.setInput(data, offset, length)
            val out = ByteArray(expected)
            var written = 0
            while (written < out.size && !inflater.finished()) {
                val count = inflater.inflate(out, written, out.size - written)
                if (count == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                written += count
            }
            inflater.end()
            if (written == 0) null else out
        } catch (error: Exception) {
            null
        }
    }

    private fun readMergedImage(
        data: ByteArray,
        offset: Int,
        width: Int,
        height: Int,
        channels: Int,
        depth: Int,
        stride: Int,
        palette: IntArray?,
    ): Pixmap? {
        if (offset + 2 > data.size || width <= 0 || height <= 0) return null
        val cursor = Cursor(data, offset)
        val compression = cursor.u16()
        val planes = ArrayList<IntArray>()
        var position = cursor.position
        for (c in 0 until minOf(channels, 4)) {
            val decoded = decodeRows(data, position, width, height, stride, depth, compression, data.size - position) ?: break
            planes.add(decoded.first)
            position += decoded.second
        }
        if (planes.isEmpty()) return null
        val outWidth = (width + stride - 1) / stride
        val outHeight = (height + stride - 1) / stride
        val pixels = IntArray(outWidth * outHeight)
        val red = planes[0]
        val alpha = planes.getOrNull(3)
        for (i in pixels.indices) {
            val r = red.getOrElse(i) { 0 }
            val a = alpha?.getOrElse(i) { 255 } ?: 255
            pixels[i] = if (palette != null) {
                // Indexed colour: the single plane is an index into the palette.
                Colors.withAlpha(palette.getOrElse(r) { Colors.BLACK }, a)
            } else {
                Colors.pack(a, r, planes.getOrNull(1)?.getOrElse(i) { r } ?: r, planes.getOrNull(2)?.getOrElse(i) { r } ?: r)
            }
        }
        return Pixmap(outWidth, outHeight, pixels)
    }

    /* ====================================================================== write */

    fun write(document: Document, options: WriteOptions = WriteOptions()): ByteArray {
        val large = options.large ?: (document.width > 30_000 || document.height > 30_000)
        val out = ByteArrayOutputStream(1 shl 20)
        val writer = Writer(out)
        writer.fourCc(SIGNATURE)
        writer.u16(if (large) 2 else 1)
        writer.zeros(6)
        writer.u16(4)
        writer.u32(document.height.toLong())
        writer.u32(document.width.toLong())
        writer.u16(8)
        writer.u16(3)
        writer.u32(0) // no colour mode data for RGB
        val resources = document.resources
        if (resources != null && resources.isNotEmpty()) {
            writer.u32(resources.size.toLong())
            writer.raw(resources)
        } else {
            writer.u32(0)
        }
        val layerSection = ByteArrayOutputStream(1 shl 16)
        writeLayerSection(document, layerSection, large)
        val layerBytes = layerSection.toByteArray()
        if (large) writer.u64(layerBytes.size.toLong()) else writer.u32(layerBytes.size.toLong())
        writer.raw(layerBytes)
        writeMergedImage(document, writer, options)
        return out.toByteArray()
    }

    private class Channel(val id: Int, val plane: ByteArray?) {
        /** RLE bytes, filled in when the channel is encoded. */
        var encoded: ByteArray? = null

        /**
         * What the record will declare. An empty channel still writes its six-byte
         * header (compression plus a zero length), and the declared length has to
         * count those bytes or every channel after it is read from the wrong place.
         */
        val storedSize: Int get() = if (plane == null) 6 else 2 + (encoded?.size ?: 0)
    }

    private class Entry(val layer: Layer, val bounds: Rect, val channels: List<Channel>)

    private fun writeLayerSection(document: Document, target: ByteArrayOutputStream, large: Boolean) {
        val records = ByteArrayOutputStream(1 shl 16)
        val recordWriter = Writer(records)
        val channelData = ByteArrayOutputStream(1 shl 16)
        val channelWriter = Writer(channelData)

        val entries = ArrayList<Entry>()
        fun walk(items: List<Layer>) {
            for (layer in items.asReversed()) {
                if (layer.isGroup) {
                    layer.psdSectionDivider = if (layer.expanded) 1 else 2
                    entries.add(entryFor(layer, document, layer.psdSectionDivider))
                    walk(layer.children)
                    val divider = Layer("</Layer group>")
                    divider.psdSectionDivider = 3
                    entries.add(entryFor(divider, document, 3))
                    continue
                }
                entries.add(entryFor(layer, document, 0))
            }
        }
        walk(document.layers)

        recordWriter.i16(entries.size)
        val lengthPatchOffsets = ArrayList<Int>()
        for (entry in entries) {
            val bounds = entry.bounds
            recordWriter.i32(bounds.y)
            recordWriter.i32(bounds.x)
            recordWriter.i32(bounds.bottom)
            recordWriter.i32(bounds.right)
            recordWriter.u16(entry.channels.size)
            for (channel in entry.channels) {
                recordWriter.i16(channel.id)
                lengthPatchOffsets.add(records.size())
                if (large) {
                    recordWriter.zeros(8)
                } else {
                    recordWriter.u32(0)
                }
            }
            recordWriter.fourCc(blendKeyOf(entry.layer.blend))
            recordWriter.u8((entry.layer.opacity.coerceIn(0f, 1f) * 255f).toInt())
            recordWriter.u8(if (entry.layer.clipping) 1 else 0)
            var flags = 0
            if (!entry.layer.visible) flags = flags or 0x02
            if (entry.layer.locked) flags = flags or 0x01
            if (entry.layer.kind == LayerKind.TEXT) flags = flags or 0x10
            recordWriter.u8(flags)
            recordWriter.u8(0)

            val extra = ByteArrayOutputStream(512)
            val extraWriter = Writer(extra)
            val mask = entry.layer.mask
            if (mask != null && entry.layer.maskBounds != null) {
                val maskBounds = entry.layer.maskBounds!!
                extraWriter.u32(20)
                extraWriter.i32(maskBounds.y)
                extraWriter.i32(maskBounds.x)
                extraWriter.i32(maskBounds.bottom)
                extraWriter.i32(maskBounds.right)
                extraWriter.u8(0)
                extraWriter.u8(0)
                extraWriter.u8(0)
                extraWriter.u16(0)
            } else {
                extraWriter.u32(0)
            }
            extraWriter.u32(0) // no blending ranges
            extraWriter.pascalName(entry.layer.psdName ?: entry.layer.name)

            val blocks = LinkedHashMap<String, ByteArray>()
            entry.layer.psdBlocks.forEach { (key, block) -> blocks[key] = block }
            val text = entry.layer.text
            if (entry.layer.kind == LayerKind.TEXT && text != null) {
                val original = if (entry.layer.textEdited) null else entry.layer.psdTextBlock
                blocks[TextLayers.BLOCK_KEY] = original
                    ?: TextLayers.build(text, entry.layer.textTransform ?: Mat3(), entry.layer.textLayout, document.bounds)
            }
            if (entry.layer.psdSectionDivider != 0) blocks["lsct"] = intBytes(entry.layer.psdSectionDivider)
            val name = entry.layer.psdName ?: entry.layer.name
            val unicode = ByteArrayOutputStream(8 + name.length * 2)
            Writer(unicode).apply {
                u32(name.length.toLong())
                raw(name.toByteArray(Charsets.UTF_16BE))
            }
            blocks["luni"] = unicode.toByteArray()
            blocks.forEach { (key, block) -> writeBlock(extraWriter, key, block) }

            val extraBytes = extra.toByteArray()
            recordWriter.u32(extraBytes.size.toLong())
            recordWriter.raw(extraBytes)

            for (channel in entry.channels) {
                if (channel.plane == null) {
                    channelWriter.u16(0)
                    channelWriter.u32(0)
                    continue
                }
                val encoded = encodePlane(channel.plane, bounds.w, bounds.h)
                channel.encoded = encoded
                channelWriter.u16(1)
                channelWriter.raw(encoded)
            }
        }

        // Patch each channel's byte length into the record block.
        val recordBytes = records.toByteArray()
        val lengths = ArrayList<Int>()
        for (entry in entries) for (channel in entry.channels) lengths.add(channel.storedSize)
        for ((index, offset) in lengthPatchOffsets.withIndex()) {
            val value = lengths.getOrElse(index) { 0 }.toLong()
            val size = if (large) 8 else 4
            for (b in 0 until size) {
                val shift = (size - 1 - b) * 8
                recordBytes[offset + b] = ((value ushr shift) and 0xFF).toByte()
            }
        }

        val channelBytes = channelData.toByteArray()
        if (large) {
            val total = recordBytes.size.toLong() + channelBytes.size
            for (b in 0 until 8) target.write(((total ushr ((7 - b) * 8)) and 0xFF).toInt())
        } else {
            target.write(intBytes(recordBytes.size + channelBytes.size))
        }
        target.write(recordBytes)
        target.write(channelBytes)
        target.write(intBytes(0)) // no global layer mask info
        document.extraLayerInfo?.let { if (it.isNotEmpty()) target.write(it) }
    }

    private fun entryFor(layer: Layer, document: Document, divider: Int): Entry {
        layer.psdSectionDivider = divider
        val bounds = contentBounds(layer, document)
        val channels = ArrayList<Channel>(5)
        val pixels = layer.pixels
        if (pixels != null && bounds.w > 0 && bounds.h > 0) {
            channels.add(Channel(-1, plane(pixels, bounds, 3)))
            channels.add(Channel(0, plane(pixels, bounds, 0)))
            channels.add(Channel(1, plane(pixels, bounds, 1)))
            channels.add(Channel(2, plane(pixels, bounds, 2)))
        } else {
            channels.add(Channel(-1, null))
            channels.add(Channel(0, null))
            channels.add(Channel(1, null))
            channels.add(Channel(2, null))
        }
        layer.mask?.let { mask ->
            if (bounds.w > 0 && bounds.h > 0) {
                val data = ByteArray(bounds.w * bounds.h)
                for (y in 0 until bounds.h) {
                    for (x in 0 until bounds.w) {
                        data[y * bounds.w + x] = Colors.luminance(mask[x, y]).toByte()
                    }
                }
                channels.add(Channel(-2, data))
            }
        }
        return Entry(layer, bounds, channels)
    }

    private fun contentBounds(layer: Layer, document: Document): Rect {
        layer.pixels?.let { return Rect(layer.x, layer.y, it.width, it.height) }
        layer.areaBounds()?.let { if (!it.isEmpty) return it }
        return Rect(layer.x, layer.y, 0, 0)
    }

    /** One channel plane of a layer, in the layer's own coordinates. */
    private fun plane(pixels: Pixmap, bounds: Rect, channel: Int): ByteArray {
        val out = ByteArray(bounds.w * bounds.h)
        for (y in 0 until bounds.h) {
            for (x in 0 until bounds.w) {
                val colour = pixels[x, y]
                out[y * bounds.w + x] = when (channel) {
                    0 -> Colors.red(colour).toByte()
                    1 -> Colors.green(colour).toByte()
                    2 -> Colors.blue(colour).toByte()
                    else -> Colors.alpha(colour).toByte()
                }
            }
        }
        return out
    }

    /** Row tables first, then the rows: that is the layout the format expects. */
    private fun encodePlane(plane: ByteArray, width: Int, height: Int): ByteArray {
        val out = ByteArrayOutputStream(plane.size / 2 + height * 2)
        val line = ByteArray(width)
        val rows = ArrayList<ByteArray>(height)
        for (y in 0 until height) {
            System.arraycopy(plane, y * width, line, 0, width)
            rows.add(encodeRleRow(line))
        }
        for (row in rows) {
            out.write((row.size ushr 8) and 0xFF)
            out.write(row.size and 0xFF)
        }
        for (row in rows) out.write(row, 0, row.size)
        return out.toByteArray()
    }

    /** PackBits: runs of three or more become a repeat, everything else is literal. */
    fun encodeRleRow(row: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(row.size / 2 + 8)
        var index = 0
        while (index < row.size) {
            var run = 1
            while (index + run < row.size && row[index + run] == row[index] && run < 128) run++
            if (run >= 3) {
                out.write(1 - run)
                out.write(row[index].toInt() and 0xFF)
                index += run
                continue
            }
            val start = index
            var literal = 0
            while (index < row.size && literal < 128) {
                if (index + 2 < row.size && row[index] == row[index + 1] && row[index] == row[index + 2]) break
                index++
                literal++
            }
            if (literal == 0) {
                var count = 1
                while (index + count < row.size && row[index + count] == row[index] && count < 128) count++
                out.write(1 - count)
                out.write(row[index].toInt() and 0xFF)
                index += count
            } else {
                out.write(literal - 1)
                out.write(row, start, literal)
            }
        }
        return out.toByteArray()
    }

    private fun writeBlock(out: Writer, key: String, block: ByteArray) {
        out.fourCc("8BIM")
        out.fourCc(key)
        out.u32(block.size.toLong())
        out.raw(block)
        if (block.size % 2 == 1) out.u8(0)
    }

    private fun writeMergedImage(document: Document, out: Writer, options: WriteOptions) {
        if (!options.writeMerged || document.width <= 0 || document.height <= 0) {
            out.u16(0)
            return
        }
        val width = document.width
        val height = document.height
        val flat = Pixmap(width, height)
        Compositor.flatten(document, flat, options.background)
        out.u16(1)
        val line = ByteArray(width)
        for (channel in 0 until 4) {
            val rows = ArrayList<ByteArray>(height)
            for (y in 0 until height) {
                val row = y * width
                for (x in 0 until width) {
                    val colour = flat.pixels[row + x]
                    line[x] = when (channel) {
                        0 -> Colors.red(colour).toByte()
                        1 -> Colors.green(colour).toByte()
                        2 -> Colors.blue(colour).toByte()
                        else -> Colors.alpha(colour).toByte()
                    }
                }
                rows.add(encodeRleRow(line))
            }
            for (row in rows) out.u16(row.size)
            for (row in rows) out.raw(row)
        }
    }

    /* ----------------------------------------------------------------- mappings */

    fun blendModeFromPsd(key: String): BlendMode = when (key.trim()) {
        "norm" -> BlendMode.NORMAL
        "diss" -> BlendMode.DISSOLVE
        "dark" -> BlendMode.DARKEN
        "mul" -> BlendMode.MULTIPLY
        "idiv" -> BlendMode.COLOR_BURN
        "lbrn" -> BlendMode.LINEAR_BURN
        "dkCl" -> BlendMode.DARKER_COLOR
        "lite" -> BlendMode.LIGHTEN
        "scrn" -> BlendMode.SCREEN
        "div" -> BlendMode.COLOR_DODGE
        "lddg" -> BlendMode.LINEAR_DODGE
        "lgCl" -> BlendMode.LIGHTER_COLOR
        "over" -> BlendMode.OVERLAY
        "sLit" -> BlendMode.SOFT_LIGHT
        "hLit" -> BlendMode.HARD_LIGHT
        "vLit" -> BlendMode.VIVID_LIGHT
        "lLit" -> BlendMode.LINEAR_LIGHT
        "pLit" -> BlendMode.PIN_LIGHT
        "hMix" -> BlendMode.HARD_MIX
        "diff" -> BlendMode.DIFFERENCE
        "smud" -> BlendMode.EXCLUSION
        "fsub" -> BlendMode.SUBTRACT
        "fdiv" -> BlendMode.DIVIDE
        "hue" -> BlendMode.HUE
        "sat" -> BlendMode.SATURATION
        "colr" -> BlendMode.COLOR
        "lum" -> BlendMode.LUMINOSITY
        "pass" -> BlendMode.PASS_THROUGH
        else -> BlendMode.NORMAL
    }

    fun blendKeyOf(mode: BlendMode): String = when (mode) {
        BlendMode.NORMAL -> "norm"
        BlendMode.DISSOLVE -> "diss"
        BlendMode.DARKEN -> "dark"
        BlendMode.MULTIPLY -> "mul "
        BlendMode.COLOR_BURN -> "idiv"
        BlendMode.LINEAR_BURN -> "lbrn"
        BlendMode.DARKER_COLOR -> "dkCl"
        BlendMode.LIGHTEN -> "lite"
        BlendMode.SCREEN -> "scrn"
        BlendMode.COLOR_DODGE -> "div "
        BlendMode.LINEAR_DODGE -> "lddg"
        BlendMode.LIGHTER_COLOR -> "lgCl"
        BlendMode.OVERLAY -> "over"
        BlendMode.SOFT_LIGHT -> "sLit"
        BlendMode.HARD_LIGHT -> "hLit"
        BlendMode.VIVID_LIGHT -> "vLit"
        BlendMode.LINEAR_LIGHT -> "lLit"
        BlendMode.PIN_LIGHT -> "pLit"
        BlendMode.HARD_MIX -> "hMix"
        BlendMode.DIFFERENCE -> "diff"
        BlendMode.EXCLUSION -> "smud"
        BlendMode.SUBTRACT -> "fsub"
        BlendMode.DIVIDE -> "fdiv"
        BlendMode.HUE -> "hue "
        BlendMode.SATURATION -> "sat "
        BlendMode.COLOR -> "colr"
        BlendMode.LUMINOSITY -> "lum "
        BlendMode.PASS_THROUGH -> "pass"
    }

    private fun intBytes(value: Int): ByteArray = byteArrayOf(
        (value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte(),
    )

    /* ------------------------------------------------------------------ helpers */

    class Cursor(val data: ByteArray, var position: Int = 0) {

        fun atEnd(): Boolean = position >= data.size
        fun remaining(): Int = data.size - position

        fun u8(): Int = if (position < data.size) data[position++].toInt() and 0xFF else 0

        fun u16(): Int = (u8() shl 8) or u8()

        fun i16(): Int = u16().let { if (it >= 32768) it - 65536 else it }

        fun u32(): Long = ((u8().toLong() shl 24) or (u8().toLong() shl 16) or (u8().toLong() shl 8) or u8().toLong()) and 0xFFFFFFFFL

        fun u32Int(): Int = u32().toInt()

        fun i32(): Int = u32().toInt()

        fun u64(): Long = (u32() shl 32) or u32()

        fun u64Int(): Int = (u64() and 0x7FFFFFFFL).toInt()

        fun fourCc(): String {
            if (position + 4 > data.size) {
                position = data.size
                return ""
            }
            val text = String(data, position, 4, Charsets.US_ASCII)
            position += 4
            return text
        }

        fun bytes(count: Int): ByteArray {
            val length = count.coerceIn(0, data.size - position)
            val out = data.copyOfRange(position, position + length)
            position += length
            return out
        }

        fun skip(count: Int) {
            position = (position + count).coerceIn(0, data.size)
        }

        fun pascalString(pad: Int): String {
            val length = u8()
            val text = String(data, position, minOf(length, data.size - position), Charsets.ISO_8859_1)
            position += length
            var consumed = length + 1
            while (consumed % pad != 0) {
                consumed++
                position++
            }
            return text
        }
    }

    class Writer(private val out: ByteArrayOutputStream) {

        fun u8(value: Int) = out.write(value and 0xFF)

        fun u16(value: Int) {
            u8(value ushr 8); u8(value)
        }

        fun i16(value: Int) = u16(value)

        fun u32(value: Long) {
            val v = (value and 0xFFFFFFFFL).toInt()
            u8(v ushr 24); u8(v ushr 16); u8(v ushr 8); u8(v)
        }

        fun u64(value: Long) {
            val high = (value ushr 32) and 0xFFFFFFFFL
            u32(high)
            u32(value and 0xFFFFFFFFL)
        }

        fun i32(value: Int) = u32(value.toLong() and 0xFFFFFFFFL)

        fun zeros(count: Int) = repeat(count) { u8(0) }

        fun raw(bytes: ByteArray) = out.write(bytes, 0, bytes.size)

        fun fourCc(value: String) {
            val text = value.padEnd(4, ' ').substring(0, 4)
            for (c in text) u8(c.code)
        }

        fun pascalName(name: String) {
            val text = name.take(255)
            u8(text.length)
            for (c in text) u8(c.code)
            var consumed = text.length + 1
            while (consumed % 4 != 0) {
                consumed++
                u8(0)
            }
        }
    }
}
