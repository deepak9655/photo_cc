package com.photocc.app.core

import java.io.ByteArrayOutputStream
import java.util.Locale

/**
 * Adobe "engine data" support — the `Txt2` blob inside a text layer.
 *
 * A PSD keeps text twice: a descriptor (what the UI dialogs show) and this
 * s-expression document, which is what Photoshop actually re-renders from
 * (font sets, style runs, paragraph runs, warp). To keep text layers editable
 * after saving, the engine reads this format, exposes the fields the phone UI
 * edits, and writes a complete, Photoshop-shaped document back.
 *
 * Format notes that matter for correctness:
 *  * strings are written as UTF-16BE bytes preceded by a `FE FF` marker, so
 *    non-ASCII text survives;
 *  * numbers carry no type — being an integer or a float depends on the key
 *    name (see [floatKeys]) — so the writer reproduces that rule;
 *  * dictionary keys are stored without their `/` prefix and are ordered.
 */
object EngineData {

    /* ------------------------------------------------------------- tree */

    sealed class Ed {
        class Dict(val entries: LinkedHashMap<String, Ed> = LinkedHashMap()) : Ed() {
            operator fun get(key: String): Ed? = entries[key]
            operator fun set(key: String, value: Ed) { entries[key] = value }
        }

        class ListValue(val items: MutableList<Ed> = mutableListOf()) : Ed()

        /** `asInt` records whether the token was written without a fraction. */
        class Num(val value: Double, val asInt: Boolean = false) : Ed()
        class Str(val value: String) : Ed()
        class Bool(val value: Boolean) : Ed()
        object Null : Ed() {
            override fun toString(): String = "null"
        }
    }

    fun int(value: Int): Ed.Num = Ed.Num(value.toDouble(), asInt = true)
    fun num(value: Double): Ed.Num = Ed.Num(value, asInt = value == Math.floor(value) && !value.isInfinite())
    fun float(value: Double): Ed.Num = Ed.Num(value, asInt = false)
    fun str(value: String): Ed.Str = Ed.Str(value)
    fun bool(value: Boolean): Ed.Bool = Ed.Bool(value)

    fun dict(vararg pairs: Pair<String, Ed>): Ed.Dict {
        val d = Ed.Dict()
        for ((k, v) in pairs) d[k] = v
        return d
    }

    fun listOf(vararg values: Ed): Ed.ListValue = Ed.ListValue(values.toMutableList())

    /* ------------------------------------------------------------- parsing */

    /** Parses a `Txt2` / EngineData blob. Returns null for anything unparseable. */
    fun parse(bytes: ByteArray): Ed.Dict? = try {
        val parser = Parser(bytes)
        val tree = parser.parseTop() as? Ed.Dict
        if (parser.problems > 0 && System.getenv("PHOTO_CC_DEBUG") != null) {
            println("  EngineData: skipped ${parser.problems} unrecognised token(s)")
        }
        tree
    } catch (_: Exception) {
        null
    }

    private class Parser(private val data: ByteArray) {
        private var pos = 0
        private var end = data.size
        private val trace = System.getenv("PHOTO_CC_DEBUG") != null

        /** Counts tokens the parser had to skip; a non-zero value means a damaged blob. */
        var problems = 0

        init {
            // Photoshop pads the block with zeros; treat them as the end.
            while (end > 0 && data[end - 1] == 0.toByte()) end--
        }

        fun parseTop(): Ed? {
            val value = parseValue()
            return value
        }

        private fun skipSpace() {
            while (pos < end) {
                val c = data[pos].toInt().and(0xFF)
                if (c == ' '.code || c == '\n'.code || c == '\r'.code || c == '\t'.code) pos++ else break
            }
        }

        private fun peek(offset: Int = 0): Int = if (pos + offset < end) data[pos + offset].toInt().and(0xFF) else -1

