package com.fastkiwi.scan.core


/** A grab point on the crop outline: one of 4 corners, or the middle of one of 4 sides. */
data class Handle(val kind: Kind, val index: Int) {
    enum class Kind { CORNER, EDGE }
}

/** Why an outline cannot be used as a page. */
enum class QuadProblem { CROSSED, CONCAVE, TOO_SMALL }

/**
 * Pure geometry behind the crop editor, in any 2-D coordinate space
 * (the editor uses on-screen pixels). Side i runs from corner i to corner i+1
 * in the order TL, TR, BR, BL.
 */
object CropGeometry {

    fun midpoint(q: Quad, side: Int): Pt {
        val p = q.points
        val a = p[side]
        val b = p[(side + 1) % 4]
        return Pt((a.x + b.x) / 2f, (a.y + b.y) / 2f)
    }

    /** Nearest handle within [radius] of [p]; corners win ties so they stay easy to grab. */
    fun hitTest(q: Quad, p: Pt, radius: Float): Handle? {
        var best: Handle? = null
        var bestD = Float.MAX_VALUE
        q.points.forEachIndexed { i, c ->
            val d = c.dist(p)
            if (d <= radius && d < bestD) { bestD = d; best = Handle(Handle.Kind.CORNER, i) }
        }
        if (best != null) return best
        for (s in 0 until 4) {
            val d = midpoint(q, s).dist(p)
            if (d <= radius && d < bestD) { bestD = d; best = Handle(Handle.Kind.EDGE, s) }
        }
        return best
    }

    private fun clamp(p: Pt, w: Float, h: Float) = Pt(p.x.coerceIn(0f, w), p.y.coerceIn(0f, h))

    private fun with(q: Quad, i: Int, p: Pt): Quad {
        val pts = q.points.toMutableList()
        pts[i] = p
        return Quad(pts[0], pts[1], pts[2], pts[3])
    }

    /** Moves one corner to [to], kept inside the image (0..w, 0..h). */
    fun dragCorner(q: Quad, corner: Int, to: Pt, w: Float, h: Float): Quad = with(q, corner, clamp(to, w, h))

    /**
     * Moves a whole side parallel to itself by the part of [delta] that is
     * perpendicular to the side, so the side never twists under the finger.
     */
    fun dragEdge(q: Quad, side: Int, delta: Pt, w: Float, h: Float): Quad {
        val p = q.points
        val a = p[side]
        val b = p[(side + 1) % 4]
        val len = a.dist(b)
        if (len < 1e-3f) return q
        val nx = -(b.y - a.y) / len
        val ny = (b.x - a.x) / len
        val k = delta.x * nx + delta.y * ny
        val move = Pt(nx * k, ny * k)
        return with(with(q, side, clamp(a + move, w, h)), (side + 1) % 4, clamp(b + move, w, h))
    }

    /** Snaps [p] onto the nearest of [targets] when it is within [radius]. */
    fun snapPoint(p: Pt, targets: List<Pt>, radius: Float): Pt? =
        targets.filter { it.dist(p) <= radius }.minByOrNull { it.dist(p) }

    /**
     * Snaps a dragged corner to a detected corner, or onto a detected side
     * (perpendicular foot) when it is close to that line. Returns null when nothing is near.
     */
    fun snapCorner(p: Pt, detected: Quad?, radius: Float): Pt? {
        detected ?: return null
        snapPoint(p, detected.points, radius)?.let { return it }
        var best: Pt? = null
        var bestD = radius
        for (s in 0 until 4) {
            val a = detected.points[s]
            val b = detected.points[(s + 1) % 4]
            val foot = footOnSegment(p, a, b) ?: continue
            val d = foot.dist(p)
            if (d <= bestD) { bestD = d; best = foot }
        }
        return best
    }

    /** Snaps a dragged side onto the matching detected side when both ends are close. */
    fun snapEdge(q: Quad, side: Int, detected: Quad?, radius: Float): Quad? {
        detected ?: return null
        val a = q.points[side]
        val b = q.points[(side + 1) % 4]
        for (s in 0 until 4) {
            val c = detected.points[s]
            val d = detected.points[(s + 1) % 4]
            if (a.dist(c) <= radius && b.dist(d) <= radius) return with(with(q, side, c), (side + 1) % 4, d)
            if (a.dist(d) <= radius && b.dist(c) <= radius) return with(with(q, side, d), (side + 1) % 4, c)
        }
        return null
    }

