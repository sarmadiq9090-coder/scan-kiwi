// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan

import com.fastkiwi.scan.ui.LocalFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.util.Locale

/** Locale digits for counts (CLDR) and the Arabic metadata separator (design spec §2.2). */
class FormatTest {
    @Test
    fun countsUseTheLocaleDigits() {
        assertEquals("٣", LocalFormat.count(3, Locale.forLanguageTag("ar")))
        assertEquals("١٢", LocalFormat.count(12, Locale.forLanguageTag("ar-EG")))
        assertEquals("3", LocalFormat.count(3, Locale.forLanguageTag("ar-MA")))
        assertEquals("3", LocalFormat.count(3, Locale.ENGLISH))
    }

    @Test
    fun arabicSeparatorIsNeverTheMiddleDot() {
        assertEquals("، ", LocalFormat.metaSep(Locale.forLanguageTag("ar")))
        assertFalse(LocalFormat.metaSep(Locale.forLanguageTag("ar-MA")).contains("·"))
        assertEquals(" · ", LocalFormat.metaSep(Locale.ENGLISH))
    }

    @Test
    fun latinRunsAreIsolated() {
        assertEquals("⁨A4⁩", LocalFormat.isolate("A4"))
    }
}
