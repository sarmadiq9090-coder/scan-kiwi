// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Two-shot glare fusion (A4): the second photo, taken from another angle, is aligned to the first
 * by a homography and the pixels with less glare are kept, tile by tile, with soft seams.
 * Nothing is invented: every output pixel comes from one of the two photos.
 *
 * Alignment: the page corners from [QuadDetector] give a first homography; it is refined with
 * feature points from our own corner detector (Shi-Tomasi on the first photo) matched by
 * normalised cross-correlation in the second, then a least-squares homography (DLT) with outlier
 * rejection. Pure Kotlin on packed ARGB pixels.
 */
object GlareFusion {

    class Result(
        /** The fused photo, same size as the first. */
        val px: IntArray,
        /** Share of the page taken from the second photo. */
        val secondShare: Float,
        /** Feature matches used for the refined alignment (0 = corners only). */
        val matches: Int,
    )

    /**
     * Fuses [b] into [a]. [qa]/[qb] are the page outlines in pixels of each photo.
     * Returns null when the outlines cannot be mapped onto each other.
     */
    fun fuse(a: IntArray, aw: Int, ah: Int, qa: Quad, b: IntArray, bw: Int, bh: Int, qb: Quad): Result? {
        val ga = ImageFilters.toGray(a, aw, ah)
        val gb = ImageFilters.toGray(b, bw, bh)
        val h0 = Mat3.squareToQuad(qb)?.times(Mat3.squareToQuad(qa)?.inverse() ?: return null) ?: return null
        val (h, matches) = refine(ga, gb, qa, h0)
        // No feature agreement: the outlines may be wrong; never blend a misaligned photo.
        if (matches < 8) return null
        // Paper level of each photo (robust median of the page), for the glare test and exposure match.
        val region = QualityMetrics.shrink(qa, 0.02f)
        val x0 = max(0, region.points.minOf { it.x }.toInt()); val x1 = min(aw - 1, region.points.maxOf { it.x }.toInt() + 1)
        val y0 = max(0, region.points.minOf { it.y }.toInt()); val y1 = min(ah - 1, region.points.maxOf { it.y }.toInt() + 1)
        // Warp b into a's frame inside the page box.
        val bw2 = x1 - x0 + 1
        val bh2 = y1 - y0 + 1
        val warped = IntArray(bw2 * bh2)
        val valid = BooleanArray(bw2 * bh2)
        for (y in y0..y1) for (x in x0..x1) {
            if (!QualityMetrics.inside(region, x + 0.5f, y + 0.5f)) continue
            val p = h.map(x.toDouble(), y.toDouble())
            if (p.x < 0 || p.y < 0 || p.x > bw - 1.001f || p.y > bh - 1.001f) continue
            val i = (y - y0) * bw2 + (x - x0)
            warped[i] = bilinearArgb(b, bw, p.x, p.y)
            valid[i] = true
        }
        // Exposure match: per-channel gain of b to a from the median paper colour.
        val gain = FloatArray(3) { c ->
            val va = ArrayList<Int>(); val vb = ArrayList<Int>()
            var i = 0
            for (y in y0..y1 step 4) for (x in x0..x1 step 4) {
                val k = (y - y0) * bw2 + (x - x0)
                if (!valid[k]) continue
                va += (a[y * aw + x] shr (16 - 8 * c)) and 0xFF
                vb += (warped[k] shr (16 - 8 * c)) and 0xFF
                i++
            }
            if (va.size < 20) 1f else (pct(va, 0.6) / max(1f, pct(vb, 0.6))).coerceIn(0.7f, 1.4f)
        }
        // Tile glare: reflections add light, so on the same (aligned, exposure-matched) paper the
        // brighter tile has more glare, halo included.
        val tile = max(12, max(aw, ah) / 64)
        val tw = (bw2 + tile - 1) / tile
        val th = (bh2 + tile - 1) / tile
        val wB = FloatArray(tw * th)
        for (ty in 0 until th) for (tx in 0 until tw) {
            var n = 0
            var sa = 0L; var sb = 0L
            for (yy in ty * tile until min(bh2, (ty + 1) * tile)) for (xx in tx * tile until min(bw2, (tx + 1) * tile)) {
                val k = yy * bw2 + xx
                if (!valid[k]) continue
                val la = ImageFilters.luma(a[(yy + y0) * aw + xx + x0])
                val lb = (ImageFilters.luma(warped[k]) * (gain[0] + gain[1] + gain[2]) / 3f).toInt()
                sa += la; sb += lb
                n++
            }
            if (n == 0) continue
            val lift = (sa - sb).toFloat() / n
            // Take b only where a is clearly brighter (more glare); ramp over 4..12 grey levels.
            wB[ty * tw + tx] = ((lift - 4f) / 8f).coerceIn(0f, 1f)
        }
        // Cover the glare halo (one tile around) and feather the seams.
        var wm = maxFilter(wB, tw, th)
        wm = boxBlur(wm, tw, th)
        val out = a.copyOf()
        var fromB = 0f
        var total = 0
        for (y in y0..y1) for (x in x0..x1) {
            val k = (y - y0) * bw2 + (x - x0)
            if (!valid[k]) continue
            val fx = ((x - x0 + 0.5f) / tile - 0.5f).coerceIn(0f, tw - 1f)
            val fy = ((y - y0 + 0.5f) / tile - 0.5f).coerceIn(0f, th - 1f)
            val ix = fx.toInt(); val iy = fy.toInt()
            val jx = min(tw - 1, ix + 1); val jy = min(th - 1, iy + 1)
            val ax = fx - ix; val ay = fy - iy
            val wt = (wm[iy * tw + ix] * (1 - ax) + wm[iy * tw + jx] * ax) * (1 - ay) + (wm[jy * tw + ix] * (1 - ax) + wm[jy * tw + jx] * ax) * ay
            total++
            if (wt <= 0.001f) continue
            fromB += wt
            val ca = a[y * aw + x]
            val cb = warped[k]
            var c = 0xFF shl 24
            for (ch in 0 until 3) {
                val sh = 16 - 8 * ch
                val va = (ca shr sh) and 0xFF
                val vb = (((cb shr sh) and 0xFF) * gain[ch]).roundToInt().coerceIn(0, 255)
                c = c or ((va + (vb - va) * wt).roundToInt().coerceIn(0, 255) shl sh)
            }
            out[y * aw + x] = c
        }
        return Result(out, if (total == 0) 0f else fromB / total, matches)
    }

