package com.photocc.app.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * photo_cc document model.
 *
 * Everything here is plain Kotlin/JVM — no `android.*` imports — so the whole
 * engine can be compiled, run and tested on a desktop JVM (and on the phone the
 * same code runs unchanged). Pixels are stored as **straight (non-premultiplied)
 * ARGB** ints, which is exactly what the PSD format stores, so no colour is lost
 * when converting between the file and the screen.
 */

/** Straight-alpha ARGB helper. 0xAARRGGBB, alpha is *not* premultiplied. */
object Argb {
    fun pack(a: Int, r: Int, g: Int, b: Int): Int =
        ((a and 0xFF) shl 24) or ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)

    fun a(c: Int): Int = (c ushr 24) and 0xFF
    fun r(c: Int): Int = (c ushr 16) and 0xFF
    fun g(c: Int): Int = (c ushr 8) and 0xFF
    fun b(c: Int): Int = c and 0xFF

    fun transparent(): Int = 0

    /** Straight-alpha source-over of `src` (with alpha scaled by `alphaScale` 0..255) onto `dst`. */
    fun over(dst: Int, src: Int, alphaScale: Int): Int {
        val sa = (((src ushr 24) and 0xFF) * alphaScale + 127) / 255
        if (sa == 0) return dst
        if (sa == 255) return src
        val da = (dst ushr 24) and 0xFF
        val outA = sa + (da * (255 - sa) + 127) / 255
        if (outA == 0) return 0
        val sr = (src ushr 16) and 0xFF
        val sg = (src ushr 8) and 0xFF
        val sb = src and 0xFF
        val dr = (dst ushr 16) and 0xFF
        val dg = (dst ushr 8) and 0xFF
        val db = dst and 0xFF
        val w = (da * (255 - sa) + 127) / 255
        val r = (sr * sa + dr * w) / outA
        val g = (sg * sa + dg * w) / outA
        val b = (sb * sa + db * w) / outA
        return pack(outA, r, g, b)
    }
}

enum class BlendMode(val psd: String, val key: String, val label: String) {
    NORMAL("normal", "norm", "Normal"),
    DISSOLVE("dissolve", "diss", "Dissolve"),
    DARKEN("darken", "dark", "Darken"),
    MULTIPLY("multiply", "mul ", "Multiply"),
    COLOR_BURN("color burn", "idiv", "Color Burn"),
    LINEAR_BURN("linear burn", "lbrn", "Linear Burn"),
    DARKER_COLOR("darker color", "dkCl", "Darker Color"),
    LIGHTEN("lighten", "lite", "Lighten"),
    SCREEN("screen", "scrn", "Screen"),
    COLOR_DODGE("color dodge", "div ", "Color Dodge"),
    LINEAR_DODGE("linear dodge", "lddg", "Linear Dodge (Add)"),
    LIGHTER_COLOR("lighter color", "lgCl", "Lighter Color"),
    OVERLAY("overlay", "over", "Overlay"),
    SOFT_LIGHT("soft light", "sLit", "Soft Light"),
    HARD_LIGHT("hard light", "hLit", "Hard Light"),
    VIVID_LIGHT("vivid light", "vLit", "Vivid Light"),
    LINEAR_LIGHT("linear light", "lLit", "Linear Light"),
    PIN_LIGHT("pin light", "pLit", "Pin Light"),
    HARD_MIX("hard mix", "hMix", "Hard Mix"),
    DIFFERENCE("difference", "diff", "Difference"),
    EXCLUSION("exclusion", "smud", "Exclusion"),
    SUBTRACT("subtract", "fsub", "Subtract"),
    DIVIDE("divide", "fdiv", "Divide"),
    HUE("hue", "hue ", "Hue"),
    SATURATION("saturation", "sat ", "Saturation"),
    COLOR("color", "colr", "Color"),
    LUMINOSITY("luminosity", "lum ", "Luminosity"),
    PASS_THROUGH("pass through", "pass", "Pass Through");

