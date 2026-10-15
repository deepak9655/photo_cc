package com.strata.editor.ui

import android.graphics.Paint
import android.graphics.RectF
import com.strata.editor.core.Adjustment
import com.strata.editor.core.BlendMode
import com.strata.editor.core.Brush
import com.strata.editor.core.Colors
import com.strata.editor.core.Gradients
import com.strata.editor.core.ImageFormat
import com.strata.editor.core.Layer
import com.strata.editor.core.LayerKind
import com.strata.editor.core.Rect
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The panel under the canvas.
 *
 * It is drawn immediately: given the state and a rectangle, it lays out controls
 * and reacts to them on the spot. Every control registers itself with the
 * hit-testing [Ui] as it is painted, so there is no view tree to keep in step with
 * the document — and a slider under a thumb keeps working while the layer behind
 * it changes sixty times a second.
 */
class ToolPanel(private val state: EditorState) {

    /** How tall the panel wants to be, in pixels. */
    fun preferredHeight(): Float = when (state.panel) {
        Panel.NONE -> 0f
        Panel.TOOL_OPTIONS -> if (state.tool.hasOptions) Palette.dp(216f) else Palette.dp(120f)
        Panel.LAYERS -> Palette.dp(300f)
        Panel.COLOUR -> Palette.dp(316f)
        Panel.ADJUSTMENTS -> Palette.dp(320f)
        Panel.FILTERS -> Palette.dp(280f)
        Panel.HISTORY -> Palette.dp(210f)
        Panel.CANVAS -> Palette.dp(340f)
        Panel.FILE -> Palette.dp(300f)
        Panel.TEXT -> Palette.dp(300f)
        Panel.LAYER_STYLE -> Palette.dp(240f)
    }

    /**
     * Draws the panel and returns the height its content actually needs, so the
     * chrome can decide whether it has to scroll.
     */
    fun draw(c2: Canvas2D, ui: Ui, rect: RectF): Float {
        c2.card(rect.left, rect.top, rect.right, rect.bottom, Palette.surface, 0f)
        c2.line(rect.left, rect.top, rect.right, rect.top, 0x1FFFFFFF, 1f)
        val left = rect.left + Palette.dp(12f)
        val width = rect.right - rect.left - Palette.dp(24f)
        val y = rect.top + Palette.dp(12f)
        var needed = 0f
        val saved = c2.save()
        c2.clip(rect.left, rect.top, rect.right, rect.bottom)
        c2.translate(0f, -state.panelScroll)
        ui.viewportOffsetY = state.panelScroll
        try {
            when (state.panel) {
                Panel.NONE -> Unit
                Panel.TOOL_OPTIONS -> toolOptions(c2, ui, left, y, width)
                Panel.LAYERS -> layers(c2, ui, left, y, width)
                Panel.COLOUR -> colour(c2, ui, left, y, width)
                Panel.ADJUSTMENTS -> adjustments(c2, ui, left, y, width)
                Panel.FILTERS -> filters(c2, ui, left, y, width)
                Panel.HISTORY -> history(c2, ui, left, y, width)
                Panel.CANVAS -> canvasSize(c2, ui, left, y, width)
                Panel.FILE -> files(c2, ui, left, y, width)
                Panel.TEXT -> text(c2, ui, left, y, width)
                Panel.LAYER_STYLE -> layerStyle(c2, ui, left, y, width)
            }
        } catch (error: OutOfMemoryError) {
            state.report("Not enough memory for that panel", true)
        } finally {
            c2.restore()
            saved.hashCode()
            ui.viewportOffsetY = 0f
        }
        // The lowest control drawn decides whether the panel has to scroll.
        needed = ui.contentBottom() - rect.top + Palette.dp(16f)
        return max(needed, 0f)
    }

    private fun half(width: Float): Float = (width - Palette.dp(10f)) / 2f

    /* -------------------------------------------------------- tool options */

