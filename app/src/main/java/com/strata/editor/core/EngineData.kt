package com.strata.editor.core

/**
 * The "engine data" blob inside a text layer.
 *
 * It is a small typed Lisp: `<< /Key value … >>` for dictionaries, `[ … ]` for
 * arrays, `/Name`, numbers with an optional trailing `.`, and `(strings)` with
 * backslash escapes. Photoshop reads it to lay the text out, and it is what makes
 * a text layer editable after a round trip.
 */
object EngineData {

    private const val MAX_DEPTH = 32

    /* --------------------------------------------------------------- writing */

    fun write(root: Map<String, Any>): ByteArray {
        val out = StringBuilder(2048)
        out.append("<<\r")
        writeDict(out, root, 1)
        out.append(">>")
        return out.toString().toByteArray(Charsets.US_ASCII)
    }

    private fun writeDict(out: StringBuilder, map: Map<String, Any>, depth: Int) {
        for ((key, value) in map) {
            indent(out, depth)
            out.append('/').append(key).append(' ')
            writeValue(out, value, depth)
            out.append("\r")
        }
    }

    private fun writeValue(out: StringBuilder, value: Any, depth: Int) {
        when (value) {
            is String -> out.append('(').append(escape(value)).append(')')
            is Boolean -> out.append(if (value) "true" else "false")
            is Int -> out.append(value).append('.')
            is Long -> out.append(value).append('.')
            is Float -> out.append(number(value.toDouble()))
            is Double -> out.append(number(value))
            is IntArray -> {
                out.append('[')
                value.forEach { out.append(it).append(' ') }
                if (value.isNotEmpty()) out.setLength(out.length - 1)
                out.append(']')
            }
            is FloatArray -> {
                out.append('[')
                value.forEach { out.append(number(it.toDouble())).append(' ') }
                if (value.isNotEmpty()) out.setLength(out.length - 1)
                out.append(']')
            }
            is Map<*, *> -> {
                @Suppress("UNCHECKED_CAST")
                val nested = value as Map<String, Any>
                out.append("<<\r")
                writeDict(out, nested, depth + 1)
                indent(out, depth)
                out.append(">>")
            }
            is List<*> -> {
                out.append("[\r")
                for (item in value) {
                    indent(out, depth + 1)
                    @Suppress("UNCHECKED_CAST")
                    writeValue(out, item as Any, depth + 1)
                    out.append("\r")
                }
                indent(out, depth)
                out.append("]")
            }
            else -> out.append("0.")
        }
    }

    /** Photoshop's numbers keep a trailing dot: `48.` is a real, `48` an integer. */
    private fun number(value: Double): String {
        if (value == value.toLong().toDouble() && kotlin.math.abs(value) < 1e15) return "${value.toLong()}."
        return value.toString()
    }

    private fun indent(out: StringBuilder, depth: Int) {
        repeat(depth) { out.append('\t') }
    }

    private fun escape(text: String): String {
        val out = StringBuilder(text.length + 8)
        for (c in text) {
            when (c) {
                '(' -> out.append("\\(")
                ')' -> out.append("\\)")
                '\\' -> out.append("\\\\")
                '\r' -> out.append("\\r")
                '\n' -> out.append("\\n")
                else -> out.append(c)
            }
        }
        return out.toString()
    }

    /* --------------------------------------------------------------- reading */

    private class Cursor(val text: String) {
        var position = 0
        fun skipWhitespace() {
            while (position < text.length && text[position].isWhitespace()) position++
        }
        fun peek(): Char = if (position < text.length) text[position] else '\u0000'
        fun take(): Char = text[position++]
        fun atEnd(): Boolean = position >= text.length
    }

    fun read(bytes: ByteArray): Map<String, Any> {
        val text = String(bytes, Charsets.ISO_8859_1)
        val cursor = Cursor(text)
        cursor.skipWhitespace()
        return try {
            @Suppress("UNCHECKED_CAST")
            (parseValue(cursor, 0) as? Map<String, Any>) ?: emptyMap()
        } catch (error: Exception) {
            emptyMap()
        }
    }

    private fun parseValue(cursor: Cursor, depth: Int): Any? {
        if (depth > MAX_DEPTH) return null
        cursor.skipWhitespace()
        if (cursor.atEnd()) return null
        return when (cursor.peek()) {
            '<' -> parseDictionary(cursor, depth)
            '[' -> parseArray(cursor, depth)
            '(' -> parseString(cursor)
            '/' -> parseName(cursor)
            else -> parseAtom(cursor)
        }
    }

