// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.export

import android.graphics.Bitmap
import android.graphics.Color
import com.fastkiwi.scan.core.PageSize
import com.fastkiwi.scan.data.DocumentStore
import com.fastkiwi.scan.pdf.NumberPosition
import com.fastkiwi.scan.pdf.PageDecor
import com.fastkiwi.scan.pdf.PageNumbering
import com.fastkiwi.scan.pdf.Watermark
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.verapdf.gf.foundry.VeraGreenfieldFoundryProvider
import org.verapdf.pdfa.Foundries
import org.verapdf.pdfa.flavours.PDFAFlavour
import java.io.File

/** PDF-09 page numbers and PDF-10 watermark: drawn where asked, and PDF/A-2b stays valid (veraPDF). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class DecoratedExportTest {
    private val ctx = RuntimeEnvironment.getApplication()

    @Test
    fun numberLabels() {
        assertEquals("١٢", PageNumbering(start = 12).label(0, 5))
        assertEquals("3 / 7", PageNumbering(arabicDigits = false, start = 1, withTotal = true).label(2, 7))
        assertEquals("٥ / ٩", PageNumbering(start = 5, withTotal = true).label(0, 5))
    }

    private fun dark(b: Bitmap, x0: Int, y0: Int, x1: Int, y1: Int, below: Int = 200): Int {
        var n = 0
        for (y in y0 until y1) for (x in x0 until x1) if (Color.red(b.getPixel(x, y)) < below) n++
        return n
    }

    @Test
    fun numbersAndWatermarkLandWhereAsked() {
        val decor = PageDecor.load(ctx)
        for (pos in NumberPosition.entries) {
            val b = Bitmap.createBitmap(1000, 1414, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
            decor.draw(b, 0, 3, PageNumbering(position = pos), null, bitonal = false)
            val top = pos.name.startsWith("TOP")
            val band = if (top) 0 to 140 else 1414 - 140 to 1414
            val third = when {
                pos.name.endsWith("LEFT") -> 0 to 333
                pos.name.endsWith("RIGHT") -> 667 to 1000
                else -> 333 to 667
            }
            assertTrue("$pos drawn", dark(b, third.first, band.first, third.second, band.second) > 20)
            assertEquals("$pos nothing elsewhere", 0, dark(b, 0, 300, 1000, 1100))
        }
        val w = Bitmap.createBitmap(1000, 1414, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        decor.draw(w, 0, 1, null, Watermark("سري — Confidential", opacity = 0.4f), bitonal = false)
        assertTrue("watermark across the centre", dark(w, 300, 500, 700, 900, below = 245) > 500)
        val px = w.getPixel(500, 707)
        assertTrue("translucent, not black", Color.red(px) > 60)
    }

    private fun validate(pdf: File): List<String> {
        VeraGreenfieldFoundryProvider.initialise()
        Foundries.defaultInstance().createParser(pdf.inputStream(), PDFAFlavour.PDFA_2_B).use { parser ->
            val result = Foundries.defaultInstance().createValidator(PDFAFlavour.PDFA_2_B, false).validate(parser)
            if (result.isCompliant) return emptyList()
            return listOf("not compliant") + result.testAssertions.filter { it.status.toString() == "FAILED" }
                .map { "${it.ruleId.clause} #${it.ruleId.testNumber}: ${it.message}" }.distinct()
        }
    }

    @Test
    fun pdfA2bWithPageNumbersAndWatermarkPassesVeraPdf() = runBlocking {
        val store = DocumentStore(File(ctx.filesDir, "decor-${System.nanoTime()}"))
        val doc = ExportSeed.doc(store, "أرشيف مرقّم", 3, seed = 2)
        val opt = ExportOptions(
            PageSize.A4, Quality.SMALL, archival = true,
            pageNumbers = PageNumbering(NumberPosition.BOTTOM_CENTER, arabicDigits = true, start = 1, withTotal = true),
            watermark = Watermark("نسخة للمراجعة", 0.3f, diagonal = true),
        )
        val pdf = Exporter(ctx, store).exportPdf(doc, opt) { _, _ -> }
        val failures = validate(pdf)
        assertTrue("PDF/A-2b failures:\n" + failures.joinToString("\n"), failures.isEmpty())
        // The same with the JPEG path and the bitonal page (every third page is black and white).
        val jpgs = Exporter(ctx, store).exportJpegs(doc, opt) { _, _ -> }
        assertEquals(3, jpgs.size)
    }
}
