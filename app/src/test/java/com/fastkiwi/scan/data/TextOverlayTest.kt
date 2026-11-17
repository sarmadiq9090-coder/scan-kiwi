// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.data

import com.fastkiwi.scan.core.OcrWord
import com.fastkiwi.scan.core.Quad
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** OCR-10: corrections are an overlay on the OCR words, applied where cached words are read. */
class TextOverlayTest {
    @get:Rule
    val tmp = TemporaryFolder()

    /** Words of one line, right to left as an Arabic line is laid out, logical order kept. */
    private fun line(y: Int, vararg texts: String): List<OcrWord> =
        texts.mapIndexed { i, t -> OcrWord(t, 2000 - (i + 1) * 300, y, 2000 - i * 300 - 20, y + 60) }

    private fun texts(w: List<OcrWord>) = w.map { it.text }

    @Test
    fun oneWordFixKeepsEveryBox() {
        val words = line(100, "فاتورة", "الكهرباه", "لشهر", "مارس")
        val edits = TextOverlay.diff(texts(words), "فاتورة الكهرباء لشهر مارس")
        assertEquals(mapOf(1 to "الكهرباء"), edits)
        val out = TextOverlay(0, texts(words), edits).apply(words)
        assertEquals(listOf("فاتورة", "الكهرباء", "لشهر", "مارس"), texts(out))
        // The corrected word keeps its box.
        assertEquals(words.map { listOf(it.left, it.top, it.right, it.bottom) }, out.map { listOf(it.left, it.top, it.right, it.bottom) })
    }

    @Test
    fun insertDeleteAndSplitAreAttachedToNeighbouringBoxes() {
        val base = listOf("a", "b", "c", "d")
        // Delete "b", insert "x" after "c", split "d" into "d1 d2": the words read in order, "a" untouched.
        val mixed = TextOverlay.diff(base, "a c x d1 d2")
        assertFalse(0 in mixed)
        val words = base.mapIndexed { i, t -> OcrWord(t, i * 10, 0, i * 10 + 8, 10) }
        assertEquals("a c x d1 d2", TextOverlay(0, base, mixed).apply(words).joinToString(" ") { it.text })
        // Insert before the first word: shares the first word's box.
        assertEquals(mapOf(0 to "z a"), TextOverlay.diff(base, "z a b c d"))
        // Insert at the end: joins the last word.
        assertEquals(mapOf(3 to "d e"), TextOverlay.diff(base, "a b c d e"))
        // Unchanged text (other spacing / line breaks): no edits.
        assertTrue(TextOverlay.diff(base, "a  b\nc\td").isEmpty())
        // Everything deleted.
        assertEquals(mapOf(0 to "", 1 to "", 2 to "", 3 to ""), TextOverlay.diff(base, ""))
        // No word at all: no box to carry text.
        assertTrue(TextOverlay.diff(emptyList(), "new").isEmpty())
    }

    @Test
    fun appliesOnlyToTheOcrResultItWasMadeOn() {
        val words = line(100, "one", "tow")
        val ov = TextOverlay(3, texts(words), mapOf(1 to "two"))
        assertTrue(ov.appliesTo(3, words))
        assertFalse("new revision", ov.appliesTo(4, words))
        assertFalse("other OCR result", ov.appliesTo(3, line(100, "one", "tow", "more")))
        val back = TextOverlay.fromJson(JSONObject(ov.toJson().toString()))
        assertEquals(ov, back)
    }

    /** The store applies the overlay inside loadOcr: export, search and copy all see it; a new revision drops it. */
    @Test
    fun storeAppliesOverlayAndDropsItOnNewRevision() {
        val store = DocumentStore(tmp.root)
        val page = ScanPage("abcd", Quad.full(1f, 1f), revision = 2)
        val doc = store.save(store.create("t").copy(pages = listOf(page)))
        val words = line(100, "المبلغ", "المستحق", "١٢٠٠")
        store.saveOcr(doc.id, page, words, "ara+eng", 2000, 2800)
        val base = texts(words)
        store.saveTextOverlay(doc.id, page.id, TextOverlay(2, base, TextOverlay.diff(base, "المبلغ المستحق ١٥٠٠")))

        assertEquals("١٥٠٠", store.loadOcr(doc.id, page)!![2].text)
        assertEquals("export path (with language) sees it", "١٥٠٠", store.loadOcrFrame(doc.id, page, "ara+eng")!!.words[2].text)
        assertEquals("raw cache untouched", "١٢٠٠", store.loadOcr(doc.id, page, corrected = false)!![2].text)
        assertEquals("١٥٠٠", store.pageWords(doc.id, page)!!.corrected[2].text)

        // Copies (merge, copy to folder) carry the correction.
        val other = store.create("copy")
        val copied = store.copyPage(doc.id, page, other.id)
        assertEquals("١٥٠٠", store.loadOcr(other.id, copied)!![2].text)

        // The page itself changes (new revision): OCR is stale and the correction no longer applies.
        val changed = page.copy(revision = 3)
        assertNull(store.loadOcr(doc.id, changed))
        store.saveOcr(doc.id, changed, words, "ara+eng", 2000, 2800)
        assertEquals("١٢٠٠", store.loadOcr(doc.id, changed)!![2].text)
        assertNull(store.pageWords(doc.id, changed)!!.overlay)

        // Garbage collection keeps a live page's overlay and removes an orphan's.
        val orphan = java.io.File(store.dir(doc.id), "p_ffff_text.json").apply { writeText("{}"); setLastModified(System.currentTimeMillis() - 60_000) }
        store.collectGarbage(olderThan = System.currentTimeMillis() - 2_000)
        assertFalse(orphan.exists())
        assertTrue(store.loadTextOverlay(doc.id, page.id) != null)
        store.deletePageFiles(doc.id, page.id)
        assertNull(store.loadTextOverlay(doc.id, page.id))
    }

