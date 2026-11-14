// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.core

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class QuadDetectorTest {

    /** Paints a bright page (with dark "text" lines) on a darker, noisy table. */
    private fun scene(w: Int, h: Int, page: Quad, seed: Int = 1, textured: Boolean = false): GrayImage {
        val rnd = Random(seed)
        val data = ByteArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var v = 70 + rnd.nextInt(-12, 13)
            if (textured && (x / 9 + y / 7) % 2 == 0) v += 18 // wood-grain-like texture
            if (inside(page, x + 0.5f, y + 0.5f)) {
                v = 215 + rnd.nextInt(-8, 9)
                // a few text lines inside the page
                val c = centroid(page)
                if (y % 22 in 0..3 && kotlin.math.abs(x - c.x) < 0.25f * w && kotlin.math.abs(y - c.y) < 0.25f * h) v = 40
            }
            data[y * w + x] = v.coerceIn(0, 255).toByte()
        }
        return GrayImage(w, h, data)
    }

    private fun centroid(q: Quad) = Pt(q.points.map { it.x }.average().toFloat(), q.points.map { it.y }.average().toFloat())

    private fun inside(q: Quad, x: Float, y: Float): Boolean {
        val p = q.points
        var sign = 0
        for (i in 0 until 4) {
            val a = p[i]; val b = p[(i + 1) % 4]
            val cross = (b.x - a.x) * (y - a.y) - (b.y - a.y) * (x - a.x)
            val s = if (cross >= 0) 1 else -1
            if (sign == 0) sign = s else if (s != sign) return false
        }
        return true
    }

    private fun assertClose(expected: Quad, actual: Quad, tol: Float) {
        val shift = expected.maxCornerShift(actual)
        assertTrue("corner error $shift px > $tol\nexpected $expected\nactual   $actual", shift <= tol)
    }

    @Test
    fun findsTiltedPageInPerspective() {
        val w = 960; val h = 1280
        val page = Quad(Pt(180f, 210f), Pt(790f, 260f), Pt(860f, 1120f), Pt(110f, 1060f))
        val det = QuadDetector(320).detect(scene(w, h, page))
        assertNotNull("page not detected", det)
        assertClose(page, det!!.quad, 0.02f * h)
    }

    @Test
    fun findsPageOnTexturedBackground() {
        val w = 640; val h = 480
        val page = Quad(Pt(120f, 70f), Pt(530f, 95f), Pt(510f, 420f), Pt(95f, 400f))
        val det = QuadDetector(320).detect(scene(w, h, page, seed = 7, textured = true))
        assertNotNull("page not detected", det)
        assertClose(page, det!!.quad, 0.025f * w)
    }

    @Test
    fun noPageInPlainNoise() {
        val w = 400; val h = 300
        val rnd = Random(3)
        val data = ByteArray(w * h) { (90 + rnd.nextInt(-15, 16)).toByte() }
        assertNull(QuadDetector(320).detect(GrayImage(w, h, data)))
    }

    @Test
    fun convexHullOfSquareWithInteriorPoints() {
        val pts = listOf(Pt(0f, 0f), Pt(10f, 0f), Pt(10f, 10f), Pt(0f, 10f), Pt(5f, 5f), Pt(3f, 7f))
        val hull = QuadDetector.convexHull(pts)
        assertTrue(hull.size == 4)
        val q = QuadDetector.bestQuadInHull(hull)!!
        assertTrue(kotlin.math.abs(q.area() - 100f) < 1e-3f)
    }
}
