package com.photocc.app.core

/**
 * PSD / PSB reader.
 *
 * Hand-written rather than ported from a general-purpose library so the engine
 * allocates as little as possible: layer channels are decoded once into flat
 * 8-bit buffers, and every block the editor does not model is kept as raw bytes
 * so saving writes it back untouched (layer styles, masks, vector paths,
 * smart-object links, image resources, ...).
 *
 * Supported: RGB and Grayscale, 8 and 16 bit (16-bit is down-shifted to 8 with a
 * note in the document), Raw / RLE / ZIP / ZIP+prediction channel compression.
 */
class PsdFormatException(message: String) : Exception(message)

object PsdRead {

    /** Set `PHOTO_CC_DEBUG=1` to print layer records while reading (development aid). */
    var debug: Boolean = System.getenv("PHOTO_CC_DEBUG") != null

    const val COLOR_MODE_BITMAP = 0
    const val COLOR_MODE_GRAYSCALE = 1
    const val COLOR_MODE_INDEXED = 2
    const val COLOR_MODE_RGB = 3
    const val COLOR_MODE_CMYK = 4
    const val COLOR_MODE_MULTICHANNEL = 7
    const val COLOR_MODE_DUOTONE = 8
    const val COLOR_MODE_LAB = 9

    private val largeLengthKeys = setOf(
        "LMsk", "Lr16", "Lr32", "Layr", "Mt16", "Mt32", "Mtrn", "Alph", "FMsk", "lnk2", "FEid", "FXid", "PxSD", "cinf",
    )

    /** Keys that mean "this layer is not a picture this editor can draw". */
    private val adjustmentKeys = setOf(
        "levl", "curv", "brit", "blnc", "hue2", "selc", "mixr", "phfl", "thrs", "nvrt", "post",
        "cngs", "clrL", "expA", "vibA", "blwh", "grsc", "grdm", "oglM",
    )
    private val smartObjectKeys = setOf("SoLd", "PlLd", "SoLE")
    private val vectorFillKeys = setOf("SoCo", "GdFl", "PtFl")

    /**
     * Pixel budget for an imported document.
     *
     * Decoding a 60 MPix poster at full resolution costs 240 MB of ARGB pixels
     * *per full-size layer*, which is an instant `OutOfMemoryError` on a phone.
     * Above the budget the reader samples the document down instead (every other
     * pixel, then every fourth …) and the app says so in the document info sheet.
     * 24 MPix ≈ 96 MB for a single full-canvas layer — already generous.
     */
    const val DEFAULT_MAX_PIXELS = 24_000_000

    /** Set by [read]: how much the document was sampled down (1 = full resolution). */
    var lastImportStride: Int = 1
        private set

    /** Original document size before sampling, or null when nothing was sampled. */
    var lastSourceSize: Pair<Int, Int>? = null
        private set

