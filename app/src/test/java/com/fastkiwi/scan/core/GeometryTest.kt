package com.fastkiwi.scan.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeometryTest {
    @Test
    fun orderSortsClockwiseFromTopLeft() {
        val q = Quad.order(listOf(Pt(90f, 95f), Pt(10f, 5f), Pt(5f, 90f), Pt(95f, 10f)))
        assertEquals(Pt(10f, 5f), q.tl)
        assertEquals(Pt(95f, 10f), q.tr)
        assertEquals(Pt(90f, 95f), q.br)
        assertEquals(Pt(5f, 90f), q.bl)
    }

    @Test
    fun areaAndConvexity() {
        val q = Quad.full(10f, 20f)
        assertEquals(200f, q.area(), 1e-3f)
        assertTrue(q.isConvex())
        val bowtie = Quad(Pt(0f, 0f), Pt(10f, 10f), Pt(10f, 0f), Pt(0f, 10f))
        assertFalse(bowtie.isConvex())
    }

    @Test
    fun outputSizeUsesLongestOppositeEdges() {
        val q = Quad(Pt(0f, 0f), Pt(100f, 0f), Pt(90f, 200f), Pt(10f, 200f))
        val (w, h) = q.outputSize()
        assertEquals(100, w)
        assertEquals(200, h)
    }

    @Test
    fun rotateNormalizedMatchesUprightFrame() {
        // A point at the raw top-left goes to the top-right after a 90 degree clockwise turn.
        val raw = Quad(Pt(0.1f, 0.1f), Pt(0.5f, 0.1f), Pt(0.5f, 0.4f), Pt(0.1f, 0.4f))
        val r = raw.rotateNormalized(90)
        assertEquals(0.6f, r.tl.x, 1e-4f)
        assertEquals(0.1f, r.tl.y, 1e-4f)
        assertEquals(0.9f, r.br.x, 1e-4f)
        assertEquals(0.5f, r.br.y, 1e-4f)
        assertEquals(raw, raw.rotateNormalized(0))
    }
}
