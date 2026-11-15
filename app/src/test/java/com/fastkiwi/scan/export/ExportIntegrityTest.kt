// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.export

import com.fastkiwi.scan.core.PageSize
import com.fastkiwi.scan.data.DocumentStore
import com.fastkiwi.scan.pdf.NoSpaceException
import com.fastkiwi.scan.pdf.PdfValidator
import com.fastkiwi.scan.pdf.SafeOutput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream
import kotlin.random.Random

/**
 * PRD §6.3 / ROADMAP 1.4.0 integrity: an export killed at 10 random points (5 cancelled
 * coroutines at a random page, 5 I/O errors at a random byte) leaves the document's files and
 * the previous good export byte-identical (sha256), leaves no `.part` file, and never hands a
 * file to the share path.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class ExportIntegrityTest {
    private val ctx = RuntimeEnvironment.getApplication()
    private val store = DocumentStore(File(ctx.filesDir, "integ-${System.nanoTime()}"))
    private val exports = File(ctx.cacheDir, "exports")

    /** Throws after [limit] bytes, like a full disk or a yanked SD card. */
    private class FailingStream(out: OutputStream, private val limit: Long) : FilterOutputStream(out) {
        var n = 0L
        override fun write(b: Int) { if (++n > limit) throw IOException("injected I/O error at byte $limit"); out.write(b) }
        override fun write(b: ByteArray, off: Int, len: Int) {
            if (n + len > limit) throw IOException("injected I/O error at byte $limit")
            n += len; out.write(b, off, len)
        }
    }

    @Test
    fun killedExportsLeaveOriginalsUntouchedAndShareNothing() = runBlocking {
        val doc = ExportSeed.doc(store, "integrity", 6, seed = 1)
        val opt = ExportOptions(PageSize.A4, Quality.SMALL)
        val good = Exporter(ctx, store).exportPdf(doc, opt) { _, _ -> }
        val goodSize = good.length()
        val goodSha = ExportSeed.sha(good)
        val docSha = ExportSeed.docFingerprint(store, doc)
        val rnd = Random(10)
        var shared = 0
        repeat(10) { run ->
            // Different options each time, so the cached file is never simply reused.
            val o = opt.copy(quality = Quality.entries[run % 3], pageNumbers = com.fastkiwi.scan.pdf.PageNumbering(start = run + 2))
            val exporter: Exporter
            var job: Job? = null
            if (run % 2 == 0) {
                val cancelAt = rnd.nextInt(doc.pages.size)
                exporter = Exporter(ctx, store)
                val d = async(Dispatchers.Default, start = CoroutineStart.LAZY) {
                    exporter.exportPdf(doc, o) { i, _ -> if (i == cancelAt) job?.cancel() }
                }
                job = d
                try {
                    d.await(); shared++
                } catch (_: CancellationException) {
                }
            } else {
                val failAt = 1L + rnd.nextLong(goodSize - 1)
                exporter = Exporter(ctx, store, openStream = { FailingStream(FileOutputStream(it), failAt) })
                try {
                    exporter.exportPdf(doc, o) { _, _ -> }; shared++
                } catch (e: IOException) {
                    assertTrue(e.message!!, e.message!!.contains("injected"))
                }
            }
            assertEquals("run $run: no result reached the share path", 0, shared)
            assertEquals("run $run: previous export unchanged", goodSha, ExportSeed.sha(good))
            assertEquals("run $run: document files unchanged", docSha, ExportSeed.docFingerprint(store, doc))
            assertTrue("run $run: no .part left: ${exports.list()?.toList()}", exports.listFiles().orEmpty().none { it.name.endsWith(SafeOutput.PART) })
        }
        PdfValidator.validate(good, doc.pages.size)
        Unit
    }

    @Test
    fun lowSpaceIsRefusedBeforeWriting() {
        val safe = SafeOutput(File(exports, "x.pdf"))
        try {
            safe.checkSpace(neededBytes = 1_000_000, freeBytes = 1_100_000)
            fail("1.2 MB needed, 1.1 MB free")
        } catch (e: NoSpaceException) {
            assertEquals(1_200_000, e.needed)
        }
        safe.checkSpace(neededBytes = 1_000_000, freeBytes = 1_300_000)
        assertFalse(safe.part.exists())
    }

    @Test
    fun anInvalidFileIsNeverCommitted() {
        exports.mkdirs()
        val safe = SafeOutput(File(exports, "broken.pdf"))
        safe.write { it.write("%PDF-1.7\n1 0 obj\n<< >>\nendobj\n".toByteArray()) }
        try {
            safe.commitPdf(1); fail()
        } catch (_: PdfValidator.Invalid) {
        }
        assertFalse(safe.final.exists() || safe.part.exists())
    }

    @Test
    fun leftoversAreSweptAtNextStart() {
        exports.mkdirs()
        val old = File(exports, "killed.pdf.part").apply { writeText("half"); setLastModified(System.currentTimeMillis() - 10_000) }
        val running = File(exports, "running.pdf.part").apply { writeText("now") }
        val start = System.currentTimeMillis() - 5_000
        assertEquals(1, SafeOutput.sweep(listOf(exports), before = start))
        assertFalse(old.exists())
        assertTrue("an export started in this session is left alone", running.exists())
        running.delete()
    }
}