    fun read(
        data: ByteArray,
        fileName: String = "untitled.psd",
        maxPixels: Int = DEFAULT_MAX_PIXELS,
    ): PsdDocument {
        if (data.size < 26) throw PsdFormatException("File is too small to be a PSD")
        val cursor = Cursor(data)
        val signature = cursor.fourCc()
        if (signature != "8BPS") throw PsdFormatException("Not a PSD file (no 8BPS signature)")
        val version = cursor.u16()
        val large = version == 2
        cursor.skip(6)
        val channelCount = cursor.u16()
        val height = cursor.u32().toInt()
        val width = cursor.u32().toInt()
        val bitDepth = cursor.u16()
        val colorMode = cursor.u16()

        if (width <= 0 || height <= 0) throw PsdFormatException("Invalid document size ${width}x$height")
        if (colorMode != COLOR_MODE_RGB && colorMode != COLOR_MODE_GRAYSCALE) {
            throw PsdFormatException(
                "This file is ${colorModeName(colorMode)}; photo_cc edits RGB and Grayscale documents. " +
                    "Save a copy as RGB in another app first."
            )
        }
        if (bitDepth != 8 && bitDepth != 16) throw PsdFormatException("Unsupported bit depth $bitDepth")

        // Memory budget: above `maxPixels` the document is sampled down (2×, 4×, …).
        var stride = 1
        while (stride < 64 &&
            (width.toLong() / stride) * (height.toLong() / stride) > maxPixels.toLong()
        ) {
            stride *= 2
        }
        while (stride > 1 && (width / stride < 1 || height / stride < 1)) stride /= 2
        importStride = stride
        val doc = PsdDocument(
            (width + stride - 1) / stride,
            (height + stride - 1) / stride,
        )
        doc.importStride = stride
        doc.sourceWidth = width
        doc.sourceHeight = height
        if (stride > 1) {
            doc.notes.add("Opened at 1/$stride resolution (${width}×$height original) to fit the phone's memory")
        }
        lastImportStride = stride
        lastSourceSize = if (stride > 1) width to height else null
        doc.fileName = fileName
        doc.outlineName = outlineName(fileName)
        doc.sourceBytes = data.size.toLong()
        doc.sourceColorMode = colorMode
        doc.sourceBitDepth = bitDepth
        if (bitDepth == 16) doc.notes.add("16-bit file — layers are edited and saved as 8-bit")
        if (colorMode == COLOR_MODE_GRAYSCALE) doc.notes.add("Grayscale file — converted to RGB when saved")

        val colorModeLength = cursor.u32().toInt()
        doc.colorModeDataRaw = cursor.bytes(colorModeLength)
        val resourceLength = cursor.u32().toInt()
        doc.imageResourcesRaw = cursor.bytes(resourceLength)

        val layerAndMaskStart = cursor.pos
        val layerAndMaskLength = if (large) cursor.u64() else cursor.u32()
        val layerAndMaskEnd = (layerAndMaskStart + 4 + layerAndMaskLength).toInt().coerceIn(0, data.size)

        readLayerAndMask(cursor, doc, large)
        cursor.pos = layerAndMaskEnd

        if (doc.layerCount() == 0) {
            doc.notes.add("This file has no layer data — the flattened image was imported as a single layer")
            importMergedAsLayer(doc, data, layerAndMaskEnd, channelCount, width, height, bitDepth, colorMode)
        }
        return doc
    }

    /* ------------------------------------------------------------------ layer and mask section */

    private class ChannelInfo(val id: Int, val length: Long)

    private class MaskRecord(
        val top: Int,
        val left: Int,
        val bottom: Int,
        val right: Int,
        val defaultColor: Int,
        val flags: Int,
    ) {
        val width: Int get() = (right - left).coerceAtLeast(0)
        val height: Int get() = (bottom - top).coerceAtLeast(0)
    }

    private class LayerRecord(
        val top: Int,
        val left: Int,
        val bottom: Int,
        val right: Int,
        val channels: List<ChannelInfo>,
        val blendKey: String,
        val opacity: Int,
        val flags: Int,
        val mask: MaskRecord?,
        val name: String,
        val extra: MutableList<ExtraInfo>,
        var sectionDivider: Int = 0,
    ) {
        val width: Int get() = (right - left).coerceAtLeast(0)
        val height: Int get() = (bottom - top).coerceAtLeast(0)
        val visible: Boolean get() = (flags and 0x02) == 0
        val locked: Boolean get() = (flags and 0x01) != 0
        val hasContent: Boolean get() = width > 0 && height > 0

        fun block(key: String): ByteArray? = extra.firstOrNull { it.key == key }?.data
        fun has(key: String): Boolean = extra.any { it.key == key }
    }

    private fun readLayerAndMask(cursor: Cursor, doc: PsdDocument, large: Boolean) {
        if (cursor.remaining < 8) return
        var layerInfo: Cursor? = null

        // 16/32-bit documents wrap their layer info in an 'Lr16'/'Lr32' block.
        val probe = Cursor(cursor.data, cursor.pos)
        if (probe.remaining >= 12) {
            val signature = probe.fourCc()
            if (signature == "8BIM" || signature == "8B64") {
                val key = probe.fourCc()
                if (key == "Lr16" || key == "Lr32" || key == "Layr") {
                    val length = if (largeLengthKeys.contains(key)) probe.u64() else probe.u32()
                    layerInfo = Cursor(probe.bytes(length.toInt().coerceAtLeast(0)))
                    cursor.pos = probe.pos
                    if (key == "Lr16") doc.notes.add("Layers are stored as 16-bit and read as 8-bit")
                    if (key == "Lr32") doc.notes.add("Layers are stored as 32-bit and read as 8-bit")
                }
            }
        }
        if (layerInfo == null) {
            val length = if (large) cursor.u64() else cursor.u32()
            layerInfo = Cursor(cursor.bytes(length.toInt().coerceAtLeast(0)))
        }

        readLayerInfo(layerInfo, doc, large)

        // Global layer mask info (overlay colour / opacity of quick masks).
        if (cursor.remaining >= 4) {
            val length = cursor.u32().toInt()
            doc.globalLayerMaskRaw = cursor.bytes(length)
        }
    }