        private fun parseValue(): Ed? {
            skipSpace()
            if (pos >= end) return null
            val c = peek()
            return when {
                c == '<'.code && peek(1) == '<'.code -> parseDict()
                c == '['.code -> parseList()
                c == '('.code -> parseString()
                c == 'n'.code && matches("null") -> { pos += 4; Ed.Null }
                c == 't'.code && matches("true") -> { pos += 4; Ed.Bool(true) }
                c == 'f'.code && matches("false") -> { pos += 5; Ed.Bool(false) }
                else -> parseNumberOrBareword()
            }
        }

        /** A short readable window around a byte offset, for debugging damaged blobs. */
        fun describe(at: Int): String {
            val from = (at - 24).coerceAtLeast(0)
            val to = (at + 8).coerceAtMost(end)
            val sb = StringBuilder()
            for (i in from until to) {
                val c = data[i].toInt().and(0xFF)
                sb.append(if (c in 32..126) c.toChar() else '.')
            }
            return sb.toString()
        }

        private fun matches(word: String): Boolean {
            if (pos + word.length > end) return false
            for (i in word.indices) if (data[pos + i].toInt().and(0xFF) != word[i].code) return false
            return true
        }

        /**
         * A dictionary is `/key value /key value ... >>`. Everything unknown is
         * skipped a byte at a time, so a damaged blob can never spin forever.
         */
        private fun parseDict(): Ed.Dict {
            val dict = Ed.Dict()
            pos += 2
            while (pos < end) {
                skipSpace()
                if (pos >= end) break
                if (peek() == '>'.code && peek(1) == '>'.code) { pos += 2; break }
                val before = pos
                if (peek() == '/'.code) {
                    pos++
                    val start = pos
                    while (pos < end && !isWhitespace(peek()) && peek() != '>'.code && peek() != ']'.code) pos++
                    val key = String(data, start, pos - start, Charsets.ISO_8859_1)
                    val value = parseValue()
                    if (value != null) dict[key] = value
                } else if (peek() == ')'.code || peek() == ']'.code || peek() == '>'.code) {
                    pos++
                } else {
                    val value = parseValue()
                    if (value != null && problems < 40) {
                        problems++
                        if (trace) println("      skipped near: " + describe(pos))
                    }
                }
                if (pos == before) pos++
            }
            return dict
        }

        private fun parseList(): Ed.ListValue {
            val list = Ed.ListValue()
            pos++
            while (pos < end) {
                skipSpace()
                if (pos >= end) break
                if (peek() == ']'.code) { pos++; break }
                val before = pos
                val value = parseValue()
                if (value != null) list.items.add(value)
                if (pos == before) pos++
            }
            return list
        }

        private fun parseString(): Ed.Str {
            pos++ // (
            val builder = StringBuilder()
            // Photoshop writes strings as UTF-16BE with a FE FF marker.
            if (peek() == 0xFE && peek(1) == 0xFF) {
                pos += 2
                while (pos < end) {
                    val hi = peek()
                    if (hi == ')'.code) { pos++; break }
                    if (hi == '\\'.code) {
                        pos++
                        val escaped = peek()
                        pos++
                        if (escaped == ')'.code || escaped == '('.code || escaped == '\\'.code) {
                            builder.append(escaped.toChar())
                            continue
                        }
                        // UTF-8 style escape inside a UTF-16 string.
                        val b1 = escaped
                        val b2 = peek()
                        pos++
                        val b3 = peek()
                        pos++
                        builder.append(String(byteArrayOf(b1.toByte(), b2.toByte(), b3.toByte()), Charsets.UTF_8))
                        continue
                    }
                    val value = data[pos].toInt().and(0xFF)
                    pos++
                    val lo = if (pos < end) data[pos].toInt().and(0xFF) else 0
                    pos++
                    builder.append((((value shl 8) or lo) and 0xFFFF).toChar())
                }
            } else {
                while (pos < end) {
                    val value = data[pos].toInt().and(0xFF)
                    pos++
                    if (value == ')'.code) break
                    if (value == '\\'.code && pos < end) {
                        builder.append(data[pos].toInt().and(0xFF).toChar())
                        pos++
                        continue
                    }
                    builder.append(value.toChar())
                }
            }
            return Ed.Str(builder.toString())
        }

