// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.core

import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Verifies our PDF writer with an independent reader (Apache PDFBox):
 * structure, page sizes, the searchable text layer and AES-256 encryption.
 */
class PdfWriterTest {
    private val font: ByteArray = File("src/main/assets/fonts/glyphless.ttf").readBytes()
    private val colorJpeg: ByteArray = javaClass.getResourceAsStream("/page_color.jpg")!!.readBytes()

    private fun jpeg() = PdfImage.Jpeg(1240, 1754, colorJpeg, PdfWriter.jpegComponents(colorJpeg))

    private fun bitonal(w: Int, h: Int): PdfImage.Bitonal {
        val px = IntArray(w * h) { if ((it / w) % 10 < 2) 0xFF000000.toInt() else -1 }
        return PdfImage.Bitonal(w, h, ImageFilters.packBits(px, w, h))
    }

    private fun build(size: PageSize, password: String? = null): ByteArray {
        val wr = PdfWriter(size, "فاتورة Invoice", password, font)
        val words = listOf(OcrWord("Hello", 100, 100, 300, 140), OcrWord("Kiwi", 320, 100, 420, 140))
        wr.addPage(PdfPage(jpeg(), 150f, words))
        wr.addPage(PdfPage(bitonal(1754, 1240), 150f, listOf(OcrWord("مرحبا", 200, 200, 500, 260))))
        val bos = ByteArrayOutputStream()
        wr.write(bos)
        return bos.toByteArray()
    }

    @Test
    fun writesValidA4PdfWithSearchableText() {
        val bytes = build(PageSize.A4)
        Loader.loadPDF(bytes).use { doc ->
            assertEquals(2, doc.numberOfPages)
            val p1 = doc.getPage(0).mediaBox
            assertEquals(595.28f, p1.width, 0.01f)
            assertEquals(841.89f, p1.height, 0.01f)
            // Landscape image -> landscape A4 page.
            val p2 = doc.getPage(1).mediaBox
            assertEquals(841.89f, p2.width, 0.01f)
            assertEquals("فاتورة Invoice", doc.documentInformation.title)
            val text = PDFTextStripper().getText(doc)
            assertTrue("text layer: $text", text.contains("Hello"))
            assertTrue("text layer: $text", text.contains("Kiwi"))
            assertTrue("arabic text layer: $text", text.contains("مرحبا"))
        }
    }

    @Test
    fun fitPageUsesImageSize() {
        Loader.loadPDF(build(PageSize.FIT)).use { doc ->
            val box = doc.getPage(0).mediaBox
            assertEquals(1240 * 72f / 150f, box.width, 0.01f)
            assertEquals(1754 * 72f / 150f, box.height, 0.01f)
        }
    }

    @Test
    fun aes256PasswordProtection() {
        val bytes = build(PageSize.LETTER, password = "Kiwi-123!")
        try {
            Loader.loadPDF(bytes).close()
            fail("opened without password")
        } catch (_: InvalidPasswordException) {
        }
        Loader.loadPDF(bytes, "Kiwi-123!").use { doc ->
            assertTrue(doc.isEncrypted)
            assertEquals(256, doc.encryption.length)
            assertEquals(2, doc.numberOfPages)
            assertEquals(612f, doc.getPage(0).mediaBox.width, 0.01f)
            assertEquals("فاتورة Invoice", doc.documentInformation.title)
            assertTrue(PDFTextStripper().getText(doc).contains("Hello"))
            // The image stream must decrypt back to the exact original JPEG bytes.
            val res = doc.getPage(0).resources
            val img = res.getXObject(res.xObjectNames.first()) as PDImageXObject
            val raw = img.cosObject.createRawInputStream().use { it.readBytes() }
            assertArrayEquals(colorJpeg, raw)
        }
    }

    /**
     * Strict structure check that does not rely on a lenient reader (PDFBox silently
     * rebuilds a broken xref): every xref offset must point at "N 0 obj", and
     * startxref at the xref table.
     */
    private fun assertXrefExact(bytes: ByteArray) {
        val text = String(bytes, Charsets.ISO_8859_1)
        val startxref = Regex("startxref\n(\\d+)\n%%EOF\n$").find(text)!!.groupValues[1].toInt()
        assertTrue(text.startsWith("xref\n", startxref))
        val header = Regex("xref\n0 (\\d+)\n").find(text, startxref)!!
        val count = header.groupValues[1].toInt()
        var pos = header.range.last + 1
        for (id in 0 until count) {
            val entry = text.substring(pos, pos + 20)
            assertEquals("entry $id is 20 bytes", '\n', entry[19])
            if (id == 0) assertEquals("0000000000 65535 f \n", entry)
            else {
                val off = entry.substring(0, 10).toInt()
                assertTrue("object $id at $off", text.startsWith("$id 0 obj\n", off))
            }
            pos += 20
        }
        assertTrue(text.startsWith("trailer\n<< /Size $count ", pos))
    }