    private fun readLayerInfo(cursor: Cursor, doc: PsdDocument, large: Boolean) {
        if (cursor.remaining < 6) return
        val rawCount = cursor.i16()
        val layerCount = kotlin.math.abs(rawCount)
        if (layerCount == 0) return
        if (layerCount > 8000) throw PsdFormatException("Layer count ${layerCount} looks corrupt")
        if (rawCount < 0) doc.notes.add("Merged image carries transparency")

        val records = ArrayList<LayerRecord>(layerCount)
        for (i in 0 until layerCount) {
            if (cursor.remaining <= 0) break
            records.add(readLayerRecord(cursor, large))
        }
        val layerByRecord = buildLayerTree(doc, records)
        readChannelData(cursor, doc, records, layerByRecord, large)
    }

    private fun readLayerRecord(cursor: Cursor, large: Boolean): LayerRecord {
        val top = cursor.i32()
        val left = cursor.i32()
        val bottom = cursor.i32()
        val right = cursor.i32()
        val channelCount = cursor.u16()
        val channels = ArrayList<ChannelInfo>(channelCount)
        for (i in 0 until channelCount) {
            val id = cursor.i16()
            var length = cursor.u32()
            if (large) {
                val high = cursor.u32()
                if (high != 0L) length = high
            }
            channels.add(ChannelInfo(id, length))
        }
        val blendSignature = cursor.fourCc()
        val blendKey = if (blendSignature == "8BIM" || blendSignature == "8B64") cursor.fourCc() else "norm"
        val opacity = cursor.u8()
        cursor.u8() // clipping
        val flags = cursor.u8()
        cursor.u8() // filler

        val extraLength = cursor.u32().toInt().coerceAtLeast(0)
        val extraEnd = (cursor.pos + extraLength).coerceIn(cursor.pos, cursor.size)

        var mask: MaskRecord? = null
        val maskLength = cursor.u32().toInt()
        if (maskLength > 0) {
            val maskTop = cursor.i32()
            val maskLeft = cursor.i32()
            val maskBottom = cursor.i32()
            val maskRight = cursor.i32()
            val defaultColor = cursor.u8()
            val maskFlags = cursor.u8()
            mask = MaskRecord(maskTop, maskLeft, maskBottom, maskRight, defaultColor, maskFlags)
            val consumed = 18
            if (maskLength > consumed) cursor.skip((maskLength - consumed).toLong())
        }
        val blendingRangesLength = cursor.u32().toInt()
        if (blendingRangesLength > 0) cursor.skip(blendingRangesLength.toLong())

        val name = cursor.pascalString(4)

        val extra = mutableListOf<ExtraInfo>()
        while (cursor.pos + 12 <= extraEnd && cursor.remaining >= 12) {
            val signature = cursor.fourCc()
            if (signature != "8BIM" && signature != "8B64") break
            val key = cursor.fourCc()
            var length = if (largeLengthKeys.contains(key)) cursor.u64() else cursor.u32()
            if (length < 0 || length > cursor.remaining) {
                // Tolerate a partly written block rather than failing the whole file.
                length = cursor.remaining.toLong()
                extra.add(ExtraInfo(key, cursor.bytes(length.toInt())))
                break
            }
            extra.add(ExtraInfo(key, cursor.bytes(length.toInt())))
        }
        cursor.pos = extraEnd

        val record = LayerRecord(top, left, bottom, right, channels, blendKey, opacity, flags, mask, name, extra)
        record.sectionDivider = readSectionType(record)
        if (debug) {
            println(
                "  record '$name' rect=$left,$top-${right}x$bottom divider=${record.sectionDivider} " +
                    "blend=$blendKey opacity=$opacity keys=" + extra.joinToString(",") { it.key } +
                    " channels=" + record.channels.joinToString(",") { "${it.id}:${it.length}" }
            )
        }
        return record
    }

