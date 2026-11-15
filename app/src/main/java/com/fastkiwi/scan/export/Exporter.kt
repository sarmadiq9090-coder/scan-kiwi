// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.export

import android.content.Context
import android.graphics.Bitmap
import com.fastkiwi.scan.core.Filter
import com.fastkiwi.scan.core.OcrWord
import com.fastkiwi.scan.core.PageSize
import com.fastkiwi.scan.core.PdfImage
import com.fastkiwi.scan.core.PdfPage
import com.fastkiwi.scan.core.PdfWriter
import com.fastkiwi.scan.data.DocumentStore
import com.fastkiwi.scan.data.ScanDocument
import com.fastkiwi.scan.imaging.BitmapIO
import com.fastkiwi.scan.imaging.PageRenderer
import com.fastkiwi.scan.ocr.DefaultOcrEngineFactory
import com.fastkiwi.scan.ocr.OcrInput
import com.fastkiwi.scan.ocr.OcrLanguage
import com.fastkiwi.scan.ocr.OcrMode
import com.fastkiwi.scan.ocr.OcrOptions
import com.fastkiwi.scan.ocr.OcrService
import com.fastkiwi.scan.ocr.fromBitmap
import com.fastkiwi.scan.ocr.key
import com.fastkiwi.scan.ocr.toOcrLangs
import com.fastkiwi.scan.pdf.EncodeSettings
import com.fastkiwi.scan.pdf.PageDecor
import com.fastkiwi.scan.pdf.PageNumbering
import com.fastkiwi.scan.pdf.SafeOutput
import com.fastkiwi.scan.pdf.TargetSize
import com.fastkiwi.scan.pdf.Watermark
import com.fastkiwi.scan.perf.MemoryBudget
import com.fastkiwi.scan.perf.PixelStrips
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/** Size/quality presets. SMALL targets messaging apps and e-mail attachments. */
enum class Quality(val longSide: Int, val jpegQuality: Int, val bitonalLongSide: Int) {
    SMALL(1700, 55, 2200),
    BALANCED(2400, 72, 2800),
    HIGH(3200, 90, 3200);

    val settings: EncodeSettings get() = EncodeSettings(longSide, jpegQuality, bitonalLongSide)
}

data class ExportOptions(
    val pageSize: PageSize = PageSize.A4,
    val quality: Quality = Quality.BALANCED,
    val ocr: Boolean = false,
    val ocrLanguage: OcrLanguage = OcrLanguage.ARABIC_ENGLISH,
    val password: String? = null,
    /** PDF/A-2b ("أرشيفي"); ignored when a password is set (PDF/A forbids encryption). */
    val archival: Boolean = false,
    // 2.0-pdf:
    /** PDF-06 "custom target size": the file should be at most this many bytes; [quality] is then ignored. */
    val targetBytes: Long? = null,
    /** PDF-09 page numbers, drawn into the pages. */
    val pageNumbers: PageNumbering? = null,
    /** PDF-10 text watermark, drawn into the pages. */
    val watermark: Watermark? = null,
)

/**
 * A finished PDF export (2.0-pdf). With a target size, [reached] says whether the real file
 * ([bytes]) is at most [targetBytes]; it is computed from the file on disk, never from an estimate.
 */
data class PdfExportResult(val file: File, val bytes: Long, val targetBytes: Long?, val settings: EncodeSettings) {
    val reached: Boolean? get() = targetBytes?.let { bytes <= it }
}

