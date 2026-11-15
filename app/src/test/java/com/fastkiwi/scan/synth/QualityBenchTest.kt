// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.synth

import com.fastkiwi.scan.core.DetectionConfig
import com.fastkiwi.scan.core.GrayImage
import com.fastkiwi.scan.core.ImageFilters
import com.fastkiwi.scan.core.Pt
import com.fastkiwi.scan.core.QualityMetrics
import com.fastkiwi.scan.core.QualityWarning
import com.fastkiwi.scan.core.QuadDetector
import com.fastkiwi.scan.synth.SceneGenerator.Background
import com.fastkiwi.scan.synth.SceneGenerator.Light
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CAM-03 blur detector (AUC between sigma <= 0.8 and sigma >= 2.0 on 40 synthetic pages) and
 * CAM-04 incomplete-page detector (40 synthetic crops: 20 cut, 20 whole), ROADMAP 1.3.0.
 */
class QualityBenchTest {

    private val sigmas = floatArrayOf(0f, 0.4f, 0.8f, 2.0f, 2.5f, 3.0f, 4.0f)

    /** Sharp and blurry scores of the same pages; blur is optical, so noise is added after it. */
    private fun blurScores(n: Int, w: Int, h: Int): Pair<List<Float>, List<Float>> {
        val sharp = ArrayList<Float>()
        val blurry = ArrayList<Float>()
        for (i in 0 until n) {
            val bg = Background.entries[i % 6]
            val light = Light.entries[(i / 6) % 3]
            val s = SceneGenerator.page(bg, light, SceneGenerator.TILTS[i % 4], 2000 + i, w, h, noise = 0f)
            val gray = ImageFilters.toGray(s.px, w, h).data
            for (sigma in sigmas) {
                val blurred = SceneGenerator.blurGray(gray, w, h, sigma)
                val noisy = addGrayNoise(blurred, SceneGenerator.noiseOf(light), 31L * i + (sigma * 10).toLong())
                val v = QualityMetrics.sharpness(GrayImage(w, h, noisy), s.quad)
                if (sigma <= 0.8f) sharp += v else blurry += v
            }
        }
        return sharp to blurry
    }

    private fun addGrayNoise(src: ByteArray, sigma: Float, seed: Long): ByteArray {
        val rnd = java.util.Random(seed)
        return ByteArray(src.size) { ((src[it].toInt() and 0xFF) + rnd.nextGaussian() * sigma).toInt().coerceIn(0, 255).toByte() }
    }

    /** Probability that a sharp page scores higher than a blurry one (ties count half). */
    private fun auc(pos: List<Float>, neg: List<Float>): Double {
        var s = 0.0
        for (p in pos) for (q in neg) s += if (p > q) 1.0 else if (p == q) 0.5 else 0.0
        return s / (pos.size * neg.size)
    }

    /** Threshold with the best balanced accuracy (reported, and compared with the config). */
    private fun bestThreshold(pos: List<Float>, neg: List<Float>): Pair<Float, Double> {
        val all = (pos + neg).sorted()
        var best = all.first() to 0.0
        for (t in all) {
            val acc = 0.5 * (pos.count { it >= t }.toDouble() / pos.size + neg.count { it < t }.toDouble() / neg.size)
            if (acc > best.second) best = t to acc
        }
        return best
    }

    @Test
    fun blurAucOnLiveFrames() {
        val (sharp, blurry) = blurScores(40, 240, 320)
        assertTrue(sharp.none { it.isNaN() } && blurry.none { it.isNaN() })
        val a = auc(sharp, blurry)
        val (t, acc) = bestThreshold(sharp, blurry)
        val cfgT = DetectionConfig.DEFAULT.liveBlurThreshold
        val cfgAcc = 0.5 * (sharp.count { it >= cfgT }.toDouble() / sharp.size + blurry.count { it < cfgT }.toDouble() / blurry.size)
        println("[blur 320px] 40 pages x ${sigmas.size} sigmas: AUC=%.4f best threshold=%.4f (balanced acc %.3f); config %.4f -> %.3f".format(a, t, acc, cfgT, cfgAcc))
        println("[blur 320px] sharp median=%.4f min=%.4f  blurry median=%.4f max=%.4f".format(sharp.sorted()[sharp.size / 2], sharp.min(), blurry.sorted()[blurry.size / 2], blurry.max()))
        assertTrue("AUC $a", a >= 0.95)
        assertTrue("config threshold accuracy $cfgAcc", cfgAcc >= 0.9)
    }

    @Test
    fun blurAucOnStills() {
        val (sharp, blurry) = blurScores(20, 720, 960)
        val a = auc(sharp, blurry)
        val (t, acc) = bestThreshold(sharp, blurry)
        val cfgT = DetectionConfig.DEFAULT.stillBlurThreshold
        val cfgAcc = 0.5 * (sharp.count { it >= cfgT }.toDouble() / sharp.size + blurry.count { it < cfgT }.toDouble() / blurry.size)
        println("[blur 960px] 20 pages x ${sigmas.size} sigmas: AUC=%.4f best threshold=%.4f (balanced acc %.3f); config %.4f -> %.3f".format(a, t, acc, cfgT, cfgAcc))
        assertTrue("AUC $a", a >= 0.95)
    }

