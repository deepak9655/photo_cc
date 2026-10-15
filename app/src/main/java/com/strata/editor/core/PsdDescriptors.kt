package com.strata.editor.core

import java.io.ByteArrayOutputStream

/**
 * Photoshop's descriptor format: the nested key → value structure used by text
 * layers and by most "additional layer info" blocks.
 *
 * Keys are either a 4-byte id (`Txt `, `TxLr`, …) or a length-prefixed UTF-16
 * string, with nothing to tell them apart — the reader decides from the length
 * field, which is the same trick every other implementation uses. Unknown value
 * types abort the current descriptor instead of corrupting everything after it, so
 * a file containing a 3D layer or a smart object still opens.
 */
class DescriptorReader(private val data: ByteArray, var position: Int = 0) {

    fun remaining(): Int = data.size - position

    /**
     * Reading past the end yields zero instead of throwing: a truncated or corrupt
     * descriptor has to leave a layer unreadable, not take the whole file down.
     */
    fun u8(): Int {
        if (position < 0 || position >= data.size) {
            position = data.size
            return 0
        }
        return data[position++].toInt() and 0xFF
    }
    fun u16(): Int = (u8() shl 8) or u8()
    fun i16(): Int = u16().toShort().toInt()
    fun i32(): Int = (u8() shl 24) or (u8() shl 16) or (u8() shl 8) or u8()
    fun u32(): Long = i32().toLong() and 0xFFFFFFFFL
    fun f32(): Float = Float.fromBits(i32())
    fun f64(): Double = Double.fromBits((u32() shl 32) or u32())

    fun fourCc(): String {
        val bytes = ByteArray(4) { u8().toByte() }
        return String(bytes, Charsets.US_ASCII)
    }

    fun skip(count: Int) {
        position = (position + count).coerceIn(0, data.size)
    }

    fun string(length: Int): String {
        val bytes = ByteArray(length * 2)
        val available = minOf(bytes.size, data.size - position)
        System.arraycopy(data, position, bytes, 0, available)
        position += available
        return String(bytes, 0, available, Charsets.UTF_16BE)
    }

    fun unicodeString(): String {
        val length = i32()
        if (length < 0 || length > 100_000) return ""
        return string(length)
    }

    /** A key: 4-byte id or length-prefixed string, decided by the length field. */
    fun key(): String {
        val start = position
        val length = i32()
        if (length in 1..512) return string(length)
        position = start
        return fourCc()
    }

    fun pascalString(pad: Int = 1): String {
        val length = u8()
        if (length < 0 || length > 255) return ""
        val text = String(data, position, minOf(length, data.size - position), Charsets.ISO_8859_1)
        position += length
        var consumed = length + 1
        while (consumed % pad != 0) {
            consumed++
            position++
        }
        return text
    }

    /** An inline (or referenced) descriptor. */
    fun descriptor(): MutableMap<String, Any> {
        val out = LinkedHashMap<String, Any>()
        if (remaining() < 8) return out
        out["__class__"] = key()
        val count = i32()
        if (count !in 0..4096) return out
        for (i in 0 until count) {
            if (remaining() < 8) break
            val name = key()
            out[name] = value() ?: break
        }
        return out
    }

    fun value(): Any? {
        val type = fourCc()
        return when (type) {
            "TEXT" -> unicodeString()
            "enum" -> mapOf("type" to key(), "value" to key())
            "long" -> i32()
            "doub" -> f64()
            "untF" -> mapOf("unit" to fourCc(), "value" to f64())
            "bool" -> u8() != 0
            "Objc", "GlbO" -> descriptor()
            "obj " -> key() // a reference to an object defined elsewhere: ignore it
            "VlLs" -> {
                val count = i32()
                val list = ArrayList<Any>()
                if (count in 0..4096) repeat(count) { value()?.let { item -> list.add(item) } }
                list
            }
            "ObAr" -> {
                val out2 = LinkedHashMap<String, Any>()
                val count = i32()
                if (count in 0..4096) {
                    var i = 0
                    while (i < count) {
                        val name = key()
                        val item = value() ?: break
                        out2[name] = item
                        i++
                    }
                }
                out2
            }
            "tdta", "UnFl", "alis" -> {
                val length = i32().coerceIn(0, remaining())
                val bytes = ByteArray(length)
                System.arraycopy(data, position, bytes, 0, length)
                position += length
                bytes
            }
            "Pth " -> {
                val units = u16()
                val count = u16()
                mapOf("units" to units, "points" to List(count) { doubleArrayOf(f64(), f64(), f64(), f64(), f64(), f64()) })
            }
            "indx" -> u16()
            "type", "GlbC" -> key()
            else -> null
        }
    }
}

