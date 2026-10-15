package com.strata.editor.core

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Destructive filters: the effects that rewrite pixels rather than describing a
 * change. Each one takes the pixmap, the region to touch, and its parameters, and
 * is applied inside a recorded edit so it can be undone.
 *
 * Everything works on straight-alpha ARGB. Filters that blur sample in
 * premultiplied space and divide back out, otherwise transparent pixels bleed
 * black into the edges of a layer.
 */
object Filters {

    /* ------------------------------------------------------------- blur */

    /**
     * Separable box blur, applied three times — the classic cheap approximation of
     * a Gaussian. Cost is independent of the radius.
     */
    fun blur(source: Pixmap, region: Rect, radius: Float) {
        val r = radius.coerceAtLeast(0f)
        if (r < 0.4f) return
        val target = region.clampTo(source.width, source.height)
        if (target.isEmpty) return
        val passes = when {
            r <= 1f -> 1
            r <= 3f -> 2
            else -> 3
        }
        val boxes = boxSizesFor(r, passes)
        val temp = Pixmap(source.width, source.height, source.pixels.copyOf())
        for (size in boxes) {
            if (size < 1) continue
            boxBlur(temp, target, size)
        }
        System.arraycopy(temp.pixels, 0, source.pixels, 0, source.pixels.size)
    }

    private fun boxSizesFor(sigma: Float, passes: Int): IntArray {
        val ideal = sqrt((12f * sigma * sigma / passes) + 1f).toInt().coerceAtLeast(1)
        return IntArray(passes) { ideal }
    }

    /** One box-blur pass (horizontal then vertical) over `region`, with edge clamping. */
    private fun boxBlur(pixmap: Pixmap, region: Rect, radius: Int) {
        val w = pixmap.width
        val h = pixmap.height
        val window = radius * 2 + 1
        val out = IntArray(region.w * region.h)

        // ---- horizontal, in premultiplied space
        val row = IntArray(region.w)
        val rowA = IntArray(region.w)
        for (y in region.y until region.bottom) {
            val base = y * w
            var sa = 0L; var sr = 0L; var sg = 0L; var sb = 0L
            for (i in -radius..radius) {
                val x = (region.x + i).coerceIn(0, w - 1)
                val p = pixmap.pixels[base + x]
                val a = Colors.alpha(p)
                sa += a; sr += Colors.red(p) * a; sg += Colors.green(p) * a; sb += Colors.blue(p) * a
            }
            for (x in region.x until region.right) {
                val p = if (sa == 0L) 0 else Colors.pack(
                    (sa / window).toInt().coerceIn(0, 255),
                    (sr / sa).toInt().coerceIn(0, 255),
                    (sg / sa).toInt().coerceIn(0, 255),
                    (sb / sa).toInt().coerceIn(0, 255),
                )
                row[x - region.x] = p
                rowA[x - region.x] = (sa / window).toInt().coerceIn(0, 255)
                val removeX = (x - radius).coerceIn(0, w - 1)
                val addX = (x + radius + 1).coerceIn(0, w - 1)
                val rem = pixmap.pixels[base + removeX]
                val add = pixmap.pixels[base + addX]
                val ra = Colors.alpha(rem)
                val aa = Colors.alpha(add)
                sa += aa - ra
                sr += Colors.red(add) * aa - Colors.red(rem) * ra
                sg += Colors.green(add) * aa - Colors.green(rem) * ra
                sb += Colors.blue(add) * aa - Colors.blue(rem) * ra
            }
            System.arraycopy(row, 0, out, (y - region.y) * region.w, region.w)
        }

        // ---- vertical, using the horizontals we just computed
        val col = IntArray(region.h)
        for (x in 0 until region.w) {
            var sa = 0L; var sr = 0L; var sg = 0L; var sb = 0L
            for (i in -radius..radius) {
                val y = (region.y + i).coerceIn(region.y, region.bottom - 1)
                val p = out[(y - region.y) * region.w + x]
                val a = Colors.alpha(p)
                sa += a; sr += Colors.red(p) * a; sg += Colors.green(p) * a; sb += Colors.blue(p) * a
            }
            for (y in region.y until region.bottom) {
                val value = if (sa == 0L) 0 else Colors.pack(
                    (sa / window).toInt().coerceIn(0, 255),
                    (sr / sa).toInt().coerceIn(0, 255),
                    (sg / sa).toInt().coerceIn(0, 255),
                    (sb / sa).toInt().coerceIn(0, 255),
                )
                col[y - region.y] = value
                val rem = out[((y - radius).coerceIn(region.y, region.bottom - 1) - region.y) * region.w + x]
                val add = out[((y + radius + 1).coerceIn(region.y, region.bottom - 1) - region.y) * region.w + x]
                val ra = Colors.alpha(rem)
                val aa = Colors.alpha(add)
                sa += aa - ra
                sr += Colors.red(add) * aa - Colors.red(rem) * ra
                sg += Colors.green(add) * aa - Colors.green(rem) * ra
                sb += Colors.blue(add) * aa - Colors.blue(rem) * ra
            }
            for (y in region.y until region.bottom) {
                pixmap.pixels[y * w + x] = col[y - region.y]
            }
        }
    }

