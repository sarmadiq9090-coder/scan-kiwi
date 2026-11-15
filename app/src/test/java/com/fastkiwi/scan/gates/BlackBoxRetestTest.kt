// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.gates

import android.Manifest
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.fastkiwi.scan.core.QualityWarning
import com.fastkiwi.scan.imaging.warnings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The findings of the black-box re-test (docs/BLACKBOX-2.0.md, «إعادة الاختبار 2026-11-15»),
 * driven through the same command interpreter as bb.sh.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "ar-w400dp-h860dp-xxhdpi", shadows = [ShadowWorldPdfRenderer::class, ShadowWorldPdfPage::class])
class BlackBoxRetestTest {
    @get:Rule
    val rule = createEmptyComposeRule()

    @get:Rule
    val tmp = TemporaryFolder()

    private var bb: BlackBox? = null
    private val app get() = RuntimeEnvironment.getApplication()
    private val store get() = com.fastkiwi.scan.data.DocumentStore(app.filesDir)

    @After
    fun tearDown() {
        bb?.close()
        store.list().forEach { store.delete(it.id) }
        app.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        shadowOf(app).denyPermissions(Manifest.permission.CAMERA)
    }

    private fun BlackBox.ok(cmd: String): BlackBox.Step {
        val s = execute(cmd)
        val crash = dir.listFiles()?.filter { it.name.endsWith("-crash.txt") }?.joinToString("\n") { it.readText().take(3000) } ?: ""
        assertTrue("step ${s.number} «$cmd» failed:\n${s.text}\n$crash", s.ok)
        assertTrue("app stopped at «$cmd»:\n${s.text}\n$crash", s.events.none { it.startsWith("APP STOPPED") })
        return s
    }

    private fun session(lang: String = "ar"): BlackBox = BlackBox(rule, File(tmp.root, "session-$lang")).also { bb = it; it.start(lang) }

    private fun BlackBox.openCamera() {
        ok("tap \"مسح\"")
        ok("permission allow")
    }

    private fun noIncompleteWarning() {
        for (p in store.list().flatMap { it.pages }) {
            assertTrue("a split page warns «cut off»: ${p.quality}", p.quality?.warnings()?.contains(QualityWarning.INCOMPLETE) != true)
        }
    }

    /** Task 3 (U2): a two-page photo picked from the gallery in Book mode goes to the split screen; split pages do not warn «cut off» (U3). */
    @Test
    fun aSpreadFromTheGalleryInBookModeIsSplit() {
        val box = session()
        box.ok("gallery_has book.jpg book-spread")
        box.openCamera()
        box.ok("tap \"كتاب\"")
        val picked = run { box.ok("tap \"اختيار من المعرض\""); box.ok("pick \"book.jpg\"") }
        assertTrue(picked.text, picked.text.contains("تقسيم وإضافة صفحتين"))
        assertEquals(0, store.list().sumOf { it.pages.size })
        box.ok("tap \"تقسيم وإضافة صفحتين\"")
        assertEquals(2, store.list().single().pages.size)
        noIncompleteWarning()
    }

    /**
     * Task 3 (U2): with a page already in view, auto-capture in Document mode fired before the user could
     * choose Book, and a stray unsplit page was added. It waits after the camera opens and after a mode change.
     */
    @Test
    fun autoCaptureWaitsUntilTheModeCanBeChosen() {
        val box = session()
        box.ok("camera_sees book-spread")
        box.ok("tap \"مسح\"")
        val opened = box.ok("permission allow")
        assertTrue(opened.text, opened.events.none { it.contains("photo taken") })
        val book = box.ok("tap \"كتاب\"")
        val split = if (book.text.contains("تقسيم وإضافة صفحتين")) book else box.ok("wait 3000")
        assertTrue(split.text, split.text.contains("تقسيم وإضافة صفحتين"))
        assertEquals("no stray page", 0, store.list().sumOf { it.pages.size })
        box.ok("tap \"تقسيم وإضافة صفحتين\"")
        assertEquals(2, store.list().single().pages.size)
        noIncompleteWarning()
    }

    /** Task 3 (U2): «تعذّر استخدام الكاميرا» on a camera visit while only the desk is in view. */
    @Test
    fun theCameraIsAvailableWithOnlyTheDeskInView() {
        val box = session()
        box.openCamera()
        val first = box.ok("wait 2000")
        assertTrue(first.text, !first.text.contains("تعذّر استخدام الكاميرا"))
        box.ok("back")
        box.ok("tap \"مسح\"")
        val again = box.ok("wait 2000")
        assertTrue(again.text, !again.text.contains("تعذّر استخدام الكاميرا") && again.text.contains("وجّه الكاميرا نحو الورقة"))
    }

