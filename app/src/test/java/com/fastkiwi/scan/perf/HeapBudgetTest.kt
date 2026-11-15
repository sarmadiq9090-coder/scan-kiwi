// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.perf

import android.graphics.Bitmap
import android.graphics.Color
import com.fastkiwi.scan.core.PageSize
import com.fastkiwi.scan.data.DocumentStore
import com.fastkiwi.scan.export.ExportOptions
import com.fastkiwi.scan.export.Exporter
import com.fastkiwi.scan.export.Quality
import com.fastkiwi.scan.imaging.BitmapIO
import com.fastkiwi.scan.ocr.OcrEngineFactory
import com.fastkiwi.scan.ocr.OcrService
import kotlinx.coroutines.runBlocking
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * ROADMAP 1.7.0 heap acceptance, on phone-size pages (2400 x 3200 sources, every third page black and
 * white). Measured with [MemoryMeter]: native peak (bitmaps) + an upper bound of the live Java heap, as
 * deltas over the running app. Numbers go to `app/build/perf-heap.txt` and docs/features-2.0/perf.md.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class HeapBudgetTest {
    private val ctx = RuntimeEnvironment.getApplication()

    @After
    fun normalMode() = MemoryBudget.setForTest(false)

    private fun store(tag: String) = DocumentStore(File(ctx.filesDir, "heap-$tag-${System.nanoTime()}"))

    private fun record(line: String) {
        println("PERF-HEAP $line")
        File("build/perf-heap.txt").appendText(line + "\n")
    }

    /** The meter must see a 30 MB native bitmap, or every budget below would pass for nothing. */
    @Test
    fun meterSeesANativeBitmap() {
        val m = MemoryMeter(sampleMs = 2)
        m.start()
        val b = Bitmap.createBitmap(2400, 3200, Bitmap.Config.ARGB_8888)
        b.eraseColor(Color.GRAY)
        Thread.sleep(100)
        val r = m.stop()
        b.recycle()
        record("calibration: one 2400x3200 bitmap (29.3 MB) -> native peak ${MemoryMeter.mb(r.nativePeak)} MB")
        assertTrue("meter saw ${r.nativePeak} bytes for a 30.7 MB bitmap", r.nativePeak >= 25L * 1024 * 1024)
    }

    @Test
    fun twentyPagesWithOcrStayUnder256MB() = runBlocking {
        val st = store("20ocr")
        val doc = PerfSeed.doc(st, "heap 20 ocr", 20)
        val fake = PerfSeed.FakeOcr()
        val exporter = Exporter(ctx, st, OcrService(OcrEngineFactory.fixed(fake)))
        val m = MemoryMeter()
        m.start()
        val t0 = System.nanoTime()
        val pdf = exporter.exportPdf(doc, ExportOptions(PageSize.A4, Quality.HIGH, ocr = true)) { _, _ -> m.checkpoint() }
        val ms = (System.nanoTime() - t0) / 1_000_000
        val r = m.stop()
        record("20 pages, OCR (fake engine), HIGH: $r; Debug.getNativeHeapAllocatedSize peak (Java heap under Robolectric) ${MemoryMeter.mb(r.debugNativeHeapPeak)} MB; ${ms} ms incl. GC checkpoints; ${pdf.length() / 1024} KB")
        assertEquals("every page read", 20, fake.calls)
        assertEquals(20, Loader.loadPDF(pdf).use { it.numberOfPages })
        assertTrue("the meter sees the page bitmaps (not a vacuous pass): ${MemoryMeter.mb(r.nativePeak)} MB", r.nativePeak >= 25L * 1024 * 1024)
        assertTrue("20-page OCR export used ${MemoryMeter.mb(r.total)} MB > 256 MB", r.total <= MemoryBudget.BUDGET_20_PAGES_OCR)
    }

    @Test
    fun twoHundredPagesWithoutOcrStayUnder128MB() = runBlocking {
        val st = store("200")
        val doc = PerfSeed.doc(st, "heap 200", 200)
        val exporter = Exporter(ctx, st)
        val m = MemoryMeter()
        m.start()
        val t0 = System.nanoTime()
        val pdf = exporter.exportPdf(doc, ExportOptions(PageSize.A4, Quality.BALANCED)) { _, _ -> m.checkpoint() }
        val ms = (System.nanoTime() - t0) / 1_000_000
        val r = m.stop()
        record("200 pages, no OCR, BALANCED: $r; ${ms} ms incl. GC checkpoints; ${pdf.length() / 1024} KB")
        assertEquals(200, Loader.loadPDF(pdf).use { it.numberOfPages })
        assertTrue("the meter sees the page bitmaps (not a vacuous pass): ${MemoryMeter.mb(r.nativePeak)} MB", r.nativePeak >= 25L * 1024 * 1024)
        assertTrue("Java heap grows with pages: ${MemoryMeter.mb(r.javaRetainedAfter)} MB retained", r.javaRetainedAfter < 16L * 1024 * 1024)
        assertTrue("200-page export used ${MemoryMeter.mb(r.total)} MB > 128 MB", r.total <= MemoryBudget.BUDGET_200_PAGES)
    }

    /** Low-memory mode: the OCR plane and every exported page are at most 2000 px, and it costs less. */
    @Test
    fun lowMemoryModeReadsAndExportsAt2000px() = runBlocking {
        val results = HashMap<Boolean, MemoryMeter.Result>()
        for (low in listOf(false, true)) {
            MemoryBudget.setForTest(low)
            val st = store("low$low")
            val doc = PerfSeed.doc(st, "low $low", 6)
            val fake = PerfSeed.FakeOcr()
            val exporter = Exporter(ctx, st, OcrService(OcrEngineFactory.fixed(fake)))
            val m = MemoryMeter()
            m.start()
            val res = exporter.exportPdfResult(doc, ExportOptions(PageSize.A4, Quality.HIGH, ocr = true)) { _, _ -> m.checkpoint() }
            val r = m.stop()
            results[low] = r
            val maxImage = Loader.loadPDF(res.file).use { pd ->
                pd.pages.maxOf { p ->
                    p.resources.xObjectNames.mapNotNull { p.resources.getXObject(it) as? PDImageXObject }.maxOf { maxOf(it.width, it.height) }
                }
            }
            record("6 pages HIGH + OCR, low-memory=$low: plane ${fake.maxPlaneSide} px, largest PDF image $maxImage px, settings ${res.settings}; $r")
            if (low) {
                assertEquals("OCR plane in low-memory mode", MemoryBudget.LOW_MEMORY_LONG_SIDE, fake.maxPlaneSide)
                assertTrue("export pages ≤ 2000 px, got $maxImage", maxImage <= MemoryBudget.LOW_MEMORY_LONG_SIDE)
                assertEquals(MemoryBudget.LOW_MEMORY_LONG_SIDE, res.settings.longSide)
            } else {
                assertEquals("OCR plane in normal mode", 2400, fake.maxPlaneSide)
                assertEquals(3200, maxImage)
            }
        }
        assertTrue("low-memory mode uses less: ${results[true]} vs ${results[false]}", results.getValue(true).total < results.getValue(false).total)
    }

    /** JPEG export decodes at the export size, not the 3200 px source size. */
    @Test
    fun jpegExportDecodesOnlyWhatItNeeds() = runBlocking {
        val st = store("jpeg")
        val doc = PerfSeed.doc(st, "jpeg", 3)
        val m = MemoryMeter(sampleMs = 2)
        m.start()
        val files = Exporter(ctx, st).exportJpegs(doc, ExportOptions(quality = Quality.SMALL)) { _, _ -> m.checkpoint() }
        val r = m.stop()
        record("JPEG export 3 pages SMALL (1700 px): $r")
        assertEquals(3, files.size)
        files.forEach { f ->
            val (w, h) = BitmapIO.imageSize(f)!!
            assertEquals(1700, maxOf(w, h))
        }
    }
}
