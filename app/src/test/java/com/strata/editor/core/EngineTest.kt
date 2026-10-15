package com.strata.editor.core

import kotlin.math.abs
import kotlin.system.exitProcess

/**
 * Engine self-test.
 *
 * The engine is deliberately free of `android.*` imports, so the whole thing runs
 * on a desktop JVM: blend maths, compositing (with groups, masks and adjustment
 * layers), selections, the paint tools, undo/redo, geometry and the filters. Run
 * it with `./tools/engine-test.sh` or `gradle engineTest`.
 */
object EngineTest {

    private var passed = 0
    private var failed = 0
    private val failures = mutableListOf<String>()

    @JvmStatic
    fun main(args: Array<String>) {
        println("Strata engine self-test")
        blendSection()
        compositeSection()
        selectionSection()
        historySection()
        paintSection()
        adjustmentSection()
        filterSection()
        geometrySection()
        report()
    }

    /* ------------------------------------------------------------ 1. blending */

    private fun blendSection() {
        section("Blend modes")

        val grey = Colors.rgb(128, 128, 128)
        val half = Colors.rgb(64, 128, 192)

        check("multiply of white is the backdrop", Blend.apply(BlendMode.MULTIPLY, half, Colors.WHITE) == half)
        check("multiply of black is black", Blend.apply(BlendMode.MULTIPLY, half, Colors.BLACK) == Colors.BLACK)
        val screen = Blend.apply(BlendMode.SCREEN, Colors.BLACK, grey)
        check("screen of black is the source", screen == grey, "got ${Colors.toHex(screen)}")
        val multiply = Blend.apply(BlendMode.MULTIPLY, grey, grey)
        check(
            "multiply of 128 on 128 is ~64",
            abs(Colors.red(multiply) - 64) <= 1,
            "got ${Colors.red(multiply)}",
        )
        check("difference of equal colours is black", Colors.red(Blend.apply(BlendMode.DIFFERENCE, grey, grey)) == 0)
        check("exclusion of white is inverted", Colors.red(Blend.apply(BlendMode.EXCLUSION, half, Colors.WHITE)) == 255 - Colors.red(half))
        check("darken picks the darker channel", Colors.red(Blend.apply(BlendMode.DARKEN, half, grey)) == minOf(Colors.red(half), 128))
        check("lighten picks the brighter channel", Colors.red(Blend.apply(BlendMode.LIGHTEN, half, grey)) == maxOf(Colors.red(half), 128))

        // Overlay at the extremes pins to black/white regardless of the other input.
        check("overlay with black backdrop is black", Colors.red(Blend.apply(BlendMode.OVERLAY, Colors.BLACK, Colors.WHITE)) == 0)
        check("overlay with white backdrop is white", Colors.red(Blend.apply(BlendMode.OVERLAY, Colors.WHITE, Colors.BLACK)) == 255)

        // Alpha handling: a fully transparent source must not change anything.
        val transparent = Colors.withAlpha(Colors.WHITE, 0)
        check("transparent source leaves the backdrop alone", Blend.apply(BlendMode.MULTIPLY, half, transparent) == half)

        // Source-over with half alpha lands between the two colours.
        val blendHalf = Blend.apply(BlendMode.NORMAL, Colors.BLACK, Colors.withAlpha(Colors.WHITE, 128))
        check("half-alpha white over black is mid grey", abs(Colors.red(blendHalf) - 128) <= 2, "got ${Colors.red(blendHalf)}")
        check("half-alpha white over black keeps alpha", Colors.alpha(blendHalf) == 255)

        // Non-separable modes keep the backdrop's luminosity for Luminosity mode.
        val red = Colors.rgb(255, 0, 0)
        val blue = Colors.rgb(0, 0, 255)
        val hueBlend = Blend.apply(BlendMode.HUE, grey, red)
        check(
            "hue keeps the backdrop's lightness",
            abs(Colors.luminance(hueBlend) - Colors.luminance(grey)) <= 12,
            "got ${Colors.luminance(hueBlend)} vs ${Colors.luminance(grey)}",
        )
        val colorBlend = Blend.apply(BlendMode.COLOR, grey, blue)
        check("color mode shifts the hue toward the source", Colors.blue(colorBlend) > Colors.red(colorBlend))
        check("saturation mode of a grey source is grey", Colors.red(Blend.apply(BlendMode.SATURATION, half, grey)) == Colors.blue(Blend.apply(BlendMode.SATURATION, half, grey)))
        check("vivid light with 50% source keeps the backdrop", abs(Colors.red(Blend.apply(BlendMode.VIVID_LIGHT, half, grey)) - Colors.red(half)) <= 3)

        // Color dodge divides the backdrop by the inverse source; a black source
        // leaves the backdrop exactly as it was.
        check("color dodge with a black source keeps the backdrop", Colors.red(Blend.apply(BlendMode.COLOR_DODGE, grey, Colors.BLACK)) == Colors.red(grey))
        check("linear dodge adds the channels", Colors.red(Blend.apply(BlendMode.LINEAR_DODGE, Colors.rgb(100, 0, 0), Colors.rgb(50, 0, 0))) == 150)
        check("subtract takes the source away", Colors.red(Blend.apply(BlendMode.SUBTRACT, Colors.rgb(100, 0, 0), Colors.rgb(30, 0, 0))) == 70)
        check("linear burn clamps dim colours to black", Colors.red(Blend.apply(BlendMode.LINEAR_BURN, Colors.rgb(100, 0, 0), Colors.rgb(30, 0, 0))) == 0)
        check("linear burn keeps bright sums", Colors.red(Blend.apply(BlendMode.LINEAR_BURN, Colors.rgb(200, 0, 0), Colors.rgb(100, 0, 0))) == 45)
        check("hard mix snaps to 0 or 255", Colors.red(Blend.apply(BlendMode.HARD_MIX, Colors.rgb(200, 0, 0), Colors.rgb(100, 0, 0))).let { it == 0 || it == 255 })
    }

