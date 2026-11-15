// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.synth

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 2.0-cam2: rectified synthetic document pages for the noise and alignment benchmarks. Glyph-like
 * text (2-3 px strokes in 12 x 16 px cells, a bold heading, a grey box) on paper with soft shading,
 * rendered with 3x3 supersampling. Fixed seeds; nothing is committed.
 */
object DocPages {

    class Page(val w: Int, val h: Int, val px: IntArray, /** Ground-truth ink (true = ink) at pixel centres. */ val ink: BooleanArray)

    /** One page; [skewDeg] turns the text (not the paper) about the page centre. */
    fun page(seed: Int, w: Int = 1200, h: Int = 1600, skewDeg: Float = 0f, shade: Boolean = true, colour: Boolean = true): Page {
        val rnd = java.util.Random(seed.toLong())
        val cellW = 12f * w / 1200f
        val cellH = 16f * w / 1200f
        val stroke = 2.4f * w / 1200f
        // Glyph shapes: each a set of strokes in a unit cell (x0, y0, x1, y1).
        val shapes = List(40) {
            List(2 + rnd.nextInt(3)) {
                when (rnd.nextInt(4)) {
                    0 -> floatArrayOf(0.2f + 0.6f * rnd.nextFloat(), 0.1f, 0.2f + 0.6f * rnd.nextFloat(), 0.9f)
                    1 -> floatArrayOf(0.1f, 0.2f + 0.6f * rnd.nextFloat(), 0.9f, 0.2f + 0.6f * rnd.nextFloat())
                    2 -> floatArrayOf(0.15f, 0.85f, 0.85f, 0.15f)
                    else -> floatArrayOf(0.5f, 0.75f, 0.5f, 0.78f)
                }
            }
        }
        data class Glyph(val x: Float, val y: Float, val shape: Int, val bold: Boolean)
        val glyphs = ArrayList<Glyph>()
        val left = 0.08f * w; val right = 0.92f * w
        var y = 0.07f * h
        // Heading.
        run {
            var x = left + 0.2f * w
            while (x < right - 0.2f * w) { glyphs += Glyph(x, y, rnd.nextInt(40), true); x += cellW * 1.6f }
            y += cellH * 3f
        }
        while (y < 0.92f * h) {
            if (rnd.nextInt(8) == 0) { y += cellH; continue }
            val end = if (rnd.nextInt(6) == 0) left + (right - left) * (0.3f + 0.5f * rnd.nextFloat()) else right
            var x = left
            while (x < end - cellW) {
                val wordLen = 2 + rnd.nextInt(7)
                for (k in 0 until wordLen) {
                    if (x >= end - cellW) break
                    glyphs += Glyph(x, y, rnd.nextInt(40), false); x += cellW
                }
                x += cellW * 0.8f
            }
            y += cellH * 1.9f
        }
        // Index glyphs by grid cell for speed.
        val bandH = cellH * 2
        val bands = HashMap<Long, MutableList<Glyph>>()
        fun key(bx: Int, by: Int) = by.toLong() * 100000L + bx
        for (gl in glyphs) {
            val gw = if (gl.bold) cellW * 1.6f else cellW
            val gh = if (gl.bold) cellH * 1.6f else cellH
            for (b in (gl.y / bandH).toInt()..((gl.y + gh) / bandH).toInt()) {
                for (c in (gl.x / cellW).toInt()..((gl.x + gw) / cellW).toInt()) bands.getOrPut(key(c, b)) { ArrayList() } += gl
            }
        }
        val box = floatArrayOf(0.6f * w, 0.8f * h, 0.85f * w, 0.86f * h) // a grey table cell
        val rad = Math.toRadians(skewDeg.toDouble())
        val ca = cos(rad).toFloat(); val sa = sin(rad).toFloat()
        val cx = w / 2f; val cy = h / 2f
        fun inkAt(px0: Float, py0: Float): Int {
            // Turn the sample point back by the skew: content is turned by +skew.
            val dx = px0 - cx; val dy = py0 - cy
            val px = cx + ca * dx + sa * dy
            val py = cy - sa * dx + ca * dy
            if (px >= box[0] && px <= box[2] && py >= box[1] && py <= box[3]) return 2
            if (px < 0f || py < 0f) return 0
            val list = bands[key((px / cellW).toInt(), (py / bandH).toInt())] ?: return 0
            for (gl in list) {
                val gw = if (gl.bold) cellW * 1.6f else cellW
                val gh = if (gl.bold) cellH * 1.6f else cellH
                if (px < gl.x || px > gl.x + gw || py < gl.y || py > gl.y + gh) continue
                val u = (px - gl.x) / gw; val v = (py - gl.y) / gh
                val sw = (if (gl.bold) stroke * 1.6f else stroke) / gw
                for (s in shapes[gl.shape]) {
                    if (segDist(u, v * gh / gw, s[0], s[1] * gh / gw, s[2], s[3] * gh / gw) <= sw / 2) return 1
                }
            }
            return 0
        }
        val px = IntArray(w * h)
        val ink = BooleanArray(w * h)
        val inkCol = if (colour && rnd.nextBoolean()) intArrayOf(30, 40, 120) else intArrayOf(38, 38, 42)
        for (yy in 0 until h) for (xx in 0 until w) {
            var r = 0f; var g = 0f; var b = 0f
            var inkCount = 0
            for (sy in 0 until 3) for (sx in 0 until 3) {
                val k = inkAt(xx + (sx + 0.5f) / 3f, yy + (sy + 0.5f) / 3f)
                when (k) {
                    1 -> { r += inkCol[0]; g += inkCol[1]; b += inkCol[2]; inkCount++ }
                    2 -> { r += 200f; g += 200f; b += 204f }
                    else -> { r += 240f; g += 238f; b += 232f }
                }
            }
            ink[yy * w + xx] = inkAt(xx + 0.5f, yy + 0.5f) == 1
            val l = if (shade) 1f - 0.18f * (xx.toFloat() / w) * (yy.toFloat() / h) else 1f
            px[yy * w + xx] = SceneGenerator.rgb((r / 9 * l).roundToInt(), (g / 9 * l).roundToInt(), (b / 9 * l).roundToInt())
        }
        return Page(w, h, px, ink)
    }

