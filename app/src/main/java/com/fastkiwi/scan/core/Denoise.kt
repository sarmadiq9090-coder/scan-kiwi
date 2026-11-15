// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.core

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 2.0-cam2: noise reduction (PRD §4.2) for low-light / high-gain captures.
 *
 * The page's own noise is measured first ([estimateSigma]: the low percentile of 8x8 block
 * deviations, i.e. the noise on blank paper). Clean pages (sigma < [MIN_SIGMA]) are returned
 * exactly as they were, and so are textures that only look like noise (sigma > [MAX_SIGMA]:
 * halftone, checks). Noisy pages get a self-guided filter (He et al., 2010) on the luminance:
 * an edge-preserving smoothing whose strength follows the measured sigma, so text strokes keep
 * their edges while the paper and the ink areas lose their grain. Colour noise is averaged over
 * the same window. Runs in strips with a 2r-row overlap and global row clamping, so the
 * result does not depend on the strip height (no seams). Pure Kotlin, O(pixels).
 *
 * Used inside «ألوان محسّنة», «تبييض» and «رمادي», never in «الأصلي» (original colour preservation)
 * nor in «أبيض وأسود» (no gain there), see [ImageFilters.apply].
 */
object Denoise {
    /** Below this sigma (grey levels) a page is clean and left exactly as it is. */
    const val MIN_SIGMA = 3f

    /** Above this sigma the "noise" is texture, not sensor noise, and is left alone. */
    const val MAX_SIGMA = 24f

    /** Edge-preservation threshold: eps = (k sigma)^2. Larger k smooths more. */
    var epsFactor = 2f

    /** Block side of the noise estimate. */
    private const val B = 8

    /** Measures the noise and reduces it when the page needs it. Returns the sigma treated, or 0. */
    fun auto(s: PixelStore, chroma: Boolean, opt: FilterOptions = FilterOptions.DEFAULT): Float {
        val sigma = estimateSigma(s, opt)
        if (sigma < MIN_SIGMA || sigma > MAX_SIGMA) return 0f
        guided(s, sigma, chroma, opt)
        return sigma
    }

    /** Window radius for a noise level: 5x5 for mild noise, 7x7 for strong noise. */
    fun radiusFor(sigma: Float): Int = if (sigma < 9f) 2 else 3

    /**
     * Noise sigma of the luminance: the 20th percentile of the standard deviation of 8x8 blocks
     * (blank paper dominates any page; text and edges only raise the upper percentiles), ignoring
     * clipped blocks. One read of the page, strip by strip.
     */
    fun estimateSigma(s: PixelStore, opt: FilterOptions = FilterOptions.DEFAULT): Float {
        val w = s.width; val h = s.height
        if (w < 2 * B || h < 2 * B) return 0f
        val bw = w / B
        val rows = ImageFilters.stripRows(w, opt, B)
        val buf = IntArray(rows * w)
        val hist = IntArray(641) // block sigma in 0.1 steps, 0..64
        val sum = IntArray(bw)
        val sum2 = LongArray(bw)
        var n = 0
        ImageFilters.strips(s, rows, buf, write = false) { _, nRows ->
            var band = 0
            while (band + B <= nRows) {
                sum.fill(0); sum2.fill(0)
                for (yy in 0 until B) {
                    val row = (band + yy) * w
                    for (x in 0 until bw * B) {
                        val l = ImageFilters.luma(buf[row + x])
                        sum[x / B] += l
                        sum2[x / B] += (l * l).toLong()
                    }
                }
                for (bx in 0 until bw) {
                    val mean = sum[bx] / 64.0
                    if (mean < 6.0 || mean > 249.0) continue
                    val v = max(0.0, sum2[bx] / 64.0 - mean * mean)
                    hist[min(640, (sqrt(v) * 10).toInt())]++
                    n++
                }
                band += B
            }
        }
        if (n < 16) return 0f
        val target = (n * 0.2).toLong()
        var acc = 0L
        for (i in hist.indices) {
            acc += hist[i]
            // Lower percentiles of a 64-sample deviation sit a little under sigma.
            if (acc > target) return (i + 0.5f) / 10f / 0.95f
        }
        return 64f
    }

