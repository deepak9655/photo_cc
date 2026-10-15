package com.strata.editor.platform

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import com.strata.editor.core.Colors
import com.strata.editor.core.ImageCodec
import com.strata.editor.core.ImageFormat
import com.strata.editor.core.ImageIo
import com.strata.editor.core.Pixmap
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * The phone's image codec: `android.graphics` decoding and encoding, with the
 * file's own EXIF rotation applied so a photo taken sideways arrives upright.
 *
 * Two things matter here. Decoding uses `inSampleSize` so a 50 MPix camera photo
 * never has to exist at full size in memory on the way to something smaller, and
 * every bitmap this class creates is recycled — a phone editing a document that is
 * already hundreds of megabytes cannot afford to leak a full-size bitmap per
 * import.
 */
class AndroidImageCodec : ImageCodec {

    override fun decode(data: ByteArray, maxPixels: Int): Pixmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try {
            BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        } catch (error: Exception) {
            return null
        } catch (error: OutOfMemoryError) {
            return null
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, maxPixels)
        }
        val decoded = try {
            BitmapFactory.decodeByteArray(data, 0, data.size, options)
        } catch (error: OutOfMemoryError) {
            null
        } catch (error: Exception) {
            null
        } ?: return null

        val upright = applyOrientation(decoded, exifOrientation(data))
        val pixmap = Pixmap(upright.width, upright.height)
        upright.getPixels(pixmap.pixels, 0, upright.width, 0, 0, upright.width, upright.height)
        if (upright !== decoded) upright.recycle()
        decoded.recycle()
        return pixmap
    }

    override fun encode(pixmap: Pixmap, format: ImageFormat, quality: Int): ByteArray? {
        if (pixmap.width <= 0 || pixmap.height <= 0) return null
        val target = if (format.keepsAlpha) {
            Bitmap.createBitmap(pixmap.pixels, pixmap.width, pixmap.height, Bitmap.Config.ARGB_8888)
        } else {
            // JPEG has nowhere to put transparency: composite over white first.
            val flattened = IntArray(pixmap.pixels.size)
            for (i in flattened.indices) {
                val colour = pixmap.pixels[i]
                val alpha = Colors.alpha(colour) / 255f
                flattened[i] = if (alpha >= 1f) {
                    colour or (0xFF shl 24)
                } else {
                    Colors.pack(
                        255,
                        (Colors.red(colour) * alpha + 255 * (1 - alpha)).toInt().coerceIn(0, 255),
                        (Colors.green(colour) * alpha + 255 * (1 - alpha)).toInt().coerceIn(0, 255),
                        (Colors.blue(colour) * alpha + 255 * (1 - alpha)).toInt().coerceIn(0, 255),
                    )
                }
            }
            Bitmap.createBitmap(flattened, pixmap.width, pixmap.height, Bitmap.Config.ARGB_8888)
        } ?: return null

        val out = ByteArrayOutputStream(pixmap.pixels.size / 4 + 1024)
        val formatValue = when (format) {
            ImageFormat.PNG -> Bitmap.CompressFormat.PNG
            ImageFormat.JPEG -> Bitmap.CompressFormat.JPEG
            ImageFormat.WEBP -> Bitmap.CompressFormat.WEBP
            ImageFormat.BMP -> Bitmap.CompressFormat.PNG
            ImageFormat.TIFF -> Bitmap.CompressFormat.PNG
        }
        val written = try {
            target.compress(formatValue, quality.coerceIn(1, 100), out)
        } catch (error: Exception) {
            false
        }
        target.recycle()
        return if (written) out.toByteArray() else null
    }

    override fun sizeOf(data: ByteArray): IntArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        return try {
            BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) null else intArrayOf(bounds.outWidth, bounds.outHeight)
        } catch (error: Exception) {
            null
        }
    }

    override fun orientation(data: ByteArray): Int = exifOrientation(data)

    /** A power of two, as `inSampleSize` requires. */
    private fun sampleSizeFor(width: Int, height: Int, maxPixels: Int): Int {
        var sample = 1
        while (width / sample.toLong() * (height / sample) > maxPixels && sample < 32) sample *= 2
        return sample
    }

    private fun exifOrientation(data: ByteArray): Int {
        return try {
            ByteArrayInputStream(data).use { stream ->
                val exif = ExifInterface(stream)
                exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            }
        } catch (error: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }
    }

    private fun applyOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postScale(-1f, 1f)
                matrix.postRotate(90f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postScale(-1f, 1f)
                matrix.postRotate(270f)
            }
            else -> return bitmap
        }
        return try {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true) ?: bitmap
        } catch (error: OutOfMemoryError) {
            bitmap
        }
    }
}
