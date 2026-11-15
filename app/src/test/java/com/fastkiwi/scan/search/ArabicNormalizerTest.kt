// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The table of Arabic variants the search normaliser folds (ROADMAP 1.6.0 test plan). */
class ArabicNormalizerTest {

    /** input → expected normalised form. */
    private val table = listOf(
        // harakat (fatha, damma, kasra, sukun, shadda, tanwin) and superscript alef
        "كَتَبَ" to "كتب",
        "الْعِلْمُ" to "العلم",
        "مُدَرِّسٌ" to "مدرس",
        "كِتَابًا" to "كتابا",
        "رَحْمٰن" to "رحمن",
        // tatweel
        "مـــحـمد" to "محمد",
        // alef forms
        "أحمد" to "احمد",
        "إسلام" to "اسلام",
        "آمال" to "امال",
        "ٱلرحمن" to "الرحمن",
        "إِلَى" to "الي",
        // alef maqsura and Persian ya
        "مستشفى" to "مستشفي",
        "علی" to "علي",
        // ta marbuta
        "فاتورة" to "فاتوره",
        "مدرسةٌ" to "مدرسه",
        // keheh
        "کتاب" to "كتاب",
        // digits: Arabic-Indic, Persian, Western; thousands separators
        "٢٠٢٦" to "2026",
        "۱۴۴۷" to "1447",
        "١٬٢٥٠٫٧٥" to "1250 75",
        "1,250.75" to "1250 75",
        "1,2,3" to "1 2 3",
        // punctuation and Latin
        "«فاتورة»، الكهرباء؟" to "فاتوره الكهرباء",
        "Invoice No. INV-2026" to "invoice no inv 2026",
        "PDF/A" to "pdf a",
        // bidi controls and joiners are dropped
        "⁨Passport⁩ copy" to "passport copy",
        "ك‌تاب" to "كتاب",
        // hamza on waw / ya and standalone hamza are kept (not in the approved rules)
        "مسؤول" to "مسؤول",
        "رئيس" to "رئيس",
        "سماء" to "سماء",
    )

    @Test
    fun variantTable() {
        for ((input, expected) in table) assertEquals("normalize($input)", expected, ArabicNormalizer.normalize(input))
    }

    @Test
    fun queryWithoutHarakatMatchesVocalisedText() {
        assertEquals(ArabicNormalizer.normalize("إِنَّ الْعِلْمَ نُورٌ"), ArabicNormalizer.normalize("ان العلم نور"))
        assertEquals(ArabicNormalizer.normalize("مُسْتَشْفَى"), ArabicNormalizer.normalize("مستشفي"))
        assertEquals(ArabicNormalizer.normalize("أَسْعَار"), ArabicNormalizer.normalize("اسعار"))
    }

    @Test
    fun articleStemIsIndexedToo() {
        assertEquals(listOf("الكهرباء", "كهرباء"), ArabicNormalizer.indexTokens("الكهرباء"))
        assertEquals(listOf("والطلاب", "طلاب"), ArabicNormalizer.indexTokens("والطلاب"))
        assertEquals("بنك", ArabicNormalizer.stem("للبنك"))
        assertNull("too short to strip", ArabicNormalizer.stem("الم"))
        assertTrue(ArabicNormalizer.wordMatches("الكهرباء،", "كهرب"))
        assertTrue(ArabicNormalizer.wordMatches("الفاتورة", "فاتوره"))
        assertFalse(ArabicNormalizer.wordMatches("الماء", "كهرب"))
    }

    @Test
    fun matchExpressionCannotInjectOperators() {
        assertEquals("a* or* b*", SearchIndex.matchExpression("A OR b"))
        assertEquals("فاتوره* near* 3*", SearchIndex.matchExpression("\"فاتورة\" NEAR/3 *"))
        assertNull(SearchIndex.matchExpression("  ، ؟ ! "))
    }
}
