// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.imaging

import androidx.core.graphics.createBitmap
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF

/**
 * ID-card copy: both sides of a card (ISO/IEC 7810 ID-1, 85.6 x 54 mm) placed
 * at true size on one A4 page, like a photocopy, at 300 dpi.
 */
object IdCardComposer {
    private const val DPI = 300f
    private fun mm(v: Float) = v / 25.4f * DPI

    fun compose(front: Bitmap, back: Bitmap): Bitmap {
        val pageW = mm(210f).toInt()
        val pageH = mm(297f).toInt()
        val out = createBitmap(pageW, pageH)
        val c = Canvas(out)
        c.drawColor(Color.WHITE)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        val cardW = mm(85.6f)
        val cardH = mm(54f)
        val left = (pageW - cardW) / 2f
        listOf(front to mm(40f), back to mm(40f) + cardH + mm(25f)).forEach { (bmp, top) ->
            // Cards are landscape; turn portrait captures so they fit the slot.
            val src = if (bmp.height > bmp.width) BitmapIO.rotate(bmp.copy(Bitmap.Config.ARGB_8888, false), 90) else bmp
            val slot = RectF(left, top, left + cardW, top + cardH)
            val k = minOf(slot.width() / src.width, slot.height() / src.height)
            val dw = src.width * k
            val dh = src.height * k
            val dst = RectF(slot.centerX() - dw / 2, slot.centerY() - dh / 2, slot.centerX() + dw / 2, slot.centerY() + dh / 2)
            c.drawBitmap(src, Rect(0, 0, src.width, src.height), dst, paint)
            if (src !== bmp) src.recycle()
        }
        return out
    }
}
