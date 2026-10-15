package com.photocc.app.doc

import com.photocc.app.core.Argb
import com.photocc.app.core.BlendMode
import com.photocc.app.core.GroupLayer
import com.photocc.app.core.Layer
import com.photocc.app.core.MaskInfo
import com.photocc.app.core.Pixels
import com.photocc.app.core.PsdDocument
import com.photocc.app.core.PsdWrite
import com.photocc.app.core.RasterLayer
import com.photocc.app.core.Rect
import com.photocc.app.core.Render
import com.photocc.app.core.TextData
import com.photocc.app.core.TextLayer
import com.photocc.app.core.findPath
import com.photocc.app.core.layerBounds
import com.photocc.app.core.newLayerId

/**
 * Editor state: the open document, the selection, undo/redo and every editing
 * operation the UI can trigger.
 *
 * Deliberately free of `android.*` — the views call into it, and the JVM engine
 * self-test drives the same operations.
 *
 * Undo design (this is what keeps RAM flat on a phone):
 *  - **Pixel edits** never copy a whole layer. Only the 128 px cells an operation
 *    actually touches are captured, before the change.
 *  - **Structural edits** (add/delete/reorder/merge/…) clone the layer tree while
 *    *sharing* the pixel buffers, so a step costs a few hundred bytes.
 *  - The history is budgeted in bytes rather than steps, so one huge document
 *    cannot push the app out of memory.
 */
class Session(var doc: PsdDocument) {

    var selectedId: String? = null
        private set

    val history = History()

    private val listeners = ArrayList<() -> Unit>(2)

    /** Things the UI should explain about the loaded file (converted bit depth, …). */
    val notes: MutableList<String> get() = doc.notes

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun changed() {
        doc.dirty = true
        notifyListeners()
    }

    private fun notifyListeners() {
        for (listener in listeners) listener()
    }

    /* ------------------------------------------------------------- selection */

    val selected: Layer? get() = selectedId?.let { doc.findLayer(it) }

    fun select(id: String?) {
        if (selectedId == id) return
        selectedId = id
        notifyListeners()
    }

    /** Moves the selection up (offset +1) or down (offset -1) the layer stack. */
    fun selectNext(offset: Int) {
        val flat = ArrayList<Layer>()
        doc.walkLayers { layer, _ -> flat.add(layer) }
        if (flat.isEmpty()) {
            select(null)
            return
        }
        val index = flat.indexOfFirst { it.id == selectedId }
        val next = if (index < 0) 0 else (index + offset).mod(flat.size)
        select(flat[next].id)
    }

    /* ---------------------------------------------------------- new layers */

    fun addRasterLayer(
        name: String? = null,
        width: Int = doc.width,
        height: Int = doc.height,
        x: Int = 0,
        y: Int = 0,
        fill: Int = 0,
        pixels: Pixels? = null,
    ): RasterLayer {
        val buffer = pixels ?: Pixels.empty(width, height).also { target ->
            if (fill != 0) java.util.Arrays.fill(target.data, fill)
        }
        val layer = RasterLayer(name = name ?: defaultLayerName("Layer"), x = x, y = y, pixels = buffer)
        structural("New layer") { insertAbove(layer) }
        select(layer.id)
        return layer
    }

    fun addTextLayer(text: String, x: Float, y: Float, style: TextData = TextData()): TextLayer {
        val layer = TextLayer(
            name = text.lineSequence().firstOrNull()?.take(24)?.ifBlank { "Text" } ?: "Text",
            x = x,
            y = y,
            text = style.copyOf(),
        )
        layer.text.text = text
        layer.textDirty = true
        structural("New text layer") { insertAbove(layer) }
        select(layer.id)
        return layer
    }

    fun addGroup(): GroupLayer {
        val group = GroupLayer(name = defaultLayerName("Group"))
        structural("New group") { insertAbove(group) }
        select(group.id)
        return group
    }

    private fun defaultLayerName(prefix: String): String {
        val taken = HashSet<String>()
        doc.walkLayers { layer, _ -> taken.add(layer.name) }
        var index = 1
        while (taken.contains("$prefix $index")) index++
        return "$prefix $index"
    }

