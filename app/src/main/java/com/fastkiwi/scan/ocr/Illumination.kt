// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.ocr

import kotlin.math.max
import kotlin.math.min

/**
 * Shadows and uneven light divided out of a luminance plane (paper ≈ 255): the same arithmetic as
 * [com.fastkiwi.scan.core.ImageFilters.normalizedGray] on ARGB pixels, so Tesseract sees the same bytes
 * as in 1.2 (IlluminationTest compares them). Working on the gray plane avoids an ARGB IntArray copy.
 */
object Illumination {
    fun normalize(gray: ByteArray, w: Int, h: Int): ByteArray {
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
            var y = y0
            while (y < y1) {
                var x = x0
                while (x < x1) { hist[gray[y * w + x].toInt() and 0xFF]++; n++; x += 2 }
                y += 2
            }
            grid[gy * gw + gx] = percentile(hist, n, 0.90).toFloat()
        }
        var cur = maxFilter(grid, gw, gh)
        repeat(2) { cur = boxBlur(cur, gw, gh) }
        for (i in cur.indices) cur[i] = max(cur[i], 24f)
        val out = ByteArray(w * h)
        for (y in 0 until h) {
            val fy = ((y + 0.5f) / cell - 0.5f).coerceIn(0f, gh - 1f)
            val y0 = fy.toInt(); val y1 = min(gh - 1, y0 + 1); val ay = fy - y0
            val row = y * w
            for (x in 0 until w) {
                val fx = ((x + 0.5f) / cell - 0.5f).coerceIn(0f, gw - 1f)
                val x0 = fx.toInt(); val x1 = min(gw - 1, x0 + 1); val ax = fx - x0
                val top = cur[y0 * gw + x0] * (1 - ax) + cur[y0 * gw + x1] * ax
                val bot = cur[y1 * gw + x0] * (1 - ax) + cur[y1 * gw + x1] * ax
                val bg = top * (1 - ay) + bot * ay
                val v = ((gray[row + x].toInt() and 0xFF) / bg * 255f).toInt()
                out[row + x] = (if (v < 0) 0 else if (v > 255) 255 else v).toByte()
            }
        }
        return out
    }

    private fun percentile(hist: IntArray, total: Int, p: Double): Int {
        val target = (total * p).toLong()
        var acc = 0L
        for (i in 0 until 256) {
            acc += hist[i]
            if (acc > target) return i
        }
        return 255
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
}
