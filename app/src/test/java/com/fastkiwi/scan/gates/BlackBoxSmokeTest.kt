// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.gates

import android.Manifest
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import org.junit.After
import org.junit.Assert.assertEquals
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
 * Proves the black-box session works end to end on the current app, with the same
 * command interpreter bb.sh drives: scan a simulated page, finish, open the
 * document, share it as PDF to the simulated WhatsApp and read the size from the
 * event. Once in Arabic, once in English at 200 % text size.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "ar-w400dp-h860dp-xxhdpi", shadows = [ShadowWorldPdfRenderer::class, ShadowWorldPdfPage::class])
class BlackBoxSmokeTest {
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
        val crash = dir.listFiles()?.filter { it.name.endsWith("-crash.txt") }?.joinToString("\n") { it.readText().take(4000) } ?: ""
        assertTrue("step ${s.number} «$cmd» failed:\n${s.text}\n$crash", s.ok)
        assertTrue("no screenshot for «$cmd»", s.png.length() > 1000)
        assertTrue("app crashed at «$cmd»:\n${s.text}", s.events.none { it.startsWith("APP STOPPED") })
        return s
    }

    private fun scanShareFlow(lang: String, fontScale: Float?) {
        val box = BlackBox(rule, File(tmp.root, "session-$lang"))
        bb = box
        val start = box.start(lang)
        assertTrue(start.txt.readText().contains("Layer 1: app window"))
        if (fontScale != null) box.ok("fontscale $fontScale")
        val en = lang == "en"
        box.ok("pages").also { assertTrue(it.text.contains("invoice")) }
        // Home ▸ Scan: the system asks for the camera. The Scan button has no accessible
        // name in the tree (finding F1), so it is tapped by its number, as a tester would.
        val home0 = box.ok("screenshot")
        val scanLabel = if (en) "Scan" else "مسح"
        val scanAt = Regex("\\[(\\d+)] button(?: \"$scanLabel\")?  actions: tap  bounds \\[\\d+,(\\d+)]").findAll(home0.text)
            .maxByOrNull { it.groupValues[2].toInt() }!!.groupValues[1]
        val asked = box.ok("tap @$scanAt")
        assertTrue("no permission dialog:\n${asked.text}", asked.events.any { it.startsWith("SYSTEM PERMISSION DIALOG") })
        box.ok("permission allow")
        // Point the camera at a page; auto-capture takes it.
        val shot = box.ok("camera_sees ${if (en) "en-letter" else "invoice"}")
        assertTrue("no auto capture:\n${shot.text}", shot.events.any { it.contains("photo taken automatically") })
        // Arabic numbers are shown with Arabic-Indic digits («١»).
        val done = if (en) "Done, pages: 1" else "تم، عدد الصفحات ١"
        assertTrue("Done pill missing:\n${shot.text}", shot.text.contains(done))
        box.ok("tap \"${if (en) "Done" else "تم"}\"")
        // Back to Home and open the document from the list.
        box.ok("back")
        val home = box.ok("screenshot")
        val docLabel = Regex("\"((?:Scan|مسح) \\d{4}-\\d\\d-\\d\\d [^\"·]*)").find(home.text)?.groupValues?.get(1)
        assertTrue("document not listed:\n${home.text}", docLabel != null)
        box.ok("tap \"${docLabel!!.trim()}\"")
        box.ok("tap \"${if (en) "Export" else "تصدير"}\"")
        box.ok("whatsapp_target")
        val shared = box.ok("tap \"${if (en) "Share" else "مشاركة"}\"")
        val got = shared.events.firstOrNull { it.startsWith("WhatsApp (simulated) received") }
        assertTrue("WhatsApp got nothing:\n${shared.text}", got != null)
        assertTrue(got!!, got.contains("application/pdf") && got.contains("PDF with 1 page"))
        val size = Regex("\\((\\d+) bytes\\)").find(got)!!.groupValues[1].toLong()
        assertTrue("PDF size $size", size in 5_000..5_000_000)
        println("BLACKBOX-SMOKE $lang fontScale=${fontScale ?: 1f}: shared PDF $size bytes")
        // A manual shot through the shutter, and a gallery import, also work.
        box.ok("back")
        box.ok("gallery_has receipt.jpg receipt")
        box.ok("tap \"${if (en) "Add pages" else "إضافة صفحات"}\"")
        val picker = box.ok("tap \"${if (en) "Import images or PDF" else "استيراد صور أو PDF"}\"")
        assertTrue("no file picker:\n${picker.text}", picker.events.any { it.startsWith("SYSTEM FILE PICKER") && it.contains("receipt.jpg") })
        val imported = box.ok("pick \"receipt.jpg\"")
        assertTrue("import not announced:\n${imported.text}", imported.events.any { it.contains(if (en) "Imported: 1" else "تم الاستيراد: 1") })
        box.ok("quit")
        assertEquals(true, box.quit)
    }

    @Test
    fun arabicScanToWhatsApp() = scanShareFlow("ar", null)

    @Test
    fun englishAtDoubleTextSize() = scanShareFlow("en", 2.0f)
}
