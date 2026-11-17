// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.ocr.fused

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * A rotated rectangle in page pixels (edge coordinates: the page spans 0..W x 0..H).
 * [angle] (radians) is the direction of the box's width.
 */
data class TextBox(val cx: Double, val cy: Double, val w: Double, val h: Double, val angle: Double, val score: Double) {
    fun corners(): List<Pair<Double, Double>> {
        val ux = cos(angle); val uy = sin(angle)
        val vx = -uy; val vy = ux
        val hw = w / 2; val hh = h / 2
        return listOf(
            (cx - hw * ux - hh * vx) to (cy - hw * uy - hh * vy),
            (cx + hw * ux - hh * vx) to (cy + hw * uy - hh * vy),
            (cx + hw * ux + hh * vx) to (cy + hw * uy + hh * vy),
            (cx - hw * ux + hh * vx) to (cy - hw * uy + hh * vy),
        )
    }

    /** Axis-aligned bounds clipped to the page: (x0, y0, x1, y1). */
    fun bounds(pageW: Int, pageH: Int): DoubleArray {
        val c = corners()
        return doubleArrayOf(
            max(0.0, c.minOf { it.first }), max(0.0, c.minOf { it.second }),
            min(pageW.toDouble(), c.maxOf { it.first }), min(pageH.toDouble(), c.maxOf { it.second }),
        )
    }
}

/**
 * PP-OCRv6 tiny text detector (PaddlePaddle, Apache-2.0) on MNN. Pre-processing (gray → 3 x H x W,
 * long side ≤ [MAX_SIDE], multiple of 32) and the DB post-processing are plain Kotlin, the same steps as
 * tools/ocr-bench/kiwi_pipeline.py, so the desktop benchmark measures exactly this code.
 */
class PpDetector(private val runner: ModelRunner) : AutoCloseable {

    fun detect(gray: ByteArray, w: Int, h: Int): List<TextBox> {
        val (rw, rh) = size(w, h)
        val input = input(gray, w, h, rw, rh)
        val out = runner.run(input, intArrayOf(1, 3, rh, rw))
        require(out.values.size >= rw * rh) { "detector output ${out.shape.contentToString()}" }
        return boxes(out.values, rw, rh, w, h)
    }

    override fun close() = runner.close()

    companion object {
        const val MAX_SIDE = 960
        const val THRESH = 0.3f
        const val BOX_THRESH = 0.5
        const val UNCLIP_RATIO = 1.6
        const val MIN_SIZE = 3.0
        const val MAX_CANDIDATES = 1000
        const val MAX_TILT_DEG = 10.0

        private fun roundHalfUp(x: Double) = floor(x + 0.5).toInt()

        /** Detector input size: long side ≤ 960 (never upscaled), both sides multiples of 32. */
        fun size(w: Int, h: Int): Pair<Int, Int> {
            val s = min(1.0, MAX_SIDE.toDouble() / max(w, h))
            return max(32, roundHalfUp(w * s / 32) * 32) to max(32, roundHalfUp(h * s / 32) * 32)
        }

        /** NCHW float input: bilinear resize, (v / 255 - 0.5) / 0.5, the gray plane in all 3 channels. */
        fun input(gray: ByteArray, w: Int, h: Int, rw: Int, rh: Int): FloatArray {
            val plane = Resample.bilinear(gray, w, h, rw, rh)
            val n = rw * rh
            val out = FloatArray(3 * n)
            for (i in 0 until n) {
                val v = (plane[i] / 255f - 0.5f) / 0.5f
                out[i] = v; out[n + i] = v; out[2 * n + i] = v
            }
            return out
        }

        /** DB post-processing of the probability map [prob] (rh x rw) into boxes in page pixels. */
        fun boxes(prob: FloatArray, rw: Int, rh: Int, pageW: Int, pageH: Int): List<TextBox> {
            // threshold, then 2x2 dilation (each pixel ORs itself, its left, upper and upper-left neighbours)
            val b = BooleanArray(rw * rh) { prob[it] > THRESH }
            val d = BooleanArray(rw * rh)
            for (y in 0 until rh) for (x in 0 until rw) {
                val i = y * rw + x
                d[i] = b[i] || (x > 0 && b[i - 1]) || (y > 0 && b[i - rw]) || (x > 0 && y > 0 && b[i - rw - 1])
            }
            val out = ArrayList<TextBox>()
            val fx = pageW.toDouble() / rw
            val fy = pageH.toDouble() / rh
            var k = 0
            for (comp in Components.label(d, rw, rh)) {
                if (k++ >= MAX_CANDIDATES) break
                boxOf(comp, prob, rw, rh, fx, fy)?.let { out.add(it) }
            }
            return out
        }

        private fun boxOf(comp: IntArray, prob: FloatArray, rw: Int, rh: Int, fx: Double, fy: Double): TextBox? {
            val n = comp.size
            var sx = 0.0; var sy = 0.0
            for (p in comp) { sx += p % rw; sy += p / rw }
            val mx = sx / n; val my = sy / n
            var cxx = 0.0; var cyy = 0.0; var cxy = 0.0
            for (p in comp) {
                val dx = p % rw - mx; val dy = p / rw - my
                cxx += dx * dx; cyy += dy * dy; cxy += dx * dy
            }
            cxx /= n; cyy /= n; cxy /= n
            var theta = 0.5 * atan2(2 * cxy, cxx - cyy)
            if (abs(theta) > Math.toRadians(MAX_TILT_DEG)) theta = 0.0
            val ux = cos(theta); val uy = sin(theta)
            var umin = Double.MAX_VALUE; var umax = -Double.MAX_VALUE
            var vmin = Double.MAX_VALUE; var vmax = -Double.MAX_VALUE
            for (p in comp) {
                val dx = p % rw - mx; val dy = p / rw - my
                val pu = dx * ux + dy * uy
                val pv = -dx * uy + dy * ux
                if (pu < umin) umin = pu; if (pu > umax) umax = pu
                if (pv < vmin) vmin = pv; if (pv > vmax) vmax = pv
            }
            val bw = umax - umin + 1.0
            val bh = vmax - vmin + 1.0
            if (min(bw, bh) < MIN_SIZE) return null
            val cu = (umin + umax) / 2; val cv = (vmin + vmax) / 2
            val ccx = mx + cu * ux - cv * uy
            val ccy = my + cu * uy + cv * ux
            // score: mean probability over the pixels whose centres lie inside the rectangle
            val r = 0.5 * hypot(bw, bh)
            val x0 = max(0, floor(ccx - r).toInt()); val x1 = min(rw - 1, ceil(ccx + r).toInt())
            val y0 = max(0, floor(ccy - r).toInt()); val y1 = min(rh - 1, ceil(ccy + r).toInt())
            var sum = 0.0; var cnt = 0
            for (y in y0..y1) for (x in x0..x1) {
                val qx = x - ccx; val qy = y - ccy
                if (abs(qx * ux + qy * uy) <= bw / 2 && abs(-qx * uy + qy * ux) <= bh / 2) {
                    sum += prob[y * rw + x]; cnt++
                }
            }
            if (cnt == 0) return null
            val score = sum / cnt
            if (score < BOX_THRESH) return null
            val dist = bw * bh * UNCLIP_RATIO / (2 * (bw + bh))
            val ew = bw + 2 * dist; val eh = bh + 2 * dist
            if (min(ew, eh) < MIN_SIZE + 2) return null
            val pw = ew * hypot(ux * fx, uy * fy)
            val ph = eh * hypot(-uy * fx, ux * fy)
            if (pw <= 3 || ph <= 3) return null
            return TextBox((ccx + 0.5) * fx, (ccy + 0.5) * fy, pw, ph, atan2(uy * fy, ux * fx), score)
        }
    }
}

