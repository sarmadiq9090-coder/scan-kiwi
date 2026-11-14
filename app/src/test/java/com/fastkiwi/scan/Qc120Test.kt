// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Looper
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.fastkiwi.scan.core.AutoCapture
import com.fastkiwi.scan.core.Filter
import com.fastkiwi.scan.core.OcrWord
import com.fastkiwi.scan.core.PageSize
import com.fastkiwi.scan.core.Pt
import com.fastkiwi.scan.core.Quad
import com.fastkiwi.scan.data.DocumentStore
import com.fastkiwi.scan.data.ScanPage
import com.fastkiwi.scan.export.ExportOptions
import com.fastkiwi.scan.export.Quality
import com.fastkiwi.scan.feedback.DeviceInfo
import com.fastkiwi.scan.feedback.Feedback
import com.fastkiwi.scan.feedback.FeedbackKind
import com.fastkiwi.scan.imaging.BitmapIO
import com.fastkiwi.scan.imaging.PdfImporter
import com.fastkiwi.scan.ocr.OcrLanguage
import com.fastkiwi.scan.ui.AppViewModel
import com.fastkiwi.scan.ui.ExportResult
import com.fastkiwi.scan.ui.Screen
import com.fastkiwi.scan.ui.Settings
import com.fastkiwi.scan.ui.theme.ThemeMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream
import java.io.File

/*
 * Independent QC and bug hunt for 1.2.0 (QC-1.2.0-report.md). Each test below
 * failed on 77ad6d6 before its fix, except where a comment says it guards an
 * area that was already right (migration, performance).
 */

/** QC-1 (S2): the export sheet's Share / Save must be reachable without scrolling (spec §3, §3.6). */
private fun assertExportFooterVisible(rule: androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, MainActivity>, withOcr: Boolean) {
    val vm = rule.activity.vm
    val doc = vm.store.save(ExportPerformanceTest().seedPages(vm, 3).copy(name = "عقد"))
    rule.runOnUiThread { vm.refresh() }
    rule.waitUntil(10_000) { vm.docs.value.any { it.doc.id == doc.id } }
    rule.runOnUiThread { vm.open(Screen.Editor(doc.id)) }
    rule.waitForIdle()
    rule.onNodeWithText("تصدير").performClick()
    rule.waitUntil(5_000) { rule.onAllNodesWithText("حماية بكلمة مرور").fetchSemanticsNodes().isNotEmpty() }
    if (withOcr) {
        rule.onNodeWithText("جعل النص قابلًا للبحث (OCR)").performClick()
        rule.waitForIdle()
    }
    rule.waitForIdle(); Thread.sleep(500); rule.waitForIdle()
    val height = rule.activity.resources.displayMetrics.heightPixels
    for (label in listOf("مشاركة", "حفظ في الجهاز")) {
        val b = rule.onAllNodesWithText(label, useUnmergedTree = true).fetchSemanticsNodes().last().boundsInWindow
        assertTrue("«$label» is not fully on screen (ocr=$withOcr): $b, window height $height", b.height > 0f && b.top >= 0f && b.bottom <= height + 1f)
    }
    vm.store.delete(doc.id) // the app's files outlive the test; keep other screenshots clean
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "ar-w400dp-h860dp-xxhdpi")
class QcExportFooterTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun shareAndSaveAreOnScreen() = assertExportFooterVisible(rule, withOcr = false)

    @Test
    fun shareAndSaveAreOnScreenWithOcrOptionsOpen() = assertExportFooterVisible(rule, withOcr = true)
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "ar-w400dp-h860dp-xxhdpi", fontScale = 2.0f)
class QcExportFooterLargeTextTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun shareAndSaveAreOnScreenAt200Percent() = assertExportFooterVisible(rule, withOcr = false)
}

