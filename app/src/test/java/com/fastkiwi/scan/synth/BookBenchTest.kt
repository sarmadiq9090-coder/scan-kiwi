// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.synth

import com.fastkiwi.scan.core.BookSplit
import com.fastkiwi.scan.core.GrayImage
import com.fastkiwi.scan.core.Pt
import com.fastkiwi.scan.core.Quad
import com.fastkiwi.scan.core.ReadingOrder
import com.fastkiwi.scan.core.Spine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Book mode (A4, ROADMAP 1.8.0): spine within 2% of the page width on >= 90% of 40 synthetic
 * spreads (flat and mildly curved, two text sizes, Arabic and English layouts); reading order
 * correct in 100%.
 */
class BookBenchTest {

    class Spread(val img: GrayImage, val spine: Spine, val arabic: Boolean, val label: String)

    /** A rectified two-page spread (the outline already corrected), rendered from a seed. */
    private fun spread(seed: Int, curved: Boolean, arabic: Boolean, bigText: Boolean, w: Int = 800, h: Int = 560): Spread {
        val rnd = java.util.Random(seed.toLong())
        val sTop = 0.45f + 0.1f * rnd.nextFloat()
        val sBot = (sTop + (rnd.nextFloat() * 2 - 1) * 0.012f).coerceIn(0.44f, 0.56f)
        val lineH = if (bigText) 0.018f else 0.012f
        val pages = List(2) { layout(rnd, arabic, lineH, outer = 0.08f + 0.04f * rnd.nextFloat(), inner = 0.06f + 0.06f * rnd.nextFloat(), left = it == 0) }
        val px = ByteArray(w * h)
        val curl = if (curved) 0.012f + 0.01f * rnd.nextFloat() else 0f
        for (y in 0 until h) {
            val v = y / h.toFloat()
            val sx = (sTop + (sBot - sTop) * v) * w
            for (x in 0 until w) {
                val left = x < sx
                val pw = if (left) sx else w - sx
                val u = if (left) x / sx else (x - sx) / (w - sx)
                val d = abs(x - sx) / pw // distance from the spine in page widths
                // Lines bend down towards the spine on a curled page.
                val bend = if (curved && d < 0.3f) curl * ((0.3f - d) / 0.3f).let { it * it } else 0f
                var c = 238f
                if (pages[if (left) 0 else 1].ink(u, v - bend)) c = 45f
                // Gutter: a thin crease on a flat spread, a wide shadow on a curled one.
                val dx = (x - sx) / w
                c *= if (curved) 1f - 0.35f * exp(-(dx * dx) / (2 * 0.025f * 0.025f)) else
                    (1f - 0.18f * exp(-(dx * dx) / (2 * 0.004f * 0.004f))) * (1f - 0.06f * exp(-(dx * dx) / (2 * 0.03f * 0.03f)))
                c += (rnd.nextGaussian() * 3).toFloat()
                px[y * w + x] = c.roundToInt().coerceIn(0, 255).toByte()
            }
        }
        val label = "spread-s$seed-${if (curved) "curved" else "flat"}-${if (arabic) "ar" else "en"}-${if (bigText) "big" else "small"}"
        return Spread(GrayImage(w, h, px), Spine(sTop, sBot), arabic, label)
    }

    /** Justified paragraphs; the short last line and the first-line indent sit on the reading side. */
    private class Page(val rows: List<FloatArray>, val lineH: Float) {
        fun ink(u: Float, v: Float): Boolean {
            for (r in rows) {
                if (v < r[0] || v > r[0] + lineH) continue
                var i = 1
                while (i + 1 < r.size) { if (u >= r[i] && u <= r[i + 1]) return true; i += 2 }
            }
            return false
        }
    }

