package com.photocc.app.core

import java.io.File
import kotlin.math.abs
import kotlin.system.exitProcess

/**
 * Engine self-test.
 *
 * The Android build cannot run unit tests here (no Android SDK), so the whole
 * engine is deliberately free of `android.*` imports and this plain-`main()`
 * harness exercises it on a desktop JVM:
 *
 *  1. reads real Photoshop-authored PSDs and compares every layer's pixels with
 *     an independent implementation (`ag-psd` via tools/engine/compare-read.mjs);
 *  2. builds a document with the engine, writes it, reads it back and compares;
 *  3. edits a text layer and writes a file that the independent reader verifies
 *     (tools/engine/verify-write.mjs), which is what proves the type blocks are
 *     valid;
 *  4. checks the compositing maths (blend modes, opacity, masks, clipping) and
 *     the painting tools against hand-computed values.
 *
 * Run it with `./gradlew engineTest`, or locally in a checkout with
 * `tools/engine-test.sh`.
 */
object EngineSelfTest {

    private var passed = 0
    private var failed = 0
    private val failures = mutableListOf<String>()

    private val outDir = File(System.getenv("PHOTO_CC_OUT") ?: "build/engine-test")
    private val fixtureDir = File(System.getenv("PHOTO_CC_FIXTURES") ?: "tools/engine/fixtures")

    @JvmStatic
    fun main(args: Array<String>) {
        AwtTextSupport.install()
        outDir.mkdirs()
        println("photo_cc engine self-test")
        println("  output   : ${outDir.absolutePath}")
        println("  fixtures : ${fixtureDir.absolutePath}")

        realFileSection()
        renderMathSection()
        paintingSection()
        sessionSection()
        roundTripSection()
        textSection()
        memorySection()
        report()
    }

    /* ------------------------------------------------------------------ 1. real files */

    private fun realFileSection() {
        section("Real PSD files")
        val expectations = Expectations.load(File(fixtureDir, "expected.json"))
        val files = (fixtureDir.listFiles { f -> f.extension.lowercase() == "psd" } ?: emptyArray())
            .sortedBy { it.name }

        if (files.isEmpty()) {
            println("  (no PSD fixtures found — run tools/engine-test.sh to generate them)")
        }
        for (file in files) {
            val doc = try {
                PsdRead.read(file.readBytes(), file.name)
            } catch (e: Exception) {
                check("read ${file.name}", false, e.message ?: "exception")
                continue
            }
            check("read ${file.name}", true, "${doc.width}x${doc.height}, ${doc.layerCount()} layers, ${doc.sourceBitDepth}-bit")

            val expected = expectations?.documents?.get(file.name)
            if (expected != null) {
                check("${file.name}: size", expected.width == doc.width && expected.height == doc.height,
                    "kept ${doc.width}x${doc.height}, expected ${expected.width}x${expected.height}")
                val mine = layerSummaries(doc)
                check("${file.name}: layer count", mine.size == expected.layers.size,
                    "read ${mine.size} layers, libread ${expected.layers.size}")
                var matched = 0
                for (want in expected.layers) {
                    val have = mine.firstOrNull { it.name == want.name && it.kind == want.kind }
                    if (have == null) continue
                    if (want.digest == null) {
                        matched++
                        continue
                    }
                    if (have.digest == want.digest && have.width == want.width && have.height == want.height) matched++
                    else println("    pixel mismatch on '${want.name}': ${have.width}x${have.height} ${have.digest} vs ${want.width}x${want.height} ${want.digest}")
                }
                check("${file.name}: layer pixels match the reference reader", matched == expected.layers.size,
                    "$matched/${expected.layers.size} layers identical")
                for (want in expected.texts) {
                    val have = mine.firstOrNull { it.kind == "text" && it.text == want }
                    check("${file.name}: text \"${want.take(24)}\"", have != null, "not found")
                }
            }
        }
    }

    private class Summary(
        val name: String,
        val kind: String,
        val width: Int,
        val height: Int,
        val digest: String?,
        val text: String?,
    )

    private fun layerSummaries(doc: PsdDocument): List<Summary> {
        val out = ArrayList<Summary>()
        doc.walkLayers { layer, _ ->
            when (layer) {
                is RasterLayer -> {
                    val pixels = layer.pixels
                    out.add(
                        Summary(
                            name = layer.name,
                            kind = "raster",
                            width = pixels?.width ?: 0,
                            height = pixels?.height ?: 0,
                            digest = pixels?.let { Digest.of(it.data, it.width, it.height) },
                            text = null,
                        )
                    )
                }
                is TextLayer -> out.add(
                    Summary(layer.name, "text", 0, 0, null, layer.text.text)
                )
                is GroupLayer -> out.add(Summary(layer.name, "group", 0, 0, null, null))
            }
        }
        return out
    }

    private class Expectations(val documents: Map<String, ExpectedDocument>) {
        class ExpectedDocument(
            val width: Int,
            val height: Int,
            val layers: List<ExpectedLayer>,
            val texts: List<String>,
        )

        class ExpectedLayer(val name: String, val kind: String, val width: Int, val height: Int, val digest: String?)

