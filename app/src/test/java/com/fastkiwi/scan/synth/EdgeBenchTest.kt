// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.synth

import com.fastkiwi.scan.core.Detection
import com.fastkiwi.scan.core.DetectionConfig
import com.fastkiwi.scan.core.ImageFilters
import com.fastkiwi.scan.core.QuadDetector
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CAM-01/02/05/06 benchmark on the synthetic set (ROADMAP 1.3.0 acceptance):
 * share of the 72 pages with all four corners within 3% of the page diagonal, false-page rate
 * on 20 empty scenes, and analysis time per 240x320 frame on the JVM (both channels, as the camera runs).
 */
class EdgeBenchTest {

    data class Result(val hitRate: Float, val falseRate: Float, val msPerFrame: Double, val misses: List<String>, val perGroup: Map<String, String>)

    companion object {
        val pages by lazy { SceneGenerator.pageSet() }
        val empty by lazy { SceneGenerator.emptySet() }

        fun bench(detect: (SceneGenerator.Scene) -> Detection?): Result {
            var hits = 0
            val misses = ArrayList<String>()
            val groups = HashMap<String, IntArray>()
            for (s in pages) {
                val d = detect(s)
                val gt = s.quad!!
                val err = d?.quad?.maxCornerShift(gt) ?: Float.MAX_VALUE
                val ok = d != null && !d.cut && err <= 0.03f * SceneGenerator.diagonal(gt)
                if (ok) hits++ else misses += "${s.label} err=${if (d == null) "none" else "%.1f".format(err)}"
                for (key in s.label.split('-').drop(1).take(3)) {
                    val g = groups.getOrPut(key) { IntArray(2) }
                    g[1]++; if (ok) g[0]++
                }
            }
            var falsePages = 0
            for (s in empty) {
                // A false page is any outline the camera would show (whole or cut).
                val d = detect(s)
                if (d != null) { falsePages++; misses += "${s.label} FALSE ${d.quad}" }
            }
            // Timing: warm the JIT, then three timed passes over all 92 frames.
            val all = pages + empty
            all.forEach { detect(it) }
            val t0 = System.nanoTime()
            repeat(3) { all.forEach { detect(it) } }
            val ms = (System.nanoTime() - t0) / 1e6 / (3 * all.size)
            return Result(
                hits.toFloat() / pages.size, falsePages.toFloat() / empty.size, ms, misses,
                groups.toSortedMap().mapValues { (_, v) -> "${v[0]}/${v[1]}" },
            )
        }

        fun report(name: String, r: Result) {
            println("[$name] pages within 3%%: %.1f%%  false pages: %.1f%%  ms/frame: %.2f".format(r.hitRate * 100, r.falseRate * 100, r.msPerFrame))
            println("[$name] per group: ${r.perGroup}")
            r.misses.forEach { println("[$name]   miss: $it") }
        }

        /** The 1.2.0 camera path: luminance first, then the paper channel; first confident wins. */
        fun legacy(s: SceneGenerator.Scene): Detection? = detectWith(DetectionConfig.LEGACY_1_2, s)

        fun detectWith(cfg: DetectionConfig, s: SceneGenerator.Scene): Detection? =
            QuadDetector(320, cfg).detectBest(listOf({ ImageFilters.toGray(s.px, s.w, s.h) }, { ImageFilters.paperMap(s.px, s.w, s.h) }))
    }

    /** The 1.2.0 baseline, measured before any detector change and kept reproducible. */
    @Test
    fun baseline120() {
        val r = bench(::legacy)
        report("1.2.0", r)
        // Recorded in docs/features-2.0/camera.md: 69.4% / 5.0%.
        assertTrue(r.hitRate < 0.75f)
    }

    @Test
    fun acceptance20() {
        val r = bench { detectWith(DetectionConfig.DEFAULT, it) }
        report("2.0", r)
        assertTrue("pages within 3%: ${r.hitRate}", r.hitRate >= 0.90f)
        assertTrue("false pages: ${r.falseRate}", r.falseRate <= 0.05f)
        // 25 ms is the acceptance figure on this PC; the guard is looser so slower CI machines pass.
        assertTrue("ms per frame: ${r.msPerFrame}", r.msPerFrame <= 60.0)
    }
}