        private fun parseNumberOrBareword(): Ed {
            val start = pos
            while (pos < end && !isWhitespace(peek()) && peek() != ']'.code && peek() != ')'.code && peek() != '>'.code) pos++
            if (pos == start) {
                // Not a token we understand: consume one byte and report "nothing".
                pos++
                return Ed.Null
            }
            val token = String(data, start, pos - start, Charsets.ISO_8859_1)
            val number = token.toDoubleOrNull()
            return if (number != null) {
                Ed.Num(number, asInt = !token.contains('.') && !token.contains('e') && !token.contains('E'))
            } else {
                Ed.Str(token)
            }
        }

        private fun isWhitespace(value: Int): Boolean =
            value == ' '.code || value == '\n'.code || value == '\r'.code || value == '\t'.code || value == -1
    }

    /* ------------------------------------------------------------- writing */

    /** Keys whose numbers must be written with a decimal point. */
    private val floatKeys = setOf(
        "Axis", "XY", "Zone", "WordSpacing", "FirstLineIndent", "GlyphSpacing", "StartIndent", "EndIndent",
        "SpaceBefore", "SpaceAfter", "LetterSpacing", "Values", "GridSize", "GridLeading", "PointBase",
        "BoxBounds", "TransformPoint0", "TransformPoint1", "TransformPoint2", "FontSize", "Leading",
        "HorizontalScale", "VerticalScale", "BaselineShift", "Tsume", "OutlineWidth", "AutoLeading",
    )

    private val intArrayKeys = setOf("RunLengthArray")

    fun serialize(root: Ed): ByteArray {
        val out = ByteArrayOutputStream(4096)
        writeValue(out, root, null, 0, false)
        out.write('\n'.code)
        return out.toByteArray()
    }

    private fun writeValue(out: ByteArrayOutputStream, value: Ed, key: String?, indent: Int, inProperty: Boolean) {
        when (value) {
            is Ed.Null -> { writePrefix(out, indent, inProperty); out.write("null".toByteArray()) }
            is Ed.Num -> {
                writePrefix(out, indent, inProperty)
                out.write(formatNumber(value, key).toByteArray())
            }
            is Ed.Bool -> { writePrefix(out, indent, inProperty); out.write((if (value.value) "true" else "false").toByteArray()) }
            is Ed.Str -> {
                writePrefix(out, indent, inProperty)
                writeQuotedString(out, value.value)
            }
            is Ed.ListValue -> {
                writePrefix(out, indent, inProperty)
                out.write('['.code)
                if (value.items.isNotEmpty() && value.items.all { it is Ed.Num }) {
                    val asInt = key != null && intArrayKeys.contains(key)
                    for (item in value.items) {
                        out.write(' '.code)
                        val number = item as Ed.Num
                        out.write((if (asInt) formatInt(number.value) else formatFloat(number.value)).toByteArray())
                    }
                    out.write(" ]".toByteArray())
                } else {
                    if (value.items.isEmpty()) {
                        out.write(" ]".toByteArray())
                    } else {
                        out.write('\n'.code)
                        for (item in value.items) {
                            writeValue(out, item, key, indent + 1, false)
                            out.write('\n'.code)
                        }
                        writeIndent(out, indent)
                        out.write(']'.code)
                    }
                }
            }
            is Ed.Dict -> {
                writePrefix(out, indent, inProperty)
                out.write("<<".toByteArray())
                if (value.entries.isNotEmpty()) {
                    out.write('\n'.code)
                    for ((childKey, child) in value.entries) {
                        writeIndent(out, indent + 1)
                        out.write('/'.code)
                        out.write(childKey.toByteArray(Charsets.ISO_8859_1))
                        writeValue(out, child, childKey, indent + 1, true)
                        out.write('\n'.code)
                    }
                    writeIndent(out, indent)
                }
                out.write(">>".toByteArray())
            }
        }
    }

