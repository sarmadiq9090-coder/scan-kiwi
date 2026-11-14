package com.fastkiwi.scan.ocr

import android.content.Context
import android.graphics.Bitmap
import com.fastkiwi.scan.BuildConfig
import com.fastkiwi.scan.core.ImageFilters
import com.fastkiwi.scan.core.OcrWord
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File
import kotlin.math.max

/** OCR languages offered. Tesseract codes; models ship inside the APK (offline). */
enum class OcrLanguage(val code: String) { ARABIC_ENGLISH("ara+eng"), ENGLISH("eng"), ARABIC("ara") }

/**
 * Fully offline OCR with Tesseract (Apache-2.0) and the tessdata_fast LSTM
 * models for Arabic and English. No network, no Google Play services.
 */
class OcrEngine(private val ctx: Context) {
    private var api: TessBaseAPI? = null
    private var loaded: String? = null

    private fun dataDir(): File {
        val root = File(ctx.filesDir, "ocr")
        val tess = File(root, "tessdata").apply { mkdirs() }
        // Re-copy the bundled models once per app version.
        val marker = File(root, "models.version")
        val version = BuildConfig.VERSION_CODE.toString()
        val upToDate = marker.exists() && marker.readText() == version
        for (name in listOf("ara.traineddata", "eng.traineddata")) {
            val f = File(tess, name)
            if (!upToDate || !f.exists()) {
                val tmp = File(tess, "$name.tmp")
                ctx.assets.open("tessdata/$name").use { input -> tmp.outputStream().use { input.copyTo(it) } }
                f.delete()
                tmp.renameTo(f)
            }
        }
        marker.writeText(version)
        return root
    }

    @Synchronized
    private fun ensure(lang: OcrLanguage): TessBaseAPI {
        api?.let { if (loaded == lang.code) return it }
        api?.recycle()
        val a = TessBaseAPI()
        check(a.init(dataDir().absolutePath, lang.code, TessBaseAPI.OEM_LSTM_ONLY)) { "Tesseract init failed" }
        a.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO)
        api = a
        loaded = lang.code
        return a
    }

    /** Recognises words in a page image; boxes are in that image's pixel coordinates. */
    @Synchronized
    fun recognize(page: Bitmap, lang: OcrLanguage): List<OcrWord> {
        val a = ensure(lang)
        // Tesseract works best around 300 dpi; our pages are <= 3200 px so keep them.
        val k = 3200f / max(page.width, page.height)
        val bmp = if (k < 1f) Bitmap.createScaledBitmap(page, (page.width * k).toInt(), (page.height * k).toInt(), true) else page
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        val gray = ImageFilters.normalizedGray(px, bmp.width, bmp.height)
        a.setImage(gray.data, bmp.width, bmp.height, 1, bmp.width)
        val scale = if (bmp === page) 1f else page.width.toFloat() / bmp.width
        if (bmp !== page) bmp.recycle()
        a.getUTF8Text() // runs recognition
        val words = ArrayList<OcrWord>()
        val it = a.getResultIterator() ?: return words
        try {
            it.begin()
            do {
                val text = it.getUTF8Text(TessBaseAPI.PageIteratorLevel.RIL_WORD) ?: continue
                if (text.isBlank() || it.confidence(TessBaseAPI.PageIteratorLevel.RIL_WORD) < 35f) continue
                val r = it.getBoundingRect(TessBaseAPI.PageIteratorLevel.RIL_WORD)
                words.add(OcrWord(text, (r.left * scale).toInt(), (r.top * scale).toInt(), (r.right * scale).toInt(), (r.bottom * scale).toInt()))
            } while (it.next(TessBaseAPI.PageIteratorLevel.RIL_WORD))
        } finally {
            it.delete()
            a.clear()
        }
        return words
    }

    @Synchronized
    fun close() {
        api?.recycle()
        api = null
        loaded = null
    }
}
