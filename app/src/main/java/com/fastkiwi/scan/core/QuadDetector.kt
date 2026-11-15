// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** 8-bit luminance image. */
class GrayImage(val width: Int, val height: Int, val data: ByteArray) {
    init {
        require(data.size >= width * height)
    }

    operator fun get(x: Int, y: Int): Int = data[y * width + x].toInt() and 0xFF
}

/**
 * A found page outline. [minSupport]/[meanSupport] are the fractions of the
 * sides lying on detected edges (0..1); low values mean a doubtful outline.
 * [cut]: one or two sides lie on the frame border, so part of the page is outside the
 * picture (CAM-04); such an outline is never confident.
 */
data class Detection(
    val quad: Quad,
    val score: Float,
    val minSupport: Float = 1f,
    val meanSupport: Float = 1f,
    val cut: Boolean = false,
) {
    /** Confident enough to crop without asking the user. */
    val confident: Boolean
        get() = !cut && minSupport >= DetectionConfig.DEFAULT.confidentMinSupport && meanSupport >= DetectionConfig.DEFAULT.confidentMeanSupport
}

/**
 * Finds the outline of a document (the largest convex quadrilateral whose
 * sides are backed by strong edges) in a luminance image.
 *
 * Our own pure-Kotlin implementation (no OpenCV), so the APK stays small:
 *  1. downscale to ~[workSize] px; 2.0: grey-level closing removes thin dark text, and a
 *     percentile stretch lifts low-contrast frames (dim rooms, white page on a white table)
 *  2. Gaussian blur, Canny edges (Sobel, non-maximum suppression, hysteresis)
 *  3. connected edge components -> convex hull -> best inscribed quad
 *  4. score = area x (fraction of each side lying on detected edges); 2.0: the page must
 *     differ from its surroundings with the same sign on every side (rejects texture)
 *
 * Every threshold lives in [DetectionConfig]; [DetectionConfig.LEGACY_1_2] is the 1.2.0 detector.
 */
class QuadDetector(private val workSize: Int = 320, private val cfg: DetectionConfig = DetectionConfig.DEFAULT) {

    /** Test hook: receives why candidates were rejected. */
    internal var trace: ((String) -> Unit)? = null

    /**
     * Detects on each channel (e.g. luminance and [ImageFilters.paperMap]). 2.0 (CAM-01/02 fusion):
     * every channel is scored and the best outline wins, a confident one before a doubtful one.
     * 1.2.0 ([DetectionConfig.fuseChannels] off) stopped at the first confident channel.
     */
    fun detectBest(channels: List<() -> GrayImage>): Detection? {
        var best: Detection? = null
        for (make in channels) {
            val d = detect(make()) ?: continue
            if (best == null || better(d, best)) best = d
            if (!cfg.fuseChannels && best.confident) break
        }
        return best
    }

    private fun better(a: Detection, b: Detection): Boolean = when {
        a.cut != b.cut -> !a.cut
        a.confident != b.confident -> a.confident
        else -> a.score > b.score
    }

    fun detect(img: GrayImage): Detection? {
        val scale = max(img.width, img.height).toFloat() / workSize
        val small0 = if (scale > 1.05f) downscale(img, scale) else img
        val s = if (scale > 1.05f) img.width.toFloat() / small0.width else 1f
        val w = small0.width
        val h = small0.height
        if (w < 16 || h < 16) return null
        val small = prepare(small0)

        val blurred = gaussian5(small.data, w, h)
        val nms = nonMaxSuppressed(blurred, w, h)
        val (baseHigh, strong) = thresholds(nms)

        val minArea = cfg.minArea * w * h
        var best: Detection? = null
        var cutBest: Detection? = null
        // Several passes with rising thresholds: weak texture (wood, cloth) that
        // merges with the page outline at a low threshold drops out at a higher one.
        for ((pass, factor) in cfg.passFactors.withIndex()) {
            val high = (baseHigh * factor).toInt().coerceAtMost(max(cfg.highFloor, (strong * 0.8f).toInt()))
            val edges = hysteresis(nms, w, h, high, high / 2)
            // Dilation closes small gaps in the outline and tolerates 1 px of error.
            val support = dilate(edges, w, h, 1)
            val hulls = componentHulls(support, w, h)
            trace?.invoke("pass $factor high=$high base=$baseHigh strong=$strong hulls=${hulls.size}")
            val candidates = ArrayList<Quad>()
            for (hull in hulls) {
                val rough = bestQuadInHull(hull) ?: continue
                candidates += refine(rough, edges, w, h)
                if (cfg.detectCut && cutBest == null) cutBest = cutPage(hull, rough, support, small, w, h)
            }
            // 2.0 (CAM-05): quads from four strong straight lines, immune to other edges
            // (shadows, text blocks, clutter) touching the page outline.
            if (pass < cfg.linePasses) candidates += lineQuads(edges, blurred, w, h).map { refine(it, edges, w, h) }
            for (quad in candidates) {
                val area = quad.area()
                trace?.invoke("  cand area=${area / (w * h)} quad=$quad")
                if (area < minArea || area > cfg.maxArea * w * h) continue
                if (!quad.isConvex()) continue
                val (lo, hi) = quad.angleRange()
                if (lo < cfg.minAngle || hi > cfg.maxAngle) continue
                val border = if (cfg.detectCut) borderSides(quad, w, h) else BooleanArray(4)
                val nBorder = border.count { it }
                if (nBorder > 2) continue
                val sides = sideSupport(quad, support, w, h)
                for (i in 0 until 4) if (border[i]) sides[i] = 1f
                val minSide = sides.min()
                val mean = sides.average().toFloat()
                trace?.invoke("  support min=$minSide mean=$mean border=${border.toList()}")
                if (minSide < cfg.minSideSupport || mean < cfg.minMeanSupport) continue
                if (cfg.minSideContrast > 0f && sideContrast(quad, small, border).also { trace?.invoke("  contrast=$it") } < cfg.minSideContrast) continue
                val outside = quad.points.any { it.x < -0.5f || it.y < -0.5f || it.x > w - 0.5f || it.y > h - 0.5f }
                val cut = nBorder > 0 || outside
                if (cut && !paperLike(quad, small, border, w, h)) continue
                // A cut outline only matters when nothing whole was found.
                val score = (area / (w * h)) * mean * mean * (if (cut) 0.5f else 1f)
                val d = Detection(quad, score, minSide, mean, cut)
                // 1.2.0 kept the highest score within one channel; 2.0 prefers confident outlines.
                if (best == null || (if (cfg.fuseChannels) better(d, best) else d.score > best.score)) best = d
            }
            if (best != null && !best.cut && best.score > 0.25f) break
        }
        // A whole page wins; otherwise a page running off the frame is reported as cut (CAM-04).
        val result = if (best != null && !best.cut) best else cutBest ?: best
        return result?.let { it.copy(quad = it.quad.scale(s, s)) }
    }

