// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.export

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.fastkiwi.scan.core.Filter
import com.fastkiwi.scan.data.DocumentStore
import com.fastkiwi.scan.data.ScanDocument
import com.fastkiwi.scan.data.ScanPage
import com.fastkiwi.scan.imaging.BitmapIO
import java.io.File
import java.security.MessageDigest
import kotlin.random.Random

/** Synthetic scanned documents for export tests (never a real document). */
object ExportSeed {

    /**
     * [pages] pages of [w] x [h]: text-like lines, a photo-like noisy block (scaled by [noise],
     * which drives the JPEG size) and every third page black-and-white when [mixBw].
     */
    fun doc(store: DocumentStore, name: String, pages: Int, seed: Int, noise: Float = 0.3f, mixBw: Boolean = true, w: Int = 1800, h: Int = 2546): ScanDocument {
        val rnd = Random(seed)
        var doc = store.create(name)
        val list = ArrayList<ScanPage>()
        for (p in 0 until pages) {
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            c.drawColor(Color.rgb(250, 248, 240))
            val ink = Paint().apply { color = Color.rgb(25, 25, 35); strokeWidth = 6f }
            for (i in 0 until 40) {
                val y = 150f + i * (h - 300f) / 40f
                c.drawLine(150f, y, 150f + rnd.nextInt(w - 300), y, ink)
            }
            // A noisy "photo" block: random blocks of colour.
            val blocks = (noise * 60).toInt().coerceAtLeast(1)
            val fill = Paint()
            val bs = (w / 3) / blocks.coerceAtLeast(1)
            for (by in 0 until blocks) for (bx in 0 until blocks) {
                fill.color = Color.rgb(rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256))
                c.drawRect(w / 2f + bx * bs, h / 3f + by * bs, w / 2f + (bx + 1) * bs, h / 3f + (by + 1) * bs, fill)
            }
            val id = DocumentStore.newId()
            BitmapIO.saveJpeg(bmp, store.srcFile(doc.id, id), 92)
            BitmapIO.saveJpeg(bmp, store.outFile(doc.id, id), 92)
            BitmapIO.saveJpeg(BitmapIO.fit(bmp, 360), store.thumbFile(doc.id, id), 82)
            list.add(ScanPage(id, BitmapIO.FULL, 0, if (mixBw && p % 3 == 2) Filter.BW else Filter.ENHANCED))
        }
        doc = store.save(doc.copy(pages = list))
        return doc
    }

    fun sha(f: File): String = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }

    /** sha256 of every file of a document (its "original"). */
    fun docFingerprint(store: DocumentStore, doc: ScanDocument): Map<String, String> =
        store.dir(doc.id).listFiles().orEmpty().filter { it.isFile }.associate { it.name to sha(it) }
}
