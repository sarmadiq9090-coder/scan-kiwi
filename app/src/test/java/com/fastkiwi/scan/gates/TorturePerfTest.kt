// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.gates

import android.graphics.Bitmap
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.core.app.ActivityScenario
import com.fastkiwi.scan.MainActivity
import com.fastkiwi.scan.core.OcrWord
import com.fastkiwi.scan.core.PageSize
import com.fastkiwi.scan.data.DocumentStore
import com.fastkiwi.scan.export.ExportOptions
import com.fastkiwi.scan.export.Exporter
import com.fastkiwi.scan.export.Quality
import com.fastkiwi.scan.ocr.OcrLanguage
import com.fastkiwi.scan.ocr.PageOcr
import com.fastkiwi.scan.ui.Screen
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.atomic.AtomicInteger

/**
 * Performance of a 100-page document: open it, scroll the page grid, export Balanced
 * without OCR, with OCR (engine runs on every page) and again with the OCR cache, and the
 * share-size estimate. Numbers go to app/build/torture-perf.txt; only hangs (> 60 s) fail.
 */
@Category(TortureSuite::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "ar-w400dp-h860dp-xxhdpi")
class TorturePerfTest {
    @get:Rule
    val rule = createEmptyComposeRule()

    private val app get() = RuntimeEnvironment.getApplication()

    /** Stands in for Tesseract (no native library on the JVM): a few words per page, ~20 ms. */
    /** A fake «دقيق» engine behind the 2.0 `OcrEngine` seam: three words per page, 20 ms per page. */
    private class FakeOcr(val calls: AtomicInteger) : com.fastkiwi.scan.ocr.OcrEngine {
        override val id = "fake"
        override val version = 1
        override suspend fun recognize(page: com.fastkiwi.scan.ocr.OcrInput, options: com.fastkiwi.scan.ocr.OcrOptions): com.fastkiwi.scan.ocr.OcrResult {
            calls.incrementAndGet()
            Thread.sleep(20)
            fun w(t: String, l: Int, tp: Int, r: Int, b: Int) = com.fastkiwi.scan.ocr.RecognizedWord(t, com.fastkiwi.scan.ocr.OcrBox(l, tp, r, b), 1f)
            val ws = listOf(w("فاتورة", 100, 100, 400, 160), w("Invoice", 450, 100, 700, 160), w("1047", 100, 200, 260, 250))
            val box = ws.drop(1).fold(ws[0].box) { a, x -> a.union(x.box) }
            return com.fastkiwi.scan.ocr.OcrResult(listOf(com.fastkiwi.scan.ocr.RecognizedLine(ws, box, 1f, com.fastkiwi.scan.ocr.TextDirection.RTL)), page.pageWidth, page.pageHeight, id, version)
        }

        override fun close() = Unit
    }

    private fun ocrExporter(store: DocumentStore, engine: com.fastkiwi.scan.ocr.OcrEngine) =
        Exporter(app, store, com.fastkiwi.scan.ocr.OcrService(com.fastkiwi.scan.ocr.OcrEngineFactory.fixed(engine)))

    @Before
    fun setUp() {
        Gates.assumeTorture()
        Gates.wipe(DocumentStore(app.filesDir))
    }

    @After
    fun tearDown() {
        if (Gates.torture) Gates.wipe(DocumentStore(app.filesDir))
    }

    private fun label(s: String) = androidx.compose.ui.test.hasContentDescription(s, substring = true) or androidx.compose.ui.test.hasText(s, substring = true)

    private inline fun <T> timed(label: String, budgetMs: Long = 60_000, block: () -> T): T {
        val t0 = System.nanoTime()
        val r = block()
        val ms = Gates.ms(t0)
        Gates.perf("perf: $label: $ms ms")
        assertTrue("$label took $ms ms (hang?)", ms < budgetMs)
        return r
    }

    @Test(timeout = 1_200_000)
    fun hundredPageDocument() {
        val store = DocumentStore(app.filesDir)
        val doc = timed("seed 100 pages of 2400x3200") { Gates.seed(store, 100, name = "مستند طويل", w = 2400, h = 3200) }
        resetViewModelFactory()
        BlackBox.resetFileProviderCache()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            var vm: com.fastkiwi.scan.ui.AppViewModel? = null
            scenario.onActivity { vm = it.vm }
            rule.waitForIdle()
            // Open: from the tap to the first page tiles on screen.
            timed("open the 100-page document (first tiles shown)") {
                scenario.onActivity { it.vm.open(Screen.Editor(doc.id)) }
                rule.waitUntil(60_000) { rule.onAllNodes(label("الصفحة ١ من ١٠٠")).fetchSemanticsNodes().isNotEmpty() }
            }
            // Scroll the grid to the end in 30 swipes.
            val grid = SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)
            timed("scroll the page grid: 30 swipes") {
                repeat(30) {
                    rule.onAllNodes(grid)[0].performTouchInput { swipeUp(durationMillis = 200) }
                    rule.waitForIdle()
                }
            }
            val last = rule.onAllNodes(label("١٠٠ من ١٠٠")).fetchSemanticsNodes().isNotEmpty()
            Gates.perf("perf: after 30 swipes the last page is ${if (last) "" else "NOT "}on screen")
            val opt = ExportOptions(PageSize.A4, Quality.BALANCED, ocr = false)
            val est = timed("share size estimate, 100 pages Balanced", 10_000) { runBlocking { vm!!.estimateSize(doc.id, opt) } }
            // Exports (the same Exporter calls the app makes; the fake OCR engine through its test seam).
            val plain = timed("export 100 pages Balanced, no OCR", 300_000) {
                runBlocking { Exporter(app, store).exportPdf(doc, opt) { _, _ -> } }
            }
            val sizePlain = plain.length()
            val calls = AtomicInteger()
            val ocrOpt = opt.copy(ocr = true, ocrLanguage = OcrLanguage.ARABIC_ENGLISH)
            val withOcr = timed("export 100 pages Balanced, OCR on, no cache (fake engine)", 600_000) {
                runBlocking { ocrExporter(store, FakeOcr(calls)).exportPdf(doc, ocrOpt) { _, _ -> } }
            }
            assertEquals(100, calls.get())
            val sizeOcr = withOcr.length()
            val cached = AtomicInteger()
            val again = timed("export 100 pages Balanced, OCR on, OCR cache warm", 300_000) {
                runBlocking { ocrExporter(store, FakeOcr(cached)).exportPdf(doc, ocrOpt) { _, _ -> } }
            }
            assertEquals("OCR ran again although every page was cached", 0, cached.get())
            org.apache.pdfbox.Loader.loadPDF(again).use { assertEquals(100, it.numberOfPages) }
            Gates.perf("perf: sizes: estimate ${est!! / 1024} KB, no-OCR ${sizePlain / 1024} KB, OCR ${sizeOcr / 1024} KB, OCR-cached ${again.length() / 1024} KB")
        } finally {
            scenario.close()
        }
    }
}
