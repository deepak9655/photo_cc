package com.photocc.app.core

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import kotlin.math.roundToInt

/**
 * Photoshop "descriptor" support.
 *
 * Descriptors are the typed dictionaries Photoshop stores inside layer records
 * (`TySh` text layers, smart objects, layer styles, ...). The engine reads them
 * into a small dynamic tree, lets the editor change the fields it owns (text
 * string, colour, transform) and writes them back in the layout Photoshop
 * expects, so a saved file stays openable by both Photoshop and other tools.
 */
object Desc {

    /** Reference — a pointer, not a dictionary. */
    const val OBJ = "obj "

    /** The type used for a nested descriptor (Photoshop reads `obj ` as a reference). */
    const val OBJC = "Objc"
    const val TEXT = "TEXT"
    const val ENUM = "enum"
    const val LONG = "long"
    const val DOUB = "doub"
    const val UNTF = "UntF"
    const val BOOL = "bool"
    const val VLLS = "VlLs"
    const val OBAR = "ObAr"
    const val ALIS = "alis"
    const val TDTA = "tdta"
    const val UNFL = "UnFl"
    const val PTH = "Pth "
    const val TYPE = "type"
    const val GLBC = "GlbC"
    const val INDX = "indx"

    sealed class Value

    /** A descriptor with its (optional) name and class id. */
    class Obj(
        var dict: Dict = Dict(),
        var name: String = "",
        var classId: String = "null",
    ) : Value()

    /** An ordered map — insertion order is preserved so writes stay byte-similar. */
    class Dict(val entries: MutableList<Pair<String, Value>> = mutableListOf()) : Value() {
        val size: Int get() = entries.size

        operator fun get(key: String): Value? = entries.firstOrNull { it.first == key }?.second

        operator fun set(key: String, value: Value) {
            val i = entries.indexOfFirst { it.first == key }
            if (i >= 0) entries[i] = key to value else entries.add(key to value)
        }

        fun remove(key: String) {
            entries.removeAll { it.first == key }
        }

        fun string(key: String): String? = (get(key) as? Text)?.value
        fun int(key: String): Int? = (get(key) as? IntValue)?.value

        fun double(key: String): Double? = when (val v = get(key)) {
            is DoubleValue -> v.value
            is IntValue -> v.value.toDouble()
            is UnitFloat -> v.value
            else -> null
        }

        fun bool(key: String): Boolean? = (get(key) as? BoolValue)?.value
        fun dict(key: String): Dict? = (get(key) as? Obj)?.dict ?: (get(key) as? Dict)
        fun obj(key: String): Obj? = get(key) as? Obj
        fun list(key: String): ListValue? = get(key) as? ListValue
        fun raw(key: String): Raw? = get(key) as? Raw

        fun copy(): Dict {
            val out = Dict()
            for ((k, v) in entries) out[k] = copyValue(v)
            return out
        }
    }

    class Text(var value: String) : Value()
    class IntValue(var value: Int) : Value()
    class DoubleValue(var value: Double) : Value()

    /** Number with a unit tag: `#Pnt`, `#Prc`, `#Ang`, `#Rsl`, `#Pxl`, `#Mlm`, `#Dens`, `#Nne`. */
    class UnitFloat(var unit: String, var value: Double) : Value()

    class BoolValue(var value: Boolean) : Value()
    class ListValue(var type: String = OBJ, var items: MutableList<Value> = mutableListOf()) : Value()

    /** A `obj ` reference: kept as an opaque marker, never interpreted. */
    object Reference : Value()

    /** enum: `typeID` + `enum` key. */
    class Enum(val typeId: String, val value: String) : Value()

    /** Anything not modelled keeps its exact bytes so nothing is lost. */
    class Raw(val kind: String, var data: ByteArray) : Value()

    fun copyValue(value: Value): Value = when (value) {
        is Reference -> Reference
        is Obj -> Obj(value.dict.copy(), value.name, value.classId)
        is Dict -> value.copy()
        is Text -> Text(value.value)
        is IntValue -> IntValue(value.value)
        is DoubleValue -> DoubleValue(value.value)
        is UnitFloat -> UnitFloat(value.unit, value.value)
        is BoolValue -> BoolValue(value.value)
        is ListValue -> ListValue(value.type, MutableList(value.items.size) { copyValue(value.items[it]) })
        is Enum -> Enum(value.typeId, value.value)
        is Raw -> Raw(value.kind, value.data.copyOf())
    }

    fun dictOf(vararg pairs: Pair<String, Value>): Dict {
        val d = Dict()
        for ((k, v) in pairs) d[k] = v
        return d
    }

    /* ------------------------------------------------------------- reading */

