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
 *  - 2.0 (CAM-01/02): corners are the median of the last [DetectionConfig.medianFrames] frames,
 *    so a single bad frame neither moves the outline nor restarts the countdown;
 *  - 2.0 (CAM-03): the countdown waits while the frame is blurry or the phone is moving;
 *  - after a shot the gate stays closed until the page changes (it leaves the
 *    view, or moves by more than [rearmShift]), so a page is never captured twice;
 *  - [startDelayMillis] (BLACKBOX-2.0 re-test): no shot by itself until that long after the first frame or
 *    [restartDelay] (a mode change), so the user can choose Book before a page in view is taken;
 *  - 2.0-cam2 (CAM-06, [DetectionConfig.contentRearm]): with a content signature
 *    ([QualityMetrics.contentSignature]) the page itself decides: the page just taken, seen again
 *    after the phone moved or the page was lifted and put back, is not taken again; a new page
 *    laid on the same spot (outline unchanged) re-arms after [DetectionConfig.contentChangeFrames].
 *
 * Corners are smoothed for display. Pure Kotlin, unit-tested.
 */
class AutoCapture(
    private val cfg: DetectionConfig = DetectionConfig.DEFAULT,
    private val holdMillis: Long = cfg.holdMillis,
    private val maxShift: Float = cfg.maxShift,
    private val graceMisses: Int = cfg.graceMisses,
    private val minArea: Float = cfg.autoMinArea,
    private val rearmShift: Float = cfg.rearmShift,
    private val startDelayMillis: Long = 0L,
) {
    enum class State { SEARCHING, TOO_SMALL, FOUND, HOLD, FIRE, CAPTURED, BLURRY, MOVING }

    data class Update(val state: State, val quad: Quad?, val progress: Float)

    private var smooth: Quad? = null
    private var anchor: Quad? = null
    private var holdStart = 0L
    private var misses = 0
    private var firedQuad: Quad? = null
    private val history = ArrayDeque<Quad>()
    private var lastRaw: Quad? = null
    private var lastTime = 0L
    /** State of the last frame that had an outline and no countdown (FOUND, TOO_SMALL, BLURRY, MOVING). */
    private var lastSeen = State.FOUND
    /** 2.0-cam2: content of the last frame, and of the page last taken (survives the page leaving the view). */
    private var lastContent: FloatArray? = null
    private var firedContent: FloatArray? = null
    private var changedFrames = 0
    /** Start of the current wait ([startDelayMillis]); -1 = from the next frame. */
    private var startedAt = -1L

    fun reset() {
        smooth = null; anchor = null; misses = 0; firedQuad = null; history.clear(); lastRaw = null
        lastContent = null; firedContent = null; changedFrames = 0
    }

    /** The camera mode changed: wait [startDelayMillis] again before a shot by itself. */
    fun restartDelay(timeMs: Long) { startedAt = timeMs }

    /** Call after a photo was taken (manually or by [State.FIRE]). */
    fun captured(quad: Quad?) {
        firedQuad = quad ?: smooth ?: anchor
        firedContent = lastContent
        changedFrames = 0
        anchor = null
    }

    /** True / false when both signatures are known, null otherwise. */
    private fun sameAsTaken(content: FloatArray?): Boolean? {
        val f = firedContent ?: return null
        val c = content ?: return null
        return QualityMetrics.similarity(c, f) >= cfg.sameContent
    }

    /**
     * Feeds one analysed frame. [quad] is normalised (0..1) or null when no page
     * was found; [confident] comes from the detector; [enabled] is the auto switch;
     * [sharp] comes from [QualityMetrics.sharpness] on the same frame; [content] is the page's
     * [QualityMetrics.contentSignature] on the same frame (null = unknown: outline rules only).
     */
    fun feed(quad: Quad?, confident: Boolean, timeMs: Long, enabled: Boolean = true, sharp: Boolean = true, content: FloatArray? = null): Update {
        val useContent = cfg.contentRearm
        if (startedAt < 0) startedAt = timeMs
        // A blurred or moving frame's signature is not trusted (the outline may lag the page).
        if (useContent && quad != null) lastContent = if (sharp) content else null
        if (quad == null) {
            misses++
            if (misses > graceMisses) {
                smooth = null; anchor = null; history.clear(); lastRaw = null
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
        history.addLast(quad)
        while (history.size > cfg.medianFrames.coerceAtLeast(1)) history.removeFirst()
        val med = medianQuad(history)
        // Corner speed per 100 ms between consecutive median outlines (shake, CAM-03); the median
        // keeps a single bad frame from looking like movement.
        val prevMed = lastRaw
        val motion = if (prevMed == null) 0f else med.maxCornerShift(prevMed) * 100f / (timeMs - lastTime).coerceAtLeast(1L)
        lastRaw = med
        lastTime = timeMs
        val prev = smooth
        val s = if (prev == null) med else blend(prev, med, 0.6f)
        smooth = s

        if (quad.area() < minArea) {
            anchor = null
            lastSeen = State.TOO_SMALL
            return Update(State.TOO_SMALL, s, 0f)
        }
        val fired = firedQuad
        if (fired != null) {
            val same = if (useContent && sharp) sameAsTaken(content) else null
            changedFrames = if (same == false) changedFrames + 1 else 0
            val moved = med.maxCornerShift(fired) > rearmShift
            // Moved, unless the content says it is still the same page; or new content in place.
            val rearm = (moved && same != true) || (same == false && changedFrames >= cfg.contentChangeFrames)
            if (rearm) { firedQuad = null; changedFrames = 0 }
            else return Update(State.CAPTURED, s, 0f)
        }
        lastSeen = State.FOUND
        if (!enabled || !confident) {
            anchor = null
            return Update(State.FOUND, s, 0f)
        }
        if (motion > cfg.maxMotionPer100ms) {
            anchor = null
            lastSeen = State.MOVING
            return Update(State.MOVING, s, 0f)
        }
        if (!sharp) {
            anchor = null
            lastSeen = State.BLURRY
            return Update(State.BLURRY, s, 0f)
        }
        val a = anchor
        if (a == null || med.maxCornerShift(a) > maxShift) {
            anchor = med
            holdStart = timeMs
        }
        // The page last taken, back in view (outline moved, or it was lifted and put back): stay closed.
        if (useContent && sameAsTaken(content) == true) {
            firedQuad = med
            anchor = null
            return Update(State.CAPTURED, s, 0f)
        }
        val p = progress(timeMs)
        return if (p >= 1f) Update(State.FIRE, s, 1f) else Update(State.HOLD, s, p)
    }

    /** Hold progress; while the start delay runs, the ring fills over the longer of the two. */
    private fun progress(timeMs: Long): Float {
        if (anchor == null) return 0f
        val hold = ((timeMs - holdStart).toFloat() / holdMillis).coerceIn(0f, 1f)
        if (startDelayMillis <= 0L || startedAt < 0) return hold
        val readyAt = startedAt + startDelayMillis
        if (timeMs >= readyAt) return hold
        val span = (readyAt - holdStart).coerceAtLeast(holdMillis)
        return minOf(hold, ((timeMs - holdStart).toFloat() / span).coerceIn(0f, 0.99f))
    }

    private fun blend(a: Quad, b: Quad, t: Float): Quad {
        fun m(p: Pt, q: Pt) = Pt(p.x + (q.x - p.x) * t, p.y + (q.y - p.y) * t)
        return Quad(m(a.tl, b.tl), m(a.tr, b.tr), m(a.br, b.br), m(a.bl, b.bl))
    }

    companion object {
        /** The camera's start delay (opening, or a mode change) before auto-capture may fire. */
        const val CAMERA_START_DELAY_MS = 2500L

        /** Per-coordinate median of the corners (mean of the two middle values for an even count). */
        fun medianQuad(qs: Collection<Quad>): Quad {
            val arrs = qs.map { it.toArray() }
            val out = FloatArray(8) { k ->
                val v = arrs.map { it[k] }.sorted()
                val n = v.size
                if (n % 2 == 1) v[n / 2] else (v[n / 2 - 1] + v[n / 2]) / 2f
            }
            return Quad.fromArray(out)
        }
    }
}
