// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class PagePlansTest {
    @Test
    fun parsesRangesInArabicAndWesternDigits() {
        assertEquals(listOf(0, 1, 2, 4, 7, 8, 9), PagePlans.parse("1-3, 5, 8-", 10))
        assertEquals(listOf(0, 1, 2, 4, 7, 8, 9), PagePlans.parse("١-٣، ٥، ٨-", 10))
        assertEquals(listOf(0, 1, 2), PagePlans.parse("-3", 10))
        assertEquals(listOf(4, 0), PagePlans.parse("5,1", 10)) // typed order is kept (reorder)
        assertEquals(listOf(listOf(0, 1), listOf(5)), PagePlans.parseGroups("۱–۲؛ ۶", 10))
    }

    @Test
    fun rejectsBadRanges() {
        for ((text, reason) in listOf(
            "" to RangeException.Reason.EMPTY, "0" to RangeException.Reason.OUT_OF_RANGE, "11" to RangeException.Reason.OUT_OF_RANGE,
            "5-2" to RangeException.Reason.REVERSED, "a" to RangeException.Reason.SYNTAX, "1--2" to RangeException.Reason.SYNTAX,
            "1,,2" to RangeException.Reason.SYNTAX,
        )) {
            try {
                PagePlans.parse(text, 10)
                fail("accepted «$text»")
            } catch (e: RangeException) {
                assertEquals(text, reason, e.reason)
            }
        }
    }

    @Test
    fun plans() {
        assertEquals(listOf(PageRef(0, 0), PageRef(0, 1), PageRef(1, 0)), PagePlans.merge(listOf(2, 1)))
        assertEquals(listOf(listOf(0, 1, 2), listOf(3, 4, 5), listOf(6)), PagePlans.everyN(7, 3))
        assertEquals(listOf(PageRef(0, 0, 0), PageRef(0, 1, 270)), PagePlans.rotate(2, -90, setOf(1)))
        assertEquals(listOf(PageRef(0, 0), PageRef.blank(), PageRef(0, 1)), PagePlans.insertBlank(2, 1))
        assertEquals(listOf(PageRef(0, 0), PageRef.blank()), PagePlans.insertBlank(1, 1))
        assertEquals("1-3, 5, 8-10", PagePlans.format(listOf(0, 1, 2, 4, 7, 8, 9)))
    }
}