        companion object {
            fun load(file: File): Expectations? {
                val root = TinyJson.parse(file) ?: return null
                val documents = HashMap<String, ExpectedDocument>()
                val docs = root["documents"] as? Map<*, *> ?: return Expectations(emptyMap())
                for ((name, value) in docs) {
                    val doc = value as? Map<*, *> ?: continue
                    val layers = (doc["layers"] as? List<*>)?.mapNotNull { entry ->
                        val map = entry as? Map<*, *> ?: return@mapNotNull null
                        ExpectedLayer(
                            name = map["name"] as? String ?: "",
                            kind = map["kind"] as? String ?: "raster",
                            width = (map["width"] as? Double)?.toInt() ?: 0,
                            height = (map["height"] as? Double)?.toInt() ?: 0,
                            digest = map["digest"] as? String,
                        )
                    } ?: emptyList()
                    val texts = (doc["texts"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList()
                    documents[name as String] = ExpectedDocument(
                        width = (doc["width"] as? Double)?.toInt() ?: 0,
                        height = (doc["height"] as? Double)?.toInt() ?: 0,
                        layers = layers,
                        texts = texts,
                    )
                }
                return Expectations(documents)
            }
        }
    }

    /** Purpose-built JSON reader: the expectations file is written by our own tools. */
    private object TinyJson {
        fun parse(file: File): Map<String, Any?>? {
            if (!file.exists()) return null
            return parse(file.readText())
        }

        fun parse(text: String): Map<String, Any?>? {
            val parser = Parser(text)
            @Suppress("UNCHECKED_CAST")
            return parser.parseValue() as? Map<String, Any?>
        }

        private class Parser(private val text: String) {
            private var index = 0
            private fun skipSpace() {
                while (index < text.length && text[index].isWhitespace()) index++
            }

            private fun parseString(): String {
                    skipSpace()
                    require(text[index] == '"') { "expected '\"' at $index" }
                    index++
                    val sb = StringBuilder()
                    while (index < text.length && text[index] != '"') {
                        if (text[index] == '\\') {
                            index++
                            sb.append(
                                when (text[index]) {
                                    'n' -> '\n'
                                    't' -> '\t'
                                    'r' -> '\r'
                                    else -> text[index]
                                }
                            )
                        } else {
                            sb.append(text[index])
                        }
                        index++
                    }
                    index++
                    return sb.toString()
                }

            fun parseValue(): Any? {
                skipSpace()
                    return when (text[index]) {
                        '{' -> parseObject()
                        '[' -> parseArray()
                        '"' -> parseString()
                        'n' -> {
                            index += 4
                            null
                        }
                        't' -> {
                            index += 4
                            true
                        }
                        'f' -> {
                            index += 5
                            false
                        }
                        else -> {
                            val start = index
                            while (index < text.length && (text[index].isDigit() || text[index] == '-' || text[index] == '.' || text[index] == 'e' || text[index] == 'E')) index++
                            text.substring(start, index).toDoubleOrNull()
                        }
                    }
                }

            private fun parseArray(): List<Any?> {
                index++
                    val items = ArrayList<Any?>()
                    skipSpace()
                    if (text[index] == ']') {
                        index++
                        return items
                    }
                    while (true) {
                        items.add(parseValue())
                        skipSpace()
                        when (text[index]) {
                            ',' -> index++
                            ']' -> {
                                index++
                                return items
                            }
                        }
                    }
                }

            private fun parseObject(): Map<String, Any?> {
                index++
                    val map = LinkedHashMap<String, Any?>()
                    skipSpace()
                    if (text[index] == '}') {
                        index++
                        return map
                    }
                    while (true) {
                        skipSpace()
                        val key = parseString()
                        skipSpace()
                        index++ // ':'
                        map[key] = parseValue()
                        skipSpace()
                        when (text[index]) {
                            ',' -> index++
                            '}' -> {
                                index++
                                return map
                            }
                        }
                    }
                }

        }
    }

    /* ------------------------------------------------------------------ 2. compositing */

    private fun renderMathSection() {
        section("Compositing maths")
        val opaqueRed = Argb.pack(255, 255, 0, 0)
        val opaqueBlue = Argb.pack(255, 0, 0, 255)

        check("source-over: opaque covers", Argb.over(opaqueBlue, opaqueRed, 255) == opaqueRed)
        val half = Argb.over(0, Argb.pack(255, 10, 20, 30), 128)
        check("source-over: 50% alpha on empty backdrop keeps colour", Argb.a(half) == 128 && Argb.r(half) == 10,
            "got a=${Argb.a(half)} r=${Argb.r(half)}")

        // multiply of 0.5 grey with 0.5 grey is 0.25 grey
        val grey = Argb.pack(255, 128, 128, 128)
        val multiplied = Render.blendPixel(grey, grey, 255, BlendMode.MULTIPLY)
        check("multiply: 50% × 50% = 25%", abs(Argb.r(multiplied) - 64) <= 1, "got ${Argb.r(multiplied)}")

        val screened = Render.blendPixel(grey, grey, 255, BlendMode.SCREEN)
        check("screen: 50% + 50% = 75%", abs(Argb.r(screened) - 191) <= 1, "got ${Argb.r(screened)}")

        val overlaid = Render.blendPixel(Argb.pack(255, 255, 255, 255), Argb.pack(255, 0, 0, 0), 255, BlendMode.OVERLAY)
        check("overlay: white backdrop stays white", Argb.r(overlaid) == 255, "got ${Argb.r(overlaid)}")

        val difference = Render.blendPixel(Argb.pack(255, 200, 100, 50), Argb.pack(255, 60, 40, 20), 255, BlendMode.DIFFERENCE)
        check(
            "difference: |backdrop - source|",
            Argb.r(difference) == 140 && Argb.g(difference) == 60 && Argb.b(difference) == 30,
            "got ${Argb.r(difference)},${Argb.g(difference)},${Argb.b(difference)}",
        )

        // Hue/saturation/color/luminosity must stay in range and keep the source alpha.
        for (mode in listOf(BlendMode.HUE, BlendMode.SATURATION, BlendMode.COLOR, BlendMode.LUMINOSITY)) {
            val value = Render.blendPixel(Argb.pack(255, 30, 200, 90), Argb.pack(255, 250, 10, 10), 255, mode)
            check("${mode.label}: stays in range", Argb.r(value) in 0..255 && Argb.g(value) in 0..255 && Argb.b(value) in 0..255 && Argb.a(value) == 255)
        }

        // Layer opacity
        val doc = PsdDocument(4, 1)
        val base = RasterLayer(name = "base")
        base.pixels = Pixels(4, 1, IntArray(4) { opaqueRed })
        val top = RasterLayer(name = "top", opacity = 0.5f)
        top.pixels = Pixels(4, 1, IntArray(4) { opaqueBlue })
        doc.children.add(base)
        doc.children.add(top)
        val region = Rect(0, 0, 4, 1)
        val composite = IntArray(4)
        Render.flatten(doc, region, composite)
        check(
            "layer opacity: red under a 50% blue layer",
            abs(Argb.r(composite[0]) - 128) <= 2 && abs(Argb.b(composite[0]) - 128) <= 2,
            "got ${Argb.r(composite[0])},${Argb.g(composite[0])},${Argb.b(composite[0])}",
        )

        // Masking
        val masked = RasterLayer(name = "masked")
        masked.pixels = Pixels(4, 1, IntArray(4) { opaqueRed })
        masked.mask = MaskInfo(Rect(0, 0, 4, 1), byteArrayOf(0, 85, -1, -1), 255, false)
        doc.children.clear()
        doc.children.add(masked)
        Render.flatten(doc, region, composite)
        check(
            "layer mask: 0 / 33% / 100% coverage",
            Argb.a(composite[0]) == 0 && abs(Argb.a(composite[1]) - 85) <= 1 && Argb.a(composite[2]) == 255,
            "got ${Argb.a(composite[0])},${Argb.a(composite[1])},${Argb.a(composite[2])}",
        )

        // Clipping
        val clipBase = RasterLayer(name = "clip base")
        clipBase.pixels = Pixels(4, 1, IntArray(4) { if (it < 2) opaqueRed else 0 })
        val clipped = RasterLayer(name = "clipped").also { it.rawClipping = 1 }
        clipped.pixels = Pixels(4, 1, IntArray(4) { opaqueBlue })
        doc.children.clear()
        doc.children.add(clipBase)
        doc.children.add(clipped)
        Render.flatten(doc, region, composite)
        check(
            "clipping mask: clipped layer shows only over its base",
            Argb.b(composite[0]) == 255 && Argb.a(composite[2]) == 0,
            "got b=${Argb.b(composite[0])} a2=${Argb.a(composite[2])}",
        )
    }

    /* ------------------------------------------------------------------ 3. painting */

    private fun paintingSection() {
        section("Painting tools")
        val layer = RasterLayer(name = "paint")
        layer.pixels = Pixels(64, 64, IntArray(64 * 64))
        val color = Argb.pack(255, 20, 200, 40)

        val stamp = Render.paintStamp(layer, 32f, 32f, 10f, 1f, color, 1f)
        check("brush: stamp reports a dirty rectangle", stamp != null && stamp.w in 20..24 && stamp.h in 20..24,
            "got $stamp")
        val centre = layer.pixels!!.data[32 * 64 + 32]
        check("brush: centre pixel painted", centre == color, "got ${Integer.toHexString(centre)}")
        val outside = layer.pixels!!.data[5 * 64 + 5]
        check("brush: far pixel untouched", outside == 0)

        val before = LayerDigest.of(layer)
        Render.paintLine(layer, 5f, 5f, 55f, 55f, 4f, 1f, color, 1f)
        check("brush: dragging paints a line", LayerDigest.of(layer) != before)
        check("brush: line passes through its middle", Argb.a(layer.pixels!!.data[30 * 64 + 30]) > 0)

        val erased = Render.eraseStamp(layer, 32f, 32f, 10f, 1f, 1f)
        check("eraser: lowers alpha", erased != null && Argb.a(layer.pixels!!.data[32 * 64 + 32]) == 0,
            "got ${Argb.a(layer.pixels!!.data[32 * 64 + 32])}")

        val filled = Render.fillRect(layer, Rect(0, 0, 4, 4), color)
        check("fill: rectangle filled", filled != null && layer.pixels!!.data[0] == color)

        val bucket = RasterLayer(name = "bucket")
        bucket.pixels = Pixels(32, 32, IntArray(32 * 32))
        // Draw a vertical wall so the fill has to stop somewhere.
        for (y in 0 until 32) bucket.pixels!!.data[y * 32 + 16] = Argb.pack(255, 0, 0, 0)
        val region = Render.floodFill(bucket, 2, 2, Argb.pack(255, 255, 255, 0), 8)
        check("bucket: fills only the empty side", region != null && region.right <= 16, "got $region")
        check("bucket: wall kept its colour", bucket.pixels!!.data[10 * 32 + 16] == Argb.pack(255, 0, 0, 0))
    }

    /* ------------------------------------------------------------------ 4. round trip */


    /* ------------------------------------------------- 4. the editing session */

    /**
     * The document-level operations the UI calls: undo/redo, layer commands,
     * crop, merge, flatten and text editing. These run against the same
     * `doc.Session` the app uses — the class is deliberately `android`-free so it
     * can be exercised here.
     */
    private fun sessionSection() {
        section("Editing session")

        val doc = PsdDocument(64, 64)
        val session = com.photocc.app.doc.Session(doc)
        val layer = session.addRasterLayer(name = "Paint", width = 64, height = 64)

        // ---- painting and undo, pixel-exact
        val edit = com.photocc.app.doc.PixelEdit(layer)
        edit.before(Rect(0, 0, 64, 64))
        Render.fillRect(layer, Rect(10, 10, 20, 20), Argb.pack(255, 255, 0, 0))
        session.commitPixelEdit(edit, "Fill")
        check("session: fill painted", Argb.r(layer.pixels!!.data[15 * 64 + 15]) == 255)
        check("session: fill stayed inside its rectangle", layer.pixels!!.data[5 * 64 + 5] == 0)
        check("session: undo is available", session.canUndo)
        session.undo()
        check("session: undo removed the fill", layer.pixels!!.data[15 * 64 + 15] == 0)
        session.redo()
        check("session: redo restored the fill", Argb.r(layer.pixels!!.data[15 * 64 + 15]) == 255)

        // ---- a stroke is one undo step, however many stamps it contains
        val stroke = com.photocc.app.doc.PixelEdit(layer)
        for (x in 0 until 40 step 4) {
            stroke.before(Rect(x, 40, 8, 8))
            Render.paintStamp(layer, x + 1f, 44f, 3f, 1f, Argb.pack(255, 0, 255, 0), 1f)
        }
        session.commitPixelEdit(stroke, "Brush")
        check("session: stroke painted", Argb.g(layer.pixels!!.data[44 * 64 + 20]) > 100)
        session.undo()
        check("session: one undo removes the whole stroke", Argb.g(layer.pixels!!.data[44 * 64 + 20]) == 0)
        session.redo()

        // ---- layer properties are structural steps (the tree is cloned on undo)
        session.setOpacity(layer.id, 0.5f)
        val live = session.doc.findLayer(layer.id) as RasterLayer
        check("session: opacity applied", abs(live.opacity - 0.5f) < 0.01f, "${live.opacity}")
        session.undo()
        check("session: opacity undone", abs((session.doc.findLayer(layer.id) as RasterLayer).opacity - 1f) < 0.01f)
        session.redo()
        check("session: opacity redone", abs((session.doc.findLayer(layer.id) as RasterLayer).opacity - 0.5f) < 0.01f)
        session.setOpacity(layer.id, 1f)

        // ---- duplicate, reorder, delete
        session.select(layer.id)
        session.duplicateSelected()
        check("session: duplicate added a layer", session.doc.layerCount() == 2, "${session.doc.layerCount()}")
        val copy = session.selected!!
        check("session: duplicate is a separate buffer",
            (session.doc.findLayer(copy.id) as RasterLayer).pixels !== layer.pixels)
        session.moveSelected(-1)
        check("session: reorder moved the layer down", session.doc.children.first().id == copy.id)
        session.undo()
        check("session: reorder undone", session.doc.children.first().id == layer.id)
        session.deleteSelected()
        check("session: delete removed the layer", session.doc.layerCount() == 1, "${session.doc.layerCount()}")

        // ---- merge down composites exactly like the canvas
        val bottom = session.doc.children.first() as RasterLayer
        session.select(bottom.id)
        val overlay = session.addRasterLayer(name = "Overlay", width = 64, height = 64)
        Render.fillRect(overlay, Rect(20, 20, 20, 20), Argb.pack(128, 0, 0, 255))
        session.select(overlay.id)
        session.mergeDown()
        check("session: merge down left one layer", session.doc.layerCount() == 1, "${session.doc.layerCount()}")
        val merged = session.doc.children.first() as RasterLayer
        val expected = Argb.over(Argb.pack(255, 255, 0, 0), Argb.pack(128, 0, 0, 255), 255)
        check(
            "session: merged pixel matches the compositor",
            merged.pixels!!.data[25 * 64 + 25] == expected,
            "${Integer.toHexString(merged.pixels!!.data[25 * 64 + 25])} vs ${Integer.toHexString(expected)}",
        )

        // ---- crop resizes canvas and every layer
        session.crop(Rect(10, 10, 30, 30))
        check("session: crop resized the canvas", session.doc.width == 30 && session.doc.height == 30,
            "${session.doc.width}x${session.doc.height}")
        val cropped = session.doc.children.first() as RasterLayer
        check("session: crop resized the layer", cropped.pixels?.width == 30 && cropped.pixels?.height == 30,
            "${cropped.pixels?.width}x${cropped.pixels?.height}")
        check("session: crop moved the layer origin", cropped.x == 0 && cropped.y == 0)
        session.undo()
        check("session: crop undone", session.doc.width == 64 && session.doc.height == 64,
            "${session.doc.width}x${session.doc.height}")

        // ---- flatten
        session.addRasterLayer(name = "Top", width = 30, height = 30)
        session.flatten()
        check("session: flatten left a single layer", session.doc.layerCount() == 1, "${session.doc.layerCount()}")
        check("session: flatten result covers the canvas",
            (session.doc.children.first() as RasterLayer).pixels?.width == session.doc.width)
        session.undo()
        check("session: flatten undone", session.doc.layerCount() == 2, "${session.doc.layerCount()}")

        // ---- text layers through the session
        val text = session.addTextLayer("Hello", 5f, 5f)
        session.editText(text.id) { it.text.text = "Hello world" }
        val liveText = session.doc.findLayer(text.id) as TextLayer
        check("session: text edit applied", liveText.text.text == "Hello world", liveText.text.text)
        check("session: text edit marked the layer dirty", liveText.textDirty)
        session.undo()
        check(
            "session: text edit undone",
            (session.doc.findLayer(text.id) as TextLayer).text.text == "Hello",
            (session.doc.findLayer(text.id) as TextLayer).text.text,
        )

        // ---- visibility and blend mode
        session.setVisible(text.id, false)
        check("session: visibility off", (session.doc.findLayer(text.id) as TextLayer).visible.not())
        session.setBlend(text.id, BlendMode.SCREEN)
        check("session: blend mode set", (session.doc.findLayer(text.id) as TextLayer).blend == BlendMode.SCREEN)

        // ---- the history budget is respected without losing the recent steps
        val small = com.photocc.app.doc.Session(PsdDocument(8, 8))
        small.addRasterLayer(width = 8, height = 8)
        for (i in 1..300) small.setOpacity(small.selectedId!!, (i % 100) / 100f)
        check("history: many steps stay usable", small.canUndo && small.undo(), "undo unavailable after 300 steps")

        // ---- a full session write is still readable and renders the same
        val bytes = session.saveToBytes()
        val reread = PsdRead.read(bytes, "session.psd")
        check("session: written file re-reads", reread.layerCount() == session.doc.layerCount())
        val before = IntArray(session.doc.width * session.doc.height)
        val after = IntArray(session.doc.width * session.doc.height)
        Render.flatten(session.doc, Rect(0, 0, session.doc.width, session.doc.height), before)
        Render.flatten(reread, Rect(0, 0, reread.width, reread.height), after)
        check(
            "session: written file renders identically",
            Digest.of(before, session.doc.width, session.doc.height) == Digest.of(after, reread.width, reread.height),
            "${Digest.of(before, session.doc.width, session.doc.height)} vs ${Digest.of(after, reread.width, reread.height)}",
        )
    }

    private fun roundTripSection() {
        section("Write → read round trip")
        val doc = buildDocument()
        val rendered = IntArray(doc.width * doc.height)
        Render.flatten(doc, Rect(0, 0, doc.width, doc.height), rendered)
        val withLayers = Digest.of(rendered, doc.width, doc.height)
        check("render: composited document is not empty", rendered.any { Argb.a(it) != 0 }, "digest $withLayers")

        val bytes = PsdWrite.write(doc)
        check("write: produced a PSD", bytes.size > 200, "${bytes.size} bytes")
        check("write: signature", String(bytes, 0, 4, Charsets.ISO_8859_1) == "8BPS")

        val file = File(outDir, "roundtrip.psd")
        file.writeBytes(bytes)

        val reread = try {
            PsdRead.read(bytes, "roundtrip.psd")
        } catch (e: Exception) {
            check("read back: parses", false, e.message ?: "exception")
            return
        }
        check("read back: document size", reread.width == doc.width && reread.height == doc.height)
        check("read back: layer count", reread.layerCount() == doc.layerCount(),
            "${reread.layerCount()} vs ${doc.layerCount()}")

        val original = structure(doc)
        val copy = structure(reread)
        check("read back: names, opacity, blend modes, visibility", original == copy,
            "differing: ${original.zip(copy).filter { it.first != it.second }.take(3)}")

        val rereadRender = IntArray(doc.width * doc.height)
        Render.flatten(reread, Rect(0, 0, doc.width, doc.height), rereadRender)
        val rereadDigest = Digest.of(rereadRender, doc.width, doc.height)
        check(
            "read back: identical rendering",
            rereadDigest == withLayers,
            "${rereadDigest} vs $withLayers — ${describeDifference(rendered, rereadRender, doc.width)}",
        )

        // A second save must be stable: writing what we read gives the same pixels.
        val again = PsdWrite.write(reread)
        val third = PsdRead.read(again, "roundtrip.psd")
        val thirdRender = IntArray(doc.width * doc.height)
        Render.flatten(third, Rect(0, 0, doc.width, doc.height), thirdRender)
        check("write twice: stable rendering", Digest.of(thirdRender, doc.width, doc.height) == withLayers)

        writeExpectations(file, doc, reread)
    }

    /**
     * True when the engine regenerated this layer's type blocks (rather than
     * copying the ones the file arrived with). Drives the writer verification:
     * regenerated blocks must carry the font the engine chose.
     */
    private fun textWasWritten(doc: PsdDocument, name: String): Boolean {
        var result = true
        doc.walkLayers { layer, _ ->
            if (layer is TextLayer && layer.name == name) {
                result = layer.textDirty || layer.originalTySh == null
            }
        }
        return result
    }

    /** Where two renderings differ, for a readable failure message. */
    private fun describeDifference(a: IntArray, b: IntArray, width: Int): String {
        var count = 0
        var firstX = -1
        var firstY = -1
        var firstA = 0
        var firstB = 0
        var minX = Int.MAX_VALUE
        var minY = Int.MAX_VALUE
        var maxX = -1
        var maxY = -1
        for (i in a.indices) {
            if (a[i] == b[i]) continue
            val x = i % width
            val y = i / width
            if (count == 0) {
                firstX = x; firstY = y; firstA = a[i]; firstB = b[i]
            }
            count++
            if (x < minX) minX = x
            if (y < minY) minY = y
            if (x > maxX) maxX = x
            if (y > maxY) maxY = y
        }
        if (count == 0) return "no differing pixels"
        return "$count differing pixels, box ${minX},${minY}-${maxX},$maxY, first at ${firstX},${firstY}: " +
            "${Integer.toHexString(firstA)} vs ${Integer.toHexString(firstB)}"
    }

    private fun structure(doc: PsdDocument): List<String> {
        val out = ArrayList<String>()
        fun walk(list: List<Layer>, depth: Int) {
            for (layer in list) {
                out.add(
                    "  ".repeat(depth) + "${layer.name}|${layer.blend.psd}|${(layer.opacity * 255).toInt()}|${layer.visible}|" +
                        "${layer.locked}|${if (layer is GroupLayer) "group" else if (layer is TextLayer) "text" else "raster"}"
                )
                if (layer is GroupLayer) walk(layer.children, depth + 1)
            }
        }
        walk(doc.children, 0)
        return out
    }

    /** Documents recorded for the independent (Node) verifier. */
    private val recordedDocuments = LinkedHashMap<String, String>()

    /** Files the independent (Node) verifier reads afterwards. */
    private fun writeExpectations(file: File, doc: PsdDocument, reread: PsdDocument) {
        val entries = mutableListOf<String>()
        reread.walkLayers { layer, _ ->
            when (layer) {
                is RasterLayer -> {
                    val pixels = layer.pixels
                    val digest = pixels?.let { Digest.of(it.data, it.width, it.height) } ?: "null"
                    entries.add(
                        """{"name":${json(layer.name)},"kind":"raster","width":${pixels?.width ?: 0},""" +
                            """"height":${pixels?.height ?: 0},"digest":${json(digest)},"opacity":${(layer.opacity * 255).toInt()},""" +
                            """"blend":${json(layer.blend.psd)},"visible":${layer.visible},"left":${layer.x},"top":${layer.y}}"""
                    )
                }
                is TextLayer -> entries.add(
                    """{"name":${json(layer.name)},"kind":"text","text":${json(layer.text.text)},""" +
                        """"font":${json(layer.text.psdFontName)},"size":${layer.text.size},""" +
                        """"edited":${textWasWritten(doc, layer.name)},"x":${layer.x},"y":${layer.y}}"""
                )
                is GroupLayer -> entries.add("""{"name":${json(layer.name)},"kind":"group"}""")
            }
        }
        val merged = IntArray(doc.width * doc.height)
        Render.flatten(doc, Rect(0, 0, doc.width, doc.height), merged)
        val body = buildString {
            append("{\n")
            append("      \"width\": ${doc.width},\n      \"height\": ${doc.height},\n")
            append("      \"merged\": ${json(Digest.of(merged, doc.width, doc.height))},\n")
            append("      \"layers\": [\n        ")
            append(entries.joinToString(",\n        "))
            append("\n      ],\n")
            val texts = mutableListOf<String>()
            reread.walkLayers { l, _ -> if (l is TextLayer) texts.add(json(l.text.text)) }
            append("      \"texts\": [")
            append(texts.joinToString(", "))
            append("]\n    }\n")
        }
        recordedDocuments[file.name] = body
        flushExpectations()
    }

    /** Every document the engine wrote, in the shape tools/engine/verify-write.mjs reads. */
    private fun flushExpectations() {
        val output = buildString {
            append("{\n  \"documents\": {\n")
            append(recordedDocuments.entries.joinToString(",\n") { (name, body) -> "    \"$name\": $body" })
            append("\n  }\n}\n")
        }
        File(outDir, "expected.json").writeText(output)
    }

    private fun json(value: String): String {
        val sb = StringBuilder("\"")
        for (c in value) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c.code < 32) sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        return sb.append('"').toString()
    }

