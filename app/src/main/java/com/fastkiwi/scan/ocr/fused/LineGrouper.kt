// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.ocr.fused

import com.fastkiwi.scan.ocr.OcrBox
import com.fastkiwi.scan.ocr.RecognizedLine
import com.fastkiwi.scan.ocr.RecognizedWord
import com.fastkiwi.scan.ocr.TextDirection
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Groups read line boxes into rows (top to bottom) and orders the cells of a row right to left on Arabic
 * pages and left to right on pages with more Latin than Arabic letters. A table row becomes one line whose
 * words are the cells' words, cell by cell in reading order (OCR-06). Same rule as group_lines() in
 * tools/ocr-bench/kiwi_pipeline.py.
 */
object LineGrouper {

    /** One read box: axis-aligned bounds in plane pixels, its text and confidence. */
    data class Cell(val x0: Double, val y0: Double, val x1: Double, val y1: Double, val text: String, val confidence: Float)

    data class Row(val cells: List<Cell>, val y0: Double, val y1: Double) {
        val text: String get() = cells.map { it.text.trim() }.filter { it.isNotEmpty() }.joinToString(" ")
    }

    fun arabicLetters(s: String) = s.count { it in 'ؠ'..'ي' || it in 'ٱ'..'ۓ' }

    fun latinLetters(s: String) = s.count { it.lowercaseChar() in 'a'..'z' }

    /** RTL unless the page holds more Latin than Arabic letters. */
    fun isRtlPage(cells: List<Cell>): Boolean {
        val all = cells.joinToString("") { it.text }
        return !(latinLetters(all) > arabicLetters(all))
    }

    fun group(cells: List<Cell>, rtl: Boolean): List<Row> {
        class Acc(var y0: Double, var y1: Double, val cells: MutableList<Cell>)
        val rows = cells.sortedBy { (it.y0 + it.y1) / 2 }
        val lines = ArrayList<Acc>()
        for (r in rows) {
            val hit = lines.firstOrNull { l ->
                val ov = min(l.y1, r.y1) - max(l.y0, r.y0)
                ov > 0.5 * min(l.y1 - l.y0, r.y1 - r.y0)
            }
            if (hit != null) {
                hit.cells.add(r); hit.y0 = min(hit.y0, r.y0); hit.y1 = max(hit.y1, r.y1)
            } else {
                lines.add(Acc(r.y0, r.y1, mutableListOf(r)))
            }
        }
        return lines.sortedBy { (it.y0 + it.y1) / 2 }
            .map { l -> Row(if (rtl) l.cells.sortedBy { -it.x1 } else l.cells.sortedBy { it.x0 }, l.y0, l.y1) }
            .filter { it.text.isNotEmpty() }
    }

    /**
     * The rows as [RecognizedLine]s in page pixels ([scale] maps plane pixels to the page). A cell's words
     * get boxes that split the cell's width by character count, the first word at the right on RTL pages.
     */
    fun toLines(rows: List<Row>, rtl: Boolean, scale: Float): List<RecognizedLine> = rows.map { row ->
        val words = ArrayList<RecognizedWord>()
        for (c in row.cells) {
            val parts = c.text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (parts.isEmpty()) continue
            val total = parts.sumOf { it.length } + (parts.size - 1)
            val width = c.x1 - c.x0
            var offset = 0
            for (p in parts) {
                val a = width * offset / total
                val b = width * (offset + p.length) / total
                val (l, r) = if (rtl) (c.x1 - b) to (c.x1 - a) else (c.x0 + a) to (c.x0 + b)
                words.add(RecognizedWord(p, box(l, c.y0, r, c.y1, scale), c.confidence))
                offset += p.length + 1
            }
        }
        val box = words.drop(1).fold(words.first().box) { b, w -> b.union(w.box) }
        RecognizedLine(words, box, words.map { it.confidence }.average().toFloat(), if (rtl) TextDirection.RTL else TextDirection.LTR)
    }

    private fun box(l: Double, t: Double, r: Double, b: Double, k: Float) =
        OcrBox((l * k).roundToInt(), (t * k).roundToInt(), max((l * k).roundToInt() + 1, (r * k).roundToInt()), max((t * k).roundToInt() + 1, (b * k).roundToInt()))
}
