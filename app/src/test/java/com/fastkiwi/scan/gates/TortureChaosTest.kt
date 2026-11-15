// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.gates

import android.Manifest
import android.content.ComponentCallbacks2
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.fastkiwi.scan.R
import com.fastkiwi.scan.core.PageSize
import com.fastkiwi.scan.data.DocumentStore
import com.fastkiwi.scan.export.ExportOptions
import com.fastkiwi.scan.export.Quality
import com.fastkiwi.scan.ui.AppViewModel
import com.fastkiwi.scan.ui.ExportResult
import com.fastkiwi.scan.ui.UiMessage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Chaos: process death in the middle of an export and of a burst of saves (the files
 * on disk at that instant are copied and a new app starts from them), a full or broken
 * disk, the camera permission taken away mid-session, and memory pressure during export.
 */
@Category(TortureSuite::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "ar-w400dp-h860dp-xxhdpi", shadows = [ShadowWorldPdfRenderer::class, ShadowWorldPdfPage::class])
class TortureChaosTest {
    @get:Rule
    val rule = createEmptyComposeRule()

    private val app get() = RuntimeEnvironment.getApplication()
    private val tmp = TemporaryFolder()
    private lateinit var uncaught: Gates.Uncaught

    @Before
    fun setUp() {
        Gates.assumeTorture()
        tmp.create()
        uncaught = Gates.Uncaught().install()
        Gates.wipe(DocumentStore(app.filesDir))
        File(app.cacheDir, "exports").deleteRecursively()
    }

    @After
    fun tearDown() {
        if (!Gates.torture) return
        uncaught.uninstall()
        tmp.delete()
        Gates.wipe(DocumentStore(app.filesDir))
        File(app.cacheDir, "exports").deleteRecursively()
        shadowOf(app).denyPermissions(Manifest.permission.CAMERA)
        assertTrue("uncaught: ${uncaught.errors.map { Gates.stack(it.second) }}", uncaught.errors.isEmpty())
    }

    /** Copies [from] into [to] as it is right now (files being written may be missed, as at a crash). */
    private fun snapshot(from: File, to: File) {
        from.walkTopDown().forEach { f ->
            val rel = f.relativeTo(from)
            val dst = File(to, rel.path)
            if (f.isDirectory) dst.mkdirs() else repeat(3) { attempt ->
                val ok = runCatching { f.copyTo(dst, overwrite = true) }.isSuccess
                if (ok) return@forEach
                if (attempt == 2 && f.exists() && f.name == "doc.json") throw IllegalStateException("could not copy ${f.path}")
                Thread.sleep(2)
            }
        }
    }

    private fun restore(snap: File, target: File) {
        target.deleteRecursively()
        snap.copyRecursively(target, overwrite = true)
    }

    private fun nextMessage(vm: AppViewModel, ms: Long): UiMessage? = runBlocking {
        val end = System.currentTimeMillis() + ms
        var m: UiMessage? = null
        while (m == null && System.currentTimeMillis() < end) {
            Gates.idleMain()
            m = withTimeoutOrNull(20) { vm.messages.first() }
        }
        m
    }

    @Test(timeout = 600_000)
    fun processDeathMidExport() {
        val vm = AppViewModel(app)
        val doc = Gates.seed(vm.store, 30, w = 2000, h = 2667)
        vm.share(doc.id, ExportOptions(PageSize.A4, Quality.HIGH), asJpeg = false)
        assertTrue(Gates.pump(60_000) { (vm.exportState.value?.page ?: 0) >= 5 })
        // The process dies here: this is what is on disk at that moment.
        val snapFiles = tmp.newFolder("files")
        val snapCache = tmp.newFolder("cache")
        snapshot(app.filesDir, snapFiles)
        snapshot(app.cacheDir, snapCache)
        vm.cancelExport()
        Gates.pump(30_000) { vm.exportState.value == null }
        Gates.awaitIdle(vm)
        // Restart from the snapshot.
        restore(snapFiles, app.filesDir)
        restore(snapCache, app.cacheDir)
        val partials = File(app.cacheDir, "exports").listFiles()?.map { it.name } ?: emptyList()
        for (f in File(app.cacheDir, "exports").listFiles() ?: emptyArray()) {
            if (f.name.endsWith(".pdf")) org.apache.pdfbox.Loader.loadPDF(f).use { } // anything named .pdf must be complete
        }
        val vm2 = AppViewModel(app)
        assertTrue(Gates.pump(20_000) { vm2.docs.value.isNotEmpty() })
        Gates.awaitIdle(vm2)
        assertEquals(30, vm2.store.load(doc.id)!!.pages.size)
        assertTrue(Gates.integrity(vm2.store).toString(), Gates.integrity(vm2.store).isEmpty())
        vm2.share(doc.id, ExportOptions(PageSize.A4, Quality.SMALL), asJpeg = false)
        val r = Gates.awaitExport(vm2, 180_000) as? ExportResult.Share
        assertNotNull(r)
        org.apache.pdfbox.Loader.loadPDF(r!!.files.single()).use { assertEquals(30, it.numberOfPages) }
        Gates.perf("chaos: death mid-export: files in exports/ at death $partials; restart OK, re-export OK")
    }

