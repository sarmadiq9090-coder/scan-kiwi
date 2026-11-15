// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.search

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import com.fastkiwi.scan.data.DocumentStore
import com.fastkiwi.scan.data.ScanDocument
import java.io.File

/**
 * Full-text index of every page's recognised (and corrected) text: SQLite FTS4 from the platform,
 * no library (OCR-07). One row per page; the indexed body is [ArabicNormalizer.indexTokens] of the
 * page's words, so queries typed without harakat or with alef / ya / ta marbuta variants match.
 *
 * The index is only a cache of the OCR caches: it lives in its own file, carries a schema version
 * ([VERSION], which includes the normaliser's), and is rebuilt from the cached OCR when the file is
 * missing, damaged or from another version. [sync] compares a per-page stamp (revision, OCR cache
 * and correction file times and sizes) so it picks up OCR saved by any path (export, background
 * OCR), corrections, deleted pages and deleted documents.
 */
class SearchIndex(private val file: File) {
    data class Hit(val docId: String, val pageId: String)

    private var db: SQLiteDatabase? = null

    @Synchronized
    private fun db(): SQLiteDatabase {
        db?.let { if (it.isOpen) return it }
        file.parentFile?.mkdirs()
        val d = try {
            openChecked()
        } catch (e: SQLiteException) {
            // A damaged index is only a cache: start again from the OCR caches.
            deleteFiles()
            openChecked()
        }
        db = d
        return d
    }

    private fun openChecked(): SQLiteDatabase {
        val d = SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            if (d.version != VERSION) {
                d.beginTransaction()
                try {
                    d.execSQL("DROP TABLE IF EXISTS pages")
                    d.execSQL("DROP TABLE IF EXISTS page_fts")
                    d.execSQL("CREATE TABLE pages(id INTEGER PRIMARY KEY, doc_id TEXT NOT NULL, page_id TEXT NOT NULL, stamp TEXT NOT NULL, UNIQUE(doc_id, page_id))")
                    d.execSQL("CREATE VIRTUAL TABLE page_fts USING fts4(body, tokenize=simple)")
                    d.version = VERSION
                    d.setTransactionSuccessful()
                } finally {
                    d.endTransaction()
                }
            }
            return d
        } catch (e: SQLiteException) {
            d.close()
            throw e
        }
    }

    private fun deleteFiles() {
        for (suffix in listOf("", "-journal", "-wal", "-shm")) File(file.path + suffix).delete()
    }

    /** Brings one document's rows up to date; returns how many pages were (re)indexed. */
    @Synchronized
    fun syncDoc(store: DocumentStore, doc: ScanDocument): Int {
        val d = db()
        val rows = HashMap<String, Pair<Long, String>>()
        d.rawQuery("SELECT page_id, id, stamp FROM pages WHERE doc_id = ?", arrayOf(doc.id)).use { c ->
            while (c.moveToNext()) rows[c.getString(0)] = c.getLong(1) to c.getString(2)
        }
        var changed = 0
        d.beginTransaction()
        try {
            val live = HashSet<String>()
            for (p in doc.pages) {
                live.add(p.id)
                val stamp = store.searchStamp(doc.id, p)
                val row = rows[p.id]
                if (row != null && row.second == stamp) continue
                val words = store.loadOcr(doc.id, p)
                val body = if (words.isNullOrEmpty()) "" else ArabicNormalizer.indexTokens(words.joinToString(" ") { it.text }).joinToString(" ")
                if (row == null) {
                    d.execSQL("INSERT INTO pages(doc_id, page_id, stamp) VALUES (?, ?, ?)", arrayOf(doc.id, p.id, stamp))
                    val id = d.rawQuery("SELECT last_insert_rowid()", null).use { c -> c.moveToFirst(); c.getLong(0) }
                    d.execSQL("INSERT INTO page_fts(docid, body) VALUES (?, ?)", arrayOf<Any>(id, body))
                } else {
                    d.execSQL("UPDATE pages SET stamp = ? WHERE id = ?", arrayOf<Any>(stamp, row.first))
                    d.execSQL("UPDATE page_fts SET body = ? WHERE docid = ?", arrayOf<Any>(body, row.first))
                }
                changed++
            }
            for ((pageId, row) in rows) if (pageId !in live) {
                d.execSQL("DELETE FROM page_fts WHERE docid = ?", arrayOf<Any>(row.first))
                d.execSQL("DELETE FROM pages WHERE id = ?", arrayOf<Any>(row.first))
            }
            d.setTransactionSuccessful()
        } finally {
            d.endTransaction()
        }
        return changed
    }

    /** Full reconciliation with [docs] (all documents): rows of deleted documents go, changed pages are redone. */
    @Synchronized
    fun sync(store: DocumentStore, docs: List<ScanDocument>): Int {
        val d = db()
        val ids = docs.map { it.id }.toHashSet()
        val stale = ArrayList<String>()
        d.rawQuery("SELECT DISTINCT doc_id FROM pages", null).use { c -> while (c.moveToNext()) if (c.getString(0) !in ids) stale.add(c.getString(0)) }
        stale.forEach { removeDoc(it) }
        return docs.sumOf { syncDoc(store, it) }
    }

    @Synchronized
    fun removeDoc(docId: String) {
        val d = db()
        d.beginTransaction()
        try {
            d.execSQL("DELETE FROM page_fts WHERE docid IN (SELECT id FROM pages WHERE doc_id = ?)", arrayOf(docId))
            d.execSQL("DELETE FROM pages WHERE doc_id = ?", arrayOf(docId))
            d.setTransactionSuccessful()
        } finally {
            d.endTransaction()
        }
    }

    /**
     * Pages whose text contains every word of [query] (each as a word prefix, after normalising).
     * At most [limit] hits, in index order; the caller orders them by document and page.
     */
    @Synchronized
    fun search(query: String, limit: Int = 500): List<Hit> {
        val match = matchExpression(query) ?: return emptyList()
        val out = ArrayList<Hit>()
        db().rawQuery(
            "SELECT p.doc_id, p.page_id FROM page_fts JOIN pages p ON p.id = page_fts.docid WHERE page_fts MATCH ? LIMIT ?",
            arrayOf(match, limit.toString()),
        ).use { c -> while (c.moveToNext()) out.add(Hit(c.getString(0), c.getString(1))) }
        return out
    }

    /** Number of pages in the index (with or without text). */
    @Synchronized
    fun pageCount(): Int = db().rawQuery("SELECT COUNT(*) FROM pages", null).use { c -> c.moveToFirst(); c.getInt(0) }

    @Synchronized
    fun close() {
        db?.close()
        db = null
    }

    companion object {
        /** Table layout version × 1000 + normaliser version: either change rebuilds the index. */
        const val VERSION = 1 * 1000 + ArabicNormalizer.VERSION

        /**
         * FTS4 query: every normalised token as a prefix term, implicitly ANDed. Tokens hold only
         * letters and digits (lower case), so no FTS operator or quote can be injected.
         */
        fun matchExpression(query: String): String? {
            val toks = ArabicNormalizer.tokens(query).take(16)
            if (toks.isEmpty()) return null
            return toks.joinToString(" ") { "$it*" }
        }
    }
}
