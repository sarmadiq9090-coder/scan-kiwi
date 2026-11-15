// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan

import android.graphics.Bitmap
import android.os.Looper
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.fastkiwi.scan.core.OcrWord
import com.fastkiwi.scan.core.PageSize
import com.fastkiwi.scan.data.ScanDocument
import com.fastkiwi.scan.export.ExportOptions
import com.fastkiwi.scan.export.Exporter
import com.fastkiwi.scan.export.Quality
import com.fastkiwi.scan.ocr.OcrLanguage
import com.fastkiwi.scan.ui.AppViewModel
import com.fastkiwi.scan.ui.Screen
import kotlinx.coroutines.runBlocking
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

private typealias Rule2 = AndroidComposeTestRule<*, MainActivity>

/** Words of a synthetic invoice, as the OCR cache stores them (frame 2400 x 3200, right to left). */
private val invoiceLines = listOf(
    listOf("فاتورة", "الكهرباء", "لشهر", "مارس"),
    listOf("المبلغ", "المستحق", "1200", "ريال"),
    listOf("Total", "1200", "SAR"),
)

private fun invoiceWords(): List<OcrWord> = invoiceLines.flatMapIndexed { li, line ->
    var right = 2200
    line.map { t ->
        val w = 120 + t.length * 30
        OcrWord(t, right - w, 300 + li * 160, right, 380 + li * 160).also { right -= w + 40 }
    }
}

/** Three documents with rendered pages; the invoice has OCR text, one is in a folder and starred. */
private fun seedDocs(rule: Rule2): Triple<ScanDocument, ScanDocument, ScanDocument> {
    val vm = rule.activity.vm
    val perf = ExportPerformanceTest()
    val invoice = vm.store.save(perf.seedPages(vm, 2).copy(name = "فاتورة الكهرباء"))
    vm.store.saveOcr(invoice.id, invoice.pages[0], invoiceWords(), OcrLanguage.ARABIC_ENGLISH.code, 2400, 3200)
    val lease = vm.store.save(perf.seedPages(vm, 1).copy(name = "عقد الإيجار", favorite = true))
    val passport = vm.store.save(perf.seedPages(vm, 1).copy(name = "Passport copy"))
    rule.runOnUiThread { vm.refresh() }
    rule.waitUntil(10_000) { vm.docs.value.size >= 3 }
    return Triple(invoice, lease, passport)
}

private fun idle(rule: Rule2) {
    val vm = rule.activity.vm
    rule.waitForIdle()
    rule.waitUntil(30_000) {
        shadowOf(Looper.getMainLooper()).idle()
        vm.busy.value == 0
    }
    rule.waitForIdle()
}

private fun shot(rule: Rule2, name: String) {
    rule.waitForIdle(); Thread.sleep(800); rule.waitForIdle()
    File("../screenshots").mkdirs()
    saveWindows(File("../screenshots/$name.png"))
}

private fun assertTargets(rule: Rule2, screen: String) {
    val density = rule.activity.resources.displayMetrics.density
    val min = 48f * density - 1f
    val nodes = rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick), useUnmergedTree = true).fetchSemanticsNodes()
    val small = nodes.filter { n -> n.boundsInRoot.width > 0f && n.boundsInRoot.height > 0f && (n.boundsInRoot.width < min || n.boundsInRoot.height < min) }
        .map { n -> "${n.config.toString().take(80)} ${n.boundsInRoot.width / density}x${n.boundsInRoot.height / density} dp" }
    assertTrue("$screen: touch targets under 48 dp:\n" + small.joinToString("\n"), small.isEmpty())
}

private fun clean(rule: Rule2) {
    val vm = rule.activity.vm
    vm.store.list().forEach { vm.store.delete(it.id) }
    vm.folders.value.forEach { vm.folderStore.delete(it.id, vm.store) }
    rule.runOnUiThread { vm.refresh() }
    rule.waitUntil(10_000) { vm.docs.value.isEmpty() && vm.folders.value.isEmpty() }
}

/** Like a real engine for the export: the invoice words scaled to the bitmap it is given. */
/** A fake engine (2.0 OcrEngine seam): the invoice words, in the frame of the page it is given. */
private open class InvoiceOcr : com.fastkiwi.scan.ocr.OcrEngine {
    override val id = "fake"
    override val version = 1
    override suspend fun recognize(page: com.fastkiwi.scan.ocr.OcrInput, options: com.fastkiwi.scan.ocr.OcrOptions): com.fastkiwi.scan.ocr.OcrResult {
        val kx = page.pageWidth / 2400f
        val ky = page.pageHeight / 3200f
        val ws = invoiceWords().map {
            com.fastkiwi.scan.ocr.RecognizedWord(it.text, com.fastkiwi.scan.ocr.OcrBox((it.left * kx).toInt(), (it.top * ky).toInt(), (it.right * kx).toInt(), (it.bottom * ky).toInt()), 1f)
        }
        val box = ws.drop(1).fold(ws[0].box) { b, w -> b.union(w.box) }
        return com.fastkiwi.scan.ocr.OcrResult(listOf(com.fastkiwi.scan.ocr.RecognizedLine(ws, box, 1f, com.fastkiwi.scan.ocr.TextDirection.RTL)), page.pageWidth, page.pageHeight, id, version)
    }
    override fun close() {}
}

