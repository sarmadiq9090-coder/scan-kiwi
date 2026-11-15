// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.ocr.fused

import com.fastkiwi.scan.ocr.OcrInput
import com.fastkiwi.scan.ocr.TextDirection
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Unit tests of the fused pipeline's pure parts (roadmap 1.5 test plan: grouper, fusion, post-processor). */
class FusedPartsTest {

    // ------------------------------------------------------------------ OcrFusion
    @Test
    fun tesseractWinsWhenItReadsMoreNumbers() {
        val f = OcrFusion()
        // PP-OCRv5 Arabic drops Western digits inside Arabic lines.
        assertEquals("رقم الملف 4821/77 والرقم المرجعي 559102", f.choose("رقم الملف والرقم المرجعي", "رقم الملف 4821/77 والرقم المرجعي 559102"))
        // Same number count: the Arabic recogniser keeps the line (it reads letters better).
        assertEquals("المبلغ ١٢٥٫٥٠ ريال", f.choose("المبلغ ١٢٥٫٥٠ ريال", "المبلع 125.50 ريال"))
        // Arabic-Indic digits count as numbers too.
        assertTrue(f.preferTesseract("سجل الفريق نقطة", "سجل الفريق ٤٥ نقطة"))
        assertFalse(f.preferTesseract("Total 12", ""))
    }

    @Test
    fun latinRuleIsOffAsTunedOnTheDevSet() {
        assertFalse(OcrFusion.LATIN_RULE)
        assertEquals("اسم المنتج موديل", OcrFusion().choose("اسم المنتج موديل", "اسم المنتج Smart Router موديل"))
        assertEquals("اسم المنتج Smart Router موديل", OcrFusion(latinRule = true).choose("اسم المنتج موديل", "اسم المنتج Smart Router موديل"))
    }

    @Test
    fun numberCountUsesSeparatorsInsideNumbers() {
        assertEquals(1, OcrFusion.countNumbers("2026/11/15"))
        assertEquals(1, OcrFusion.countNumbers("21,075.75"))
        assertEquals(2, OcrFusion.countNumbers("12 و 34"))
        assertEquals(1, OcrFusion.countNumbers("٢١٬٠٧٥٫٧٥"))
    }

    // ------------------------------------------------------------------ ArabicPostProcessor
    @Test
    fun arabicCommaBetweenDigitsBecomesDecimalSeparator() {
        assertEquals("١٢٥٫٥٠ ريال", ArabicPostProcessor.apply("١٢٥،٥٠ ريال"))
        assertEquals("12٫5", ArabicPostProcessor.apply("12،5"))
        // A real comma between words stays.
        assertEquals("أولا، ثانيا", ArabicPostProcessor.apply("أولا، ثانيا"))
        assertEquals("٣، ٤", ArabicPostProcessor.apply("٣، ٤"))
    }

    // ------------------------------------------------------------------ LineGrouper
    private fun cell(x0: Int, y0: Int, x1: Int, y1: Int, t: String) = LineGrouper.Cell(x0.toDouble(), y0.toDouble(), x1.toDouble(), y1.toDouble(), t, 0.9f)

    @Test
    fun tableRowsAreCellsRightToLeftOnArabicPages() {
        // A 2-row table, cells detected in arbitrary order.
        val cells = listOf(
            cell(100, 210, 200, 250, "١٥"), cell(700, 100, 900, 140, "البند"), cell(100, 100, 200, 140, "الكمية"),
            cell(700, 205, 900, 245, "رسوم شحن"), cell(400, 100, 600, 142, "السعر"), cell(400, 208, 600, 248, "٦٢٦٫٢٥"),
        )
        val rtl = LineGrouper.isRtlPage(cells)
        assertTrue(rtl)
        val rows = LineGrouper.group(cells, rtl)
        assertEquals(listOf("البند السعر الكمية", "رسوم شحن ٦٢٦٫٢٥ ١٥"), rows.map { it.text })
        val lines = LineGrouper.toLines(rows, rtl, 1f)
        assertEquals(TextDirection.RTL, lines[0].direction)
        // Reading order: the first word is the rightmost.
        assertTrue(lines[1].words.first().box.left > lines[1].words.last().box.left)
    }

    @Test
    fun englishPageReadsLeftToRight() {
        val cells = listOf(cell(500, 100, 800, 140, "Branch 7"), cell(100, 100, 400, 140, "Reference: PO-0042"))
        val rtl = LineGrouper.isRtlPage(cells)
        assertFalse(rtl)
        assertEquals(listOf("Reference: PO-0042 Branch 7"), LineGrouper.group(cells, rtl).map { it.text })
    }

    @Test
    fun wordBoxesSplitTheCellByCharacters() {
        val rows = LineGrouper.group(listOf(cell(0, 0, 110, 20, "abcde fghij")), rtl = false)
        val w = LineGrouper.toLines(rows, rtl = false, scale = 2f)[0].words
        assertEquals(listOf("abcde", "fghij"), w.map { it.text })
        assertEquals(0, w[0].box.left); assertEquals(100, w[0].box.right) // 5/11 of 110 px, x2
        assertEquals(120, w[1].box.left); assertEquals(220, w[1].box.right)
        assertEquals(40, w[0].box.bottom)
    }

