// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.ocr.fused

/**
 * The recogniser reads a line left to right, so an Arabic line comes out in visual order. This applies
 * the core of the Unicode bidi algorithm (rules W1-W7, N1-N2, I1-I2, L2; no explicit embeddings, no
 * mirroring) to that string, which gives the logical order back (as python-bidi's get_display does in
 * RapidOCR). Same code as visual_to_logical() in tools/ocr-bench/kiwi_pipeline.py.
 */
object Bidi {
    private const val L = 0
    private const val R = 1
    private const val AL = 2
    private const val EN = 3
    private const val ES = 4
    private const val ET = 5
    private const val AN = 6
    private const val CS = 7
    private const val NSM = 8
    private const val WS = 9
    private const val ON = 10

    fun bidiClass(c: Char): Int = when (Character.getDirectionality(c)) {
        Character.DIRECTIONALITY_LEFT_TO_RIGHT -> L
        Character.DIRECTIONALITY_RIGHT_TO_LEFT -> R
        Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC -> AL
        Character.DIRECTIONALITY_EUROPEAN_NUMBER -> EN
        Character.DIRECTIONALITY_EUROPEAN_NUMBER_SEPARATOR -> ES
        Character.DIRECTIONALITY_EUROPEAN_NUMBER_TERMINATOR -> ET
        Character.DIRECTIONALITY_ARABIC_NUMBER -> AN
        Character.DIRECTIONALITY_COMMON_NUMBER_SEPARATOR -> CS
        Character.DIRECTIONALITY_NONSPACING_MARK -> NSM
        Character.DIRECTIONALITY_WHITESPACE, Character.DIRECTIONALITY_SEGMENT_SEPARATOR,
        Character.DIRECTIONALITY_PARAGRAPH_SEPARATOR -> WS
        else -> ON
    }

    /** The class names used by the Python reference, for the golden test. */
    fun className(c: Char): String = listOf("L", "R", "AL", "EN", "ES", "ET", "AN", "CS", "NSM", "WS", "ON")[bidiClass(c)]

    fun visualToLogical(s: String): String {
        val n = s.length
        if (n == 0) return s
        val t = IntArray(n) { bidiClass(s[it]) }
        var base = 0
        for (c in t) {
            if (c == L) { base = 0; break }
            if (c == R || c == AL) { base = 1; break }
        }
        val sos = if (base == 1) R else L
        // W1
        var prev = sos
        for (i in 0 until n) { if (t[i] == NSM) t[i] = prev; prev = t[i] }
        // W2, W3
        var last = sos
        for (i in 0 until n) {
            if (t[i] == L || t[i] == R || t[i] == AL) last = t[i]
            else if (t[i] == EN && last == AL) t[i] = AN
        }
        for (i in 0 until n) if (t[i] == AL) t[i] = R
        // W4
        for (i in 1 until n - 1) {
            if (t[i] == ES && t[i - 1] == EN && t[i + 1] == EN) t[i] = EN
            else if (t[i] == CS && t[i - 1] == t[i + 1] && (t[i - 1] == EN || t[i - 1] == AN)) t[i] = t[i - 1]
        }
        // W5
        var i = 0
        while (i < n) {
            if (t[i] == ET) {
                var j = i
                while (j < n && t[j] == ET) j++
                if ((i > 0 && t[i - 1] == EN) || (j < n && t[j] == EN)) for (k in i until j) t[k] = EN
                i = j
            } else i++
        }
        // W6
        for (k in 0 until n) if (t[k] == ES || t[k] == ET || t[k] == CS) t[k] = ON
        // W7
        last = sos
        for (k in 0 until n) {
            if (t[k] == L || t[k] == R) last = t[k]
            else if (t[k] == EN && last == L) t[k] = L
        }
        // N1, N2
        fun strong(c: Int) = if (c == R || c == EN || c == AN) R else c
        i = 0
        while (i < n) {
            if (t[i] == ON || t[i] == WS) {
                var j = i
                while (j < n && (t[j] == ON || t[j] == WS)) j++
                val before = if (i > 0) strong(t[i - 1]) else sos
                val after = if (j < n) strong(t[j]) else sos
                val d = if (before == after) before else if (base == 1) R else L
                for (k in i until j) t[k] = d
                i = j
            } else i++
        }
        // I1, I2
        val lv = IntArray(n) { k ->
            val c = t[k]
            if (base == 0) (if (c == R) 1 else if (c == AN || c == EN) 2 else 0)
            else (if (c == L || c == EN || c == AN) 2 else 1)
        }
        // L2
        val chars = s.toCharArray()
        val hi = lv.max()
        val loOdd = lv.filter { it % 2 == 1 }.minOrNull() ?: (hi + 1)
        for (level in hi downTo loOdd) {
            i = 0
            while (i < n) {
                if (lv[i] >= level) {
                    var j = i
                    while (j < n && lv[j] >= level) j++
                    chars.reverse(i, j)
                    lv.reverse(i, j)
                    i = j
                } else i++
            }
        }
        return String(chars)
    }

    private fun CharArray.reverse(from: Int, to: Int) {
        var a = from; var b = to - 1
        while (a < b) { val x = this[a]; this[a] = this[b]; this[b] = x; a++; b-- }
    }

    private fun IntArray.reverse(from: Int, to: Int) {
        var a = from; var b = to - 1
        while (a < b) { val x = this[a]; this[a] = this[b]; this[b] = x; a++; b-- }
    }
}
