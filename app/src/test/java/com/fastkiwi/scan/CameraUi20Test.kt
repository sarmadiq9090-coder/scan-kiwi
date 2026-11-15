// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.fastkiwi.scan.core.Filter
import com.fastkiwi.scan.core.Pt
import com.fastkiwi.scan.core.Quad
import com.fastkiwi.scan.core.ReadingOrder
import com.fastkiwi.scan.core.Spine
import com.fastkiwi.scan.data.DocumentStore
import com.fastkiwi.scan.data.PageQuality
import com.fastkiwi.scan.data.ScanPage
import com.fastkiwi.scan.imaging.BitmapIO
import com.fastkiwi.scan.imaging.BookCapture
import com.fastkiwi.scan.imaging.CaptureQuality
import com.fastkiwi.scan.imaging.PageRenderer
import com.fastkiwi.scan.imaging.warnings
import com.fastkiwi.scan.ui.Screen
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.security.MessageDigest

/** 2.0 camera area on the real app UI (Arabic): quality chip, book mode, re-crop from the original. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "ar-w400dp-h860dp-xxhdpi")
class CameraUi20Test {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Before
    fun cleanStore() {
        val vm = rule.activity.vm
        vm.store.list().forEach { vm.store.delete(it.id) }
        rule.runOnUiThread { vm.refresh() }
        rule.waitUntil(10_000) { vm.docs.value.isEmpty() }
    }

    /** A photo of a white page with text lines on a dark desk; [spread] draws two facing pages. */
    private fun photo(w: Int = 1200, h: Int = 900, spread: Boolean = false): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.rgb(70, 52, 40))
        val paper = Paint().apply { color = Color.rgb(238, 236, 230) }
        c.drawRect(w * 0.08f, h * 0.08f, w * 0.92f, h * 0.92f, paper)
        val ink = Paint().apply { color = Color.rgb(40, 40, 45) }
        for (i in 0 until 16) {
            val y = h * 0.14f + i * h * 0.045f
            if (spread) {
                c.drawRect(w * 0.12f, y, w * 0.46f, y + h * 0.012f, ink)
                c.drawRect(w * 0.54f, y, w * 0.88f, y + h * 0.012f, ink)
            } else c.drawRect(w * 0.14f, y, w * 0.86f, y + h * 0.012f, ink)
        }
        if (spread) c.drawRect(w * 0.497f, h * 0.08f, w * 0.503f, h * 0.92f, Paint().apply { color = Color.rgb(150, 148, 140) })
        return bmp
    }

    private fun addPage(quality: PageQuality?): Pair<String, ScanPage> {
        val vm = rule.activity.vm
        val doc = vm.store.create("وثيقة")
        val id = DocumentStore.newId()
        BitmapIO.saveJpeg(photo(), vm.store.srcFile(doc.id, id), 92)
        val page = ScanPage(id, Quad(Pt(0.08f, 0.08f), Pt(0.92f, 0.08f), Pt(0.92f, 0.92f), Pt(0.08f, 0.92f)), 0, Filter.ENHANCED, quality = quality)
        runBlocking { PageRenderer.renderAndSave(vm.store, doc.id, page)?.recycle() }
        vm.store.save(doc.copy(pages = listOf(page)))
        rule.runOnUiThread { vm.refresh() }
        rule.waitUntil(10_000) { vm.docs.value.any { it.doc.id == doc.id } }
        return doc.id to page
    }

    private fun sha(f: File) = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }

    @Test
    fun pageChipOffersRetakeRecropDismissAndNeverBlocks() {
        val vm = rule.activity.vm
        val (docId, page) = addPage(PageQuality(glare = 0.04f))
        assertEquals(listOf(com.fastkiwi.scan.core.QualityWarning.GLARE), page.quality!!.warnings())
        rule.runOnUiThread { vm.open(Screen.Editor(docId)) }
        rule.waitForIdle()
        // The tile badge in the document grid.
        rule.onNodeWithContentDescription("تنبيه جودة: لمعان على الصفحة").assertExists()
        rule.runOnUiThread { vm.open(Screen.Page(docId, 0)) }
        rule.waitForIdle()
        rule.onNodeWithText("لمعان على الصفحة").assertExists()
        rule.onNodeWithText("إعادة الالتقاط").assertHeightIsAtLeast(48.dp)
        rule.onNodeWithText("إعادة القص").assertHeightIsAtLeast(48.dp)
        rule.onNodeWithText("تجاهل").assertHeightIsAtLeast(48.dp).performClick()
        rule.waitUntil(10_000) { vm.store.load(docId)!!.pages[0].quality!!.dismissed }
        rule.waitUntil(5_000) { rule.onAllNodesWithText("لمعان على الصفحة").fetchSemanticsNodes().isEmpty() }
        // Dismissing does not re-render the page (no new revision, OCR caches stay valid).
        assertEquals(page.revision, vm.store.load(docId)!!.pages[0].revision)
        Thread.sleep(200)
        rule.waitForIdle()
        saveWindows(File("../screenshots/40-page-quality-dismissed-ar.png"))
    }

    @Test
    fun recropAndRefilterUseTheKeptOriginal() {
        val vm = rule.activity.vm
        val (docId, page) = addPage(null)
        val src = vm.store.srcFile(docId, page.id)
        val before = sha(src)
        val (w0, _) = BitmapIO.imageSize(vm.store.outFile(docId, page.id))!!
        // Re-crop to the left half, then change the filter: no new photo.
        val half = Quad(Pt(0.08f, 0.08f), Pt(0.5f, 0.08f), Pt(0.5f, 0.92f), Pt(0.08f, 0.92f))
        vm.editPage(docId, page.id) { it.copy(corners = half) }
        rule.waitUntil(15_000) { vm.store.load(docId)!!.pages[0].corners == half }
        vm.editPage(docId, page.id) { it.copy(filter = Filter.BW) }
        rule.waitUntil(15_000) { vm.store.load(docId)!!.pages[0].filter == Filter.BW }
        val (w1, _) = BitmapIO.imageSize(vm.store.outFile(docId, page.id))!!
        assertTrue("half crop $w1 vs $w0", w1 < w0 * 0.6f)
        // Back to the full outline: the whole page returns, from the untouched original.
        vm.editPage(docId, page.id) { it.copy(corners = page.corners, filter = Filter.ORIGINAL) }
        rule.waitUntil(15_000) { vm.store.load(docId)!!.pages[0].filter == Filter.ORIGINAL }
        val (w2, _) = BitmapIO.imageSize(vm.store.outFile(docId, page.id))!!
        assertEquals(w0.toFloat(), w2.toFloat(), 2f)
        assertEquals(before, sha(src))
    }

    @Test
    fun bookModeSplitsInReadingOrder() {
        val vm = rule.activity.vm
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(android.Manifest.permission.CAMERA)
        val doc = vm.store.create("كتاب")
        rule.runOnUiThread { vm.open(Screen.Camera(doc.id, book = true)) }
        rule.waitForIdle()
        rule.onNodeWithText("كتاب").assertExists()
        val bmp = photo(spread = true)
        val det = BitmapIO.detectPage(bmp)
        val pending = BookCapture.prepare(rule.activity, vm.store, doc.id, bmp, det?.quad)
        assertEquals("spine found at the gutter: ${pending.spine}", 0.5f, pending.spine.mid, 0.03f)
        rule.runOnUiThread { vm.bookPending.value = pending }
        rule.waitForIdle()
        rule.onNodeWithText("تقسيم الصفحتين").assertExists()
        rule.onNodeWithText("لا تُسوّى الصفحات المنحنية: اضغط الكتاب برفق ليستوي قرب الكعب.").assertExists()
        rule.onNodeWithContentDescription("خط الكعب").assertExists()
        Thread.sleep(200)
        rule.waitForIdle()
        saveWindows(File("../screenshots/41-book-split-ar.png"))
        rule.runOnUiThread { vm.confirmBookSplit(Spine(0.5f, 0.5f), ReadingOrder.RTL) }
        rule.waitUntil(20_000) { (vm.store.load(doc.id)?.pages?.size ?: 0) == 2 }
        val pages = vm.store.load(doc.id)!!.pages
        // Arabic book: the right-hand page comes first.
        assertTrue("first page is the right half: ${pages[0].corners}", pages[0].corners.tl.x > 0.4f)
        assertTrue(pages[1].corners.tr.x < 0.6f)
        Thread.sleep(500); rule.waitForIdle()
        assertEquals(ReadingOrder.RTL, BookCapture.order(vm.store, doc.id))
        rule.waitUntil(5_000) { (vm.screen as? Screen.Camera)?.captured == 2 }
    }

    @Test
    fun captureReportFlagsGlareOnAStill() {
        val bmp = photo()
        val clean = CaptureQuality.assess(bmp, Quad(Pt(0.08f, 0.08f), Pt(0.92f, 0.08f), Pt(0.92f, 0.92f), Pt(0.08f, 0.92f)), false)
        assertTrue("clean: $clean", clean.warnings().isEmpty())
        val c = Canvas(bmp)
        c.drawCircle(600f, 450f, 110f, Paint().apply { color = Color.WHITE })
        val shiny = CaptureQuality.assess(bmp, Quad(Pt(0.08f, 0.08f), Pt(0.92f, 0.08f), Pt(0.92f, 0.92f), Pt(0.08f, 0.92f)), false)
        assertTrue("glare: $shiny", shiny.warnings().contains(com.fastkiwi.scan.core.QualityWarning.GLARE))
    }

    @Test
    fun highResolutionDecodeStaysWithinBudget() {
        // 50 MP and 200 MP sensors: the decoded bitmap never exceeds 13 MP (A8).
        for ((w, h) in listOf(8160 to 6120, 16320 to 12240, 4000 to 3000)) {
            val s = BitmapIO.sampleSize(w, h, 3200)
            assertTrue("$w x $h -> 1/$s", (w.toLong() / s) * (h.toLong() / s) <= BitmapIO.MAX_DECODE_PIXELS)
        }
        assertEquals(1, BitmapIO.sampleSize(4000, 3000, 3200))
    }
}
