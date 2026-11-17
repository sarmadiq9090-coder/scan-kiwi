// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.gates

import android.Manifest
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import org.junit.After
import org.junit.Assume.assumeTrue
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
 * QC gate 6.5 (docs/QC-2.0.0.md): sampled screenshots, each main screen once in Arabic light, and Home, Export,
 * the document and Updates in English dark and Arabic at 200 % text, driven like the black-box tester.
 * Runs only with KIWI_QC=1; writes `build/qc/<name>.png` and `.txt` (what TalkBack can reach).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "ar-w400dp-h860dp-xxhdpi", shadows = [ShadowWorldPdfRenderer::class, ShadowWorldPdfPage::class])
class QcShotsTest {
    @get:Rule
    val rule = createEmptyComposeRule()

    @get:Rule
    val tmp = TemporaryFolder()

    private var bb: BlackBox? = null
    private val out = File(System.getProperty("user.dir"), "build/qc").apply { mkdirs() }
    private val log = StringBuilder()

    @After
    fun tearDown() {
        bb?.close()
        val app = RuntimeEnvironment.getApplication()
        val store = com.fastkiwi.scan.data.DocumentStore(app.filesDir)
        store.list().forEach { store.delete(it.id) }
        app.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        shadowOf(app).denyPermissions(Manifest.permission.CAMERA)
        File(out, "qc-log.txt").writeText(log.toString())
    }

    private fun BlackBox.run(cmd: String): BlackBox.Step = execute(cmd).also { log.append("${it.number} ${if (it.ok) "ok" else "FAILED"} $cmd\n") }

    private fun BlackBox.shot(name: String, cmd: String = "screenshot") {
        val s = run(cmd)
        s.png.copyTo(File(out, "$name.png"), overwrite = true)
        File(out, "$name.txt").writeText(s.text)
    }

    private fun BlackBox.home() { repeat(6) { if (!run("screenshot").text.contains("ابحث في الأسماء والنص") && !run("screenshot").text.contains("Search names and text")) run("back") } }

    @Test(timeout = 1_800_000)
    fun sampledScreens() {
        assumeTrue("QC screenshots run with KIWI_QC=1", System.getenv("KIWI_QC") == "1")
        val box = BlackBox(rule, File(tmp.root, "qc")).also { bb = it; it.start("ar") }
        box.shot("01-home-empty-ar")
        box.run("gallery_has invoice.jpg invoice")
        box.run("gallery_has memo.jpg mixed-memo")
        box.run("tap \"استيراد صور أو PDF\"")
        box.run("pick \"invoice.jpg\" \"memo.jpg\"")
        box.run("wait 3000")
        box.shot("02-document-ar")
        box.run("tap \"الصفحة ١ من ٢\"")
        box.shot("03-page-ar")
        box.shot("04-crop-ar", "tap \"قص\"")
        box.run("back")
        box.shot("05-signature-ar", "tap \"توقيع\"")
        box.run("back")
        box.run("tap \"خيارات أخرى\"")
        box.shot("06-text-ar", "tap \"عرض النص وتصحيحه\"")
        box.run("back"); box.run("back")
        box.shot("07-export-ar", "tap \"تصدير\"")
        box.run("back"); box.run("back")
        box.shot("08-home-ar")
        box.run("tap \"ابحث في الأسماء والنص\"")
        box.shot("09-search-ar", "type \"ابحث\" \"Toner\"")
        box.run("back")
        box.home()
        box.run("tap \"مسح\"")
        box.shot("10-camera-ar", "permission allow")
        box.run("tap \"كتاب\"")
        box.run("camera_sees book-spread")
        box.shot("11-book-split-ar", "wait 3000")
        box.run("tap \"تقسيم وإضافة صفحتين\"")
        box.run("camera_sees nothing")
        box.run("tap \"إغلاق الكاميرا\"")
        box.home()
        box.shot("12-settings-ar", "tap \"الإعدادات\"")
        var st = box.run("screenshot"); var n = 0
        while (!st.text.contains("\"التحديثات") && n++ < 6) st = box.run("scroll down")
        box.shot("13-updates-ar", "tap \"التحديثات\"")
        box.run("back")
        st = box.run("screenshot"); n = 0
        while (!st.text.contains("\"أبلغنا عن مشكلة أو اقتراح") && n++ < 8) st = box.run("scroll down")
        box.shot("14-feedback-ar", "tap \"أبلغنا عن مشكلة أو اقتراح\"")
        box.home()
        box.shot("15-pdf-tools-ar", "tap \"فتح أدوات PDF\"")
        box.home()
        // English, dark.
        box.run("locale en"); box.run("darkmode on")
        box.home()
        box.shot("20-home-en-dark")
        val home = box.run("screenshot")
        Regex(""""(Scan \d{4}-\d\d-\d\d \d\d\.\d\d[^"]*)"""").find(home.text)?.groupValues?.get(1)?.let { box.run("tap \"$it\"") }
        box.shot("21-document-en-dark")
        box.shot("22-export-en-dark", "tap \"Export\"")
        box.run("back"); box.home()
        box.run("tap \"Settings\"")
        st = box.run("screenshot"); n = 0
        while (!st.text.contains("\"Updates") && n++ < 6) st = box.run("scroll down")
        box.shot("23-updates-en-dark", "tap \"Updates\"")
        box.home()
        // Arabic, light, 200 % text.
        box.run("locale ar"); box.run("darkmode off"); box.run("fontscale 2.0")
        box.home()
        box.shot("30-home-200-ar")
        val home2 = box.run("screenshot")
        Regex(""""(مسح \d{4}-\d\d-\d\d \d\d\.\d\d[^"]*)"""").find(home2.text)?.groupValues?.get(1)?.let { box.run("tap \"$it\"") }
        box.shot("31-document-200-ar")
        box.shot("32-export-200-ar", "tap \"تصدير\"")
        box.shot("32b-export-200-ar-scrolled", "scroll down")
        box.run("back"); box.home()
        box.run("tap \"الإعدادات\"")
        st = box.run("screenshot"); n = 0
        while (!st.text.contains("\"التحديثات") && n++ < 8) st = box.run("scroll down")
        box.shot("33-updates-200-ar", "tap \"التحديثات\"")
    }
}
