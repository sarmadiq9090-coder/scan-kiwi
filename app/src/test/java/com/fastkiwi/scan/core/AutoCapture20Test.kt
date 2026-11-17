// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 2.0 auto-capture: 5-frame corner median (CAM-01/02), blur and shake gates (CAM-03). */
class AutoCapture20Test {
    private val page = Quad(Pt(0.1f, 0.1f), Pt(0.9f, 0.1f), Pt(0.9f, 0.9f), Pt(0.1f, 0.9f))
    private val wild = Quad(Pt(0.3f, 0.05f), Pt(0.95f, 0.3f), Pt(0.7f, 0.95f), Pt(0.05f, 0.6f))

    @Test
    fun medianIgnoresSingleOutlierFrames() {
        val m = AutoCapture.medianQuad(listOf(page, page, wild, page, page))
        assertTrue(m.maxCornerShift(page) < 1e-6f)
        // With 1.2.0 (no median) the same outlier would restart the countdown.
        val g = AutoCapture(holdMillis = 900)
        var t = 0L
        g.feed(page, true, t)
        for (i in 1..9) {
            t += 100
            g.feed(if (i == 4 || i == 7) wild else page, true, t)
        }
        assertEquals(AutoCapture.State.FIRE, g.feed(page, true, t + 100).state)
        val legacy = AutoCapture(DetectionConfig.LEGACY_1_2)
        t = 0L
        legacy.feed(page, true, t)
        for (i in 1..9) { t += 100; legacy.feed(if (i == 7) wild else page, true, t) }
        assertEquals(AutoCapture.State.HOLD, legacy.feed(page, true, t + 100).state)
    }

    @Test
    fun waitsAfterTheCameraOpensAndAfterAModeChange() {
        val g = AutoCapture(holdMillis = 900, startDelayMillis = 2500)
        var t = 1000L
        // A page held still from the first frame: no shot before 2.5 s (BLACKBOX-2.0 re-test, Book mode).
        while (t < 1000L + 2400) { assertEquals("at $t", AutoCapture.State.HOLD, g.feed(page, true, t).state); t += 100 }
        t = 1000L + 2500
        assertEquals(AutoCapture.State.FIRE, g.feed(page, true, t).state)
        // A mode change waits again.
        g.captured(page)
        val g2 = AutoCapture(holdMillis = 900, startDelayMillis = 2500)
        g2.feed(page, true, 0L)
        g2.restartDelay(2000L)
        for (k in 1..40) assertTrue(g2.feed(page, true, 2000L + k * 100).state != AutoCapture.State.FIRE || 2000L + k * 100 >= 4500L)
        assertEquals(AutoCapture.State.FIRE, g2.feed(page, true, 4600L).state)
    }

    @Test
    fun waitsForSharpFrame() {
        val g = AutoCapture(holdMillis = 300)
        var t = 0L
        repeat(10) { t += 100; assertEquals(AutoCapture.State.BLURRY, g.feed(page, true, t, sharp = false).state) }
        // Sharp again: the countdown starts from zero, then fires.
        t += 100; assertEquals(AutoCapture.State.HOLD, g.feed(page, true, t).state)
        t += 400; assertEquals(AutoCapture.State.FIRE, g.feed(page, true, t).state)
    }

    @Test
    fun shakingPhoneDoesNotFire() {
        val g = AutoCapture(holdMillis = 300)
        var t = 0L
        g.feed(page, true, t)
        var sawMoving = false
        for (i in 1..20) {
            t += 50
            val d = if (i % 2 == 0) 0.012f else -0.012f // 2.4% per 50 ms: a shaking hand
            val u = g.feed(page.map { Pt(it.x + d, it.y) }, true, t)
            assertTrue(u.state != AutoCapture.State.FIRE)
            if (u.state == AutoCapture.State.MOVING) sawMoving = true
        }
        assertTrue(sawMoving)
    }

    @Test
    fun completenessRules() {
        assertTrue(QualityMetrics.isComplete(page))
        // A corner within 2% of the border.
        assertTrue(!QualityMetrics.isComplete(page.map { if (it == page.tl) Pt(0.01f, 0.1f) else it }))
        // Too small (under 20% of the frame).
        assertTrue(!QualityMetrics.isComplete(Quad(Pt(0.4f, 0.4f), Pt(0.8f, 0.4f), Pt(0.8f, 0.8f), Pt(0.4f, 0.8f))))
        assertTrue(!QualityMetrics.isComplete(page, cut = true))
    }
}