    private fun writePrefix(out: ByteArrayOutputStream, indent: Int, inProperty: Boolean) {
        if (inProperty) out.write(' '.code) else writeIndent(out, indent)
    }

    private fun writeIndent(out: ByteArrayOutputStream, indent: Int) {
        repeat(indent) { out.write('\t'.code) }
    }

    private fun writeQuotedString(out: ByteArrayOutputStream, value: String) {
        out.write('('.code)
        out.write(0xFE)
        out.write(0xFF)
        for (c in value) {
            val code = c.code
            writeByteEscaped(out, (code ushr 8) and 0xFF)
            writeByteEscaped(out, code and 0xFF)
        }
        out.write(')'.code)
    }

    private fun writeByteEscaped(out: ByteArrayOutputStream, value: Int) {
        if (value == '('.code || value == ')'.code || value == '\\'.code) out.write('\\'.code)
        out.write(value)
    }

    private fun formatNumber(value: Ed.Num, key: String?): String {
        val mustBeFloat = key != null && floatKeys.contains(key)
        return if (mustBeFloat || !value.asInt) formatFloat(value.value) else formatInt(value.value)
    }

    private fun formatInt(value: Double): String = if (value.isNaN()) "0" else Math.round(value).toString()

    /** `12.5`, `.5`, `-.05` — the exact shape Photoshop writes. */
    private fun formatFloat(value: Double): String {
        if (value.isNaN() || value.isInfinite()) return "0"
        var text = String.format(Locale.ROOT, "%.5f", value)
        if (text.contains('.')) {
            text = text.trimEnd('0')
            if (text.endsWith(".")) text = text.dropLast(1)
        }
        if (text.startsWith("0.")) text = text.substring(1)
        else if (text.startsWith("-0.")) text = "-" + text.substring(2)
        if (text.isEmpty() || text == "-") text = "0"
        return text
    }

    /* ------------------------------------------------------------- accessors */

    fun Ed.Dict.dictAt(key: String): Ed.Dict? = this[key] as? Ed.Dict
    fun Ed.Dict.listAt(key: String): Ed.ListValue? = this[key] as? Ed.ListValue
    fun Ed.Dict.stringAt(key: String): String? = (this[key] as? Ed.Str)?.value
    fun Ed.Dict.numAt(key: String): Double? = (this[key] as? Ed.Num)?.value
    fun Ed.Dict.boolAt(key: String): Boolean? = (this[key] as? Ed.Bool)?.value

    /** Fields the editor exposes, decoded from a `Txt2` blob. */
    data class ParsedText(
        val text: String,
        val fontSize: Float?,
        val tracking: Int?,
        val leading: Float?,
        val autoLeading: Boolean,
        val fauxBold: Boolean,
        val fauxItalic: Boolean,
        val underline: Boolean,
        val fillColor: Int?,
        val justification: Int?,
        val fontName: String?,
        val fontScript: Int,
        val antiAlias: Int?,
        val horizontalScale: Double?,
        val verticalScale: Double?,
        val shapeType: Int?,
        val boxBounds: List<Double>?,
        val pointBase: List<Double>?,
    )