/**
 * Writes descriptors.
 *
 * The important rule: a **nested object must be tagged `Objc`** — an inline object.
 * Tagging it `obj ` makes it a *reference*, and a block like that is rejected
 * outright (the text layer silently disappears). The entry count of every
 * descriptor is passed in explicitly, because the format requires it up front.
 */
class DescriptorWriter {

    private val out = ByteArrayOutputStream(1024)

    fun bytes(): ByteArray = out.toByteArray()
    fun size(): Int = out.size()

    fun u8(value: Int) {
        out.write(value and 0xFF)
    }

    fun i16(value: Int) {
        u8(value ushr 8); u8(value)
    }

    fun i32(value: Int) {
        u8(value ushr 24); u8(value ushr 16); u8(value ushr 8); u8(value)
    }

    fun f32(value: Float) = i32(value.toRawBits())

    fun f64(value: Double) {
        val bits = value.toRawBits()
        i32((bits ushr 32).toInt())
        i32(bits.toInt())
    }

    fun raw(bytes: ByteArray) {
        out.write(bytes, 0, bytes.size)
    }

    fun fourCc(value: String) {
        val text = value.padEnd(4, ' ').substring(0, 4)
        for (c in text) u8(c.code)
    }

    fun unicodeString(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_16BE)
        i32(value.length)
        out.write(bytes, 0, bytes.size)
    }

    /** Writes a key as a 4-byte id when it looks like one, else as a string. */
    fun key(name: String) {
        if (name.length == 4 && name.all { it.code in 32..126 }) fourCc(name) else unicodeString(name)
    }

    fun text(name: String, value: String) {
        key(name); fourCc("TEXT"); unicodeString(value)
    }

    fun enumeration(name: String, type: String, value: String) {
        key(name); fourCc("enum"); key(type); key(value)
    }

    fun long(name: String, value: Int) {
        key(name); fourCc("long"); i32(value)
    }

    fun double(name: String, value: Double) {
        key(name); fourCc("doub"); f64(value)
    }

    fun unitDouble(name: String, unit: String, value: Double) {
        key(name); fourCc("untF"); fourCc(unit); f64(value)
    }

    fun bool(name: String, value: Boolean) {
        key(name); fourCc("bool"); u8(if (value) 1 else 0)
    }

    /** `objc` — an inline object with `entries` key/value pairs inside. */
    fun objectValue(name: String, classId: String, entries: Int, body: DescriptorWriter.() -> Unit) {
        key(name)
        fourCc("Objc")
        descriptor(classId, entries, body)
    }

    fun descriptor(classId: String, entries: Int, body: DescriptorWriter.() -> Unit) {
        unicodeString(classId)
        i32(entries)
        body()
    }

    /** A list value; the caller writes exactly `count` items in `body`. */
    fun list(name: String, count: Int, body: DescriptorWriter.() -> Unit) {
        key(name)
        fourCc("VlLs")
        i32(count)
        body()
    }

    fun textValue(value: String) {
        fourCc("TEXT"); unicodeString(value)
    }

    fun objectItem(classId: String, entries: Int, body: DescriptorWriter.() -> Unit) {
        fourCc("Objc")
        descriptor(classId, entries, body)
    }

    fun enumerationValue(type: String, value: String) {
        fourCc("enum"); key(type); key(value)
    }

    fun unitDoubleValue(unit: String, value: Double) {
        fourCc("untF"); fourCc(unit); f64(value)
    }

    fun doubleValue(value: Double) {
        fourCc("doub"); f64(value)
    }

    fun longValue(value: Int) {
        fourCc("long"); i32(value)
    }

    fun boolValue(value: Boolean) {
        fourCc("bool"); u8(if (value) 1 else 0)
    }
}
