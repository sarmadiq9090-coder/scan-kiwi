// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.gates

import com.fastkiwi.scan.core.Filter
import com.fastkiwi.scan.data.ScanPage
import com.fastkiwi.scan.imaging.BitmapIO
import com.fastkiwi.scan.imaging.PageRenderer
import com.fastkiwi.scan.ocr.OcrInput
import com.fastkiwi.scan.ocr.OcrOptions
import com.fastkiwi.scan.ocr.fromBitmap
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The black-box driver's simulated OCR reads each invented sheet as the app's own pipeline crops it. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class SimulatedOcrTest {
    private fun scanned(id: String, seed: Int, filter: Filter): OcrInput {
        val photo = SyntheticPages.photo(id, seed)
        val quad = BitmapIO.detectPage(photo)?.quad ?: BitmapIO.FULL
        val page = PageRenderer.render(photo, ScanPage("t", quad, 0, filter))
        photo.recycle()
        return OcrInput.fromBitmap(page, OcrOptions.DEFAULT_MAX_SIDE).also { page.recycle() }
    }

    @Test
    fun everySheetIsRecognisedFromAPhotoAndTheDeskIsNot() {
        val wrong = ArrayList<String>()
        for (spec in SyntheticPages.catalogue) {
            if (spec.id == "desk" || spec.id == "book-spread") continue
            for ((i, f) in listOf(Filter.ENHANCED, Filter.BW, Filter.ORIGINAL).withIndex()) {
                val input = scanned(spec.id, 300 + i, f)
                val got = SimulatedOcr.identify(input)
                println("SIMOCR ${spec.id} $f -> $got ${SimulatedOcr.lastScore} ${input.width}x${input.height}")
                if (got != spec.id) wrong.add("${spec.id}/$f -> $got")
            }
        }
        assertTrue(wrong.joinToString(), wrong.isEmpty())
        assertNull(SimulatedOcr.identify(scanned("desk", 7, Filter.ENHANCED)))
    }

    @Test
    fun theInvoiceReadsItsWords() {
        val r = runBlocking { SimulatedOcr.recognize(scanned("invoice", 11, Filter.ENHANCED), OcrOptions(listOf(com.fastkiwi.scan.ocr.OcrLang.ARABIC, com.fastkiwi.scan.ocr.OcrLang.ENGLISH))) }
        val text = r.plainText()
        // The page detection leaves out the dark header band («فاتورة / Invoice No. 1047»), so the body is what is read.
        assertTrue(text, text.contains("Toner cartridge") && text.contains("ورق طباعة A4") && text.contains("2,385.50"))
        assertTrue(r.words.all { it.box.left < it.box.right && it.box.right <= r.frameWidth && it.box.bottom <= r.frameHeight })
        assertEquals("book halves", listOf("الفصل الأول", "الفصل الثاني"), listOf(0.5f, 0f).map { x ->
            val (bmp, _) = SyntheticPages.renderWithText("book-spread", 1200)
            val half = android.graphics.Bitmap.createBitmap(bmp, (x * bmp.width).toInt(), 0, bmp.width / 2, bmp.height)
            runBlocking { SimulatedOcr.recognize(OcrInput.fromBitmap(half), OcrOptions(listOf(com.fastkiwi.scan.ocr.OcrLang.ARABIC, com.fastkiwi.scan.ocr.OcrLang.ENGLISH))) }.lines.first().text
        })
    }
}
