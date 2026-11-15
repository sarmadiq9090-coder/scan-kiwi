// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.pdf

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.fastkiwi.scan.core.PageSize
import com.fastkiwi.scan.imaging.BitmapIO
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * §7.4 "final document preview": the first pages as they will look in the file — on the chosen
 * paper size and orientation, image fitted and centred like [com.fastkiwi.scan.core.PdfWriter]
 * does, with page numbers and watermark drawn by the same [PageDecor] code as the export.
 * Works from the small thumbnails, so it is instant.
 */
object ExportPreview {

    fun render(thumb: File, pageSize: PageSize, decor: PageDecor, index: Int, total: Int, numbers: PageNumbering?, watermark: Watermark?, bitonal: Boolean, height: Int = 480): Bitmap? {
        val src = BitmapIO.decodeFile(thumb, 720) ?: return null
        val img = if (src.isMutable) src else src.copy(Bitmap.Config.ARGB_8888, true).also { src.recycle() }
        if (decor.isActive(numbers, watermark)) decor.draw(img, index, total, numbers, watermark, bitonal)
        if (pageSize == PageSize.FIT) return img
        val landscape = img.width > img.height
        val pw = if (landscape) pageSize.heightPt else pageSize.widthPt
        val ph = if (landscape) pageSize.widthPt else pageSize.heightPt
        val h = height
        val w = max(1, (h * pw / ph).roundToInt())
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.WHITE)
        val s = minOf(w.toFloat() / img.width, h.toFloat() / img.height)
        val dw = img.width * s
        val dh = img.height * s
        c.drawBitmap(img, null, RectF((w - dw) / 2, (h - dh) / 2, (w + dw) / 2, (h + dh) / 2), Paint(Paint.FILTER_BITMAP_FLAG))
        img.recycle()
        return out
    }
}
