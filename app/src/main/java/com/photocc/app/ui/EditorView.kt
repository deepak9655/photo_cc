package com.photocc.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import com.photocc.app.core.Argb
import com.photocc.app.core.GroupLayer
import com.photocc.app.core.PsdDocument
import com.photocc.app.core.RasterLayer
import com.photocc.app.core.Rect
import com.photocc.app.core.Render
import com.photocc.app.core.TextGeometry
import com.photocc.app.core.TextLayer
import com.photocc.app.core.layerBounds
import com.photocc.app.doc.PixelEdit
import com.photocc.app.doc.Session
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The canvas.
 *
 * ### How it renders without dying on a phone
 *
 * The composited buffer is sized to **the screen, not the document**. A 108 MPix
 * poster on a 1080p phone composites into ~2 Mpix (~8 MB), and the document is
 * composited in horizontal bands that are box-filtered down to screen size
 * (`Render.flattenScaled`). Earlier versions sized this buffer to the *visible
 * document region*, which at "fit to screen" meant the whole document — 48 MB of
 * `IntArray` plus a 48 MB `Bitmap` for a 12 MPix photo, and an instant
 * `Failed to allocate … byte allocation` crash on a phone. That is gone: buffer
 * size now depends only on the screen.
 *
 * Zooming in still composites 1:1 (crisp pixels, tiny buffer); only zoomed-out
 * rendering is downscaled. Buffers are reused, sizes are quantised so a pinch
 * does not reallocate every frame, and an allocation failure degrades the
 * preview quality instead of killing the app.
 *
 * ### Touch
 *
 * One finger does whatever the tool does; two fingers *always* pan and zoom, and
 * keep the document point under the fingers fixed. Lifting one finger resumes
 * the tool. Double-tap fits the document to the screen.
 */
class EditorView(context: Context) : View(context) {

    enum class Tool(val label: String, val glyph: Glyph) {
        MOVE("Move", Glyph.Move),
        BRUSH("Brush", Glyph.Brush),
        ERASER("Eraser", Glyph.Eraser),
        FILL("Fill", Glyph.Fill),
        EYEDROPPER("Pick", Glyph.EyeDropper),
        TEXT("Text", Glyph.Text),
        CROP("Crop", Glyph.Crop),
    }

    var session: Session? = null

    var tool: Tool = Tool.BRUSH
        set(value) {
            field = value
            cursorVisible = value == Tool.BRUSH || value == Tool.ERASER
            invalidate()
        }

    var brushSize: Float = 24f
    var brushHardness: Float = 0.8f
    var brushFlow: Float = 1f
    var paintColor: Int = Argb.pack(255, 255, 255, 255)
    var fillTolerance: Int = 32

    var onMessage: ((String) -> Unit)? = null
    var onTextTapped: ((TextLayer) -> Unit)? = null
    var onPickColor: ((Int) -> Unit)? = null
    var onViewportChanged: ((Float) -> Unit)? = null