    companion object {
        fun fromPsd(name: String?): BlendMode {
            if (name == null) return NORMAL
            for (mode in entries) if (mode.psd.equals(name, ignoreCase = true)) return mode
            return NORMAL
        }

        fun byKey(key: String?): BlendMode? {
            if (key == null) return null
            for (mode in entries) if (mode.key == key) return mode
            return null
        }

        /** The modes this engine can reproduce exactly; the file keeps whatever it had regardless. */
        fun isExact(mode: BlendMode): Boolean = when (mode) {
            NORMAL, DISSOLVE, DARKEN, MULTIPLY, LIGHTEN, SCREEN, OVERLAY, DIFFERENCE,
            EXCLUSION, HARD_LIGHT, SOFT_LIGHT, COLOR_BURN, COLOR_DODGE, LINEAR_DODGE,
            DARKER_COLOR, LIGHTER_COLOR, LINEAR_BURN, VIVID_LIGHT, LINEAR_LIGHT,
            PIN_LIGHT, HARD_MIX, SUBTRACT, DIVIDE, PASS_THROUGH -> true
            // Hue/Saturation/Color/Luminosity need a colour-space round trip that
            // a phone cannot afford per pixel; they are previewed as Normal.
            HUE, SATURATION, COLOR, LUMINOSITY -> false
        }
    }
}

data class Rect(val x: Int, val y: Int, val w: Int, val h: Int) {
    val right: Int get() = x + w
    val bottom: Int get() = y + h
    val isEmpty: Boolean get() = w <= 0 || h <= 0

    fun union(other: Rect?): Rect {
        if (other == null || other.isEmpty) return this
        if (isEmpty) return other
        val x0 = min(x, other.x)
        val y0 = min(y, other.y)
        val x1 = max(right, other.right)
        val y1 = max(bottom, other.bottom)
        return Rect(x0, y0, x1 - x0, y1 - y0)
    }

    fun pad(amount: Int): Rect = Rect(x - amount, y - amount, w + amount * 2, h + amount * 2)

    fun intersects(other: Rect): Boolean =
        !isEmpty && !other.isEmpty && x < other.right && right > other.x && y < other.bottom && bottom > other.y

    fun intersect(other: Rect): Rect {
        val x0 = max(x, other.x)
        val y0 = max(y, other.y)
        val x1 = min(right, other.right)
        val y1 = min(bottom, other.bottom)
        return Rect(x0, y0, max(0, x1 - x0), max(0, y1 - y0))
    }

    companion object {
        val EMPTY = Rect(0, 0, 0, 0)

        fun ofPoints(x0: Float, y0: Float, x1: Float, y1: Float): Rect {
            val left = kotlin.math.floor(min(x0, x1)).toInt()
            val top = kotlin.math.floor(min(y0, y1)).toInt()
            val right = kotlin.math.ceil(max(x0, x1)).toInt()
            val bottom = kotlin.math.ceil(max(y0, y1)).toInt()
            return Rect(left, top, right - left, bottom - top)
        }
    }
}

/** A block of straight-alpha ARGB pixels positioned in document space. */
class Pixels(var width: Int, var height: Int, var data: IntArray) {
    init {
        require(data.size >= width * height) { "pixel buffer too small: ${data.size} < ${width * height}" }
    }

    companion object {
        fun empty(width: Int, height: Int) = Pixels(max(1, width), max(1, height), IntArray(max(1, width) * max(1, height)))
    }
}

private var idCounter = 0
fun newLayerId(prefix: String = "L"): String {
    idCounter++
    return "$prefix${idCounter.toString(36)}${System.nanoTime().toString(36).takeLast(4)}"
}

/** Blend modes we can read and write; anything unknown degrades to Normal. */
sealed class Layer {
    abstract var id: String
    abstract var name: String
    abstract var visible: Boolean
    abstract var opacity: Float
    abstract var blend: BlendMode
    abstract var locked: Boolean

    /** Bumped whenever the appearance changes; drives render caches. */
    abstract var rev: Int

    /**
     * The original PSD layer record's "additional layer info" blocks that this
     * engine does not model (layer styles, masks, metadata, vector paths, …).
     * They are written back untouched on save.
     */
    var extraInfo: MutableList<ExtraInfo> = mutableListOf()

    /** Set when the layer is preserved but cannot be drawn here (adjustment, smart object). */
    var unsupported: String? = null

    /** Shown in the layer properties sheet when something is approximated. */
    var note: String? = null

    /** Layer record flag/clipping bytes from the file, kept so saving does not change them. */
    var rawFlags: Int = 0
    var rawClipping: Int = 0

