// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.ocr

import android.content.Context
import com.fastkiwi.scan.BuildConfig
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File

/**
 * One Tesseract instance (Tesseract4Android, Apache-2.0) with the bundled tessdata_fast models.
 * Not thread-safe except [stop], which may be called from another thread while a read runs.
 */
class TesseractSession(private val ctx: Context) {
    @Volatile private var api: TessBaseAPI? = null
    private var loaded: String? = null

    private fun dataDir(): File {
        val root = File(ctx.filesDir, "ocr")
        val tess = File(root, "tessdata").apply { mkdirs() }
        // Re-copy the bundled models once per app version.
        val marker = File(root, "models.version")
        val version = BuildConfig.VERSION_CODE.toString()
        val upToDate = marker.exists() && marker.readText() == version
        for (name in listOf("ara.traineddata", "eng.traineddata")) {
            val f = File(tess, name)
            if (!upToDate || !f.exists()) {
                val tmp = File(tess, "$name.tmp")
                try {
                    ctx.assets.open("tessdata/$name").use { input -> tmp.outputStream().use { input.copyTo(it) } }
                } catch (e: java.io.IOException) {
                    throw OcrUnavailable(OcrUnavailable.Reason.MODEL_MISSING, e)
                }
                f.delete()
                tmp.renameTo(f)
            }
        }
        marker.writeText(version)
        return root
    }

    /** The engine for [code] ("ara+eng") with page segmentation [psm]. Throws [OcrUnavailable]. */
    fun ensure(code: String, psm: Int): TessBaseAPI {
        api?.let {
            if (loaded == code) { it.setPageSegMode(psm); return it }
        }
        // Drop the old engine first, so a failed init never leaves a recycled one in use.
        api?.recycle()
        api = null
        loaded = null
        val dir = dataDir()
        val a = try {
            TessBaseAPI()
        } catch (e: LinkageError) {
            throw OcrUnavailable(OcrUnavailable.Reason.NATIVE_LIBRARY, e)
        }
        val ok = try {
            a.init(dir.absolutePath, code, TessBaseAPI.OEM_LSTM_ONLY)
        } catch (e: LinkageError) {
            throw OcrUnavailable(OcrUnavailable.Reason.NATIVE_LIBRARY, e)
        } catch (e: RuntimeException) {
            throw OcrUnavailable(OcrUnavailable.Reason.INIT_FAILED, e)
        }
        if (!ok) {
            a.recycle()
            throw OcrUnavailable(OcrUnavailable.Reason.INIT_FAILED)
        }
        a.setPageSegMode(psm)
        api = a
        loaded = code
        return a
    }

    /** Interrupts a running read from another thread. The next [ensure] starts a fresh engine. */
    fun stop() {
        val a = api ?: return
        runCatching { a.stop() }
        stopped = true
    }

    @Volatile private var stopped = false

    /** True after [stop] until [afterRead] recycled the engine. */
    val wasStopped: Boolean get() = stopped

    /** After a stop, recycle so a leftover cancel flag cannot cut the next read short. */
    fun afterRead() {
        if (stopped) { stopped = false; close() }
    }

    fun close() {
        api?.recycle()
        api = null
        loaded = null
    }
}
