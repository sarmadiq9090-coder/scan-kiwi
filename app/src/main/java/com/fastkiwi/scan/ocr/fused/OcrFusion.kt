// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.ocr.fused

/**
 * The fusion rule (roadmap §3): per line, Tesseract's re-read wins when it holds more numbers than the
 * PP-OCR Arabic recogniser's text (that model drops Western digits inside Arabic lines). With
 * [latinRule], Tesseract also wins when it read at least 3 more Latin letters (tuned on the dev set only).
 * 2.0: and on mixed Arabic/English lines ([mixedLine]).
 */
class OcrFusion(private val latinRule: Boolean = LATIN_RULE) {

    /** True when the Tesseract text (in logical order, [TesseractLineOrder]) should replace the PP-OCR text. */
    fun preferTesseract(pp: String, tess: String): Boolean =
        countNumbers(tess) > countNumbers(pp) || (latinRule && latinLetters(tess) >= latinLetters(pp) + 3) ||
            mixedLine(pp, tess)

    /**
     * 2.0 mixed-page fix (per-line script routing, tuned on the dev sets only): an Arabic line with Latin
     * words the Arabic model dropped or mangled. Tesseract (ara+eng) read at least [MIXED_MIN_GAIN] more
     * Latin letters, the same Arabic ([arabicAgreement] ≥ [MIXED_AGREE]) and no fewer digits (its eng
     * model reads Arabic-Indic digits as Latin letters: ٧ → V, ٨ → A).
     */
    fun mixedLine(pp: String, tess: String): Boolean =
        ROUTE_MIXED && latinLetters(tess) >= latinLetters(pp) + MIXED_MIN_GAIN &&
            arabicAgreement(pp, tess) >= MIXED_AGREE && digits(tess) >= digits(pp)

    fun choose(pp: String, tess: String): String = if (preferTesseract(pp, tess)) tess else pp

    companion object {
        /** Decided on the 52-page dev set (docs/ocr-benchmark.md). */
        const val LATIN_RULE = false

        private val NUMBER = Regex("[0-9٠-٩]+(?:[.,،٫٬/:\\-][0-9٠-٩]+)*")

        fun countNumbers(s: String): Int = NUMBER.findAll(s).count()

        fun latinLetters(s: String): Int = s.count { it.lowercaseChar() in 'a'..'z' }

        // 2.0 mixed-page fix; the same values as tools/ocr-bench/kiwi_pipeline.py (docs/ocr-benchmark.md).
        const val ROUTE_MIXED = true
        const val MIXED_MIN_GAIN = 4
        const val MIXED_AGREE = 0.8
        /** Fewer Arabic letters than this in the PP-OCR text: not an Arabic line (a cell of digits). */
        const val MIXED_MIN_ARABIC = 3

        fun digits(s: String): Int = s.count { it in '0'..'9' || it in '٠'..'٩' }

        /** How alike the Arabic letters of two readings are (0..1); 0 when [a] has fewer than [MIXED_MIN_ARABIC]. */
        fun arabicAgreement(a: String, b: String): Double {
            val x = a.filter(TesseractLineOrder::isArabicLetter)
            val y = b.filter(TesseractLineOrder::isArabicLetter)
            if (x.length < MIXED_MIN_ARABIC) return 0.0
            return 1.0 - levenshtein(x, y).toDouble() / maxOf(x.length, y.length)
        }

        fun levenshtein(a: String, b: String): Int {
            var prev = IntArray(b.length + 1) { it }
            var cur = IntArray(b.length + 1)
            for (i in 1..a.length) {
                cur[0] = i
                for (j in 1..b.length) cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] != b[j - 1]) 1 else 0)
                val t = prev; prev = cur; cur = t
            }
            return prev[b.length]
        }
    }
}

/** An Arabic comma between two digits is the Arabic decimal separator (٫) read as a comma. */
object ArabicPostProcessor {
    private val COMMA_BETWEEN_DIGITS = Regex("(?<=[0-9٠-٩])،(?=[0-9٠-٩])")

    fun apply(s: String): String = COMMA_BETWEEN_DIGITS.replace(s, "٫")
}
