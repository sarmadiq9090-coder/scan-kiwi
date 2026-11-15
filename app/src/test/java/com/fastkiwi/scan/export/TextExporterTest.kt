// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.export

import com.fastkiwi.scan.core.OcrWord
import com.fastkiwi.scan.core.Quad
import com.fastkiwi.scan.data.DocumentStore
import com.fastkiwi.scan.data.ScanPage
import com.fastkiwi.scan.data.TextOverlay
import com.fastkiwi.scan.search.SynthCorpus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.random.Random

/** OCR-08 TXT export. */
class TextExporterTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val header = { n: Int -> "— صفحة $n —" }

    /**
     * Acceptance (ROADMAP 1.6.0): the TXT of benchmark-like pages (every category of the OCR test
     * set: Arabic prose, vocalised text, Arabic-Indic and Western numbers, amounts, dates, mixed
     * Arabic/English, tables) equals the engine output byte for byte: the exporter does not
     * reorder words, reshape, or add direction marks.
     */
    @Test
    fun txtEqualsEngineOutputByteForByte() {
        val store = DocumentStore(tmp.root)
        val r = Random(52)
        val engineOutput = ArrayList<String>()
        val pages = ArrayList<ScanPage>()
        var doc = store.create("benchmark")
        // 52 pages like the benchmark set: the categories in turn.
        for (i in 0 until 52) {
            val cat = SynthCorpus.Category.entries[i % SynthCorpus.Category.entries.size]
            val lines = SynthCorpus.lines(cat, r)
            val words: List<OcrWord> = SynthCorpus.words(lines)
            val p = ScanPage(DocumentStore.newId(), Quad.full(1f, 1f))
            store.saveOcr(doc.id, p, words, "ara+eng", 2000, 2828)
            // What the engine read: its words in its order, one line per text line.
            engineOutput.add(lines.joinToString("\n"))
            pages.add(p)
        }
        doc = store.save(doc.copy(pages = pages))
        val txt = runBlocking { TextExporter(store).text(doc, header) }
        val expected = engineOutput.withIndex().joinToString("\n") { (i, t) -> header(i + 1) + "\n" + t + "\n" }
        assertArrayEquals(expected.toByteArray(Charsets.UTF_8), txt.toByteArray(Charsets.UTF_8))

        // Each page body, cut out of the file, is exactly the engine text.
        val bodies = txt.split(Regex("(?m)^— صفحة \\d+ —\n")).drop(1).map { it.removeSuffix("\n").removeSuffix("\n") }
        assertEquals(engineOutput, bodies)

        val f = TextExporter(store).write(txt, tmp.newFolder("out"), "benchmark")
        val bytes = f.readBytes()
        assertFalse("no BOM", bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte())
        assertFalse("no direction marks added", String(bytes, Charsets.UTF_8).any { it in "‎‏؜‪‫‬⁦⁧⁨⁩" })
        assertArrayEquals(expected.toByteArray(Charsets.UTF_8), bytes)
    }

    @Test
    fun singlePageHasNoHeaderAndCorrectionsAreExported() {
        val store = DocumentStore(tmp.root)
        val p = ScanPage("abcd", Quad.full(1f, 1f))
        val doc = store.save(store.create("one").copy(pages = listOf(p)))
        val words = SynthCorpus.words(listOf("رقم الفاتوره ٤٥٦", "Total 1,250.00 USD"))
        store.saveOcr(doc.id, p, words, "ara+eng", 2000, 2828)
        assertEquals("رقم الفاتوره ٤٥٦\nTotal 1,250.00 USD\n", runBlocking { TextExporter(store).text(doc, header) })
        val base = words.map { it.text }
        store.saveTextOverlay(doc.id, p.id, TextOverlay(0, base, TextOverlay.diff(base, "رقم الفاتورة ٤٥٦ Total 1,250.00 USD")))
        assertEquals("رقم الفاتورة ٤٥٦\nTotal 1,250.00 USD\n", runBlocking { TextExporter(store).text(doc, header) })
    }

    @Test
    fun missingPagesAreReadOnceAndEmptyPagesKeepTheirHeader() {
        val store = DocumentStore(tmp.root)
        val p1 = ScanPage("aaaa", Quad.full(1f, 1f))
        val p2 = ScanPage("bbbb", Quad.full(1f, 1f))
        val doc = store.save(store.create("two").copy(pages = listOf(p1, p2)))
        var reads = 0
        val reader = TextExporter.MissingText { d, page ->
            reads++
            if (page.id == "aaaa") SynthCorpus.words(listOf("نص مقروء")).also { store.saveOcr(d.id, page, it, "ara+eng", 2000, 2828) } else null
        }
        val txt = runBlocking { TextExporter(store).text(doc, header, reader) }
        assertEquals("— صفحة 1 —\nنص مقروء\n\n— صفحة 2 —\n\n", txt)
        assertEquals(2, reads)
        runBlocking { TextExporter(store).text(doc, header, reader) }
        assertEquals("cached page not read again", 3, reads)
    }
}
