// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import com.fastkiwi.scan.R
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max

/** Where page numbers go (PDF-09). "Start/end" follow the page, not the UI direction. */
enum class NumberPosition { BOTTOM_CENTER, BOTTOM_LEFT, BOTTOM_RIGHT, TOP_CENTER, TOP_LEFT, TOP_RIGHT }

/** PDF-09: [arabicDigits] = «١٢٣» (Arabic-Indic), else «123»; numbering starts at [start]. */
data class PageNumbering(
    val position: NumberPosition = NumberPosition.BOTTOM_CENTER,
    val arabicDigits: Boolean = true,
    val start: Int = 1,
    /** «٣ / ١٠» instead of «٣». */
    val withTotal: Boolean = false,
) {
    fun label(index: Int, total: Int): String {
        val n = digits(start + index)
        return if (withTotal) n + " / " + digits(start + total - 1) else n
    }

    fun digits(n: Int): String {
        val s = n.toString()
        return if (!arabicDigits) s else buildString { for (c in s) append(if (c in '0'..'9') '٠' + (c - '0') else c) }
    }
}

/** PDF-10: a simple text watermark, [opacity] 0..1, across the page diagonal or horizontal. */
data class Watermark(val text: String, val opacity: Float = 0.25f, val diagonal: Boolean = true) {
    val isEmpty: Boolean get() = text.isBlank()
}

/**
 * Draws page numbers and the watermark into the page image before it is encoded.
 *
 * Why raster and not PDF text with an embedded font subset: Android's own text stack (HarfBuzz
 * shaping, Unicode bidi, font fallback) shapes Arabic correctly — joining forms, lam-alef,
 * mixed Arabic/Latin/digits — which a hand-written PDF font subsetter would have to reimplement
 * (GSUB lookups) and get wrong for some text. Pixels add no font to the file, keep PDF/A-2b valid
 * without any font rules, work the same with AES encryption, and cost a few bytes of JPEG. The
 * price: the numbers and watermark are not selectable text (the OCR text layer is separate and
 * unaffected), which for a watermark is arguably a feature. Font: the bundled Noto Sans Arabic
 * Bold (OFL-1.1), with the system fallback for other scripts.
 */
class PageDecor(private val typeface: Typeface) {

    fun isActive(numbers: PageNumbering?, watermark: Watermark?): Boolean = numbers != null || (watermark != null && !watermark.isEmpty)

    /**
     * Draws on [bmp] (must be mutable). [bitonal]: the page becomes 1-bit later, so everything is
     * drawn in solid black (the watermark as an outline, so it stays light).
     */
    fun draw(bmp: Bitmap, index: Int, total: Int, numbers: PageNumbering?, watermark: Watermark?, bitonal: Boolean) {
        val canvas = Canvas(bmp)
        val w = bmp.width.toFloat()
        val h = bmp.height.toFloat()
        val longSide = max(w, h)
        if (watermark != null && !watermark.isEmpty) drawWatermark(canvas, w, h, watermark, bitonal)
        if (numbers != null) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = this@PageDecor.typeface
                textSize = longSide * NUMBER_SIZE
                color = Color.BLACK
                textAlign = Paint.Align.CENTER
            }
            val label = numbers.label(index, total)
            val tw = p.measureText(label)
            val margin = longSide * NUMBER_MARGIN
            val x = when (numbers.position) {
                NumberPosition.BOTTOM_LEFT, NumberPosition.TOP_LEFT -> margin + tw / 2
                NumberPosition.BOTTOM_RIGHT, NumberPosition.TOP_RIGHT -> w - margin - tw / 2
                else -> w / 2
            }
            val fm = p.fontMetrics
            val y = when (numbers.position) {
                NumberPosition.TOP_CENTER, NumberPosition.TOP_LEFT, NumberPosition.TOP_RIGHT -> margin - fm.ascent
                else -> h - margin - fm.descent
            }
            if (!bitonal) {
                // A white halo keeps the number readable on dark page edges.
                val halo = Paint(p).apply { style = Paint.Style.STROKE; strokeWidth = p.textSize * 0.18f; color = Color.WHITE }
                canvas.drawText(label, x, y, halo)
            }
            canvas.drawText(label, x, y, p)
        }
    }

    private fun drawWatermark(canvas: Canvas, w: Float, h: Float, wm: Watermark, bitonal: Boolean) {
        val text = wm.text.trim()
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = this@PageDecor.typeface
            textAlign = Paint.Align.CENTER
            textSize = 100f
        }
        val span = if (wm.diagonal) hypot(w, h) * 0.7f else w * 0.8f
        val natural = p.measureText(text).coerceAtLeast(1f)
        p.textSize = (100f * span / natural).coerceAtMost(max(w, h) * 0.2f)
        if (bitonal) {
            p.style = Paint.Style.STROKE
            p.strokeWidth = (p.textSize * 0.03f).coerceAtLeast(2f)
            p.color = Color.BLACK
        } else {
            p.color = Color.argb((wm.opacity.coerceIn(0.05f, 1f) * 255).toInt(), 128, 128, 128)
        }
        canvas.save()
        canvas.translate(w / 2, h / 2)
        if (wm.diagonal) canvas.rotate(-Math.toDegrees(atan2(h.toDouble(), w.toDouble())).toFloat())
        val fm = p.fontMetrics
        canvas.drawText(text, 0f, -(fm.ascent + fm.descent) / 2, p)
        canvas.restore()
    }

    companion object {
        /** Number height as a share of the image's long side (about 11 pt on an A4 page). */
        const val NUMBER_SIZE = 0.016f
        const val NUMBER_MARGIN = 0.03f

        fun load(ctx: Context): PageDecor {
            val tf = runCatching { ResourcesCompat.getFont(ctx, R.font.noto_sans_arabic_bold) }.getOrNull() ?: Typeface.DEFAULT_BOLD
            return PageDecor(tf)
        }
    }
}