    @Test
    fun xrefOffsetsAreExactPlainEncryptedAndStreamed() {
        assertXrefExact(build(PageSize.A4))
        assertXrefExact(build(PageSize.A4, password = "Kiwi-123!"))
        val wr = PdfWriter(PageSize.FIT, "t", null, font)
        val bos = ByteArrayOutputStream()
        wr.begin(bos)
        wr.addPage(PdfPage(jpeg(), 150f))
        wr.addPage(PdfPage(bitonal(200, 100), 150f, listOf(OcrWord("Kiwi", 10, 10, 60, 30))))
        wr.finish()
        assertXrefExact(bos.toByteArray())
        Loader.loadPDF(bos.toByteArray()).use { assertEquals(2, it.numberOfPages) }
    }

    /**
     * Regression (1.1.1): 1.1.0 kept every encoded page in memory until the end,
     * so a 200-page "High" export (~1.5 MB per page) ran out of memory on 2-3 GB
     * phones. In streaming mode a page is written at once and not retained.
     */
    @Test
    fun streamedPagesAreNotRetained() {
        val wr = PdfWriter(PageSize.A4, "t")
        wr.begin(ByteArrayOutputStream())
        var bytes: ByteArray? = colorJpeg.copyOf()
        val ref = java.lang.ref.WeakReference(bytes)
        wr.addPage(PdfPage(PdfImage.Jpeg(1240, 1754, bytes!!, 3), 150f))
        bytes = null
        for (i in 0 until 20) {
            if (ref.get() == null) break
            System.gc()
            Thread.sleep(20)
        }
        assertTrue("page image still referenced by the writer", ref.get() == null)
        wr.finish()
    }

    @Test
    fun twoHundredPageDocumentIsValid() {
        val file = File.createTempFile("kiwi200", ".pdf")
        try {
            val wr = PdfWriter(PageSize.A4, "200", "pw", font)
            file.outputStream().buffered().use { out ->
                wr.begin(out)
                for (i in 0 until 200) {
                    val words = if (i % 50 == 0) listOf(OcrWord("Page$i", 100, 100, 300, 140)) else emptyList()
                    wr.addPage(PdfPage(if (i % 2 == 0) jpeg() else bitonal(600, 800), 150f, words))
                }
                wr.finish()
            }
            assertXrefExact(file.readBytes())
            Loader.loadPDF(file, "pw").use { doc ->
                assertEquals(200, doc.numberOfPages)
                assertTrue(PDFTextStripper().getText(doc).contains("Page150"))
            }
        } finally {
            file.delete()
        }
    }

    /** Arabic words mixed with digits and Latin keep their logical order on extraction. */
    @Test
    fun arabicTextLayerKeepsLogicalOrder() {
        val wr = PdfWriter(PageSize.A4, "t", null, font)
        val words = listOf(
            OcrWord("فاتورة", 700, 100, 900, 140),
            OcrWord("رقم", 500, 100, 650, 140),
            OcrWord("2026", 300, 100, 450, 140),
        )
        wr.addPage(PdfPage(jpeg(), 150f, words))
        val bos = ByteArrayOutputStream()
        wr.write(bos)
        Loader.loadPDF(bos.toByteArray()).use { doc ->
            val text = PDFTextStripper().getText(doc)
            assertTrue("text layer: $text", text.contains("فاتورة"))
            assertTrue("text layer: $text", text.contains("رقم"))
            assertTrue("text layer: $text", text.contains("2026"))
        }
        assertEquals("ةروتاف", wr.visualOrder("فاتورة"))
        assertEquals("Kiwi", wr.visualOrder("Kiwi"))
    }

    /** Empty password means "no protection"; the writer itself still handles one correctly. */
    @Test
    fun emptyUserPasswordOpensWithoutPassword() {
        val bytes = build(PageSize.A4, password = "")
        Loader.loadPDF(bytes).use { doc ->
            assertTrue(doc.isEncrypted)
            assertEquals(2, doc.numberOfPages)
            assertTrue(PDFTextStripper().getText(doc).contains("Hello"))
        }
    }

    @Test
    fun jpegComponentCount() {
        val gray = javaClass.getResourceAsStream("/gray.jpg")!!.readBytes()
        assertEquals(1, PdfWriter.jpegComponents(gray))
        assertEquals(3, PdfWriter.jpegComponents(colorJpeg))
    }
}
