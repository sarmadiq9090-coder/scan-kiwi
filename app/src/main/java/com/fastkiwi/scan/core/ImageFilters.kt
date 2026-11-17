// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.core

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Page looks offered to the user. Stored by name in documents, so never rename entries. */
enum class Filter { ORIGINAL, ENHANCED, WHITEN, GRAYSCALE, BW }

/**
 * Document filters working in place on packed ARGB pixels (as from
 * Bitmap.getPixels). Pure Kotlin: no Android types, unit-testable on the JVM.
 *
 * 2.0-cam2 (A8, PRD §4.2): every filter runs over horizontal strips of a [PixelStore], so a page of
 * any size needs one strip of working memory besides the page itself, never a second full-size copy.
 * Global measures (histograms, white balance, the paper-brightness grid) are gathered over all
 * strips first and then applied strip by strip, so the result is the same for any strip height
 * (seam-free by construction; `ImageTilesTest`). With [FilterOptions.denoise], an edge-preserving
 * noise reduction ([Denoise]) runs first on noisy pages in «ألوان محسّنة», «تبييض» and «رمادي»;
 * «الأصلي» (original colour preservation) is never touched.
 */
object ImageFilters {

    fun apply(filter: Filter, px: IntArray, w: Int, h: Int) {
        apply(filter, IntPixels(px, w, h))
    }

    /**
     * Applies [filter] to the pixels of [s] in place, strip by strip. Returns the noise sigma that
     * was reduced first (0 when the page was clean, the filter is «الأصلي», or denoise is off).
     */
    fun apply(filter: Filter, s: PixelStore, opt: FilterOptions = FilterOptions.DEFAULT): Float {
        if (filter == Filter.ORIGINAL) return 0f
        // «أبيض وأسود» is left out: its threshold already ignores the grain, and smoothing first
        // measurably thickened thin strokes (DenoiseBenchTest).
        val sigma = if (opt.denoise && filter != Filter.BW) Denoise.auto(s, chroma = filter == Filter.ENHANCED || filter == Filter.WHITEN, opt) else 0f
        when (filter) {
            Filter.ORIGINAL -> Unit
            Filter.ENHANCED -> enhanceStore(s, opt)
            Filter.WHITEN -> whitenStore(s, opt)
            Filter.GRAYSCALE -> grayscaleStore(s, opt)
            Filter.BW -> blackWhiteStore(s, opt)
        }
        return sigma
    }

    // ------------------------------------------------------------- helpers

    internal inline fun r(c: Int) = (c shr 16) and 0xFF
    internal inline fun g(c: Int) = (c shr 8) and 0xFF
    internal inline fun b(c: Int) = c and 0xFF
    internal inline fun argb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    internal inline fun clamp(v: Int) = if (v < 0) 0 else if (v > 255) 255 else v
    fun luma(c: Int) = (r(c) * 77 + g(c) * 150 + b(c) * 29) shr 8

    /** Value at the given percentile (0..1) of a 256-bin histogram. */
    private fun percentile(hist: IntArray, total: Int, p: Double): Int {
        val target = (total * p).toLong()
        var acc = 0L
        for (i in 0 until 256) {
            acc += hist[i]
            if (acc > target) return i
        }
        return 255
    }

    private fun percentileAt(hist: IntArray, off: Int, total: Int, p: Double): Int {
        val target = (total * p).toLong()
        var acc = 0L
        for (i in 0 until 256) {
            acc += hist[off + i]
            if (acc > target) return i
        }
        return 255
    }

    /** Rows per strip for [opt] on a [w] px wide page (a multiple of [multipleOf]). */
    internal fun stripRows(w: Int, opt: FilterOptions, multipleOf: Int = 1): Int {
        val rows = max(1, opt.stripPixels / max(1, w))
        return max(multipleOf, rows / multipleOf * multipleOf)
    }