    /** Puts a new layer directly above the selected one, or on top of the document. */
    private fun insertAbove(layer: Layer) {
        val path = selectedId?.let { findPath(doc, it) }
        if (path == null) {
            doc.children.add(layer)
        } else {
            path.list.add(path.index + 1, layer)
        }
    }

    /* -------------------------------------------------------- layer commands */

    fun deleteSelected(): Boolean {
        val path = selectedId?.let { findPath(doc, it) } ?: return false
        val flat = ArrayList<Layer>()
        doc.walkLayers { layer, _ -> flat.add(layer) }
        val index = flat.indexOfFirst { it.id == path.layer.id }
        structural("Delete layer") { path.list.removeAt(path.index) }
        val remaining = ArrayList<Layer>()
        doc.walkLayers { layer, _ -> remaining.add(layer) }
        select(remaining.getOrNull(minOf(index, remaining.size - 1))?.id ?: remaining.lastOrNull()?.id)
        return true
    }

    fun duplicateSelected(): Boolean {
        val path = selectedId?.let { findPath(doc, it) } ?: return false
        val copy = cloneLayer(path.layer, freshIds = true)
        copy.name = path.layer.name + " copy"
        structural("Duplicate layer") { path.list.add(path.index + 1, copy) }
        select(copy.id)
        return true
    }

    /** Moves the selected layer up/down inside its own sibling list. */
    fun moveSelected(delta: Int): Boolean {
        val path = selectedId?.let { findPath(doc, it) } ?: return false
        val target = path.index + delta
        if (target < 0 || target >= path.list.size) return false
        structural(if (delta > 0) "Bring forward" else "Send backward") {
            val layer = path.list.removeAt(path.index)
            path.list.add(target, layer)
        }
        return true
    }

    /** Merge down: composites the selected layer into the one below it. */
    fun mergeDown(): Boolean {
        val path = selectedId?.let { findPath(doc, it) } ?: return false
        if (path.index == 0) return false
        val below = path.list[path.index - 1]
        val top = path.list[path.index]
        if (below is GroupLayer || top is GroupLayer) return false
        if (below is TextLayer) return false
        var merged: RasterLayer? = null
        structural("Merge down") {
            merged = mergePair(top, below)
            path.list[path.index - 1] = merged!!
            path.list.removeAt(path.index)
        }
        select(merged?.id)
        return true
    }

    /** Composites two layers into one raster layer using the canvas compositor. */
    private fun mergePair(top: Layer, bottom: Layer): RasterLayer {
        val topBounds = layerBounds(top)
        val bottomBounds = layerBounds(bottom)
        val union = when {
            topBounds == null -> bottomBounds ?: Rect(0, 0, 1, 1)
            bottomBounds == null -> topBounds
            else -> topBounds.union(bottomBounds)
        }
        val area = if (union.isEmpty) Rect(0, 0, 1, 1) else union
        val buffer = IntArray(area.w * area.h)
        Render.composite(listOf(bottom, top), area, buffer)
        return RasterLayer(
            name = bottom.name,
            visible = bottom.visible,
            opacity = 1f,
            blend = BlendMode.NORMAL,
            x = area.x,
            y = area.y,
            pixels = Pixels(area.w, area.h, buffer),
        )
    }

    /** Flattens the document into a single raster layer. */
    fun flatten(): Boolean {
        if (doc.children.isEmpty()) return false
        val buffer = IntArray(doc.width * doc.height)
        Render.flatten(doc, Rect(0, 0, doc.width, doc.height), buffer)
        val layer = RasterLayer(
            name = "Background",
            x = 0,
            y = 0,
            pixels = Pixels(doc.width, doc.height, buffer),
        )
        structural("Flatten image") {
            doc.children.clear()
            doc.children.add(layer)
        }
        select(layer.id)
        return true
    }

    /* ------------------------------------------------------------ properties */

    fun setVisible(id: String, visible: Boolean) = editProperty(id, "Visibility") { it.visible = visible }

    fun setLocked(id: String, locked: Boolean) = editProperty(id, "Lock") { it.locked = locked }

    fun setOpacity(id: String, opacity: Float) = editProperty(id, "Opacity") { it.opacity = opacity.coerceIn(0f, 1f) }

    /** Opacity while a slider is being dragged: no history entry per pixel. */
    fun setOpacityLive(id: String, opacity: Float) {
        val layer = doc.findLayer(id) ?: return
        layer.opacity = opacity.coerceIn(0f, 1f)
        doc.dirty = true
        notifyListeners()
    }