private class CountingOcr : InvoiceOcr() {
    var calls = 0
    override suspend fun recognize(page: com.fastkiwi.scan.ocr.OcrInput, options: com.fastkiwi.scan.ocr.OcrOptions): com.fastkiwi.scan.ocr.OcrResult { calls++; return super.recognize(page, options) }
}

private fun fakeExporter(ctx: android.content.Context, store: com.fastkiwi.scan.data.DocumentStore, engine: com.fastkiwi.scan.ocr.OcrEngine) =
    Exporter(ctx, store, com.fastkiwi.scan.ocr.OcrService(com.fastkiwi.scan.ocr.OcrEngineFactory.fixed(engine)))

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "ar-w400dp-h860dp-xxhdpi")
class DocsArabicUiTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @org.junit.Before
    fun before() = clean(rule)

    /** Home in Arabic: folders, favorites filter, recent, sort; search and the page text with highlights. */
    @Test
    fun homeSearchAndTextInArabic() {
        val (invoice, lease, _) = seedDocs(rule)
        val vm = rule.activity.vm
        rule.runOnUiThread { vm.createFolder("فواتير", listOf(invoice.id)) }
        idle(rule)
        assertEquals(vm.folders.value.single().id, vm.store.load(invoice.id)!!.folderId)
        rule.onNodeWithText("المجلدات").assertExists()
        rule.onNodeWithText("فواتير").assertExists()
        rule.onNodeWithText("الأخيرة").assertExists()
        rule.onNodeWithText("ابحث في الأسماء والنص").assertExists()
        assertTargets(rule, "Home")
        shot(rule, "40-home-folders-ar")

        // Favorites filter: only the starred lease.
        rule.onNodeWithText("المفضلة").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("عقد الإيجار").assertExists()
        assertTrue(rule.onAllNodesWithText("Passport copy").fetchSemanticsNodes().isEmpty())
        shot(rule, "41-home-favorites-ar")
        rule.onNodeWithText("الكل").performClick()

        // Folder contents.
        rule.onNodeWithText("فواتير").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("فاتورة الكهرباء").assertExists()
        assertTargets(rule, "Folder")
        shot(rule, "42-folder-ar")
        rule.runOnUiThread { vm.back() }

        // Search typed without hamza or ta marbuta: «فاتوره الكهرباء» finds the page.
        rule.onNodeWithText("ابحث في الأسماء والنص").performClick()
        rule.waitForIdle()
        rule.onNode(hasSetTextAction()).performTextInput("الكهرباء المستحق")
        rule.waitUntil(10_000) { rule.onAllNodesWithText("في نص الصفحات").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("صفحة " + com.fastkiwi.scan.ui.LocalFormat.count(1)).assertExists()
        assertTargets(rule, "Search")
        shot(rule, "43-search-ar")

        rule.onNodeWithText("صفحة " + com.fastkiwi.scan.ui.LocalFormat.count(1)).performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithText("نص الصفحة " + com.fastkiwi.scan.ui.LocalFormat.count(1)).fetchSemanticsNodes().isNotEmpty() }
        rule.waitUntil(10_000) { rule.onAllNodesWithText("المبلغ المستحق", substring = true).fetchSemanticsNodes().isNotEmpty() }
        assertTargets(rule, "Text")
        shot(rule, "44-page-text-highlight-ar")
        val state = runBlocking { vm.pageText(invoice.id, invoice.pages[0].id, "الكهرباء المستحق") }!!
        assertEquals("both matching words are highlighted", 2, state.highlights.size)
        assertTrue(state.highlights.all { it.left in 0f..1f && it.right in 0f..1f && it.top < it.bottom })
        assertFalse(lease.favorite && vm.store.load(lease.id)!!.folderId != null)
    }

    /** 200 % text: Home's primary action and the search results stay inside the window. */
    @Test
    @Config(fontScale = 2.0f)
    fun largeTextHomeAndSearch() {
        seedDocs(rule)
        val root = rule.onRoot().fetchSemanticsNode().boundsInRoot
        val b = rule.onNodeWithText("مسح", useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(b.right <= root.right + 1 && b.bottom <= root.bottom + 1)
        shot(rule, "45-home-200pct-ar")
        rule.runOnUiThread { rule.activity.vm.open(Screen.Search) }
        rule.waitForIdle()
        rule.onNode(hasSetTextAction()).performTextInput("فاتوره")
        rule.waitUntil(10_000) { rule.onAllNodesWithText("مستندات بالاسم").fetchSemanticsNodes().isNotEmpty() }
        shot(rule, "46-search-200pct-ar")
    }

    /**
     * Acceptance (ROADMAP 1.6.0): an edited word is found by search and appears in the exported
     * PDF text layer (PDFBox); the image is not changed; Undo brings the old word back.
     */
    @Test
    fun correctedWordIsSearchableAndInPdfTextLayer() {
        val (invoice, _, _) = seedDocs(rule)
        val vm = rule.activity.vm
        val page = invoice.pages[0]
        val imageBefore = vm.store.outFile(invoice.id, page.id).readBytes()
        // First export with OCR fills the cache in the export's frame.
        val opt = ExportOptions(PageSize.A4, Quality.SMALL, ocr = true)
        runBlocking { fakeExporter(rule.activity, vm.store, InvoiceOcr()).exportPdf(vm.store.load(invoice.id)!!, opt) { _, _ -> } }

        val before = runBlocking { vm.pageText(invoice.id, page.id, null) }!!
        val edited = before.text.replace("1200", "1500").replace("مارس", "أبريل")
        var ok: Boolean? = null
        rule.runOnUiThread { vm.saveText(invoice.id, page.id, edited) { ok = it } }
        idle(rule)
        assertEquals(true, ok)

        val hits = runBlocking { vm.search("ابريل") } // typed without hamza
        assertEquals(listOf(invoice.id to page.id), hits.pages.map { it.doc.id to it.page.id })
        assertTrue("the corrected page no longer has the old word", runBlocking { vm.search("مارس") }.pages.none { it.page.id == page.id })

        val again = CountingOcr()
        val pdf = runBlocking { fakeExporter(rule.activity, vm.store, again).exportPdf(vm.store.load(invoice.id)!!, opt) { _, _ -> } }
        assertEquals("the cached (corrected) words are used, no new OCR", 0, again.calls)
        val text = Loader.loadPDF(pdf).use { PDFTextStripper().apply { startPage = 1; endPage = 1 }.getText(it) }
        assertTrue("corrected amount in the PDF text layer: $text", text.contains("1500"))
        assertFalse("old amount gone: $text", text.contains("1200"))
        assertTrue("image never changes", imageBefore.contentEquals(vm.store.outFile(invoice.id, page.id).readBytes()))

        // Undo the correction.
        rule.runOnUiThread { vm.undoText(invoice.id, page.id) }
        idle(rule)
        assertTrue(runBlocking { vm.search("مارس") }.pages.any { it.page.id == page.id })
        assertNull(vm.store.loadTextOverlay(invoice.id, page.id))
    }

    /** Folders through the view model: move, copy (pages and text kept), delete keeps documents; a star is not an edit. */
    @Test
    fun folderMoveCopyDeleteAndFavorite() {
        val (invoice, lease, passport) = seedDocs(rule)
        val vm = rule.activity.vm
        val modified = vm.store.load(passport.id)!!.modified
        rule.runOnUiThread { vm.setFavorite(passport.id, true) }
        idle(rule)
        assertTrue(vm.store.load(passport.id)!!.favorite)
        assertEquals("starring is not an edit", modified, vm.store.load(passport.id)!!.modified)

        rule.runOnUiThread { vm.createFolder("أرشيف") }
        idle(rule)
        val folder = vm.folders.value.single()
        rule.runOnUiThread { vm.moveDocs(listOf(lease.id, passport.id), folder.id) }
        idle(rule)
        rule.runOnUiThread { vm.copyDocs(listOf(invoice.id), folder.id) }
        idle(rule)
        val copy = vm.store.list().single { it.id !in setOf(invoice.id, lease.id, passport.id) }
        assertEquals(folder.id, copy.folderId)
        assertEquals(invoice.pages.size, copy.pages.size)
        assertEquals("text copied with the pages", "1200", vm.store.loadOcr(copy.id, copy.pages[0])!![6].text)
        assertEquals(2, runBlocking { vm.search("الكهرباء") }.pages.size)

        rule.runOnUiThread { vm.deleteFolder(folder.id) }
        idle(rule)
        assertTrue(vm.folders.value.isEmpty())
        val all = vm.store.list()
        assertEquals("folder delete never deletes documents", 4, all.size)
        assertTrue(all.all { it.folderId == null })
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w400dp-h860dp-xxhdpi")
class DocsEnglishUiTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @org.junit.Before
    fun before() = clean(rule)

    @Test
    fun homeAndSearchInEnglish() {
        seedDocs(rule)
        val vm = rule.activity.vm
        rule.runOnUiThread { vm.createFolder("Receipts") }
        idle(rule)
        rule.onNodeWithText("Folders").assertExists()
        rule.onNodeWithText("Receipts").assertExists()
        rule.onNodeWithText("Favorites").assertExists()
        rule.onNodeWithText("Newest first").assertExists()
        assertTargets(rule, "Home en")
        shot(rule, "47-home-en")
        rule.onNodeWithText("Search names and text").performClick()
        rule.waitForIdle()
        rule.onNode(hasSetTextAction()).performTextInput("total")
        rule.waitUntil(10_000) { rule.onAllNodesWithText("In the page text").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Page " + com.fastkiwi.scan.ui.LocalFormat.count(1)).assertExists()
        assertTargets(rule, "Search en")
        shot(rule, "48-search-en")
    }
}
