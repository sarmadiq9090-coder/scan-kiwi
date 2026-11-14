package com.fastkiwi.scan.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** 8-bit luminance image. */
class GrayImage(val width: Int, val height: Int, val data: ByteArray) {
    init {
        require(data.size >= width * height)
    }

    operator fun get(x: Int, y: Int): Int = data[y * width + x].toInt() and 0xFF
}

data class Detection(val quad: Quad, val score: Float)

/**
 * Finds the outline of a document (the largest convex quadrilateral whose
 * sides are backed by strong edges) in a luminance image.
 *
 * Our own pure-Kotlin implementation (no OpenCV), so the APK stays small:
 *  1. downscale to ~[workSize] px, Gaussian blur
 *  2. Canny edges (Sobel, non-maximum suppression, hysteresis)
 *  3. connected edge components -> convex hull -> best inscribed quad
 *  4. score = area x (fraction of each side lying on detected edges)
 */
class QuadDetector(private val workSize: Int = 320) {

    fun detect(img: GrayImage): Detection? {
        val scale = max(img.width, img.height).toFloat() / workSize
        val small = if (scale > 1.05f) downscale(img, scale) else img
        val s = if (scale > 1.05f) img.width.toFloat() / small.width else 1f
        val w = small.width
        val h = small.height
        if (w < 16 || h < 16) return null

        val blurred = gaussian5(small.data, w, h)
        val nms = nonMaxSuppressed(blurred, w, h)
        val (baseHigh, strong) = thresholds(nms)

        val minArea = 0.12f * w * h
        var best: Detection? = null
        // Several passes with rising thresholds: weak texture (wood, cloth) that
        // merges with the page outline at a low threshold drops out at a higher one.
        for (factor in floatArrayOf(1f, 1.7f, 2.8f)) {
            val high = (baseHigh * factor).toInt().coerceAtMost(max(60, (strong * 0.8f).toInt()))
            val edges = hysteresis(nms, w, h, high, high / 2)
            // Dilation closes small gaps in the outline and tolerates 1 px of error.
            val support = dilate(edges, w, h, 1)
            for (hull in componentHulls(support, w, h)) {
                val rough = bestQuadInHull(hull) ?: continue
                val quad = refine(rough, edges, w, h)
                val area = quad.area()
                if (area < minArea || area > 0.995f * w * h) continue
                if (!quad.isConvex()) continue
                val (lo, hi) = quad.angleRange()
                if (lo < 35f || hi > 145f) continue
                val sides = sideSupport(quad, support, w, h)
                val minSide = sides.min()
                val mean = sides.average().toFloat()
                if (minSide < 0.45f || mean < 0.62f) continue
                val score = (area / (w * h)) * mean * mean
                if (best == null || score > best.score) best = Detection(quad, score)
            }
            if (best != null && best.score > 0.25f) break
        }
        return best?.let { Detection(it.quad.scale(s, s), it.score) }
    }

    /**
     * Snaps each side to the edge pixels along it (total-least-squares line fit)
     * and recomputes the corners as intersections of neighbouring sides.
     */
    internal fun refine(q: Quad, edges: ByteArray, w: Int, h: Int): Quad {
        val p = q.points
        val lines = arrayOfNulls<FloatArray>(4) // (px, py, dx, dy)
        for (s in 0 until 4) {
            val a = p[s]
            val b = p[(s + 1) % 4]
            val dx = b.x - a.x
            val dy = b.y - a.y
            val len = sqrt(dx * dx + dy * dy)
            if (len < 8f) continue
            val ux = dx / len
            val uy = dy / len
            val x0 = max(0, (min(a.x, b.x) - 4).toInt()); val x1 = min(w - 1, (max(a.x, b.x) + 4).toInt())
            val y0 = max(0, (min(a.y, b.y) - 4).toInt()); val y1 = min(h - 1, (max(a.y, b.y) + 4).toInt())
            var n = 0
            var sx = 0.0; var sy = 0.0; var sxx = 0.0; var syy = 0.0; var sxy = 0.0
            for (y in y0..y1) for (x in x0..x1) {
                if (edges[y * w + x].toInt() == 0) continue
                val rx = x - a.x
                val ry = y - a.y
                val t = (rx * ux + ry * uy) / len
                if (t < 0.12f || t > 0.88f) continue
                if (abs(rx * uy - ry * ux) > 3f) continue
                n++
                sx += x; sy += y; sxx += x.toDouble() * x; syy += y.toDouble() * y; sxy += x.toDouble() * y
            }
            if (n < 12) continue
            val mx = sx / n; val my = sy / n
            val cxx = sxx / n - mx * mx
            val cyy = syy / n - my * my
            val cxy = sxy / n - mx * my
            // Principal direction of the 2x2 covariance matrix.
            val theta = 0.5 * kotlin.math.atan2(2 * cxy, cxx - cyy)
            lines[s] = floatArrayOf(mx.toFloat(), my.toFloat(), kotlin.math.cos(theta).toFloat(), kotlin.math.sin(theta).toFloat())
        }
        val diag = sqrt((w * w + h * h).toFloat())
        val out = p.toMutableList()
        for (c in 0 until 4) {
            val l1 = lines[(c + 3) % 4] ?: continue
            val l2 = lines[c] ?: continue
            val det = l1[2] * l2[3] - l1[3] * l2[2]
            if (abs(det) < 0.2f) continue // nearly parallel
            val t = ((l2[0] - l1[0]) * l2[3] - (l2[1] - l1[1]) * l2[2]) / det
            val ip = Pt(l1[0] + t * l1[2], l1[1] + t * l1[3])
            if (ip.dist(p[c]) < 0.05f * diag) out[c] = Pt(ip.x.coerceIn(0f, w - 1f), ip.y.coerceIn(0f, h - 1f))
        }
        return Quad(out[0], out[1], out[2], out[3])
    }

