// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.pdf

import android.graphics.Bitmap
import android.graphics.Color
import com.fastkiwi.scan.core.Filter
import com.fastkiwi.scan.data.DocumentStore
import com.fastkiwi.scan.data.ScanDocument
import com.fastkiwi.scan.data.ScanPage
import com.fastkiwi.scan.imaging.BitmapIO
import com.fastkiwi.scan.imaging.PageRenderer
import java.io.File

/**
 * The PDF engine (PRD §13): one set of page operations for every kind of document. Merge,
 * split, extract, reorder, rotate and insert-blank are all [assemble] with a different
 * [PageRef] plan from [PagePlans]; the camera and OCR modules never see this.
 *
 * - [KiwiPdfEngine]: Scan Kiwi documents. Pages are rebuilt from our own source images
 *   (lossless), and the PDF is written by our own writer at export time.
 * - [ExternalPdfEngine]: PDF files from other apps, copied object for object by PdfBox-Android
 *   in the isolated `:pdf` process.
 */
interface PdfEngine<D> {
    suspend fun pageCount(doc: D): Int

    /** A new document named [name] with the pages of [plan] taken from [sources]. Sources are never changed. */
    suspend fun assemble(sources: List<D>, plan: List<PageRef>, name: String): D

    /** PDF-12: plain text per page, logical order. */
    suspend fun text(doc: D): List<String>
}

// ------------------------------------------------------------------ Scan Kiwi documents

class KiwiPdfEngine(private val store: DocumentStore) : PdfEngine<ScanDocument> {

    override suspend fun pageCount(doc: ScanDocument): Int = doc.pages.size

    override suspend fun assemble(sources: List<ScanDocument>, plan: List<PageRef>, name: String): ScanDocument {
        require(plan.isNotEmpty()) { "empty plan" }
        val target = store.create(name)
        val pages = ArrayList<ScanPage>(plan.size)
        try {
            var last: Pair<Int, Int>? = null
            for (ref in plan) {
                if (ref.isBlank) {
                    pages.add(blankPage(target.id, last ?: (BLANK_W to BLANK_H)))
                    continue
                }
                val src = sources[ref.source]
                val sp = src.pages[ref.index]
                var np = store.copyPage(src.id, sp, target.id)
                if (PagePlans.norm(ref.rotate) != 0) {
                    np = np.copy(rotation = PagePlans.norm(np.rotation + ref.rotate), revision = np.revision + 1)
                    PageRenderer.renderAndSave(store, target.id, np)?.recycle()
                }
                last = BitmapIO.imageSize(store.outFile(target.id, np.id)) ?: last
                pages.add(np)
            }
            return store.save(target.copy(pages = pages))
        } catch (e: Throwable) {
            store.delete(target.id) // never leave a half-built document
            throw e
        }
    }

    /** PDF-11: a white page sized like its neighbour (A4 at 150 dpi when there is none). */
    fun blankPage(docId: String, size: Pair<Int, Int>): ScanPage {
        val (w, h) = size
        val k = minOf(1f, BLANK_MAX_SIDE.toFloat() / maxOf(w, h))
        val bmp = Bitmap.createBitmap(maxOf(1, (w * k).toInt()), maxOf(1, (h * k).toInt()), Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.WHITE)
        val id = DocumentStore.newId()
        BitmapIO.saveJpeg(bmp, store.srcFile(docId, id), 92)
        BitmapIO.saveJpeg(bmp, store.outFile(docId, id), 92)
        val thumb = BitmapIO.fit(bmp, PageRenderer.THUMB_SIDE)
        BitmapIO.saveJpeg(thumb, store.thumbFile(docId, id), 82)
        thumb.recycle()
        return ScanPage(id, BitmapIO.FULL, 0, Filter.ORIGINAL)
    }

    override suspend fun text(doc: ScanDocument): List<String> = DocumentText.pages(store, doc)

    companion object {
        const val BLANK_W = 1240
        const val BLANK_H = 1754
        const val BLANK_MAX_SIDE = 2480
    }
}

// ------------------------------------------------------------------ external PDF files

/** An external PDF copied into app storage, with the password the user typed for it (if any). */
data class PdfFile(val file: File, val password: String? = null)

class ExternalPdfEngine(
    private val channel: PdfChannel,
    /** Where results go (the export cache, shared through the FileProvider). */
    private val outDir: File,
) : PdfEngine<PdfFile> {

    override suspend fun pageCount(doc: PdfFile): Int =
        channel.call(PdfProtocol.request(PdfProtocol.OP_COUNT, listOf(doc.file), listOf(doc.password))).getInt(PdfProtocol.K_PAGES)

    override suspend fun assemble(sources: List<PdfFile>, plan: List<PageRef>, name: String): PdfFile {
        val safe = SafeOutput(File(outDir.apply { mkdirs() }, name))
        // A copy never needs more than the inputs together (plus the 20% margin).
        safe.checkSpace(sources.sumOf { it.file.length() })
        try {
            channel.call(PdfProtocol.request(PdfProtocol.OP_ASSEMBLE, sources.map { it.file }, sources.map { it.password }, plan, safe.part))
            safe.commitPdf(plan.size)
        } catch (e: Throwable) {
            safe.part.delete()
            throw e
        }
        return PdfFile(safe.final)
    }

    /** PDF-14: an unprotected copy, only with the right password. */
    suspend fun unlock(doc: PdfFile, password: String, name: String): PdfFile {
        val safe = SafeOutput(File(outDir.apply { mkdirs() }, name))
        safe.checkSpace(doc.file.length())
        try {
            val n = channel.call(PdfProtocol.request(PdfProtocol.OP_UNLOCK, listOf(doc.file), listOf(password), out = safe.part)).getInt(PdfProtocol.K_PAGES)
            safe.commitPdf(n)
        } catch (e: Throwable) {
            safe.part.delete()
            throw e
        }
        return PdfFile(safe.final)
    }

    override suspend fun text(doc: PdfFile): List<String> =
        channel.call(PdfProtocol.request(PdfProtocol.OP_TEXT, listOf(doc.file), listOf(doc.password))).getStringArray(PdfProtocol.K_TEXT)?.toList() ?: emptyList()
}