    private fun toolOptions(c2: Canvas2D, ui: Ui, x: Float, y0: Float, width: Float) {
        var y = y0
        val tool = state.tool
        val options = state.options
        val gap = Palette.dp(10f)
        val halfWidth = half(width)
        when (tool) {
            Tool.BRUSH, Tool.ERASER, Tool.CLONE, Tool.RETOUCH, Tool.SMOOTH -> {
                val brush = options.activeBrush(tool)
                ui.slider("size", x, y, width, "Size ${brush.size.roundToInt()} px", brush.size, 1f, 600f, showValue = false) {
                    brush.size = it
                }
                y += Palette.dp(46f)
                ui.slider("hardness", x, y, width, "Hardness", brush.hardness, 0f, 1f) { brush.hardness = it }
                y += Palette.dp(46f)
                ui.slider("flow", x, y, width, "Flow", brush.flow, 0.02f, 1f) { brush.flow = it }
                y += Palette.dp(46f)
                if (tool == Tool.RETOUCH) {
                    ui.chips("retouch", x, y, width, RetouchMode.values().toList(), { it.label }, options.retouch.ordinal, perRow = 4) { index ->
                        options.retouch = RetouchMode.values()[index]
                    }
                    y += Palette.dp(46f)
                    ui.slider("retouchStrength", x, y, width, "Strength", options.retouchStrength, 0.05f, 1f) { options.retouchStrength = it }
                    y += Palette.dp(46f)
                }
                if (tool == Tool.SMOOTH) {
                    ui.chips("smooth", x, y, width, SmoothMode.values().toList(), { it.label }, options.smooth.ordinal, perRow = 3) { index ->
                        options.smooth = SmoothMode.values()[index]
                    }
                    y += Palette.dp(46f)
                    ui.slider("smoothStrength", x, y, width, "Strength", options.smoothStrength, 0.05f, 1f) { options.smoothStrength = it }
                    y += Palette.dp(46f)
                }
                if (tool == Tool.CLONE) {
                    ui.button("resetClone", x, y, halfWidth, Palette.dp(44f), "Reset source") {
                        options.cloneSourceX = -1f
                        options.cloneSourceY = -1f
                        state.report("Tap the canvas to set the clone source")
                    }
                    ui.toggle("aligned", x + halfWidth + gap, y, halfWidth, Palette.dp(44f), "Aligned", options.cloneAligned) { options.cloneAligned = it }
                    y += Palette.dp(50f)
                }
                y = ui.section("Presets", x, y, width)
                val presets = Brush.presets
                presets.forEachIndexed { index, preset ->
                    val column = index % 4
                    val row = index / 4
                    val chipWidth = (width - Palette.dp(6f) * 3) / 4f
                    val selected = brush.size == preset.size && kotlin.math.abs(brush.hardness - preset.hardness) < 0.01f
                    ui.button("preset$index", x + column * (chipWidth + Palette.dp(6f)), y + row * Palette.dp(42f), chipWidth, Palette.dp(36f), preset.name, selected = selected) {
                        val chosen = Brush.fromPreset(preset)
                        if (tool == Tool.ERASER) options.eraser = chosen else options.brush = chosen
                    }
                }
            }
            Tool.FILL -> {
                ui.slider("fillTolerance", x, y, width, "Tolerance", options.fillTolerance.toFloat(), 0f, 255f, valueLabel = { it.roundToInt().toString() }) {
                    options.fillTolerance = it.roundToInt()
                }
                y += Palette.dp(46f)
                ui.toggle("contiguous", x, y, width, Palette.dp(44f), "Contiguous", options.fillContiguous) { options.fillContiguous = it }
                y += Palette.dp(50f)
                ui.toggle("antialias", x, y, width, Palette.dp(44f), "Anti-alias edges", options.fillAntialias) { options.fillAntialias = it }
                y += Palette.dp(54f)
                ui.button("fillLayer", x, y, width, Palette.dp(46f), "Fill whole layer", icon = Icon.FILL) {
                    state.editLayerPixels("Fill layer") { target, pixels ->
                        val painter = com.strata.editor.core.Painter(target, state.selection())
                        painter.fillRect(Rect(0, 0, pixels.width, pixels.height), state.primary, 1f)
                    }
                }
            }
            Tool.GRADIENT -> {
                y = ui.section("Gradient", x, y, width)
                ui.chips("gradient", x, y, width, Gradients.presets, { it.name }, options.gradientIndex, perRow = 2, rowHeight = Palette.dp(38f)) { index ->
                    options.gradientIndex = index
                }
                y += ((Gradients.presets.size + 1) / 2) * Palette.dp(44f)
                ui.chips("gradientStyle", x, y, width, listOf("Linear", "Radial"), { it }, options.gradientStyle, perRow = 2) { index ->
                    options.gradientStyle = index
                }
                y += Palette.dp(48f)
                ui.toggle("gradientReverse", x, y, width, Palette.dp(44f), "Reverse", options.gradientReverse) { options.gradientReverse = it }
            }
            Tool.SHAPE -> {
                ui.chips("shape", x, y, width, ShapeKind.values().toList(), { it.label }, options.shape.ordinal, perRow = 2) { index ->
                    options.shape = ShapeKind.values()[index]
                }
                y += Palette.dp(48f)
                ui.toggle("shapeFilled", x, y, width, Palette.dp(44f), "Filled", options.shapeFilled) { options.shapeFilled = it }
                y += Palette.dp(50f)
                ui.slider("shapeStroke", x, y, width, "Stroke ${options.shapeStroke.roundToInt()} px", options.shapeStroke, 1f, 80f, showValue = false) {
                    options.shapeStroke = it
                }
            }
            Tool.SELECT, Tool.LASSO, Tool.WAND -> {
                ui.chips("selectionOp", x, y, width, listOf("New", "Add", "Subtract", "Intersect"), { it }, options.selectionOp, perRow = 4, rowHeight = Palette.dp(36f)) { index ->
                    options.selectionOp = index
                }
                y += Palette.dp(46f)
                if (tool == Tool.WAND) {
                    ui.slider("wandTolerance", x, y, width, "Tolerance", options.wandTolerance.toFloat(), 0f, 255f, valueLabel = { it.roundToInt().toString() }) {
                        options.wandTolerance = it.roundToInt()
                    }
                    y += Palette.dp(46f)
                    ui.toggle("wandContiguous", x, y, width, Palette.dp(44f), "Contiguous", options.wandContiguous) { options.wandContiguous = it }
                    y += Palette.dp(50f)
                }
                ui.slider("feather", x, y, width, "Feather ${options.selectionFeather.roundToInt()} px", options.selectionFeather, 0f, 120f, showValue = false) {
                    options.selectionFeather = it
                }
                y += Palette.dp(48f)
                ui.button("selectAll", x, y, halfWidth, Palette.dp(44f), "Select all") { state.selectAll() }
                ui.button("deselect", x + halfWidth + gap, y, halfWidth, Palette.dp(44f), "Deselect") { state.deselect() }
                y += Palette.dp(50f)
                ui.button("invertSel", x, y, halfWidth, Palette.dp(44f), "Invert") { state.invertSelection() }
                ui.button("featherNow", x + halfWidth + gap, y, halfWidth, Palette.dp(44f), "Apply feather") { state.featherSelection(options.selectionFeather) }
                y += Palette.dp(50f)
                ui.button("growSel", x, y, halfWidth, Palette.dp(44f), "Grow 2 px") { state.growSelection(2) }
                ui.button("shrinkSel", x + halfWidth + gap, y, halfWidth, Palette.dp(44f), "Shrink 2 px") { state.growSelection(-2) }
            }
            Tool.TEXT -> {
                ui.button("openText", x, y, width, Palette.dp(46f), "Edit this layer's text", icon = Icon.TEXT) {
                    val layer = state.activeLayer
                    if (layer != null && layer.kind == LayerKind.TEXT) state.openTextEditor(layer)
                    else state.report("Tap the canvas with the text tool to place text", true)
                }
                y += Palette.dp(56f)
                ui.slider("textSize", x, y, width, "Size ${options.textSize.roundToInt()} px", options.textSize, 6f, 600f, showValue = false) {
                    options.textSize = it
                }
                y += Palette.dp(46f)
                ui.button("bold", x, y, halfWidth, Palette.dp(44f), "Bold", selected = options.textBold) { options.textBold = !options.textBold }
                ui.button("italic", x + halfWidth + gap, y, halfWidth, Palette.dp(44f), "Italic", selected = options.textItalic) { options.textItalic = !options.textItalic }
                y += Palette.dp(52f)
                ui.chips("align", x, y, width, listOf("Left", "Centre", "Right"), { it }, options.textAlign, perRow = 3) { index ->
                    state.setTextAlign(index)
                }
            }
            else -> {
                c2.labelFitted(
                    "${tool.label}: drag on the canvas. Pinch with two fingers to zoom, drag with two to move.",
                    x,
                    y + Palette.dp(18f),
                    Palette.textDim,
                    Palette.sp(12.5f),
                    width,
                )
            }
        }
    }