    /** `lsct` divider: 1 = open folder, 2 = closed folder, 3 = end of folder. */
    private fun readSectionType(record: LayerRecord): Int {
        val block = record.block("lsct") ?: return 0
        if (block.size < 4) return 0
        return Cursor(block).i32()
    }

    /* ------------------------------------------------------------------ tree building */

    private fun buildLayerTree(doc: PsdDocument, records: List<LayerRecord>): Array<Layer?> {
        // File order is top-to-bottom while the model is bottom-first, so records
        // are walked backwards and appended. Folder headers push a scope, and the
        // "bounding section divider" that closes a folder pops it.
        val layerByRecord = arrayOfNulls<Layer>(records.size)
        val stack = ArrayList<MutableList<Layer>>()
        stack.add(doc.children)
        for (i in records.indices.reversed()) {
            val record = records[i]
            when (record.sectionDivider) {
                1, 2 -> {
                    val group = GroupLayer(
                        name = record.name.ifBlank { "Group" },
                        visible = record.visible,
                        opacity = record.opacity / 255f,
                        blend = groupBlend(record),
                        locked = record.locked,
                    )
                    group.opened = record.sectionDivider == 1
                    group.extraInfo = record.extra.filter { it.key != "lsct" && it.key != "luni" }.toMutableList()
                    group.sourceId = readLayerId(record)
                    stack.last().add(group)
                    stack.add(group.children)
                }
                3 -> if (stack.size > 1) stack.removeAt(stack.size - 1)
                else -> {
                    val layer = toLayer(record)
                    if (layer != null) {
                        stack.last().add(layer)
                        layerByRecord[i] = layer
                    }
                }
            }
        }
        if (stack.size > 1) doc.notes.add("The file ends inside a layer group — it was closed automatically")
        return layerByRecord
    }

    private fun readLayerId(record: LayerRecord): Int? {
        val block = record.block("lyid") ?: return null
        return if (block.size >= 4) Cursor(block).i32() else null
    }

    private fun groupBlend(record: LayerRecord): BlendMode {
        val block = record.block("lsct") ?: return BlendMode.PASS_THROUGH
        if (block.size >= 12) {
            val cursor = Cursor(block, 4)
            val signature = cursor.fourCc()
            if (signature == "8BIM" || signature == "8B64") {
                return BlendMode.byKey(cursor.fourCc()) ?: BlendMode.PASS_THROUGH
            }
        }
        return BlendMode.PASS_THROUGH
    }

    private fun unsupportedReason(record: LayerRecord): String? = when {
        record.extra.any { adjustmentKeys.contains(it.key) } -> "Adjustment layer — preserved, not editable here"
        record.extra.any { smartObjectKeys.contains(it.key) } -> "Smart object — preserved, not editable here"
        record.extra.any { vectorFillKeys.contains(it.key) } -> "Vector fill layer — preserved, not editable here"
        else -> null
    }

    private fun toLayer(record: LayerRecord): Layer? {
        val note = unsupportedReason(record)
        val textBlock = record.block("TySh")
        if (textBlock != null) return readTextLayer(record, textBlock)

        val layer = RasterLayer(name = record.name.ifBlank { "Layer" })
        applyCommon(layer, record)
        layer.x = scaled(record.left)
        layer.y = scaled(record.top)
        when {
            // Files often keep the rendered pixels of fill and adjustment layers
            // (that is how other apps show them), so use them when they are there.
            record.hasContent && hasPixelChannels(record) -> {
                layer.pixels = Pixels.empty(record.width, record.height)
                if (note != null) layer.note = note
            }
            note != null -> {
                // Nothing to draw, but keep the record (and its mask) so saving
                // writes back exactly what the file had.
                layer.unsupported = note
                layer.preservedBounds = scaledRect(record.left, record.top, record.width, record.height)
            }
            else -> layer.preservedBounds = scaledRect(record.left, record.top, record.width, record.height)
        }
        return layer
    }

