// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.ocr

import android.graphics.Bitmap
import com.fastkiwi.scan.core.ImageFilters
import kotlin.math.max

/**
 * The luminance plane OCR reads, from a page bitmap: scaled to at most [maxSide] (the 1.2 call,
 * createScaledBitmap with filtering), then luma. Boxes come back in [page]'s pixels. Does not recycle [page].
 */
fun OcrInput.Companion.fromBitmap(page: Bitmap, maxSide: Int = OcrOptions.DEFAULT_MAX_SIDE): OcrInput {
    val k = maxSide.toFloat() / max(page.width, page.height)
    val bmp = if (k < 1f) Bitmap.createScaledBitmap(page, (page.width * k).toInt(), (page.height * k).toInt(), true) else page
    try {
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        val luma = ImageFilters.toGray(px, bmp.width, bmp.height)
        return OcrInput(luma.data, bmp.width, bmp.height, page.width, page.height)
    } finally {
        if (bmp !== page) bmp.recycle()
    }
}