/** 8-connected components of a binary mask, each as pixel indices, ordered by their first pixel. */
internal object Components {
    fun label(mask: BooleanArray, w: Int, h: Int): List<IntArray> {
        val seen = BooleanArray(mask.size)
        val out = ArrayList<IntArray>()
        val queue = IntArray(mask.size)
        for (start in mask.indices) {
            if (!mask[start] || seen[start]) continue
            var head = 0; var tail = 0
            queue[tail++] = start; seen[start] = true
            while (head < tail) {
                val p = queue[head++]
                val x = p % w; val y = p / w
                for (dy in -1..1) {
                    val yy = y + dy
                    if (yy < 0 || yy >= h) continue
                    for (dx in -1..1) {
                        val xx = x + dx
                        if (xx < 0 || xx >= w || (dx == 0 && dy == 0)) continue
                        val q = yy * w + xx
                        if (mask[q] && !seen[q]) { seen[q] = true; queue[tail++] = q }
                    }
                }
            }
            out.add(queue.copyOf(tail))
        }
        return out
    }
}

/** Half-pixel-centre bilinear resampling, float arithmetic in the same order as kiwi_pipeline.py. */
object Resample {
    fun bilinear(gray: ByteArray, w: Int, h: Int, rw: Int, rh: Int): FloatArray {
        val sx = (w.toDouble() / rw).toFloat()
        val sy = (h.toDouble() / rh).toFloat()
        val xs = FloatArray(rw) { ((it + 0.5f) * sx - 0.5f).coerceIn(0f, (w - 1).toFloat()) }
        val ys = FloatArray(rh) { ((it + 0.5f) * sy - 0.5f).coerceIn(0f, (h - 1).toFloat()) }
        val out = FloatArray(rw * rh)
        for (j in 0 until rh) {
            val fy = ys[j]; val y0 = fy.toInt(); val y1 = min(h - 1, y0 + 1); val ay = fy - y0
            for (i in 0 until rw) {
                val fx = xs[i]; val x0 = fx.toInt(); val x1 = min(w - 1, x0 + 1); val ax = fx - x0
                val g00 = (gray[y0 * w + x0].toInt() and 0xFF).toFloat()
                val g01 = (gray[y0 * w + x1].toInt() and 0xFF).toFloat()
                val g10 = (gray[y1 * w + x0].toInt() and 0xFF).toFloat()
                val g11 = (gray[y1 * w + x1].toInt() and 0xFF).toFloat()
                val top = g00 * (1 - ax) + g01 * ax
                val bot = g10 * (1 - ax) + g11 * ax
                out[j * rw + i] = top * (1 - ay) + bot * ay
            }
        }
        return out
    }
}