    /** True when the record carries channels this engine can turn into pixels. */
    private fun hasPixelChannels(record: LayerRecord): Boolean =
        record.channels.any { channel ->
            channel.length > 2L && (channel.id in 0..3 || channel.id == -1)
        }

    /** Sampling stride of the document being read (1 = full resolution). */
    private var importStride = 1

    private fun scaled(value: Int): Int = if (importStride == 1) value else value / importStride

    private fun scaledLength(value: Int): Int =
        if (importStride == 1) value.coerceAtLeast(1) else ((value + importStride - 1) / importStride).coerceAtLeast(1)

    private fun scaledRect(left: Int, top: Int, width: Int, height: Int): Rect =
        Rect(scaled(left), scaled(top), scaledLength(width), scaledLength(height))

    private fun applyCommon(layer: Layer, record: LayerRecord) {
        layer.visible = record.visible
        layer.opacity = record.opacity / 255f
        layer.blend = BlendMode.byKey(record.blendKey) ?: BlendMode.NORMAL
        layer.locked = record.locked
        layer.extraInfo = record.extra
            .filter { it.key != "lsct" && it.key != "luni" && it.key != "TySh" && it.key != "lyid" }
            .toMutableList()
        layer.sourceId = readLayerId(record)
        unicodeName(record)?.takeIf { it.isNotBlank() }?.let { layer.name = it }
    }

    private fun unicodeName(record: LayerRecord): String? {
        val block = record.block("luni") ?: return null
        if (block.size < 4) return null
        val cursor = Cursor(block)
        val length = cursor.u32().toInt()
        if (length <= 0) return null
        val bytes = cursor.bytes(kotlin.math.min(length * 2, cursor.remaining))
        return String(bytes, Charsets.UTF_16BE).trimEnd('\u0000')
    }

    /* ------------------------------------------------------------------ text layers */

    private fun readTextLayer(record: LayerRecord, block: ByteArray): TextLayer {
        val layer = TextLayer(name = record.name.ifBlank { "Text" })
        applyCommon(layer, record)
        layer.originalTySh = block
        try {
            val cursor = Cursor(block)
            cursor.i16() // version
            val transform = DoubleArray(6) { cursor.f64() }
            cursor.i16() // text version (50)
            val descriptor = readVersionAndDescriptor(cursor)
            cursor.i16() // warp version
            val warp = readVersionAndDescriptor(cursor)
            val left = cursor.f32()
            val top = cursor.f32()
            val right = cursor.f32()
            val bottom = cursor.f32()

            layer.transform = transform
            layer.x = (if (left.isFinite()) left else transform[4].toFloat()) / importStride
            layer.y = (if (top.isFinite()) top else transform[5].toFloat()) / importStride
            layer.sourceBoxWidth = (right - left).takeIf { it.isFinite() && it > 0f }

            val dict = descriptor.dict
            val rawText = dict.string("Txt ")?.replace('\r', '\n')?.trimEnd('\n') ?: ""
            layer.text.text = rawText
            layer.textIndex = dict.int("TextIndex") ?: 0
            layer.vertical = (dict["Ornt"] as? Desc.Enum)?.value == "Vrtc"
            layer.warp = WarpData(
                style = (warp.dict["warpStyle"] as? Desc.Enum)?.value ?: "warpNone",
                value = warp.dict.double("warpValue") ?: 0.0,
                perspective = warp.dict.double("warpPerspective") ?: 0.0,
                perspectiveOther = warp.dict.double("warpPerspectiveOther") ?: 0.0,
                // Stored as the Ornt enum ("Vrtc") or, in files written by other
                // tools, as an angle.
                rotate = (warp.dict["warpRotate"] as? Desc.Enum)?.let { if (it.value == "Vrtc") 90.0 else 0.0 }
                    ?: warp.dict.double("warpRotate")
                    ?: 0.0,
            )

            val engine = dict.raw("EngineData")?.data
            if (engine != null) {
                layer.engineData = engine
                val parsed = EngineData.readText(engine)
                if (parsed != null) {
                    if (parsed.text.isNotBlank() || rawText.isBlank()) layer.text.text = parsed.text
                    parsed.fontSize?.let { if (it > 0f) layer.text.size = it / importStride }
                    parsed.tracking?.let { layer.text.tracking = it }
                    layer.text.leading = if (parsed.autoLeading) null else parsed.leading
                    layer.text.fauxBold = parsed.fauxBold
                    layer.text.fauxItalic = parsed.fauxItalic
                    layer.text.underline = parsed.underline
                    parsed.fillColor?.let { layer.text.color = it }
                    layer.text.align = TextData.Align.fromPsd(parsed.justification)
                    if (!parsed.fontName.isNullOrBlank() && parsed.fontName != "AdobeInvisFont") {
                        layer.text.psdFontName = parsed.fontName
                        layer.text.psdFontScript = parsed.fontScript
                    }
                    layer.antiAlias = parsed.antiAlias
                }
            }
            if (layer.text.text.isBlank()) layer.text.text = record.name.ifBlank { "Text" }
            layer.text.fontFamily = FontMap.psdNameToDevice(layer.text.psdFontName).family
            layer.sourceBounds = scaledRect(record.left, record.top, record.width, record.height)
        } catch (_: Exception) {
            layer.unsupported = "Text layer whose type block could not be read — preserved as-is"
        }
        return layer
    }

