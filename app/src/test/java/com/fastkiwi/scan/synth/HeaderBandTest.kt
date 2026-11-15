// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.synth

import com.fastkiwi.scan.core.Detection
import com.fastkiwi.scan.core.HeaderBand
import com.fastkiwi.scan.core.ImageFilters
import com.fastkiwi.scan.core.QuadDetector
import com.fastkiwi.scan.synth.SceneGenerator.Background
import com.fastkiwi.scan.synth.SceneGenerator.Light
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Content loss (developer report, BLACKBOX-2.0 S3): a dark band printed along the paper's edge (an invoice
 * title bar, an ID card header) was left outside the page, so its text was cut away. Pages of the still
 * path (480 px, both channels, then [HeaderBand]) with such a band must keep all four corners within 3 % of
 * the diagonal, and pages without one must not change (the edge benchmark, run with the same step).
 */
class HeaderBandTest {

    private val bandColors = listOf(
        intArrayOf(30, 70, 150), // navy title bar
        intArrayOf(32, 32, 38), // near black
        intArrayOf(140, 28, 38), // dark red
        intArrayOf(25, 95, 60), // dark green
    )

    /** 6 backgrounds x 3 lights x 3 tilts, a band of 6-12 % of the page height with white title text in it. */
    private fun bandScenes(w: Int = 360, h: Int = 480, bottom: Boolean = false): List<SceneGenerator.Scene> {
        val out = ArrayList<SceneGenerator.Scene>()
        var seed = 7100
        for (bg in Background.entries) for (light in Light.entries) for (tilt in intArrayOf(0, 10, 20)) {
            val rnd = java.util.Random((seed++).toLong())
            val quad = SceneGenerator.placePage(rnd, tilt.toFloat(), w, h, 0.30f..0.55f, 0.08f)
            val body = SceneGenerator.paperFn(rnd)
            // On cloth (blue, red or green) the band is black, as a dark bar on cloth of its own colour cannot be told apart.
            val c = if (bg == Background.CLOTH) bandColors[1] else bandColors[rnd.nextInt(bandColors.size)]
            val band = 0.06f + rnd.nextFloat() * 0.06f
            val paper: (Float, Float) -> Int = { u, v ->
                val bv = if (bottom) 1f - v else v
                if (bv < band) {
                    val t = bv / band
                    val title = t in 0.3f..0.7f && (u in 0.08f..0.34f || u in 0.6f..0.9f) && ((u * 60).toInt() % 3 != 0)
                    if (title) SceneGenerator.rgb(250, 250, 250) else SceneGenerator.rgb(c[0], c[1], c[2])
                } else body(u, v)
            }
            val bgf = SceneGenerator.backgroundFn(bg, rnd, w, h)
            val lf = SceneGenerator.lightFn(light, rnd, w, h)
            out += SceneGenerator.renderFrame("band-$bg-$light-tilt$tilt-s$seed", w, h, quad, paper, bgf, lf, SceneGenerator.noiseOf(light), rnd)
        }
        return out
    }

    private fun still(s: SceneGenerator.Scene, band: Boolean): Detection? {
        val d = QuadDetector(480).detectBest(listOf({ ImageFilters.toGray(s.px, s.w, s.h) }, { ImageFilters.paperMap(s.px, s.w, s.h) }))
        return if (band) HeaderBand.extend(d, s.px, s.w, s.h) else d
    }

    private fun hitRate(scenes: List<SceneGenerator.Scene>, band: Boolean, misses: MutableList<String>): Float {
        var hits = 0
        for (s in scenes) {
            val d = still(s, band)
            val gt = s.quad!!
            val err = d?.quad?.maxCornerShift(gt) ?: Float.MAX_VALUE
            if (d != null && !d.cut && err <= 0.03f * SceneGenerator.diagonal(gt)) hits++
            else misses += "${s.label} err=${if (d == null) "none" else "%.1f".format(err)}"
        }
        return hits.toFloat() / scenes.size
    }

    /**
     * On a white table the detector finds no outline at all for a banded page (the band's outer edge and the
     * paper's edge have opposite contrast), so the photo is kept whole and the crop screen asks: no content is
     * lost, but there is nothing to extend. Those 9 scenes are reported, not counted.
     */
    private fun check(name: String, scenes: List<SceneGenerator.Scene>) {
        val counted = scenes.filter { !it.label.contains("WHITE_TABLE") }
        val before = hitRate(counted, band = false, ArrayList())
        val misses = ArrayList<String>()
        val after = hitRate(counted, band = true, misses)
        val white = scenes.filter { it.label.contains("WHITE_TABLE") }.count { still(it, band = true) == null }
        println("[header band] $name: within 3%% without the band step %.1f%%, with it %.1f%% (%d scenes); white table: %d/9 not detected (asked)".format(before * 100, after * 100, counted.size, white))
        misses.forEach { println("[header band]   miss: $it") }
        assertTrue("$name with the band step: $after", after >= 0.90f)
        // The step never makes an outline worse.
        for (s in scenes) {
            val plain = still(s, band = false) ?: continue
            val ext = still(s, band = true)!!
            assertTrue("${s.label}: worse with the band step", ext.quad.maxCornerShift(s.quad!!) <= plain.quad.maxCornerShift(s.quad!!) + 1f)
        }
    }

    @Test
    fun aDarkBandAtThePapersTopStaysInsideThePage() = check("top", bandScenes())

    @Test
    fun aDarkBandAtThePapersBottomStaysInsideThePage() = check("bottom", bandScenes(bottom = true))

    /** The camera/edge benchmark (72 pages, 20 empty scenes) with the band step: no page gets worse, no false page. */
    @Test
    fun theEdgeBenchmarkDoesNotRegress() {
        val plain = EdgeBenchTest.bench { EdgeBenchTest.detectWith(com.fastkiwi.scan.core.DetectionConfig.DEFAULT, it) }
        val band = EdgeBenchTest.bench { HeaderBand.extend(EdgeBenchTest.detectWith(com.fastkiwi.scan.core.DetectionConfig.DEFAULT, it), it.px, it.w, it.h) }
        EdgeBenchTest.report("2.0 + band step", band)
        assertTrue("pages within 3%: ${band.hitRate} < ${plain.hitRate}", band.hitRate >= plain.hitRate)
        assertTrue("false pages: ${band.falseRate}", band.falseRate <= plain.falseRate)
        // Every page found without the step is still found with it.
        val lost = band.misses.filter { it.startsWith("page-") }.map { it.substringBefore(" ") } - plain.misses.map { it.substringBefore(" ") }.toSet()
        assertTrue("pages lost: $lost", lost.isEmpty())
    }
}
