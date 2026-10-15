package com.strata.editor.core

/**
 * Importing and exporting pixels.
 *
 * The engine itself never touches a JPEG: [ImageCodec] is the seam. On a phone the
 * implementation is `android.graphics` (fast, hardware-backed, knows about EXIF and
 * subsampling); in the JVM tests it is `javax.imageio`. Nothing in this file needs
 * either, which is what keeps the engine testable off-device.
 */
enum class ImageFormat(val extension: String, val mime: String, val keepsAlpha: Boolean) {
    PNG("png", "image/png", true),
    JPEG("jpg", "image/jpeg", false),
    WEBP("webp", "image/webp", true),
    BMP("bmp", "image/bmp", false),
    TIFF("tiff", "image/tiff", true),
    ;

    companion object {
        fun ofFile(name: String): ImageFormat? {
            val lower = name.substringAfterLast('.', "").lowercase()
            return entries.firstOrNull { it.extension == lower || (lower == "jpeg" && it == JPEG) || (lower == "tif" && it == TIFF) }
        }

        fun ofMime(mime: String?): ImageFormat? = when (mime?.lowercase()) {
            "image/png" -> PNG
            "image/jpeg", "image/jpg" -> JPEG
            "image/webp" -> WEBP
            "image/bmp", "image/x-ms-bmp" -> BMP
            "image/tiff" -> TIFF
            else -> null
        }
    }
}

/** Whatever the platform can do with image bytes. */
interface ImageCodec {
    /** Decodes to straight-alpha ARGB, already rotated the way the file asks. */
    fun decode(data: ByteArray, maxPixels: Int): Pixmap?

    /** Encodes; `quality` is 0–100 and only meaningful for lossy formats. */
    fun encode(pixmap: Pixmap, format: ImageFormat, quality: Int): ByteArray?

    /** Pixel size without a full decode, or null when the format cannot say. */
    fun sizeOf(data: ByteArray): IntArray? = null

    /** Rotation in degrees clockwise that the file asks for, for display. */
    fun orientation(data: ByteArray): Int = 0
}

object ImageIo {

    /** Installed by the platform layer; null under a bare JVM until a test sets it. */
    var codec: ImageCodec? = null

    const val MAX_IMPORT_PIXELS = 32_000_000

    class Result(val pixmap: Pixmap, val format: ImageFormat?, val originalWidth: Int, val originalHeight: Int) {
        val scaled: Boolean get() = pixmap.width != originalWidth || pixmap.height != originalHeight
    }

    /** Reads image bytes, refusing anything larger than the import budget at full size. */
    fun decode(data: ByteArray, fileName: String = "", maxPixels: Int = MAX_IMPORT_PIXELS): Result? {
        val implementation = codec ?: return null
        if (data.isEmpty()) return null
        val size = implementation.sizeOf(data)
        val pixmap = implementation.decode(data, maxPixels) ?: return null
        return Result(pixmap, ImageFormat.ofFile(fileName) ?: ImageFormat.ofMime(null), size?.getOrNull(0) ?: pixmap.width, size?.getOrNull(1) ?: pixmap.height)
    }

    fun encode(pixmap: Pixmap, format: ImageFormat, quality: Int = 90): ByteArray? {
        val implementation = codec ?: return null
        return implementation.encode(pixmap, format, quality.coerceIn(1, 100))
    }

    /** Encodes a document the way a viewer would see it. */
    fun exportDocument(document: Document, format: ImageFormat, quality: Int = 90, background: Int = Colors.WHITE): ByteArray? {
        val flat = Pixmap(document.width, document.height)
        Compositor.flatten(document, flat, if (format.keepsAlpha) 0 else background)
        return encode(flat, format, quality)
    }

    /** Makes an image file into a layer, centred on the canvas and never enlarged. */
    fun layerFrom(document: Document, data: ByteArray, fileName: String = "", maxPixels: Int = MAX_IMPORT_PIXELS): Layer? {
        val decoded = decode(data, fileName, maxPixels) ?: return null
        val pixmap = decoded.pixmap
        val layer = Layer(fileName.substringBeforeLast('.').ifEmpty { "Image" })
        layer.pixels = pixmap
        layer.x = ((document.width - pixmap.width) / 2).coerceAtLeast(0).coerceAtMost((document.width - pixmap.width).coerceAtLeast(0))
        layer.y = ((document.height - pixmap.height) / 2).coerceAtLeast(0)
        if (decoded.scaled) layer.note = "imported at ${pixmap.width}×${pixmap.height} from ${decoded.originalWidth}×${decoded.originalHeight}"
        return layer
    }

    /**
     * Trims a flat colour border, the way a "trim" command does. Used after a scan
     * or a screenshot import, where the interesting part is usually in the middle.
     */
    fun trim(pixmap: Pixmap, tolerance: Int = 12): Rect {
        val corner = pixmap[0, 0]
        fun near(colour: Int): Boolean {
            return kotlin.math.abs(Colors.red(colour) - Colors.red(corner)) <= tolerance &&
                kotlin.math.abs(Colors.green(colour) - Colors.green(corner)) <= tolerance &&
                kotlin.math.abs(Colors.blue(colour) - Colors.blue(corner)) <= tolerance &&
                kotlin.math.abs(Colors.alpha(colour) - Colors.alpha(corner)) <= tolerance
        }

        var left = pixmap.width
        var right = -1
        var top = pixmap.height
        var bottom = -1
        for (y in 0 until pixmap.height) {
            for (x in 0 until pixmap.width) {
                if (!near(pixmap[x, y])) {
                    if (x < left) left = x
                    if (x > right) right = x
                    if (y < top) top = y
                    if (y > bottom) bottom = y
                }
            }
        }
        if (right < left || bottom < top) return Rect(0, 0, 0, 0)
        return Rect(left, top, right - left + 1, bottom - top + 1)
    }
}
