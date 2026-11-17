// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.ocr.fused

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * The shipped OCR models are exactly the pinned files (tools/mnn/models.json, THIRD_PARTY.md), and the
 * fused engine's cache version belongs to them: changing a model, a threshold or the fusion rule without
 * bumping [FusedOcrEngine.VERSION] would let stale cached text stand in for the new engine's.
 */
class ModelPinTest {
    private fun sha256(f: File) = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }

    @Test
    fun assetsAreThePinnedFiles() {
        val spec = JSONObject(File("../tools/mnn/models.json").readText())
        val models = spec.getJSONArray("models")
        for (i in 0 until models.length()) {
            val m = models.getJSONObject(i).getJSONObject("mnn")
            assertEquals(m.getString("file"), m.getString("sha256"), sha256(File("src/main/assets/ocr/" + m.getString("file"))))
        }
        val dict = spec.getJSONObject("dictionary")
        assertEquals(dict.getString("sha256"), sha256(File("src/main/assets/ocr/" + dict.getString("file"))))
        assertEquals(FusedOcrEngine.DET_MODEL, models.getJSONObject(0).getJSONObject("mnn").getString("file"))
        assertEquals(FusedOcrEngine.REC_MODEL, models.getJSONObject(1).getJSONObject("mnn").getString("file"))
    }

    @Test
    fun engineVersionIsPinnedToItsInputs() {
        val inputs = listOf(
            sha256(File("src/main/assets/ocr/" + FusedOcrEngine.DET_MODEL)),
            sha256(File("src/main/assets/ocr/" + FusedOcrEngine.REC_MODEL)),
            PpDetector.MAX_SIDE, PpDetector.THRESH, PpDetector.BOX_THRESH, PpDetector.UNCLIP_RATIO, PpDetector.MAX_TILT_DEG,
            PpArabicRecognizer.HEIGHT, PpArabicRecognizer.MIN_WIDTH, PpArabicRecognizer.PAD, PpArabicRecognizer.PAD_MIN,
            FusedPipeline.TEXT_SCORE, FusedPipeline.TESS_PAD, OcrFusion.LATIN_RULE,
        ).joinToString("|")
        // If this fails: bump FusedOcrEngine.VERSION, then record the new pair here.
        assertEquals(
            "1 " + "a1d21086e7508b10fe977de651cdcba902d60d5fc6d1f87f17cea20f0fe129e6|9c0098c4d0b37437f55e1e1225c6be2fae6506239bfef868fb3c8f90a5cc484e|960|0.3|0.5|1.6|10.0|48|320|0.25|96|0.3|0.25|false",
            "${FusedOcrEngine.VERSION} $inputs",
        )
    }
}
