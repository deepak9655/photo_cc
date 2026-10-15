package com.strata.editor.core

import java.io.ByteArrayOutputStream

/**
 * Text layers: the `TySh` block.
 *
 * A text layer in a PSD is three things at once — the rendered pixels (so other
 * programs can show a preview), the descriptor that describes the text (so it can
 * be re-rendered), and the engine data (what the layout engine actually reads).
 * All three are written here, and a text layer that is opened and not edited is
 * re-emitted byte for byte, so nothing is lost just by opening and saving a file.
 */
object TextLayers {

    const val BLOCK_KEY = "TySh"

    /** The parsed text of a layer, with the original bytes kept for a faithful save. */
    class ParsedText(
        val content: TextContent,
        val rawBlock: ByteArray,
        val transform: Mat3,
        val layout: Rect,
    )

    /* ---------------------------------------------------------------- reading */

    fun parse(block: ByteArray): ParsedText? {
        try {
            val reader = DescriptorReader(block)
            val version = reader.i16()
            if (version != 1) return null
            // The file stores xx, xy, yx, yy, tx, ty — not the constructor's order.
            val xx = reader.f64().toFloat()
            val xy = reader.f64().toFloat()
            val yx = reader.f64().toFloat()
            val yy = reader.f64().toFloat()
            val tx = reader.f64().toFloat()
            val ty = reader.f64().toFloat()
            val transform = Mat3(xx, xy, tx, yx, yy, ty)
            reader.i16() // text version, 50 in every file that exists

            val classId = reader.key()
            if (classId != "TxLr") return null
            val count = reader.i32()
            if (count !in 0..512) return null
            var text: String? = null
            var engineData: ByteArray? = null
            var bounds = Rect.EMPTY
            var orientation = "Hrzn"
            for (i in 0 until count) {
                val key = reader.key()
                when (key) {
                    "Txt " -> {
                        val type = reader.fourCc()
                        if (type != "TEXT") return null
                        text = reader.unicodeString()
                    }
                    "EngineData" -> {
                        val type = reader.fourCc()
                        if (type != "tdta") return null
                        val length = reader.i32().coerceIn(0, reader.remaining())
                        engineData = ByteArray(length)
                        System.arraycopy(block, reader.position, engineData, 0, length)
                        reader.skip(length)
                    }
                    "Ornt " -> {
                        reader.fourCc()
                        reader.key() // the enum's class, always "Ornt"
                        orientation = reader.key()
                    }
                    "bounds" -> {
                        val type = reader.fourCc()
                        if (type == "Objc") {
                            val boundsClass = reader.key()
                            val boundsCount = reader.i32()
                            var left = 0.0
                            var top = 0.0
                            var right = 0.0
                            var bottom = 0.0
                            for (j in 0 until boundsCount.coerceIn(0, 64)) {
                                val name = reader.key()
                                val value = reader.value() ?: break
                                val numeric = when (value) {
                                    is Map<*, *> -> (value["value"] as? Double) ?: 0.0
                                    is Double -> value
                                    is Int -> value.toDouble()
                                    else -> 0.0
                                }
                                when (name) {
                                    "Left " -> left = numeric
                                    "Top " -> top = numeric
                                    "Rght" -> right = numeric
                                    "Botm" -> bottom = numeric
                                }
                            }
                            if (boundsClass.isNotEmpty()) {
                                bounds = Rect(
                                    left.toInt(),
                                    top.toInt(),
                                    (right - left).toInt().coerceAtLeast(1),
                                    (bottom - top).toInt().coerceAtLeast(1),
                                )
                            }
                        }
                    }
                    else -> {
                        if (reader.value() == null) break
                    }
                }
            }
            val textValue = text ?: return null
            val document = engineData?.let { EngineData.read(it) }
            val content = TextContent(text = textValue)
            document?.let {
                it.engineFontName()?.let { name -> content.fontFamily = name }
                it.engineFontSize()?.let { size -> content.fontSize = size.toFloat() }
                readStyle(it, content)
            }
            if (orientation == "Vrtc") content.alignment = TextAlign.LEFT
            return ParsedText(content, block, transform, bounds)
        } catch (error: Exception) {
            return null
        }
    }

    private fun readStyle(document: Map<String, Any>, content: TextContent) {
        val engine = document.dict("EngineDict") ?: return
        val styleSheet = engine.dict("StyleRun")?.dict("DefaultRunData")?.dict("StyleSheet") ?: return
        val data = styleSheet.dict("StyleSheetData") ?: return
        data.bool("FauxBold")?.let { content.bold = it }
        data.bool("FauxItalic")?.let { content.italic = it }
        data.number("Tracking")?.let { content.tracking = it.toFloat() }
        data.number("Leading")?.let { leading ->
            if (content.fontSize > 0f) content.leading = (leading / content.fontSize).toFloat().coerceIn(0.5f, 4f)
        }
        val fill = data.dict("FillColor")?.list("Values")
        if (fill != null && fill.size >= 4) {
            val values = fill.mapNotNull { it as? Double }
            if (values.size == 4) {
                content.color = Colors.pack(
                    (values[0] * 255).toInt().coerceIn(0, 255),
                    (values[1] * 255).toInt().coerceIn(0, 255),
                    (values[2] * 255).toInt().coerceIn(0, 255),
                    (values[3] * 255).toInt().coerceIn(0, 255),
                )
            }
        }
        val justification = engine.dict("ParagraphRun")?.dict("DefaultRunData")?.dict("ParagraphSheet")?.dict("Properties")?.int("Justification")
        if (justification != null) {
            content.alignment = when (justification) {
                1 -> TextAlign.RIGHT
                2 -> TextAlign.CENTER
                3 -> TextAlign.CENTER
                else -> TextAlign.LEFT
            }
        }
        content.tracking = data.number("Tracking")?.toFloat() ?: content.tracking
    }

