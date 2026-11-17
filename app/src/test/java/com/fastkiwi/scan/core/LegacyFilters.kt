// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.core

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt


/**
 * Document filters working in place on packed ARGB pixels (as from
 * Bitmap.getPixels). Pure Kotlin: no Android types, unit-testable on the JVM.
 */
/** The 2.0.0 (pre-cam2) filters, copied verbatim: the reference that the strip engine must reproduce exactly. */
internal object LegacyFilters {

    fun apply(filter: Filter, px: IntArray, w: Int, h: Int) {
        when (filter) {
            Filter.ORIGINAL -> Unit
            Filter.ENHANCED -> enhance(px, w, h)
            Filter.WHITEN -> whiten(px, w, h)
            Filter.GRAYSCALE -> grayscale(px, w, h)
            Filter.BW -> blackWhite(px, w, h)
        }
    }

    // ------------------------------------------------------------- helpers

    private inline fun r(c: Int) = (c shr 16) and 0xFF
    private inline fun g(c: Int) = (c shr 8) and 0xFF
    private inline fun b(c: Int) = c and 0xFF
    private inline fun argb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    private inline fun clamp(v: Int) = if (v < 0) 0 else if (v > 255) 255 else v
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
        // Dilate (3x3 max) to swallow bold text/blocks, then blur twice.
        var cur = grid
        cur = maxFilter(cur, gw, gh)
        repeat(2) { cur = boxBlur(cur, gw, gh) }
        for (i in cur.indices) cur[i] = max(cur[i], 24f)
        return Background(gw, gh, cell, cur)
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
    fun grayscale(px: IntArray, w: Int, h: Int) {
        val hist = IntArray(256)
        for (c in px) hist[luma(c)]++
        val lo = percentile(hist, px.size, 0.005)
        val hi = max(lo + 1, percentile(hist, px.size, 0.995))
        val lut = IntArray(256) { clamp(((it - lo) * 255f / (hi - lo)).roundToInt()) }
        for (i in px.indices) {
            val l = lut[luma(px[i])]
            px[i] = argb(l, l, l)
        }
    }

    /**
     * "Enhanced colour": lifts shadows a little, auto-levels, white balance
     * on the paper colour and a gentle saturation boost. Keeps photos natural.
     */
    fun enhance(px: IntArray, w: Int, h: Int) {
        val bg = background(px, w, h, ::luma)
        // Paper white balance: average colour of the brightest 10% of pixels.
        val hist = IntArray(256)
        for (c in px) hist[luma(c)]++
        val bright = percentile(hist, px.size, 0.90)
        var sr = 0L; var sg = 0L; var sb = 0L; var n = 0L
        for (c in px) if (luma(c) >= bright) { sr += r(c); sg += g(c); sb += b(c); n++ }
        val wr = if (n > 0) sr.toFloat() / n else 255f
        val wg = if (n > 0) sg.toFloat() / n else 255f
        val wb = if (n > 0) sb.toFloat() / n else 255f
        val wm = (wr + wg + wb) / 3f
        val kr = (wm / wr.coerceAtLeast(1f)).coerceIn(0.8f, 1.25f)
        val kg = (wm / wg.coerceAtLeast(1f)).coerceIn(0.8f, 1.25f)
        val kb = (wm / wb.coerceAtLeast(1f)).coerceIn(0.8f, 1.25f)
        val lhist = IntArray(256)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                val i = row + x
                val c = px[i]
                // Partial illumination correction (60%) keeps the image natural.
                val gain = 1f + 0.6f * (245f / bg.at(x, y) - 1f)
                val nr = clamp((r(c) * kr * gain).toInt())
                val ng = clamp((g(c) * kg * gain).toInt())
                val nb = clamp((b(c) * kb * gain).toInt())
                px[i] = argb(nr, ng, nb)
                lhist[luma(px[i])]++
            }
        }
        val lo = percentile(lhist, px.size, 0.01)
        val hi = max(lo + 1, percentile(lhist, px.size, 0.98))
        val lut = IntArray(256) { clamp(((it - lo) * 255f / (hi - lo)).roundToInt()) }
        for (i in px.indices) {
            val c = px[i]
            val cr = lut[r(c)]; val cg = lut[g(c)]; val cb = lut[b(c)]
            val l = (cr * 77 + cg * 150 + cb * 29) shr 8
            val s = 1.2f
            px[i] = argb(clamp((l + (cr - l) * s).toInt()), clamp((l + (cg - l) * s).toInt()), clamp((l + (cb - l) * s).toInt()))
        }
    }

    /**
     * Shadow removal / whitening: divides every channel by its own
     * illumination estimate so paper becomes pure white while ink keeps its colour.
     */
    fun whiten(px: IntArray, w: Int, h: Int) {
        val br = background(px, w, h) { r(it) }
        val bgc = background(px, w, h) { g(it) }
        val bb = background(px, w, h) { b(it) }
        // Map normalised value v in [0,1]: >= 0.90 -> white, darker ink deepened a bit.
        val lut = IntArray(1025) { i ->
            val v = i / 1024f
            val t = ((v - 0.08f) / (0.90f - 0.08f)).coerceIn(0f, 1f)
            (255f * Math.pow(t.toDouble(), 1.25).toFloat()).roundToInt()
        }
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                val i = row + x
                val c = px[i]
                val nr = lut[min(1024, (r(c) / br.at(x, y) * 1024f).toInt())]
                val ng = lut[min(1024, (g(c) / bgc.at(x, y) * 1024f).toInt())]
                val nb = lut[min(1024, (b(c) / bb.at(x, y) * 1024f).toInt())]
                px[i] = argb(nr, ng, nb)
            }
        }
    }

    /** Black & white document: illumination-normalised luminance + Otsu threshold. */
    fun blackWhite(px: IntArray, w: Int, h: Int) {
        val bg = background(px, w, h, ::luma)
        val norm = ByteArray(px.size)
        val hist = IntArray(256)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                val i = row + x
                val v = clamp((luma(px[i]) / bg.at(x, y) * 255f).toInt())
                norm[i] = v.toByte()
                hist[v]++
            }
        }
        val t = otsu(hist, px.size).coerceIn(150, 215)
        for (i in px.indices) {
            val v = norm[i].toInt() and 0xFF
            px[i] = if (v < t) argb(0, 0, 0) else argb(255, 255, 255)
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
