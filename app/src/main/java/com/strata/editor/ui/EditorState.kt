package com.strata.editor.ui

import android.graphics.Bitmap
import android.net.Uri
import com.strata.editor.core.Adjustment
import com.strata.editor.core.BlendMode
import com.strata.editor.core.Colors
import com.strata.editor.core.Compositor
import com.strata.editor.core.Document
import com.strata.editor.core.Filters
import com.strata.editor.core.Gradients
import com.strata.editor.core.History
import com.strata.editor.core.ImageFormat
import com.strata.editor.core.ImageIo
import com.strata.editor.core.Layer
import com.strata.editor.core.LayerKind
import com.strata.editor.core.LayerRenderer
import com.strata.editor.core.Painter
import com.strata.editor.core.Pixmap
import com.strata.editor.core.PsdFile
import com.strata.editor.core.Rect
import com.strata.editor.core.Selection
import com.strata.editor.core.TextAlign
import com.strata.editor.core.TextContent
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Everything the editor is doing right now: the document, what is selected, which
 * tool is up, and the undo stack.
 *
 * The view layer never touches the document directly — it calls these operations,
 * which keep the history, the selection and the thumbnails in step. Every edit is
 * therefore one function here, which is what makes undo reliable. Everything runs
 * on the main thread; only reading and writing files happens elsewhere.
 */
class EditorState {

    var document: Document = Document(1080, 1350)
        private set

    var fileName: String = "untitled.psd"
    var fileUri: Uri? = null
    var fileIsPsd: Boolean = true

    val options = ToolOptions()
    var tool: Tool = Tool.BRUSH
    var panel: Panel = Panel.NONE

    var primary: Int = Colors.BLACK
    var secondary: Int = Colors.WHITE
    /** The last few colours used, newest first — the swatch row under the wells. */
    val recentColours = ArrayList<Int>()

    /** Bumped whenever anything that affects the drawing changes. */
    var revision: Int = 0
        private set

    var status: String = "Ready"
    var statusIsError: Boolean = false
    var busy: String? = null

    /** The layer the panels and every tool act on. */
    var activeLayer: Layer? = null
        private set(value) {
            field = value
            layerSelection = listOfNotNull(value)
            if (value != null) options.layerOpacity = value.opacity
        }

    /** Multi-select is supported so a few layers can be dragged or deleted together. */
    var layerSelection: List<Layer> = emptyList()
        private set

    /** Set while a tool is mid-gesture, so the view can draw the right overlay. */
    var pendingRect: Rect? = null
    var pendingHandle: Int = -1
    var textTarget: Layer? = null
    var textDraft: TextContent? = null
    var adjustmentTarget: Layer? = null

    /** Called when the canvas needs redrawing; the damage rect keeps it cheap. */
    var onInvalidate: ((Rect?) -> Unit)? = null
    var onThumbnailsChanged: (() -> Unit)? = null
    var onPanelsChanged: (() -> Unit)? = null
    /** Ask the view to fit the document on screen (after opening a file, say). */
    var onFitRequest: (() -> Unit)? = null

    private val thumbnails = HashMap<Long, Bitmap>()
    private var openBuilder: History.PixelBuilder? = null
    private var liveLabel: String = "Change"

    /** How far the open panel is scrolled down. */
    var panelScroll: Float = 0f
    private var strokePainter: Painter? = null
    private var liveBefore: List<Layer>? = null
    private var liveSelectionBefore: Selection? = null

    init {
        document.selection = Selection(document.width, document.height)
        createFirstDocument()
    }

    /* ------------------------------------------------------------------ files */

    private fun createFirstDocument() {
        val created = Document(1080, 1350)
        val background = Layer("Background")
        background.pixels = Pixmap(created.width, created.height).also { it.fill(Colors.WHITE) }
        background.psdName = "Background"
        created.add(background)
        created.selection = Selection(created.width, created.height)
        adopt(created, "untitled.psd", null, true)
        document.history.clear()
    }

    fun newDocument(width: Int, height: Int, background: Int?, name: String = "untitled.psd") {
        val w = width.coerceIn(1, 30_000)
        val h = height.coerceIn(1, 30_000)
        val created = Document(w, h)
        if (background != null) {
            val layer = Layer("Background")
            layer.pixels = Pixmap(w, h).also { it.fill(background) }
            layer.psdName = "Background"
            created.add(layer)
        }
        created.selection = Selection(w, h)
        adopt(created, name, null, true)
        report("New document ${w}×${h}")
    }

    fun openBytes(bytes: ByteArray, name: String, uri: Uri?) {
        val isPsd = looksLikePsd(bytes)
        if (isPsd) {
            val opened = PsdFile.read(bytes, name, PsdFile.ReadOptions(maxPixels = PsdFile.MAX_PIXELS))
            adopt(opened, name, uri, true)
            report("Opened $name — ${opened.width}×${opened.height}, ${countLayers()} layers")
        } else {
            val result = ImageIo.decode(bytes, name)
            if (result == null) {
                report("That image could not be opened", error = true)
                return
            }
            val pixmap = result.pixmap
            val created = Document(pixmap.width, pixmap.height)
            val layer = Layer(name.substringBeforeLast('.').ifEmpty { "Image" })
            layer.pixels = pixmap
            layer.psdName = layer.name
            created.add(layer)
            created.sourceWidth = result.originalWidth
            created.sourceHeight = result.originalHeight
            created.selection = Selection(pixmap.width, pixmap.height)
            adopt(created, name.substringBeforeLast('.') + ".psd", uri, false)
            report("Opened $name as a new document")
        }
    }

    fun openFailed(message: String) = report(message, error = true)

    fun importAsLayer(bytes: ByteArray, name: String) {
        val layer = ImageIo.layerFrom(document, bytes, name)
        if (layer == null) {
            report("That image could not be imported", error = true)
            return
        }
        addLayer(layer, "Place image")
    }

    fun saveBytes(background: Int = Colors.WHITE): ByteArray? = try {
        val bytes = PsdFile.write(document, PsdFile.WriteOptions(writeMerged = true, background = background))
        document.dirty = false
        report("Saved $fileName (${bytes.size / 1024} kB)")
        onPanelsChanged?.invoke()
        bytes
    } catch (error: OutOfMemoryError) {
        report("Not enough memory to save this document as a PSD", error = true)
        null
    } catch (error: Exception) {
        report("Could not write the PSD: ${error.message}", error = true)
        null
    }

    fun exportBytes(format: ImageFormat): ByteArray? {
        val bytes = ImageIo.exportDocument(document, format)
        if (bytes == null) report("Export failed — no encoder for ${format.extension}", error = true)
        else report("Exported as ${format.extension.uppercase()} (${bytes.size / 1024} kB)")
        return bytes
    }

    fun exportedName(format: ImageFormat): String =
        fileName.substringBeforeLast('.', "untitled") + "." + format.extension

    private fun looksLikePsd(bytes: ByteArray): Boolean =
        bytes.size > 4 && bytes[0] == 0x38.toByte() && bytes[1] == 0x42.toByte() &&
            bytes[2] == 0x50.toByte() && bytes[3] == 0x53.toByte()

