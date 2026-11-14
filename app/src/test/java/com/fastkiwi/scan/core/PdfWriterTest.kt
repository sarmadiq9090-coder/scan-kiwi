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

    @Test
    fun jpegComponentCount() {
        val gray = javaClass.getResourceAsStream("/gray.jpg")!!.readBytes()
        assertEquals(1, PdfWriter.jpegComponents(gray))
        assertEquals(3, PdfWriter.jpegComponents(colorJpeg))
    }
}
