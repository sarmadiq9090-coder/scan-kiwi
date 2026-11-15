// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * A dark band printed along the paper's edge (an invoice title bar, an ID card header, a letterhead) is part
 * of the page, but in a grey image its outer edge against the desk can be weaker than the band's inner edge
 * against the white paper, so [QuadDetector] puts the page side on the inner edge and the band's text is cut
 * away (developer report, BLACKBOX-2.0 S3: «فاتورة» was not in the page).
 *
 * This step looks just outside each side of a found outline, in colour, and moves the side out when all of
 * these hold (any doubt keeps the outline as found):
 *  - the rows just outside are one solid colour, clearly unlike the paper and unlike the surroundings;
 *  - the band's rows are that colour plus paper-coloured print (white title text), mostly band colour;
 *  - the band ends, on both halves of the side alike, within [MAX_BAND] of the page height and inside the
 *    picture, and the next rows are the surroundings;
 *  - the band ends sideways where the page's own sides end (beyond them is not the band colour), so it is as
 *    wide as the paper: an object lying next to the page is not taken for a band.
 * The new side lies on the band's outer edge, along the page's side lines (perspective is kept).
 */
object HeaderBand {
    /** Longest band, as a share of the page's extent across the side. */
    const val MAX_BAND = 0.25f
    private const val SAMPLES = 48
    private const val NONE = Int.MIN_VALUE
    internal var trace: ((String) -> Unit)? = null

    fun extend(d: Detection?, px: IntArray, w: Int, h: Int): Detection? {
        if (d == null || d.cut) return d
        val p = d.quad.points
        // Surroundings: just outside every side; the paper: just inside.
        val outside = ArrayList<Int>()
        val inside = ArrayList<Int>()
        for (i in 0 until 4) {
            for (k in intArrayOf(4, 6, 8)) outside.addAll(row(p, i, k.toFloat(), k.toFloat(), px, w, h, 0.15f, 0.85f, 16).toList())
            for (k in intArrayOf(-4, -6, -8)) inside.addAll(row(p, i, k.toFloat(), k.toFloat(), px, w, h, 0.15f, 0.85f, 16).toList())
        }
        val paper = median(inside.filter { it != NONE }) ?: return d
        val shift = (0 until 4).map { i -> bandWidth(p, i, px, w, h, paper, outside) }
        if (shift.all { it == null }) return d
        // Each side line moved out by its band, corners re-intersected along the page's side lines.
        val lines = (0 until 4).map { i -> shift[i]?.let { (ka, kb) -> moved(p, i, ka, kb) } ?: (p[i] to p[(i + 1) % 4]) }
        val corners = (0 until 4).map { i -> intersect(lines[(i + 3) % 4], lines[i]) ?: return d }
        val out = Quad(corners[0], corners[1], corners[2], corners[3])
        if (!out.isConvex() || out.points.any { it.x < 0f || it.y < 0f || it.x > w - 1f || it.y > h - 1f }) return d
        return d.copy(quad = out)
    }

