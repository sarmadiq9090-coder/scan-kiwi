// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.ocr

import android.content.Context
import android.graphics.Bitmap
import com.fastkiwi.scan.core.OcrWord
import kotlinx.coroutines.runBlocking

/**
 * 1.2's blocking OCR seam, kept as a thin adapter over [OcrEngine] for code written against it
 * (2.0 merge: the Documents area's MissingTextReader). New code should use [OcrService] / [OcrEngine].
 */
@Deprecated("Use OcrService or OcrEngine")
interface PageOcr {
    /** Recognises words in [page]; boxes are in [page]'s pixel coordinates. Blocking. */
    fun recognize(page: Bitmap, lang: OcrLanguage): List<OcrWord>

    /** Interrupts a running [recognize] from another thread. */
    fun stop()

    fun close()
}

/**
 * What `OcrEngine(ctx)` meant in 1.2: a blocking page reader, now on the engine Settings asks for
 * ([mode], Accurate by default, with the Fast fallbacks of [DefaultOcrEngineFactory]).
 */
@Suppress("DEPRECATION", "FunctionName")
fun OcrEngine(ctx: Context, mode: OcrMode = OcrMode.ACCURATE): PageOcr = LegacyPageOcr(OcrService(DefaultOcrEngineFactory(ctx)), mode)

@Suppress("DEPRECATION")
internal class LegacyPageOcr(private val service: OcrService, private val mode: OcrMode) : PageOcr {
    @Volatile private var running: Thread? = null

    override fun recognize(page: Bitmap, lang: OcrLanguage): List<OcrWord> {
        val options = OcrOptions(lang.toOcrLangs(), mode)
        running = Thread.currentThread()
        try {
            return runBlocking { service.recognize(OcrInput.fromBitmap(page, options.maxSide), options) }.toPdfWords()
        } finally {
            running = null
        }
    }

    /** runBlocking turns the interrupt into cancellation, which stops the engine (runStoppable). */
    override fun stop() {
        running?.interrupt()
    }

    override fun close() = service.close()
}