    /** Directional blur: `angleDeg` is the direction of the smear. */
    fun motionBlur(source: Pixmap, region: Rect, distance: Float, angleDeg: Float) {
        val d = distance.coerceIn(0f, 500f)
        if (d < 1f) return
        val target = region.clampTo(source.width, source.height)
        if (target.isEmpty) return
        val angle = Math.toRadians(angleDeg.toDouble())
        val dx = cos(angle).toFloat()
        val dy = kotlin.math.sin(angle).toFloat()
        val steps = d.toInt().coerceIn(1, 64)
        val original = source.pixels.copyOf()
        val src = Pixmap(source.width, source.height, original)
        for (y in target.y until target.bottom) {
            for (x in target.x until target.right) {
                var sa = 0L; var sr = 0L; var sg = 0L; var sb = 0L
                var count = 0
                for (s in 0 until steps) {
                    val t = (s / (steps - 1f).coerceAtLeast(1f) - 0.5f) * d
                    val px = (x + dx * t).toInt()
                    val py = (y + dy * t).toInt()
                    if (px < 0 || py < 0 || px >= src.width || py >= src.height) continue
                    val p = src.pixels[py * src.width + px]
                    val a = Colors.alpha(p)
                    sa += a; sr += Colors.red(p) * a; sg += Colors.green(p) * a; sb += Colors.blue(p) * a
                    count++
                }
                if (count == 0 || sa == 0L) {
                    source.pixels[y * source.width + x] = 0
                } else {
                    source.pixels[y * source.width + x] = Colors.pack(
                        (sa / count).toInt().coerceIn(0, 255),
                        (sr / sa).toInt().coerceIn(0, 255),
                        (sg / sa).toInt().coerceIn(0, 255),
                        (sb / sa).toInt().coerceIn(0, 255),
                    )
                }
            }
        }
    }

    /** Radial zoom blur around a point. */
    fun zoomBlur(source: Pixmap, region: Rect, amount: Float, centerX: Float, centerY: Float) {
        val strength = amount.coerceIn(0f, 1f)
        if (strength <= 0.001f) return
        val target = region.clampTo(source.width, source.height)
        if (target.isEmpty) return
        val src = Pixmap(source.width, source.height, source.pixels.copyOf())
        val steps = 10
        for (y in target.y until target.bottom) {
            for (x in target.x until target.right) {
                var sa = 0L; var sr = 0L; var sg = 0L; var sb = 0L
                for (s in 0 until steps) {
                    val t = 1f - strength * (s / (steps - 1f))
                    val px = (centerX + (x - centerX) * t).toInt()
                    val py = (centerY + (y - centerY) * t).toInt()
                    if (px < 0 || py < 0 || px >= src.width || py >= src.height) continue
                    val p = src.pixels[py * src.width + px]
                    val a = Colors.alpha(p)
                    sa += a; sr += Colors.red(p) * a; sg += Colors.green(p) * a; sb += Colors.blue(p) * a
                }
                source.pixels[y * source.width + x] = if (sa == 0L) 0 else Colors.pack(
                    (sa / steps).toInt().coerceIn(0, 255),
                    (sr / sa).toInt().coerceIn(0, 255),
                    (sg / sa).toInt().coerceIn(0, 255),
                    (sb / sa).toInt().coerceIn(0, 255),
                )
            }
        }
    }