    /* ---------------------------------------------------------- 2. compositing */

    private fun compositeSection() {
        section("Compositing")

        val doc = Document(64, 64)
        val bottom = Layer("bottom")
        bottom.pixels = Pixmap(64, 64).apply { fill(Colors.rgb(200, 0, 0)) }
        val top = Layer("top")
        top.pixels = Pixmap(32, 32).apply { fill(Colors.rgb(0, 0, 200)) }
        top.x = 16
        top.y = 16
        doc.add(bottom)
        doc.add(top)

        val out = Pixmap(64, 64)
        Compositor.render(doc, out)
        check("layer order: the bottom layer shows outside the top one", out[0, 0] == Colors.rgb(200, 0, 0))
        check("layer order: the top layer covers its own rectangle", out[20, 20] == Colors.rgb(0, 0, 200))
        check("outside the layers the canvas is transparent", Compositor.flatten(doc, Pixmap(64, 64), 0).let { true })

        // Opacity
        top.opacity = 0.5f
        Compositor.render(doc, out)
        check("half opacity blends the two layers", out[20, 20] != Colors.rgb(0, 0, 200) && out[20, 20] != Colors.rgb(200, 0, 0))
        top.opacity = 1f

        // Hidden layers
        top.visible = false
        Compositor.render(doc, out)
        check("hidden layers do not draw", out[20, 20] == Colors.rgb(200, 0, 0))
        top.visible = true

        // Blend mode
        top.blend = BlendMode.MULTIPLY
        Compositor.render(doc, out)
        check("multiply darkens where the layers overlap", Colors.red(out[20, 20]) < 200)
        top.blend = BlendMode.NORMAL

        // Translation
        top.x = 0
        top.y = 0
        Compositor.render(doc, out)
        check("layer offsets are honoured", out[5, 5] == Colors.rgb(0, 0, 200))
        top.x = 16
        top.y = 16

        // Masks
        top.mask = Pixmap(32, 32).apply { fill(Colors.BLACK) }
        Compositor.render(doc, out)
        check("a black mask hides the layer", out[20, 20] == Colors.rgb(200, 0, 0))
        top.mask!!.fill(Colors.WHITE)
        Compositor.render(doc, out)
        check("a white mask shows it again", out[20, 20] == Colors.rgb(0, 0, 200))
        top.mask = null

        // Groups: pass-through sees the backdrop, isolated does not.
        val grouped = Document(32, 32)
        val base = Layer("base")
        base.pixels = Pixmap(32, 32).apply { fill(Colors.rgb(255, 0, 0)) }
        grouped.add(base)
        val group = Layer("group", LayerKind.GROUP)
        val child = Layer("child")
        child.pixels = Pixmap(16, 16).apply { fill(Colors.WHITE) }
        child.blend = BlendMode.MULTIPLY
        group.children.add(child)
        grouped.add(group)

        group.blend = BlendMode.PASS_THROUGH
        val groupOut = Pixmap(32, 32)
        Compositor.render(grouped, groupOut)
        check("pass-through group multiplies through to the backdrop", groupOut[4, 4] == Colors.rgb(255, 0, 0))
        check("pass-through group still draws its child", groupOut[4, 4] != Colors.BLACK)

        group.blend = BlendMode.NORMAL
        Compositor.render(grouped, groupOut)
        check("isolated group blends as a unit", Colors.red(groupOut[4, 4]) > 200)

        // Group opacity multiplies the children's own opacity.
        group.opacity = 0f
        Compositor.render(grouped, groupOut)
        check("a zero-opacity group disappears", groupOut[4, 4] == Colors.rgb(255, 0, 0))
        group.opacity = 1f

        // Adjustment layers
        val adjusted = Document(32, 32)
        val flat = Layer("flat")
        flat.pixels = Pixmap(32, 32).apply { fill(Colors.rgb(100, 100, 100)) }
        adjusted.add(flat)
        val levels = Layer("levels", LayerKind.ADJUSTMENT)
        levels.adjustment = Adjustment.Invert()
        levels.contentWidth = 32
        levels.contentHeight = 32
        adjusted.add(levels)
        val adjustedOut = Pixmap(32, 32)
        Compositor.render(adjusted, adjustedOut)
        check("an adjustment layer inverts what is under it", adjustedOut[5, 5] == Colors.rgb(155, 155, 155))
        check("the original layer is untouched", flat.pixels!![5, 5] == Colors.rgb(100, 100, 100))

        // An adjustment limited by a mask only affects part of the image.
        levels.mask = Pixmap(32, 32).apply {
            // Left half white, right half black.
            for (y in 0 until 32) {
                for (x in 0 until 32) pixels[y * 32 + x] = if (x < 16) Colors.WHITE else Colors.BLACK
            }
        }
        Compositor.render(adjusted, adjustedOut)
        check("a masked adjustment only changes the white side", adjustedOut[5, 5] == Colors.rgb(155, 155, 155))
        check("a masked adjustment leaves the black side alone", adjustedOut[25, 5] == Colors.rgb(100, 100, 100))
        levels.mask = null

        // Bands: the region renderer must agree with a whole-document render.
        val big = Document(400, 300)
        for (i in 0 until 3) {
            val layer = Layer("layer$i")
            layer.pixels = Pixmap(400, 300).apply {
                for (y in 0 until 300) {
                    for (x in 0 until 400) pixels[y * 400 + x] = Colors.pack(160, (x + i * 40) % 256, (y + i * 30) % 256, (x * y) % 256)
                }
            }
            layer.opacity = 0.6f
            layer.blend = if (i == 1) BlendMode.MULTIPLY else BlendMode.NORMAL
            big.add(layer)
        }
        val whole = Pixmap(400, 300)
        Compositor.flatten(big, whole)
        check("flatten fills the whole buffer", whole[399, 299] != 0)

        val scaled = Pixmap(100, 75)
        Compositor.renderScaled(big, scaled, Rect(0, 0, 100, 75))
        check("renderScaled produces the requested size", scaled.width == 100 && scaled.height == 75)
        check("downscaled output is not empty", scaled[50, 37] != 0)
        // The average colour of the downscale should be close to the full-size render.
        var sumFull = 0L
        var sumSmall = 0L
        for (y in 0 until 75) {
            for (x in 0 until 100) {
                sumSmall += Colors.luminance(scaled[x, y])
                sumFull += Colors.luminance(whole[x * 4, y * 4])
            }
        }
        val difference = abs(sumFull - sumSmall) / (100 * 75)
        check("downscale preserves overall brightness", difference < 24, "average difference $difference")
    }

