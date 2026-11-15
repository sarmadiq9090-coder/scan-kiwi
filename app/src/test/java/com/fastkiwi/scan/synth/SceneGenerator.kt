// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.synth

import com.fastkiwi.scan.core.Homography
import com.fastkiwi.scan.core.Pt
import com.fastkiwi.scan.core.Quad
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Synthetic camera scenes for the 2.0 camera benchmarks (ROADMAP 1.3.0 acceptance).
 * Everything is generated from fixed seeds in test code; no image is ever committed.
 *
 * A scene is a page (white or tinted paper with text-like lines) placed on one of six
 * backgrounds under one of three light levels, seen at one of four tilts through a
 * pinhole camera, rendered at the analysis resolution with 2x2 supersampling.
 */
object SceneGenerator {

    enum class Background { WHITE_TABLE, LIGHT_WOOD, DARK_WOOD, CLOTH, CLUTTERED_DESK, GREY_CARPET }
    enum class Light { NORMAL, DIM, SIDE_LIT }

    val TILTS = intArrayOf(0, 10, 20, 30)

    class Scene(
        val label: String,
        val w: Int,
        val h: Int,
        /** Packed opaque ARGB, as from Bitmap.getPixels. */
        val px: IntArray,
        /** Ground-truth corners in pixels, or null for an empty scene. */
        val quad: Quad?,
    )

    /** The 72-scene page set: 6 backgrounds x 3 light levels x 4 tilts. */
    fun pageSet(w: Int = 240, h: Int = 320): List<Scene> {
        val out = ArrayList<Scene>()
        var seed = 1000
        for (bg in Background.entries) for (light in Light.entries) for (tilt in TILTS) {
            out += page(bg, light, tilt, seed++, w, h)
        }
        return out
    }

    /** 20 scenes without a page: every background x light, plus two table-edge scenes. */
    fun emptySet(w: Int = 240, h: Int = 320): List<Scene> {
        val out = ArrayList<Scene>()
        var seed = 5000
        for (bg in Background.entries) for (light in Light.entries) {
            val rnd = java.util.Random(seed.toLong())
            val bgf = backgroundFn(bg, rnd, w, h)
            val lf = lightFn(light, rnd, w, h)
            out += render("empty-$bg-$light", w, h, null, rnd, bgf, null, lf, noiseOf(light), 0f)
            seed++
        }
        // A table edge across the frame: light table above, dark floor below.
        for (k in 0 until 2) {
            val rnd = java.util.Random((seed++).toLong())
            val table = backgroundFn(if (k == 0) Background.WHITE_TABLE else Background.LIGHT_WOOD, rnd, w, h)
            val floor = backgroundFn(Background.DARK_WOOD, rnd, w, h)
            val edgeY = h * (0.45f + 0.1f * k)
            val slope = 0.15f * (if (k == 0) 1 else -1)
            val f: (Float, Float) -> Int = { x, y -> if (y < edgeY + slope * (x - w / 2f)) table(x, y) else floor(x, y) }
            out += render("empty-table-edge-$k", w, h, null, rnd, f, null, lightFn(Light.NORMAL, rnd, w, h), 2.5f, 0f)
        }
        return out
    }

    /**
     * One page scene. [tiltDeg] tilts the page plane away from the camera (perspective);
     * the in-plane angle, position and size come from [seed].
     */
    fun page(
        bg: Background,
        light: Light,
        tiltDeg: Int,
        seed: Int,
        w: Int = 240,
        h: Int = 320,
        areaRange: ClosedFloatingPointRange<Float> = 0.30f..0.55f,
        margin: Float = 0.06f,
        allowCut: Boolean = false,
        cutShift: Pt = Pt(0f, 0f),
    ): Scene {
        val rnd = java.util.Random(seed.toLong())
        val quad = placePage(rnd, tiltDeg.toFloat(), w, h, areaRange, margin, cutShift)
        val paper = paperFn(rnd)
        val bgf = backgroundFn(bg, rnd, w, h)
        val lf = lightFn(light, rnd, w, h)
        val label = "page-$bg-$light-tilt$tiltDeg-s$seed"
        return render(label, w, h, quad, rnd, bgf, paper, lf, noiseOf(light), if (allowCut) 0f else 1f)
    }

    // ------------------------------------------------------------------ geometry