    /* ---------------------------------------------------------------- writing */

    /**
     * Builds a complete `TySh` block.
     *
     * The descriptor is the documented one: version, transform, text version 50,
     * the `TxLr` text-layer descriptor (text, orientation, warp, engine data,
     * bounds, anti-aliasing) and a warp descriptor.
     */
    fun build(content: TextContent, transform: Mat3, layoutBounds: Rect, documentBounds: Rect): ByteArray {
        val out = ByteArrayOutputStream(4096)
        writeI16(out, 1)
        // Transform: xx, xy, yx, yy, tx, ty.
        writeF64(out, transform.a.toDouble())
        writeF64(out, transform.b.toDouble())
        writeF64(out, transform.c.toDouble())
        writeF64(out, transform.d.toDouble())
        writeF64(out, transform.tx.toDouble())
        writeF64(out, transform.ty.toDouble())
        writeI16(out, 50)

        val descriptor = DescriptorWriter()
        descriptor.descriptor("TxLr", TXLR_ENTRIES) {
            text("Txt ", content.text)
            // Where this text sits on the canvas, in points.
            objectValue("bounds", "bounds", 4) {
                unitDouble("Left ", "#Pnt", layoutBounds.x.toDouble())
                unitDouble("Top ", "#Pnt", layoutBounds.y.toDouble())
                unitDouble("Rght", "#Pnt", layoutBounds.right.toDouble())
                unitDouble("Botm", "#Pnt", layoutBounds.bottom.toDouble())
            }
            enumeration("Ornt ", "Ornt", "Hrzn")
            text("textGridding", "None")
            enumeration("AntA", "Annt", "AnAA")
            // Five entries, and exactly five written: an entry count that disagrees
            // with the body desynchronises the whole descriptor.
            objectValue("warp", "warp", 5) {
                enumeration("warpStyle", "warpStyle", "warpNone")
                double("warpValue", 0.0)
                double("warpPerspective", 0.0)
                double("warpPerspectiveOther", 0.0)
                // The warp rotation is an orientation enum, never an angle.
                enumeration("warpRotate", "Ornt", "Hrzn")
            }
            long("TextIndex", 0)
            key("EngineData")
            fourCc("tdta")
            val engine = engineData(content, documentBounds)
            i32(engine.size)
            raw(engine)
            long("TextType", 0)
            long("TextVersion", 50)
            long("TextOrientation", 0)
        }
        out.write(descriptor.bytes())

        // Trailing warp block; a flat warp with a horizontal orientation.
        val warp = DescriptorWriter()
        warp.descriptor("warp", WARP_ENTRIES) {
            enumeration("warpStyle", "warpStyle", "warpNone")
            double("warpValue", 0.0)
            double("warpPerspective", 0.0)
            double("warpPerspectiveOther", 0.0)
            enumeration("warpRotate", "Ornt", "Hrzn")
        }

        writeI16(out, 1)
        out.write(warp.bytes())
        return out.toByteArray()
    }

    private const val TXLR_ENTRIES = 11
    private const val WARP_ENTRIES = 5