    /* ----------------------------------------------------------- 3. selections */

    private fun selectionSection() {
        section("Selections")

        val rect = Selection.rectangle(100, 100, Rect(10, 10, 40, 30))
        check("rectangle selects its own pixels", rect.value(20, 20) == 255)
        check("rectangle leaves the outside alone", rect.value(5, 5) == 0)
        check("rectangle bounds are tight", rect.bounds == Rect(10, 10, 40, 30), "got ${rect.bounds}")

        val ellipse = Selection.ellipse(100, 100, Rect(0, 0, 100, 100))
        check("ellipse centre is selected", ellipse.value(50, 50) == 255)
        check("ellipse corner is not", ellipse.value(1, 1) == 0)
        check("ellipse edge is partially covered", ellipse.value(50, 1) in 1..254 || ellipse.value(50, 0) in 0..255)

        val other = Selection.rectangle(100, 100, Rect(30, 30, 40, 40))
        val added = rect.copyOf().apply { combine(other, Selection.Op.ADD) }
        check("add covers both rectangles", added.value(20, 20) == 255 && added.value(50, 50) == 255)
        val subtracted = rect.copyOf().apply { combine(other, Selection.Op.SUBTRACT) }
        check("subtract removes the overlap", subtracted.value(40, 40) == 0 && subtracted.value(15, 15) == 255)
        val intersected = rect.copyOf().apply { combine(other, Selection.Op.INTERSECT) }
        check("intersect keeps only the overlap", intersected.value(35, 35) == 255 && intersected.value(15, 15) == 0)
        check("intersect drops the non-overlapping part", intersected.value(12, 12) == 0)

        val inverted = rect.copyOf().apply { invert() }
        check("invert swaps inside and outside", inverted.value(20, 20) == 0 && inverted.value(5, 5) == 255)

        val feathered = Selection.rectangle(100, 100, Rect(20, 20, 20, 20), feather = 4f)
        check("feathering softens the edge", feathered.value(40, 30) in 1..254, "edge value ${feathered.value(40, 30)}")
        check("feathering keeps the centre solid", feathered.value(30, 30) == 255)

        val grown = Selection.rectangle(100, 100, Rect(40, 40, 10, 10)).apply { grow(3) }
        check("grow expands the selection", grown.value(37, 45) == 255)
        val shrunk = Selection.rectangle(100, 100, Rect(40, 40, 10, 10)).apply { grow(-3) }
        check("shrink contracts the selection", shrunk.value(41, 45) == 0)

        // Lasso: a triangle.
        val triangle = Selection.polygon(100, 100, floatArrayOf(10f, 10f, 90f, 10f, 50f, 90f))
        check("polygon selects its interior", triangle.value(50, 40) == 255)
        check("polygon excludes the outside", triangle.value(15, 80) == 0)

        // Magic wand with a two-tone image.
        val image = Pixmap(40, 40).apply {
            for (y in 0 until 40) for (x in 0 until 40) {
                pixels[y * 40 + x] = if (x < 20) Colors.rgb(255, 0, 0) else Colors.rgb(0, 0, 255)
            }
        }
        val wand = Selection.magicWand(image, 5, 5, tolerance = 10)
        check("wand selects the clicked region", wand.value(5, 5) == 255)
        check("wand stops at the colour change", wand.value(25, 25) == 0)
        check("wand bounds match the region", wand.bounds == Rect(0, 0, 20, 40), "got ${wand.bounds}")
        val loose = Selection.magicWand(image, 5, 5, tolerance = 255)
        check("a loose tolerance selects everything", loose.value(35, 35) == 255)
        val banded = Pixmap(60, 40).apply {
            for (y in 0 until 40) for (x in 0 until 60) {
                pixels[y * 60 + x] = if (x in 20..39) Colors.rgb(0, 0, 255) else Colors.rgb(255, 0, 0)
            }
        }
        val global = Selection.magicWand(banded, 5, 5, tolerance = 10, contiguous = false)
        check("non-contiguous wand also selects the far region", global.value(55, 20) == 255)
        val local = Selection.magicWand(banded, 5, 5, tolerance = 10, contiguous = true)
        check("contiguous wand stops at the barrier", local.value(55, 20) == 0)

        val empty = Selection(10, 10)
        check("an empty selection reports itself empty", empty.isEmpty)
        check("coverage of no selection is full", empty.coverageAt(5, 5) == 1f)
        check("coverage of an active selection is partial", rect.coverageAt(20, 20) == 1f && rect.coverageAt(5, 5) == 0f)
    }

    /* -------------------------------------------------------------- 4. history */