    /**
     * CAM-04: a large edge hull that runs along the frame border is a page cut by the picture
     * edge when its other sides lie on edges and the page differs from the surface there with
     * one sign. Returns a cut detection whose quad approximates the visible part.
     */
    private fun cutPage(hull: List<Pt>, rough: Quad, support: ByteArray, img: GrayImage, w: Int, h: Int): Detection? {
        val area = polygonArea(hull)
        if (area < cfg.minArea * w * h || area > cfg.maxArea * w * h) return null
        val tol = 3f
        fun nearX(v: Float) = v <= tol || v >= w - 1 - tol
        fun nearY(v: Float) = v <= tol || v >= h - 1 - tol
        fun onBorder(a: Pt, b: Pt): Boolean =
            (a.x <= tol && b.x <= tol) || (a.x >= w - 1 - tol && b.x >= w - 1 - tol) ||
                (a.y <= tol && b.y <= tol) || (a.y >= h - 1 - tol && b.y >= h - 1 - tol) ||
                ((nearX(a.x) || nearY(a.y)) && (nearX(b.x) || nearY(b.y)) && a.dist(b) < 4 * tol)
        var borderLen = 0f
        var freeLen = 0f
        var hit = 0
        var total = 0
        var signSum = 0L
        val diffs = ArrayList<Int>()
        val cx = hull.sumOf { it.x.toDouble() }.toFloat() / hull.size
        val cy = hull.sumOf { it.y.toDouble() }.toFloat() / hull.size
        for (i in hull.indices) {
            val a = hull[i]; val b = hull[(i + 1) % hull.size]
            val len = a.dist(b)
            if (len < 1e-3f) continue
            if (onBorder(a, b)) { borderLen += len; continue }
            freeLen += len
            val steps = max(2, (len / 3f).toInt())
            var nxv = -(b.y - a.y) / len; var nyv = (b.x - a.x) / len
            if ((cx - (a.x + b.x) / 2) * nxv + (cy - (a.y + b.y) / 2) * nyv < 0) { nxv = -nxv; nyv = -nyv }
            for (k in 1 until steps) {
                val t = k / steps.toFloat()
                val x = a.x + (b.x - a.x) * t; val y = a.y + (b.y - a.y) * t
                val xi = x.roundToInt(); val yi = y.roundToInt()
                if (xi < 0 || yi < 0 || xi >= w || yi >= h) continue
                total++
                var on = false
                for (dy in -1..1) for (dx in -1..1) {
                    val xx = xi + dx; val yy = yi + dy
                    if (xx in 0 until w && yy in 0 until h && support[yy * w + xx].toInt() != 0) on = true
                }
                if (on) hit++
                val ins = sample(img, x + 3 * nxv, y + 3 * nyv)
                val out = sample(img, x - 3 * nxv, y - 3 * nyv)
                if (ins >= 0 && out >= 0) { diffs += ins - out; signSum += ins - out }
            }
        }
        val perimeter = borderLen + freeLen
        trace?.invoke("  cut? border=${borderLen / perimeter} hit=$hit/$total")
        if (borderLen < 0.12f * perimeter || borderLen > 0.6f * perimeter || total < 20) return null
        val sup = hit.toFloat() / total
        if (sup < 0.7f) return null
        val sign = if (signSum >= 0) 1 else -1
        val contrast = diffs.count { it * sign >= cfg.sideContrastLevel }.toFloat() / diffs.size.coerceAtLeast(1)
        trace?.invoke("  cut? support=$sup contrast=$contrast")
        if (contrast < cfg.cutSideContrast || sign < 0) return null
        if (!paperLike(rough, img, borderSides(rough, w, h), w, h)) return null
        return Detection(rough, (area / (w * h)) * sup * sup * 0.5f, sup, sup, cut = true)
    }

