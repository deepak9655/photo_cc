package com.strata.editor.core

/**
 * A layer.
 *
 * Everything visible is a layer: pixels, a text object, a solid colour, or a
 * group of other layers. A layer carries its own transform, blend mode, opacity
 * and mask, and its *content* is either raster ([pixels]) or a description the
 * renderer runs on demand ([kind], see `Adjustment`).
 */
class Layer(
    var name: String = "Layer",
    var kind: LayerKind = LayerKind.RASTER,
) {
    /** Stable identity: selections, history and the layer panel refer to this. */
    var id: Long = nextId++

    var visible: Boolean = true
    var opacity: Float = 1f
    var blend: BlendMode = BlendMode.NORMAL
    var locked: Boolean = false
    var clipping: Boolean = false

    /** Position of the layer's own pixel grid inside the document, before `transform`. */
    var x: Int = 0
    var y: Int = 0

    /** Rotation / scale / flip, applied about the layer's own centre. */
    var transform: Mat3? = null

    /** Raster content. Null for groups, text without pixels, and empty layers. */
    var pixels: Pixmap? = null

    /** Per-pixel opacity, 0 = hidden, 255 = shown. Same size as [pixels]. */
    var mask: Pixmap? = null

    /** Group members. Only meaningful when this layer is a group. */
    val children: MutableList<Layer> = mutableListOf()

    /** True for a group that should be isolated from what is under it. */
    var expanded: Boolean = true

    /**
     * Size of a solid or adjustment layer's area. Adjustments default to the whole
     * document (set when the layer is created), solids to whatever the user drew.
     */
    var contentWidth: Int = 0
    var contentHeight: Int = 0

    /** Non-raster content: adjustments, text and solids keep their description here. */
    var adjustment: Adjustment? = null
    var text: TextContent? = null
    var solidColor: Int? = null

    /** Marks the layer as "this came from a file feature we only pass through". */
    var note: String? = null

    /** Additional-info blocks from the file, written back untouched. */
    val psdBlocks: MutableMap<String, ByteArray> = LinkedHashMap()

    /** The name exactly as the file spelled it (may differ from [name]). */
    var psdName: String? = null

    /** 0 = ordinary layer, 1 = open group, 2 = closed group, 3 = group end marker. */
    var psdSectionDivider: Int = 0

    /** The original text block, re-emitted unchanged while the text is not edited. */
    var psdTextBlock: ByteArray? = null

    /** Set by the editor when the text changes, so the old block is no longer reused. */
    var textEdited: Boolean = false

    /** Where the text is laid out on the canvas, and the transform applied to it. */
    var textLayout: Rect = Rect.EMPTY
    var textTransform: Mat3? = null

    /** Where a mask sits in document space (the mask buffer is the layer's size). */
    var maskBounds: Rect? = null

    val isGroup: Boolean get() = kind == LayerKind.GROUP
    val hasPixels: Boolean get() = pixels != null

    /** Bounds of the layer's content in document space, or null when it has none. */
    fun bounds(): Rect? {
        val base = pixels?.let { Rect(x, y, it.width, it.height) } ?: return null
        val t = transform ?: return base
        return t.mapRect(base)
    }

    /**
     * Where a layer without pixels draws: the document-size area an adjustment
     * covers, or the rectangle a solid fills.
     */
    fun areaBounds(): Rect? = when {
        text != null -> textLayout.takeIf { !it.isEmpty } ?: Rect(x, y, maxOf(1, contentWidth), maxOf(1, contentHeight))
        solidColor != null -> Rect(x, y, maxOf(1, contentWidth), maxOf(1, contentHeight))
        adjustment != null -> Rect(x, y, maxOf(1, contentWidth), maxOf(1, contentHeight))
        else -> null
    }

    /**
     * A deep-enough copy for history: the description is cloned, but the pixel
     * buffers are shared, because an edit that changes pixels already recorded the
     * pixels it touched.
     */
    fun shallowCopy(): Layer {
        val layer = Layer(name, kind)
        layer.id = id
        layer.visible = visible
        layer.opacity = opacity
        layer.blend = blend
        layer.locked = locked
        layer.clipping = clipping
        layer.x = x
        layer.y = y
        layer.transform = transform?.copy()
        layer.contentWidth = contentWidth
        layer.contentHeight = contentHeight
        layer.pixels = pixels
        layer.mask = mask
        layer.adjustment = adjustment?.copyOf()
        layer.text = text?.copyOf()
        layer.solidColor = solidColor
        layer.note = note
        layer.psdName = psdName
        layer.psdSectionDivider = psdSectionDivider
        layer.psdTextBlock = psdTextBlock
        layer.textEdited = textEdited
        layer.textLayout = textLayout
        layer.textTransform = textTransform?.copy()
        layer.maskBounds = maskBounds
        psdBlocks.forEach { (key, block) -> layer.psdBlocks[key] = block }
        layer.expanded = expanded
        layer.children.addAll(children.map { it.shallowCopy() })
        return layer
    }

    /** A true deep copy, pixels included. Used by "duplicate layer". */
    fun deepCopy(): Layer {
        val layer = shallowCopy()
        layer.id = nextId++
        layer.pixels = pixels?.copy()
        layer.mask = mask?.copy()
        remapIds(layer)
        return layer
    }

    private fun remapIds(layer: Layer) {
        layer.id = nextId++
        layer.children.forEach { remapIds(it) }
    }

    companion object {
        private var nextId: Long = 1
        fun newId(): Long = nextId++
    }
}

