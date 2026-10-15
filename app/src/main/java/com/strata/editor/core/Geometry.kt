package com.strata.editor.core

/**
 * A rectangle in document coordinates.
 *
 * The whole engine speaks in integer document pixels; the canvas scales to
 * screen coordinates only when it draws. `right`/`bottom` are exclusive, so
 * `Rect(0, 0, w, h)` covers exactly `w * h` pixels.
 */
data class Rect(val x: Int, val y: Int, val w: Int, val h: Int) {

    val right: Int get() = x + w
    val bottom: Int get() = y + h

    val isEmpty: Boolean get() = w <= 0 || h <= 0
    val isValid: Boolean get() = w > 0 && h > 0

    /** Pixel count, as a long so a huge selection cannot overflow. */
    val area: Long get() = w.toLong() * h.toLong()

    fun contains(px: Int, py: Int): Boolean = px >= x && py >= y && px < right && py < bottom

    fun contains(other: Rect): Boolean = !other.isEmpty && other.x >= x && other.y >= y && other.right <= right && other.bottom <= bottom

    fun intersects(other: Rect): Boolean = !isEmpty && !other.isEmpty && x < other.right && other.x < right && y < other.bottom && other.y < bottom

    fun intersect(other: Rect): Rect {
        if (!intersects(other)) return EMPTY
        val left = maxOf(x, other.x)
        val top = maxOf(y, other.y)
        val right = minOf(right, other.right)
        val bottom = minOf(bottom, other.bottom)
        return Rect(left, top, right - left, bottom - top)
    }

    fun union(other: Rect): Rect {
        if (isEmpty) return other
        if (other.isEmpty) return this
        val left = minOf(x, other.x)
        val top = minOf(y, other.y)
        val right = maxOf(right, other.right)
        val bottom = maxOf(bottom, other.bottom)
        return Rect(left, top, right - left, bottom - top)
    }

    fun inflate(dx: Int, dy: Int): Rect = Rect(x - dx, y - dy, w + dx * 2, h + dy * 2)

    fun offset(dx: Int, dy: Int): Rect = Rect(x + dx, y + dy, w, h)

    /** Clamps to a document of the given size. */
    fun clampTo(width: Int, height: Int): Rect = intersect(Rect(0, 0, width, height))

    fun scaled(factor: Float): Rect =
        Rect((x * factor).toInt(), (y * factor).toInt(), (w * factor).toInt(), (h * factor).toInt())

    companion object {
        val EMPTY = Rect(0, 0, 0, 0)

        /** The rectangle spanned by two corner points, in any order. */
        fun ofPoints(x0: Int, y0: Int, x1: Int, y1: Int): Rect {
            val left = minOf(x0, x1)
            val top = minOf(y0, y1)
            return Rect(left, top, maxOf(x0, x1) - left, maxOf(y0, y1) - top)
        }

        fun ofLTWH(x: Int, y: Int, w: Int, h: Int): Rect = Rect(x, y, w, h)
    }
}

/**
 * A 2×3 affine matrix, row-major: `[a b tx; c d ty]`.
 *
 * Used for layer transforms (move, scale, rotate, flip) and for the canvas
 * viewport. Kept here rather than using `android.graphics.Matrix` so the engine
 * stays testable off-device.
 */
class Mat3(
    var a: Float = 1f, var b: Float = 0f, var tx: Float = 0f,
    var c: Float = 0f, var d: Float = 1f, var ty: Float = 0f,
) {

    fun copy(): Mat3 = Mat3(a, b, tx, c, d, ty)

    /** `this = this * other` (other applied first). */
    fun postConcat(other: Mat3): Mat3 {
        val na = a * other.a + b * other.c
        val nb = a * other.b + b * other.d
        val ntx = a * other.tx + b * other.ty + tx
        val nc = c * other.a + d * other.c
        val nd = c * other.b + d * other.d
        val nty = c * other.tx + d * other.ty + ty
        a = na; b = nb; tx = ntx; c = nc; d = nd; ty = nty
        return this
    }

    /** `this = other * this` (this applied first). */
    fun preConcat(other: Mat3): Mat3 = other.copy().postConcat(this)

    fun mapX(x: Float, y: Float): Float = a * x + b * y + tx
    fun mapY(x: Float, y: Float): Float = c * x + d * y + ty

    val determinant: Float get() = a * d - b * c

    fun invert(): Mat3? {
        val det = determinant
        if (kotlin.math.abs(det) < 1e-9f) return null
        val ia = d / det
        val ib = -b / det
        val ic = -c / det
        val id = a / det
        val itx = -(ia * tx + ib * ty)
        val ity = -(ic * tx + id * ty)
        return Mat3(ia, ib, itx, ic, id, ity)
    }

    /** Axis-aligned bounds of a transformed rectangle, rounded outwards. */
    fun mapRect(rect: Rect): Rect {
        val corners = floatArrayOf(
            mapX(rect.x.toFloat(), rect.y.toFloat()), mapY(rect.x.toFloat(), rect.y.toFloat()),
            mapX(rect.right.toFloat(), rect.y.toFloat()), mapY(rect.right.toFloat(), rect.y.toFloat()),
            mapX(rect.right.toFloat(), rect.bottom.toFloat()), mapY(rect.right.toFloat(), rect.bottom.toFloat()),
            mapX(rect.x.toFloat(), rect.bottom.toFloat()), mapY(rect.x.toFloat(), rect.bottom.toFloat()),
        )
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        var i = 0
        while (i < corners.size) {
            minX = minOf(minX, corners[i]); maxX = maxOf(maxX, corners[i])
            minY = minOf(minY, corners[i + 1]); maxY = maxOf(maxY, corners[i + 1])
            i += 2
        }
        val left = kotlin.math.floor(minX).toInt()
        val top = kotlin.math.floor(minY).toInt()
        return Rect(left, top, kotlin.math.ceil(maxX).toInt() - left + 1, kotlin.math.ceil(maxY).toInt() - top + 1)
    }

    companion object {
        fun translation(dx: Float, dy: Float) = Mat3(1f, 0f, dx, 0f, 1f, dy)

        fun scale(sx: Float, sy: Float) = Mat3(sx, 0f, 0f, 0f, sy, 0f)

        fun rotation(degrees: Float): Mat3 {
            val r = Math.toRadians(degrees.toDouble())
            val cos = kotlin.math.cos(r).toFloat()
            val sin = kotlin.math.sin(r).toFloat()
            return Mat3(cos, -sin, 0f, sin, cos, 0f)
        }

        /** Rotation about a pivot point. */
        fun rotationAbout(degrees: Float, px: Float, py: Float): Mat3 =
            translation(px, py).postConcat(rotation(degrees)).postConcat(translation(-px, -py))
    }
}
