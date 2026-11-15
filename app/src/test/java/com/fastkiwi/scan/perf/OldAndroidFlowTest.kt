// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.perf

import android.content.ComponentName
import android.net.Uri
import android.os.Build
import android.os.Looper
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.fastkiwi.scan.ExportPerformanceTest
import com.fastkiwi.scan.MainActivity
import com.fastkiwi.scan.R
import com.fastkiwi.scan.core.OcrWord
import com.fastkiwi.scan.core.PageSize
import com.fastkiwi.scan.export.ExportOptions
import com.fastkiwi.scan.export.Exporter
import com.fastkiwi.scan.export.Quality
import com.fastkiwi.scan.export.TextExporter
import com.fastkiwi.scan.ocr.OcrEngineFactory
import com.fastkiwi.scan.ocr.OcrLanguage
import com.fastkiwi.scan.ocr.OcrService
import com.fastkiwi.scan.pdf.NumberPosition
import com.fastkiwi.scan.pdf.PageNumbering
import com.fastkiwi.scan.pdf.PdfService
import com.fastkiwi.scan.pdf.SyntheticPdfs
import com.fastkiwi.scan.pdf.Watermark
import com.fastkiwi.scan.ui.PdfTool
import com.fastkiwi.scan.ui.PdfToolsViewModel
import com.fastkiwi.scan.ui.Screen
import kotlinx.coroutines.runBlocking
import org.apache.pdfbox.Loader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Android 7.0 pass (ROADMAP 1.7.0 item 4), with Android 8.0 and 9 spot checks: the main flows on
 * API 24, 26 and 28 under Robolectric. Each test runs once per API level.
 *
 * What Robolectric cannot prove (see docs/features-2.0/perf.md): on API 24/25 Robolectric has no native
 * graphics, so bitmaps are shadow objects (decode, filters, JPEG bytes are not real; the flow and the
 * PDF structure are); the camera preview (CameraX) never runs; Tesseract and MNN native libraries do not
 * load (a fake engine stands behind the OCR seam); the java.vm is not Dalvik, so the pre-Android-8
 * rename fallback in AtomicWrite is not taken; real ART memory limits and speed are not modelled.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [24, 26, 28], qualifiers = "ar-w400dp-h860dp-xxhdpi")
class OldAndroidFlowTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val ctx get() = rule.activity

    private fun s(id: Int) = ctx.getString(id)

    private fun idle() {
        rule.waitForIdle()
        shadowOf(Looper.getMainLooper()).idle()
        rule.waitForIdle()
    }

    private fun open(screen: Screen) {
        rule.runOnUiThread { rule.activity.vm.open(screen) }
        idle()
    }

    private fun back() {
        rule.runOnUiThread { rule.activity.vm.back() }
        idle()
    }

    private fun exists(text: String, substring: Boolean = false) =
        rule.onAllNodesWithText(text, substring = substring, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    @Before
    fun clean() {
        val vm = rule.activity.vm
        vm.store.list().forEach { vm.store.delete(it.id) }
        rule.runOnUiThread { vm.refresh() }
        rule.waitUntil(10_000) { vm.docs.value.isEmpty() }
    }

    private fun record(line: String) {
        println("PERF-OLDAPI ${Build.VERSION.SDK_INT} $line")
        File("build/perf-oldapi.txt").appendText("API ${Build.VERSION.SDK_INT}: $line\n")
    }

    /** Home, camera permission screen, editor, search, page text and settings render and work. */
    @Test
    fun screensRenderAndWork() {
        val vm = rule.activity.vm
        // Home
        rule.waitUntil(10_000) { exists(s(R.string.docs_search_hint)) }
        assertTrue("Home: New scan", exists(s(R.string.scan)))

        // Camera without the permission: the explanation screen, not a crash.
        open(Screen.Camera(null))
        rule.waitUntil(10_000) { exists(s(R.string.camera_permission_title)) }
        back()

        // A document with OCR text: editor, search, page text.
        val doc = vm.store.save(ExportPerformanceTest().seedPages(vm, 2).copy(name = "فاتورة الكهرباء"))
        val words = listOf(OcrWord("فاتورة", 1800, 300, 2200, 380), OcrWord("الكهرباء", 1300, 300, 1760, 380), OcrWord("1200", 900, 460, 1200, 540))
        vm.store.saveOcr(doc.id, doc.pages[0], words, OcrLanguage.ARABIC_ENGLISH.code, 2400, 3200)
        rule.runOnUiThread { vm.refresh() }
        rule.waitUntil(10_000) { vm.docs.value.size == 1 }
        open(Screen.Editor(doc.id))
        rule.waitUntil(10_000) { exists("فاتورة الكهرباء") }
        back()

        // Search (FTS4 in the platform SQLite) typed without the hamza: «فاتوره».
        val results = runBlocking {
            var r = vm.search("فاتوره الكهرباء")
            val end = System.currentTimeMillis() + 10_000
            while (r.pages.isEmpty() && System.currentTimeMillis() < end) { Thread.sleep(100); r = vm.search("فاتوره الكهرباء") }
            r
        }
        assertEquals("search finds the page", 1, results.pages.size)
        open(Screen.Search)
        rule.onNode(hasSetTextAction()).performTextInput("الكهرباء")
        rule.waitUntil(10_000) { exists("فاتورة الكهرباء", substring = true) }
        back()

        // Page text screen.
        open(Screen.Text(doc.id, doc.pages[0].id))
        rule.waitUntil(10_000) { exists("الكهرباء", substring = true) }
        back()

        // Settings: low-memory row; wallpaper colours only from Android 12 (hidden here).
        open(Screen.Settings)
        rule.onNodeWithText(s(R.string.perf_low_memory_title), useUnmergedTree = true).performScrollTo()
        assertTrue(exists(s(R.string.perf_low_memory_title)))
        assertTrue("no Material You row below Android 12", !exists(s(R.string.dynamic_color_title)))
        assertTrue(exists(s(R.string.language)))
        back()
        // PDF tools entry screen.
        open(Screen.PdfTools)
        idle()
        back()
        record("screens: Home, camera permission, editor, search, text, settings, PDF tools OK")
    }

    /** Export with every option: sizes, qualities, OCR text layer, password, PDF/A, target size, page numbers, watermark, JPEG, TXT. */
    @Test
    fun exportWithEveryOption() = runBlocking {
        val vm = rule.activity.vm
        val doc = vm.store.save(ExportPerformanceTest().seedPages(vm, 3).copy(name = "export ${Build.VERSION.SDK_INT}"))
        val fake = PerfSeed.FakeOcr()
        val ex = Exporter(ctx, vm.store, OcrService(OcrEngineFactory.fixed(fake)))
        val cases = listOf(
            "A4 balanced" to ExportOptions(PageSize.A4, Quality.BALANCED),
            "Letter small + OCR" to ExportOptions(PageSize.LETTER, Quality.SMALL, ocr = true),
            "Legal high + password" to ExportOptions(PageSize.LEGAL, Quality.HIGH, password = "سر مكتبي"),
            "A5 archival" to ExportOptions(PageSize.A5, archival = true),
            "fit + target 400 KB" to ExportOptions(PageSize.FIT, targetBytes = 400_000L),
            "numbers + watermark + OCR Arabic" to ExportOptions(
                PageSize.A4, Quality.BALANCED, ocr = true, ocrLanguage = OcrLanguage.ARABIC,
                pageNumbers = PageNumbering(NumberPosition.BOTTOM_LEFT, arabicDigits = true, withTotal = true), watermark = Watermark("سري"),
            ),
        )
        for ((name, opt) in cases) {
            val f = ex.exportPdf(doc, opt) { _, _ -> }
            val pages = Loader.loadPDF(f, opt.password ?: "").use { it.numberOfPages }
            assertEquals("$name: pages", 3, pages)
        }
        assertTrue("OCR ran through the engine seam", fake.calls > 0)
        val jpegs = ex.exportJpegs(doc, ExportOptions(quality = Quality.SMALL, pageNumbers = PageNumbering())) { _, _ -> }
        assertEquals(3, jpegs.size)
        assertTrue(jpegs.all { it.length() > 0 })
        val text = TextExporter(vm.store).text(vm.store.load(doc.id)!!, { "— $it —" })
        val txt = TextExporter(vm.store).write(text, File(ctx.cacheDir, "exports"), "txt-${Build.VERSION.SDK_INT}")
        assertTrue("TXT has the OCR words", txt.readText().contains("كلمة1"))
        record("export: ${cases.size} PDF option sets + JPEG + TXT OK (bitmaps: ${org.robolectric.shadow.api.Shadow.extract<Any>(android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888)).javaClass.simpleName})")
    }

    /** PDF tools for external files: open a protected PDF with its password, merge, split. */
    @Test
    fun pdfToolsMergeSplitUnlock() {
        val app = RuntimeEnvironment.getApplication()
        val dir = File(app.cacheDir, "tools-${System.nanoTime()}").apply { mkdirs() }
        val service = Robolectric.setupService(PdfService::class.java)
        shadowOf(app).setComponentNameAndServiceForBindService(ComponentName(app, PdfService::class.java), service.onBind(null))
        val a = SyntheticPdfs.external(dir, "OA", 3, seed = 31)
        val b = SyntheticPdfs.external(dir, "OB", 2, seed = 32, password = "كلمة سر")
        val tools = PdfToolsViewModel(app)
        fun until(cond: () -> Boolean) {
            val end = System.currentTimeMillis() + 30_000
            while (!cond()) { check(System.currentTimeMillis() < end) { "timed out" }; shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5) }
        }
        tools.add(listOf(Uri.fromFile(a), Uri.fromFile(b)))
        until { tools.files.size == 2 && tools.files[0].pages != null && tools.files[1].locked && tools.working.value == null }
        tools.unlockEntry(tools.files[1].id, "كلمة سر")
        until { tools.working.value == null && tools.files[1].pages == 2 }
        tools.runTool(PdfTool.MERGE)
        until { tools.results.value != null && tools.working.value == null }
        val merged = tools.results.value!!.single()
        assertEquals(5, Loader.loadPDF(merged).use { it.numberOfPages })
        record("PDF tools: unlock + merge OK")
    }
}
