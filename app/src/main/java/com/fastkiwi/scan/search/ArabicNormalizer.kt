// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.search

/**
 * Folds Arabic and English text into search tokens (OCR-07, ROADMAP 1.6.0 item 1). Used for both the
 * index and the query, and for matching only: the text shown, exported and stored is never changed.
 *
 * - harakat, Quranic marks, superscript alef and tatweel are removed;
 * - أ إ آ ٱ → ا, ى ی → ي, ة → ه, ک → ك;
 * - Arabic-Indic (٠-٩) and Persian (۰-۹) digits → 0-9; a thousands separator between digits
 *   («١٬٢٥٠», "1,250") is dropped, so 1250 finds either;
 * - Latin is lower-cased; bidi controls and zero-width joiners are dropped;
 * - anything that is not a letter or digit separates tokens (Arabic punctuation included).
 *
 * [VERSION] is part of the index schema: changing a rule here rebuilds the index.
 */
object ArabicNormalizer {
    const val VERSION = 1

    /** Leading clitic + article forms whose stem is indexed too: «الكهرباء» is found by «كهرباء». */
    private val ARTICLES = arrayOf("وال", "بال", "كال", "فال", "لل", "ال")

    /** Normalises one character; returns 0 for characters to drop entirely, ' ' for separators. */
    fun fold(c: Char): Char = when (c) {
        in 'ً'..'ٟ', 'ٰ', in 'ۖ'..'ۭ', in 'ؐ'..'ؚ', 'ـ' -> DROP
        '\u200B', '\u200C', '\u200D', '\u200E', '\u200F', '\u061C', '\uFEFF', in '\u202A'..'\u202E', in '\u2066'..'\u2069' -> DROP
        'أ', 'إ', 'آ', 'ٱ' -> 'ا'
        'ى', 'ی' -> 'ي'
        'ة' -> 'ه'
        'ک' -> 'ك'
        in '٠'..'٩' -> '0' + (c - '٠')
        in '۰'..'۹' -> '0' + (c - '۰')
        in 'A'..'Z' -> c + 32
        else -> when {
            c.code < 128 -> if (c.isLetterOrDigit()) c else ' '
            Character.isLetterOrDigit(c) -> c.lowercaseChar()
            else -> ' '
        }
    }

    private const val DROP = '\u0000'

    private fun isDigitLike(c: Char) = c in '0'..'9' || c in '٠'..'٩' || c in '۰'..'۹'

    /** Tokens of [text] after folding, in reading (logical) order. */
    fun tokens(text: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var i = 0
        val n = text.length
        while (i < n) {
            val c = text[i]
            // "1,250" / «١٬٢٥٠»: a thousands separator followed by exactly three digits joins the number.
            if ((c == ',' || c == '٬') && sb.isNotEmpty() && sb.last() in '0'..'9' &&
                (1..3).all { k -> i + k < n && isDigitLike(text[i + k]) } &&
                (i + 4 >= n || !isDigitLike(text[i + 4]))
            ) { i++; continue }
            val f = fold(c)
            when (f) {
                DROP -> {}
                ' ' -> if (sb.isNotEmpty()) { out.add(sb.toString()); sb.setLength(0) }
                else -> sb.append(f)
            }
            i++
        }
        if (sb.isNotEmpty()) out.add(sb.toString())
        return out
    }

    /** One normalised string: tokens joined by single spaces. */
    fun normalize(text: String): String = tokens(text).joinToString(" ")

    /**
     * Tokens for the index: every token, plus the stem of a token that starts with the article
     * («الكهرباء» → also «كهرباء») so a query typed without it still matches.
     */
    fun indexTokens(text: String): List<String> {
        val out = ArrayList<String>()
        for (t in tokens(text)) {
            out.add(t)
            stem(t)?.let { out.add(it) }
        }
        return out
    }

    /** The token without a leading article, or null when it has none (or the rest would be too short). */
    fun stem(token: String): String? {
        for (a in ARTICLES) if (token.length >= a.length + 2 && token.startsWith(a)) return token.substring(a.length)
        return null
    }

    /** True when the word [word] (any form) matches the query token [q] (already normalised) as a prefix. */
    fun wordMatches(word: String, q: String): Boolean =
        tokens(word).any { t -> t.startsWith(q) || stem(t)?.startsWith(q) == true }
}
