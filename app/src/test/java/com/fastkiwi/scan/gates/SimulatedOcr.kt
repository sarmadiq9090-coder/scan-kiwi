// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.gates

import android.graphics.RectF
import com.fastkiwi.scan.ocr.OcrBox
import com.fastkiwi.scan.ocr.OcrEngine
import com.fastkiwi.scan.ocr.OcrInput
import com.fastkiwi.scan.ocr.OcrOptions
import com.fastkiwi.scan.ocr.OcrResult
import com.fastkiwi.scan.ocr.RecognizedLine
import com.fastkiwi.scan.ocr.RecognizedWord
import com.fastkiwi.scan.ocr.TextDirection
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * The simulated phone's text recognition (installed through `ocr/OcrSeam` by the black-box driver).
 * Tesseract and MNN ship Android libraries only, so the driver's JVM cannot run them (BLACKBOX-2.0 #2).
 * This engine knows the invented sheets of [SyntheticPages]: it recognises which sheet (or which half of
 * the book spread) a page image shows, by comparing a small grey thumbnail and the page's shape, and
 * returns that sheet's lines and words with their boxes on the page. A page that matches no sheet
 * well (a blank page, a photo of the desk) has no text. Everything after recognize() is the app's code.
 */
object SimulatedOcr : OcrEngine {
    override val id = "blackbox-sim"
    override val version = 1

    private const val GX = 12
    private const val SW = 120
    private const val R = 4
    private const val GY = 16
    /** Correlation a page must reach to count as a sheet (a desk photo or a blank page stays below). */
    private const val MIN_SCORE = 0.45f

    private class Candidate(val name: String, val crop: RectF, val thumb: FloatArray, val aspect: Float, val runs: List<SyntheticPages.Run>)

    @Volatile private var candidates: List<Candidate>? = null

    /** The best score of the last match (for tests of the engine itself). */
    @Volatile var lastScore = 0f
        private set

    /** Renders the sheets once (call on the test thread; recognize() runs on a worker). */
    fun prepare() {
        if (candidates != null) return
        val list = ArrayList<Candidate>()
        for (spec in SyntheticPages.catalogue) {
            if (spec.id == "desk") continue
            val (bmp, runs) = SyntheticPages.renderWithText(spec.id, 480)
            val px = IntArray(bmp.width * bmp.height)
            bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
            val gray = ByteArray(px.size) { i -> val c = px[i]; (((c shr 16 and 255) * 77 + (c shr 8 and 255) * 150 + (c and 255) * 29) shr 8).toByte() }
            // Also the parts the app may keep: each half of the spread, and a card or invoice whose dark
            // header band the page detection leaves out (the outline then starts under the band).
            val crops = listOf(RectF(0f, 0f, 1f, 1f)) + when (spec.id) {
                "book-spread" -> listOf(RectF(0f, 0f, 0.5f, 1f), RectF(0.5f, 0f, 1f, 1f))
                "id-card" -> listOf(RectF(0f, 0.19f, 1f, 1f))
                "invoice" -> listOf(RectF(0f, 0.085f, 1f, 1f))
                else -> emptyList()
            }
            for (c in crops) {
                val x0 = (c.left * bmp.width).toInt()
                val x1 = (c.right * bmp.width).toInt()
                val y0 = (c.top * bmp.height).toInt()
                val y1 = (c.bottom * bmp.height).toInt()
                val mine = runs.filter { r -> r.box.centerX() in c.left..c.right && r.box.centerY() in c.top..c.bottom }
                list.add(Candidate(spec.id, c, thumb(gray, bmp.width, x0, y0, x1, y1), (y1 - y0).toFloat() / (x1 - x0), mine))
            }
            bmp.recycle()
        }
        candidates = list
    }

    /** Which sheet a page image shows, or null (for tests of the engine itself). */
    fun identify(page: OcrInput): String? = best(page)?.name

    private fun best(page: OcrInput): Candidate? {
        val all = candidates ?: run { prepare(); candidates!! }
        val t = thumb(page.gray, page.width, 0, 0, page.width, page.height)
        val aspect = page.height.toFloat() / page.width
        var top: Candidate? = null
        var topScore = -1f
        for (c in all) {
            var dot = 0f
            for (i in t.indices) dot += t[i] * c.thumb[i]
            val score = dot / t.size - 0.6f * abs(ln(aspect / c.aspect))
            if (score > topScore) { topScore = score; top = c }
        }
        lastScore = topScore
        return top?.takeIf { topScore >= MIN_SCORE }
    }