    /** Calls [body] for each strip (rows [y0, y0 + n)) with its pixels in [buf]; writes them back if [write]. */
    internal inline fun strips(s: PixelStore, rows: Int, buf: IntArray, write: Boolean, body: (y0: Int, n: Int) -> Unit) {
        var y0 = 0
        while (y0 < s.height) {
            val n = min(rows, s.height - y0)
            s.read(y0, n, buf)
            body(y0, n)
            if (write) s.write(y0, n, buf)
            y0 += n
        }
    }

    /**
     * Low-resolution estimate of the paper brightness (illumination) for one
     * channel, so shadows and uneven light can be divided out.
     * Max over blocks removes thin dark ink, then a blur smooths the field.
     */
    class Background(val gw: Int, val gh: Int, val cell: Int, val v: FloatArray) {
        fun at(x: Int, y: Int): Float {
            val fx = ((x + 0.5f) / cell - 0.5f).coerceIn(0f, gw - 1f)
            val fy = ((y + 0.5f) / cell - 0.5f).coerceIn(0f, gh - 1f)
            val x0 = fx.toInt(); val y0 = fy.toInt()
            val x1 = min(gw - 1, x0 + 1); val y1 = min(gh - 1, y0 + 1)
            val ax = fx - x0; val ay = fy - y0
            val top = v[y0 * gw + x0] * (1 - ax) + v[y0 * gw + x1] * ax
            val bot = v[y1 * gw + x0] * (1 - ax) + v[y1 * gw + x1] * ax
            return top * (1 - ay) + bot * ay
        }
    }

    fun background(px: IntArray, w: Int, h: Int, channel: (Int) -> Int): Background {
        val cell = max(8, max(w, h) / 64)
        val gw = (w + cell - 1) / cell
        val gh = (h + cell - 1) / cell
        val grid = FloatArray(gw * gh)
        val hist = IntArray(256)
        for (gy in 0 until gh) for (gx in 0 until gw) {
            hist.fill(0)
            var n = 0
            val x0 = gx * cell; val y0 = gy * cell
            val x1 = min(w, x0 + cell); val y1 = min(h, y0 + cell)
            // Sample every other pixel; take the 90th percentile (robust "max").
            var y = y0
            while (y < y1) {
                var x = x0
                while (x < x1) { hist[channel(px[y * w + x])]++; n++; x += 2 }
                y += 2
            }
            grid[gy * gw + gx] = percentile(hist, n, 0.90).toFloat()
        }
        return smoothGrid(grid, gw, gh, cell)
    }

    private fun smoothGrid(grid: FloatArray, gw: Int, gh: Int, cell: Int): Background {
        // Dilate (3x3 max) to swallow bold text/blocks, then blur twice.
        var cur = grid
        cur = maxFilter(cur, gw, gh)
        repeat(2) { cur = boxBlur(cur, gw, gh) }
        for (i in cur.indices) cur[i] = max(cur[i], 24f)
        return Background(gw, gh, cell, cur)
    }

    private const val CH_LUMA = 0
    private const val CH_R = 1
    private const val CH_G = 2
    private const val CH_B = 3

    private fun channel(c: Int, ch: Int): Int = when (ch) {
        CH_R -> r(c)
        CH_G -> g(c)
        CH_B -> b(c)
        else -> luma(c)
    }

    /**
     * [background] over a strip store: the same cells, samples and smoothing, so the grid equals
     * the one-array version. Only one band of cells is counted at a time.
     */
    private fun backgrounds(s: PixelStore, channels: IntArray, opt: FilterOptions): Array<Background> {
        val w = s.width; val h = s.height
        val cell = max(8, max(w, h) / 64)
        val gw = (w + cell - 1) / cell
        val gh = (h + cell - 1) / cell
        val grids = Array(channels.size) { FloatArray(gw * gh) }
        val hist = Array(channels.size) { IntArray(gw * 256) }
        val counts = IntArray(gw)
        val rows = stripRows(w, opt, cell)
        val buf = IntArray(rows * w)
        strips(s, rows, buf, write = false) { y0, n ->
            var band = 0
            while (band < n) {
                val gy = (y0 + band) / cell
                val bandRows = min(cell, n - band)
                for (hs in hist) hs.fill(0)
                counts.fill(0)
                // Every other pixel from the cell origin, as in [background].
                var yy = 0
                while (yy < bandRows) {
                    val row = (band + yy) * w
                    for (gx in 0 until gw) {
                        val x1 = min(w, gx * cell + cell)
                        var x = gx * cell
                        while (x < x1) {
                            val c = buf[row + x]
                            for (k in channels.indices) hist[k][gx * 256 + channel(c, channels[k])]++
                            counts[gx]++
                            x += 2
                        }
                    }
                    yy += 2
                }
                for (k in channels.indices) for (gx in 0 until gw) {
                    grids[k][gy * gw + gx] = percentileAt(hist[k], gx * 256, counts[gx], 0.90).toFloat()
                }
                band += bandRows
            }
        }
        return Array(channels.size) { k -> smoothGrid(grids[k], gw, gh, cell) }
    }