    fun setBlend(id: String, mode: BlendMode) = editProperty(id, "Blend mode") { it.blend = mode }

    fun rename(id: String, name: String) = editProperty(id, "Rename") { it.name = name.ifBlank { it.name } }

    private fun editProperty(id: String, label: String, apply: (Layer) -> Unit): Boolean {
        val layer = doc.findLayer(id) ?: return false
        structural(label) { apply(layer) }
        return true
    }

    /**
     * Applies a text change *without* recording history, for live previews while
     * a dialog is open. Wrap the whole dialog in [beginStructuralEdit] /
     * [endStructuralEdit] so the edit still becomes one undo step.
     */
    fun updateTextLive(id: String, apply: (TextLayer) -> Unit) {
        val layer = doc.findLayer(id) as? TextLayer ?: return
        apply(layer)
        layer.textDirty = true
        doc.dirty = true
        notifyListeners()
    }

    /**
     * Applies a text change. Setting the string marks the type blocks dirty so
     * the writer regenerates both the descriptor and the engine data.
     */
    fun editText(id: String, apply: (TextLayer) -> Unit): Boolean {
        val layer = doc.findLayer(id) as? TextLayer ?: return false
        structural("Edit text") {
            apply(layer)
            layer.textDirty = true
        }
        return true
    }

    /* ---------------------------------------------------------------- pixels */

    fun invertLayer(id: String): Boolean {
        val layer = doc.findLayer(id) as? RasterLayer ?: return false
        val pixels = layer.pixels ?: return false
        val edit = PixelEdit(layer)
        edit.before(Rect(layer.x, layer.y, pixels.width, pixels.height))
        for (i in pixels.data.indices) {
            val value = pixels.data[i]
            pixels.data[i] = Argb.pack(Argb.a(value), 255 - Argb.r(value), 255 - Argb.g(value), 255 - Argb.b(value))
        }
        commitPixelEdit(edit, "Invert")
        return true
    }

    fun clearLayer(id: String): Boolean {
        val layer = doc.findLayer(id) as? RasterLayer ?: return false
        val pixels = layer.pixels ?: return false
        val edit = PixelEdit(layer)
        edit.before(Rect(layer.x, layer.y, pixels.width, pixels.height))
        java.util.Arrays.fill(pixels.data, 0)
        commitPixelEdit(edit, "Clear layer")
        return true
    }

    /** Crops every layer to `region` (document space) and resizes the canvas. */
    fun crop(region: Rect): Boolean {
        val rect = region.intersect(Rect(0, 0, doc.width, doc.height))
        if (rect.isEmpty || (rect.w == doc.width && rect.h == doc.height)) return false
        structural("Crop") {
            doc.width = rect.w
            doc.height = rect.h
            shiftLayers(doc.children, rect)
        }
        return true
    }

    private fun shiftLayers(list: MutableList<Layer>, rect: Rect) {
        for (layer in list) {
            when (layer) {
                is RasterLayer -> {
                    val pixels = layer.pixels
                    if (pixels != null) {
                        val keep = Rect(layer.x, layer.y, pixels.width, pixels.height).intersect(rect)
                        if (keep.isEmpty) {
                            layer.pixels = null
                        } else {
                            val copy = IntArray(keep.w * keep.h)
                            for (y in 0 until keep.h) {
                                System.arraycopy(
                                    pixels.data,
                                    (keep.y - layer.y + y) * pixels.width + (keep.x - layer.x),
                                    copy,
                                    y * keep.w,
                                    keep.w,
                                )
                            }
                            layer.pixels = Pixels(keep.w, keep.h, copy)
                            layer.x = keep.x - rect.x
                            layer.y = keep.y - rect.y
                        }
                    } else {
                        layer.x -= rect.x
                        layer.y -= rect.y
                    }
                    layer.mask?.let { mask ->
                        val keep = mask.bounds.intersect(rect)
                        layer.mask = if (keep.isEmpty) {
                            null
                        } else {
                            MaskInfo(Rect(keep.x - rect.x, keep.y - rect.y, keep.w, keep.h), mask.data, mask.defaultColor, mask.disabled)
                        }
                    }
                }

                is TextLayer -> {
                    layer.x -= rect.x
                    layer.y -= rect.y
                    layer.textDirty = true
                }

                is GroupLayer -> shiftLayers(layer.children, rect)
            }
        }
    }

