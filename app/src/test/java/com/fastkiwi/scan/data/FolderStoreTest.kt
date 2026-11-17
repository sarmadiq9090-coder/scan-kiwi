// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.data

import com.fastkiwi.scan.core.Quad
import com.fastkiwi.scan.ui.DocEntry
import com.fastkiwi.scan.ui.DocSort
import com.fastkiwi.scan.ui.homeEntries
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.text.Collator
import java.util.Locale

/** Folders (A3, 2.0): migration from 1.x, delete never deletes documents, move / sort. */
class FolderStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    /** A doc.json exactly as 1.0–1.2 wrote it: no favorite, no folderId. */
    private fun write1x(root: File, id: String, name: String, modified: Long) {
        val dir = File(root, "documents/$id").apply { mkdirs() }
        File(dir, "doc.json").writeText(
            """{"version":1,"id":"$id","name":"$name","created":$modified,"modified":$modified,""" +
                """"pages":[{"id":"aa$id","corners":[0,0,1,0,1,1,0,1],"rotation":0,"filter":"ENHANCED","revision":0,"stamps":[]}]}""",
        )
    }

    @Test
    fun migrationFrom1xKeepsEveryDocumentAtTopLevel() {
        val names = (1..25).map { "مستند $it" }
        names.forEachIndexed { i, n -> write1x(tmp.root, "%04x".format(i + 1), n, 1_700_000_000_000L + i) }
        val store = DocumentStore(tmp.root)
        val folders = FolderStore(tmp.root)
        assertTrue("no folders.json in 1.x", folders.list().isEmpty())
        val docs = store.list()
        assertEquals(25, docs.size)
        assertEquals(names.toSet(), docs.map { it.name }.toSet())
        assertTrue(docs.all { it.folderId == null && !it.favorite && it.pages.size == 1 })
        // Every one of them is shown on Home (top level).
        val entries = docs.map { DocEntry(it, "") }
        assertEquals(25, homeEntries(entries, folders.list(), null, false, DocSort.DATE, Collator.getInstance()).size)
        // A 2.0 save keeps the 1.x fields and adds the new ones.
        val d = store.save(docs[0].copy(favorite = true), touch = false)
        val back = store.load(d.id)!!
        assertTrue(back.favorite)
        assertEquals(docs[0].modified, back.modified)
        assertEquals(docs[0].pages, back.pages)
    }

    @Test
    fun deletingAFolderNeverDeletesItsDocuments() {
        val store = DocumentStore(tmp.root)
        val folders = FolderStore(tmp.root)
        val bills = folders.create("فواتير")
        val other = folders.create("عقود")
        val inBills = (1..3).map { store.save(store.create("فاتورة $it").copy(folderId = bills.id, pages = listOf(ScanPage("b$it", Quad.full(1f, 1f))))) }
        val inOther = store.save(store.create("عقد").copy(folderId = other.id))
        val top = store.save(store.create("جواز"))
        val before = store.list().associate { it.id to it.modified }

        val moved = folders.delete(bills.id, store)

        assertEquals(3, moved)
        assertEquals(listOf(other), folders.list())
        val after = store.list()
        assertEquals("no document lost", 5, after.size)
        for (d in inBills) {
            val now = store.load(d.id)!!
            assertNull("moved to top level", now.folderId)
            assertEquals("pages kept", d.pages, now.pages)
            assertEquals("not shown as edited", before[d.id], now.modified)
        }
        assertEquals(other.id, store.load(inOther.id)!!.folderId)
        assertNull(store.load(top.id)!!.folderId)
    }

    @Test
    fun documentInAMissingFolderIsShownAtTopLevel() {
        val store = DocumentStore(tmp.root)
        val folders = FolderStore(tmp.root)
        val d = store.save(store.create("يتيم").copy(folderId = "gone"))
        val shown = homeEntries(listOf(DocEntry(d, "")), folders.list(), null, false, DocSort.DATE, Collator.getInstance())
        assertEquals(listOf(d.id), shown.map { it.doc.id })
    }

    @Test
    fun renamePersistsAndCorruptFileIsNotFatal() {
        val folders = FolderStore(tmp.root)
        val f = folders.create("  أ  ")
        folders.rename(f.id, "ب")
        assertEquals("ب", FolderStore(tmp.root).list().single().name)
        folders.rename(f.id, "   ")
        assertEquals("blank names are ignored", "ب", folders.list().single().name)
        // No last good copy (a folders.json from before the backup existed): damaged means empty, never a crash.
        File(tmp.root, "folders.json.bak").delete()
        File(tmp.root, "folders.json").writeText("{not json")
        assertTrue(FolderStore(tmp.root).list().isEmpty())
        assertFalse(File(tmp.root, "folders.json.tmp").exists())
    }

    @Test
    fun sortByDateNameAndSize() {
        fun e(name: String, modified: Long, bytes: Long, folder: String? = null, fav: Boolean = false) =
            DocEntry(ScanDocument(name, name, 0, modified, emptyList(), favorite = fav, folderId = folder), "", bytes)
        val f = Folder("f1", "x", 0)
        val all = listOf(e("ب", 3, 10), e("أ", 1, 30), e("ج", 2, 20, fav = true), e("د", 9, 99, folder = "f1", fav = true))
        val c = Collator.getInstance(Locale.forLanguageTag("ar"))
        assertEquals(listOf("ب", "ج", "أ"), homeEntries(all, listOf(f), null, false, DocSort.DATE, c).map { it.doc.name })
        assertEquals(listOf("أ", "ب", "ج"), homeEntries(all, listOf(f), null, false, DocSort.NAME, c).map { it.doc.name })
        assertEquals(listOf("أ", "ج", "ب"), homeEntries(all, listOf(f), null, false, DocSort.SIZE, c).map { it.doc.name })
        assertEquals(listOf("د"), homeEntries(all, listOf(f), "f1", false, DocSort.DATE, c).map { it.doc.name })
        // Favorites at the top level gather starred documents from every folder.
        assertEquals(listOf("د", "ج"), homeEntries(all, listOf(f), null, true, DocSort.DATE, c).map { it.doc.name })
    }

    /** S3 (torture gate): a damaged folders.json must not lose the folder names; the last good copy comes back. */
    @Test
    fun aDamagedFoldersFileIsRestoredFromTheLastGoodCopy() {
        val folders = FolderStore(tmp.root)
        val a = folders.create("فواتير")
        val b = folders.create("Receipts")
        folders.rename(a.id, "فواتير ٢٠٢٦")
        val expected = folders.list()
        assertEquals(2, expected.size)
        val file = File(tmp.root, "folders.json")
        assertTrue("a backup is kept beside the file", File(tmp.root, "folders.json.bak").exists())
        for (damage in listOf("", "{\"version\":1,\"fold", "not json at all", "{\"version\":1}")) {
            file.writeText(damage)
            assertEquals("damage: <$damage>", expected, FolderStore(tmp.root).list())
            assertTrue("the main file is repaired too", file.length() > 20)
            assertEquals(expected, FolderStore(tmp.root).list())
        }
        // The main file vanished altogether: the backup brings it back.
        file.delete()
        assertEquals(expected, FolderStore(tmp.root).list())
        // Work goes on normally afterwards and the backup follows.
        FolderStore(tmp.root).delete(b.id, DocumentStore(tmp.root))
        file.writeText("garbage")
        assertEquals(listOf(a.id), FolderStore(tmp.root).list().map { it.id })
        // Both damaged: empty, never a crash.
        file.writeText("x"); File(tmp.root, "folders.json.bak").writeText("y")
        assertTrue(FolderStore(tmp.root).list().isEmpty())
    }
}
