package com.fastkiwi.scan.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CropGeometryTest {
    private val w = 1000f
    private val h = 1400f
    private val q = Quad(Pt(100f, 100f), Pt(900f, 120f), Pt(880f, 1300f), Pt(120f, 1280f))

    private fun assertPt(e: Pt, a: Pt, tol: Float = 1e-3f) {
        assertEquals("x", e.x, a.x, tol)
        assertEquals("y", e.y, a.y, tol)
    }

    // ---------------------------------------------------------------- hit test

    @Test
    fun hitTestFindsCornersBeforeEdges() {
        assertEquals(Handle(Handle.Kind.CORNER, 0), CropGeometry.hitTest(q, Pt(110f, 95f), 40f))
        assertEquals(Handle(Handle.Kind.CORNER, 2), CropGeometry.hitTest(q, Pt(870f, 1290f), 40f))
        // Midpoint of the top side (TL -> TR).
        assertEquals(Handle(Handle.Kind.EDGE, 0), CropGeometry.hitTest(q, Pt(500f, 112f), 40f))
        // Midpoint of the left side (BL -> TL) is side 3.
        assertEquals(Handle(Handle.Kind.EDGE, 3), CropGeometry.hitTest(q, Pt(112f, 690f), 40f))
        assertNull(CropGeometry.hitTest(q, Pt(500f, 700f), 40f))
    }

    // ---------------------------------------------------------------- dragging

    @Test
    fun cornerDragMovesOnlyThatCornerAndStaysInside() {
        val moved = CropGeometry.dragCorner(q, 1, Pt(950f, 60f), w, h)
        assertPt(Pt(950f, 60f), moved.tr)
        assertEquals(q.tl, moved.tl); assertEquals(q.br, moved.br); assertEquals(q.bl, moved.bl)
        val outside = CropGeometry.dragCorner(q, 3, Pt(-50f, 2000f), w, h)
        assertPt(Pt(0f, h), outside.bl)
    }

    @Test
    fun edgeDragMovesSideParallelUsingOnlyPerpendicularMotion() {
        // Top side of an axis-aligned quad: sideways motion is ignored, vertical motion applied.
        val r = Quad(Pt(100f, 100f), Pt(900f, 100f), Pt(900f, 1300f), Pt(100f, 1300f))
        val moved = CropGeometry.dragEdge(r, 0, Pt(70f, 50f), w, h)
        assertPt(Pt(100f, 150f), moved.tl)
        assertPt(Pt(900f, 150f), moved.tr)
        assertEquals(r.br, moved.br); assertEquals(r.bl, moved.bl)
        // Right side moves horizontally only.
        val right = CropGeometry.dragEdge(r, 1, Pt(-40f, 300f), w, h)
        assertPt(Pt(860f, 100f), right.tr)
        assertPt(Pt(860f, 1300f), right.br)
    }

    @Test
    fun edgeDragIsClampedToImage() {
        val r = Quad(Pt(100f, 100f), Pt(900f, 100f), Pt(900f, 1300f), Pt(100f, 1300f))
        val moved = CropGeometry.dragEdge(r, 2, Pt(0f, 500f), w, h)
        assertPt(Pt(900f, h), moved.br)
        assertPt(Pt(100f, h), moved.bl)
    }

    // ---------------------------------------------------------------- snapping

    @Test
    fun cornerSnapsToDetectedCornerOrSide() {
        val detected = Quad(Pt(98f, 104f), Pt(905f, 118f), Pt(882f, 1305f), Pt(118f, 1282f))
        assertPt(Pt(905f, 118f), CropGeometry.snapCorner(Pt(915f, 125f), detected, 30f)!!)
        // Near the middle of the detected left side: snaps onto that line.
        val onSide = CropGeometry.snapCorner(Pt(125f, 700f), detected, 30f)!!
        assertTrue(onSide.x in 105f..112f)
        // Far from everything: no snap.
        assertNull(CropGeometry.snapCorner(Pt(500f, 700f), detected, 30f))
        assertNull(CropGeometry.snapCorner(Pt(500f, 700f), null, 30f))
    }

    @Test
    fun edgeSnapsWhenBothEndsAreClose() {
        val detected = Quad(Pt(98f, 104f), Pt(905f, 118f), Pt(882f, 1305f), Pt(118f, 1282f))
        val snapped = CropGeometry.snapEdge(q, 0, detected, 20f)
        assertNotNull(snapped)
        assertEquals(detected.tl, snapped!!.tl)
        assertEquals(detected.tr, snapped.tr)
        val far = CropGeometry.dragEdge(q, 0, Pt(0f, 200f), w, h)
        assertNull(CropGeometry.snapEdge(far, 0, detected, 20f))
    }

    // ---------------------------------------------------------------- validity

    @Test
    fun detectsCrossedConcaveAndTinyOutlines() {
        assertNull(CropGeometry.validate(q, w, h))
        // Swapping two corners makes a bow-tie.
        val crossed = Quad(q.tl, q.br, q.tr, q.bl)
        assertEquals(QuadProblem.CROSSED, CropGeometry.validate(crossed, w, h))
        // Pulling one corner inside makes it concave.
        val concave = q.copy(br = Pt(300f, 300f))
        assertEquals(QuadProblem.CONCAVE, CropGeometry.validate(concave, w, h))
        val tiny = Quad(Pt(10f, 10f), Pt(20f, 10f), Pt(20f, 20f), Pt(10f, 20f))
        assertEquals(QuadProblem.TOO_SMALL, CropGeometry.validate(tiny, w, h))
        // All points on one line: degenerate, never crashes.
        val line = Quad(Pt(0f, 0f), Pt(10f, 10f), Pt(20f, 20f), Pt(30f, 30f))
        assertTrue(CropGeometry.validate(line, w, h) != null)
    }

    @Test
    fun fixReordersCrossedOutline() {
        val crossed = Quad(q.tl, q.br, q.tr, q.bl)
        val fixed = CropGeometry.fix(crossed, w, h)!!
        assertNull(CropGeometry.validate(fixed, w, h))
        assertEquals(q, fixed)
        assertNull(CropGeometry.fix(q.copy(br = Pt(300f, 300f)), w, h))
    }

    // ---------------------------------------------------------------- rotation / EXIF

    @Test
    fun viewRotationRoundTrips() {
        val p = Pt(0.2f, 0.7f)
        for (r in listOf(0, 90, 180, 270)) {
            assertPt(p, CropGeometry.fromView(CropGeometry.toView(p, r), r), 1e-5f)
        }
        assertPt(Pt(0.3f, 0.2f), CropGeometry.toView(p, 90), 1e-5f)
        assertEquals(1400f to 1000f, CropGeometry.viewSize(1000f, 1400f, 90))
    }

    /** Applies an EXIF transform to real pixels and checks the point mapping agrees. */
    @Test
    fun exifPointMappingMatchesPixelTransformForAllEightOrientations() {
        val rw = 5
        val rh = 3
        val mx = 3
        val my = 1 // marked pixel in the raw image
        for (o in 1..8) {
            val t = ExifOrientation.transform(o)
            // Rotate the pixel grid clockwise, then mirror (reference implementation).
            var cw = rw; var ch = rh; var x = mx; var y = my
            repeat(t.rotation / 90) {
                val nx = ch - 1 - y
                val ny = x
                x = nx; y = ny
                val tmp = cw; cw = ch; ch = tmp
            }
            if (t.flipHorizontal) x = cw - 1 - x
            // The same point through the normalised mapping (pixel centres).
            val mapped = ExifOrientation.mapPoint(Pt((mx + 0.5f) / rw, (my + 0.5f) / rh), t)
            assertEquals("orientation $o x", (x + 0.5f) / cw, mapped.x, 1e-5f)
            assertEquals("orientation $o y", (y + 0.5f) / ch, mapped.y, 1e-5f)
        }
        // Known values: 6 = rotate 90, 5 = transpose (x,y) -> (y,x).
        assertEquals(ExifOrientation.Transform(90, false), ExifOrientation.transform(6))
        val transposed = ExifOrientation.mapPoint(Pt(0.1f, 0.6f), ExifOrientation.transform(5))
        assertPt(Pt(0.6f, 0.1f), transposed, 1e-5f)
    }

    @Test
    fun quadDetectedOnRawImageMapsToUprightOutline() {
        // A page detected in a sideways (EXIF 6) photo stays a valid page once upright.
        val raw = Quad(Pt(0.1f, 0.2f), Pt(0.8f, 0.15f), Pt(0.85f, 0.9f), Pt(0.12f, 0.85f))
        val t = ExifOrientation.transform(6)
        val upright = Quad.order(raw.points.map { ExifOrientation.mapPoint(it, t) })
        assertNull(CropGeometry.validate(upright, 1f, 1f))
        assertEquals(raw.area(), upright.area(), 1e-4f)
    }
}