enum class LayerKind { RASTER, GROUP, TEXT, SOLID, ADJUSTMENT }

/** How a group combines with what is beneath it. */
enum class GroupBlend { PASS_THROUGH, ISOLATED }

/** Editable text, kept as data so it stays sharp and stays editable. */
class TextContent(
    var text: String = "",
    var fontFamily: String = "sans-serif",
    var fontSize: Float = 48f,
    var bold: Boolean = false,
    var italic: Boolean = false,
    var alignment: TextAlign = TextAlign.LEFT,
    var tracking: Float = 0f,
    var leading: Float = 1.2f,
    var color: Int = Colors.WHITE,
) {
    fun copyOf(): TextContent = TextContent(text, fontFamily, fontSize, bold, italic, alignment, tracking, leading, color)
}

enum class TextAlign { LEFT, CENTER, RIGHT }

/**
 * A document: a canvas plus a stack of layers, bottom-first in [layers].
 */
class Document(val width: Int, val height: Int) {

    val layers: MutableList<Layer> = mutableListOf()

    /** The undo stack for this document. */
    val history: History = History()

    /** The active selection, or null when nothing is selected (everything is writable). */
    var selection: Selection? = null

    /** Background colour of the canvas, shown where every layer is transparent. */
    var background: Int = 0

    /** Bits per channel the file was loaded at; saves are always 8-bit. */
    var bitDepth: Int = 8

    var fileName: String = "untitled.psd"
    var dirty: Boolean = false

    /** Features found in the source file that the editor preserves but cannot draw. */
    val preserved: MutableList<String> = mutableListOf()

    /** The real pixel size of the file this document came from. */
    var sourceWidth: Int = 0
    var sourceHeight: Int = 0

    /** Raw image-resources section from the source file, re-emitted on save. */
    var resources: ByteArray? = null

    /** Global layer mask info from the source file, re-emitted on save. */
    var globalMask: ByteArray? = null

    /** Extra top-level additional-info blocks from the source file. */
    var extraLayerInfo: ByteArray? = null

    /** How the file was written: channel count, bit depth and version. */
    var sourceChannels: Int = 4
    var stride: Int = 1

    val bounds: Rect get() = Rect(0, 0, width, height)

    /** A selection covering `rect`, or null when it covers the whole canvas. */
    fun selectionOrNull(rect: Rect): Selection? {
        val selection = selection ?: return null
        if (rect.contains(selection.bounds)) return selection
        return selection
    }

    fun add(layer: Layer, index: Int? = null) {
        if (index != null) layers.add(index.coerceIn(0, layers.size), layer) else layers.add(layer)
        dirty = true
    }

    fun remove(layer: Layer): Boolean = layers.remove(layer).also { if (it) dirty = true }

    fun contains(layer: Layer): Boolean {
        if (layers.contains(layer)) return true
        return layers.any { containsIn(it, layer) }
    }

    private fun containsIn(parent: Layer, target: Layer): Boolean =
        parent.children.contains(target) || parent.children.any { containsIn(it, target) }

    fun findBy(id: Long): Layer? {
        fun search(items: List<Layer>): Layer? {
            for (item in items) {
                if (item.id == id) return item
                search(item.children)?.let { return it }
            }
            return null
        }
        return search(layers)
    }

    /** The parent group of a layer, or null when it lives at the document root. */
    fun parentOf(layer: Layer): Layer? {
        fun search(items: List<Layer>, parent: Layer?): Layer? {
            for (item in items) {
                if (item === layer) return parent
                search(item.children, item)?.let { return it }
            }
            return null
        }
        return search(layers, null)
    }

    /** The list a layer lives in, which is where it can be reordered or removed. */
    fun siblingsOf(layer: Layer): MutableList<Layer> = parentOf(layer)?.children ?: layers

    /** All layers, depth-first, top of the stack first — the order a panel shows. */
    fun flattenTopFirst(): List<Pair<Layer, Int>> {
        val out = ArrayList<Pair<Layer, Int>>()
        fun walk(items: List<Layer>, depth: Int) {
            for (item in items.asReversed()) {
                out.add(item to depth)
                if (item.isGroup) walk(item.children, depth + 1)
            }
        }
        walk(layers, 0)
        return out
    }

    /**
     * Every raster layer, mapped to its document-space offset, in bottom-first
     * paint order. Groups are flattened (their children inherit the group's
     * opacity and blend by being painted inside the group's buffer, but for the
     * common case this flat walk is enough — see `Compositor` for the general case).
     */
    fun visibleRasterLayers(): List<Layer> = layers.filter { it.visible }

    /** A flat copy of the whole document: what "flatten image" produces. */
    fun flattened(background: Int = 0): Pixmap {
        val out = Pixmap(width, height)
        Compositor.render(this, out, background)
        return out
    }

    /** Records a structural change: call [apply], and the tree is snapshotted around it. */
    fun structural(label: String, apply: () -> Unit) {
        val before = layers.map { it.shallowCopy() }
        val selectionBefore = selection?.copyOf()
        apply()
        val after = layers.map { it.shallowCopy() }
        history.push(History.StructureEntry(label, before, after, selectionBefore, selection?.copyOf()))
        dirty = true
    }
}