    private fun parseDictionary(cursor: Cursor, depth: Int): Map<String, Any> {
        cursor.take(); cursor.take() // <<
        val out = LinkedHashMap<String, Any>()
        while (!cursor.atEnd()) {
            cursor.skipWhitespace()
            if (cursor.peek() == '>' && cursor.position + 1 < cursor.text.length && cursor.text[cursor.position + 1] == '>') {
                cursor.take(); cursor.take()
                break
            }
            if (cursor.peek() != '/') {
                // Malformed: bail out rather than spin.
                if (cursor.position + 1 >= cursor.text.length) break
                cursor.take()
                continue
            }
            val key = parseName(cursor)
            val value = parseValue(cursor, depth + 1) ?: break
            out[key] = value
        }
        return out
    }

    private fun parseArray(cursor: Cursor, depth: Int): List<Any> {
        cursor.take() // [
        val out = ArrayList<Any>()
        while (!cursor.atEnd()) {
            cursor.skipWhitespace()
            if (cursor.peek() == ']') {
                cursor.take()
                break
            }
            val value = parseValue(cursor, depth + 1) ?: break
            out.add(value)
        }
        return out
    }

    private fun parseName(cursor: Cursor): String {
        cursor.take() // /
        val start = cursor.position
        while (!cursor.atEnd() && !cursor.peek().isWhitespace() && cursor.peek() != '/' && cursor.peek() != '(' && cursor.peek() != '<' && cursor.peek() != '[') {
            cursor.take()
        }
        return cursor.text.substring(start, cursor.position)
    }

    private fun parseString(cursor: Cursor): String {
        cursor.take() // (
        val out = StringBuilder()
        var depth = 1
        while (!cursor.atEnd()) {
            val c = cursor.take()
            when {
                c == '\\' && !cursor.atEnd() -> {
                    when (val escaped = cursor.take()) {
                        'r' -> out.append('\r')
                        'n' -> out.append('\n')
                        't' -> out.append('\t')
                        else -> out.append(escaped)
                    }
                }
                c == '(' -> {
                    depth++
                    out.append(c)
                }
                c == ')' -> {
                    depth--
                    if (depth == 0) break
                    out.append(c)
                }
                else -> out.append(c)
            }
        }
        return out.toString()
    }

    private fun parseAtom(cursor: Cursor): Any {
        val start = cursor.position
        while (!cursor.atEnd() && !cursor.peek().isWhitespace() && cursor.peek() !in ")]>") cursor.take()
        val token = cursor.text.substring(start, cursor.position)
        return when {
            token == "true" -> true
            token == "false" -> false
            token.isEmpty() -> ""
            token.endsWith(".") -> token.dropLast(1).toDoubleOrNull() ?: 0.0
            else -> token.toDoubleOrNull() ?: token
        }
    }

}

/* ----------------------------------------------------------- typed accessors */


@Suppress("UNCHECKED_CAST")
fun Map<String, Any>.dict(key: String): Map<String, Any>? = this[key] as? Map<String, Any>

fun Map<String, Any>.string(key: String): String? = this[key] as? String

fun Map<String, Any>.number(key: String): Double? = when (val value = this[key]) {
    is Double -> value
    is Int -> value.toDouble()
    is Float -> value.toDouble()
    is Long -> value.toDouble()
    else -> null
}

fun Map<String, Any>.int(key: String): Int? = number(key)?.toInt()

@Suppress("UNCHECKED_CAST")
fun Map<String, Any>.items(key: String): List<Any>? = this[key] as? List<Any>

/** Alias for [items], because a descriptor calls these arrays. */
@Suppress("UNCHECKED_CAST")
fun Map<String, Any>.list(key: String): List<Any>? = this[key] as? List<Any>

fun Map<String, Any>.bool(key: String): Boolean? = this[key] as? Boolean

/** The plain text of a document, from `/EngineDict/Editor/Text`. */
fun Map<String, Any>.engineText(): String? =
    dict("EngineDict")?.dict("Editor")?.string("Text")

fun Map<String, Any>.engineFontName(): String? {
    val fonts = dict("EngineDict")?.items("FontSet") ?: return null
    val first = fonts.firstOrNull() as? Map<String, Any> ?: return null
    return first.string("Name")
}

fun Map<String, Any>.engineFontSize(): Double? {
    val styleRun = dict("EngineDict")?.dict("StyleRun") ?: return null
    val sheetData = styleRun.dict("DefaultRunData")?.dict("StyleSheet")?.dict("StyleSheetData")
        ?: (styleRun.items("RunArray")?.firstOrNull() as? Map<String, Any>)
            ?.dict("StyleSheet")?.dict("StyleSheetData")
    return sheetData?.number("FontSize")
}