    /* ---------------------------------------------------------- sharpen */

    /** Unsharp mask: subtracting a blurred copy is what makes edges crisp. */
    fun unsharpMask(source: Pixmap, region: Rect, radius: Float, amount: Float, threshold: Int) {
        if (amount <= 0.001f) return
        val target = region.clampTo(source.width, source.height)
        if (target.isEmpty) return
        val blurred = Pixmap(source.width, source.height, source.pixels.copyOf())
        blur(blurred, target, radius)
        for (y in target.y until target.bottom) {
            for (x in target.x until target.right) {
                val index = y * source.width + x
                val original = source.pixels[index]
                val a = Colors.alpha(original)
                if (a == 0) continue
                val soft = blurred.pixels[index]
                fun channel(value: Int, blurredValue: Int): Int {
                    val diff = value - blurredValue
                    // Threshold keeps flat areas (and their noise) untouched.
                    if (abs(diff) < threshold) return value
                    return (value + diff * amount).toInt().coerceIn(0, 255)
                }
                source.pixels[index] = Colors.pack(
                    a,
                    channel(Colors.red(original), Colors.red(soft)),
                    channel(Colors.green(original), Colors.green(soft)),
                    channel(Colors.blue(original), Colors.blue(soft)),
                )
            }
        }
    }

    /** Sobel edge detection, either as white lines on black or as a hard mask. */
    fun edgeDetect(source: Pixmap, region: Rect, invert: Boolean = false) {
        val target = region.clampTo(source.width, source.height)
        if (target.isEmpty) return
        val src = Pixmap(source.width, source.height, source.pixels.copyOf())
        for (y in target.y until target.bottom) {
            for (x in target.x until target.right) {
                val a = Colors.alpha(src.pixels[y * src.width + x])
                if (a == 0) continue
                var gx = 0
                var gy = 0
                for (ky in -1..1) {
                    for (kx in -1..1) {
                        val weightX = kx * (if (ky == 0) 2 else 1)
                        val weightY = ky * (if (kx == 0) 2 else 1)
                        val lum = Colors.luminance(src.get(x + kx, y + ky))
                        gx += lum * weightX
                        gy += lum * weightY
                    }
                }
                val magnitude = (sqrt((gx * gx + gy * gy).toDouble())).toInt().coerceIn(0, 255)
                val value = if (invert) 255 - magnitude else magnitude
                source.pixels[y * source.width + x] = Colors.pack(a, value, value, value)
            }
        }
    }

    /** Emboss: a directional difference, biased to mid-grey. */
    fun emboss(source: Pixmap, region: Rect, strength: Float = 1f) {
        val target = region.clampTo(source.width, source.height)
        if (target.isEmpty) return
        val src = Pixmap(source.width, source.height, source.pixels.copyOf())
        val s = strength.coerceIn(0.1f, 4f)
        for (y in target.y until target.bottom) {
            for (x in target.x until target.right) {
                val a = Colors.alpha(src.pixels[y * src.width + x])
                if (a == 0) continue
                val topLeft = Colors.luminance(src.get(x - 1, y - 1))
                val bottomRight = Colors.luminance(src.get(x + 1, y + 1))
                val value = (128f + (topLeft - bottomRight) * s).toInt().coerceIn(0, 255)
                source.pixels[y * source.width + x] = Colors.pack(a, value, value, value)
            }
        }
    }

    /* ------------------------------------------------------------ noise */

