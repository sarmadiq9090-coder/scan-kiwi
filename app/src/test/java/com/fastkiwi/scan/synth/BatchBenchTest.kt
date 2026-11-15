// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.synth

import com.fastkiwi.scan.core.AutoCapture
import com.fastkiwi.scan.core.DetectionConfig
import com.fastkiwi.scan.core.ImageFilters
import com.fastkiwi.scan.core.Pt
import com.fastkiwi.scan.core.QualityMetrics
import com.fastkiwi.scan.core.Quad
import com.fastkiwi.scan.core.QuadDetector
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2.0-cam2, CAM-06 better batch detection. Auto-capture sessions on the synthetic scene generator,
 * 10 frames per second at the 240 x 320 analysis size, the camera's own pipeline per frame
 * (detector, quality gates, [AutoCapture], 800 ms of processing after each shot):
 * pages changed by hand (covered, slid, or laid on top of the last one at the same spot),
 * hand jitter while holding, and the same page held twice (phone moved, page lifted and put back).
 * Counts correct captures, duplicates, misses and shots without a page, for the 2.0 camera-agent
 * rule (outline only) and the content-aware re-arm.
 */
class BatchBenchTest {

    private val w = 240
    private val h = 320

    /** How a segment begins: the page changes (first three) or the same page is shown again (last two). */
    enum class Move { COVER, SLIDE, ON_TOP, PHONE_MOVED, LIFTED_BACK }

    private data class Frame(val scene: SceneGenerator.Scene, val page: Int?, val hold: Boolean)

    data class Score(val correct: Int, val duplicates: Int, val missed: Int, val noPage: Int, val expected: Int)

    private val moves = listOf(Move.COVER, Move.ON_TOP, Move.PHONE_MOVED, Move.SLIDE, Move.LIFTED_BACK, Move.ON_TOP, Move.COVER, Move.PHONE_MOVED, Move.ON_TOP)

    private val sessions = listOf(
        SceneGenerator.Background.WHITE_TABLE to SceneGenerator.Light.NORMAL,
        SceneGenerator.Background.LIGHT_WOOD to SceneGenerator.Light.NORMAL,
        SceneGenerator.Background.DARK_WOOD to SceneGenerator.Light.DIM,
        SceneGenerator.Background.CLOTH to SceneGenerator.Light.SIDE_LIT,
        SceneGenerator.Background.CLUTTERED_DESK to SceneGenerator.Light.NORMAL,
        SceneGenerator.Background.GREY_CARPET to SceneGenerator.Light.DIM,
    )

    private fun shift(q: Quad, dx: Float, dy: Float) = q.map { Pt(it.x + dx, it.y + dy) }

    private fun inside(q: Quad) = q.points.all { it.x >= 0.03f * w && it.x <= 0.97f * w && it.y >= 0.03f * h && it.y <= 0.97f * h }