    /**
     * The band just outside side [i]: how far out its outer edge lies at the side's two ends (px), or null.
     * The edge is found separately on the side's two halves, so a band edge that is not quite parallel to the
     * found side (perspective, a slightly turned side line) still ends cleanly.
     */
    private fun bandWidth(p: List<Pt>, i: Int, px: IntArray, w: Int, h: Int, paper: Int, outsideAll: List<Int>): Pair<Float, Float>? {
        val a = p[i]; val b = p[(i + 1) % 4]; val dPt = p[(i + 3) % 4]; val c = p[(i + 2) % 4]
        val extent = min(a.dist(dPt), b.dist(c))
        if (extent < 20f || a.dist(b) < 20f) return null
        val maxK = (extent * MAX_BAND).toInt()
        // The band colour: rows 1..3 just outside the side, one solid colour unlike the paper.
        val near = (1..3).flatMap { row(p, i, it.toFloat(), it.toFloat(), px, w, h).toList() }
        if (near.any { it == NONE }) return null
        val band = median(near) ?: return null
        val tol = tolerance(band, paper)
        if (dist(band, paper) < 3 * tol) return null
        if (near.count { dist(it, band) <= tol } < near.size * 0.6f) return null
        // Unlike the surroundings (the other sides' outside; this side's own is what is tested).
        val others = (0 until 4).filter { it != i }.flatMap { j -> outsideAll.subList(j * 48, (j + 1) * 48) }.filter { it != NONE }
        if (others.size < 16) return null
        if (others.count { dist(it, band) <= tol } > others.size * 0.25f) return null
        val ground = median(others) ?: return null
        // Clearly another colour than the surroundings (wood grain on the desk is not a band) and not the
        // surroundings in shade (the paper's own shadow: the same colour, somewhat darker).
        if (dist(band, ground) < 2f * tolerance(band, ground)) return null
        if (cosine(band, ground) >= 0.99f && level(band) >= 0.45f * level(ground)) return null
        trace?.invoke("side $i: band=${Integer.toHexString(band)} paper=${Integer.toHexString(paper)} ground=${Integer.toHexString(ground)} tol=$tol")
        // Each sample goes to the nearest of band, paper (or the blend of band and paper at print edges) and
        // surroundings. Light changes along a side, so each half of it uses its own band colour.
        fun classifier(bandColour: Int, groundColour: Int): (Int) -> Int = { col ->
            val db = dist(col, bandColour)
            val dp = min(dist(col, paper), toSegment(col, bandColour, paper) + tol)
            val dg = min(dist(col, groundColour), dist(col, ground))
            when {
                db <= dg && db <= dp && db <= 2.5f * tol -> 0
                dp <= dg -> 1
                else -> 2
            }
        }
        fun halfBand(t0: Float, t1: Float) = median((1..3).flatMap { row(p, i, it.toFloat(), it.toFloat(), px, w, h, t0, t1, SAMPLES / 2).toList() }) ?: band
        // The surroundings near each end: outside the side line that ends there (16 samples per row, 3 rows).
        fun nearGround(side: Int, from: Int) = median((0 until 3).flatMap { r -> outsideAll.subList(side * 48 + r * 16 + from, side * 48 + r * 16 + from + 8) }.filter { it != NONE }) ?: ground
        val clsA = classifier(halfBand(0.06f, 0.4f), nearGround((i + 3) % 4, 8))
        val clsB = classifier(halfBand(0.6f, 0.94f), nearGround((i + 1) % 4, 0))
        val ka = walk(p, i, px, w, h, maxK, 0.06f, 0.4f, clsA) ?: return null
        val kb = walk(p, i, px, w, h, maxK, 0.6f, 0.94f, clsB) ?: return null
        // The two halves agree (one band, not a step or a slanted object).
        if (abs(ka - kb) > max(3, (max(ka, kb) * 0.35f).toInt())) { trace?.invoke("side $i: halves $ka $kb"); return null }
        // Measured around t = 0.23 and 0.77 of the side: extended to its ends.
        val slope = (kb - ka) / 0.54f
        val endA = ka - slope * 0.23f - 0.5f
        val endB = kb + slope * 0.23f - 0.5f
        // A printed band is at least 2 % of the page (a shadow line along the edge is thinner).
        if (min(endA, endB) < max(2.5f, 0.02f * extent)) return null
        if (!bandRows(p, i, px, w, h, endA, endB) { col -> min(clsA(col), clsB(col)) }) { trace?.invoke("side $i: not a printed band ($ka, $kb)"); return null }
        // As wide as the paper: just beyond the page's side lines, the band rows are not band colour.
        var lateral = 0
        var seen = 0
        var wide = 0
        val rows = max(endA, endB).toInt()
        for (kk in 1 until rows) {
            val f = kk.toFloat() / rows
            val (a2, b2) = moved(p, i, endA * f, endB * f)
            val u = unit(a2 - b2)
            for (off in intArrayOf(4, 6, 8)) {
                for ((col, c) in listOf(sample(px, w, h, a2.x + u.x * off, a2.y + u.y * off) to clsA, sample(px, w, h, b2.x - u.x * off, b2.y - u.y * off) to clsB)) {
                    lateral++
                    if (col == NONE) continue
                    seen++
                    if (c(col) == 0) wide++
                }
            }
        }
        if (seen < lateral / 2) return null
        if (wide > seen * 0.25f) { trace?.invoke("side $i: wider than the paper"); return null }
        trace?.invoke("side $i: band $endA $endB")
        return endA to endB
    }

