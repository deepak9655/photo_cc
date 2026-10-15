package com.photocc.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import com.photocc.app.core.Argb
import com.photocc.app.core.GroupLayer
import com.photocc.app.core.RasterLayer
import com.photocc.app.core.Rect
import com.photocc.app.core.PsdDocument
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
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The canvas.
 *
 * Rendering strategy (mobile first, low RAM):
 *  - Only the *visible* part of the document is composited, at roughly screen
 *    resolution (capped), into one reusable `IntArray` → one reusable `Bitmap`.
 *  - The buffer is invalidated per stroke, per layer change, or when panning
 *    reveals new content. Nothing is composited while the user simply looks.
 *  - A pinch changes the transform only: the same bitmap is rescaled by the GPU.
 *
 * Touch strategy:
 *  - one finger  → the active tool (brush, eraser, fill, text, move, …);
 *  - two fingers → pan and zoom, always, whatever tool is active.
 *
 * That split is what makes painting usable on a phone: no modal pan tool, no
 * accidental strokes while zooming.
 */
class EditorView(context: Context) : View(context) {

    enum class Tool(val label: String) {
        MOVE("Move"),
        BRUSH("Brush"),
        ERASER("Eraser"),
        FILL("Fill"),
        EYEDROPPER("Pick"),
        TEXT("Text"),
        CROP("Crop"),
    }

    var session: Session? = null

    var tool: Tool = Tool.BRUSH
        set(value) {
            field = value
            invalidate()
        }

    var brushSize: Float = 24f
    var brushHardness: Float = 0.8f
    var brushFlow: Float = 1f
    var paintColor: Int = Argb.pack(255, 255, 255, 255)
    var fillTolerance: Int = 32

    /** Called when a tool wants the UI to speak (colour picked, error, …). */
    var onMessage: ((String) -> Unit)? = null
    var onTextTapped: ((TextLayer) -> Unit)? = null
    var onPickColor: ((Int) -> Unit)? = null
    var onDocChanged: (() -> Unit)? = null

