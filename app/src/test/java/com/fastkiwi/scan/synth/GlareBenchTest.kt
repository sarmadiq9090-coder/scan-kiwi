// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.synth

import com.fastkiwi.scan.core.GlareFusion
import com.fastkiwi.scan.core.GrayImage
import com.fastkiwi.scan.core.Homography
import com.fastkiwi.scan.core.ImageFilters
import com.fastkiwi.scan.core.Mat3
import com.fastkiwi.scan.core.Pt
import com.fastkiwi.scan.core.QualityMetrics
import com.fastkiwi.scan.core.Quad
import com.fastkiwi.scan.core.QuadDetector
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Two-shot glare fusion (A4, ROADMAP 1.8.0) on 30 synthetic pairs with specular spots.
 *
 * OCR proxy: the real OCR engine does not run on the JVM, so text quality is measured as the
 * glyph error: the share of ground-truth ink pixels (and false ink) lost after binarising the
 * rectified page against its local paper level, inside the text block. Acceptance: fused error
 * <= the better single shot on >= 90% of pairs; no ghosting: SSIM of the text block vs ground
 * truth >= 0.9.
 */
class GlareBenchTest {

    private val pw = 600
    private val ph = 848

    /** Ground-truth page (grey), rendered at 2x for clean resampling. */
    private fun truth(seed: Int): GrayImage {
        val rnd = java.util.Random(seed.toLong())
        val lines = SceneGenerator.TextLayout.random(rnd, 0.1f, 0.9f)
        val w = pw * 2; val h = ph * 2
        val px = ByteArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            px[y * w + x] = (if (lines.inkAt((x + 0.5f) / w, (y + 0.5f) / h)) 40 else 236).toByte()
        }
        return GrayImage(w, h, px)
    }

    private class Shot(val px: IntArray, val w: Int, val h: Int, val quad: Quad)

    /** The page photographed through [q] on dark wood, with a clipped specular spot centred at (sx, sy) of the page. */
    private fun shoot(gt: GrayImage, q: Quad, w: Int, h: Int, sx: Float, sy: Float, radius: Float, strength: Float, seed: Long): Shot {
        val rnd = java.util.Random(seed)
        val inv = Homography.squareToQuad(q)!!.inverse()!!
        val bg = SceneGenerator.backgroundFn(SceneGenerator.Background.DARK_WOOD, rnd, w, h)
        val px = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val uv = inv.map(x + 0.5f, y + 0.5f)
            var v: Float
            if (uv.x in 0f..1f && uv.y in 0f..1f) {
                v = QualityMetrics.bilinear(gt, uv.x * gt.width - 0.5f, uv.y * gt.height - 0.5f).toFloat()
                // Specular reflection: additive, clipped (it washes the ink out).
                val dx = uv.x - sx; val dy = (uv.y - sy) * ph / pw
                v += 255f * strength * exp(-(dx * dx + dy * dy) / (2 * radius * radius))
                v += (rnd.nextGaussian() * 2.0).toFloat()
                val c = v.roundToInt().coerceIn(0, 255)
                px[y * w + x] = SceneGenerator.rgb(c, c, (c - 2).coerceAtLeast(0))
            } else {
                val c = bg(x + 0.5f, y + 0.5f)
                val n = (rnd.nextGaussian() * 2.0).toInt()
                px[y * w + x] = SceneGenerator.rgb(((c shr 16) and 0xFF) + n, ((c shr 8) and 0xFF) + n, (c and 0xFF) + n)
            }
        }
        return Shot(px, w, h, q)
    }

    /** A page outline seen from a random angle, inside a [w] x [h] frame. */
    private fun view(rnd: java.util.Random, w: Int, h: Int, tilt: Int): Quad =
        SceneGenerator.placePage(rnd, tilt.toFloat(), w, h, 0.42f..0.55f, 0.06f)

    private fun detect(s: Shot): Quad? =
        QuadDetector(480).detectBest(listOf({ ImageFilters.toGray(s.px, s.w, s.h) }, { ImageFilters.paperMap(s.px, s.w, s.h) }))?.quad

    /** Glyph error inside the text block: (lost ink + false ink) / truth ink, after local binarisation. */
    private fun glyphError(img: GrayImage, gt: GrayImage): Float {
        val x0 = (0.1f * pw).toInt(); val x1 = (0.9f * pw).toInt()
        val y0 = (0.08f * ph).toInt(); val y1 = (0.9f * ph).toInt()
        var err = 0; var ink = 0
        val tile = 24
        for (ty in y0 until y1 step tile) for (tx in x0 until x1 step tile) {
            // Local paper level (p90) and ink threshold at 60% of it.
            val vals = ArrayList<Int>()
            for (y in ty until min(y1, ty + tile)) for (x in tx until min(x1, tx + tile)) vals += img[x, y]
            vals.sort()
            val paper = vals[(vals.size * 0.9).toInt()]
            val thr = paper * 0.6f
            for (y in ty until min(y1, ty + tile)) for (x in tx until min(x1, tx + tile)) {
                val t = gt[x * 2, y * 2] < 128
                val p = img[x, y] < thr
                if (t) ink++
                if (t != p) err++
            }
        }
        return err.toFloat() / max(1, ink)
    }

    /** Mean SSIM over 8x8 windows of the text block. */
    private fun ssim(img: GrayImage, gt: GrayImage): Double {
        val x0 = (0.1f * pw).toInt(); val x1 = (0.9f * pw).toInt()
        val y0 = (0.08f * ph).toInt(); val y1 = (0.9f * ph).toInt()
        val c1 = (0.01 * 255) * (0.01 * 255); val c2 = (0.03 * 255) * (0.03 * 255)
        var sum = 0.0; var n = 0
        for (y in y0 until y1 - 8 step 4) for (x in x0 until x1 - 8 step 4) {
            var sa = 0.0; var sb = 0.0; var saa = 0.0; var sbb = 0.0; var sab = 0.0
            for (dy in 0 until 8) for (dx in 0 until 8) {
                val a = img[x + dx, y + dy].toDouble()
                val b = ((gt[2 * (x + dx), 2 * (y + dy)] + gt[2 * (x + dx) + 1, 2 * (y + dy)] + gt[2 * (x + dx), 2 * (y + dy) + 1] + gt[2 * (x + dx) + 1, 2 * (y + dy) + 1]) / 4).toDouble()
                sa += a; sb += b; saa += a * a; sbb += b * b; sab += a * b
            }
            val ma = sa / 64; val mb = sb / 64
            val va = saa / 64 - ma * ma; val vb = sbb / 64 - mb * mb; val cov = sab / 64 - ma * mb
            sum += ((2 * ma * mb + c1) * (2 * cov + c2)) / ((ma * ma + mb * mb + c1) * (va + vb + c2)); n++
        }
        return sum / n
    }

    /** Rectifies the page of a photo (ARGB) to the ground-truth page size, with an exposure normalisation to the truth's paper level. */
    private fun rectified(px: IntArray, w: Int, h: Int, q: Quad): GrayImage {
        val g = QualityMetrics.rectify(ImageFilters.toGray(px, w, h), q, pw, ph)!!
        return g
    }

    @Test
    fun fusionBeatsBetterSingleShotWithoutGhosting() {
        val w = 900; val h = 1200
        var wins = 0
        var minSsim = 1.0
        var sumFused = 0.0; var sumBest = 0.0
        var ssimSum = 0.0
        var aligned = 0
        val lines = ArrayList<String>()
        for (i in 0 until 30) {
            val rnd = java.util.Random(9000L + i)
            val gt = truth(9100 + i)
            val qa = view(rnd, w, h, 5 + (i % 3) * 8)
            val qb = view(rnd, w, h, 12 + (i % 4) * 6)
            // Spots on different parts of the page (the second shot moves the reflection).
            val s1 = Pt(0.25f + 0.5f * rnd.nextFloat(), 0.2f + 0.3f * rnd.nextFloat())
            val s2 = Pt(0.25f + 0.5f * rnd.nextFloat(), 0.55f + 0.3f * rnd.nextFloat())
            val (sa, sb) = if (i % 2 == 0) s1 to s2 else s2 to s1
            val r = 0.06f + 0.04f * rnd.nextFloat()
            val st = 0.8f + 0.6f * rnd.nextFloat()
            val a = shoot(gt, qa, w, h, sa.x, sa.y, r, st, 1L + i)
            val b = shoot(gt, qb, w, h, sb.x, sb.y, r * (0.8f + 0.4f * rnd.nextFloat()), st, 100L + i)
            val da = detect(a) ?: error("page A not found in pair $i")
            val db = detect(b) ?: error("page B not found in pair $i")
            val fused = GlareFusion.fuse(a.px, w, h, da, b.px, w, h, db)
            if (fused != null) aligned++
            // Fusion used detected corners; the measurement rectifies with the true outlines so it
            // sees fusion errors (ghosting), not corner-detection error.
            val ra = rectified(a.px, w, h, qa)
            val rb = rectified(b.px, w, h, qb)
            // Alignment refused: the app keeps the first photo.
            val rf = rectified(fused?.px ?: a.px, w, h, qa)
            val ea = glyphError(ra, gt); val eb = glyphError(rb, gt); val ef = glyphError(rf, gt)
            val s = ssim(rf, gt)
            minSsim = min(minSsim, s)
            ssimSum += s
            val best = min(ea, eb)
            if (ef <= best) wins++
            sumFused += ef; sumBest += best
            lines += "pair $i: errA=%.3f errB=%.3f fused=%.3f ssim=%.3f ssimA=%.3f fromB=%.2f matches=%d".format(ea, eb, ef, s, ssim(ra, gt), fused?.secondShare ?: 0f, fused?.matches ?: 0)
        }
        lines.forEach { println("[glare]   $it") }
        println("[glare] fused <= better single shot: $wins/30 = %.1f%%; mean glyph error fused %.3f vs better shot %.3f; text SSIM mean %.3f min %.3f; feature-aligned %d/30"
            .format(wins * 100f / 30, sumFused / 30, sumBest / 30, ssimSum / 30, minSsim, aligned))
        assertTrue("wins $wins/30", wins >= 27)
        assertTrue("ghosting: min SSIM $minSsim", minSsim >= 0.9)
    }

    @Test
    fun dltRecoversAKnownHomography() {
        val q = Quad(Pt(100f, 80f), Pt(900f, 120f), Pt(860f, 1250f), Pt(140f, 1180f))
        val h = Mat3.squareToQuad(q)!!
        val src = List(20) { Pt((it % 5) / 4f, (it / 5) / 3f) }
        val fit = Mat3.fit(src, src.map { h.map(it.x.toDouble(), it.y.toDouble()) })!!
        for (p in src) assertTrue(fit.map(p.x.toDouble(), p.y.toDouble()).dist(h.map(p.x.toDouble(), p.y.toDouble())) < 1e-2f)
    }
}
