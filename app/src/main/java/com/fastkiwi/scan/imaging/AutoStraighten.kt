// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.imaging

import com.fastkiwi.scan.core.Deskew
import com.fastkiwi.scan.core.ImageFilters
import com.fastkiwi.scan.core.QualityMetrics
import com.fastkiwi.scan.data.DocumentStore
import com.fastkiwi.scan.data.ScanPage
import kotlin.math.max
import kotlin.math.min

/**
 * 2.0-cam2: automatic document alignment (PRD §4.2) on stored pages. The page outline is turned by
 * [Deskew.straighten], so the change is an ordinary crop edit: Undo, or a re-crop in the crop
 * editor, brings the previous outline back. Runs off the main thread (inside an edit).
 */
object AutoStraighten {

    /**
     * After a capture: the page turned by the skew its quality report measured, with the report's
     * skew set to what is left (0); the page itself when there is nothing to do.
     */
    fun fromReport(store: DocumentStore, docId: String, page: ScanPage): ScanPage {
        val q = page.quality ?: return page
        if (!Deskew.wants(q.skewDegrees)) return page
        val (w, h) = BitmapIO.imageSize(store.srcFile(docId, page.id)) ?: return page
        val turned = Deskew.straighten(page.corners, w, h, q.skewDegrees) ?: return page
        return page.copy(corners = turned, quality = q.copy(skewDegrees = 0f))
    }

    /** The skew of a stored page measured now (any page, imports too), as the capture report does. */
    fun measure(store: DocumentStore, docId: String, page: ScanPage): Float {
        val src = BitmapIO.decodeFile(store.srcFile(docId, page.id), CaptureQuality.SIDE) ?: return 0f
        val w = src.width; val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        src.recycle()
        val quad = page.corners.scale(w.toFloat(), h.toFloat())
        val (ow, oh) = quad.outputSize()
        val k = min(1f, 900f / max(ow, oh))
        val rect = QualityMetrics.rectify(ImageFilters.toGray(px, w, h), quad, max(8, (ow * k).toInt()), max(8, (oh * k).toInt())) ?: return 0f
        return QualityMetrics.skew(rect).degrees
    }

    /** «تسوية الميل» in the page editor: the page turned by its measured skew, or null when it is straight. */
    fun measureAndTurn(store: DocumentStore, docId: String, page: ScanPage): Pair<ScanPage, Float>? {
        val skew = measure(store, docId, page)
        val (w, h) = BitmapIO.imageSize(store.srcFile(docId, page.id)) ?: return null
        val turned = Deskew.straighten(page.corners, w, h, skew) ?: return null
        return page.copy(corners = turned, quality = page.quality?.copy(skewDegrees = 0f)) to skew
    }
}