    @Test(timeout = 600_000)
    fun processDeathMidSaveBurst() {
        val vm = AppViewModel(app)
        val doc = Gates.seed(vm.store, 12, w = 1200, h = 1600)
        // A burst of edits that re-render pages and rewrite doc.json many times...
        for (round in 0 until 6) for (p in doc.pages) vm.editPage(doc.id, p.id) { it.copy(rotation = (it.rotation + 90) % 360) }
        // ...while the process "dies" at several instants.
        var checked = 0
        val docs = File(app.filesDir, "documents")
        for (k in 0 until 8) {
            Gates.pump(150L + k * 90) { false }
            val snap = tmp.newFolder("snap$k")
            val ok = runCatching { snapshot(docs, File(snap, "documents")) }.isSuccess
            if (!ok) continue
            val store = DocumentStore(snap)
            val problems = Gates.integrity(store)
            assertTrue("after death #$k: $problems", problems.isEmpty())
            val d = store.load(doc.id)
            assertNotNull("document lost at death #$k", d)
            assertEquals(12, d!!.pages.size)
            checked++
        }
        assertTrue(Gates.awaitIdle(vm, 300_000))
        assertTrue(checked >= 4)
        assertTrue(vm.store.load(doc.id)!!.pages.all { it.rotation == (6 * 90) % 360 })
        Gates.perf("chaos: death mid-save: $checked snapshots taken during a 72-edit burst, all consistent")
    }

    /**
     * Disk full / write failure. There is no I/O fault seam in the app (see
     * tools/blackbox/seams/io-faults.patch), so writes are made to fail from outside:
     * the export folder is a file, or doc.json's temporary file is a folder.
     */
    @Test(timeout = 300_000)
    fun writeFailuresKeepDocumentsIntact() {
        val vm = AppViewModel(app)
        val doc = Gates.seed(vm.store, 4, w = 800, h = 1066)
        // 1. Export cannot write its file.
        val exports = File(app.cacheDir, "exports")
        exports.deleteRecursively()
        exports.writeText("not a folder")
        vm.share(doc.id, ExportOptions(PageSize.A4, Quality.SMALL), asJpeg = false)
        assertNull("an export without a file must not open the share sheet", Gates.awaitExport(vm, 20_000))
        val m1 = nextMessage(vm, 5_000)
        assertEquals("no error shown for a failed export", R.string.msg_error, m1?.res)
        assertTrue(Gates.pump(10_000) { vm.exportState.value == null })
        exports.delete()
        // 2. Saving the document fails (doc.json cannot be replaced).
        val before = File(vm.store.dir(doc.id), "doc.json").readText()
        val blocker = File(vm.store.dir(doc.id), "doc.json.tmp").apply { mkdirs(); File(this, "x").writeText("x") }
        vm.editPage(doc.id, doc.pages[0].id) { it.copy(rotation = 90) }
        Gates.awaitIdle(vm)
        val m2 = nextMessage(vm, 5_000)
        assertEquals("no error shown for a failed save", R.string.msg_error, m2?.res)
        assertEquals("doc.json changed although the save failed", before, File(vm.store.dir(doc.id), "doc.json").readText())
        assertEquals(0, vm.store.load(doc.id)!!.pages[0].rotation)
        // 3. Import into that document while saves fail: the document stays as it was.
        World.setUp(tmp.newFolder("world"))
        val u = World.put("in.jpg", SyntheticPages.jpeg(Gates.pageBitmap(500, 700), 80))
        vm.importUris(doc.id, listOf(u)) { }
        Gates.awaitIdle(vm)
        nextMessage(vm, 3_000)
        assertEquals(4, vm.store.load(doc.id)!!.pages.size)
        // 4. The disk is fine again: everything works and nothing was lost.
        blocker.deleteRecursively()
        vm.editPage(doc.id, doc.pages[0].id) { it.copy(rotation = 90) }
        Gates.awaitIdle(vm)
        assertEquals(90, vm.store.load(doc.id)!!.pages[0].rotation)
        vm.share(doc.id, ExportOptions(PageSize.A4, Quality.SMALL), asJpeg = false)
        assertNotNull(Gates.awaitExport(vm, 60_000))
        assertTrue(Gates.integrity(vm.store).isEmpty())
        Gates.perf("chaos: write failures (export folder blocked, doc.json blocked, import while blocked): errors shown, documents intact")
    }

