// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
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
 * What the exporter needs from an OCR engine. A seam for tests (a fake engine runs
 * on the JVM, where Tesseract's native library does not) and the first step toward
 * the 1.3 `OcrEngine` interface.
 */
interface PageOcr {
    /** Recognises words in [page]; boxes are in [page]'s pixel coordinates. Blocking. */
    fun recognize(page: Bitmap, lang: OcrLanguage): List<OcrWord>

    /** Interrupts a running [recognize] from another thread (export cancelled). */
    fun stop()

    fun close()
}

/**
 * Fully offline OCR with Tesseract (Apache-2.0) and the tessdata_fast LSTM
 * models for Arabic and English. No network, no Google Play services.
 */
class OcrEngine(private val ctx: Context) : PageOcr {
    @Volatile private var api: TessBaseAPI? = null
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
        // Drop the old engine first, so a failed init never leaves a recycled one in use.
        api?.recycle()
        api = null
        loaded = null
        val dir = dataDir()
        val a = TessBaseAPI()
        if (!a.init(dir.absolutePath, lang.code, TessBaseAPI.OEM_LSTM_ONLY)) {
            a.recycle()
            throw IllegalStateException("Tesseract init failed")
        }
        a.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO)
        api = a
        loaded = lang.code
        return a
    }

    /** Recognises words in a page image; boxes are in that image's pixel coordinates. */
    @Synchronized
    override fun recognize(page: Bitmap, lang: OcrLanguage): List<OcrWord> {
        val a = ensure(lang)
        // ~200 dpi on A4 is plenty for Tesseract and about 1.8x faster than 3200 px pages.
        val k = WORK_SIDE.toFloat() / max(page.width, page.height)
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

    /** Interrupts a running [recognize] from another thread (export cancelled). */
    override fun stop() {
        runCatching { api?.stop() }
    }

    @Synchronized
    override fun close() {
        api?.recycle()
        api = null
        loaded = null
    }

    companion object {
        /** Longest side of the image Tesseract reads. */
        const val WORK_SIDE = 2400
    }
}