    /** Pages read so far (for tests). */
    val calls = java.util.concurrent.atomic.AtomicInteger()

    override suspend fun recognize(page: OcrInput, options: OcrOptions): OcrResult {
        calls.incrementAndGet()
        val c = best(page) ?: return OcrResult.empty(page.pageWidth, page.pageHeight, id, version)
        val fw = page.pageWidth.toFloat()
        val fh = page.pageHeight.toFloat()
        fun box(r: RectF) = OcrBox(
            (((r.left - c.crop.left) / c.crop.width()) * fw).toInt().coerceIn(0, page.pageWidth - 1),
            (((r.top - c.crop.top) / c.crop.height()) * fh).toInt().coerceIn(0, page.pageHeight - 1),
            (((r.right - c.crop.left) / c.crop.width()) * fw).toInt().coerceIn(1, page.pageWidth),
            (((r.bottom - c.crop.top) / c.crop.height()) * fh).toInt().coerceIn(1, page.pageHeight),
        )
        val lines = c.runs.sortedBy { it.box.top }.map { run ->
            val words = run.words.map { (w, r) -> RecognizedWord(w, box(r), 0.96f) }
            RecognizedLine(words, box(run.box), 0.96f, if (run.rtl) TextDirection.RTL else TextDirection.LTR)
        }
        val rtl = lines.count { it.direction == TextDirection.RTL } * 2 >= lines.size
        return OcrResult(lines, page.pageWidth, page.pageHeight, id, version, if (rtl) TextDirection.RTL else TextDirection.LTR)
    }

    override fun close() = Unit

    /**
     * Where the ink is: the region is shrunk to [SW] px wide, each pixel is compared with its blurred
     * surroundings (so room light, shadows and the app's colour filters cancel out), and the share of
     * dark-against-surroundings pixels per cell of a GX x GY grid is returned, zero mean and unit variance.
     * A 6 % border is left out (crop edges, desk remnants).
     */
    private fun thumb(gray: ByteArray, stride: Int, x0: Int, y0: Int, x1: Int, y1: Int): FloatArray {
        val bw = x1 - x0
        val bh = y1 - y0
        val sw = SW
        val sh = maxOf(8, (SW.toLong() * bh / bw).toInt())
        val small = FloatArray(sw * sh)
        for (y in 0 until sh) for (x in 0 until sw) {
            val ax = x0 + x * bw / sw
            val bx = maxOf(ax + 1, x0 + (x + 1) * bw / sw)
            val ay = y0 + y * bh / sh
            val by = maxOf(ay + 1, y0 + (y + 1) * bh / sh)
            var s = 0L
            var n = 0
            for (yy in ay until by) for (xx in ax until bx) { s += gray[yy * stride + xx].toInt() and 255; n++ }
            small[y * sw + x] = s.toFloat() / n
        }
        // Integral image for the surroundings (box of radius R).
        val ii = DoubleArray((sw + 1) * (sh + 1))
        for (y in 0 until sh) {
            var row = 0.0
            for (x in 0 until sw) { row += small[y * sw + x]; ii[(y + 1) * (sw + 1) + x + 1] = ii[y * (sw + 1) + x + 1] + row }
        }
        val out = FloatArray(GX * GY)
        val cnt = IntArray(GX * GY)
        val mx = sw * 6 / 100
        val my = sh * 6 / 100
        for (y in my until sh - my) for (x in mx until sw - mx) {
            val ax = maxOf(0, x - R); val bx = minOf(sw, x + R + 1)
            val ay = maxOf(0, y - R); val by = minOf(sh, y + R + 1)
            val mean = (ii[by * (sw + 1) + bx] - ii[ay * (sw + 1) + bx] - ii[by * (sw + 1) + ax] + ii[ay * (sw + 1) + ax]) / ((bx - ax) * (by - ay))
            val cell = ((y - my) * GY / (sh - 2 * my)) * GX + (x - mx) * GX / (sw - 2 * mx)
            if (small[y * sw + x] < mean * 0.9) out[cell] += 1f
            cnt[cell]++
        }
        for (i in out.indices) out[i] /= maxOf(1, cnt[i])
        val avg = out.average().toFloat()
        var v = 0f
        for (i in out.indices) { out[i] -= avg; v += out[i] * out[i] }
        val sd = sqrt(v / out.size)
        if (sd < 0.005f) return FloatArray(out.size) // no ink anywhere (blank page, desk): matches nothing
        for (i in out.indices) out[i] /= sd
        return out
    }
}