    @Test
    fun pageTextKeepsEngineOrderAndBreaksLines() {
        val words = line(100, "تاريخ", "الفاتورة:", "2026/03/14") + line(200, "Total:", "1,250.00", "USD") + line(300, "شكرا")
        assertEquals("تاريخ الفاتورة: 2026/03/14\nTotal: 1,250.00 USD\nشكرا", PageText.text(words))
        assertEquals(3, PageText.lines(words).size)
    }

    /**
     * S3 (torture gate): a later export re-reads the page (another language or engine) and saves different
     * words for the same revision. The user's corrections stay: the corrected text is authoritative, a new
     * correction builds on it instead of starting from the raw text, and the overlay is never hidden.
     */
    @Test
    fun aLaterExportKeepsTheUsersCorrectionsAuthoritative() {
        val store = DocumentStore(tmp.root)
        val page = ScanPage("abcd", Quad.full(1f, 1f), revision = 2)
        val doc = store.save(store.create("t").copy(pages = listOf(page)))
        val first = line(100, "المبلغ", "المستحق", "١٢٠٠")
        store.saveOcr(doc.id, page, first, "ara", 2000, 2800)
        store.saveTextOverlay(doc.id, page.id, TextOverlay(2, texts(first), TextOverlay.diff(texts(first), "المبلغ المستحق ١٥٠٠")))

        // The export reads again and gets other words (one misread, two extra words).
        val again = line(100, "المبلغ", "المستحقة", "١٢٠٠", "شكرا", "جزيلا")
        store.saveOcr(doc.id, page, again, "ara+eng", 2000, 2800)
        assertEquals("المبلغ المستحق ١٥٠٠", PageText.text(store.loadOcr(doc.id, page)!!))
        assertEquals("export path sees the corrections", "المبلغ المستحق ١٥٠٠", PageText.text(store.loadOcrFrame(doc.id, page, "ara+eng")!!.words))
        val pw = store.pageWords(doc.id, page)!!
        assertTrue("overlay still applies, the editor shows the corrected text", pw.overlay != null)
        assertEquals("المبلغ المستحق ١٥٠٠", PageText.text(pw.corrected))
        assertEquals("the raw cache is the new reading", "المبلغ المستحقة ١٢٠٠ شكرا جزيلا", PageText.text(store.loadOcr(doc.id, page, corrected = false)!!))

        // A new correction made on the new words keeps the old one (the editor started from the corrected text).
        val base2 = pw.base.map { it.text }
        store.saveTextOverlay(doc.id, page.id, TextOverlay(2, base2, TextOverlay.diff(base2, "المبلغ المستحق ١٥٠٠ فقط")))
        assertEquals("المبلغ المستحق ١٥٠٠ فقط", PageText.text(store.loadOcr(doc.id, page)!!))

        // A new revision of the page still drops the correction (it belonged to the old picture).
        val changed = page.copy(revision = 3)
        store.saveOcr(doc.id, changed, again, "ara", 2000, 2800)
        assertEquals("المبلغ المستحقة ١٢٠٠ شكرا جزيلا", PageText.text(store.loadOcr(doc.id, changed)!!))
    }

    @Test
    fun anEmptyRereadNeverWipesCorrections() {
        val store = DocumentStore(tmp.root)
        val page = ScanPage("abcd", Quad.full(1f, 1f), revision = 0)
        val doc = store.save(store.create("t").copy(pages = listOf(page)))
        val first = line(100, "one", "tow")
        store.saveOcr(doc.id, page, first, "eng", 2000, 2800)
        store.saveTextOverlay(doc.id, page.id, TextOverlay(0, texts(first), mapOf(1 to "two")))
        store.saveOcr(doc.id, page, emptyList(), "eng", 2000, 2800)
        assertEquals("one two", PageText.text(store.loadOcr(doc.id, page)!!))
    }
}
