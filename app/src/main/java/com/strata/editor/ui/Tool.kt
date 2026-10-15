package com.strata.editor.ui

import com.strata.editor.core.BlendMode
import com.strata.editor.core.Brush
import com.strata.editor.core.Painter

/** The tools on the rail. Order is the order they appear in. */
enum class Tool(val label: String, val icon: Icon) {
    BRUSH("Brush", Icon.BRUSH),
    ERASER("Eraser", Icon.ERASER),
    FILL("Fill", Icon.FILL),
    GRADIENT("Gradient", Icon.GRADIENT),
    TEXT("Text", Icon.TEXT),
    SHAPE("Shape", Icon.SHAPE),
    SELECT("Marquee", Icon.SELECT),
    LASSO("Lasso", Icon.LASSO),
    WAND("Wand", Icon.WAND),
    CLONE("Clone", Icon.CLONE),
    RETOUCH("Retouch", Icon.RETOUCH),
    SMOOTH("Blur", Icon.SMOOTH),
    MOVE("Move", Icon.MOVE),
    TRANSFORM("Transform", Icon.TRANSFORM),
    CROP("Crop", Icon.CROP),
    PICKER("Pick colour", Icon.PICKER),
    HAND("Pan", Icon.HAND),
    ZOOM("Zoom", Icon.ZOOM),
    ;

    /** Tools whose options are worth a panel of their own. */
    val hasOptions: Boolean
        get() = this == BRUSH || this == ERASER || this == FILL || this == GRADIENT ||
            this == TEXT || this == SHAPE || this == CLONE || this == RETOUCH || this == SMOOTH

    /** Tools that should keep a second finger free for pan and zoom. */
    val usesViewGestures: Boolean
        get() = this == HAND || this == ZOOM || this == PICKER
}

enum class ShapeKind(val label: String) { RECTANGLE("Rectangle"), ELLIPSE("Ellipse"), LINE("Line"), POLYGON("Polygon") }

enum class RetouchMode(val kind: Painter.RetouchKind, val label: String) {
    DODGE(Painter.RetouchKind.DODGE, "Dodge"),
    BURN(Painter.RetouchKind.BURN, "Burn"),
    SPONGE(Painter.RetouchKind.SPONGE_SATURATE, "Sponge"),
    SATURATE(Painter.RetouchKind.SPONGE_DESATURATE, "Saturate"),
}

enum class SmoothMode(val kind: Painter.NeighbourhoodKind, val label: String) {
    BLUR(Painter.NeighbourhoodKind.BLUR, "Blur"),
    SHARPEN(Painter.NeighbourhoodKind.SHARPEN, "Sharpen"),
    SMEAR(Painter.NeighbourhoodKind.SMUDGE, "Smear"),
}

/**
 * What the tools are set to. Kept separate from the view so a tool keeps its
 * settings while the user switches away and back, which is what every editor does
 * and what fingers expect.
 */
class ToolOptions {

    var brush: Brush = Brush.fromPreset(Brush.presets[2])
    var eraser: Brush = Brush.fromPreset(Brush.presets[1])
    var eraserOpacity: Float = 1f

    var fillTolerance: Int = 32
    var fillContiguous: Boolean = true
    var fillAntialias: Boolean = true

    var gradientIndex: Int = 0
    var gradientReverse: Boolean = false
    var gradientStyle: Int = 0 // 0 linear, 1 radial, 2 reflected, 3 diamond

    var textSize: Float = 72f
    var textBold: Boolean = false
    var textItalic: Boolean = false
    var textAlign: Int = 0 // 0 left, 1 centre, 2 right
    var textFont: String = "sans-serif"
    var textLeading: Float = 1.2f
    var textTracking: Float = 0f

    var shape: ShapeKind = ShapeKind.RECTANGLE
    var shapeFilled: Boolean = true
    var shapeStroke: Float = 6f

    var selectionFeather: Float = 0f
    var selectionAntialias: Boolean = true
    var wandTolerance: Int = 32
    var wandContiguous: Boolean = true
    var selectionOp: Int = 0 // 0 replace, 1 add, 2 subtract, 3 intersect

    var cloneAligned: Boolean = true
    var cloneSourceX: Float = -1f
    var cloneSourceY: Float = -1f

    var retouch: RetouchMode = RetouchMode.DODGE
    var retouchStrength: Float = 0.35f

    var smooth: SmoothMode = SmoothMode.BLUR
    var smoothStrength: Float = 0.4f

    var layerOpacity: Float = 1f
    var blend: BlendMode = BlendMode.NORMAL
    var lockAlpha: Boolean = false
    var showGrid: Boolean = false
    var showSelectionEdges: Boolean = true

    /** The brush the active tool actually paints with. */
    fun activeBrush(tool: Tool): Brush = if (tool == Tool.ERASER) eraser else brush

    fun copyOf(): ToolOptions = ToolOptions().also { copy ->
        copy.brush = brush.copyOf()
        copy.eraser = eraser.copyOf()
        copy.eraserOpacity = eraserOpacity
        copy.fillTolerance = fillTolerance
        copy.fillContiguous = fillContiguous
        copy.fillAntialias = fillAntialias
        copy.gradientIndex = gradientIndex
        copy.gradientReverse = gradientReverse
        copy.gradientStyle = gradientStyle
        copy.textSize = textSize
        copy.textBold = textBold
        copy.textItalic = textItalic
        copy.textAlign = textAlign
        copy.textFont = textFont
        copy.textLeading = textLeading
        copy.textTracking = textTracking
        copy.shape = shape
        copy.shapeFilled = shapeFilled
        copy.shapeStroke = shapeStroke
        copy.selectionFeather = selectionFeather
        copy.selectionAntialias = selectionAntialias
        copy.wandTolerance = wandTolerance
        copy.wandContiguous = wandContiguous
        copy.selectionOp = selectionOp
        copy.cloneAligned = cloneAligned
        copy.cloneSourceX = cloneSourceX
        copy.cloneSourceY = cloneSourceY
        copy.retouch = retouch
        copy.retouchStrength = retouchStrength
        copy.smooth = smooth
        copy.smoothStrength = smoothStrength
        copy.layerOpacity = layerOpacity
        copy.blend = blend
        copy.lockAlpha = lockAlpha
        copy.showGrid = showGrid
        copy.showSelectionEdges = showSelectionEdges
    }
}