    /**
     * The rectangle the layer had in the file when we hold no pixels for it
     * (adjustment layers, smart objects). Written back so masks and styles stay
     * attached to the right area.
     */
    var preservedBounds: Rect? = null

    /** PSD `lyid`, kept so Photoshop's layer ids survive. */
    var sourceId: Int? = null

    fun touch() { rev++ }
}

data class ExtraInfo(val key: String, val data: ByteArray) {
    override fun equals(other: Any?): Boolean = other is ExtraInfo && other.key == key && other.data.contentEquals(data)
    override fun hashCode(): Int = key.hashCode() * 31 + data.contentHashCode()
}

class RasterLayer(
    override var id: String = newLayerId("R"),
    override var name: String = "Layer",
    override var visible: Boolean = true,
    override var opacity: Float = 1f,
    override var blend: BlendMode = BlendMode.NORMAL,
    override var locked: Boolean = false,
    override var rev: Int = 1,
    var x: Int = 0,
    var y: Int = 0,
    var pixels: Pixels? = null,
    var mask: MaskInfo? = null,
) : Layer() {
    val w: Int get() = pixels?.width ?: 0
    val h: Int get() = pixels?.height ?: 0
    val bounds: Rect get() = if (w > 0 && h > 0) Rect(x, y, w, h) else Rect.EMPTY
}

/** Layer mask carried along untouched (not editable yet, but never lost). */
class MaskInfo(var bounds: Rect, var data: ByteArray, var defaultColor: Int, var disabled: Boolean)

class TextData(
    var text: String = "Text",
    var fontFamily: String = "sans-serif",
    var psdFontName: String = "ArialMT",
    var psdFontScript: Int = 0,
    var size: Float = 48f,
    var color: Int = Argb.pack(255, 255, 255, 255),
    var align: Align = Align.LEFT,
    /** PSD tracking in 1/1000 em. */
    var tracking: Int = 0,
    /** Baseline-to-baseline distance; null means automatic (1.2 × size). */
    var leading: Float? = null,
    var fauxBold: Boolean = false,
    var fauxItalic: Boolean = false,
    var underline: Boolean = false,
) {
    enum class Align(val psd: Int, val label: String) {
        LEFT(0, "Left"), RIGHT(1, "Right"), CENTER(2, "Centre");

        companion object {
            fun fromPsd(value: Int?): Align = when (value) {
                1 -> RIGHT
                2 -> CENTER
                else -> LEFT
            }
        }
    }

    fun copyOf(): TextData = TextData(
        text, fontFamily, psdFontName, psdFontScript, size, color, align, tracking,
        leading, fauxBold, fauxItalic, underline,
    )
}

/** Warp (text on a curve) — preserved and written back, not editable yet. */
class WarpData(
    var style: String = "warpNone",
    var value: Double = 0.0,
    var perspective: Double = 0.0,
    var perspectiveOther: Double = 0.0,
    var rotate: Double = 0.0,
)

class TextStyle(
    var enabled: Boolean = false,
    var color: Int = Argb.pack(255, 255, 255, 255),
    var width: Float = 2f,
)

class ShadowStyle(
    var enabled: Boolean = false,
    var color: Int = Argb.pack(200, 0, 0, 0),
    var blur: Float = 8f,
    var dx: Float = 3f,
    var dy: Float = 3f,
)

class TextLayer(
    override var id: String = newLayerId("T"),
    override var name: String = "Text",
    override var visible: Boolean = true,
    override var opacity: Float = 1f,
    override var blend: BlendMode = BlendMode.NORMAL,
    override var locked: Boolean = false,
    override var rev: Int = 1,
    var x: Float = 0f,
    var y: Float = 0f,
    var text: TextData = TextData(),
    var stroke: TextStyle = TextStyle(),
    var shadow: ShadowStyle = ShadowStyle(),
) : Layer() {
    /** The width the text block had in the file (`right - left`), used for alignment. */
    var sourceBoxWidth: Float? = null
    /** `index` from the PSD text record — required for a faithful re-write. */
    var textIndex: Int = 0
    /** TySh transform (6 doubles) as found in the file. */
    var transform: DoubleArray? = null
    var vertical: Boolean = false
    var warp: WarpData? = null
    /** True once the user edited this text, so the writer regenerates the blocks. */
    var textDirty: Boolean = false
    /** Original `TySh` bytes: written back verbatim while the text is untouched. */
    var originalTySh: ByteArray? = null
    /** Original `Txt2` bytes, used when only some fields changed. */
    var engineData: ByteArray? = null
    /** Engine `AntiAlias` value from the file. */
    var antiAlias: Int? = null
    /** Bounds as Photoshop recorded them, kept when the text is untouched. */
    var sourceBounds: Rect? = null
}

