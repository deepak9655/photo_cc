package com.photocc.app.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import com.photocc.app.R
import com.photocc.app.core.Argb
import com.photocc.app.core.Pixels
import com.photocc.app.core.PsdDocument
import com.photocc.app.core.PsdRead
import com.photocc.app.core.Rect
import com.photocc.app.core.Render
import com.photocc.app.core.TextData
import com.photocc.app.core.TextLayer
import com.photocc.app.doc.Session
import com.photocc.app.platform.AndroidText
import java.io.ByteArrayOutputStream

/**
 * The single activity: an open document, a canvas, and everything reachable with
 * a thumb.
 *
 * Layout is built in code (no XML inflation) so start-up stays in the tens of
 * milliseconds, and every control is at least 44 dp tall. The phone is expected
 * to be held in one hand, so the tool row and the layer sheet live at the bottom
 * edge, and the top bar carries only the document actions.
 */
class MainActivity : Activity() {

    private lateinit var editor: EditorView
    private lateinit var toolOptions: ToolOptions
    private lateinit var layerPanel: LayerPanel
    private lateinit var root: FrameLayout
    private lateinit var status: TextView
    private lateinit var toolButtons: MutableMap<EditorView.Tool, android.widget.ImageButton>
    private lateinit var undoButton: android.widget.ImageButton
    private lateinit var redoButton: android.widget.ImageButton
    private lateinit var emptyState: View
    private var panelOpen = false

    private var session: Session? = null
    private var paintColor: Int = Argb.pack(255, 255, 255, 255)
    private var busy = false

    private val openPsd = 1001
    private val importImage = 1002
    private val savePsd = 1003
    private val exportFile = 1004

