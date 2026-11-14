// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Looper
import com.fastkiwi.scan.core.Filter
import com.fastkiwi.scan.core.PageSize
import com.fastkiwi.scan.data.DocumentStore
import com.fastkiwi.scan.data.ScanDocument
import com.fastkiwi.scan.data.ScanPage
import com.fastkiwi.scan.export.ExportOptions
import com.fastkiwi.scan.export.Quality
import com.fastkiwi.scan.imaging.BitmapIO
import com.fastkiwi.scan.ui.AppViewModel
import com.fastkiwi.scan.ui.ExportResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Owner report (1.1.1): «عند الضغط على زر مشاركة كانه يتاخر او يعلق».
 * Measures what happens between tapping Share and the share sheet, on
 * phone-sized pages (2400 x 3200), for 1, 5 and 20 pages.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class ExportPerformanceTest {

    internal fun seedPages(vm: AppViewModel, n: Int, filter: Filter = Filter.ENHANCED): ScanDocument {
        val store = vm.store
        var doc = store.create("perf $n")
        // One rendered page of phone size, copied n times (fast seeding).
        val bmp = Bitmap.createBitmap(2400, 3200, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        val ink = Paint().apply { color = Color.rgb(30, 30, 40); strokeWidth = 9f }
        for (i in 0 until 60) c.drawLine(200f, 200f + i * 45f, 2200f - (i % 5) * 200f, 200f + i * 45f, ink)
        val id = DocumentStore.newId()
        BitmapIO.saveJpeg(bmp, store.srcFile(doc.id, id), 92)
        BitmapIO.saveJpeg(bmp, store.outFile(doc.id, id), 92)
        BitmapIO.saveJpeg(BitmapIO.fit(bmp, 360), store.thumbFile(doc.id, id), 82)
        val first = ScanPage(id, BitmapIO.FULL, 0, filter)
        val pages = mutableListOf(first)
        repeat(n - 1) { pages.add(store.copyPage(doc.id, first, doc.id)) }
        doc = store.save(doc.copy(pages = pages))
        return doc
    }

    private fun timeShare(n: Int): String {
        // The view model alone: the test is the only consumer of its export results.
        val vm = AppViewModel(RuntimeEnvironment.getApplication())
        val doc = seedPages(vm, n)
        val opt = ExportOptions(PageSize.A4, Quality.BALANCED, ocr = false)
        val t0 = System.nanoTime()
        vm.share(doc.id, opt, asJpeg = false)
        // How long is the main thread busy right after the tap?
        val m0 = System.nanoTime()
        shadowOf(Looper.getMainLooper()).idle()
        val mainMs = (System.nanoTime() - m0) / 1_000_000
        val result = runBlocking {
            var r: ExportResult? = null
            while (r == null) {
                shadowOf(Looper.getMainLooper()).idle()
                r = kotlinx.coroutines.withTimeoutOrNull(50) { vm.exports.first() }
            }
            r
        }
        val totalMs = (System.nanoTime() - t0) / 1_000_000
        val size = (result as ExportResult.Share).files.first().length()
        return "pages=$n total=${totalMs}ms mainThreadAfterTap=${mainMs}ms size=${size / 1024}KB"
    }

    @Test
    fun shareTimings() {
        val lines = listOf(1, 5, 20).map { timeShare(it) }
        lines.forEach { println("SHARE-TIMING $it") }
        File("../screenshots").mkdirs()
        File("build/share-timings.txt").writeText(lines.joinToString("\n") + "\n")
    }
}
