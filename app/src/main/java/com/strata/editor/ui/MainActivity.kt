package com.strata.editor.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import com.strata.editor.core.ImageFormat
import com.strata.editor.platform.Platform
import java.io.File
import java.util.concurrent.Executors

/**
 * The activity: three stacked views — the bar, the canvas and the controls — plus
 * everything that touches the outside world (opening, saving, exporting), which
 * all goes through the system file picker so the app needs no permissions.
 *
 * File reading and writing happen on a background thread, and the result is handed
 * back to the document on the main thread: a big PSD takes a moment to parse, and
 * no camera-roll JPEG is worth freezing the interface over.
 */
class MainActivity : Activity() {

    private lateinit var state: EditorState
    private lateinit var editor: EditorView
    private lateinit var chrome: ChromeView
    private lateinit var topBar: TopBarView
    private lateinit var input: EditText
    private val io = Executors.newSingleThreadExecutor()
    private var pendingUri: Uri? = null
    private var pendingSaveAs = false

    companion object {
        private const val REQUEST_OPEN = 4101
        private const val REQUEST_SAVE = 4102
        private const val REQUEST_PLACE = 4103
        private const val REQUEST_EXPORT = 4104

        /** Reading the whole file into memory is the only way in, so cap it. */
        private const val MAX_FILE_BYTES = 384L * 1024L * 1024L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installCrashHandler()
        Platform.install(this)
        Palette.install(this)
        state = EditorState()
        input = EditText(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Palette.background)
        }
        topBar = TopBarView(this)
        editor = EditorView(this)
        chrome = ChromeView(this)
        chrome.attach(state, editor)
        root.addView(topBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(editor, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(chrome, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
        setContentView(root)

        editor.state = state
        topBar.state = state
        topBar.editor = editor
        topBar.onPanelRequested = { resizeChrome() }
        chrome.onPanelToggled = { resizeChrome() }
        input.apply {
            setBackgroundColor(Color.TRANSPARENT)
            setTextColor(Color.TRANSPARENT)
            setCursorVisible(false)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI or
                android.view.inputmethod.EditorInfo.IME_FLAG_NO_FULLSCREEN
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    state.setTextDraft(s?.toString().orEmpty())
                }
            })
        }

