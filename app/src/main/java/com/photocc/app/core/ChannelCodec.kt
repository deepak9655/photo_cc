package com.photocc.app.core

import java.io.ByteArrayOutputStream
import java.util.zip.Inflater

/**
 * PSD channel (de)compression. Photoshop stores every channel as raw bytes,
 * PackBits ("RLE") or zlib ("ZIP", "ZIP with prediction"); the merged image may
 * additionally be RLE-compressed. Only the codecs a phone can afford are here,
 * and all of them are byte-oriented so they cost no per-pixel objects.
 */
object ChannelCodec {

    /** PackBits / RLE. `out` must be `w * h` bytes. */
    fun decodeRle(data: ByteArray, offset: Int, width: Int, height: Int, out: ByteArray) {
        // Skip the scanline length table (2 bytes per row, big-endian).
        var pos = offset + height * 2
        var written = 0
        val total = width * height
        while (written < total && pos < data.size) {
            val n = data[pos].toInt()
            pos++
            if (n >= 0) {
                val count = n + 1
                val end = minOf(pos + count, data.size)
                var i = pos
                while (i < end && written < total) out[written++] = data[i++]
                pos += count
            } else if (n != -128) {
                val count = 1 - n
                if (pos >= data.size) break
                val value = data[pos]
                pos++
                var i = 0
                while (i < count && written < total) {
                    out[written++] = value
                    i++
                }
            }
        }
    }

    /** zlib container. */
    fun inflate(data: ByteArray, offset: Int, length: Int, expected: Int): ByteArray {
        val inflater = Inflater()
        try {
            inflater.setInput(data, offset, length)
            val out = ByteArray(expected)
            var written = 0
            while (written < expected) {
                val n = inflater.inflate(out, written, expected - written)
                if (n == 0) {
                    if (inflater.finished() || inflater.needsInput() || inflater.needsDictionary()) break
                }
                written += n
            }
            return if (written == expected) out else out.copyOf(written)
        } finally {
            inflater.end()
        }
    }

    /**
     * ZIP + delta prediction for 8-bit channels: every sample is the difference
     * from its left neighbour, rows are independent.
     */
    fun undelta8(source: ByteArray, width: Int, height: Int, out: ByteArray) {
        var i = 0
        for (y in 0 until height) {
            var previous = 0
            for (x in 0 until width) {
                val value = if (i < source.size) (source[i].toInt() and 0xFF) else 0
                i++
                val decoded = (value + previous) and 0xFF
                if (y * width + x < out.size) out[y * width + x] = decoded.toByte()
                previous = decoded
            }
        }
    }

    /**
     * ZIP + delta prediction for 16-bit channels. Photoshop stores 16-bit samples
     * big-endian and delta-codes them against the left neighbour, so the delta is
     * applied to the big-endian value; results are handed over as 8-bit (the
     * editor works in 8-bit and says so when a 16-bit file is opened).
     */
    fun undelta16(source: ByteArray, width: Int, height: Int, out: ByteArray) {
        var offset = 0
        for (y in 0 until height) {
            var previous = 0
            var x = 0
            while (x < width) {
                val hi = if (offset < source.size) source[offset].toInt() and 0xFF else 0
                val lo = if (offset + 1 < source.size) source[offset + 1].toInt() and 0xFF else 0
                offset += 2
                val raw = (hi shl 8) or lo
                val decoded = (raw + previous) and 0xFFFF
                out[y * width + x] = (decoded ushr 8).toByte()
                previous = decoded
                x++
            }
        }
    }

    /** Encodes a single 8-bit channel as PackBits rows (Photoshop's "RLE"). */
    fun encodeRle(source: ByteArray, width: Int, height: Int): ByteArray {
        val rows = ArrayList<ByteArray>(height)
        val out = ByteArrayOutputStream(width * height / 2 + 64)
        val lengths = IntArray(height)
        for (y in 0 until height) {
            val rowStart = y * width
            val packed = packRow(source, rowStart, width)
            rows.add(packed)
            lengths[y] = packed.size
        }
        for (y in 0 until height) {
            out.write((lengths[y] ushr 8) and 0xFF)
            out.write(lengths[y] and 0xFF)
        }
        for (row in rows) out.write(row, 0, row.size)
        return out.toByteArray()
    }

    private fun packRow(source: ByteArray, start: Int, width: Int): ByteArray {
        val out = ByteArrayOutputStream(width + 8)
        var i = 0
        while (i < width) {
            // Count the run at i.
            var runLength = 1
            while (i + runLength < width && runLength < 128 && source[start + i + runLength] == source[start + i]) runLength++
            if (runLength >= 3) {
                out.write(1 - runLength) // negative: -1..-128
                out.write(source[start + i].toInt() and 0xFF)
                i += runLength
            } else {
                // Literal run: extends until a >= 3 long repeat starts.
                var literal = 0
                while (i + literal < width && literal < 128) {
                    var ahead = 1
                    while (i + literal + ahead < width && ahead < 3 &&
                        source[start + i + literal + ahead] == source[start + i + literal]
                    ) ahead++
                    if (ahead >= 3) break
                    literal++
                }
                if (literal == 0) literal = 1
                out.write(literal - 1)
                for (j in 0 until literal) out.write(source[start + i + j].toInt() and 0xFF)
                i += literal
            }
        }
        return out.toByteArray()
    }

    /** Wraps raw bytes in a zlib stream (Photoshop's "ZIP" option). */
    fun deflate(raw: ByteArray): ByteArray {
        val deflater = java.util.zip.Deflater(java.util.zip.Deflater.BEST_SPEED)
        try {
            deflater.setInput(raw)
            deflater.finish()
            val out = ByteArrayOutputStream(raw.size / 2 + 64)
            val buffer = ByteArray(16 * 1024)
            while (!deflater.finished()) {
                val n = deflater.deflate(buffer)
                if (n <= 0) break
                out.write(buffer, 0, n)
            }
            return out.toByteArray()
        } finally {
            deflater.end()
        }
    }
}
