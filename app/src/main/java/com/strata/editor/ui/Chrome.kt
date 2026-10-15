package com.strata.editor.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * Everything under the canvas: the tool rail, the panel tabs and whichever panel
 * is open.
 *
 * It is one view rather than a stack of them, because the whole interface is
 * drawn on demand and touch targets are matched to what was painted. That keeps
 * the layout cheap and leaves as much of the screen as possible to the picture.
 */
class ChromeView(context: Context) : View(context) {

    var editor: EditorView? = null
        private set
    private var state: EditorState? = null
    private var panel: ToolPanel? = null
    private val c2 = Canvas2D(Canvas())
    private val ui = Ui(c2, Palette.density)
    private val background = Paint()
    private var contentHeight = 0f
    private var railScroll = 0f
    private var railMax = 0f
    private var tabScroll = 0f
    private var tabMax = 0f

    /** Called when the panel was opened or closed, so the activity can re-measure. */
    var onPanelToggled: (() -> Unit)? = null

    fun attach(state: EditorState, editor: EditorView) {
        this.state = state
        this.editor = editor
        panel = ToolPanel(state)
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val state = state
        val panelHeight = if (state == null || state.panel == Panel.NONE) 0f else {
            val available = resources.displayMetrics.heightPixels * 0.5f
            min(panel?.preferredHeight() ?: Palette.dp(200f), available)
        }
        val desired = (Palette.railHeight * 1.92f + panelHeight).toInt()
        setMeasuredDimension(width, resolveSize(desired, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        val state = state ?: return
        c2.retarget(canvas)
        val rail = Palette.railHeight
        val tabs = Palette.railHeight * 0.92f
        val panelTop = rail + tabs
        val panelHeight = max(0f, height - panelTop)
        background.color = Palette.background
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), background)
        try {
            ui.beginFrame()
            railRow(state, rail)
            tabRow(state, rail, tabs)
            val openPanel = panel
            if (state.panel != Panel.NONE && openPanel != null && panelHeight > 1f) {
                val rect = RectF(0f, panelTop, width.toFloat(), height.toFloat())
                contentHeight = openPanel.draw(c2, ui, rect)
                val maxScroll = max(0f, contentHeight - panelHeight)
                state.panelScroll = state.panelScroll.coerceIn(0f, maxScroll)
                if (maxScroll > 1f) {
                    ui.register("panelScroll", rect, Ui.Kind.SCROLL) { delta ->
                        state.panelScroll = (state.panelScroll - delta).coerceIn(0f, maxScroll)
                    }
                    val fraction = (panelHeight / contentHeight).coerceIn(0f, 1f)
                    val barHeight = max(Palette.dp(28f), panelHeight * fraction)
                    val barY = panelTop + (panelHeight - barHeight) * (state.panelScroll / maxScroll)
                    c2.card(width - Palette.dp(3f), barY, width.toFloat(), barY + barHeight, Palette.accentSoft, Palette.dp(2f))
                }
            }
            ui.endFrame()
        } catch (error: OutOfMemoryError) {
            state.report("Not enough memory to draw the controls", true)
        } catch (error: Exception) {
            state.report("Control problem: ${error.message}", true)
        }
    }

    /* ------------------------------------------------------------------ rail */

    private fun railRow(state: EditorState, height: Float) {
        val tools = Tool.values()
        val size = height - Palette.dp(8f)
        val step = size + Palette.dp(4f)
        val total = tools.size * step + Palette.dp(8f)
        railMax = max(0f, total - width)
        railScroll = railScroll.coerceIn(0f, railMax)
        val top = Palette.dp(4f)
        var x = Palette.dp(4f) - railScroll
        for (tool in tools) {
            val selected = state.tool == tool
            c2.card(x, top, x + size, top + size, if (selected) Palette.accentSoft else Palette.surface, Palette.dp(10f))
            if (selected) c2.outline(x, top, x + size, top + size, Palette.accent, Palette.dp(10f), Palette.dp(1.5f))
            Icons.draw(c2, tool.icon, x + size / 2f, top + size / 2f, size * 0.52f, if (selected) Palette.accent else Palette.textPrimary, Palette.dp(1.6f))
            ui.register("tool${tool.ordinal}", RectF(x, top, x + size, top + size), Ui.Kind.BUTTON) {
                if (state.tool == tool && state.panel == Panel.TOOL_OPTIONS) state.panel = Panel.NONE
                else {
                    state.tool = tool
                    state.panel = Panel.TOOL_OPTIONS
                }
                onPanelToggled?.invoke()
            }
            x += step
        }
        if (railMax > 0f) {
            ui.register("railScroll", RectF(0f, 0f, width.toFloat(), height), Ui.Kind.SCROLL) { delta ->
                railScroll = (railScroll - delta).coerceIn(0f, railMax)
            }
        }
    }

    /* ------------------------------------------------------------------ tabs */

    private val tabs = listOf(
        Panel.LAYERS to "Layers",
        Panel.COLOUR to "Colour",
        Panel.ADJUSTMENTS to "Adjust",
        Panel.FILTERS to "Filters",
        Panel.HISTORY to "History",
        Panel.CANVAS to "Canvas",
        Panel.FILE to "File",
        Panel.LAYER_STYLE to "Layer",
    )