    /* --------------------------------------------------------------- layers */

    private fun layers(c2: Canvas2D, ui: Ui, x: Float, y0: Float, width: Float) {
        var y = y0
        val gap = Palette.dp(8f)
        val actionWidth = (width - gap * 4) / 5f
        val icons = listOf(Icon.PLUS, Icon.COPY, Icon.MASK, Icon.FILTER, Icon.TRASH)
        val hints = listOf("New layer", "Duplicate", "Add mask", "Merge down", "Delete")
        val actions: List<() -> Unit> = listOf(
            { state.newPixelLayer() },
            { state.duplicateLayer() },
            { state.addMaskFromSelection() },
            { state.mergeDown() },
            { state.deleteSelectedLayers() },
        )
        icons.forEachIndexed { index, icon ->
            ui.button("layerAction$index", x + index * (actionWidth + gap), y, actionWidth, Palette.dp(48f), null, icon = icon) { actions[index]() }
        }
        y += Palette.dp(58f)

        val rows = state.document.flattenTopFirst()
        val rowHeight = Palette.dp(64f)
        val step = rowHeight + Palette.dp(4f)
        val listHeight = Palette.dp(150f)
        val total = rows.size * step
        val scroll = state.layerScroll.coerceIn(0f, max(0f, total - listHeight))
        state.layerScroll = scroll
        var rowY = y - scroll
        for ((layer, depth) in rows) {
            if (rowY > y - rowHeight && rowY < y + listHeight) drawLayerRow(c2, ui, x, rowY, width, rowHeight, layer, depth)
            rowY += step
        }
        if (rows.isEmpty()) c2.label("No layers yet", x, y + Palette.dp(20f), Palette.textDim, Palette.sp(13f))
        if (total > listHeight) {
            ui.register("layerScroll", RectF(x, y, x + width, y + listHeight), Ui.Kind.SCROLL) { delta ->
                state.layerScroll = (state.layerScroll - delta).coerceIn(0f, max(0f, total - listHeight))
            }
            val fraction = listHeight / total
            val barHeight = max(Palette.dp(28f), listHeight * fraction)
            val barY = y + (listHeight - barHeight) * (scroll / max(1f, total - listHeight))
            c2.card(x + width - Palette.dp(3f), barY, x + width, barY + barHeight, Palette.surfaceMax, Palette.dp(2f))
        }
        y += listHeight + Palette.dp(10f)

        val active = state.activeLayer
        if (active != null) {
            c2.labelFitted(active.name, x, y + Palette.dp(14f), Palette.textPrimary, Palette.sp(13f), width)
            y += Palette.dp(22f)
            ui.slider("layerOpacity", x, y, width, "Opacity", active.opacity, 0f, 1f) { value ->
                state.beginLiveEdit("Layer opacity")
                state.setLayerOpacity(active, value)
            }
            y += Palette.dp(48f)
            ui.button("blend", x, y, width, Palette.dp(44f), "Blend: ${Ui.blendLabel(active.blend)}", icon = Icon.SLIDERS) {
                state.cycleBlend(active)
            }
            y += Palette.dp(50f)
            ui.toggle("clip", x, y, half(width), Palette.dp(44f), "Clip below", active.clipping) { state.setLayerClipping(active, it) }
            ui.toggle("lock", x + half(width) + gap, y, half(width), Palette.dp(44f), "Lock", active.locked) { state.setLayerLocked(active, it) }
            y += Palette.dp(52f)
            ui.button("layerStyle", x, y, width, Palette.dp(44f), "Transform, mask, rasterise…", icon = Icon.TRANSFORM) {
                state.panel = Panel.LAYER_STYLE
            }
        }
    }

    private fun drawLayerRow(c2: Canvas2D, ui: Ui, x: Float, y: Float, width: Float, height: Float, layer: Layer, depth: Int) {
        val selected = state.layerSelection.any { it.id == layer.id }
        val indent = Palette.dp(8f) * min(depth, 4)
        val rowX = x + indent
        val rowWidth = width - indent
        c2.card(rowX, y, rowX + rowWidth, y + height, if (selected) Palette.accentSoft else Palette.surfaceHigh, Palette.dp(10f))
        if (selected) c2.outline(rowX, y, rowX + rowWidth, y + height, Palette.accent, Palette.dp(10f), Palette.dp(1.5f))

        val eyeSize = Palette.dp(32f)
        val eyeY = y + (height - eyeSize) / 2f
        val eyeCx = rowX + Palette.dp(20f)
        Icons.draw(c2, if (layer.visible) Icon.EYE else Icon.EYE_OFF, eyeCx, eyeY + eyeSize / 2f, Palette.dp(18f), if (layer.visible) Palette.textPrimary else Palette.textFaint, Palette.dp(1.6f))
        ui.register("eye${layer.id}", RectF(rowX + Palette.dp(4f), eyeY, rowX + Palette.dp(36f), eyeY + eyeSize), Ui.Kind.BUTTON) {
            state.setLayerVisible(layer, !layer.visible)
        }

        val thumbHeight = height - Palette.dp(14f)
        val thumbWidth = thumbHeight * 1.25f
        val thumbX = rowX + Palette.dp(40f)
        val thumbY = y + Palette.dp(7f)
        if (!layer.isGroup) {
            val thumbnail = state.thumbnail(layer, max(8, thumbWidth.toInt()), max(8, thumbHeight.toInt()))
            c2.bitmap(thumbnail, thumbX, thumbY, thumbX + thumbWidth, thumbY + thumbHeight, Palette.dp(5f))
        } else {
            c2.card(thumbX, thumbY, thumbX + thumbWidth, thumbY + thumbHeight, Palette.surfaceMax, Palette.dp(5f))
            Icons.draw(c2, Icon.GROUP, thumbX + thumbWidth / 2f, thumbY + thumbHeight / 2f, Palette.dp(20f), Palette.textDim, Palette.dp(1.5f))
        }
        c2.outline(thumbX, thumbY, thumbX + thumbWidth, thumbY + thumbHeight, 0x33FFFFFF, Palette.dp(5f), Palette.dp(1f))

        var textX = thumbX + thumbWidth + Palette.dp(10f)
        if (depth > 0) {
            Icons.draw(c2, Icon.MORE, rowX + Palette.dp(30f), y + height / 2f, Palette.dp(12f), Palette.textFaint, Palette.dp(1.4f))
            textX = thumbX + thumbWidth + Palette.dp(10f)
        }
        val available = rowX + rowWidth - textX - Palette.dp(10f)
        c2.labelFitted(layer.name, textX, y + Palette.dp(26f), Palette.textPrimary, Palette.sp(13.5f), available)
        val detail = when {
            layer.isGroup -> "${layer.children.size} layers"
            layer.kind == LayerKind.TEXT -> "Text"
            layer.kind == LayerKind.ADJUSTMENT -> layer.adjustment?.title ?: "Adjustment"
            layer.kind == LayerKind.SOLID -> "Solid colour"
            else -> "${layer.pixels?.width ?: 0} × ${layer.pixels?.height ?: 0}"
        }
        val blend = if (layer.blend == BlendMode.NORMAL) "" else " · ${Ui.blendLabel(layer.blend)}"
        val opacity = if (layer.opacity >= 0.999f) "" else " · ${(layer.opacity * 100).roundToInt()}%"
        val mask = if (layer.mask != null) " · mask" else ""
        c2.labelFitted(detail + blend + opacity + mask, textX, y + Palette.dp(45f), Palette.textDim, Palette.sp(11.5f), available)

        ui.register("layer${layer.id}", RectF(rowX, y, rowX + rowWidth, y + height), Ui.Kind.BUTTON) {
            if (state.layerSelection.any { it.id == layer.id } && state.layerSelection.size == 1) state.openLayerMenu(layer)
            else state.selectLayer(layer)
        }
    }

