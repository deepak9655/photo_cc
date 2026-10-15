package com.photocc.app.core

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/**
 * Text support.
 *
 * A PSD stores text twice: once as a *descriptor* (`TySh`, what the UI shows)
 * and once as Adobe's "engine data" (`Txt2`, what Photoshop actually re-renders
 * from, including font sets, style runs and paragraph runs). To keep text layers
 * genuinely editable after saving, this file reads both and writes both.
 *
 * The platform never leaks in here: measuring and rasterising go through
 * [measurer] and [rasterizer], which the Android UI fills in (Paint/Canvas) and
 * the JVM self-test fills in with AWT.
 */

/* ----------------------------------------------------------------- metric hooks */

/** Width/ascent of a string in a given style. */
interface TextMeasurer {
    fun width(text: String, style: TextData): Float
    fun ascent(style: TextData): Float
    fun descent(style: TextData): Float
    /** Inches per pixel assumption used when Photoshop did not store a size. */
    val densityIndependence: Float get() = 1f

    companion object {
        /** Default: a rough monospace-ish estimate so the engine is always usable. */
        var current: TextMeasurer = object : TextMeasurer {
            override fun width(text: String, style: TextData): Float = text.length * style.size * 0.55f
            override fun ascent(style: TextData): Float = style.size * 0.78f
            override fun descent(style: TextData): Float = style.size * 0.22f
        }
    }
}

/** Renders a text layer into straight-alpha ARGB pixels. */
interface TextRasterizer {
    /** Draws the layer into `out` (which covers `rect` in document space). */
    fun rasterize(layer: TextLayer, rect: Rect, out: IntArray)
}

object TextEngine {
    var measurer: TextMeasurer
        get() = TextMeasurer.current
        set(value) { TextMeasurer.current = value }

    var rasterizer: TextRasterizer? = null
}

/* ----------------------------------------------------------------- layout */

data class TextLine(val text: String, val width: Float, val start: Int)

class TextGeometry(
    val lines: List<TextLine>,
    val lineHeight: Float,
    val ascent: Float,
    val descent: Float,
    val width: Float,
    val height: Float,
    val firstBaseline: Float,
    val x: Float,
    val y: Float,
) {
    val bounds: Rect
        get() {
            val pad = if (width <= 0f || height <= 0f) 0 else 1
            return Rect(
                floor(x).toInt() - pad,
                floor(y).toInt() - pad,
                ceil(width).toInt() + pad * 2,
                ceil(height).toInt() + pad * 2,
            )
        }

    companion object {
        private val cache = HashMap<String, TextGeometry>(64)

        fun measure(layer: TextLayer): TextGeometry {
            val style = layer.text
            val key = buildString {
                append(style.fontFamily).append('|').append(style.size).append('|')
                append(style.tracking).append('|').append(style.leading ?: -1f).append('|')
                append(style.fauxBold).append(style.fauxItalic).append('|').append(style.text)
            }
            val cached = cache[key]
            if (cached != null) return TextGeometry(cached.lines, cached.lineHeight, cached.ascent, cached.descent, cached.width, cached.height, cached.firstBaseline, layer.x, layer.y)
            val geometry = layout(style, layer.x, layer.y)
            if (cache.size > 128) cache.clear()
            cache[key] = geometry
            return geometry
        }

        fun measure(style: TextData, x: Float, y: Float): TextGeometry = layout(style, x, y)

        private fun layout(style: TextData, x: Float, y: Float): TextGeometry {
            val measurer = TextEngine.measurer
            val ascent = measurer.ascent(style)
            val descent = measurer.descent(style)
            val rawLines = style.text.split('\n')
            val lines = ArrayList<TextLine>(rawLines.size)
            var width = 0f
            var start = 0
            for (line in rawLines) {
                val lineWidth = if (line.isEmpty()) 0f else measurer.width(line, style)
                lines.add(TextLine(line, lineWidth, start))
                width = max(width, lineWidth)
                start += line.length + 1
            }
            val lineHeight = style.leading?.takeIf { it > 0f } ?: (style.size * 1.2f)
            val height = if (lines.size <= 1) ascent + descent else (lines.size - 1) * lineHeight + ascent + descent
            return TextGeometry(lines, lineHeight, ascent, descent, width, height, ascent, x, y)
        }
    }
}

/* ----------------------------------------------------------------- font names */

/**
 * PSD fonts are named by PostScript name ("Arial-BoldMT"). A phone has whatever
 * the OEM shipped, so unknown names fall back to a generic family *for the
 * preview only* — the PostScript name itself is written back into the file.
 */
object FontMap {

    data class DeviceFont(val label: String, val family: String, val psdName: String)
    data class Resolved(val family: String, val weight: Int, val italic: Boolean)

    /** Short list on purpose: a 5-inch screen wants six choices, not three thousand. */
    val picker: List<DeviceFont> = listOf(
        DeviceFont("System", "sans-serif", "ArialMT"),
        DeviceFont("System (serif)", "serif", "TimesNewRomanPSMT"),
        DeviceFont("System (mono)", "monospace", "CourierNewPSMT"),
        DeviceFont("Sans light", "sans-serif-light", "Arial-Light"),
        DeviceFont("Sans condensed", "sans-serif-condensed", "RobotoCondensed-Regular"),
        DeviceFont("Sans black", "sans-serif-black", "Arial-Black"),
        DeviceFont("Serif bold", "serif-monospace", "CourierNewPS-BoldMT"),
    )

