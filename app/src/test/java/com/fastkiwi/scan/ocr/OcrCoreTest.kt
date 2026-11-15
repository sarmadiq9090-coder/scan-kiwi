// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.ocr

import com.fastkiwi.scan.core.ImageFilters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/** The engine-neutral OCR API: models, classifier, cancellation, Tesseract line assembly, cache codec. */
class OcrCoreTest {

    @Test
    fun classifierDirectionAndScript() {
        assertEquals(TextDirection.RTL, TextClassifier.direction("فاتورة"))
        assertEquals(ScriptHint.ARABIC, TextClassifier.script("فاتورة"))
        assertEquals(TextDirection.LTR, TextClassifier.direction("Kiwi"))
        assertEquals(ScriptHint.LATIN, TextClassifier.script("Kiwi"))
        assertEquals(TextDirection.NEUTRAL, TextClassifier.direction("2026"))
        assertEquals(ScriptHint.DIGITS, TextClassifier.script("٢٠٢٦"))
        assertEquals(ScriptHint.MIXED, TextClassifier.script("رقم2026"))
    }

    @Test
    fun resultFlattensLinesInReadingOrder() {
        val a = RecognizedWord("فاتورة", OcrBox(700, 100, 900, 140), 0.9f)
        val b = RecognizedWord("2026", OcrBox(300, 100, 450, 140), 0.8f)
        val c = RecognizedWord("Total", OcrBox(100, 200, 300, 240), 0.7f)
        val r = OcrResult(listOf(RecognizedLine(listOf(a, b), a.box.union(b.box), 0.85f, TextDirection.RTL),
            RecognizedLine(listOf(c), c.box, 0.7f, TextDirection.LTR)), 1000, 1400, "x", 1)
        assertEquals("فاتورة 2026\nTotal", r.plainText())
        assertEquals(listOf("فاتورة", "2026", "Total"), r.toPdfWords().map { it.text })
        assertEquals(OcrBox(300, 100, 900, 140), r.lines[0].box)
        assertEquals(OcrBox(150, 50, 450, 70), OcrBox(300, 100, 900, 140).scaled(0.5f))
    }

    /** Tesseract reads the same bytes as in 1.2: the gray-plane normalisation equals normalizedGray(ARGB). */
    @Test
    fun illuminationMatchesImageFiltersByteForByte() {
        val rnd = Random(20261115)
        for ((w, h) in listOf(400 to 300, 1 to 1, 37 to 91, 640 to 480, 333 to 777)) {
            val px = IntArray(w * h) { i ->
                val x = i % w
                val paper = 235 - x * 120 / maxOf(1, w) // side shadow
                val v = if (rnd.nextInt(12) == 0) paper / 4 else (paper + rnd.nextInt(-6, 7)).coerceIn(0, 255)
                (0xFF shl 24) or (v shl 16) or ((v * 9 / 10) shl 8) or (v * 8 / 10)
            }
            val want = ImageFilters.normalizedGray(px, w, h).data
            val got = Illumination.normalize(ImageFilters.toGray(px, w, h).data, w, h)
            assertArrayEquals("$w x $h", want, got)
        }
    }

    @Test
    fun runStoppableCallsStopOnceFromAnotherThreadOnCancel() = runBlocking {
        val started = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        val stops = AtomicInteger()
        var stopThread: Thread? = null
        var blockThread: Thread? = null
        val job = async(Dispatchers.Default, start = CoroutineStart.DEFAULT) {
            runStoppable(stop = { stops.incrementAndGet(); stopThread = Thread.currentThread(); stopped.countDown() }) {
                blockThread = Thread.currentThread()
                started.countDown()
                stopped.await(10, TimeUnit.SECONDS)
                "late"
            }
        }
        assertTrue(started.await(10, TimeUnit.SECONDS))
        job.cancel()
        assertTrue("stop called", stopped.await(5, TimeUnit.SECONDS))
        val r = runCatching { job.await() }
        assertTrue("cancelled caller gets CancellationException", r.exceptionOrNull() is CancellationException)
        assertEquals(1, stops.get())
        assertTrue(stopThread !== blockThread)
        // A normal end never calls stop.
        val calls = AtomicInteger()
        assertEquals(42, runStoppable(stop = { calls.incrementAndGet() }) { 42 })
        assertEquals(0, calls.get())
    }

