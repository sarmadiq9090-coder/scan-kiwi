// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.imaging

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import com.fastkiwi.scan.core.ImageFilters
import com.fastkiwi.scan.core.Quad
import com.fastkiwi.scan.data.DocumentStore
import com.fastkiwi.scan.data.ScanPage
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.max

/**
 * Turns a page source into the final page: perspective correction (crop),
 * rotation and filter. Uses android.graphics.Matrix.setPolyToPoly for the
 * 4-point homography, so no native imaging library is needed.
 */
object PageRenderer {
    /** One full-resolution render at a time keeps memory use predictable. */
    private val lock = Mutex()
    const val THUMB_SIDE = 360

    suspend fun renderAndSave(store: DocumentStore, docId: String, page: ScanPage): Bitmap? = lock.withLock {
        val src = BitmapIO.decodeFile(store.srcFile(docId, page.id)) ?: return@withLock null
        val signature = if (page.stamps.isNotEmpty()) BitmapIO.decodePng(store.signatureFile) else null
        val out = render(src, page, signature)
        src.recycle()
        signature?.recycle()
        BitmapIO.saveJpeg(out, store.outFile(docId, page.id), 92)
        val thumb = BitmapIO.fit(out.copy(Bitmap.Config.ARGB_8888, false), THUMB_SIDE)
        BitmapIO.saveJpeg(thumb, store.thumbFile(docId, page.id), 82)
        out.recycle()
        thumb
    }

    fun render(src: Bitmap, page: ScanPage, signature: Bitmap? = null): Bitmap {
        val warped = warp(src, page.corners)
        val rotated = BitmapIO.rotate(warped, page.rotation)
        val px = IntArray(rotated.width * rotated.height)
        rotated.getPixels(px, 0, rotated.width, 0, 0, rotated.width, rotated.height)
        ImageFilters.apply(page.filter, px, rotated.width, rotated.height)
        val out = if (rotated.isMutable) rotated else rotated.copy(Bitmap.Config.ARGB_8888, true).also { rotated.recycle() }
        out.setPixels(px, 0, out.width, 0, 0, out.width, out.height)
        if (signature != null) drawStamps(out, page.stamps, signature)
        return out
    }

    /** Draws the signature at each stamp position (after filters, so ink stays crisp). */
    fun drawStamps(page: Bitmap, stamps: List<com.fastkiwi.scan.data.Stamp>, signature: Bitmap) {
        val c = Canvas(page)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        for (s in stamps) {
            val w = s.width * page.width
            val h = w * signature.height / signature.width
            val l = s.cx * page.width - w / 2f
            val t = s.cy * page.height - h / 2f
            c.drawBitmap(signature, null, android.graphics.RectF(l, t, l + w, t + h), paint)
        }
    }

    /** Rectifies the quad (normalised corners) to an upright rectangle. */
    fun warp(src: Bitmap, corners: Quad): Bitmap {
        val q = corners.scale(src.width.toFloat(), src.height.toFloat())
        val isFull = corners.maxCornerShift(BitmapIO.FULL) < 0.002f
        if (isFull) return src.copy(Bitmap.Config.ARGB_8888, true)
        var (w, h) = q.outputSize()
        val m = max(w, h)
        if (m > BitmapIO.MAX_SOURCE_SIDE) {
            val k = BitmapIO.MAX_SOURCE_SIDE.toFloat() / m
            w = (w * k).toInt(); h = (h * k).toInt()
        }
        val out = Bitmap.createBitmap(max(1, w), max(1, h), Bitmap.Config.ARGB_8888)
        val matrix = Matrix()
        // Map the outline slightly outside the output so the page edge (often a
        // dark line or a sliver of desk) is trimmed: ~0.4% on each side.
        val ix = w * 0.004f
        val iy = h * 0.004f
        val ok = matrix.setPolyToPoly(
            q.toArray(), 0,
            floatArrayOf(-ix, -iy, w + ix, -iy, w + ix, h + iy, -ix, h + iy), 0,
            4,
        )
        val canvas = Canvas(out)
        canvas.drawColor(Color.WHITE)
        if (ok) {
            canvas.drawBitmap(src, matrix, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG))
        } else {
            canvas.drawBitmap(src, null, android.graphics.Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG))
        }
        return out
    }
}