    /* --------------------------------------------------------------- colour */

    private fun colour(c2: Canvas2D, ui: Ui, x: Float, y0: Float, width: Float) {
        var y = y0
        val gap = Palette.dp(10f)
        val wellWidth = (width - gap) / 2f
        val wellHeight = Palette.dp(76f)
        c2.card(x, y, x + wellWidth, y + wellHeight, state.primary, Palette.dp(10f))
        c2.outline(x, y, x + wellWidth, y + wellHeight, Palette.accent, Palette.dp(10f), Palette.dp(2f))
        c2.label("Foreground #${Colors.toHex(state.primary)}", x + Palette.dp(8f), y + wellHeight + Palette.dp(14f), Palette.textDim, Palette.sp(11f))
        c2.card(x + wellWidth + gap, y, x + width, y + wellHeight, state.secondary, Palette.dp(10f))
        c2.outline(x + wellWidth + gap, y, x + width, y + wellHeight, 0x33FFFFFF, Palette.dp(10f), Palette.dp(1.5f))
        c2.label("Background", x + wellWidth + gap + Palette.dp(8f), y + wellHeight + Palette.dp(14f), Palette.textDim, Palette.sp(11f))
        y += wellHeight + Palette.dp(24f)

        ui.button("swapColours", x, y, wellWidth, Palette.dp(44f), "Swap", icon = Icon.SWAP) {
            val temp = state.primary
            state.primary = state.secondary
            state.secondary = temp
        }
        ui.button("resetColours", x + wellWidth + gap, y, wellWidth, Palette.dp(44f), "Black / white") {
            state.primary = Colors.BLACK
            state.secondary = Colors.WHITE
        }
        y += Palette.dp(54f)

        y = ui.section("Mix", x, y, width)
        val hsv = Colors.toHsv(state.primary)
        var hue = hsv[0]
        var saturation = hsv[1]
        var value = hsv[2]
        ui.slider("hue", x, y, width, "Hue", hue, 0f, 360f, valueLabel = { "${it.roundToInt()}°" }) { next ->
            hue = next
            state.primary = Colors.withAlpha(Colors.fromHsv(hue, max(0.02f, saturation), max(0.05f, value)), 255)
        }
        y += Palette.dp(46f)
        ui.slider("saturation", x, y, width, "Saturation", saturation, 0f, 1f) { next ->
            saturation = next
            state.primary = Colors.withAlpha(Colors.fromHsv(hue, saturation, max(0.05f, value)), 255)
        }
        y += Palette.dp(46f)
        ui.slider("value", x, y, width, "Brightness", value, 0f, 1f) { next ->
            value = next
            state.primary = Colors.withAlpha(Colors.fromHsv(hue, saturation, value), 255)
        }
        y += Palette.dp(52f)

        val swatchSize = (width - Palette.dp(5f) * 5) / 6f
        if (state.recentColours.isNotEmpty()) {
            y = ui.section("Recent", x, y, width)
            state.recentColours.take(12).forEachIndexed { index, colour ->
                val column = index % 6
                val row = index / 6
                val swatchX = x + column * (swatchSize + Palette.dp(5f))
                val swatchY = y + row * (swatchSize + Palette.dp(5f))
                c2.swatch(swatchX, swatchY, swatchX + swatchSize, swatchY + swatchSize, colour, Palette.dp(6f), colour == state.primary)
                ui.register("recent$index", RectF(swatchX, swatchY, swatchX + swatchSize, swatchY + swatchSize), Ui.Kind.BUTTON) {
                    state.primary = colour
                }
            }
            y += ((state.recentColours.size + 5) / 6).coerceAtMost(2) * (swatchSize + Palette.dp(5f)) + Palette.dp(8f)
        }
        y = ui.section("Presets", x, y, width)
        presetColours.forEachIndexed { index, colour ->
            val column = index % 6
            val row = index / 6
            val swatchX = x + column * (swatchSize + Palette.dp(5f))
            val swatchY = y + row * (swatchSize + Palette.dp(5f))
            c2.swatch(swatchX, swatchY, swatchX + swatchSize, swatchY + swatchSize, colour, Palette.dp(6f), colour == state.primary)
            ui.register("preset$index", RectF(swatchX, swatchY, swatchX + swatchSize, swatchY + swatchSize), Ui.Kind.BUTTON) {
                state.primary = colour
                state.rememberColour(colour)
            }
        }
    }

