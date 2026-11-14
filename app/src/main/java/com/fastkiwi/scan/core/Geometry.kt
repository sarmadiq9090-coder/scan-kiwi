// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.core

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** A 2-D point. Pure Kotlin so the geometry can be unit-tested on the JVM. */
data class Pt(val x: Float, val y: Float) {
    operator fun minus(o: Pt) = Pt(x - o.x, y - o.y)
    operator fun plus(o: Pt) = Pt(x + o.x, y + o.y)
    fun dist(o: Pt): Float = hypot(x - o.x, y - o.y)
}

/**
 * A document outline: four corners in clockwise order (screen coordinates,
 * y grows downwards) starting at the top-left corner.
 */
data class Quad(val tl: Pt, val tr: Pt, val br: Pt, val bl: Pt) {

    val points: List<Pt> get() = listOf(tl, tr, br, bl)

    /** Shoelace area (always positive). */
    fun area(): Float {
        val p = points
        var s = 0f
        for (i in 0 until 4) {
            val a = p[i]
            val b = p[(i + 1) % 4]
            s += a.x * b.y - b.x * a.y
        }
        return abs(s) / 2f
    }

    fun isConvex(): Boolean {
        val p = points
        var sign = 0
        for (i in 0 until 4) {
            val a = p[i]
            val b = p[(i + 1) % 4]
            val c = p[(i + 2) % 4]
            val cross = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
            if (abs(cross) < 1e-6f) return false
            val s = if (cross > 0) 1 else -1
            if (sign == 0) sign = s else if (s != sign) return false
        }
        return true
    }

    /** Smallest and largest interior angle in degrees. */
    fun angleRange(): Pair<Float, Float> {
        val p = points
        var lo = 360f
        var hi = 0f
        for (i in 0 until 4) {
            val prev = p[(i + 3) % 4]
            val cur = p[i]
            val next = p[(i + 1) % 4]
            val v1 = prev - cur
            val v2 = next - cur
            val d = (v1.x * v2.x + v1.y * v2.y) / (hypot(v1.x, v1.y) * hypot(v2.x, v2.y)).coerceAtLeast(1e-6f)
            val ang = Math.toDegrees(acos(d.coerceIn(-1f, 1f).toDouble())).toFloat()
            lo = min(lo, ang)
            hi = max(hi, ang)
        }
        return lo to hi
    }

    fun map(f: (Pt) -> Pt) = Quad(f(tl), f(tr), f(br), f(bl))

    fun scale(sx: Float, sy: Float) = map { Pt(it.x * sx, it.y * sy) }

    /** Width/height of the rectified page, from the longest opposite edges. */
    fun outputSize(): Pair<Int, Int> {
        val w = max(tl.dist(tr), bl.dist(br))
        val h = max(tl.dist(bl), tr.dist(br))
        return w.toInt().coerceAtLeast(1) to h.toInt().coerceAtLeast(1)
    }

    fun toArray(): FloatArray = floatArrayOf(tl.x, tl.y, tr.x, tr.y, br.x, br.y, bl.x, bl.y)

    fun maxCornerShift(o: Quad): Float = points.zip(o.points).maxOf { (a, b) -> a.dist(b) }

    companion object {
        fun full(w: Float, h: Float) = Quad(Pt(0f, 0f), Pt(w, 0f), Pt(w, h), Pt(0f, h))

        fun fromArray(a: FloatArray) = Quad(Pt(a[0], a[1]), Pt(a[2], a[3]), Pt(a[4], a[5]), Pt(a[6], a[7]))

        /** Orders 4 arbitrary points clockwise starting from the top-left one. */
        fun order(pts: List<Pt>): Quad {
            require(pts.size == 4)
            val cx = pts.sumOf { it.x.toDouble() }.toFloat() / 4f
            val cy = pts.sumOf { it.y.toDouble() }.toFloat() / 4f
            val sorted = pts.sortedBy { atan2((it.y - cy).toDouble(), (it.x - cx).toDouble()) }
            // atan2 ascending == clockwise on screen (y down). Rotate so TL (min x+y) is first.
            val start = sorted.indices.minBy { sorted[it].x + sorted[it].y }
            val r = List(4) { sorted[(start + it) % 4] }
            return Quad(r[0], r[1], r[2], r[3])
        }
    }
}

/**
 * Rotates a quad expressed in normalised [0,1] coordinates of a frame by a
 * clockwise multiple of 90 degrees, so it matches the upright frame.
 */
fun Quad.rotateNormalized(degreesCw: Int): Quad {
    val f: (Pt) -> Pt = when (((degreesCw % 360) + 360) % 360) {
        90 -> { p -> Pt(1f - p.y, p.x) }
        180 -> { p -> Pt(1f - p.x, 1f - p.y) }
        270 -> { p -> Pt(p.y, 1f - p.x) }
        else -> { p -> p }
    }
    return Quad.order(points.map(f))
}