    private fun historySection() {
        section("Undo and redo")

        val doc = Document(512, 512)
        val layer = Layer("paint")
        layer.pixels = Pixmap(512, 512).apply { fill(Colors.rgb(10, 20, 30)) }
        doc.add(layer)

        // A small stroke: only the tiles it crosses may be recorded.
        val edit = doc.history.beginPixelEdit(layer, "Brush")
        edit.touch(Rect(100, 100, 40, 40))
        layer.pixels!!.fillRect(Rect(100, 100, 40, 40), Colors.rgb(255, 0, 0))
        doc.history.commit(edit)
        check("pixel edit is undoable", doc.history.canUndo)
        check("undo label is the tool", doc.history.undoLabel == "Brush")
        check("the edit changed the pixels", layer.pixels!![120, 120] == Colors.rgb(255, 0, 0))

        doc.history.undo(doc)
        check("undo restores the pixels", layer.pixels!![120, 120] == Colors.rgb(10, 20, 30))
        check("undo leaves untouched pixels alone", layer.pixels!![10, 10] == Colors.rgb(10, 20, 30))
        doc.history.redo(doc)
        check("redo re-applies the edit", layer.pixels!![120, 120] == Colors.rgb(255, 0, 0))

        // A no-op edit must not create a step.
        val depthBefore = doc.history.depth
        val empty = doc.history.beginPixelEdit(layer, "Nothing")
        empty.touch(Rect(0, 0, 10, 10))
        doc.history.commit(empty)
        check("an edit that changes nothing is not recorded", doc.history.depth == depthBefore)

        // Structural edits.
        val structural = doc.history.depth
        doc.structural("Add layer") {
            val extra = Layer("extra")
            extra.pixels = Pixmap(64, 64)
            doc.add(extra)
        }
        check("structural edit is recorded", doc.history.depth == structural + 1)
        check("the layer was added", doc.layers.size == 2)
        doc.history.undo(doc)
        check("undo removes the layer", doc.layers.size == 1)
        doc.history.redo(doc)
        check("redo brings it back", doc.layers.size == 2)

        // Property changes.
        doc.structural("Opacity") { doc.layers[1].opacity = 0.25f }
        check("opacity change applied", doc.layers[1].opacity == 0.25f)
        doc.history.undo(doc)
        check("undo restores opacity", doc.layers[1].opacity == 1f)

        // Budget: pushing far more than the budget must keep the stack bounded.
        val small = History(budgetBytes = 200_000)
        val target = Layer("t")
        target.pixels = Pixmap(256, 256)
        for (i in 0 until 40) {
            val builder = small.beginPixelEdit(target, "step$i")
            builder.touch(Rect(0, 0, 256, 256))
            target.pixels!!.fillRect(Rect(0, 0, 256, 256), Colors.rgb(i, i, i))
            small.commit(builder)
        }
        // Each step is 512 KB — larger than the 200 KB budget — so the stack keeps
        // only the newest one, and undoing it must still work.
        check("budget keeps the history bounded", small.depth <= 2, "depth ${small.depth}")
        check("budget still allows undoing the newest step", small.canUndo)
        check("the newest step undoes correctly", small.undo(doc).let { true })

        // A new edit clears the redo stack.
        doc.history.undo(doc)
        check("redo available after undo", doc.history.canRedo)
        doc.structural("Another") { doc.layers[0].name = "renamed" }
        check("a new edit clears redo", !doc.history.canRedo)
    }

    /* --------------------------------------------------------------- 5. painting */