    class Reader(private val data: ByteArray, private var pos: Int = 0) {
        /** Set true (PHOTO_CC_DEBUG=1) to print every descriptor element while reading. */
        var trace: Boolean = System.getenv("PHOTO_CC_DEBUG") != null

        val position: Int get() = pos

        fun seek(to: Int) { pos = to }
        fun skip(count: Int) { pos += count }

        fun readFloat32(): Float = Float.fromBits(readInt())

        /** Version-prefixed descriptor (version must be 16). */
        fun readVersionAndDescriptor(): Obj {
            readInt()
            return readDescriptor()
        }

        fun readDescriptor(): Obj {
            val name = readUnicodeString().trimEnd('\u0000')
            val classId = readId() ?: "null"
            val declared = readInt()
            // A corrupt length must never turn into a multi-billion iteration loop.
            val count = declared.coerceIn(0, 4096)
            if (trace) println("    descriptor name='$name' class='$classId' count=$declared")
            val dict = Dict()
            var index = 0
            while (index < count && pos < data.size) {
                index++
                val key = readId() ?: break
                val value = readItem()
                if (trace) println("      key='$key' type=${value?.javaClass?.simpleName} pos=$pos")
                if (value != null) dict[key] = value
            }
            return Obj(dict, name, classId)
        }

        fun readItem(): Value? {
            val type = readFourCc() ?: return null
            return when (type) {
                OBJC, GLBC -> readDescriptor()
                OBJ -> {
                    // A reference, not a dictionary. Read it loosely: the editor has no
                    // use for the contents but must keep the stream aligned.
                    val count = readInt().coerceIn(0, 4096)
                    repeat(count) { readReferenceItem() }
                    Reference
                }
                TEXT -> Text(readUnicodeString().let { if (it.endsWith("\u0000")) it.dropLast(1) else it })
                ENUM -> {
                    val enumType = readId() ?: ""
                    val enumValue = readId() ?: ""
                    Enum(enumType, enumValue)
                }
                LONG -> IntValue(readInt())
                DOUB -> DoubleValue(readDouble())
                UNTF -> {
                    val unit = readFourCc() ?: ""
                    UnitFloat(unit, readDouble())
                }
                BOOL -> BoolValue(readByte().toInt() != 0)
                VLLS, OBAR -> {
                    val itemType = readFourCc() ?: OBJ
                    val count = readInt()
                    val list = ListValue(itemType)
                    repeat(count) {
                        val item = readItem()
                        if (item != null) list.items.add(item)
                    }
                    list
                }
                ALIS, PTH, TDTA, INDX, TYPE, UNFL -> {
                    val length = readInt().coerceAtLeast(0)
                    val available = (data.size - pos).coerceAtLeast(0)
                    val bytes = ByteArray(minOf(length, available))
                    for (i in bytes.indices) bytes[i] = readByte()
                    Raw(type, bytes)
                }
                else -> null
            }
        }

        /** Skips one `obj ` reference item, whatever flavour it is. */
        private fun readReferenceItem() {
            val kind = readFourCc() ?: return
            when (kind) {
                "prop" -> {
                    skipClassStructure()
                    readId()
                }
                "Clss" -> skipClassStructure()
                "Enmr" -> {
                    skipClassStructure()
                    readId()
                    readId()
                }
                "rele", "Idnt", "indx" -> readInt()
                "name" -> {
                    skipClassStructure()
                    readUnicodeString()
                }
                else -> {}
            }
        }

        private fun skipClassStructure() {
            readUnicodeString()
            readId()
        }

        fun readInt(): Int {
            val b0 = readByte().toInt() and 0xFF
            val b1 = readByte().toInt() and 0xFF
            val b2 = readByte().toInt() and 0xFF
            val b3 = readByte().toInt() and 0xFF
            return (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
        }

        fun readShort(): Int = ((readByte().toInt() and 0xFF) shl 8) or (readByte().toInt() and 0xFF)

        fun readByte(): Byte = data[pos++]

        fun readDouble(): Double = Double.fromBits(readLong())

        fun readLong(): Long {
            var value = 0L
            for (i in 0 until 8) value = (value shl 8) or (readByte().toLong() and 0xFF)
            return value
        }

        fun readUnicodeString(): String {
            val length = readInt()
            if (length <= 0) return ""
            if (length * 2 > data.size - pos) throw PsdFormatException("Descriptor string runs past the end of the block")
            val bytes = ByteArray(length * 2)
            for (i in bytes.indices) bytes[i] = readByte()
            return String(bytes, StandardCharsets.UTF_16BE)
        }

        /** Length-prefixed ASCII id; a zero length means a raw 4-character class id. */
        fun readId(): String? {
            val length = readInt()
            if (length == 0) return readFourCc()
            if (length < 0) return null
            if (length > data.size - pos) return null
            val bytes = ByteArray(length)
            for (i in bytes.indices) bytes[i] = readByte()
            return String(bytes, StandardCharsets.US_ASCII)
        }

        fun readFourCc(): String? {
            if (pos + 4 > data.size) return null
            val sb = StringBuilder(4)
            repeat(4) { sb.append(readByte().toInt().toChar()) }
            return sb.toString()
        }
    }

