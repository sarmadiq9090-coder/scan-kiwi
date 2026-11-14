// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.core

import org.junit.Assert.assertTrue
import org.junit.Test
import org.verapdf.gf.foundry.VeraGreenfieldFoundryProvider
import org.verapdf.pdfa.Foundries
import org.verapdf.pdfa.flavours.PDFAFlavour
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/** The «أرشيفي» option must produce PDF/A-2b that an independent validator (veraPDF) accepts. */
class PdfATest {
    private val font = File("src/main/assets/fonts/glyphless.ttf").readBytes()
    private val icc = File("src/main/assets/icc/sRGB-v2-micro.icc").readBytes()
    private val jpeg = javaClass.getResourceAsStream("/page_color.jpg")!!.readBytes()

    private fun build(withText: Boolean): ByteArray {
        val w = PdfWriter(PageSize.A4, "فاتورة Invoice & <test>", null, font, pdfAIccProfile = icc)
        val words = if (withText) listOf(OcrWord("Hello", 100, 100, 300, 140), OcrWord("مرحبا", 320, 100, 520, 140)) else emptyList()
        w.addPage(PdfPage(PdfImage.Jpeg(1240, 1754, jpeg, PdfWriter.jpegComponents(jpeg)), 150f, words))
        val px = IntArray(800 * 600) { if ((it / 800) % 9 < 2) 0xFF000000.toInt() else -1 }
        w.addPage(PdfPage(PdfImage.Bitonal(800, 600, ImageFilters.packBits(px, 800, 600)), 150f))
        return ByteArrayOutputStream().also { w.write(it) }.toByteArray()
    }

    private fun validate(pdf: ByteArray): List<String> {
        VeraGreenfieldFoundryProvider.initialise()
        Foundries.defaultInstance().createParser(ByteArrayInputStream(pdf), PDFAFlavour.PDFA_2_B).use { parser ->
            val validator = Foundries.defaultInstance().createValidator(PDFAFlavour.PDFA_2_B, false)
            val result = validator.validate(parser)
            if (result.isCompliant) return emptyList()
            return listOf("not compliant") + result.testAssertions
                .filter { it.status.toString() == "FAILED" }
                .map { "${it.ruleId.clause} #${it.ruleId.testNumber}: ${it.message}" }
                .distinct()
        }
    }

    @Test
    fun archivalOutputIsValidPdfA2b() {
        for (withText in listOf(false, true)) {
            val failures = validate(build(withText))
            assertTrue("PDF/A-2b failures (text=$withText):\n" + failures.joinToString("\n"), failures.isEmpty())
        }
    }

    /** Control: a normal (non-archival) file must be rejected, so the validator really checks. */
    @Test
    fun ordinaryPdfIsNotPdfA() {
        val w = PdfWriter(PageSize.A4, "x", null, font)
        w.addPage(PdfPage(PdfImage.Jpeg(1240, 1754, jpeg, PdfWriter.jpegComponents(jpeg)), 150f))
        val bytes = ByteArrayOutputStream().also { w.write(it) }.toByteArray()
        assertTrue(validate(bytes).isNotEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun archivalCannotBeEncrypted() {
        PdfWriter(PageSize.A4, "x", "secret", null, pdfAIccProfile = icc)
    }
}
