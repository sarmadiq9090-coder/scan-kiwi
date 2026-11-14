// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.core

import com.fastkiwi.scan.feedback.DeviceInfo
import com.fastkiwi.scan.feedback.Feedback
import com.fastkiwi.scan.feedback.FeedbackKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder
import kotlin.random.Random

/** 1.2.0 core: signature mapping, auto-capture gate, paper channel, feedback links. */
class V12CoreTest {

    // ------------------------------------------------------------ homography / stamps

    @Test
    fun homographyMapsCornersAndInverts() {
        val q = Quad(Pt(100f, 80f), Pt(900f, 120f), Pt(860f, 1250f), Pt(140f, 1180f))
        val h = Homography.squareToQuad(q)!!
        assertPt(q.tl, h.map(0f, 0f)); assertPt(q.tr, h.map(1f, 0f))
        assertPt(q.br, h.map(1f, 1f)); assertPt(q.bl, h.map(0f, 1f))
        val inv = h.inverse()!!
        val p = h.map(0.3f, 0.7f)
        assertPt(Pt(0.3f, 0.7f), inv.map(p.x, p.y), 1e-4f)
    }

    @Test
    fun signatureFollowsRotation() {
        // Bottom-right signature on an upright page; after rotating the page 90°
        // clockwise the same paper spot is at the bottom-left.
        val full = Quad.full(1f, 1f)
        val out = StampMapper.remap(listOf(floatArrayOf(0.8f, 0.9f, 0.3f)), 2000f, 3000f, full, 0, full, 90)!![0]
        assertEquals(0.1f, out[0], 1e-3f)
        assertEquals(0.8f, out[1], 1e-3f)
        // Width: 0.3 of a 2000 px wide page = 600 px; the rotated page is 3000 px wide.
        assertEquals(600f / 3000f, out[2], 1e-3f)
    }

    @Test
    fun signatureFollowsRecrop() {
        // Old crop: the whole photo. New crop: only the right half.
        val full = Quad.full(1f, 1f)
        val right = Quad(Pt(0.5f, 0f), Pt(1f, 0f), Pt(1f, 1f), Pt(0.5f, 1f))
        val out = StampMapper.remap(listOf(floatArrayOf(0.75f, 0.5f, 0.2f)), 2000f, 2000f, full, 0, right, 0)!![0]
        assertEquals(0.5f, out[0], 1e-3f) // centre of the right half
        assertEquals(0.5f, out[1], 1e-3f)
        assertEquals(0.4f, out[2], 1e-3f) // same 400 px on a 1000 px page
        // A spot that was cropped away is clamped onto the page, never lost.
        val gone = StampMapper.remap(listOf(floatArrayOf(0.1f, 0.5f, 0.2f)), 2000f, 2000f, full, 0, right, 0)!![0]
        assertEquals(0f, gone[0], 1e-6f)
    }

    // ------------------------------------------------------------ auto-capture

    private val page = Quad(Pt(0.1f, 0.1f), Pt(0.9f, 0.1f), Pt(0.9f, 0.9f), Pt(0.1f, 0.9f))

    @Test
    fun firesAfterHoldingSteadyAndOnlyOnce() {
        val g = AutoCapture(holdMillis = 900)
        var t = 0L
        assertEquals(AutoCapture.State.HOLD, g.feed(page, true, t).state)
        t += 500; assertEquals(AutoCapture.State.HOLD, g.feed(page, true, t).state)
        t += 500; assertEquals(AutoCapture.State.FIRE, g.feed(page, true, t).state)
        g.captured(page)
        // Same page still in view: no second shot, however long it stays.
        repeat(10) { t += 500; assertEquals(AutoCapture.State.CAPTURED, g.feed(page, true, t).state) }
        // The page leaves the view, a new one arrives: the gate re-arms.
        repeat(6) { t += 100; g.feed(null, false, t) }
        t += 100; assertEquals(AutoCapture.State.HOLD, g.feed(page, true, t).state)
    }

    @Test
    fun jitterAndShortDropoutsDoNotRestartButMovementDoes() {
        val g = AutoCapture(holdMillis = 900, maxShift = 0.02f, graceMisses = 4)
        val rnd = Random(1)
        var t = 0L
        g.feed(page, true, t)
        for (i in 0 until 6) {
            t += 100
            if (i == 2) { g.feed(null, false, t); continue } // one missed frame
            val j = page.map { Pt(it.x + rnd.nextFloat() * 0.01f, it.y + rnd.nextFloat() * 0.01f) }
            g.feed(j, true, t)
        }
        t += 400
        assertEquals(AutoCapture.State.FIRE, g.feed(page, true, t).state)
        // Moving the phone resets the countdown.
        val g2 = AutoCapture(holdMillis = 900)
        g2.feed(page, true, 0)
        val moved = page.map { Pt(it.x + 0.05f, it.y) }
        assertEquals(0f, g2.feed(moved, true, 600).progress, 1e-6f)
    }