    @Test
    fun incompletePagesOn40Crops() {
        var correct = 0
        val wrong = ArrayList<String>()
        val w = 240; val h = 320
        for (i in 0 until 40) {
            val isCut = i % 2 == 0
            val bg = Background.entries[i % 6]
            val light = if (i % 5 == 4) Light.SIDE_LIT else Light.NORMAL
            val s = if (isCut) {
                SceneGenerator.page(bg, light, SceneGenerator.TILTS[i % 4], 3000 + i, w, h, cutSide = (i / 2) % 8, cutFrac = 0.08f + 0.2f * ((i * 7) % 10) / 10f)
            } else {
                SceneGenerator.page(bg, light, SceneGenerator.TILTS[i % 4], 3000 + i, w, h, areaRange = 0.28f..0.6f, margin = 0.07f)
            }
            val det = QuadDetector(320).detectBest(listOf({ ImageFilters.toGray(s.px, w, h) }, { ImageFilters.paperMap(s.px, w, h) }))
            val said = when {
                det == null -> "none"
                QualityMetrics.isComplete(det.quad.map { Pt(it.x / w, it.y / h) }, det.cut) -> "whole"
                else -> "cut"
            }
            val ok = said == (if (isCut) "cut" else "whole")
            if (ok) correct++ else wrong += "${s.label} cut=$isCut said=$said"
        }
        println("[incomplete] 40 crops (20 cut, 20 whole): ${correct}/40 = %.1f%% correct".format(correct * 100f / 40))
        wrong.forEach { println("[incomplete]   wrong: $it") }
        assertTrue("$correct/40", correct >= 38)
    }

    @Test
    fun glareLightingAndSkewMeasures() {
        val w = 720; val h = 960
        val s = SceneGenerator.page(Background.DARK_WOOD, Light.NORMAL, 10, 4242, w, h)
        val gray = ImageFilters.toGray(s.px, w, h)
        assertEquals(0f, QualityMetrics.glare(gray, s.quad), 0.002f)
        // A specular spot (clipped) on 4% of the page area.
        val q = s.quad!!
        val c = Pt(q.points.map { it.x }.average().toFloat(), q.points.map { it.y }.average().toFloat())
        val r = kotlin.math.sqrt(0.04f * q.area() / Math.PI.toFloat())
        val spot = gray.data.copyOf()
        for (y in 0 until h) for (x in 0 until w) if ((x - c.x) * (x - c.x) + (y - c.y) * (y - c.y) < r * r) spot[y * w + x] = 255.toByte()
        val g = QualityMetrics.glare(GrayImage(w, h, spot), q)
        assertTrue("glare $g", g > DetectionConfig.DEFAULT.glareShare)
        // Dim and noisy.
        val dim = SceneGenerator.page(Background.DARK_WOOD, Light.DIM, 10, 4242, w, h)
        val lit = QualityMetrics.lightingScore(QualityMetrics.light(gray, q))
        val dark = QualityMetrics.lightingScore(QualityMetrics.light(ImageFilters.toGray(dim.px, w, h), dim.quad))
        println("[light] normal %.2f dim %.2f; glare spot share %.3f".format(lit, dark, g))
        assertTrue(lit >= 0.5f && dark < 0.5f)
        // Residual skew on a rectified page: rotate the outline by 3 degrees about its centre.
        val rot = Math.toRadians(3.0)
        val turned = q.map { p ->
            val dx = p.x - c.x; val dy = p.y - c.y
            Pt(c.x + (dx * Math.cos(rot) - dy * Math.sin(rot)).toFloat(), c.y + (dx * Math.sin(rot) + dy * Math.cos(rot)).toFloat())
        }
        val (ow, oh) = q.outputSize()
        val straight = QualityMetrics.skewDegrees(QualityMetrics.rectify(gray, q, ow, oh)!!)
        val skewed = QualityMetrics.skewDegrees(QualityMetrics.rectify(gray, turned, ow, oh)!!)
        println("[skew] straight %.2f°, outline turned 3° -> %.2f°".format(straight, skewed))
        assertEquals(0f, straight, 0.5f)
        assertEquals(3f, kotlin.math.abs(skewed), 0.75f)
        val report = QualityMetrics.assess(s.px, w, h, q, cut = false)
        assertTrue("clean page should have no warning: $report", QualityMetrics.warnings(report).isEmpty())
        assertEquals(
            listOf(QualityWarning.INCOMPLETE, QualityWarning.BLURRY, QualityWarning.GLARE, QualityWarning.DIM, QualityWarning.SKEWED),
            QualityMetrics.warnings(0.1f, 0f, 0.1f, 0.05f, 5f),
        )
    }
}
