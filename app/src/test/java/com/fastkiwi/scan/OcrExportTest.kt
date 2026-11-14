// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.fastkiwi.scan.core.Filter
import com.fastkiwi.scan.core.OcrWord
import com.fastkiwi.scan.core.PageSize
import com.fastkiwi.scan.data.DocumentStore
import com.fastkiwi.scan.data.ScanDocument
import com.fastkiwi.scan.data.ScanPage
import com.fastkiwi.scan.export.ExportOptions
import com.fastkiwi.scan.export.Exporter
import com.fastkiwi.scan.export.Quality
import com.fastkiwi.scan.imaging.BitmapIO
import com.fastkiwi.scan.ocr.OcrLanguage
import com.fastkiwi.scan.ocr.PageOcr
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.text.TextPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The OCR text layer of an exported PDF (1.2.0 lead-session findings).
 *
 * 1. Cached OCR words are reused by later exports. They must land on the same spot of the page at every
 *    quality, whatever quality the first OCR ran at. (Before the fix, the cache kept boxes in the frame OCR
 *    read, at least 2400 px, and a reuse drew them on the export-size image: up to 1.41x off, with words
 *    in the right and bottom third outside the page.)
 * 2. Cancelling an export interrupts the page Tesseract is reading. (Before the fix, `stop()` was never
 *    called, so Cancel waited for the page to finish.)
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class OcrExportTest {

    private val ctx = RuntimeEnvironment.getApplication()

    /** Word name -> (left, top, right, bottom) as fractions of the image the engine reads. */
    private val words = mapOf(
        "LEFTTOP" to floatArrayOf(0.08f, 0.08f, 0.30f, 0.12f),
        "RIGHTEDGE" to floatArrayOf(0.70f, 0.86f, 0.95f, 0.90f),
        "CENTRE" to floatArrayOf(0.40f, 0.48f, 0.60f, 0.52f),
    )

    /** Like a real engine: boxes in the pixel coordinates of the bitmap it was given. */
    private inner class FractionOcr : PageOcr {
        var calls = 0
        override fun recognize(page: Bitmap, lang: OcrLanguage): List<OcrWord> {
            calls++
            return words.map { (t, f) ->
                OcrWord(t, (f[0] * page.width).roundToInt(), (f[1] * page.height).roundToInt(),
                    (f[2] * page.width).roundToInt(), (f[3] * page.height).roundToInt())
            }
        }
        override fun stop() {}
        override fun close() {}
    }

    private var storeCount = 0

    /** A document with a colour page and a black-and-white page, 2400 x 3200 (phone size). */
    private fun seed(): Pair<DocumentStore, ScanDocument> {
        val store = DocumentStore(File(ctx.filesDir, "ocr-export-${storeCount++}-${System.nanoTime()}"))
        var doc = store.create("ocr layer")
        val bmp = Bitmap.createBitmap(2400, 3200, Bitmap.Config.ARGB_8888)
        Canvas(bmp).apply {
            drawColor(Color.WHITE)
            val ink = Paint().apply { color = Color.rgb(30, 30, 40); strokeWidth = 9f }
            for (i in 0 until 50) drawLine(200f, 200f + i * 55f, 2200f, 200f + i * 55f, ink)
        }
        val pages = listOf(Filter.ENHANCED, Filter.BW).map { filter ->
            val id = DocumentStore.newId()
            BitmapIO.saveJpeg(bmp, store.srcFile(doc.id, id), 92)
            BitmapIO.saveJpeg(bmp, store.outFile(doc.id, id), 92)
            BitmapIO.saveJpeg(BitmapIO.fit(bmp.copy(Bitmap.Config.ARGB_8888, false), 360), store.thumbFile(doc.id, id), 82) // fit() recycles its input
            ScanPage(id, BitmapIO.FULL, 0, filter)
        }
        doc = store.save(doc.copy(pages = pages))
        return store to doc
    }

    private class Found(val text: String, val x: Float, val baseline: Float, val right: Float)

    /** Every shown word with its left edge, baseline (from the top) and right edge, in points. */
    private fun textPositions(pdf: File): Pair<List<List<Found>>, List<Pair<Float, Float>>> {
        Loader.loadPDF(pdf).use { doc ->
            val sizes = doc.pages.map { it.mediaBox.width to it.mediaBox.height }
            val perPage = (1..doc.numberOfPages).map { n ->
                val out = ArrayList<Found>()
                val stripper = object : PDFTextStripper() {
                    override fun writeString(text: String, textPositions: MutableList<TextPosition>) {
                        if (textPositions.isEmpty()) return
                        val first = textPositions.first()
                        val last = textPositions.last()
                        out.add(Found(text.trim(), first.xDirAdj, first.yDirAdj, last.xDirAdj + last.widthDirAdj))
                    }
                }
                stripper.startPage = n
                stripper.endPage = n
                stripper.getText(doc)
                out
            }
            return perPage to sizes
        }
    }

    /** Each word sits where the engine saw it on the page image, and inside the page. */
    private fun assertWordsInPlace(pdf: File, tag: String) {
        val (pages, sizes) = textPositions(pdf)
        assertEquals("$tag: pages", 2, pages.size)
        pages.forEachIndexed { i, found ->
            val (pw, ph) = sizes[i]
            // A4 portrait; a 3:4 image is width-limited and centred vertically.
            val dw = pw
            val dh = pw * 3200f / 2400f
            val oy = (ph - dh) / 2f
            for ((t, f) in words) {
                val w = found.firstOrNull { it.text.contains(t) }
                assertTrue("$tag page ${i + 1}: $t is in the text layer (${found.map { it.text }})", w != null)
                w!!
                val wantX = f[0] * dw
                val wantBase = oy + f[3] * dh
                assertTrue("$tag page ${i + 1}: $t left ${w.x} pt, expected $wantX pt", abs(w.x - wantX) <= 0.01f * pw)
                assertTrue("$tag page ${i + 1}: $t baseline ${w.baseline} pt, expected $wantBase pt", abs(w.baseline - wantBase) <= 0.01f * ph)
                assertTrue("$tag page ${i + 1}: $t inside the page (right ${w.right} pt of $pw)", w.x >= 0f && w.right <= pw + 0.5f && w.baseline <= ph)
            }
        }
    }

    @Test
    fun cachedOcrLandsOnTheWordAtEveryQuality() = runBlocking {
        for (first in Quality.entries) {
            val (store, doc) = seed()
            val ocr = FractionOcr()
            val opt = ExportOptions(PageSize.A4, first, ocr = true)
            val fresh = Exporter(ctx, store) { ocr }.exportPdf(doc, opt) { _, _ -> }
            assertEquals("fresh OCR at $first reads both pages", 2, ocr.calls)
            assertWordsInPlace(fresh, "fresh $first")
            for (again in Quality.entries) {
                val reuse = FractionOcr()
                // A new exporter, as after reopening the app: no same-session file reuse.
                val pdf = Exporter(ctx, store) { reuse }.exportPdf(doc, opt.copy(quality = again)) { _, _ -> }
                assertEquals("cache from $first reused at $again (no new OCR)", 0, reuse.calls)
                assertWordsInPlace(pdf, "OCR at $first, reused at $again")
            }
        }
    }

    @Test
    fun legacyCacheWithoutFrameIsReadAgainForExport() = runBlocking {
        val (store, doc) = seed()
        // A 1.1.x / early 1.2.0 cache: words but no frame size, so their coordinate frame is unknown.
        for (p in doc.pages) store.saveOcr(doc.id, p, listOf(OcrWord("OLD", 2200, 3000, 2390, 3100)), OcrLanguage.ARABIC_ENGLISH.code)
        val ocr = FractionOcr()
        val pdf = Exporter(ctx, store) { ocr }.exportPdf(doc, ExportOptions(PageSize.A4, Quality.SMALL, ocr = true)) { _, _ -> }
        assertEquals("legacy cache is not trusted for placement: OCR runs again", 2, ocr.calls)
        assertWordsInPlace(pdf, "legacy cache")
        // Search still uses whatever text is cached.
        assertTrue(store.documentText(doc).contains("RIGHTEDGE"))
    }

    /** Blocks like a long Tesseract page until stop() is called. */
    private class BlockingOcr : PageOcr {
        val entered = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        override fun recognize(page: Bitmap, lang: OcrLanguage): List<OcrWord> {
            entered.countDown()
            stopped.await(20, TimeUnit.SECONDS) // a real page returns early once stop() is called
            return listOf(OcrWord("PARTIAL", 10, 10, 100, 40))
        }
        override fun stop() = stopped.countDown()
        override fun close() {}
    }

    @Test
    fun cancelInterruptsThePageBeingRead() = runBlocking {
        val (store, doc) = seed()
        val ocr = BlockingOcr()
        val exporter = Exporter(ctx, store) { ocr }
        val job = async(Dispatchers.Default, start = CoroutineStart.DEFAULT) {
            exporter.exportPdf(doc, ExportOptions(PageSize.A4, Quality.BALANCED, ocr = true)) { _, _ -> }
        }
        assertTrue("OCR started", ocr.entered.await(20, TimeUnit.SECONDS))
        val t0 = System.nanoTime()
        job.cancel()
        assertTrue("Cancel calls stop() on the engine", ocr.stopped.await(2, TimeUnit.SECONDS))
        withTimeout(5_000) { runCatching { job.await() } }
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("export ends soon after Cancel ($ms ms)", ms < 5_000)
        assertTrue("cancelled", job.isCancelled)
        // A stopped page is not cached, and no PDF is left behind.
        assertTrue("no OCR cached for a stopped page", doc.pages.all { store.loadOcr(doc.id, it) == null })
        val leftovers = File(ctx.cacheDir, "exports").listFiles()?.filter { it.name.startsWith("ocr layer") } ?: emptyList()
        assertFalse("no partial PDF left (${leftovers.map { it.name }})", leftovers.isNotEmpty())
    }
}
