// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.pdf

import android.os.Bundle
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.random.Random

/**
 * ROADMAP 1.4.0 fuzz gate: 200 generated malformed PDFs (truncated, bad xref, deep recursion,
 * page-tree loops, bogus lengths, flipped bytes) go through the external path — the request
 * handler the `:pdf` process runs, on a worker thread with the service's 64 MB stack — for
 * page count, assemble (all pages) and text. Every file must come back within the 60 s
 * timeout with an error code, or with an output that passes the main-process validator.
 * Nothing may escape as an exception (in the app that would only end the `:pdf` process; here it
 * fails the test).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PdfFuzzTest {
    private val ctx = RuntimeEnvironment.getApplication()
    private val dir = File(ctx.cacheDir, "fuzz-${System.nanoTime()}").apply { mkdirs() }
    private val ops = PdfBoxOps(ctx)

    private val pool: ExecutorService = Executors.newCachedThreadPool { r -> Thread(null, r, "pdf-op", 64L * 1024 * 1024).apply { isDaemon = true } }

    /** Like the service plus [IsolatedPdfEngine]'s timeout: the handler on a big-stack thread, at most 60 s. */
    private val channel = PdfChannel { req ->
        val f = pool.submit(Callable { PdfProtocol.handle(ops, req) })
        val reply: Bundle = try {
            f.get(IsolatedPdfEngine.TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            f.cancel(true); throw PdfOpException(PdfError.TIMEOUT)
        }
        if (!reply.getBoolean(PdfProtocol.K_OK)) throw PdfOpException(PdfError.of(reply.getInt(PdfProtocol.K_ERR)))
        reply
    }

    private fun corpus(): List<Pair<String, ByteArray>> {
        val rnd = Random(2026)
        val bases = listOf(
            SyntheticPdfs.external(dir, "F1", 6, seed = 1).readBytes(), // xref stream + object streams (PDFBox 3)
            SyntheticPdfs.ours(dir, "F2", 3).readBytes(), // classic xref (our writer)
            SyntheticPdfs.external(dir, "F3", 2, seed = 3, password = "pw").readBytes(), // AES-256
        )
        val out = ArrayList<Pair<String, ByteArray>>()
        // 1. Truncated anywhere (40).
        repeat(40) { val b = bases[it % 3]; out.add("truncated" to b.copyOf(1 + rnd.nextInt(b.size - 1))) }
        // 2. Bad xref / startxref (40).
        repeat(40) {
            val b = bases[it % 3].copyOf()
            val s = String(b, Charsets.ISO_8859_1)
            val sx = s.lastIndexOf("startxref")
            when (it % 4) {
                0 -> { // startxref points somewhere else
                    val tail = "startxref\n${rnd.nextInt(b.size)}\n%%EOF\n".toByteArray(Charsets.ISO_8859_1)
                    out.add("bad-startxref" to b.copyOf(sx) + tail)
                }
                1 -> { // garbage over the xref section
                    val xr = s.lastIndexOf("xref", sx - 1).coerceAtLeast(0)
                    for (i in xr until minOf(b.size, xr + 200)) b[i] = rnd.nextInt(256).toByte()
                    out.add("garbled-xref" to b)
                }
                2 -> { // every offset shifted
                    out.add("shifted-offsets" to Regex("(\\d{10}) 00000 n").replace(s) { m -> "%010d 00000 n".format(m.groupValues[1].toLong() + 7 + rnd.nextInt(50)) }.toByteArray(Charsets.ISO_8859_1))
                }
                else -> { // no xref at all
                    out.add("no-xref" to s.replace("xref", "xxxx").replace("startxref", "startxxxx").toByteArray(Charsets.ISO_8859_1))
                }
            }
        }
        // 3. Deep recursion and loops (40).
        repeat(40) {
            val depth = 20_000 + rnd.nextInt(200_000)
            out.add(
                when (it % 5) {
                    0 -> "deep-array" to handmade("[".repeat(depth) + "]".repeat(depth))
                    1 -> "deep-dict" to handmade("<< /A ".repeat(depth) + "1" + " >>".repeat(depth))
                    2 -> "unclosed-deep" to handmade("[".repeat(depth))
                    3 -> "kids-loop" to pageTree("<< /Type /Pages /Kids [2 0 R] /Count 1 >>")
                    else -> "parent-chain" to pageTree("<< /Type /Pages /Kids [4 0 R] /Count ${1 + rnd.nextInt(1_000_000)} >>", extra = "4 0 obj\n<< /Type /Pages /Parent 2 0 R /Kids [2 0 R 4 0 R] /Count 3 >>\nendobj\n")
                },
            )
        }
        // 4. Random byte flips (40).
        repeat(40) {
            val b = bases[it % 3].copyOf()
            repeat(1 + rnd.nextInt(60)) { b[rnd.nextInt(b.size)] = rnd.nextInt(256).toByte() }
            out.add("flipped" to b)
        }
        // 5. Lies about sizes, empty and junk files (40).
        repeat(40) {
            out.add(
                when (it % 4) {
                    0 -> "huge-length" to String(bases[1], Charsets.ISO_8859_1).replaceFirst(Regex("/Length \\d+"), "/Length 99999999999").toByteArray(Charsets.ISO_8859_1)
                    1 -> "junk" to ("%PDF-1.7\n".toByteArray() + ByteArray(rnd.nextInt(5000)) { rnd.nextInt(256).toByte() })
                    2 -> "empty" to ByteArray(rnd.nextInt(10))
                    else -> "bad-objstm" to handmade("<< /Type /ObjStm /N 2147483647 /First -5 /Length 10 >>\nstream\n0123456789\nendstream", stream = true)
                },
            )
        }
        return out
    }

    /** A small PDF whose catalog points at [obj] (object 4 as a page /Contents or plain value). */
    private fun handmade(obj: String, stream: Boolean = false): ByteArray {
        val objs = listOf(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 100 100] /Contents 4 0 R /Resources << >> >>",
            if (stream) obj else "<< /Length 4 /X $obj >>\nstream\nq Q\n\nendstream",
        )
        return assemblePdf(objs)
    }

    private fun pageTree(pages: String, extra: String = ""): ByteArray {
        val objs = listOf("<< /Type /Catalog /Pages 2 0 R >>", pages, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 10 10] >>")
        return assemblePdf(objs, extra)
    }

    private fun assemblePdf(objs: List<String>, extra: String = ""): ByteArray {
        val sb = StringBuilder("%PDF-1.7\n")
        val offs = ArrayList<Int>()
        for ((i, o) in objs.withIndex()) { offs.add(sb.length); sb.append("${i + 1} 0 obj\n$o\nendobj\n") }
        sb.append(extra)
        val x = sb.length
        sb.append("xref\n0 ${objs.size + 1}\n0000000000 65535 f \n")
        for (o in offs) sb.append("%010d 00000 n \n".format(o))
        sb.append("trailer\n<< /Size ${objs.size + 1} /Root 1 0 R >>\nstartxref\n$x\n%%EOF\n")
        return sb.toString().toByteArray(Charsets.ISO_8859_1)
    }

    @Test
    fun twoHundredMalformedPdfsNeverCrashAndAlwaysAnswerInTime() = runBlocking {
        val files = corpus()
        assertEquals(200, files.size)
        val out = File(dir, "out")
        val engine = ExternalPdfEngine(channel, out)
        var errors = 0
        var repaired = 0
        var maxMs = 0L
        val byKind = HashMap<String, IntArray>()
        for ((i, pair) in files.withIndex()) {
            val (kind, bytes) = pair
            val f = File(dir, "fuzz-$i.pdf").apply { writeBytes(bytes) }
            val t0 = System.nanoTime()
            val outcome = try {
                val n = engine.pageCount(PdfFile(f))
                val result = engine.assemble(listOf(PdfFile(f)), PagePlans.merge(listOf(n)), "o$i.pdf")
                PdfValidator.validate(result.file, n) // what the main process checks before sharing
                engine.text(PdfFile(f))
                "ok"
            } catch (e: PdfOpException) {
                "error"
            } catch (e: IllegalArgumentException) {
                "error" // e.g. a page count of 0: nothing to assemble
            }
            val ms = (System.nanoTime() - t0) / 1_000_000
            maxMs = maxOf(maxMs, ms)
            assertTrue("file $i ($kind) took $ms ms", ms < IsolatedPdfEngine.TIMEOUT_MS)
            if (outcome == "ok") repaired++ else errors++
            byKind.getOrPut(kind) { IntArray(2) }[if (outcome == "ok") 0 else 1]++
            assertTrue("no partial output for $i", out.listFiles().orEmpty().none { it.name.endsWith(SafeOutput.PART) })
        }
        println("FUZZ 200 files: $errors errors, $repaired read after repair (output validated), max $maxMs ms")
        println("FUZZ by kind (ok/error): " + byKind.entries.sortedBy { it.key }.joinToString { "${it.key}=${it.value[0]}/${it.value[1]}" })
        assertEquals(200, errors + repaired)
        pool.shutdownNow()
        Unit
    }
}
