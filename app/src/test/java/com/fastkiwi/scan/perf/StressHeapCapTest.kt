// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.perf

import com.fastkiwi.scan.core.PageSize
import com.fastkiwi.scan.data.DocumentStore
import com.fastkiwi.scan.export.ExportOptions
import com.fastkiwi.scan.export.Exporter
import com.fastkiwi.scan.export.Quality
import com.fastkiwi.scan.ocr.OcrEngineFactory
import com.fastkiwi.scan.ocr.OcrService
import kotlinx.coroutines.runBlocking
import org.apache.pdfbox.Loader
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
 * ROADMAP 1.7.0: no OutOfMemoryError in a 500-page synthetic stress test with a 512 MB heap cap.
 * Runs in its own JVM (Gradle task `perfStressTest`, `maxHeapSize = 512m`, run after every
 * `testDebugUnitTest`); the main unit-test JVM excludes it. 500 phone-size pages (2400 x 3200 sources,
 * every third black and white), PDF export with OCR (fake engine, so every page's gray plane, words
 * and text layer are made), then a JPEG export of all 500 pages.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class StressHeapCapTest {
    private val ctx = RuntimeEnvironment.getApplication()

    @Test
    fun fiveHundredPagesWithA512MbHeap() = runBlocking {
        val cap = Runtime.getRuntime().maxMemory()
        assertTrue("this test must run with a 512 MB heap cap (perfStressTest), max heap is ${cap / 1048576} MB", cap <= 540L * 1024 * 1024)
        val store = DocumentStore(File(ctx.filesDir, "stress-${System.nanoTime()}"))
        val doc = PerfSeed.doc(store, "stress 500", 500)
        val fake = PerfSeed.FakeOcr()
        val exporter = Exporter(ctx, store, OcrService(OcrEngineFactory.fixed(fake)))
        var maxUsed = 0L
        val t0 = System.nanoTime()
        val pdf = exporter.exportPdf(doc, ExportOptions(PageSize.A4, Quality.BALANCED, ocr = true)) { _, _ ->
            val rt = Runtime.getRuntime()
            maxUsed = maxOf(maxUsed, rt.totalMemory() - rt.freeMemory())
        }
        val pdfMs = (System.nanoTime() - t0) / 1_000_000
        val pdfBytes = pdf.length()
        assertEquals(500, fake.calls)
        val pages = Loader.loadPDF(pdf).use { it.numberOfPages }
        assertEquals(500, pages)
        val t1 = System.nanoTime()
        val jpegs = exporter.exportJpegs(doc, ExportOptions(quality = Quality.BALANCED)) { _, _ -> }
        val jpegMs = (System.nanoTime() - t1) / 1_000_000
        assertEquals(500, jpegs.size)
        val line = "500-page stress, heap cap ${cap / 1048576} MB: PDF+OCR ${pdfMs / 1000} s (${pdfBytes / 1048576} MB), " +
            "JPEG ${jpegMs / 1000} s; Java heap used (incl. garbage) at page starts ≤ ${maxUsed / 1048576} MB; no OutOfMemoryError"
        println("PERF-STRESS $line")
        File("build/perf-heap.txt").appendText(line + "\n")
        jpegs.forEach { it.delete() }
        pdf.delete()
        store.delete(doc.id)
    }
}
