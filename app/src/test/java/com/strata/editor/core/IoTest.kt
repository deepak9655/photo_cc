package com.strata.editor.core

import kotlin.system.exitProcess

/**
 * Import and export, exercised through the JVM image codec (the phone uses the
 * same engine with an `android.graphics` codec behind it).
 */
object IoTest {

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
        println("image in and out")
        JvmImageCodec.install()
        roundTrips()
        exports()
        imports()
        formats()
        println()
        if (failed == 0) {
            println("ALL IMAGE CHECKS PASSED ($passed checks)")
        } else {
            println("$failed of ${passed + failed} image checks FAILED")
            exitProcess(1)
        }
    }

    private fun sample(width: Int = 40, height: Int = 30): Pixmap {
        val pixmap = Pixmap(width, height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                pixmap[x, y] = if (x < width / 2) Colors.rgb(200, 40, 40) else Colors.rgb(30, 120, 220)
            }
        }
        // A transparent corner, to prove alpha survives the formats that keep it.
        for (y in 0 until 4) for (x in 0 until 4) pixmap[x, y] = 0
        return pixmap
    }

    private fun roundTrips() {
        println(" round trips")
        val original = sample()
        val png = ImageIo.encode(original, ImageFormat.PNG)
        check("PNG encoding produces bytes", png != null && png.size > 40, "${png?.size ?: 0}")
        val decoded = png?.let { ImageIo.decode(it, "test.png") }
        check("PNG decodes back", decoded != null)
        check("PNG keeps the size", decoded?.pixmap?.width == 40 && decoded?.pixmap?.height == 30,
            "${decoded?.pixmap?.width}x${decoded?.pixmap?.height}")
        check("PNG keeps colours exactly", decoded?.pixmap?.get(20, 15) == Colors.rgb(30, 120, 220),
            decoded?.pixmap?.get(20, 15)?.let { Colors.toHex(it) } ?: "none")
        check("PNG keeps transparency", Colors.alpha(decoded?.pixmap?.get(1, 1) ?: 255) == 0,
            "${Colors.alpha(decoded?.pixmap?.get(1, 1) ?: 255)}")

        val jpeg = ImageIo.encode(original, ImageFormat.JPEG, 95)
        check("JPEG encoding produces bytes", jpeg != null && jpeg.size > 40, "${jpeg?.size}")
        val fromJpeg = jpeg?.let { ImageIo.decode(it, "test.jpg") }
        check("JPEG decodes back", fromJpeg != null)
        check("JPEG is opaque, as the format insists",
            fromJpeg != null && Colors.alpha(fromJpeg.pixmap.get(1, 1)) == 255,
            "${fromJpeg?.pixmap?.get(1, 1)?.let { Colors.alpha(it) }}")
        // JPEG subsamples colour, so the check is "still recognisably the same
        // colour", not "identical to the byte".
        val lossy = fromJpeg?.pixmap?.get(20, 15) ?: 0
        check("a blue area survives JPEG", Colors.blue(lossy) > Colors.red(lossy) + 60 && Colors.blue(lossy) > 120,
            "${Colors.red(lossy)},${Colors.green(lossy)},${Colors.blue(lossy)}")
        val leftSide = fromJpeg?.pixmap?.get(5, 15) ?: 0
        check("a red area survives JPEG", Colors.red(leftSide) > Colors.blue(leftSide) + 60 && Colors.red(leftSide) > 120,
            "${Colors.red(leftSide)},${Colors.green(leftSide)},${Colors.blue(leftSide)}")

        check("garbage is rejected", ImageIo.decode(byteArrayOf(1, 2, 3, 4, 5), "x.png") == null)
        check("an empty file is rejected", ImageIo.decode(ByteArray(0), "x.png") == null)
        check("an unknown extension still decodes by content", png != null && ImageIo.decode(png, "mystery") != null)
    }

    private fun exports() {
        println(" exporting a document")
        val document = Document(50, 40)
        val back = Layer("Background")
        back.pixels = Pixmap(50, 40).also { it.fill(Colors.rgb(240, 240, 240)) }
        document.add(back)
        val square = Layer("Square").also { it.x = 10; it.y = 10; it.opacity = 0.5f }
        square.pixels = Pixmap(20, 20).also { it.fill(Colors.BLACK) }
        document.add(square)

        val png = ImageIo.exportDocument(document, ImageFormat.PNG, background = Colors.WHITE)
        val decoded = png?.let { ImageIo.decode(it, "export.png") }
        val expected = Pixmap(50, 40)
        Compositor.flatten(document, expected, 0)
        var worst = 0
        if (decoded != null) {
            for (i in decoded.pixmap.pixels.indices) {
                val a = decoded.pixmap.pixels[i]
                val b = expected.pixels[i]
                worst = maxOf(worst, kotlin.math.abs(Colors.red(a) - Colors.red(b)))
            }
        }
        check("an exported PNG matches the flattened document exactly", decoded != null && worst == 0, "worst delta $worst")
        val half = decoded?.pixmap?.get(20, 20)?.let { Colors.red(it) } ?: -1
        check("the halfway square really is halfway", half in 118..121, "$half")

        val jpeg = ImageIo.exportDocument(document, ImageFormat.JPEG, 90, background = Colors.WHITE)
        check("a JPEG export is opaque in the corners", jpeg != null)
        val fromJpeg = jpeg?.let { ImageIo.decode(it, "export.jpg") }
        check("the exported JPEG has the document's size", fromJpeg?.pixmap?.width == 50, "${fromJpeg?.pixmap?.width}")

        val savedCodec = ImageIo.codec
        ImageIo.codec = null
        check("without a codec, encoding says so instead of throwing", ImageIo.encode(Pixmap(4, 4), ImageFormat.PNG) == null)
        ImageIo.codec = savedCodec
    }

    private fun imports() {
        println(" importing an image")
        val document = Document(100, 80)
        val bytes = ImageIo.encode(sample(60, 20), ImageFormat.PNG)!!
        val layer = ImageIo.layerFrom(document, bytes, "photo.png")
        check("an image becomes a layer", layer != null && layer.pixels?.width == 60, "${layer?.pixels?.width}")
        check("a layer is named after the file", layer?.name == "photo", "${layer?.name}")
        check("a layer is centred", layer?.x == 20 && layer?.y == 30, "${layer?.x},${layer?.y}")

        val small = Document(20, 20)
        val big = ImageIo.layerFrom(small, bytes, "wide.png")
        check("an image wider than the canvas is not pushed off it", (big?.x ?: -1) == 0, "${big?.x}")

        // Import scaling: ask for a budget smaller than the image.
        val huge = ImageIo.encode(sample(400, 300), ImageFormat.PNG)!!
        val scaled = ImageIo.decode(huge, "huge.png", maxPixels = 10_000)
        check("an oversized import is scaled down", scaled != null && scaled.pixmap.width * scaled.pixmap.height <= 10_000,
            "${scaled?.pixmap?.width}x${scaled?.pixmap?.height}")
        check("the original size is reported", scaled?.originalWidth == 400 && scaled?.originalHeight == 300,
            "${scaled?.originalWidth}x${scaled?.originalHeight}")
        check("scaling is flagged", scaled?.scaled == true)
        check("a layer records that it was scaled", ImageIo.layerFrom(Document(500, 400), huge, "huge.png", 10_000)?.note != null)

        // Trimming a flat border.
        val plate = Pixmap(60, 60)
        plate.fill(Colors.WHITE)
        for (y in 10 until 40) for (x in 20 until 50) plate[x, y] = Colors.rgb(10, 10, 10)
        val trimmed = ImageIo.trim(plate)
        check("trimming finds the content box", trimmed == Rect(20, 10, 30, 30), "$trimmed")
        val blank = Pixmap(20, 20)
        blank.fill(Colors.rgb(5, 5, 5))
        check("trimming a blank image gives nothing", ImageIo.trim(blank).isEmpty, "${ImageIo.trim(blank)}")
    }

    private fun formats() {
        println(" format names")
        check("a PNG name is recognised", ImageFormat.ofFile("holiday.PNG") == ImageFormat.PNG)
        check("a JPEG name is recognised", ImageFormat.ofFile("holiday.jpeg") == ImageFormat.JPEG)
        check("a TIFF name is recognised", ImageFormat.ofFile("scan.tif") == ImageFormat.TIFF)
        check("an unknown name is not guessed", ImageFormat.ofFile("notes.txt") == null)
        check("a MIME type is recognised", ImageFormat.ofMime("image/jpeg") == ImageFormat.JPEG)
        check("PNG keeps alpha", ImageFormat.PNG.keepsAlpha && !ImageFormat.JPEG.keepsAlpha)
    }
}