class Exporter(
    private val ctx: Context,
    private val store: DocumentStore,
    // 2.0-ocr: the OCR engines, shared with the background reader (AppViewModel); tests pass one with a
    // fake engine. Null: this exporter makes its own for each export and releases it at the end.
    private val sharedOcr: OcrService? = null,
    /** 2.0-pdf: opens the temp file an export writes; tests inject failures here. */
    // 2.0-gates: through the fault-injection seam (inert unless a test sets IoFaults.hook).
    private val openStream: (File) -> OutputStream = { com.fastkiwi.scan.data.IoFaults.wrap(FileOutputStream(it), it) },
    /** 2.0-ocr: «دقيق» Accurate or «سريع» Fast (Settings). */
    private val ocrMode: () -> OcrMode = { OcrMode.ACCURATE },
) {
    private val decor by lazy { PageDecor.load(ctx) }

    private fun exportDir(): File = File(ctx.cacheDir, "exports").apply { mkdirs() }

    /**
     * Removes earlier exports (all, or those last written before [before]); they only live until
     * shared or saved. [except] keeps the file just made. Leftover `.part` files of a killed
     * export go too (the app start passes its start time, so a running export is never touched).
     */
    fun clearOldExports(before: Long = Long.MAX_VALUE, except: Set<File> = emptySet()) {
        exportDir().listFiles()?.forEach { if (it.lastModified() < before && it !in except) it.delete() }
    }

    fun safeName(name: String): String {
        val cleaned = name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().trim('.')
        val name80 = cleaned.ifEmpty { "scan" }.take(80)
        // Never cut an emoji (surrogate pair) in half: a lone surrogate is not a valid file name.
        return if (name80.isNotEmpty() && name80.last().isHighSurrogate()) name80.dropLast(1) else name80
    }

    /**
     * Decodes a finished page no larger than [maxSide] (decoding smaller is much faster).
     * 2.0-perf: with a [pool], into the bitmap the previous page used (give it back with [release]).
     */
    private suspend fun pageBitmap(doc: ScanDocument, index: Int, maxSide: Int = BitmapIO.MAX_SOURCE_SIDE, pool: BitmapIO.Pool? = null): Bitmap? {
        val page = doc.pages[index]
        val f = store.outFile(doc.id, page.id)
        if (!f.exists()) PageRenderer.renderAndSave(store, doc.id, page)?.recycle()
        return if (pool != null) BitmapIO.decodeFile(f, maxSide, pool) else BitmapIO.decodeFile(f, maxSide)
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

    /** Phase of a running PDF export, for the progress text. FITTING: trying sizes for a target. */
    enum class Phase { READING_TEXT, WRITING, FITTING }

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
    ): File = exportPdfResult(doc, opt, onOcrUnavailable, phase, progress).file

    @Volatile private var lastResult: PdfExportResult? = null

    /**
     * As [exportPdf], with the size outcome. 2.0-pdf integrity (PRD §6.3): free-space check
     * (estimate × 1.2), write to a `.part` file, validate (xref + page count), atomic rename;
     * an earlier export is only replaced once the new one is complete. With a target size the
     * settings are searched on sample pages first, then whole passes step down until the file
     * fits or the smallest settings are reached ([PdfExportResult.reached]).
     */
    suspend fun exportPdfResult(
        doc: ScanDocument,
        opt: ExportOptions,
        onOcrUnavailable: () -> Unit = {},
        phase: (Phase) -> Unit = {},
        progress: (Int, Int) -> Unit,
    ): PdfExportResult {
        // Same document, same pages, same options: share the file made a moment ago.
        val key = exportKey(doc, opt)
        val cached = lastResult
        if (key != null && key == lastKey && cached != null && cached.file == lastFile && cached.file.exists()) {
            progress(doc.pages.size, doc.pages.size)
            return cached
        }
        lastKey = null; lastFile = null; lastResult = null
        val safe = SafeOutput(File(exportDir(), safeName(doc.name) + ".pdf"), openStream)
        val pass = PdfRun(doc, opt, onOcrUnavailable, phase, progress)
        val result = try {
            val target = opt.targetBytes
            if (target == null) {
                val s = MemoryBudget.cap(opt.quality.settings) // 2.0-perf
                safe.checkSpace(estimatePdfSize(doc, s))
                pass.write(safe, s)
                PdfExportResult(safe.final, safe.final.length(), null, s)
            } else {
                fitToTarget(pass, safe, target)
            }
        } finally {
            pass.close()
        }
        // Earlier exports go only now, so a failed export never removed the last good file.
        clearOldExports(except = setOf(result.file))
        if (key != null) { lastKey = key; lastFile = result.file; lastResult = result }
        return result
    }

    /** PDF-06 target size: estimate on sample pages, then full passes from the chosen rung down. */
    private suspend fun fitToTarget(pass: PdfRun, safe: SafeOutput, target: Long): PdfExportResult {
        val doc = pass.doc
        pass.phase(Phase.FITTING)
        val samples = samplePages(doc.pages.size)
        val estimates = HashMap<Int, Long>()
        suspend fun estimate(level: Int): Long = estimates[level] ?: run {
            val s = TargetSize.LADDER[level]
            var sum = 0L
            for (i in samples) {
                currentCoroutineContext().ensureActive()
                sum += pass.encodedSize(i, s)
            }
            val e = PDF_OVERHEAD + doc.pages.size * PAGE_OVERHEAD + sum * doc.pages.size / samples.size.coerceAtLeast(1)
            estimates[level] = e
            e
        }
        var level = TargetSize.choose(target) { estimate(it) }
        safe.checkSpace(estimate(level))
        var passes = 0
        while (true) {
            passes++
            val s = TargetSize.LADDER[level]
            pass.write(safe, s)
            val bytes = safe.final.length()
            if (bytes <= target || passes >= TargetSize.MAX_PASSES) return PdfExportResult(safe.final, bytes, target, s)
            pass.phase(Phase.FITTING)
            level = TargetSize.afterMiss(level, bytes, target) { estimate(it) } ?: return PdfExportResult(safe.final, bytes, target, s)
        }
    }

    /** Up to three pages spread over the document (first, middle, last). */
    private fun samplePages(n: Int): List<Int> = if (n <= 3) (0 until n).toList() else listOf(0, n / 2, n - 1)

    /** One export's state shared by its passes: the OCR engine (opened once), fonts, callbacks. */
    private inner class PdfRun(
        val doc: ScanDocument,
        val opt: ExportOptions,
        val onOcrUnavailable: () -> Unit,
        val phase: (Phase) -> Unit,
        val progress: (Int, Int) -> Unit,
    ) {
        val pdfA = opt.archival && opt.password.isNullOrEmpty()
        val font = if (opt.ocr) ctx.assets.open("fonts/glyphless.ttf").use { it.readBytes() } else null
        val icc = if (pdfA) ctx.assets.open("icc/sRGB-v2-micro.icc").use { it.readBytes() } else null
        // 2.0-ocr: one OCR service for the export (shared with the background reader when given).
        val ocr = if (opt.ocr) (sharedOcr ?: OcrService(DefaultOcrEngineFactory(ctx))) else null
        // 2.0-perf: low-memory mode reads a 2000 px plane (MemoryBudget).
        val ocrOptions = OcrOptions(opt.ocrLanguage.toOcrLangs(), ocrMode(), MemoryBudget.ocrMaxSide())
        var ocrOn = ocr != null
        var revived = false
        // 2.0-perf: one decode bitmap reused page after page (inBitmap).
        val pool = BitmapIO.Pool()

        fun close() {
            pool.close()
            if (sharedOcr == null) runCatching { ocr?.close() }
        }

        /** Bytes of page [i] encoded at [s0] (decorations included), for the size search. */
        suspend fun encodedSize(i: Int, s0: EncodeSettings): Long {
            val s = MemoryBudget.cap(s0) // 2.0-perf
            val bitonal = doc.pages[i].filter == Filter.BW
            val full = pageBitmap(doc, i, if (bitonal) s.bitonalLongSide else s.longSide, pool) ?: return 0L
            return when (val img = encode(full, bitonal, s, i, doc.pages.size, opt, pool).first) {
                is PdfImage.Jpeg -> img.bytes.size.toLong()
                is PdfImage.Bitonal -> PdfWriter.deflate(img.bits).size.toLong()
            }
        }

        /** One full pass: every page at [s0] into [safe]'s `.part` file, then validated and renamed. */
        suspend fun write(safe: SafeOutput, s0: EncodeSettings) {
            val s = MemoryBudget.cap(s0) // 2.0-perf: low-memory mode keeps pages at most 2000 px
            val writer = PdfWriter(opt.pageSize, doc.name, opt.password?.takeIf { it.isNotEmpty() }, font, pdfAIccProfile = icc)
            var written = 0
            safe.write { stream ->
                writer.begin(stream)
                for (i in doc.pages.indices) {
                    currentCoroutineContext().ensureActive()
                    progress(i, doc.pages.size)
                    val page = doc.pages[i]
                    val bitonal = page.filter == Filter.BW
                    if (!revived) { ocr?.reviveIfDead(); revived = true }
                    // 2.0-ocr: cached words count only when the engine that would read the page now wrote them.
                    val engineKey = if (ocrOn && ocr != null) ocr.selectionFor(ocrOptions)?.engine?.key else null
                    val cached = if (engineKey != null) store.loadOcrFrame(doc.id, page, opt.ocrLanguage.code, engineKey) else null
                    val needOcr = engineKey != null && cached == null
                    // Decode only as large as needed: the export size, or OCR's working size.
                    val target = if (bitonal) s.bitonalLongSide else s.longSide
                    val full = pageBitmap(doc, i, if (needOcr) max(target, ocrOptions.maxSide) else target, pool) ?: continue
                    // Cached boxes are in the frame OCR read (often larger than this export's
                    // image): bring them into this bitmap's pixels before anything else.
                    var words: List<OcrWord> = cached?.let {
                        scaleWords(it.words, full.width.toFloat() / it.frameWidth, full.height.toFloat() / it.frameHeight)
                    } ?: emptyList()
                    if (needOcr && ocr != null) {
                        phase(Phase.READING_TEXT)
                        words = try {
                            // Cancelling the export stops the engine inside this page.
                            val r = ocr.recognize(OcrInput.fromBitmap(full, ocrOptions.maxSide), ocrOptions)
                            currentCoroutineContext().ensureActive() // a stopped page is not cached
                            store.saveOcr(doc.id, page, r, opt.ocrLanguage.code, ocrOptions.maxSide)
                            r.toPdfWords()
                        } catch (e: Throwable) {
                            // OOM and cancellation keep their meaning; anything else (no native
                            // library, missing or broken model) means "export without text".
                            if (e is OutOfMemoryError || e is kotlinx.coroutines.CancellationException) throw e
                            ocrOn = false
                            onOcrUnavailable()
                            emptyList()
                        }
                    }
                    phase(Phase.WRITING)
                    val (pdfImage, k) = encode(full, bitonal, s, i, doc.pages.size, opt, pool)
                    val scaledWords = scaleWords(words, k, k)
                    // Physical size so a page is about A4 tall when "fit to image" is chosen.
                    val dpi = max(pdfImage.width, pdfImage.height) / 11.69f
                    writer.addPage(PdfPage(pdfImage, dpi, scaledWords))
                    written++
                }
                writer.finish()
                // Cancelled while the last page was written: the file must not appear.
                currentCoroutineContext().ensureActive()
            }
            progress(doc.pages.size, doc.pages.size)
            // Validated before it can replace anything or be shared.
            safe.commitPdf(written)
        }
    }

    private fun scaleWords(words: List<OcrWord>, kx: Float, ky: Float): List<OcrWord> =
        if (kx == 1f && ky == 1f) words else words.map {
            OcrWord(it.text, (it.left * kx).roundToInt(), (it.top * ky).roundToInt(), (it.right * kx).roundToInt(), (it.bottom * ky).roundToInt())
        }

    /**
     * Encodes one finished page for the PDF at the chosen quality (recycles [full]).
     * Returns the image and the scale factor applied to it.
     */
    private fun encode(full: Bitmap, bitonal: Boolean, quality: EncodeSettings, index: Int = 0, total: Int = 1, opt: ExportOptions? = null, pool: BitmapIO.Pool? = null): Pair<PdfImage, Float> {
        val fullW = full.width
        // 2.0-perf: a pooled bitmap goes back to the pool instead of being recycled.
        val release: (Bitmap) -> Unit = { b -> if (pool != null) pool.give(b) else b.recycle() }
        val side = if (bitonal) quality.bitonalLongSide else quality.longSide
        var img = if (max(full.width, full.height) <= side) full else {
            val kk = side.toFloat() / max(full.width, full.height)
            Bitmap.createScaledBitmap(full, max(1, (full.width * kk).toInt()), max(1, (full.height * kk).toInt()), true)
                .also { if (it !== full) release(full) }
        }
        val k = img.width.toFloat() / fullW
        // 2.0-pdf: page numbers and watermark go into the pixels (see PageDecor for why).
        if (opt != null && decor.isActive(opt.pageNumbers, opt.watermark)) {
            if (!img.isMutable) img = img.copy(Bitmap.Config.ARGB_8888, true).also { release(img) }
            decor.draw(img, index, total, opt.pageNumbers, opt.watermark, bitonal)
        }
        val pdfImage = if (bitonal) {
            // 2.0-perf: packed a strip of rows at a time (no full ARGB IntArray copy); same bits as packBits.
            PdfImage.Bitonal(img.width, img.height, PixelStrips.packBits(img))
        } else {
            val bos = ByteArrayOutputStream()
            img.compress(Bitmap.CompressFormat.JPEG, quality.jpegQuality, bos)
            val bytes = bos.toByteArray()
            PdfImage.Jpeg(img.width, img.height, bytes, PdfWriter.jpegComponents(bytes))
        }
        release(img)
        return pdfImage to k
    }

    /**
     * Estimated PDF size in bytes, without decoding any image. (1.1.1 encoded three
     * full pages, which competed with the export itself and made Share feel stuck.)
     * Uses each page's stored JPEG size scaled by pixel count and a JPEG-quality
     * factor; black-and-white pages are about 1 bit per pixel after Flate.
     */
    fun estimatePdfSize(doc: ScanDocument, opt: ExportOptions): Long =
        opt.targetBytes?.let { minOf(it, estimatePdfSize(doc, TargetSize.LADDER.first())) } ?: estimatePdfSize(doc, opt.quality.settings)

    fun estimatePdfSize(doc: ScanDocument, s0: EncodeSettings): Long {
        val s = MemoryBudget.cap(s0) // 2.0-perf: what the export will really write
        var total = 2000L
        for (p in doc.pages) {
            val f = store.outFile(doc.id, p.id)
            val (w, h) = BitmapIO.imageSize(f) ?: continue
            val bitonal = p.filter == Filter.BW
            val target = if (bitonal) s.bitonalLongSide else s.longSide
            val scale = minOf(1f, target.toFloat() / max(w, h))
            val pixels = w * h * scale * scale
            total += if (bitonal) {
                (pixels / 8f * BITONAL_DEFLATE_RATIO).toLong()
            } else {
                // Stored pages are JPEG q92; the factors were measured on scanned pages.
                // Downscaled pages keep more detail per pixel, so size falls roughly linearly with scale.
                (f.length() * scale * jpegFactor(s.jpegQuality)).toLong()
            }
            total += 1500L
        }
        return total
    }

    /** Measured on scanned pages for q55 / q72 / q90 (0.45 / 0.6 / 1.0), interpolated between. */
    private fun jpegFactor(q: Int): Float = when {
        q <= 55 -> 0.45f * (q / 55f).coerceAtLeast(0.4f)
        q <= 72 -> 0.45f + (q - 55) * (0.15f / 17f)
        else -> 0.6f + (q - 72) * (0.4f / 18f)
    }

    private companion object {
        const val BITONAL_DEFLATE_RATIO = 0.12f
        /** Header, catalog, page tree, xref: ~2 KB; objects per page ~1.5 KB. */
        const val PDF_OVERHEAD = 2_500L
        const val PAGE_OVERHEAD = 1_600L
    }

    suspend fun exportJpegs(doc: ScanDocument, opt: ExportOptions, progress: (Int, Int) -> Unit): List<File> {
        clearOldExports()
        val files = ArrayList<File>()
        val base = safeName(doc.name)
        try {
            for (i in doc.pages.indices) {
                currentCoroutineContext().ensureActive()
                progress(i, doc.pages.size)
                // 2.0-perf: decode only as large as the export (was the 3200 px source size), within the budget.
                val side = MemoryBudget.capSide(opt.quality.longSide)
                val full = pageBitmap(doc, i, side) ?: continue
                var img = BitmapIO.fit(full, side)
                if (decor.isActive(opt.pageNumbers, opt.watermark)) {
                    if (!img.isMutable) img = img.copy(Bitmap.Config.ARGB_8888, true).also { img.recycle() }
                    decor.draw(img, i, doc.pages.size, opt.pageNumbers, opt.watermark, bitonal = false)
                }
                // 2.0-pdf: each image through a temp file and a rename, like the PDF.
                val safe = SafeOutput(File(exportDir(), if (doc.pages.size == 1) "$base.jpg" else "${base}_${i + 1}.jpg"), openStream)
                val ok = safe.write { img.compress(Bitmap.CompressFormat.JPEG, opt.quality.jpegQuality, it) }
                img.recycle()
                if (!ok) { safe.part.delete(); throw java.io.IOException("cannot write ${safe.final.name}") }
                safe.commit()
                files.add(safe.final)
            }
        } catch (e: Throwable) {
            files.forEach { it.delete() } // no half set of pages is ever shared
            throw e
        }
        progress(doc.pages.size, doc.pages.size)
        return files
    }
}
