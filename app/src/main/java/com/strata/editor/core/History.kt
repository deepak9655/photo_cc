package com.strata.editor.core

/**
 * Undo/redo.
 *
 * Two kinds of entry:
 *
 *  * **Pixel edits** record only the 256-pixel tiles they overwrite — a thin
 *    diagonal stroke across a 100 MPix layer costs a few hundred kilobytes, not a
 *    copy of the layer. A tool marks the rectangle it is about to write *before*
 *    it writes it (see [History.PixelBuilder.touch]); the tile snapshots are then
 *    taken automatically.
 *  * **Structural edits** (add, delete, reorder, rename, blend mode, opacity, text
 *    content) snapshot the layer tree. That is cheap, because pixel buffers are
 *    shared by reference and any pixel change gets its own entry.
 *
 * The stack has a byte budget: the first thing to go when memory runs short is the
 * oldest undo step, never the document.
 */
class History(private val budgetBytes: Int = 192 * 1024 * 1024) {

    interface Entry {
        val label: String
        val bytes: Int
        fun undo(doc: Document)
        fun redo(doc: Document)
    }

    private val undoStack = ArrayDeque<Entry>()
    private val redoStack = ArrayDeque<Entry>()
    private var used = 0
    private var suspended = 0

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()
    val undoLabel: String? get() = undoStack.lastOrNull()?.label
    val redoLabel: String? get() = redoStack.lastOrNull()?.label
    val depth: Int get() = undoStack.size
    val usedBytes: Int get() = used

    fun push(entry: Entry) {
        if (suspended > 0) return
        undoStack.addLast(entry)
        used += entry.bytes
        while (redoStack.isNotEmpty()) used -= redoStack.removeLast().bytes
        // An entry bigger than the whole budget still has to be undoable — it is the
        // newest step. Trim the *older* steps around it instead of refusing it.
        while (used > budgetBytes && undoStack.size > 1) used -= undoStack.removeFirst().bytes
    }

    fun undo(doc: Document): Boolean {
        val entry = undoStack.removeLastOrNull() ?: return false
        used -= entry.bytes
        entry.undo(doc)
        redoStack.addLast(entry)
        used += entry.bytes
        return true
    }

    fun redo(doc: Document): Boolean {
        val entry = redoStack.removeLastOrNull() ?: return false
        used -= entry.bytes
        entry.redo(doc)
        undoStack.addLast(entry)
        used += entry.bytes
        return true
    }

    fun clear() {
        undoStack.clear()
        redoStack.clear()
        used = 0
    }

    /** Runs `block` without recording anything: used while loading a document. */
    fun <T> silent(block: () -> T): T {
        suspended++
        try {
            return block()
        } finally {
            suspended--
        }
    }

    /** Begins recording a pixel edit on a layer. */
    fun beginPixelEdit(layer: Layer, label: String): PixelBuilder = PixelBuilder(layer, label)

    /** Finishes a pixel edit; pushes an entry unless nothing actually changed. */
    fun commit(builder: PixelBuilder) {
        builder.finish()?.let { push(it) }
    }

    /**
     * Throws an unfinished edit away and puts the tiles back the way they were.
     * A stroke that is interrupted — a second finger arriving, the app going to
     * the background — should leave no trace and no history step.
     */
    fun rollback(builder: PixelBuilder) = builder.restoreBefore()

    /* ------------------------------------------------------------- pixel edits */

