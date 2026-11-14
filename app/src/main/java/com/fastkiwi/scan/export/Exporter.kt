// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.export

import android.content.Context
import android.graphics.Bitmap
import com.fastkiwi.scan.core.Filter
import com.fastkiwi.scan.core.ImageFilters
import com.fastkiwi.scan.core.OcrWord
import com.fastkiwi.scan.core.PageSize
import com.fastkiwi.scan.core.PdfImage
import com.fastkiwi.scan.core.PdfPage
import com.fastkiwi.scan.core.PdfWriter
import com.fastkiwi.scan.data.DocumentStore
import com.fastkiwi.scan.data.ScanDocument
import com.fastkiwi.scan.imaging.BitmapIO
import com.fastkiwi.scan.imaging.PageRenderer
import com.fastkiwi.scan.ocr.OcrEngine
import com.fastkiwi.scan.ocr.OcrLanguage
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max

/** Size/quality presets. SMALL targets messaging apps and e-mail attachments. */
enum class Quality(val longSide: Int, val jpegQuality: Int, val bitonalLongSide: Int) {
    SMALL(1700, 55, 2200),
    BALANCED(2400, 72, 2800),
    HIGH(3200, 90, 3200),
}

data class ExportOptions(
    val pageSize: PageSize = PageSize.A4,
    val quality: Quality = Quality.BALANCED,
    val ocr: Boolean = false,
    val ocrLanguage: OcrLanguage = OcrLanguage.ARABIC_ENGLISH,
    val password: String? = null,
    /** PDF/A-2b ("أرشيفي"); ignored when a password is set (PDF/A forbids encryption). */
    val archival: Boolean = false,
)

class Exporter(private val ctx: Context, private val store: DocumentStore) {

    private fun exportDir(): File = File(ctx.cacheDir, "exports").apply { mkdirs() }

    /** Removes earlier exports (all, or those last written before [before]); they only live until shared or saved. */
    fun clearOldExports(before: Long = Long.MAX_VALUE) {
        exportDir().listFiles()?.forEach { if (it.lastModified() < before) it.delete() }
    }

    fun safeName(name: String): String {
        val cleaned = name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().trim('.')
        val name80 = cleaned.ifEmpty { "scan" }.take(80)
        // Never cut an emoji (surrogate pair) in half: a lone surrogate is not a valid file name.
        return if (name80.isNotEmpty() && name80.last().isHighSurrogate()) name80.dropLast(1) else name80
    }

    /** Decodes a finished page no larger than [maxSide] (decoding smaller is much faster). */
    private suspend fun pageBitmap(doc: ScanDocument, index: Int, maxSide: Int = BitmapIO.MAX_SOURCE_SIDE): Bitmap? {
        val page = doc.pages[index]
        val f = store.outFile(doc.id, page.id)
        if (!f.exists()) PageRenderer.renderAndSave(store, doc.id, page)?.recycle()
        return BitmapIO.decodeFile(f, maxSide)
    }

    /** What a PDF export depends on; equal keys mean the last file can be shared again as is. */
    private fun exportKey(doc: ScanDocument, opt: ExportOptions): String? {
        if (!opt.password.isNullOrEmpty()) return null // never keep encrypted copies around
        return buildString {
            append(doc.id).append('|').append(doc.name).append('|').append(opt.copy(password = null))
            doc.pages.forEach { append('|').append(it.id).append(':').append(it.revision) }
        }
    }

    @Volatile private var lastKey: String? = null
    @Volatile private var lastFile: File? = null

    /** Phase of a running PDF export, for the progress text. */
    enum class Phase { READING_TEXT, WRITING }