    /**
     * Extra evidence for a cut page, which has fewer visible sides to check: the inside is
     * brighter than the surface with the same sign all round, smoother than the surface
     * (paper after the text-removing closing), and the frame border is not on two opposite sides.
     */
    private fun paperLike(q: Quad, img: GrayImage, border: BooleanArray, w: Int, h: Int): Boolean {
        if ((border[0] && border[2]) || (border[1] && border[3])) return false
        val p = q.points
        val hm = Homography.squareToQuad(q) ?: return false
        var inSum = 0f
        var inN = 0
        var inLevel = 0L
        for (j in 0 until 10) for (i in 0 until 10) {
            val pt = hm.map(0.15f + 0.07f * i, 0.15f + 0.07f * j)
            val x = pt.x.roundToInt(); val y = pt.y.roundToInt()
            if (x < 1 || y < 1 || x >= w - 1 || y >= h - 1) continue
            inSum += roughness(img, x, y); inLevel += img[x, y]; inN++
        }
        if (inN < 10) return false
        var outSum = 0f
        var outN = 0
        var outLevel = 0L
        val cx = p.sumOf { it.x.toDouble() }.toFloat() / 4f
        val cy = p.sumOf { it.y.toDouble() }.toFloat() / 4f
        for (s in 0 until 4) {
            if (border[s]) continue
            val a = p[s]; val b = p[(s + 1) % 4]
            val len = a.dist(b).coerceAtLeast(1e-3f)
            var nx = -(b.y - a.y) / len; var ny = (b.x - a.x) / len
            if ((cx - (a.x + b.x) / 2) * nx + (cy - (a.y + b.y) / 2) * ny < 0) { nx = -nx; ny = -ny }
            for (k in 1..12) {
                val t = k / 13f
                val x = (a.x + (b.x - a.x) * t - 6 * nx).roundToInt()
                val y = (a.y + (b.y - a.y) * t - 6 * ny).roundToInt()
                if (x < 1 || y < 1 || x >= w - 1 || y >= h - 1) continue
                outSum += roughness(img, x, y); outLevel += img[x, y]; outN++
            }
        }
        if (outN < 6) return false
        val inR = inSum / inN
        val outR = outSum / outN
        val step = (inLevel / inN - outLevel / outN).toFloat()
        trace?.invoke("  paperLike inR=$inR outR=$outR step=$step")
        // Smoother than the surface, or (white on white) a step far above the paper's own grain.
        return step > cfg.sideContrastLevel && (inR <= cfg.cutSmoothRatio * outR || step >= cfg.cutStepOverGrain * inR)
    }

    private fun roughness(img: GrayImage, x: Int, y: Int): Float =
        (abs(img[x + 1, y] - img[x, y]) + abs(img[x, y + 1] - img[x, y])).toFloat()

    private fun polygonArea(p: List<Pt>): Float {
        var s = 0f
        for (i in p.indices) { val a = p[i]; val b = p[(i + 1) % p.size]; s += a.x * b.y - b.x * a.y }
        return abs(s) / 2f
    }

    // ---------------------------------------------------------------- 2.0 line candidates (CAM-05)

    private class Line(val theta: Float, val rho: Float, val votes: Int, val border: Boolean = false) {
        val c = kotlin.math.cos(theta)
        val s = kotlin.math.sin(theta)
    }

