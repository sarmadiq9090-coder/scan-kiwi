// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Redo (PRD §7.3) and drag-and-drop reorder state (PDF-04), without a screen. */
class EditHistoryTest {

    @Test
    fun undoThenRedoWalksBothWays() {
        val h = EditHistory<String>()
        h.record("A") // A -> B
        h.record("B") // B -> C
        assertEquals("B", h.undo("C"))
        assertEquals("A", h.undo("B"))
        assertNull(h.undo("A"))
        assertEquals(2, h.redoCount)
        assertEquals("B", h.redo("A"))
        assertEquals("C", h.redo("B"))
        assertNull(h.redo("C"))
        assertEquals(2, h.undoCount)
    }

    @Test
    fun aNewEditClearsRedo() {
        val h = EditHistory<String>()
        h.record("A")
        assertEquals("A", h.undo("B"))
        assertEquals(1, h.redoCount)
        h.record("A") // a different edit from A
        assertEquals(0, h.redoCount)
        assertNull(h.redo("D"))
    }

    @Test
    fun historyIsBounded() {
        val h = EditHistory<Int>(limit = 3)
        for (i in 0 until 10) h.record(i)
        assertEquals(3, h.undoCount)
        assertEquals(9, h.undo(10))
        assertEquals(8, h.undo(9))
        assertEquals(7, h.undo(8))
        assertNull(h.undo(7))
    }

    @Test
    fun dragShowsLiveOrderAndDropsTheMove() {
        val r = ReorderState(5)
        assertFalse(r.dragging)
        r.start(1)
        assertEquals(listOf(0, 1, 2, 3, 4), r.order())
        r.hover(3)
        assertEquals(listOf(0, 2, 3, 1, 4), r.order())
        r.hover(99) // beyond the last tile: clamps
        assertEquals(listOf(0, 2, 3, 4, 1), r.order())
        r.hover(0)
        assertEquals(listOf(1, 0, 2, 3, 4), r.order())
        assertEquals(1 to 0, r.drop())
        assertFalse(r.dragging)
        assertEquals(listOf(0, 1, 2, 3, 4), r.order())
    }

    @Test
    fun dropOnItselfOrCancelChangesNothing() {
        val r = ReorderState(3)
        r.start(2)
        r.hover(2)
        assertNull(r.drop())
        r.start(0)
        r.hover(2)
        r.cancel()
        assertNull(r.drop())
        r.hover(1) // not dragging: ignored
        assertTrue(r.order() == listOf(0, 1, 2))
    }
}
