// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.ocr

import android.content.Context
import com.googlecode.tesseract.android.TessBaseAPI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * «سريع» Fast mode: fully offline OCR with Tesseract and the tessdata_fast LSTM models for Arabic and
 * English (the 1.2 engine, same steps in the same order, so the words are the same as in 1.2).
 */
class TesseractOcrEngine(ctx: Context) : OcrEngine {
    override val id = ID
    override val version = VERSION
    private val session = TesseractSession(ctx)
    private val mutex = Mutex()

    override suspend fun recognize(page: OcrInput, options: OcrOptions): OcrResult = mutex.withLock {
        withContext(Dispatchers.Default) {
            val api = session.ensure(options.tesseractCode, TessBaseAPI.PageSegMode.PSM_AUTO)
            val norm = Illumination.normalize(page.gray, page.width, page.height)
            try {
                runStoppable(stop = session::stop) {
                    api.setImage(norm, page.width, page.height, 1, page.width)
                    api.getUTF8Text() // runs recognition
                    readResult(api, page)
                }
            } finally {
                runCatching { api.clear() }
                session.afterRead()
            }
        }
    }

    private fun readResult(api: TessBaseAPI, page: OcrInput): OcrResult {
        val scale = page.scaleToPage
        val asm = TesseractLineAssembler()
        val it = api.resultIterator ?: return asm.result(page, ID, VERSION)
        try {
            it.begin()
            do {
                val lineStart = it.isAtBeginningOf(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE)
                val text = it.getUTF8Text(TessBaseAPI.PageIteratorLevel.RIL_WORD)
                val conf = it.confidence(TessBaseAPI.PageIteratorLevel.RIL_WORD)
                val r = it.getBoundingRect(TessBaseAPI.PageIteratorLevel.RIL_WORD)
                asm.add(text, conf, OcrBox((r.left * scale).toInt(), (r.top * scale).toInt(), (r.right * scale).toInt(), (r.bottom * scale).toInt()), lineStart)
            } while (it.next(TessBaseAPI.PageIteratorLevel.RIL_WORD))
        } finally {
            it.delete()
        }
        return asm.result(page, ID, VERSION)
    }

    override fun close() = session.close()

    companion object {
        const val ID = "tesseract"

        /** Bump on any change that alters output (models, preprocessing, thresholds, PSM). */
        const val VERSION = 1

        /** Raw Tesseract scale, compared before converting to 0..1 (as in 1.2). */
        const val MIN_WORD_CONFIDENCE = 35f
    }
}

/**
 * Builds lines from Tesseract's RIL_WORD iterator: lines are consecutive runs of words, a new one starts
 * at RIL_TEXTLINE beginnings. The flat word list is exactly 1.2's (same filter, same order).
 */
internal class TesseractLineAssembler {
    private val lines = ArrayList<RecognizedLine>()
    private var cur = ArrayList<RecognizedWord>()

    fun add(text: String?, rawConfidence: Float, box: OcrBox, lineStart: Boolean) {
        if (lineStart) flush()
        if (text == null || text.isBlank() || rawConfidence < TesseractOcrEngine.MIN_WORD_CONFIDENCE) return
        cur.add(RecognizedWord(text, box, (rawConfidence / 100f).coerceIn(0f, 1f)))
    }

    private fun flush() {
        if (cur.isEmpty()) return
        val box = cur.drop(1).fold(cur[0].box) { b, w -> b.union(w.box) }
        val dir = majority(cur.map { it.direction })
        lines.add(RecognizedLine(cur, box, cur.map { it.confidence }.average().toFloat(), dir))
        cur = ArrayList()
    }

    fun result(page: OcrInput, id: String, version: Int): OcrResult {
        flush()
        return OcrResult(lines.toList(), page.pageWidth, page.pageHeight, id, version, majority(lines.map { it.direction }))
    }

    companion object {
        fun majority(d: List<TextDirection>): TextDirection {
            val r = d.count { it == TextDirection.RTL }
            val l = d.count { it == TextDirection.LTR }
            return when {
                r == 0 && l == 0 -> TextDirection.NEUTRAL
                l > r -> TextDirection.LTR
                else -> TextDirection.RTL
            }
        }
    }
}
