package com.strata.editor.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import com.strata.editor.core.Colors
import com.strata.editor.core.Compositor
import com.strata.editor.core.GradientShape
import com.strata.editor.core.GradientType
import com.strata.editor.core.LayerKind
import com.strata.editor.core.LayerRenderer
import com.strata.editor.core.Painter
import com.strata.editor.core.Pixmap
import com.strata.editor.core.Rect
import com.strata.editor.core.Selection
import com.strata.editor.core.TextAlign
import com.strata.editor.core.TextContent
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The canvas: one view, drawn straight on the main thread.
 *
 * The document is composited into a cached bitmap covering whatever is on screen,
 * so panning and pinching never re-composite unless they have to, and a stroke
 * only re-composites the rectangle it touched. That is what keeps a twelve
 * megapixel document usable under a thumb.
 *
 * Every tool works in *layer* coordinates: the compositor places a raster layer's
 * pixels at `layer.x/layer.y`, so a brush stroke has to subtract that offset
 * before it writes. Overlays and hit tests stay in document coordinates.
 */
class EditorView(context: Context) : View(context) {

    /** The state this view draws and edits. The activity sets it before the first frame. */
    var state: EditorState? = null

    /* ------------------------------------------------------------- transform */

    var zoom = 1f
        private set
    private var originX = 0f
    private var originY = 0f
    private var fitZoom = 1f
    private var gesturing = false

    /* ---------------------------------------------------------------- cache */

    private var frame: Bitmap? = null
    private var buffer: IntArray = IntArray(0)
    private var bufferWidth = 0
    private var bufferHeight = 0
    private val frameDoc = RectF()
    private var frameValid = false
    private var damage: Rect? = null
    private val framePaint = Paint(Paint.FILTER_BITMAP_FLAG)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dashes = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val checkerPaint = Paint()
    private var checkerShader: BitmapShader? = null

    /** Cached marching-ants spans, in document coordinates. */
    private var ants: IntArray = IntArray(0)
    private var antsRevision = -1

    /* -------------------------------------------------------------- gestures */

    private enum class Mode { NONE, PAINT, MARQUEE, LASSO, SHAPE, GRADIENT, MOVE, VIEW }

    private var mode = Mode.NONE
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var startDocX = 0f
    private var startDocY = 0f
    private var lastDocX = 0f
    private var lastDocY = 0f
    /** Where the stroke's layer sits, so document points can become layer points. */
    private var strokeOffsetX = 0
    private var strokeOffsetY = 0
    private var strokeCarry = 0f
    private var moveStepX = 0
    private var moveStepY = 0
    private var pinchDistance = 1f
    private var pinchZoom = 1f
    private var pinchOriginX = 0f
    private var pinchOriginY = 0f
    private var pinchFocusX = 0f
    private var pinchFocusY = 0f
    private var pinchTapFocusX = 0f
    private var pinchTapFocusY = 0f
    private val lassoPoints = ArrayList<Pair<Float, Float>>(256)
    private var lastTapTime = 0L
    private var cursorX = -1f
    private var cursorY = -1f

    var onStatus: ((String) -> Unit)? = null

