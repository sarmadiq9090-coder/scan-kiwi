// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.gates

import android.Manifest
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import org.junit.After
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
import java.io.File

/**
 * The black-box tasks the code-blind tester (gate 6.4, docs/BLACKBOX-2.0.md) could not finish,
 * driven through the same command interpreter as bb.sh.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "ar-w400dp-h860dp-xxhdpi", shadows = [ShadowWorldPdfRenderer::class, ShadowWorldPdfPage::class])
class BlackBoxTasksTest {
    @get:Rule
    val rule = createEmptyComposeRule()

    @get:Rule
    val tmp = TemporaryFolder()

    private var bb: BlackBox? = null

    @After
    fun tearDown() {
        bb?.close()
        val app = RuntimeEnvironment.getApplication()
        val store = com.fastkiwi.scan.data.DocumentStore(app.filesDir)
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

    private fun BlackBox.importPage(file: String, page: String, en: Boolean = false) {
        ok("gallery_has $file $page")
        ok("tap \"${if (en) "Import images or PDF" else "استيراد صور أو PDF"}\"")
        ok("pick \"$file\"")
    }

    /** BLACKBOX-2.0 #1 (U1): «مجلد جديد» froze the driver with AppNotIdleException (the dialog relaid itself out for ever). */
    @Test
    fun aFolderCanBeCreatedFromHomeAndFromMoveToFolder() {
        val box = session()
        box.importPage("memo.jpg", "mixed-memo")
        box.ok("back")
        val dialog = box.ok("tap \"مجلد جديد\"")
        assertTrue(dialog.text, dialog.text.contains("text field \"اسم المجلد\""))
        box.ok("type \"اسم المجلد\" \"عقود\"")
        val home = box.ok("tap \"إنشاء\"")
        assertTrue(home.text, home.text.contains("عقود"))
        // The same dialog from «نقل إلى مجلد»: the document goes into the new folder.
        box.ok("tap \"خيارات أخرى لـ\"")
        box.ok("tap \"نقل إلى مجلد\"")
        box.ok("tap \"مجلد جديد\"")
        box.ok("type \"اسم المجلد\" \"فواتير\"")
        val moved = box.ok("tap \"إنشاء\"")
        assertTrue(moved.text, moved.text.contains("فواتير"))
        val store = com.fastkiwi.scan.data.DocumentStore(RuntimeEnvironment.getApplication().filesDir)
        val folders = com.fastkiwi.scan.data.FolderStore(RuntimeEnvironment.getApplication().filesDir).list()
        val target = folders.first { it.name == "فواتير" }
        assertTrue("document not moved: ${store.list().map { it.folderId }}", store.list().single().folderId == target.id)
    }

    /**
     * BLACKBOX-2.0 #3 (U1) and #7 (U2): after a language and theme change and a relaunch the camera said
     * "Camera unavailable" and the shutter did nothing; the Scan button had no name.
     */
    @Test
    fun theCameraWorksAfterLanguageThemeChangeAndRelaunch() {
        val box = session()
        box.ok("locale en")
        box.ok("darkmode on")
        val asked = box.ok("tap \"Scan\"")
        assertTrue(asked.text, asked.events.any { it.startsWith("SYSTEM PERMISSION DIALOG") })
        box.ok("permission allow")
        val shot = box.ok("camera_sees en-letter")
        assertTrue(shot.text, shot.events.any { it.contains("photo taken automatically") })
        assertTrue(shot.text, !shot.text.contains("Camera unavailable"))
        // Close the app, open it from the launcher, and take a photo with the shutter.
        box.ok("tap \"Done\"")
        repeat(3) { if (box.activity() != null) box.execute("back") }
        box.ok("launch")
        box.ok("tap \"Scan\"")
        box.ok("camera_sees invoice")
        val manual = box.ok("tap \"Take photo\"")
        assertTrue(manual.text, manual.events.any { it.contains("photo taken") })
        assertTrue(manual.text, !manual.text.contains("Camera unavailable"))
    }

    /** BLACKBOX-2.0 #3, #4: book mode says what to do, takes a spread by itself and splits it into two pages. */
    @Test
    fun bookModeGuidesTakesAndSplitsASpread() {
        val box = session()
        box.ok("tap \"مسح\"")
        box.ok("permission allow")
        val book = box.ok("tap \"كتاب\"")
        assertTrue(book.text, book.text.contains("ضع الكتاب مفتوحاً"))
        val shot = box.ok("camera_sees book-spread")
        assertTrue(shot.text, shot.events.any { it.contains("photo taken") })
        val split = if (shot.text.contains("تقسيم وإضافة صفحتين")) shot else box.ok("tap \"التقاط صورة\"")
        assertTrue(split.text, split.text.contains("تقسيم وإضافة صفحتين"))
        val added = box.ok("tap \"تقسيم وإضافة صفحتين\"")
        assertTrue(added.text, added.text.contains("تم، عدد الصفحات ٢"))
        val store = com.fastkiwi.scan.data.DocumentStore(RuntimeEnvironment.getApplication().filesDir)
        assertTrue(store.list().single().pages.size == 2)
    }

    /**
     * BLACKBOX-2.0 #2 (U1), #5 and #6 (U2): with the simulated OCR the text can be searched, viewed from the
     * page and from the document, and exported as TXT from the export sheet; «إزالة الوهج» is in the page editor.
     */
    @Test
    fun textCanBeSearchedViewedAndExportedAsTxt() {
        val box = session()
        box.importPage("invoice.jpg", "invoice")
        box.ok("wait 3000")
        box.ok("back")
        box.ok("tap \"ابحث في الأسماء والنص\"")
        val hits = box.ok("type \"ابحث\" \"Toner\"")
        assertTrue(hits.text, hits.text.contains("في نص الصفحات"))
        val home = box.ok("back")
        val doc = Regex(""""(مسح \d{4}-\d\d-\d\d \d\d\.\d\d)""").find(home.text)!!.groupValues[1]
        box.ok("tap \"$doc\"")
        // From the document's menu.
        box.ok("tap \"خيارات أخرى\"")
        val text = box.ok("tap \"عرض النص وتصحيحه\"")
        assertTrue(text.text, text.text.contains("Toner cartridge"))
        box.ok("back")
        // From the page editor: the text screen and glare fusion, by name.
        box.ok("tap \"الصفحة ١ من ١\"")
        val menu = box.ok("tap \"خيارات أخرى\"")
        assertTrue(menu.text, menu.text.contains("عرض النص وتصحيحه") && menu.text.contains("إزالة الوهج"))
        box.ok("back")
        box.ok("back")
        // TXT in the export sheet.
        box.ok("tap \"تصدير\"")
        box.ok("tap \"نص TXT\"")
        box.ok("whatsapp_target")
        val shared = box.ok("tap \"مشاركة\"")
        assertTrue(shared.text, shared.events.any { it.startsWith("WhatsApp (simulated) received") && it.contains("text/plain") })
    }

    /** BLACKBOX-2.0 #9 (U2): at 200 % text Share and Save sit side by side, so the options keep most of the sheet. */
    @Test
    fun theExportSheetAtDoubleTextSizeKeepsItsFooterSmall() {
        val box = session()
        box.importPage("memo.jpg", "mixed-memo")
        box.ok("fontscale 2.0")
        val sheet = box.ok("tap \"تصدير\"")
        assertTrue(sheet.text, sheet.text.contains("radio button \"PDF\"") && sheet.text.contains("نص TXT"))
        fun top(label: String) = Regex("""button "$label"[^\n]*bounds \[\d+,(\d+)]\[\d+,(\d+)]""").find(sheet.text)!!.groupValues.let { it[1].toInt() to it[2].toInt() }
        val share = top("مشاركة")
        val save = top("حفظ في الجهاز")
        assertTrue("not side by side: $share $save", share.first < save.second && save.first < share.second)
        assertTrue("footer taller than a sixth of the screen: $share $save", 2580 - minOf(share.first, save.first) < 2580 / 6)
    }

    /** BLACKBOX-2.0 #13, #14, #15 (U3): the offline message is announced with a hint; names follow the language. */
    @Test
    fun offlineIsAnnouncedAndTheDefaultNameFollowsTheLanguage() {
        val box = session()
        box.importPage("memo.jpg", "mixed-memo")
        box.ok("back")
        val home = box.ok("locale en")
        assertTrue(home.text, Regex(""""Scan \d{4}-\d\d-\d\d \d\d\.\d\d""").containsMatchIn(home.text))
        assertTrue(home.text, !home.text.contains("مسح 20"))
        box.ok("tap \"Settings\"")
        var st = box.ok("screenshot")
        var n = 0
        while (!st.text.contains("\"Updates") && n++ < 6) st = box.ok("scroll down")
        box.ok("tap \"Updates\"")
        val check = box.ok("tap \"Check for updates\"")
        val all = check.events + box.ok("wait 2500").events
        assertTrue(all.joinToString(" | "), all.any { it.startsWith("announced:") && it.contains("No internet connection. Connect to Wi-Fi or mobile data") })
    }
}
