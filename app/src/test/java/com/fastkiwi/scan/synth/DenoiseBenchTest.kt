// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.synth

import com.fastkiwi.scan.core.Denoise
import com.fastkiwi.scan.core.Filter
import com.fastkiwi.scan.core.FilterOptions
import com.fastkiwi.scan.core.ImageFilters
import com.fastkiwi.scan.core.IntPixels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2.0-cam2, PRD §4.2 noise reduction. Synthetic glyph pages with sensor-like noise (Gaussian and
 * Poisson-like shot noise, several levels), each run through a filter with and without the
 * noise reduction and compared, in the text block, with the clean page through the same filter:
 * PSNR, SSIM and the glyph-error OCR proxy (GlareBenchTest). Clean pages must not change at all.
 */
class DenoiseBenchTest {

    private val w = 1000
    private val h = 1340

    private val levels = listOf(
        DocPages.NoiseKind.GAUSSIAN to 4f, DocPages.NoiseKind.GAUSSIAN to 8f,
        DocPages.NoiseKind.GAUSSIAN to 12f, DocPages.NoiseKind.GAUSSIAN to 16f,
        DocPages.NoiseKind.POISSON to 8f, DocPages.NoiseKind.POISSON to 14f,
    )

    private fun run(f: Filter, px: IntArray, denoise: Boolean): IntArray =
        px.copyOf().also { ImageFilters.apply(f, IntPixels(it, w, h), FilterOptions(denoise = denoise)) }

    @Test
    fun noisyPagesGainCleanPagesUnchanged() {
        val pages = (0 until 3).map { DocPages.page(4200 + it, w, h) }
        val filters = listOf(Filter.ENHANCED, Filter.GRAYSCALE, Filter.WHITEN)
        var cases = 0; var psnrWins = 0; var glyphWins = 0
        for (f in filters) {
            var dPsnr = 0.0; var dSsim = 0.0; var gOff = 0.0; var gOn = 0.0; var n = 0
            val perLevel = StringBuilder()
            for ((kind, sigma) in levels) {
                var lp = 0.0; var lg0 = 0.0; var lg1 = 0.0
                for ((pi, p) in pages.withIndex()) {
                    val ref = DocPages.luma(run(f, p.px, denoise = false))
                    val noisy = DocPages.noisy(p.px, sigma, kind, 77L + pi)
                    val off = DocPages.luma(run(f, noisy, denoise = false))
                    val on = DocPages.luma(run(f, noisy, denoise = true))
                    val bitonal = f == Filter.BW
                    val pOff = DocPages.psnr(off, ref, w, h); val pOn = DocPages.psnr(on, ref, w, h)
                    val sOff = DocPages.ssim(off, ref, w, h); val sOn = DocPages.ssim(on, ref, w, h)
                    val eOff = DocPages.glyphError(off, p.ink, w, h, bitonal); val eOn = DocPages.glyphError(on, p.ink, w, h, bitonal)
                    dPsnr += pOn - pOff; dSsim += sOn - sOff; gOff += eOff; gOn += eOn; n++
                    lp += pOn - pOff; lg0 += eOff; lg1 += eOn
                    cases++
                    if (pOn > pOff) psnrWins++
                    if (eOn <= eOff) glyphWins++
                }
                perLevel.append(" %s%.0f: +%.1f dB, glyph %.3f->%.3f;".format(kind.name.take(1), sigma, lp / pages.size, lg0 / pages.size, lg1 / pages.size))
            }
            println("[denoise] %-9s mean PSNR gain %+.2f dB, SSIM gain %+.3f, glyph error %.3f -> %.3f |%s".format(f, dPsnr / n, dSsim / n, gOff / n, gOn / n, perLevel))
            assertTrue("$f PSNR gain", dPsnr / n > 1.0)
            assertTrue("$f glyph error", gOn <= gOff)
        }
        println("[denoise] PSNR better on $psnrWins/$cases noisy cases, glyph error not worse on $glyphWins/$cases")
        assertTrue(psnrWins >= cases * 0.9)
        // Clean pages (no noise, or the mild noise of a well-lit capture): bit-identical output.
        for ((pi, p) in pages.withIndex()) for (sigma in listOf(0f, 1f, 2f)) {
            val src = DocPages.noisy(p.px, sigma, DocPages.NoiseKind.GAUSSIAN, 5L + pi)
            val est = Denoise.estimateSigma(IntPixels(src, w, h))
            for (f in Filter.entries) {
                val d = DocPages.maxAbsDiff(run(f, src, true), run(f, src, false))
                assertEquals("clean page $pi sigma $sigma (est %.2f) $f".format(est), 0, d)
            }
        }
    }