    private fun adopt(opened: Document, name: String, uri: Uri?, isPsd: Boolean) {
        document = opened
        if (opened.selection == null) opened.selection = Selection(opened.width, opened.height)
        fileName = name
        fileUri = uri
        fileIsPsd = isPsd
        opened.fileName = name
        opened.dirty = false
        openBuilder = null
        strokePainter = null
        thumbnails.clear()
        activeLayer = if (isPsd) topMostDrawable(opened.layers) else opened.layers.lastOrNull()
        pendingRect = null
        adjustColoursToDocument()
        onFitRequest?.invoke()
        onThumbnailsChanged?.invoke()
        onPanelsChanged?.invoke()
        invalidate(null)
    }

    private fun adjustColoursToDocument() {
        if (Colors.alpha(primary) == 0) primary = referenceInk()
        if (Colors.alpha(secondary) == 0) secondary = Colors.WHITE
    }

    /** Black on a light document, white on a dark one, so the default brush shows. */
    private fun referenceInk(): Int {
        val layer = document.layers.lastOrNull { it.pixels != null && it.visible } ?: return Colors.BLACK
        val pixels = layer.pixels ?: return Colors.BLACK
        var total = 0L
        var count = 0
        val stepX = max(1, pixels.width / 24)
        val stepY = max(1, pixels.height / 24)
        var y = 0
        while (y < pixels.height) {
            var x = 0
            while (x < pixels.width) {
                val colour = pixels[x, y]
                if (Colors.alpha(colour) > 8) {
                    total += Colors.luminance(colour)
                    count++
                }
                x += stepX
            }
            y += stepY
        }
        if (count == 0) return Colors.BLACK
        return if (total / count > 128) Colors.BLACK else Colors.WHITE
    }

    /* ------------------------------------------------------------------- view */

    fun selection(): Selection? = document.selection?.takeIf { it.isActive }

    fun hasSelection(): Boolean = document.selection?.isActive == true

    fun invalidate(rect: Rect?) {
        onInvalidate?.invoke(rect)
    }

    private fun changed(structure: Boolean, damage: Rect? = null) {
        document.dirty = true
        revision++
        if (structure) {
            // A structural step restores fresh copies of the layers, so anything
            // holding a layer — the panels, the active layer — has to be re-found
            // by id rather than by reference.
            val current = activeLayer
            activeLayer = if (current == null) topMostDrawable(document.layers)
            else document.findBy(current.id) ?: topMostDrawable(document.layers)
            layerSelection = layerSelection.mapNotNull { document.findBy(it.id) }.ifEmpty { listOfNotNull(activeLayer) }
            adjustmentTarget = adjustmentTarget?.let { target -> document.findBy(target.id) }
            textTarget = textTarget?.let { target -> document.findBy(target.id) }
            thumbnails.clear()
            onThumbnailsChanged?.invoke()
        }
        canvasDraftWidth = document.width
        canvasDraftHeight = document.height
        onPanelsChanged?.invoke()
        invalidate(damage)
    }

    fun report(message: String, error: Boolean = false) {
        status = message
        statusIsError = error
        revision++
        onPanelsChanged?.invoke()
    }

    /* ------------------------------------------------------------------ text */

    /** Opens the text panel on a layer, with a draft the panel can edit live. */
    fun openTextEditor(layer: Layer) {
        val content = layer.text ?: TextContent()
        textTarget = layer
        textDraft = content.copyOf()
        options.textSize = content.fontSize
        options.textBold = content.bold
        options.textItalic = content.italic
        options.textFont = content.fontFamily
        options.textAlign = when (content.alignment) {
            TextAlign.CENTER -> 1
            TextAlign.RIGHT -> 2
            else -> 0
        }
        panel = Panel.TEXT
        onPanelsChanged?.invoke()
    }

    fun closeTextEditor(commit: Boolean) {
        val layer = textTarget
        val draft = textDraft
        if (commit && layer != null && draft != null) {
            updateTextLayer(layer, draft)
        } else if (layer != null && layer.text == null && draft != null && draft.text.isBlank()) {
            deleteLayer(layer)
        }
        textTarget = null
        textDraft = null
        if (commit) report("Text updated")
        onPanelsChanged?.invoke()
    }

    /** The keyboard's text, straight into the layer. */
    fun setTextDraft(text: String) {
        val draft = textDraft ?: return
        if (draft.text == text) return
        draft.text = text
        previewTextDraft()
    }

    /** Applies the draft to the layer as the user types, without touching history. */
    fun previewTextDraft() {
        val layer = textTarget ?: return
        val draft = textDraft ?: return
        updateTextLayer(layer, draft, pushHistory = false)
    }

    /* ----------------------------------------------------------- live moves */

    /** Drags the active layer (or the whole selection of layers) by a delta. */
    fun moveLayerLive(dx: Int, dy: Int) {
        if (dx == 0 && dy == 0) return
        beginLiveEdit()
        val targets = layerSelection.ifEmpty { listOfNotNull(activeLayer) }
        for (layer in targets) {
            layer.x += dx
            layer.y += dy
            if (!layer.textLayout.isEmpty) {
                layer.textLayout = Rect(layer.textLayout.x + dx, layer.textLayout.y + dy, layer.textLayout.w, layer.textLayout.h)
            }
        }
        changed(structure = false)
    }

    fun reportBusy(message: String?) {
        busy = message
        onPanelsChanged?.invoke()
    }

    fun countLayers(): Int {
        fun count(items: List<Layer>): Int = items.sumOf { 1 + count(it.children) }
        return count(document.layers)
    }

    fun topMostDrawable(layers: List<Layer>): Layer? {
        for (layer in layers.asReversed()) {
            if (layer.isGroup) topMostDrawable(layer.children)?.let { return it }
            if (layer.pixels != null || layer.kind == LayerKind.TEXT || layer.kind == LayerKind.SOLID) return layer
        }
        return layers.lastOrNull()
    }

    fun selectLayer(layer: Layer, additive: Boolean = false) {
        layerSelection = when {
            !additive -> listOf(layer)
            layerSelection.any { it.id == layer.id } -> layerSelection.filter { it.id != layer.id }
            else -> layerSelection + layer
        }
        activeLayer = layerSelection.lastOrNull() ?: layer
        onPanelsChanged?.invoke()
        invalidate(null)
    }

    /* ------------------------------------------------------------- painting */

    /** Opens a stroke. The touched tiles are recorded as the work goes. */
    fun beginPaint(label: String): Boolean {
        val layer = activeLayer ?: return false
        val pixels = layer.pixels ?: return false
        if (pixels.width <= 0 || pixels.height <= 0) return false
        if (openBuilder != null) return true
        openBuilder = document.history.beginPixelEdit(layer, label)
        strokePainter = try {
            Painter(layer, selection())
        } catch (error: IllegalArgumentException) {
            report(error.message ?: "Cannot paint on that layer", error = true)
            openBuilder = null
            return false
        }
        return true
    }

    fun painter(): Painter? = strokePainter

    fun touchPaint(rect: Rect) {
        openBuilder?.touch(rect)
    }

    fun commitPaint() {
        val builder = openBuilder ?: return
        openBuilder = null
        strokePainter = null
        val touched = builder.touchedTiles > 0
        document.history.commit(builder)
        if (touched) changed(structure = false) else invalidate(null)
        onPanelsChanged?.invoke()
    }