/** Pure-Kotlin QC findings. */
class QcCoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val quad = Quad(Pt(0.1f, 0.1f), Pt(0.9f, 0.1f), Pt(0.9f, 0.9f), Pt(0.1f, 0.9f))

    /** QC-3 (S3): a dropped frame must not show «اثبت قليلًا…» when nothing is counting down. */
    @Test
    fun missedFrameDoesNotClaimHoldWhenAutoIsOffOrDoubtful() {
        val off = AutoCapture()
        assertEquals(AutoCapture.State.FOUND, off.feed(quad, confident = true, timeMs = 0, enabled = false).state)
        assertEquals(AutoCapture.State.FOUND, off.feed(null, confident = false, timeMs = 33, enabled = false).state)
        val doubtful = AutoCapture()
        assertEquals(AutoCapture.State.FOUND, doubtful.feed(quad, confident = false, timeMs = 0).state)
        assertEquals(AutoCapture.State.FOUND, doubtful.feed(null, confident = false, timeMs = 33).state)
        // While it really is counting down, a short dropout keeps the countdown.
        val on = AutoCapture()
        on.feed(quad, confident = true, timeMs = 0)
        val u = on.feed(null, confident = false, timeMs = 300)
        assertEquals(AutoCapture.State.HOLD, u.state)
        assertTrue(u.progress > 0f)
    }

    /** QC-4 (S3): the description-shortening loop must always end, whatever the fixed part costs. */
    @Test(timeout = 5_000)
    fun githubUrlLoopAlwaysEnds() {
        val huge = DeviceInfo("1.2.0", 4, "15", "X".repeat(Feedback.MAX_URL), "ar")
        val url = Feedback.githubIssue(FeedbackKind.PROBLEM, "1.2.0", "مشكلة في الزر", huge)
        assertTrue(url.contains("&description=&"))
        val small = DeviceInfo("1.2.0", 4, "15", "X".repeat(Feedback.MAX_URL - 300), "ar")
        val url2 = Feedback.githubIssue(FeedbackKind.PROBLEM, "1.2.0", "مشكلة ".repeat(200), small)
        assertTrue(url2.length <= Feedback.MAX_URL || url2.contains("&description=&"))
    }

    /** QC-5 (S3): pasting more than the limit keeps the first 4000 characters instead of ignoring the paste. */
    @Test
    fun overlongPasteIsCutNotDropped() {
        val pasted = "ب".repeat(8000)
        assertEquals(Feedback.MAX_TEXT, Feedback.clipInput(pasted).length)
        val emojiAtEdge = "ا".repeat(Feedback.MAX_TEXT - 1) + "😀" + "تتمة"
        val cut = Feedback.clipInput(emojiAtEdge)
        assertFalse("a lone surrogate was left at the end", Character.isHighSurrogate(cut.last()))
        assertEquals("قصير", Feedback.clipInput("قصير"))
    }

    /** QC-2 (S2): OCR words cached for one language must not be reused for another. */
    @Test
    fun ocrCacheIsPerLanguage() {
        val store = DocumentStore(tmp.root)
        val page = ScanPage("abcd", Quad.full(1f, 1f), revision = 2)
        val doc = store.save(store.create("x").copy(pages = listOf(page)))
        val english = listOf(OcrWord("Invoice", 1, 2, 3, 4))
        store.saveOcr(doc.id, page, english, OcrLanguage.ENGLISH.code)
        assertEquals(english, store.loadOcr(doc.id, page, OcrLanguage.ENGLISH.code))
        assertNull("English-only words reused for an Arabic + English export", store.loadOcr(doc.id, page, OcrLanguage.ARABIC_ENGLISH.code))
        // Search uses whatever text is there, in any language.
        assertEquals(english, store.loadOcr(doc.id, page))
        assertTrue(store.documentText(doc).contains("Invoice"))
    }

    /** 1.1.1 OCR caches have no language: still searchable, but redone for an export. */
    @Test
    fun ocrCacheFrom111IsSearchableButRecomputed() {
        val store = DocumentStore(tmp.root)
        val page = ScanPage("abcd", Quad.full(1f, 1f), revision = 0)
        val doc = store.save(store.create("x").copy(pages = listOf(page)))
        File(store.dir(doc.id), "p_abcd_ocr.json").writeText("""{"revision":0,"words":[["فاتورة",1,2,3,4]]}""")
        assertTrue(store.documentText(doc).contains("فاتورة"))
        assertNull(store.loadOcr(doc.id, page, OcrLanguage.ARABIC_ENGLISH.code))
    }
}