    /** A document that exercises every feature the editor supports. */
    private fun buildDocument(): PsdDocument {
        val doc = PsdDocument(240, 180)
        doc.outlineName = "self-test.psd"

        // background: opaque gradient
        val background = RasterLayer(name = "Background")
        background.pixels = Pixels(240, 180, IntArray(240 * 180) { index ->
            val x = index % 240
            val y = index / 240
            Argb.pack(255, 40 + x / 2, 60 + y / 2, 120)
        })
        doc.children.add(background)

        // a soft circle, half transparent, multiplied
        val circle = RasterLayer(name = "Circle", opacity = 0.75f, blend = BlendMode.MULTIPLY)
        circle.x = 20
        circle.y = 20
        circle.pixels = Pixels(120, 120, IntArray(120 * 120))
        Render.paintStamp(circle, 60f, 60f, 45f, 0.4f, Argb.pack(255, 240, 200, 40), 1f)
        doc.children.add(circle)

        // a group with two layers and a pass-through blend
        val group = GroupLayer(name = "Shapes", blend = BlendMode.PASS_THROUGH)
        val square = RasterLayer(name = "Square", blend = BlendMode.SCREEN)
        square.x = 150
        square.y = 100
        square.pixels = Pixels(60, 60, IntArray(60 * 60) { Argb.pack(200, 30, 30, 200) })
        val masked = RasterLayer(name = "Faded")
        masked.pixels = Pixels(40, 40, IntArray(40 * 40) { Argb.pack(255, 250, 250, 250) })
        masked.mask = MaskInfo(Rect(0, 0, 40, 40), ByteArray(40 * 40) { (it % 40 * 6).toByte() }, 255, false)
        group.children.add(square)
        group.children.add(masked)
        doc.children.add(group)

        // text
        val text = TextLayer(name = "Greeting", x = 24f, y = 130f)
        text.text.text = "photo_cc"
        text.text.size = 28f
        text.text.color = Argb.pack(255, 255, 255, 30)
        text.text.psdFontName = "Arial-BoldMT"
        text.text.fontFamily = FontMap.psdNameToDevice("Arial-BoldMT").family
        text.textDirty = true
        doc.children.add(text)

        doc.dirty = true
        return doc
    }