    private var pendingExport: Pair<ExportFormat, Pair<Int, Float>>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AndroidText.install()
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        setContentView(buildUi())
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent != null) handleIntent(intent)
    }

    /* ----------------------------------------------------------------- UI */

    private fun buildUi(): View {
        root = FrameLayout(this)
        root.setBackgroundColor(Ui.BACKGROUND)

        editor = EditorView(this)
        editor.onMessage = { message -> toast(message) }
        editor.onTextTapped = { layer -> editText(layer) }
        editor.onPickColor = { color ->
            paintColor = color
            toolOptions.bind(editor.tool, editor.brushSize, paintColor)
            toast(getString(R.string.picked_colour, String.format("#%06X", color and 0xFFFFFF)))
        }
        root.addView(
            editor,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
        )

        root.addView(buildTopBar())
        root.addView(buildBottomBar())
        root.addView(buildLayerPanel())
        emptyState = buildEmptyState()
        root.addView(emptyState)
        return root
    }

    private fun buildTopBar(): View = Ui.row(this, 0).apply {
        setBackgroundColor(0xE623252B.toInt())
        setPadding(Ui.dp(this@MainActivity, 6f), Ui.dp(this@MainActivity, 6f), Ui.dp(this@MainActivity, 6f), Ui.dp(this@MainActivity, 6f))
        val menuButton = Ui.iconButton(this@MainActivity, Glyph.Menu) { showMainMenu(root) }
        addView(menuButton)
        status = Ui.label(this@MainActivity, getString(R.string.no_document), 13f, Ui.TEXT_DIM).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        }
        addView(status)
        undoButton = Ui.iconButton(this@MainActivity, Glyph.Undo) { session?.undo(); refreshUi() }
        redoButton = Ui.iconButton(this@MainActivity, Glyph.Redo) { session?.redo(); refreshUi() }
        addView(undoButton)
        addView(redoButton)
        addView(Ui.iconButton(this@MainActivity, Glyph.Layers) { togglePanel() })
        layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP
        }
    }

    private fun buildBottomBar(): View = Ui.column(this).apply {
        setBackgroundColor(Ui.SURFACE)
        toolOptions = ToolOptions(this@MainActivity).apply {
            onSize = { value -> editor.brushSize = value }
            onHardness = { value -> editor.brushHardness = value }
            onFlow = { value -> editor.brushFlow = value }
            onTolerance = { value -> editor.fillTolerance = value }
            onPickColor = { pickColour() { color -> applyPaintColour(color) } }
        }
        addView(toolOptions)

        val tools = Ui.row(this@MainActivity, 0).apply {
            setPadding(Ui.dp(this@MainActivity, 4f), 0, Ui.dp(this@MainActivity, 4f), Ui.dp(this@MainActivity, 4f))
        }
        toolButtons = HashMap()
        val order = listOf(
            EditorView.Tool.MOVE to Glyph.Move,
            EditorView.Tool.BRUSH to Glyph.Brush,
            EditorView.Tool.ERASER to Glyph.Eraser,
            EditorView.Tool.FILL to Glyph.Fill,
            EditorView.Tool.EYEDROPPER to Glyph.EyeDropper,
            EditorView.Tool.TEXT to Glyph.Text,
            EditorView.Tool.CROP to Glyph.Crop,
        )
        for ((tool, glyph) in order) {
            val button = Ui.iconButton(this@MainActivity, glyph, Ui.TEXT_DIM, 44) { selectTool(tool) }
            toolButtons[tool] = button
            tools.addView(button)
        }
        tools.addView(Ui.spacer(this@MainActivity, 0.2f))
        tools.addView(Ui.iconButton(this@MainActivity, Glyph.Fit) { editor.fitToScreen() })
        addView(tools)
        layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM
        }
    }

    private fun buildLayerPanel(): View {
        layerPanel = LayerPanel(this, Session(PsdDocument(1, 1))).apply {
            visibility = View.GONE
            onClose = { togglePanel() }
            onAddRaster = raster@{
                val session = session ?: return@raster
                session.addRasterLayer()
                layerPanel.refresh()
                refreshUi()
            }
            onAddText = text@{
                val session = session ?: return@text
                createTextAtCenter(session)
            }
            onAddGroup = {
                session?.addGroup()
                layerPanel.refresh()
                refreshUi()
            }
            onDuplicate = {
                session?.duplicateSelected()
                layerPanel.refresh()
                refreshUi()
            }
            onMoveUp = {
                session?.moveSelected(1)
                layerPanel.refresh()
                refreshUi()
            }
            onMoveDown = {
                session?.moveSelected(-1)
                layerPanel.refresh()
                refreshUi()
            }
            onMerge = {
                session?.mergeDown()
                layerPanel.refresh()
                refreshUi()
            }
            onFlatten = flatten@{
                val session = session ?: return@flatten
                Dialogs.confirm(
                    this@MainActivity,
                    R.string.flatten,
                    getString(R.string.flatten_confirm),
                    R.string.flatten,
                ) {
                    session.flatten()
                    layerPanel.refresh()
                    refreshUi()
                }.show()
            }
            onDelete = delete@{
                val session = session ?: return@delete
                val layer = session.selected ?: return@delete
                Dialogs.confirm(
                    this@MainActivity,
                    R.string.delete_layer,
                    getString(R.string.delete_confirm, layer.name),
                    R.string.delete,
                ) {
                    session.deleteSelected()
                    layerPanel.refresh()
                    refreshUi()
                }.show()
            }
            onRename = { layer ->
                Dialogs.rename(this@MainActivity, layer.name) { name ->
                    session?.rename(layer.id, name)
                    layerPanel.refresh()
                    refreshUi()
                }.show()
            }
        }
        val params = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 340f)).apply {
            gravity = Gravity.BOTTOM
        }
        layerPanel.layoutParams = params
        return layerPanel
    }

    private fun buildEmptyState(): View = Ui.column(this).apply {
        gravity = Gravity.CENTER
        setBackgroundColor(Ui.BACKGROUND)
        addView(Ui.label(this@MainActivity, getString(R.string.empty_title), 20f).apply { gravity = Gravity.CENTER })
        addView(
            Ui.label(this@MainActivity, getString(R.string.empty_hint), 13f, Ui.TEXT_DIM).apply {
                gravity = Gravity.CENTER
                setPadding(0, Ui.dp(this@MainActivity, 12f), 0, Ui.dp(this@MainActivity, 16f))
            },
        )
        addView(Ui.textButton(this@MainActivity, getString(R.string.open_psd)) { pickDocument(openPsd) })
        addView(
            Ui.label(this@MainActivity, getString(R.string.new_document), 15f).apply {
                setPadding(0, Ui.dp(this@MainActivity, 18f), 0, 0)
                gravity = Gravity.CENTER
                setOnClickListener { newDocument() }
            },
        )
        addView(
            Ui.label(this@MainActivity, getString(R.string.import_image), 15f).apply {
                setPadding(0, Ui.dp(this@MainActivity, 12f), 0, 0)
                gravity = Gravity.CENTER
                setOnClickListener { pickDocument(importImage) }
            },
        )
    }

    /* --------------------------------------------------------------- menu */

    private fun showMainMenu(anchor: View) {
        val menu = PopupMenu(this, anchor)
        menu.menu.add(0, 1, 0, getString(R.string.open_psd))
        menu.menu.add(0, 2, 1, getString(R.string.new_document))
        menu.menu.add(0, 3, 2, getString(R.string.import_image))
        menu.menu.add(0, 4, 3, getString(R.string.save_psd))
        menu.menu.add(0, 5, 4, getString(R.string.export))
        menu.menu.add(0, 6, 5, getString(R.string.fit_screen))
        menu.menu.add(0, 7, 6, getString(R.string.zoom_100))
        menu.menu.add(0, 8, 7, getString(R.string.document_info))
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> pickDocument(openPsd)
                2 -> newDocument()
                3 -> pickDocument(importImage)
                4 -> saveDocument()
                5 -> showExportDialog()
                6 -> editor.fitToScreen()
                7 -> editor.zoomTo100Percent()
                8 -> showInfo()
                else -> false
            }
            true
        }
        menu.show()
    }

    private fun selectTool(tool: EditorView.Tool) {
        editor.tool = tool
        for ((each, button) in toolButtons) {
            button.setImageDrawable(GlyphDrawable(glyphOf(each), if (each == tool) Ui.ACCENT else Ui.TEXT_DIM))
            button.background = if (each == tool) Ui.rounded(0x22FFB74D, Ui.dp(this, 12f).toFloat()) else null
        }
        toolOptions.bind(tool, editor.brushSize, paintColor)
    }

    private fun glyphOf(tool: EditorView.Tool): Glyph = when (tool) {
        EditorView.Tool.MOVE -> Glyph.Move
        EditorView.Tool.BRUSH -> Glyph.Brush
        EditorView.Tool.ERASER -> Glyph.Eraser
        EditorView.Tool.FILL -> Glyph.Fill
        EditorView.Tool.EYEDROPPER -> Glyph.EyeDropper
        EditorView.Tool.TEXT -> Glyph.Text
        EditorView.Tool.CROP -> Glyph.Crop
    }

    private fun togglePanel() {
        val session = session ?: return
        panelOpen = !panelOpen
        layerPanel.visibility = View.VISIBLE
        layerPanel.attach(session)
        layerPanel.refresh()
        layerPanel.animate().translationY(if (panelOpen) 0f else Ui.dp(this, 340f).toFloat()).setDuration(160).start()
        if (!panelOpen) {
            layerPanel.postDelayed({ layerPanel.visibility = View.GONE }, 170)
        }
    }

    /* ------------------------------------------------------------- documents */

    private fun handleIntent(intent: Intent?) {
        val uri = intent?.data ?: return
        if (intent.action == Intent.ACTION_VIEW || intent.action == Intent.ACTION_SEND) loadDocument(uri)
    }

    private fun pickDocument(requestCode: Int) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = if (requestCode == importImage) "image/*" else "*/*"
            if (requestCode == importImage) putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*"))
        }
        startActivityForResult(intent, requestCode)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        when (requestCode) {
            openPsd -> loadDocument(uri)
            importImage -> importImageLayer(uri)
            savePsd, exportFile -> writeDocument(uri)
            else -> Unit
        }
    }

    private fun displayName(uri: Uri): String? = try {
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    } catch (_: Exception) {
        null
    }

    private fun loadDocument(uri: Uri) {
        if (busy) return
        busy = true
        editor.busy = true
        editor.invalidate()
        Thread {
            val result = runCatching {
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw IllegalStateException(getString(R.string.error_unreadable))
                val name = displayName(uri) ?: "untitled.psd"
                val doc = PsdRead.read(bytes, name)
                doc.sourceBytes = bytes.size.toLong()
                doc
            }
            runOnUiThread {
                busy = false
                editor.busy = false
                result.onSuccess { doc ->
                    session = Session(doc).also { it.addListener { refreshUi() } }
                    editor.session = session
                    editor.fitToScreen()
                    emptyState.visibility = View.GONE
                    selectTool(editor.tool)
                    refreshUi()
                    val unsupported = mutableListOf<String>()
                    doc.walkLayers { layer, _ -> layer.unsupported?.let { unsupported.add("${layer.name}: $it") } }
                    if (unsupported.isEmpty()) {
                        toast(getString(R.string.opened, doc.fileName, doc.layerCount()))
                    } else {
                        toast(getString(R.string.opened_partial, doc.fileName, unsupported.size))
                    }
                }
                result.onFailure { error ->
                    Dialogs.info(this, getString(R.string.error_open), error.message ?: "").show()
                }
            }
        }.start()
    }

    private fun importImageLayer(uri: Uri) {
        if (busy) return
        busy = true
        Thread {
            val result = runCatching {
                val bitmap = contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it) }
                    ?: throw IllegalStateException(getString(R.string.error_unreadable))
                val pixels = Pixels(bitmap.width, bitmap.height, IntArray(bitmap.width * bitmap.height))
                bitmap.getPixels(pixels.data, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                val name = displayName(uri) ?: "image"
                bitmap.recycle()
                pixels to name
            }
            runOnUiThread {
                busy = false
                result.onSuccess { (pixels, name) ->
                    val current = session
                    if (current == null) {
                        val doc = PsdDocument(pixels.width, pixels.height)
                        doc.fileName = "$name.psd"
                        doc.outlineName = "${name}-edited.psd"
                        val fresh = Session(doc).also { it.addListener { refreshUi() } }
                        session = fresh
                        editor.session = fresh
                        emptyState.visibility = View.GONE
                        fresh.addRasterLayer(name = name, pixels = pixels)
                    } else {
                        current.addRasterLayer(name = name, pixels = pixels)
                    }
                    editor.fitToScreen()
                    layerPanel.refresh()
                    refreshUi()
                }
                result.onFailure { error ->
                    Dialogs.info(this, getString(R.string.error_open), error.message ?: "").show()
                }
            }
        }.start()
    }

    private fun newDocument() {
        Dialogs.newDocument(this) { width, height, background ->
            val doc = PsdDocument(width, height)
            doc.fileName = "untitled.psd"
            doc.outlineName = "untitled-edited.psd"
            val fresh = Session(doc).also { it.addListener { refreshUi() } }
            session = fresh
            editor.session = fresh
            emptyState.visibility = View.GONE
            fresh.addRasterLayer(name = getString(R.string.background_layer), fill = background)
            editor.fitToScreen()
            layerPanel.refresh()
            refreshUi()
        }.show()
    }

    private fun saveDocument() {
        val session = session ?: return
        if (busy) return
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_TITLE, session.savedFileName)
        }
        pendingExport = null
        startActivityForResult(intent, savePsd)
    }

    private fun showExportDialog() {
        val session = session ?: return
        Dialogs.export(this, session.savedFileName) { format, quality, scale ->
            if (format == ExportFormat.Psd) {
                saveDocument()
                return@export
            }
            val extension = if (format == ExportFormat.Png) "png" else "jpg"
            val mime = if (format == ExportFormat.Png) "image/png" else "image/jpeg"
            pendingExport = format to (quality to scale)
            val name = session.savedFileName.substringBeforeLast('.') + "." + extension
            startActivityForResult(
                Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = mime
                    putExtra(Intent.EXTRA_TITLE, name)
                },
                exportFile,
            )
        }.show()
    }

    private fun writeDocument(uri: Uri) {
        val session = session ?: return
        if (busy) return
        busy = true
        editor.busy = true
        editor.invalidate()
        val export = pendingExport
        Thread {
            val result = runCatching {
                val bytes: ByteArray = if (export == null) {
                    session.saveToBytes()
                } else {
                    val (format, settings) = export
                    val (quality, scale) = settings
                    val bitmap = flattenToBitmap(session, scale)
                    val stream = ByteArrayOutputStream()
                    val ok = bitmap.compress(
                        if (format == ExportFormat.Png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG,
                        quality,
                        stream,
                    )
                    bitmap.recycle()
                    if (!ok) throw IllegalStateException(getString(R.string.error_export))
                    stream.toByteArray()
                }
                contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                    ?: throw IllegalStateException(getString(R.string.error_unwritable))
                bytes.size
            }
            runOnUiThread {
                busy = false
                editor.busy = false
                pendingExport = null
                editor.invalidate()
                result.onSuccess { size ->
                    session.doc.dirty = false
                    toast(getString(R.string.saved, size / 1024))
                    refreshUi()
                }
                result.onFailure { error ->
                    Dialogs.info(this, getString(R.string.error_save), error.message ?: "").show()
                }
            }
        }.start()
    }

    /** Composites the document into a bitmap at an optional scale, for PNG/JPEG export. */
    private fun flattenToBitmap(session: Session, scale: Float): Bitmap {
        val doc = session.doc
        val width = (doc.width * scale).toInt().coerceAtLeast(1)
        val height = (doc.height * scale).toInt().coerceAtLeast(1)
        val full = IntArray(doc.width * doc.height)
        Render.flatten(doc, Rect(0, 0, doc.width, doc.height), full)
        if (scale >= 0.999f) return AndroidText.toBitmap(full, doc.width, doc.height)
        val scaled = IntArray(width * height)
        for (y in 0 until height) {
            val sourceY = (y / scale).toInt().coerceIn(0, doc.height - 1)
            for (x in 0 until width) {
                val sourceX = (x / scale).toInt().coerceIn(0, doc.width - 1)
                scaled[y * width + x] = full[sourceY * doc.width + sourceX]
            }
        }
        return AndroidText.toBitmap(scaled, width, height)
    }

    /* ------------------------------------------------------------------ text */

    private fun createTextAtCenter(session: Session) {
        val style = TextData()
        style.color = paintColor
        style.fontFamily = "sans-serif"
        style.psdFontName = "ArialMT"
        Dialogs.textLayer(
            this,
            R.string.new_text_layer,
            style,
            isNew = true,
            onPreview = { preview -> applyTextPreview(null, preview) },
            onPickColor = { callback -> pickColour(style.color, callback) },
            onOk = { result ->
                val layer = session.addTextLayer(result.text, session.doc.width * 0.08f, session.doc.height * 0.4f, result)
                layer.text = result
                layer.textDirty = true
                editor.invalidateContent()
                layerPanel.refresh()
                refreshUi()
            },
        ).show()
    }

    /** Opens the editor for an existing text layer, or for a freshly tapped position. */
    private fun editText(layer: TextLayer) {
        val session = session ?: return
        if (layer.id.isEmpty() || session.doc.findLayer(layer.id) == null) {
            // A new layer created by tapping the canvas: pre-fill from that spot.
            val style = layer.text.copyOf()
            if (style.text.isEmpty()) style.text = getString(R.string.default_text)
            style.color = paintColor
            Dialogs.textLayer(
                this,
                R.string.new_text_layer,
                style,
                isNew = true,
                onPreview = { preview -> applyTextPreview(null, preview) },
                onPickColor = { callback -> pickColour(style.color, callback) },
                onOk = { result ->
                    val created = session.addTextLayer(result.text, layer.x, layer.y, result)
                    created.text = result
                    created.textDirty = true
                    session.editText(created.id) { }
                    editor.invalidateContent()
                    layerPanel.refresh()
                    refreshUi()
                },
            ).show()
            return
        }

        session.beginStructuralEdit("Edit text")
        val dialog = Dialogs.textLayer(
            this,
            R.string.edit_text_layer,
            layer.text.copyOf(),
            isNew = false,
            onPreview = { preview -> applyTextPreview(layer, preview) },
            onPickColor = { callback -> pickColour(layer.text.color, callback) },
            onOk = { result ->
                session.updateTextLive(layer.id) {
                    it.text = result.copyOf()
                    it.textDirty = true
                }
                session.endStructuralEdit("Edit text")
                editor.invalidateContent()
                layerPanel.refresh()
                refreshUi()
            },
        )
        dialog.setOnDismissListener {
            // Cancelling still closes the step (and keeps whatever was previewed out
            // of the history when nothing changed).
            session.endStructuralEdit("Edit text")
            editor.invalidateContent()
            layerPanel.refresh()
            refreshUi()
        }
        dialog.show()
    }

    private fun applyTextPreview(layer: TextLayer?, preview: TextData) {
        val session = session ?: return
        if (layer == null) return
        session.updateTextLive(layer.id) {
            it.text = preview.copyOf()
            it.textDirty = true
        }
        editor.invalidateContent()
    }

    /* --------------------------------------------------------------- colour */

    private fun pickColour(callback: (Int) -> Unit) = pickColour(paintColor, callback)

    private fun pickColour(initial: Int, callback: (Int) -> Unit) {
        Dialogs.color(this, initial, callback).show()
    }

    private fun applyPaintColour(color: Int) {
        paintColor = color
        toolOptions.bind(editor.tool, editor.brushSize, paintColor)
    }

    /* ------------------------------------------------------------------- misc */

    private fun showInfo() {
        val session = session ?: return
        val builder = StringBuilder(session.describe())
        builder.append('\n').append(getString(R.string.info_engine, (editor.currentZoom() * 100f).toInt()))
        session.notes.forEach { note -> builder.append('\n').append("• ").append(note) }
        val unsupported = mutableListOf<String>()
        session.doc.walkLayers { layer, _ -> layer.unsupported?.let { unsupported.add("• ${layer.name}: $it") } }
        unsupported.forEach { builder.append('\n').append(it) }
        builder.append('\n').append(getString(R.string.info_buffer, editor.bufferBytes() / 1024))
        Dialogs.info(this, getString(R.string.document_info), builder.toString()).show()
    }

    private fun refreshUi() {
        val session = session
        undoButton.isEnabled = session?.canUndo == true
        undoButton.alpha = if (undoButton.isEnabled) 1f else 0.35f
        redoButton.isEnabled = session?.canRedo == true
        redoButton.alpha = if (redoButton.isEnabled) 1f else 0.35f
        status.text = session?.let { doc ->
            val dirty = if (doc.doc.dirty) " •" else ""
            "${doc.title}$dirty"
        } ?: getString(R.string.no_document)
        emptyState.visibility = if (session == null) View.VISIBLE else View.GONE
        editor.invalidateContent()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun onBackPressed() {
        when {
            panelOpen -> togglePanel()
            session?.doc?.dirty == true -> Dialogs.confirm(
                this,
                R.string.unsaved_title,
                getString(R.string.unsaved_message),
                R.string.discard,
            ) { finish() }.show()
            else -> super.onBackPressed()
        }
    }
}
