// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.ocr.fused

import com.fastkiwi.scan.ocr.OcrInput
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.GZIPInputStream
import kotlin.math.abs

/**
 * Parity of the Kotlin pre/post-processing with the Python reference (tools/ocr-bench/kiwi_pipeline.py),
 * the code the desktop benchmark measured. tools/ocr-bench/record_golden.py ran the reference on three dev
 * pages with MNN and Tesseract and recorded the raw model outputs (detector map, recogniser argmax and
 * probability per time step) and Tesseract's line texts. Fed with the same outputs, the Kotlin pipeline must
 * build the same model inputs, find the same boxes and give exactly the same text.
 */
class FusedPipelineGoldenTest {

    private val chars = PpArabicRecognizer.dictionary(File("src/main/assets/ocr/ppocrv5_arabic_dict.txt").readText(Charsets.UTF_8))

    private fun res(path: String) = javaClass.getResourceAsStream("/ocr-golden/$path") ?: error("missing $path")

    private fun gunzip(path: String): ByteArray = GZIPInputStream(res(path)).use { it.readBytes() }

    private fun gray(id: String): Triple<ByteArray, Int, Int> {
        val pgm = gunzip("$id/gray.pgm.gz")
        // "P5\n<w> <h>\n255\n" + pixels
        var nl = 0; var i = 0
        while (nl < 3) { if (pgm[i] == '\n'.code.toByte()) nl++; i++ }
        val head = String(pgm, 0, i, Charsets.US_ASCII).split(Regex("\\s+"))
        val w = head[1].toInt(); val h = head[2].toInt()
        return Triple(pgm.copyOfRange(i, i + w * h), w, h)
    }

    private fun close(a: Double, b: Double, rel: Double) = abs(a - b) <= rel * maxOf(1.0, abs(b))

    private fun check(id: String) = runBlocking {
        val t = JSONObject(String(res("$id/trace.json").readBytes(), Charsets.UTF_8))
        val (plane, w, h) = gray(id)
        assertEquals(t.getInt("width"), w)
        val detShape = t.getJSONArray("det_shape")
        val rh = detShape.getInt(0); val rw = detShape.getInt(1)
        val map = gunzip("$id/det_map.u8.gz")
        val det = object : ModelRunner {
            override fun run(input: FloatArray, shape: IntArray): ModelOutput {
                assertEquals("$id detector input shape", listOf(1, 3, rh, rw), shape.toList())
                val sum = input.sumOf { it.toDouble() }
                assertTrue("$id detector input sum $sum vs ${t.getDouble("det_input_sum")}", close(sum, t.getDouble("det_input_sum"), 1e-6))
                return ModelOutput(FloatArray(rh * rw) { (map[it].toInt() and 0xFF) / 255f }, intArrayOf(1, 1, rh, rw))
            }
        }
        val recs = t.getJSONArray("rec")
        var k = 0
        val rec = object : ModelRunner {
            override fun run(input: FloatArray, shape: IntArray): ModelOutput {
                val r = recs.getJSONObject(k++)
                assertEquals("$id line $k width", r.getInt("width"), shape[3])
                val sum = input.sumOf { it.toDouble() }
                assertTrue("$id line $k input sum $sum vs ${r.getDouble("input_sum")}", close(sum, r.getDouble("input_sum"), 1e-5))
                val idx = r.getJSONArray("idx"); val prob = r.getJSONArray("prob")
                val classes = chars.size
                val out = FloatArray(idx.length() * classes)
                for (s in 0 until idx.length()) out[s * classes + idx.getInt(s)] = prob.getDouble(s).toFloat()
                return ModelOutput(out, intArrayOf(1, idx.length(), classes))
            }
        }
        val tess = t.getJSONArray("tess")
        val tessTexts = (0 until tess.length()).filter { !tess.isNull(it) }.map { tess.getString(it) }.iterator()
        val reader = object : LineReader {
            override suspend fun begin(norm: ByteArray, w: Int, h: Int) {}
            override suspend fun read(left: Int, top: Int, right: Int, bottom: Int) = tessTexts.next()
        }
        val boxes = PpDetector.boxes(FloatArray(rh * rw) { (map[it].toInt() and 0xFF) / 255f }, rw, rh, w, h)
        val want = t.getJSONArray("boxes")
        assertEquals("$id box count", want.length(), boxes.size)
        boxes.forEachIndexed { i, b ->
            val e = want.getJSONArray(i)
            listOf(b.cx, b.cy, b.w, b.h, b.angle, b.score).forEachIndexed { j, v ->
                assertTrue("$id box $i field $j: $v vs ${e.getDouble(j)}", close(v, e.getDouble(j), 1e-6))
            }
        }
        val pipeline = FusedPipeline(PpDetector(det), PpArabicRecognizer(rec, chars), reader, normalize = { g, _, _ -> g })
        val result = pipeline.run(OcrInput(plane, w, h), "pp-fused", 1)
        assertEquals("$id recogniser calls", recs.length(), k)
        assertEquals("$id text", strings(t.getJSONArray("lines")), result.lines.map { it.text })
    }

    private fun strings(a: JSONArray) = List(a.length()) { a.getString(it) }

    @Test fun amountsPage() = check("019_amounts")

    @Test fun tableRowsInReadingOrder() = check("047_small_lowq")

    @Test fun mixedArabicEnglishPage() = check("048_small_lowq")

    /** The bidi classes Kotlin derives for every dictionary character equal Python's (unicodedata). */
    @Test
    fun bidiClassesMatchPython() {
        val lines = String(res("bidi_classes.txt").readBytes(), Charsets.UTF_8).lines().filter { it.isNotBlank() }
        assertEquals(chars.size - 1, lines.size)
        val diff = lines.mapNotNull { l ->
            val (hex, cls) = l.split(" ")
            val c = hex.toInt(16).toChar()
            if (Bidi.className(c) != cls) "$hex kotlin=${Bidi.className(c)} python=$cls" else null
        }
        assertTrue("bidi class differences: $diff", diff.isEmpty())
    }
}