    /** Puts the pixels back the way they were: for a cancelled stroke. */
    fun cancelPaint() {
        val builder = openBuilder ?: return
        openBuilder = null
        strokePainter = null
        document.history.rollback(builder)
        invalidate(null)
    }

    /** A one-shot pixel edit with its own undo step (filters, fills, adjustments). */
    fun editLayerPixels(label: String, damage: Rect? = null, block: (Layer, Pixmap) -> Unit) {
        val layer = activeLayer
        if (layer == null) {
            report("Select a layer first", error = true)
            return
        }
        val pixels = layer.pixels
        if (pixels == null) {
            report("${layer.name} has no pixels to edit", error = true)
            return
        }
        val before = pixels.copy()
        try {
            block(layer, pixels)
        } catch (error: OutOfMemoryError) {
            layer.pixels = before
            report("Not enough memory for that operation", error = true)
            return
        } catch (error: Exception) {
            layer.pixels = before
            report("That did not work: ${error.message}", error = true)
            return
        }
        document.history.push(History.BufferEntry(label, layer, before, pixels.copy()))
        thumbnails.remove(layer.id)
        changed(structure = false, damage = damage)
        report(label)
    }

    /* -------------------------------------------------------------- history */

    fun undo() {
        cancelOpenEdit()
        if (document.history.undo(document)) {
            changed(structure = true)
            report("Undo${document.history.redoLabel?.let { " — $it" } ?: ""}")
        } else {
            report("Nothing to undo")
        }
    }

    fun redo() {
        cancelOpenEdit()
        if (document.history.redo(document)) {
            changed(structure = true)
            report("Redo${document.history.undoLabel?.let { " — $it" } ?: ""}")
        } else {
            report("Nothing to redo")
        }
    }

    private fun cancelOpenEdit() {
        openBuilder?.let { document.history.rollback(it) }
        openBuilder = null
        strokePainter = null
    }

    val canUndo: Boolean get() = document.history.canUndo
    val canRedo: Boolean get() = document.history.canRedo

    /* ---------------------------------------------------------- live edits */

    /** Snapshots the tree so a dragged slider becomes one undo step on release. */
    fun beginLiveEdit(label: String = "Change") {
        if (liveBefore == null) {
            liveBefore = document.layers.map { it.shallowCopy() }
            liveSelectionBefore = document.selection?.copyOf()
            liveLabel = label
        }
    }

    fun endLiveEdit(label: String) {
        val before = liveBefore ?: return
        liveBefore = null
        val selectionBefore = liveSelectionBefore
        liveSelectionBefore = null
        val after = document.layers.map { it.shallowCopy() }
        if (before.map { it.id } == after.map { it.id } && selectionBefore == document.selection) {
            // Same tree, same selection: only pixels or properties changed, and the
            // caller has already pushed an entry for those.
            return
        }
        document.history.push(History.StructureEntry(label, before, after, selectionBefore, document.selection?.copyOf()))
        document.dirty = true
        onPanelsChanged?.invoke()
    }

    /**
     * Closes whatever live edit is open. The view calls this when a finger lifts,
     * so a slider the user has been dragging becomes exactly one undo step.
     */
    fun finishPendingEdits() {
        val before = liveBefore ?: return
        endLiveEdit(liveLabel)
    }

    /* --------------------------------------------------------------- layers */

    fun addLayer(layer: Layer, label: String = "New layer") {
        document.structural(label) {
            if (layer.psdName == null) layer.psdName = layer.name
            val reference = activeLayer
            val parent = reference?.let { document.parentOf(it) }
            val siblings = parent?.children ?: document.layers
            val index = reference?.let { siblings.indexOf(it) } ?: -1
            if (parent != null) {
                parent.children.add(if (index >= 0) index else parent.children.size, layer)
            } else {
                document.add(layer, if (index >= 0) index else null)
            }
            activeLayer = layer
        }
        thumbnails.clear()
        changed(structure = true)
        report(label)
    }

    fun newPixelLayer(): Layer {
        val layer = Layer("Layer ${countLayers() + 1}")
        layer.pixels = Pixmap(document.width, document.height)
        addLayer(layer, "New layer")
        return layer
    }

    fun newSolidLayer(colour: Int) {
        val layer = Layer("Colour fill")
        layer.kind = LayerKind.SOLID
        layer.solidColor = colour
        layer.contentWidth = document.width
        layer.contentHeight = document.height
        addLayer(layer, "Colour fill")
    }

    fun newAdjustmentLayer(adjustment: Adjustment, name: String = adjustment.title): Layer {
        val layer = Layer(name)
        layer.kind = LayerKind.ADJUSTMENT
        layer.adjustment = adjustment
        layer.contentWidth = document.width
        layer.contentHeight = document.height
        addLayer(layer, name)
        adjustmentTarget = layer
        return layer
    }

    fun newTextLayer(content: TextContent, layout: Rect): Layer {
        val layer = Layer(content.text.take(28).ifBlank { "Text" })
        layer.kind = LayerKind.TEXT
        layer.text = content
        layer.textLayout = layout
        layer.contentWidth = max(1, layout.w)
        layer.contentHeight = max(1, layout.h)
        addLayer(layer, "Text layer")
        return layer
    }

    fun duplicateLayer(layer: Layer? = activeLayer) {
        val source = layer ?: return
        val copy = source.deepCopy()
        copy.name = "${source.name} copy"
        copy.psdName = copy.name
        document.structural("Duplicate layer") {
            val siblings = document.siblingsOf(source)
            val index = siblings.indexOf(source)
            siblings.add(if (index >= 0) index + 1 else siblings.size, copy)
            activeLayer = copy
        }
        invalidate(null)
        changed(structure = true)
        report("Duplicated ${source.name}")
    }

    fun deleteLayer(layer: Layer? = activeLayer) {
        val target = layer ?: return
        if (countLayers() <= 1) {
            report("A document needs at least one layer", error = true)
            return
        }
        document.structural("Delete layer") {
            val parent = document.parentOf(target)
            val siblings = parent?.children ?: document.layers
            if (parent != null && parent.children.size <= 1) parent.children.remove(target)
            else siblings.remove(target)
            activeLayer = null
        }
        changed(structure = true)
        report("Deleted ${target.name}")
    }

    fun deleteSelectedLayers() {
        if (layerSelection.size <= 1) {
            deleteLayer()
            return
        }
        if (countLayers() - layerSelection.size < 1) {
            report("Keep at least one layer", error = true)
            return
        }
        document.structural("Delete layers") {
            layerSelection.forEach { target ->
                val siblings = document.siblingsOf(target)
                siblings.remove(target)
            }
            activeLayer = null
        }
        changed(structure = true)
        report("Deleted ${layerSelection.size} layers")
    }

