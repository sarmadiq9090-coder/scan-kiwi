// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.math.tan

/** Quality warnings in the order the camera shows them (one at a time, PRD §7.2). */
enum class QualityWarning { INCOMPLETE, BLURRY, GLARE, DIM, SKEWED }

/**
 * Capture-time quality report (PRD §4.3). Scores are 0..1, higher is better, and a score below
 * 0.5 means a warning; [glare] is the share of clipped highlights inside the page and
 * [skewDegrees] the text-line angle left after the perspective correction.
 */
data class QualityReport(
    val sharpness: Float,
    val completeness: Float,
    val lighting: Float,
    val glare: Float,
    val skewDegrees: Float,
)

/**
 * Image-quality measures for the camera (CAM-03, CAM-04, PRD §4.3, A4 glare warning).
 * Pure Kotlin on grey or ARGB pixels; every threshold comes from [DetectionConfig].
 * Live measures run on the 320 px analysis frame only, so the preview stays smooth.
 */
object QualityMetrics {

    // ------------------------------------------------------------------ sharpness (CAM-03)

    /**
     * Contrast-normalised variance of the Laplacian inside the page ([quad] in pixels, shrunk by
     * 6% so the page border does not count). The sensor noise's own share of the variance
     * (20 sigma^2 for this kernel) is taken out, and the rest divided by the squared contrast of the
     * page (p1..p99 less the noise spread), so neither a dim nor a noisy page looks sharp or
     * blurry by itself. NaN when the page is blank (nothing to judge).
     */
    fun sharpness(img: GrayImage, quad: Quad?, cfg: DetectionConfig = DetectionConfig.DEFAULT): Float {
        val w = img.width
        val h = img.height
        val region = quad?.let { shrink(it, 0.06f) }
        val hist = IntArray(256)
        val diff = IntArray(256)
        var n = 0
        var sum = 0.0
        var sum2 = 0.0
        val (x0, y0, x1, y1) = bounds(region, w, h, 1)
        for (y in y0..y1) for (x in x0..x1) {
            if (region != null && !inside(region, x + 0.5f, y + 0.5f)) continue
            val c = img[x, y]
            hist[c]++
            diff[abs(c - img[x + 1, y])]++
            val lap = 4 * c - img[x - 1, y] - img[x + 1, y] - img[x, y - 1] - img[x, y + 1]
            sum += lap; sum2 += lap.toDouble() * lap
            n++
        }
        if (n < 50) return Float.NaN
        val noise = percentile(diff, n, 0.5) / (0.6745f * 1.4142f)
        val contrast = (percentile(hist, n, 0.99) - percentile(hist, n, 0.01)) - 4f * noise
        if (contrast < cfg.minTextContrast) return Float.NaN
        val mean = sum / n
        val variance = sum2 / n - mean * mean - 20.0 * noise * noise
        return (max(0.0, variance) / (contrast.toDouble() * contrast)).toFloat() / 16f
    }

    /** Sharpness as a 0..1 score; 0.5 is the threshold, blank pages score 1 (no warning). */
    fun sharpnessScore(raw: Float, threshold: Float): Float =
        if (raw.isNaN()) 1f else (0.5f * raw / threshold).coerceIn(0f, 1f)

    // ------------------------------------------------------------------ completeness (CAM-04)

    /**
     * False when part of the page is outside the picture: a corner within
     * [DetectionConfig.borderMargin] of the frame border, an outline cut by the border, or a page
     * smaller than [DetectionConfig.minCompleteArea] of the frame. [quad] is normalised (0..1).
     */
    fun isComplete(quad: Quad, cut: Boolean = false, cfg: DetectionConfig = DetectionConfig.DEFAULT): Boolean {
        if (cut) return false
        val m = cfg.borderMargin
        if (quad.points.any { it.x < m || it.y < m || it.x > 1f - m || it.y > 1f - m }) return false
        return quad.area() >= cfg.minCompleteArea
    }

    // ------------------------------------------------------------------ light and glare

    /** Mean luminance and noise sigma inside the page. */
    data class Light(val mean: Float, val noise: Float)