    /**
     * Self-guided filter with window radius [radiusFor] and eps = ([epsFactor] sigma)^2, in place.
     * [chroma]: also average the colour difference channels (colour filters); otherwise every
     * channel moves by the luminance correction only (grey filters need nothing more).
     *
     * All window sums are exact integers (luma, its square, a and b in fixed point), computed with
     * running sums: O(1) per pixel whatever the radius, and bit-identical for any strip height.
     */
    fun guided(s: PixelStore, sigma: Float, chroma: Boolean, opt: FilterOptions = FilterOptions.DEFAULT, radius: Int = radiusFor(sigma)) {
        val w = s.width; val h = s.height
        val r = radius
        val o = 2 * r
        val eps = (epsFactor * sigma) * (epsFactor * sigma)
        val core = max(16, ImageFilters.stripRows(w, opt) / 2)
        val cap = (core + 2 * o) * w
        var cur = IntArray(cap)
        var prev = IntArray(cap)
        val out = IntArray(core * w)
        val yv = IntArray(cap)
        val tmp = IntArray(cap)
        val tmp2 = IntArray(cap)
        val m1 = IntArray(cap)
        val m2 = IntArray(cap)
        val fa = IntArray(cap)
        val fb = IntArray(cap)
        val acc = IntArray(w)
        val acc2 = IntArray(w)
        val cr = if (chroma) IntArray(cap) else null
        val cb = if (chroma) IntArray(cap) else null
        val n = ((2 * r + 1) * (2 * r + 1)).toFloat()
        val invN = 1f / n
        var prevYa = 0
        var y0 = 0
        while (y0 < h) {
            val y1 = min(h, y0 + core)
            val ya = max(0, y0 - o)
            val yb = min(h, y1 + o)
            val nr = yb - ya
            // Rows above the core were overwritten by the previous strip: take their originals from it.
            if (y0 > ya) System.arraycopy(prev, (ya - prevYa) * w, cur, 0, (y0 - ya) * w)
            s.read(y0, yb - y0, cur, (y0 - ya) * w)
            for (i in 0 until nr * w) {
                val c = cur[i]
                val rr = (c shr 16) and 0xFF; val bb = c and 0xFF
                val l = (rr * 77 + ((c shr 8) and 0xFF) * 150 + bb * 29 + 128) shr 8
                yv[i] = l
                if (cr != null) { cr[i] = rr - l; cb!![i] = bb - l }
            }
            // Level 1 (window mean and mean square -> a, b) on core rows +- r; level 2 (means of a, b) on the core.
            val l1a = max(0, y0 - r) - ya
            val l1b = min(h, y1 + r) - ya
            val ca = y0 - ya
            val ce = y1 - ya
            boxH2(yv, null, tmp, tmp2, 0, nr, w, r)
            boxV2(tmp, tmp2, m1, m2, acc, acc2, l1a, l1b, ya, h, w, r)
            for (i in l1a * w until l1b * w) {
                val mean = m1[i] * invN
                val v = max(0f, m2[i] * invN - mean * mean)
                val a = v / (v + eps)
                fa[i] = (a * A_ONE + 0.5f).toInt()
                fb[i] = (mean * (1f - a) * B_ONE + 0.5f).toInt()
            }
            boxH2(fa, fb, tmp, tmp2, l1a, l1b, w, r)
            boxV2(tmp, tmp2, m1, m2, acc, acc2, ca, ce, ya, h, w, r) // window sums of a and b
            if (cr != null) {
                boxH2(cr, cb!!, tmp, tmp2, l1a, l1b, w, r)
                boxV2(tmp, tmp2, fa, fb, acc, acc2, ca, ce, ya, h, w, r) // window sums of R - Y and B - Y
            }
            val ka = invN / A_ONE
            val kb = invN / B_ONE
            for (j in ca until ce) {
                val base = j * w
                val ob = (j - ca) * w
                for (x in 0 until w) {
                    val i = base + x
                    val l = yv[i]
                    val q = m1[i] * ka * l + m2[i] * kb
                    out[ob + x] = if (cr != null) {
                        val rr = q + fa[i] * invN
                        val bb = q + fb[i] * invN
                        val gg = (256f * q - 77f * rr - 29f * bb) / 150f
                        pack(rr, gg, bb)
                    } else {
                        val c = cur[i]
                        val d = q - l
                        pack(((c shr 16) and 0xFF) + d, ((c shr 8) and 0xFF) + d, (c and 0xFF) + d)
                    }
                }
            }
            s.write(y0, y1 - y0, out, 0)
            val t = prev; prev = cur; cur = t
            prevYa = ya
            y0 = y1
        }
    }