    @Test
    fun doubtfulOrTinyOrDisabledNeverFires() {
        val g = AutoCapture(holdMillis = 100)
        assertEquals(AutoCapture.State.FOUND, g.feed(page, false, 0).state)
        assertEquals(AutoCapture.State.FOUND, g.feed(page, false, 1000).state)
        val tiny = Quad(Pt(0.4f, 0.4f), Pt(0.5f, 0.4f), Pt(0.5f, 0.5f), Pt(0.4f, 0.5f))
        assertEquals(AutoCapture.State.TOO_SMALL, g.feed(tiny, true, 2000).state)
        assertEquals(AutoCapture.State.FOUND, g.feed(page, true, 3000, enabled = false).state)
    }

    // ------------------------------------------------------------ hard backgrounds

    @Test
    fun whitePageOnLightColouredDeskIsFoundViaPaperChannel() {
        val w = 480; val h = 640
        val q = Quad(Pt(90f, 80f), Pt(400f, 100f), Pt(420f, 570f), Pt(70f, 550f))
        val px = IntArray(w * h)
        val rnd = Random(5)
        for (y in 0 until h) for (x in 0 until w) {
            val inside = inside(q, x + 0.5f, y + 0.5f)
            // Desk: light orange-pink with about the same brightness as the paper.
            val (r, g, b) = if (inside) Triple(232, 230, 226) else Triple(250, 205, 175)
            val n = rnd.nextInt(-6, 7)
            px[y * w + x] = (0xFF shl 24) or ((r + n).coerceIn(0, 255) shl 16) or ((g + n).coerceIn(0, 255) shl 8) or (b + n).coerceIn(0, 255)
        }
        val det = QuadDetector(320).detectBest(listOf({ ImageFilters.toGray(px, w, h) }, { ImageFilters.paperMap(px, w, h) }))
        assertNotNull("page not found on a light coloured desk", det)
        assertTrue("corners off: ${det!!.quad}", det.quad.maxCornerShift(q) < 0.03f * h)
    }

    private fun inside(q: Quad, x: Float, y: Float): Boolean {
        val p = q.points
        var sign = 0
        for (i in 0 until 4) {
            val a = p[i]; val b = p[(i + 1) % 4]
            val s = if ((b.x - a.x) * (y - a.y) - (b.y - a.y) * (x - a.x) >= 0) 1 else -1
            if (sign == 0) sign = s else if (s != sign) return false
        }
        return true
    }

    // ------------------------------------------------------------ feedback links

    private val info = DeviceInfo("1.2.0", 4, "15 (API 35)", "Google Pixel 8", "ar", "EXPORT", "IOException")

    @Test
    fun mailtoIsEncodedAndCarriesArabic() {
        val m = Feedback.mailto(FeedbackKind.PROBLEM, "1.2.0", "لا يعمل الزر & 100%", null)
        assertTrue(m.startsWith("mailto:support@fastkiwi.app?subject="))
        assertFalse(m.contains(" "))
        assertFalse(m.contains("+"))
        val subject = URLDecoder.decode(m.substringAfter("subject=").substringBefore("&body="), "UTF-8")
        assertEquals("[Scan Kiwi 1.2.0] مشكلة", subject)
        val body = URLDecoder.decode(m.substringAfter("&body="), "UTF-8")
        assertEquals("لا يعمل الزر & 100%", body)
    }

    @Test
    fun deviceInfoOnlyWhenTicked() {
        val without = Feedback.body("نص", null)
        assertFalse(without.contains("Android"))
        assertFalse(without.contains("Pixel"))
        val with = Feedback.body("نص", info)
        assertTrue(with.contains("Android: 15 (API 35)"))
        assertTrue(with.contains("Error code: EXPORT"))
        assertTrue(with.contains("Error type: IOException"))
        val url = Feedback.githubIssue(FeedbackKind.SUGGESTION, "1.2.0", "فكرة", null)
        assertFalse(url.contains("device="))
        assertTrue(url.contains("template=feature_request.yml"))
    }

    @Test
    fun githubUrlStaysUnderLimitWithoutBreakingCharacters() {
        val long = "مشكلة 😀 ".repeat(2000)
        val url = Feedback.githubIssue(FeedbackKind.PROBLEM, "1.2.0", long, info)
        assertTrue("length ${url.length}", url.length <= Feedback.MAX_URL)
        assertTrue(url.startsWith("https://github.com/sarmadiq9090-coder/scan-kiwi/issues/new?template=bug_report.yml&title="))
        val desc = URLDecoder.decode(url.substringAfter("&description=").substringBefore("&device="), "UTF-8")
        assertTrue(desc.endsWith("…"))
        // No broken surrogate pair (a half emoji would not round-trip).
        assertEquals(desc, String(desc.toByteArray(Charsets.UTF_8), Charsets.UTF_8))
        assertTrue(url.contains("&device="))
    }

    private fun assertPt(e: Pt, a: Pt, tol: Float = 1e-2f) {
        assertEquals(e.x, a.x, tol); assertEquals(e.y, a.y, tol)
    }
}