    private fun readVersionAndDescriptor(cursor: Cursor): Desc.Obj {
        val reader = Desc.Reader(cursor.data, cursor.pos)
        val descriptor = reader.readVersionAndDescriptor()
        cursor.pos = reader.position
        return descriptor
    }

    /* ------------------------------------------------------------------ channel data */

    private fun readChannelData(
        cursor: Cursor,
        doc: PsdDocument,
        records: List<LayerRecord>,
        layerByRecord: Array<Layer?>,
        large: Boolean,
    ) {
        val bitDepth = doc.sourceBitDepth
        val grayscale = doc.sourceColorMode == COLOR_MODE_GRAYSCALE
        for (i in records.indices) {
            val record = records[i]
            val layer = layerByRecord[i]
            val raster = layer as? RasterLayer
            var red: ByteArray? = null
            var green: ByteArray? = null
            var blue: ByteArray? = null
            var alpha: ByteArray? = null
            var maskData: ByteArray? = null

            if (debug) {
                println("  channels for '${record.name}' rect=${record.left},${record.top} ${record.width}x${record.height} layer=${layer?.javaClass?.simpleName}")
            }
            for (channel in record.channels) {
                val start = cursor.pos
                if (channel.length <= 0L) continue
                val mask = record.mask
                val isMaskChannel = channel.id == -2
                val channelWidth = if (isMaskChannel && mask != null) mask.width else record.width
                val channelHeight = if (isMaskChannel && mask != null) mask.height else record.height
                if (channelWidth <= 0 || channelHeight <= 0) {
                    cursor.pos = (start + channel.length).toInt().coerceAtMost(cursor.size)
                    continue
                }
                val samples = readChannelSamples(cursor, channel.length, channelWidth, channelHeight, bitDepth)
                if (debug) {
                    val nonZero = samples.count { it != 0.toByte() }
                    println("    channel ${channel.id} len=${channel.length} ${channelWidth}x$channelHeight nonzero=$nonZero")
                }
                cursor.pos = (start + channel.length).toInt().coerceAtMost(cursor.size)
                when {
                    isMaskChannel -> maskData = samples
                    channel.id == -3 -> Unit // real user mask: kept in the file, not applied
                    layer == null -> Unit
                    channel.id == 0 -> red = samples
                    channel.id == 1 -> green = samples
                    channel.id == 2 -> blue = samples
                    channel.id == 3 || channel.id == -1 -> alpha = samples
                }
            }

            if (raster != null) {
                if (record.hasContent && raster.unsupported == null) {
                    val pixels = Pixels.empty(scaledLength(record.width), scaledLength(record.height))
                    raster.pixels = pixels
                    assemble(pixels, red, green, blue, alpha, grayscale, record.width, importStride)
                }
                val mask = record.mask
                val maskWidth = mask?.width ?: 0
                val maskHeight = mask?.height ?: 0
                if (maskData != null && mask != null && maskData.size >= maskWidth * maskHeight && maskWidth > 0) {
                    raster.mask = MaskInfo(
                        bounds = scaledRect(mask.left, mask.top, maskWidth, maskHeight),
                        data = if (importStride == 1) maskData else subsampleBytes(maskData, maskWidth, maskHeight),
                        defaultColor = mask.defaultColor,
                        disabled = (mask.flags and 0x02) != 0,
                    )
                }
            }
        }
    }

