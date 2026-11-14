// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.core

import kotlin.math.abs
import kotlin.math.hypot

/**
 * Projective map from the unit square to a quadrilateral:
 * (0,0)->TL, (1,0)->TR, (1,1)->BR, (0,1)->BL (Heckbert's square-to-quad).
 * This is the same mapping the renderer applies (in reverse) when it
 * straightens a page, so it lets us move points between the photo and the page.
 */
class Homography private constructor(private val m: DoubleArray) {

    fun map(u: Float, v: Float): Pt {
        val w = m[6] * u + m[7] * v + m[8]
        return Pt(((m[0] * u + m[1] * v + m[2]) / w).toFloat(), ((m[3] * u + m[4] * v + m[5]) / w).toFloat())
    }

    fun inverse(): Homography? {
        val a = m
        val det = a[0] * (a[4] * a[8] - a[5] * a[7]) - a[1] * (a[3] * a[8] - a[5] * a[6]) + a[2] * (a[3] * a[7] - a[4] * a[6])
        if (abs(det) < 1e-12) return null
        val inv = doubleArrayOf(
            (a[4] * a[8] - a[5] * a[7]), -(a[1] * a[8] - a[2] * a[7]), (a[1] * a[5] - a[2] * a[4]),
            -(a[3] * a[8] - a[5] * a[6]), (a[0] * a[8] - a[2] * a[6]), -(a[0] * a[5] - a[2] * a[3]),
            (a[3] * a[7] - a[4] * a[6]), -(a[0] * a[7] - a[1] * a[6]), (a[0] * a[4] - a[1] * a[3]),
        )
        for (i in inv.indices) inv[i] /= det
        return Homography(inv)
    }

    companion object {
        fun squareToQuad(q: Quad): Homography? {
            val x0 = q.tl.x.toDouble(); val y0 = q.tl.y.toDouble()
            val x1 = q.tr.x.toDouble(); val y1 = q.tr.y.toDouble()
            val x2 = q.br.x.toDouble(); val y2 = q.br.y.toDouble()
            val x3 = q.bl.x.toDouble(); val y3 = q.bl.y.toDouble()
            val dx1 = x1 - x2; val dx2 = x3 - x2; val dx3 = x0 - x1 + x2 - x3
            val dy1 = y1 - y2; val dy2 = y3 - y2; val dy3 = y0 - y1 + y2 - y3
            val den = dx1 * dy2 - dx2 * dy1
            if (abs(den) < 1e-12) return null
            val g = (dx3 * dy2 - dx2 * dy3) / den
            val h = (dx1 * dy3 - dx3 * dy1) / den
            return Homography(
                doubleArrayOf(
                    x1 - x0 + g * x1, x3 - x0 + h * x3, x0,
                    y1 - y0 + g * y1, y3 - y0 + h * y3, y0,
                    g, h, 1.0,
                ),
            )
        }
    }
}

/**
 * Keeps a signature on the same spot of the paper when the page is
 * re-cropped or rotated afterwards (1.1.1 left it at the old page position).
 *
 * A stamp is (cx, cy, width) as fractions of the finished (cropped + rotated)
 * page. Each stamp is taken back to the photo through the old crop and
 * rotation, then forward through the new ones. Its width is measured along
 * its baseline, so it keeps its real size even when the page turns 90°.
 */
object StampMapper {

    /** Finished-page size in pixels for a crop (source-normalised corners) and rotation. */
    fun pageSize(srcW: Float, srcH: Float, corners: Quad, rotation: Int): Pair<Float, Float> {
        val (w, h) = corners.scale(srcW, srcH).outputSize()
        return CropGeometry.viewSize(w.toFloat(), h.toFloat(), rotation)
    }

    /**
     * Returns the stamps re-mapped from the old crop/rotation to the new one,
     * each as floatArrayOf(cx, cy, width); null when a crop is degenerate.
     * Centres are clamped to the page so a signature is never silently lost.
     */
    fun remap(
        stamps: List<FloatArray>,
        srcW: Float,
        srcH: Float,
        oldCorners: Quad,
        oldRotation: Int,
        newCorners: Quad,
        newRotation: Int,
    ): List<FloatArray>? {
        val h1 = Homography.squareToQuad(oldCorners.scale(srcW, srcH)) ?: return null
        val h2inv = Homography.squareToQuad(newCorners.scale(srcW, srcH))?.inverse() ?: return null
        val (nw, nh) = pageSize(srcW, srcH, newCorners, newRotation)

        fun toNew(p: Pt): Pt {
            val unrot = CropGeometry.fromView(p, oldRotation)
            val src = h1.map(unrot.x, unrot.y)
            val uv = h2inv.map(src.x, src.y)
            return CropGeometry.toView(uv, newRotation)
        }

        return stamps.map { s ->
            val c = toNew(Pt(s[0], s[1]))
            val l = toNew(Pt(s[0] - s[2] / 2f, s[1]))
            val r = toNew(Pt(s[0] + s[2] / 2f, s[1]))
            val widthPx = hypot((r.x - l.x) * nw, (r.y - l.y) * nh)
            floatArrayOf(c.x.coerceIn(0f, 1f), c.y.coerceIn(0f, 1f), (widthPx / nw).coerceIn(0.03f, 1f))
        }
    }
}