    fun light(img: GrayImage, quad: Quad?): Light {
        val region = quad?.let { shrink(it, 0.05f) }
        val (x0, y0, x1, y1) = bounds(region, img.width, img.height, 1)
        var sum = 0L
        var n = 0
        val diff = IntArray(256)
        var nd = 0
        for (y in y0..y1 step 2) for (x in x0..x1 step 2) {
            if (region != null && !inside(region, x + 0.5f, y + 0.5f)) continue
            val c = img[x, y]
            sum += c; n++
            diff[abs(c - img[x + 1, y])]++; nd++
        }
        if (n == 0) return Light(0f, 0f)
        // Median absolute neighbour difference -> Gaussian sigma (robust to text edges).
        val noise = percentile(diff, nd, 0.5) / (0.6745f * 1.4142f)
        return Light(sum.toFloat() / n, noise)
    }

    fun lightingScore(l: Light, cfg: DetectionConfig = DetectionConfig.DEFAULT): Float {
        val meanTerm = 0.5f * l.mean / cfg.dimMean
        val noiseTerm = if (l.noise <= 0.01f) 1f else 0.5f * cfg.noisySigma / l.noise
        return min(meanTerm, noiseTerm).coerceIn(0f, 1f)
    }

    /**
     * Share of clipped highlights inside the page: specular glare. Counted only while the paper
     * itself is not clipped (an over-exposed page is a lighting problem, not glare).
     */
    fun glare(img: GrayImage, quad: Quad?, cfg: DetectionConfig = DetectionConfig.DEFAULT): Float {
        val region = quad?.let { shrink(it, 0.03f) }
        val (x0, y0, x1, y1) = bounds(region, img.width, img.height, 0)
        val hist = IntArray(256)
        var n = 0
        for (y in y0..y1) for (x in x0..x1) {
            if (region != null && !inside(region, x + 0.5f, y + 0.5f)) continue
            hist[img[x, y]]++; n++
        }
        if (n == 0) return 0f
        if (percentile(hist, n, 0.5) >= cfg.clipLevel) return 0f
        var clipped = 0
        for (v in cfg.clipLevel..255) clipped += hist[v]
        return clipped.toFloat() / n
    }

    // ------------------------------------------------------------------ skew

    /**
     * Residual text-line angle (degrees) on a rectified page: the shear that makes the row
     * profile of the ink most peaked. 0 when there is too little text to tell.
     */
    fun skewDegrees(page: GrayImage, maxDeg: Float = 6f, stepDeg: Float = 0.25f): Float {
        val w = page.width
        val h = page.height
        val hist = IntArray(256)
        for (i in 0 until w * h) hist[page.data[i].toInt() and 0xFF]++
        val paper = percentile(hist, w * h, 0.9)
        val ink = (paper * 0.6f).toInt()
        val xs = ArrayList<Int>()
        val ys = ArrayList<Int>()
        // Sample ink pixels on a grid; enough for a stable profile at any page size.
        val step = max(1, max(w, h) / 600)
        for (y in 0 until h step step) for (x in 0 until w step step) if (page[x, y] < ink) { xs += x; ys += y }
        if (xs.size < 200) return 0f
        var best = 0f
        var bestScore = -1.0
        val bins = DoubleArray(h + 2 * w + 2)
        var a = -maxDeg
        while (a <= maxDeg + 1e-3f) {
            val t = tan(Math.toRadians(a.toDouble()))
            bins.fill(0.0)
            for (i in xs.indices) {
                val r = (ys[i] - xs[i] * t).roundToInt() + w
                if (r in bins.indices) bins[r] += 1.0
            }
            var sc = 0.0
            for (b in bins) sc += b * b
            if (sc > bestScore + 1e-9) { bestScore = sc; best = a }
            a += stepDeg
        }
        return best
    }

    // ------------------------------------------------------------------ report (PRD §4.3)

    /**
     * The quality report of a still: [px] is the photo sampled to about 960 px, [quadPx] the page
     * outline in its pixels (null = whole image), [cut] whether the detector saw the page cut.
     */
    fun assess(px: IntArray, w: Int, h: Int, quadPx: Quad?, cut: Boolean, cfg: DetectionConfig = DetectionConfig.DEFAULT): QualityReport {
        val gray = ImageFilters.toGray(px, w, h)
        val sharp = sharpnessScore(sharpness(gray, quadPx, cfg), cfg.stillBlurThreshold)
        val complete = quadPx == null || isComplete(quadPx.scale(1f / w, 1f / h), cut, cfg)
        val lighting = lightingScore(light(gray, quadPx), cfg)
        val glare = glare(gray, quadPx, cfg)
        val skew = quadPx?.let { q ->
            val (ow, oh) = q.outputSize()
            val k = min(1f, 900f / max(ow, oh))
            rectify(gray, q, max(8, (ow * k).toInt()), max(8, (oh * k).toInt()))?.let { skewDegrees(it) }
        } ?: 0f
        return QualityReport(sharp, if (complete) 1f else 0f, lighting, glare, skew)
    }