    private fun maxFilter(src: FloatArray, w: Int, h: Int): FloatArray {
        val out = FloatArray(src.size)
        for (y in 0 until h) for (x in 0 until w) {
            var m = 0f
            for (dy in -1..1) for (dx in -1..1) {
                val xx = (x + dx).coerceIn(0, w - 1); val yy = (y + dy).coerceIn(0, h - 1)
                m = max(m, src[yy * w + xx])
            }
            out[y * w + x] = m
        }
        return out
    }

    private fun boxBlur(src: FloatArray, w: Int, h: Int): FloatArray {
        val out = FloatArray(src.size)
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0f
            for (dy in -1..1) for (dx in -1..1) {
                val xx = (x + dx).coerceIn(0, w - 1); val yy = (y + dy).coerceIn(0, h - 1)
                s += src[yy * w + xx]
            }
            out[y * w + x] = s / 9f
        }
        return out
    }

    // ------------------------------------------------------------- filters

    /** Grayscale with automatic levels (0.5% / 99.5% stretch). */
    fun grayscale(px: IntArray, w: Int, h: Int) = apply(Filter.GRAYSCALE, px, w, h)

    /**
     * "Enhanced colour": lifts shadows a little, auto-levels, white balance
     * on the paper colour and a gentle saturation boost. Keeps photos natural.
     */
    fun enhance(px: IntArray, w: Int, h: Int) = apply(Filter.ENHANCED, px, w, h)

    /**
     * Shadow removal / whitening: divides every channel by its own
     * illumination estimate so paper becomes pure white while ink keeps its colour.
     */
    fun whiten(px: IntArray, w: Int, h: Int) = apply(Filter.WHITEN, px, w, h)

    /** Black & white document: illumination-normalised luminance + Otsu threshold. */
    fun blackWhite(px: IntArray, w: Int, h: Int) = apply(Filter.BW, px, w, h)

    private fun grayscaleStore(s: PixelStore, opt: FilterOptions) {
        val w = s.width
        val total = w * s.height
        val rows = stripRows(w, opt)
        val buf = IntArray(rows * w)
        val hist = IntArray(256)
        strips(s, rows, buf, write = false) { _, n -> for (i in 0 until n * w) hist[luma(buf[i])]++ }
        val lo = percentile(hist, total, 0.005)
        val hi = max(lo + 1, percentile(hist, total, 0.995))
        val lut = IntArray(256) { clamp(((it - lo) * 255f / (hi - lo)).roundToInt()) }
        strips(s, rows, buf, write = true) { _, n ->
            for (i in 0 until n * w) {
                val l = lut[luma(buf[i])]
                buf[i] = argb(l, l, l)
            }
        }
    }

    private fun enhanceStore(s: PixelStore, opt: FilterOptions) {
        val w = s.width
        val total = w * s.height
        val bg = backgrounds(s, intArrayOf(CH_LUMA), opt)[0]
        val rows = stripRows(w, opt)
        val buf = IntArray(rows * w)
        // Paper white balance: average colour of the brightest 10% of pixels. Sums are kept per
        // luma level, so one pass gives the sums a second pass over the 90th percentile would.
        val hist = IntArray(256)
        val sumR = LongArray(256); val sumG = LongArray(256); val sumB = LongArray(256)
        strips(s, rows, buf, write = false) { _, n ->
            for (i in 0 until n * w) {
                val c = buf[i]
                val l = luma(c)
                hist[l]++
                sumR[l] += r(c).toLong(); sumG[l] += g(c).toLong(); sumB[l] += b(c).toLong()
            }
        }
        val bright = percentile(hist, total, 0.90)
        var sr = 0L; var sg = 0L; var sb = 0L; var n = 0L
        for (l in bright until 256) { sr += sumR[l]; sg += sumG[l]; sb += sumB[l]; n += hist[l] }
        val wr = if (n > 0) sr.toFloat() / n else 255f
        val wg = if (n > 0) sg.toFloat() / n else 255f
        val wb = if (n > 0) sb.toFloat() / n else 255f
        val wm = (wr + wg + wb) / 3f
        val kr = (wm / wr.coerceAtLeast(1f)).coerceIn(0.8f, 1.25f)
        val kg = (wm / wg.coerceAtLeast(1f)).coerceIn(0.8f, 1.25f)
        val kb = (wm / wb.coerceAtLeast(1f)).coerceIn(0.8f, 1.25f)
        val lhist = IntArray(256)
        strips(s, rows, buf, write = true) { y0, rowsHere ->
            for (j in 0 until rowsHere) {
                val y = y0 + j
                val row = j * w
                for (x in 0 until w) {
                    val i = row + x
                    val c = buf[i]
                    // Partial illumination correction (60%) keeps the image natural.
                    val gain = 1f + 0.6f * (245f / bg.at(x, y) - 1f)
                    val nr = clamp((r(c) * kr * gain).toInt())
                    val ng = clamp((g(c) * kg * gain).toInt())
                    val nb = clamp((b(c) * kb * gain).toInt())
                    buf[i] = argb(nr, ng, nb)
                    lhist[luma(buf[i])]++
                }
            }
        }
        val lo = percentile(lhist, total, 0.01)
        val hi = max(lo + 1, percentile(lhist, total, 0.98))
        val lut = IntArray(256) { clamp(((it - lo) * 255f / (hi - lo)).roundToInt()) }
        strips(s, rows, buf, write = true) { _, rowsHere ->
            for (i in 0 until rowsHere * w) {
                val c = buf[i]
                val cr = lut[r(c)]; val cg = lut[g(c)]; val cb = lut[b(c)]
                val l = (cr * 77 + cg * 150 + cb * 29) shr 8
                val sat = 1.2f
                buf[i] = argb(clamp((l + (cr - l) * sat).toInt()), clamp((l + (cg - l) * sat).toInt()), clamp((l + (cb - l) * sat).toInt()))
            }
        }
    }

    /** Whitening curve on the normalised value v in [0,1]: >= 0.90 -> white, darker ink deepened a bit. */
    private val whitenLut = IntArray(1025) { i ->
        val v = i / 1024f
        val t = ((v - 0.08f) / (0.90f - 0.08f)).coerceIn(0f, 1f)
        (255f * Math.pow(t.toDouble(), 1.25).toFloat()).roundToInt()
    }

    private fun whitenStore(s: PixelStore, opt: FilterOptions) {
        val w = s.width
        val (br, bgc, bb) = backgrounds(s, intArrayOf(CH_R, CH_G, CH_B), opt)
        val lut = whitenLut
        val rows = stripRows(w, opt)
        val buf = IntArray(rows * w)
        strips(s, rows, buf, write = true) { y0, n ->
            for (j in 0 until n) {
                val y = y0 + j
                val row = j * w
                for (x in 0 until w) {
                    val i = row + x
                    val c = buf[i]
                    val nr = lut[min(1024, (r(c) / br.at(x, y) * 1024f).toInt())]
                    val ng = lut[min(1024, (g(c) / bgc.at(x, y) * 1024f).toInt())]
                    val nb = lut[min(1024, (b(c) / bb.at(x, y) * 1024f).toInt())]
                    buf[i] = argb(nr, ng, nb)
                }
            }
        }
    }

    private fun blackWhiteStore(s: PixelStore, opt: FilterOptions) {
        val w = s.width
        val total = w * s.height
        val bg = backgrounds(s, intArrayOf(CH_LUMA), opt)[0]
        val rows = stripRows(w, opt)
        val buf = IntArray(rows * w)
        val hist = IntArray(256)
        strips(s, rows, buf, write = false) { y0, n ->
            for (j in 0 until n) {
                val y = y0 + j
                for (x in 0 until w) hist[clamp((luma(buf[j * w + x]) / bg.at(x, y) * 255f).toInt())]++
            }
        }
        val t = otsu(hist, total).coerceIn(150, 215)
        strips(s, rows, buf, write = true) { y0, n ->
            for (j in 0 until n) {
                val y = y0 + j
                for (x in 0 until w) {
                    val i = j * w + x
                    val v = clamp((luma(buf[i]) / bg.at(x, y) * 255f).toInt())
                    buf[i] = if (v < t) argb(0, 0, 0) else argb(255, 255, 255)
                }
            }
        }
    }

    fun otsu(hist: IntArray, total: Int): Int {
        var sum = 0.0
        for (i in 0 until 256) sum += i.toDouble() * hist[i]
        var sumB = 0.0
        var wB = 0
        var best = 0.0
        var threshold = 128
        for (t in 0 until 256) {
            wB += hist[t]
            if (wB == 0) continue
            val wF = total - wB
            if (wF == 0) break
            sumB += t.toDouble() * hist[t]
            val mB = sumB / wB
            val mF = (sum - sumB) / wF
            val between = wB.toDouble() * wF * (mB - mF) * (mB - mF)
            if (between > best) { best = between; threshold = t + 1 }
        }
        return threshold
    }

    /**
     * Luminance with shadows and uneven light divided out (paper ~ 255).
     * Used before OCR: Tesseract binarises far better without shading.
     */
    fun normalizedGray(px: IntArray, w: Int, h: Int): GrayImage {
        val bg = background(px, w, h, ::luma)
        val out = ByteArray(w * h)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                out[row + x] = clamp((luma(px[row + x]) / bg.at(x, y) * 255f).toInt()).toByte()
            }
        }
        return GrayImage(w, h, out)
    }

    /**
     * "Paper-likeness": bright and colourless scores high. A white page on a
     * light but coloured surface (wood, cloth, a coloured desk) has almost the
     * same luminance as the surface, but a very different value here.
     */
    fun paperMap(px: IntArray, w: Int, h: Int): GrayImage {
        val out = ByteArray(w * h)
        for (i in 0 until w * h) {
            val c = px[i]
            val r = r(c); val g = g(c); val b = b(c)
            val chroma = max(r, max(g, b)) - min(r, min(g, b))
            out[i] = clamp(luma(c) - chroma * 3 / 2).toByte()
        }
        return GrayImage(w, h, out)
    }

    /** Luminance plane of ARGB pixels. */
    fun toGray(px: IntArray, w: Int, h: Int): GrayImage {
        val out = ByteArray(w * h)
        for (i in 0 until w * h) out[i] = luma(px[i]).toByte()
        return GrayImage(w, h, out)
    }

    /** True when every pixel is pure black or white (1-bit page). */
    fun isBitonal(px: IntArray): Boolean {
        for (c in px) {
            val v = c and 0xFFFFFF
            if (v != 0 && v != 0xFFFFFF) return false
        }
        return true
    }

    /**
     * Packs pixels into 1-bit rows (PDF DeviceGray: 1 = white, 0 = black),
     * thresholding at mid-grey, each row padded to a whole byte.
     */
    fun packBits(px: IntArray, w: Int, h: Int): ByteArray {
        val stride = (w + 7) / 8
        val out = ByteArray(stride * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (luma(px[y * w + x]) >= 128) {
                    val idx = y * stride + (x shr 3)
                    out[idx] = (out[idx].toInt() or (0x80 ushr (x and 7))).toByte()
                }
            }
        }
        return out
    }
}