    /* --------------------------------------------------------------- history */

    /** Runs a structural edit and records it as a single undo step. */
    private fun structural(label: String, block: () -> Unit) {
        val before = snapshot()
        val selectedBefore = selectedId
        block()
        history.add(StructureChange(before, snapshot(), selectedBefore, selectedId, label))
        changed()
    }

    /**
     * A structural edit that spans several gestures (dragging a layer around):
     * capture before, then close the step when the gesture ends.
     */
    private var pendingBefore: StructureChange.Snapshot? = null
    private var pendingSelection: String? = null
    private var pendingLabel: String = "Edit"

    fun beginStructuralEdit(label: String = "Edit") {
        pendingBefore = snapshot()
        pendingSelection = selectedId
        pendingLabel = label
    }

    /** Closes a [beginStructuralEdit] group; a no-op when nothing was opened. */
    fun endStructuralEdit(label: String? = null) {
        val before = pendingBefore ?: return
        pendingBefore = null
        history.add(StructureChange(before, snapshot(), pendingSelection, selectedId, label ?: pendingLabel))
        changed()
    }

    private fun snapshot(): StructureChange.Snapshot =
        StructureChange.Snapshot(doc.children.mapTo(ArrayList()) { cloneLayer(it, freshIds = false) }, doc.width, doc.height)

    fun commitPixelEdit(edit: PixelEdit, label: String): Boolean {
        val change = edit.finish(label)
        if (change == null) {
            changed()
            return false
        }
        history.add(change)
        changed()
        return true
    }

    fun undo(): Boolean = history.popUndo(this) != null

    fun redo(): Boolean = history.popRedo(this) != null

    val canUndo: Boolean get() = history.canUndo

    val canRedo: Boolean get() = history.canRedo

    /* ----------------------------------------------------------------- save */

    fun saveToBytes(): ByteArray = PsdWrite.write(doc, PsdWrite.Options())

    val title: String get() = doc.fileName

    /** Name suggested when saving: "myphoto-edited.psd". */
    val savedFileName: String get() = doc.outlineName

    /** One-line summary for the info sheet. */
    fun describe(): String {
        val sb = StringBuilder()
        sb.append(doc.width).append(" × ").append(doc.height).append(" px")
        sb.append("  ·  ").append(doc.layerCount()).append(" layers")
        sb.append("  ·  ").append(if (doc.sourceBitDepth == 8) "8-bit RGB" else "${doc.sourceBitDepth}-bit → 8-bit RGB")
        return sb.toString()
    }
}

/* ---------------------------------------------------------------------- undo */

/** A bounded, byte-budgeted undo/redo stack. */
class History(private val budgetBytes: Long = 96L * 1024 * 1024) {

    private val undoStack = ArrayDeque<Change>()
    private val redoStack = ArrayDeque<Change>()
    private var bytes = 0L

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    fun add(change: Change) {
        while (redoStack.isNotEmpty()) bytes -= redoStack.removeLast().sizeBytes()
        undoStack.addLast(change)
        bytes += change.sizeBytes()
        while (bytes > budgetBytes && undoStack.size > 1) {
            bytes -= undoStack.removeFirst().sizeBytes()
        }
    }

    fun popUndo(session: Session): Change? {
        if (undoStack.isEmpty()) return null
        val change = undoStack.removeLast()
        bytes -= change.sizeBytes()
        change.undo(session)
        redoStack.addLast(change)
        return change
    }

    fun popRedo(session: Session): Change? {
        if (redoStack.isEmpty()) return null
        val change = redoStack.removeLast()
        change.redo(session)
        undoStack.addLast(change)
        bytes += change.sizeBytes()
        return change
    }

    /** Label of the step that would be undone, for the UI. */
    val undoLabel: String? get() = (undoStack.lastOrNull() as? Labelled)?.label
    val redoLabel: String? get() = (redoStack.lastOrNull() as? Labelled)?.label
}

interface Labelled {
    val label: String
}

interface Change {
    fun undo(session: Session)
    fun redo(session: Session)
    fun sizeBytes(): Long
}