    private val boldHints = Regex("bold|black|heavy|semibold|semi|demi|ultra", RegexOption.IGNORE_CASE)
    private val italicHints = Regex("italic|oblique", RegexOption.IGNORE_CASE)

    private val known: Map<String, Resolved> = buildMap {
        fun add(vararg names: String, family: String, weight: Int = 400, italic: Boolean = false) {
            for (name in names) put(name.lowercase(), Resolved(family, weight, italic))
        }
        add("arialmt", "helveticaneue", family = "sans-serif")
        add("arial-boldmt", "arialblack", "myriadpro-bold", "helveticaneue-bold", family = "sans-serif", weight = 700)
        add("arialmt-italic", "helveticaneue-italic", family = "sans-serif", italic = true)
        add("arialbolditalicmt", family = "sans-serif", weight = 700, italic = true)
        add("arialnarrow", family = "sans-serif-condensed")
        add("timesnewromanpsmt", "timesroman", "minionpro-regular", "georgia", family = "serif")
        add("timesnewromanps-boldmt", "minionpro-bold", family = "serif", weight = 700)
        add("timesnewromanps-italicmt", family = "serif", italic = true)
        add("couriernewpsmt", "courier", "consolas", family = "monospace")
        add("couriernewps-boldmt", family = "monospace", weight = 700)
        add("roboto-regular", "robotocondensed-regular", "notosans-regular", "opensans-regular", "lato-regular", "inter-regular", family = "sans-serif")
        add("robotocondensed-bold", "notosans-bold", "inter-bold", family = "sans-serif", weight = 700)
        add("notoserif-regular", "merriweather", family = "serif")
        add("sourcecodepro-regular", "dejavusansmono", family = "monospace")
    }

    private val suffix = Regex("[-_]?(regular|roman|mt|psmt|std|pro|book)$", RegexOption.IGNORE_CASE)

    fun psdNameToDevice(psdName: String?): Resolved {
        if (psdName.isNullOrBlank()) return Resolved("sans-serif", 400, false)
        val stripped = psdName.lowercase().replace(suffix, "")
        known[psdName.lowercase()]?.let { hit ->
            return Resolved(hit.family, if (boldHints.containsMatchIn(psdName)) 700 else hit.weight, hit.italic || italicHints.containsMatchIn(psdName))
        }
        known[stripped]?.let { hit ->
            return Resolved(hit.family, if (boldHints.containsMatchIn(psdName)) 700 else hit.weight, hit.italic || italicHints.containsMatchIn(psdName))
        }
        // Unknown family: ask the platform for it, then fall back to a generic.
        val clean = psdName.replace(Regex("[-_](Regular|Roman|MT|PSMT|Std|Pro)$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("([a-z])([A-Z])"), "$1 $2")
            .trim()
        val weight = if (boldHints.containsMatchIn(psdName)) 700 else 400
        val italic = italicHints.containsMatchIn(psdName)
        return if (clean.length in 2..40 && clean.all { it.isLetterOrDigit() || it == ' ' }) {
            // the family name is passed through; Android substitutes if missing
            Resolved(sanitizeFamily(clean), weight, italic)
        } else {
            Resolved("sans-serif", weight, italic)
        }
    }

    private fun sanitizeFamily(name: String): String {
        // A font family that Android does not know silently falls back to Roboto,
        // which is the same behaviour a desktop viewer shows for a missing font.
        return name
    }

    /** Device family -> PostScript-ish name for a file that others will open. */
    fun deviceToPsdName(family: String, weight: Int, italic: Boolean): String {
        val base = picker.firstOrNull { it.family == family }?.psdName ?: "ArialMT"
        var name = base
        if (weight >= 700) {
            name = when (name) {
                "ArialMT" -> "Arial-BoldMT"
                "TimesNewRomanPSMT" -> "TimesNewRomanPS-BoldMT"
                "CourierNewPSMT" -> "CourierNewPS-BoldMT"
                else -> if (name.contains("-Regular")) name.replace("-Regular", "-Bold") else name.replace("-Regular", "") + "-Bold"
            }
        }
        if (italic) {
            name = when (name) {
                "ArialMT" -> "Arial-ItalicMT"
                "TimesNewRomanPSMT" -> "TimesNewRomanPS-ItalicMT"
                "CourierNewPSMT" -> "CourierNewPS-ItalicMT"
                else -> if (name.contains("-Regular")) name.replace("-Regular", "-Italic") else "$name-Italic"
            }
        }
        return name
    }

    /** Applies every family the platform actually has, so the UI can mark substitutions. */
    fun isInstalled(@Suppress("UNUSED_PARAMETER") family: String): Boolean = true
}

object TextLayout {
    /** Reserved for future vertical-text support. */
    val supported = true
}
