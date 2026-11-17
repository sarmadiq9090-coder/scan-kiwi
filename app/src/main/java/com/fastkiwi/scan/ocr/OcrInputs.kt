// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.ocr

import androidx.core.graphics.scale
import android.graphics.Bitmap
import com.fastkiwi.scan.perf.PixelStrips
import kotlin.math.max

/**
 * The luminance plane OCR reads, from a page bitmap: scaled to at most [maxSide] (the 1.2 call,
 * createScaledBitmap with filtering), then luma. Boxes come back in [page]'s pixels. Does not recycle [page].
 */
fun OcrInput.Companion.fromBitmap(page: Bitmap, maxSide: Int = OcrOptions.DEFAULT_MAX_SIDE): OcrInput {
    val k = maxSide.toFloat() / max(page.width, page.height)
    val bmp = if (k < 1f) page.scale(maxOf(1, (page.width * k).toInt()), maxOf(1, (page.height * k).toInt())) else page
    try {
        // 2.0-perf: the gray plane is read in strips of rows, without a full ARGB IntArray copy
        // (4 bytes per pixel); the bytes equal ImageFilters.toGray on a full getPixels copy.
        val luma = PixelStrips.gray(bmp)
        return OcrInput(luma, bmp.width, bmp.height, page.width, page.height)
    } finally {
        if (bmp !== page) bmp.recycle()
    }
}
