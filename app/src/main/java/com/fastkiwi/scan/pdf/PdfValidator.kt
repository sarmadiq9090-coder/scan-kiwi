// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.pdf

import java.io.File
import java.io.RandomAccessFile

/**
 * Structural check of a finished PDF before it replaces anything or is shared (PRD §6.3):
 * header, `startxref`, the cross-reference table (every in-use offset must point at its
 * "N G obj"), the trailer's /Root, the catalog's /Pages and the page count. Strict on
 * purpose: unlike a reader, it never repairs. Supports classic xref tables, which is what our
 * writer and PdfBox-Android 2.0 produce.
 */
object PdfValidator {

    class Invalid(message: String) : Exception(message)

    /** Returns the page count; throws [Invalid] when the file is not a complete PDF. */
    fun validate(file: File, expectedPages: Int? = null): Int {
        RandomAccessFile(file, "r").use { f ->
            val len = f.length()
            if (len < 64) throw Invalid("too short")
            val head = read(f, 0, minOf(1024L, len).toInt())
            if (!head.contains("%PDF-")) throw Invalid("no header")
            val tailLen = minOf(2048L, len).toInt()
            val tail = read(f, len - tailLen, tailLen)
            if (!tail.trimEnd().endsWith("%%EOF")) throw Invalid("no %%EOF at the end")
            val sx = Regex("startxref\\s+(\\d+)\\s+%%EOF\\s*$").find(tail) ?: throw Invalid("no startxref")
            var xrefPos = sx.groupValues[1].toLong()
            val offsets = HashMap<Int, Long>()
            var root: Int? = null
            var hops = 0
            while (true) {
                if (xrefPos <= 0 || xrefPos >= len) throw Invalid("startxref out of range")
                val trailer = readXref(f, xrefPos, len, offsets)
                if (root == null) root = Regex("/Root\\s+(\\d+)\\s+\\d+\\s+R").find(trailer)?.groupValues?.get(1)?.toInt()
                val prev = Regex("/Prev\\s+(\\d+)").find(trailer)?.groupValues?.get(1)?.toLong() ?: break
                if (++hops > 16) throw Invalid("too many xref sections")
                xrefPos = prev
            }
            for ((id, off) in offsets) {
                if (off <= 0 || off >= len) throw Invalid("object $id offset out of range")
                val at = read(f, off, minOf(32L, len - off).toInt())
                if (!Regex("^$id\\s+\\d+\\s+obj").containsMatchIn(at)) throw Invalid("object $id is not at its offset")
            }
            val rootId = root ?: throw Invalid("no /Root")
            val catalog = objectText(f, offsets, rootId, len)
            if (!Regex("/Type\\s*/Catalog").containsMatchIn(catalog)) throw Invalid("root is not a catalog")
            val pagesId = Regex("/Pages\\s+(\\d+)\\s+\\d+\\s+R").find(catalog)?.groupValues?.get(1)?.toInt() ?: throw Invalid("no /Pages")
            val pages = objectText(f, offsets, pagesId, len)
            val countMatch = Regex("/Count\\s+(\\d+)(\\s+\\d+\\s+R)?").find(pages) ?: throw Invalid("no /Count")
            val count = if (countMatch.groupValues[2].isNotEmpty()) {
                val ref = countMatch.groupValues[1].toInt()
                Regex("obj\\s+(\\d+)").find(objectText(f, offsets, ref, len))?.groupValues?.get(1)?.toInt() ?: throw Invalid("bad /Count")
            } else countMatch.groupValues[1].toInt()
            if (expectedPages != null && count != expectedPages) throw Invalid("page count $count, expected $expectedPages")
            return count
        }
    }

    /** Reads one classic xref section into [offsets] (first definition wins) and returns its trailer text. */
    private fun readXref(f: RandomAccessFile, pos: Long, len: Long, offsets: HashMap<Int, Long>): String {
        // The table and trailer run from [pos] to the next startxref; 32 MB covers ~1.6 million objects.
        val n0 = len - pos
        if (n0 > 32L * 1024 * 1024) throw Invalid("xref section too large")
        val buf = read(f, pos, n0.toInt())
        if (!buf.startsWith("xref")) throw Invalid("no xref table at startxref")
        var i = 4
        fun skipWs() { while (i < buf.length && buf[i].isWhitespace()) i++ }
        val head = Regex("\\G(\\d+)[ \\t]+(\\d+)")
        val entry = Regex("\\G(\\d{10}) (\\d{5}) ([nf])")
        while (true) {
            skipWs()
            if (i >= buf.length) throw Invalid("xref truncated")
            if (buf.startsWith("trailer", i)) {
                val end = buf.indexOf("startxref", i).let { if (it < 0) buf.length else it }
                return buf.substring(i, end)
            }
            val m = head.find(buf, i) ?: throw Invalid("bad xref subsection")
            val first = m.groupValues[1].toInt()
            val n = m.groupValues[2].toInt()
            if (n < 0 || n > 10_000_000) throw Invalid("bad xref count")
            i = m.range.last + 1
            for (k in 0 until n) {
                skipWs()
                val em = entry.find(buf, i) ?: throw Invalid("bad xref entry")
                if (em.groupValues[3] == "n") offsets.putIfAbsent(first + k, em.groupValues[1].toLong())
                i = em.range.last + 1
            }
        }
    }

    private fun objectText(f: RandomAccessFile, offsets: Map<Int, Long>, id: Int, len: Long): String {
        val off = offsets[id] ?: throw Invalid("object $id missing")
        val s = read(f, off, minOf(16_384L, len - off).toInt())
        val end = s.indexOf("endobj")
        val stream = s.indexOf("stream")
        val stop = listOf(end, stream).filter { it >= 0 }.minOrNull() ?: s.length
        return s.substring(0, stop)
    }

    private fun read(f: RandomAccessFile, pos: Long, n: Int): String {
        val b = ByteArray(maxOf(0, n))
        f.seek(pos)
        f.readFully(b)
        return String(b, Charsets.ISO_8859_1)
    }
}
