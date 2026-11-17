// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.synth

import com.fastkiwi.scan.core.Deskew
import com.fastkiwi.scan.core.GrayImage
import com.fastkiwi.scan.core.Homography
import com.fastkiwi.scan.core.ImageFilters
import com.fastkiwi.scan.core.Pt
import com.fastkiwi.scan.core.QualityMetrics
import com.fastkiwi.scan.core.Quad
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.tan

/**
 * 2.0-cam2, PRD §4.2 automatic document alignment. Photos of synthetic pages whose text is turned
 * 0.5-5 degrees on the paper, seen at 0-20 degrees of tilt, with outlines a little off (as a
 * detector's are). The skew is measured as the capture report does (a 960 px copy), the outline
 * turned by [Deskew.straighten], and the residual measured independently on the full-size result.
 * Accept: residual <= 0.3 degrees on >= 95%; no change below the threshold or without text lines.
 */
class DeskewBenchTest {

    private val pw = 900
    private val ph = 1200

    private class Shot(val px: IntArray, val w: Int, val h: Int, val quad: Quad)

    /** The page (with its text turned by [skew]) photographed through [q] on dark wood. */
    private fun shoot(seed: Int, skew: Float, q: Quad, textured: Boolean = true): Shot {
        val page = if (textured) DocPages.page(seed, 640, 905, skewDeg = skew, shade = false) else picture(seed, 640, 905)
        val gt = GrayImage(page.w, page.h, ByteArray(page.w * page.h) { ImageFilters.luma(page.px[it]).toByte() })
        val rnd = java.util.Random(seed.toLong())
        val inv = Homography.squareToQuad(q)!!.inverse()!!
        val bg = SceneGenerator.backgroundFn(SceneGenerator.Background.DARK_WOOD, rnd, pw, ph)
        val px = IntArray(pw * ph)
        for (y in 0 until ph) for (x in 0 until pw) {
            val uv = inv.map(x + 0.5f, y + 0.5f)
            px[y * pw + x] = if (uv.x in 0f..1f && uv.y in 0f..1f) {
                val v = QualityMetrics.bilinear(gt, uv.x * gt.width - 0.5f, uv.y * gt.height - 0.5f) + (rnd.nextGaussian() * 2).toFloat()
                val c = v.roundToInt().coerceIn(0, 255)
                SceneGenerator.rgb(c, c, c)
            } else bg(x + 0.5f, y + 0.5f)
        }
        return Shot(px, pw, ph, q)
    }

    /** A page without text lines: soft blobs and gradients (a photo or a drawing). */
    private fun picture(seed: Int, w: Int, h: Int): DocPages.Page {
        val rnd = java.util.Random(seed.toLong())
        val blobs = List(12) { floatArrayOf(rnd.nextFloat() * w, rnd.nextFloat() * h, 30 + rnd.nextFloat() * 120, 60 + rnd.nextFloat() * 150) }
        val px = IntArray(w * h) { i ->
            val x = i % w; val y = i / w
            var v = 230f - 60f * y / h
            for (b in blobs) { val dx = x - b[0]; val dy = y - b[1]; v -= b[3] * kotlin.math.exp(-(dx * dx + dy * dy) / (2 * b[2] * b[2])) }
            val c = v.roundToInt().coerceIn(0, 255)
            SceneGenerator.rgb(c, c, c)
        }
        return DocPages.Page(w, h, px, BooleanArray(w * h))
    }

    /** The skew the capture report measures: on a copy sampled to 960 px, as CaptureQuality does. */
    private fun reportSkew(s: Shot): Float {
        val k = min(1f, 960f / max(s.w, s.h))
        val sw = (s.w * k).toInt(); val sh = (s.h * k).toInt()
        val g = ImageFilters.toGray(s.px, s.w, s.h)
        val small = IntArray(sw * sh) { i ->
            val v = QualityMetrics.bilinear(g, (i % sw + 0.5f) / k - 0.5f, (i / sw + 0.5f) / k - 0.5f)
            SceneGenerator.rgb(v, v, v)
        }
        return QualityMetrics.assess(small, sw, sh, s.quad.scale(k, k), false).skewDegrees
    }

    /** Independent residual measure on the full-size rectified page: finest projection-profile search. */
    private fun residual(s: Shot, quadNorm: Quad): Float {
        val q = quadNorm.scale(s.w.toFloat(), s.h.toFloat())
        val (ow, oh) = q.outputSize()
        val page = QualityMetrics.rectify(ImageFilters.toGray(s.px, s.w, s.h), q, ow, oh)!!
        val xs = ArrayList<Int>(); val ys = ArrayList<Int>()
        // Only the text block (the paper's border carries no lines).
        for (y in (oh * 0.06f).toInt() until (oh * 0.94f).toInt()) for (x in (ow * 0.06f).toInt() until (ow * 0.94f).toInt()) {
            if (page[x, y] < 120) { xs += x; ys += y }
        }
        val bins = DoubleArray(oh + 2 * ow + 4)
        var best = 0.0; var bestScore = -1.0
        var a = -5.6
        while (a <= 5.6 + 1e-9) {
            val t = tan(Math.toRadians(a))
            bins.fill(0.0)
            for (i in xs.indices) {
                val r = ys[i] - xs[i] * t + ow
                val k = kotlin.math.floor(r).toInt()
                val f = r - k
                bins[k] += 1 - f; bins[k + 1] += f
            }
            var sc = 0.0
            for (b in bins) sc += b * b
            if (sc > bestScore) { bestScore = sc; best = a }
            a += 0.02
        }
        return best.toFloat()
    }

