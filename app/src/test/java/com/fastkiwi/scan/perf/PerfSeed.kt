// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.perf

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.fastkiwi.scan.core.Filter
import com.fastkiwi.scan.data.DocumentStore
import com.fastkiwi.scan.data.ScanDocument
import com.fastkiwi.scan.data.ScanPage
import com.fastkiwi.scan.imaging.BitmapIO
import com.fastkiwi.scan.ocr.OcrBox
import com.fastkiwi.scan.ocr.OcrEngine
import com.fastkiwi.scan.ocr.OcrInput
import com.fastkiwi.scan.ocr.OcrOptions
import com.fastkiwi.scan.ocr.OcrResult
import com.fastkiwi.scan.ocr.RecognizedLine
import com.fastkiwi.scan.ocr.RecognizedWord
import com.fastkiwi.scan.ocr.TextDirection
import kotlin.random.Random

/** Synthetic phone-size documents and a fake OCR engine for the 2.0-perf tests (never a real document). */
object PerfSeed {

    /** A text-like page of [w] x [h] with a noisy block (JPEG cost like a real scan). */
    fun page(w: Int, h: Int, seed: Int): Bitmap {
        val rnd = Random(seed)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.rgb(250, 248, 240))
        val ink = Paint().apply { color = Color.rgb(25, 25, 35); strokeWidth = 8f }
        for (i in 0 until 60) {
            val y = 180f + i * (h - 360f) / 60f
            c.drawLine(180f, y, 180f + rnd.nextInt(w - 360), y, ink)
        }
        val fill = Paint()
        val bs = w / 30
        for (by in 0 until 10) for (bx in 0 until 10) {
            fill.color = Color.rgb(rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256))
            c.drawRect(w / 2f + bx * bs, h / 3f + by * bs, w / 2f + (bx + 1) * bs, h / 3f + (by + 1) * bs, fill)
        }
        return bmp
    }

    /**
     * [n] pages of [w] x [h] (phone size by default): three distinct pages (two colour, one
     * black-and-white) copied round-robin, so seeding 500 pages stays fast.
     */
    fun doc(store: DocumentStore, name: String, n: Int, w: Int = 2400, h: Int = 3200): ScanDocument {
        var doc = store.create(name)
        val firsts = (0 until minOf(3, n)).map { k ->
            val bmp = page(w, h, k)
            val id = DocumentStore.newId()
            BitmapIO.saveJpeg(bmp, store.srcFile(doc.id, id), 92)
            BitmapIO.saveJpeg(bmp, store.outFile(doc.id, id), 92)
            BitmapIO.saveJpeg(BitmapIO.fit(bmp, 360), store.thumbFile(doc.id, id), 82) // fit recycles bmp
            ScanPage(id, BitmapIO.FULL, 0, if (k == 2) Filter.BW else Filter.ENHANCED)
        }
        val pages = ArrayList<ScanPage>(firsts)
        while (pages.size < n) pages.add(store.copyPage(doc.id, firsts[pages.size % firsts.size], doc.id))
        doc = store.save(doc.copy(pages = pages))
        return doc
    }

    /**
     * A fake engine that reads the whole gray plane (like a real one would) and returns 40 words in the
     * page's pixels. Records the largest plane it was given.
     */
    class FakeOcr : OcrEngine {
        override val id = "perf-fake"
        override val version = 1
        @Volatile var calls = 0
        @Volatile var maxPlaneSide = 0
        @Volatile var closed = 0
        var checksum = 0L

        override suspend fun recognize(page: OcrInput, options: OcrOptions): OcrResult {
            calls++
            maxPlaneSide = maxOf(maxPlaneSide, page.width, page.height)
            var s = 0L
            for (i in 0 until page.width * page.height) s += page.gray[i].toInt() and 0xFF
            checksum += s
            val k = page.scaleToPage
            val words = (0 until 40).map { i ->
                val y = (60 + i * (page.height - 120) / 40)
                RecognizedWord("كلمة$i", OcrBox((40 * k).toInt(), (y * k).toInt(), ((40 + page.width / 3) * k).toInt(), ((y + 20) * k).toInt()), 0.9f)
            }
            val line = RecognizedLine(words, words.drop(1).fold(words[0].box) { b, w -> b.union(w.box) }, 0.9f, TextDirection.RTL)
            return OcrResult(listOf(line), page.pageWidth, page.pageHeight, id, version)
        }

        override fun close() { closed++ }
    }
}
