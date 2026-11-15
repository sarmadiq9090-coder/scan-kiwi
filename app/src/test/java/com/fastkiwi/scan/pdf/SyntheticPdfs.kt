// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.pdf

import com.fastkiwi.scan.core.ImageFilters
import com.fastkiwi.scan.core.OcrWord
import com.fastkiwi.scan.core.PageSize
import com.fastkiwi.scan.core.PdfImage
import com.fastkiwi.scan.core.PdfPage
import com.fastkiwi.scan.core.PdfWriter
import org.apache.pdfbox.Loader
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.cos.COSStream
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.encryption.AccessPermission
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.apache.pdfbox.pdmodel.common.PDStream
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceRGB
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.apache.pdfbox.pdmodel.interactive.action.PDActionGoTo
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination
import org.apache.pdfbox.text.PDFTextStripper
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import kotlin.random.Random

/**
 * Synthetic PDFs for the PDF tests (never a real document), written with desktop Apache PDFBox
 * 3 (test-only): every page carries a unique marker text, a small image and a link to the next
 * page, and pages vary in size and rotation. Plus "ours", written by Scan Kiwi's own writer.
 */
object SyntheticPdfs {

    fun marker(tag: String, page: Int) = "MARK-$tag-${page + 1}"

    /** An external-style PDF with [pages] pages. */
    fun external(dir: File, tag: String, pages: Int, seed: Int, password: String? = null): File {
        val rnd = Random(seed)
        val f = File(dir, "ext-$tag.pdf")
        PDDocument().use { doc ->
            val font = PDType1Font(Standard14Fonts.FontName.HELVETICA)
            val list = ArrayList<PDPage>()
            for (i in 0 until pages) {
                val box = if (rnd.nextInt(4) == 0) PDRectangle.LETTER else if (rnd.nextInt(5) == 0) PDRectangle(842f, 595f) else PDRectangle.A4
                val page = PDPage(box)
                page.rotation = listOf(0, 0, 0, 90, 180)[rnd.nextInt(5)]
                doc.addPage(page)
                list.add(page)
                val raw = ByteArray(40 * 30 * 3) { rnd.nextInt(256).toByte() }
                val ximg = PDImageXObject(PDStream(doc, ByteArrayInputStream(raw), COSName.FLATE_DECODE), null).apply {
                    width = 40; height = 30; bitsPerComponent = 8; colorSpace = PDDeviceRGB.INSTANCE
                }
                PDPageContentStream(doc, page).use { cs ->
                    cs.beginText()
                    cs.setFont(font, 14f)
                    cs.newLineAtOffset(72f, box.height - 100f)
                    cs.showText(marker(tag, i))
                    cs.endText()
                    cs.drawImage(ximg, 72f, 72f, 80f, 60f)
                }
            }
            // Links to the next page: an extract must not pull the linked pages in.
            for (i in 0 until pages - 1) {
                val link = PDAnnotationLink()
                link.rectangle = PDRectangle(72f, 200f, 100f, 20f)
                link.action = PDActionGoTo().apply { destination = PDPageFitDestination().apply { page = list[i + 1] } }
                list[i].annotations.add(link)
            }
            if (password != null) {
                val policy = StandardProtectionPolicy("owner-$password", password, AccessPermission())
                policy.encryptionKeyLength = 256
                doc.protect(policy)
            }
            doc.save(f)
        }
        return f
    }

    /** A PDF written by our own writer, with a text layer marker on each page. */
    fun ours(dir: File, tag: String, pages: Int): File {
        val font = File("src/main/assets/fonts/glyphless.ttf").readBytes()
        val jpeg = SyntheticPdfs::class.java.getResourceAsStream("/page_color.jpg")!!.readBytes()
        val w = PdfWriter(PageSize.A4, "ours $tag", null, font)
        val f = File(dir, "ours-$tag.pdf")
        f.outputStream().buffered().use { out ->
            w.begin(out)
            for (i in 0 until pages) {
                val words = listOf(OcrWord(marker(tag, i), 100, 100, 500, 140))
                val img = if (i % 2 == 0) PdfImage.Jpeg(1240, 1754, jpeg, PdfWriter.jpegComponents(jpeg)) else {
                    val px = IntArray(600 * 800) { if ((it / 600) % 10 < 2) 0xFF000000.toInt() else -1 }
                    PdfImage.Bitonal(600, 800, ImageFilters.packBits(px, 600, 800))
                }
                w.addPage(PdfPage(img, 150f, words))
            }
            w.finish()
        }
        return f
    }

    /** Marker text of every page, read with desktop PDFBox (independent of PdfBox-Android). */
    fun markers(f: File, password: String = ""): List<String> = Loader.loadPDF(f, password).use { doc ->
        val s = PDFTextStripper()
        s.sortByPosition = true // rotated pages otherwise come out one character per line
        List(doc.numberOfPages) { i ->
            s.startPage = i + 1; s.endPage = i + 1
            Regex("MARK-[A-Za-z0-9]+-\\d+").find(s.getText(doc))?.value ?: ""
        }
    }

    /**
     * Fingerprint of each page's untransformed content: sha256 over the raw (still encoded)
     * bytes of its content streams and of every image XObject, plus the media box and rotation.
     */
    fun pageFingerprints(f: File, password: String = ""): List<String> = Loader.loadPDF(f, password).use { doc ->
        doc.pages.map { p ->
            val md = MessageDigest.getInstance("SHA-256")
            val contents = p.cosObject.getDictionaryObject(COSName.CONTENTS)
            val streams = when (contents) {
                is COSStream -> listOf(contents)
                is org.apache.pdfbox.cos.COSArray -> (0 until contents.size()).mapNotNull { contents.getObject(it) as? COSStream }
                else -> emptyList()
            }
            for (s in streams) s.createRawInputStream().use { md.update(it.readBytes()) }
            val res = p.resources
            for (n in res.xObjectNames.sortedBy { it.name }) {
                val x = res.getXObject(n)
                x.cosObject.createRawInputStream().use { md.update(it.readBytes()) }
            }
            md.update("${p.mediaBox.width}x${p.mediaBox.height}".toByteArray())
            md.digest().joinToString("") { "%02x".format(it) }
        }
    }

    fun rotations(f: File): List<Int> = Loader.loadPDF(f).use { d -> d.pages.map { it.rotation } }

    /** Number of page objects anywhere in the file (catches pages leaked through links). */
    fun pageObjects(f: File): Int = Loader.loadPDF(f).use { d -> d.document.getObjectsByType(COSName.PAGE).size }

    fun sha256(f: File): String = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }

    @Suppress("unused")
    fun bytes(doc: PDDocument): ByteArray = ByteArrayOutputStream().also { doc.save(it) }.toByteArray()
}