    @Test
    fun tesseractLinesAreConsecutiveRunsAndTheFilterIsOnTheRawConfidence() {
        val asm = TesseractLineAssembler()
        asm.add("فاتورة", 90f, OcrBox(700, 100, 900, 140), lineStart = true)
        asm.add("x", 34.99f, OcrBox(600, 100, 650, 140), lineStart = false) // dropped, as in 1.2
        asm.add("2026", 35f, OcrBox(300, 100, 450, 140), lineStart = false) // kept
        asm.add("  ", 99f, OcrBox(0, 0, 1, 1), lineStart = true)            // blank: dropped, but starts a line
        asm.add("Total", 80f, OcrBox(100, 200, 300, 240), lineStart = false)
        val r = asm.result(OcrInput(ByteArray(4), 2, 2, 1000, 1400), TesseractOcrEngine.ID, TesseractOcrEngine.VERSION)
        assertEquals(listOf("فاتورة", "2026", "Total"), r.words.map { it.text })
        assertEquals(listOf("فاتورة 2026", "Total"), r.lines.map { it.text })
        assertEquals(0.35f, r.words[1].confidence, 1e-6f)
        assertEquals(1000, r.frameWidth)
    }

    @Test
    fun codecReadsA12FileAsTesseractV1() {
        val v12 = """{"revision":3,"words":[["فاتورة",700,100,900,140],["2026",300,100,450,140]],"lang":"ara+eng","frame":[2400,3394]}"""
        val d = OcrResultCodec.decode(v12)!!
        assertEquals(OcrEngineKey.LEGACY, d.engine)
        assertEquals(3, d.revision)
        assertEquals("ara+eng", d.lang)
        assertEquals(2400, d.frameWidth)
        assertEquals(listOf("فاتورة", "2026"), d.words.map { it.text })
        assertEquals(listOf(0 until 2), d.lines)
        assertNull(d.confidences)
        assertNull(OcrResultCodec.decode("{not json"))
        // 1.1 caches: no lang, no frame
        assertEquals(0, OcrResultCodec.decode("""{"revision":1,"words":[]}""")!!.frameWidth)
    }

    @Test
    fun codecFormat2RoundTripsAndStaysReadableBy12() {
        val w1 = RecognizedWord("المبلغ", OcrBox(500, 10, 700, 50), 0.912f)
        val w2 = RecognizedWord("١٢٥٫٥٠", OcrBox(300, 10, 480, 50), 0.5f)
        val w3 = RecognizedWord("USD", OcrBox(100, 80, 200, 120), 1f)
        val r = OcrResult(listOf(RecognizedLine(listOf(w1, w2), w1.box.union(w2.box), 0.7f, TextDirection.RTL),
            RecognizedLine(listOf(w3), w3.box, 1f, TextDirection.LTR)), 800, 600, "pp-fused", 1)
        val json = OcrResultCodec.encode(7, r, "ara+eng", 2400)
        val d = OcrResultCodec.decode(json)!!
        assertEquals(OcrEngineKey("pp-fused", 1), d.engine)
        assertEquals(listOf(0 until 2, 2 until 3), d.lines)
        assertEquals(listOf(0.912f, 0.5f, 1f), d.confidences)
        assertEquals(r.toPdfWords().map { listOf(it.text, it.left, it.top, it.right, it.bottom) },
            d.words.map { listOf(it.text, it.left, it.top, it.right, it.bottom) })
        // What 1.2's DocumentStore.loadOcr/loadOcrFrame read: revision, lang, words, frame.
        val o = JSONObject(json)
        assertEquals(7, o.optInt("revision", -1))
        assertEquals("ara+eng", o.optString("lang"))
        assertEquals(3, o.getJSONArray("words").length())
        assertEquals(800, o.getJSONArray("frame").getInt(0))
        assertNotNull(o.getJSONArray("words").getJSONArray(1).getString(0))
    }
}