    /**
     * Gradient-directed Hough transform: each edge pixel votes only for lines near its own
     * orientation. The strongest lines are paired into opposite sides and every two pairs
     * give a candidate quad (validated by the same support and contrast checks as hull quads).
     */
    private fun lineQuads(edges: ByteArray, g: IntArray, w: Int, h: Int): List<Quad> {
        val nTheta = 90
        val diag = sqrt((w * w + h * h).toFloat()).toInt() + 1
        val nRho = 2 * diag + 1
        val acc = IntArray(nTheta * nRho)
        val cosT = FloatArray(nTheta) { kotlin.math.cos(it * Math.PI / nTheta).toFloat() }
        val sinT = FloatArray(nTheta) { kotlin.math.sin(it * Math.PI / nTheta).toFloat() }
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            if (edges[i].toInt() == 0) continue
            val gx = -g[i - w - 1] - 2 * g[i - 1] - g[i + w - 1] + g[i - w + 1] + 2 * g[i + 1] + g[i + w + 1]
            val gy = -g[i - w - 1] - 2 * g[i - w] - g[i - w + 1] + g[i + w - 1] + 2 * g[i + w] + g[i + w + 1]
            if (gx == 0 && gy == 0) continue
            var phi = kotlin.math.atan2(gy.toDouble(), gx.toDouble())
            if (phi < 0) phi += Math.PI
            val t0 = (phi / Math.PI * nTheta).roundToInt()
            for (dt in -3..3) {
                val t = ((t0 + dt) % nTheta + nTheta) % nTheta
                val r = (x * cosT[t] + y * sinT[t]).roundToInt() + diag
                acc[t * nRho + r]++
            }
        }
        val minVotes = (cfg.lineMinVotes * min(w, h)).toInt()
        val peaks = ArrayList<Line>()
        for (t in 0 until nTheta) for (r in 0 until nRho) {
            val v = acc[t * nRho + r]
            if (v < minVotes) continue
            var isMax = true
            loop@ for (dt in -2..2) for (dr in -4..4) {
                if (dt == 0 && dr == 0) continue
                var tt = t + dt
                var rr = r + dr
                if (tt < 0) { tt += nTheta; rr = 2 * diag - rr } else if (tt >= nTheta) { tt -= nTheta; rr = 2 * diag - rr }
                if (rr < 0 || rr >= nRho) continue
                val o = acc[tt * nRho + rr]
                if (o > v || (o == v && (dt < 0 || (dt == 0 && dr < 0)))) { isMax = false; break@loop }
            }
            if (isMax) peaks += Line((t * Math.PI / nTheta).toFloat(), (r - diag).toFloat(), v)
        }
        val lines = peaks.sortedByDescending { it.votes }.take(cfg.maxLines).toMutableList()
        // CAM-04: the frame border stands in for a page side that is outside the picture.
        if (cfg.detectCut) {
            lines += Line(0f, 0f, 0, true); lines += Line(0f, w - 1f, 0, true)
            lines += Line((Math.PI / 2).toFloat(), 0f, 0, true); lines += Line((Math.PI / 2).toFloat(), h - 1f, 0, true)
        }
        val ox = if (cfg.detectCut) cfg.cutOutside * w else 2f
        val oy = if (cfg.detectCut) cfg.cutOutside * h else 2f
        // Opposite sides: nearly parallel lines that do not cross inside the frame.
        val pairs = ArrayList<IntArray>()
        val maxPar = kotlin.math.sin(Math.toRadians(30.0)).toFloat()
        for (i in lines.indices) for (j in i + 1 until lines.size) {
            val a = lines[i]; val b = lines[j]
            val sinD = abs(a.c * b.s - a.s * b.c)
            if (sinD > maxPar) continue
            val p = intersect(a, b)
            if (p != null && p.x > -0.1f * w && p.x < 1.1f * w && p.y > -0.1f * h && p.y < 1.1f * h) continue
            // Far enough apart (distance from one line's centre point to the other).
            val mid = Pt(w / 2f, h / 2f)
            val da = a.c * mid.x + a.s * mid.y - a.rho
            val db = b.c * mid.x + b.s * mid.y - b.rho
            val sameSide = (a.c * b.c + a.s * b.s) > 0
            val sep = if (sameSide) abs(da - db) else abs(da + db)
            if (sep < 0.2f * min(w, h)) continue
            pairs += intArrayOf(i, j)
        }
        val out = ArrayList<Quad>()
        val minCross = kotlin.math.sin(Math.toRadians(45.0)).toFloat()
        for (pi in pairs.indices) for (pj in pi + 1 until pairs.size) {
            val (a1, a2) = pairs[pi].let { lines[it[0]] to lines[it[1]] }
            val (b1, b2) = pairs[pj].let { lines[it[0]] to lines[it[1]] }
            if (pairs[pi].any { it in pairs[pj] }) continue
            if (abs(a1.c * b1.s - a1.s * b1.c) < minCross) continue
            val c1 = intersect(a1, b1) ?: continue
            val c2 = intersect(b1, a2) ?: continue
            val c3 = intersect(a2, b2) ?: continue
            val c4 = intersect(b2, a1) ?: continue
            val pts = listOf(c1, c2, c3, c4)
            if (pts.any { it.x < -ox || it.y < -oy || it.x > w - 1 + ox || it.y > h - 1 + oy }) continue
            fun near(p: Pt) = p.x >= -2f && p.y >= -2f && p.x <= w + 1f && p.y <= h + 1f
            out += Quad.order(pts.map { if (near(it)) Pt(it.x.coerceIn(0f, w - 1f), it.y.coerceIn(0f, h - 1f)) else it })
            if (out.size >= cfg.maxLineQuads) return out
        }
        return out
    }

    private fun intersect(a: Line, b: Line): Pt? {
        val det = a.c * b.s - a.s * b.c
        if (abs(det) < 1e-4f) return null
        return Pt((a.rho * b.s - b.rho * a.s) / det, (a.c * b.rho - b.c * a.rho) / det)
    }

    // ---------------------------------------------------------------- 2.0 pre-processing

    /** Closing (text removal) and percentile stretch, as configured. */
    internal fun prepare(img: GrayImage): GrayImage {
        var data = img.data
        val w = img.width
        val h = img.height
        if (cfg.closeRadius > 0) data = minFilter(maxFilter(data, w, h, cfg.closeRadius), w, h, cfg.closeRadius)
        if (cfg.stretchBelowSpread > 0) data = stretch(data)
        return if (data === img.data) img else GrayImage(w, h, data)
    }

    /** Linear stretch of the p2..p98 range to 0..255 when the frame is low in contrast. */
    private fun stretch(src: ByteArray): ByteArray {
        val hist = IntArray(256)
        for (b in src) hist[b.toInt() and 0xFF]++
        val lo = percentile(hist, src.size, cfg.stretchLowPct)
        val hi = percentile(hist, src.size, cfg.stretchHighPct)
        val spread = hi - lo
        if (spread >= cfg.stretchBelowSpread) return src
        val gain = min(cfg.maxStretchGain, 255f / max(1, spread))
        val mid = (lo + hi) / 2f
        val lut = ByteArray(256) { (128 + (it - mid) * gain).roundToInt().coerceIn(0, 255).toByte() }
        return ByteArray(src.size) { lut[src[it].toInt() and 0xFF] }
    }

    private fun percentile(hist: IntArray, total: Int, p: Double): Int {
        val target = (total * p).toLong()
        var acc = 0L
        for (i in 0 until 256) {
            acc += hist[i]
            if (acc > target) return i
        }
        return 255
    }

    private fun maxFilter(src: ByteArray, w: Int, h: Int, r: Int): ByteArray = rankFilter(src, w, h, r, true)
    private fun minFilter(src: ByteArray, w: Int, h: Int, r: Int): ByteArray = rankFilter(src, w, h, r, false)

    /** Separable square max (or min) filter of radius [r]. */
    private fun rankFilter(src: ByteArray, w: Int, h: Int, r: Int, isMax: Boolean): ByteArray {
        val tmp = ByteArray(w * h)
        val out = ByteArray(w * h)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                var m = if (isMax) 0 else 255
                for (d in max(0, x - r)..min(w - 1, x + r)) {
                    val v = src[row + d].toInt() and 0xFF
                    m = if (isMax) max(m, v) else min(m, v)
                }
                tmp[row + x] = m.toByte()
            }
        }
        for (y in 0 until h) for (x in 0 until w) {
            var m = if (isMax) 0 else 255
            for (d in max(0, y - r)..min(h - 1, y + r)) {
                val v = tmp[d * w + x].toInt() and 0xFF
                m = if (isMax) max(m, v) else min(m, v)
            }
            out[y * w + x] = m.toByte()
        }
        return out
    }

    /** Which sides run along the frame border (the page continues outside the picture). */
    private fun borderSides(q: Quad, w: Int, h: Int): BooleanArray {
        val p = q.points
        val tol = 3f
        return BooleanArray(4) { s ->
            val a = p[s]; val b = p[(s + 1) % 4]
            fun near(v: Float, lim: Float) = v <= tol || v >= lim - 1 - tol
            (near(a.x, w.toFloat()) && near(b.x, w.toFloat()) && abs(a.x - b.x) <= 2 * tol) ||
                (near(a.y, h.toFloat()) && near(b.y, h.toFloat()) && abs(a.y - b.y) <= 2 * tol)
        }
    }

    /**
     * Minimum over the non-border sides of the share of points where the inside differs from
     * the outside with the page's overall sign (paper brighter, or darker, than the surface).
     */
    private fun sideContrast(q: Quad, img: GrayImage, border: BooleanArray): Float {
        val p = q.points
        val cx = p.sumOf { it.x.toDouble() }.toFloat() / 4f
        val cy = p.sumOf { it.y.toDouble() }.toFloat() / 4f
        val n = 24
        val diffs = Array(4) { IntArray(n) }
        val valid = Array(4) { BooleanArray(n) }
        var total = 0L
        for (s in 0 until 4) {
            if (border[s]) continue
            val a = p[s]; val b = p[(s + 1) % 4]
            val dx = b.x - a.x; val dy = b.y - a.y
            val len = sqrt(dx * dx + dy * dy).coerceAtLeast(1e-3f)
            var nx = -dy / len; var ny = dx / len
            val mx = (a.x + b.x) / 2f; val my = (a.y + b.y) / 2f
            if ((cx - mx) * nx + (cy - my) * ny < 0) { nx = -nx; ny = -ny }
            for (i in 0 until n) {
                val t = 0.1f + 0.8f * i / (n - 1)
                val x = a.x + dx * t; val y = a.y + dy * t
                val inside = sample(img, x + 3 * nx, y + 3 * ny)
                val outside = sample(img, x - 3 * nx, y - 3 * ny)
                val ok = inside >= 0 && outside >= 0
                val d = if (ok) inside - outside else 0
                diffs[s][i] = d
                valid[s][i] = ok
                total += d
            }
        }
        val sign = if (total >= 0) 1 else -1
        var worst = 1f
        for (s in 0 until 4) {
            if (border[s]) continue
            val nValid = if (cfg.detectCut) valid[s].count { it } else n
            if (nValid < 6) continue
            val ok = diffs[s].indices.count { (valid[s][it] || !cfg.detectCut) && diffs[s][it] * sign >= cfg.sideContrastLevel }
            worst = min(worst, ok.toFloat() / nValid)
        }
        return worst
    }

    private fun sample(img: GrayImage, x: Float, y: Float): Int {
        val xi = x.roundToInt(); val yi = y.roundToInt()
        if (xi < 0 || yi < 0 || xi >= img.width || yi >= img.height) return -1
        return img[xi, yi]
    }

    /**
     * Snaps each side to the edge pixels along it (total-least-squares line fit)
     * and recomputes the corners as intersections of neighbouring sides.
     */
    internal fun refine(q: Quad, edges: ByteArray, w: Int, h: Int): Quad {
        val p = q.points
        val lines = arrayOfNulls<FloatArray>(4) // (px, py, dx, dy)
        for (s in 0 until 4) {
            val a = p[s]
            val b = p[(s + 1) % 4]
            val dx = b.x - a.x
            val dy = b.y - a.y
            val len = sqrt(dx * dx + dy * dy)
            if (len < 8f) continue
            val ux = dx / len
            val uy = dy / len
            val x0 = max(0, (min(a.x, b.x) - 4).toInt()); val x1 = min(w - 1, (max(a.x, b.x) + 4).toInt())
            val y0 = max(0, (min(a.y, b.y) - 4).toInt()); val y1 = min(h - 1, (max(a.y, b.y) + 4).toInt())
            var n = 0
            var sx = 0.0; var sy = 0.0; var sxx = 0.0; var syy = 0.0; var sxy = 0.0
            for (y in y0..y1) for (x in x0..x1) {
                if (edges[y * w + x].toInt() == 0) continue
                val rx = x - a.x
                val ry = y - a.y
                val t = (rx * ux + ry * uy) / len
                if (t < 0.12f || t > 0.88f) continue
                if (abs(rx * uy - ry * ux) > 3f) continue
                n++
                sx += x; sy += y; sxx += x.toDouble() * x; syy += y.toDouble() * y; sxy += x.toDouble() * y
            }
            if (n < 12) continue
            val mx = sx / n; val my = sy / n
            val cxx = sxx / n - mx * mx
            val cyy = syy / n - my * my
            val cxy = sxy / n - mx * my
            // Principal direction of the 2x2 covariance matrix.
            val theta = 0.5 * kotlin.math.atan2(2 * cxy, cxx - cyy)
            lines[s] = floatArrayOf(mx.toFloat(), my.toFloat(), kotlin.math.cos(theta).toFloat(), kotlin.math.sin(theta).toFloat())
        }
        val diag = sqrt((w * w + h * h).toFloat())
        val out = p.toMutableList()
        for (c in 0 until 4) {
            val l1 = lines[(c + 3) % 4] ?: continue
            val l2 = lines[c] ?: continue
            val det = l1[2] * l2[3] - l1[3] * l2[2]
            if (abs(det) < 0.2f) continue // nearly parallel
            val t = ((l2[0] - l1[0]) * l2[3] - (l2[1] - l1[1]) * l2[2]) / det
            val ip = Pt(l1[0] + t * l1[2], l1[1] + t * l1[3])
            val wasInside = p[c].x in 0f..(w - 1f) && p[c].y in 0f..(h - 1f)
            // A corner outside the frame (a cut page's line quad) stays outside.
            if (ip.dist(p[c]) < 0.05f * diag) out[c] = if (wasInside) Pt(ip.x.coerceIn(0f, w - 1f), ip.y.coerceIn(0f, h - 1f)) else ip
        }
        return Quad(out[0], out[1], out[2], out[3])
    }

    // ---------------------------------------------------------------- stages

    private fun downscale(img: GrayImage, scale: Float): GrayImage {
        val w = max(1, (img.width / scale).roundToInt())
        val h = max(1, (img.height / scale).roundToInt())
        val out = ByteArray(w * h)
        val fx = img.width.toFloat() / w
        val fy = img.height.toFloat() / h
        for (y in 0 until h) {
            val y0 = (y * fy).toInt()
            val y1 = min(img.height, max(y0 + 1, ((y + 1) * fy).toInt()))
            for (x in 0 until w) {
                val x0 = (x * fx).toInt()
                val x1 = min(img.width, max(x0 + 1, ((x + 1) * fx).toInt()))
                var sum = 0
                var n = 0
                // Sparse box average (at most 3x3 samples) keeps this fast.
                val sx = max(1, (x1 - x0) / 3)
                val sy = max(1, (y1 - y0) / 3)
                var yy = y0
                while (yy < y1) {
                    var xx = x0
                    while (xx < x1) {
                        sum += img[xx, yy]; n++
                        xx += sx
                    }
                    yy += sy
                }
                out[y * w + x] = (sum / n).toByte()
            }
        }
        return GrayImage(w, h, out)
    }

    private fun gaussian5(src: ByteArray, w: Int, h: Int): IntArray {
        val k = intArrayOf(1, 4, 6, 4, 1)
        val tmp = IntArray(w * h)
        val out = IntArray(w * h)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                var acc = 0
                for (i in -2..2) {
                    val xx = (x + i).coerceIn(0, w - 1)
                    acc += k[i + 2] * (src[row + xx].toInt() and 0xFF)
                }
                tmp[row + x] = acc
            }
        }
        for (y in 0 until h) {
            for (x in 0 until w) {
                var acc = 0
                for (i in -2..2) {
                    val yy = (y + i).coerceIn(0, h - 1)
                    acc += k[i + 2] * tmp[yy * w + x]
                }
                out[y * w + x] = acc / 256
            }
        }
        return out
    }

    /** Sobel gradient magnitude after non-maximum suppression (thin edges). */
    private fun nonMaxSuppressed(g: IntArray, w: Int, h: Int): IntArray {
        val mag = IntArray(w * h)
        val dir = ByteArray(w * h)
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val i = y * w + x
                val gx = -g[i - w - 1] - 2 * g[i - 1] - g[i + w - 1] + g[i - w + 1] + 2 * g[i + 1] + g[i + w + 1]
                val gy = -g[i - w - 1] - 2 * g[i - w] - g[i - w + 1] + g[i + w - 1] + 2 * g[i + w] + g[i + w + 1]
                mag[i] = abs(gx) + abs(gy)
                val ax = abs(gx)
                val ay = abs(gy)
                // 0: horizontal gradient, 1: 45deg, 2: vertical, 3: 135deg
                dir[i] = when {
                    ay * 5 < ax * 2 -> 0
                    ax * 5 < ay * 2 -> 2
                    (gx > 0) == (gy > 0) -> 1
                    else -> 3
                }.toByte()
            }
        }
        val nms = IntArray(w * h)
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val i = y * w + x
                val m = mag[i]
                if (m == 0) continue
                val (a, b) = when (dir[i].toInt()) {
                    0 -> mag[i - 1] to mag[i + 1]
                    2 -> mag[i - w] to mag[i + w]
                    1 -> mag[i - w - 1] to mag[i + w + 1]
                    else -> mag[i - w + 1] to mag[i + w - 1]
                }
                if (m >= a && m > b) nms[i] = m
            }
        }
        return nms
    }

    /**
     * Adaptive thresholds from the distribution of thin-edge strengths:
     * returns (high threshold, strength of the strongest 1% of edges).
     */
    private fun thresholds(nms: IntArray): Pair<Int, Int> {
        val hist = IntArray(1024)
        var count = 0
        for (m in nms) if (m > 0) { hist[min(1023, m / 2)]++; count++ }
        if (count == 0) return 80 to 80
        fun pct(p: Double): Int {
            var acc = 0
            val target = (count * p).toInt()
            for (b in hist.indices) {
                acc += hist[b]
                if (acc >= target) return b * 2
            }
            return 2046
        }
        val p80 = pct(cfg.highPercentile)
        val p99 = pct(0.99)
        // Ignore edges much weaker than the strongest ones (texture, noise).
        val high = max(p80, (p99 * cfg.strongFraction).toInt()).coerceIn(cfg.highFloor, cfg.highCeil)
        return high to p99
    }

    private fun hysteresis(nms: IntArray, w: Int, h: Int, high: Int, low: Int): ByteArray {
        val out = ByteArray(w * h)
        val stack = IntArray(w * h)
        var sp = 0
        for (i in nms.indices) {
            if (nms[i] >= high && out[i].toInt() == 0) {
                out[i] = 1
                stack[sp++] = i
                while (sp > 0) {
                    val p = stack[--sp]
                    val px = p % w
                    val py = p / w
                    for (dy in -1..1) for (dx in -1..1) {
                        val nx = px + dx
                        val ny = py + dy
                        if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                        val q = ny * w + nx
                        if (out[q].toInt() == 0 && nms[q] >= low) {
                            out[q] = 1
                            stack[sp++] = q
                        }
                    }
                }
            }
        }
        return out
    }

    private fun dilate(src: ByteArray, w: Int, h: Int, r: Int): ByteArray {
        val tmp = ByteArray(w * h)
        val out = ByteArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var v = 0
            for (d in -r..r) {
                val xx = x + d
                if (xx in 0 until w && src[y * w + xx].toInt() != 0) { v = 1; break }
            }
            tmp[y * w + x] = v.toByte()
        }
        for (y in 0 until h) for (x in 0 until w) {
            var v = 0
            for (d in -r..r) {
                val yy = y + d
                if (yy in 0 until h && tmp[yy * w + x].toInt() != 0) { v = 1; break }
            }
            out[y * w + x] = v.toByte()
        }
        return out
    }

    /** Convex hulls of connected edge components that are big enough to be a page. */
    private fun componentHulls(e: ByteArray, w: Int, h: Int): List<List<Pt>> {
        val label = IntArray(w * h)
        val stack = IntArray(w * h)
        val minExtent = 0.25f * min(w, h)
        val hulls = ArrayList<List<Pt>>()
        var next = 1
        val rowMin = IntArray(h)
        val rowMax = IntArray(h)
        for (start in e.indices) {
            if (e[start].toInt() == 0 || label[start] != 0) continue
            val id = next++
            rowMin.fill(Int.MAX_VALUE)
            rowMax.fill(Int.MIN_VALUE)
            var minX = w; var maxX = 0; var minY = h; var maxY = 0
            var sp = 0
            stack[sp++] = start
            label[start] = id
            while (sp > 0) {
                val p = stack[--sp]
                val px = p % w
                val py = p / w
                if (px < rowMin[py]) rowMin[py] = px
                if (px > rowMax[py]) rowMax[py] = px
                if (px < minX) minX = px
                if (px > maxX) maxX = px
                if (py < minY) minY = py
                if (py > maxY) maxY = py
                for (dy in -1..1) for (dx in -1..1) {
                    val nx = px + dx
                    val ny = py + dy
                    if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                    val q = ny * w + nx
                    if (e[q].toInt() != 0 && label[q] == 0) {
                        label[q] = id
                        stack[sp++] = q
                    }
                }
            }
            if (maxX - minX < minExtent || maxY - minY < minExtent) continue
            // Hull of the per-row extremes equals the hull of the whole component.
            val pts = ArrayList<Pt>()
            for (y in minY..maxY) {
                if (rowMin[y] == Int.MAX_VALUE) continue
                pts.add(Pt(rowMin[y].toFloat(), y.toFloat()))
                if (rowMax[y] != rowMin[y]) pts.add(Pt(rowMax[y].toFloat(), y.toFloat()))
            }
            val hull = convexHull(pts)
            if (hull.size >= 4) hulls.add(hull)
        }
        return hulls
    }

    /** Fraction of sample points along each side that lie on an edge pixel. */
    private fun sideSupport(q: Quad, e: ByteArray, w: Int, h: Int): FloatArray {
        val p = q.points
        val res = FloatArray(4)
        for (s in 0 until 4) {
            val a = p[s]
            val b = p[(s + 1) % 4]
            val n = 48
            var hit = 0
            var total = 0
            // Skip the ends: corners are often rounded or occluded by fingers.
            for (i in 3 until n - 3) {
                val t = i / (n - 1f)
                val x = (a.x + (b.x - a.x) * t).roundToInt()
                val y = (a.y + (b.y - a.y) * t).roundToInt()
                if (x < 0 || y < 0 || x >= w || y >= h) {
                    // 2.0: a page running off the frame cannot be checked there (CAM-04).
                    if (!cfg.detectCut) total++
                    continue
                }
                total++
                var found = false
                loop@ for (dy in -1..1) for (dx in -1..1) {
                    val xx = x + dx
                    val yy = y + dy
                    if (xx in 0 until w && yy in 0 until h && e[yy * w + xx].toInt() != 0) { found = true; break@loop }
                }
                if (found) hit++
            }
            res[s] = if (total == 0) (if (cfg.detectCut) 1f else 0f) else if (cfg.detectCut && total < 8) 1f else hit.toFloat() / total
        }
        return res
    }

    companion object {
        /** Andrew's monotone chain; returns hull clockwise on screen (y down). */
        fun convexHull(input: List<Pt>): List<Pt> {
            val pts = input.distinct().sortedWith(compareBy({ it.x }, { it.y }))
            if (pts.size < 3) return pts
            fun cross(o: Pt, a: Pt, b: Pt) = (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)
            val lower = ArrayList<Pt>()
            for (p in pts) {
                while (lower.size >= 2 && cross(lower[lower.size - 2], lower[lower.size - 1], p) <= 0) lower.removeAt(lower.size - 1)
                lower.add(p)
            }
            val upper = ArrayList<Pt>()
            for (p in pts.asReversed()) {
                while (upper.size >= 2 && cross(upper[upper.size - 2], upper[upper.size - 1], p) <= 0) upper.removeAt(upper.size - 1)
                upper.add(p)
            }
            lower.removeAt(lower.size - 1)
            upper.removeAt(upper.size - 1)
            return lower + upper
        }

        /** Largest-area quadrilateral with vertices on the hull. */
        fun bestQuadInHull(hull: List<Pt>): Quad? {
            var pts = simplify(hull)
            if (pts.size < 4) return null
            if (pts.size > 28) {
                val step = pts.size / 28f
                pts = List(28) { pts[(it * step).toInt()] }
            }
            val n = pts.size
            var bestArea = -1f
            var best: IntArray? = null
            for (a in 0 until n) for (b in a + 1 until n) for (c in b + 1 until n) for (d in c + 1 until n) {
                val ar = quadArea(pts[a], pts[b], pts[c], pts[d])
                if (ar > bestArea) {
                    bestArea = ar
                    best = intArrayOf(a, b, c, d)
                }
            }
            val idx = best ?: return null
            return Quad.order(idx.map { pts[it] })
        }

        private fun quadArea(a: Pt, b: Pt, c: Pt, d: Pt): Float {
            val s = (a.x * b.y - b.x * a.y) + (b.x * c.y - c.x * b.y) + (c.x * d.y - d.x * c.y) + (d.x * a.y - a.x * d.y)
            return abs(s) / 2f
        }

        /** Douglas-Peucker on a closed polygon (epsilon = 1.5% of perimeter). */
        private fun simplify(poly: List<Pt>): List<Pt> {
            if (poly.size <= 8) return poly
            var per = 0f
            for (i in poly.indices) per += poly[i].dist(poly[(i + 1) % poly.size])
            val eps = per * 0.015f
            // Split at the two farthest-apart points to handle the closed ring.
            var i0 = 0
            var i1 = 0
            var far = -1f
            for (i in poly.indices) {
                val d = poly[0].dist(poly[i])
                if (d > far) { far = d; i1 = i }
            }
            val first = poly.subList(i0, i1 + 1)
            val second = poly.subList(i1, poly.size) + poly[0]
            val r1 = dp(first, eps)
            val r2 = dp(second, eps)
            return r1.dropLast(1) + r2.dropLast(1)
        }

        private fun dp(pts: List<Pt>, eps: Float): List<Pt> {
            if (pts.size < 3) return pts
            val a = pts.first()
            val b = pts.last()
            var idx = -1
            var maxD = 0f
            val dx = b.x - a.x
            val dy = b.y - a.y
            val len = sqrt(dx * dx + dy * dy).coerceAtLeast(1e-6f)
            for (i in 1 until pts.size - 1) {
                val p = pts[i]
                val d = abs(dy * p.x - dx * p.y + b.x * a.y - b.y * a.x) / len
                if (d > maxD) { maxD = d; idx = i }
            }
            return if (maxD > eps && idx > 0) {
                dp(pts.subList(0, idx + 1), eps).dropLast(1) + dp(pts.subList(idx, pts.size), eps)
            } else listOf(a, b)
        }
    }
}