    fun mergeDown(layer: Layer? = activeLayer) {
        val target = layer ?: return
        val siblings = document.siblingsOf(target)
        val index = siblings.indexOf(target)
        if (index <= 0) {
            report("Nothing below to merge into", error = true)
            return
        }
        val below = siblings[index - 1]
        if (below.isGroup || target.isGroup) {
            report("Groups cannot be merged this way", error = true)
            return
        }
        val merged = Pixmap(document.width, document.height)
        val before = below.pixels?.copy()
        document.structural("Merge down") {
            Compositor.compose(listOf(below, target), merged, document.bounds, 0, 0, 0)
            below.pixels = merged
            below.kind = LayerKind.RASTER
            below.text = null
            below.adjustment = null
            below.solidColor = null
            below.mask = null
            below.x = 0
            below.y = 0
            below.transform = null
            below.textLayout = Rect.EMPTY
            below.psdTextBlock = null
            siblings.removeAt(index)
            activeLayer = below
        }
        changed(structure = true)
        report("Merged ${target.name} into ${below.name}")
    }

    fun mergeVisible() {
        val flat = Pixmap(document.width, document.height)
        Compositor.flatten(document, flat, Colors.TRANSPARENT)
        document.structural("Merge visible") {
            val keep = Layer("Merged")
            keep.pixels = flat
            keep.psdName = keep.name
            document.layers.clear()
            document.layers.add(keep)
            activeLayer = keep
        }
        changed(structure = true)
        report("Merged visible layers")
    }

    fun flattenImage() {
        val flat = Pixmap(document.width, document.height)
        Compositor.flatten(document, flat, Colors.WHITE)
        document.structural("Flatten image") {
            val layer = Layer("Background")
            layer.pixels = flat
            layer.psdName = "Background"
            document.layers.clear()
            document.layers.add(layer)
            activeLayer = layer
            document.selection?.clear()
        }
        changed(structure = true)
        report("Flattened")
    }

    fun groupLayers() {
        val first = activeLayer ?: return
        val chosen = layerSelection.ifEmpty { listOf(first) }
        document.structural("Group layers") {
            val siblings = document.siblingsOf(first)
            val index = siblings.indexOf(first)
            val group = Layer(chosen.first().name + (if (chosen.size > 1) " group" else " group"))
            group.kind = LayerKind.GROUP
            group.children.addAll(chosen)
            chosen.forEach { siblings.remove(it) }
            siblings.add(index.coerceAtLeast(0), group)
            activeLayer = group
        }
        changed(structure = true)
        report("Grouped ${chosen.size} layer${if (chosen.size == 1) "" else "s"}")
    }

    fun ungroupLayers() {
        val group = activeLayer ?: return
        if (!group.isGroup) {
            report("That is not a group", error = true)
            return
        }
        document.structural("Ungroup") {
            val siblings = document.siblingsOf(group)
            val index = siblings.indexOf(group)
            val children = group.children.toList()
            group.children.clear()
            siblings.removeAt(index)
            siblings.addAll(index.coerceAtLeast(0), children)
            activeLayer = children.firstOrNull()
        }
        changed(structure = true)
        report("Ungrouped")
    }

    fun moveLayer(layer: Layer, delta: Int) {
        val siblings = document.siblingsOf(layer)
        val index = siblings.indexOf(layer)
        val target = index + delta
        if (index < 0 || target < 0 || target >= siblings.size) return
        document.structural(if (delta < 0) "Move layer down" else "Move layer up") {
            siblings.removeAt(index)
            siblings.add(target, layer)
        }
        changed(structure = true)
    }

    fun moveLayerToEdge(layer: Layer, top: Boolean) {
        val siblings = document.siblingsOf(layer)
        val index = siblings.indexOf(layer)
        if (index < 0) return
        document.structural(if (top) "Bring to front" else "Send to back") {
            siblings.removeAt(index)
            siblings.add(if (top) siblings.size else 0, layer)
        }
        changed(structure = true)
    }

    /** Moves a layer into a group (or back to the root) — for the layer panel. */
    fun reparentLayer(layer: Layer, parent: Layer?) {
        if (parent != null && (parent === layer || parent.children.any { it === layer } || isDescendant(parent, layer))) return
        document.structural("Move layer") {
            document.siblingsOf(layer).remove(layer)
            if (parent != null) parent.children.add(layer) else document.layers.add(layer)
            activeLayer = layer
        }
        changed(structure = true)
    }

    private fun isDescendant(candidate: Layer, ancestor: Layer): Boolean {
        var current = document.parentOf(candidate)
        while (current != null) {
            if (current === ancestor) return true
            current = document.parentOf(current)
        }
        return false
    }

    fun setLayerVisible(layer: Layer, visible: Boolean) {
        document.structural(if (visible) "Show layer" else "Hide layer") { layer.visible = visible }
        changed(structure = true)
    }

    fun setLayerOpacity(layer: Layer, opacity: Float) {
        layer.opacity = opacity.coerceIn(0f, 1f)
        changed(structure = false)
    }

    /** Steps through the blend modes in menu order, which is how a phone does it. */
    fun cycleBlend(layer: Layer) {
        val choices = Ui.blendChoices
        val index = choices.indexOf(layer.blend)
        setLayerBlend(layer, choices[(index + 1) % choices.size])
    }

    fun setLayerBlend(layer: Layer, mode: BlendMode) {
        document.structural("Blend mode") { layer.blend = mode }
        changed(structure = true)
        report("Blend mode: ${Ui.blendLabel(mode)}")
    }

    fun setLayerLocked(layer: Layer, locked: Boolean) {
        document.structural(if (locked) "Lock layer" else "Unlock layer") { layer.locked = locked }
        changed(structure = true)
    }

    fun setLayerClipping(layer: Layer, clipping: Boolean) {
        document.structural(if (clipping) "Clip to layer below" else "Stop clipping") { layer.clipping = clipping }
        changed(structure = true)
    }

    fun renameLayer(layer: Layer, name: String) {
        val trimmed = name.trim().ifEmpty { layer.name }
        document.structural("Rename layer") {
            layer.name = trimmed
            layer.psdName = trimmed
        }
        changed(structure = true)
    }

    fun offsetLayer(layer: Layer, dx: Int, dy: Int) {
        document.structural("Move layer") {
            layer.x += dx
            layer.y += dy
            if (layer.kind == LayerKind.TEXT && !layer.textLayout.isEmpty) {
                layer.textLayout = Rect(layer.textLayout.x + dx, layer.textLayout.y + dy, layer.textLayout.w, layer.textLayout.h)
            }
        }
        changed(structure = true)
    }

    /* ----------------------------------------------------------------- masks */

    fun addMaskFromSelection(layer: Layer? = activeLayer, hide: Boolean = false) {
        val target = layer ?: return
        document.structural("Add layer mask") {
            val mask = Pixmap(document.width, document.height)
            val selected = document.selection
            if (selected != null && selected.isActive) {
                for (y in 0 until mask.height) {
                    for (x in 0 until mask.width) {
                        var coverage = (selected.coverageAt(x, y) * 255f).toInt().coerceIn(0, 255)
                        if (hide) coverage = 255 - coverage
                        mask[x, y] = Colors.pack(255, coverage, coverage, coverage)
                    }
                }
            } else {
                mask.fill(Colors.WHITE)
            }
            target.mask = mask
        }
        changed(structure = true)
        report("Layer mask added")
    }

    fun removeMask(layer: Layer? = activeLayer) {
        val target = layer ?: return
        document.structural("Delete layer mask") { target.mask = null }
        changed(structure = true)
    }