    /** Reads one channel at the cursor and hands back 8-bit samples. */
    private fun readChannelSamples(cursor: Cursor, length: Long, width: Int, height: Int, bitDepth: Int): ByteArray {
        val count = width * height
        val samples = ByteArray(count)
        val start = cursor.pos
        val available = (length.toInt() - 2).coerceAtLeast(0)
        var compression = cursor.u16()
        if (compression > 3) {
            // A one-byte shift appears in files from a few third-party writers.
            cursor.pos = start + 1
            val retry = cursor.u16()
            if (retry <= 3) compression = retry else cursor.pos = start
        }
        val payload = cursor.bytes((start + length.toInt() - cursor.pos).coerceIn(0, cursor.remaining))
        decodeInto(payload, compression, width, height, bitDepth, samples)
        cursor.pos = (start + length.toInt()).coerceAtMost(cursor.size)
        return samples
    }

    private fun decodeInto(payload: ByteArray, compression: Int, width: Int, height: Int, bitDepth: Int, out: ByteArray) {
        val count = width * height
        when (compression) {
            0 -> {
                if (bitDepth == 16) downshift16(payload, out, count)
                else System.arraycopy(payload, 0, out, 0, minOf(payload.size, count))
            }
            1 -> {
                if (bitDepth == 16) {
                    val interleaved = ByteArray(count * 2)
                    ChannelCodec.decodeRle(payload, 0, width * 2, height, interleaved)
                    downshift16(interleaved, out, count)
                } else {
                    ChannelCodec.decodeRle(payload, 0, width, height, out)
                }
            }
            2, 3 -> {
                val expected = if (bitDepth == 16) count * 2 else count
                val inflated = ChannelCodec.inflate(payload, 0, payload.size, expected)
                if (bitDepth == 16) {
                    if (compression == 3) ChannelCodec.undelta16(inflated, width, height, out)
                    else downshift16(inflated, out, count)
                } else {
                    if (compression == 3) ChannelCodec.undelta8(inflated, width, height, out)
                    else System.arraycopy(inflated, 0, out, 0, minOf(inflated.size, count))
                }
            }
            else -> Unit
        }
    }

    private fun downshift16(source: ByteArray, out: ByteArray, count: Int) {
        for (i in 0 until count) {
            val index = i * 2
            out[i] = if (index + 1 < source.size) source[index] else 0
        }
    }

    /**
     * Packs the decoded channel samples into ARGB pixels. When [stride] is > 1
     * the source is sampled down (every stride-th pixel in both directions), which
     * is how a document that does not fit in memory is still opened.
     */
    private fun assemble(
        pixels: Pixels,
        red: ByteArray?,
        green: ByteArray?,
        blue: ByteArray?,
        alpha: ByteArray?,
        grayscale: Boolean,
        sourceWidth: Int = pixels.width,
        stride: Int = 1,
    ) {
        val data = pixels.data
        val width = pixels.width
        val height = pixels.height
        var i = 0
        for (y in 0 until height) {
            val row = y * stride * sourceWidth
            for (x in 0 until width) {
                val index = row + x * stride
                val a = if (alpha != null && index < alpha.size) alpha[index].toInt() and 0xFF else 255
                val r = if (red != null && index < red.size) red[index].toInt() and 0xFF else 0
                val g = if (green != null && index < green.size) green[index].toInt() and 0xFF else if (grayscale) r else 0
                val b = if (blue != null && index < blue.size) blue[index].toInt() and 0xFF else if (grayscale) r else 0
                data[i++] = Argb.pack(a, r, g, if (grayscale) r else b)
            }
        }
    }

