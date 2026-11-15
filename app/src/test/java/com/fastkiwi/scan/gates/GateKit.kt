// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.gates

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Looper
import com.fastkiwi.scan.core.Filter
import com.fastkiwi.scan.data.DocumentStore
import com.fastkiwi.scan.data.ScanDocument
import com.fastkiwi.scan.data.ScanPage
import com.fastkiwi.scan.imaging.BitmapIO
import com.fastkiwi.scan.ui.AppViewModel
import com.fastkiwi.scan.ui.ExportResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assume
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue

/** Marker for the torture suite (JUnit category); run with tools/blackbox/torture.sh. */
interface TortureSuite

/** Shared helpers for the release-gate tests. */
object Gates {
    /** The torture suite runs only when asked for: it takes ~15 minutes. */
    val torture: Boolean get() = System.getenv("KIWI_TORTURE") == "1" || System.getProperty("kiwi.torture") == "1"

    fun assumeTorture() = Assume.assumeTrue("torture suite: run tools/blackbox/torture.sh (sets KIWI_TORTURE=1)", torture)

    fun idleMain() = shadowOf(Looper.getMainLooper()).idle()

    /** Keeps the main looper draining until [done] or [ms] pass; returns whether [done] became true. */
    fun pump(ms: Long, done: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            idleMain()
            if (done()) return true
            Thread.sleep(5)
        }
        idleMain()
        return done()
    }

    /** Waits until no background job of [vm] runs. */
    fun awaitIdle(vm: AppViewModel, ms: Long = 60_000): Boolean {
        idleMain()
        return pump(ms) { vm.busy.value == 0 && vm.exportState.value == null }.also { idleMain() }
    }

    /** The next export result (only for a view model without UI collecting its results). */
    fun awaitExport(vm: AppViewModel, ms: Long): ExportResult? = runBlocking {
        val end = System.currentTimeMillis() + ms
        var r: ExportResult? = null
        while (r == null && System.currentTimeMillis() < end) {
            idleMain()
            r = withTimeoutOrNull(20) { vm.exports.first() }
        }
        r
    }

    /** A plain page image (white paper, dark lines) of [w] x [h]. */
    fun pageBitmap(w: Int, h: Int, seed: Int = 0): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        val ink = Paint().apply { color = Color.rgb(30, 30, 40); strokeWidth = maxOf(2f, w / 260f) }
        val step = h / 70f
        for (i in 0 until 60) {
            val y = h * 0.06f + i * step
            c.drawLine(w * 0.08f, y, w * (0.92f - ((i + seed) % 5) * 0.08f), y, ink)
        }
        // A page number block so pages differ (reorder checks look at ids, renders differ anyway).
        c.drawRect(w * 0.08f, h * 0.9f, w * (0.1f + (seed % 10) * 0.05f), h * 0.94f, Paint().apply { color = Color.rgb(30, 90, 160) })
        return bmp
    }

    /**
     * A document of [n] pages made fast: one rendered page whose files are copied
     * [n] times (each copy is its own page with its own files).
     */
    fun seed(store: DocumentStore, n: Int, name: String = "gate $n", w: Int = 1200, h: Int = 1600, filter: Filter = Filter.ENHANCED): ScanDocument {
        var doc = store.create(name)
        val bmp = pageBitmap(w, h)
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

    /**
     * Deletes a folder. On a Windows JVM a file whose stream the native decoder has not
     * closed yet cannot be deleted until it is collected, so retry after a GC.
     */
    fun deleteDir(dir: File): Boolean {
        repeat(10) {
            if (!dir.exists() || dir.deleteRecursively()) return true
            System.gc()
            @Suppress("DEPRECATION") System.runFinalization()
            Thread.sleep(20)
        }
        return !dir.exists()
    }

    /** Deletes every document (Robolectric keeps app files between tests of one class). */
    fun wipe(store: DocumentStore) {
        store.list().forEach { store.delete(it.id) }
        store.dir("x").parentFile?.listFiles()?.forEach { deleteDir(it) }
    }

    /** Integrity of everything on disk: every doc.json parses, every page has its source file. */
    fun integrity(store: DocumentStore): List<String> {
        val problems = ArrayList<String>()
        val base = store.dir("x").parentFile!!
        for (d in base.listFiles() ?: emptyArray()) {
            if (!d.isDirectory) continue
            val meta = File(d, "doc.json")
            if (!meta.exists()) {
                if ((d.listFiles() ?: emptyArray()).any { it.name.endsWith(".jpg") }) problems.add("${d.name}: page files without doc.json")
                continue
            }
            val doc = runCatching { store.load(d.name) }.getOrElse { problems.add("${d.name}: doc.json unreadable: ${it.javaClass.simpleName}"); null } ?: continue
            for (p in doc.pages) {
                if (!store.srcFile(doc.id, p.id).exists()) problems.add("${doc.id}: page ${p.id} has no source image")
            }
        }
        return problems
    }

    /** Collects exceptions that escape on any thread (crashes the app would have). */
    class Uncaught : Thread.UncaughtExceptionHandler {
        val errors = ConcurrentLinkedQueue<Pair<String, Throwable>>()
        private var previous: Thread.UncaughtExceptionHandler? = null

        fun install(): Uncaught {
            previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler(this)
            return this
        }

        fun uninstall() = Thread.setDefaultUncaughtExceptionHandler(previous)

        override fun uncaughtException(t: Thread, e: Throwable) {
            errors.add(t.name to e)
        }
    }

    /** Appends lines to app/build/torture-perf.txt (the test working directory is app/). */
    fun perf(line: String) {
        println("TORTURE-PERF $line")
        File("build").mkdirs()
        File("build/torture-perf.txt").appendText(line + "\n")
    }

    fun ms(t0: Long) = (System.nanoTime() - t0) / 1_000_000

    fun stack(e: Throwable): String = java.io.StringWriter().also { e.printStackTrace(java.io.PrintWriter(it)) }.toString()
}

/**
 * AndroidViewModelFactory keeps the first Application in a static; Robolectric gives each
 * test a new Application but keeps statics, so a later test's activity would get a view
 * model bound to an earlier test's Application and data folder. Call before launching.
 */
fun resetViewModelFactory() {
    androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory::class.java.getDeclaredField("_instance").apply { isAccessible = true }.set(null, null)
}

/**
 * A view model without UI: nobody reads its messages, and its buffered channel fills up
 * after 64 messages, which then blocks every job (busy never returns to 0). In the app the
 * UI collects them; tests that do not read messages themselves call this.
 */
fun drainMessages(vm: com.fastkiwi.scan.ui.AppViewModel): java.util.concurrent.ConcurrentLinkedQueue<com.fastkiwi.scan.ui.UiMessage> {
    val seen = java.util.concurrent.ConcurrentLinkedQueue<com.fastkiwi.scan.ui.UiMessage>()
    val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main.immediate + kotlinx.coroutines.SupervisorJob())
    scope.launch { vm.messages.collect { seen.add(it) } }
    return seen
}