    fun applyMask(layer: Layer? = activeLayer) {
        val target = layer ?: return
        val mask = target.mask ?: return
        val pixels = target.pixels
        if (pixels == null) {
            report("Nothing to apply the mask to", error = true)
            return
        }
        val before = pixels.copy()
        document.structural("Apply layer mask") {
            for (y in 0 until pixels.height) {
                for (x in 0 until pixels.width) {
                    val index = y * pixels.width + x
                    val coverage = if (x < mask.width && y < mask.height) Colors.red(mask[x, y]) else 255
                    val colour = pixels.pixels[index]
                    pixels.pixels[index] = Colors.withAlpha(colour, Colors.alpha(colour) * coverage / 255)
                }
            }
            target.mask = null
        }
        document.history.push(History.BufferEntry("Apply layer mask", target, before, pixels.copy()))
        changed(structure = true)
    }

    fun invertMask(layer: Layer? = activeLayer) {
        val target = layer ?: return
        val mask = target.mask ?: return
        val before = mask.copy()
        document.structural("Invert layer mask") {
            for (index in mask.pixels.indices) {
                val colour = mask.pixels[index]
                mask.pixels[index] = Colors.pack(255, 255 - Colors.red(colour), 255 - Colors.green(colour), 255 - Colors.blue(colour))
            }
        }
        document.history.push(History.BufferEntry("Invert layer mask", target, before, mask.copy()))
        changed(structure = true)
    }

    /* ------------------------------------------------------------ selection */

    fun setSelection(next: Selection?, label: String?) {
        val before = document.selection?.copyOf()
        document.selection = next ?: Selection(document.width, document.height)
        if (label != null) {
            document.history.push(SelectionEntry(label, before, document.selection?.copyOf()))
        }
        changed(structure = false)
        onPanelsChanged?.invoke()
    }

    fun combineSelection(next: Selection, label: String) {
        val op = when (options.selectionOp) {
            1 -> Selection.Op.ADD
            2 -> Selection.Op.SUBTRACT
            3 -> Selection.Op.INTERSECT
            else -> Selection.Op.REPLACE
        }
        val current = document.selection
        if (current == null || op == Selection.Op.REPLACE) {
            setSelection(next, label)
            return
        }
        val before = current.copyOf()
        current.combine(next, op)
        document.history.push(SelectionEntry(label, before, current.copyOf()))
        changed(structure = false)
        onPanelsChanged?.invoke()
    }

    fun selectAll() {
        val before = document.selection?.copyOf()
        document.selection?.selectAll()
        document.history.push(SelectionEntry("Select all", before, document.selection?.copyOf()))
        changed(structure = false)
        onPanelsChanged?.invoke()
        report("All pixels selected")
    }

    fun deselect() {
        if (!hasSelection()) return
        val before = document.selection?.copyOf()
        document.selection?.clear()
        document.history.push(SelectionEntry("Deselect", before, document.selection?.copyOf()))
        changed(structure = false)
        onPanelsChanged?.invoke()
        report("Deselected")
    }

    fun invertSelection() {
        val before = document.selection?.copyOf()
        val selection = document.selection ?: Selection(document.width, document.height).also { document.selection = it }
        selection.invert()
        document.history.push(SelectionEntry("Invert selection", before, selection.copyOf()))
        changed(structure = false)
        onPanelsChanged?.invoke()
        report("Selection inverted")
    }

    fun featherSelection(radius: Float) {
        val selection = document.selection ?: return
        if (!selection.isActive) {
            report("Nothing is selected", error = true)
            return
        }
        val before = selection.copyOf()
        selection.feather(radius)
        document.history.push(SelectionEntry("Feather selection", before, selection.copyOf()))
        changed(structure = false)
        onPanelsChanged?.invoke()
        report("Feathered by ${radius.toInt()} px")
    }

    fun growSelection(amount: Int) {
        val selection = document.selection ?: return
        if (!selection.isActive) {
            report("Nothing is selected", error = true)
            return
        }
        val before = selection.copyOf()
        selection.grow(amount)
        document.history.push(SelectionEntry(if (amount > 0) "Grow selection" else "Shrink selection", before, selection.copyOf()))
        changed(structure = false)
        onPanelsChanged?.invoke()
    }

    fun selectionBounds(): Rect? = document.selection?.takeIf { it.isActive }?.bounds

    private class SelectionEntry(
        override val label: String,
        private val before: Selection?,
        private val after: Selection?,
    ) : History.Entry {
        override val bytes: Int = 0
        override fun undo(doc: Document) = restore(doc, before)
        override fun redo(doc: Document) = restore(doc, after)
        private fun restore(doc: Document, source: Selection?) {
            if (source == null) {
                doc.selection?.clear()
                return
            }
            if (doc.selection == null || doc.selection?.width != source.width || doc.selection?.height != source.height) {
                doc.selection = source.copyOf()
            } else {
                doc.selection?.replaceWith(source)
            }
        }
    }

    /* ------------------------------------------------------------------ text */

    fun textContentOf(layer: Layer): TextContent? = layer.text

    /** Live text edit: the layout follows the content as it grows. */
    fun updateTextLayer(layer: Layer, content: TextContent, pushHistory: Boolean = true) {
        val measured = LayerRenderer.textRasterizer?.measure(content)
        if (pushHistory) document.structural("Edit text") {
            layer.text = content
            layer.textEdited = true
            applyMeasuredText(layer, content, measured)
        } else {
            layer.text = content
            layer.textEdited = true
            applyMeasuredText(layer, content, measured)
        }
        changed(structure = false)
    }

    private fun applyMeasuredText(layer: Layer, content: TextContent, measured: Rect?) {
        layer.contentWidth = max(1, measured?.w ?: layer.contentWidth)
        layer.contentHeight = max(1, measured?.h ?: layer.contentHeight)
        if (layer.textLayout.isEmpty) {
            layer.textLayout = Rect(layer.x, layer.y, layer.contentWidth, layer.contentHeight)
        } else {
            layer.textLayout = Rect(layer.textLayout.x, layer.textLayout.y, layer.contentWidth, layer.contentHeight)
        }
    }

    /* ----------------------------------------------------------- adjustments */

    fun applyAdjustmentToLayer(adjustment: Adjustment, label: String = adjustment.title) {
        val selection = selection()
        editLayerPixels(label) { _, pixels ->
            adjustment.apply(pixels, selection?.bounds ?: pixels.bounds)
            if (selection != null) applySelectionToAlpha(pixels, selection)
        }
    }

    /** Adjustment layers keep changing the pixels under them, so edits are live. */
    fun updateAdjustmentLayer(layer: Layer, adjustment: Adjustment) {
        val live = liveBefore != null
        if (!live) document.structural("Adjustment") { layer.adjustment = adjustment }
        else layer.adjustment = adjustment
        changed(structure = false)
    }

    private fun applySelectionToAlpha(pixels: Pixmap, selection: Selection) {
        for (y in 0 until pixels.height) {
            for (x in 0 until pixels.width) {
                val coverage = selection.coverageAt(x, y)
                if (coverage >= 0.999f) continue
                val index = y * pixels.width + x
                val colour = pixels.pixels[index]
                pixels.pixels[index] = Colors.withAlpha(colour, (Colors.alpha(colour) * coverage).toInt())
            }
        }
    }

    /* --------------------------------------------------------------- filters */