    private val presetColours = listOf(
        0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFF7C6CFF.toInt(), 0xFF35C4A0.toInt(), 0xFFF2C14E.toInt(), 0xFFE8564B.toInt(),
        0xFF2D6BE4.toInt(), 0xFFE04FA8.toInt(), 0xFF8B5A2B.toInt(), 0xFF9AA7B4.toInt(), 0xFF6ECB60.toInt(), 0xFF14161A.toInt(),
    )

    /* ---------------------------------------------------------- adjustments */

    private fun adjustments(c2: Canvas2D, ui: Ui, x: Float, y0: Float, width: Float) {
        var y = y0
        val gap = Palette.dp(10f)
        val target = state.adjustmentTarget
        val live = target?.adjustment
        if (live != null) {
            c2.labelFitted("Editing ${target.name}", x, y + Palette.dp(16f), Palette.accent, Palette.sp(12.5f), width)
            y += Palette.dp(26f)
            y = adjustmentControls(c2, ui, live, x, y, width)
            y += Palette.dp(8f)
            val halfWidth = half(width)
            ui.button("adjDone", x, y, halfWidth, Palette.dp(46f), "Done", selected = true) { state.adjustmentTarget = null }
            ui.button("adjFlatten", x + halfWidth + gap, y, halfWidth, Palette.dp(46f), "Bake into pixels") { state.bakeAdjustment() }
            return
        }
        c2.label("A new adjustment layer", x, y + Palette.dp(16f), Palette.textDim, Palette.sp(12f))
        y += Palette.dp(26f)
        val catalog = Adjustment.catalog()
        catalog.forEachIndexed { index, entry ->
            val column = index % 2
            val row = index / 2
            val chipWidth = (width - Palette.dp(8f)) / 2f
            ui.button("adj$index", x + column * (chipWidth + Palette.dp(8f)), y + row * Palette.dp(50f), chipWidth, Palette.dp(42f), entry.first) {
                val layer = state.newAdjustmentLayer(entry.second())
                state.adjustmentTarget = layer
            }
        }
        y += ((catalog.size + 1) / 2) * Palette.dp(50f) + Palette.dp(6f)
        ui.button("adjToLayer", x, y, width, Palette.dp(46f), "Adjustment layers keep working as you edit", icon = Icon.ADJUST, enabled = false) { }
    }

