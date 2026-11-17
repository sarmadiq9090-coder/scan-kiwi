// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Page order of a book (per document): Arabic books right page first, English left page first. */
enum class ReadingOrder { RTL, LTR }

/**
 * The spine of an open book on the rectified two-page spread: x as a fraction of the spread
 * width at the top ([top]) and the bottom ([bottom]) edge, so a slightly slanted spine is kept.
 */
data class Spine(val top: Float, val bottom: Float, val confidence: Float = 1f) {
    val mid: Float get() = (top + bottom) / 2f
}

/**
 * Book mode (A4): find the spine in a two-page spread and split it into two pages, each with its
 * own 4-point perspective correction (which also flattens a mild curl per half). No curved-page
 * dewarping: lines that bend near the spine stay bent (said in the UI and the docs).
 *
 * The spine is the gutter: a darker valley (shadow or crease) inside a band without text.
 */
object BookSplit {

    /** Finds the spine on a rectified spread (grey, any size; the search runs on the middle 30%). */
    fun findSpine(spread: GrayImage): Spine {
        val top = spineIn(spread, 0.08f, 0.5f)
        val bottom = spineIn(spread, 0.5f, 0.92f)
        val whole = spineIn(spread, 0.08f, 0.92f)
        // Halves that disagree a lot mean one of them was fooled (a picture, a short page): use the whole.
        return if (abs(top.first - bottom.first) > 0.03f) Spine(whole.first, whole.first, whole.second)
        else Spine(top.first, bottom.first, min(top.second, bottom.second))
    }

    /** (spine x as a fraction of the width, confidence 0..1) from the rows between [v0] and [v1]. */
    private fun spineIn(img: GrayImage, v0: Float, v1: Float): Pair<Float, Float> {
        val w = img.width
        val h = img.height
        val y0 = (v0 * h).toInt()
        val y1 = max(y0 + 1, (v1 * h).toInt())
        val hist = IntArray(256)
        for (y in y0 until y1 step 2) for (x in 0 until w step 2) hist[img[x, y]]++
        val paper = percentile(hist, 0.9)
        val inkLevel = (paper * 0.6f).toInt()
        val bright = FloatArray(w)
        val ink = FloatArray(w)
        for (x in 0 until w) {
            var s = 0f
            var k = 0
            for (y in y0 until y1) {
                val v = img[x, y]
                s += v
                if (v < inkLevel) k++
            }
            bright[x] = s / (y1 - y0)
            ink[x] = k.toFloat() / (y1 - y0)
        }
        val r = max(1, w / 200)
        val b = smooth(bright, r)
        val t = smooth(ink, max(1, w / 100))
        val lo = (0.35f * w).toInt()
        val hi = (0.65f * w).toInt()
        // Text-free band: columns whose ink is a small share of the typical text column.
        val textCols = t.filter { it > 0f }.sorted()
        val typical = if (textCols.isEmpty()) 0f else textCols[textCols.size * 3 / 4]
        val free = BooleanArray(w) { t[it] <= 0.12f * typical }
        // Gutter valley: how far each column is below the brightest column near it.
        val win = max(2, w / 16)
        val depth = FloatArray(w)
        for (x in lo..hi) {
            var m = 0f
            for (k in max(0, x - win)..min(w - 1, x + win)) m = max(m, b[k])
            depth[x] = (m - b[x]) / max(1f, paper.toFloat())
        }
        // Score: valley depth inside text-free columns, plus a little for the band itself.
        var best = -1
        var bestScore = -1f
        for (x in lo..hi) {
            val sc = depth[x] * (if (free[x]) 1f else 0.25f)
            if (sc > bestScore) { bestScore = sc; best = x }
        }
        // The text-free run around the centre (or the best column), for flat spreads without a valley.
        val seed = if (best >= 0 && free[best]) best else (lo..hi).filter { free[it] }.minByOrNull { abs(it - w / 2) } ?: (w / 2)
        var a = seed
        while (a > 0 && free[a - 1]) a--
        var z = seed
        while (z < w - 1 && free[z + 1]) z++
        val bandCentre = (a + z) / 2f
        val valley = bestScore >= 0.02f
        val x = if (valley) refineMin(b, best, max(1, w / 100)) else bandCentre
        val conf = if (valley) min(1f, bestScore / 0.08f) else if (free[seed]) 0.4f else 0.1f
        return x / w to conf
    }