    /**
     * Accumulates the tiles a tool is about to change. Mark the touched rectangle
     * *before* writing to it — the first mark of a tile is when its "before"
     * pixels are copied out.
     */
    class PixelBuilder internal constructor(
        private val layer: Layer,
        private val label: String,
        private val tileSize: Int = TILE,
    ) {
        private val before = LinkedHashMap<Long, IntArray>()
        private val after = LinkedHashMap<Long, IntArray>()

        val touchedTiles: Int get() = before.size
        val labelName: String get() = label

        /** Marks a rectangle about to be written. Call this first, then write. */
        fun touch(rect: Rect) {
            val pixels = layer.pixels ?: return
            val r = rect.clampTo(pixels.width, pixels.height)
            if (r.isEmpty) return
            for (ty in r.y / tileSize..(r.bottom - 1) / tileSize) {
                for (tx in r.x / tileSize..(r.right - 1) / tileSize) {
                    val key = key(tx, ty)
                    if (!before.containsKey(key)) {
                        before[key] = copyTile(pixels, tx * tileSize, ty * tileSize)
                    }
                }
            }
        }

        /** Puts the tiles back the way they were, for an abandoned stroke. */
        internal fun restoreBefore() {
            val pixels = layer.pixels ?: return
            for ((key, data) in before) {
                if (data.isEmpty()) continue
                val x0 = tileX(key) * tileSize
                val y0 = tileY(key) * tileSize
                val w = minOf(tileSize, pixels.width - x0)
                val h = minOf(tileSize, pixels.height - y0)
                if (w <= 0 || h <= 0) continue
                for (y in 0 until h) {
                    System.arraycopy(data, y * w, pixels.pixels, (y0 + y) * pixels.width + x0, w)
                }
            }
        }

        internal fun finish(): Entry? {
            if (before.isEmpty()) return null
            val pixels = layer.pixels ?: return null
            var changed = false
            for ((key, original) in before) {
                val current = copyTile(pixels, tileX(key) * tileSize, tileY(key) * tileSize)
                after[key] = current
                if (!changed && !original.contentEquals(current)) changed = true
            }
            if (!changed) return null
            val tiles = before.mapValues { (_, value) -> value }
            return PixelEntry(label, layer, tiles, HashMap(after), tileSize)
        }

        private fun copyTile(pixels: Pixmap, x0: Int, y0: Int): IntArray {
            val w = minOf(tileSize, pixels.width - x0)
            val h = minOf(tileSize, pixels.height - y0)
            if (w <= 0 || h <= 0) return IntArray(0)
            val out = IntArray(w * h)
            for (y in 0 until h) {
                System.arraycopy(pixels.pixels, (y0 + y) * pixels.width + x0, out, y * w, w)
            }
            return out
        }

        private fun key(tx: Int, ty: Int): Long = (ty.toLong() shl 32) or (tx.toLong() and 0xFFFFFFFFL)
        private fun tileX(key: Long): Int = (key and 0xFFFFFFFFL).toInt()
        private fun tileY(key: Long): Int = (key ushr 32).toInt()

        companion object {
            const val TILE = 256
        }
    }

    class PixelEntry(
        override val label: String,
        private val layer: Layer,
        private val before: Map<Long, IntArray>,
        private val after: Map<Long, IntArray>,
        private val tileSize: Int,
    ) : Entry {

        override val bytes: Int get() = (before.size + after.size) * tileSize * tileSize * 4

        override fun undo(doc: Document) = restore(before)

        override fun redo(doc: Document) = restore(after)

        private fun restore(source: Map<Long, IntArray>) {
            val pixels = layer.pixels ?: return
            for ((key, data) in source) {
                val tx = (key and 0xFFFFFFFFL).toInt()
                val ty = (key ushr 32).toInt()
                val x0 = tx * tileSize
                val y0 = ty * tileSize
                val w = minOf(tileSize, pixels.width - x0)
                val h = minOf(tileSize, pixels.height - y0)
                if (w <= 0 || h <= 0 || data.isEmpty()) continue
                for (y in 0 until h) {
                    System.arraycopy(data, y * w, pixels.pixels, (y0 + y) * pixels.width + x0, w)
                }
            }
        }
    }

    /** Records a whole-buffer replacement (a filter, a crop, a transform). */
    class BufferEntry(
        override val label: String,
        private val layer: Layer,
        private val before: Pixmap,
        private val after: Pixmap?,
    ) : Entry {

        override val bytes: Int get() = before.pixels.size * 4 * (if (after == null) 1 else 2)

        override fun undo(doc: Document) {
            layer.pixels = before.copy()
        }

        override fun redo(doc: Document) {
            layer.pixels = after?.copy()
        }
    }

    /** A structural change to the layer tree (and, optionally, the selection). */
    class StructureEntry(
        override val label: String,
        private val beforeLayers: List<Layer>,
        private val afterLayers: List<Layer>,
        private val beforeSelection: Selection? = null,
        private val afterSelection: Selection? = null,
    ) : Entry {

        override val bytes: Int get() = 96 * (count(beforeLayers) + count(afterLayers))

        private fun count(layers: List<Layer>): Int = layers.sumOf { 1 + count(it.children) }

        override fun undo(doc: Document) {
            doc.layers.clear()
            doc.layers.addAll(beforeLayers.map { it.shallowCopy() })
            beforeSelection?.let { doc.selection?.replaceWith(it) }
        }

        override fun redo(doc: Document) {
            doc.layers.clear()
            doc.layers.addAll(afterLayers.map { it.shallowCopy() })
            afterSelection?.let { doc.selection?.replaceWith(it) }
        }
    }
}
