// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.perf

import java.io.File

/**
 * Measures the memory one operation adds on top of the running test app (ROADMAP 1.7.0 heap tests).
 *
 * Robolectric's `Debug.getNativeHeapAllocatedSize()` returns the Java heap in use (ShadowDebug), and
 * with native graphics the page bitmaps live in real native memory the JVM does not count. So:
 * - **native** = the process's private memory (Windows: commit charge; Linux: RssAnon) minus what the
 *   JVM itself committed (heap + non-heap), sampled every [sampleMs] ms on a background thread; the
 *   peak minus the value at [start] is what the operation's bitmaps and native buffers added. A
 *   calibration bitmap checks the meter can see a 30 MB native allocation, and its release, at all.
 *   Linux needs two settings from the test task (app/build.gradle.kts), checked at [start]: RssAnon
 *   counts touched pages only, so the heap is pre-touched (-XX:+AlwaysPreTouch: committed = resident);
 *   and glibc keeps freed large blocks in its arena once its dynamic mmap threshold has risen, so the
 *   next bitmap would reuse resident memory unseen; GLIBC_TUNABLES pins the threshold (as on Windows,
 *   whose heap maps and unmaps large blocks, and on Android).
 * - **Java** = an upper bound of the live Java heap at any moment: the live heap after a full GC at a
 *   checkpoint (each page) plus everything allocated since that checkpoint (all threads'
 *   allocated-bytes counters). Garbage is never counted as live, and no peak can hide between samples.
 * The total peak is native peak + Java bound; both are deltas from [start] (the app, Robolectric and
 * the seeded document are already loaded then).
 */
class MemoryMeter(private val sampleMs: Long = 5) : AutoCloseable {
    private val statusFile = File("/proc/self/status").takeIf { it.canRead() }

    @Volatile private var running = false
    @Volatile private var nativePeak = 0L
    @Volatile var samples = 0
        private set
    @Volatile var samplerError: Throwable? = null
        private set
    private var sampler: Thread? = null

    private var nativeBase = 0L
    private var javaBase = 0L

    /** Live Java heap after the last checkpoint, and the allocation counter then. */
    private var liveAtCheckpoint = 0L
    private var allocAtCheckpoint = 0L
    var javaPeakBound = 0L
        private set

    var checkpoints = 0
        private set

    /** Debug.getNativeHeapAllocatedSize() as the app sees it (Java heap under Robolectric), peak. */
    var debugNativeHeapPeak = 0L
        private set

    /** Private process memory outside the JVM's own heap/non-heap, bytes. */
    fun nativeNow(): Long {
        val committed = Jmx.heapCommitted() + Jmx.nonHeapCommitted()
        val proc = statusFile?.let { rssAnon(it) } ?: Jmx.committedVirtual() ?: 0L
        return proc - committed
    }

    private fun rssAnon(f: File): Long? =
        f.readLines().firstOrNull { it.startsWith("RssAnon:") }?.split(Regex("\\s+"))?.getOrNull(1)?.toLongOrNull()?.times(1024)

    /** On Linux, fails unless the two settings the RssAnon reading depends on are in effect. */
    private fun checkSetup() {
        if (statusFile == null) return
        val tunables = System.getenv("GLIBC_TUNABLES").orEmpty()
        if ("glibc.malloc.mmap_threshold=" !in tunables) throw AssertionError("memory meter: GLIBC_TUNABLES has no mmap_threshold ('$tunables'); freed bitmaps would stay in the malloc arena unseen")
        if ("-XX:+AlwaysPreTouch" !in Jmx.inputArguments()) throw AssertionError("memory meter: the test JVM needs -XX:+AlwaysPreTouch; untouched heap pages would read as native growth")
    }

    private fun allocated(): Long = Jmx.allocatedBytes()

    private fun gcLive(): Long {
        repeat(2) { System.gc() }
        return Jmx.heapUsed()
    }

    /** Starts measuring: GC, baselines, sampler thread. */
    fun start() {
        checkSetup()
        javaBase = gcLive()
        liveAtCheckpoint = javaBase
        allocAtCheckpoint = allocated()
        javaPeakBound = 0L
        // G1 uncommits freed heap concurrently after a GC (JDK 17+): the JVM reports the smaller heap at
        // once, the OS a moment later, which would inflate a baseline taken right away. Settle, then
        // take the lowest of a few samples. (The test JVM also runs with MaxHeapFreeRatio=100.)
        Thread.sleep(400)
        nativeBase = (0 until 8).minOf { Thread.sleep(25); nativeNow() }
        nativePeak = nativeBase
        running = true
        sampler = Thread {
            while (running) {
                try {
                    val n = nativeNow()
                    if (n > nativePeak) nativePeak = n
                    samples++
                } catch (t: Throwable) { samplerError = t }
                runCatching { android.os.Debug.getNativeHeapAllocatedSize() }.getOrNull()?.let { if (it > debugNativeHeapPeak) debugNativeHeapPeak = it }
                try { Thread.sleep(sampleMs) } catch (e: InterruptedException) { return@Thread }
            }
        }.apply { isDaemon = true; name = "memory-meter"; start() }
    }

    /**
     * A checkpoint (call at each page): the bound since the last checkpoint is closed with the bytes
     * allocated meanwhile, then a full GC gives the live heap the next interval starts from.
     */
    fun checkpoint() {
        val since = allocated() - allocAtCheckpoint
        javaPeakBound = maxOf(javaPeakBound, liveAtCheckpoint - javaBase + since)
        liveAtCheckpoint = gcLive()
        allocAtCheckpoint = allocated()
        javaPeakBound = maxOf(javaPeakBound, liveAtCheckpoint - javaBase)
        checkpoints++
    }

    /** Stops; returns the result. */
    fun stop(): Result {
        checkpoint()
        running = false
        sampler?.join(1000)
        val n = maxOf(nativeNow(), nativePeak)
        samplerError?.let { throw AssertionError("memory sampler failed", it) }
        return Result(maxOf(0L, n - nativeBase), maxOf(0L, javaPeakBound), maxOf(0L, liveAtCheckpoint - javaBase), checkpoints, debugNativeHeapPeak, samples)
    }

    override fun close() { running = false }

    data class Result(val nativePeak: Long, val javaPeakBound: Long, val javaRetainedAfter: Long, val checkpoints: Int, val debugNativeHeapPeak: Long, val samples: Int) {
        val total: Long get() = nativePeak + javaPeakBound
        override fun toString() =
            "total=${mb(total)} MB (native peak ${mb(nativePeak)} MB + Java live bound ${mb(javaPeakBound)} MB), " +
                "Java retained after ${mb(javaRetainedAfter)} MB, checkpoints=$checkpoints, samples=$samples"
    }

    companion object {
        fun mb(b: Long) = String.format(java.util.Locale.ROOT, "%.1f", b / 1048576.0)
    }
}