        wireState()
        handleIntent(intent)
        reportCrashIfAny()
    }

    private fun wireState() {
        state.onInvalidate = { rect -> editor.documentChanged(rect) }
        state.onPanelsChanged = {
            topBar.invalidate()
            chrome.invalidate()
        }
        state.onThumbnailsChanged = { chrome.invalidate() }
        state.onFitRequest = {
            editor.post { editor.fitToWindow() }
        }
        state.onFileAction = { action ->
            when (action) {
                EditorState.FileAction.NEW_DOCUMENT -> newDocument()
                EditorState.FileAction.OPEN -> openDocument()
                EditorState.FileAction.SAVE -> saveDocument()
                EditorState.FileAction.SAVE_AS -> saveDocumentAs()
                EditorState.FileAction.EXPORT -> exportImage()
                EditorState.FileAction.PLACE_IMAGE -> placeImage()
            }
        }
        state.onTextInputFocus = { focusText() }
        editor.onStatus = { message ->
            state.report(message)
            topBar.showMessage(message)
        }
    }

    /* ------------------------------------------------------------- file pickers */

    private fun openDocument() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "application/octet-stream", "application/x-photoshop", "*/*"))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, REQUEST_OPEN)
    }

    private fun placeImage() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
        }
        startActivityForResult(intent, REQUEST_PLACE)
    }

    private fun saveDocument() {
        val uri = state.fileUri
        if (uri != null && state.fileIsPsd) {
            saveToUri(uri)
        } else {
            saveDocumentAs()
        }
    }

    private fun saveDocumentAs() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_TITLE, if (state.fileName.endsWith(".psd", true)) state.fileName else state.fileName + ".psd")
        }
        startActivityForResult(intent, REQUEST_SAVE)
    }

    private fun exportImage() {
        val format = state.pendingExportFormat
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = format.mime
            putExtra(Intent.EXTRA_TITLE, state.exportedName(format))
        }
        startActivityForResult(intent, REQUEST_EXPORT)
    }

    private fun newDocument() {
        state.newDocument(1080, 1350, com.strata.editor.core.Colors.WHITE)
        editor.fitToWindow()
    }

    @Deprecated("The system picker is the only way in, and it is a plain result.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        when (requestCode) {
            REQUEST_OPEN -> {
                try {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (error: Exception) {
                    // Not every provider offers a lasting grant; reading once is fine.
                }
                readBytes(uri) { bytes, name -> state.openBytes(bytes, name, uri) }
            }
            REQUEST_PLACE -> readBytes(uri) { bytes, name -> state.importAsLayer(bytes, name) }
            REQUEST_SAVE -> {
                state.fileUri = uri
                state.fileIsPsd = true
                state.fileName = nameOf(uri)
                saveToUri(uri)
            }
            REQUEST_EXPORT -> writeBytesForExport(uri)
        }
    }

    /** Called when the app is started by "open with" rather than by its icon. */
    private fun handleIntent(intent: Intent?) {
        val uri = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
            else -> null
        } ?: return
        readBytes(uri) { bytes, name -> state.openBytes(bytes, name, uri) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun nameOf(uri: Uri): String {
        var name: String? = null
        try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) name = cursor.getString(index)
            }
        } catch (error: Exception) {
            name = null
        }
        return name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "untitled.psd"
    }

    /** The size a file claims to be, or -1 when the provider will not say. */
    private fun sizeOf(uri: Uri): Long = try {
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
            if (index >= 0 && cursor.moveToFirst()) cursor.getLong(index) else -1L
        } ?: -1L
    } catch (error: Exception) {
        -1L
    }

    private fun readBytes(uri: Uri, then: (ByteArray, String) -> Unit) {
        state.reportBusy("Opening…")
        val name = nameOf(uri)
        val declared = sizeOf(uri)
        if (declared > MAX_FILE_BYTES) {
            state.reportBusy(null)
            state.openFailed("That file is ${declared / (1024 * 1024)} MB — too big to open on a phone")
            return
        }
        io.execute {
            var bytes: ByteArray? = null
            var failure: String? = null
            try {
                bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
            } catch (error: OutOfMemoryError) {
                failure = "That file is too big for this device's memory"
            } catch (error: Exception) {
                failure = "Could not read the file: ${error.message}"
            }
            val data = bytes
            runOnUiThread {
                state.reportBusy(null)
                if (data == null || data.isEmpty()) {
                    state.openFailed(failure ?: "That file is empty")
                    return@runOnUiThread
                }
                try {
                    then(data, name)
                    editor.fitToWindow()
                } catch (error: OutOfMemoryError) {
                    state.openFailed("Not enough memory to open that file")
                } catch (error: Exception) {
                    state.openFailed("Could not open ${name}: ${error.message}")
                }
                resizeChrome()
            }
        }
    }

    /**
     * The PSD is built on the main thread, because it reads the live document and
     * a background read could catch a half-finished edit. Writing the finished
     * bytes then happens off the main thread, which is where the slow part is.
     */
    private fun saveToUri(uri: Uri) {
        state.reportBusy("Saving…")
        val bytes = state.saveBytes()
        if (bytes == null) {
            state.reportBusy(null)
            return
        }
        io.execute {
            val failure = writeTo(uri, bytes)
            runOnUiThread {
                state.reportBusy(null)
                if (failure != null) state.report(failure, true)
                else topBar.showMessage("Saved ${state.fileName}")
            }
        }
    }

    private fun writeBytesForExport(uri: Uri) {
        val format = state.pendingExportFormat
        state.reportBusy("Exporting…")
        val bytes = state.exportBytes(format)
        if (bytes == null) {
            state.reportBusy(null)
            return
        }
        io.execute {
            val failure = writeTo(uri, bytes)
            runOnUiThread {
                state.reportBusy(null)
                if (failure != null) state.report(failure, true)
                else topBar.showMessage("Exported ${state.exportedName(format)}")
            }
        }
    }

    /** @return a message when the write failed, null when it worked. */
    private fun writeTo(uri: Uri, bytes: ByteArray): String? = try {
        contentResolver.openOutputStream(uri, "wt")?.use { stream ->
            stream.write(bytes)
            stream.flush()
            null
        } ?: "The system refused to write there"
    } catch (error: OutOfMemoryError) {
        "Not enough memory to write the file"
    } catch (error: Exception) {
        "Could not write the file: ${error.message}"
    }


    /* ------------------------------------------------------------------- text */

    private fun focusText() {
        val draft = state.textDraft ?: return
        input.setText(draft.text)
        input.setSelection(input.text.length)
        input.requestFocus()
        val manager = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        manager.showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        resizeChrome()
    }

    override fun onBackPressed() {
        when {
            state.panel != Panel.NONE -> {
                if (state.panel == Panel.TEXT) state.closeTextEditor(commit = true)
                state.panel = Panel.NONE
                resizeChrome()
            }
            state.document.dirty -> {
                Toast.makeText(this, "Use File ▸ Save to keep your work", Toast.LENGTH_SHORT).show()
                @Suppress("DEPRECATION")
                super.onBackPressed()
            }
            else -> {
                @Suppress("DEPRECATION")
                super.onBackPressed()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        // A stroke in progress would otherwise be left half-recorded.
        state.finishPendingEdits()
        state.commitPaint()
    }

    override fun onDestroy() {
        super.onDestroy()
        io.shutdown()
    }

    private fun resizeChrome() {
        chrome.requestLayout()
        chrome.invalidate()
        topBar.invalidate()
        editor.requestLayout()
    }

    /* ------------------------------------------------------------- crash log */

    /**
     * A crash should be reportable, not mysterious. The trace is written to the
     * app's own directory — no permissions involved — and the next launch says so.
     */
    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val file = File(filesDir, "last-crash.txt")
                file.writeText(
                    "Strata crash\n" +
                        "Thread: ${thread.name}\n" +
                        "Time: ${java.util.Date()}\n" +
                        "Version: ${packageManager.getPackageInfo(packageName, 0).versionName}\n\n" +
                        error.stackTraceToString(),
                )
            } catch (ignored: Throwable) {
                // Nothing we can do here; the trace still goes to the log.
            }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun reportCrashIfAny() {
        val file = File(filesDir, "last-crash.txt")
        if (!file.exists()) return
        val text = try {
            file.readText()
        } catch (error: Exception) {
            null
        } ?: return
        file.delete()
        val firstLine = text.lines().firstOrNull { it.contains("Exception") || it.contains("Error") } ?: "unknown error"
        topBar.showMessage("Last run stopped: ${firstLine.trim().take(70)}")
        android.util.Log.w("Strata", text)
    }
}
