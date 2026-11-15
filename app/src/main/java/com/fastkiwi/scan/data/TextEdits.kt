// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.data

import com.fastkiwi.scan.core.OcrWord
import org.json.JSONArray
import org.json.JSONObject

/**
 * The user's corrections to a page's recognised text (OCR-10), kept beside the OCR cache as an
 * overlay (`p_<id>_text.json`) and applied by [DocumentStore.loadOcr], the one place cached words
 * are read. So the PDF text layer, TXT export, copy and search all see the corrected text, while
 * the page image and the OCR cache itself never change.
 *
 * An overlay belongs to the exact OCR result it corrected: it records the page [revision] and the
 * [base] word texts. A new revision (re-crop, rotation, filter, Undo of those) or a different OCR
 * result means the words it refers to are gone, and the overlay is ignored (decision recorded in
 * docs/features-2.0/docs.md). A corrected word keeps its box; words the user adds are attached to
 * the box of the word before them (or after, at the start); a deleted word is dropped.
 */
data class TextOverlay(
    val revision: Int,
    /** Word texts of the OCR result the edits were made on, in cache order. */
    val base: List<String>,
    /** Word index → replacement text ("" = word removed; may hold several words). */
    val edits: Map<Int, String>,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("version", 1)
        .put("revision", revision)
        .put("base", JSONArray().apply { base.forEach { put(it) } })
        .put("edits", JSONObject().apply { edits.toSortedMap().forEach { (k, v) -> put(k.toString(), v) } })

    /** True when this overlay was made on [words] at [pageRevision]. */
    fun appliesTo(pageRevision: Int, words: List<OcrWord>): Boolean =
        revision == pageRevision && base.size == words.size && base.indices.all { base[it] == words[it].text }

    /** [words] with the corrections applied; boxes unchanged. */
    fun apply(words: List<OcrWord>): List<OcrWord> {
        if (edits.isEmpty()) return words
        val out = ArrayList<OcrWord>(words.size)
        for ((i, w) in words.withIndex()) {
            val t = edits[i] ?: w.text
            if (t.isNotBlank()) out.add(if (t == w.text) w else w.copy(text = t))
        }
        return out
    }

    companion object {
        fun fromJson(o: JSONObject): TextOverlay {
            val b = o.getJSONArray("base")
            val e = o.optJSONObject("edits") ?: JSONObject()
            val edits = HashMap<Int, String>()
            for (k in e.keys()) k.toIntOrNull()?.let { edits[it] = e.getString(k) }
            return TextOverlay(o.getInt("revision"), List(b.length()) { b.getString(it) }, edits)
        }

        /**
         * Edits that turn [base] into the user's [edited] text. Tokens are split on white space and
         * aligned to the words by an edit-distance alignment (common start and end trimmed first, so
         * a one-word fix on a long page is cheap). Returns an empty map when nothing changed.
         */
        fun diff(base: List<String>, edited: String): Map<Int, String> {
            val toks = edited.split(WS).filter { it.isNotEmpty() }
            val n = base.size
            val m = toks.size
            var pre = 0
            while (pre < n && pre < m && base[pre] == toks[pre]) pre++
            var suf = 0
            while (suf < n - pre && suf < m - pre && base[n - 1 - suf] == toks[m - 1 - suf]) suf++
            val a = base.subList(pre, n - suf)
            val b = toks.subList(pre, m - suf)
            if (a.isEmpty() && b.isEmpty()) return emptyMap()
            // Tokens replacing each base word (null = unchanged, empty = removed).
            val repl = arrayOfNulls<MutableList<String>>(n)
            // Tokens typed before any word: they go into the next word's box.
            val pending = ArrayList<String>()
            var last = pre - 1
            fun take(i: Int, t: String?) {
                val l = ArrayList<String>(pending)
                pending.clear()
                if (t != null) l.add(t)
                repl[i] = l
                last = i
            }
            for (step in align(a, b)) {
                when (step.kind) {
                    Step.KEEP, Step.SUB -> take(pre + step.i, b[step.j])
                    Step.DEL -> take(pre + step.i, null)
                    else -> if (last >= 0) {
                        (repl[last] ?: mutableListOf(base[last]).also { repl[last] = it }).add(b[step.j])
                    } else pending.add(b[step.j])
                }
            }
            if (pending.isNotEmpty()) {
                // Only insertions at the very start: they share the first remaining word's box.
                val target = pre + a.size
                if (target >= n) return emptyMap() // no word, so no box to carry the text
                repl[target] = (pending + (repl[target] ?: listOf(base[target]))).toMutableList()
            }
            val out = HashMap<Int, String>()
            for (i in 0 until n) {
                val s = repl[i]?.joinToString(" ") ?: continue
                if (s != base[i]) out[i] = s
            }
            return out
        }

        private val WS = Regex("\\s+")
        private const val SUB_COST = 3
        private const val INDEL = 2

        private class Step(val kind: Int, val i: Int, val j: Int) {
            companion object { const val KEEP = 0; const val SUB = 1; const val DEL = 2; const val INS = 3 }
        }

        /**
         * Weighted edit-distance alignment of two token lists (O(n·m) on the changed middle only).
         * A substitution costs 3 and an insertion or deletion 2, so the alignment keeps identical
         * words paired rather than shifting every word by one.
         */
        private fun align(a: List<String>, b: List<String>): List<Step> {
            val n = a.size
            val m = b.size
            if (n.toLong() * m > 4_000_000L) {
                // A rewrite of a huge page: pair words in order, the rest deleted / appended.
                return buildList {
                    for (k in 0 until maxOf(n, m)) when {
                        k < n && k < m -> add(Step(if (a[k] == b[k]) Step.KEEP else Step.SUB, k, k))
                        k < n -> add(Step(Step.DEL, k, -1))
                        else -> add(Step(Step.INS, -1, k))
                    }
                }
            }
            val w = m + 1
            val d = IntArray((n + 1) * w)
            for (i in 0..n) d[i * w] = i * INDEL
            for (j in 0..m) d[j] = j * INDEL
            for (i in 1..n) for (j in 1..m) {
                val sub = d[(i - 1) * w + j - 1] + if (a[i - 1] == b[j - 1]) 0 else SUB_COST
                d[i * w + j] = minOf(sub, d[(i - 1) * w + j] + INDEL, d[i * w + j - 1] + INDEL)
            }
            val steps = ArrayList<Step>()
            var i = n
            var j = m
            while (i > 0 || j > 0) {
                if (i > 0 && j > 0 && d[i * w + j] == d[(i - 1) * w + j - 1] + if (a[i - 1] == b[j - 1]) 0 else SUB_COST) {
                    steps.add(Step(if (a[i - 1] == b[j - 1]) Step.KEEP else Step.SUB, i - 1, j - 1)); i--; j--
                } else if (i > 0 && d[i * w + j] == d[(i - 1) * w + j] + INDEL) {
                    steps.add(Step(Step.DEL, i - 1, -1)); i--
                } else {
                    steps.add(Step(Step.INS, -1, j - 1)); j--
                }
            }
            steps.reverse()
            return steps
        }
    }
}

/** Page text in reading order, as shown, copied and exported (OCR-08). */
object PageText {
    /**
     * Words in their cache (engine) order, grouped into lines: a word starts a new line when it
     * sits below the previous word (its vertical centre past that word's bottom) or above it
     * (a new column). Words are never reordered, so RTL text keeps its logical order.
     */
    fun lines(words: List<OcrWord>): List<List<OcrWord>> {
        val out = ArrayList<MutableList<OcrWord>>()
        var prev: OcrWord? = null
        for (w in words) {
            val p = prev
            val cy = (w.top + w.bottom) / 2
            if (p == null || cy > p.bottom || cy < p.top) out.add(ArrayList()) // new line
            out.last().add(w)
            prev = w
        }
        return out
    }

    /** Lines joined by "\n", words by a single space. */
    fun text(words: List<OcrWord>): String =
        lines(words).joinToString("\n") { line -> line.joinToString(" ") { it.text.trim() } }
}