    /** The filter menu: name plus the call that runs it over the selected region. */
    fun filtersAvailable(): List<Pair<String, (Pixmap, Rect) -> Unit>> = listOf(
        "Blur" to { p, r -> Filters.blur(p, r, 2f + options.smoothStrength * 8f) },
        "Motion blur" to { p, r -> Filters.motionBlur(p, r, 24f, 0f) },
        "Zoom blur" to { p, r -> Filters.zoomBlur(p, r, 20f, r.x + r.w / 2f, r.y + r.h / 2f) },
        "Sharpen" to { p, r -> Filters.unsharpMask(p, r, 2f, 1.2f, 4) },
        "Edge detect" to { p, r -> Filters.edgeDetect(p, r, false) },
        "Emboss" to { p, r -> Filters.emboss(p, r, 1f) },
        "Add noise" to { p, r -> Filters.addNoise(p, r, 24f, false, System.nanoTime()) },
        "Film grain" to { p, r -> Filters.grain(p, r, 36f, System.nanoTime()) },
        "Mosaic" to { p, r -> Filters.mosaic(p, r, 12) },
        "Vignette" to { p, r -> Filters.vignette(p, r, 0.6f, 0.5f, 1f) },
        "Oil paint" to { p, r -> Filters.oilPaint(p, r, 3, 16) },
        "Glow" to { p, r -> Filters.glow(p, r, 12f, 0.5f) },
        "Crystalise" to { p, r -> Filters.crystalize(p, r, 8) },
        "Solarise" to { p, r -> Filters.solarize(p, r, 128) },
        "Sepia" to { p, r -> Filters.sepia(p, r, 1f) },
        "Fade" to { p, r -> Filters.fade(p, r, 0.35f) },
        "Lens distortion" to { p, r -> Filters.lensDistortion(p, r, 0.2f) },
        "Twirl" to { p, r -> Filters.twirl(p, r, 180f) },
        "Posterise hue" to { p, r -> Filters.posterizeHue(p, r, 8) },
    )

    /** Applies a filter inside the current selection, or the whole layer. */
    fun applyFilter(name: String, block: (Pixmap, Rect) -> Unit) {
        editLayerPixels(name) { _, pixels ->
            val area = selection()?.bounds?.intersect(pixels.bounds) ?: pixels.bounds
            block(pixels, area)
        }
    }

    /* ------------------------------------------------------------ transforms */

    fun flipCanvas(horizontal: Boolean) = transformCanvas(
        label = if (horizontal) "Flip horizontal" else "Flip vertical",
        clockwise = false,
        rotate = false,
        horizontal = horizontal,
        map = { pixels ->
            val out = Pixmap(pixels.width, pixels.height)
            for (y in 0 until pixels.height) {
                for (x in 0 until pixels.width) {
                    val sx = if (horizontal) pixels.width - 1 - x else x
                    val sy = if (horizontal) y else pixels.height - 1 - y
                    out.pixels[y * pixels.width + x] = pixels[sx, sy]
                }
            }
            out
        },
    )

    fun rotateCanvas(clockwise: Boolean) = transformCanvas(
        label = if (clockwise) "Rotate 90° clockwise" else "Rotate 90° counter-clockwise",
        clockwise = clockwise,
        rotate = true,
        horizontal = false,
        map = { pixels ->
            val out = Pixmap(pixels.height, pixels.width)
            for (y in 0 until pixels.height) {
                for (x in 0 until pixels.width) {
                    val colour = pixels[x, y]
                    if (clockwise) out.pixels[x * out.width + (pixels.height - 1 - y)] = colour
                    else out.pixels[(pixels.width - 1 - x) * out.width + y] = colour
                }
            }
            out
        },
    )

    /**
     * Runs a geometry change over every raster layer and rebuilds the canvas.
     * Text, solid and adjustment layers keep their own content: their bounds and
     * (for text) their layout rectangle are rotated with the canvas, so a flip or
     * a quarter turn never forces anything to be rasterised.
     */
    private fun transformCanvas(label: String, clockwise: Boolean, map: (Pixmap) -> Pixmap, rotate: Boolean, horizontal: Boolean) {
        val oldWidth = document.width
        val oldHeight = document.height
        val mapped = HashMap<Long, Pixmap>()
        val rotatedBounds = HashMap<Long, Rect>()
        for ((layer, _) in document.flattenTopFirst()) {
            val pixels = layer.pixels
            if (pixels != null) {
                mapped[layer.id] = map(pixels)
                continue
            }
            if (layer.kind == LayerKind.TEXT || layer.kind == LayerKind.SOLID || layer.kind == LayerKind.ADJUSTMENT) {
                val area = layer.areaBounds() ?: continue
                rotatedBounds[layer.id] = if (!rotate) area else if (clockwise) {
                    Rect(oldHeight - area.y - area.h, area.x, area.h, area.w)
                } else {
                    Rect(area.y, oldWidth - area.x - area.w, area.h, area.w)
                }
            }
        }
        val newWidth = if (rotate) oldHeight else oldWidth
        val newHeight = if (rotate) oldWidth else oldHeight
        document.structural(label) {
            for ((layer, _) in document.flattenTopFirst()) {
                val replacement = mapped[layer.id]
                if (replacement != null) {
                    layer.pixels = replacement
                    layer.x = 0
                    layer.y = 0
                } else {
                    val bounds = rotatedBounds[layer.id]
                    if (bounds != null) {
                        layer.x = bounds.x
                        layer.y = bounds.y
                        if (layer.kind == LayerKind.TEXT && !layer.textLayout.isEmpty) {
                            layer.textLayout = Rect(bounds.x, bounds.y, bounds.w, bounds.h)
                        }
                        if (layer.kind == LayerKind.ADJUSTMENT || layer.kind == LayerKind.SOLID) {
                            layer.contentWidth = max(1, bounds.w)
                            layer.contentHeight = max(1, bounds.h)
                        }
                    } else if (!rotate) {
                        val area = layer.areaBounds()
                        if (area != null) {
                            layer.x = if (horizontal) max(0, oldWidth - area.right) else area.x
                            layer.y = if (horizontal) area.y else max(0, oldHeight - area.bottom)
                        }
                    }
                }
            }
            document.width = newWidth
            document.height = newHeight
            document.selection = Selection(newWidth, newHeight)
        }
        changed(structure = true)
        onFitRequest?.invoke()
        report(label)
    }

    fun resizeCanvas(width: Int, height: Int, anchorX: Float, anchorY: Float) {
        val w = width.coerceIn(1, 30_000)
        val h = height.coerceIn(1, 30_000)
        val dx = ((w - document.width) * anchorX).toInt()
        val dy = ((h - document.height) * anchorY).toInt()
        document.structural("Canvas size") {
            for ((layer, _) in document.flattenTopFirst()) {
                layer.x += dx
                layer.y += dy
                if (layer.kind == LayerKind.TEXT && !layer.textLayout.isEmpty) {
                    layer.textLayout = Rect(layer.textLayout.x + dx, layer.textLayout.y + dy, layer.textLayout.w, layer.textLayout.h)
                }
            }
            document.width = w
            document.height = h
            document.selection = Selection(w, h)
        }
        changed(structure = true)
        onFitRequest?.invoke()
        report("Canvas resized to ${w}×${h}")
    }

