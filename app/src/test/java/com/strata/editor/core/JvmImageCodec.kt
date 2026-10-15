package com.strata.editor.core

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * An [ImageCodec] for the JVM tests, built on `javax.imageio`.
 *
 * The app uses `android.graphics` instead; this exists so the import and export
 * paths are exercised by the same tests as everything else, and so the PSD
 * container can be checked against a real encoder rather than against itself.
 */
object JvmImageCodec : ImageCodec {

    private val lock = Any()

    /**
     * Registers this implementation. Called explicitly rather than from `init`:
     * Kotlin compiles the methods of a stateless object as statics, so `init` is
     * not guaranteed to have run by the time the first call arrives.
     */
    @JvmStatic
    fun install(): JvmImageCodec {
        ImageIo.codec = this
        return this
    }

    override fun decode(data: ByteArray, maxPixels: Int): Pixmap? {
        val image = readImage(data) ?: return null
        val width = image.width
        val height = image.height
        if (width <= 0 || height <= 0) return null
        val pixels = IntArray(width * height)
        image.getRGB(0, 0, width, height, pixels, 0, width)
        val pixmap = Pixmap(width, height, pixels)
        if (pixels.size <= maxPixels) return pixmap
        var stride = 1
        while (width.toLong() * height / (stride.toLong() * stride) > maxPixels && stride < 32) stride++
        val outWidth = (width + stride - 1) / stride
        val outHeight = (height + stride - 1) / stride
        val out = IntArray(outWidth * outHeight)
        pixmap.downsample(out, 0, outWidth, outWidth, outHeight, 0)
        return Pixmap(outWidth, outHeight, out)
    }

    override fun encode(pixmap: Pixmap, format: ImageFormat, quality: Int): ByteArray? {
        if (pixmap.width <= 0 || pixmap.height <= 0) return null
        val image = if (format.keepsAlpha) {
            BufferedImage(pixmap.width, pixmap.height, BufferedImage.TYPE_INT_ARGB).also {
                it.setRGB(0, 0, pixmap.width, pixmap.height, pixmap.pixels, 0, pixmap.width)
            }
        } else {
            // JPEG has no transparency: composite over white, as every editor does.
            BufferedImage(pixmap.width, pixmap.height, BufferedImage.TYPE_INT_RGB).also { image ->
                val graphics = image.createGraphics()
                graphics.color = java.awt.Color.WHITE
                graphics.fillRect(0, 0, image.width, image.height)
                graphics.dispose()
                val row = IntArray(pixmap.width)
                for (y in 0 until pixmap.height) {
                    for (x in 0 until pixmap.width) {
                        val colour = pixmap[x, y]
                        val alpha = Colors.alpha(colour) / 255f
                        row[x] = Colors.rgb(
                            (Colors.red(colour) * alpha + 255 * (1 - alpha)).toInt().coerceIn(0, 255),
                            (Colors.green(colour) * alpha + 255 * (1 - alpha)).toInt().coerceIn(0, 255),
                            (Colors.blue(colour) * alpha + 255 * (1 - alpha)).toInt().coerceIn(0, 255),
                        )
                    }
                    image.setRGB(0, y, pixmap.width, 1, row, 0, pixmap.width)
                }
            }
        }
        val name = when (format) {
            ImageFormat.JPEG -> "jpeg"
            ImageFormat.WEBP -> "png"
            else -> format.extension
        }
        val out = ByteArrayOutputStream(pixmap.pixels.size / 2 + 64)
        val written = try {
            synchronized(lock) { ImageIO.write(image, name, out) }
        } catch (error: Exception) {
            return null
        }
        return if (written) out.toByteArray() else null
    }

    override fun sizeOf(data: ByteArray): IntArray? {
        val image = readImage(data) ?: return null
        return intArrayOf(image.width, image.height)
    }

    private fun readImage(data: ByteArray): BufferedImage? {
        return try {
            synchronized(lock) {
                ImageIO.read(ByteArrayInputStream(data))
            }
        } catch (error: Exception) {
            null
        }
    }
}
