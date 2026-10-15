package com.photocc.app.core

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/** Big-endian random access reader over a byte array (the PSD byte order). */
class Cursor(val data: ByteArray, var pos: Int = 0) {
    val size: Int get() = data.size
    val remaining: Int get() = data.size - pos

    fun u8(): Int = data[pos++].toInt() and 0xFF
    fun i8(): Int = data[pos++].toInt()
    fun u16(): Int = ((u8() shl 8) or u8())
    fun i16(): Int = u16().let { if (it >= 0x8000) it - 0x10000 else it }

    fun i32(): Int = ((u8() shl 24) or (u8() shl 16) or (u8() shl 8) or u8())

    /** Unsigned 32-bit as a Long, so sizes above 2 GB do not go negative. */
    fun u32(): Long = i32().toLong() and 0xFFFFFFFFL

    fun u64(): Long = ((u32() shl 32) or u32())

    fun f32(): Float = Float.fromBits(i32())

    fun f64(): Double = Double.fromBits(u64())

    fun fourCc(): String {
        val sb = StringBuilder(4)
        repeat(4) { sb.append(u8().toChar()) }
        return sb.toString()
    }

    fun bytes(count: Int): ByteArray {
        val length = if (count < 0) 0 else minOf(count, data.size - pos).coerceAtLeast(0)
        val out = ByteArray(length)
        System.arraycopy(data, pos, out, 0, length)
        pos += length
        return out
    }

    fun skip(count: Long) {
        pos = (pos + count).coerceIn(0, data.size.toLong()).toInt()
    }

    /** Pascal string (1-byte length) padded to a `pad` byte boundary. */
    fun pascalString(pad: Int = 2): String {
        val length = u8()
        val bytes = ByteArray(length)
        for (i in 0 until length) bytes[i] = data[pos++]
        // Padding is relative to the whole field (length byte included).
        var consumed = length + 1
        while (consumed % pad != 0) {
            consumed++
            pos++
        }
        return String(bytes, Charsets.ISO_8859_1)
    }
}

/** Growable big-endian writer with back-patching, used to build PSD files. */
class Blob(initial: Int = 8192) {
    private val out = ByteArrayOutputStream(initial)

    val size: Int get() = out.size()

    fun byte(value: Int) { out.write(value and 0xFF) }
    fun raw(bytes: ByteArray) { out.write(bytes, 0, bytes.size) }
    fun raw(bytes: ByteArray, offset: Int, length: Int) { out.write(bytes, offset, length) }
    fun short(value: Int) { byte(value ushr 8); byte(value) }
    fun int(value: Int) { byte(value ushr 24); byte(value ushr 16); byte(value ushr 8); byte(value) }
    fun u32(value: Long) { int((value and 0xFFFFFFFFL).toInt()) }
    fun long(value: Long) { int((value ushr 32).toInt()); int(value.toInt()) }
    fun f32(value: Float) = int(value.toBits())
    fun f64(value: Double) = long(value.toBits())
    fun fourCc(value: String) {
        for (i in 0 until 4) byte(if (i < value.length) value[i].code else ' '.code)
    }

    fun zeros(count: Int) { repeat(count) { byte(0) } }

    /** 4-byte length + UTF-16BE characters (PSD's Unicode strings). */
    fun unicodeString(value: String) {
        int(value.length)
        raw(value.toByteArray(StandardCharsets.UTF_16BE))
    }

    /** Length-prefixed ASCII id; Photoshop writes class ids the same way. */
    fun asciiId(value: String) {
        int(value.length)
        raw(value.toByteArray(StandardCharsets.US_ASCII))
    }

    fun pascalString(value: String, pad: Int = 2) {
        val bytes = value.toByteArray(Charsets.ISO_8859_1)
        val length = minOf(bytes.size, 255)
        byte(length)
        raw(bytes, 0, length)
        var consumed = length + 1
        while (consumed % pad != 0) {
            consumed++
            byte(0)
        }
    }

    /** Writes a placeholder that [patchInt] fills in once the length is known. */
    fun reserveInt(): Int {
        val at = out.size()
        int(0)
        return at
    }

    fun patchInt(offset: Int, value: Int) {
        val bytes = out.toByteArray()
        bytes[offset] = (value ushr 24).toByte()
        bytes[offset + 1] = (value ushr 16).toByte()
        bytes[offset + 2] = (value ushr 8).toByte()
        bytes[offset + 3] = value.toByte()
        reset(bytes)
    }

    /** Writes everything from `offset` in `blob` (used to stitch sections). */
    fun append(blob: Blob, offset: Int = 0) {
        val bytes = blob.toByteArray()
        out.write(bytes, offset, bytes.size - offset)
    }

    fun toByteArray(): ByteArray = out.toByteArray()

    private fun reset(bytes: ByteArray) {
        out.reset()
        out.write(bytes, 0, bytes.size)
    }
}