    /** Projects an A4 page through a pinhole camera and fits it into the frame. */
    fun placePage(
        rnd: java.util.Random,
        tiltDeg: Float,
        w: Int,
        h: Int,
        areaRange: ClosedFloatingPointRange<Float>,
        margin: Float,
        cutShift: Pt = Pt(0f, 0f),
    ): Quad {
        val roll = Math.toRadians((rnd.nextDouble() * 24 - 12)).toFloat()
        val tilt = Math.toRadians(tiltDeg.toDouble()).toFloat()
        val yaw = Math.toRadians((rnd.nextDouble() * 2 - 1) * tiltDeg * 0.3).toFloat()
        val local = listOf(Pt(-0.5f, -0.707f), Pt(0.5f, -0.707f), Pt(0.5f, 0.707f), Pt(-0.5f, 0.707f))
        val f = 1.6f
        val z0 = 2.2f
        val proj = local.map { p ->
            // In-plane rotation, then tilt about the x axis, then a little yaw about y.
            val x1 = p.x * cos(roll) - p.y * sin(roll)
            val y1 = p.x * sin(roll) + p.y * cos(roll)
            val y2 = y1 * cos(tilt)
            val z2 = y1 * sin(tilt)
            val x3 = x1 * cos(yaw) + z2 * sin(yaw)
            val z3 = -x1 * sin(yaw) + z2 * cos(yaw)
            val z = z0 + z3
            Pt(f * x3 / z, f * y2 / z)
        }
        // Scale so the page covers the wanted share of the frame.
        val q0 = Quad(proj[0], proj[1], proj[2], proj[3])
        val target = areaRange.start + rnd.nextFloat() * (areaRange.endInclusive - areaRange.start)
        val k = sqrt(target * w * h / q0.area())
        val scaled = proj.map { Pt(it.x * k, it.y * k) }
        val minX = scaled.minOf { it.x }; val maxX = scaled.maxOf { it.x }
        val minY = scaled.minOf { it.y }; val maxY = scaled.maxOf { it.y }
        val mx = margin * w; val my = margin * h
        val freeX = max(0f, (w - 2 * mx) - (maxX - minX))
        val freeY = max(0f, (h - 2 * my) - (maxY - minY))
        val ox = mx + rnd.nextFloat() * freeX - minX + cutShift.x * w
        val oy = my + rnd.nextFloat() * freeY - minY + cutShift.y * h
        return Quad.order(scaled.map { Pt(it.x + ox, it.y + oy) })
    }

    // ------------------------------------------------------------------ content

    /** Paper colour at page coordinates (u, v) in [0,1]: white or tinted, text-like lines. */
    fun paperFn(rnd: java.util.Random): (Float, Float) -> Int {
        val tinted = rnd.nextInt(4) == 0
        val base = if (tinted) intArrayOf(240, 233, 212) else intArrayOf(246, 245, 241)
        val lines = TextLayout.random(rnd)
        return { u, v ->
            if (lines.inkAt(u, v)) rgb(42, 42, 48) else rgb(base[0], base[1], base[2])
        }
    }

    /** Text-like layout: a heading and justified lines of "words" with gaps. */
    class TextLayout(private val rows: List<FloatArray>, private val lineH: Float) {
        fun inkAt(u: Float, v: Float): Boolean {
            for (r in rows) {
                // r = [top, x0, x1, x0, x1, ...]
                if (v < r[0] || v > r[0] + lineH) continue
                var i = 1
                while (i + 1 < r.size) {
                    if (u >= r[i] && u <= r[i + 1]) return true
                    i += 2
                }
            }
            return false
        }

        companion object {
            fun random(rnd: java.util.Random, left: Float = 0.1f, right: Float = 0.9f): TextLayout {
                val rows = ArrayList<FloatArray>()
                val lineH = 0.012f
                var v = 0.09f
                // Heading.
                rows += floatArrayOf(v, left + 0.15f, right - 0.15f); v += 0.05f
                while (v < 0.9f) {
                    if (rnd.nextInt(7) == 0) { v += 0.03f; continue } // paragraph gap
                    val words = ArrayList<Float>()
                    var u = left
                    val end = if (rnd.nextInt(6) == 0) left + (right - left) * (0.3f + 0.5f * rnd.nextFloat()) else right
                    while (u < end - 0.02f) {
                        val wl = 0.03f + rnd.nextFloat() * 0.08f
                        words += u; words += min(end, u + wl)
                        u += wl + 0.015f
                    }
                    val a = FloatArray(1 + words.size)
                    a[0] = v
                    for (i in words.indices) a[i + 1] = words[i]
                    rows += a
                    v += 0.03f
                }
                return TextLayout(rows, lineH)
            }
        }
    }

