// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.ui

/**
 * Undo and Redo (PRD §7.3) for one document: snapshots before each edit. A new edit clears the
 * redo side, as in every editor. Thread-safe; at most [limit] steps each way.
 */
class EditHistory<T>(private val limit: Int = 20) {
    private val undo = ArrayDeque<T>()
    private val redo = ArrayDeque<T>()

    /** Called with the state before a new edit. */
    @Synchronized
    fun record(before: T) {
        undo.addLast(before)
        while (undo.size > limit) undo.removeFirst()
        redo.clear()
    }

    /** Returns the state to go back to, remembering [current] for Redo; null when there is none. */
    @Synchronized
    fun undo(current: T): T? {
        val prev = undo.removeLastOrNull() ?: return null
        redo.addLast(current)
        while (redo.size > limit) redo.removeFirst()
        return prev
    }

    /** Returns the state an Undo left, remembering [current] for Undo; null when there is none. */
    @Synchronized
    fun redo(current: T): T? {
        val next = redo.removeLastOrNull() ?: return null
        undo.addLast(current)
        while (undo.size > limit) undo.removeFirst()
        return next
    }

    val undoCount: Int @Synchronized get() = undo.size
    val redoCount: Int @Synchronized get() = redo.size
}

/**
 * Drag-and-drop reorder of the page grid (PDF-04, §7.3), as plain state so it can be tested
 * without a screen. While a tile is dragged, [order] is the order shown (the dragged page moves
 * live); [drop] returns the move to save, or null when nothing changed or the drag was cancelled.
 */
class ReorderState(private val count: Int) {
    // Snapshot state, so the grid recomposes while the finger moves.
    private val fromState = androidx.compose.runtime.mutableIntStateOf(-1)
    private val toState = androidx.compose.runtime.mutableIntStateOf(-1)
    var from: Int
        get() = fromState.intValue
        private set(v) { fromState.intValue = v }
    var to: Int
        get() = toState.intValue
        private set(v) { toState.intValue = v }
    val dragging: Boolean get() = from >= 0

    fun start(index: Int) {
        require(index in 0 until count)
        from = index; to = index
    }

    /** The pointer is over the tile shown at position [index]: the page will land there. */
    fun hover(index: Int) {
        if (!dragging) return
        to = index.coerceIn(0, count - 1)
    }

    fun cancel() { from = -1; to = -1 }

    fun drop(): Pair<Int, Int>? {
        val move = if (dragging && from != to) from to to else null
        cancel()
        return move
    }

    /** Indices of the original list in the order shown now. */
    fun order(): List<Int> {
        val l = (0 until count).toMutableList()
        if (dragging && from != to) l.add(to, l.removeAt(from))
        return l
    }
}