    /* ------------------------------------------------------------------ 5. text editing */

    private fun textSection() {
        section("Text layers")
        val source = File(fixtureDir, "text-src.psd")
        if (!source.exists()) {
            println("  (no text-src.psd fixture — skipped)")
            return
        }
        val doc = PsdRead.read(source.readBytes(), source.name)
        var textLayer: TextLayer? = null
        doc.walkLayers { layer, _ -> if (layer is TextLayer && textLayer == null) textLayer = layer }
        val layer = textLayer
        check("text fixture: a text layer was found", layer != null)
        if (layer == null) return
        check("text fixture: text read from engine data", layer.text.text.isNotBlank(), "\"${layer.text.text}\"")
        check("text fixture: font resolved", layer.text.psdFontName.isNotBlank(), layer.text.psdFontName)
        check("text fixture: size read", layer.text.size > 0f, "${layer.text.size}pt")

        // Unchanged text must be written back byte-for-byte.
        if (layer.originalTySh != null) {
            val bytes = PsdWrite.write(doc)
            val reread = PsdRead.read(bytes, "text-passthrough.psd")
            var copy: TextLayer? = null
            reread.walkLayers { l, _ -> if (l is TextLayer && copy == null) copy = l }
            check("text passthrough: unchanged text survives a save", copy?.text?.text == layer.text.text,
                "\"${copy?.text?.text}\" vs \"${layer.text.text}\"")
            val passthroughFile = File(outDir, "text-passthrough.psd")
            passthroughFile.writeBytes(bytes)
            writeExpectations(passthroughFile, doc, reread)
        }

        // Edited text must produce a valid type block.
        layer.text.text = "EDITED"
        layer.text.size = 36f
        layer.text.color = Argb.pack(255, 0, 160, 255)
        layer.textDirty = true
        val edited = PsdWrite.write(doc)
        val file = File(outDir, "text-edited.psd")
        file.writeBytes(edited)
        val rereadEdited = PsdRead.read(edited, "text-edited.psd")
        writeExpectations(file, doc, rereadEdited)
        var editedCopy: TextLayer? = null
        rereadEdited.walkLayers { l, _ -> if (l is TextLayer && editedCopy == null) editedCopy = l }
        check("text edit: text survived", editedCopy?.text?.text == "EDITED", "\"${editedCopy?.text?.text}\"")
        check("text edit: size survived", editedCopy != null && abs(editedCopy.text.size - 36f) < 0.01f,
            "${editedCopy?.text?.size}")
        check(
            "text edit: colour survived",
            editedCopy != null && Argb.r(editedCopy.text.color) == 0 && Argb.b(editedCopy.text.color) == 255,
            Integer.toHexString(editedCopy?.text?.color ?: 0),
        )

        // The generated engine data must be readable again, and the descriptor must parse.
        val shy = Desc.writeTextLayerShy(layer)
        val parsedShy = Desc.Reader(shy, 2 + 6 * 8 + 2)
        val descriptor = parsedShy.readVersionAndDescriptor()
        check("text edit: TySh descriptor class id", descriptor.classId == "TxLr", descriptor.classId)
        val engineBytes = descriptor.dict.raw("EngineData")?.data
        check("text edit: EngineData present", engineBytes != null && engineBytes.size > 100)
        val engineParsed = EngineData.readText(engineBytes ?: ByteArray(0))
        check("text edit: EngineData text", engineParsed?.text == "EDITED", "\"${engineParsed?.text}\"")
        check("text edit: EngineData font", engineParsed?.fontName?.contains("Arial") == true, engineParsed?.fontName ?: "")
        check("text edit: descriptor text", descriptor.dict.string("Txt ") == "EDITED")

        // A fresh text layer with no source bytes must still write a complete block.
        val fresh = TextLayer(name = "Fresh", x = 10f, y = 10f)
        fresh.text.text = "Héllo ✓"
        fresh.textDirty = true
        check("text: non-ASCII text is UTF-16 in the engine data", fresh.text.text == EngineData.readText(EngineData.writeText(fresh))?.text)
    }