    /** True when a photo's page shows glare worth a second shot (same rule as the quality report). */
    fun needsSecondShot(glareShare: Float, cfg: DetectionConfig = DetectionConfig.DEFAULT) = glareShare > cfg.glareShare


    private fun pct(v: MutableList<Int>, p: Double): Float { v.sort(); return v[(v.size * p).toInt().coerceAtMost(v.size - 1)].toFloat() }

    // ------------------------------------------------------------------ alignment

    /** Refines [h0] (a pixels -> b pixels) with matched corner features; returns (homography, matches). */
    internal fun refine(ga: GrayImage, gb: GrayImage, qa: Quad, h0: Mat3): Pair<Mat3, Int> {
        val scale = max(ga.width, ga.height)
        val patch = max(5, scale / 160) // half size
        val search = max(4, scale / 120)
        val pts = corners(ga, QualityMetrics.shrink(qa, 0.06f), patch + search + 2)
        val src = ArrayList<Pt>()
        val dst = ArrayList<Pt>()
        val side = 2 * patch + 1
        val pa = FloatArray(side * side)
        val win = 2 * (patch + search) + 1
        val pb = FloatArray(win * win)
        for (p in pts) {
            // Patch of a, and b sampled through h0 around the same place (so perspective is undone).
            var k = 0
            for (dy in -patch..patch) for (dx in -patch..patch) pa[k++] = ga[p.x.toInt() + dx, p.y.toInt() + dy].toFloat()
            k = 0
            val r = patch + search
            for (dy in -r..r) for (dx in -r..r) {
                val q = h0.map((p.x + dx).toDouble(), (p.y + dy).toDouble())
                pb[k++] = QualityMetrics.bilinear(gb, q.x, q.y).toFloat()
            }
            var best = -2f
            var bx = 0; var by = 0
            val scores = FloatArray((2 * search + 1) * (2 * search + 1))
            for (oy in -search..search) for (ox in -search..search) {
                val s = ncc(pa, pb, side, win, ox + search, oy + search)
                scores[(oy + search) * (2 * search + 1) + ox + search] = s
                if (s > best) { best = s; bx = ox; by = oy }
            }
            if (best < 0.85f) continue
            // Sub-pixel peak.
            fun sc(ox: Int, oy: Int) = scores[(oy + search) * (2 * search + 1) + ox + search]
            var sx = bx.toFloat(); var sy = by.toFloat()
            if (abs(bx) < search) { val l = sc(bx - 1, by); val c = sc(bx, by); val rr = sc(bx + 1, by); val d = l - 2 * c + rr; if (d < -1e-6f) sx += 0.5f * (l - rr) / d }
            if (abs(by) < search) { val u = sc(bx, by - 1); val c = sc(bx, by); val dd = sc(bx, by + 1); val d = u - 2 * c + dd; if (d < -1e-6f) sy += 0.5f * (u - dd) / d }
            src += p
            dst += h0.map((p.x + sx).toDouble(), (p.y + sy).toDouble())
        }
        if (src.size < 8) return h0 to 0
        var keep = src.indices.toList()
        var h = h0
        repeat(3) {
            h = Mat3.fit(keep.map { src[it] }, keep.map { dst[it] }) ?: return h0 to 0
            val res = keep.map { i -> h.map(src[i].x.toDouble(), src[i].y.toDouble()).dist(dst[i]) }
            val med = res.sorted()[res.size / 2]
            val lim = max(1f, 3f * med)
            val next = keep.filterIndexed { j, _ -> res[j] <= lim }
            if (next.size < 8) return h to keep.size
            keep = next
        }
        return h to keep.size
    }

