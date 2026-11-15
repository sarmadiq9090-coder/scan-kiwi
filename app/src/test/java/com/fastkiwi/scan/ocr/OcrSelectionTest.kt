// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.ocr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.fastkiwi.scan.core.Filter
import com.fastkiwi.scan.core.PageSize
import com.fastkiwi.scan.data.DocumentStore
import com.fastkiwi.scan.data.ScanDocument
import com.fastkiwi.scan.data.ScanPage
import com.fastkiwi.scan.export.ExportOptions
import com.fastkiwi.scan.export.Exporter
import com.fastkiwi.scan.export.Quality
import com.fastkiwi.scan.imaging.BitmapIO
import com.fastkiwi.scan.ocr.fused.MnnNative
import kotlinx.coroutines.runBlocking
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Accurate/Fast selection, fallbacks, the shared service and the background reader (roadmap 1.5 items 2, 3). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class OcrSelectionTest {
    private val ctx = RuntimeEnvironment.getApplication()
    private val arEn = listOf(OcrLang.ARABIC, OcrLang.ENGLISH)

    /** A stand-in engine: one word per page, in the page frame. */
    private class FakeEngine(override val id: String, private val fail: OcrUnavailable? = null) : OcrEngine {
        override val version = 1
        var calls = 0
        var closed = 0
        override suspend fun recognize(page: OcrInput, options: OcrOptions): OcrResult {
            calls++
            fail?.let { throw it }
            val w = RecognizedWord("WORD-$id", OcrBox(10, 10, page.pageWidth / 2, 60), 0.9f)
            return OcrResult(listOf(RecognizedLine(listOf(w), w.box, 0.9f, TextDirection.LTR)), page.pageWidth, page.pageHeight, id, version)
        }
        override fun close() { closed++ }
    }

    private fun factory(lowRam: Boolean = false, mnn: () -> Boolean = { true }, fast: OcrEngine = FakeEngine("tesseract"), accurate: OcrEngine = FakeEngine("pp-fused")) =
        DefaultOcrEngineFactory(ctx, device = { DeviceProfile(lowRam) }, mnnAvailable = mnn, fast = { fast }, accurate = { accurate })

    @Test
    fun accurateIsTheDefaultWhenEverythingIsThere() {
        val s = factory().create(OcrMode.ACCURATE, arEn)
        assertEquals(OcrMode.ACCURATE, s.effective)
        assertNull(s.fallback)
        assertEquals("pp-fused", s.engine.id)
    }

    @Test
    fun fastFallbacks() {
        assertEquals(FallbackReason.LOW_RAM_DEVICE, factory(lowRam = true).create(OcrMode.ACCURATE, arEn).fallback)
        assertEquals(FallbackReason.RUNTIME_MISSING, factory(mnn = { false }).create(OcrMode.ACCURATE, arEn).fallback)
        assertEquals(FallbackReason.ENGLISH_ONLY, factory().create(OcrMode.ACCURATE, listOf(OcrLang.ENGLISH)).fallback)
        val fast = factory().create(OcrMode.FAST, arEn)
        assertEquals(OcrMode.FAST, fast.effective); assertNull(fast.fallback)
        val acc = factory().create(OcrMode.ACCURATE, arEn)
        val next = factory().fallbackAfter(acc, OcrUnavailable(OcrUnavailable.Reason.MODEL_MISSING))!!
        assertEquals(OcrMode.FAST, next.effective); assertEquals(FallbackReason.MODEL_MISSING, next.fallback)
        assertNull(factory().fallbackAfter(fast, OcrUnavailable(OcrUnavailable.Reason.INIT_FAILED)))
    }

    @Test
    fun lowRamIsUnder3GbOrTheSystemFlag() {
        assertEquals(3L * 1024 * 1024 * 1024, DeviceProfile.LOW_RAM_BYTES)
        assertNotNull(DeviceProfile.of(ctx)) // never throws
    }

    /** The MNN library is missing here (JVM): the real probe says so and OCR still works in Fast mode. */
    @Test
    fun ocrStillWorksInFastModeWhenTheMnnLibraryIsMissing() = runBlocking {
        assertFalse("no libkiwi_mnn.so on the JVM", MnnNative.tryLoad())
        val tess = FakeEngine("tesseract")
        val f = DefaultOcrEngineFactory(ctx, device = { DeviceProfile(false) }, fast = { tess }) // real MNN probe
        val sel = f.create(OcrMode.ACCURATE, arEn)
        assertEquals(OcrMode.FAST, sel.effective)
        assertEquals(FallbackReason.RUNTIME_MISSING, sel.fallback)
        val (store, doc) = seed()
        var unavailable = false
        val pdf = Exporter(ctx, store, OcrService(f)) { OcrMode.ACCURATE }
            .exportPdf(doc, ExportOptions(PageSize.A4, Quality.SMALL, ocr = true), onOcrUnavailable = { unavailable = true }) { _, _ -> }
        assertFalse(unavailable)
        assertEquals(1, tess.calls)
        assertTrue(text(pdf).contains("WORD-tesseract"))
        assertEquals(OcrEngineKey("tesseract", 1), OcrResultCodec.decode(File(store.dir(doc.id), "p_${doc.pages[0].id}_ocr.json").readText())!!.engine)
    }

    @Test
    fun accurateFailingAtFirstUseFallsBackToFastOnTheSamePage() = runBlocking {
        val tess = FakeEngine("tesseract")
        val broken = FakeEngine("pp-fused", OcrUnavailable(OcrUnavailable.Reason.INIT_FAILED))
        val seen = ArrayList<OcrSelection>()
        val service = OcrService(factory(fast = tess, accurate = broken), onPage = { s, _, _ -> seen.add(s) })
        val r = service.recognize(OcrInput(ByteArray(100), 10, 10), OcrOptions(arEn, OcrMode.ACCURATE))
        assertEquals("tesseract", r.engineId)
        assertEquals(1, broken.closed)
        assertEquals(FallbackReason.INIT_FAILED, seen.single().fallback)
        // Later pages go straight to Fast.
        service.recognize(OcrInput(ByteArray(100), 10, 10), OcrOptions(arEn, OcrMode.ACCURATE))
        assertEquals(1, broken.calls); assertEquals(2, tess.calls)
    }

    @Test
    fun whenNothingWorksTheExportHasNoTextLayerAndSaysSo() = runBlocking {
        val dead = FakeEngine("tesseract", OcrUnavailable(OcrUnavailable.Reason.NATIVE_LIBRARY))
        val (store, doc) = seed()
        var unavailable = 0
        val service = OcrService(factory(mnn = { false }, fast = dead))
        val exporter = Exporter(ctx, store, service) { OcrMode.ACCURATE }
        exporter.exportPdf(doc, ExportOptions(PageSize.A4, Quality.SMALL, ocr = true), onOcrUnavailable = { unavailable++ }) { _, _ -> }
        assertEquals(1, unavailable)
        // The next export tries again (D24: per export), and fails the same way.
        exporter.exportPdf(doc, ExportOptions(PageSize.A4, Quality.BALANCED, ocr = true), onOcrUnavailable = { unavailable++ }) { _, _ -> }
        assertEquals(2, unavailable)
    }

    /** Background OCR after capture fills the cache, so the export does not read again (no wait). */
    @Test
    fun backgroundReadMakesTheExportUseTheCache() = runBlocking {
        val acc = FakeEngine("pp-fused")
        val service = OcrService(factory(accurate = acc))
        val (store, doc) = seed()
        val bg = BackgroundOcr(store, service, idle, options = { OcrOptions(arEn, OcrMode.ACCURATE) to OcrLanguage.ARABIC_ENGLISH })
        assertTrue(bg.readDocument(doc.id))
        assertEquals(1, acc.calls)
        assertFalse("nothing left to read", bg.readDocument(doc.id))
        val pdf = Exporter(ctx, store, service) { OcrMode.ACCURATE }.exportPdf(doc, ExportOptions(PageSize.A4, Quality.BALANCED, ocr = true)) { _, _ -> }
        assertEquals("the export found the page cached", 1, acc.calls)
        assertTrue(text(pdf).contains("WORD-pp-fused"))
        // A Fast export does not take Accurate's words: Fast reads the page itself.
        val tess = FakeEngine("tesseract")
        Exporter(ctx, store, OcrService(factory(fast = tess, accurate = acc))) { OcrMode.FAST }
            .exportPdf(doc, ExportOptions(PageSize.A4, Quality.SMALL, ocr = true)) { _, _ -> }
        assertEquals(1, tess.calls)
        // Search reads any engine's words.
        assertTrue(store.documentText(store.load(doc.id)!!).contains("WORD-"))
    }

    @Test
    fun aPageEditedWhileBeingReadIsNotCached() = runBlocking {
        val (store, doc) = seed()
        val editing = object : OcrEngine {
            override val id = "pp-fused"
            override val version = 1
            override suspend fun recognize(page: OcrInput, options: OcrOptions): OcrResult {
                // The user changes the filter meanwhile: a new revision.
                store.save(doc.copy(pages = doc.pages.map { it.copy(revision = it.revision + 1) }))
                return OcrResult.empty(page.pageWidth, page.pageHeight, id, version)
            }
            override fun close() {}
        }
        val bg = BackgroundOcr(store, OcrService(factory(accurate = editing)), idle, options = { OcrOptions(arEn) to OcrLanguage.ARABIC_ENGLISH })
        assertFalse(bg.readDocument(doc.id))
        val now = store.load(doc.id)!!
        assertNull(store.loadOcr(doc.id, now.pages[0]))
    }

    @Test
    fun cacheEngineKeySelectsTheRightWords() {
        val (store, doc) = seed()
        val p = doc.pages[0]
        val w = RecognizedWord("نص", OcrBox(1, 2, 3, 4), 1f)
        store.saveOcr(doc.id, p, OcrResult(listOf(RecognizedLine(listOf(w), w.box, 1f, TextDirection.RTL)), 100, 100, "pp-fused", 1), "ara+eng", 2400)
        assertNotNull(store.loadOcrFrame(doc.id, p, "ara+eng", OcrEngineKey("pp-fused", 1)))
        assertNull(store.loadOcrFrame(doc.id, p, "ara+eng", OcrEngineKey("pp-fused", 2)))
        assertNull(store.loadOcrFrame(doc.id, p, "ara+eng", OcrEngineKey.LEGACY))
        assertNull(store.loadOcr(doc.id, p, "eng"))
        assertEquals("نص", store.loadOcr(doc.id, p)!!.single().text)
        // A 1.2 cache is Tesseract v1's.
        store.saveOcr(doc.id, p, listOf(com.fastkiwi.scan.core.OcrWord("old", 1, 2, 3, 4)), "ara+eng", 100, 100)
        assertNotNull(store.loadOcrFrame(doc.id, p, "ara+eng", OcrEngineKey.LEGACY))
        assertNull(store.loadOcrFrame(doc.id, p, "ara+eng", OcrEngineKey("pp-fused", 1)))
    }

    private var n = 0

    /** The background reader's queue loop runs here, apart from the test's own coroutine. */
    private val idle = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob())

    @org.junit.After
    fun stopQueue() = idle.coroutineContext[kotlinx.coroutines.Job]!!.cancel()

    private fun seed(): Pair<DocumentStore, ScanDocument> {
        val store = DocumentStore(File(ctx.filesDir, "ocr-sel-${n++}-${System.nanoTime()}"))
        var doc = store.create("ocr select")
        val bmp = Bitmap.createBitmap(1200, 1600, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawColor(Color.WHITE)
        val id = DocumentStore.newId()
        BitmapIO.saveJpeg(bmp, store.srcFile(doc.id, id), 92)
        BitmapIO.saveJpeg(bmp, store.outFile(doc.id, id), 92)
        doc = store.save(doc.copy(pages = listOf(ScanPage(id, BitmapIO.FULL, 0, Filter.ENHANCED))))
        return store to doc
    }

    private fun text(pdf: File) = Loader.loadPDF(pdf).use { PDFTextStripper().getText(it) }
}