    /** Set while a save/export runs so the canvas can say so. */
    var busy: Boolean = false

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = 0x33FFFFFF
    }
    private val accent = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = 0xFFFFB74D.toInt()
    }
    private val checker = Paint()
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66000000 }
    private val dim = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x9E000000.toInt() }
    private val handle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFF9E3D.toInt() }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xE6FFFFFF.toInt()
        textSize = 13f * resources.displayMetrics.scaledDensity
    }

    private var zoom = 1f
    private var panX = 0f
    private var panY = 0f
    private var fitted = false

    /** The document rectangle the buffer currently holds. */
    private var region = Rect(0, 0, 0, 0)
    private var buffer: IntArray = IntArray(0)
    private var bitmap: Bitmap? = null
    private var dirty = true
    private var failed = false

    private var activePointerId = -1
    private var lastTapTime = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var downX = 0f
    private var downY = 0f
    private var strokeEdit: PixelEdit? = null
    private var strokeLayer: RasterLayer? = null
    private var movedSinceDown = false
    private var cropRect: Rect? = null
    private var gestureStartZoom = 1f
    private var gestureStartDistance = 0f
    private var gestureDocX = 0f
    private var gestureDocY = 0f
    private var twoFinger = false
    private var cursorX = 0f
    private var cursorY = 0f
    private var cursorVisible = false

    /** Preview quality after an allocation failure (1 = full screen resolution). */
    private var quality = 1f

    /* --------------------------------------------------------------- layout */

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (!fitted && w > 0 && h > 0) {
            fitToScreen()
            fitted = true
        }
        dirty = true
    }

    fun invalidateContent() {
        dirty = true
        invalidate()
    }

    fun fitToScreen() {
        val doc = session?.doc ?: return
        if (width == 0 || height == 0 || doc.width == 0 || doc.height == 0) return
        val scale = min(width.toFloat() / doc.width, height.toFloat() / doc.height)
        zoom = (if (scale > 0f) scale else 1f).coerceIn(0.02f, 8f)
        centerOn(doc.width / 2f, doc.height / 2f)
        dirty = true
        invalidate()
    }

    fun zoomTo100Percent() {
        val doc = session?.doc ?: return
        zoom = 1f
        centerOn(doc.width / 2f, doc.height / 2f)
        dirty = true
        invalidate()
    }

    fun currentZoom(): Float = zoom

    /** Zooming out is bounded so the document can never become a postage stamp. */
    private fun minimumZoom(): Float {
        val doc = session?.doc ?: return 0.02f
        if (width == 0 || height == 0 || doc.width == 0 || doc.height == 0) return 0.02f
        val fit = min(width.toFloat() / doc.width, height.toFloat() / doc.height)
        return (fit * 0.5f).coerceIn(0.02f, 1f)
    }

    /**
     * Multiplies the zoom, keeping whatever is in the middle of the screen in
     * the middle of the screen — that is what people expect from a "+" button.
     */
    fun zoomBy(factor: Float) {
        val doc = session?.doc ?: return
        if (width == 0 || height == 0) return
        val (docX, docY) = toDocument(width / 2f, height / 2f)
        zoom = (zoom * factor).coerceIn(minimumZoom(), 8f)
        centerOn(docX, docY)
        dirty = true
        invalidate()
    }

    /** Bytes currently held by the canvas buffer, for the info sheet. */
    fun bufferBytes(): Int = buffer.size * 4

    private fun centerOn(docX: Float, docY: Float) {
        panX = width / 2f - docX * zoom
        panY = height / 2f - docY * zoom
    }

    private fun toDocument(screenX: Float, screenY: Float): Pair<Float, Float> =
        (screenX - panX) / zoom to (screenY - panY) / zoom

    private fun clampPan() {
        val doc = session?.doc ?: return
        val margin = max(width, height) * 0.8f
        panX = panX.coerceIn(-(doc.width * zoom) - margin, width + margin)
        panY = panY.coerceIn(-(doc.height * zoom) - margin, height + margin)
    }

    /* --------------------------------------------------------------- drawing */

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Ui.CANVAS_BACKGROUND)

        val session = session
        val doc = session?.doc
        if (session == null || doc == null || width <= 0 || height <= 0) return

        if (!ensureBuffers()) {
            canvas.drawText(context.getString(com.photocc.app.R.string.canvas_out_of_memory), 24f, height / 2f, text)
            return
        }

        if (dirty) {
            Render.flattenScaled(doc, region, buffer, outW, outH, background = 0)
            bitmap?.setPixels(buffer, 0, outW, 0, 0, outW, outH)
            dirty = false
        }

        val left = region.x * zoom + panX
        val top = region.y * zoom + panY
        val right = (region.x + region.w) * zoom + panX
        val bottom = (region.y + region.h) * zoom + panY

        // A soft drop shadow under the document, then the checkerboard, then the pixels.
        canvas.drawRect(left + 6f, top + 8f, right + 8f, bottom + 10f, shadow)
        canvas.drawRect(left + 3f, top + 4f, right + 4f, bottom + 5f, shadow)
        checker.color = Ui.CHECKER_DARK
        canvas.drawRect(left, top, right, bottom, checker)
        checker.color = Ui.CHECKER_LIGHT
        val cell = 8f
        var y = top
        var row = 0
        while (y < bottom) {
            var x = if (row % 2 == 0) left else left + cell
            while (x < right) {
                canvas.drawRect(x, y, min(x + cell, right), min(y + cell, bottom), checker)
                x += cell * 2
            }
            y += cell
            row++
        }
        // Android has no RectF/RectF drawBitmap overload; a null source rect means "all of it".
        bitmap?.let { canvas.drawBitmap(it, null, RectF(left, top, right, bottom), paint) }
        canvas.drawRect(left, top, right, bottom, outline)

        // The selected layer's outline, so "what am I editing" is never a guess.
        session.selected?.let { layer ->
            if (layer !is GroupLayer) {
                layerBounds(layer)?.let { bounds ->
                    accent.color = Ui.ACCENT
                    canvas.drawRect(
                        bounds.x * zoom + panX,
                        bounds.y * zoom + panY,
                        (bounds.x + bounds.w) * zoom + panX,
                        (bounds.y + bounds.h) * zoom + panY,
                        accent,
                    )
                }
            }
        }

        // Crop: dim what will be thrown away, frame what will be kept, and put
        // real handles on the corners so the rectangle can be judged by eye.
        cropRect?.let { rect ->
            val cl = rect.x * zoom + panX
            val ct = rect.y * zoom + panY
            val cr = (rect.x + rect.w) * zoom + panX
            val cb = (rect.y + rect.h) * zoom + panY
            val topEdge = ct.coerceIn(top, bottom)
            val bottomEdge = cb.coerceIn(top, bottom)
            val leftEdge = cl.coerceIn(left, right)
            val rightEdge = cr.coerceIn(left, right)
            canvas.drawRect(left, top, right, topEdge, dim)
            canvas.drawRect(left, bottomEdge, right, bottom, dim)
            canvas.drawRect(left, topEdge, leftEdge, bottomEdge, dim)
            canvas.drawRect(rightEdge, topEdge, right, bottomEdge, dim)
            accent.color = Ui.ACCENT
            accent.strokeWidth = 2f
            canvas.drawRect(cl, ct, cr, cb, accent)
            accent.strokeWidth = 1f
            for (cx in floatArrayOf(cl, cr)) {
                for (cy in floatArrayOf(ct, cb)) canvas.drawCircle(cx, cy, 7f, handle)
            }
        }

        if (cursorVisible && !twoFinger) {
            outline.color = 0xCCFFFFFF.toInt()
            outline.strokeWidth = 1.5f
            canvas.drawCircle(cursorX * zoom + panX, cursorY * zoom + panY, (brushSize / 2f) * zoom, outline)
            outline.strokeWidth = 1f
        }

        if (busy) {
            canvas.drawColor(0x99000000.toInt())
            canvas.drawText(context.getString(com.photocc.app.R.string.working), 32f, height - 56f, text)
        }
    }

    /* --------------------------------------------------------- buffer sizing */

    private var outW = 0
    private var outH = 0

    /**
     * Chooses the output resolution and allocates the buffer + bitmap.
     *
     * Zoomed out → one buffer pixel per *screen* pixel (the document is
     * downscaled by the compositor). Zoomed in → one buffer pixel per document
     * pixel, which stays small because less of the document is visible.
     */
    private fun ensureBuffers(): Boolean {
        val viewW = max(1, width)
        val viewH = max(1, height)
        val zoomedIn = zoom > 1f
        val wantedW = if (zoomedIn) quantise(ceil(viewW / zoom).toInt() + 1) else viewW
        val wantedH = if (zoomedIn) quantise(ceil(viewH / zoom).toInt() + 1) else viewH

        if (wantedW != outW || wantedH != outH || bitmap == null) {
            if (!allocate(wantedW, wantedH)) return false
            outW = wantedW
            outH = wantedH
            dirty = true
        }

        // The document rectangle this buffer covers: it is exactly `out / zoom`
        // starting where the screen starts, so the mapping to the view is stable.
        region = Rect(
            floor(-panX / zoom).toInt(),
            floor(-panY / zoom).toInt(),
            max(1, ceil(outW / zoom).toInt()),
            max(1, ceil(outH / zoom).toInt()),
        )
        return true
    }

    private fun quantise(value: Int): Int = ((value + 63) / 64) * 64

    private fun allocate(w: Int, h: Int): Boolean {
        val width = max(1, w)
        val height = max(1, h)
        val pixels = width.toLong() * height.toLong()
        // Never let a transient allocation exceed ~96 MB of ARGB pixels.
        if (pixels * 4 > 96L * 1024 * 1024) {
            failed = true
            return false
        }
        return try {
            val newBuffer = IntArray(width * height)
            val newBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap?.recycle()
            buffer = newBuffer
            bitmap = newBitmap
            failed = false
            true
        } catch (_: OutOfMemoryError) {
            // One retry at reduced preview quality, then fail gracefully: a soft
            // preview beats a crash.
            if (quality > 0.5f) {
                quality = 0.5f
                val halfW = max(1, width / 2)
                val halfH = max(1, height / 2)
                try {
                    val newBuffer = IntArray(halfW * halfH)
                    val newBitmap = Bitmap.createBitmap(halfW, halfH, Bitmap.Config.ARGB_8888)
                    bitmap?.recycle()
                    buffer = newBuffer
                    bitmap = newBitmap
                    outW = halfW
                    outH = halfH
                    failed = false
                    return true
                } catch (_: OutOfMemoryError) {
                    failed = true
                    return false
                }
            }
            failed = true
            false
        }
    }

    /* --------------------------------------------------------------- touches */

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val session = session ?: return false
        val doc = session.doc

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val now = SystemClock.uptimeMillis()
                if (now - lastTapTime < 320 && hypot(event.x - lastTapX, event.y - lastTapY) < 80f) {
                    lastTapTime = 0
                    fitToScreen()
                    return true
                }
                lastTapTime = now
                lastTapX = event.x
                lastTapY = event.y
                activePointerId = event.getPointerId(0)
                twoFinger = false
                movedSinceDown = false
                val (x, y) = toDocument(event.x, event.y)
                downX = x
                downY = y
                lastX = x
                lastY = y
                cursorX = x
                cursorY = y
                onDown(session, x, y)
                invalidate()
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (!twoFinger) endToolGesture(session)
                twoFinger = true
                cursorVisible = false
                movedSinceDown = true
                beginGesture(event)
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (twoFinger || event.pointerCount > 1) {
                    updateGesture(event)
                    return true
                }
                val (x, y) = toDocument(event.x, event.y)
                lastX = x
                lastY = y
                cursorX = x
                cursorY = y
                if (hypot(x - downX, y - downY) > 1.5f) movedSinceDown = true
                onMove(session, doc, x, y)
                invalidate()
                return true
            }

            MotionEvent.ACTION_POINTER_UP -> {
                if (event.pointerCount > 1) beginGesture(event, event.actionIndex)
                return true
            }

            MotionEvent.ACTION_UP -> {
                val (x, y) = toDocument(event.x, event.y)
                cursorVisible = tool == Tool.BRUSH || tool == Tool.ERASER
                if (!twoFinger) onUp(session, doc, x, y)
                endToolGesture(session)
                activePointerId = -1
                invalidate()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                cursorVisible = false
                endToolGesture(session)
                activePointerId = -1
                invalidate()
                return true
            }

            else -> return true
        }
    }

    private fun pointerDistance(event: MotionEvent, skipIndex: Int = -1): Float {
        val indices = (0 until event.pointerCount).filter { it != skipIndex }
        if (indices.size < 2) return 0f
        val dx = event.getX(indices[1]) - event.getX(indices[0])
        val dy = event.getY(indices[1]) - event.getY(indices[0])
        return hypot(dx, dy)
    }

    private fun pointerMidpoint(event: MotionEvent, skipIndex: Int = -1): Pair<Float, Float> {
        val indices = (0 until event.pointerCount).filter { it != skipIndex }
        if (indices.isEmpty()) return event.x to event.y
        var sumX = 0f
        var sumY = 0f
        for (index in indices) {
            sumX += event.getX(index)
            sumY += event.getY(index)
        }
        return sumX / indices.size to sumY / indices.size
    }

    private fun beginGesture(event: MotionEvent, skipPointerIndex: Int = -1) {
        gestureStartDistance = pointerDistance(event, skipPointerIndex)
        gestureStartZoom = zoom
        val (midX, midY) = pointerMidpoint(event, skipPointerIndex)
        val (docX, docY) = toDocument(midX, midY)
        gestureDocX = docX
        gestureDocY = docY
    }

    private fun updateGesture(event: MotionEvent) {
        val distance = pointerDistance(event)
        if (gestureStartDistance > 0f && distance > 0f) {
            zoom = (gestureStartZoom * (distance / gestureStartDistance)).coerceIn(minimumZoom(), 8f)
        }
        val (midX, midY) = pointerMidpoint(event)
        panX = midX - gestureDocX * zoom
        panY = midY - gestureDocY * zoom
        clampPan()
        dirty = true
        onViewportChanged?.invoke(zoom)
        invalidate()
    }

    /* ----------------------------------------------------------------- tools */

    private fun onDown(session: Session, x: Float, y: Float) {
        when (tool) {
            Tool.BRUSH, Tool.ERASER -> beginStroke(session, x, y)
            Tool.FILL -> {
                val layer = session.selected as? RasterLayer
                if (layer == null) {
                    onMessage?.invoke(context.getString(com.photocc.app.R.string.need_raster_layer))
                    return
                }
                if (layer.locked) {
                    onMessage?.invoke(context.getString(com.photocc.app.R.string.layer_locked))
                    return
                }
                val edit = PixelEdit(layer)
                layer.pixels?.let { edit.before(Rect(layer.x, layer.y, it.width, it.height)) }
                val changed = Render.floodFill(layer, x.toInt(), y.toInt(), paintColor, fillTolerance)
                session.commitPixelEdit(edit, "Fill")
                if (changed == null) session.changed()
            }
            Tool.EYEDROPPER -> {
                val index = sampleIndex(x, y)
                if (index >= 0) onPickColor?.invoke(buffer[index])
            }
            Tool.TEXT -> Unit
            Tool.CROP -> cropRect = Rect(x.toInt(), y.toInt(), 0, 0)
            Tool.MOVE -> {
                val layer = session.selected
                if (layer != null && !layer.locked) session.beginStructuralEdit("Move layer")
            }
        }
    }

    private fun beginStroke(session: Session, x: Float, y: Float) {
        val layer = session.selected as? RasterLayer
        if (layer == null) {
            onMessage?.invoke(context.getString(com.photocc.app.R.string.need_raster_layer))
            return
        }
        if (layer.locked) {
            onMessage?.invoke(context.getString(com.photocc.app.R.string.layer_locked))
            return
        }
        if (layer.pixels == null) return
        val edit = PixelEdit(layer)
        strokeEdit = edit
        strokeLayer = layer
        applyStamp(layer, edit, x, y)
    }

    private fun applyStamp(layer: RasterLayer, edit: PixelEdit, x: Float, y: Float) {
        val radius = brushSize / 2f
        val padded = radius + 2f
        val size = ceil(padded * 2f).toInt() + 2
        edit.before(Rect(floor(x - padded).toInt(), floor(y - padded).toInt(), size, size))
        if (tool == Tool.ERASER) {
            Render.eraseStamp(layer, x, y, radius, brushHardness, brushFlow)
        } else {
            Render.paintStamp(layer, x, y, radius, brushHardness, paintColor, brushFlow)
        }
    }

    private fun onMove(session: Session, doc: PsdDocument, x: Float, y: Float) {
        when (tool) {
            Tool.BRUSH, Tool.ERASER -> {
                val layer = strokeLayer ?: return
                val edit = strokeEdit ?: return
                val radius = brushSize / 2f + 2f
                edit.before(
                    Rect(
                        floor(min(lastX, x) - radius).toInt(),
                        floor(min(lastY, y) - radius).toInt(),
                        ceil(abs(x - lastX) + radius * 2f).toInt() + 2,
                        ceil(abs(y - lastY) + radius * 2f).toInt() + 2,
                    ),
                )
                Render.paintLine(
                    layer, lastX, lastY, x, y,
                    brushSize / 2f, brushHardness, paintColor, brushFlow,
                    erase = tool == Tool.ERASER,
                )
                invalidateContent()
            }

            Tool.MOVE -> {
                val layer = session.selected ?: return
                val dx = (x - lastX).roundToInt()
                val dy = (y - lastY).roundToInt()
                if (dx == 0 && dy == 0) return
                when (layer) {
                    is RasterLayer -> {
                        layer.x += dx
                        layer.y += dy
                    }
                    is TextLayer -> {
                        layer.x += dx
                        layer.y += dy
                        layer.textDirty = true
                    }
                    else -> Unit
                }
                movedSinceDown = true
                session.changed()
                invalidateContent()
            }

            Tool.CROP -> cropRect = Rect.ofPoints(downX, downY, x, y)
            Tool.TEXT, Tool.EYEDROPPER, Tool.FILL -> Unit
        }
    }

    private fun onUp(session: Session, doc: PsdDocument, x: Float, y: Float) {
        when (tool) {
            Tool.BRUSH, Tool.ERASER -> {
                val edit = strokeEdit
                if (edit != null) {
                    val layer = strokeLayer
                    if (layer != null && (abs(x - lastX) > 0.1f || abs(y - lastY) > 0.1f)) {
                        applyStamp(layer, edit, x, y)
                    }
                    session.commitPixelEdit(edit, if (tool == Tool.ERASER) "Erase" else "Brush")
                }
                strokeEdit = null
                strokeLayer = null
                invalidateContent()
            }

            Tool.TEXT -> {
                if (!movedSinceDown) {
                    val hit = hitTestText(session, x, y)
                    if (hit != null) {
                        session.select(hit.id)
                        onTextTapped?.invoke(hit)
                    } else {
                        onTextTapped?.invoke(TextLayer(x = x, y = y).apply {
                            text.text = ""
                            textDirty = true
                        })
                    }
                }
            }

            Tool.MOVE -> session.endStructuralEdit("Move layer")

            Tool.CROP -> {
                val rect = cropRect
                cropRect = null
                if (rect != null && rect.w > 8 && rect.h > 8) {
                    session.crop(rect)
                    invalidateContent()
                }
            }

            Tool.EYEDROPPER, Tool.FILL -> Unit
        }
    }

    /**
     * Ends the in-progress gesture. A stroke painted a moment ago stays painted
     * *and* undoable: silently dropping pixels that already changed would be
     * worse than keeping them.
     */
    private fun endToolGesture(session: Session) {
        val edit = strokeEdit
        if (edit != null) {
            session.commitPixelEdit(edit, if (tool == Tool.ERASER) "Erase" else "Brush")
            strokeEdit = null
            strokeLayer = null
            invalidateContent()
        }
        if (tool == Tool.MOVE) session.endStructuralEdit("Move layer")
        cropRect = null
    }

    private fun sampleIndex(x: Float, y: Float): Int {
        val cx = floor(x).toInt() - region.x
        val cy = floor(y).toInt() - region.y
        if (cx < 0 || cy < 0 || cx >= outW || cy >= outH) return -1
        return cy * outW + cx
    }

    private fun hitTestText(session: Session, x: Float, y: Float): TextLayer? {
        var hit: TextLayer? = null
        session.doc.walkLayers { layer, _ ->
            if (hit == null && layer is TextLayer && layer.visible) {
                val bounds = TextGeometry.measure(layer).bounds
                if (x >= bounds.x && x < bounds.right && y >= bounds.y && y < bounds.bottom) hit = layer
            }
        }
        return hit
    }
}