/** Robolectric QC findings and the 1.1.1 → 1.2.0 data migration. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class QcAppTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val prefs get() = app.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
    private var savedPrefs: Map<String, *> = emptyMap<String, Any>()
    private var hadSignature = false

    /** Robolectric keeps the app's files and settings between test classes: leave them as found. */
    @org.junit.Before
    fun remember() {
        savedPrefs = HashMap(prefs.all)
        hadSignature = File(app.filesDir, "signature/signature.png").exists()
    }

    @org.junit.After
    fun restore() {
        DocumentStore(app.filesDir).let { st -> st.list().forEach { st.delete(it.id) } }
        if (!hadSignature) File(app.filesDir, "signature/signature.png").delete()
        val e = prefs.edit().clear()
        for ((k, v) in savedPrefs) when (v) {
            is Boolean -> e.putBoolean(k, v)
            is String -> e.putString(k, v)
            is Int -> e.putInt(k, v)
            is Long -> e.putLong(k, v)
            is Float -> e.putFloat(k, v)
        }
        e.commit()
    }

    private fun idle(ms: Long = 10_000, done: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!done() && System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** QC-6 (S3): a PDF whose provider gives no MIME type and no ".pdf" name is still read as a PDF. */
    @Test
    fun pdfIsRecognisedByItsHeader() {
        val uri = Uri.parse("content://com.example.mail.attachments/message/42/part/3")
        val pdf = "%PDF-1.7\n%âãÏÓ\n1 0 obj\n<< >>\nendobj\n".toByteArray(Charsets.ISO_8859_1)
        shadowOf(app.contentResolver).registerInputStreamSupplier(uri) { ByteArrayInputStream(pdf) }
        val type = app.contentResolver.getType(uri) ?: ""
        // What 1.2.0 (77ad6d6) checked: neither matches, so the PDF went to the image decoder.
        assertFalse(type == "application/pdf" || uri.toString().endsWith(".pdf", true))
        assertTrue(PdfImporter.isPdf(app, uri, type))
        val jpegUri = Uri.parse("content://com.example.mail.attachments/message/42/part/4")
        shadowOf(app.contentResolver).registerInputStreamSupplier(jpegUri) { ByteArrayInputStream(byteArrayOf(-1, -40, -1, -32, 0, 16)) }
        assertFalse(PdfImporter.isPdf(app, jpegUri, ""))
    }

    private fun page(w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        val ink = Paint().apply { color = Color.rgb(20, 20, 30); strokeWidth = 6f }
        for (i in 0 until 20) c.drawLine(60f, 80f + i * 40f, w - 60f, 80f + i * 40f, ink)
        return bmp
    }

    /**
     * Upgrade: documents, settings, signature and OCR text written by 1.1.1 (versionCode 3)
     * are all there under 1.2.0, start-up clean-up keeps them, and an old signature follows
     * a re-crop. (Guards an area that was already right: no failing run before.)
     */
    @Test
    fun dataFrom111SurvivesTheUpgrade() {
        val files = app.filesDir
        DocumentStore(files).let { st -> st.list().forEach { st.delete(it.id) } }
        val docId = "0123456789abcdef"
        val dir = File(files, "documents/$docId").apply { mkdirs() }
        val ids = listOf("aaaa000000000001", "aaaa000000000002", "aaaa000000000003")
        for (id in ids) {
            val b = page(900, 1200)
            BitmapIO.saveJpeg(b, File(dir, "p_$id.jpg"), 92)
            BitmapIO.saveJpeg(b, File(dir, "p_${id}_out.jpg"), 92)
            BitmapIO.saveJpeg(BitmapIO.fit(b, 360), File(dir, "p_${id}_thumb.jpg"), 82)
        }
        // Exactly what 1.1.1's ScanDocument.toJson() wrote.
        File(dir, "doc.json").writeText(
            """{"version":1,"id":"$docId","name":"عقد الإيجار","created":1763100000000,"modified":1763100500000,"pages":[""" +
                """{"id":"${ids[0]}","corners":[0.05,0.04,0.95,0.06,0.94,0.96,0.06,0.95],"rotation":90,"filter":"BW","revision":3,"stamps":[[0.6,0.8,0.25]]},""" +
                """{"id":"${ids[1]}","corners":[0,0,1,0,1,1,0,1],"rotation":0,"filter":"WHITEN","revision":0,"stamps":[]},""" +
                """{"id":"${ids[2]}","corners":[0,0,1,0,1,1,0,1],"rotation":180,"filter":"ORIGINAL","revision":1,"stamps":[]}]}""",
        )
        File(dir, "p_${ids[1]}_ocr.json").writeText("""{"revision":0,"words":[["فاتورة",1,2,3,4],["Invoice",5,6,7,8]]}""")
        File(files, "signature").mkdirs()
        val sig = Bitmap.createBitmap(300, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.TRANSPARENT); setPixel(150, 50, Color.BLACK) }
        BitmapIO.savePng(sig, File(files, "signature/signature.png"))
        // Files from an older session (start-up clean-up only looks at those).
        dir.listFiles()!!.forEach { it.setLastModified(System.currentTimeMillis() - 86_400_000L) }
        app.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE).edit().clear()
            .putBoolean("auto_capture", false).putBoolean("review_after_capture", false)
            .putString("default_filter", "BW").putString("page_size", "LETTER").putString("quality", "SMALL")
            .putBoolean("ocr", true).putString("ocr_language", "ENGLISH").putString("language", "en").commit()

        val vm = AppViewModel(app)
        idle { vm.docs.value.isNotEmpty() }
        Thread.sleep(300); idle { true } // let start-up clean-up finish
        val doc = vm.docs.value.single().doc
        assertEquals("عقد الإيجار", doc.name)
        assertEquals(ids, doc.pages.map { it.id })
        assertEquals(listOf(Filter.BW, Filter.WHITEN, Filter.ORIGINAL), doc.pages.map { it.filter })
        assertEquals(listOf(90, 0, 180), doc.pages.map { it.rotation })
        assertEquals(1, doc.pages[0].stamps.size)
        for (id in ids) {
            assertTrue(File(dir, "p_$id.jpg").exists())
            assertTrue(File(dir, "p_${id}_out.jpg").exists())
        }
        assertTrue(File(files, "signature/signature.png").exists())
        assertTrue("1.1.1 OCR text no longer searchable", vm.docs.value.single().text.contains("فاتورة"))
        val s = Settings(app)
        assertFalse(s.autoCapture); assertFalse(s.reviewAfterCapture)
        assertEquals(Filter.BW, s.defaultFilter); assertEquals(PageSize.LETTER, s.pageSize); assertEquals(Quality.SMALL, s.quality)
        assertTrue(s.ocr); assertEquals(OcrLanguage.ENGLISH, s.ocrLanguage)
        assertEquals("en", Settings.languageTag(app))
        assertEquals(ThemeMode.SYSTEM, s.themeMode); assertFalse(s.dynamicColor)

        // An old signature follows a tighter re-crop (it used to stay at its page position).
        val before = doc.pages[0].stamps.single()
        vm.editPage(doc.id, ids[0]) { it.copy(corners = Quad(Pt(0.2f, 0.2f), Pt(0.9f, 0.2f), Pt(0.9f, 0.9f), Pt(0.2f, 0.9f))) }
        idle { vm.store.load(doc.id)!!.pages[0].revision > 3 }
        val after = vm.store.load(doc.id)!!.pages[0]
        assertEquals(1, after.stamps.size)
        assertNotEquals(before, after.stamps.single())

        // And the migrated document exports.
        vm.share(doc.id, ExportOptions(PageSize.A4, Quality.SMALL), asJpeg = false)
        val r = runBlocking {
            var r: ExportResult? = null
            val end = System.currentTimeMillis() + 60_000
            while (r == null && System.currentTimeMillis() < end) {
                shadowOf(Looper.getMainLooper()).idle()
                r = withTimeoutOrNull(50) { vm.exports.first() }
            }
            r
        }
        assertNotNull(r)
        assertTrue((r as ExportResult.Share).files.single().length() > 1000)
    }

    /** Export / share timings for 1, 10 and 50 pages, main-thread time after the tap, and cancel latency. */
    @Test
    fun exportTimings1to50Pages() {
        val perf = ExportPerformanceTest()
        val lines = ArrayList<String>()
        for (n in listOf(1, 10, 50)) {
            val vm = AppViewModel(app)
            val doc = perf.seedPages(vm, n)
            fun timed(jpeg: Boolean, label: String) {
                val t0 = System.nanoTime()
                vm.share(doc.id, ExportOptions(PageSize.A4, Quality.BALANCED), asJpeg = jpeg)
                val m0 = System.nanoTime()
                shadowOf(Looper.getMainLooper()).idle()
                val mainMs = (System.nanoTime() - m0) / 1_000_000
                val r = runBlocking {
                    var r: ExportResult? = null
                    while (r == null) { shadowOf(Looper.getMainLooper()).idle(); r = withTimeoutOrNull(50) { vm.exports.first() } }
                    r
                } as ExportResult.Share
                val ms = (System.nanoTime() - t0) / 1_000_000
                assertTrue("main thread busy $mainMs ms", mainMs < 200)
                lines.add("pages=$n $label total=${ms}ms mainThreadAfterTap=${mainMs}ms files=${r.files.size} size=${r.files.sumOf { it.length() } / 1024}KB")
            }
            timed(false, "pdf")
            timed(false, "pdf-cached-reshare")
            timed(true, "jpg")
        }
        // Cancel a 50-page export after its first page.
        val vm = AppViewModel(app)
        val doc = perf.seedPages(vm, 50)
        vm.share(doc.id, ExportOptions(PageSize.A4, Quality.HIGH), asJpeg = false)
        idle(30_000) { (vm.exportState.value?.page ?: 0) >= 1 }
        val c0 = System.nanoTime()
        vm.cancelExport()
        idle(30_000) { vm.exportState.value == null }
        val cancelMs = (System.nanoTime() - c0) / 1_000_000
        lines.add("pages=50 cancel after page 1: stopped in ${cancelMs}ms")
        assertTrue("cancel took $cancelMs ms", cancelMs < 3_000)
        lines.forEach { println("QC-TIMING $it") }
        File("build/qc-timings.txt").writeText(lines.joinToString("\n") + "\n")
    }
}