    /**
     * Writes the PDF page by page straight to the file (only one page image is in
     * memory at a time, so 200-page documents export on 2-3 GB phones).
     * When OCR cannot run (engine or model missing) the PDF is still made, without
     * a text layer, and [onOcrUnavailable] is called.
     */
    suspend fun exportPdf(
        doc: ScanDocument,
        opt: ExportOptions,
        onOcrUnavailable: () -> Unit = {},
        phase: (Phase) -> Unit = {},
        progress: (Int, Int) -> Unit,
    ): File {
        // Same document, same pages, same options: share the file made a moment ago.
        val key = exportKey(doc, opt)
        val cached = lastFile
        if (key != null && key == lastKey && cached != null && cached.exists()) {
            progress(doc.pages.size, doc.pages.size)
            return cached
        }
        lastKey = null; lastFile = null
        clearOldExports()
        val job = currentCoroutineContext()[Job]
        val pdfA = opt.archival && opt.password.isNullOrEmpty()
        val font = if (opt.ocr) ctx.assets.open("fonts/glyphless.ttf").use { it.readBytes() } else null
        val icc = if (pdfA) ctx.assets.open("icc/sRGB-v2-micro.icc").use { it.readBytes() } else null
        val writer = PdfWriter(opt.pageSize, doc.name, opt.password?.takeIf { it.isNotEmpty() }, font, pdfAIccProfile = icc)
        var ocr = if (opt.ocr) OcrEngine(ctx) else null
        // Cancelling the export also interrupts a page that Tesseract is reading.
        val stopOcr = job?.invokeOnCompletion { cause -> if (cause != null) ocr?.stop() }
        val out = File(exportDir(), safeName(doc.name) + ".pdf")
        val part = File(exportDir(), out.name + ".part")
        try {
            part.outputStream().buffered(64 * 1024).use { stream ->
                writer.begin(stream)
                for (i in doc.pages.indices) {
                    currentCoroutineContext().ensureActive()
                    progress(i, doc.pages.size)
                    val page = doc.pages[i]
                    val bitonal = page.filter == Filter.BW
                    val engine = ocr
                    val cachedWords = if (engine != null) store.loadOcr(doc.id, page, opt.ocrLanguage.code) else null
                    val needOcr = engine != null && cachedWords == null
                    // Decode only as large as needed: the export size, or OCR's working size.
                    val target = if (bitonal) opt.quality.bitonalLongSide else opt.quality.longSide
                    val full = pageBitmap(doc, i, if (needOcr) max(target, OcrEngine.WORK_SIDE) else target) ?: continue
                    var words: List<OcrWord> = cachedWords ?: emptyList()
                    if (needOcr && engine != null) {
                        phase(Phase.READING_TEXT)
                        words = try {
                            engine.recognize(full, opt.ocrLanguage).also {
                                currentCoroutineContext().ensureActive() // a stopped page is not cached
                                store.saveOcr(doc.id, page, it, opt.ocrLanguage.code)
                            }
                        } catch (e: Throwable) {
                            // OOM and cancellation keep their meaning; anything else (no native
                            // library, missing or broken model) means "export without text".
                            if (e is OutOfMemoryError || e is kotlinx.coroutines.CancellationException) throw e
                            runCatching { engine.close() }
                            ocr = null
                            onOcrUnavailable()
                            emptyList()
                        }
                    }
                    phase(Phase.WRITING)
                    val (pdfImage, k) = encode(full, bitonal, opt.quality)
                    val scaledWords = if (k == 1f) words else words.map {
                        OcrWord(it.text, (it.left * k).toInt(), (it.top * k).toInt(), (it.right * k).toInt(), (it.bottom * k).toInt())
                    }
                    // Physical size so a page is about A4 tall when "fit to image" is chosen.
                    val dpi = max(pdfImage.width, pdfImage.height) / 11.69f
                    writer.addPage(PdfPage(pdfImage, dpi, scaledWords))
                }
                writer.finish()
            }
            progress(doc.pages.size, doc.pages.size)
            if (!part.renameTo(out)) { out.delete(); if (!part.renameTo(out)) throw java.io.IOException("cannot write ${out.name}") }
        } catch (e: Throwable) {
            part.delete() // also on cancel: no half-written file is left behind
            throw e
        } finally {
            stopOcr?.dispose()
            runCatching { ocr?.close() }
        }
        if (key != null) { lastKey = key; lastFile = out }
        return out
    }

    /**
     * Encodes one finished page for the PDF at the chosen quality (recycles [full]).
     * Returns the image and the scale factor applied to it.
     */
    private fun encode(full: Bitmap, bitonal: Boolean, quality: Quality): Pair<PdfImage, Float> {
        val fullW = full.width
        val img = BitmapIO.fit(full, if (bitonal) quality.bitonalLongSide else quality.longSide)
        val k = img.width.toFloat() / fullW
        val pdfImage = if (bitonal) {
            val px = IntArray(img.width * img.height)
            img.getPixels(px, 0, img.width, 0, 0, img.width, img.height)
            PdfImage.Bitonal(img.width, img.height, ImageFilters.packBits(px, img.width, img.height))
        } else {
            val bos = ByteArrayOutputStream()
            img.compress(Bitmap.CompressFormat.JPEG, quality.jpegQuality, bos)
            val bytes = bos.toByteArray()
            PdfImage.Jpeg(img.width, img.height, bytes, PdfWriter.jpegComponents(bytes))
        }
        img.recycle()
        return pdfImage to k
    }

    /**
     * Estimated PDF size in bytes, without decoding any image. (1.1.1 encoded three
     * full pages, which competed with the export itself and made Share feel stuck.)
     * Uses each page's stored JPEG size scaled by pixel count and a JPEG-quality
     * factor; black-and-white pages are about 1 bit per pixel after Flate.
     */
    fun estimatePdfSize(doc: ScanDocument, opt: ExportOptions): Long {
        var total = 2000L
        for (p in doc.pages) {
            val f = store.outFile(doc.id, p.id)
            val (w, h) = BitmapIO.imageSize(f) ?: continue
            val bitonal = p.filter == Filter.BW
            val target = if (bitonal) opt.quality.bitonalLongSide else opt.quality.longSide
            val scale = minOf(1f, target.toFloat() / max(w, h))
            val pixels = w * h * scale * scale
            total += if (bitonal) {
                (pixels / 8f * BITONAL_DEFLATE_RATIO).toLong()
            } else {
                // Stored pages are JPEG q92; the factors were measured on scanned pages.
                // Downscaled pages keep more detail per pixel, so size falls roughly linearly with scale.
                (f.length() * scale * jpegFactor(opt.quality)).toLong()
            }
            total += 1500L
        }
        return total
    }

    private fun jpegFactor(q: Quality) = when (q) {
        Quality.SMALL -> 0.45f
        Quality.BALANCED -> 0.6f
        Quality.HIGH -> 1.0f
    }

    private companion object {
        const val BITONAL_DEFLATE_RATIO = 0.12f
    }

    suspend fun exportJpegs(doc: ScanDocument, opt: ExportOptions, progress: (Int, Int) -> Unit): List<File> {
        clearOldExports()
        val files = ArrayList<File>()
        val base = safeName(doc.name)
        for (i in doc.pages.indices) {
            currentCoroutineContext().ensureActive()
            progress(i, doc.pages.size)
            val full = pageBitmap(doc, i) ?: continue
            val img = BitmapIO.fit(full, opt.quality.longSide)
            val f = File(exportDir(), if (doc.pages.size == 1) "$base.jpg" else "${base}_${i + 1}.jpg")
            f.outputStream().buffered().use { img.compress(Bitmap.CompressFormat.JPEG, opt.quality.jpegQuality, it) }
            img.recycle()
            files.add(f)
        }
        progress(doc.pages.size, doc.pages.size)
        return files
    }
}
