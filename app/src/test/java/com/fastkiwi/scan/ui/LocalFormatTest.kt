// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** File sizes are rounded to the nearest unit (BLACKBOX-2.0 re-test: 2007 bytes said «3 KB»). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en")
class LocalFormatTest {
    private fun size(bytes: Long) = LocalFormat.size(RuntimeEnvironment.getApplication(), bytes)

    @Test
    fun sizesAreRoundedToTheNearestUnit() {
        assertEquals("2 KB", size(2007))
        assertEquals("1 KB", size(1))
        assertEquals("1 KB", size(1499))
        assertEquals("2 KB", size(1500))
        assertEquals("999 KB", size(999_499))
        assertEquals("1 MB", size(999_500))
        assertEquals("1.2 MB", size(1_249_999))
        assertEquals("1.3 MB", size(1_250_000))
    }
}