    /* ------------------------------------------------------------- writing */

    class Writer {
        private val out = ByteArrayOutputStream(1024)

        fun bytes(): ByteArray = out.toByteArray()

        /** Version 16 + descriptor, the wrapper every PSD descriptor uses. */
        fun writeVersionAndDescriptor(descriptor: Obj) {
            writeInt(16)
            writeDescriptor(descriptor)
        }

        fun writeDescriptor(obj: Obj) {
            writeUnicodeStringWithPadding(obj.name)
            writeId(obj.classId)
            writeInt(obj.dict.size)
            for ((key, value) in obj.dict.entries) {
                writeId(key)
                writeItem(value)
            }
        }

        fun writeItem(value: Value) {
            when (value) {
                is Reference -> {
                    writeFourCc(OBJ)
                    writeInt(0)
                }
                is Obj -> {
                    writeFourCc(OBJC)
                    writeDescriptor(value)
                }
                is Dict -> {
                    writeFourCc(OBJC)
                    writeDescriptor(Obj(value, "", "null"))
                }
                is Text -> {
                    writeFourCc(TEXT)
                    writeUnicodeString(value.value)
                }
                is Enum -> {
                    writeFourCc(ENUM)
                    writeId(value.typeId)
                    writeId(value.value)
                }
                is IntValue -> {
                    writeFourCc(LONG)
                    writeInt(value.value)
                }
                is DoubleValue -> {
                    writeFourCc(DOUB)
                    writeDouble(value.value)
                }
                is UnitFloat -> {
                    writeFourCc(UNTF)
                    writeFourCc(value.unit)
                    writeDouble(value.value)
                }
                is BoolValue -> {
                    writeFourCc(BOOL)
                    writeByte(if (value.value) 1 else 0)
                }
                is ListValue -> {
                    writeFourCc(VLLS)
                    writeFourCc(value.type)
                    writeInt(value.items.size)
                    for (item in value.items) writeItem(item)
                }
                is Raw -> {
                    writeFourCc(value.kind)
                    writeInt(value.data.size)
                    out.write(value.data)
                }
            }
        }

        fun writeInt(value: Int) {
            out.write(value ushr 24 and 0xFF)
            out.write(value ushr 16 and 0xFF)
            out.write(value ushr 8 and 0xFF)
            out.write(value and 0xFF)
        }

        fun writeShort(value: Int) {
            out.write(value ushr 8 and 0xFF)
            out.write(value and 0xFF)
        }

        fun writeByte(value: Int) { out.write(value and 0xFF) }

        fun writeFloat32(value: Float) = writeInt(value.toBits())

        fun writeDouble(value: Double) = writeLong(value.toBits())

        fun writeLong(value: Long) {
            for (i in 7 downTo 0) out.write(((value ushr (i * 8)) and 0xFF).toInt())
        }

        fun writeUnicodeString(value: String) {
            writeInt(value.length)
            out.write(value.toByteArray(StandardCharsets.UTF_16BE))
        }

        /** Class-structure names carry a trailing NUL, as Photoshop writes them. */
        fun writeUnicodeStringWithPadding(value: String) {
            writeInt(value.length + 1)
            out.write(value.toByteArray(StandardCharsets.UTF_16BE))
            out.write(0)
            out.write(0)
        }

        /**
         * Class ids and 4-character keys are written as a zero length followed by
         * the four raw characters — that is what Photoshop and every other writer
         * does, and readers rely on it to tell the two forms apart.
         */
        fun writeId(value: String) {
            if (value.length == 4 && value != "warp" && value != "time" && value != "hold" && value != "list") {
                writeInt(0)
                writeFourCc(value)
                return
            }
            writeInt(value.length)
            out.write(value.toByteArray(StandardCharsets.US_ASCII))
        }

        fun writeFourCc(value: String) {
            for (i in 0 until 4) out.write(if (i < value.length) value[i].code else ' '.code)
        }

        fun writeRawBytes(bytes: ByteArray) { out.write(bytes) }
    }

    /* ------------------------------------------------------------- text layer helpers */