    /** Sliders for whichever adjustment object is being edited. */
    private fun adjustmentControls(c2: Canvas2D, ui: Ui, adjustment: Adjustment, x: Float, y0: Float, width: Float): Float {
        var y = y0
        fun live(label: String, block: () -> Unit) {
            state.beginLiveEdit(label)
            block()
            state.refreshAdjustment()
        }
        when (adjustment) {
            is Adjustment.BrightnessContrast -> {
                ui.slider("bcBright", x, y, width, "Brightness", adjustment.brightness, -1f, 1f) { live("Brightness") { adjustment.brightness = it } }
                y += Palette.dp(46f)
                ui.slider("bcContrast", x, y, width, "Contrast", adjustment.contrast, -1f, 1f) { live("Contrast") { adjustment.contrast = it } }
                y += Palette.dp(46f)
            }
            is Adjustment.Levels -> {
                ui.slider("lvInBlack", x, y, width, "Input black", adjustment.inputBlack.toFloat(), 0f, 254f, valueLabel = { it.roundToInt().toString() }) {
                    live("Levels") { adjustment.inputBlack = it.roundToInt() }
                }
                y += Palette.dp(46f)
                ui.slider("lvInWhite", x, y, width, "Input white", adjustment.inputWhite.toFloat(), 1f, 255f, valueLabel = { it.roundToInt().toString() }) {
                    live("Levels") { adjustment.inputWhite = it.roundToInt() }
                }
                y += Palette.dp(46f)
                ui.slider("lvGamma", x, y, width, "Gamma", adjustment.gamma, 0.1f, 4f) { live("Levels") { adjustment.gamma = it } }
                y += Palette.dp(46f)
                ui.slider("lvOutBlack", x, y, width, "Output black", adjustment.outputBlack.toFloat(), 0f, 255f, valueLabel = { it.roundToInt().toString() }) {
                    live("Levels") { adjustment.outputBlack = it.roundToInt() }
                }
                y += Palette.dp(46f)
                ui.slider("lvOutWhite", x, y, width, "Output white", adjustment.outputWhite.toFloat(), 0f, 255f, valueLabel = { it.roundToInt().toString() }) {
                    live("Levels") { adjustment.outputWhite = it.roundToInt() }
                }
                y += Palette.dp(46f)
            }
            is Adjustment.Exposure -> {
                ui.slider("exExposure", x, y, width, "Exposure", adjustment.exposure, -4f, 4f) { live("Exposure") { adjustment.exposure = it } }
                y += Palette.dp(46f)
                ui.slider("exOffset", x, y, width, "Offset", adjustment.offset, -0.5f, 0.5f) { live("Exposure") { adjustment.offset = it } }
                y += Palette.dp(46f)
                ui.slider("exGamma", x, y, width, "Gamma", adjustment.gamma, 0.1f, 4f) { live("Exposure") { adjustment.gamma = it } }
                y += Palette.dp(46f)
            }
            is Adjustment.Vibrance -> {
                ui.slider("vib", x, y, width, "Vibrance", adjustment.vibrance, -1f, 1f) { live("Vibrance") { adjustment.vibrance = it } }
                y += Palette.dp(46f)
                ui.slider("vibSat", x, y, width, "Saturation", adjustment.saturation, -1f, 1f) { live("Saturation") { adjustment.saturation = it } }
                y += Palette.dp(46f)
            }
            is Adjustment.HueSaturation -> {
                ui.slider("hsHue", x, y, width, "Hue", adjustment.hue, -180f, 180f) { live("Hue") { adjustment.hue = it } }
                y += Palette.dp(46f)
                ui.slider("hsSat", x, y, width, "Saturation", adjustment.saturation, -1f, 1f) { live("Saturation") { adjustment.saturation = it } }
                y += Palette.dp(46f)
                ui.slider("hsLight", x, y, width, "Lightness", adjustment.lightness, -1f, 1f) { live("Lightness") { adjustment.lightness = it } }
                y += Palette.dp(46f)
                ui.toggle("hsColorize", x, y, width, Palette.dp(44f), "Colourise", adjustment.colorize) {
                    live("Colourise") { adjustment.colorize = it }
                }
                y += Palette.dp(50f)
            }
            is Adjustment.ColorBalance -> {
                val labels = listOf("Red", "Green", "Blue")
                for (index in 0..2) {
                    ui.slider("cbShadow$index", x, y, width, "Shadows ${labels[index]}", adjustment.shadows[index], -1f, 1f) {
                        live("Colour balance") { adjustment.shadows[index] = it }
                    }
                    y += Palette.dp(46f)
                }
                for (index in 0..2) {
                    ui.slider("cbMid$index", x, y, width, "Midtones ${labels[index]}", adjustment.midtones[index], -1f, 1f) {
                        live("Colour balance") { adjustment.midtones[index] = it }
                    }
                    y += Palette.dp(46f)
                }
                for (index in 0..2) {
                    ui.slider("cbHigh$index", x, y, width, "Highlights ${labels[index]}", adjustment.highlights[index], -1f, 1f) {
                        live("Colour balance") { adjustment.highlights[index] = it }
                    }
                    y += Palette.dp(46f)
                }
            }
            is Adjustment.ChannelMixer -> {
                val rows = listOf(adjustment.red to "Red", adjustment.green to "Green", adjustment.blue to "Blue")
                val sources = listOf("Red", "Green", "Blue")
                rows.forEachIndexed { rowIndex, entry ->
                    entry.first.forEachIndexed { column, _ ->
                        ui.slider("cm$rowIndex$column", x, y, width, "${entry.second} from ${sources[column]}", entry.first[column], -200f, 200f) {
                            live("Channel mixer") { entry.first[column] = it }
                        }
                        y += Palette.dp(46f)
                    }
                }
                ui.toggle("cmMono", x, y, width, Palette.dp(44f), "Monochrome", adjustment.monochrome) {
                    live("Monochrome") { adjustment.monochrome = it }
                }
                y += Palette.dp(50f)
            }
            is Adjustment.PhotoFilter -> {
                ui.slider("pfDensity", x, y, width, "Density", adjustment.density, 0f, 1f) { live("Photo filter") { adjustment.density = it } }
                y += Palette.dp(46f)
                ui.toggle("pfPreserve", x, y, width, Palette.dp(44f), "Preserve luminosity", adjustment.preserveLuminosity) {
                    live("Photo filter") { adjustment.preserveLuminosity = it }
                }
                y += Palette.dp(50f)
                val filters = listOf(0xFFEC8A00.toInt() to "Warming", 0xFF85A0EC.toInt() to "Cooling", 0xFF9C7A5C.toInt() to "Sepia", 0xFF3E9E6B.toInt() to "Green")
                ui.chips("pfColour", x, y, width, filters, { it.second }, filters.indexOfFirst { it.first == adjustment.color }, perRow = 4, rowHeight = Palette.dp(36f)) { index ->
                    live("Photo filter") { adjustment.color = filters[index].first }
                }
            }
            is Adjustment.GradientMap -> {
                ui.button("gmSet", x, y, width, Palette.dp(46f), "Map foreground → background") {
                    live("Gradient map") {
                        adjustment.stops = arrayOf(intArrayOf(0, state.primary), intArrayOf(255, state.secondary))
                    }
                }
                y += Palette.dp(54f)
                ui.button("gmReverse", x, y, width, Palette.dp(44f), "Swap the two ends") {
                    live("Gradient map") {
                        val first = adjustment.stops.firstOrNull()
                        val last = adjustment.stops.lastOrNull()
                        if (first != null && last != null) {
                            adjustment.stops = arrayOf(intArrayOf(0, last[1]), intArrayOf(255, first[1]))
                        }
                    }
                }
                y += Palette.dp(54f)
            }
            is Adjustment.Posterize -> {
                ui.slider("poLevels", x, y, width, "Levels ${adjustment.levels}", adjustment.levels.toFloat(), 2f, 255f, showValue = false) {
                    live("Posterise") { adjustment.levels = it.roundToInt() }
                }
                y += Palette.dp(46f)
            }
            is Adjustment.Threshold -> {
                ui.slider("thLevel", x, y, width, "Threshold ${adjustment.level}", adjustment.level.toFloat(), 1f, 255f, showValue = false) {
                    live("Threshold") { adjustment.level = it.roundToInt() }
                }
                y += Palette.dp(46f)
            }
            is Adjustment.Desaturate -> {
                ui.slider("dsAmount", x, y, width, "Amount", adjustment.amount, 0f, 1f) { live("Desaturate") { adjustment.amount = it } }
                y += Palette.dp(46f)
            }
            is Adjustment.Curves -> {
                c2.labelFitted("Shape the tone curve with a preset, per channel.", x, y + Palette.dp(14f), Palette.textDim, Palette.sp(12f), width)
                y += Palette.dp(26f)
                ui.chips("curveChannel", x, y, width, listOf("RGB", "Red", "Green", "Blue"), { it }, state.curveChannel, perRow = 4, rowHeight = Palette.dp(36f)) { index ->
                    state.chooseCurveChannel(index)
                }
                y += Palette.dp(46f)
                val presets = listOf("Linear", "Contrast", "Brighten", "Darken", "Fade")
                ui.chips("curvePreset", x, y, width, presets, { it }, state.curvePreset, perRow = 3, rowHeight = Palette.dp(36f)) { index ->
                    state.applyCurvePreset(index)
                }
                y += Palette.dp(84f)
                c2.labelFitted("Points: ${pointsSummary(adjustment)}", x, y + Palette.dp(14f), Palette.textDim, Palette.sp(11.5f), width)
                y += Palette.dp(24f)
            }
            else -> {
                c2.label("This adjustment has no settings.", x, y + Palette.dp(16f), Palette.textDim, Palette.sp(12f))
                y += Palette.dp(30f)
            }
        }
        return y
    }

    private fun pointsSummary(curves: Adjustment.Curves): String =
        curves.points.joinToString(" ") { "${it[0].roundToInt()}→${it[1].roundToInt()}" }

    /* -------------------------------------------------------------- filters */

