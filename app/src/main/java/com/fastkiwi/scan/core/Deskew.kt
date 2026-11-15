// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.core

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * 2.0-cam2: automatic document alignment (PRD §4.2). The quality report measures the text-line
 * angle left after the perspective correction ([QualityMetrics.skew]); a small residual
 * ([MIN_DEGREES]..[MAX_DEGREES]) is removed by turning the page outline itself: the rectified
 * page's rectangle is turned by the skew about its centre and mapped back into the photo through
 * the outline's homography. The page is then rendered by the one perspective warp it always had
 * (no second resampling, nothing invented at the corners), the crop editor shows the turned
 * outline, and Undo or a re-crop brings the old one back.
 */
object Deskew {
    /** Smaller residual skew is left as it is (it is within the measure's own precision). */
    const val MIN_DEGREES = 0.3f

    /** 5 degrees plus the measure's margin; larger "skew" is more likely a layout choice than a crooked capture. */
    const val MAX_DEGREES = 5.5f

    fun wants(skewDegrees: Float): Boolean = abs(skewDegrees) >= MIN_DEGREES && abs(skewDegrees) <= MAX_DEGREES

    /**
     * The outline (normalised in a [srcW] x [srcH] photo) whose rectification shows the page turned
     * back by [skewDegrees]. Null when the measure is outside the range, the outline is degenerate,
     * or a turned corner would leave the photo (a page touching the frame is left as it is).
     */
    fun straighten(corners: Quad, srcW: Int, srcH: Int, skewDegrees: Float): Quad? {
        if (!wants(skewDegrees) || srcW <= 0 || srcH <= 0) return null
        val q = corners.scale(srcW.toFloat(), srcH.toFloat())
        val hm = Homography.squareToQuad(q) ?: return null
        val (ow, oh) = q.outputSize()
        if (ow < 8 || oh < 8) return null
        val cx = ow / 2f
        val cy = oh / 2f
        val rad = Math.toRadians(skewDegrees.toDouble())
        val c = cos(rad).toFloat()
        val s = sin(rad).toFloat()
        val rect = listOf(Pt(0f, 0f), Pt(ow.toFloat(), 0f), Pt(ow.toFloat(), oh.toFloat()), Pt(0f, oh.toFloat()))
        val pts = rect.map { p ->
            val dx = p.x - cx
            val dy = p.y - cy
            // Text lines run along (cos a, sin a) in the rectified page (y down): the new top edge does too.
            val u = (cx + c * dx - s * dy) / ow
            val v = (cy + s * dx + c * dy) / oh
            val m = hm.map(u, v)
            Pt(m.x / srcW, m.y / srcH)
        }
        if (pts.any { it.x.isNaN() || it.y.isNaN() || it.x < 0f || it.y < 0f || it.x > 1f || it.y > 1f }) return null
        val out = Quad(pts[0], pts[1], pts[2], pts[3])
        return if (out.isConvex()) out else null
    }
}
