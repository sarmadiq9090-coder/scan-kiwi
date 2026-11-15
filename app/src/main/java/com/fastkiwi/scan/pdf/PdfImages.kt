// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import kotlin.math.max

/**
 * PDF-08: every page of a PDF as a JPEG, rendered by the platform's PdfRenderer (PDFium, no
 * library of ours). Each image goes through [SafeOutput]; on any failure the images already
 * made are removed, so a half set is never shared.
 */
object PdfImages {
    /** Long side cap, so a poster-size page cannot exhaust memory. */
    const val MAX_SIDE = 4000

    suspend fun toJpegs(pdf: File, outDir: File, baseName: String, dpi: Int, quality: Int = 88, progress: (Int, Int) -> Unit = { _, _ -> }): List<File> {
        val files = ArrayList<File>()
        try {
            ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                val r = try { PdfRenderer(pfd) } catch (e: SecurityException) { throw PdfOpException(PdfError.NEEDS_PASSWORD, e) }
                r.use {
                    val n = it.pageCount
                    SafeOutput(File(outDir, "x")).checkSpace(pdf.length() * 2)
                    for (i in 0 until n) {
                        currentCoroutineContext().ensureActive()
                        progress(i, n)
                        it.openPage(i).use { page ->
                            var k = dpi / 72f
                            val longest = max(page.width, page.height) * k
                            if (longest > MAX_SIDE) k *= MAX_SIDE / longest
                            val bmp = Bitmap.createBitmap(max(1, (page.width * k).toInt()), max(1, (page.height * k).toInt()), Bitmap.Config.ARGB_8888)
                            bmp.eraseColor(Color.WHITE)
                            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                            val safe = SafeOutput(File(outDir.apply { mkdirs() }, if (n == 1) "$baseName.jpg" else "${baseName}_${i + 1}.jpg"))
                            val ok = safe.write { out -> bmp.compress(Bitmap.CompressFormat.JPEG, quality, out) }
                            bmp.recycle()
                            if (!ok) throw java.io.IOException("cannot write ${safe.final.name}")
                            safe.commit()
                            files.add(safe.final)
                        }
                    }
                    progress(n, n)
                }
            }
        } catch (e: Throwable) {
            files.forEach { it.delete() }
            throw e
        }
        return files
    }
}