    private fun filters(c2: Canvas2D, ui: Ui, x: Float, y0: Float, width: Float) {
        var y = y0
        c2.labelFitted("Filters act on the active layer, inside the selection.", x, y + Palette.dp(14f), Palette.textDim, Palette.sp(11.5f), width)
        y += Palette.dp(26f)
        val available = state.filtersAvailable()
        available.forEachIndexed { index, entry ->
            val column = index % 2
            val row = index / 2
            val chipWidth = (width - Palette.dp(8f)) / 2f
            ui.button("filter$index", x + column * (chipWidth + Palette.dp(8f)), y + row * Palette.dp(48f), chipWidth, Palette.dp(40f), entry.first) {
                state.applyFilter(entry.first, entry.second)
            }
        }
    }

    /* -------------------------------------------------------------- history */

    private fun history(c2: Canvas2D, ui: Ui, x: Float, y0: Float, width: Float) {
        var y = y0
        val gap = Palette.dp(10f)
        val halfWidth = half(width)
        ui.button("undo", x, y, halfWidth, Palette.dp(54f), "Undo", icon = Icon.UNDO, enabled = state.canUndo) { state.undo() }
        ui.button("redo", x + halfWidth + gap, y, halfWidth, Palette.dp(54f), "Redo", icon = Icon.REDO, enabled = state.canRedo) { state.redo() }
        y += Palette.dp(64f)
        val history = state.document.history
        c2.labelFitted("${history.depth} steps · ${history.usedBytes / (1024 * 1024)} MB kept", x, y + Palette.dp(14f), Palette.textPrimary, Palette.sp(13f), width)
        y += Palette.dp(26f)
        c2.labelFitted("Undo: ${history.undoLabel ?: "nothing"}", x, y + Palette.dp(14f), Palette.textDim, Palette.sp(12.5f), width)
        y += Palette.dp(24f)
        c2.labelFitted("Redo: ${history.redoLabel ?: "nothing"}", x, y + Palette.dp(14f), Palette.textDim, Palette.sp(12.5f), width)
        y += Palette.dp(34f)
        ui.button("clearHistory", x, y, width, Palette.dp(44f), "Clear history") {
            state.document.history.clear()
            state.report("History cleared")
        }
    }

    /* ----------------------------------------------------------- canvas size */

    private fun canvasSize(c2: Canvas2D, ui: Ui, x: Float, y0: Float, width: Float) {
        var y = y0
        val gap = Palette.dp(10f)
        val halfWidth = half(width)
        ui.slider("canvasWidth", x, y, width, "Width", state.canvasDraftWidth.toFloat(), 1f, 20000f, valueLabel = { "${it.roundToInt()}" }) {
            state.canvasDraftWidth = it.roundToInt()
        }
        y += Palette.dp(46f)
        ui.slider("canvasHeight", x, y, width, "Height", state.canvasDraftHeight.toFloat(), 1f, 20000f, valueLabel = { "${it.roundToInt()}" }) {
            state.canvasDraftHeight = it.roundToInt()
        }
        y += Palette.dp(52f)
        ui.button("resizeCanvas", x, y, halfWidth, Palette.dp(46f), "Canvas size") {
            state.resizeCanvas(state.canvasDraftWidth, state.canvasDraftHeight, 0.5f, 0.5f)
        }
        ui.button("resizeImage", x + halfWidth + gap, y, halfWidth, Palette.dp(46f), "Image size") {
            state.resizeImage(state.canvasDraftWidth, state.canvasDraftHeight)
        }
        y += Palette.dp(56f)
        ui.button("cropSelection", x, y, halfWidth, Palette.dp(46f), "Crop to selection") { state.cropToSelection() }
        ui.button("trim", x + halfWidth + gap, y, halfWidth, Palette.dp(46f), "Trim transparent") { state.trimTransparent() }
        y += Palette.dp(58f)
        y = ui.section("Turn and flip", x, y, width)
        ui.button("rotateLeft", x, y, halfWidth, Palette.dp(46f), "Left", icon = Icon.RESET) { state.rotateCanvas(false) }
        ui.button("rotateRight", x + halfWidth + gap, y, halfWidth, Palette.dp(46f), "Right", icon = Icon.REDO) { state.rotateCanvas(true) }
        y += Palette.dp(52f)
        ui.button("flipH", x, y, halfWidth, Palette.dp(46f), "Flip across") { state.flipCanvas(true) }
        ui.button("flipV", x + halfWidth + gap, y, halfWidth, Palette.dp(46f), "Flip down") { state.flipCanvas(false) }
        y += Palette.dp(58f)
        ui.toggle("showGrid", x, y, width, Palette.dp(44f), "Show grid", state.options.showGrid) { state.options.showGrid = it }
        y += Palette.dp(50f)
        ui.toggle("showEdges", x, y, width, Palette.dp(44f), "Show selection edges", state.options.showSelectionEdges) {
            state.options.showSelectionEdges = it
        }
    }

    /* ---------------------------------------------------------------- files */

    private fun files(c2: Canvas2D, ui: Ui, x: Float, y0: Float, width: Float) {
        var y = y0
        val gap = Palette.dp(10f)
        val halfWidth = half(width)
        ui.button("newDoc", x, y, halfWidth, Palette.dp(50f), "New", icon = Icon.PLUS) { state.request(EditorState.FileAction.NEW_DOCUMENT) }
        ui.button("openDoc", x + halfWidth + gap, y, halfWidth, Palette.dp(50f), "Open…", icon = Icon.FOLDER) { state.request(EditorState.FileAction.OPEN) }
        y += Palette.dp(58f)
        ui.button("saveDoc", x, y, halfWidth, Palette.dp(50f), "Save PSD", icon = Icon.SAVE, selected = state.document.dirty) { state.request(EditorState.FileAction.SAVE) }
        ui.button("saveAs", x + halfWidth + gap, y, halfWidth, Palette.dp(50f), "Save as…", icon = Icon.EXPORT) { state.request(EditorState.FileAction.SAVE_AS) }
        y += Palette.dp(58f)
        y = ui.section("Export a flat image", x, y, width)
        val formats = listOf(ImageFormat.PNG, ImageFormat.JPEG, ImageFormat.WEBP)
        formats.forEachIndexed { index, format ->
            val chipWidth = (width - gap * 2) / 3f
            ui.button("export$index", x + index * (chipWidth + gap), y, chipWidth, Palette.dp(46f), format.extension.uppercase()) {
                state.requestExport(format)
            }
        }
        y += Palette.dp(56f)
        ui.button("placeImage", x, y, width, Palette.dp(46f), "Place an image as a layer…", icon = Icon.OPEN) { state.request(EditorState.FileAction.PLACE_IMAGE) }
        y += Palette.dp(56f)
        c2.labelFitted(state.fileName + if (state.document.dirty) " •" else "", x, y + Palette.dp(14f), Palette.textPrimary, Palette.sp(12.5f), width)
        y += Palette.dp(24f)
        c2.labelFitted(
            "${state.document.width} × ${state.document.height} · ${state.countLayers()} layers" +
                if (state.fileIsPsd) " · PSD" else "",
            x,
            y + Palette.dp(14f),
            Palette.textDim,
            Palette.sp(12f),
            width,
        )
    }