    /** Shi-Tomasi corners inside [region], one strongest per grid cell, away from clipped glare. */
    private fun corners(g: GrayImage, region: Quad, margin: Int): List<Pt> {
        val w = g.width; val hgt = g.height
        val cells = 10
        val x0 = max(margin, region.points.minOf { it.x }.toInt()); val x1 = min(w - 1 - margin, region.points.maxOf { it.x }.toInt())
        val y0 = max(margin, region.points.minOf { it.y }.toInt()); val y1 = min(hgt - 1 - margin, region.points.maxOf { it.y }.toInt())
        if (x1 <= x0 || y1 <= y0) return emptyList()
        val cw = (x1 - x0) / cells.toFloat(); val ch = (y1 - y0) / cells.toFloat()
        val best = arrayOfNulls<Pt>(cells * cells)
        val bestV = FloatArray(cells * cells)
        val step = max(1, max(w, hgt) / 800)
        var y = y0
        while (y <= y1) {
            var x = x0
            while (x <= x1) {
                if (QualityMetrics.inside(region, x.toFloat(), y.toFloat()) && g[x, y] < 245) {
                    var sxx = 0f; var syy = 0f; var sxy = 0f
                    for (dy in -2..2) for (dx in -2..2) {
                        val xx = x + dx * step; val yy = y + dy * step
                        val ix = (g[xx + 1, yy] - g[xx - 1, yy]).toFloat()
                        val iy = (g[xx, yy + 1] - g[xx, yy - 1]).toFloat()
                        sxx += ix * ix; syy += iy * iy; sxy += ix * iy
                    }
                    val tr = sxx + syy
                    val det = sxx * syy - sxy * sxy
                    val minEig = tr / 2 - sqrt(max(0f, tr * tr / 4 - det))
                    val c = (((x - x0) / cw).toInt().coerceIn(0, cells - 1)) + cells * ((y - y0) / ch).toInt().coerceIn(0, cells - 1)
                    if (minEig > bestV[c]) { bestV[c] = minEig; best[c] = Pt(x.toFloat(), y.toFloat()) }
                }
                x += step * 2
            }
            y += step * 2
        }
        val minV = (bestV.maxOrNull() ?: 0f) * 0.02f
        return best.indices.filter { best[it] != null && bestV[it] > minV }.map { best[it]!! }
    }