    /** Warnings of a report, most important first (incomplete > blur > glare > dim > skew). */
    fun warnings(sharpness: Float, completeness: Float, lighting: Float, glare: Float, skewDegrees: Float, cfg: DetectionConfig = DetectionConfig.DEFAULT): List<QualityWarning> {
        val out = ArrayList<QualityWarning>()
        if (completeness < 0.5f) out += QualityWarning.INCOMPLETE
        if (sharpness < 0.5f) out += QualityWarning.BLURRY
        if (glare > cfg.glareShare) out += QualityWarning.GLARE
        if (lighting < 0.5f) out += QualityWarning.DIM
        if (abs(skewDegrees) > cfg.skewWarnDegrees) out += QualityWarning.SKEWED
        return out
    }

    fun warnings(r: QualityReport, cfg: DetectionConfig = DetectionConfig.DEFAULT) =
        warnings(r.sharpness, r.completeness, r.lighting, r.glare, r.skewDegrees, cfg)

    // ------------------------------------------------------------------ geometry helpers

    /** Bilinear perspective rectification of [quad] (pixels) to an [ow] x [oh] grey image. */
    fun rectify(img: GrayImage, quad: Quad, ow: Int, oh: Int): GrayImage? {
        val hm = Homography.squareToQuad(quad) ?: return null
        val out = ByteArray(ow * oh)
        for (y in 0 until oh) for (x in 0 until ow) {
            val p = hm.map((x + 0.5f) / ow, (y + 0.5f) / oh)
            out[y * ow + x] = bilinear(img, p.x - 0.5f, p.y - 0.5f).toByte()
        }
        return GrayImage(ow, oh, out)
    }

    fun bilinear(img: GrayImage, x: Float, y: Float): Int {
        val xc = x.coerceIn(0f, img.width - 1.001f)
        val yc = y.coerceIn(0f, img.height - 1.001f)
        val x0 = xc.toInt(); val y0 = yc.toInt()
        val ax = xc - x0; val ay = yc - y0
        val top = img[x0, y0] * (1 - ax) + img[x0 + 1, y0] * ax
        val bot = img[x0, y0 + 1] * (1 - ax) + img[x0 + 1, y0 + 1] * ax
        return (top * (1 - ay) + bot * ay).roundToInt()
    }

    /** The quad moved [f] of the way towards its centre. */
    fun shrink(q: Quad, f: Float): Quad {
        val cx = q.points.sumOf { it.x.toDouble() }.toFloat() / 4f
        val cy = q.points.sumOf { it.y.toDouble() }.toFloat() / 4f
        return q.map { Pt(it.x + (cx - it.x) * f, it.y + (cy - it.y) * f) }
    }

    fun inside(q: Quad, x: Float, y: Float): Boolean {
        val p = q.points
        var sign = 0
        for (i in 0 until 4) {
            val a = p[i]; val b = p[(i + 1) % 4]
            val s = if ((b.x - a.x) * (y - a.y) - (b.y - a.y) * (x - a.x) >= 0) 1 else -1
            if (sign == 0) sign = s else if (s != sign) return false
        }
        return true
    }

    private data class Box(val x0: Int, val y0: Int, val x1: Int, val y1: Int)

    private fun bounds(q: Quad?, w: Int, h: Int, pad: Int): Box {
        if (q == null) return Box(pad, pad, w - 1 - pad, h - 1 - pad)
        return Box(
            max(pad, q.points.minOf { it.x }.toInt()), max(pad, q.points.minOf { it.y }.toInt()),
            min(w - 1 - pad, q.points.maxOf { it.x }.toInt() + 1), min(h - 1 - pad, q.points.maxOf { it.y }.toInt() + 1),
        )
    }

    private fun percentile(hist: IntArray, total: Int, p: Double): Int {
        val target = (total * p).toLong()
        var acc = 0L
        for (i in hist.indices) {
            acc += hist[i]
            if (acc > target) return i
        }
        return hist.size - 1
    }

}