    /** Adds monochrome or colour noise; `monochrome` keeps the grain neutral. */
    fun addNoise(source: Pixmap, region: Rect, amount: Float, monochrome: Boolean, seed: Long) {
        val target = region.clampTo(source.width, source.height)
        if (target.isEmpty || amount <= 0f) return
        val random = java.util.Random(seed)
        val strength = amount.coerceIn(0f, 100f) / 100f * 128f
        for (y in target.y until target.bottom) {
            for (x in target.x until target.right) {
                val index = y * source.width + x
                val color = source.pixels[index]
                val a = Colors.alpha(color)
                if (a == 0) continue
                val noise = ((random.nextFloat() * 2f - 1f) * strength).toInt()
                val noiseG = if (monochrome) noise else ((random.nextFloat() * 2f - 1f) * strength).toInt()
                val noiseB = if (monochrome) noise else ((random.nextFloat() * 2f - 1f) * strength).toInt()
                source.pixels[index] = Colors.pack(
                    a,
                    (Colors.red(color) + noise).coerceIn(0, 255),
                    (Colors.green(color) + noiseG).coerceIn(0, 255),
                    (Colors.blue(color) + noiseB).coerceIn(0, 255),
                )
            }
        }
    }

    /** A film-grain look: noise that fades out of the deepest shadows. */
    fun grain(source: Pixmap, region: Rect, amount: Float, seed: Long) {
        val target = region.clampTo(source.width, source.height)
        if (target.isEmpty || amount <= 0f) return
        val random = java.util.Random(seed)
        for (y in target.y until target.bottom) {
            for (x in target.x until target.right) {
                val index = y * source.width + x
                val color = source.pixels[index]
                val a = Colors.alpha(color)
                if (a == 0) continue
                val lum = Colors.luminance(color) / 255f
                val weight = (1f - abs(lum - 0.5f) * 2f).coerceIn(0f, 1f)
                val noise = ((random.nextFloat() * 2f - 1f) * amount * 0.6f * (0.4f + weight)).toInt()
                source.pixels[index] = Colors.pack(
                    a,
                    (Colors.red(color) + noise).coerceIn(0, 255),
                    (Colors.green(color) + noise).coerceIn(0, 255),
                    (Colors.blue(color) + noise).coerceIn(0, 255),
                )
            }
        }
    }

    /* ----------------------------------------------------- stylise / pixel */

    fun mosaic(source: Pixmap, region: Rect, cellSize: Int) {
        val target = region.clampTo(source.width, source.height)
        if (target.isEmpty) return
        val size = cellSize.coerceIn(2, 512)
        var y = target.y
        while (y < target.bottom) {
            var x = target.x
            val yEnd = min(y + size, target.bottom)
            while (x < target.right) {
                val xEnd = min(x + size, target.right)
                var a = 0L; var r = 0L; var g = 0L; var b = 0L
                var count = 0
                for (yy in y until yEnd) {
                    for (xx in x until xEnd) {
                        val p = source.pixels[yy * source.width + xx]
                        val pa = Colors.alpha(p)
                        a += pa
                        r += Colors.red(p) * pa
                        g += Colors.green(p) * pa
                        b += Colors.blue(p) * pa
                        count++
                    }
                }
                val packed = if (count == 0 || a == 0L) 0 else Colors.pack(
                    (a / count).toInt().coerceIn(0, 255),
                    (r / a).toInt().coerceIn(0, 255),
                    (g / a).toInt().coerceIn(0, 255),
                    (b / a).toInt().coerceIn(0, 255),
                )
                for (yy in y until yEnd) {
                    java.util.Arrays.fill(source.pixels, yy * source.width + x, yy * source.width + xEnd, packed)
                }
                x = xEnd
            }
            y = yEnd
        }
    }

