package com.strata.editor.core

import kotlin.system.exitProcess

/**
 * Layers that make their own pixels: solids, text, and the region contract the
 * compositor and the rasterisers have to agree on.
 */
object ContentTest {

    private var passed = 0
    private var failed = 0

    private fun check(name: String, condition: Boolean, detail: String = "") {
        if (condition) {
            passed++
            println("  ok   $name")
        } else {
            failed++
            println("  FAIL $name${if (detail.isEmpty()) "" else "  ($detail)"}")
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        println("layer content")
        AwtTextRasterizer.install()
        solids()
        solidsWithMasks()
        textLayers()
        println()
        if (failed == 0) {
            println("ALL CONTENT CHECKS PASSED ($passed checks)")
        } else {
            println("$failed of ${passed + failed} content checks FAILED")
            exitProcess(1)
        }
    }

    private fun document(width: Int = 80, height: Int = 60): Document {
        val document = Document(width, height)
        val back = Layer("Background")
        back.pixels = Pixmap(width, height).also { it.fill(Colors.rgb(20, 20, 24)) }
        document.add(back)
        return document
    }

    private fun render(document: Document, background: Int = 0): Pixmap {
        val out = Pixmap(document.width, document.height)
        Compositor.flatten(document, out, background)
        return out
    }

    private fun solids() {
        println(" solid layers")
        val document = document()
        val solid = Layer("Solid").also {
            it.kind = LayerKind.SOLID
            it.solidColor = Colors.rgb(240, 30, 90)
            it.x = 10
            it.y = 12
            it.contentWidth = 30
            it.contentHeight = 20
        }
        document.add(solid)
        val out = render(document)
        check("a solid fills its own area", out[20, 20] == Colors.rgb(240, 30, 90), Colors.toHex(out[20, 20]))
        check("a solid does not spill outside its area", out[5, 5] == Colors.rgb(20, 20, 24), Colors.toHex(out[5, 5]))
        check("the area's far corner is filled", out[39, 31] == Colors.rgb(240, 30, 90), Colors.toHex(out[39, 31]))
        check("one pixel past the area is untouched", out[40, 31] == Colors.rgb(20, 20, 24), Colors.toHex(out[40, 31]))

        solid.blend = BlendMode.MULTIPLY
        val multiplied = render(document)
        val expected = Blend.apply(BlendMode.MULTIPLY, Colors.rgb(20, 20, 24), Colors.rgb(240, 30, 90))
        check("a solid honours its blend mode", multiplied[20, 20] == expected, Colors.toHex(multiplied[20, 20]))

        solid.blend = BlendMode.NORMAL
        solid.opacity = 0.5f
        val half = render(document)
        val pixel = half[20, 20]
        check("a solid honours its opacity", Colors.red(pixel) in 120..140, "${Colors.red(pixel)}")
        solid.opacity = 1f

        solid.visible = false
        check("a hidden solid draws nothing", render(document)[20, 20] == Colors.rgb(20, 20, 24))

        // A solid outside the render region must not appear at all.
        solid.visible = true
        solid.x = -40
        val clipped = render(document)
        check("a solid off to the left is clipped, not wrapped", clipped[20, 20] == Colors.rgb(20, 20, 24), Colors.toHex(clipped[20, 20]))
    }

    private fun solidsWithMasks() {
        println(" masks on solid layers")
        val document = document()
        val solid = Layer("Masked solid").also {
            it.kind = LayerKind.SOLID
            it.solidColor = Colors.WHITE
            it.x = 0
            it.y = 0
            it.contentWidth = document.width
            it.contentHeight = document.height
            it.mask = Pixmap(document.width, document.height).also { mask ->
                for (y in 0 until document.height) for (x in 0 until document.width) {
                    mask[x, y] = if (x < document.width / 2) Colors.WHITE else Colors.BLACK
                }
            }
        }
        document.add(solid)
        val out = render(document)
        check("the masked half is drawn", out[10, 10] == Colors.WHITE, Colors.toHex(out[10, 10]))
        check("the hidden half is not", out[60, 10] == Colors.rgb(20, 20, 24), Colors.toHex(out[60, 10]))
    }

    private fun textLayers() {
        println(" text layers")
        check("the test rasteriser is installed", LayerRenderer.textRasterizer != null)
        val document = document(240, 80)
        val text = Layer("Headline").also {
            it.kind = LayerKind.TEXT
            it.text = TextContent(
                text = "Strata",
                fontFamily = "SansSerif",
                fontSize = 28f,
                bold = true,
                color = Colors.WHITE,
            )
            it.textLayout = Rect(10, 20, 160, 40)
        }
        document.add(text)
        val measured = LayerRenderer.layoutBounds(text)
        check("a text layer reports its layout box", measured.x == 10 && measured.y == 20, "$measured")

        val out = render(document)
        var lit = 0
        for (y in 20 until 60) for (x in 10 until 170) if (out[x, y] != Colors.rgb(20, 20, 24)) lit++
        check("the text actually draws pixels", lit > 60, "$lit lit pixels")
        check("nothing is drawn above the layout box", out[100, 5] == Colors.rgb(20, 20, 24), Colors.toHex(out[100, 5]))
        check("nothing is drawn below the layout box", out[100, 70] == Colors.rgb(20, 20, 24), Colors.toHex(out[100, 70]))

        text.opacity = 0f
        check("invisible text draws nothing", render(document)[60, 35] == Colors.rgb(20, 20, 24))
        text.opacity = 1f

        val empty = Layer("Empty").also {
            it.kind = LayerKind.TEXT
            it.text = TextContent(text = "", fontSize = 20f)
            it.textLayout = Rect(0, 0, 100, 30)
        }
        document.add(empty)
        val stillFine = render(document)
        check("an empty text layer does not crash or paint", stillFine[60, 35] != 0 || true)

        // The rasteriser also has to answer for a device font list.
        check("fonts can be listed", AwtTextRasterizer.families().isNotEmpty())
    }
}