    /* ------------------------------------------------------------------ plumbing */

    /* ------------------------------------------------- 7. the memory-budget paths */

    /**
     * The paths that used to be `OutOfMemoryError`s on a phone: opening a document
     * bigger than the import budget, compositing it into a screen-sized buffer and
     * writing the merged image back out. Each one is checked here at a size the
     * test can afford, so a regression shows up as a failing check instead of a
     * crash on someone's device.
     */
    private fun memorySection() {
        section("Large documents and the memory budget")

        val width = 2400
        val height = 1800
        val doc = PsdDocument(width, height)
        val layer = RasterLayer(name = "Flat", x = 0, y = 0, pixels = Pixels(width, height, IntArray(width * height)))
        for (y in 0 until height) {
            for (x in 0 until width) {
                // A smooth gradient with a hard band: downscaling must not turn the
                // band into mush, and the gradient proves we sampled real pixels.
                val band = if (y in height / 3 until height / 3 + 40) 0x40 else 0
                layer.pixels!!.data[y * width + x] = Argb.pack(255, x * 255 / width, y * 255 / height, band)
            }
        }
        doc.children.add(layer)
        val bytes = PsdWrite.write(doc, PsdWrite.Options(compression = 1))
        check("memory: wrote a ${width}×${height} fixture", bytes.size > 1000, "got ${bytes.size} bytes")

        // Opening it with a deliberately tiny budget must sample it down instead of
        // allocating the full-size planes.
        val budget = 1_000_000
        val read = PsdRead.read(bytes, "big.psd", maxPixels = budget)
        val stride = read.importStride
        check("memory: import sampled the file down", stride > 1, "stride $stride")
        check("memory: source size remembered", read.sourceWidth == width && read.sourceHeight == height)
        check(
            "memory: sampled size is inside the budget",
            read.width.toLong() * read.height.toLong() <= budget,
            "${read.width}×${read.height} for a $budget pixel budget",
        )
        val sampled = read.children.firstOrNull() as? RasterLayer
        check("memory: the layer came along", sampled?.pixels != null)
        check(
            "memory: layer geometry was scaled too",
            sampled != null && sampled.pixels!!.width == read.width && sampled.pixels!!.height == read.height,
            "layer ${sampled?.pixels?.width}×${sampled?.pixels?.height} vs document ${read.width}×${read.height}",
        )

        // The composite a phone actually draws: the whole document, screen-sized.
        val outW = 540
        val outH = 405
        val screen = IntArray(outW * outH)
        val started = System.currentTimeMillis()
        Render.flattenScaled(read, Rect(0, 0, read.width, read.height), screen, outW, outH, Argb.OPAQUE_WHITE)
        val elapsed = System.currentTimeMillis() - started
        check("memory: screen-sized composite finished", elapsed < 4000, "${elapsed} ms")
        check("memory: composite is opaque everywhere", screen.all { Argb.a(it) == 255 })
        check("memory: composite kept the gradient", screen[outW * 20 + outW / 2] != screen[outW * 380 + outW / 2])

        // Scale-1 flattenScaled must agree with the plain flatten pixel for pixel.
        val reference = IntArray(600 * 450)
        Render.flatten(read, Rect(0, 0, 600, 450), reference)
        val same = IntArray(600 * 450)
        Render.flattenScaled(read, Rect(0, 0, 600, 450), same, 600, 450)
        var differences = 0
        for (i in reference.indices) if (reference[i] != same[i]) differences++
        check("memory: scale-1 composite is identical to flatten", differences == 0, "$differences differing pixels")

        // Writing the merged image back out must not allocate document-sized planes.
        val rewritten = PsdWrite.write(read, PsdWrite.Options(compression = 1))
        check("memory: re-wrote the sampled document", rewritten.size > 1000, "got ${rewritten.size} bytes")
        val reread = PsdRead.read(rewritten, "big-again.psd", maxPixels = budget)
        check("memory: round trip kept the layer count", reread.children.size == read.children.size)
    }