    @Test
    fun originalColourIsNeverDenoised() {
        val p = DocPages.page(4300, 600, 800)
        val noisy = DocPages.noisy(p.px, 12f, DocPages.NoiseKind.GAUSSIAN, 3)
        val out = noisy.copyOf()
        assertEquals(0f, ImageFilters.apply(Filter.ORIGINAL, IntPixels(out, 600, 800)))
        assertTrue(out.contentEquals(noisy))
        // Black & white: measured without a gain (glyph error 0.044 -> 0.049 when it was on), so off.
        assertEquals(0f, ImageFilters.apply(Filter.BW, IntPixels(noisy.copyOf(), 600, 800)))
        // The three others reduce it.
        for (f in listOf(Filter.ENHANCED, Filter.WHITEN, Filter.GRAYSCALE)) assertTrue(ImageFilters.apply(f, IntPixels(noisy.copyOf(), 600, 800)) > 3f)
    }

    @Test
    fun estimateFollowsTheNoise() {
        val p = DocPages.page(4400, w, h)
        val lines = StringBuilder()
        for (sigma in listOf(0f, 2f, 4f, 8f, 12f, 16f)) {
            val est = Denoise.estimateSigma(IntPixels(DocPages.noisy(p.px, sigma, DocPages.NoiseKind.GAUSSIAN, 1), w, h))
            lines.append(" %.0f->%.2f".format(sigma, est))
            // DocPages adds luma 0.9 sigma plus a little colour: the luminance sigma is ~0.92 sigma.
            assertEquals("sigma $sigma", sigma * 0.92f, est, 0.6f + 0.08f * sigma)
        }
        println("[denoise] estimate (true -> measured):$lines")
        // A fine checked pattern is texture, not noise: left alone.
        val check = IntArray(200 * 200) { val v = if (it % 2 == 0) 100 else 150; (0xFF shl 24) or (v shl 16) or (v shl 8) or v }
        assertTrue(Denoise.estimateSigma(IntPixels(check, 200, 200)) > Denoise.MAX_SIGMA)
    }

    @Test
    fun costOn2400x3200IsBounded() {
        val pw = 2400; val ph = 3200
        val page = DocPages.page(4500, pw, ph)
        val noisy = DocPages.noisy(page.px, 8f, DocPages.NoiseKind.GAUSSIAN, 9)
        fun time(f: Filter, denoise: Boolean, src: IntArray): Double {
            var best = Double.MAX_VALUE
            repeat(3) {
                val px = src.copyOf()
                val t0 = System.nanoTime()
                ImageFilters.apply(f, IntPixels(px, pw, ph), FilterOptions(denoise = denoise))
                best = minOf(best, (System.nanoTime() - t0) / 1e6)
            }
            return best
        }
        val lines = StringBuilder()
        var worst = 0.0
        for (f in listOf(Filter.ENHANCED, Filter.GRAYSCALE, Filter.WHITEN)) {
            val off = time(f, false, noisy)
            val on = time(f, true, noisy)
            val clean = time(f, true, page.px)
            worst = maxOf(worst, on - off)
            lines.append(" %s: %.0f ms -> %.0f ms with denoise (clean page %.0f ms);".format(f, off, on, clean))
        }
        println("[denoise] 2400x3200 JVM (best of 3):$lines")
        assertTrue("denoise adds $worst ms", worst < 1500)
    }
}
