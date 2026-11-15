// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.perf

import android.app.ActivityManager
import android.content.Context
import com.fastkiwi.scan.ocr.OcrOptions
import com.fastkiwi.scan.pdf.EncodeSettings

/**
 * 2.0-perf (ROADMAP 1.7.0, PRD §8): every memory threshold of the app in one place.
 *
 * Low-memory mode is on when the system says the phone is a low-RAM device
 * (`ActivityManager.isLowRamDevice`) or the phone has less than [LOW_RAM_TOTAL_BYTES] of RAM,
 * unless the user overrides it in Settings («وضع الذاكرة المنخفضة»: automatic / on / off).
 * In low-memory mode:
 * - OCR reads a plane of at most [LOW_MEMORY_LONG_SIDE] px (normal: [OcrOptions.DEFAULT_MAX_SIDE]);
 * - export pages are at most [LOW_MEMORY_LONG_SIDE] px (colour and black-and-white);
 * - OCR uses a single worker: «سريع» Fast (Tesseract, one thread) instead of the 4-thread fused engine
 *   (AppViewModel hands this mode to `DefaultOcrEngineFactory` as its `DeviceProfile`); pages are read
 *   one at a time anyway (`OcrService` serialises every read, export and background);
 * - page sources are decoded sampled to at most [SOURCE_SIDE_SMALL] px (see [sourceSide]).
 * Always (any phone): decodes are sampled ([MAX_DECODE_PIXELS]), OCR gets its gray plane in strips of
 * [STRIP_ROWS] rows (no full ARGB copy), export reuses one decode bitmap, and the OCR engines release
 * their native memory [OCR_IDLE_RELEASE_MS] after the last page.
 */
object MemoryBudget {
    /** Total RAM below this is a low-memory phone (roadmap 1.7: "< 3 GB"). */
    const val LOW_RAM_TOTAL_BYTES = 3L * 1024 * 1024 * 1024

    /** `largeMemoryClass` (MB, largeHeap is on) below this keeps page sources at [SOURCE_SIDE_SMALL]. */
    const val SMALL_HEAP_MB = 256

    /** OCR plane and export page long side in low-memory mode (about 170 dpi on A4). */
    const val LOW_MEMORY_LONG_SIDE = 2000

    /** Longest side of stored page sources: normal phones / low-memory or small-heap phones. */
    const val SOURCE_SIDE_NORMAL = 3200
    const val SOURCE_SIDE_SMALL = 2400

    /** Largest intermediate bitmap a decode may allocate before scaling (13 MP; A8 "sampled decode above 12 MP"). */
    const val MAX_DECODE_PIXELS = 13_000_000L

    /** Rows read per `getPixels` strip when OCR or the black-and-white export needs the pixels. */
    const val STRIP_ROWS = 64

    /** The OCR engines (native MNN / Tesseract memory) are released this long after the last page. */
    const val OCR_IDLE_RELEASE_MS = 60_000L

    /** The acceptance budgets the heap tests check (ROADMAP 1.7.0), in bytes. */
    const val BUDGET_20_PAGES_OCR = 256L * 1024 * 1024
    const val BUDGET_200_PAGES = 128L * 1024 * 1024

    /** Settings override of the automatic choice. */
    enum class Mode { AUTO, ON, OFF }

    /** What the phone reports; [totalRamBytes] ≤ 0 means unknown. */
    data class Device(val isLowRamDevice: Boolean, val totalRamBytes: Long, val largeMemoryClassMb: Int)

    fun deviceIsLow(d: Device): Boolean = d.isLowRamDevice || (d.totalRamBytes in 1 until LOW_RAM_TOTAL_BYTES)

    fun isLowMemory(d: Device, mode: Mode): Boolean = when (mode) {
        Mode.ON -> true
        Mode.OFF -> false
        Mode.AUTO -> deviceIsLow(d)
    }

    /** Low-memory mode now (set by [configure] at start and when the Settings choice changes). */
    @Volatile
    var lowMemory: Boolean = false
        private set

    /** The phone's own verdict, for the Settings summary ("automatic: on for this phone"). */
    @Volatile
    var deviceLow: Boolean = false
        private set

    @Volatile
    var device: Device = Device(false, 0L, 512)
        private set

    fun readDevice(ctx: Context): Device {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return Device(false, 0L, 512)
        val mi = ActivityManager.MemoryInfo()
        runCatching { am.getMemoryInfo(mi) }
        return Device(am.isLowRamDevice, mi.totalMem, am.largeMemoryClass)
    }

    fun configure(ctx: Context, mode: Mode) = configure(readDevice(ctx), mode)

    fun configure(d: Device, mode: Mode) {
        device = d
        deviceLow = deviceIsLow(d)
        lowMemory = isLowMemory(d, mode)
    }

    /** Tests: force the mode without a Context. */
    fun setForTest(low: Boolean) { lowMemory = low; deviceLow = low }

    /** Longest side of stored page sources. */
    fun sourceSide(d: Device = device, low: Boolean = lowMemory): Int =
        if (low || d.largeMemoryClassMb < SMALL_HEAP_MB) SOURCE_SIDE_SMALL else SOURCE_SIDE_NORMAL

    /** Longest side of the plane OCR reads. */
    fun ocrMaxSide(low: Boolean = lowMemory): Int = if (low) LOW_MEMORY_LONG_SIDE else OcrOptions.DEFAULT_MAX_SIDE

    /** Export settings within the budget: in low-memory mode no page side exceeds [LOW_MEMORY_LONG_SIDE]. */
    fun cap(s: EncodeSettings, low: Boolean = lowMemory): EncodeSettings =
        if (!low) s else EncodeSettings(
            minOf(s.longSide, LOW_MEMORY_LONG_SIDE), s.jpegQuality, minOf(s.bitonalLongSide, LOW_MEMORY_LONG_SIDE),
        )

    /** A JPEG-export long side within the budget. */
    fun capSide(side: Int, low: Boolean = lowMemory): Int = if (low) minOf(side, LOW_MEMORY_LONG_SIDE) else side
}