    private fun layout(rnd: java.util.Random, arabic: Boolean, lineH: Float, outer: Float, inner: Float, left: Boolean): Page {
        val l = if (left) outer else inner
        val r = if (left) 1f - inner else 1f - outer
        val rows = ArrayList<FloatArray>()
        var v = 0.08f
        var lineInPara = 0
        var paraLen = 3 + rnd.nextInt(5)
        while (v < 0.92f - lineH) {
            val last = lineInPara == paraLen - 1
            val indent = if (lineInPara == 0) 0.04f * (r - l) else 0f
            val len = if (last) (0.25f + 0.5f * rnd.nextFloat()) * (r - l) else (r - l) - indent
            // Arabic lines start at the right edge, English at the left.
            val (a, b) = if (arabic) (r - indent - len) to (r - indent) else (l + indent) to (l + indent + len)
            val words = ArrayList<Float>()
            var u = a
            while (u < b - 0.01f) {
                val wl = 0.03f + rnd.nextFloat() * 0.07f
                words += u; words += minOf(b, u + wl)
                u += wl + 0.015f
            }
            rows += FloatArray(1 + words.size).also { it[0] = v; for (i in words.indices) it[i + 1] = words[i] }
            v += lineH * 2.2f
            lineInPara++
            if (last) { lineInPara = 0; paraLen = 3 + rnd.nextInt(5); v += lineH }
        }
        return Page(rows, lineH)
    }

    private fun set(): List<Spread> = List(40) { i ->
        spread(7000 + i, curved = i % 2 == 1, arabic = (i / 2) % 2 == 0, bigText = (i / 4) % 2 == 0)
    }

    @Test
    fun spineWithinTwoPercentOfPageWidth() {
        var ok = 0
        var maxErr = 0f
        val bad = ArrayList<String>()
        for (s in set()) {
            val f = BookSplit.findSpine(s.img)
            // Error in units of one page's width (half the spread), the stricter reading.
            val err = max(abs(f.top - s.spine.top), abs(f.bottom - s.spine.bottom)) * 2f
            maxErr = max(maxErr, err)
            if (err <= 0.02f) ok++ else bad += "${s.label} err=%.3f gt=%.3f/%.3f found=%.3f/%.3f".format(err, s.spine.top, s.spine.bottom, f.top, f.bottom)
        }
        println("[book] spine within 2%% of page width: $ok/40 = %.1f%% (max error %.3f page widths)".format(ok * 2.5f, maxErr))
        bad.forEach { println("[book]   miss: $it") }
        assertTrue("$ok/40", ok >= 36)
    }

    @Test
    fun readingOrderAndDirection() {
        var guessed = 0
        var unknown = 0
        for (s in set()) {
            val setting = if (s.arabic) ReadingOrder.RTL else ReadingOrder.LTR
            // Reading order from the document setting: Arabic = right page first.
            assertEquals(if (s.arabic) listOf(1, 0) else listOf(0, 1), BookSplit.order(setting))
            when (BookSplit.guessDirection(s.img, BookSplit.findSpine(s.img))) {
                setting -> guessed++
                null -> unknown++
                else -> println("[book]   wrong direction guess: ${s.label}")
            }
        }
        println("[book] reading order from the setting: 40/40; direction guessed from the layout: $guessed/40 correct, $unknown undecided")
        assertTrue(guessed >= 36)
    }

    @Test
    fun halvesShareTheSpineAndKeepOuterCorners() {
        val q = Quad(Pt(0.1f, 0.12f), Pt(0.92f, 0.08f), Pt(0.95f, 0.9f), Pt(0.05f, 0.86f))
        val (l, r) = BookSplit.halves(q, Spine(0.5f, 0.52f))!!
        assertEquals(q.tl, l.tl); assertEquals(q.bl, l.bl); assertEquals(q.tr, r.tr); assertEquals(q.br, r.br)
        assertEquals(l.tr, r.tl); assertEquals(l.br, r.bl)
        assertNotNull(com.fastkiwi.scan.core.Homography.squareToQuad(l))
        assertTrue(l.isConvex() && r.isConvex())
    }
}
