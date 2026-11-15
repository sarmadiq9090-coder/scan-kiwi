// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.pdf

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
import java.io.File
import kotlin.random.Random

/**
 * ROADMAP 1.4.0 acceptance for external PDFs, through the same code path the `:pdf` process
 * runs ([PdfProtocol.handle] behind [ExternalPdfEngine]); results are checked with desktop
 * Apache PDFBox 3, an independent reader.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PdfOpsAcceptanceTest {
    private val ctx = RuntimeEnvironment.getApplication()
    private val dir = File(ctx.cacheDir, "acc-${System.nanoTime()}").apply { mkdirs() }
    private val out = File(dir, "out").apply { mkdirs() }
    private val ops = PdfBoxOps(ctx)
    private val engine = ExternalPdfEngine(InProcessChannel(ops), out)

    /**
     * Merge of 3 PDFs (ours + 2 external synthetic, 1–50 pages): page count = sum, order kept,
     * every page's content streams and images byte-identical (raw, still-encoded bytes).
     */
    @Test
    fun mergeOfOursAndTwoExternalKeepsCountOrderAndBytes() = runBlocking {
        val rnd = Random(14)
        repeat(3) { round ->
            val a = SyntheticPdfs.ours(dir, "O$round", 1 + rnd.nextInt(8))
            val b = SyntheticPdfs.external(dir, "A$round", 1 + rnd.nextInt(50), seed = round * 10 + 1)
            val c = SyntheticPdfs.external(dir, "B$round", 1 + rnd.nextInt(50), seed = round * 10 + 2)
            val inputs = listOf(a, b, c)
            val before = inputs.map { SyntheticPdfs.sha256(it) }
            val counts = inputs.map { engine.pageCount(PdfFile(it)) }
            assertTrue(inputs.flatMap { SyntheticPdfs.markers(it) }.none { it.isEmpty() })
            val merged = engine.assemble(inputs.map { PdfFile(it) }, PagePlans.merge(counts), "merged-$round.pdf").file
            assertEquals(counts.sum(), Loader.loadPDF(merged).use { it.numberOfPages })
            assertEquals(inputs.flatMap { SyntheticPdfs.markers(it) }, SyntheticPdfs.markers(merged))
            assertEquals(inputs.flatMap { SyntheticPdfs.pageFingerprints(it) }, SyntheticPdfs.pageFingerprints(merged))
            assertEquals(inputs.flatMap { SyntheticPdfs.rotations(it) }, SyntheticPdfs.rotations(merged))
            assertEquals("inputs are never changed", before, inputs.map { SyntheticPdfs.sha256(it) })
            PdfValidator.validate(merged, counts.sum())
        }
    }

    /** Split / extract: exactly the selected pages, in order, for 30 random selections; no other page leaks in. */
    @Test
    fun splitAndExtractGiveExactlyTheSelectedPages() = runBlocking {
        val rnd = Random(30)
        repeat(30) { round ->
            val n = 1 + rnd.nextInt(50)
            val src = SyntheticPdfs.external(dir, "S$round", n, seed = 100 + round)
            val all = SyntheticPdfs.markers(src)
            assertTrue("every page readable: $all", all.none { it.isEmpty() })
            val prints = SyntheticPdfs.pageFingerprints(src)
            val pick = (0 until n).filter { rnd.nextInt(3) == 0 }.ifEmpty { listOf(rnd.nextInt(n)) }
            val sel = if (round % 3 == 2) pick.shuffled(rnd) else pick // a third reordered (PDF-04)
            val f = engine.assemble(listOf(PdfFile(src)), PagePlans.select(sel), "x-$round.pdf").file
            assertEquals("round $round", sel.map { all[it] }, SyntheticPdfs.markers(f))
            assertEquals(sel.map { prints[it] }, SyntheticPdfs.pageFingerprints(f))
            assertEquals("no unselected page object leaks in (links)", sel.distinct().size, SyntheticPdfs.pageObjects(f))
        }
        // Split by ranges and every N pages.
        val src = SyntheticPdfs.external(dir, "R", 11, seed = 7)
        val all = SyntheticPdfs.markers(src)
        val groups = PagePlans.parseGroups("١-٣، 5, 9-", 11)
        val parts = PagePlans.split(groups).mapIndexed { i, plan -> engine.assemble(listOf(PdfFile(src)), plan, "part$i.pdf").file }
        assertEquals(listOf(all.subList(0, 3), listOf(all[4]), all.subList(8, 11)), parts.map { SyntheticPdfs.markers(it) })
        val everyFour = PagePlans.everyN(11, 4).map { g -> engine.assemble(listOf(PdfFile(src)), PagePlans.select(g), "n${g.first()}.pdf").file }
        assertEquals(listOf(4, 4, 3), everyFour.map { SyntheticPdfs.markers(it).size })
    }

    @Test
    fun rotateAndBlankPage() = runBlocking {
        val src = SyntheticPdfs.external(dir, "T", 4, seed = 3)
        val rot = SyntheticPdfs.rotations(src)
        val rotated = engine.assemble(listOf(PdfFile(src)), PagePlans.rotate(4, 90, setOf(1, 3)), "rot.pdf").file
        assertEquals(rot.mapIndexed { i, r -> if (i == 1 || i == 3) (r + 90) % 360 else r }, SyntheticPdfs.rotations(rotated))
        // Rotation is the only change: content bytes stay identical.
        assertEquals(SyntheticPdfs.pageFingerprints(src), SyntheticPdfs.pageFingerprints(rotated))
        val withBlank = engine.assemble(listOf(PdfFile(src)), PagePlans.insertBlank(4, 2), "blank.pdf").file
        val m = SyntheticPdfs.markers(withBlank)
        assertEquals(5, m.size)
        assertEquals("", m[2])
    }

    /** PDF-14: protection is removed only with the right password; wrong or missing password is an error. */
    @Test
    fun unlockNeedsTheRightPassword() = runBlocking {
        val src = SyntheticPdfs.external(dir, "P", 3, seed = 5, password = "kiwi-pw")
        try { engine.pageCount(PdfFile(src)); fail() } catch (e: PdfOpException) { assertEquals(PdfError.NEEDS_PASSWORD, e.error) }
        try { engine.pageCount(PdfFile(src, "wrong")); fail() } catch (e: PdfOpException) { assertEquals(PdfError.BAD_PASSWORD, e.error) }
        try { engine.unlock(PdfFile(src), "wrong", "u.pdf"); fail() } catch (e: PdfOpException) { assertEquals(PdfError.BAD_PASSWORD, e.error) }
        assertFalse("no partial output", File(out, "u.pdf").exists() || File(out, "u.pdf.part").exists())
        val open = engine.unlock(PdfFile(src), "kiwi-pw", "u.pdf").file
        Loader.loadPDF(open).use { assertFalse(it.isEncrypted); assertEquals(3, it.numberOfPages) }
        assertEquals(SyntheticPdfs.markers(src, "kiwi-pw"), SyntheticPdfs.markers(open))
        // Merging a protected file with its password works too.
        assertEquals(3, engine.pageCount(PdfFile(src, "kiwi-pw")))
    }

    @Test
    fun textExtractionExternalAndOurs() = runBlocking {
        val ext = SyntheticPdfs.external(dir, "X", 3, seed = 9)
        val t = engine.text(PdfFile(ext))
        assertEquals(3, t.size)
        assertTrue(t[1], t[1].contains("MARK-X-2"))
        val ours = SyntheticPdfs.ours(dir, "Y", 2)
        assertTrue(engine.text(PdfFile(ours))[0].contains("MARK-Y-1"))
        // An Arabic line written right to left: «فاتورة» rightmost, then «رقم», then «2026»;
        // a second line «Total مرحبا» (Latin at the left).
        val font = File("src/main/assets/fonts/glyphless.ttf").readBytes()
        val jpeg = javaClass.getResourceAsStream("/page_color.jpg")!!.readBytes()
        val w = com.fastkiwi.scan.core.PdfWriter(com.fastkiwi.scan.core.PageSize.A4, "ar", null, font)
        w.addPage(com.fastkiwi.scan.core.PdfPage(com.fastkiwi.scan.core.PdfImage.Jpeg(1240, 1754, jpeg, 3), 150f, listOf(
            com.fastkiwi.scan.core.OcrWord("فاتورة", 700, 100, 900, 140), com.fastkiwi.scan.core.OcrWord("رقم", 500, 100, 650, 140),
            com.fastkiwi.scan.core.OcrWord("2026", 300, 100, 450, 140), com.fastkiwi.scan.core.OcrWord("مرحبا", 520, 300, 720, 340),
            com.fastkiwi.scan.core.OcrWord("Kiwi", 100, 300, 300, 340),
        )))
        val ar = File(dir, "ar.pdf").also { f -> f.outputStream().use { w.write(it) } }
        val lines = engine.text(PdfFile(ar))[0].lines().map { it.trim() }.filter { it.isNotEmpty() }
        assertEquals(listOf("فاتورة رقم 2026", "مرحبا Kiwi"), lines)
    }

    @Test
    fun arabicPresentationFormsAreFolded() {
        // «مرحبا» in presentation forms (visual shapes), and the lam-alef ligature «لا».
        assertEquals("مرحبا", ArabicText.fix("ﻣﺮﺤﺒﺎ"))
        assertEquals("لا", ArabicText.fix("ﻻ"))
        // Visual word order (as PDFBox 2.0 gives it) back to logical order.
        assertEquals("فاتورة رقم 2026", ArabicText.fix("2026 رقم فاتورة"))
        assertEquals("المجموع Total 2026 ريال", ArabicText.fix("ريال Total 2026 المجموع"))
        assertEquals("Invoice number 7", ArabicText.fix("Invoice number 7"))
        assertEquals("سطر\nThe end", ArabicText.fix("سطر\nThe end"))
    }
}
