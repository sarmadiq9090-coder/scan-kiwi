// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.ocr.fused

import com.fastkiwi.scan.ocr.OcrUnavailable

/** A model output: values in row-major order and the tensor shape. */
class ModelOutput(val values: FloatArray, val shape: IntArray)

/** Runs one neural network on an NCHW float input. The JVM tests use recorded outputs instead. */
interface ModelRunner : AutoCloseable {
    fun run(input: FloatArray, shape: IntArray): ModelOutput
    override fun close() {}
}

/** The JNI glue (app/src/main/cpp/kiwi_mnn.cpp) over MNN 3.6.2's CPU backend. */
internal object MnnNative {
    @Volatile private var loaded: Boolean? = null

    /**
     * Loads libkiwi_mnn.so (and with it libMNN.so). False when the library is missing or cannot load
     * (a test build without it, an unsupported ABI): the app then uses the Fast mode.
     */
    @Synchronized
    fun tryLoad(): Boolean {
        loaded?.let { return it }
        val ok = try {
            System.loadLibrary("kiwi_mnn"); true
        } catch (e: Throwable) {
            if (e is OutOfMemoryError) throw e
            false
        }
        loaded = ok
        return ok
    }

    @JvmStatic external fun nativeCreate(model: ByteArray, threads: Int): Long
    @JvmStatic external fun nativeRun(handle: Long, input: FloatArray, shape: IntArray, outShape: IntArray): FloatArray?
    @JvmStatic external fun nativeRelease(handle: Long)
}

/** One MNN model held in native memory until [close]. */
class MnnModelRunner(model: ByteArray, threads: Int) : ModelRunner {
    @Volatile private var handle: Long

    init {
        if (!MnnNative.tryLoad()) throw OcrUnavailable(OcrUnavailable.Reason.NATIVE_LIBRARY)
        handle = MnnNative.nativeCreate(model, threads)
        if (handle == 0L) throw OcrUnavailable(OcrUnavailable.Reason.INIT_FAILED)
    }

    override fun run(input: FloatArray, shape: IntArray): ModelOutput {
        val h = handle
        if (h == 0L) throw OcrUnavailable(OcrUnavailable.Reason.CLOSED)
        val outShape = IntArray(4)
        val values = MnnNative.nativeRun(h, input, shape, outShape) ?: throw OcrUnavailable(OcrUnavailable.Reason.INIT_FAILED)
        val rank = outShape.indexOfLast { it != 1 }.coerceAtLeast(0) + 1
        return ModelOutput(values, outShape.copyOf(maxOf(rank, 3)))
    }

    @Synchronized
    override fun close() {
        val h = handle
        handle = 0L
        if (h != 0L) MnnNative.nativeRelease(h)
    }
}
