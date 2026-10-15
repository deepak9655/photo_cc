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
import kotlin.math.max
import kotlin.math.roundToInt

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

    companion object {
        /** Largest image the editor will decode/import (same budget as the PSD reader). */
        private const val MAX_IMPORT_PIXELS = 24_000_000L

        /** Largest bitmap PNG/JPEG export will produce; bigger canvases are scaled down. */
        private const val MAX_EXPORT_PIXELS = 24_000_000L
    }


    private lateinit var editor: EditorView
    private lateinit var toolOptions: ToolOptions
    private lateinit var layerPanel: LayerPanel
    private lateinit var root: FrameLayout
    private lateinit var status: TextView
    private lateinit var statusDetail: TextView
    private lateinit var zoomChip: TextView
    private lateinit var swatchButton: android.widget.ImageButton
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
        root = FrameLayout(this).apply { setBackgroundColor(Ui.BACKGROUND) }

        editor = EditorView(this).apply {
            onMessage = { message -> snack(message) }
            onTextTapped = { layer -> editText(layer) }
            onPickColor = { color ->
                paintColor = color
                toolOptions.bind(editor.tool, editor.brushSize, paintColor)
                refreshSwatch()
                snack(getString(R.string.picked_colour, String.format("#%06X", color and 0xFFFFFF)))
            }
            onViewportChanged = { updateZoomChip() }
        }
        root.addView(
            editor,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
        )

        root.addView(buildTopBar())
        root.addView(buildZoomStack())
        root.addView(buildBottomArea())
        root.addView(buildLayerPanel())

        emptyState = buildEmptyState()
        root.addView(emptyState)
        return root
    }

    /**
     * The top bar: a floating pill holding the document identity on the left and
     * undo/redo on the right. Everything else moved to the bottom dock, so the
     * top of the screen stays free for the canvas.
     */
    private fun buildTopBar(): View {
        val margin = Ui.dp(this, 10f)
        val bar = Ui.floatingBar(this, Ui.RADIUS_LG).apply {
            setPadding(Ui.dp(this@MainActivity, 4f), Ui.dp(this@MainActivity, 4f), Ui.dp(this@MainActivity, 6f), Ui.dp(this@MainActivity, 4f))
        }
        bar.addView(Ui.iconButton(this, Glyph.Menu, Ui.TEXT, 46) { showMainMenu(bar) })

        val identity = Ui.column(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(Ui.dp(this@MainActivity, 6f), 0, 0, 0)
        }
        status = Ui.label(this, getString(R.string.no_document), 15f, Ui.TEXT, medium = true).apply {
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        }
        statusDetail = Ui.label(this, getString(R.string.app_name), 11f, Ui.TEXT_FAINT, mono = false)
        identity.addView(status)
        identity.addView(statusDetail)
        bar.addView(identity)

        undoButton = Ui.iconButton(this, Glyph.Undo) { session?.undo(); refreshUi() }
        redoButton = Ui.iconButton(this, Glyph.Redo) { session?.redo(); refreshUi() }
        bar.addView(undoButton)
        bar.addView(redoButton)

        Ui.place(bar, Gravity.TOP or Gravity.START, start = margin, end = margin, top = margin)
        (bar.layoutParams as FrameLayout.LayoutParams).width = FrameLayout.LayoutParams.MATCH_PARENT
        return bar
    }

    /**
     * Zoom controls, floating on the right edge: in, fit, out, plus a live
     * readout. Double-tap on the canvas does the same thing, but a visible
     * control is what people reach for first.
     */
    private fun buildZoomStack(): View {
        val zoomIn = Ui.iconButton(this, Glyph.Add, Ui.TEXT, 46) { editor.zoomBy(1.35f); updateZoomChip() }
        val fit = Ui.iconButton(this, Glyph.Fit, Ui.TEXT, 46) { editor.fitToScreen(); updateZoomChip() }
        val zoomOut = Ui.iconButton(this, Glyph.Remove, Ui.TEXT, 46) { editor.zoomBy(1f / 1.35f); updateZoomChip() }
        zoomChip = Ui.label(this, "100%", 11.5f, Ui.TEXT, medium = true, mono = true).apply {
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(Ui.dp(this@MainActivity, 46f), Ui.dp(this@MainActivity, 24f))
            setOnClickListener { editor.zoomTo100Percent(); updateZoomChip() }
        }
        val stack = Ui.stack(this, zoomChip, zoomIn, fit, zoomOut)
        Ui.place(stack, Gravity.END or Gravity.CENTER_VERTICAL, end = Ui.dp(this, 10f))
        return stack
    }

    /** Contextual options on top, the tool dock underneath. */
    private fun buildBottomArea(): View {
        val area = Ui.column(this).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.BOTTOM
                bottomMargin = Ui.dp(this@MainActivity, 10f)
                marginStart = Ui.dp(this@MainActivity, 10f)
                marginEnd = Ui.dp(this@MainActivity, 10f)
            }
            setClipToPadding(false)
        }

        toolOptions = ToolOptions(this).apply {
            onSize = { value -> editor.brushSize = value }
            onHardness = { value -> editor.brushHardness = value }
            onFlow = { value -> editor.brushFlow = value }
            onTolerance = { value -> editor.fillTolerance = value }
            onPickColor = { pickColour { color -> applyPaintColour(color) } }
        }
        val optionsStrip = android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            background = Ui.roundedStroke(Ui.SURFACE, Ui.OUTLINE, 1f, Ui.RADIUS_LG)
            addView(toolOptions, android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
            ))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = Ui.dp(this@MainActivity, 8f)
            }
        }
        area.addView(optionsStrip)

        val dock = Ui.floatingBar(this, Ui.RADIUS_LG).apply {
            setPadding(Ui.dp(this@MainActivity, 6f), Ui.dp(this@MainActivity, 6f), Ui.dp(this@MainActivity, 6f), Ui.dp(this@MainActivity, 6f))
        }
        val toolsRow = Ui.row(this, 0)
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
            val button = toolButton(tool, glyph)
            toolButtons[tool] = button
            toolsRow.addView(button)
        }
        dock.addView(
            android.widget.HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                addView(toolsRow)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            },
        )
        dock.addView(Ui.divider(this, vertical = true).apply {
            layoutParams = LinearLayout.LayoutParams(Ui.dp(this@MainActivity, 1f), Ui.dp(this@MainActivity, 30f)).apply {
                marginStart = Ui.dp(this@MainActivity, 4f)
                marginEnd = Ui.dp(this@MainActivity, 4f)
            }
        })
        swatchButton = Ui.iconButton(this, Glyph.Sliders, Ui.TEXT, 46) { pickColour { color -> applyPaintColour(color) } }
        dock.addView(swatchButton)
        dock.addView(Ui.iconButton(this, Glyph.Layers, Ui.TEXT, 46) { togglePanel() })
        area.addView(dock)
        return area
    }

    /** A tool icon that carries its active state as a tinted pill. */
    private fun toolButton(tool: EditorView.Tool, glyph: Glyph): android.widget.ImageButton =
        Ui.iconButton(this, glyph, Ui.TEXT_DIM, 46) { selectTool(tool) }.apply {
            background = Ui.rounded(android.graphics.Color.TRANSPARENT, Ui.RADIUS_MD)
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
        val params = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 380f)).apply {
            gravity = Gravity.BOTTOM
        }
        layerPanel.layoutParams = params
        return layerPanel
    }

    /** The first thing anyone sees: what the app is, and the three ways in. */
    private fun buildEmptyState(): View {
        val column = Ui.column(this).apply {
            gravity = Gravity.CENTER
            setBackgroundColor(Ui.BACKGROUND)
            setPadding(Ui.dp(this@MainActivity, 20f), Ui.dp(this@MainActivity, 40f), Ui.dp(this@MainActivity, 20f), Ui.dp(this@MainActivity, 24f))
        }
        val mark = android.widget.ImageView(this).apply {
            val box = Ui.dp(this@MainActivity, 84f)
            layoutParams = LinearLayout.LayoutParams(box, box).apply { gravity = Gravity.CENTER_HORIZONTAL }
            setPadding(Ui.dp(this@MainActivity, 14f), Ui.dp(this@MainActivity, 14f), Ui.dp(this@MainActivity, 14f), Ui.dp(this@MainActivity, 14f))
            setImageDrawable(GlyphDrawable(Glyph.Layers, Ui.ACCENT))
            background = Ui.roundedStroke(Ui.SURFACE, Ui.OUTLINE, 1f, Ui.RADIUS_LG)
        }
        column.addView(mark)
        column.addView(
            Ui.label(this, getString(R.string.empty_title), 22f, Ui.TEXT, medium = true).apply {
                gravity = Gravity.CENTER
                setPadding(0, Ui.dp(this@MainActivity, 18f), 0, 0)
            },
        )
        column.addView(
            Ui.label(this, getString(R.string.empty_hint), 13f, Ui.TEXT_DIM).apply {
                gravity = Gravity.CENTER
                isSingleLine = false
                maxLines = 3
                setPadding(Ui.dp(this@MainActivity, 12f), Ui.dp(this@MainActivity, 8f), Ui.dp(this@MainActivity, 12f), Ui.dp(this@MainActivity, 22f))
            },
        )
        val card = Ui.card(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }
        card.addView(
            Ui.menuRow(this, Glyph.Open, getString(R.string.open_psd), getString(R.string.empty_open_hint), accent = true) {
                pickDocument(openPsd)
            },
        )
        card.addView(Ui.divider(this))
        card.addView(Ui.menuRow(this, Glyph.New, getString(R.string.new_document), getString(R.string.empty_new_hint)) { newDocument() })
        card.addView(Ui.divider(this))
        card.addView(Ui.menuRow(this, Glyph.Image, getString(R.string.import_image), getString(R.string.empty_import_hint)) { pickDocument(importImage) })
        column.addView(card)
        column.addView(
            Ui.label(this, getString(R.string.empty_tagline), 11.5f, Ui.TEXT_FAINT).apply {
                gravity = Gravity.CENTER
                isSingleLine = false
                maxLines = 2
                setPadding(0, Ui.dp(this@MainActivity, 18f), 0, 0)
            },
        )
        return android.widget.ScrollView(this).apply {
            setBackgroundColor(Ui.BACKGROUND)
            addView(column)
        }
    }

    /* ------------------------------------------------------------ chrome bits */

    /** Keeps the zoom readout honest while gestures change the viewport. */
    private fun updateZoomChip() {
        val chip = zoomChip ?: return
        val zoom = editor.currentZoom()
        val percent = (zoom * 100f).roundToInt()
        chip.text = if (percent >= 100) "$percent%" else "${(zoom * 100f).roundToInt()}%"
        chip.setTextColor(if (percent == 100) Ui.TEXT_DIM else Ui.TEXT)
    }

    /** Paints the current colour onto the dock swatch button. */
    private fun refreshSwatch() {
        val button = swatchButton ?: return
        val chip = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(paintColor)
            setStroke(Ui.dp(this@MainActivity, 2f), Ui.OUTLINE)
        }
        button.setImageDrawable(chip)
        button.scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
        button.setPadding(Ui.dp(this@MainActivity, 13f), Ui.dp(this@MainActivity, 13f), Ui.dp(this@MainActivity, 13f), Ui.dp(this@MainActivity, 13f))
    }

    private fun snack(message: String) {
        Ui.snack(root, this, message)
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
            val active = each == tool
            button.setImageDrawable(GlyphDrawable(glyphOf(each), if (active) Ui.ACCENT else Ui.TEXT_DIM))
            button.background = if (active) {
                Ui.rounded(Ui.ACCENT_SOFT, Ui.dp(this, 14f).toFloat())
            } else {
                Ui.rounded(android.graphics.Color.TRANSPARENT, Ui.dp(this, 14f).toFloat())
            }
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
                val bitmap = decodeImageScaled(uri)
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
                    val bitmap = flattenToBitmap(session, scale, if (format == ExportFormat.Jpeg) Argb.OPAQUE_WHITE else 0)
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

    /**
     * Composites the document straight into an export-sized bitmap.
     *
     * The old version allocated a **document-sized** `IntArray` (192 MB for a
     * 48 MPix poster) and then nearest-neighbour scaled it. [Render.flattenScaled]
     * composites band by band and box-filters into the target buffer, so peak
     * memory is the exported bitmap and a couple of megapixels of scratch.
     */
    private fun flattenToBitmap(session: Session, scale: Float, background: Int): Bitmap {
        val doc = session.doc
        var effective = scale.coerceIn(0.01f, 1f)
        val target = doc.width.toDouble() * doc.height.toDouble() * effective.toDouble() * effective.toDouble()
        if (target > MAX_EXPORT_PIXELS) {
            effective *= kotlin.math.sqrt(MAX_EXPORT_PIXELS.toDouble() / target).toFloat()
        }
        val width = max(1, (doc.width * effective).roundToInt())
        val height = max(1, (doc.height * effective).roundToInt())
        val pixels = IntArray(width * height)
        Render.flattenScaled(doc, Rect(0, 0, doc.width, doc.height), pixels, width, height, background)
        return AndroidText.toBitmap(pixels, width, height)
    }

    /**
     * Decodes an image file, downsampling while decoding.
     *
     * `BitmapFactory.decodeStream` on a modern 50 MPix camera JPEG allocates
     * ~200 MB and takes the whole app down; measuring the bounds first and
     * setting `inSampleSize` keeps the decoded bitmap inside [MAX_IMPORT_PIXELS].
     * If even that fails, the sample size is doubled and retried.
     */
    private fun decodeImageScaled(uri: Uri): Bitmap {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
            ?: throw IllegalStateException(getString(R.string.error_unreadable))
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw IllegalStateException(getString(R.string.error_unreadable))
        }
        val options = android.graphics.BitmapFactory.Options()
        options.inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight)
        options.inPreferredConfig = Bitmap.Config.ARGB_8888
        var attempt = 0
        while (true) {
            try {
                return contentResolver.openInputStream(uri)?.use {
                    android.graphics.BitmapFactory.decodeStream(it, null, options)
                } ?: throw IllegalStateException(getString(R.string.error_unreadable))
            } catch (oom: OutOfMemoryError) {
                if (attempt >= 3) throw OutOfMemoryError(getString(R.string.canvas_out_of_memory))
                attempt++
                options.inSampleSize = max(2, options.inSampleSize * 2)
            }
        }
    }

    /** The power-of-two downsample factor that keeps a decoded image within budget. */
    private fun sampleSizeFor(width: Int, height: Int): Int {
        var sample = 1
        while (width.toLong() * height.toLong() / (sample.toLong() * sample) > MAX_IMPORT_PIXELS && sample < 64) {
            sample *= 2
        }
        return sample
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
        if (session == null) {
            status.text = getString(R.string.no_document)
            statusDetail.text = getString(R.string.app_name)
        } else {
            val document = session.doc
            val dirty = if (document.dirty) "  •" else ""
            status.text = session.title + dirty
            // The meta line is where the awkward facts live: real pixel size vs the
            // resolution the document was actually opened at, and the live zoom.
            val meta = StringBuilder("${document.width} × ${document.height} px")
            if (document.importStride > 1 && document.sourceWidth > 0) {
                meta.append("  ·  ")
                meta.append(getString(R.string.opened_downscaled, document.sourceWidth, document.sourceHeight))
            }
            meta.append("  ·  ")
            meta.append((editor.currentZoom() * 100f).roundToInt()).append('%')
            statusDetail.text = meta.toString()
        }
        emptyState.visibility = if (session == null) View.VISIBLE else View.GONE
        editor.invalidateContent()
    }

    private fun toast(message: String) {
        // Snackbars, not system toasts: the app has its own voice and its own theme.
        if (::root.isInitialized) Ui.snack(root, this, message)
        else Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
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