    /** Vignette: darkens (or lightens) the edges of the region. */
    fun vignette(source: Pixmap, region: Rect, amount: Float, softness: Float, roundness: Float) {
        val target = region.clampTo(source.width, source.height)
        if (target.isEmpty) return
        val cx = region.x + region.w / 2f
        val cy = region.y + region.h / 2f
        val maxDistance = sqrt((cx - region.x).toDouble().let { it * it } + (cy - region.y).toDouble().let { it * it }).toFloat()
        val strength = amount.coerceIn(-1f, 1f)
        val soft = softness.coerceIn(0.01f, 1f)
        for (y in target.y until target.bottom) {
            for (x in target.x until target.right) {
                val index = y * source.width + x
                val color = source.pixels[index]
                val a = Colors.alpha(color)
                if (a == 0) continue
                val dx = (x - cx) / maxDistance
                val dy = (y - cy) / maxDistance * (1f / roundness.coerceIn(0.1f, 4f))
                val distance = min(1f, sqrt((dx * dx + dy * dy).toDouble()).toFloat())
                val t = ((distance - (1f - soft)) / soft).coerceIn(0f, 1f)
                val factor = 1f + strength * t
                source.pixels[index] = if (factor >= 0f) {
                    Colors.pack(
                        a,
                        (Colors.red(color) * factor).toInt().coerceIn(0, 255),
                        (Colors.green(color) * factor).toInt().coerceIn(0, 255),
                        (Colors.blue(color) * factor).toInt().coerceIn(0, 255),
                    )
                } else {
                    Colors.pack(a, 0, 0, 0)
                }
            }
        }
    }

    /** Oil-paint style: each pixel takes the colour of the most common level nearby. */
    fun oilPaint(source: Pixmap, region: Rect, radius: Int, levels: Int = 24) {
        val target = region.clampTo(source.width, source.height)
        if (target.isEmpty) return
        val r = radius.coerceIn(1, 12)
        val binCount = levels.coerceIn(4, 64)
        val src = Pixmap(source.width, source.height, source.pixels.copyOf())
        val counts = IntArray(binCount)
        val sumR = IntArray(binCount)
        val sumG = IntArray(binCount)
        val sumB = IntArray(binCount)
        val sumA = IntArray(binCount)
        for (y in target.y until target.bottom) {
            for (x in target.x until target.right) {
                java.util.Arrays.fill(counts, 0)
                java.util.Arrays.fill(sumR, 0); java.util.Arrays.fill(sumG, 0)
                java.util.Arrays.fill(sumB, 0); java.util.Arrays.fill(sumA, 0)
                for (dy in -r..r) {
                    for (dx in -r..r) {
                        val px = x + dx
                        val py = y + dy
                        if (px < 0 || py < 0 || px >= src.width || py >= src.height) continue
                        val p = src.pixels[py * src.width + px]
                        val a = Colors.alpha(p)
                        if (a == 0) continue
                        val bin = (Colors.luminance(p) * (binCount - 1) / 255f).toInt().coerceIn(0, binCount - 1)
                        counts[bin]++
                        sumR[bin] += Colors.red(p); sumG[bin] += Colors.green(p)
                        sumB[bin] += Colors.blue(p); sumA[bin] += a
                    }
                }
                var best = 0
                for (i in 1 until binCount) if (counts[i] > counts[best]) best = i
                val n = counts[best]
                source.pixels[y * source.width + x] = if (n == 0) 0 else Colors.pack(
                    sumA[best] / n, sumR[best] / n, sumG[best] / n, sumB[best] / n,
                )
            }
        }
    }

    /** A simple "glow": the bright parts of the image, blurred and added back. */
    fun glow(source: Pixmap, region: Rect, radius: Float, amount: Float, threshold: Int = 180) {
        val target = region.clampTo(source.width, source.height)
        if (target.isEmpty) return
        val highlights = Pixmap(source.width, source.height, source.pixels.copyOf())
        for (y in target.y until target.bottom) {
            for (x in target.x until target.right) {
                val index = y * source.width + x
                val color = highlights.pixels[index]
                val a = Colors.alpha(color)
                val lum = Colors.luminance(color)
                highlights.pixels[index] = if (a == 0 || lum < threshold) 0 else color
            }
        }
        blur(highlights, target, radius)
        for (y in target.y until target.bottom) {
            for (x in target.x until target.right) {
                val index = y * source.width + x
                val base = source.pixels[index]
                val added = highlights.pixels[index]
                if (added == 0) continue
                source.pixels[index] = Colors.over(base, Colors.scaleAlpha(added, amount))
            }
        }
    }