    fun readText(bytes: ByteArray): ParsedText? {
        val root = parse(bytes) ?: return null
        val engineDict = root.dictAt("EngineDict") ?: return null
        val editor = engineDict.dictAt("Editor")
        var text = editor?.stringAt("Text")?.replace('\r', '\n') ?: return null
        // Photoshop always terminates the run with a carriage return; the editor
        // works with plain text, so drop the trailing newlines it added.
        while (text.endsWith("\n")) text = text.dropLast(1)

        val resourceDict = root.dictAt("ResourceDict")
        val fontSet = resourceDict?.listAt("FontSet")
        val firstFont = fontSet?.items?.firstOrNull() as? Ed.Dict
        var fontName = firstFont?.stringAt("Name")
        var fontScript = (firstFont?.numAt("Script") ?: 0.0).toInt()

        val styleRun = engineDict.dictAt("StyleRun")
        val defaultStyle = styleRun?.dictAt("DefaultRunData")?.dictAt("StyleSheet")?.dictAt("StyleSheetData")
        val firstRun = (styleRun?.listAt("RunArray")?.items?.firstOrNull() as? Ed.Dict)
            ?.dictAt("StyleSheet")?.dictAt("StyleSheetData")
        val style = firstRun ?: defaultStyle

        val fontIndex = (style?.numAt("Font") ?: 0.0).toInt()
        val runFont = fontSet?.items?.getOrNull(fontIndex) as? Ed.Dict
        if (runFont?.stringAt("Name") != null) {
            fontName = runFont.stringAt("Name")
            fontScript = (runFont.numAt("Script") ?: 0.0).toInt()
        }

        val paragraphRun = engineDict.dictAt("ParagraphRun")
        val paragraphSheet = paragraphRun?.dictAt("DefaultRunData")?.dictAt("ParagraphSheet")
        val paragraphProperties = paragraphSheet?.dictAt("Properties")
        val firstParagraph = (paragraphRun?.listAt("RunArray")?.items?.firstOrNull() as? Ed.Dict)
            ?.dictAt("ParagraphSheet")?.dictAt("Properties")

        val rendered = engineDict.dictAt("Rendered")
        val shape = ((rendered?.dictAt("Shapes")?.listAt("Children")?.items?.firstOrNull() as? Ed.Dict)
            ?.dictAt("Cookie")?.dictAt("Photoshop"))
        val boxBounds = shape?.listAt("BoxBounds")?.items?.mapNotNull { (it as? Ed.Num)?.value }
        val pointBase = shape?.listAt("PointBase")?.items?.mapNotNull { (it as? Ed.Num)?.value }

        fun number(source: Ed.Dict?, key: String): Double? = source?.numAt(key)

        return ParsedText(
            text = text,
            fontSize = number(style, "FontSize")?.toFloat(),
            tracking = number(style, "Tracking")?.toInt(),
            leading = number(style, "Leading")?.toFloat(),
            autoLeading = style?.boolAt("AutoLeading") ?: true,
            fauxBold = style?.boolAt("FauxBold") ?: false,
            fauxItalic = style?.boolAt("FauxItalic") ?: false,
            underline = style?.boolAt("Underline") ?: false,
            fillColor = decodeEngineColor(style?.dictAt("FillColor")),
            justification = (firstParagraph ?: paragraphProperties)?.numAt("Justification")?.toInt(),
            fontName = fontName,
            fontScript = fontScript,
            antiAlias = engineDict.numAt("AntiAlias")?.toInt(),
            horizontalScale = number(style, "HorizontalScale"),
            verticalScale = number(style, "VerticalScale"),
            shapeType = shape?.numAt("ShapeType")?.toInt(),
            boxBounds = boxBounds,
            pointBase = pointBase,
        )
    }

    /** Engine-data colours are `[alpha, r, g, b]` for RGB, `[a, c, m, y, k]` for CMYK. */
    private fun decodeEngineColor(dict: Ed.Dict?): Int? {
        if (dict == null) return null
        val type = (dict["Type"] as? Ed.Num)?.value?.toInt() ?: 1
        val values = (dict["Values"] as? Ed.ListValue)?.items?.mapNotNull { (it as? Ed.Num)?.value } ?: return null
        if (values.size < 2) return null
        fun channel(index: Int): Int {
            val raw = values.getOrNull(index) ?: return 0
            val scaled = if (raw <= 1.0) raw * 255.0 else raw
            return Math.round(scaled).coerceIn(0, 255).toInt()
        }
        return when (type) {
            1 -> {
                // RGB colours are `[alpha, r, g, b]`; a three-value array is bare RGB.
                val offset = if (values.size >= 4) 1 else 0
                val alpha = if (values.size >= 4) channel(0) else 255
                Argb.pack(alpha, channel(offset), channel(offset + 1), channel(offset + 2))
            }
            0 -> {
                val gray = channel(1)
                Argb.pack(255, gray, gray, gray)
            }
            2 -> {
                val c = values.getOrNull(1) ?: 0.0
                val m = values.getOrNull(2) ?: 0.0
                val y = values.getOrNull(3) ?: 0.0
                val k = values.getOrNull(4) ?: 0.0
                fun conv(v: Double) = Math.round((1.0 - v * (1.0 - 0.0)) * 255.0).coerceIn(0, 255).toInt()
                Argb.pack(255, conv(c + k * (1 - c)), conv(m + k * (1 - m)), conv(y + k * (1 - y)))
            }
            else -> null
        }
    }