/**
 * A pixel edit. `before(rect)` must be called *before* the rect is modified;
 * only the first call for a 128 px cell keeps data, so overlapping brush stamps
 * inside one stroke stay cheap.
 */
class PixelEdit(private val layer: RasterLayer) {

    private val cell = 128
    private val saved = LinkedHashMap<Long, IntArray>()
    private var union: Rect? = null

    fun before(rect: Rect) {
        val pixels = layer.pixels ?: return
        val area = rect.intersect(Rect(layer.x, layer.y, pixels.width, pixels.height))
        if (area.isEmpty) return
        union = union?.union(area) ?: area
        var cy = area.y / cell
        while (cy <= (area.bottom - 1) / cell) {
            var cx = area.x / cell
            while (cx <= (area.right - 1) / cell) {
                val key = (cy.toLong() shl 32) or (cx.toLong() and 0xFFFFFFFFL)
                if (!saved.containsKey(key)) saved[key] = copyCell(pixels, cx, cy)
                cx++
            }
            cy++
        }
    }

    private fun copyCell(pixels: Pixels, cx: Int, cy: Int): IntArray {
        val x0 = (cx * cell).coerceAtLeast(0)
        val y0 = (cy * cell).coerceAtLeast(0)
        val x1 = ((cx + 1) * cell).coerceAtMost(pixels.width)
        val y1 = ((cy + 1) * cell).coerceAtMost(pixels.height)
        val w = (x1 - x0).coerceAtLeast(0)
        val h = (y1 - y0).coerceAtLeast(0)
        val out = IntArray(w * h)
        for (y in 0 until h) {
            System.arraycopy(pixels.data, (y0 + y) * pixels.width + x0, out, y * w, w)
        }
        return out
    }

    /** Builds the undo step, or null when nothing was touched. */
    fun finish(label: String): Change? {
        val pixels = layer.pixels ?: return null
        val rect = union?.intersect(Rect(layer.x, layer.y, pixels.width, pixels.height)) ?: return null
        if (rect.isEmpty) return null
        val before = IntArray(rect.w * rect.h)
        val after = IntArray(rect.w * rect.h)
        for (y in 0 until rect.h) {
            val row = (rect.y - layer.y + y) * pixels.width + (rect.x - layer.x)
            System.arraycopy(pixels.data, row, after, y * rect.w, rect.w)
            for (x in 0 until rect.w) {
                before[y * rect.w + x] = savedValue(pixels, rect.x + x, rect.y + y)
                    ?: after[y * rect.w + x]
            }
        }
        return PixelsChange(layer, pixels, rect, before, after, label, layer.name)
    }

    private fun savedValue(pixels: Pixels, docX: Int, docY: Int): Int? {
        val cx = docX / cell
        val cy = docY / cell
        val cellData = saved[(cy.toLong() shl 32) or (cx.toLong() and 0xFFFFFFFFL)] ?: return null
        val originX = (cx * cell).coerceAtLeast(0)
        val originY = (cy * cell).coerceAtLeast(0)
        val width = (((cx + 1) * cell).coerceAtMost(pixels.width) - originX).coerceAtLeast(0)
        val index = (docY - originY) * width + (docX - originX)
        return if (index in cellData.indices) cellData[index] else null
    }
}

/** Undo step for a pixel edit: only the touched rectangle is stored. */
class PixelsChange(
    private val layer: RasterLayer,
    private val pixels: Pixels,
    private val rect: Rect,
    private val before: IntArray,
    private val after: IntArray,
    override val label: String,
    private val layerName: String,
) : Change, Labelled {

    override fun undo(session: Session) = apply(session, before)

    override fun redo(session: Session) = apply(session, after)

    private fun apply(session: Session, data: IntArray) {
        // If the layer was replaced (crop, merge, flatten) the buffers no longer
        // line up: the step is skipped rather than corrupting the image.
        val target = session.doc.findLayer(layer.id) as? RasterLayer ?: return
        if (target.pixels !== pixels) return
        for (y in 0 until rect.h) {
            val index = (rect.y - layer.y + y) * pixels.width + (rect.x - layer.x)
            if (index < 0 || index + rect.w > pixels.data.size) continue
            System.arraycopy(data, y * rect.w, pixels.data, index, rect.w)
        }
        target.touch()
    }

    override fun sizeBytes(): Long = (before.size + after.size).toLong() * 4

    override fun toString(): String = "$label ($layerName)"
}