    private fun segDist(px: Float, py: Float, x0: Float, y0: Float, x1: Float, y1: Float): Float {
        val dx = x1 - x0; val dy = y1 - y0
        val l2 = dx * dx + dy * dy
        val t = if (l2 <= 1e-9f) 0f else (((px - x0) * dx + (py - y0) * dy) / l2).coerceIn(0f, 1f)
        val ex = x0 + t * dx - px; val ey = y0 + t * dy - py
        return sqrt(ex * ex + ey * ey)
    }

    enum class NoiseKind { GAUSSIAN, POISSON }

    /**
     * Sensor-like noise. GAUSSIAN: the same sigma everywhere, luminance plus a weaker independent
     * colour part. POISSON: shot noise, sigma = [sigma] * sqrt(v / 240) (strongest on the paper).
     */
    fun noisy(px: IntArray, sigma: Float, kind: NoiseKind, seed: Long): IntArray {
        if (sigma <= 0f) return px.copyOf()
        val rnd = java.util.Random(seed)
        return IntArray(px.size) { i ->
            val c = px[i]
            val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
            val l = (r * 77 + g * 150 + b * 29) / 256f
            val s = if (kind == NoiseKind.POISSON) sigma * sqrt(max(1f, l) / 240f) else sigma
            val n = rnd.nextGaussian().toFloat() * s * 0.9f
            val cr = rnd.nextGaussian().toFloat() * s * 0.35f
            val cb = rnd.nextGaussian().toFloat() * s * 0.35f
            SceneGenerator.rgb((r + n + cr).roundToInt(), (g + n - 0.5f * (cr + cb)).roundToInt(), (b + n + cb).roundToInt())
        }
    }