    /**
     * Rows out from side [i] on the stretch [t0]..[t1] of it: the first row of the surroundings, or null. The
     * band's outer edge is the largest drop of the band-colour share from one row pair to the next (title text
     * inside the band only halves it); before it the rows are mostly band colour, after it almost none.
     */
    private fun walk(p: List<Pt>, i: Int, px: IntArray, w: Int, h: Int, maxK: Int, t0: Float, t1: Float, cls: (Int) -> Int): Int? {
        val n = SAMPLES / 2
        val f = ArrayList<Float>()
        for (k in 1..maxK + 3) {
            val r = row(p, i, k.toFloat(), k.toFloat(), px, w, h, t0, t1, n)
            if (r.any { it == NONE }) break
            f += r.count { cls(it) == 0 }.toFloat() / n
        }
        var best = -1
        var bestDrop = 0f
        // f[j] is row j + 1; a candidate first-surroundings row k = j + 1 needs rows k-2, k-1 and k..k+2.
        for (j in 2..f.size - 3) {
            val drop = (f[j - 2] + f[j - 1]) / 2f - (f[j] + f[j + 1]) / 2f
            if (drop > bestDrop) { bestDrop = drop; best = j }
        }
        trace?.invoke("  side $i [$t0] profile=${f.joinToString(",") { "%.2f".format(it) }} edge=${best + 1}")
        if (best < 0 || best + 1 > maxK) return null
        if ((f[best - 2] + f[best - 1]) / 2f < 0.55f) return null
        // The first row may be the blurred edge itself.
        if (f[best] > 0.6f || f[best + 1] > 0.3f || f[best + 2] > 0.3f) return null
        return best + 1
    }

    /** Every row of the band is band colour plus print (little surroundings), and mostly band colour. */
    private fun bandRows(p: List<Pt>, i: Int, px: IntArray, w: Int, h: Int, endA: Float, endB: Float, cls: (Int) -> Int): Boolean {
        val rows = (min(endA, endB) - 1f).toInt()
        val shares = ArrayList<Int>()
        for (kk in 1..rows) {
            val f = kk.toFloat() / max(endA, endB)
            val c = row(p, i, endA * f, endB * f, px, w, h).map(cls)
            val inBand = c.count { it == 0 }
            val bg = c.count { it == 2 }
            trace?.invoke("  side $i k=$kk band=$inBand ground=$bg")
            if (inBand < SAMPLES * 0.25f) return false
            shares += inBand
        }
        return shares.isNotEmpty() && shares.sorted()[shares.size / 2] >= SAMPLES * 0.6f
    }

    // ------------------------------------------------------------------ sampling

    /** [k] px out along the side line through [from], away from its other end [opposite]. */
    private fun along(from: Pt, opposite: Pt, k: Float): Pt {
        val u = unit(from - opposite)
        return Pt(from.x + u.x * k, from.y + u.y * k)
    }

    /**
     * Side [i] moved out by [ka] / [kb] px at its two ends (negative = in). Each end moves along its own side
     * line in proportion to that line's length, so under perspective the moved side stays on a line of the paper.
     */
    private fun moved(p: List<Pt>, i: Int, ka: Float, kb: Float): Pair<Pt, Pt> {
        val a = p[i]; val d = p[(i + 3) % 4]; val b = p[(i + 1) % 4]; val c = p[(i + 2) % 4]
        val la = a.dist(d); val lb = b.dist(c); val m = ((la + lb) / 2f).coerceAtLeast(1f)
        return along(a, d, ka * la / m) to along(b, c, kb * lb / m)
    }