    private fun ncc(a: FloatArray, b: FloatArray, side: Int, win: Int, ox: Int, oy: Int): Float {
        var sa = 0f; var sb = 0f; var saa = 0f; var sbb = 0f; var sab = 0f
        var k = 0
        for (y in 0 until side) for (x in 0 until side) {
            val va = a[k++]
            val vb = b[(y + oy) * win + x + ox]
            sa += va; sb += vb; saa += va * va; sbb += vb * vb; sab += va * vb
        }
        val n = (side * side).toFloat()
        val cov = sab - sa * sb / n
        val den = sqrt(max(1e-6f, (saa - sa * sa / n) * (sbb - sb * sb / n)))
        return cov / den
    }

    private fun bilinearArgb(px: IntArray, w: Int, x: Float, y: Float): Int {
        val x0 = x.toInt(); val y0 = y.toInt()
        val ax = x - x0; val ay = y - y0
        val c00 = px[y0 * w + x0]; val c10 = px[y0 * w + x0 + 1]; val c01 = px[(y0 + 1) * w + x0]; val c11 = px[(y0 + 1) * w + x0 + 1]
        var out = 0xFF shl 24
        for (sh in intArrayOf(16, 8, 0)) {
            val v = (((c00 shr sh) and 0xFF) * (1 - ax) + ((c10 shr sh) and 0xFF) * ax) * (1 - ay) +
                (((c01 shr sh) and 0xFF) * (1 - ax) + ((c11 shr sh) and 0xFF) * ax) * ay
            out = out or (v.roundToInt().coerceIn(0, 255) shl sh)
        }
        return out
    }

    private fun maxFilter(src: FloatArray, w: Int, h: Int): FloatArray = FloatArray(src.size) { i ->
        val x = i % w; val y = i / w
        var m = 0f
        for (dy in -1..1) for (dx in -1..1) {
            val xx = x + dx; val yy = y + dy
            if (xx in 0 until w && yy in 0 until h) m = max(m, src[yy * w + xx])
        }
        m
    }

    private fun boxBlur(src: FloatArray, w: Int, h: Int): FloatArray = FloatArray(src.size) { i ->
        val x = i % w; val y = i / w
        var s = 0f
        var n = 0
        for (dy in -1..1) for (dx in -1..1) {
            val xx = x + dx; val yy = y + dy
            if (xx in 0 until w && yy in 0 until h) { s += src[yy * w + xx]; n++ }
        }
        s / n
    }
}

/** A 3x3 projective matrix (row-major) with the operations glare alignment needs. */
class Mat3(val m: DoubleArray) {
    fun map(x: Double, y: Double): Pt {
        val w = m[6] * x + m[7] * y + m[8]
        return Pt(((m[0] * x + m[1] * y + m[2]) / w).toFloat(), ((m[3] * x + m[4] * y + m[5]) / w).toFloat())
    }

    operator fun times(o: Mat3): Mat3 = Mat3(DoubleArray(9) { i ->
        val r = i / 3; val c = i % 3
        m[r * 3] * o.m[c] + m[r * 3 + 1] * o.m[3 + c] + m[r * 3 + 2] * o.m[6 + c]
    })

    fun inverse(): Mat3? {
        val a = m
        val det = a[0] * (a[4] * a[8] - a[5] * a[7]) - a[1] * (a[3] * a[8] - a[5] * a[6]) + a[2] * (a[3] * a[7] - a[4] * a[6])
        if (abs(det) < 1e-12) return null
        val inv = doubleArrayOf(
            (a[4] * a[8] - a[5] * a[7]), -(a[1] * a[8] - a[2] * a[7]), (a[1] * a[5] - a[2] * a[4]),
            -(a[3] * a[8] - a[5] * a[6]), (a[0] * a[8] - a[2] * a[6]), -(a[0] * a[5] - a[2] * a[3]),
            (a[3] * a[7] - a[4] * a[6]), -(a[0] * a[7] - a[1] * a[6]), (a[0] * a[4] - a[1] * a[3]),
        )
        return Mat3(DoubleArray(9) { inv[it] / det })
    }