    /** The `TySh` block: version, transform, text descriptor, warp, box floats. */
    fun writeTextLayerShy(layer: TextLayer, documentResolution: Double = 72.0): ByteArray {
        val writer = Writer()
        val geometry = TextGeometry.measure(layer)
        writer.writeShort(1)
        val transform = layer.transform
        val t = if (transform != null && transform.size >= 6) transform else doubleArrayOf(1.0, 0.0, 0.0, 1.0, layer.x.toDouble(), layer.y.toDouble())
        for (i in 0 until 6) writer.writeDouble(t[i])
        writer.writeShort(50)
        val descriptor = Obj(textDescriptorDict(layer), "", "TxLr")
        writer.writeVersionAndDescriptor(descriptor)
        writer.writeShort(1)
        writer.writeVersionAndDescriptor(Obj(warpDescriptor(layer), "", "warp"))
        writer.writeFloat32(layer.x)
        writer.writeFloat32(layer.y)
        writer.writeFloat32(layer.x + geometry.width)
        writer.writeFloat32(layer.y + geometry.height)
        return writer.bytes()
    }

    fun textDescriptorDict(layer: TextLayer): Dict {
        val style = layer.text
        val geometry = TextGeometry.measure(layer)
        val dict = Dict()
        dict["Txt "] = Text(style.text.replace(Regex("\r?\n"), "\r"))
        dict["textGridding"] = Enum("textGridding", "None")
        dict["Ornt"] = Enum("Ornt", if (layer.vertical) "Vrtc" else "Hrzn")
        dict["AntA"] = Enum("Annt", "antiAliasSharp")
        dict["bounds"] = boundsDict(layer.x.toDouble(), layer.y.toDouble(), layer.x + geometry.width.toDouble(), layer.y + geometry.height.toDouble())
        dict["boundingBox"] = boundsDict(layer.x.toDouble(), layer.y.toDouble(), layer.x + geometry.width.toDouble(), layer.y + geometry.height.toDouble())
        dict["TextIndex"] = IntValue(layer.textIndex)
        dict["EngineData"] = Raw(TDTA, EngineData.writeText(layer))
        return dict
    }

    private fun boundsDict(left: Double, top: Double, right: Double, bottom: Double): Obj {
        val d = Dict()
        d["Left"] = UnitFloat("#Pxl", left)
        d["Top "] = UnitFloat("#Pxl", top)
        d["Rght"] = UnitFloat("#Pxl", right)
        d["Btom"] = UnitFloat("#Pxl", bottom)
        return Obj(d, "", "bounds")
    }

    private fun warpDescriptor(layer: TextLayer): Dict {
        val d = Dict()
        val warp = layer.warp
        d["warpStyle"] = Enum("warpStyle", warp?.style ?: "warpNone")
        d["warpValue"] = DoubleValue(warp?.value ?: 0.0)
        d["warpPerspective"] = DoubleValue(warp?.perspective ?: 0.0)
        d["warpPerspectiveOther"] = DoubleValue(warp?.perspectiveOther ?: 0.0)
        // Photoshop stores the warp rotation as an orientation enum, not an angle.
        d["warpRotate"] = Enum("Ornt", if ((warp?.rotate ?: 0.0) != 0.0) "Vrtc" else "Hrzn")
        return d
    }

    fun colorDescriptor(color: Int): Dict {
        val d = Dict()
        d["Type"] = IntValue(1)
        val values = ListValue("doub")
        // Descriptor colours are RGBA with alpha *last*.
        values.items.add(DoubleValue(Argb.r(color) / 255.0))
        values.items.add(DoubleValue(Argb.g(color) / 255.0))
        values.items.add(DoubleValue(Argb.b(color) / 255.0))
        values.items.add(DoubleValue(Argb.a(color) / 255.0))
        d["Values"] = values
        return d
    }

    fun colorFromDescriptor(dict: Dict?): Int {
        if (dict == null) return Argb.pack(255, 255, 255, 255)
        val values = dict.list("Values") ?: return Argb.pack(255, 255, 255, 255)
        fun at(index: Int): Double = when (val v = values.items.getOrNull(index)) {
            is DoubleValue -> v.value
            is IntValue -> v.value.toDouble()
            else -> Double.NaN
        }
        val count = values.items.size
        if (count < 3) return Argb.pack(255, 255, 255, 255)
        fun channel(index: Int): Int {
            val raw = at(index)
            if (raw.isNaN()) return 255
            val scaled = if (raw <= 1.0) raw * 255.0 else raw
            return scaled.roundToInt().coerceIn(0, 255)
        }
        // 4 values: [r,g,b,a]; 5 values: [a,r,g,b] (CMYK-ish order used by some writers).
        return if (count >= 5) Argb.pack(channel(0), channel(1), channel(2), channel(3))
        else Argb.pack(if (count >= 4) channel(3) else 255, channel(0), channel(1), channel(2))
    }
}
