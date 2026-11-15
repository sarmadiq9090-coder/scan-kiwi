// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.pdf

import android.content.ComponentName
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Message
import android.os.Messenger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * The main-process client of the `:pdf` process: the real [PdfService] message protocol end to
 * end, a worker that never answers (timeout), and a service that cannot be bound (crash).
 * (Robolectric runs everything in one JVM; the process boundary itself is the manifest's
 * `android:process=":pdf"` and is checked on the phone.)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class IsolatedPdfEngineTest {
    private val app = RuntimeEnvironment.getApplication()
    private val component = ComponentName(app, PdfService::class.java)
    private val dir = File(app.cacheDir, "iso-${System.nanoTime()}").apply { mkdirs() }

    /** Runs [block] off the main thread while the main looper keeps delivering binder callbacks. */
    private fun <T> offMain(block: suspend () -> T): Result<T> {
        val result = AtomicReference<Result<T>?>(null)
        val t = Thread { result.set(runCatching { runBlocking { block() } }) }
        t.start()
        while (result.get() == null) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        return result.get()!!
    }

    @Test
    fun realServiceProtocolEndToEnd() {
        val service = Robolectric.setupService(PdfService::class.java)
        shadowOf(app).setComponentNameAndServiceForBindService(component, service.onBind(null))
        val src = SyntheticPdfs.external(dir, "I", 5, seed = 4)
        val engine = ExternalPdfEngine(IsolatedPdfEngine(app, 30_000, component), File(dir, "out"))
        assertEquals(5, offMain { engine.pageCount(PdfFile(src)) }.getOrThrow())
        val f = offMain { engine.assemble(listOf(PdfFile(src)), PagePlans.select(listOf(4, 0)), "sel.pdf") }.getOrThrow()
        assertEquals(listOf("MARK-I-5", "MARK-I-1"), SyntheticPdfs.markers(f.file))
        // An error inside the worker comes back as a code, not an exception in this process.
        val bad = File(dir, "bad.pdf").apply { writeText("%PDF-1.7 nothing here") }
        val err = offMain { engine.pageCount(PdfFile(bad)) }.exceptionOrNull()
        assertTrue("$err", err is PdfOpException && err.error == PdfError.MALFORMED)
    }

    @Test
    fun hungWorkerTimesOut() {
        val silent = HandlerThread("silent").apply { start() }
        val never = Messenger(object : Handler(silent.looper) { override fun handleMessage(msg: Message) {} })
        shadowOf(app).setComponentNameAndServiceForBindService(component, never.binder)
        val t0 = System.nanoTime()
        val err = offMain { IsolatedPdfEngine(app, 400, component).call(PdfProtocol.request(PdfProtocol.OP_COUNT, listOf(File("x")))) }.exceptionOrNull()
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("$err", err is PdfOpException && err.error == PdfError.TIMEOUT)
        assertTrue("answered after $ms ms", ms in 300..5_000)
        silent.quitSafely()
    }

    @Test
    fun unbindableServiceIsAnErrorNotACrash() {
        shadowOf(app).declareComponentUnbindable(component)
        val err = offMain { IsolatedPdfEngine(app, 5_000, component).call(PdfProtocol.request(PdfProtocol.OP_COUNT, listOf(File("x")))) }.exceptionOrNull()
        assertTrue("$err", err is PdfOpException && err.error == PdfError.CRASHED)
    }
}