/** Undo step for a structural edit: two cheap tree snapshots. */
class StructureChange(
    private val before: Snapshot,
    private val after: Snapshot,
    private val selectedBefore: String?,
    private val selectedAfter: String?,
    override val label: String,
) : Change, Labelled {

    class Snapshot(val children: MutableList<Layer>, val width: Int, val height: Int)

    override fun undo(session: Session) {
        before.apply(session)
        session.select(selectedBefore)
    }

    override fun redo(session: Session) {
        after.apply(session)
        session.select(selectedAfter)
    }

    private fun Snapshot.apply(session: Session) {
        // Clone on restore: the snapshots stay valid for a later undo/redo even
        // though the editor keeps editing the restored tree (pixels are shared).
        session.doc.children = children.mapTo(ArrayList()) { cloneLayer(it, freshIds = false) }
        session.doc.width = width
        session.doc.height = height
    }

    override fun sizeBytes(): Long = 2048

    override fun toString(): String = label
}

/**
 * The one place layers are copied.
 *
 * `freshIds = false` is the undo-snapshot clone: ids and pixel buffers are shared
 * (pixel edits are tracked separately by [PixelsChange]), `freshIds = true` is a
 * real duplicate with its own buffers.
 */
private fun cloneLayer(layer: Layer, freshIds: Boolean): Layer = when (layer) {
    is RasterLayer -> RasterLayer(
        id = if (freshIds) newLayerId("R") else layer.id,
        name = layer.name,
        visible = layer.visible,
        opacity = layer.opacity,
        blend = layer.blend,
        locked = layer.locked,
        x = layer.x,
        y = layer.y,
        pixels = if (freshIds) layer.pixels?.let { Pixels(it.width, it.height, it.data.copyOf()) } else layer.pixels,
        mask = if (freshIds) layer.mask?.let { MaskInfo(it.bounds, it.data.copyOf(), it.defaultColor, it.disabled) } else layer.mask,
    ).also { copy ->
        copy.extraInfo = layer.extraInfo
        copy.unsupported = layer.unsupported
        copy.note = layer.note
        copy.rawFlags = layer.rawFlags
        copy.rawClipping = layer.rawClipping
        copy.preservedBounds = layer.preservedBounds
        copy.sourceId = layer.sourceId
    }

    is TextLayer -> TextLayer(
        id = if (freshIds) newLayerId("T") else layer.id,
        name = layer.name,
        visible = layer.visible,
        opacity = layer.opacity,
        blend = layer.blend,
        locked = layer.locked,
        x = layer.x,
        y = layer.y,
        // always copied: a snapshot must not see a later text edit
        text = layer.text.copyOf(),
    ).also { copy ->
        copy.textDirty = layer.textDirty || freshIds
        copy.sourceBoxWidth = layer.sourceBoxWidth
        copy.textIndex = layer.textIndex
        copy.transform = layer.transform
        copy.vertical = layer.vertical
        copy.warp = layer.warp
        copy.textDirty = layer.textDirty
        copy.originalTySh = layer.originalTySh
        copy.engineData = layer.engineData
        copy.antiAlias = layer.antiAlias
        copy.sourceBounds = layer.sourceBounds
        copy.extraInfo = layer.extraInfo
        copy.unsupported = layer.unsupported
        copy.note = layer.note
        copy.rawFlags = layer.rawFlags
        copy.rawClipping = layer.rawClipping
        copy.preservedBounds = layer.preservedBounds
        copy.sourceId = layer.sourceId
    }

    is GroupLayer -> GroupLayer(
        id = if (freshIds) newLayerId("G") else layer.id,
        name = layer.name,
        visible = layer.visible,
        opacity = layer.opacity,
        blend = layer.blend,
        locked = layer.locked,
        opened = layer.opened,
    ).also { group ->
        for (child in layer.children) group.children.add(cloneLayer(child, freshIds = false))
        group.extraInfo = layer.extraInfo
        group.unsupported = layer.unsupported
        group.note = layer.note
        group.rawFlags = layer.rawFlags
        group.rawClipping = layer.rawClipping
        group.preservedBounds = layer.preservedBounds
        group.sourceId = layer.sourceId
    }

    else -> layer
}