    // ---------------------------------------------------------------- stages

    private fun downscale(img: GrayImage, scale: Float): GrayImage {
        val w = max(1, (img.width / scale).roundToInt())
        val h = max(1, (img.height / scale).roundToInt())
        val out = ByteArray(w * h)
        val fx = img.width.toFloat() / w
        val fy = img.height.toFloat() / h
        for (y in 0 until h) {
            val y0 = (y * fy).toInt()
            val y1 = min(img.height, max(y0 + 1, ((y + 1) * fy).toInt()))
            for (x in 0 until w) {
                val x0 = (x * fx).toInt()
                val x1 = min(img.width, max(x0 + 1, ((x + 1) * fx).toInt()))
                var sum = 0
                var n = 0
                // Sparse box average (at most 3x3 samples) keeps this fast.
                val sx = max(1, (x1 - x0) / 3)
                val sy = max(1, (y1 - y0) / 3)
                var yy = y0
                while (yy < y1) {
                    var xx = x0
                    while (xx < x1) {
                        sum += img[xx, yy]; n++
                        xx += sx
                    }
                    yy += sy
                }
                out[y * w + x] = (sum / n).toByte()
            }
        }
        return GrayImage(w, h, out)
    }

    private fun gaussian5(src: ByteArray, w: Int, h: Int): IntArray {
        val k = intArrayOf(1, 4, 6, 4, 1)
        val tmp = IntArray(w * h)
        val out = IntArray(w * h)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                var acc = 0
                for (i in -2..2) {
                    val xx = (x + i).coerceIn(0, w - 1)
                    acc += k[i + 2] * (src[row + xx].toInt() and 0xFF)
                }
                tmp[row + x] = acc
            }
        }
        for (y in 0 until h) {
            for (x in 0 until w) {
                var acc = 0
                for (i in -2..2) {
                    val yy = (y + i).coerceIn(0, h - 1)
                    acc += k[i + 2] * tmp[yy * w + x]
                }
                out[y * w + x] = acc / 256
            }
        }
        return out
    }

    /** Sobel gradient magnitude after non-maximum suppression (thin edges). */
    private fun nonMaxSuppressed(g: IntArray, w: Int, h: Int): IntArray {
        val mag = IntArray(w * h)
        val dir = ByteArray(w * h)
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val i = y * w + x
                val gx = -g[i - w - 1] - 2 * g[i - 1] - g[i + w - 1] + g[i - w + 1] + 2 * g[i + 1] + g[i + w + 1]
                val gy = -g[i - w - 1] - 2 * g[i - w] - g[i - w + 1] + g[i + w - 1] + 2 * g[i + w] + g[i + w + 1]
                mag[i] = abs(gx) + abs(gy)
                val ax = abs(gx)
                val ay = abs(gy)
                // 0: horizontal gradient, 1: 45deg, 2: vertical, 3: 135deg
                dir[i] = when {
                    ay * 5 < ax * 2 -> 0
                    ax * 5 < ay * 2 -> 2
                    (gx > 0) == (gy > 0) -> 1
                    else -> 3
                }.toByte()
            }
        }
        val nms = IntArray(w * h)
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val i = y * w + x
                val m = mag[i]
                if (m == 0) continue
                val (a, b) = when (dir[i].toInt()) {
                    0 -> mag[i - 1] to mag[i + 1]
                    2 -> mag[i - w] to mag[i + w]
                    1 -> mag[i - w - 1] to mag[i + w + 1]
                    else -> mag[i - w + 1] to mag[i + w - 1]
                }
                if (m >= a && m > b) nms[i] = m
            }
        }
        return nms
    }

    /**
     * Adaptive thresholds from the distribution of thin-edge strengths:
     * returns (high threshold, strength of the strongest 1% of edges).
     */
    private fun thresholds(nms: IntArray): Pair<Int, Int> {
        val hist = IntArray(1024)
        var count = 0
        for (m in nms) if (m > 0) { hist[min(1023, m / 2)]++; count++ }
        if (count == 0) return 80 to 80
        fun pct(p: Double): Int {
            var acc = 0
            val target = (count * p).toInt()
            for (b in hist.indices) {
                acc += hist[b]
                if (acc >= target) return b * 2
            }
            return 2046
        }
        val p80 = pct(0.80)
        val p99 = pct(0.99)
        // Ignore edges much weaker than the strongest ones (texture, noise).
        val high = max(p80, (p99 * 0.3f).toInt()).coerceIn(60, 400)
        return high to p99
    }

    private fun hysteresis(nms: IntArray, w: Int, h: Int, high: Int, low: Int): ByteArray {
        val out = ByteArray(w * h)
        val stack = IntArray(w * h)
        var sp = 0
        for (i in nms.indices) {
            if (nms[i] >= high && out[i].toInt() == 0) {
                out[i] = 1
                stack[sp++] = i
                while (sp > 0) {
                    val p = stack[--sp]
                    val px = p % w
                    val py = p / w
                    for (dy in -1..1) for (dx in -1..1) {
                        val nx = px + dx
                        val ny = py + dy
                        if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                        val q = ny * w + nx
                        if (out[q].toInt() == 0 && nms[q] >= low) {
                            out[q] = 1
                            stack[sp++] = q
                        }
                    }
                }
            }
        }
        return out
    }

    private fun dilate(src: ByteArray, w: Int, h: Int, r: Int): ByteArray {
        val tmp = ByteArray(w * h)
        val out = ByteArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var v = 0
            for (d in -r..r) {
                val xx = x + d
                if (xx in 0 until w && src[y * w + xx].toInt() != 0) { v = 1; break }
            }
            tmp[y * w + x] = v.toByte()
        }
        for (y in 0 until h) for (x in 0 until w) {
            var v = 0
            for (d in -r..r) {
                val yy = y + d
                if (yy in 0 until h && tmp[yy * w + x].toInt() != 0) { v = 1; break }
            }
            out[y * w + x] = v.toByte()
        }
        return out
    }

    /** Convex hulls of connected edge components that are big enough to be a page. */
    private fun componentHulls(e: ByteArray, w: Int, h: Int): List<List<Pt>> {
        val label = IntArray(w * h)
        val stack = IntArray(w * h)
        val minExtent = 0.25f * min(w, h)
        val hulls = ArrayList<List<Pt>>()
        var next = 1
        val rowMin = IntArray(h)
        val rowMax = IntArray(h)
        for (start in e.indices) {
            if (e[start].toInt() == 0 || label[start] != 0) continue
            val id = next++
            rowMin.fill(Int.MAX_VALUE)
            rowMax.fill(Int.MIN_VALUE)
            var minX = w; var maxX = 0; var minY = h; var maxY = 0
            var sp = 0
            stack[sp++] = start
            label[start] = id
            while (sp > 0) {
                val p = stack[--sp]
                val px = p % w
                val py = p / w
                if (px < rowMin[py]) rowMin[py] = px
                if (px > rowMax[py]) rowMax[py] = px
                if (px < minX) minX = px
                if (px > maxX) maxX = px
                if (py < minY) minY = py
                if (py > maxY) maxY = py
                for (dy in -1..1) for (dx in -1..1) {
                    val nx = px + dx
                    val ny = py + dy
                    if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                    val q = ny * w + nx
                    if (e[q].toInt() != 0 && label[q] == 0) {
                        label[q] = id
                        stack[sp++] = q
                    }
                }
            }
            if (maxX - minX < minExtent || maxY - minY < minExtent) continue
            // Hull of the per-row extremes equals the hull of the whole component.
            val pts = ArrayList<Pt>()
            for (y in minY..maxY) {
                if (rowMin[y] == Int.MAX_VALUE) continue
                pts.add(Pt(rowMin[y].toFloat(), y.toFloat()))
                if (rowMax[y] != rowMin[y]) pts.add(Pt(rowMax[y].toFloat(), y.toFloat()))
            }
            val hull = convexHull(pts)
            if (hull.size >= 4) hulls.add(hull)
        }
        return hulls
    }

    /** Fraction of sample points along each side that lie on an edge pixel. */
    private fun sideSupport(q: Quad, e: ByteArray, w: Int, h: Int): FloatArray {
        val p = q.points
        val res = FloatArray(4)
        for (s in 0 until 4) {
            val a = p[s]
            val b = p[(s + 1) % 4]
            val n = 48
            var hit = 0
            var total = 0
            // Skip the ends: corners are often rounded or occluded by fingers.
            for (i in 3 until n - 3) {
                val t = i / (n - 1f)
                val x = (a.x + (b.x - a.x) * t).roundToInt()
                val y = (a.y + (b.y - a.y) * t).roundToInt()
                if (x < 0 || y < 0 || x >= w || y >= h) { total++; continue }
                total++
                var found = false
                loop@ for (dy in -1..1) for (dx in -1..1) {
                    val xx = x + dx
                    val yy = y + dy
                    if (xx in 0 until w && yy in 0 until h && e[yy * w + xx].toInt() != 0) { found = true; break@loop }
                }
                if (found) hit++
            }
            res[s] = if (total == 0) 0f else hit.toFloat() / total
        }
        return res
    }

    companion object {
        /** Andrew's monotone chain; returns hull clockwise on screen (y down). */
        fun convexHull(input: List<Pt>): List<Pt> {
            val pts = input.distinct().sortedWith(compareBy({ it.x }, { it.y }))
            if (pts.size < 3) return pts
            fun cross(o: Pt, a: Pt, b: Pt) = (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)
            val lower = ArrayList<Pt>()
            for (p in pts) {
                while (lower.size >= 2 && cross(lower[lower.size - 2], lower[lower.size - 1], p) <= 0) lower.removeAt(lower.size - 1)
                lower.add(p)
            }
            val upper = ArrayList<Pt>()
            for (p in pts.asReversed()) {
                while (upper.size >= 2 && cross(upper[upper.size - 2], upper[upper.size - 1], p) <= 0) upper.removeAt(upper.size - 1)
                upper.add(p)
            }
            lower.removeAt(lower.size - 1)
            upper.removeAt(upper.size - 1)
            return lower + upper
        }

        /** Largest-area quadrilateral with vertices on the hull. */
        fun bestQuadInHull(hull: List<Pt>): Quad? {
            var pts = simplify(hull)
            if (pts.size < 4) return null
            if (pts.size > 28) {
                val step = pts.size / 28f
                pts = List(28) { pts[(it * step).toInt()] }
            }
            val n = pts.size
            var bestArea = -1f
            var best: IntArray? = null
            for (a in 0 until n) for (b in a + 1 until n) for (c in b + 1 until n) for (d in c + 1 until n) {
                val ar = quadArea(pts[a], pts[b], pts[c], pts[d])
                if (ar > bestArea) {
                    bestArea = ar
                    best = intArrayOf(a, b, c, d)
                }
            }
            val idx = best ?: return null
            return Quad.order(idx.map { pts[it] })
        }

        private fun quadArea(a: Pt, b: Pt, c: Pt, d: Pt): Float {
            val s = (a.x * b.y - b.x * a.y) + (b.x * c.y - c.x * b.y) + (c.x * d.y - d.x * c.y) + (d.x * a.y - a.x * d.y)
            return abs(s) / 2f
        }

        /** Douglas-Peucker on a closed polygon (epsilon = 1.5% of perimeter). */
        private fun simplify(poly: List<Pt>): List<Pt> {
            if (poly.size <= 8) return poly
            var per = 0f
            for (i in poly.indices) per += poly[i].dist(poly[(i + 1) % poly.size])
            val eps = per * 0.015f
            // Split at the two farthest-apart points to handle the closed ring.
            var i0 = 0
            var i1 = 0
            var far = -1f
            for (i in poly.indices) {
                val d = poly[0].dist(poly[i])
                if (d > far) { far = d; i1 = i }
            }
            val first = poly.subList(i0, i1 + 1)
            val second = poly.subList(i1, poly.size) + poly[0]
            val r1 = dp(first, eps)
            val r2 = dp(second, eps)
            return r1.dropLast(1) + r2.dropLast(1)
        }

        private fun dp(pts: List<Pt>, eps: Float): List<Pt> {
            if (pts.size < 3) return pts
            val a = pts.first()
            val b = pts.last()
            var idx = -1
            var maxD = 0f
            val dx = b.x - a.x
            val dy = b.y - a.y
            val len = sqrt(dx * dx + dy * dy).coerceAtLeast(1e-6f)
            for (i in 1 until pts.size - 1) {
                val p = pts[i]
                val d = abs(dy * p.x - dx * p.y + b.x * a.y - b.y * a.x) / len
                if (d > maxD) { maxD = d; idx = i }
            }
            return if (maxD > eps && idx > 0) {
                dp(pts.subList(0, idx + 1), eps).dropLast(1) + dp(pts.subList(idx, pts.size), eps)
            } else listOf(a, b)
        }
    }
}