    /** Colours along side [i] moved [ka] / [kb] px out, on the stretch [t0]..[t1] of it. */
    private fun row(p: List<Pt>, i: Int, ka: Float, kb: Float, px: IntArray, w: Int, h: Int, t0: Float = 0.08f, t1: Float = 0.92f, n: Int = SAMPLES): IntArray {
        val (a, b) = moved(p, i, ka, kb)
        return IntArray(n) { s ->
            val t = t0 + (t1 - t0) * s / (n - 1)
            sample(px, w, h, a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
        }
    }

    private fun intersect(l1: Pair<Pt, Pt>, l2: Pair<Pt, Pt>): Pt? {
        val (p1, p2) = l1
        val (p3, p4) = l2
        val d = (p1.x - p2.x) * (p3.y - p4.y) - (p1.y - p2.y) * (p3.x - p4.x)
        if (abs(d) < 1e-4f) return null
        val t = ((p1.x - p3.x) * (p3.y - p4.y) - (p1.y - p3.y) * (p3.x - p4.x)) / d
        return Pt(p1.x + t * (p2.x - p1.x), p1.y + t * (p2.y - p1.y))
    }

    private fun sample(px: IntArray, w: Int, h: Int, x: Float, y: Float): Int {
        val xi = (x + 0.5f).toInt()
        val yi = (y + 0.5f).toInt()
        if (x < 0f || y < 0f || xi >= w || yi >= h) return NONE
        return px[yi * w + xi] and 0xFFFFFF
    }

    private fun unit(v: Pt): Pt {
        val l = sqrt(v.x * v.x + v.y * v.y).coerceAtLeast(1e-6f)
        return Pt(v.x / l, v.y / l)
    }

    // ------------------------------------------------------------------ colour

    private fun dist(a: Int, b: Int): Float {
        val dr = ((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)
        val dg = ((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)
        val db = (a and 0xFF) - (b and 0xFF)
        return sqrt((dr * dr + dg * dg + db * db).toFloat())
    }

    /** Cosine of the angle between two colours as RGB vectors (1 = same hue and saturation, any brightness). */
    private fun cosine(a: Int, b: Int): Float {
        fun ch(v: Int, s: Int) = ((v shr s) and 0xFF).toFloat()
        val dot = ch(a, 16) * ch(b, 16) + ch(a, 8) * ch(b, 8) + ch(a, 0) * ch(b, 0)
        val na = sqrt(ch(a, 16) * ch(a, 16) + ch(a, 8) * ch(a, 8) + ch(a, 0) * ch(a, 0))
        val nb = sqrt(ch(b, 16) * ch(b, 16) + ch(b, 8) * ch(b, 8) + ch(b, 0) * ch(b, 0))
        return if (na < 1f || nb < 1f) 0f else dot / (na * nb)
    }

    /** Distance from colour [c] to the straight line between colours [a] and [b] in RGB. */
    private fun toSegment(c: Int, a: Int, b: Int): Float {
        fun ch(v: Int, s: Int) = ((v shr s) and 0xFF).toFloat()
        val ax = ch(a, 16); val ay = ch(a, 8); val az = ch(a, 0)
        val dx = ch(b, 16) - ax; val dy = ch(b, 8) - ay; val dz = ch(b, 0) - az
        val px = ch(c, 16) - ax; val py = ch(c, 8) - ay; val pz = ch(c, 0) - az
        val len = dx * dx + dy * dy + dz * dz
        val t = if (len <= 0f) 0f else ((px * dx + py * dy + pz * dz) / len).coerceIn(0f, 1f)
        val ex = px - t * dx; val ey = py - t * dy; val ez = pz - t * dz
        return sqrt(ex * ex + ey * ey + ez * ez)
    }

    /** Colour tolerance: a share of the brighter colour's level (dim rooms lower every difference), at least 18. */
    private fun tolerance(a: Int, b: Int): Float = max(18f, 0.16f * max(level(a), level(b)))

    private fun level(c: Int): Float = max((c shr 16) and 0xFF, max((c shr 8) and 0xFF, c and 0xFF)).toFloat()

    private fun median(cs: List<Int>): Int? {
        if (cs.isEmpty()) return null
        fun ch(shift: Int) = cs.map { (it shr shift) and 0xFF }.sorted()[cs.size / 2]
        return (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}
