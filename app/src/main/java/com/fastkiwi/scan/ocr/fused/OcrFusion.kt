// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.ocr.fused

/**
 * The fusion rule (roadmap §3): per line, Tesseract's re-read wins when it holds more numbers than the
 * PP-OCR Arabic recogniser's text (that model drops Western digits inside Arabic lines). With
 * [latinRule], Tesseract also wins when it read at least 3 more Latin letters (tuned on the dev set only).
 */
class OcrFusion(private val latinRule: Boolean = LATIN_RULE) {

    /** True when the Tesseract text should replace the PP-OCR text. */
    fun preferTesseract(pp: String, tess: String): Boolean =
        countNumbers(tess) > countNumbers(pp) || (latinRule && latinLetters(tess) >= latinLetters(pp) + 3)

    fun choose(pp: String, tess: String): String = if (preferTesseract(pp, tess)) tess else pp

    companion object {
        /** Decided on the 52-page dev set (docs/ocr-benchmark.md). */
        const val LATIN_RULE = false

        private val NUMBER = Regex("[0-9٠-٩]+(?:[.,،٫٬/:\\-][0-9٠-٩]+)*")

        fun countNumbers(s: String): Int = NUMBER.findAll(s).count()

        fun latinLetters(s: String): Int = s.count { it.lowercaseChar() in 'a'..'z' }
    }
}

/** An Arabic comma between two digits is the Arabic decimal separator (٫) read as a comma. */
object ArabicPostProcessor {
    private val COMMA_BETWEEN_DIGITS = Regex("(?<=[0-9٠-٩])،(?=[0-9٠-٩])")

    fun apply(s: String): String = COMMA_BETWEEN_DIGITS.replace(s, "٫")
}
