// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.search

import com.fastkiwi.scan.core.Quad
import com.fastkiwi.scan.data.DocumentStore
import com.fastkiwi.scan.data.ScanDocument
import com.fastkiwi.scan.data.ScanPage
import com.fastkiwi.scan.data.TextOverlay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import kotlin.random.Random

/**
 * OCR-07 search index (SQLite FTS4). Robolectric runs the platform SQLite in native mode
 * (SQLite 3.44 with FTS3/FTS4 compiled in, as on Android), so these tests use the real FTS4
 * tokenizer and MATCH, not a stand-in.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SearchIndexTest {
    private val ctx = RuntimeEnvironment.getApplication()
    private var n = 0
    private fun root() = File(ctx.filesDir, "search-test-${n++}-${System.nanoTime()}").apply { mkdirs() }

    private class Corpus(val store: DocumentStore, val docs: List<ScanDocument>, val text: Map<Pair<String, String>, List<String>>)

    /** [docs] synthetic documents of [pagesPerDoc] pages, OCR cached as the export would. */
    private fun corpus(root: File, docs: Int, pagesPerDoc: (Int) -> Int, seed: Int = 20261115): Corpus {
        val r = Random(seed)
        val store = DocumentStore(root)
        val cats = SynthCorpus.Category.entries
        val text = HashMap<Pair<String, String>, List<String>>()
        val out = ArrayList<ScanDocument>()
        for (i in 0 until docs) {
            val d = store.create("مستند تجريبي $i")
            val pages = (0 until pagesPerDoc(i)).map { j ->
                val p = ScanPage(DocumentStore.newId(), Quad.full(1f, 1f))
                val lines = SynthCorpus.lines(cats[(i + j) % cats.size], r)
                store.saveOcr(d.id, p, SynthCorpus.words(lines), "ara+eng", 2000, 2828)
                text[d.id to p.id] = lines
                p
            }
            out.add(store.save(d.copy(pages = pages)))
        }
        return Corpus(store, out, text)
    }

    private fun refTokens(s: String) = SynthCorpus.referenceFold(s).split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }

    /**
     * Acceptance (ROADMAP 1.6.0): recall ≥ 95 % on 200 synthetic documents for queries typed
     * without harakat or with alef / ya (and ta marbuta, digit) variants, judged against the
     * ground-truth text with an independent reference fold.
     */
    @Test
    fun recallOn200SyntheticDocumentsWithTypedVariants() {
        val c = corpus(root(), 200, { 1 + it % 3 })
        val index = SearchIndex(File(c.store.dir("x").parentFile!!.parentFile, "search.db"))
        index.sync(c.store, c.docs)
        val r = Random(7)
        // Ground truth: page key -> set of reference tokens.
        val truth = c.text.mapValues { (_, lines) -> lines.flatMap { refTokens(it) }.toSet() }
        var relevant = 0
        var found = 0
        var queries = 0
        var variantQueries = 0
        val misses = ArrayList<String>()
        for (d in c.docs) {
            repeat(5) { q ->
                val p = d.pages[r.nextInt(d.pages.size)]
                val words = c.text[d.id to p.id]!!.flatMap { it.split(' ') }.filter { w -> w.count { it.isLetterOrDigit() } >= 3 }
                val word = words[r.nextInt(words.size)]
                val second = if (q == 4) words[r.nextInt(words.size)] else null
                val query = listOfNotNull(word, second).joinToString(" ") { SynthCorpus.typed(it, r.nextInt(6)) }
                if (query != listOfNotNull(word, second).joinToString(" ")) variantQueries++
                queries++
                val qt = refTokens(query)
                val rel = truth.filterValues { toks -> qt.all { it in toks } }.keys
                val hits = index.search(query, limit = 10_000).map { it.docId to it.pageId }.toSet()
                relevant += rel.size
                val f = rel.count { it in hits }
                found += f
                if (f < rel.size && misses.size < 10) misses.add("$query ($word)")
            }
        }
        val recall = found.toDouble() / relevant
        File("build/docs-metrics").mkdirs()
        File("build/docs-metrics/search-recall.txt").writeText(
            "documents=200 pages=${c.text.size} queries=$queries (typed with a variant: $variantQueries) relevant=$relevant found=$found recall=${"%.4f".format(recall)}\nmisses: $misses\n",
        )
        assertTrue("recall $recall < 0.95; misses: $misses", recall >= 0.95)
        assertTrue("most queries must actually use a variant", variantQueries >= queries / 2)
    }

    /** Acceptance (ROADMAP 1.6.0): a query over 1,000 pages answers in ≤ 300 ms on the JVM. */
    @Test
    fun queryOver1000PagesIsFast() {
        val c = corpus(root(), 200, { 5 }, seed = 99)
        val index = SearchIndex(File(c.store.dir("x").parentFile!!.parentFile, "search.db"))
        val t0 = System.nanoTime()
        val indexed = index.sync(c.store, c.docs)
        val buildMs = (System.nanoTime() - t0) / 1_000_000
        assertEquals(1000, indexed)
        assertEquals(1000, index.pageCount())
        val queries = listOf("الفاتوره", "اجمالي", "رسوم", "2026", "٢٠٢٤", "مستشفي", "الطالبه", "PDF", "شقه سكنيه", "ريال", "اسعار", "ا", "تاريخ الاصدار", "ذهب الموظف")
        index.search("warm up")
        val times = queries.map { q ->
            val s = System.nanoTime()
            val hits = index.search(q, limit = 10_000)
            ((System.nanoTime() - s) / 1_000_000.0) to hits.size
        }
        val worst = times.maxOf { it.first }
        // Re-sync with nothing changed only checks stamps.
        val t1 = System.nanoTime()
        assertEquals(0, index.sync(c.store, c.docs))
        val resyncMs = (System.nanoTime() - t1) / 1_000_000
        File("build/docs-metrics").mkdirs()
        File("build/docs-metrics/search-speed.txt").writeText(
            "pages=1000 build=${buildMs}ms resync-unchanged=${resyncMs}ms worst-query=${"%.1f".format(worst)}ms median=${"%.1f".format(times.map { it.first }.sorted()[times.size / 2])}ms\n" +
                queries.zip(times).joinToString("\n") { (q, t) -> "  $q: ${"%.1f".format(t.first)} ms, ${t.second} pages" } + "\n",
        )
        assertTrue("worst query $worst ms", worst <= 300.0)
        assertTrue("every query finds pages", times.filter { it.second == 0 }.size <= 1)
    }

    /** The index follows OCR saves, corrections, page and document deletes, and rebuilds itself. */
    @Test
    fun indexStaysInSyncAndRebuildsFromCache() {
        val root = root()
        val store = DocumentStore(root)
        val dbFile = File(root, "search/search.db")
        var index = SearchIndex(dbFile)
        val p1 = ScanPage("aaaa", Quad.full(1f, 1f))
        val p2 = ScanPage("bbbb", Quad.full(1f, 1f))
        var doc = store.save(store.create("عقد").copy(pages = listOf(p1, p2)))
        index.sync(store, listOf(doc))
        assertTrue("no OCR yet", index.search("ايجار").isEmpty())

        // OCR saved by any path (export, background) is picked up by the next sync.
        store.saveOcr(doc.id, p1, SynthCorpus.words(listOf("عقد إيجار شقة سكنية")), "ara+eng", 2000, 2828)
        store.saveOcr(doc.id, p2, SynthCorpus.words(listOf("يلتزم المستأجر بالدفع")), "ara+eng", 2000, 2828)
        index.syncDoc(store, doc)
        assertEquals(listOf(SearchIndex.Hit(doc.id, "aaaa")), index.search("ايجار"))
        assertEquals(listOf(SearchIndex.Hit(doc.id, "bbbb")), index.search("مستاجر"))

        // A correction replaces the word in the index.
        val base = store.pageWords(doc.id, p1)!!.base.map { it.text }
        store.saveTextOverlay(doc.id, p1.id, TextOverlay(p1.revision, base, TextOverlay.diff(base, "عقد تمليك شقة سكنية")))
        index.syncDoc(store, doc)
        assertTrue(index.search("ايجار").isEmpty())
        assertEquals(listOf(SearchIndex.Hit(doc.id, "aaaa")), index.search("تمليك"))

        // Page deleted.
        doc = store.save(doc.copy(pages = listOf(p1)))
        index.syncDoc(store, doc)
        assertTrue(index.search("مستاجر").isEmpty())

        // New revision (crop / rotate / filter): the old OCR no longer counts.
        doc = store.save(doc.copy(pages = listOf(p1.copy(revision = 1))))
        index.syncDoc(store, doc)
        assertTrue(index.search("تمليك").isEmpty())
        store.saveOcr(doc.id, doc.pages[0], SynthCorpus.words(listOf("عقد تمليك")), "ara+eng", 2000, 2828)

        // Index file deleted: rebuilt from the OCR caches.
        index.close()
        dbFile.delete()
        index = SearchIndex(dbFile)
        index.sync(store, store.list())
        assertEquals(1, index.search("تمليك").size)

        // Older schema version: dropped and rebuilt.
        index.close()
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { it.version = 1 }
        index = SearchIndex(dbFile)
        assertEquals(0, index.pageCount())
        index.sync(store, store.list())
        assertEquals(1, index.search("تمليك").size)

        // Damaged file: replaced, not a crash.
        index.close()
        dbFile.writeText("this is not a database")
        index = SearchIndex(dbFile)
        index.sync(store, store.list())
        assertEquals(1, index.search("تمليك").size)

        // Document deleted.
        store.delete(doc.id)
        index.sync(store, store.list())
        assertTrue(index.search("تمليك").isEmpty())
        assertEquals(0, index.pageCount())
        index.close()
    }
}
