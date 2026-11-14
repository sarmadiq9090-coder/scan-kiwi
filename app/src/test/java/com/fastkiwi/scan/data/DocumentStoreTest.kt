// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.data

import com.fastkiwi.scan.core.Quad
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class DocumentStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun page(id: String) = ScanPage(id, Quad.full(1f, 1f))

    /**
     * Regression (1.1.1): garbage collection runs at app start, in parallel with an
     * import started by "Share to Scan Kiwi". 1.1.0 deleted the page files that
     * import had just written (not yet listed in doc.json), losing those pages.
     */
    @Test
    fun garbageCollectionKeepsFilesWrittenSinceStart() {
        val store = DocumentStore(tmp.root)
        val doc = store.save(store.create("a").copy(pages = listOf(page("aaaa"))))
        val started = System.currentTimeMillis()
        // Old leftovers from an earlier session...
        val oldOrphan = store.srcFile(doc.id, "bbbb").apply { writeText("x"); setLastModified(started - 60_000) }
        val oldTmp = File(store.outFile(doc.id, "aaaa").path + ".tmp").apply { writeText("x"); setLastModified(started - 60_000) }
        val oldDocTmp = File(store.dir(doc.id), "doc.json.tmp").apply { writeText("x"); setLastModified(started - 60_000) }
        // ...and a page an import is writing right now.
        val fresh = store.srcFile(doc.id, "cccc").apply { writeText("x") }
        val live = store.srcFile(doc.id, "aaaa").apply { writeText("x"); setLastModified(started - 60_000) }

        val removed = store.collectGarbage(olderThan = started - 2_000)

        assertTrue("fresh page of a running import was deleted", fresh.exists())
        assertTrue(live.exists())
        assertFalse(oldOrphan.exists())
        assertFalse("tmp of a live page left by a crash", oldTmp.exists())
        assertFalse(oldDocTmp.exists())
        assertEquals(3, removed)
    }

    /** A failed write (full disk, crash) must leave the previous file intact. */
    @Test
    fun atomicWriteKeepsOldContentWhenWritingFails() {
        val f = File(tmp.root, "doc.json")
        writeAtomically(f) { it.write("good".toByteArray()) }
        try {
            writeAtomically(f) { out ->
                out.write("half-writ".toByteArray())
                throw IOException("disk full")
            }
            fail("expected the IOException")
        } catch (_: IOException) {
        }
        assertEquals("good", f.readText())
        assertFalse(File(tmp.root, "doc.json.tmp").exists())
    }

    @Test
    fun saveAndLoadRoundTrip() {
        val store = DocumentStore(tmp.root)
        val doc = store.save(store.create("فاتورة / 2026: \"x\"").copy(pages = listOf(page("abcd"))))
        val back = store.load(doc.id)!!
        assertEquals(doc.name, back.name)
        assertEquals(listOf("abcd"), back.pages.map { it.id })
        assertFalse(File(store.dir(doc.id), "doc.json.tmp").exists())
    }
}