    /** One session's frames. */
    private fun session(si: Int): List<Frame> {
        val (bgKind, light) = sessions[si]
        val rnd = java.util.Random(8000L + si)
        val bg = SceneGenerator.backgroundFn(bgKind, rnd, w, h)
        val lf = SceneGenerator.lightFn(light, rnd, w, h)
        val noise = SceneGenerator.noiseOf(light)
        val papers = HashMap<Int, (Float, Float) -> Int>()
        fun paper(id: Int) = papers.getOrPut(id) { SceneGenerator.paperFn(java.util.Random(90_000L + 100L * si + id)) }
        val frames = ArrayList<Frame>()
        fun frame(q: Quad?, id: Int?, hold: Boolean, blur: Float = 0f) {
            val sc = SceneGenerator.renderFrame("s$si", w, h, q, id?.let { paper(it) }, bg, lf, if (blur > 0f) 0f else noise, rnd)
            val px = if (blur > 0f) SceneGenerator.addNoise(SceneGenerator.blur(sc.px, w, h, blur), noise, rnd.nextLong()) else sc.px
            frames += Frame(SceneGenerator.Scene(sc.label, w, h, px, q), if (q == null) null else id, hold)
        }
        fun place(): Quad {
            while (true) {
                val q = SceneGenerator.placePage(rnd, (rnd.nextInt(3) * 10).toFloat(), w, h, 0.32f..0.45f, 0.08f)
                if (inside(q)) return q
            }
        }
        fun holdOn(q: Quad, id: Int) {
            // Hand jitter: ~0.15% of the frame per frame around the pose.
            repeat(18) {
                frame(shift(q, (rnd.nextGaussian() * 0.0015 * w).toFloat(), (rnd.nextGaussian() * 0.0015 * h).toFloat()), id, hold = true)
            }
        }
        var id = 0
        var pose = place()
        repeat(4) { frame(null, null, false) }
        holdOn(pose, id)
        for (m in moves) {
            when (m) {
                Move.COVER -> {
                    repeat(6) { frame(null, null, false) } // the hand covers the view, the page is swapped
                    id++; pose = place()
                }
                Move.SLIDE -> {
                    val dir = if (pose.points.sumOf { it.x.toDouble() } / 4 > w / 2) -1f else 1f
                    for (k in 1..3) frame(shift(pose, dir * 0.25f * w * k, 0f), id, false, blur = 2f)
                    id++
                    val next = place()
                    for (k in 3 downTo 1) frame(shift(next, -dir * 0.25f * w * k, 0f), id, false, blur = 2f)
                    pose = next
                }
                Move.ON_TOP -> {
                    // The next sheet is laid on the last one: about the same spot, a moment of blur.
                    id++
                    var next: Quad
                    do { next = shift(pose, (rnd.nextFloat() - 0.5f) * 0.03f * w, (rnd.nextFloat() - 0.5f) * 0.03f * h) } while (!inside(next))
                    frame(shift(next, 0.06f * w, 0.02f * h), id, false, blur = 2.5f)
                    pose = next
                }
                Move.PHONE_MOVED -> {
                    // Same page, the phone moves by ~20% of the frame and stops again.
                    var next: Quad
                    var tries = 0
                    do {
                        val ang = rnd.nextFloat() * 6.28f
                        next = shift(pose, kotlin.math.cos(ang) * 0.2f * w, kotlin.math.sin(ang) * 0.2f * h)
                        tries++
                    } while (!inside(next) && tries < 50)
                    if (!inside(next)) next = place()
                    for (k in 1..4) {
                        val t = k / 5f
                        frame(Quad(lerp(pose.tl, next.tl, t), lerp(pose.tr, next.tr, t), lerp(pose.br, next.br, t), lerp(pose.bl, next.bl, t)), id, false, blur = 2f)
                    }
                    pose = next
                }
                Move.LIFTED_BACK -> {
                    repeat(5) { frame(null, null, false) }
                    var next: Quad
                    do { next = shift(pose, (rnd.nextFloat() - 0.5f) * 0.12f * w, (rnd.nextFloat() - 0.5f) * 0.12f * h) } while (!inside(next))
                    pose = next
                }
            }
            holdOn(pose, id)
        }
        return frames
    }

    private fun lerp(a: Pt, b: Pt, t: Float) = Pt(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)

    /** Runs the camera's per-frame pipeline over a session; returns the page id of every shot (null = no page). */
    private fun run(frames: List<Frame>, cfg: DetectionConfig): List<Int?> {
        val gate = AutoCapture(cfg)
        val detector = QuadDetector(320)
        val shots = ArrayList<Int?>()
        var busyUntil = -1L
        for ((i, f) in frames.withIndex()) {
            val t = i * 100L
            val s = f.scene
            val gray = ImageFilters.toGray(s.px, w, h)
            val det = detector.detectBest(listOf({ gray }, { ImageFilters.paperMap(s.px, w, h) }))
            val norm = det?.quad?.map { Pt(it.x / w, it.y / h) }
            // As CameraScreen.liveWarning: whole page, sharp frame.
            var sharp = true
            var complete = true
            if (det != null && norm != null) {
                complete = QualityMetrics.isComplete(norm, det.cut, cfg)
                if (complete) {
                    val sh = QualityMetrics.sharpness(gray, det.quad, cfg)
                    sharp = sh.isNaN() || sh >= cfg.liveBlurThreshold
                }
            }
            val confident = det?.confident == true && complete
            val content = det?.let { QualityMetrics.contentSignature(gray, it.quad) }
            val u = gate.feed(norm, confident, t, true, sharp, content)
            if (u.state == AutoCapture.State.FIRE && t >= busyUntil) {
                gate.captured(u.quad)
                shots += f.page
                busyUntil = t + 800
            }
        }
        return shots
    }

