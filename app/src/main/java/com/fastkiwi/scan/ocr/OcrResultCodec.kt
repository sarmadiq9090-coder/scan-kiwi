// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.ocr

import org.json.JSONArray
import org.json.JSONObject

/**
 * The OCR cache file (p_<pageId>_ocr.json). Format 2 (2.0) is a superset of 1.2's: the same `revision`,
 * `words`, `lang` and `frame` keys, so 1.2 can still read a 2.0 file, plus:
 *   "v": 2, "engine": "pp-fused", "engineVersion": 1, "maxSide": 2400,
 *   "wconf": [per-mille confidence per word], "lines": [[firstWord, endWord), ...], "dir": "R" | "L" | "N"
 * A file without "engine" was written by 1.0-1.2's Tesseract engine ([OcrEngineKey.LEGACY]).
 */
object OcrResultCodec {
    const val FORMAT = 2

    class Decoded(
        val revision: Int,
        val engine: OcrEngineKey,
        val lang: String?,
        val frameWidth: Int,
        val frameHeight: Int,
        val words: List<com.fastkiwi.scan.core.OcrWord>,
        /** Line ranges into [words] (half-open); one line with every word for 1.2 files. */
        val lines: List<IntRange>,
        val confidences: List<Float>?,
    )

    fun encode(revision: Int, r: OcrResult, lang: String, maxSide: Int): String {
        val words = JSONArray()
        val conf = JSONArray()
        val lines = JSONArray()
        var i = 0
        for (l in r.lines) {
            val start = i
            for (w in l.words) {
                words.put(JSONArray().put(w.text).put(w.box.left).put(w.box.top).put(w.box.right).put(w.box.bottom))
                conf.put((w.confidence * 1000).toInt().coerceIn(0, 1000))
                i++
            }
            lines.put(JSONArray().put(start).put(i))
        }
        return JSONObject()
            .put("revision", revision)
            .put("words", words)
            .put("lang", lang)
            .put("frame", JSONArray().put(r.frameWidth).put(r.frameHeight))
            .put("v", FORMAT)
            .put("engine", r.engineId)
            .put("engineVersion", r.engineVersion)
            .put("maxSide", maxSide)
            .put("wconf", conf)
            .put("lines", lines)
            .put("dir", when (r.direction) { TextDirection.RTL -> "R"; TextDirection.LTR -> "L"; TextDirection.NEUTRAL -> "N" })
            .toString()
    }

    /** Null on any parse problem (a miss, as in 1.2). Unknown keys are ignored. */
    fun decode(json: String): Decoded? = runCatching {
        val o = JSONObject(json)
        val arr = o.getJSONArray("words")
        val words = List(arr.length()) {
            val a = arr.getJSONArray(it)
            com.fastkiwi.scan.core.OcrWord(a.getString(0), a.getInt(1), a.getInt(2), a.getInt(3), a.getInt(4))
        }
        val frame = o.optJSONArray("frame")
        val engine = if (o.has("engine")) OcrEngineKey(o.getString("engine"), o.getInt("engineVersion")) else OcrEngineKey.LEGACY
        val lines = o.optJSONArray("lines")?.let { la ->
            List(la.length()) { val r = la.getJSONArray(it); r.getInt(0) until r.getInt(1) }
        }?.takeIf { ls -> ls.all { it.first >= 0 && it.last < words.size } } ?: listOf(words.indices)
        val conf = o.optJSONArray("wconf")?.let { c -> List(c.length()) { c.getInt(it) / 1000f } }?.takeIf { it.size == words.size }
        Decoded(
            o.getInt("revision"), engine, if (o.has("lang")) o.getString("lang") else null,
            frame?.optInt(0, 0) ?: 0, frame?.optInt(1, 0) ?: 0, words, lines, conf,
        )
    }.getOrNull()
}