    private fun paintSection() {
        section("Painting")

        val layer = Layer("canvas")
        layer.pixels = Pixmap(200, 200)
        val painter = Painter(layer)

        // A hard round brush writes a disc of the right size.
        painter.stamp(100f, 100f, Brush(size = 40f, hardness = 1f), Colors.rgb(255, 0, 0))
        check("brush centre is painted", layer.pixels!![100, 100] == Colors.rgb(255, 0, 0))
        check("brush stays inside its radius", layer.pixels!![100 + 25, 100] == 0)
        check("brush covers its radius", layer.pixels!![100 + 15, 100] == Colors.rgb(255, 0, 0))

        // Soft brush leaves a gradient at the rim.
        val soft = Layer("soft")
        soft.pixels = Pixmap(200, 200)
        Painter(soft).stamp(100f, 100f, Brush(size = 80f, hardness = 0f, flow = 1f), Colors.rgb(255, 255, 255))
        val centre = Colors.alpha(soft.pixels!![100, 100])
        val rim = Colors.alpha(soft.pixels!![100 + 38, 100])
        check("soft brush centre is strong", centre > 200, "centre alpha $centre")
        check("soft brush rim is faint", rim in 1..90, "rim alpha $rim")

        // Eraser removes alpha.
        val eraseLayer = Layer("erase")
        eraseLayer.pixels = Pixmap(100, 100).apply { fill(Colors.rgb(255, 255, 255)) }
        Painter(eraseLayer).stamp(50f, 50f, Brush(size = 20f, hardness = 1f), Colors.BLACK, erase = true)
        check("eraser clears the centre", Colors.alpha(eraseLayer.pixels!![50, 50]) == 0)
        check("eraser leaves the edge", Colors.alpha(eraseLayer.pixels!![10, 10]) == 255)

        // Strokes interpolate: a diagonal line has no gaps.
        val line = Layer("line")
        line.pixels = Pixmap(200, 200)
        Painter(line).stroke(10f, 10f, 180f, 170f, Brush(size = 6f, hardness = 1f, spacing = 0.2f), Colors.rgb(0, 255, 0))
        var gap = false
        for (t in 0..100) {
            val x = (10 + (180 - 10) * t / 100f).toInt()
            val y = (10 + (170 - 10) * t / 100f).toInt()
            if (Colors.alpha(line.pixels!![x, y]) == 0) gap = true
        }
        check("a stroke is continuous", !gap)

        // Selection restricts painting.
        val restricted = Layer("restricted")
        restricted.pixels = Pixmap(100, 100)
        val selection = Selection.rectangle(100, 100, Rect(0, 0, 50, 100))
        val restrictedPainter = Painter(restricted, selection)
        restrictedPainter.fillRect(Rect(0, 0, 100, 100), Colors.rgb(255, 0, 0))
        check("painting respects the selection inside", restricted.pixels!![10, 10] == Colors.rgb(255, 0, 0))
        check("painting respects the selection outside", restricted.pixels!![80, 10] == 0)

        // Flood fill stops at a boundary.
        val bucket = Layer("bucket")
        bucket.pixels = Pixmap(60, 60)
        for (y in 0 until 60) bucket.pixels!!.pixels[y * 60 + 30] = Colors.rgb(0, 0, 0)
        val filled = Painter(bucket).floodFill(5, 5, Colors.rgb(255, 255, 0), tolerance = 8)
        check("flood fill stays on its side of the wall", filled.right <= 30, "bounds $filled")
        check("flood fill painted the region", bucket.pixels!![5, 5] == Colors.rgb(255, 255, 0))
        check("flood fill did not jump the wall", bucket.pixels!![40, 5] == 0)

        // Gradient: endpoints and orientation.
        val gradientLayer = Layer("gradient")
        gradientLayer.pixels = Pixmap(100, 100)
        Painter(gradientLayer).gradient(
            Rect(0, 0, 100, 100),
            Colors.rgb(0, 0, 0),
            Colors.rgb(255, 255, 255),
            GradientType.HORIZONTAL,
            GradientShape.LINEAR,
        )
        check("gradient starts dark on the left", Colors.red(gradientLayer.pixels!![1, 50]) < 20)
        check("gradient ends light on the right", Colors.red(gradientLayer.pixels!![98, 50]) > 235)
        check("gradient is monotonic", Colors.red(gradientLayer.pixels!![30, 50]) < Colors.red(gradientLayer.pixels!![70, 50]))

        // Radial gradient is symmetric.
        val radialLayer = Layer("radial")
        radialLayer.pixels = Pixmap(100, 100)
        Painter(radialLayer).gradient(
            Rect(0, 0, 100, 100),
            Colors.rgb(0, 0, 0),
            Colors.rgb(255, 255, 255),
            GradientType.RADIAL,
            GradientShape.RADIAL,
        )
        val left = Colors.red(radialLayer.pixels!![10, 50])
        val right = Colors.red(radialLayer.pixels!![89, 50])
        check("radial gradient is symmetric", abs(left - right) < 12, "$left vs $right")
        check("radial gradient is darkest at the edges", Colors.red(radialLayer.pixels!![50, 50]) < left)

        // Shapes.
        val shapes = Layer("shapes")
        shapes.pixels = Pixmap(120, 120)
        val shapePainter = Painter(shapes)
        shapePainter.rectangle(Rect(10, 10, 40, 30), Colors.rgb(255, 0, 0), 2f, filled = false)
        check("rectangle outline draws its top edge", Colors.red(shapes.pixels!![20, 10]) > 200)
        check("rectangle outline leaves the middle empty", shapes.pixels!![25, 25] == 0)
        shapePainter.rectangle(Rect(60, 60, 40, 40), Colors.rgb(0, 255, 0), 1f, filled = true)
        check("filled rectangle covers its area", shapes.pixels!![80, 80] == Colors.rgb(0, 255, 0))
        shapePainter.ellipse(Rect(10, 60, 40, 40), Colors.rgb(0, 0, 255), 2f, filled = true)
        check("ellipse fills its centre", Colors.blue(shapes.pixels!![30, 80]) > 200)
        check("ellipse leaves its corner empty", shapes.pixels!![12, 62] == 0)

        // Retouch tools move pixels in the expected direction.
        val retouch = Layer("retouch")
        retouch.pixels = Pixmap(50, 50).apply { fill(Colors.rgb(120, 120, 120)) }
        Painter(retouch).retouch(25f, 25f, Brush(size = 20f, hardness = 1f), Painter.RetouchKind.DODGE, amount = 1f)
        check("dodge lightens", Colors.red(retouch.pixels!![25, 25]) > 120)
        Painter(retouch).retouch(25f, 25f, Brush(size = 20f, hardness = 1f), Painter.RetouchKind.BURN, amount = 1f)
        check("burn darkens", Colors.red(retouch.pixels!![25, 25]) < 230)

        val sponge = Layer("sponge")
        sponge.pixels = Pixmap(50, 50).apply { fill(Colors.rgb(200, 120, 60)) }
        Painter(sponge).retouch(25f, 25f, Brush(size = 20f, hardness = 1f), Painter.RetouchKind.SPONGE_DESATURATE, amount = 1f)
        val hsv = FloatArray(3)
        Colors.toHsv(sponge.pixels!![25, 25], hsv)
        val before = FloatArray(3)
        Colors.toHsv(Colors.rgb(200, 120, 60), before)
        check("sponge desaturates", hsv[1] < before[1])

        // Clone stamp copies from the source offset.
        val clone = Layer("clone")
        clone.pixels = Pixmap(60, 60)
        for (y in 0 until 30) for (x in 0 until 30) clone.pixels!!.pixels[y * 60 + x] = Colors.rgb(0, 200, 0)
        val clonePainter = Painter(clone)
        clonePainter.cloneSource = clone.pixels
        clonePainter.cloneOffsetX = -30f
        clonePainter.cloneOffsetY = 0f
        clonePainter.cloneStamp(45f, 15f, Brush(size = 10f, hardness = 1f))
        check("clone stamp copies the source pixels", Colors.green(clone.pixels!![45, 15]) == 200)

        // Blur smooths a hard edge.
        val blurLayer = Layer("blur")
        blurLayer.pixels = Pixmap(60, 60)
        for (y in 0 until 60) for (x in 0 until 60) blurLayer.pixels!!.pixels[y * 60 + x] = if (x < 30) Colors.rgb(0, 0, 0) else Colors.rgb(255, 255, 255)
        Painter(blurLayer).neighbourhood(30f, 30f, Brush(size = 20f, hardness = 1f), Painter.NeighbourhoodKind.BLUR, strength = 1f)
        val blurred = Colors.red(blurLayer.pixels!![29, 30])
        check("blur softens the edge", blurred in 1..254, "value $blurred")
    }

    /* ---------------------------------------------------------- 6. adjustments */