    // ------------------------------------------------------------------ backgrounds

    fun backgroundFn(bg: Background, rnd: java.util.Random, w: Int, h: Int): (Float, Float) -> Int {
        val p1 = rnd.nextFloat() * 6.28f
        val p2 = rnd.nextFloat() * 6.28f
        return when (bg) {
            // White-on-white: a table only ~14 levels darker than the paper.
            Background.WHITE_TABLE -> { x, y ->
                val v = 230 + 3 * sin(x / w * 3f + p1) + 3 * sin(y / h * 2f + p2)
                rgb(v.toInt(), v.toInt(), (v - 3).toInt())
            }
            Background.LIGHT_WOOD -> { x, y ->
                val g = 14 * sin((y + 6 * sin(x / 23f + p1)) / 3.2f) + 7 * sin(x / 41f + y / 9f + p2)
                rgb((205 + g).toInt(), (165 + g * 0.8f).toInt(), (115 + g * 0.6f).toInt())
            }
            Background.DARK_WOOD -> { x, y ->
                val g = 16 * sin((x + 5 * sin(y / 19f + p1)) / 2.7f) + 6 * sin(y / 31f + p2)
                rgb((110 + g).toInt(), (70 + g * 0.7f).toInt(), (40 + g * 0.5f).toInt())
            }
            Background.CLOTH -> {
                val col = listOf(intArrayOf(60, 90, 160), intArrayOf(150, 40, 50), intArrayOf(60, 120, 70))[rnd.nextInt(3)]
                return { x, y ->
                    val weave = if ((x.toInt() + y.toInt()) % 2 == 0) 9 else -9
                    val fold = 22 * sin(x / w * 5f + y / h * 3f + p1)
                    rgb((col[0] + weave + fold).toInt(), (col[1] + weave + fold).toInt(), (col[2] + weave + fold).toInt())
                }
            }
            Background.CLUTTERED_DESK -> {
                // Pens, a phone, sticky notes, a mug: objects smaller than a page.
                data class Obj(val cx: Float, val cy: Float, val a: Float, val b: Float, val ang: Float, val col: Int, val round: Boolean)
                val objs = List(7 + rnd.nextInt(4)) {
                    val pen = rnd.nextInt(3) == 0
                    Obj(
                        rnd.nextFloat() * w, rnd.nextFloat() * h,
                        if (pen) w * (0.18f + 0.12f * rnd.nextFloat()) else w * (0.05f + 0.1f * rnd.nextFloat()),
                        if (pen) w * 0.012f else h * (0.04f + 0.07f * rnd.nextFloat()),
                        rnd.nextFloat() * 3.14f,
                        rgb(rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256)),
                        rnd.nextInt(3) == 0,
                    )
                }
                return { x, y ->
                    var c = rgb(150, 145, 138)
                    for (o in objs) {
                        val dx = x - o.cx; val dy = y - o.cy
                        val rx = dx * cos(o.ang) + dy * sin(o.ang)
                        val ry = -dx * sin(o.ang) + dy * cos(o.ang)
                        val inside = if (o.round) (rx * rx) / (o.a * o.a) + (ry * ry) / (o.b * o.b) <= 1f else abs(rx) <= o.a && abs(ry) <= o.b
                        if (inside) c = o.col
                    }
                    c
                }
            }
            Background.GREY_CARPET -> {
                val seed = rnd.nextInt()
                return { x, y ->
                    val n = hashNoise(x.toInt(), y.toInt(), seed) * 30 + hashNoise(x.toInt() / 3, y.toInt() / 3, seed + 1) * 22
                    val v = (122 + n).toInt()
                    rgb(v, v, v + 3)
                }
            }
        }
    }

    /** Multiplicative light field and its noise level. */
    fun lightFn(light: Light, rnd: java.util.Random, w: Int, h: Int): (Float, Float) -> Float {
        return when (light) {
            Light.NORMAL -> { _, _ -> 1f }
            // Dim room: the camera's gain lifts the image but contrast stays low and noise high.
            Light.DIM -> { _, _ -> 0.42f }
            Light.SIDE_LIT -> {
                val ang = rnd.nextFloat() * 6.28f
                val dx = cos(ang); val dy = sin(ang)
                // A soft shadow band (a hand or the phone) across part of the scene.
                val bandPos = 0.3f + 0.4f * rnd.nextFloat()
                return { x, y ->
                    val t = ((x / w - 0.5f) * dx + (y / h - 0.5f) * dy + 0.5f).coerceIn(0f, 1f)
                    val grad = 1f - 0.5f * t
                    val s = ((x / w) * dy - (y / h) * dx + 0.5f) - bandPos
                    val band = 1f - 0.3f * exp(-(s * s) / 0.004f)
                    grad * band
                }
            }
        }
    }

    fun noiseOf(light: Light): Float = when (light) {
        Light.NORMAL -> 2.5f
        Light.DIM -> 7f
        Light.SIDE_LIT -> 3f
    }

    // ------------------------------------------------------------------ rendering

    private fun render(
        label: String,
        w: Int,
        h: Int,
        quad: Quad?,
        rnd: java.util.Random,
        bg: (Float, Float) -> Int,
        paper: ((Float, Float) -> Int)?,
        light: (Float, Float) -> Float,
        noise: Float,
        @Suppress("UNUSED_PARAMETER") unused: Float,
    ): Scene {
        val inv = quad?.let { Homography.squareToQuad(it)?.inverse() }
        val px = IntArray(w * h)
        // Soft contact shadow just outside the page edge (paper lying on a surface).
        for (y in 0 until h) for (x in 0 until w) {
            var r = 0; var g = 0; var b = 0
            for (sy in 0 until 2) for (sx in 0 until 2) {
                val fx = x + 0.25f + 0.5f * sx
                val fy = y + 0.25f + 0.5f * sy
                var c = bg(fx, fy)
                if (inv != null && paper != null) {
                    val uv = inv.map(fx, fy)
                    if (uv.x in 0f..1f && uv.y in 0f..1f) c = paper(uv.x, uv.y)
                }
                r += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF
            }
            val l = light(x + 0.5f, y + 0.5f)
            val n = (rnd.nextGaussian() * noise).toFloat()
            px[y * w + x] = rgb((r / 4f * l + n).roundToInt(), (g / 4f * l + n).roundToInt(), (b / 4f * l + n).roundToInt())
        }
        return Scene(label, w, h, px, quad)
    }

    fun rgb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    private fun hashNoise(x: Int, y: Int, seed: Int): Float {
        var hsh = x * 374761393 + y * 668265263 + seed * 1442695041
        hsh = (hsh xor (hsh ushr 13)) * 1274126177
        hsh = hsh xor (hsh ushr 16)
        return ((hsh and 0xFFFF) / 65535f) - 0.5f
    }

    // ------------------------------------------------------------------ helpers for other sets

    /** Separable Gaussian blur of packed ARGB (sigma in pixels; 0 = unchanged). */
    fun blur(px: IntArray, w: Int, h: Int, sigma: Float): IntArray {
        if (sigma <= 0.05f) return px.copyOf()
        val r = max(1, (sigma * 3).roundToInt())
        val k = FloatArray(2 * r + 1) { exp(-((it - r) * (it - r)) / (2 * sigma * sigma)) }
        val s = k.sum(); for (i in k.indices) k[i] /= s
        fun pass(src: Array<FloatArray>, horiz: Boolean): Array<FloatArray> {
            val out = Array(3) { FloatArray(w * h) }
            for (c in 0 until 3) for (y in 0 until h) for (x in 0 until w) {
                var acc = 0f
                for (i in -r..r) {
                    val xx = if (horiz) (x + i).coerceIn(0, w - 1) else x
                    val yy = if (horiz) y else (y + i).coerceIn(0, h - 1)
                    acc += k[i + r] * src[c][yy * w + xx]
                }
                out[c][y * w + x] = acc
            }
            return out
        }
        val ch = Array(3) { c -> FloatArray(w * h) { ((px[it] shr (16 - 8 * c)) and 0xFF).toFloat() } }
        val o = pass(pass(ch, true), false)
        return IntArray(w * h) { rgb(o[0][it].roundToInt(), o[1][it].roundToInt(), o[2][it].roundToInt()) }
    }

    fun inside(q: Quad, x: Float, y: Float): Boolean {
        val p = q.points
        var sign = 0
        for (i in 0 until 4) {
            val a = p[i]; val b = p[(i + 1) % 4]
            val s = if ((b.x - a.x) * (y - a.y) - (b.y - a.y) * (x - a.x) >= 0) 1 else -1
            if (sign == 0) sign = s else if (s != sign) return false
        }
        return true
    }

    /** Largest diagonal of a quad (the "page diagonal" of the acceptance criterion). */
    fun diagonal(q: Quad): Float = max(q.tl.dist(q.br), q.tr.dist(q.bl))
}
