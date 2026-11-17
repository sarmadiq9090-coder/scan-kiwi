// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.pdf

/**
 * One page of an output document: page [index] (0-based) of input [source], turned clockwise by
 * [rotate] degrees on top of its own rotation. [source] = [BLANK] makes an empty page instead
 * (PDF-11), sized like the page before it.
 */
data class PageRef(val source: Int, val index: Int, val rotate: Int = 0) {
    init {
        require(rotate % 90 == 0) { "rotation must be a multiple of 90" }
    }

    val isBlank: Boolean get() = source == BLANK

    companion object {
        const val BLANK = -1
        fun blank(): PageRef = PageRef(BLANK, 0)
    }
}

/** A page range text the user typed could not be read; [position] is the offending character. */
class RangeException(val reason: Reason, val position: Int) : IllegalArgumentException("$reason at $position") {
    enum class Reason { EMPTY, SYNTAX, OUT_OF_RANGE, REVERSED }
}

/**
 * Builds page plans for every PDF operation (merge, split, extract, reorder, rotate, insert
 * blank), the same for Scan Kiwi documents and external files, and reads the page range text
 * the user types («١-٣، ٥، ٨-»). Pure functions, no Android.
 */
object PagePlans {

    /** PDF-01: all pages of every input, inputs in the given order. */
    fun merge(pageCounts: List<Int>): List<PageRef> =
        pageCounts.flatMapIndexed { s, n -> List(n) { PageRef(s, it) } }

    /** PDF-03 / PDF-04: the given 0-based pages of one input, in the given order (duplicates allowed). */
    fun select(pages: List<Int>, source: Int = 0): List<PageRef> = pages.map { PageRef(source, it) }

    /** PDF-02 by ranges: one output per range group. */
    fun split(groups: List<List<Int>>, source: Int = 0): List<List<PageRef>> = groups.map { select(it, source) }

    /** PDF-02 every N pages: [1..N], [N+1..2N], ... */
    fun everyN(pageCount: Int, n: Int): List<List<Int>> {
        require(n >= 1) { "n must be >= 1" }
        return (0 until pageCount).chunked(n)
    }

    /** PDF-05: every page (or only [pages]) turned by [degrees]; the others unchanged. */
    fun rotate(pageCount: Int, degrees: Int, pages: Set<Int>? = null, source: Int = 0): List<PageRef> =
        List(pageCount) { PageRef(source, it, if (pages == null || it in pages) norm(degrees) else 0) }

    /** PDF-11: a blank page inserted before page [at] (0-based; [at] = pageCount appends). */
    fun insertBlank(pageCount: Int, at: Int, source: Int = 0): List<PageRef> {
        require(at in 0..pageCount)
        val out = ArrayList<PageRef>(pageCount + 1)
        for (i in 0 until pageCount) {
            if (i == at) out.add(PageRef.blank())
            out.add(PageRef(source, i))
        }
        if (at == pageCount) out.add(PageRef.blank())
        return out
    }

    fun norm(degrees: Int): Int = ((degrees % 360) + 360) % 360

    /**
     * Reads «1-3, 5, 8-» (1-based, inclusive; "8-" = to the end; "-3" = from the start) into
     * 0-based page indices, in the typed order. Arabic-Indic and Persian digits, the Arabic
     * comma «،», «؛», spaces and en/em dashes are accepted. Throws [RangeException].
     */
    fun parse(text: String, pageCount: Int): List<Int> = parseGroups(text, pageCount).flatten()

    /** As [parse], one group per comma-separated part (each becomes one file when splitting). */
    fun parseGroups(text: String, pageCount: Int): List<List<Int>> {
        val s = normalizeDigits(text)
        if (s.isBlank()) throw RangeException(RangeException.Reason.EMPTY, 0)
        val groups = ArrayList<List<Int>>()
        var start = 0
        for (i in 0..s.length) {
            if (i == s.length || s[i] == ',' || s[i] == ';') {
                val part = s.substring(start, i)
                if (part.isNotBlank()) groups.add(parsePart(part, start, pageCount))
                else if (i < s.length) throw RangeException(RangeException.Reason.SYNTAX, i)
                start = i + 1
            }
        }
        if (groups.isEmpty()) throw RangeException(RangeException.Reason.EMPTY, 0)
        return groups
    }

    private fun parsePart(part: String, offset: Int, pageCount: Int): List<Int> {
        val p = part.trim()
        val lead = offset + part.indexOf(p.firstOrNull() ?: ' ').coerceAtLeast(0)
        val dash = p.indexOf('-')
        fun num(t: String, at: Int): Int {
            val v = t.trim()
            if (v.isEmpty() || !v.all { it in '0'..'9' } || v.length > 6) throw RangeException(RangeException.Reason.SYNTAX, at)
            val n = v.toInt()
            if (n < 1 || n > pageCount) throw RangeException(RangeException.Reason.OUT_OF_RANGE, at)
            return n
        }
        if (dash < 0) return listOf(num(p, lead) - 1)
        if (p.indexOf('-', dash + 1) >= 0) throw RangeException(RangeException.Reason.SYNTAX, lead + p.indexOf('-', dash + 1))
        val a = p.substring(0, dash)
        val b = p.substring(dash + 1)
        val from = if (a.isBlank()) 1 else num(a, lead)
        val to = if (b.isBlank()) pageCount else num(b, lead + dash + 1)
        if (from > to) throw RangeException(RangeException.Reason.REVERSED, lead)
        return (from - 1 until to).toList()
    }

    /** Western digits, ASCII comma and hyphen for the parser. */
    fun normalizeDigits(text: String): String = buildString(text.length) {
        for (c in text) append(
            when (c) {
                in '٠'..'٩' -> '0' + (c - '٠')
                in '۰'..'۹' -> '0' + (c - '۰')
                '،', '٫' -> ','
                '؛' -> ';'
                '–', '—', '−' -> '-'
                else -> c
            },
        )
    }

    /** Formats 0-based pages back to «1-3, 5» (for showing a selection as text). */
    fun format(pages: List<Int>): String {
        if (pages.isEmpty()) return ""
        val out = StringBuilder()
        var i = 0
        while (i < pages.size) {
            var j = i
            while (j + 1 < pages.size && pages[j + 1] == pages[j] + 1) j++
            if (out.isNotEmpty()) out.append(", ")
            out.append(pages[i] + 1)
            if (j > i) out.append('-').append(pages[j] + 1)
            i = j + 1
        }
        return out.toString()
    }
}