    fun resizeImage(width: Int, height: Int) {
        val w = width.coerceIn(1, 30_000)
        val h = height.coerceIn(1, 30_000)
        if (w == document.width && h == document.height) return
        val scaleX = w.toFloat() / document.width
        val scaleY = h.toFloat() / document.height
        document.structural("Image size") {
            for ((layer, _) in document.flattenTopFirst()) {
                val pixels = layer.pixels
                if (pixels != null) {
                    layer.pixels = pixels.scaledTo(max(1, (pixels.width * scaleX).toInt()), max(1, (pixels.height * scaleY).toInt()))
                }
                val mask = layer.mask
                if (mask != null) {
                    layer.mask = mask.scaledTo(max(1, (mask.width * scaleX).toInt()), max(1, (mask.height * scaleY).toInt()))
                }
                layer.x = (layer.x * scaleX).toInt()
                layer.y = (layer.y * scaleY).toInt()
                if (!layer.textLayout.isEmpty) {
                    layer.textLayout = Rect(
                        (layer.textLayout.x * scaleX).toInt(),
                        (layer.textLayout.y * scaleY).toInt(),
                        max(1, (layer.textLayout.w * scaleX).toInt()),
                        max(1, (layer.textLayout.h * scaleY).toInt()),
                    )
                }
                layer.text?.let { content -> content.fontSize = max(1f, content.fontSize * ((scaleX + scaleY) / 2f)) }
            }
            document.width = w
            document.height = h
            document.selection = Selection(w, h)
        }
        changed(structure = true)
        onFitRequest?.invoke()
        report("Image resized to ${w}×${h}")
    }

    /** Crops to a rectangle in document coordinates, undoably. */
    fun cropToSelection() {
        val bounds = document.selection?.takeIf { it.isActive }?.bounds
        if (bounds == null || bounds.isEmpty) {
            report("Select the area to keep first", error = true)
            return
        }
        cropTo(bounds)
    }

    fun trimTransparent() {
        val flat = Pixmap(document.width, document.height)
        Compositor.flatten(document, flat, Colors.TRANSPARENT)
        val bounds = ImageIo.trim(flat, 6)
        if (bounds.isEmpty) {
            report("Nothing to trim", error = true)
            return
        }
        cropTo(bounds)
    }

    fun cropTo(bounds: Rect) {
        val area = bounds.intersect(document.bounds)
        if (area.isEmpty) {
            report("That crop leaves nothing", error = true)
            return
        }
        val offsetX = area.x
        val offsetY = area.y
        val out = HashMap<Long, Pixmap>()
        for ((layer, _) in document.flattenTopFirst()) {
            val pixels = layer.pixels ?: continue
            val local = Rect(layer.x, layer.y, pixels.width, pixels.height)
            val keep = local.intersect(area)
            if (keep.isEmpty) {
                out[layer.id] = Pixmap(1, 1)
                continue
            }
            val cropX = keep.x - layer.x
            val cropY = keep.y - layer.y
            out[layer.id] = pixels.crop(Rect(cropX, cropY, keep.w, keep.h))
        }
        document.structural("Crop") {
            for ((layer, _) in document.flattenTopFirst()) {
                val cropped = out[layer.id]
                val pixels = layer.pixels
                if (cropped != null && pixels != null) {
                    val local = Rect(layer.x, layer.y, pixels.width, pixels.height)
                    val keep = local.intersect(area)
                    layer.pixels = if (keep.isEmpty) null else cropped
                    layer.x = (keep.x - offsetX).coerceAtLeast(0)
                    layer.y = (keep.y - offsetY).coerceAtLeast(0)
                } else {
                    layer.x -= offsetX
                    layer.y -= offsetY
                }
                if (!layer.textLayout.isEmpty) {
                    layer.textLayout = Rect(layer.textLayout.x - offsetX, layer.textLayout.y - offsetY, layer.textLayout.w, layer.textLayout.h)
                }
            }
            document.width = area.w
            document.height = area.h
            document.selection = Selection(area.w, area.h)
        }
        changed(structure = true)
        onFitRequest?.invoke()
        report("Cropped to ${area.w}×${area.h}")
    }

    /* ----------------------------------------------------------- thumbnails */

