// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.ocr

import kotlin.math.roundToInt

/** OCR languages offered in the export sheet. Tesseract codes; models ship inside the APK (offline). */
enum class OcrLanguage(val code: String) { ARABIC_ENGLISH("ara+eng"), ENGLISH("eng"), ARABIC("ara") }

/** Engine-neutral language. */
enum class OcrLang(val tesseractCode: String) { ARABIC("ara"), ENGLISH("eng") }

fun OcrLanguage.toOcrLangs(): List<OcrLang> = when (this) {
    OcrLanguage.ARABIC_ENGLISH -> listOf(OcrLang.ARABIC, OcrLang.ENGLISH)
    OcrLanguage.ENGLISH -> listOf(OcrLang.ENGLISH)
    OcrLanguage.ARABIC -> listOf(OcrLang.ARABIC)
}

/** «دقيق» Accurate = the fused pipeline (PP-OCR + Tesseract); «سريع» Fast = Tesseract alone. */
enum class OcrMode { ACCURATE, FAST }

data class OcrOptions(
    /** Ordered, primary first, non-empty. Tesseract joins the codes with '+'. */
    val languages: List<OcrLang>,
    val mode: OcrMode = OcrMode.ACCURATE,
    /** Longest side of the plane the engine reads (also the export decode size when OCR runs). */
    val maxSide: Int = DEFAULT_MAX_SIDE,
) {
    init { require(languages.isNotEmpty() && maxSide > 0) }

    val tesseractCode: String get() = languages.joinToString("+") { it.tesseractCode }

    companion object {
        /** About 205 dpi on A4: plenty for Tesseract, and the 1.2 working size. */
        const val DEFAULT_MAX_SIDE = 2400
    }
}

/**
 * One page as an 8-bit luminance plane (0 = black, 255 = white), row-major, stride = width.
 * The plane may be smaller than the page: boxes are returned in page pixels
 * ([pageWidth] x [pageHeight]) using one factor from the widths, like 1.2 did.
 * Engines must not modify [gray] and must not keep it after recognize() returns.
 */
class OcrInput(
    val gray: ByteArray,
    val width: Int,
    val height: Int,
    val pageWidth: Int = width,
    val pageHeight: Int = height,
) {
    init {
        require(width > 0 && height > 0) { "empty page" }
        require(gray.size >= width * height) { "plane too small" }
        require(pageWidth >= width && pageHeight >= height) { "page smaller than plane" }
    }

    val scaleToPage: Float get() = if (pageWidth == width) 1f else pageWidth.toFloat() / width

    companion object
}

enum class TextDirection { LTR, RTL, NEUTRAL }

enum class ScriptHint { ARABIC, LATIN, DIGITS, MIXED, UNKNOWN }

/** Pixel box, right/bottom exclusive (android.graphics.Rect convention). */
data class OcrBox(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top

    fun scaled(kx: Float, ky: Float = kx): OcrBox =
        if (kx == 1f && ky == 1f) this
        else OcrBox((left * kx).roundToInt(), (top * ky).roundToInt(), (right * kx).roundToInt(), (bottom * ky).roundToInt())

    fun union(o: OcrBox) = OcrBox(minOf(left, o.left), minOf(top, o.top), maxOf(right, o.right), maxOf(bottom, o.bottom))
}

/** One recognised word. [text] is in logical (reading) order, no bidi controls. */
data class RecognizedWord(
    val text: String,
    val box: OcrBox,
    /** 0..1. */
    val confidence: Float,
    val direction: TextDirection = TextClassifier.direction(text),
    val script: ScriptHint = TextClassifier.script(text),
)

/**
 * One line in reading order: for an RTL line the first word is the rightmost. A table row (OCR-06)
 * is one line whose words are the cells' words, cell by cell in reading order.
 */
data class RecognizedLine(
    val words: List<RecognizedWord>,
    val box: OcrBox,
    val confidence: Float,
    val direction: TextDirection,
) {
    val text: String get() = words.joinToString(" ") { it.text }
}

data class OcrResult(
    /** Reading order. */
    val lines: List<RecognizedLine>,
    /** The frame of every box (= OcrInput.pageWidth x pageHeight). */
    val frameWidth: Int,
    val frameHeight: Int,
    val engineId: String,
    val engineVersion: Int,
    /** Majority base direction of the page. */
    val direction: TextDirection = TextDirection.RTL,
) {
    /** Flattened reading order. */
    val words: List<RecognizedWord> get() = lines.flatMap { it.words }

    /** Logical-order text: words joined by ' ', lines by '\n'. */
    fun plainText(): String = lines.joinToString("\n") { it.text }

    /** The PDF writer's input type, in reading order. */
    fun toPdfWords(): List<com.fastkiwi.scan.core.OcrWord> = words.map {
        com.fastkiwi.scan.core.OcrWord(it.text, it.box.left, it.box.top, it.box.right, it.box.bottom)
    }

    companion object {
        fun empty(frameWidth: Int, frameHeight: Int, engineId: String, engineVersion: Int) =
            OcrResult(emptyList(), frameWidth, frameHeight, engineId, engineVersion)
    }
}

/** OCR cannot run here. The message never contains document content (D28). */
class OcrUnavailable(val reason: Reason, cause: Throwable? = null) : Exception("OCR unavailable: $reason", cause) {
    enum class Reason { NATIVE_LIBRARY, MODEL_MISSING, INIT_FAILED, LOW_MEMORY_DEVICE, CLOSED }
}