    /**
     * When saving an edit fails, the page image on disk must still show the saved state:
     * the thumbnail, the page view and the next export read that image.
     */
    @Test(timeout = 300_000)
    fun failedSaveLeavesPageImageAsSaved() {
        val vm = AppViewModel(app)
        val doc = Gates.seed(vm.store, 2, w = 800, h = 1066)
        val p = doc.pages[0]
        val blocker = File(vm.store.dir(doc.id), "doc.json.tmp").apply { mkdirs(); File(this, "x").writeText("x") }
        vm.editPage(doc.id, p.id) { it.copy(rotation = 90) }
        Gates.awaitIdle(vm)
        nextMessage(vm, 3_000)
        blocker.deleteRecursively()
        assertEquals(0, vm.store.load(doc.id)!!.pages[0].rotation)
        val (w, h) = com.fastkiwi.scan.imaging.BitmapIO.imageSize(vm.store.outFile(doc.id, p.id))!!
        assertTrue("page image is rotated (${w}x$h) although the document says 0°", w < h)
    }

    /** Low memory signals while an export runs: the export still finishes correctly. */
    @Test(timeout = 300_000)
    fun lowMemoryDuringExport() {
        val vm = AppViewModel(app)
        val doc = Gates.seed(vm.store, 20, w = 2000, h = 2667)
        vm.share(doc.id, ExportOptions(PageSize.A4, Quality.BALANCED), asJpeg = false)
        assertTrue(Gates.pump(60_000) { (vm.exportState.value?.page ?: 0) >= 2 })
        for (level in listOf(ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE, ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW, ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)) {
            app.onTrimMemory(level)
            Gates.pump(30) { false }
        }
        app.onLowMemory()
        System.gc()
        val r = Gates.awaitExport(vm, 180_000) as? ExportResult.Share
        assertNotNull(r)
        org.apache.pdfbox.Loader.loadPDF(r!!.files.single()).use { assertEquals(20, it.numberOfPages) }
        Gates.perf("chaos: onTrimMemory/onLowMemory during a 20-page export: export complete")
    }

    /** The camera permission is taken away while the camera is open (Android restarts the app). */
    @Test(timeout = 300_000)
    fun cameraPermissionRevokedMidSession() {
        val bb = BlackBox(rule, tmp.newFolder("bb"))
        try {
            bb.start("ar")
            shadowOf(app).grantPermissions(Manifest.permission.CAMERA)
            val home = bb.execute("screenshot")
            val scan = Regex("\\[(\\d+)] button(?: \"مسح\")?  actions: tap  bounds \\[\\d+,(\\d+)]").findAll(home.text).maxByOrNull { it.groupValues[2].toInt() }!!.groupValues[1]
            bb.execute("tap @$scan")
            val seen = bb.execute("camera_sees invoice")
            assertTrue(seen.text, seen.events.any { it.contains("photo taken automatically") })
            // Settings ▸ Apps ▸ Scan Kiwi ▸ Permissions ▸ Camera: Deny. The system restarts the activity.
            shadowOf(app).denyPermissions(Manifest.permission.CAMERA)
            bb.recreate()
            val after = bb.execute("screenshot")
            assertTrue("crashed:\n${after.text}", after.events.none { it.startsWith("APP STOPPED") })
            val asks = after.text.contains(app.getString(R.string.allow_camera)) || after.events.any { it.startsWith("SYSTEM PERMISSION DIALOG") } ||
                after.text.contains(app.getString(R.string.camera_permission_title))
            assertTrue("no permission prompt after revoking:\n${after.text}", asks)
            val shot = bb.execute("permission deny")
            assertTrue(shot.events.none { it.startsWith("APP STOPPED") })
            val back = bb.execute("back")
            assertTrue(back.events.none { it.startsWith("APP STOPPED") })
            // The page taken before the revocation is still there.
            assertEquals(1, DocumentStore(app.filesDir).list().sumOf { it.pages.size })
            Gates.perf("chaos: camera permission revoked mid-session: permission screen shown, page kept, no crash")
        } finally {
            bb.close()
        }
    }
}
