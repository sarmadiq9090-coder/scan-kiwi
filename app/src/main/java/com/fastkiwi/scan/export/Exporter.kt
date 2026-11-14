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
)

class Exporter(private val ctx: Context, private val store: DocumentStore) {

    private fun exportDir(): File = File(ctx.cacheDir, "exports").apply { mkdirs() }

    /** Removes earlier exports; they only live until shared or saved. */
    fun clearOldExports() {
        exportDir().listFiles()?.forEach { it.delete() }
    }

    fun safeName(name: String): String {
        val cleaned = name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().trim('.')
        return cleaned.ifEmpty { "scan" }.take(80)
    }

    private suspend fun pageBitmap(doc: ScanDocument, index: Int): Bitmap? {
        val page = doc.pages[index]
        val f = store.outFile(doc.id, page.id)
        if (!f.exists()) PageRenderer.renderAndSave(store, doc.id, page)?.recycle()
        return BitmapIO.decodeFile(f)
    }

    suspend fun exportPdf(doc: ScanDocument, opt: ExportOptions, progress: (Int, Int) -> Unit): File {
        clearOldExports()
        val font = if (opt.ocr) ctx.assets.open("fonts/glyphless.ttf").use { it.readBytes() } else null
        val writer = PdfWriter(opt.pageSize, doc.name, opt.password?.takeIf { it.isNotEmpty() }, font)
        val ocr = if (opt.ocr) OcrEngine(ctx) else null
        try {
            for (i in doc.pages.indices) {
                progress(i, doc.pages.size)
                val page = doc.pages[i]
                val full = pageBitmap(doc, i) ?: continue
                var words: List<OcrWord> = emptyList()
                if (ocr != null) {
                    words = store.loadOcr(doc.id, page) ?: ocr.recognize(full, opt.ocrLanguage).also { store.saveOcr(doc.id, page, it) }
                }
                val fullW = full.width
                val (pdfImage, k) = encode(full, page.filter == Filter.BW, opt.quality)
                val scaledWords = if (k == 1f) words else words.map {
                    OcrWord(it.text, (it.left * k).toInt(), (it.top * k).toInt(), (it.right * k).toInt(), (it.bottom * k).toInt())
                }
                // Physical size so a page is about A4 tall when "fit to image" is chosen.
                val dpi = max(pdfImage.width, pdfImage.height) / 11.69f
                writer.addPage(PdfPage(pdfImage, dpi, scaledWords))
            }
            progress(doc.pages.size, doc.pages.size)
        } finally {
            ocr?.close()
        }
        val out = File(exportDir(), safeName(doc.name) + ".pdf")
        out.outputStream().buffered().use { writer.write(it) }
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
     * Estimated PDF size in bytes without OCR: encodes up to 3 evenly spread
     * pages and extrapolates. Bitonal pages are counted after Flate compression.
     */
    suspend fun estimatePdfSize(doc: ScanDocument, opt: ExportOptions): Long {
        if (doc.pages.isEmpty()) return 0
        val n = doc.pages.size
        val sample = if (n <= 3) doc.pages.indices.toList() else listOf(0, n / 2, n - 1)
        var bytes = 0L
        for (i in sample) {
            val full = pageBitmap(doc, i) ?: continue
            val (img, _) = encode(full, doc.pages[i].filter == Filter.BW, opt.quality)
            bytes += when (img) {
                is PdfImage.Jpeg -> img.bytes.size.toLong()
                is PdfImage.Bitonal -> PdfWriter.deflate(img.bits).size.toLong()
            }
        }
        val perPage = bytes / sample.size
        return perPage * n + 1500L * n + 2000L
    }

    suspend fun exportJpegs(doc: ScanDocument, opt: ExportOptions, progress: (Int, Int) -> Unit): List<File> {
        clearOldExports()
        val files = ArrayList<File>()
        val base = safeName(doc.name)
        for (i in doc.pages.indices) {
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