    /** The engine data document: editor text, runs, styles, font set and grid info. */
    fun engineData(content: TextContent, documentBounds: Rect): ByteArray {
        val length = content.text.length
        val fill = floatArrayOf(
            Colors.alpha(content.color) / 255f,
            Colors.red(content.color) / 255f,
            Colors.green(content.color) / 255f,
            Colors.blue(content.color) / 255f,
        )
        val leading = content.fontSize * content.leading.coerceIn(0.5f, 4f)
        val styleSheet = linkedMapOf<String, Any>(
            "StyleSheetData" to linkedMapOf<String, Any>(
                "Font" to 0,
                "FontSize" to content.fontSize.toDouble(),
                "FauxBold" to content.bold,
                "FauxItalic" to content.italic,
                "FauxUnderline" to "None",
                "FillColor" to linkedMapOf<String, Any>("Type" to 1, "Values" to fill),
                "Tracking" to content.tracking.toDouble(),
                "Leading" to leading.toDouble(),
                "AutoLeading" to (content.leading <= 0f),
                "Underline" to false,
                "Strikethrough" to false,
                "HorizontalScale" to 1.0,
                "VerticalScale" to 1.0,
                "BaselineShift" to 0.0,
                "CharacterDirection" to 0,
                "Language" to 0,
                "Kerning" to 0.0,
                "FontBaselineOption" to 0,
                "TextLanguage" to 0,
            ),
        )
        val justification = when (content.alignment) {
            TextAlign.LEFT -> 0
            TextAlign.RIGHT -> 1
            TextAlign.CENTER -> 2
        }
        val document = linkedMapOf<String, Any>(
            "EngineDict" to linkedMapOf<String, Any>(
                "Editor" to linkedMapOf<String, Any>(
                    "Text" to content.text,
                ),
                "ParagraphRun" to linkedMapOf<String, Any>(
                    "DefaultRunData" to linkedMapOf<String, Any>(
                        "ParagraphSheet" to linkedMapOf<String, Any>(
                            "DefaultStyleSheet" to 0,
                            "Properties" to linkedMapOf<String, Any>(
                                "Justification" to justification,
                                "FirstLineIndent" to 0.0,
                                "StartIndent" to 0.0,
                                "EndIndent" to 0.0,
                                "SpaceBefore" to 0.0,
                                "SpaceAfter" to 0.0,
                                "AutoHyphenate" to false,
                                "HyphenatedWordSize" to 6,
                                "PreHyphen" to 2,
                                "PostHyphen" to 2,
                                "ConsecutiveHyphens" to 8,
                                "Zone" to 36.0,
                                "WordSpacing" to floatArrayOf(1f, 1f, 1f, 1f),
                                "LetterSpacing" to floatArrayOf(0f, 0f, 0f, 0f),
                                "GlyphSpacing" to floatArrayOf(1f, 1f, 1f, 1f),
                                "Roman" to false,
                                "EveryLineComposer" to false,
                                "Hanging" to false,
                                "Burasagari" to false,
                                "KinsokuOrder" to 0,
                                "TextGridding" to "None",
                            ),
                        ),
                    ),
                    "RunArray" to listOf(
                        linkedMapOf<String, Any>(
                            "ParagraphSheet" to linkedMapOf<String, Any>("DefaultStyleSheet" to 0),
                            "Adjustment" to 0.0,
                            "RunLength" to length.coerceAtLeast(1),
                        ),
                    ),
                ),
                "StyleRun" to linkedMapOf<String, Any>(
                    "DefaultRunData" to linkedMapOf<String, Any>(
                        "StyleSheet" to styleSheet,
                    ),
                    "RunArray" to listOf(
                        linkedMapOf<String, Any>(
                            "StyleSheet" to styleSheet,
                            "RunLength" to length.coerceAtLeast(1),
                        ),
                    ),
                ),
                "MissingGlyphProtection" to false,
                "GridInfo" to linkedMapOf<String, Any>(
                    "GridIsOn" to false,
                    "ShowGrid" to false,
                    "GridSize" to 18.0,
                    "GridColor" to linkedMapOf<String, Any>(
                        "Type" to 1,
                        "Values" to floatArrayOf(0f, 0f, 1f, 1f),
                    ),
                    "GridLeading" to 22.0,
                ),
                "TextGridding" to "None",
                "TextGridInfo" to linkedMapOf<String, Any>(
                    "TextGridIsOn" to false,
                    "ShowTextGrid" to false,
                    "TextGridSize" to 18.0,
                    "TextGridLeading" to floatArrayOf(22f, 22f),
                ),
                "Antialias" to 4,
                "UseFractionalMetrics" to true,
                "FontSet" to listOf(
                    linkedMapOf<String, Any>(
                        "Name" to content.fontFamily,
                        "Script" to 0,
                        "FontType" to 1,
                        "Synthetic" to 0,
                    ),
                ),
                "SuperscriptSize" to 0.583,
                "SuperscriptPosition" to 0.333,
                "SubscriptSize" to 0.583,
                "SubscriptPosition" to 0.333,
                "SmallCapSize" to 0.7,
            ),
            "ResourceDict" to linkedMapOf<String, Any>(
                "Kern" to 0,
                "KernDigits" to 1,
                "KernD" to true,
                "KernS" to false,
                "Kerning" to 0.0,
            ),
            "DocumentResources" to linkedMapOf<String, Any>(
                "KinsokuSet" to listOf(
                    linkedMapOf<String, Any>(
                        "Name" to "Kinsoku",
                        "Characters" to "",
                    ),
                ),
                "MaxFontSize" to content.fontSize * 1.5,
                "MaxGlyphAngle" to 0.0,
            ),
            "Version" to 1,
        )
        documentBounds.hashCode()
        return EngineData.write(document)
    }

    private fun writeI16(out: ByteArrayOutputStream, value: Int) {
        out.write((value ushr 8) and 0xFF)
        out.write(value and 0xFF)
    }

    private fun writeF64(out: ByteArrayOutputStream, value: Double) {
        val bits = value.toRawBits()
        for (shift in intArrayOf(56, 48, 40, 32, 24, 16, 8, 0)) {
            out.write(((bits ushr shift) and 0xFF).toInt())
        }
    }
}
