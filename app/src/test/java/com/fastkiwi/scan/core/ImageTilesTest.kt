// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.core

import com.fastkiwi.scan.synth.DocPages
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2.0-cam2, A8 high-resolution processing: filters run in strips (tiles of full rows with an
 * overlap for the noise reduction). Proves (1) the strip engine reproduces the 2.0.0 filters bit
 * for bit, (2) the strip height never changes the result (no seams), including above 12 MP, and
 * (3) the working memory for a 50 MP page stays bounded.
 */
class ImageTilesTest {

    private fun noisyPage(w: Int, h: Int, sigma: Float, seed: Int): IntArray {
        val p = DocPages.page(seed, w, h)
        return DocPages.noisy(p.px, sigma, DocPages.NoiseKind.GAUSSIAN, seed.toLong())
    }

    @Test
    fun stripEngineMatchesThe200FiltersExactly() {
        // Clean pages (no denoise triggered) and noisy pages with denoise off: identical to 2.0.0.
        for ((i, sigma) in listOf(0f, 1.5f, 8f).withIndex()) {
            val w = 401 + 37 * i; val h = 533 + 29 * i
            val src = noisyPage(w, h, sigma, 70 + i)
            for (f in Filter.entries) {
                val want = src.copyOf().also { LegacyFilters.apply(f, it, w, h) }
                val got = src.copyOf()
                val opt = if (sigma > 3f) FilterOptions(denoise = false, stripPixels = w * 13) else FilterOptions(stripPixels = w * 13)
                val treated = ImageFilters.apply(f, IntPixels(got, w, h), opt)
                assertEquals("$f sigma $sigma: no denoise", 0f, treated)
                assertArrayEquals("$f sigma $sigma", want, got)
            }
        }
    }

    @Test
    fun stripHeightNeverChangesTheResult() {
        val w = 640; val h = 900
        val src = noisyPage(w, h, 9f, 81)
        for (f in Filter.entries) {
            val whole = src.copyOf().also { ImageFilters.apply(f, IntPixels(it, w, h), FilterOptions(stripPixels = w * h)) }
            for (rows in intArrayOf(1, 7, 16, 63, 200)) {
                val strips = src.copyOf().also { ImageFilters.apply(f, IntPixels(it, w, h), FilterOptions(stripPixels = w * rows)) }
                assertArrayEquals("$f with $rows-row strips", whole, strips)
            }
        }
    }

    @Test
    fun above12MegapixelsTilesAreSeamFree() {
        // 4096 x 3200 = 13.1 MP: one strip (the whole page) vs the default strips.
        val w = 4096; val h = 3200
        val src = IntArray(w * h)
        fillTextured(src, w, h, sigma = 7f, seed = 5)
        for (f in listOf(Filter.ENHANCED, Filter.GRAYSCALE)) {
            val whole = src.copyOf().also { ImageFilters.apply(f, IntPixels(it, w, h), FilterOptions(stripPixels = w * h)) }
            val tiled = src.copyOf()
            val sigma = ImageFilters.apply(f, IntPixels(tiled, w, h))
            var diff = 0
            for (i in whole.indices) if (whole[i] != tiled[i]) diff++
            println("[tiles] %s 13.1 MP: denoised sigma %.1f, pixels differing from the one-strip result: %d".format(f, sigma, diff))
            assertTrue("denoise ran", sigma > 3f)
            assertEquals(0, diff)
        }
    }

    @Test
    fun fiftyMegapixelPageWorkingMemoryIsBounded() {
        val w = 8160; val h = 6120 // 49.9 MP
        val px = IntArray(w * h)
        fillTextured(px, w, h, sigma = 8f, seed = 9)
        // java.lang.management is not on the Android compile classpath: reach the JVM's bean by reflection.
        val mx = Class.forName("java.lang.management.ManagementFactory").getMethod("getThreadMXBean").invoke(null)
        val allocatedOf = Class.forName("com.sun.management.ThreadMXBean").getMethod("getThreadAllocatedBytes", java.lang.Long.TYPE)
        @Suppress("DEPRECATION") val tid = Thread.currentThread().id
        fun allocated(): Long = allocatedOf.invoke(mx, tid) as Long
        for (f in listOf(Filter.ENHANCED, Filter.WHITEN, Filter.GRAYSCALE, Filter.BW)) {
            val store = IntPixels(px.copyOf(), w, h)
            System.gc()
            val before = allocated()
            val t0 = System.nanoTime()
            val sigma = ImageFilters.apply(f, store)
            val ms = (System.nanoTime() - t0) / 1e6
            val allocatedMb = (allocated() - before) / 1e6
            // Everything the filter allocated is an upper bound of its peak working memory.
            println("[tiles] %s on 50 MP (%.0f MB page): working memory <= %.1f MB, %.0f ms, sigma %.1f".format(f, w * h * 4 / 1e6, allocatedMb, ms, sigma))
            assertTrue("$f allocated $allocatedMb MB", allocatedMb < 64.0)
        }
        // A one-piece filter would need the page copied (getPixels) plus 8 float planes for the denoise.
        println("[tiles] one-piece equivalent: >= %.0f MB".format(w.toLong() * h * 4 * 9 / 1e6))
    }

    /** Fast textured page: text-like bars on shaded paper plus Gaussian-like noise (cheap RNG). */
    private fun fillTextured(px: IntArray, w: Int, h: Int, sigma: Float, seed: Int) {
        var s = seed * 2654435761L + 1
        fun noise(): Float {
            // Sum of 4 uniforms ~ Gaussian (Irwin-Hall), unit variance.
            var acc = 0f
            repeat(4) { s = s * 6364136223846793005L + 1442695040888963407L; acc += ((s ushr 40) and 0xFFFF) / 65535f }
            return (acc - 2f) * 1.732f
        }
        val line = h / 60
        for (y in 0 until h) for (x in 0 until w) {
            val paper = 236f - 30f * x / w
            val ink = (y % line) in (line / 3) until (line / 3 + line / 6) && ((x / (w / 97)) % 5 != 0) && ((x * 7 + y / line * 13) % 23 > 4)
            val v = (if (ink) 40f else paper) + noise() * sigma
            val c = v.toInt().coerceIn(0, 255)
            px[y * w + x] = (0xFF shl 24) or (c shl 16) or (c shl 8) or c
        }
    }
}
