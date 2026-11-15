// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.ocr.fused

import android.content.Context
import com.fastkiwi.scan.ocr.Illumination
import com.fastkiwi.scan.ocr.OcrEngine
import com.fastkiwi.scan.ocr.OcrInput
import com.fastkiwi.scan.ocr.OcrOptions
import com.fastkiwi.scan.ocr.OcrResult
import com.fastkiwi.scan.ocr.OcrUnavailable
import com.fastkiwi.scan.ocr.TesseractSession
import com.fastkiwi.scan.ocr.runStoppable
import com.googlecode.tesseract.android.TessBaseAPI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * «دقيق» Accurate mode: PP-OCRv6 tiny detector + PP-OCRv5 Arabic recogniser on MNN, Tesseract fast
 * re-reads each line, fusion rule, Arabic post-processor (roadmap §3 verdict, [FusedPipeline]).
 * Models load from the APK's assets on the first recognize() and stay until [close].
 */
class FusedOcrEngine(
    private val ctx: Context,
    private val threads: Int = DEFAULT_THREADS,
    /** Peak native heap probe for the diagnostics row (Debug.getNativeHeapAllocatedSize on the phone). */
    private val heapProbe: () -> Long = { android.os.Debug.getNativeHeapAllocatedSize() },
) : OcrEngine {
    override val id = ID
    override val version = VERSION
    private val mutex = Mutex()
    private var pipeline: FusedPipeline? = null
    private var detector: PpDetector? = null
    private var recognizer: PpArabicRecognizer? = null
    private var reader: TesseractLineReader? = null

    /** Highest native heap seen during the last recognize(), in bytes. */
    @Volatile var lastPeakNativeHeap: Long = 0L
        private set

    private fun asset(name: String): ByteArray = try {
        ctx.assets.open("ocr/$name").use { it.readBytes() }
    } catch (e: IOException) {
        throw OcrUnavailable(OcrUnavailable.Reason.MODEL_MISSING, e)
    }

    private fun ensure(): FusedPipeline {
        pipeline?.let { return it }
        try {
            val det = PpDetector(MnnModelRunner(asset(DET_MODEL), threads)).also { detector = it }
            val chars = PpArabicRecognizer.dictionary(String(asset(REC_DICT), Charsets.UTF_8))
            val rec = PpArabicRecognizer(MnnModelRunner(asset(REC_MODEL), threads), chars).also { recognizer = it }
            val tess = TesseractLineReader(TesseractSession(ctx)).also { reader = it }
            return FusedPipeline(det, rec, tess, normalize = Illumination::normalize, onStep = ::sample)
                .also { pipeline = it }
        } catch (e: Throwable) {
            close()
            throw e
        }
    }

    private fun sample() {
        val v = runCatching(heapProbe).getOrDefault(0L)
        if (v > lastPeakNativeHeap) lastPeakNativeHeap = v
    }

    override suspend fun recognize(page: OcrInput, options: OcrOptions): OcrResult = mutex.withLock {
        withContext(Dispatchers.Default) {
            lastPeakNativeHeap = 0L
            sample()
            val p = ensure()
            reader?.code = options.tesseractCode
            p.run(page, ID, VERSION)
        }
    }

    override fun close() {
        pipeline = null
        runCatching { detector?.close() }; detector = null
        runCatching { recognizer?.close() }; recognizer = null
        runCatching { reader?.close() }; reader = null
    }

    companion object {
        const val ID = "pp-fused"

        /** Bump on any change that alters output: models, thresholds, the fusion rule, pre/post-processing. */
        const val VERSION = 1
        const val DEFAULT_THREADS = 4
        const val DET_MODEL = "PP-OCRv6_det_tiny.mnn"
        const val REC_MODEL = "arabic_PP-OCRv5_rec_mobile.mnn"
        const val REC_DICT = "ppocrv5_arabic_dict.txt"
    }
}

/** Tesseract (PSM 7, single line) over rectangles of one normalised page. */
class TesseractLineReader(private val session: TesseractSession) : LineReader {
    @Volatile var code: String = "ara+eng"
    private var api: TessBaseAPI? = null

    override suspend fun begin(norm: ByteArray, w: Int, h: Int) {
        val a = session.ensure(code, TessBaseAPI.PageSegMode.PSM_SINGLE_LINE)
        a.setImage(norm, w, h, 1, w)
        api = a
    }

    override suspend fun read(left: Int, top: Int, right: Int, bottom: Int): String {
        val a = api ?: return ""
        return try {
            runStoppable(stop = session::stop) {
                a.setRectangle(left, top, right - left, bottom - top)
                a.getUTF8Text() ?: ""
            }
        } finally {
            if (session.wasStopped) { api = null; session.afterRead() }
        }
    }

    override fun end() {
        runCatching { api?.clear() }
        api = null
        session.afterRead()
    }

    override fun close() = session.close()
}