    companion object {
        /** Unit square -> quad, the same mapping as [Homography.squareToQuad]. */
        fun squareToQuad(q: Quad): Mat3? {
            val x0 = q.tl.x.toDouble(); val y0 = q.tl.y.toDouble()
            val x1 = q.tr.x.toDouble(); val y1 = q.tr.y.toDouble()
            val x2 = q.br.x.toDouble(); val y2 = q.br.y.toDouble()
            val x3 = q.bl.x.toDouble(); val y3 = q.bl.y.toDouble()
            val dx1 = x1 - x2; val dx2 = x3 - x2; val dx3 = x0 - x1 + x2 - x3
            val dy1 = y1 - y2; val dy2 = y3 - y2; val dy3 = y0 - y1 + y2 - y3
            val den = dx1 * dy2 - dx2 * dy1
            if (abs(den) < 1e-12) return null
            val g = (dx3 * dy2 - dx2 * dy3) / den
            val h = (dx1 * dy3 - dx3 * dy1) / den
            return Mat3(doubleArrayOf(x1 - x0 + g * x1, x3 - x0 + h * x3, x0, y1 - y0 + g * y1, y3 - y0 + h * y3, y0, g, h, 1.0))
        }

        /** Least-squares homography src -> dst (normalised DLT with h33 = 1), or null. */
        fun fit(src: List<Pt>, dst: List<Pt>): Mat3? {
            if (src.size < 4) return null
            val (ts, ns) = normaliser(src)
            val (td, nd) = normaliser(dst)
            // Normal equations A^T A h = A^T b for the 8 unknowns.
            val ata = Array(8) { DoubleArray(8) }
            val atb = DoubleArray(8)
            for (i in src.indices) {
                val (x, y) = ns[i]; val (u, v) = nd[i]
                val r1 = doubleArrayOf(x, y, 1.0, 0.0, 0.0, 0.0, -u * x, -u * y)
                val r2 = doubleArrayOf(0.0, 0.0, 0.0, x, y, 1.0, -v * x, -v * y)
                for (r in listOf(r1 to u, r2 to v)) for (a in 0 until 8) {
                    atb[a] += r.first[a] * r.second
                    for (b in 0 until 8) ata[a][b] += r.first[a] * r.first[b]
                }
            }
            val hv = solve(ata, atb) ?: return null
            val hn = Mat3(doubleArrayOf(hv[0], hv[1], hv[2], hv[3], hv[4], hv[5], hv[6], hv[7], 1.0))
            return (td.inverse() ?: return null) * hn * ts
        }

        private fun normaliser(p: List<Pt>): Pair<Mat3, List<Pair<Double, Double>>> {
            val cx = p.sumOf { it.x.toDouble() } / p.size
            val cy = p.sumOf { it.y.toDouble() } / p.size
            val d = p.sumOf { kotlin.math.hypot(it.x - cx, it.y - cy) } / p.size
            val s = if (d < 1e-9) 1.0 else sqrt(2.0) / d
            val t = Mat3(doubleArrayOf(s, 0.0, -s * cx, 0.0, s, -s * cy, 0.0, 0.0, 1.0))
            return t to p.map { (it.x - cx) * s to (it.y - cy) * s }
        }

        private fun solve(a: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
            val n = b.size
            val m = Array(n) { i -> DoubleArray(n + 1) { j -> if (j < n) a[i][j] else b[i] } }
            for (c in 0 until n) {
                var p = c
                for (r in c + 1 until n) if (abs(m[r][c]) > abs(m[p][c])) p = r
                if (abs(m[p][c]) < 1e-12) return null
                val t = m[p]; m[p] = m[c]; m[c] = t
                for (r in 0 until n) if (r != c) {
                    val f = m[r][c] / m[c][c]
                    for (k in c..n) m[r][k] -= f * m[c][k]
                }
            }
            return DoubleArray(n) { m[it][n] / m[it][it] }
        }
    }
}