    /** Set by the activity around a save so the canvas can show a busy state. */
    var busy: Boolean = false

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
        color = 0x66FFFFFF
    }
    private val accent = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = 0xFFFFB74D.toInt()
    }
    private val checker = Paint()
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCCFFFFFF.toInt()
        textSize = 14f * resources.displayMetrics.scaledDensity
    }

    private var zoom = 1f
    private var panX = 0f
    private var panY = 0f
    private var fitted = false

    /** Region currently held in [buffer]/[bitmap]. */
    private var bufferRect = Rect(0, 0, 0, 0)
    private var buffer: IntArray = IntArray(0)
    private var bitmap: Bitmap? = null
    private var bufferDirty = true

    /** Cursor for the brush preview. */
    private var cursorX = -1f
    private var cursorY = -1f

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
    private var movedLayer = false
    private var cropRect: Rect? = null
    private var gestureStartZoom = 1f
    private var gestureStartDistance = 0f
    private var gestureStartPanX = 0f
    private var gestureStartPanY = 0f
    private var twoFinger = false

    /* ------------------------------------------------------------- rendering */

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (!fitted && w > 0 && h > 0) {
            fitToScreen()
            fitted = true
        }
        bufferDirty = true
    }

    /** Marks the whole canvas as needing a recomposite (after any model change). */
    fun invalidateContent() {
        bufferDirty = true
        invalidate()
    }

    /** The area of the document that the user can see, in document space. */
    fun visibleRegion(): Rect {
        val width = ceil(width / zoom).toInt().coerceAtLeast(1)
        val height = ceil(height / zoom).toInt().coerceAtLeast(1)
        val x = floor(-panX / zoom).toInt()
        val y = floor(-panY / zoom).toInt()
        return Rect(x, y, width, height)
    }

    fun fitToScreen() {
        val doc = session?.doc ?: return
        if (width == 0 || height == 0 || doc.width == 0 || doc.height == 0) return
        val scale = min(width.toFloat() / doc.width, height.toFloat() / doc.height)
        zoom = if (scale > 0f) scale else 1f
        centerOn(doc.width / 2f, doc.height / 2f)
        invalidateContent()
    }

    fun zoomTo100Percent() {
        val doc = session?.doc ?: return
        zoom = 1f
        centerOn(doc.width / 2f, doc.height / 2f)
        invalidateContent()
    }

    private fun centerOn(docX: Float, docY: Float) {
        panX = width / 2f - docX * zoom
        panY = height / 2f - docY * zoom
    }

    private fun toDocument(screenX: Float, screenY: Float): Pair<Float, Float> =
        (screenX - panX) / zoom to (screenY - panY) / zoom

    override fun onDraw(canvas: Canvas) {
        val session = session
        val doc = session?.doc
        canvas.drawColor(0xFF17181C.toInt())
        if (session == null || doc == null) return

        val region = visibleRegion()
        if (bufferRect.w != region.w || bufferRect.h != region.h || bitmap == null) {
            bufferRect = Rect(0, 0, region.w, region.h)
            buffer = IntArray(region.w * region.h)
            bitmap?.recycle()
            bitmap = null
            bufferDirty = true
        }
        if (bufferDirty) {
            Render.flatten(doc, Rect(region.x, region.y, region.w, region.h), buffer, background = 0)
            bufferDirty = false
            val existing = bitmap
            bitmap = if (existing != null && existing.width == region.w && existing.height == region.h) {
                existing.setPixels(buffer, 0, region.w, 0, 0, region.w, region.h)
                existing
            } else {
                existing?.recycle()
                Bitmap.createBitmap(buffer, region.w, region.h, Bitmap.Config.ARGB_8888)
            }
        }

        canvas.save()
        canvas.translate(panX, panY)
        canvas.scale(zoom, zoom)
        paint.isFilterBitmap = zoom < 2f
        // checkerboard behind the document so transparency reads correctly
        checker.color = 0xFF2A2C31.toInt()
        canvas.drawRect(
            region.x.toFloat(), region.y.toFloat(),
            (region.x + region.w).toFloat(), (region.y + region.h).toFloat(),
            checker,
        )
        bitmap?.let { canvas.drawBitmap(it, region.x.toFloat(), region.y.toFloat(), paint) }

        // document border
        outline.color = 0x55FFFFFF
        canvas.drawRect(0f, 0f, doc.width.toFloat(), doc.height.toFloat(), outline)

        // active layer outline (only for layers with content)
        session.selected?.let { layer ->
            if (layer !is GroupLayer) {
                layerBounds(layer)?.let { bounds ->
                    outline.color = 0x88FFB74D.toInt()
                    canvas.drawRect(
                        bounds.x.toFloat(), bounds.y.toFloat(),
                        (bounds.x + bounds.w).toFloat(), (bounds.y + bounds.h).toFloat(),
                        outline,
                    )
                }
            }
        }

        // overlays that live in document space
        drawToolOverlay(canvas)
        canvas.restore()

        // free-floating UI: brush cursor, crop handles
        if (cursorVisible) {
            accent.color = 0xCCFFB74D.toInt()
            val r = brushSize / 2f * zoom
            canvas.drawCircle(panX + cursorX * zoom, panY + cursorY * zoom, r.coerceAtLeast(4f), outline)
        }
        cropRect?.let { rect ->
            accent.color = 0xFFFFB74D.toInt()
            val left = panX + rect.x * zoom
            val top = panY + rect.y * zoom
            val right = panX + (rect.x + rect.w) * zoom
            val bottom = panY + (rect.y + rect.h) * zoom
            canvas.drawRect(left, top, right, bottom, accent)
        }
        if (busy) {
            canvas.drawColor(0x88000000.toInt())
            canvas.drawText(context.getString(com.photocc.app.R.string.working), 32f, height - 48f, iconPaint)
        }
    }

    private var cursorVisible = false

    private fun drawToolOverlay(canvas: Canvas) {
        // (kept separate so future tool previews have an obvious home)
    }

    /* ---------------------------------------------------------------- touches */

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val session = session ?: return false
        val doc = session.doc

        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            val now = SystemClock.uptimeMillis()
            if (now - lastTapTime < 300 && hypot(event.x - lastTapX, event.y - lastTapY) < 60f) {
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
            movedLayer = false
            bufferDirty = false
            val (x, y) = toDocument(event.x, event.y)
            downX = x
            downY = y
            lastX = x
            lastY = y
            cursorX = x
            cursorY = y
            cursorVisible = tool == Tool.BRUSH || tool == Tool.ERASER
            onDown(session, x, y)
            invalidate()
            return true
        }

        if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
            twoFinger = true
            cancelStroke(session)
            gestureStartDistance = pointerDistance(event)
            gestureStartZoom = zoom
            gestureStartPanX = panX
            gestureStartPanY = panY
            val (x, y) = toDocument(event.x, event.y)
            cursorVisible = false
            movedSinceDown = true
            invalidate()
            return true
        }

        if (event.actionMasked == MotionEvent.ACTION_MOVE) {
            if (twoFinger || event.pointerCount > 1) return handleGesture(session, event)
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

        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            val (x, y) = toDocument(event.x, event.y)
            cursorVisible = false
            if (event.actionMasked == MotionEvent.ACTION_UP) onUp(session, doc, x, y) else cancelStroke(session)
            activePointerId = -1
            invalidate()
            return true
        }
        return true
    }

    private fun pointerDistance(event: MotionEvent): Float {
        if (event.pointerCount < 2) return 0f
        val dx = event.getX(1) - event.getX(0)
        val dy = event.getY(1) - event.getY(0)
        return hypot(dx, dy)
    }

    private fun handleGesture(session: Session, event: MotionEvent): Boolean {
        val doc = session.doc
        val distance = pointerDistance(event)
        if (gestureStartDistance > 0f && distance > 0f) {
            zoom = (gestureStartZoom * (distance / gestureStartDistance)).coerceIn(0.05f, 8f)
        }
        // Pan with the midpoint of the two fingers.
        val midX = (event.getX(0) + event.getX(1)) / 2f
        val midY = (event.getY(0) + event.getY(1)) / 2f
        val (docX, docY) = toDocument(midX, midY)
        panX += midX - (docX * zoom + panX)
        panY += midY - (docY * zoom + panY)
        if (doc.width > 0) {
            // keep the document reachable
            val margin = 120f
            panX = panX.coerceIn(-(doc.width * zoom) - margin, width + margin)
            panY = panY.coerceIn(-(doc.height * zoom) - margin, height + margin)
        }
        bufferDirty = true
        invalidate()
        return true
    }

    private fun onDown(session: Session, x: Float, y: Float) {
        when (tool) {
            Tool.BRUSH, Tool.ERASER -> beginStroke(session, x, y)
            Tool.FILL -> {
                val layer = session.selected as? RasterLayer
                if (layer == null) {
                    onMessage?.invoke(context.getString(com.photocc.app.R.string.need_raster_layer))
                    return
                }
                if (!session.selected!!.locked) {
                    val edit = PixelEdit(layer)
                    layer.pixels?.let { edit.before(Rect(layer.x, layer.y, it.width, it.height)) }
                    val changed = Render.floodFill(layer, x.toInt(), y.toInt(), paintColor, fillTolerance)
                    session.commitPixelEdit(edit, "Fill")
                    if (changed == null) session.changed()
                }
            }
            Tool.EYEDROPPER -> {
                val index = sampleIndex(x, y)
                if (index >= 0) {
                    val color = buffer[index]
                    onPickColor?.invoke(color)
                }
            }
            Tool.TEXT -> Unit // handled on tap-up
            Tool.CROP -> {
                cropRect = Rect(x.toInt(), y.toInt(), 0, 0)
            }
            Tool.MOVE -> {
                val layer = session.selected
                if (layer != null && !layer.locked) session.beginStructuralEdit()
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
        val radiusPadded = radius + 2f
        edit.before(
            Rect(
                floor(x - radiusPadded).toInt(),
                floor(y - radiusPadded).toInt(),
                ceil(radiusPadded * 2f).toInt() + 2,
                ceil(radiusPadded * 2f).toInt() + 2,
            ),
        )
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
            }

            Tool.MOVE -> {
                val layer = session.selected ?: return
                val dx = (x - lastX).roundToInt()
                val dy = (y - lastY).roundToInt()
                if (dx != 0 || dy != 0) {
                    movedLayer = true
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
                    session.changed()
                    invalidateContent()
                }
            }

            Tool.CROP -> {
                cropRect = Rect.ofPoints(downX, downY, x, y)
            }

            Tool.TEXT, Tool.EYEDROPPER, Tool.FILL -> Unit
        }
    }

    private fun onUp(session: Session, doc: PsdDocument, x: Float, y: Float) {
        when (tool) {
            Tool.BRUSH, Tool.ERASER -> {
                val edit = strokeEdit
                if (edit != null) {
                    applyStampTail(edit, x, y)
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
                        onTextTapped?.invoke(placeholderTextLayer(x, y))
                    }
                }
            }

            Tool.MOVE -> {
                // A drag applies layer positions live; the undo step is closed here.
                session.endStructuralEdit("Move layer")
            }

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

    private fun applyStampTail(edit: PixelEdit, x: Float, y: Float) {
        val layer = strokeLayer ?: return
        if (abs(x - lastX) > 0.1f || abs(y - lastY) > 0.1f) {
            applyStamp(layer, edit, x, y)
        }
    }

    private fun cancelStroke(@Suppress("UNUSED_PARAMETER") session: Session) {
        strokeEdit = null
        strokeLayer = null
        cropRect = null
    }

    private fun sampleIndex(x: Float, y: Float): Int {
        val region = bufferRect
        val cx = floor(x).toInt() - region.x
        val cy = floor(y).toInt() - region.y
        if (cx < 0 || cy < 0 || cx >= region.w || cy >= region.h) return -1
        return cy * region.w + cx
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

    /**
     * A stand-in while the text dialog is open for a *new* layer: it is not in
     * the document yet, so cancelling leaves no trace.
     */
    private fun placeholderTextLayer(x: Float, y: Float): TextLayer = TextLayer(x = x, y = y).apply {
        text.text = ""
        textDirty = true
    }

    /* ------------------------------------------------------------------ help */

    /** The pixels under a screen position, for the colour picker. */
    fun colorAt(screenX: Float, screenY: Float): Int {
        val (x, y) = toDocument(screenX, screenY)
        val index = sampleIndex(x, y)
        return if (index >= 0) buffer[index] else 0
    }

    fun currentZoom(): Float = zoom

    /** Rough memory estimate of the visible buffer, shown in the info sheet. */
    fun bufferBytes(): Int = buffer.size * 4
}
