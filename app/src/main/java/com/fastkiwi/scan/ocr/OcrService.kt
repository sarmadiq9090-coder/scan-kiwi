// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.ocr

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The app's one OCR engine at a time, shared by the export and the background reader after capture.
 * - Calls are serialised: an export waits for the page the background reader is on, then finds it cached.
 * - The engine is chosen by [OcrEngineFactory] (Accurate → Fast fallbacks) and created lazily.
 * - When an engine fails at use (OcrUnavailable, or any other error), the factory's fallback is tried
 *   once on the same page; when nothing is left, OCR stays off until [reviveIfDead] (D24).
 * - With an [idleScope], native memory is released [idleMillis] after the last page.
 */
class OcrService(
    private val factory: OcrEngineFactory,
    private val idleScope: CoroutineScope? = null,
    private val idleMillis: Long = 60_000L,
    /** Called after every page read, for the local diagnostics (time and peak native heap). */
    private val onPage: (OcrSelection, Long, Long) -> Unit = { _, _, _ -> },
    private val heapProbe: () -> Long = { 0L },
) : AutoCloseable {
    private val mutex = Mutex()
    private var selection: OcrSelection? = null
    private var selectedFor: Pair<OcrMode, List<OcrLang>>? = null
    private var dead = false
    private var idleJob: Job? = null

    private fun select(options: OcrOptions): OcrSelection? {
        if (dead) return null
        val want = options.mode to options.languages
        selection?.let { if (selectedFor == want) return it }
        selection?.engine?.let { runCatching { it.close() } }
        selection = factory.create(options.mode, options.languages)
        selectedFor = want
        return selection
    }

    /** The engine (and so the cache key) a read with [options] uses now; null when OCR is off. */
    suspend fun selectionFor(options: OcrOptions): OcrSelection? = mutex.withLock { select(options) }

    /**
     * Reads one page. Throws [OcrUnavailable] when no engine can run (the caller exports without text),
     * CancellationException when cancelled, OutOfMemoryError as is.
     */
    suspend fun recognize(page: OcrInput, options: OcrOptions): OcrResult = mutex.withLock {
        idleJob?.cancel()
        try {
            var sel = select(options) ?: throw OcrUnavailable(OcrUnavailable.Reason.INIT_FAILED)
            while (true) {
                val t0 = System.nanoTime()
                val heap0 = runCatching(heapProbe).getOrDefault(0L)
                try {
                    val r = sel.engine.recognize(page, options)
                    val ms = (System.nanoTime() - t0) / 1_000_000
                    val engPeak = (sel.engine as? com.fastkiwi.scan.ocr.fused.FusedOcrEngine)?.lastPeakNativeHeap ?: 0L
                    val peak = maxOf(heap0, engPeak, runCatching(heapProbe).getOrDefault(0L))
                    runCatching { onPage(sel, ms, peak) }
                    return@withLock r
                } catch (e: Throwable) {
                    if (e is OutOfMemoryError || e is CancellationException) throw e
                    val why = e as? OcrUnavailable ?: OcrUnavailable(OcrUnavailable.Reason.INIT_FAILED, e)
                    runCatching { sel.engine.close() }
                    val next = factory.fallbackAfter(sel, why)
                    if (next == null) {
                        selection = null; dead = true
                        throw why
                    }
                    selection = next
                    sel = next
                }
            }
            @Suppress("UNREACHABLE_CODE")
            throw IllegalStateException()
        } finally {
            scheduleIdleRelease()
        }
    }

    private fun scheduleIdleRelease() {
        val scope = idleScope ?: return
        idleJob?.cancel()
        idleJob = scope.launch {
            delay(idleMillis)
            mutex.withLock { release() }
        }
    }

    private fun release() {
        selection?.engine?.let { runCatching { it.close() } }
        selection = null
        selectedFor = null
    }

    /** At the start of an export: after an earlier failure, try the engines again (1.2 did so per export). */
    suspend fun reviveIfDead() = mutex.withLock { if (dead) { release(); dead = false } }

    /** Releases native memory now (when no page is being read). */
    override fun close() {
        idleJob?.cancel()
        if (mutex.tryLock()) {
            try { release() } finally { mutex.unlock() }
        }
    }
}