    /* ----------------------------------------------------------------- text */

    private fun text(c2: Canvas2D, ui: Ui, x: Float, y0: Float, width: Float) {
        var y = y0
        val gap = Palette.dp(10f)
        val halfWidth = half(width)
        val draft = state.textDraft
        if (draft == null) {
            c2.labelFitted("Tap the canvas with the text tool to place a line of text.", x, y + Palette.dp(20f), Palette.textDim, Palette.sp(13f), width)
            return
        }
        c2.labelFitted("“${draft.text.take(28)}”", x, y + Palette.dp(16f), Palette.textPrimary, Palette.sp(13f), width)
        y += Palette.dp(28f)
        ui.button("typeHere", x, y, width, Palette.dp(48f), "Type with the keyboard", icon = Icon.TEXT) {
            state.focusTextInput()
        }
        y += Palette.dp(58f)
        ui.button("bold", x, y, halfWidth, Palette.dp(44f), "Bold", selected = draft.bold) {
            draft.bold = !draft.bold
            state.previewTextDraft()
        }
        ui.button("italic", x + halfWidth + gap, y, halfWidth, Palette.dp(44f), "Italic", selected = draft.italic) {
            draft.italic = !draft.italic
            state.previewTextDraft()
        }
        y += Palette.dp(52f)
        ui.chips("align", x, y, width, listOf("Left", "Centre", "Right"), { it }, state.options.textAlign, perRow = 3) { index ->
            state.setTextAlign(index)
            draft.alignment = com.strata.editor.core.TextAlign.values()[index]
            state.previewTextDraft()
        }
        y += Palette.dp(50f)
        ui.slider("ptSize", x, y, width, "Size ${draft.fontSize.roundToInt()} px", draft.fontSize, 6f, 600f, showValue = false) {
            draft.fontSize = it
            state.options.textSize = it
            state.previewTextDraft()
        }
        y += Palette.dp(46f)
        ui.slider("ptLeading", x, y, width, "Line spacing", draft.leading, 0.6f, 3f) {
            draft.leading = it
            state.previewTextDraft()
        }
        y += Palette.dp(46f)
        ui.slider("ptTracking", x, y, width, "Letter spacing", draft.tracking, -5f, 20f) {
            draft.tracking = it
            state.previewTextDraft()
        }
        y += Palette.dp(46f)
        ui.button("textColour", x, y, width, Palette.dp(44f), "Text colour #${Colors.toHex(draft.color)}", tint = Colors.withAlpha(draft.color, 255)) {
            draft.color = state.primary
            state.previewTextDraft()
        }
        y += Palette.dp(54f)
        val families = state.fontFamilies()
        if (families.isNotEmpty()) {
            val selected = families.indexOf(draft.fontFamily)
            ui.chips("font", x, y, width, families.take(9), { it }, selected, perRow = 3, rowHeight = Palette.dp(34f)) { chosen ->
                draft.fontFamily = families[chosen]
                state.options.textFont = draft.fontFamily
                state.previewTextDraft()
            }
            y += Palette.dp(44f)
        }
        ui.button("textDone", x, y, halfWidth, Palette.dp(50f), "Done", selected = true) { state.closeTextEditor(commit = true) }
        ui.button("textCancel", x + halfWidth + gap, y, halfWidth, Palette.dp(50f), "Cancel") { state.closeTextEditor(commit = false) }
    }

    /* ---------------------------------------------------------- layer style */

    private fun layerStyle(c2: Canvas2D, ui: Ui, x: Float, y0: Float, width: Float) {
        var y = y0
        val layer = state.activeLayer ?: return
        val gap = Palette.dp(10f)
        val halfWidth = half(width)
        y = ui.section(layer.name, x, y, width)
        ui.button("rasterise", x, y, halfWidth, Palette.dp(46f), "Rasterise") { state.rasteriseActive() }
        ui.button("mask", x + halfWidth + gap, y, halfWidth, Palette.dp(46f), if (layer.mask == null) "Add mask" else "Apply mask") {
            if (layer.mask == null) state.addMaskFromSelection(layer) else state.applyMask(layer)
        }
        y += Palette.dp(54f)
        ui.button("dropMask", x, y, halfWidth, Palette.dp(46f), "Delete mask", enabled = layer.mask != null) { state.removeMask(layer) }
        ui.button("invertMask", x + halfWidth + gap, y, halfWidth, Palette.dp(46f), "Invert mask", enabled = layer.mask != null) { state.invertMask(layer) }
        y += Palette.dp(58f)
        y = ui.section("Scale and rotate the pixels", x, y, width)
        val draft = state.transformDraft
        ui.slider("tfScale", x, y, width, "Scale ${(draft.scale * 100).roundToInt()}%", draft.scale, 0.05f, 4f, showValue = false) { draft.scale = it }
        y += Palette.dp(46f)
        ui.slider("tfAngle", x, y, width, "Rotate ${draft.angle.roundToInt()}°", draft.angle, -180f, 180f, showValue = false) { draft.angle = it }
        y += Palette.dp(50f)
        ui.button("tfApply", x, y, halfWidth, Palette.dp(46f), "Apply", selected = true) { state.applyLayerTransform() }
        ui.button("tfReset", x + halfWidth + gap, y, halfWidth, Palette.dp(46f), "Reset") { state.transformDraft.reset() }
    }
}

/** Layer commands offered when a row is tapped twice. */
object LayerMenu {
    val entries: List<String> = listOf("Duplicate", "Delete", "Merge down", "Group", "Bring to front", "Send to back", "Rasterise")
}