    fun thumbnail(layer: Layer, width: Int, height: Int): Bitmap {
        thumbnails[layer.id]?.let { cached -> if (cached.width == width && cached.height == height) return cached }
        val pixmap = Pixmap(max(1, width), max(1, height))
        if (layer.isGroup) {
            pixmap.fill(Palette.surfaceHigh)
            val count = layer.children.count { it.visible }
            val marker = max(2, height / 6)
            for (i in 0 until min(count, 4)) {
                val top = height - marker * (i + 1) - 1
                for (y in max(0, top) until max(0, top + marker - 1)) {
                    for (x in 2 until width - 2) pixmap[x, y] = Palette.surfaceMax
                }
            }
        } else {
            val content: Pixmap? = layer.pixels ?: layer.areaBounds()?.let { area -> LayerRenderer.render(layer, area, null) }
            if (content == null) {
                pixmap.fill(Palette.surface)
            } else {
                val scaleX = content.width.toFloat() / width
                val scaleY = content.height.toFloat() / height
                for (y in 0 until height) {
                    for (x in 0 until width) {
                        pixmap[x, y] = content.sampleBilinear(x * scaleX, y * scaleY)
                    }
                }
            }
        }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixmap.pixels, 0, width, 0, 0, width, height)
        thumbnails[layer.id] = bitmap
        return bitmap
    }

    fun invalidateThumbnails() {
        thumbnails.clear()
        onThumbnailsChanged?.invoke()
    }

    /* --------------------------------------------------------------- colour */

    fun rememberColour(colour: Int) {
        recentColours.remove(colour)
        recentColours.add(0, colour)
        while (recentColours.size > 12) recentColours.removeAt(recentColours.size - 1)
    }

    fun gradientFor(index: Int): Gradients.Preset = Gradients.presets[index.coerceIn(0, Gradients.presets.size - 1)]

    fun setTextAlign(index: Int) {
        options.textAlign = index
        textDraft?.alignment = when (index) {
            1 -> TextAlign.CENTER
            2 -> TextAlign.RIGHT
            else -> TextAlign.LEFT
        }
        onPanelsChanged?.invoke()
    }

    /* ------------------------------------------------- things a panel needs */

    /** Which file action the user asked for; the activity does the rest. */
    enum class FileAction { NEW_DOCUMENT, OPEN, SAVE, SAVE_AS, EXPORT, PLACE_IMAGE }

    /** A live scale-and-rotate the layer panel is composing. */
    class TransformDraft {
        var scale: Float = 1f
        var angle: Float = 0f
        fun reset() {
            scale = 1f
            angle = 0f
        }
    }

    /** The scale/rotate the layer panel is composing. */
    val transformDraft = TransformDraft()

    /** How far the layer list is scrolled, in pixels. */
    var layerScroll: Float = 0f

    /** Canvas size sliders, kept in step with the document. */
    var canvasDraftWidth: Int = document.width
    var canvasDraftHeight: Int = document.height

    /** Which curve is being shaped: 0 RGB, 1 red, 2 green, 3 blue. */
    var curveChannel: Int = 0

    /** The last preset chosen per channel, for the curve panel's display. */
    var curvePreset: Int = 0

    private var pendingExport: ImageFormat = ImageFormat.PNG
    private val curveDrafts = arrayOfNulls<FloatArray>(4)

    /** The activity handles these: file pickers, saving, exporting. */
    var onFileAction: ((FileAction) -> Unit)? = null
    /** The activity shows the keyboard for text entry. */
    var onTextInputFocus: (() -> Unit)? = null

    val pendingExportFormat: ImageFormat get() = pendingExport

    fun request(action: FileAction) {
        onFileAction?.invoke(action)
    }

    fun requestExport(format: ImageFormat) {
        pendingExport = format
        onFileAction?.invoke(FileAction.EXPORT)
    }

    fun focusTextInput() {
        onTextInputFocus?.invoke()
    }

    /** The fonts the platform can draw with, for the text panel. */
    fun fontFamilies(): List<String> = try {
        LayerRenderer.textRasterizer?.families() ?: emptyList()
    } catch (error: Exception) {
        emptyList()
    }

    /** Tapping an already-selected layer row opens its commands. */
    fun openLayerMenu(layer: Layer) {
        selectLayer(layer)
        panel = Panel.LAYER_STYLE
        onPanelsChanged?.invoke()
    }

    /** Marks the document as needing a redraw after an adjustment changed. */
    fun refreshAdjustment() {
        document.dirty = true
        revision++
        invalidate(null)
        onPanelsChanged?.invoke()
    }

    fun chooseCurveChannel(index: Int) {
        curveChannel = index.coerceIn(0, 3)
        val draft = curveDrafts[curveChannel]
        val curves = adjustmentTarget?.adjustment as? Adjustment.Curves
        if (draft != null && curves != null) {
            curves.points = arrayOf(draft.copyOf())
            curves.channel = channelCode(curveChannel)
            refreshAdjustment()
        }
        onPanelsChanged?.invoke()
    }

    private fun channelCode(index: Int): Int = when (index) {
        1 -> 0
        2 -> 1
        3 -> 2
        else -> 3
    }

    fun applyCurvePreset(preset: Int) {
        val curves = adjustmentTarget?.adjustment as? Adjustment.Curves ?: return
        curvePreset = preset
        val points = curvePresetPoints(preset)
        curveDrafts[curveChannel] = points
        beginLiveEdit("Curves")
        curves.points = arrayOf(points.copyOf())
        curves.channel = channelCode(curveChannel)
        refreshAdjustment()
    }

    private fun curvePresetPoints(preset: Int): FloatArray = when (preset) {
        1 -> floatArrayOf(0f, 0f, 64f, 40f, 192f, 216f, 255f, 255f)
        2 -> floatArrayOf(0f, 12f, 128f, 150f, 255f, 255f)
        3 -> floatArrayOf(0f, 0f, 128f, 104f, 255f, 244f)
        4 -> floatArrayOf(0f, 32f, 255f, 224f)
        else -> floatArrayOf(0f, 0f, 255f, 255f)
    }

    /** Applies an adjustment layer to the pixels under it and drops the layer. */
    fun bakeAdjustment() {
        val target = adjustmentTarget ?: return
        val adjustment = target.adjustment ?: return
        val siblings = document.siblingsOf(target)
        val index = siblings.indexOf(target)
        val below = if (index > 0) siblings[index - 1] else null
        val pixels = below?.pixels
        if (below == null || pixels == null) {
            report("Nothing below to apply it to", error = true)
            return
        }
        val before = pixels.copy()
        try {
            adjustment.apply(pixels, pixels.bounds)
        } catch (error: OutOfMemoryError) {
            below.pixels = before
            report("Not enough memory for that adjustment", error = true)
            return
        }
        document.history.push(History.BufferEntry("Bake adjustment", below, before, pixels.copy()))
        document.structural("Bake adjustment") { siblings.remove(target) }
        adjustmentTarget = null
        activeLayer = below
        changed(structure = true)
        report("${adjustment.title} baked into ${below.name}")
    }

    /** Turns a text, solid or adjustment layer into pixels, as Photoshop does. */
    fun rasteriseActive() {
        val layer = activeLayer ?: return
        if (layer.pixels != null && layer.kind == LayerKind.RASTER) {
            report("${layer.name} is already pixels")
            return
        }
        if (layer.mask != null) applyMask(layer)
        val area = layer.areaBounds() ?: document.bounds
        val rendered = when {
            layer.kind == LayerKind.TEXT -> LayerRenderer.render(layer, area, null)
            layer.kind == LayerKind.SOLID -> {
                val pixmap = Pixmap(area.w, area.h)
                pixmap.fill(layer.solidColor ?: Colors.WHITE)
                pixmap
            }
            layer.kind == LayerKind.GROUP -> {
                val pixmap = Pixmap(document.width, document.height)
                Compositor.compose(layer.children, pixmap, document.bounds, 0, 0, 0)
                pixmap
            }
            else -> null
        }
        if (rendered == null) {
            report("An adjustment layer has no pixels of its own — bake it instead", error = true)
            return
        }
        document.structural("Rasterise layer") {
            layer.pixels = rendered
            layer.x = area.x
            layer.y = area.y
            layer.kind = LayerKind.RASTER
            layer.text = null
            layer.textLayout = Rect.EMPTY
            layer.psdTextBlock = null
            layer.solidColor = null
            layer.adjustment = null
            layer.contentWidth = rendered.width
            layer.contentHeight = rendered.height
        }
        changed(structure = true)
        report("Rasterised ${layer.name}")
    }

    /** Scales and rotates the active layer's pixels, in one undo step. */
    fun applyLayerTransform() {
        val layer = activeLayer ?: return
        val pixels = layer.pixels
        if (pixels == null) {
            report("${layer.name} has no pixels to transform", error = true)
            return
        }
        val draft = transformDraft
        if (abs(draft.scale - 1f) < 0.001f && abs(draft.angle) < 0.5f) {
            report("Nothing to apply")
            return
        }
        val before = pixels.copy()
        val width = pixels.width
        val height = pixels.height
        val centreX = width / 2f
        val centreY = height / 2f
        val radians = Math.toRadians(draft.angle.toDouble())
        val cosA = Math.cos(radians).toFloat()
        val sinA = Math.sin(radians).toFloat()
        val scale = draft.scale.coerceIn(0.02f, 20f)
        val out = Pixmap(width, height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val dx = (x + 0.5f - centreX) / scale
                val dy = (y + 0.5f - centreY) / scale
                val sx = centreX + dx * cosA + dy * sinA
                val sy = centreY - dx * sinA + dy * cosA
                out.pixels[y * width + x] = if (sx < 0f || sy < 0f || sx >= width || sy >= height) {
                    0
                } else {
                    pixels.sampleBilinear(sx - 0.5f, sy - 0.5f)
                }
            }
        }
        layer.pixels = out
        document.history.push(History.BufferEntry("Free transform", layer, before, out.copy()))
        draft.reset()
        changed(structure = true)
        report("Transformed ${layer.name}")
    }
}

/** Which panel is open below the canvas. */
enum class Panel { NONE, TOOL_OPTIONS, LAYERS, COLOUR, ADJUSTMENTS, FILTERS, HISTORY, CANVAS, FILE, LAYER_STYLE, TEXT }