    private fun tabRow(state: EditorState, top: Float, height: Float) {
        val size = height - Palette.dp(6f)
        val step = min(size * 1.9f, width / 4.6f)
        val total = tabs.size * step + Palette.dp(4f)
        tabMax = max(0f, total - width)
        tabScroll = tabScroll.coerceIn(0f, tabMax)
        val y = top + Palette.dp(3f)
        var x = Palette.dp(4f) - tabScroll
        for ((target, label) in tabs) {
            val selected = state.panel == target
            c2.card(x, y, x + step - Palette.dp(4f), y + size, if (selected) Palette.accentSoft else Palette.surfaceHigh, Palette.dp(9f))
            c2.labelFitted(
                label,
                x + (step - Palette.dp(4f)) / 2f,
                y + size / 2f + Palette.dp(5f),
                if (selected) Palette.accent else Palette.textPrimary,
                Palette.sp(12.5f),
                step - Palette.dp(16f),
                Paint.Align.CENTER,
            )
            ui.register("tab${target.name}", RectF(x, y, x + step - Palette.dp(4f), y + size), Ui.Kind.BUTTON) {
                state.panel = if (state.panel == target) Panel.NONE else target
                state.panelScroll = 0f
                onPanelToggled?.invoke()
            }
            x += step
        }
        if (tabMax > 0f) {
            ui.register("tabScroll", RectF(0f, top, width.toFloat(), top + height), Ui.Kind.SCROLL) { delta ->
                tabScroll = (tabScroll - delta).coerceIn(0f, tabMax)
            }
        }
    }

    /* ----------------------------------------------------------------- input */

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val state = state ?: return false
        val phase = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> 0
            MotionEvent.ACTION_MOVE -> 1
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> 2
            else -> return true
        }
        return try {
            val consumed = ui.pointer(event.x, event.y, phase)
            if (phase == 2) state.finishPendingEdits()
            postInvalidateOnAnimation()
            if (!consumed && phase == 0) false else true
        } catch (error: Exception) {
            state.report("Control problem: ${error.message}", true)
            true
        }
    }
}

/** The strip along the top: undo, redo, zoom and the document's name. */
class TopBarView(context: Context) : View(context) {

    var state: EditorState? = null
    var editor: EditorView? = null
    var onPanelRequested: (() -> Unit)? = null

    private val c2 = Canvas2D(Canvas())
    private val ui = Ui(c2, Palette.density)
    private val background = Paint()
    private var message: String? = null
    private var messageUntil = 0L

    fun showMessage(text: String) {
        message = text
        messageUntil = SystemClock.uptimeMillis() + 2600L
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), Palette.topBarHeight.toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val state = state ?: return
        c2.retarget(canvas)
        background.color = Palette.surface
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), background)
        val size = height - Palette.dp(8f)
        val top = Palette.dp(4f)
        var x = Palette.dp(4f)
        try {
            ui.beginFrame()
            fun iconButton(icon: Icon, id: String, enabled: Boolean = true, tint: Int = Palette.textPrimary, action: () -> Unit) {
                c2.card(x, top, x + size, top + size, if (enabled) Palette.surfaceHigh else Palette.surface, Palette.dp(9f))
                Icons.draw(c2, icon, x + size / 2f, top + size / 2f, size * 0.5f, if (enabled) tint else Palette.textFaint, Palette.dp(1.6f))
                ui.register(id, RectF(x, top, x + size, top + size), Ui.Kind.BUTTON) { if (enabled) action() }
                x += size + Palette.dp(4f)
            }
            iconButton(Icon.UNDO, "undo", state.canUndo) { state.undo() }
            iconButton(Icon.REDO, "redo", state.canRedo) { state.redo() }
            iconButton(Icon.HISTORY, "history") {
                state.panel = if (state.panel == Panel.HISTORY) Panel.NONE else Panel.HISTORY
                onPanelRequested?.invoke()
            }
            iconButton(Icon.CHECK, "fit") { editor?.fitToWindow() }
            iconButton(Icon.ZOOM, "zoomIn") { editor?.zoomStep(true) }
            iconButton(Icon.MINUS, "zoomOut") { editor?.zoomStep(false) }
            iconButton(Icon.CLOSE, "noSelection", state.hasSelection(), Palette.accent) { state.deselect() }
            val textX = x + Palette.dp(6f)
            val available = width - textX - Palette.dp(8f)
            val now = SystemClock.uptimeMillis()
            val banner = message?.takeIf { now < messageUntil }
            val busy = state.busy
            if (busy != null) {
                c2.labelFitted(busy, textX, height / 2f - Palette.dp(2f), Palette.accent, Palette.sp(12f), available)
            } else {
                val shown = banner ?: state.status
                val colour = if (banner != null) Palette.textPrimary else if (state.statusIsError) Palette.danger else Palette.textDim
                c2.labelFitted(shown, textX, height / 2f + Palette.dp(5f), colour, Palette.sp(12.5f), available)
            }
            ui.endFrame()
        } catch (error: Exception) {
            state.report("Top bar problem: ${error.message}", true)
        }
        if (message != null && SystemClock.uptimeMillis() >= messageUntil) {
            message = null
            postInvalidateDelayed(160)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val phase = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> 0
            MotionEvent.ACTION_MOVE -> 1
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> 2
            else -> return true
        }
        val consumed = ui.pointer(event.x, event.y, phase)
        invalidate()
        return consumed
    }
}