    /** Colour halftone-ish pixel art: posterise and mosaic combined. */
    fun crystalize(source: Pixmap, region: Rect, cellSize: Int) {
        mosaic(source, region, cellSize.coerceAtLeast(4))
        val posterize = Adjustment.Posterize(12)
        posterize.apply(source, region)
    }

    /** Solarise: invert everything above a threshold. */
    fun solarize(source: Pixmap, region: Rect, level: Int = 128) {
        val target = region.clampTo(source.width, source.height)
        val limit = level.coerceIn(0, 255)
        for (y in target.y until target.bottom) {
            for (x in target.x until target.right) {
                val index = y * source.width + x
                val color = source.pixels[index]
                val a = Colors.alpha(color)
                if (a == 0) continue
                fun channel(value: Int) = if (value >= limit) 255 - value else value
                source.pixels[index] = Colors.pack(
                    a,
                    channel(Colors.red(color)),
                    channel(Colors.green(color)),
                    channel(Colors.blue(color)),
                )
            }
        }
    }

    /** Converts to a sepia tone, keeping the original luminance. */
    fun sepia(source: Pixmap, region: Rect, amount: Float = 1f) {
        val target = region.clampTo(source.width, source.height)
        val f = amount.coerceIn(0f, 1f)
        for (y in target.y until target.bottom) {
            for (x in target.x until target.right) {
                val index = y * source.width + x
                val color = source.pixels[index]
                val a = Colors.alpha(color)
                if (a == 0) continue
                val r = Colors.red(color)
                val g = Colors.green(color)
                val b = Colors.blue(color)
                val sepiaR = (0.393f * r + 0.769f * g + 0.189f * b).toInt().coerceIn(0, 255)
                val sepiaG = (0.349f * r + 0.686f * g + 0.168f * b).toInt().coerceIn(0, 255)
                val sepiaB = (0.272f * r + 0.534f * g + 0.131f * b).toInt().coerceIn(0, 255)
                source.pixels[index] = Colors.pack(
                    a,
                    (r + (sepiaR - r) * f).toInt(),
                    (g + (sepiaG - g) * f).toInt(),
                    (b + (sepiaB - b) * f).toInt(),
                )
            }
        }
    }

    /** A camera-style radial "lens" distortion, positive = barrel, negative = pincushion. */
    fun lensDistortion(source: Pixmap, region: Rect, amount: Float) {
        val target = region.clampTo(source.width, source.height)
        if (target.isEmpty || abs(amount) < 0.001f) return
        val src = Pixmap(source.width, source.height, source.pixels.copyOf())
        val cx = region.x + region.w / 2f
        val cy = region.y + region.h / 2f
        val norm = max(region.w, region.h) / 2f
        for (y in target.y until target.bottom) {
            for (x in target.x until target.right) {
                val dx = (x - cx) / norm
                val dy = (y - cy) / norm
                val r2 = dx * dx + dy * dy
                val factor = 1f + amount * r2
                val sx = cx + dx * factor * norm
                val sy = cy + dy * factor * norm
                source.pixels[y * source.width + x] = if (sx < 0 || sy < 0 || sx >= src.width || sy >= src.height) 0 else src.sampleBilinear(sx, sy)
            }
        }
    }

