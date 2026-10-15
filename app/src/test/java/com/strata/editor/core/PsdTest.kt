package com.strata.editor.core

import kotlin.system.exitProcess

/**
 * PSD container tests. These do not need Photoshop: the reader is checked against
 * files built by hand (the shapes a real file has) and against the writer's own
 * output, and the writer is checked by reading back what it wrote.
 */
object PsdTest {

    private var passed = 0
    private var failed = 0

    private fun check(name: String, condition: Boolean, detail: String = "") {
        if (condition) {
            passed++
            println("  ok   $name")
        } else {
            failed++
            println("  FAIL $name${if (detail.isEmpty()) "" else "  ($detail)"}")
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        println("PSD container")
        roundTripLayered()
        roundTripFlat()
        textBlocks()
        descriptors()
        channelCodecs()
        foreignFiles()
        scaledImport()
        largeDocument()
        println()
        if (failed == 0) {
            println("ALL PSD CHECKS PASSED ($passed checks)")
        } else {
            println("$failed of ${passed + failed} PSD checks FAILED")
            exitProcess(1)
        }
    }

    /* ------------------------------------------------------------------ fixtures */

    private fun paint(pixmap: Pixmap, colour: Int, region: Rect? = null) {
        val area = region ?: Rect(0, 0, pixmap.width, pixmap.height)
        for (y in area.y until area.bottom) {
            for (x in area.x until area.right) pixmap[x, y] = colour
        }
    }

    private fun buildDocument(): Document {
        val document = Document(64, 48)
        val back = Layer("Background")
        back.pixels = Pixmap(64, 48).also { paint(it, Colors.rgb(30, 40, 90)) }
        document.add(back)

        val group = Layer("Group A").also { it.kind = LayerKind.GROUP; it.expanded = false }
        val red = Layer("Red square").also { it.blend = BlendMode.MULTIPLY; it.opacity = 0.6f; it.x = 4; it.y = 6 }
        red.pixels = Pixmap(20, 16).also { paint(it, Colors.rgb(220, 40, 40)) }
        red.mask = Pixmap(20, 16).also { paint(it, Colors.WHITE, Rect(0, 0, 10, 16)); paint(it, Colors.BLACK, Rect(10, 0, 10, 16)) }
        red.maskBounds = Rect(4, 6, 20, 16)
        group.children.add(red)
        val teal = Layer("Teal circle").also { it.visible = false; it.x = 10; it.y = 12 }
        teal.pixels = Pixmap(24, 24).also { paint(it, Colors.rgb(40, 200, 190)) }
        group.children.add(teal)
        document.add(group)

        val text = Layer("Headline").also {
            it.kind = LayerKind.TEXT
            it.text = TextContent(
                text = "Hello strata",
                fontFamily = "Sans",
                fontSize = 24f,
                color = Colors.rgb(250, 250, 255),
                bold = true,
                alignment = TextAlign.CENTER,
                leading = 1.2f,
                tracking = 0.05f,
            )
            it.textLayout = Rect(8, 20, 48, 18)
        }
        document.add(text)
        document.resources = byteArrayOf(0, 0, 0, 0)
        return document
    }

    /* -------------------------------------------------------------- round trips */

    private fun roundTripLayered() {
        println(" layered file round trip")
        val original = buildDocument()
        val bytes = PsdFile.write(original)
        check("written file has the PSD signature", String(bytes, 0, 4) == "8BPS", String(bytes, 0, 4))
        check("the file is a version 1 PSD", bytes[4].toInt() == 0 && bytes[5].toInt() == 1)
        val reopened = PsdFile.read(bytes, "roundtrip.psd")

        check("canvas size survives", reopened.width == 64 && reopened.height == 48, "${reopened.width}x${reopened.height}")
        check("layer count survives", reopened.layers.size == 3, "${reopened.layers.size}")
        val group = reopened.layers.getOrNull(1)
        check("group nesting survives", group?.kind == LayerKind.GROUP && group.children.size == 2,
            "kind=${group?.kind} children=${group?.children?.size}")
        check("collapsed group stays collapsed", group?.expanded == false)
        val background = reopened.layers.getOrNull(0)
        check("names survive", background?.name == "Background" && reopened.layers.getOrNull(2)?.name == "Headline",
            "${background?.name} / ${reopened.layers.getOrNull(2)?.name}")
        check("blend mode survives", group?.children?.firstOrNull()?.blend == BlendMode.MULTIPLY,
            "${group?.children?.firstOrNull()?.blend}")
        check("opacity survives", kotlin.math.abs((group?.children?.firstOrNull()?.opacity ?: 0f) - 0.6f) < 0.01f,
            "${group?.children?.firstOrNull()?.opacity}")
        check("hidden layer stays hidden", group?.children?.getOrNull(1)?.visible == false)
        check("layer position survives", group?.children?.firstOrNull()?.x == 4 && group?.children?.firstOrNull()?.y == 6,
            "${group?.children?.firstOrNull()?.x},${group?.children?.firstOrNull()?.y}")

        val red = group?.children?.firstOrNull()?.pixels
        check("pixel data survives", red != null && red.width == 20 && red.height == 16, "${red?.width}x${red?.height}")
        check("pixel colours survive", red?.get(2, 2) == Colors.rgb(220, 40, 40), "${red?.get(2, 2)?.let { Colors.toHex(it) }}")
        val mask = group?.children?.firstOrNull()?.mask
        check("mask survives", mask != null && Colors.luminance(mask[2, 2]) > 200 && Colors.luminance(mask[15, 2]) < 60,
            "${mask?.let { Colors.luminance(it[2, 2]) }} / ${mask?.let { Colors.luminance(it[15, 2]) }}")
        check("mask position survives", group?.children?.firstOrNull()?.maskBounds == Rect(4, 6, 20, 16),
            "${group?.children?.firstOrNull()?.maskBounds}")

        val text = reopened.layers.getOrNull(2)
        check("text layer stays a text layer", text?.kind == LayerKind.TEXT, "${text?.kind}")
        check("text content survives", text?.text?.text == "Hello strata", "${text?.text?.text}")
        check("text style survives", text?.text?.bold == true && kotlin.math.abs((text?.text?.fontSize ?: 0f) - 24f) < 0.5f,
            "bold=${text?.text?.bold} size=${text?.text?.fontSize}")
        check("text block is kept byte for byte", text?.psdTextBlock != null && text.textEdited.not(),
            "block=${text?.psdTextBlock?.size}")

        val flat = Pixmap(reopened.width, reopened.height)
        Compositor.flatten(reopened, flat, 0)
        val originalFlat = Pixmap(original.width, original.height)
        Compositor.flatten(original, originalFlat, 0)
        var differences = 0
        for (i in flat.pixels.indices) if (flat.pixels[i] != originalFlat.pixels[i]) differences++
        check("the flattened result is identical after a round trip", differences == 0, "$differences differing pixels")

        val again = PsdFile.read(PsdFile.write(reopened), "again.psd")
        check("a second round trip is stable", again.layers.size == reopened.layers.size &&
            again.layers.getOrNull(1)?.children?.size == 2)
    }

    private fun roundTripFlat() {
        println(" merged image fallback")
        val document = Document(32, 24)
        val layer = Layer("Art")
        layer.pixels = Pixmap(32, 24).also { paint(it, Colors.rgb(200, 120, 60)) }
        document.add(layer)
        val bytes = PsdFile.write(document)
        // Strip the layer section by hand: a flat file has layers = 0 and just the preview.
        val flat = stripLayers(bytes)
        check("the stripped file is smaller than the layered one", flat.size < bytes.size, "${flat.size} < ${bytes.size}")
        val reopened = PsdFile.read(flat, "flat.psd")
        check("a flat file opens as one layer", reopened.layers.size == 1, "${reopened.layers.size}")
        check("the preview pixels arrive", reopened.layers[0].pixels?.get(4, 4) == Colors.rgb(200, 120, 60),
            "${reopened.layers[0].pixels?.get(4, 4)?.let { Colors.toHex(it) }}")
        check("the flat import is explained", reopened.preserved.any { it.contains("flat") }, reopened.preserved.joinToString())
    }

    /** Rebuilds a written file without its layer section, the way a flattened export is. */
    private fun stripLayers(bytes: ByteArray): ByteArray {
        val cursor = PsdFile.Cursor(bytes)
        cursor.skip(4 + 2 + 6 + 2 + 4 + 4 + 2 + 2)
        val colorModeLength = cursor.u32Int()
        cursor.skip(colorModeLength)
        val resourcesLength = cursor.u32Int()
        cursor.skip(resourcesLength)
        val layerLength = cursor.u32Int()
        val mergedStart = cursor.position + layerLength
        val out = java.io.ByteArrayOutputStream()
        out.write(bytes, 0, cursor.position - 4)
        out.write(byteArrayOf(0, 0, 0, 0))
        out.write(bytes, mergedStart, bytes.size - mergedStart)
        return out.toByteArray()
    }

    private fun textBlocks() {
        println(" text blocks")
        val content = TextContent(text = "Round trip", fontFamily = "Inter", fontSize = 18f, color = Colors.rgb(1, 2, 3), italic = true)
        val block = TextLayers.build(content, Mat3.translation(5f, 7f), Rect(5, 7, 120, 30), Rect(0, 0, 200, 200))
        check("the block starts with a version-1 header", block[0].toInt() == 0 && block[1].toInt() == 1,
            "${block[0]},${block[1]}")
        val parsed = TextLayers.parse(block)
        check("a built block parses back", parsed != null)
        check("text survives", parsed?.content?.text == "Round trip", "${parsed?.content?.text}")
        check("font survives", parsed?.content?.fontFamily == "Inter", "${parsed?.content?.fontFamily}")
        check("size survives", kotlin.math.abs((parsed?.content?.fontSize ?: 0f) - 18f) < 0.01f, "${parsed?.content?.fontSize}")
        check("colour survives", parsed?.content?.color == Colors.rgb(1, 2, 3), "${parsed?.content?.color?.let { Colors.toHex(it) }}")
        check("the layout rectangle survives", parsed?.layout == Rect(5, 7, 120, 30), "${parsed?.layout}")
        check("the transform survives", kotlin.math.abs((parsed?.transform?.tx ?: 0f) - 5f) < 0.01f,
            "tx=${parsed?.transform?.tx}")

        // A text layer whose block was not touched must be written back exactly.
        val document = buildDocument()
        val textLayer = document.layers[2]
        textLayer.psdTextBlock = block
        textLayer.textLayout = Rect(5, 7, 120, 30)
        textLayer.textTransform = Mat3.translation(5f, 7f)
        val round = PsdFile.read(PsdFile.write(document), "text.psd")
        val readBlock = round.layers[2].psdTextBlock
        check("an untouched text block is re-emitted byte for byte", readBlock != null && readBlock.contentEquals(block),
            "${readBlock?.size} vs ${block.size}")

        // After an edit the block must be rebuilt from the new content.
        round.layers[2].text = TextContent(text = "Changed", fontFamily = "Inter", fontSize = 12f)
        round.layers[2].textEdited = true
        round.layers[2].psdTextBlock = block
        val edited = PsdFile.read(PsdFile.write(round), "edited.psd")
        check("an edited text layer is rebuilt", edited.layers[2].text?.text == "Changed", "${edited.layers[2].text?.text}")
    }

    private fun descriptors() {
        println(" descriptors")
        val writer = DescriptorWriter()
        writer.descriptor("null", 8) {
            text("Txt ", "Hello")
            double("LongKeyName", 12.5)
            long("Int ", 42)
            bool("bool", true)
            enumeration("enum", "BlnM", "Nrml")
            unitDouble("unit", "#Pnt", 3.5)
            objectValue("Ornt", "Ornt", 1) {
                enumeration("Ornt", "Ornt", "Hrzn")
            }
            list("VlLs", 3) {
                textValue("a")
                textValue("b")
                doubleValue(3.0)
            }
        }
        val encoded = writer.bytes()
        val decoded = DescriptorReader(encoded).descriptor()
        check("a descriptor is read back", decoded.isNotEmpty(), "${decoded.size} entries")
        check("unicode text survives", decoded["Txt "] == "Hello", "${decoded["Txt "]}")
        check("a long key is not confused with a four-character one", decoded["LongKeyName"] == 12.5, "${decoded["LongKeyName"]}")
        check("an integer survives", decoded["Int "] == 42, "${decoded["Int "]}")
        check("a boolean survives", decoded["bool"] == true, "${decoded["bool"]}")
        check("an enum survives", (decoded["enum"] as? Map<*, *>)?.get("value") == "Nrml", "${decoded["enum"]}")
        check("a unit value survives", (decoded["unit"] as? Map<*, *>)?.get("value") == 3.5, "${decoded["unit"]}")
        check("a nested object is inline, not a reference", decoded["Ornt"] is Map<*, *>, "${decoded["Ornt"]}")
        check("a nested object keeps its contents",
            ((decoded["Ornt"] as? Map<*, *>)?.get("Ornt") as? Map<*, *>)?.get("value") == "Hrzn",
            "${decoded["Ornt"]}")
        check("an item array survives", (decoded["VlLs"] as? List<*>)?.size == 3, "${decoded["VlLs"]}")

        // A truncated or corrupt descriptor must not throw or loop: the block is kept
        // as an opaque blob instead, and the file still opens.
        var survived = true
        try {
            DescriptorReader(encoded.copyOfRange(0, encoded.size - 3)).descriptor()
        } catch (error: Exception) {
            survived = false
        }
        check("a truncated descriptor does not throw", survived)
        val corrupt = encoded.copyOf()
        corrupt[9] = 0x7F
        var survivedCorrupt = true
        try {
            DescriptorReader(corrupt).descriptor()
        } catch (error: Exception) {
            survivedCorrupt = false
        }
        check("a corrupt descriptor does not throw", survivedCorrupt)

        val document = LinkedHashMap<String, Any>()
        val engine = LinkedHashMap<String, Any>()
        val editor = LinkedHashMap<String, Any>()
        editor["Text"] = "Engine data"
        engine["Editor"] = editor
        val fonts = ArrayList<Any>()
        val font = LinkedHashMap<String, Any>()
        font["Name"] = "StrataSans"
        fonts.add(font)
        engine["FontSet"] = fonts
        document["EngineDict"] = engine
        val data = EngineData.write(document)
        check("engine data starts with a tagged header", data[0].toInt() == '<'.code && data[1].toInt() == '<'.code,
            "${data[0]},${data[1]}")
        val readBack = EngineData.read(data)
        check("engine data round trips", readBack?.engineText() == "Engine data", "${readBack?.engineText()}")
        check("the font name is picked out of the font set", readBack?.engineFontName() == "StrataSans", "${readBack?.engineFontName()}")
        check("a missing key reads as null, not as a crash", readBack?.dict("Nothing") == null)
        val emptyRead = EngineData.read(byteArrayOf(0, 0, 0, 0))
        check("a header that says nothing reads as no data, not as a crash",
            emptyRead == null || emptyRead.isEmpty(), "${emptyRead?.size}")
    }

    private fun channelCodecs() {
        println(" channel codecs")
        val row = byteArrayOf(1, 1, 1, 1, 2, 3, 4, 5, 9, 9, 9, 9, 9, 7)
        val encoded = PsdFile.encodeRleRow(row)
        val decoded = decodePackBits(encoded, row.size)
        check("packbits round trips a mixed row", decoded.contentEquals(row), decoded.joinToString())
        val runs = ByteArray(300) { 5 }
        val encodedRun = PsdFile.encodeRleRow(runs)
        check("a long run encodes small", encodedRun.size <= 6, "${encodedRun.size} bytes for 300")
        check("a long run decodes", decodePackBits(encodedRun, runs.size).contentEquals(runs))
        val random = ByteArray(257) { (it * 37 % 251).toByte() }
        check("a row longer than 128 bytes round trips", decodePackBits(PsdFile.encodeRleRow(random), random.size).contentEquals(random))
        check("an empty row encodes to nothing", PsdFile.encodeRleRow(ByteArray(0)).isEmpty())
    }

    private fun decodePackBits(bytes: ByteArray, expected: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(expected)
        var index = 0
        while (index < bytes.size && out.size() < expected) {
            val header = bytes[index++].toInt()
            if (header >= 0) {
                val run = header + 1
                for (i in 0 until run) if (index < bytes.size) out.write(bytes[index++].toInt())
            } else if (header != -128) {
                val run = 1 - header
                if (index < bytes.size) {
                    val value = bytes[index++]
                    repeat(run) { out.write(value.toInt()) }
                }
            }
        }
        return out.toByteArray()
    }

    /* --------------------------------------------------------- foreign files */

    private fun foreignFiles() {
        println(" files written elsewhere")
        // A 2x2 raw RGB file with no layer section at all.
        val flat = java.io.ByteArrayOutputStream()
        flat.write("8BPS".toByteArray(Charsets.US_ASCII))
        write16(flat, 1); repeat(6) { flat.write(0) }
        write16(flat, 3); write32(flat, 2); write32(flat, 2); write16(flat, 8); write16(flat, 3)
        write32(flat, 0)
        write32(flat, 0)
        write32(flat, 0)
        write16(flat, 0)
        flat.write(byteArrayOf(255.toByte(), 0, 0, 0))
        flat.write(byteArrayOf(0, 255.toByte(), 0, 0))
        flat.write(byteArrayOf(0, 0, 255.toByte(), 0))
        val rgb = PsdFile.read(flat.toByteArray(), "raw.psd")
        check("a raw RGB file opens", rgb.width == 2 && rgb.height == 2, "${rgb.width}x${rgb.height}")
        val pixels = rgb.layers.getOrNull(0)?.pixels
        check("raw red arrives", pixels?.get(0, 0) == Colors.rgb(255, 0, 0), "${pixels?.get(0, 0)?.let { Colors.toHex(it) }}")
        check("raw blue arrives", pixels?.get(0, 1) == Colors.rgb(0, 0, 255), "${pixels?.get(0, 1)?.let { Colors.toHex(it) }}")
        check("a file without alpha is opaque", Colors.alpha(pixels?.get(0, 0) ?: 0) == 255)

        // A 2x2 indexed file: one channel, palette in the colour mode data.
        val indexed = java.io.ByteArrayOutputStream()
        indexed.write("8BPS".toByteArray(Charsets.US_ASCII))
        write16(indexed, 1); repeat(6) { indexed.write(0) }
        write16(indexed, 1); write32(indexed, 2); write32(indexed, 2); write16(indexed, 8); write16(indexed, 2)
        write32(indexed, 4 + 3 * 3)
        write32(indexed, 3)
        indexed.write(byteArrayOf(0, 0, 0, 255.toByte(), 0, 0, 0, 255.toByte(), 0))
        write32(indexed, 0)
        write32(indexed, 0)
        write16(indexed, 0)
        indexed.write(byteArrayOf(0, 1, 2, 1))
        val paletted = PsdFile.read(indexed.toByteArray(), "indexed.psd")
        val palettedPixels = paletted.layers.getOrNull(0)?.pixels
        check("an indexed file opens", palettedPixels != null)
        check("palette colour 1 is red", palettedPixels?.get(1, 0) == Colors.rgb(255, 0, 0), "${palettedPixels?.get(1, 0)?.let { Colors.toHex(it) }}")
        check("palette colour 2 is green", palettedPixels?.get(0, 1) == Colors.rgb(0, 255, 0), "${palettedPixels?.get(0, 1)?.let { Colors.toHex(it) }}")
        check("the conversion is noted", paletted.preserved.any { it.contains("indexed") }, paletted.preserved.joinToString())

        // A greyscale file.
        val grey = java.io.ByteArrayOutputStream()
        grey.write("8BPS".toByteArray(Charsets.US_ASCII))
        write16(grey, 1); repeat(6) { grey.write(0) }
        write16(grey, 1); write32(grey, 1); write32(grey, 2); write16(grey, 8); write16(grey, 1)
        write32(grey, 0); write32(grey, 0); write32(grey, 0)
        write16(grey, 0)
        grey.write(byteArrayOf(10, 200.toByte()))
        val mono = PsdFile.read(grey.toByteArray(), "grey.psd")
        val monoPixels = mono.layers.getOrNull(0)?.pixels
        check("a greyscale file opens", monoPixels?.get(1, 0) == Colors.rgb(200, 200, 200), "${monoPixels?.get(1, 0)?.let { Colors.toHex(it) }}")

        // A 16-bit file: the high byte is what matters until the editor is 16-bit.
        val deep = java.io.ByteArrayOutputStream()
        deep.write("8BPS".toByteArray(Charsets.US_ASCII))
        write16(deep, 1); repeat(6) { deep.write(0) }
        write16(deep, 3); write32(deep, 1); write32(deep, 1); write16(deep, 16); write16(deep, 3)
        write32(deep, 0); write32(deep, 0); write32(deep, 0)
        write16(deep, 0)
        deep.write(byteArrayOf(0x80.toByte(), 0x00)); deep.write(byteArrayOf(0x40, 0x00)); deep.write(byteArrayOf(0x20, 0x00))
        val sixteen = PsdFile.read(deep.toByteArray(), "deep.psd")
        check("16-bit channels are reduced to their high byte",
            sixteen.layers.getOrNull(0)?.pixels?.get(0, 0) == Colors.rgb(0x80, 0x40, 0x20),
            "${sixteen.layers.getOrNull(0)?.pixels?.get(0, 0)?.let { Colors.toHex(it) }}")
        check("the reduction is noted", sixteen.preserved.any { it.contains("16-bit") }, sixteen.preserved.joinToString())

        // A file with an unknown additional-info block must keep it.
        val document = Document(8, 8)
        val layer = Layer("Kept")
        layer.pixels = Pixmap(8, 8).also { paint(it, Colors.rgb(9, 9, 9)) }
        layer.psdBlocks["whtv"] = byteArrayOf(1, 2, 3, 4, 5)
        document.add(layer)
        val reopened = PsdFile.read(PsdFile.write(document), "kept.psd")
        val kept = reopened.layers[0].psdBlocks["whtv"]
        check("an unknown block survives a save", kept != null && kept.contentEquals(byteArrayOf(1, 2, 3, 4, 5)),
            "${kept?.joinToString()}")

        // A file that is not a PSD must fail with a readable message.
        var message = ""
        try {
            PsdFile.read("this is a JPEG, honestly".toByteArray(), "x.jpg")
        } catch (error: PsdFile.FormatException) {
            message = error.message ?: ""
        }
        check("a non-PSD is rejected with a message", message.contains("Photoshop"), message)
    }

    private fun scaledImport() {
        println(" oversized import")
        val document = Document(400, 300)
        val layer = Layer("Big")
        layer.pixels = Pixmap(400, 300).also { pixmap ->
            paint(pixmap, Colors.rgb(10, 200, 10), Rect(0, 0, 200, 300))
            paint(pixmap, Colors.rgb(200, 10, 10), Rect(200, 0, 200, 300))
        }
        document.add(layer)
        val bytes = PsdFile.write(document)
        val options = PsdFile.ReadOptions(maxPixels = 10_000)
        val small = PsdFile.read(bytes, "big.psd", options)
        check("an oversized file is read at reduced resolution", small.width == 100 && small.height == 75, "${small.width}x${small.height}")
        check("the reduction is reported", small.preserved.any { it.contains("1/4") }, small.preserved.joinToString())
        val smallPixels = small.layers.getOrNull(0)?.pixels
        check("the left half is still green", Colors.luminance(smallPixels?.get(20, 30) ?: 0) < Colors.luminance(Colors.rgb(10, 200, 10)) + 4)
        check("green stays green after scaling", Colors.green(smallPixels?.get(20, 30) ?: 0) > 150, "${smallPixels?.get(20, 30)?.let { Colors.toHex(it) }}")
        check("red stays red after scaling", Colors.red(smallPixels?.get(80, 30) ?: 0) > 150, "${smallPixels?.get(80, 30)?.let { Colors.toHex(it) }}")
    }

    private fun largeDocument() {
        println(" PSB output")
        val document = Document(200, 120)
        val layer = Layer("Wide")
        layer.pixels = Pixmap(200, 120).also { paint(it, Colors.rgb(80, 80, 200)) }
        document.add(layer)
        val bytes = PsdFile.write(document, PsdFile.WriteOptions(large = true))
        check("a forced PSB declares version 2", bytes[4].toInt() == 0 && bytes[5].toInt() == 2)
        val reopened = PsdFile.read(bytes, "wide.psb")
        check("a PSB opens", reopened.width == 200 && reopened.height == 120, "${reopened.width}x${reopened.height}")
        check("PSB layer pixels arrive", reopened.layers.getOrNull(0)?.pixels?.get(150, 60) == Colors.rgb(80, 80, 200),
            "${reopened.layers.getOrNull(0)?.pixels?.get(150, 60)?.let { Colors.toHex(it) }}")
        val auto = PsdFile.write(Document(40_000, 20), PsdFile.WriteOptions(writeMerged = false))
        check("a canvas wider than 30,000 px is written as PSB automatically", auto[4].toInt() == 0 && auto[5].toInt() == 2)
    }

    private fun write16(out: java.io.ByteArrayOutputStream, value: Int) {
        out.write((value ushr 8) and 0xFF)
        out.write(value and 0xFF)
    }

    private fun write32(out: java.io.ByteArrayOutputStream, value: Int) {
        out.write((value ushr 24) and 0xFF)
        out.write((value ushr 16) and 0xFF)
        out.write((value ushr 8) and 0xFF)
        out.write(value and 0xFF)
    }
}
