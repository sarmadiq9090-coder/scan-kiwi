// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.ocr.fused

/**
 * 2.0 mixed-page fix. Tesseract writes a mixed Arabic/Latin line with bidi marks and its runs in display
 * order. If the first mark is RLM, the line was laid out left to right and each Arabic run is wrapped
 * RLM..LRM: the logical order is the runs reversed. If it is LRM, the line is right to left and each Latin
 * run is wrapped LRM..RLM, with neighbouring numbers in display order: such a run of non-Arabic words is
 * reversed word by word. Same steps as `tess_logical` in tools/ocr-bench/kiwi_pipeline.py.
 */
object TesseractLineOrder {
    const val LRM = '‎'
    const val RLM = '‏'

    fun isArabicLetter(c: Char) = c in 'ؠ'..'ي' || c in 'ٱ'..'ۓ'

    fun logical(s: String): String {
        val l = s.indexOf(LRM)
        val r = s.indexOf(RLM)
        if (l < 0 && r < 0) return s
        if (r >= 0 && (l < 0 || r < l)) {
            val runs = ArrayList<String>()
            val buf = StringBuilder()
            var inside = false
            for (ch in s) {
                when {
                    ch == RLM -> { runs.add(buf.toString()); buf.setLength(0); inside = true }
                    ch == LRM && inside -> { runs.add(buf.toString()); buf.setLength(0); inside = false }
                    ch == LRM || ch == RLM -> Unit
                    else -> buf.append(ch)
                }
            }
            runs.add(buf.toString())
            return runs.map { it.trim() }.filter { it.isNotEmpty() }.asReversed().joinToString(" ")
        }
        val tokens = splitWhitespace(s)
        val marked = tokens.map { LRM in it || RLM in it }
        val clean = tokens.map { t -> t.filter { it != LRM && it != RLM } }
        fun latinish(t: String) = t.isNotEmpty() && t.none(::isArabicLetter)
        val out = ArrayList<String>()
        var i = 0
        while (i < clean.size) {
            if (latinish(clean[i])) {
                var j = i
                while (j < clean.size && latinish(clean[j])) j++
                val run = clean.subList(i, j)
                out.addAll(if (j - i > 1 && marked.subList(i, j).any { it }) run.asReversed() else run)
                i = j
            } else {
                out.add(clean[i]); i++
            }
        }
        return out.filter { it.isNotEmpty() }.joinToString(" ")
    }

    private fun splitWhitespace(s: String): List<String> {
        val out = ArrayList<String>()
        val b = StringBuilder()
        for (ch in s) {
            if (ch.isWhitespace()) { if (b.isNotEmpty()) { out.add(b.toString()); b.setLength(0) } } else b.append(ch)
        }
        if (b.isNotEmpty()) out.add(b.toString())
        return out
    }
}
