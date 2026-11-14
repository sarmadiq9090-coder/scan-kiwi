package com.fastkiwi.scan.imaging

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import kotlin.math.max
import kotlin.math.min

/**
 * Reads pages of an existing PDF with the platform PdfRenderer (no extra
 * library). Pages are rasterised at ~200 dpi so they can join a scan,
 * be filtered, re-ordered and merged like any other page.
 */
object PdfImporter {
    class ProtectedPdfException : Exception()

    fun importPages(ctx: Context, uri: Uri, onPage: (Bitmap) -> Unit): Int {
        val pfd = ctx.contentResolver.openFileDescriptor(uri, "r") ?: return 0
        pfd.use {
            val renderer = try {
                PdfRenderer(it)
            } catch (e: SecurityException) {
                throw ProtectedPdfException()
            }
            renderer.use { r ->
                for (i in 0 until r.pageCount) {
                    r.openPage(i).use { page ->
                        // 200 dpi, capped so huge posters do not exhaust memory.
                        var k = 200f / 72f
                        val longest = max(page.width, page.height) * k
                        if (longest > 2800f) k *= 2800f / longest
                        val w = max(1, (page.width * k).toInt())
                        val h = max(1, (page.height * k).toInt())
                        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                        onPage(bmp)
                    }
                }
                return min(r.pageCount, Int.MAX_VALUE)
            }
        }
    }
}
