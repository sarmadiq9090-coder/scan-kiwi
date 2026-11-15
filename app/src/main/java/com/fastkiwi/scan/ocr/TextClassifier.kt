// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.ocr

/** Direction and script of a recognised word, from its characters (pure, no Android). */
object TextClassifier {
    fun isArabicLetter(c: Char) = c in 'ؠ'..'ي' || c in 'ٱ'..'ۓ' || c in 'ۺ'..'ۼ'
    fun isLatinLetter(c: Char) = c in 'a'..'z' || c in 'A'..'Z'
    fun isDigit(c: Char) = c in '0'..'9' || c in '٠'..'٩' || c in '۰'..'۹'

    /** First strong character decides; digits and punctuation alone are NEUTRAL. */
    fun direction(s: String): TextDirection {
        for (c in s) {
            when (Character.getDirectionality(c)) {
                Character.DIRECTIONALITY_RIGHT_TO_LEFT, Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC -> return TextDirection.RTL
                Character.DIRECTIONALITY_LEFT_TO_RIGHT -> return TextDirection.LTR
            }
        }
        return TextDirection.NEUTRAL
    }

    fun script(s: String): ScriptHint {
        var ar = false; var lat = false; var dig = false
        for (c in s) {
            when {
                isArabicLetter(c) -> ar = true
                isLatinLetter(c) -> lat = true
                isDigit(c) -> dig = true
            }
        }
        return when {
            ar && lat -> ScriptHint.MIXED
            ar -> if (dig) ScriptHint.MIXED else ScriptHint.ARABIC
            lat -> if (dig) ScriptHint.MIXED else ScriptHint.LATIN
            dig -> ScriptHint.DIGITS
            else -> ScriptHint.UNKNOWN
        }
    }
}
