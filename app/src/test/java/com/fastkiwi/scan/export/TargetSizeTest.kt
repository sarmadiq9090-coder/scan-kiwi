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

    /** S3 (torture gate): "1,000" is one thousand, not 1.0; Arabic digits and separators count too. */
    @Test
    fun typedTargetsAreReadInTheSelectedUnit() {
        fun kb(t: String) = TargetSize.parseBytes(t, megabytes = false)
        fun mb(t: String) = TargetSize.parseBytes(t, megabytes = true)
        assertEquals(1_000_000L, kb("1,000"))
        assertEquals(1_000_000L, kb("1٬000"))      // Arabic thousands separator
        assertEquals(1_000_000L, kb("١٬٠٠٠"))
        assertEquals(1_000_000L, kb("١,٠٠٠"))
        assertEquals(1_000_000L, kb("1 000"))
        assertEquals(1_234_567_000L, kb("1,234,567"))
        assertEquals(500_000L, kb("500"))
        assertEquals(500_000L, kb("٥٠٠"))
        assertEquals(1_500_000L, mb("1.5"))
        assertEquals(1_500_000L, mb("1,5"))          // decimal comma
        assertEquals(1_500_000L, mb("١٫٥"))          // Arabic decimal separator
        assertEquals(1_500_000L, mb("١،٥"))
        assertEquals(2_000_000L, mb("2"))
        assertEquals(1_000_000_000L, mb("1,000"))
        assertEquals(1_234_500L, mb("1.2345"))
        assertEquals(1_000_000L, kb("1,000.0"))
        assertEquals(1_250_500L, kb("1,250.5"))
        for (bad in listOf("", "abc", "0", "-5", "NaN", "Infinity", "1e400", "99999999999999")) {
            assertEquals("<$bad>", null, kb(bad))
        }
    }
}
