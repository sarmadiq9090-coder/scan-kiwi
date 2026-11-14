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
                val bitonal = page.filter == Filter.BW
                val target = if (bitonal) opt.quality.bitonalLongSide else opt.quality.longSide
                val fullW = full.width
                val img = BitmapIO.fit(full, target)
                val k = img.width.toFloat() / fullW
                val scaledWords = if (k == 1f) words else words.map {
                    OcrWord(it.text, (it.left * k).toInt(), (it.top * k).toInt(), (it.right * k).toInt(), (it.bottom * k).toInt())
                }
                val pdfImage = if (bitonal) {
                    val px = IntArray(img.width * img.height)
                    img.getPixels(px, 0, img.width, 0, 0, img.width, img.height)
                    PdfImage.Bitonal(img.width, img.height, ImageFilters.packBits(px, img.width, img.height))
                } else {
                    val bos = ByteArrayOutputStream()
                    img.compress(Bitmap.CompressFormat.JPEG, opt.quality.jpegQuality, bos)
                    val bytes = bos.toByteArray()
                    PdfImage.Jpeg(img.width, img.height, bytes, PdfWriter.jpegComponents(bytes))
                }
                // Physical size so a page is about A4 tall when "fit to image" is chosen.
                val dpi = max(img.width, img.height) / 11.69f
                writer.addPage(PdfPage(pdfImage, dpi, scaledWords))
                img.recycle()
            }
            progress(doc.pages.size, doc.pages.size)
        } finally {
            ocr?.close()
        }
        val out = File(exportDir(), safeName(doc.name) + ".pdf")
        out.outputStream().buffered().use { writer.write(it) }
        return out
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
