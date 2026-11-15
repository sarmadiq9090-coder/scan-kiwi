// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.export

import com.fastkiwi.scan.core.PageSize
import com.fastkiwi.scan.data.DocumentStore
import com.fastkiwi.scan.pdf.PdfValidator
import com.fastkiwi.scan.pdf.TargetSize
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * ROADMAP 1.4.0: target size for 10 synthetic documents × targets 200 KB–5 MB. Either the file
 * is at most the target and the result says "reached", or the result says "not reached" with
 * the true size. Zero false successes: [PdfExportResult.reached] is checked against the file
 * on disk in every run.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class TargetSizeTest {
    private val ctx = RuntimeEnvironment.getApplication()
    private val store = DocumentStore(File(ctx.filesDir, "target-${System.nanoTime()}"))

    @Test
    fun tenDocumentsFiveTargetsNoFalseSuccess() = runBlocking {
        val targets = listOf(200_000L, 500_000L, 1_000_000L, 2_000_000L, 5_000_000L)
        var reached = 0
        var notReached = 0
        var falseSuccess = 0
        val lines = ArrayList<String>()
        val t0 = System.nanoTime()
        for (d in 0 until 10) {
            val pages = listOf(1, 2, 3, 4, 5, 6, 8, 10, 12, 16)[d]
            val doc = ExportSeed.doc(store, "target $d", pages, seed = 40 + d, noise = 0.1f + 0.1f * d, mixBw = d % 2 == 0)
            val exporter = Exporter(ctx, store)
            for (t in targets) {
                val r = exporter.exportPdfResult(doc, ExportOptions(PageSize.A4, targetBytes = t)) { _, _ -> }
                val real = r.file.length()
                assertEquals("reported size is the real size", real, r.bytes)
                PdfValidator.validate(r.file, pages)
                if (r.reached == true) {
                    reached++
                    if (real > t) falseSuccess++
                } else {
                    notReached++
                    assertTrue("not reached only when really over", real > t)
                    // An honest miss: we went down to the smallest settings (or used all passes).
                    assertTrue("doc $d target $t stopped at ${r.settings}", TargetSize.LADDER.indexOf(r.settings) >= TargetSize.LADDER.size - 4)
                }
                lines.add("doc=$d pages=$pages target=${t / 1000}KB size=${real / 1000}KB reached=${r.reached} settings=${r.settings.longSide}px/q${r.settings.jpegQuality}")
            }
        }
        val secs = (System.nanoTime() - t0) / 1_000_000_000
        lines.forEach { println("TARGET $it") }
        println("TARGET SUMMARY runs=50 reached=$reached notReached=$notReached falseSuccess=$falseSuccess time=${secs}s")
        assertEquals(0, falseSuccess)
        assertEquals(50, reached + notReached)
        // Big targets must be reached: a 5 MB target fits every one of these documents.
        assertTrue("reached $reached of 50", reached >= 40)
    }

    @Test
    fun ladderGetsSmaller() = runBlocking {
        val l = TargetSize.LADDER
        for (i in 1 until l.size) assertTrue("$i", l[i].longSide * l[i].longSide * l[i].jpegQuality < l[i - 1].longSide * l[i - 1].longSide * l[i - 1].jpegQuality)
        // Estimates falling along the ladder: the first rung that fits is chosen.
        val est = List(l.size) { 10_000_000L shr it }
        assertEquals(3, TargetSize.choose(10_000_000L shr 3) { est[it] / 105 * 100 })
        assertEquals(l.lastIndex, TargetSize.choose(1) { est[it] })
        assertEquals(null, TargetSize.afterMiss(l.lastIndex, 5, 1) { 1 })
    }
}