    /** Fixed-point scales of the guided filter's a (0..1) and b (0..255). */
    private const val A_ONE = 1024f
    private const val B_ONE = 64f

    private fun pack(r: Float, g: Float, b: Float): Int {
        val ri = (r + 0.5f).toInt().coerceIn(0, 255)
        val gi = (g + 0.5f).toInt().coerceIn(0, 255)
        val bi = (b + 0.5f).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ri shl 16) or (gi shl 8) or bi
    }

    /**
     * Horizontal window sums of rows [j0, j1) (running sums), clamped at the page's left and right
     * edges: [da] from [a]; [db] from [b], or from the squares of [a] when [b] is null.
     */
    private fun boxH2(a: IntArray, b: IntArray?, da: IntArray, db: IntArray, j0: Int, j1: Int, w: Int, r: Int) {
        val sq = b == null
        val bb = b ?: a
        for (j in j0 until j1) {
            val base = j * w
            var sa = 0; var sb = 0
            for (dx in -r..r) {
                val k = base + dx.coerceIn(0, w - 1)
                val v = a[k]; sa += v
                sb += if (sq) v * v else bb[k]
            }
            da[base] = sa; db[base] = sb
            val left = min(w, r + 1)
            val right = max(left, w - r)
            // Edge columns (clamped indices).
            for (x in 1 until left) {
                val ki = base + min(w - 1, x + r); val ko = base + max(0, x - r - 1)
                val vi = a[ki]; val vo = a[ko]
                sa += vi - vo
                sb += if (sq) vi * vi - vo * vo else bb[ki] - bb[ko]
                da[base + x] = sa; db[base + x] = sb
            }
            // Interior: no clamping.
            if (sq) {
                for (x in left until right) {
                    val vi = a[base + x + r]; val vo = a[base + x - r - 1]
                    sa += vi - vo
                    sb += vi * vi - vo * vo
                    da[base + x] = sa; db[base + x] = sb
                }
            } else {
                for (x in left until right) {
                    val ki = base + x + r; val ko = base + x - r - 1
                    sa += a[ki] - a[ko]
                    sb += bb[ki] - bb[ko]
                    da[base + x] = sa; db[base + x] = sb
                }
            }
            for (x in right until w) {
                if (x < 1) continue
                val ki = base + min(w - 1, x + r); val ko = base + max(0, x - r - 1)
                val vi = a[ki]; val vo = a[ko]
                sa += vi - vo
                sb += if (sq) vi * vi - vo * vo else bb[ki] - bb[ko]
                da[base + x] = sa; db[base + x] = sb
            }
        }
    }

    /** Vertical window sums of [a] and [b] for local rows [j0, j1) (running sums), clamped at the page's top and bottom. */
    private fun boxV2(a: IntArray, b: IntArray, da: IntArray, db: IntArray, acc: IntArray, acc2: IntArray, j0: Int, j1: Int, ya: Int, h: Int, w: Int, r: Int) {
        if (j0 >= j1) return
        fun row(g: Int) = (g.coerceIn(0, h - 1) - ya) * w
        acc.fill(0); acc2.fill(0)
        val g0 = ya + j0
        for (k in -r..r) {
            val rb = row(g0 + k)
            for (x in 0 until w) { acc[x] += a[rb + x]; acc2[x] += b[rb + x] }
        }
        System.arraycopy(acc, 0, da, j0 * w, w)
        System.arraycopy(acc2, 0, db, j0 * w, w)
        for (j in j0 + 1 until j1) {
            val g = ya + j
            val rin = row(g + r)
            val rout = row(g - r - 1)
            val base = j * w
            for (x in 0 until w) {
                val v = acc[x] + a[rin + x] - a[rout + x]
                acc[x] = v
                da[base + x] = v
                val u = acc2[x] + b[rin + x] - b[rout + x]
                acc2[x] = u
                db[base + x] = u
            }
        }
    }
}