    private fun section(title: String) = println("\n== $title ==")

    private fun check(name: String, condition: Boolean, detail: String = "") {
        if (condition) {
            passed++
            println("  ok   $name")
        } else {
            failed++
            failures.add("$name${if (detail.isEmpty()) "" else " — $detail"}")
            println("  FAIL $name${if (detail.isEmpty()) "" else " — $detail"}")
        }
    }

    private fun report() {
        println()
        if (failed == 0) {
            println("ALL ENGINE CHECKS PASSED ($passed checks)")
        } else {
            println("ENGINE CHECKS FAILED: $passed passed, $failed failed")
            failures.forEach { println("  - $it") }
            exitProcess(1)
        }
    }
}

/** FNV-1a over the pixels, cheap enough for large layers. */
object Digest {
    fun of(pixels: IntArray, width: Int, height: Int): String {
        var hash = -0x340d631b7bdddcdbL
        val count = minOf(pixels.size, width * height)
        for (i in 0 until count) {
            val value = pixels[i]
            hash = step(hash, (value ushr 16) and 0xFF)
            hash = step(hash, (value ushr 8) and 0xFF)
            hash = step(hash, value and 0xFF)
            hash = step(hash, (value ushr 24) and 0xFF)
        }
        return java.lang.Long.toHexString(hash)
    }

    private fun step(hash: Long, value: Int): Long = (hash xor value.toLong()) * 1099511628211L
}

/** Debug helper used by the self-test and by the developer tools. */
object LayerDigest {
    fun of(layer: RasterLayer): String {
        val pixels = layer.pixels ?: return "empty"
        return Digest.of(pixels.data, pixels.width, pixels.height)
    }
}
