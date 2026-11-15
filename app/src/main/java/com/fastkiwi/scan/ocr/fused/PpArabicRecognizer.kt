// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.ocr.fused

import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * PP-OCRv5 Arabic mobile recogniser (PaddlePaddle, Apache-2.0) on MNN: one detected line at a time.
 * The line's rotated box is sampled straight to 48 px height, followed by zero padding (the model reads a
 * line that fills its whole input badly), and the CTC output is decoded greedily with the model's
 * dictionary, then turned from visual into logical order ([Bidi]).
 */
class PpArabicRecognizer(private val runner: ModelRunner, private val chars: List<String>) : AutoCloseable {

    data class Read(val text: String, val score: Double)

    fun read(gray: ByteArray, w: Int, h: Int, box: TextBox): Read {
        val (resized, imgW) = width(box)
        val input = input(gray, w, h, box, resized, imgW)
        val out = runner.run(input, intArrayOf(1, 3, HEIGHT, imgW))
        val classes = out.shape.last()
        val steps = out.values.size / classes
        val idx = IntArray(steps)
        val prob = FloatArray(steps)
        for (t in 0 until steps) {
            var best = 0; var bv = out.values[t * classes]
            for (c in 1 until classes) {
                val v = out.values[t * classes + c]
                if (v > bv) { bv = v; best = c }
            }
            idx[t] = best; prob[t] = bv
        }
        val (visual, score) = ctcGreedy(idx, prob, chars)
        return Read(Bidi.visualToLogical(visual), score)
    }

    override fun close() = runner.close()

    companion object {
        const val HEIGHT = 48
        const val MIN_WIDTH = 320
        const val PAD = 0.25
        const val PAD_MIN = 96

        /** The model's dictionary file (one character per line) → index 0 blank, then the characters, then ' '. */
        fun dictionary(text: String): List<String> {
            val lines = text.split("\n").let { if (it.isNotEmpty() && it.last().isEmpty()) it.dropLast(1) else it }
            return listOf("<blank>") + lines + listOf(" ")
        }

        /** (width of the line image, width of the input tensor). */
        fun width(box: TextBox): Pair<Int, Int> {
            val ratio = box.w / box.h
            val resized = max(1, ceil(HEIGHT * ratio).toInt())
            val imgW = max(MIN_WIDTH, resized + max(PAD_MIN, (resized * PAD).toInt()))
            return resized to imgW
        }

        fun input(gray: ByteArray, w: Int, h: Int, box: TextBox, resized: Int, imgW: Int): FloatArray {
            val ux = cos(box.angle).toFloat(); val uy = sin(box.angle).toFloat()
            val vx = -uy; val vy = ux
            val bw = box.w.toFloat(); val bh = box.h.toFloat()
            val hw = (box.w / 2).toFloat(); val hh = (box.h / 2).toFloat()
            val cx = box.cx.toFloat(); val cy = box.cy.toFloat()
            val plane = HEIGHT * imgW
            val out = FloatArray(3 * plane)
            val maxX = (w - 1).toFloat(); val maxY = (h - 1).toFloat()
            for (i in 0 until HEIGHT) {
                val b = (i + 0.5f) / HEIGHT.toFloat() * bh - hh
                for (j in 0 until resized) {
                    val a = (j + 0.5f) / resized.toFloat() * bw - hw
                    val px = (cx + a * ux + b * vx - 0.5f).coerceIn(0f, maxX)
                    val py = (cy + a * uy + b * vy - 0.5f).coerceIn(0f, maxY)
                    val x0 = px.toInt(); val y0 = py.toInt()
                    val x1 = min(w - 1, x0 + 1); val y1 = min(h - 1, y0 + 1)
                    val ax = px - x0; val ay = py - y0
                    val g00 = (gray[y0 * w + x0].toInt() and 0xFF).toFloat()
                    val g01 = (gray[y0 * w + x1].toInt() and 0xFF).toFloat()
                    val g10 = (gray[y1 * w + x0].toInt() and 0xFF).toFloat()
                    val g11 = (gray[y1 * w + x1].toInt() and 0xFF).toFloat()
                    val top = g00 * (1 - ax) + g01 * ax
                    val bot = g10 * (1 - ax) + g11 * ax
                    val v = ((top * (1 - ay) + bot * ay) / 255f - 0.5f) / 0.5f
                    val k = i * imgW + j
                    out[k] = v; out[plane + k] = v; out[2 * plane + k] = v
                }
            }
            return out
        }

        /** Greedy CTC: drop repeats and blanks (index 0); score = mean probability of the kept steps. */
        fun ctcGreedy(idx: IntArray, prob: FloatArray, chars: List<String>): Pair<String, Double> {
            val sb = StringBuilder()
            var sum = 0.0; var n = 0; var prev = -1
            for (t in idx.indices) {
                val k = idx[t]
                if (k != prev && k != 0) {
                    if (k < chars.size) sb.append(chars[k])
                    sum += prob[t]; n++
                }
                prev = k
            }
            return sb.toString() to (if (n == 0) 0.0 else sum / n)
        }
    }
}