    /** Down-samples a mask channel with the same stride the colours used. */
    private fun subsampleBytes(source: ByteArray, width: Int, height: Int): ByteArray {
        val stride = importStride
        val outWidth = ((width + stride - 1) / stride).coerceAtLeast(1)
        val outHeight = ((height + stride - 1) / stride).coerceAtLeast(1)
        val out = ByteArray(outWidth * outHeight)
        var i = 0
        for (y in 0 until outHeight) {
            val row = y * stride * width
            for (x in 0 until outWidth) {
                val index = row + x * stride
                out[i++] = if (index < source.size) source[index] else 0
            }
        }
        return out
    }

    /* ------------------------------------------------------------------ flattened files */

    private fun importMergedAsLayer(
        doc: PsdDocument,
        data: ByteArray,
        offset: Int,
        channelCount: Int,
        width: Int,
        height: Int,
        bitDepth: Int,
        colorMode: Int,
    ) {
        if (offset <= 0 || offset + 2 >= data.size) return
        val cursor = Cursor(data, offset)
        val compression = cursor.u16()
        val count = width * height
        val channels = minOf(channelCount, 4)
        val samples = ArrayList<ByteArray>(channels)

        if (compression == 1) {
            // All row-length tables come first, then the packed rows.
            val tables = Array(channels) { IntArray(height) }
            for (c in 0 until channels) for (y in 0 until height) tables[c][y] = cursor.u16()
            for (c in 0 until channels) {
                val total = tables[c].sum()
                val packed = ByteArray(height * 2 + total)
                var p = 0
                for (y in 0 until height) {
                    packed[p++] = ((tables[c][y] ushr 8) and 0xFF).toByte()
                    packed[p++] = (tables[c][y] and 0xFF).toByte()
                }
                val rows = cursor.bytes(total)
                System.arraycopy(rows, 0, packed, p, minOf(rows.size, packed.size - p))
                val out = ByteArray(count)
                ChannelCodec.decodeRle(packed, 0, width, height, out)
                samples.add(out)
            }
        } else {
            for (c in 0 until channels) {
                val out = ByteArray(count)
                when (compression) {
                    0 -> {
                        val raw = cursor.bytes(if (bitDepth == 16) count * 2 else count)
                        if (bitDepth == 16) downshift16(raw, out, count)
                        else System.arraycopy(raw, 0, out, 0, minOf(raw.size, count))
                    }
                    2, 3 -> {
                        val blockLength = cursor.u32().toInt()
                        val payload = cursor.bytes(blockLength)
                        val expected = if (bitDepth == 16) count * 2 else count
                        val inflated = ChannelCodec.inflate(payload, 0, payload.size, expected)
                        if (bitDepth == 16) downshift16(inflated, out, count)
                        else System.arraycopy(inflated, 0, out, 0, minOf(inflated.size, count))
                    }
                }
                samples.add(out)
            }
        }

        val layer = RasterLayer(name = doc.fileName.substringAfterLast('/').ifBlank { "Flattened image" })
        val pixels = Pixels.empty(width, height)
        layer.pixels = pixels
        layer.x = 0
        layer.y = 0
        assemble(
            pixels,
            samples.getOrNull(0),
            samples.getOrNull(1),
            samples.getOrNull(2),
            samples.getOrNull(3),
            colorMode == COLOR_MODE_GRAYSCALE,
        )
        doc.children.add(layer)
    }

    /* ------------------------------------------------------------------ helpers */

    fun colorModeName(mode: Int): String = when (mode) {
        COLOR_MODE_BITMAP -> "Bitmap"
        COLOR_MODE_GRAYSCALE -> "Grayscale"
        COLOR_MODE_INDEXED -> "Indexed"
        COLOR_MODE_RGB -> "RGB"
        COLOR_MODE_CMYK -> "CMYK"
        COLOR_MODE_MULTICHANNEL -> "Multichannel"
        COLOR_MODE_DUOTONE -> "Duotone"
        COLOR_MODE_LAB -> "Lab"
        else -> "colour mode $mode"
    }

    fun outlineName(fileName: String): String {
        val base = fileName.substringAfterLast('/').substringAfterLast('\\')
        val stem = base.substringBeforeLast('.', base).ifBlank { "untitled" }
        return "$stem-edited.psd"
    }
}
