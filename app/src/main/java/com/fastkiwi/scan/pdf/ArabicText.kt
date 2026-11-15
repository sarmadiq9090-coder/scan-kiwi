// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.pdf

import java.text.Normalizer

/**
 * The "bidi fix" for text that PdfBox-Android 2.0 extracts from external PDFs (PDF-12).
 *
 * 1. Word order. PDFBox 2.0's text stripper reverses the characters of each RTL word (stored
 *    in visual order) but keeps the words of a line in visual, left-to-right order. So «فاتورة
 *    رقم 2026» comes out as «2026 رقم فاتورة». For lines that are mostly Arabic, the word order is
 *    reversed back, keeping runs of Latin words and numbers in their own order («Total 2026»
 *    inside an Arabic line stays «Total 2026»).
 * 2. Presentation forms. Many Arabic PDFs store glyph shapes (U+FB50–FDFF, U+FE70–FEFE:
 *    initial, medial, final forms and lam-alef ligatures). They are folded to the base letters
 *    (NFKC on those characters only), so copied text matches a search.
 */
object ArabicText {

    fun fix(text: String): String = text.split('\n').joinToString("\n") { reorderLine(foldForms(it)) }

    /** Visual word order -> logical, for a line whose words are each already logical. */
    fun reorderLine(line: String): String {
        val arabic = line.count { isArabic(it) }
        if (arabic == 0) return line
        val latin = line.count { it.isLetter() && !isArabic(it) }
        if (arabic < latin) return line
        val trailing = line.length - line.trimEnd().length
        val words = line.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.size < 2) return line
        fun kind(w: String) = when {
            w.any { isArabic(it) } -> 'R'
            w.any { it.isLetter() } -> 'L'
            else -> 'N'
        }
        val kinds = words.map { kind(it) }
        // Units: an LTR run (L words, with neutral words between them) stays together.
        val units = ArrayList<List<String>>()
        var i = 0
        while (i < words.size) {
            if (kinds[i] == 'L' || (kinds[i] == 'N' && kinds.getOrNull(i + 1) == 'L')) {
                var j = i
                var end = i
                while (j + 1 < words.size && kinds[j + 1] != 'R') {
                    j++
                    if (kinds[j] == 'L') end = j
                }
                // Neutral words after the last L, and numbers next to Latin words, join the run.
                var k = end
                while (k + 1 < words.size && kinds[k + 1] == 'N' && isNumberLike(words[k + 1])) k++
                units.add(words.subList(i, k + 1))
                i = k + 1
            } else {
                units.add(listOf(words[i]))
                i++
            }
        }
        return units.asReversed().joinToString(" ") { it.joinToString(" ") } + " ".repeat(trailing)
    }

    fun foldForms(text: String): String {
        if (text.none { isPresentationForm(it) }) return text
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            if (isPresentationForm(text[i])) {
                var j = i
                while (j < text.length && isPresentationForm(text[j])) j++
                sb.append(Normalizer.normalize(text.substring(i, j), Normalizer.Form.NFKC))
                i = j
            } else {
                sb.append(text[i]); i++
            }
        }
        return sb.toString()
    }

    private fun isNumberLike(w: String) = w.any { it.isDigit() }

    fun isArabic(c: Char) = c in '؀'..'ۿ' || c in 'ݐ'..'ݿ' || c in 'ࢠ'..'ࣿ' || isPresentationForm(c)

    private fun isPresentationForm(c: Char) = c in 'ﭐ'..'﷿' || c in 'ﹰ'..'﻾'
}
