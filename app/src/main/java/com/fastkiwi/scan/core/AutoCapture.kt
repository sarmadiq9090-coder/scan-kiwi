// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.core

/**
 * Decides when the camera may take a photo by itself.
 *
 * Time based (not frame based), so slow phones behave like fast ones:
 *  - the outline must stay within [maxShift] (fraction of the frame) for [holdMillis];
 *  - a few missed detections ([graceMisses]) do not restart the countdown;
 *  - only a confident outline can trigger a shot;
 *  - after a shot the gate stays closed until the page changes (it leaves the
 *    view, or moves by more than [rearmShift]), so a page is never captured twice.
 *
 * Corners are smoothed for display. Pure Kotlin, unit-tested.
 */
class AutoCapture(
    private val holdMillis: Long = 900,
    private val maxShift: Float = 0.02f,
    private val graceMisses: Int = 4,
    private val minArea: Float = 0.12f,
    private val rearmShift: Float = 0.15f,
) {
    enum class State { SEARCHING, TOO_SMALL, FOUND, HOLD, FIRE, CAPTURED }

    data class Update(val state: State, val quad: Quad?, val progress: Float)

    private var smooth: Quad? = null
    private var anchor: Quad? = null
    private var holdStart = 0L
    private var misses = 0
    private var firedQuad: Quad? = null
    /** State of the last frame that had an outline and no countdown (FOUND or TOO_SMALL). */
    private var lastSeen = State.FOUND

    fun reset() {
        smooth = null; anchor = null; misses = 0; firedQuad = null
    }

    /** Call after a photo was taken (manually or by [State.FIRE]). */
    fun captured(quad: Quad?) {
        firedQuad = quad ?: smooth ?: anchor
        anchor = null
    }

    /**
     * Feeds one analysed frame. [quad] is normalised (0..1) or null when no page
     * was found; [confident] comes from the detector; [enabled] is the auto switch.
     */
    fun feed(quad: Quad?, confident: Boolean, timeMs: Long, enabled: Boolean = true): Update {
        if (quad == null) {
            misses++
            if (misses > graceMisses) {
                smooth = null; anchor = null
                firedQuad = null // page left the view: ready for the next one
                return Update(State.SEARCHING, null, 0f)
            }
            val s = smooth ?: return Update(State.SEARCHING, null, 0f)
            // Keep showing what the last real frame showed: only a running countdown is HOLD.
            val state = when {
                firedQuad != null -> State.CAPTURED
                anchor != null -> State.HOLD
                else -> lastSeen
            }
            return Update(state, s, progress(timeMs))
        }
        misses = 0
        val prev = smooth
        val s = if (prev == null) quad else blend(prev, quad, 0.45f)
        smooth = s

        if (quad.area() < minArea) {
            anchor = null
            lastSeen = State.TOO_SMALL
            return Update(State.TOO_SMALL, s, 0f)
        }
        val fired = firedQuad
        if (fired != null) {
            if (quad.maxCornerShift(fired) > rearmShift) firedQuad = null
            else return Update(State.CAPTURED, s, 0f)
        }
        lastSeen = State.FOUND
        if (!enabled || !confident) {
            anchor = null
            return Update(State.FOUND, s, 0f)
        }
        val a = anchor
        if (a == null || quad.maxCornerShift(a) > maxShift) {
            anchor = quad
            holdStart = timeMs
        }
        val p = progress(timeMs)
        return if (p >= 1f) Update(State.FIRE, s, 1f) else Update(State.HOLD, s, p)
    }

    private fun progress(timeMs: Long): Float =
        if (anchor == null) 0f else ((timeMs - holdStart).toFloat() / holdMillis).coerceIn(0f, 1f)

    private fun blend(a: Quad, b: Quad, t: Float): Quad {
        fun m(p: Pt, q: Pt) = Pt(p.x + (q.x - p.x) * t, p.y + (q.y - p.y) * t)
        return Quad(m(a.tl, b.tl), m(a.tr, b.tr), m(a.br, b.br), m(a.bl, b.bl))
    }
}
