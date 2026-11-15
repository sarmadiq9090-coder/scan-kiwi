// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.imaging

import androidx.core.graphics.createBitmap
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import com.fastkiwi.scan.core.ImageFilters
import com.fastkiwi.scan.core.PixelStore
import com.fastkiwi.scan.core.Quad
import com.fastkiwi.scan.data.DocumentStore
import com.fastkiwi.scan.data.ScanPage
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.max

/** 2.0-cam2: a mutable ARGB bitmap as a strip store for [ImageFilters] (getPixels/setPixels per strip). */
class BitmapPixels(private val bmp: Bitmap) : PixelStore {
    override val width: Int get() = bmp.width
    override val height: Int get() = bmp.height

    override fun read(y0: Int, rows: Int, dst: IntArray, offset: Int) {
        bmp.getPixels(dst, offset, bmp.width, 0, y0, bmp.width, rows)
    }

    override fun write(y0: Int, rows: Int, src: IntArray, offset: Int) {
        bmp.setPixels(src, offset, bmp.width, 0, y0, bmp.width, rows)
    }
}

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
        val out = if (rotated.isMutable) rotated else rotated.copy(Bitmap.Config.ARGB_8888, true).also { rotated.recycle() }
        // 2.0-cam2 (A8): the filter (with its noise reduction) works on the bitmap in strips, so
        // no second full-size pixel copy is made, whatever the page size.
        ImageFilters.apply(page.filter, BitmapPixels(out))
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
        val out = createBitmap(max(1, w), max(1, h))
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