    // ------------------------------------------------------------------ Bidi
    @Test
    fun visualArabicBecomesLogical() {
        // The recogniser emits Arabic left to right, i.e. reversed.
        assertEquals("مرحبا بكم", Bidi.visualToLogical("مكب ابحرم"))
        // Numbers keep their digit order inside Arabic text.
        assertEquals("رقم 2026", Bidi.visualToLogical("2026 مقر"))
        // Pure Latin is untouched.
        assertEquals("Total: 15 USD", Bidi.visualToLogical("Total: 15 USD"))
        assertEquals("", Bidi.visualToLogical(""))
    }

    // ------------------------------------------------------------------ recogniser helpers
    @Test
    fun recogniserInputIsPaddedOnTheRight() {
        val (resized, imgW) = PpArabicRecognizer.width(TextBox(0.0, 0.0, 480.0, 48.0, 0.0, 1.0))
        assertEquals(480, resized)
        assertEquals(600, imgW) // + 25 %
        val small = PpArabicRecognizer.width(TextBox(0.0, 0.0, 30.0, 40.0, 0.0, 1.0))
        assertEquals(36, small.first)
        assertEquals(320, small.second) // the model's minimum width
    }

    @Test
    fun ctcDropsRepeatsAndBlanks() {
        val chars = PpArabicRecognizer.dictionary("a\nb\n")
        assertEquals(listOf("<blank>", "a", "b", " "), chars)
        val (text, score) = PpArabicRecognizer.ctcGreedy(intArrayOf(1, 1, 0, 1, 2, 3, 2), floatArrayOf(.9f, .8f, .5f, .7f, .6f, .5f, .4f), chars)
        assertEquals("aab b", text)
        assertEquals((.9 + .7 + .6 + .5 + .4) / 5, score, 1e-6)
    }

    // ------------------------------------------------------------------ detector
    @Test
    fun detectorInputIsCappedAt960() {
        assertEquals(672 to 960, PpDetector.size(1700, 2400)) // long side 960, both multiples of 32
        assertEquals(960 to 736, PpDetector.size(992, 744))
        assertEquals(640 to 480, PpDetector.size(640, 480)) // never upscaled
        assertEquals(32 to 32, PpDetector.size(10, 5))
    }

    @Test
    fun dbFindsTiltedLinesAndScalesToThePage() {
        // Two text bands in a 200 x 100 map; the page is 4x larger.
        val rw = 200; val rh = 100
        val map = FloatArray(rw * rh)
        for (x in 20 until 180) for (dy in 0 until 8) {
            map[(20 + x / 40 + dy) * rw + x] = 0.9f // slightly tilted line
            map[(60 + dy) * rw + x] = 0.8f
        }
        val boxes = PpDetector.boxes(map, rw, rh, 800, 400)
        assertEquals(2, boxes.size)
        val b = boxes[0]
        assertTrue("tilt ${Math.toDegrees(b.angle)}", Math.toDegrees(b.angle) in 1.0..2.5)
        assertTrue("centre ${b.cx}", abs(b.cx - 400) < 8)
        assertTrue("unclipped width ${b.w}", b.w > 640 && b.w < 720)
        // An empty map has no boxes; faint noise below the threshold neither.
        assertTrue(PpDetector.boxes(FloatArray(rw * rh) { 0.2f }, rw, rh, 800, 400).isEmpty())
    }

    @Test
    fun pipelineDropsLowScoreLinesAndKeepsReadingOrder() = runBlocking {
        val w = 64; val h = 64
        val det = object : ModelRunner {
            override fun run(input: FloatArray, shape: IntArray): ModelOutput {
                val rw = shape[3]; val rh = shape[2]
                val m = FloatArray(rw * rh)
                for (x in 4 until 28) for (y in 4 until 10) m[y * rw + x] = 0.9f // left box
                for (x in 36 until 60) for (y in 4 until 10) m[y * rw + x] = 0.9f
                for (x in 4 until 60) for (y in 30 until 36) m[y * rw + x] = 0.9f
                return ModelOutput(m, intArrayOf(1, 1, rh, rw))
            }
        }
        val texts = ArrayDeque(listOf("ب" to 0.9f, "أ" to 0.9f, "ضجيج" to 0.1f))
        val chars = listOf("<blank>", "أ", "ب", "ضجيج", " ")
        val rec = object : ModelRunner {
            override fun run(input: FloatArray, shape: IntArray): ModelOutput {
                val (t, p) = texts.removeFirst()
                val out = FloatArray(chars.size)
                out[chars.indexOf(t)] = p
                return ModelOutput(out, intArrayOf(1, 1, chars.size))
            }
        }
        val pipeline = FusedPipeline(PpDetector(det), PpArabicRecognizer(rec, chars), null, normalize = { g, _, _ -> g })
        val r = pipeline.run(OcrInput(ByteArray(w * h) { -1 }, w, h, 128, 128), "pp-fused", 1)
        // Boxes are found in raster order (left box first); the row reads right to left; the low-score line is gone.
        assertEquals(listOf("أ ب"), r.lines.map { it.text })
        assertEquals(128, r.frameWidth)
        assertTrue(r.words.all { it.box.right <= 128 })
    }
}