    private fun score(frames: List<Frame>, shots: List<Int?>): Score {
        val expected = frames.mapNotNull { it.page }.toSet()
        val taken = HashSet<Int>()
        var correct = 0; var dup = 0; var none = 0
        for (s in shots) {
            when {
                s == null -> none++
                taken.add(s) -> correct++
                else -> dup++
            }
        }
        return Score(correct, dup, expected.size - taken.size, none, expected.size)
    }

    @Test
    fun batchSessions() {
        val all = sessions.indices.map { session(it) }
        val results = LinkedHashMap<String, Score>()
        for ((name, cfg) in listOf("2.0 (outline only)" to DetectionConfig(contentRearm = false), "2.0-cam2 (content re-arm)" to DetectionConfig.DEFAULT)) {
            var c = 0; var d = 0; var m = 0; var n = 0; var e = 0
            val per = StringBuilder()
            for ((si, frames) in all.withIndex()) {
                val sc = score(frames, run(frames, cfg))
                c += sc.correct; d += sc.duplicates; m += sc.missed; n += sc.noPage; e += sc.expected
                per.append(" ${sessions[si].first.name.lowercase()}/${sessions[si].second.name.lowercase()} ${sc.correct}/${sc.expected} dup ${sc.duplicates} miss ${sc.missed};")
            }
            val total = Score(c, d, m, n, e)
            results[name] = total
            println("[batch] %-26s correct %d/%d, duplicates %d, missed %d, shots without a page %d |%s".format(name, c, e, d, m, n, per))
        }
        val frames = all.sumOf { it.size }
        println("[batch] ${all.size} sessions, $frames frames (${frames / 10} s of camera), ${all.sumOf { f -> f.mapNotNull { it.page }.toSet().size }} distinct pages, ${moves.count { it == Move.PHONE_MOVED || it == Move.LIFTED_BACK } * all.size} same-page re-holds")
        val old = results.values.first()
        val new = results.values.last()
        assertTrue("no shot without a page", new.noPage == 0)
        assertTrue("duplicates ${new.duplicates} vs ${old.duplicates}", new.duplicates <= old.duplicates)
        assertTrue("correct ${new.correct} vs ${old.correct}", new.correct >= old.correct)
        assertTrue("correct ${new.correct}/${new.expected}", new.correct >= new.expected * 0.9)
    }

    @Test
    fun signatureSeparatesPages() {
        // Same page under another pose and light vs another page with the same kind of layout.
        val rnd = java.util.Random(77)
        val bg = SceneGenerator.backgroundFn(SceneGenerator.Background.DARK_WOOD, rnd, w, h)
        val same = ArrayList<Float>(); val other = ArrayList<Float>()
        for (i in 0 until 12) {
            val pa = SceneGenerator.paperFn(java.util.Random(500L + i))
            val pb = SceneGenerator.paperFn(java.util.Random(600L + i))
            val q1 = SceneGenerator.placePage(rnd, 10f, w, h, 0.32f..0.45f, 0.08f)
            val q2 = SceneGenerator.placePage(rnd, 20f, w, h, 0.32f..0.45f, 0.08f)
            val l1 = SceneGenerator.lightFn(SceneGenerator.Light.NORMAL, rnd, w, h)
            val l2 = SceneGenerator.lightFn(SceneGenerator.Light.SIDE_LIT, rnd, w, h)
            fun sig(q: Quad, p: (Float, Float) -> Int, l: (Float, Float) -> Float): FloatArray {
                val s = SceneGenerator.renderFrame("sig", w, h, q, p, bg, l, 3f, rnd)
                return QualityMetrics.contentSignature(ImageFilters.toGray(s.px, w, h), q)!!
            }
            val a1 = sig(q1, pa, l1)
            same += QualityMetrics.similarity(a1, sig(q2, pa, l2))
            other += QualityMetrics.similarity(a1, sig(q2, pb, l2))
        }
        println("[batch] signature similarity: same page min %.2f mean %.2f; other page max %.2f mean %.2f"
            .format(same.min(), same.average(), other.max(), other.average()))
        val cfg = DetectionConfig.DEFAULT
        assertTrue(same.min() >= cfg.sameContent)
        assertTrue(other.max() < cfg.sameContent)
    }
}