    /* ------------------------------------------------------------- writing a text layer */

    private val defaultParagraphStyle: Ed.Dict = dict(
        "Justification" to int(0),
        "FirstLineIndent" to float(0.0),
        "StartIndent" to float(0.0),
        "EndIndent" to float(0.0),
        "SpaceBefore" to float(0.0),
        "SpaceAfter" to float(0.0),
        "AutoHyphenate" to bool(true),
        "HyphenatedWordSize" to int(6),
        "PreHyphen" to int(2),
        "PostHyphen" to int(2),
        "ConsecutiveHyphens" to int(8),
        "Zone" to float(36.0),
        "WordSpacing" to listOf(float(0.8), float(1.0), float(1.33)),
        "LetterSpacing" to listOf(float(0.0), float(0.0), float(0.0)),
        "GlyphSpacing" to listOf(float(1.0), float(1.0), float(1.0)),
        "AutoLeading" to float(1.2),
        "LeadingType" to int(0),
        "Hanging" to bool(false),
        "Burasagari" to bool(false),
        "KinsokuOrder" to int(0),
        "EveryLineComposer" to bool(false),
    )

    private fun styleSheetData(style: TextData, fontIndex: Int): Ed.Dict = dict(
        "Font" to int(fontIndex),
        "FontSize" to float(style.size.toDouble()),
        "FauxBold" to bool(style.fauxBold),
        "FauxItalic" to bool(style.fauxItalic),
        "AutoLeading" to bool(style.leading == null),
        "Leading" to float((style.leading ?: (style.size * 1.2f)).toDouble()),
        "HorizontalScale" to float(1.0),
        "VerticalScale" to float(1.0),
        "Tracking" to int(style.tracking),
        "AutoKerning" to bool(true),
        "Kerning" to int(0),
        "BaselineShift" to float(0.0),
        "FontCaps" to int(0),
        "FontBaseline" to int(0),
        "Underline" to bool(style.underline),
        "Strikethrough" to bool(false),
        "Ligatures" to bool(true),
        "DLigatures" to bool(false),
        "BaselineDirection" to int(0),
        "Tsume" to float(0.0),
        "StyleRunAlignment" to int(2),
        "Language" to int(0),
        "NoBreak" to bool(false),
        "FillColor" to encodeEngineColor(style.color),
        "StrokeColor" to encodeEngineColor(0),
        "FillFlag" to bool(true),
        "StrokeFlag" to bool(false),
        "FillFirst" to bool(true),
        "YUnderline" to int(1),
        "OutlineWidth" to float(1.0),
        "CharacterDirection" to int(0),
        "HindiNumbers" to bool(false),
        "Kashida" to int(1),
        "DiacriticPos" to int(2),
    )

    private fun encodeEngineColor(color: Int): Ed.Dict = dict(
        "Type" to int(1),
        // [alpha, r, g, b] — alpha first, matching Photoshop's engine data.
        "Values" to listOf(
            float(Argb.a(color) / 255.0),
            float(Argb.r(color) / 255.0),
            float(Argb.g(color) / 255.0),
            float(Argb.b(color) / 255.0),
        ),
    )

    private fun paragraphSheet(style: TextData): Ed.Dict {
        val properties = defaultParagraphStyle.copyForText()
        properties["Justification"] = int(style.align.psd)
        return dict(
            "ParagraphSheet" to dict(
                "DefaultStyleSheet" to int(0),
                "Properties" to properties,
            ),
            "Adjustments" to dict(
                "Axis" to listOf(float(1.0), float(0.0), float(1.0)),
                "XY" to listOf(float(0.0), float(0.0)),
            ),
        )
    }