    /** Twirl around the region centre. */
    fun twirl(source: Pixmap, region: Rect, angleDegrees: Float) {
        val target = region.clampTo(source.width, source.height)
        if (target.isEmpty || abs(angleDegrees) < 0.5f) return
        val src = Pixmap(source.width, source.height, source.pixels.copyOf())
        val cx = region.x + region.w / 2f
        val cy = region.y + region.h / 2f
        val radius = min(region.w, region.h) / 2f
        val maxAngle = Math.toRadians(angleDegrees.toDouble())
        for (y in target.y until target.bottom) {
            for (x in target.x until target.right) {
                val dx = x - cx
                val dy = y - cy
                val distance = sqrt((dx * dx + dy * dy).toDouble())
                if (distance >= radius) {
                    source.pixels[y * source.width + x] = src.pixels[y * src.width + x]
                    continue
                }
                val t = 1f - (distance / radius).toFloat()
                val angle = maxAngle * t * t
                val cosA = kotlin.math.cos(angle).toFloat()
                val sinA = kotlin.math.sin(angle).toFloat()
                val sx = cx + (dx * cosA - dy * sinA)
                val sy = cy + (dx * sinA + dy * cosA)
                source.pixels[y * source.width + x] = if (sx < 0 || sy < 0 || sx >= src.width || sy >= src.height) 0 else src.sampleBilinear(sx, sy)
            }
        }
    }

    /** A per-pixel "posterize the hue" effect, for a graphic look. */
    fun posterizeHue(source: Pixmap, region: Rect, steps: Int = 12) {
        val target = region.clampTo(source.width, source.height)
        if (target.isEmpty) return
        val n = steps.coerceIn(2, 64)
        val hsv = FloatArray(3)
        val rgb = FloatArray(3)
        for (y in target.y until target.bottom) {
            for (x in target.x until target.right) {
                val index = y * source.width + x
                val color = source.pixels[index]
                val a = Colors.alpha(color)
                if (a == 0) continue
                Colors.toHsv(color, hsv)
                val step = 360f / n
                val hue = (Math.round(hsv[0] / step) * step) % 360f
                Colors.fromHsv(hue, hsv[1], hsv[2], rgb)
                source.pixels[index] = Colors.pack(
                    a,
                    (rgb[0] * 255f).toInt().coerceIn(0, 255),
                    (rgb[1] * 255f).toInt().coerceIn(0, 255),
                    (rgb[2] * 255f).toInt().coerceIn(0, 255),
                )
            }
        }
    }

    /** Distance-based "old photo" fade, used by the aging filter. */
    fun fade(source: Pixmap, region: Rect, amount: Float) {
        val target = region.clampTo(source.width, source.height)
        val f = amount.coerceIn(0f, 1f)
        if (f <= 0f) return
        val random = java.util.Random(7L)
        for (y in target.y until target.bottom) {
            for (x in target.x until target.right) {
                val index = y * source.width + x
                val color = source.pixels[index]
                val a = Colors.alpha(color)
                if (a == 0) continue
                val grey = Colors.luminance(color)
                val faded = Colors.pack(
                    a,
                    (Colors.red(color) * (1f - f * 0.35f) + grey * f * 0.2f + 40 * f).toInt().coerceIn(0, 255),
                    (Colors.green(color) * (1f - f * 0.3f) + grey * f * 0.2f + 34 * f).toInt().coerceIn(0, 255),
                    (Colors.blue(color) * (1f - f * 0.2f) + grey * f * 0.2f + 20 * f).toInt().coerceIn(0, 255),
                )
                val scratched = if (random.nextFloat() < 0.002f * f) Colors.scaleChannels(faded, 1.6f) else faded
                source.pixels[index] = scratched
            }
        }
    }

    /** True gaussian falloff weights, used by the sharpen and blur radii previews. */
    fun gaussianWeights(radius: Float, taps: Int = 5): FloatArray {
        val sigma = max(0.1f, radius / 2f)
        val out = FloatArray(taps * 2 + 1)
        var sum = 0f
        for (i in out.indices) {
            val x = (i - taps).toFloat()
            val v = exp((-x * x / (2 * sigma * sigma)).toDouble()).toFloat()
            out[i] = v
            sum += v
        }
        for (i in out.indices) out[i] /= sum
        return out
    }

    /** Small helper for filter previews: how many output pixels a radius affects. */
    fun kernelExtent(radius: Float): Int = ceil(radius * 3f).toInt()
}
