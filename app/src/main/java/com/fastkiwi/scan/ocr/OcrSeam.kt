// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.ocr

/**
 * 2.0-gates: test seam for text recognition, like ui/CaptureSeam for the camera. The JVM that runs the
 * black-box driver (Robolectric) cannot load Tesseract's or MNN's Android libraries, so OCR was
 * "unavailable" there and search, the text screen, copy, correction and TXT export could not be tried
 * (BLACKBOX-2.0 #2). When [engine] is set, [DefaultOcrEngineFactory] hands it out for every mode;
 * everything after recognize() (cache, search index, text layer, overlay, TXT) is the real code.
 * Always null in the app.
 */
object OcrSeam {
    /** Set by tests only (black-box driver); production code only reads it. */
    @Volatile
    var engine: OcrEngine? = null
}