    private fun Ed.Dict.copyForText(): Ed.Dict {
        val out = Ed.Dict()
        for ((key, value) in entries) {
            out[key] = when (value) {
                is Ed.Dict -> value.copyForText()
                is Ed.ListValue -> Ed.ListValue(value.items.toMutableList())
                is Ed.Num -> Ed.Num(value.value, value.asInt)
                is Ed.Str -> Ed.Str(value.value)
                is Ed.Bool -> Ed.Bool(value.value)
                Ed.Null -> Ed.Null
            }
        }
        return out
    }

    /** Full `Txt2` document for a text layer, shaped like the one Photoshop writes. */
    fun writeText(layer: TextLayer): ByteArray {
        val style = layer.text
        val text = style.text.replace(Regex("\r?\n"), "\r") + "\r"
        val length = text.length
        val fontName = style.psdFontName.ifBlank { "ArialMT" }
        val standardCount = 1
        val fontIndex = standardCount

        val fonts = Ed.ListValue(
            mutableListOf(
                dict("Name" to str("AdobeInvisFont"), "Script" to int(0), "FontType" to int(0), "Synthetic" to int(0)),
                dict(
                    "Name" to str(fontName),
                    "Script" to int(style.psdFontScript),
                    "FontType" to int(0),
                    "Synthetic" to int(0),
                ),
            )
        )

        val styleSheet = dict("StyleSheetData" to styleSheetData(style, fontIndex))
        val styleRunArray = Ed.ListValue(mutableListOf(dict("StyleSheet" to styleSheet)))
        val styleRun = dict(
            "DefaultRunData" to dict("StyleSheet" to dict("StyleSheetData" to Ed.Dict())),
            "RunArray" to styleRunArray,
            "RunLengthArray" to Ed.ListValue(mutableListOf(int(length))),
            "IsJoinable" to int(2),
        )

        val paragraphRunData = paragraphSheet(style)
        val paragraphRun = dict(
            "DefaultRunData" to dict(
                "ParagraphSheet" to dict("DefaultStyleSheet" to int(0), "Properties" to Ed.Dict()),
                "Adjustments" to dict(
                    "Axis" to listOf(float(1.0), float(0.0), float(1.0)),
                    "XY" to listOf(float(0.0), float(0.0)),
                ),
            ),
            "RunArray" to Ed.ListValue(mutableListOf(paragraphRunData)),
            "RunLengthArray" to Ed.ListValue(mutableListOf(int(length))),
            "IsJoinable" to int(1),
        )

        val writingDirection = if (layer.vertical) 2 else 0
        val procession = if (layer.vertical) 1 else 0
        val shapeType = 0 // point text: the phone editor moves text with a drag
        val photoshop = Ed.Dict()
        photoshop["ShapeType"] = int(shapeType)
        photoshop["PointBase"] = listOf(float(layer.x.toDouble()), float(layer.y.toDouble()))
        photoshop["Base"] = dict(
            "ShapeType" to int(shapeType),
            "TransformPoint0" to listOf(float(1.0), float(0.0)),
            "TransformPoint1" to listOf(float(0.0), float(1.0)),
            "TransformPoint2" to listOf(float(0.0), float(0.0)),
        )

        val gridColor = dict("Type" to int(1), "Values" to listOf(float(0.0), float(0.0), float(0.0), float(0.0)))
        val gridInfo = dict(
            "GridIsOn" to bool(false),
            "ShowGrid" to bool(false),
            "GridSize" to float(18.0),
            "GridLeading" to float(22.0),
            "GridColor" to gridColor,
            "GridLeadingFillColor" to dict("Type" to int(1), "Values" to listOf(float(0.0), float(0.0), float(0.0), float(0.0))),
            "AlignLineHeightToGridFlags" to bool(false),
        )

        val engineDict = Ed.Dict()
        engineDict["Editor"] = dict("Text" to str(text))
        engineDict["ParagraphRun"] = paragraphRun
        engineDict["StyleRun"] = styleRun
        engineDict["GridInfo"] = gridInfo
        engineDict["AntiAlias"] = int(16)
        engineDict["UseFractionalGlyphWidths"] = bool(true)
        engineDict["WritingDirection"] = int(writingDirection)
        engineDict["Procession"] = int(procession)
        engineDict["Rendered"] = dict(
            "Version" to int(1),
            "Shapes" to dict(
                "WritingDirection" to int(writingDirection),
                "Children" to Ed.ListValue(
                    mutableListOf(
                        dict(
                            "ShapeType" to int(shapeType),
                            "Procession" to int(procession),
                            "Lines" to dict("WritingDirection" to int(writingDirection), "Children" to Ed.ListValue()),
                            "Cookie" to dict("Photoshop" to photoshop),
                        )
                    )
                ),
            ),
        )

        val resources = defaultResources(style, fonts, paragraphRunData, styleSheet)
        val root = Ed.Dict()
        root["EngineDict"] = engineDict
        root["ResourceDict"] = resources
        root["DocumentResources"] = defaultResources(style, fonts, paragraphRunData, styleSheet)
        return serialize(root)
    }