    private fun adjustmentSection() {
        section("Adjustments")

        fun apply(adjustment: Adjustment, colour: Int): Int {
            val pixmap = Pixmap(2, 2).apply { fill(colour) }
            adjustment.apply(pixmap)
            return pixmap.pixels[0]
        }

        check("invert flips every channel", apply(Adjustment.Invert(), Colors.rgb(10, 20, 30)) == Colors.rgb(245, 235, 225))
        check("invert twice is the identity", apply(Adjustment.Invert(), apply(Adjustment.Invert(), Colors.rgb(10, 20, 30))) == Colors.rgb(10, 20, 30))
        check("posterize snaps to levels", apply(Adjustment.Posterize(2), Colors.rgb(100, 100, 100)).let { Colors.red(it) == 0 || Colors.red(it) == 255 })
        check("threshold goes black or white", apply(Adjustment.Threshold(128), Colors.rgb(200, 200, 200)) == Colors.WHITE)
        check("threshold below the level is black", apply(Adjustment.Threshold(128), Colors.rgb(20, 20, 20)) == Colors.BLACK)
        check("desaturate keeps the luminance", abs(Colors.luminance(apply(Adjustment.Desaturate(), Colors.rgb(200, 50, 20))) - Colors.luminance(Colors.rgb(200, 50, 20))) <= 2)

        // Levels: black point raises, white point lowers.
        val levels = Adjustment.Levels(inputBlack = 50, inputWhite = 200)
        check("levels maps the input black to 0", Colors.red(apply(levels, Colors.rgb(50, 50, 50))) == 0)
        check("levels maps the input white to 255", Colors.red(apply(levels, Colors.rgb(200, 200, 200))) == 255)
        check("levels clips below the black point", Colors.red(apply(levels, Colors.rgb(20, 20, 20))) == 0)
        check("levels leaves mid grey in the middle", abs(Colors.red(apply(levels, Colors.rgb(125, 125, 125))) - 128) < 20)

        val outputLevels = Adjustment.Levels(outputBlack = 0, outputWhite = 128)
        check("levels output white caps the range", Colors.red(apply(outputLevels, Colors.WHITE)) == 128)

        // Curves: a linear curve is the identity, an inverted one is not.
        val linear = Adjustment.Curves.linear()
        check("a linear curve is the identity", Colors.red(apply(linear, Colors.rgb(77, 77, 77))) == 77)
        val invertedCurve = Adjustment.Curves.of(listOf(floatArrayOf(0f, 255f), floatArrayOf(255f, 0f)))
        check("an inverted curve darkens bright pixels", Colors.red(apply(invertedCurve, Colors.rgb(200, 200, 200))) < 80)

        // Brightness/contrast.
        val brighter = Adjustment.BrightnessContrast(brightness = 50f)
        check("positive brightness lightens", Colors.red(apply(brighter, Colors.rgb(100, 100, 100))) > 100)
        val darker = Adjustment.BrightnessContrast(brightness = -50f)
        check("negative brightness darkens", Colors.red(apply(darker, Colors.rgb(100, 100, 100))) < 100)
        val contrasted = Adjustment.BrightnessContrast(contrast = 60f)
        check("contrast pushes 25% down", Colors.red(apply(contrasted, Colors.rgb(64, 64, 64))) < 64)
        check("contrast pushes 75% up", Colors.red(apply(contrasted, Colors.rgb(192, 192, 192))) > 192)

        // Hue/Saturation.
        val red = Colors.rgb(255, 0, 0)
        val shifted = apply(Adjustment.HueSaturation(hue = 120f), red)
        check("a 120° hue shift takes red to green", Colors.green(shifted) > 200 && Colors.red(shifted) < 60, "got ${Colors.toHex(shifted)}")
        val grey = apply(Adjustment.HueSaturation(saturation = -100f), red)
        check("minus 100 saturation is grey", abs(Colors.red(grey) - Colors.green(grey)) < 2 && abs(Colors.green(grey) - Colors.blue(grey)) < 2)

        // Channel mixer: monochrome, and a pure-red channel from green.
        val mono = Adjustment.ChannelMixer(monochrome = true)
        val monoOut = apply(mono, Colors.rgb(10, 200, 30))
        check("monochrome mixer is grey", Colors.red(monoOut) == Colors.green(monoOut) && Colors.green(monoOut) == Colors.blue(monoOut))
        val swap = Adjustment.ChannelMixer(red = floatArrayOf(0f, 100f, 0f))
        check("channel mixer moves green into red", Colors.red(apply(swap, Colors.rgb(0, 255, 0))) == 255)

        // Vibrance affects dull colours more than saturated ones.
        val vibrance = Adjustment.Vibrance(vibrance = 100f)
        val dullBefore = FloatArray(3)
        val dullAfter = FloatArray(3)
        Colors.toHsv(Colors.rgb(160, 120, 120), dullBefore)
        Colors.toHsv(apply(vibrance, Colors.rgb(160, 120, 120)), dullAfter)
        check("vibrance boosts a dull colour", dullAfter[1] > dullBefore[1])
        val vividBefore = FloatArray(3)
        val vividAfter = FloatArray(3)
        Colors.toHsv(Colors.rgb(255, 0, 0), vividBefore)
        Colors.toHsv(apply(vibrance, Colors.rgb(255, 0, 0)), vividAfter)
        check("vibrance leaves an already-saturated colour nearly alone", abs(vividAfter[1] - vividBefore[1]) < 0.15f)

        // Gradient map: dark input maps to the first stop.
        val gradientMap = Adjustment.GradientMap(arrayOf(intArrayOf(0, Colors.BLACK), intArrayOf(255, Colors.WHITE)))
        check("gradient map maps black to black", apply(gradientMap, Colors.BLACK) == Colors.BLACK)
        check("gradient map maps white to white", apply(gradientMap, Colors.WHITE) == Colors.WHITE)

        // Photo filter tints without destroying luminance.
        val warmed = apply(Adjustment.PhotoFilter(0xFFEC8A00.toInt(), 0.4f), Colors.rgb(128, 128, 128))
        check("photo filter warms the colour", Colors.red(warmed) > Colors.blue(warmed))

        // Exposure doubles at +1 stop.
        check("exposure +1 stop doubles the value", Colors.red(apply(Adjustment.Exposure(exposure = 1f), Colors.rgb(64, 64, 64))) in 127..129)
        check("exposure of black stays black", Colors.red(apply(Adjustment.Exposure(exposure = 2f), Colors.BLACK)) == 0)

        // Alpha is never touched by a colour adjustment.
        val transparent = Colors.withAlpha(Colors.rgb(200, 200, 200), 128)
        check("adjustments leave alpha alone", Colors.alpha(apply(Adjustment.Invert(), transparent)) == 128)
    }

