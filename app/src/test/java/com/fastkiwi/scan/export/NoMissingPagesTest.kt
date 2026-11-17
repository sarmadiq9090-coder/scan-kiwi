// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.export

import com.fastkiwi.scan.core.PageSize
import com.fastkiwi.scan.data.DocumentStore
import com.fastkiwi.scan.pdf.PdfValidator
import kotlinx.coroutines.runBlocking
import org.apache.pdfbox.Loader
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

/**
 * 2.0.1: 2.0.0 left a page it could not read out of the PDF without a word, so a document could be
 * exported as a PDF with fewer pages, or none, which viewers call damaged. Now a page whose finished
 * image is cut or empty is made again from its photo, and a page that cannot be made at all stops the
 * export with [Exporter.PageUnreadable]; no partial file is left to share.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class NoMissingPagesTest {
    private val ctx = RuntimeEnvironment.getApplication()
    private val store = DocumentStore(File(ctx.filesDir, "pages-${System.nanoTime()}"))
    private val opt = ExportOptions(PageSize.A4, Quality.SMALL)

    @Test
    fun aDamagedPageImageIsMadeAgainFromItsPhoto() = runBlocking {
        val doc = ExportSeed.doc(store, "damaged", 3, seed = 4)
        // The finished image of page 2 is cut short (as after a crash), page 3's is empty.
        val out2 = store.outFile(doc.id, doc.pages[1].id)
        out2.writeBytes(out2.readBytes().copyOf(200))
        store.outFile(doc.id, doc.pages[2].id).writeBytes(ByteArray(0))
        val pdf = Exporter(ctx, store).exportPdf(doc, opt) { _, _ -> }
        assertEquals(3, PdfValidator.validate(pdf))
        Loader.loadPDF(pdf).use { assertEquals(3, it.numberOfPages) }
        val jpgs = Exporter(ctx, store).exportJpegs(doc, opt) { _, _ -> }
        assertEquals(3, jpgs.size)
    }

    @Test
    fun aPageThatCannotBeReadStopsTheExportWithItsNumber() = runBlocking {
        val doc = ExportSeed.doc(store, "unreadable", 3, seed = 5)
        val p = doc.pages[1]
        store.outFile(doc.id, p.id).writeBytes(ByteArray(0))
        store.srcFile(doc.id, p.id).writeBytes("not an image".toByteArray())
        val exports = File(ctx.cacheDir, "exports")
        try {
            Exporter(ctx, store).exportPdf(doc, opt) { _, _ -> }
            fail("a PDF without page 2 was written")
        } catch (e: Exporter.PageUnreadable) {
            assertEquals(1, e.index)
        }
        assertFalse("a partial file was left", exports.listFiles().orEmpty().any { it.name.startsWith("unreadable") })
        try {
            Exporter(ctx, store).exportJpegs(doc, opt) { _, _ -> }
            fail("JPG export without page 2")
        } catch (e: Exporter.PageUnreadable) {
            assertEquals(1, e.index)
        }
        assertTrue(exports.listFiles().orEmpty().none { it.name.startsWith("unreadable") })
    }
}
