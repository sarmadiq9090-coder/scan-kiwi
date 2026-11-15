// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.gates

import com.fastkiwi.scan.core.Filter
import com.fastkiwi.scan.core.PageSize
import com.fastkiwi.scan.data.DocumentStore
import com.fastkiwi.scan.export.ExportOptions
import com.fastkiwi.scan.export.Quality
import com.fastkiwi.scan.ui.AppViewModel
import com.fastkiwi.scan.ui.ExportResult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.random.Random

/** Stress: long documents, many quick edits, rapid exports with cancels, edits racing each other. */
@Category(TortureSuite::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], shadows = [ShadowWorldPdfRenderer::class, ShadowWorldPdfPage::class])
class TortureStressTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private lateinit var uncaught: Gates.Uncaught
    private val tmp = TemporaryFolder()

    @Before
    fun setUp() {
        Gates.assumeTorture()
        tmp.create()
        uncaught = Gates.Uncaught().install()
        Gates.wipe(DocumentStore(app.filesDir))
    }

    @After
    fun tearDown() {
        if (!Gates.torture) return
        uncaught.uninstall()
        tmp.delete()
        Gates.wipe(DocumentStore(app.filesDir))
        assertTrue("uncaught: ${uncaught.errors.map { Gates.stack(it.second) }}", uncaught.errors.isEmpty())
    }

    private fun ids(vm: AppViewModel, id: String) = vm.store.load(id)!!.pages.map { it.id }

    /**
     * A 100-page document goes through 40 cycles of reorder / delete / undo, checked against
     * a model after every step; then 30 more pages arrive by import into the same document.
     */
    @Test(timeout = 600_000)
    fun hundredPagesReorderDeleteUndo() {
        val vm = AppViewModel(app)
        drainMessages(vm)
        val doc = Gates.seed(vm.store, 100, w = 600, h = 800)
        val model = ids(vm, doc.id).toMutableList()
        val history = ArrayDeque<List<String>>()
        val rnd = Random(20_000)
        val t0 = System.nanoTime()
        var ops = 0
        repeat(40) { cycle ->
            repeat(5) {
                when (rnd.nextInt(10)) {
                    in 0..5 -> {
                        val from = rnd.nextInt(model.size)
                        val to = rnd.nextInt(model.size)
                        vm.movePage(doc.id, from, to)
                        if (from != to) { history.addLast(model.toList()); model.add(to, model.removeAt(from)) }
                    }
                    in 6..7 -> if (model.size > 1) {
                        val i = rnd.nextInt(model.size)
                        vm.deletePage(doc.id, i)
                        history.addLast(model.toList()); model.removeAt(i)
                    }
                    else -> {
                        vm.undo(doc.id)
                        history.removeLastOrNull()?.let { model.clear(); model.addAll(it) }
                    }
                }
                while (history.size > 20) history.removeFirst() // the app keeps 20 undo steps
                assertTrue("busy too long in cycle $cycle", Gates.awaitIdle(vm, 60_000))
                ops++
                assertEquals("cycle $cycle op $ops", model, ids(vm, doc.id))
            }
        }
        val problems = Gates.integrity(vm.store)
        assertTrue(problems.toString(), problems.isEmpty())
        Gates.perf("stress: 100-page document, $ops reorder/delete/undo steps in ${Gates.ms(t0)} ms")

        // Import 30 photos into the same document in one go (saved in batches of 10).
        World.setUp(tmp.newFolder("world"))
        val uris = (1..30).map { i ->
            val b = Gates.pageBitmap(500, 700, i)
            World.put("p$i.jpg", SyntheticPages.jpeg(b, 80)).also { b.recycle() }
        }
        var opened: String? = null
        val t1 = System.nanoTime()
        vm.importUris(doc.id, uris) { opened = it }
        assertTrue(Gates.awaitIdle(vm, 120_000))
        assertEquals(doc.id, opened)
        assertEquals(model.size + 30, ids(vm, doc.id).size)
        // One undo removes the whole import.
        vm.undo(doc.id)
        Gates.awaitIdle(vm)
        assertEquals(model, ids(vm, doc.id))
        Gates.perf("stress: import of 30 photos into the 100-page document: ${Gates.ms(t1)} ms")
    }

    /** 50 export taps, many cancelled after a random delay; nothing half-written survives. */
    @Test(timeout = 900_000)
    fun fiftyRapidExportsWithCancels() {
        val vm = AppViewModel(app)
        drainMessages(vm)
        val doc = Gates.seed(vm.store, 8, w = 1600, h = 2133)
        val rnd = Random(50)
        val exports = File(app.cacheDir, "exports")
        var finished = 0
        var cancelled = 0
        val t0 = System.nanoTime()
        repeat(50) { i ->
            val q = Quality.entries[i % 3]
            vm.share(doc.id, ExportOptions(PageSize.entries[i % PageSize.entries.size], q, ocr = false), asJpeg = i % 7 == 0)
            if (rnd.nextInt(3) != 0) {
                Gates.pump(rnd.nextLong(0, 120)) { false }
                vm.cancelExport()
                cancelled++
            }
            val r = Gates.awaitExport(vm, 60_000)
            if (r != null) finished++
            assertTrue("export $i still running", Gates.pump(30_000) { vm.exportState.value == null })
            Gates.awaitIdle(vm)
            val left = exports.listFiles()?.map { it.name } ?: emptyList()
            assertTrue("partial file after export $i: $left", left.none { it.endsWith(".part") })
        }
        // And a final, uninterrupted export is a valid PDF.
        vm.share(doc.id, ExportOptions(PageSize.A4, Quality.SMALL), asJpeg = false)
        val last = Gates.awaitExport(vm, 120_000) as? ExportResult.Share
        assertNotNull(last)
        org.apache.pdfbox.Loader.loadPDF(last!!.files.single()).use { assertEquals(8, it.numberOfPages) }
        Gates.perf("stress: 50 rapid exports ($cancelled cancel taps, $finished finished) in ${Gates.ms(t0)} ms")
    }

    /**
     * 300 edits fired in one burst (rotations, filters, renames, moves, 6 deletions),
     * without waiting: every rotation counts, exactly 6 pages go, files stay consistent.
     */
    @Test(timeout = 600_000)
    fun concurrentEditBurst() {
        val vm = AppViewModel(app)
        drainMessages(vm)
        val doc = Gates.seed(vm.store, 24, w = 500, h = 700)
        val ids = doc.pages.map { it.id }
        val rot = HashMap<String, Int>()
        val rnd = Random(77)
        val t0 = System.nanoTime()
        var deletes = 0
        repeat(300) { i ->
            when (rnd.nextInt(20)) {
                in 0..8 -> { val p = ids[rnd.nextInt(8)]; vm.editPage(doc.id, p) { it.copy(rotation = (it.rotation + 90) % 360) }; rot[p] = (rot[p] ?: 0) + 1 }
                in 9..11 -> { val p = ids[rnd.nextInt(8)]; vm.editPage(doc.id, p) { it.copy(filter = Filter.entries[rnd.nextInt(Filter.entries.size)]) } }
                in 12..14 -> vm.rename(doc.id, "اسم $i")
                in 15..17 -> vm.movePage(doc.id, rnd.nextInt(16), rnd.nextInt(16))
                else -> if (deletes < 6) { vm.deletePage(doc.id, 23 - deletes); deletes++ }
            }
        }
        assertTrue("edits did not finish", Gates.awaitIdle(vm, 300_000))
        val now = vm.store.load(doc.id)!!
        assertEquals(24 - deletes, now.pages.size)
        assertEquals(now.pages.size, now.pages.map { it.id }.toSet().size)
        for ((p, n) in rot) {
            val page = now.pages.firstOrNull { it.id == p } ?: continue
            assertEquals("rotation of $p after $n turns", (n * 90) % 360, page.rotation)
        }
        val problems = Gates.integrity(vm.store)
        assertTrue(problems.toString(), problems.isEmpty())
        Gates.perf("stress: 300-edit burst on 24 pages settled in ${Gates.ms(t0)} ms")
    }
}