    /** Luminance plane as ints. */
    fun luma(px: IntArray): IntArray = IntArray(px.size) { val c = px[it]; (((c shr 16) and 0xFF) * 77 + ((c shr 8) and 0xFF) * 150 + (c and 0xFF) * 29) shr 8 }

    /** The text block (where the benchmarks measure). */
    fun textBlock(w: Int, h: Int) = intArrayOf((0.08f * w).toInt(), (0.07f * h).toInt(), (0.92f * w).toInt(), (0.92f * h).toInt())

    fun psnr(a: IntArray, b: IntArray, w: Int, h: Int): Double {
        val (x0, y0, x1, y1) = textBlock(w, h)
        var se = 0.0; var n = 0
        for (y in y0 until y1) for (x in x0 until x1) { val d = (a[y * w + x] - b[y * w + x]).toDouble(); se += d * d; n++ }
        val mse = se / n
        return if (mse <= 1e-12) 99.0 else 10 * kotlin.math.log10(255.0 * 255.0 / mse)
    }

    /** Mean SSIM over 8 x 8 windows (step 4) of the text block. */
    fun ssim(a: IntArray, b: IntArray, w: Int, h: Int): Double {
        val (x0, y0, x1, y1) = textBlock(w, h)
        val c1 = (0.01 * 255) * (0.01 * 255); val c2 = (0.03 * 255) * (0.03 * 255)
        var sum = 0.0; var n = 0
        var y = y0
        while (y < y1 - 8) {
            var x = x0
            while (x < x1 - 8) {
                var sa = 0.0; var sb = 0.0; var saa = 0.0; var sbb = 0.0; var sab = 0.0
                for (dy in 0 until 8) for (dx in 0 until 8) {
                    val p = a[(y + dy) * w + x + dx].toDouble(); val q = b[(y + dy) * w + x + dx].toDouble()
                    sa += p; sb += q; saa += p * p; sbb += q * q; sab += p * q
                }
                val ma = sa / 64; val mb = sb / 64
                val va = saa / 64 - ma * ma; val vb = sbb / 64 - mb * mb; val cov = sab / 64 - ma * mb
                sum += ((2 * ma * mb + c1) * (2 * cov + c2)) / ((ma * ma + mb * mb + c1) * (va + vb + c2)); n++
                x += 4
            }
            y += 4
        }
        return sum / n
    }

    /**
     * The camera agent's OCR proxy (GlareBenchTest): glyph error = (lost + false ink pixels) / truth
     * ink pixels in the text block, after binarising at 60% of the local (24 px tile) paper level.
     * [bitonal] pages are read as they are (< 128 = ink).
     */
    fun glyphError(l: IntArray, ink: BooleanArray, w: Int, h: Int, bitonal: Boolean = false): Double {
        val (x0, y0, x1, y1) = textBlock(w, h)
        var err = 0; var nInk = 0
        val tile = 24
        val vals = IntArray(tile * tile)
        for (ty in y0 until y1 step tile) for (tx in x0 until x1 step tile) {
            val thr: Float = if (bitonal) 128f else {
                var k = 0
                for (y in ty until min(y1, ty + tile)) for (x in tx until min(x1, tx + tile)) vals[k++] = l[y * w + x]
                java.util.Arrays.sort(vals, 0, k)
                vals[(k * 0.9).toInt().coerceAtMost(k - 1)] * 0.6f
            }
            for (y in ty until min(y1, ty + tile)) for (x in tx until min(x1, tx + tile)) {
                val t = ink[y * w + x]
                val p = l[y * w + x] < thr
                if (t) nInk++
                if (t != p) err++
            }
        }
        return err.toDouble() / max(1, nInk)
    }

    fun maxAbsDiff(a: IntArray, b: IntArray): Int {
        var m = 0
        for (i in a.indices) {
            val x = a[i]; val y = b[i]
            m = max(m, max(abs(((x shr 16) and 0xFF) - ((y shr 16) and 0xFF)), max(abs(((x shr 8) and 0xFF) - ((y shr 8) and 0xFF)), abs((x and 0xFF) - (y and 0xFF)))))
        }
        return m
    }
}