    private fun defaultResources(
        style: TextData,
        fonts: Ed.ListValue,
        paragraphRunData: Ed.Dict,
        styleSheet: Ed.Dict,
    ): Ed.Dict {
        val properties = (paragraphRunData.dictAt("ParagraphSheet"))?.dictAt("Properties") ?: defaultParagraphStyle.copyForText()
        return dict(
            "KinsokuSet" to Ed.ListValue(
                mutableListOf(
                    dict(
                        "Name" to str("PhotoshopKinsokuHard"),
                        "NoStart" to str("、。，．・：；？！ー―’”）〕］｝〉》」』】ヽヾゝゞ々ぁぃぅぇぉっゃゅょゎァィゥェォッャュョヮヵヶ゛゜?!)]},.:;℃℉¢％‰"),
                        "NoEnd" to str("‘“（〔［｛〈《「『【([{￥＄£＠§〒＃"),
                        "Keep" to str("―‥"),
                        "Hanging" to str("、。.,"),
                    ),
                    dict(
                        "Name" to str("PhotoshopKinsokuSoft"),
                        "NoStart" to str("、。，．・：；？！’”）〕］｝〉》」』】ヽヾゝゞ々"),
                        "NoEnd" to str("‘“（〔［｛〈《「『【"),
                        "Keep" to str("―‥"),
                        "Hanging" to str("、。.,"),
                    ),
                )
            ),
            "MojiKumiSet" to Ed.ListValue(
                mutableListOf(
                    dict("InternalName" to str("Photoshop6MojiKumiSet1")),
                    dict("InternalName" to str("Photoshop6MojiKumiSet2")),
                    dict("InternalName" to str("Photoshop6MojiKumiSet3")),
                    dict("InternalName" to str("Photoshop6MojiKumiSet4")),
                )
            ),
            "TheNormalStyleSheet" to int(0),
            "TheNormalParagraphSheet" to int(0),
            "ParagraphSheetSet" to Ed.ListValue(
                mutableListOf(
                    dict(
                        "Name" to str("Normal RGB"),
                        "DefaultStyleSheet" to int(0),
                        "Properties" to properties.copyForText(),
                    )
                )
            ),
            "StyleSheetSet" to Ed.ListValue(
                mutableListOf(dict("Name" to str("Normal RGB"), "StyleSheetData" to styleSheet.copyForText()))
            ),
            "FontSet" to fonts,
            "SuperscriptSize" to float(0.583),
            "SuperscriptPosition" to float(0.333),
            "SubscriptSize" to float(0.583),
            "SubscriptPosition" to float(0.333),
            "SmallCapSize" to float(0.7),
        )
    }

    /** Plain-text fallback reader for blobs the full parser cannot handle. */
    fun extractText(bytes: ByteArray): String? = readText(bytes)?.text
}