    /** Task 4 (U2): «إزالة الوهج» with the second photo from the gallery fuses it into the page instead of adding one. */
    @Test
    fun aGlareSecondPhotoFromTheGalleryIsFusedIntoThePage() {
        val box = session()
        box.ok("gallery_has invoice.jpg invoice")
        box.ok("tap \"استيراد صور أو PDF\"")
        box.ok("pick \"invoice.jpg\"")
        val pageId = store.list().single().pages.single().id
        box.ok("tap \"الصفحة ١ من ١\"")
        box.ok("tap \"خيارات أخرى\"")
        box.ok("tap \"إزالة الوهج\"")
        box.ok("permission allow")
        box.ok("tap \"اختيار من المعرض\"")
        val fused = box.ok("pick \"invoice.jpg\"")
        val pages = store.list().single().pages
        assertEquals("a page was added instead of fused:\n${fused.text}", 1, pages.size)
        assertTrue("the page was not replaced", pages.single().id != pageId)
        assertTrue(fused.text, fused.events.any { it.startsWith("announced:") && it.contains("خُفّف اللمعان") })
    }

    /** Task 1 (U3): moving a document into a new folder announced «لا توجد نتائج · نُقل إلى «عقود»». */
    @Test
    fun movingTheLastDocumentIntoAFolderSaysNoResultsNowhere() {
        val box = session()
        box.ok("gallery_has memo.jpg mixed-memo")
        box.ok("tap \"استيراد صور أو PDF\"")
        box.ok("pick \"memo.jpg\"")
        box.ok("back")
        box.ok("tap \"خيارات أخرى لـ\"")
        box.ok("tap \"نقل إلى مجلد\"")
        box.ok("tap \"مجلد جديد\"")
        box.ok("type \"اسم المجلد\" \"عقود\"")
        val moved = box.ok("tap \"إنشاء\"")
        val said = (moved.events + box.ok("wait 5000").events).filter { it.startsWith("announced:") }
        assertTrue(moved.text, said.any { it.contains("عقود") })
        assertTrue("misleading: $said", said.none { it.contains("لا توجد نتائج") })
        assertTrue(moved.text, !moved.text.contains("لا توجد نتائج"))
    }

    /** Task 5 (U3): «تم الحفظ (٣ ك.ب)» for 2007 bytes, repeated on later screens. */
    @Test
    fun theSavedMessageHasTheRightSizeAndIsSaidOnce() {
        val box = session()
        box.ok("gallery_has invoice.jpg invoice")
        box.ok("tap \"استيراد صور أو PDF\"")
        box.ok("pick \"invoice.jpg\"")
        box.ok("wait 3000")
        box.ok("tap \"تصدير\"")
        box.ok("tap \"نص TXT\"")
        val saved = box.ok("tap \"حفظ في الجهاز\"")
        val bytes = World.savedDir().listFiles()!!.single { it.name.endsWith(".txt") }.length()
        val kb = java.text.NumberFormat.getIntegerInstance(java.util.Locale.forLanguageTag("ar")).format(maxOf(1L, (bytes + 500) / 1000))
        val said = saved.events.filter { it.startsWith("announced:") && it.contains("تم الحفظ") }
        assertTrue("$bytes bytes: $said", said.single().contains("تم الحفظ ($kb ك.ب)"))
        val later = listOf(box.ok("wait 2000"), box.ok("back"), box.ok("back"), box.ok("screenshot"))
        val again = later.flatMap { it.events }.filter { it.startsWith("announced:") && it.contains("تم الحفظ") }
        assertTrue("said again: $again", again.isEmpty())
    }

    /**
     * Developer report (content loss): the invoice's dark title bar was left outside the page, so «فاتورة» could
     * not be found. The page now keeps the band, and the title is searchable.
     */
    @Test
    fun theInvoiceTitleInItsDarkBandIsInThePage() {
        val box = session()
        box.ok("gallery_has invoice.jpg invoice")
        box.ok("tap \"استيراد صور أو PDF\"")
        box.ok("pick \"invoice.jpg\"")
        box.ok("wait 3000")
        box.ok("back")
        box.ok("tap \"ابحث في الأسماء والنص\"")
        val hits = box.ok("type \"ابحث\" \"فاتورة\"")
        assertTrue(hits.text, hits.text.contains("في نص الصفحات"))
    }

    /** Task 7 (U2): at 200 % text the format labels were cut («نص T…»); they are a vertical list now. */
    @Test
    fun theFormatsAtDoubleTextSizeAreAVerticalList() {
        val box = session()
        box.ok("gallery_has memo.jpg mixed-memo")
        box.ok("tap \"استيراد صور أو PDF\"")
        box.ok("pick \"memo.jpg\"")
        box.ok("fontscale 2.0")
        val sheet = box.ok("tap \"تصدير\"")
        fun bounds(role: String, label: String) = Regex("""$role "$label"[^\n]*bounds \[(\d+),(\d+)]\[(\d+),(\d+)]""").find(sheet.text)
            ?.groupValues?.drop(1)?.map { it.toInt() } ?: error("no $role «$label»:\n${sheet.text}")
        val rows = listOf("PDF", "صور JPG", "نص TXT").map { bounds("radio button", it) }
        assertTrue("not stacked: $rows", rows.zipWithNext().all { (a, b) -> b[1] >= a[3] })
        assertTrue("not full width: $rows", rows.all { it[2] - it[0] > 1200 * 3 / 4 })
        // Share and Save: equal height, side by side.
        val share = bounds("button", "مشاركة")
        val save = bounds("button", "حفظ في الجهاز")
        assertEquals("Share $share, Save $save", share[3] - share[1], save[3] - save[1])
    }
}