    private fun footOnSegment(p: Pt, a: Pt, b: Pt): Pt? {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val l2 = dx * dx + dy * dy
        if (l2 < 1e-6f) return null
        val t = ((p.x - a.x) * dx + (p.y - a.y) * dy) / l2
        if (t < 0f || t > 1f) return null
        return Pt(a.x + t * dx, a.y + t * dy)
    }

    /** Proper intersection of segments ab and cd (touching ends do not count). */
    fun segmentsCross(a: Pt, b: Pt, c: Pt, d: Pt): Boolean {
        fun orient(p: Pt, q: Pt, r: Pt): Float = (q.x - p.x) * (r.y - p.y) - (q.y - p.y) * (r.x - p.x)
        val o1 = orient(a, b, c)
        val o2 = orient(a, b, d)
        val o3 = orient(c, d, a)
        val o4 = orient(c, d, b)
        return o1 * o2 < 0f && o3 * o4 < 0f
    }

    /**
     * Checks an outline. [minAreaFraction] is relative to the w x h image.
     * Returns null when the outline is a usable convex quadrilateral.
     */
    fun validate(q: Quad, w: Float, h: Float, minAreaFraction: Float = 0.01f): QuadProblem? {
        val p = q.points
        if (segmentsCross(p[0], p[1], p[2], p[3]) || segmentsCross(p[1], p[2], p[3], p[0])) return QuadProblem.CROSSED
        if (!q.isConvex()) return QuadProblem.CONCAVE
        // Convex but counter-clockwise: a side was dragged past the opposite side,
        // so TL/TR now sit below BL/BR. The warp would mirror the page. "Fix" re-orders it.
        if (signedArea(q) < 0f) return QuadProblem.CROSSED
        if (q.area() < minAreaFraction * w * h) return QuadProblem.TOO_SMALL
        return null
    }

    /** Shoelace area with sign: positive for the TL, TR, BR, BL (clockwise on screen, y down) order. */
    fun signedArea(q: Quad): Float {
        val p = q.points
        var s = 0f
        for (i in 0 until 4) {
            val a = p[i]
            val b = p[(i + 1) % 4]
            s += a.x * b.y - b.x * a.y
        }
        return s / 2f
    }

    /** Re-orders the corners of a crossed outline; null if that cannot make it valid. */
    fun fix(q: Quad, w: Float, h: Float): Quad? {
        val ordered = Quad.order(q.points)
        return if (validate(ordered, w, h) == null) ordered else null
    }

    // ------------------------------------------------- rotation of the view

    /** Normalised source point -> normalised point in the view rotated clockwise. */
    fun toView(p: Pt, rotation: Int): Pt = when (((rotation % 360) + 360) % 360) {
        90 -> Pt(1f - p.y, p.x)
        180 -> Pt(1f - p.x, 1f - p.y)
        270 -> Pt(p.y, 1f - p.x)
        else -> p
    }

    /** Inverse of [toView]. */
    fun fromView(p: Pt, rotation: Int): Pt = toView(p, 360 - (((rotation % 360) + 360) % 360))

    /** Image size as seen after a clockwise rotation. */
    fun viewSize(w: Float, h: Float, rotation: Int): Pair<Float, Float> =
        if ((((rotation % 360) + 360) % 360) % 180 == 90) h to w else w to h

    /** True when every corner of [a] is within [tol] of [b] (same coordinate space). */
    fun near(a: Quad, b: Quad, tol: Float): Boolean = a.maxCornerShift(b) <= tol
}

/**
 * EXIF orientation (TIFF tag 274, values 1-8) as "rotate clockwise, then mirror
 * horizontally" — the same convention as androidx ExifInterface
 * (getRotationDegrees / isFlipped).
 */
object ExifOrientation {
    data class Transform(val rotation: Int, val flipHorizontal: Boolean)

    fun transform(orientation: Int): Transform = when (orientation) {
        2 -> Transform(0, true)    // FLIP_HORIZONTAL
        3 -> Transform(180, false) // ROTATE_180
        4 -> Transform(180, true)  // FLIP_VERTICAL
        5 -> Transform(90, true)   // TRANSPOSE
        6 -> Transform(90, false)  // ROTATE_90
        7 -> Transform(270, true)  // TRANSVERSE
        8 -> Transform(270, false) // ROTATE_270
        else -> Transform(0, false)
    }

    /** Where a normalised point of the stored (raw) image appears once displayed upright. */
    fun mapPoint(p: Pt, t: Transform): Pt {
        val r = CropGeometry.toView(p, t.rotation)
        return if (t.flipHorizontal) Pt(1f - r.x, r.y) else r
    }
}