    /* --------------------------------------------------------------- 7. filters */

    private fun filterSection() {
        section("Filters")

        // Blur: a hard edge becomes a ramp; the extreme edge pixels move inward.
        val edge = Pixmap(64, 64)
        for (y in 0 until 64) for (x in 0 until 64) {
            edge.pixels[y * 64 + x] = if (x < 32) Colors.BLACK else Colors.WHITE
        }
        Filters.blur(edge, Rect(0, 0, 64, 64), 4f)
        check("blur softens a hard edge", Colors.red(edge[30, 32]) in 1..254, "got ${Colors.red(edge[30, 32])}")
        check("blur keeps the far left dark", Colors.red(edge[2, 32]) < 40)
        check("blur keeps the far right light", Colors.red(edge[61, 32]) > 215)
        check("blur keeps the overall brightness", abs(average(edge) - 127.5) < 12, "average ${average(edge)}")

        // Blur must not smear transparent pixels into opaque ones as black.
        val alpha = Pixmap(32, 32)
        for (y in 0 until 32) for (x in 0 until 32) {
            alpha.pixels[y * 32 + x] = if (x < 16) Colors.rgb(255, 0, 0) else 0
        }
        Filters.blur(alpha, Rect(0, 0, 32, 32), 3f)
        val boundary = alpha[17, 16]
        check("blur does not pull black into an alpha edge", Colors.red(boundary) > 100, "red ${Colors.red(boundary)}")
        check("blur keeps the transparent side transparent", Colors.alpha(alpha[30, 16]) < 200)

        // Sharpen increases local contrast.
        val soft = Pixmap(64, 64)
        for (y in 0 until 64) for (x in 0 until 64) {
            soft.pixels[y * 64 + x] = if (x < 32) Colors.rgb(100, 100, 100) else Colors.rgb(150, 150, 150)
        }
        val before = soft[33, 32]
        Filters.unsharpMask(soft, Rect(0, 0, 64, 64), 3f, 2f, 0)
        check("unsharp mask sharpens the edge", Colors.red(soft[33, 32]) > Colors.red(before), "${Colors.red(before)} -> ${Colors.red(soft[33, 32])}")

        // Mosaic makes uniform cells.
        val mosaic = Pixmap(40, 40)
        for (y in 0 until 40) for (x in 0 until 40) mosaic.pixels[y * 40 + x] = Colors.rgb(x * 6 % 256, y * 6 % 256, 0)
        Filters.mosaic(mosaic, Rect(0, 0, 40, 40), 8)
        check("mosaic produces uniform cells", mosaic[0, 0] == mosaic[7, 7])
        check("mosaic changes between cells", mosaic[0, 0] != mosaic[10, 10])

        // Noise changes pixels but not the average much.
        val noise = Pixmap(32, 32).apply { fill(Colors.rgb(128, 128, 128)) }
        Filters.addNoise(noise, Rect(0, 0, 32, 32), 30f, monochrome = true, seed = 1L)
        check("noise changes pixels", noise[5, 5] != Colors.rgb(128, 128, 128))
        check("monochrome noise keeps channels equal", Colors.red(noise[5, 5]) == Colors.green(noise[5, 5]))
        check("noise keeps the average close", abs(average(noise) - 128.0) < 20, "average ${average(noise)}")

        // Vignette darkens the corners, not the centre.
        val vignette = Pixmap(64, 64).apply { fill(Colors.rgb(200, 200, 200)) }
        Filters.vignette(vignette, Rect(0, 0, 64, 64), -0.8f, 0.6f, 1f)
        check("vignette darkens the corner", Colors.red(vignette[2, 2]) < Colors.red(vignette[32, 32]))
        check("vignette leaves the centre bright", Colors.red(vignette[32, 32]) > 180)

        // Motion blur smears along one axis only: a single white dot becomes a
        // horizontal dash and stays a point vertically.
        val motion = Pixmap(64, 64)
        motion.pixels[32 * 64 + 32] = Colors.WHITE
        Filters.motionBlur(motion, Rect(0, 0, 64, 64), 10f, 0f)
        check("motion blur spreads along the blur axis", Colors.alpha(motion[36, 32]) > 0, "alpha ${Colors.alpha(motion[36, 32])}")
        check("motion blur leaves the other axis alone", Colors.alpha(motion[32, 36]) == 0, "alpha ${Colors.alpha(motion[32, 36])}")
        check("motion blur keeps the original dot lit", Colors.red(motion[32, 32]) == 255)

        // Edge detect: a flat area stays black, an edge lights up.
        val edges = Pixmap(32, 32)
        for (y in 0 until 32) for (x in 0 until 32) edges.pixels[y * 32 + x] = if (x < 16) Colors.BLACK else Colors.WHITE
        Filters.edgeDetect(edges, Rect(0, 0, 32, 32))
        check("edge detect lights up the boundary", Colors.red(edges[16, 16]) > 100)
        check("edge detect keeps flat areas dark", Colors.red(edges[5, 16]) < 40)

        // Sepia warms grey.
        val sepia = Pixmap(8, 8).apply { fill(Colors.rgb(128, 128, 128)) }
        Filters.sepia(sepia, Rect(0, 0, 8, 8), 1f)
        check("sepia warms the tone", Colors.red(sepia[4, 4]) > Colors.blue(sepia[4, 4]))

        // Solarize inverts the bright end.
        val solar = Pixmap(4, 4).apply { fill(Colors.rgb(200, 200, 200)) }
        Filters.solarize(solar, Rect(0, 0, 4, 4), 128)
        check("solarize inverts bright pixels", Colors.red(solar[1, 1]) == 55)
    }

