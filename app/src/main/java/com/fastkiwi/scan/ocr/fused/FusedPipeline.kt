// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.ocr.fused

import com.fastkiwi.scan.ocr.OcrInput
import com.fastkiwi.scan.ocr.OcrResult
import com.fastkiwi.scan.ocr.TextDirection
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.max
import kotlin.math.min

/** Re-reads one line rectangle of the page with Tesseract (PSM single line). */
interface LineReader : AutoCloseable {
    /** Sets the page for the following [read]s: the illumination-normalised plane. */
    suspend fun begin(norm: ByteArray, w: Int, h: Int)

    /** Text of the rectangle (plane pixels, right/bottom exclusive). Cancellable. */
    suspend fun read(left: Int, top: Int, right: Int, bottom: Int): String

    fun end() {}
    override fun close() {}
}

/**
 * The fused «دقيق» pipeline, engine-neutral and free of Android so the JVM golden test runs it end to end
 * on recorded model outputs: detect → read each line (PP-OCR Arabic) → Tesseract re-read → fusion rule
 * → Arabic post-processor → rows in reading order.
 */
class FusedPipeline(
    private val detector: PpDetector,
    private val recognizer: PpArabicRecognizer,
    private val tesseract: LineReader?,
    private val fusion: OcrFusion = OcrFusion(),
    private val normalize: (ByteArray, Int, Int) -> ByteArray,
    /** Called after each model run, for the peak-native-heap diagnostics. */
    private val onStep: () -> Unit = {},
) {
    suspend fun run(page: OcrInput, engineId: String, engineVersion: Int): OcrResult {
        val w = page.width; val h = page.height
        val boxes = detector.detect(page.gray, w, h)
        onStep()
        val cells = ArrayList<LineGrouper.Cell>()
        var began = false
        try {
            for (b in boxes) {
                currentCoroutineContext().ensureActive()
                val read = recognizer.read(page.gray, w, h, b)
                onStep()
                if (read.text.isBlank() || read.score < TEXT_SCORE) continue
                var pick = read.text
                if (tesseract != null) {
                    if (!began) { tesseract.begin(normalize(page.gray, w, h), w, h); began = true }
                    val bb = b.bounds(w, h)
                    val pad = (TESS_PAD * (bb[3] - bb[1])).toInt()
                    val x0 = max(0, bb[0].toInt() - pad); val y0 = max(0, bb[1].toInt() - pad)
                    val x1 = min(w, bb[2].toInt() + pad); val y1 = min(h, bb[3].toInt() + pad)
                    if (x1 > x0 && y1 > y0) pick = fusion.choose(pick, tesseract.read(x0, y0, x1, y1).trim())
                }
                pick = ArabicPostProcessor.apply(pick)
                val bb = b.bounds(w, h)
                cells.add(LineGrouper.Cell(bb[0], bb[1], bb[2], bb[3], pick, read.score.toFloat()))
            }
        } finally {
            if (began) tesseract?.end()
        }
        val rtl = LineGrouper.isRtlPage(cells)
        val rows = LineGrouper.group(cells, rtl)
        val lines = LineGrouper.toLines(rows, rtl, page.scaleToPage)
        return OcrResult(lines, page.pageWidth, page.pageHeight, engineId, engineVersion, if (rtl) TextDirection.RTL else TextDirection.LTR)
    }

    companion object {
        /** Lines the recogniser reads with a lower mean probability are dropped (as in the benchmark). */
        const val TEXT_SCORE = 0.3

        /** Margin around a line box for Tesseract, as a share of the box height. */
        const val TESS_PAD = 0.25
    }
}
