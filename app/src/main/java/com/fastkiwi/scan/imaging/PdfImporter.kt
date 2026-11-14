// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.imaging

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import kotlin.math.max

/**
 * Reads pages of an existing PDF with the platform PdfRenderer (no extra
 * library). Pages are rasterised at ~200 dpi so they can join a scan,
 * be filtered, re-ordered and merged like any other page.
 */
object PdfImporter {
    class ProtectedPdfException : Exception()

    /**
     * True for a PDF. Mail and cloud apps often give no MIME type (or a generic one)
     * and a URI without a file name, so when neither says "PDF" the first bytes are
     * checked for the "%PDF-" header (ISO 32000: within the first 1024 bytes).
     */
    fun isPdf(ctx: Context, uri: Uri, type: String): Boolean {
        if (type == "application/pdf" || uri.toString().endsWith(".pdf", true)) return true
        if (type.startsWith("image/")) return false
        return runCatching {
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                val head = ByteArray(1024)
                var n = 0
                while (n < head.size) {
                    val r = input.read(head, n, head.size - n)
                    if (r < 0) break
                    n += r
                }
                String(head, 0, n, Charsets.ISO_8859_1).contains("%PDF-")
            } ?: false
        }.getOrDefault(false)
    }

    /**
     * PdfRenderer needs a seekable file. Some providers (cloud drives, mail
     * apps) only hand out a pipe, so in that case the PDF is first copied to a
     * private temporary file, which is deleted right after.
     */
    suspend fun importPages(ctx: Context, uri: Uri, onPage: suspend (Bitmap) -> Unit): Int {
        val direct = runCatching { ctx.contentResolver.openFileDescriptor(uri, "r") }.getOrNull()
        if (direct != null && isSeekable(direct)) return render(direct, onPage)
        direct?.close()
        val tmp = File.createTempFile("import", ".pdf", File(ctx.cacheDir, "imports").apply { mkdirs() })
        try {
            val copied = ctx.contentResolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().use { input.copyTo(it) }
            } ?: return 0
            if (copied <= 0L) return 0
            return render(ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY), onPage)
        } finally {
            tmp.delete()
        }
    }

    /** True for a real file descriptor; false for a pipe or socket. */
    private fun isSeekable(pfd: ParcelFileDescriptor): Boolean = try {
        android.system.Os.lseek(pfd.fileDescriptor, 0L, android.system.OsConstants.SEEK_SET)
        pfd.statSize >= 0
    } catch (e: Exception) {
        false
    }

    private suspend fun render(pfd: ParcelFileDescriptor, onPage: suspend (Bitmap) -> Unit): Int {
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
                return r.pageCount
            }
        }
    }
}
