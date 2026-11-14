package com.fastkiwi.scan

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.fastkiwi.scan.core.Filter
import com.fastkiwi.scan.core.PageSize
import com.fastkiwi.scan.core.Pt
import com.fastkiwi.scan.data.ScanDocument
import com.fastkiwi.scan.data.ScanPage
import com.fastkiwi.scan.data.DocumentStore
import com.fastkiwi.scan.export.ExportOptions
import com.fastkiwi.scan.export.Exporter
import com.fastkiwi.scan.export.Quality
import com.fastkiwi.scan.imaging.BitmapIO
import com.fastkiwi.scan.imaging.PageRenderer
import com.fastkiwi.scan.ui.Screen
import kotlinx.coroutines.runBlocking
import org.apache.pdfbox.Loader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Runs the real app UI on the JVM (Robolectric with native Skia graphics):
 * a smoke test of every main screen plus the image pipeline, and it saves
 * screenshots to kiwi-scan/screenshots/.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "ar-w400dp-h860dp-xxhdpi")
class AppUiTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val outDir = File("../screenshots").apply { mkdirs() }

    /** A "photo": dark desk with a tilted white page carrying text lines and a heading. */
    private fun fakePhoto(w: Int, h: Int, page: List<Pt>, seed: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.rgb(84, 70, 58))
        val path = Path().apply {
            moveTo(page[0].x, page[0].y); page.drop(1).forEach { lineTo(it.x, it.y) }; close()
        }
        // Paper with a light shadow gradient.
        c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = android.graphics.LinearGradient(0f, 0f, w.toFloat(), 0f, Color.rgb(236, 234, 228), Color.rgb(170, 168, 160), android.graphics.Shader.TileMode.CLAMP)
        })
        c.save()
        c.clipPath(path)
        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(35, 35, 45); strokeWidth = 7f }
        val cx = page.map { it.x }.average().toFloat()
        val top = page.minOf { it.y } + h * 0.12f
        val accent = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(30, 90, 160) }
        c.drawRect(cx - w * 0.22f, top, cx + w * 0.10f, top + h * 0.035f, accent)
        for (i in 0 until 14) {
            val y = top + h * 0.08f + i * h * 0.04f
            val len = if ((i + seed) % 4 == 3) 0.18f else 0.30f
            c.drawLine(cx - w * 0.25f, y, cx - w * 0.25f + w * len * 1.6f, y, ink)
        }
        c.restore()
        return bmp
    }

    private fun seed(): ScanDocument {
        val vm = rule.activity.vm
        val store = vm.store
        var doc = store.create("فاتورة الكهرباء")
        val pages = ArrayList<ScanPage>()
        val shapes = listOf(
            listOf(Pt(170f, 140f), Pt(830f, 190f), Pt(880f, 1180f), Pt(110f, 1120f)),
            listOf(Pt(120f, 120f), Pt(860f, 100f), Pt(840f, 1200f), Pt(150f, 1230f)),
            listOf(Pt(200f, 180f), Pt(800f, 160f), Pt(860f, 1150f), Pt(140f, 1180f)),
        )
        val filters = listOf(Filter.ENHANCED, Filter.WHITEN, Filter.BW)
        for ((i, shape) in shapes.withIndex()) {
            val photo = fakePhoto(1000, 1333, shape, i)
            val id = DocumentStore.newId()
            BitmapIO.saveJpeg(photo, store.srcFile(doc.id, id), 92)
            // The real detector must find the page in the synthetic photo.
            val corners = BitmapIO.detect(photo)
            assertNotNull("page $i not detected", corners)
            photo.recycle()
            val page = ScanPage(id, corners!!, 0, filters[i])
            runBlocking { PageRenderer.renderAndSave(store, doc.id, page) }
            pages.add(page)
        }
        doc = store.save(doc.copy(pages = pages))
        store.save(store.create("عقد الإيجار").let { d ->
            val p = store.copyPage(doc.id, pages[1], d.id); d.copy(pages = listOf(p))
        })
        store.save(store.create("Passport copy").let { d ->
            val p = store.copyPage(doc.id, pages[2], d.id); d.copy(pages = listOf(p))
        })
        rule.runOnUiThread { vm.refresh() }
        rule.waitUntil(10_000) { vm.docs.value.size >= 3 }
        return doc
    }

    private fun shot(name: String) {
        rule.waitForIdle()
        Thread.sleep(1500) // let async image decodes finish
        rule.waitForIdle()
        saveWindows(File(outDir, "$name.png"))
    }

    @Test
    fun mainScreensRenderAndPipelineWorks() {
        val doc = seed()
        val vm = rule.activity.vm
        rule.onNodeWithText("فاتورة الكهرباء").assertExists()
        shot("01-home-ar")

        rule.runOnUiThread { vm.open(Screen.Editor(doc.id)) }
        rule.onNodeWithText("تصدير").assertExists()
        shot("02-document-pages-ar")

        rule.runOnUiThread { vm.open(Screen.Page(doc.id, 0)) }
        rule.onNodeWithText("إزالة الظلال").assertExists()
        shot("03-page-filters-ar")

        rule.runOnUiThread { vm.open(Screen.Crop(doc.id, doc.pages[0].id)) }
        rule.waitUntil(5_000) { rule.onAllNodesWithText("كشف تلقائي", substring = true).fetchSemanticsNodes().isNotEmpty() }
        Thread.sleep(500)
        shot("04-crop-corners-ar")

        rule.runOnUiThread { vm.back(); vm.back() }
        rule.onNodeWithText("تصدير").performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithText("حماية بكلمة مرور").fetchSemanticsNodes().isNotEmpty() }
        shot("05-export-options-ar")
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }

        rule.runOnUiThread { while (vm.back()) Unit; vm.open(Screen.Settings) }
        rule.onNodeWithText("الخصوصية").assertExists()
        shot("06-settings-privacy-ar")

        rule.runOnUiThread { vm.back(); vm.open(Screen.Camera(null)) }
        rule.waitForIdle()
        shot("07-camera-permission-ar")
    }

    @Test
    fun exportsSmallPdfFromRenderedPages() {
        val doc = seed()
        val ctx = rule.activity
        val exporter = Exporter(ctx, ctx.vm.store)
        val file = runBlocking { exporter.exportPdf(doc, ExportOptions(PageSize.A4, Quality.SMALL, ocr = false)) { _, _ -> } }
        assertTrue(file.length() > 1000)
        Loader.loadPDF(file).use { pdf ->
            assertEquals(3, pdf.numberOfPages)
            assertEquals(595.28f, pdf.getPage(0).mediaBox.width, 0.01f)
        }
        // The B&W page is stored 1-bit, so the whole 3-page file stays small.
        assertTrue("pdf too big: ${file.length()}", file.length() < 600_000)
        val pw = runBlocking { exporter.exportPdf(doc, ExportOptions(PageSize.LETTER, Quality.BALANCED, password = "Kiwi-123")) { _, _ -> } }
        Loader.loadPDF(pw, "Kiwi-123").use { assertEquals(3, it.numberOfPages) }
        File(outDir, "sample-export-small.txt").writeText("3-page A4 'Small' export: ${file.length()} bytes\n")
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w400dp-h860dp-xxhdpi")
class AppUiEnglishTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun englishHomeRenders() {
        rule.onNodeWithText("Kiwi Scan").assertExists()
        rule.onNodeWithText("Scan", useUnmergedTree = true).assertExists()
        rule.waitForIdle()
        File("../screenshots").mkdirs()
        saveWindows(File("../screenshots/08-home-en.png"))
    }
}

/**
 * Draws every window of the app (activity plus dialogs/sheets) in z-order into
 * one PNG, using a software canvas (works under Robolectric native graphics).
 */
@Suppress("UNCHECKED_CAST")
internal fun saveWindows(file: File) {
    val wmg = Class.forName("android.view.WindowManagerGlobal").getMethod("getInstance").invoke(null)
    val names = wmg.javaClass.getMethod("getViewRootNames").invoke(wmg) as Array<String>
    val roots = names.map { wmg.javaClass.getMethod("getRootView", String::class.java).invoke(wmg, it) as android.view.View }
        .filter { it.width > 0 && it.height > 0 && it.isShown }
    val base = roots.maxBy { it.width * it.height }
    val out = Bitmap.createBitmap(base.width, base.height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(out)
    canvas.drawColor(Color.WHITE)
    val loc = IntArray(2)
    for (v in roots) {
        v.getLocationOnScreen(loc)
        canvas.save()
        canvas.translate(loc[0].toFloat(), loc[1].toFloat())
        v.draw(canvas)
        canvas.restore()
    }
    file.outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
}
