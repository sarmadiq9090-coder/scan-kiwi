// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.pdf

import com.fastkiwi.scan.core.OcrWord
import com.fastkiwi.scan.data.DocumentStore
import com.fastkiwi.scan.data.ScanDocument

/**
 * PDF-12 for Scan Kiwi documents: the recognised text of each page, from the cached OCR words
 * (no OCR runs here), in logical reading order: lines top to bottom; inside a line, right to
 * left when the line is mostly Arabic, left to right otherwise. Each word's own characters are
 * already in logical order (that is how the engine returns them).
 *
 * API for other modules (TXT export, copy text): [pages] and [plain]. Pages without cached
 * text give "".
 */
object DocumentText {

    fun pages(store: DocumentStore, doc: ScanDocument): List<String> =
        doc.pages.map { p -> store.loadOcr(doc.id, p)?.let { lines(it) } ?: "" }

    /** All pages, separated by a blank line. */
    fun plain(store: DocumentStore, doc: ScanDocument): String =
        pages(store, doc).filter { it.isNotBlank() }.joinToString("\n\n")

    /** Groups words into lines and orders them; one line per output line. */
    fun lines(words: List<OcrWord>): String {
        val ws = words.filter { it.text.isNotBlank() }
        if (ws.isEmpty()) return ""
        // Greedy line building over words sorted by vertical centre: a word joins the current
        // line when its centre lies inside the line's vertical band.
        val sorted = ws.sortedBy { (it.top + it.bottom) / 2 }
        val lines = ArrayList<MutableList<OcrWord>>()
        var top = 0
        var bottom = -1
        for (w in sorted) {
            val c = (w.top + w.bottom) / 2
            if (lines.isEmpty() || c > bottom) {
                lines.add(mutableListOf(w))
                top = w.top; bottom = w.bottom
            } else {
                lines.last().add(w)
                top = minOf(top, w.top); bottom = maxOf(bottom, w.bottom)
            }
        }
        return lines.joinToString("\n") { line ->
            val rtl = line.sumOf { w -> w.text.count { isArabic(it) } } * 2 > line.sumOf { w -> w.text.count { it.isLetter() } }
            val ordered = if (rtl) line.sortedByDescending { it.right } else line.sortedBy { it.left }
            ordered.joinToString(" ") { it.text.trim() }
        }
    }

    private fun isArabic(c: Char) = c in '\u0600'..'ۿ' || c in 'ݐ'..'ݿ' || c in 'ࢠ'..'ࣿ' || c in 'ﭐ'..'﷿' || c in 'ﹰ'..'\uFEFF'
}