    private fun outline(rnd: java.util.Random, tilt: Int): Quad {
        // Pages fully inside the frame (a page touching the border is left as it is by design).
        val q = SceneGenerator.placePage(rnd, tilt.toFloat(), pw, ph, 0.30f..0.42f, 0.1f)
        // A detector's outline is a little off: up to 0.3% of the frame per corner.
        return q.map { Pt(it.x + (rnd.nextFloat() - 0.5f) * 0.006f * pw, it.y + (rnd.nextFloat() - 0.5f) * 0.006f * ph) }
    }

    @Test
    fun residualSkewIsRemoved() {
        var ok = 0
        var worst = 0f
        var sumBefore = 0.0; var sumAfter = 0.0
        val lines = ArrayList<String>()
        val n = 40
        for (i in 0 until n) {
            val rnd = java.util.Random(6000L + i)
            val mag = 0.5f + 4.5f * i / (n - 1)
            val skew = if (i % 2 == 0) mag else -mag
            val shot = shoot(6100 + i, skew, outline(rnd, (i % 3) * 10))
            val norm = shot.quad.scale(1f / pw, 1f / ph)
            val before = residual(shot, norm)
            val measured = reportSkew(shot)
            val turned = Deskew.straighten(norm, pw, ph, measured)
            val after = if (turned != null) residual(shot, turned) else before
            if (abs(after) <= 0.3f) ok++
            worst = max(worst, abs(after))
            sumBefore += abs(before); sumAfter += abs(after)
            lines += "page $i text %+.2f° tilt %d: before %+.2f°, report %+.2f°, after %+.2f°%s".format(skew, (i % 3) * 10, before, measured, after, if (turned == null) " (not turned)" else "")
        }
        lines.forEach { println("[deskew]   $it") }
        println("[deskew] residual <= 0.3°: $ok/$n = %.1f%%; mean |residual| %.2f° -> %.2f°; worst %.2f°".format(ok * 100f / n, sumBefore / n, sumAfter / n, worst))
        assertTrue("$ok/$n", ok >= 38)
    }

    @Test
    fun straightPagesAndPicturesAreLeftAlone() {
        var untouched = 0
        val small = listOf(0f, 0.1f, -0.1f, 0.2f, -0.2f, 0f, 0.15f, -0.15f, 0.05f, -0.05f)
        for ((i, skew) in small.withIndex()) {
            val rnd = java.util.Random(7000L + i)
            val q = SceneGenerator.placePage(rnd, ((i % 3) * 10).toFloat(), pw, ph, 0.42f..0.55f, 0.1f)
            val shot = shoot(7100 + i, skew, q)
            val measured = reportSkew(shot)
            if (Deskew.straighten(q.scale(1f / pw, 1f / ph), pw, ph, measured) == null) untouched++
            else println("[deskew] page $i text %+.2f° measured %+.2f°: turned".format(skew, measured))
        }
        println("[deskew] below the threshold: $untouched/${small.size} left as they are")
        assertEquals(small.size, untouched)
        // Pictures: no text lines, no skew, no turn.
        for (i in 0 until 5) {
            val rnd = java.util.Random(7500L + i)
            val q = SceneGenerator.placePage(rnd, 10f, pw, ph, 0.42f..0.55f, 0.1f)
            val shot = shoot(7600 + i, 3f, q, textured = false)
            val measured = reportSkew(shot)
            assertNull("picture $i measured $measured", Deskew.straighten(q.scale(1f / pw, 1f / ph), pw, ph, measured))
        }
    }

    @Test
    fun straightenGeometry() {
        // A frontal page: turning by +2° moves the top edge's right end down by about w * tan 2°.
        val q = Quad(Pt(0.2f, 0.2f), Pt(0.8f, 0.2f), Pt(0.8f, 0.8f), Pt(0.2f, 0.8f))
        val t = Deskew.straighten(q, 1000, 1000, 2f)!!
        assertEquals(600f * tan(Math.toRadians(2.0)).toFloat(), (t.tr.y - t.tl.y) * 1000f, 0.5f)
        assertEquals(q.area(), t.area(), 1e-3f)
        assertNull(Deskew.straighten(q, 1000, 1000, 0.2f)) // under the threshold
        assertNull(Deskew.straighten(q, 1000, 1000, 6f)) // over the range
        assertNull(Deskew.straighten(Quad.full(1f, 1f), 1000, 1000, 2f)) // would leave the photo
    }
}