    /* ================================================================ layout */

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        frameValid = false
        if (oldw == 0 && oldh == 0) fitToWindow()
        else {
            originX += (w - oldw) / 2f
            originY += (h - oldh) / 2f
        }
    }

    /** Scales the document to sit inside the view with a small margin. */
    fun fitToWindow() {
        val editor = state ?: return
        val doc = editor.document
        val w = max(1, width)
        val h = max(1, height)
        fitZoom = min(w.toFloat() / max(1, doc.width), h.toFloat() / max(1, doc.height)).coerceIn(0.01f, 8f)
        zoom = fitZoom
        centreDocument()
        frameValid = false
        invalidate()
    }

    private fun centreDocument() {
        val doc = state?.document ?: return
        originX = (width - doc.width * zoom) / 2f
        originY = (height - doc.height * zoom) / 2f
    }

    fun zoomAt(factor: Float, focusX: Float, focusY: Float) {
        val next = (zoom * factor).coerceIn(fitZoom * 0.2f, 64f)
        if (abs(next - zoom) < 1e-4f) return
        val ratio = next / zoom
        originX = focusX - (focusX - originX) * ratio
        originY = focusY - (focusY - originY) * ratio
        zoom = next
        frameValid = false
        invalidate()
    }

    fun zoomStep(zoomIn: Boolean) = zoomAt(if (zoomIn) 1.5f else 1f / 1.5f, width / 2f, height / 2f)

    fun documentPoint(screenX: Float, screenY: Float): Pair<Float, Float> =
        Pair((screenX - originX) / zoom, (screenY - originY) / zoom)

    private fun screenX(docX: Float): Float = docX * zoom + originX

    private fun screenY(docY: Float): Float = docY * zoom + originY

    /* ============================================================== drawing */

    /** Called by [EditorState] when the document changes; `rect` is in document space. */
    fun documentChanged(rect: Rect?) {
        if (rect == null) {
            damage = null
            frameValid = false
        } else if (!frameValid) {
            // A rebuild is already due; nothing more to do.
        } else if (rect.x >= frameDoc.left && rect.y >= frameDoc.top && rect.right <= frameDoc.right && rect.bottom <= frameDoc.bottom) {
            damage = damage?.union(rect) ?: rect
        } else {
            damage = null
            frameValid = false
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val editor = state ?: return
        canvas.drawColor(Palette.background)
        val doc = editor.document
        if (doc.width <= 0 || doc.height <= 0 || width <= 0 || height <= 0) return
        if (zoom <= 0f || zoom.isNaN() || zoom.isInfinite()) {
            fitToWindow()
            return
        }
        try {
            drawCanvas(canvas, editor)
        } catch (error: OutOfMemoryError) {
            onStatus?.invoke("Not enough memory to draw this document")
            return
        } catch (error: Exception) {
            onStatus?.invoke("Draw problem: ${error.message}")
            return
        }
        try {
            drawOverlays(canvas, editor)
        } catch (error: Exception) {
            onStatus?.invoke("Overlay problem: ${error.message}")
        }
    }

    private fun drawCanvas(canvas: Canvas, editor: EditorState) {
        val doc = editor.document
        val docLeft = screenX(0f)
        val docTop = screenY(0f)
        val docRight = screenX(doc.width.toFloat())
        val docBottom = screenY(doc.height.toFloat())
        shadow.color = 0x66000000
        canvas.drawRect(docLeft + 3f, docTop + 4f, docRight + 3f, docBottom + 4f, shadow)

        // The transparency checker, only where the document actually is.
        val shader = checkerShader()
        val matrix = Matrix()
        matrix.setScale(max(0.25f, zoom), max(0.25f, zoom))
        matrix.postTranslate(docLeft, docTop)
        shader.setLocalMatrix(matrix)
        canvas.drawRect(docLeft, docTop, docRight, docBottom, checkerPaint)

        if (!frameValid && !gesturing) rebuildFrame(editor)

        val bitmap = frame
        if (bitmap != null && !bitmap.isRecycled) {
            val src = android.graphics.Rect(0, 0, bitmap.width, bitmap.height)
            val dst = RectF(screenX(frameDoc.left), screenY(frameDoc.top), screenX(frameDoc.right), screenY(frameDoc.bottom))
            framePaint.isFilterBitmap = zoom != 1f
            canvas.drawBitmap(bitmap, src, dst, framePaint)
        }

        val region = damage
        if (region != null) {
            damage = null
            if (frameValid && !gesturing) refreshRegion(editor, region) else frameValid = false
        }
    }

    private fun checkerShader(): BitmapShader {
        checkerShader?.let { return it }
        val size = 12
        val tile = Bitmap.createBitmap(size * 2, size * 2, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(size * 2 * size * 2)
        val light = 0xFF3B4048.toInt()
        val dark = 0xFF2C3037.toInt()
        for (y in 0 until size * 2) {
            for (x in 0 until size * 2) {
                val odd = ((x / size) + (y / size)) % 2 == 0
                pixels[y * size * 2 + x] = if (odd) light else dark
            }
        }
        tile.setPixels(pixels, 0, size * 2, 0, 0, size * 2, size * 2)
        val shader = BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        checkerShader = shader
        return shader
    }

    /* ----------------------------------------------------------- compositing */

    /** The document rectangle the view covers right now, snapped to whole pixels. */
    private fun visibleRegion(editor: EditorState): Rect {
        val doc = editor.document
        val left = Math.floor(((0f - originX) / zoom).toDouble()).toInt()
        val top = Math.floor(((0f - originY) / zoom).toDouble()).toInt()
        val right = Math.ceil(((width - originX) / zoom).toDouble()).toInt()
        val bottom = Math.ceil(((height - originY) / zoom).toDouble()).toInt()
        return Rect(left, top, max(1, right - left), max(1, bottom - top)).clampTo(doc.width, doc.height)
    }

    private fun rebuildFrame(editor: EditorState) {
        val region = visibleRegion(editor)
        if (region.isEmpty) {
            frame?.recycle()
            frame = null
            frameValid = true
            damage = null
            return
        }
        val area = region.w.toLong() * region.h
        if (area <= MAX_REGION_PIXELS) {
            ensureBuffer(region.w, region.h)
            val pixmap = Pixmap(region.w, region.h, buffer)
            pixmap.fill(0)
            Compositor.compose(editor.document.layers, pixmap, Rect(0, 0, region.w, region.h), region.x, region.y, 0)
            upload(region.w, region.h, 0, 0)
            frameDoc.set(region.x.toFloat(), region.y.toFloat(), region.right.toFloat(), region.bottom.toFloat())
        } else {
            // A very large document, zoomed far out: render it whole at a size
            // that fits the budget and let the canvas scale it up.
            val doc = editor.document
            val targetW = max(1, (doc.width * (MAX_REGION_PIXELS.toDouble() / (doc.width.toDouble() * doc.height))).let { Math.sqrt(it).toInt() })
                .coerceAtMost(doc.width)
            val targetH = max(1, (doc.height.toLong() * targetW / doc.width).toInt())
            ensureBuffer(targetW, targetH)
            val pixmap = Pixmap(targetW, targetH, buffer)
            pixmap.fill(0)
            Compositor.renderScaled(doc, pixmap, Rect(0, 0, targetW, targetH), 0, 1)
            upload(targetW, targetH, 0, 0)
            frameDoc.set(0f, 0f, doc.width.toFloat(), doc.height.toFloat())
        }
        frameValid = true
        damage = null
    }

    /** Re-composites only the part of the frame a stroke touched. */
    private fun refreshRegion(editor: EditorState, region: Rect) {
        if (frame == null) {
            frameValid = false
            return
        }
        val covered = Rect(
            frameDoc.left.toInt(),
            frameDoc.top.toInt(),
            (frameDoc.right - frameDoc.left).toInt(),
            (frameDoc.bottom - frameDoc.top).toInt(),
        )
        val clipped = region.intersect(covered)
        if (clipped.isEmpty) {
            frameValid = false
            return
        }
        val localX = clipped.x - frameDoc.left.toInt()
        val localY = clipped.y - frameDoc.top.toInt()
        val width = min(clipped.w, bufferWidth - localX)
        val height = min(clipped.h, bufferHeight - localY)
        if (width <= 0 || height <= 0) {
            frameValid = false
            return
        }
        for (y in 0 until height) {
            java.util.Arrays.fill(buffer, (localY + y) * bufferWidth + localX, (localY + y) * bufferWidth + localX + width, 0)
        }
        val pixmap = Pixmap(bufferWidth, bufferHeight, buffer)
        Compositor.compose(editor.document.layers, pixmap, Rect(localX, localY, width, height), clipped.x, clipped.y, 0)
        upload(width, height, localX, localY)
    }

    private fun ensureBuffer(w: Int, h: Int) {
        if (bufferWidth != w || bufferHeight != h || buffer.size < w * h) {
            buffer = IntArray(w * h)
            bufferWidth = w
            bufferHeight = h
        }
        val current = frame
        if (current == null || current.isRecycled || current.width != w || current.height != h) {
            current?.recycle()
            frame = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        }
    }

    private fun upload(w: Int, h: Int, offsetX: Int, offsetY: Int) {
        val bitmap = frame ?: return
        bitmap.setPixels(buffer, offsetY * bufferWidth + offsetX, bufferWidth, offsetX, offsetY, w, h)
    }

    /* --------------------------------------------------------------- overlays */

    private fun drawOverlays(canvas: Canvas, editor: EditorState) {
        val doc = editor.document
        canvas.save()
        canvas.translate(originX, originY)
        canvas.scale(zoom, zoom)

        stroke.style = Paint.Style.STROKE
        stroke.strokeWidth = 1f / zoom
        stroke.color = 0x55FFFFFF
        canvas.drawRect(0f, 0f, doc.width.toFloat(), doc.height.toFloat(), stroke)

        if (editor.options.showGrid && zoom >= 0.2f) {
            val step = gridStep(doc.width, doc.height)
            dashes.style = Paint.Style.STROKE
            dashes.strokeWidth = 1f / zoom
            dashes.color = 0x22FFFFFF
            dashes.pathEffect = android.graphics.DashPathEffect(floatArrayOf(6f / zoom, 6f / zoom), 0f)
            var x = step
            while (x < doc.width) {
                canvas.drawLine(x.toFloat(), 0f, x.toFloat(), doc.height.toFloat(), dashes)
                x += step
            }
            var y = step
            while (y < doc.height) {
                canvas.drawLine(0f, y.toFloat(), doc.width.toFloat(), y.toFloat(), dashes)
                y += step
            }
            dashes.pathEffect = null
        }

        if (editor.options.showSelectionEdges) drawSelection(canvas, editor)
        drawTextLayout(canvas, editor)
        drawPending(canvas, editor)
        canvas.restore()
        drawCursor(canvas, editor)
    }

    private fun gridStep(width: Int, height: Int): Int {
        val rough = max(width, height) / 12
        var step = 16
        while (step * 2 < rough) step *= 2
        return max(16, step / 2)
    }

    private fun drawSelection(canvas: Canvas, editor: EditorState) {
        val selection = editor.document.selection ?: return
        if (!selection.isActive) return
        if (antsRevision != editor.revision) {
            ants = try {
                selection.outlinePoints()
            } catch (error: OutOfMemoryError) {
                IntArray(0)
            }
            antsRevision = editor.revision
        }
        dashes.style = Paint.Style.STROKE
        dashes.strokeWidth = 1f / zoom
        dashes.color = 0xFFFFFFFF.toInt()
        dashes.pathEffect = android.graphics.DashPathEffect(floatArrayOf(5f / zoom, 4f / zoom), 0f)
        var index = 0
        while (index + 2 < ants.size) {
            val x = ants[index].toFloat()
            val y = ants[index + 1].toFloat()
            val run = ants[index + 2].toFloat()
            canvas.drawLine(x, y, x + run, y, dashes)
            index += 3
        }
        dashes.pathEffect = null
        val bounds = selection.bounds
        if (!bounds.isEmpty) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.5f / zoom
            paint.color = 0x88FFFFFF.toInt()
            canvas.drawRect(bounds.x.toFloat(), bounds.y.toFloat(), bounds.right.toFloat(), bounds.bottom.toFloat(), paint)
        }
    }

    private fun drawTextLayout(canvas: Canvas, editor: EditorState) {
        val layer = editor.activeLayer ?: return
        if (layer.kind != LayerKind.TEXT || layer.textLayout.isEmpty) return
        if (editor.tool != Tool.TEXT && editor.tool != Tool.MOVE) return
        val layout = layer.textLayout
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.5f / zoom
        paint.color = Palette.accent
        paint.pathEffect = android.graphics.DashPathEffect(floatArrayOf(8f / zoom, 6f / zoom), 0f)
        canvas.drawRect(layout.x.toFloat(), layout.y.toFloat(), layout.right.toFloat(), layout.bottom.toFloat(), paint)
        paint.pathEffect = null
    }

    private fun drawPending(canvas: Canvas, editor: EditorState) {
        val pending = editor.pendingRect
        if (pending != null && !pending.isEmpty) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.5f / zoom
            paint.color = Palette.accent
            paint.pathEffect = android.graphics.DashPathEffect(floatArrayOf(9f / zoom, 6f / zoom), 0f)
            canvas.drawRect(pending.x.toFloat(), pending.y.toFloat(), pending.right.toFloat(), pending.bottom.toFloat(), paint)
            paint.pathEffect = null
        }
        if (mode == Mode.LASSO && lassoPoints.size > 1) {
            val path = Path()
            path.moveTo(lassoPoints[0].first, lassoPoints[0].second)
            for (i in 1 until lassoPoints.size) path.lineTo(lassoPoints[i].first, lassoPoints[i].second)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.5f / zoom
            paint.color = Palette.accent
            canvas.drawPath(path, paint)
        }
        if (mode == Mode.GRADIENT) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f / zoom
            paint.color = 0xFFFFFFFF.toInt()
            canvas.drawLine(startDocX, startDocY, lastDocX, lastDocY, paint)
            canvas.drawCircle(startDocX, startDocY, 5f / zoom, paint)
        }
    }

    private fun drawCursor(canvas: Canvas, editor: EditorState) {
        if (cursorX < 0f || cursorY < 0f) return
        val showsRing = editor.tool == Tool.BRUSH || editor.tool == Tool.ERASER || editor.tool == Tool.CLONE ||
            editor.tool == Tool.RETOUCH || editor.tool == Tool.SMOOTH
        if (!showsRing) return
        val radius = (editor.options.activeBrush(editor.tool).size / 2f * zoom).coerceAtLeast(4f)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        paint.color = 0xAA000000.toInt()
        canvas.drawCircle(cursorX, cursorY, radius + 1f, paint)
        paint.color = 0xFFFFFFFF.toInt()
        canvas.drawCircle(cursorX, cursorY, radius, paint)
    }

    /* ============================================================== gestures */

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val editor = state ?: return false
        try {
            return handleTouch(event, editor)
        } catch (error: OutOfMemoryError) {
            onStatus?.invoke("Not enough memory for that")
            cancelAll(editor)
            return true
        } catch (error: Exception) {
            onStatus?.invoke("Gesture problem: ${error.message}")
            cancelAll(editor)
            return true
        }
    }

    private fun handleTouch(event: MotionEvent, editor: EditorState): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                downX = event.x
                downY = event.y
                lastX = downX
                lastY = downY
                cursorX = downX
                cursorY = downY
                beginGesture(editor, downX, downY)
                invalidate()
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount >= 2) {
                    if (mode == Mode.PAINT || mode == Mode.MARQUEE || mode == Mode.LASSO ||
                        mode == Mode.SHAPE || mode == Mode.GRADIENT || mode == Mode.MOVE
                    ) {
                        abandonGesture(editor)
                    }
                    mode = Mode.VIEW
                    pinchDistance = max(1f, distance(event))
                    pinchZoom = zoom
                    pinchOriginX = originX
                    pinchOriginY = originY
                    pinchFocusX = focalX(event)
                    pinchFocusY = focalY(event)
                    pinchTapFocusX = pinchFocusX
                    pinchTapFocusY = pinchFocusY
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                cursorX = event.x
                cursorY = event.y
                if (mode == Mode.VIEW && event.pointerCount >= 2) updatePinch(event)
                else if (mode == Mode.VIEW) {
                    originX += event.x - lastX
                    originY += event.y - lastY
                    frameValid = false
                } else continueGesture(editor, event.x, event.y)
                lastX = event.x
                lastY = event.y
                invalidate()
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (mode == Mode.VIEW) mode = Mode.NONE
                return true
            }
            MotionEvent.ACTION_UP -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                cursorX = event.x
                cursorY = event.y
                endGesture(editor, event.x, event.y)
                invalidate()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                cancelAll(editor)
                return true
            }
        }
        return true
    }

    private fun beginGesture(editor: EditorState, x: Float, y: Float) {
        val point = documentPoint(x, y)
        startDocX = point.first
        startDocY = point.second
        lastDocX = startDocX
        lastDocY = startDocY
        val now = SystemClock.uptimeMillis()
        val doubleTap = now - lastTapTime < 260L
        lastTapTime = now
        if (doubleTap && (editor.tool == Tool.HAND || editor.tool == Tool.ZOOM)) {
            if (abs(zoom - fitZoom) < 0.001f) zoomAt(2f, x, y) else fitToWindow()
            mode = Mode.NONE
            return
        }
        if (editor.tool == Tool.HAND) {
            mode = Mode.VIEW
            pinchDistance = 1f
            pinchZoom = zoom
            pinchOriginX = originX
            pinchOriginY = originY
            pinchFocusX = x
            pinchFocusY = y
            return
        }
        mode = when (editor.tool) {
            Tool.BRUSH, Tool.ERASER, Tool.CLONE, Tool.RETOUCH, Tool.SMOOTH -> beginPaintGesture(editor)
            Tool.FILL -> {
                fillAt(editor, startDocX, startDocY)
                Mode.NONE
            }
            Tool.SELECT, Tool.SHAPE, Tool.CROP -> {
                editor.pendingRect = Rect(startDocX.toInt(), startDocY.toInt(), 1, 1)
                Mode.MARQUEE
            }
            Tool.LASSO -> {
                lassoPoints.clear()
                lassoPoints.add(Pair(startDocX, startDocY))
                Mode.LASSO
            }
            Tool.GRADIENT -> Mode.GRADIENT
            Tool.WAND -> {
                wandAt(editor, startDocX, startDocY)
                Mode.NONE
            }
            Tool.MOVE, Tool.TRANSFORM -> beginMove(editor)
            Tool.PICKER -> {
                pickColour(editor, startDocX, startDocY)
                Mode.NONE
            }
            Tool.ZOOM -> {
                zoomAt(1.6f, x, y)
                Mode.NONE
            }
            Tool.TEXT -> {
                tapText(editor, startDocX, startDocY)
                Mode.NONE
            }
            else -> Mode.NONE
        }
    }

    private fun continueGesture(editor: EditorState, x: Float, y: Float) {
        val point = documentPoint(x, y)
        val docX = point.first
        val docY = point.second
        when (mode) {
            Mode.PAINT -> paintTo(editor, docX, docY)
            Mode.MARQUEE, Mode.SHAPE -> {
                editor.pendingRect = Rect(
                    min(startDocX, docX).toInt(),
                    min(startDocY, docY).toInt(),
                    max(1, abs(docX - startDocX).toInt()),
                    max(1, abs(docY - startDocY).toInt()),
                )
            }
            Mode.LASSO -> {
                if (lassoPoints.size < 3000) lassoPoints.add(Pair(docX, docY))
            }
            Mode.GRADIENT -> {
                lastDocX = docX
                lastDocY = docY
            }
            Mode.MOVE -> {
                val stepX = (docX - startDocX).toInt()
                val stepY = (docY - startDocY).toInt()
                editor.moveLayerLive(stepX - moveStepX, stepY - moveStepY)
                moveStepX = stepX
                moveStepY = stepY
            }
            else -> Unit
        }
        lastDocX = docX
        lastDocY = docY
    }

    private fun endGesture(editor: EditorState, x: Float, y: Float) {
        val point = documentPoint(x, y)
        when (mode) {
            Mode.PAINT -> {
                paintTo(editor, point.first, point.second)
                editor.commitPaint()
            }
            Mode.MARQUEE, Mode.SHAPE -> finishMarquee(editor)
            Mode.LASSO -> finishLasso(editor)
            Mode.GRADIENT -> finishGradient(editor, point.first, point.second)
            Mode.MOVE -> editor.endLiveEdit("Move layer")
            Mode.VIEW -> {
                gesturing = false
                frameValid = false
                keepDocumentOnScreen(editor)
            }
            else -> Unit
        }
        mode = Mode.NONE
        editor.pendingRect = null
        lassoPoints.clear()
    }

    private fun abandonGesture(editor: EditorState) {
        when (mode) {
            Mode.PAINT -> editor.cancelPaint()
            Mode.MOVE -> editor.endLiveEdit("Move layer")
            else -> Unit
        }
        editor.pendingRect = null
        lassoPoints.clear()
    }

    private fun cancelAll(editor: EditorState) {
        abandonGesture(editor)
        mode = Mode.NONE
        gesturing = false
        frameValid = false
        invalidate()
    }

    private fun keepDocumentOnScreen(editor: EditorState) {
        val doc = editor.document
        val margin = 48f
        val docW = doc.width * zoom
        val docH = doc.height * zoom
        if (docW <= width) originX = (width - docW) / 2f
        else originX = originX.coerceIn(width - docW - margin, margin)
        if (docH <= height) originY = (height - docH) / 2f
        else originY = originY.coerceIn(height - docH - margin, margin)
    }

    /* ----------------------------------------------------------- pinch maths */

    private fun distance(event: MotionEvent): Float {
        val dx = event.getX(0) - event.getX(1)
        val dy = event.getY(0) - event.getY(1)
        return hypot(dx, dy)
    }

    private fun focalX(event: MotionEvent): Float = (event.getX(0) + event.getX(1)) / 2f

    private fun focalY(event: MotionEvent): Float = (event.getY(0) + event.getY(1)) / 2f

    private fun updatePinch(event: MotionEvent) {
        gesturing = true
        val scale = (distance(event) / pinchDistance).coerceIn(0.05f, 20f)
        val nextZoom = (pinchZoom * scale).coerceIn(fitZoom * 0.2f, 64f)
        originX = focalX(event) - (pinchFocusX - pinchOriginX) * (nextZoom / pinchZoom)
        originY = focalY(event) - (pinchFocusY - pinchOriginY) * (nextZoom / pinchZoom)
        zoom = nextZoom
        framePaint.isFilterBitmap = true
    }

    /* ---------------------------------------------------------------- tools */

    private fun layerOffset(editor: EditorState) {
        val layer = editor.activeLayer
        strokeOffsetX = layer?.x ?: 0
        strokeOffsetY = layer?.y ?: 0
    }

    private fun localRect(rect: Rect): Rect = Rect(rect.x - strokeOffsetX, rect.y - strokeOffsetY, rect.w, rect.h)

    private fun beginPaintGesture(editor: EditorState): Mode {
        val layer = editor.activeLayer
        if (layer == null) {
            onStatus?.invoke("Select a layer first")
            materialiseLayer(editor)
            return Mode.NONE
        }
        if (layer.locked) {
            onStatus?.invoke("${layer.name} is locked")
            return Mode.NONE
        }
        layerOffset(editor)
        if (layer.pixels == null) {
            onStatus?.invoke("${layer.name} has no pixels to paint on")
            return Mode.NONE
        }
        val label = when (editor.tool) {
            Tool.ERASER -> "Erase"
            Tool.CLONE -> "Clone stamp"
            Tool.RETOUCH -> editor.options.retouch.label
            Tool.SMOOTH -> editor.options.smooth.label
            else -> "Brush"
        }
        if (!editor.beginPaint(label)) return Mode.NONE
        strokeCarry = 0f
        paintTo(editor, startDocX, startDocY)
        return Mode.PAINT
    }

    /** Called when a tool needs pixels and the active layer is text or an adjustment. */
    private fun materialiseLayer(editor: EditorState) {
        val layer = editor.activeLayer ?: return
        if (layer.pixels != null || layer.isGroup) return
        if (layer.kind == LayerKind.TEXT || layer.kind == LayerKind.SOLID || layer.kind == LayerKind.ADJUSTMENT) {
            onStatus?.invoke("${layer.name} has no pixels — use Layer ▸ Rasterise")
        }
    }

    private fun paintTo(editor: EditorState, docX: Float, docY: Float) {
        val painter = editor.painter() ?: return
        val options = editor.options
        val tool = editor.tool
        val brush = options.activeBrush(tool)
        val colour = editor.primary
        val localLastX = lastDocX - strokeOffsetX
        val localLastY = lastDocY - strokeOffsetY
        val localX = docX - strokeOffsetX
        val localY = docY - strokeOffsetY
        val localDamage = Rect(
            (min(localLastX, localX) - brush.size).toInt(),
            (min(localLastY, localY) - brush.size).toInt(),
            (abs(localX - localLastX) + brush.size * 2).toInt(),
            (abs(localY - localLastY) + brush.size * 2).toInt(),
        )
        editor.touchPaint(localDamage)
        when (tool) {
            Tool.BRUSH -> strokeCarry = painter.stroke(localLastX, localLastY, localX, localY, brush, colour, false, strokeCarry)
            Tool.ERASER -> strokeCarry = painter.stroke(localLastX, localLastY, localX, localY, brush, colour, true, strokeCarry)
            Tool.CLONE -> {
                val layer = editor.activeLayer ?: return
                painter.cloneSource = layer.pixels
                if (options.cloneSourceX < 0f) {
                    options.cloneSourceX = localX
                    options.cloneSourceY = localY
                    onStatus?.invoke("Clone source set — drag to clone from it")
                    return
                }
                if (options.cloneAligned) {
                    painter.cloneOffsetX = options.cloneSourceX - localX
                    painter.cloneOffsetY = options.cloneSourceY - localY
                }
                painter.cloneStamp(localX, localY, brush, options.cloneAligned)
            }
            Tool.RETOUCH -> painter.retouch(localX, localY, brush, options.retouch.kind, options.retouchStrength)
            Tool.SMOOTH -> painter.neighbourhood(localX, localY, brush, options.smooth.kind, options.smoothStrength)
            else -> Unit
        }
        editor.invalidate(Rect(localDamage.x + strokeOffsetX, localDamage.y + strokeOffsetY, localDamage.w, localDamage.h))
    }

    private fun fillAt(editor: EditorState, docX: Float, docY: Float) {
        val layer = editor.activeLayer
        if (layer == null || layer.locked || layer.pixels == null) {
            onStatus?.invoke("Select a pixel layer to fill")
            return
        }
        val x = (docX - layer.x).toInt()
        val y = (docY - layer.y).toInt()
        val pixels = layer.pixels ?: return
        if (x < 0 || y < 0 || x >= pixels.width || y >= pixels.height) {
            onStatus?.invoke("Tap inside the layer to fill it")
            return
        }
        val tolerance = editor.options.fillTolerance
        val contiguous = editor.options.fillContiguous
        val antialias = editor.options.fillAntialias
        val colour = editor.primary
        editor.editLayerPixels("Fill") { target, targetPixels ->
            val painter = Painter(target, editor.selection())
            painter.floodFill(x, y, colour, tolerance, contiguous, antialias)
            targetPixels.hashCode()
        }
    }

    private fun wandAt(editor: EditorState, docX: Float, docY: Float) {
        val layer = editor.activeLayer
        val pixels = layer?.pixels
        if (layer == null || pixels == null) {
            onStatus?.invoke("Select a pixel layer to sample from")
            return
        }
        val x = (docX - layer.x).toInt()
        val y = (docY - layer.y).toInt()
        if (x < 0 || y < 0 || x >= pixels.width || y >= pixels.height) return
        val selection = Selection.magicWand(
            pixels,
            x,
            y,
            editor.options.wandTolerance,
            editor.options.wandContiguous,
            editor.options.selectionFeather,
        )
        editor.combineSelection(selection, "Magic wand")
        editor.options.selectionOp = 0
    }

    private fun pickColour(editor: EditorState, docX: Float, docY: Float) {
        val bitmap = frame
        val x = (docX - frameDoc.left).toInt()
        val y = (docY - frameDoc.top).toInt()
        var colour = 0
        if (bitmap != null && !bitmap.isRecycled && x in 0 until bitmap.width && y in 0 until bitmap.height) {
            colour = bitmap.getPixel(x, y)
        }
        if (Colors.alpha(colour) == 0) colour = sampleLayer(editor, docX.toInt(), docY.toInt())
        if (Colors.alpha(colour) == 0) {
            onStatus?.invoke("Nothing there to pick")
            return
        }
        editor.primary = Colors.withAlpha(colour, 255)
        editor.rememberColour(editor.primary)
        onStatus?.invoke("Picked #${Colors.toHex(editor.primary)}")
        editor.invalidate(null)
    }

    private fun sampleLayer(editor: EditorState, x: Int, y: Int): Int {
        for ((layer, _) in editor.document.flattenTopFirst()) {
            if (!layer.visible) continue
            val pixels = layer.pixels ?: continue
            val lx = x - layer.x
            val ly = y - layer.y
            if (lx in 0 until pixels.width && ly in 0 until pixels.height) {
                val colour = pixels[lx, ly]
                if (Colors.alpha(colour) > 0) return colour
            }
        }
        return 0
    }

    private fun tapText(editor: EditorState, docX: Float, docY: Float) {
        for ((layer, _) in editor.document.flattenTopFirst()) {
            if (layer.kind != LayerKind.TEXT || layer.textLayout.isEmpty) continue
            val layout = layer.textLayout
            if (docX >= layout.x - 16 && docX <= layout.right + 16 && docY >= layout.y - 16 && docY <= layout.bottom + 16) {
                editor.selectLayer(layer)
                editor.openTextEditor(layer)
                return
            }
        }
        val content = TextContent(
            text = "Text",
            fontFamily = editor.options.textFont,
            fontSize = editor.options.textSize,
            bold = editor.options.textBold,
            italic = editor.options.textItalic,
            alignment = TextAlign.LEFT,
            color = editor.primary,
        )
        val measured = LayerRenderer.textRasterizer?.measure(content)
        val textWidth = max(24, measured?.w ?: (content.fontSize * 4).toInt())
        val textHeight = max(12, measured?.h ?: (content.fontSize * 1.35f).toInt())
        val layer = editor.newTextLayer(content, Rect(docX.toInt(), (docY - textHeight / 2f).toInt(), textWidth, textHeight))
        editor.openTextEditor(layer)
    }

    private fun beginMove(editor: EditorState): Mode {
        val layer = editor.activeLayer
        if (layer == null) {
            onStatus?.invoke("Select a layer to move")
            return Mode.NONE
        }
        if (layer.locked) {
            onStatus?.invoke("${layer.name} is locked")
            return Mode.NONE
        }
        editor.beginLiveEdit()
        moveStepX = 0
        moveStepY = 0
        return Mode.MOVE
    }

    private fun finishMarquee(editor: EditorState) {
        val rect = editor.pendingRect
        editor.pendingRect = null
        if (rect == null) return
        val small = rect.isEmpty || (rect.w <= 2 && rect.h <= 2)
        when (editor.tool) {
            Tool.SELECT -> {
                if (small) {
                    editor.deselect()
                    return
                }
                layerOffset(editor)
                val selection = Selection.rectangle(editor.document.width, editor.document.height, rect, editor.options.selectionFeather)
                editor.combineSelection(selection, "Marquee selection")
                editor.options.selectionOp = 0
                editor.invalidate(null)
            }
            Tool.SHAPE -> drawShape(editor, if (small) Rect(rect.x, rect.y, 160, 160) else rect)
            Tool.CROP -> {
                if (!small && rect.w >= 8 && rect.h >= 8) editor.cropTo(rect)
            }
            else -> Unit
        }
    }

    private fun drawShape(editor: EditorState, rect: Rect) {
        val options = editor.options
        val shape = options.shape
        val filled = options.shapeFilled
        val strokeWidth = options.shapeStroke
        val colour = editor.primary
        editor.editLayerPixels(shape.label) { target, _ ->
            val painter = Painter(target, editor.selection())
            val local = Rect(rect.x - target.x, rect.y - target.y, rect.w, rect.h)
            when (shape) {
                ShapeKind.RECTANGLE -> painter.rectangle(local, colour, strokeWidth, filled)
                ShapeKind.ELLIPSE -> painter.ellipse(local, colour, strokeWidth, filled)
                ShapeKind.LINE -> painter.line(local.x.toFloat(), local.y.toFloat(), local.right.toFloat(), local.bottom.toFloat(), colour, strokeWidth, true)
                ShapeKind.POLYGON -> {
                    val points = FloatArray(12)
                    val cx = local.x + local.w / 2f
                    val cy = local.y + local.h / 2f
                    val rx = local.w / 2f
                    val ry = local.h / 2f
                    for (i in 0 until 6) {
                        val angle = Math.toRadians((i * 60 - 90).toDouble())
                        points[i * 2] = cx + (rx * cos(angle)).toFloat()
                        points[i * 2 + 1] = cy + (ry * sin(angle)).toFloat()
                    }
                    painter.polygon(points, colour, strokeWidth, filled)
                }
            }
        }
    }

    private fun finishLasso(editor: EditorState) {
        if (lassoPoints.size < 3) return
        val selection = Selection.lassoStroke(editor.document.width, editor.document.height, lassoPoints, editor.options.selectionFeather)
        editor.combineSelection(selection, "Lasso selection")
        editor.options.selectionOp = 0
        editor.invalidate(null)
    }

    private fun finishGradient(editor: EditorState, endDocX: Float, endDocY: Float) {
        val dx = endDocX - startDocX
        val dy = endDocY - startDocY
        if (hypot(dx, dy) < 6f) return
        val options = editor.options
        val preset = editor.gradientFor(options.gradientIndex)
        val rect = Rect(
            min(startDocX, endDocX).toInt(),
            min(startDocY, endDocY).toInt(),
            max(1, abs(dx).toInt()),
            max(1, abs(dy).toInt()),
        )
        val type = when {
            abs(dx) >= abs(dy) * 2f -> GradientType.HORIZONTAL
            abs(dy) >= abs(dx) * 2f -> GradientType.VERTICAL
            dx * dy > 0 -> GradientType.DIAGONAL_DOWN
            else -> GradientType.DIAGONAL_UP
        }
        val shape = if (options.gradientStyle == 1) GradientShape.RADIAL else GradientShape.LINEAR
        val from = if (options.gradientReverse) preset.to else preset.from
        val to = if (options.gradientReverse) preset.from else preset.to
        val seed = System.nanoTime()
        editor.editLayerPixels("Gradient") { target, _ ->
            val painter = Painter(target, editor.selection())
            val local = Rect(rect.x - target.x, rect.y - target.y, rect.w, rect.h)
            painter.gradient(local, from, to, type, shape, 0.5f, false, true, seed)
        }
    }

    companion object {
        /** The largest region composited at 1:1 in one go: about 16 MB of pixels. */
        private const val MAX_REGION_PIXELS = 4_000_000L
    }
}
