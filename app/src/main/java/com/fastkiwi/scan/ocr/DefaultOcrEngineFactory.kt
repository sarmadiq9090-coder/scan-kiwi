// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.ocr

import android.app.ActivityManager
import android.content.Context
import com.fastkiwi.scan.ocr.fused.FusedOcrEngine
import com.fastkiwi.scan.ocr.fused.MnnNative

/** A low-RAM device: the system says so, or it has under 3 GB (roadmap 1.7 definition). */
data class DeviceProfile(val lowRam: Boolean) {
    companion object {
        const val LOW_RAM_BYTES = 3L * 1024 * 1024 * 1024

        fun of(ctx: Context): DeviceProfile {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return DeviceProfile(false)
            val mi = ActivityManager.MemoryInfo()
            runCatching { am.getMemoryInfo(mi) }
            return DeviceProfile(am.isLowRamDevice || (mi.totalMem in 1 until LOW_RAM_BYTES))
        }
    }
}

/**
 * Picks the engine: «دقيق» Accurate (fused) unless the device is low on RAM, the MNN library does not
 * load, or the languages have no Arabic (the Arabic recogniser cannot help an English-only page); in those
 * cases «سريع» Fast (Tesseract), which needs nothing beyond 1.2's engine.
 */
class DefaultOcrEngineFactory(
    private val ctx: Context,
    private val device: () -> DeviceProfile = { DeviceProfile.of(ctx) },
    private val mnnAvailable: () -> Boolean = { MnnNative.tryLoad() },
    private val fast: () -> OcrEngine = { TesseractOcrEngine(ctx) },
    private val accurate: () -> OcrEngine = { FusedOcrEngine(ctx) },
) : OcrEngineFactory {

    override fun create(mode: OcrMode, languages: List<OcrLang>): OcrSelection {
        if (mode == OcrMode.FAST) return OcrSelection(fast(), mode, OcrMode.FAST)
        if (OcrLang.ARABIC !in languages) return fastFor(mode, FallbackReason.ENGLISH_ONLY)
        if (device().lowRam) return fastFor(mode, FallbackReason.LOW_RAM_DEVICE)
        if (!mnnAvailable()) return fastFor(mode, FallbackReason.RUNTIME_MISSING)
        return OcrSelection(accurate(), mode, OcrMode.ACCURATE)
    }

    /** Accurate failed at first use (model missing, MNN init failed): Fast once. */
    override fun fallbackAfter(failed: OcrSelection, error: OcrUnavailable): OcrSelection? {
        if (failed.effective != OcrMode.ACCURATE) return null
        val why = when (error.reason) {
            OcrUnavailable.Reason.MODEL_MISSING -> FallbackReason.MODEL_MISSING
            OcrUnavailable.Reason.NATIVE_LIBRARY -> FallbackReason.RUNTIME_MISSING
            else -> FallbackReason.INIT_FAILED
        }
        return fastFor(failed.requested, why)
    }

    private fun fastFor(requested: OcrMode, why: FallbackReason) = OcrSelection(fast(), requested, OcrMode.FAST, why)
}