    /** Sub-pixel minimum of [b] near [x] (parabola through three samples). */
    private fun refineMin(b: FloatArray, x: Int, r: Int): Float {
        var m = x
        for (k in max(1, x - r)..min(b.size - 2, x + r)) if (b[k] < b[m]) m = k
        if (m <= 0 || m >= b.size - 1) return m.toFloat()
        val l = b[m - 1]; val c = b[m]; val rr = b[m + 1]
        val den = l - 2 * c + rr
        return if (abs(den) < 1e-6f) m.toFloat() else m + 0.5f * (l - rr) / den
    }

    /**
     * The two pages of a spread whose outline is [spread] (any coordinates, e.g. normalised source):
     * the spine is mapped from the rectified spread back through the same homography.
     */
    fun halves(spread: Quad, spine: Spine): Pair<Quad, Quad>? {
        val h = Homography.squareToQuad(spread) ?: return null
        val st = h.map(spine.top.coerceIn(0.05f, 0.95f), 0f)
        val sb = h.map(spine.bottom.coerceIn(0.05f, 0.95f), 1f)
        val left = Quad(spread.tl, st, sb, spread.bl)
        val right = Quad(st, spread.tr, spread.br, sb)
        return left to right
    }

    /** Which half comes first: 0 = left page, 1 = right page. */
    fun order(o: ReadingOrder): List<Int> = if (o == ReadingOrder.RTL) listOf(1, 0) else listOf(0, 1)

    /**
     * Guesses the reading direction from the text layout: Arabic paragraphs end with a short line
     * that leaves the LEFT side empty (ragged left) and English ones the RIGHT side. Null when the
     * page does not say (no paragraphs, pictures): the document setting or app language decides.
     */
    fun guessDirection(spread: GrayImage, spine: Spine): ReadingOrder? {
        val w = spread.width
        val sx = (spine.mid * w).toInt()
        var rtl = 0
        var ltr = 0
        for ((x0, x1) in listOf(0 to sx, sx to w)) {
            val (r, l) = raggedLines(spread, x0, x1)
            rtl += r; ltr += l
        }
        return when {
            rtl >= 2 && rtl >= 2 * ltr -> ReadingOrder.RTL
            ltr >= 2 && ltr >= 2 * rtl -> ReadingOrder.LTR
            else -> null
        }
    }

    /** Counts (short lines leaving the left side empty, short lines leaving the right side empty). */
    private fun raggedLines(img: GrayImage, x0: Int, x1: Int): Pair<Int, Int> {
        val h = img.height
        val hist = IntArray(256)
        for (y in 0 until h step 2) for (x in x0 until x1 step 2) hist[img[x, y]]++
        val ink = (percentile(hist, 0.9) * 0.6f).toInt()
        // Text lines from the row profile; keep a margin so the gutter shadow does not count.
        val m = (0.04f * (x1 - x0)).toInt()
        val rowInk = IntArray(h)
        for (y in 0 until h) {
            var k = 0
            for (x in x0 + m until x1 - m) if (img[x, y] < ink) k++
            rowInk[y] = k
        }
        val lines = ArrayList<IntArray>() // [start x, end x]
        var y = 0
        while (y < h) {
            if (rowInk[y] < 2) { y++; continue }
            val ys = y
            while (y < h && rowInk[y] >= 2) y++
            if (y - ys < 2) continue
            var first = Int.MAX_VALUE
            var last = -1
            for (yy in ys until y) for (x in x0 + m until x1 - m) if (img[x, yy] < ink) {
                if (x < first) first = x
                if (x > last) last = x
            }
            if (last > first) lines += intArrayOf(first, last)
        }
        if (lines.size < 4) return 0 to 0
        val starts = lines.map { it[0] }.sorted()
        val ends = lines.map { it[1] }.sorted()
        val left = starts[starts.size / 10]
        val right = ends[ends.size * 9 / 10]
        val width = (right - left).coerceAtLeast(1)
        var raggedLeft = 0
        var raggedRight = 0
        for (l in lines) {
            val gapL = (l[0] - left).toFloat() / width
            val gapR = (right - l[1]).toFloat() / width
            if (gapL > 0.1f && gapR < 0.03f) raggedLeft++
            if (gapR > 0.1f && gapL < 0.03f) raggedRight++
        }
        return raggedLeft to raggedRight
    }

    private fun smooth(a: FloatArray, r: Int): FloatArray {
        val out = FloatArray(a.size)
        for (i in a.indices) {
            var s = 0f
            var n = 0
            for (k in max(0, i - r)..min(a.size - 1, i + r)) { s += a[k]; n++ }
            out[i] = s / n
        }
        return out
    }

    private fun percentile(hist: IntArray, p: Double): Int {
        val total = hist.sum()
        val target = (total * p).toLong()
        var acc = 0L
        for (i in hist.indices) {
            acc += hist[i]
            if (acc > target) return i
        }
        return 255
    }
}
