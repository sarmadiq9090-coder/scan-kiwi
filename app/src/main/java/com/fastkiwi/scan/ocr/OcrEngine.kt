// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.ocr

/**
 * Reads the text on one page image. Implementations: [TesseractOcrEngine] («سريع» Fast) and
 * [com.fastkiwi.scan.ocr.fused.FusedOcrEngine] («دقيق» Accurate). Camera, export and PDF code depend
 * only on this interface (PRD §13).
 *
 * Contract
 * - recognize() may be called from any coroutine; calls on one instance are serialised by the engine,
 *   which picks its own dispatcher for CPU/native work and never blocks the main thread.
 * - Boxes are in page pixels: the frame [OcrInput.pageWidth] x [OcrInput.pageHeight].
 * - Cancellation: cancelling the calling coroutine stops the work as soon as the engine can (Tesseract:
 *   TessBaseAPI.stop(); fused: between lines and inside each Tesseract line read). recognize() then
 *   throws CancellationException and returns no partial result. The instance stays usable.
 * - Environment failures (native library, model, init) throw [OcrUnavailable]. OutOfMemoryError is
 *   never wrapped.
 * - The engine never reads or writes the cache, never logs text, never touches the network.
 * - Creating an engine is cheap: native code and models load on the first recognize().
 */
interface OcrEngine : AutoCloseable {
    /** Stable engine name, part of the cache key: "tesseract" or "pp-fused". */
    val id: String

    /** Bumped whenever the same input may give different output. Part of the cache key. */
    val version: Int

    suspend fun recognize(page: OcrInput, options: OcrOptions): OcrResult

    /** Releases native memory. Idempotent. */
    override fun close()

    companion object {
        /** 1.2's name for the longest side OCR reads ([OcrOptions.DEFAULT_MAX_SIDE]). */
        const val WORK_SIDE = OcrOptions.DEFAULT_MAX_SIDE
    }
}

/** Why the effective mode is not the requested one (shown in Settings → About diagnostics). */
enum class FallbackReason { LOW_RAM_DEVICE, RUNTIME_MISSING, MODEL_MISSING, INIT_FAILED, ENGLISH_ONLY }

/** The engine chosen for a request, and why it is not the requested one. */
data class OcrSelection(
    val engine: OcrEngine,
    val requested: OcrMode,
    val effective: OcrMode,
    val fallback: FallbackReason? = null,
)

interface OcrEngineFactory {
    /** Picks an engine for [mode] and [languages]. Never loads native code beyond a library probe. */
    fun create(mode: OcrMode, languages: List<OcrLang>): OcrSelection

    /** Engine to try after [failed] threw [error], or null (the caller then works without text, D24). */
    fun fallbackAfter(failed: OcrSelection, error: OcrUnavailable): OcrSelection? = null

    companion object {
        /** A factory that always hands out [engine] (tests, and Fast-only paths). */
        fun fixed(engine: OcrEngine): OcrEngineFactory = object : OcrEngineFactory {
            override fun create(mode: OcrMode, languages: List<OcrLang>) = OcrSelection(engine, mode, mode)
        }
    }
}

/** Cache key part that names the engine which produced a cached result. */
data class OcrEngineKey(val id: String, val version: Int) {
    companion object {
        /** 1.0-1.2 caches were all written by the Tesseract engine that is version 1 today. */
        val LEGACY = OcrEngineKey("tesseract", 1)
    }
}

val OcrEngine.key: OcrEngineKey get() = OcrEngineKey(id, version)