class GroupLayer(
    override var id: String = newLayerId("G"),
    override var name: String = "Group",
    override var visible: Boolean = true,
    override var opacity: Float = 1f,
    override var blend: BlendMode = BlendMode.NORMAL,
    override var locked: Boolean = false,
    override var rev: Int = 1,
    var opened: Boolean = true,
    var children: MutableList<Layer> = mutableListOf(),
) : Layer()

class PsdDocument(
    var width: Int,
    var height: Int,
    /** Bottom-most layer first, exactly like PSD layer records. */
    var children: MutableList<Layer> = mutableListOf(),
) {
    var fileName: String = "untitled.psd"
    var outlineName: String = "untitled-edited.psd"
    var sourceBytes: Long = 0
    var dirty: Boolean = false
    /** Colour mode / bit depth of the file we opened, so the UI can be honest. */
    var sourceColorMode: Int = 3
    var sourceBitDepth: Int = 8
    var notes: MutableList<String> = mutableListOf()

    /** Image resource section, kept byte-for-byte (colour profile, guides, metadata). */
    var imageResourcesRaw: ByteArray = ByteArray(0)
    /** Colour mode data section (palettes for indexed files). */
    var colorModeDataRaw: ByteArray = ByteArray(0)
    /** Global layer mask info, kept as-is. */
    var globalLayerMaskRaw: ByteArray = ByteArray(0)

    fun walkLayers(visit: (Layer, List<GroupLayer>) -> Unit) = walk(children, emptyList(), visit)

    private fun walk(list: List<Layer>, parents: List<GroupLayer>, visit: (Layer, List<GroupLayer>) -> Unit) {
        for (layer in list) {
            visit(layer, parents)
            if (layer is GroupLayer) walk(layer.children, parents + layer, visit)
        }
    }

    fun findLayer(id: String): Layer? {
        var found: Layer? = null
        walkLayers { layer, _ -> if (layer.id == id) found = layer }
        return found
    }

    fun layerCount(): Int {
        var count = 0
        walkLayers { _, _ -> count++ }
        return count
    }
}

/** Where a layer sits inside the tree (parent group + index). */
data class LayerPath(val list: MutableList<Layer>, val index: Int, val parent: GroupLayer?) {
    val layer: Layer get() = list[index]
}

fun findPath(doc: PsdDocument, id: String): LayerPath? {
    fun search(list: MutableList<Layer>, parent: GroupLayer?): LayerPath? {
        for (i in list.indices) {
            if (list[i].id == id) return LayerPath(list, i, parent)
            val layer = list[i]
            if (layer is GroupLayer) {
                val found = search(layer.children, layer)
                if (found != null) return found
            }
        }
        return null
    }
    return search(doc.children, null)
}

fun insertLayer(doc: PsdDocument, layer: Layer, parentId: String?, index: Int) {
    val parent = if (parentId != null) doc.findLayer(parentId) as? GroupLayer else null
    val target = parent?.children ?: doc.children
    val at = index.coerceIn(0, target.size)
    target.add(at, layer)
}

fun removeLayer(doc: PsdDocument, id: String): Layer? {
    val path = findPath(doc, id) ?: return null
    return path.list.removeAt(path.index)
}

/** Content rectangle of a layer, including effect padding for text. */
fun layerBounds(layer: Layer): Rect? = when (layer) {
    is RasterLayer -> layer.bounds.takeIf { !it.isEmpty }
    is TextLayer -> TextGeometry.measure(layer).bounds
    is GroupLayer -> {
        var rect: Rect? = null
        for (child in layer.children) {
            val b = layerBounds(child) ?: continue
            rect = if (rect == null) b else rect.union(b)
        }
        rect
    }
}

fun nearlyEqual(a: Float, b: Float, eps: Float = 0.001f): Boolean = abs(a - b) < eps