    /* -------------------------------------------------------------- 8. geometry */

    private fun geometrySection() {
        section("Geometry")

        check("rect intersection", Rect(0, 0, 10, 10).intersect(Rect(5, 5, 10, 10)) == Rect(5, 5, 5, 5))
        check("rect union", Rect(0, 0, 10, 10).union(Rect(20, 20, 5, 5)) == Rect(0, 0, 25, 25))
        check("rect clamp", Rect(-5, -5, 20, 20).clampTo(10, 10) == Rect(0, 0, 10, 10))
        check("rect of points handles a reversed drag", Rect.ofPoints(40, 30, 10, 5) == Rect(10, 5, 30, 25))
        check("rect area is a long", Rect(0, 0, 100_000, 100_000).area == 10_000_000_000L)

        val translate = Mat3.translation(10f, 20f)
        check("translation maps a point", translate.mapX(1f, 1f) == 11f && translate.mapY(1f, 1f) == 21f)
        val scale = Mat3.scale(2f, 3f)
        check("scale maps a point", scale.mapX(2f, 2f) == 4f && scale.mapY(2f, 2f) == 6f)
        val rotate = Mat3.rotation(90f)
        check("90° rotation maps x to y", abs(rotate.mapX(1f, 0f) - 0f) < 0.001f && abs(rotate.mapY(1f, 0f) - 1f) < 0.001f)

        val combined = Mat3.translation(5f, 0f).postConcat(Mat3.scale(2f, 2f))
        check("combined transform scales then translates", combined.mapX(1f, 0f) == 7f, "got ${combined.mapX(1f, 0f)}")

        val inverse = Mat3.rotation(37f).postConcat(Mat3.translation(11f, -4f)).invert()
        check("inverse exists", inverse != null)
        val forward = Mat3.rotation(37f).postConcat(Mat3.translation(11f, -4f))
        val x = 13f
        val y = -7f
        val ix = inverse!!.mapX(forward.mapX(x, y), forward.mapY(x, y))
        val iy = inverse.mapY(forward.mapX(x, y), forward.mapY(x, y))
        check("forward then inverse returns the point", abs(ix - x) < 0.01f && abs(iy - y) < 0.01f, "got ($ix, $iy)")

        val mapped = Mat3.rotationAbout(90f, 0f, 0f).mapRect(Rect(0, 0, 10, 20))
        check("rotated bounds grow to fit", mapped.w >= 20 && mapped.h >= 10, "got $mapped")

        // Pixmap basics.
        val pixmap = Pixmap(8, 8)
        pixmap[3, 3] = Colors.rgb(1, 2, 3)
        check("pixmap reads what it wrote", pixmap[3, 3] == Colors.rgb(1, 2, 3))
        check("pixmap reads out of bounds as transparent", pixmap[-1, 0] == 0 && pixmap[100, 100] == 0)
        val cropped = pixmap.crop(Rect(2, 2, 4, 4))
        check("crop takes the right size", cropped.width == 4 && cropped.height == 4)
        check("crop keeps the pixels", cropped[1, 1] == Colors.rgb(1, 2, 3))
        val scaled = pixmap.scaledTo(16, 16)
        check("scaledTo produces the requested size", scaled.width == 16 && scaled.height == 16)

        // Downsample averages, rather than picking one pixel.
        val source = Pixmap(4, 4)
        for (y in 0 until 4) for (x in 0 until 4) {
            source.pixels[y * 4 + x] = if (x < 2) Colors.BLACK else Colors.WHITE
        }
        val out = IntArray(4)
        source.downsample(out, 0, 2, 2, 2)
        check("downsample averages the halves", Colors.red(out[0]) < 20 && Colors.red(out[1]) > 235)

        // Colour helpers.
        check("pack and unpack agree", Colors.pack(128, 10, 20, 30).let { Colors.alpha(it) == 128 && Colors.red(it) == 10 && Colors.blue(it) == 30 })
        check("hex parses", Colors.parseHex("#FF8800") == Colors.rgb(255, 136, 0))
        check("hex parses with alpha", Colors.parseHex("#80FF8800") == Colors.pack(128, 255, 136, 0))
        check("bad hex is rejected", Colors.parseHex("nope") == null)
        val hsv = FloatArray(3)
        Colors.toHsv(Colors.rgb(255, 0, 0), hsv)
        check("red is hue 0", abs(hsv[0]) < 0.01f && abs(hsv[1] - 1f) < 0.01f)
        Colors.toHsv(Colors.rgb(0, 255, 0), hsv)
        check("green is hue 120", abs(hsv[0] - 120f) < 1f, "got ${hsv[0]}")
    }

    /* ---------------------------------------------------------------- reporting */

    private fun section(title: String) = println("\n== $title ==")

    private fun check(name: String, condition: Boolean, detail: String = "") {
        if (condition) {
            passed++
            println("  ok   $name")
        } else {
            failed++
            failures.add("$name${if (detail.isEmpty()) "" else " — $detail"}")
            println("  FAIL $name${if (detail.isEmpty()) "" else " — $detail"}")
        }
    }

    private fun average(pixmap: Pixmap): Double {
        var sum = 0.0
        var count = 0
        for (pixel in pixmap.pixels) {
            if (Colors.alpha(pixel) == 0) continue
            sum += Colors.luminance(pixel)
            count++
        }
        return if (count == 0) 0.0 else sum / count
    }

    private fun report() {
        println()
        if (failed == 0) {
            println("ALL ENGINE CHECKS PASSED ($passed checks)")
        } else {
            println("$failed of ${passed + failed} CHECKS FAILED")
            failures.forEach { println("  - $it") }
            exitProcess(1)
        }
    }
}
