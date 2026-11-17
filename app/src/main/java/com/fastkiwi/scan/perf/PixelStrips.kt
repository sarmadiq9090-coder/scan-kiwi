// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.perf

import android.graphics.Bitmap
import com.fastkiwi.scan.core.ImageFilters

/**
 * 2.0-perf (ROADMAP 1.7.0 item 2): reads a bitmap's pixels a strip of rows at a time, so OCR and the
 * black-and-white export never hold a full ARGB `IntArray` copy of the page (2400 x 3200 = 30 MB) next
 * to the bitmap. The bytes are identical to `ImageFilters.toGray` / `ImageFilters.packBits` on a full
 * `getPixels` copy (PixelStripsTest).
 */
object PixelStrips {

    /** The 8-bit luma plane (row-major, stride = width). */
    fun gray(bmp: Bitmap, rows: Int = MemoryBudget.STRIP_ROWS): ByteArray {
        val w = bmp.width
        val h = bmp.height
        val out = ByteArray(w * h)
        val strip = IntArray(w * minOf(rows, h))
        var y = 0
        while (y < h) {
            val n = minOf(rows, h - y)
            bmp.getPixels(strip, 0, w, 0, y, w, n)
            val base = y * w
            for (i in 0 until w * n) out[base + i] = ImageFilters.luma(strip[i]).toByte()
            y += n
        }
        return out
    }

    /** 1-bit rows padded to whole bytes, bit set = white (luma ≥ 128), most significant bit first. */
    fun packBits(bmp: Bitmap, rows: Int = MemoryBudget.STRIP_ROWS): ByteArray {
        val w = bmp.width
        val h = bmp.height
        val stride = (w + 7) / 8
        val out = ByteArray(stride * h)
        val strip = IntArray(w * minOf(rows, h))
        var y = 0
        while (y < h) {
            val n = minOf(rows, h - y)
            bmp.getPixels(strip, 0, w, 0, y, w, n)
            for (r in 0 until n) {
                val row = (y + r) * stride
                val src = r * w
                for (x in 0 until w) {
                    if (ImageFilters.luma(strip[src + x]) >= 128) {
                        val idx = row + (x shr 3)
                        out[idx] = (out[idx].toInt() or (0x80 ushr (x and 7))).toByte()
                    }
                }
            }
            y += n
        }
        return out
    }
}
