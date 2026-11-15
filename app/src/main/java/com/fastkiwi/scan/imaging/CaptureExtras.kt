// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.imaging

import android.content.Context
import android.graphics.Bitmap
import com.fastkiwi.scan.core.BookSplit
import com.fastkiwi.scan.core.Filter
import com.fastkiwi.scan.core.GlareFusion
import com.fastkiwi.scan.core.ImageFilters
import com.fastkiwi.scan.core.Pt
import com.fastkiwi.scan.core.QualityMetrics
import com.fastkiwi.scan.core.QualityWarning
import com.fastkiwi.scan.core.Quad
import com.fastkiwi.scan.core.QuadDetector
import com.fastkiwi.scan.core.ReadingOrder
import com.fastkiwi.scan.core.Spine
import com.fastkiwi.scan.data.DocumentStore
import com.fastkiwi.scan.data.PageQuality
import com.fastkiwi.scan.data.ScanPage
import java.io.File
import kotlin.math.max
import kotlin.math.min

/** PRD §4.3: the quality report of a captured page, stored in [ScanPage.quality]. */
object CaptureQuality {
    /** Longest side the report is computed at (a sampled copy of the still). */
    const val SIDE = 960

    /** [corners] normalised (0..1), null = the whole photo; [cut] from the detector. */
    fun assess(bmp: Bitmap, corners: Quad?, cut: Boolean): PageQuality {
        val k = min(1f, SIDE.toFloat() / max(bmp.width, bmp.height))
        val small = if (k < 1f) Bitmap.createScaledBitmap(bmp, max(1, (bmp.width * k).toInt()), max(1, (bmp.height * k).toInt()), true) else bmp
        val w = small.width
        val h = small.height
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        if (small !== bmp) small.recycle()
        val r = QualityMetrics.assess(px, w, h, corners?.scale(w.toFloat(), h.toFloat()), cut)
        return PageQuality(r.sharpness, r.completeness, r.lighting, r.glare, r.skewDegrees)
    }
}

/** Warnings of a stored report, most important first; none once the user dismissed them. */
fun PageQuality.warnings(): List<QualityWarning> =
    if (dismissed) emptyList() else QualityMetrics.warnings(sharpness, completeness, lighting, glare, skewDegrees)

/** A photographed two-page spread waiting for the user to check the spine (book mode, A4). */
class BookPending(
    val docId: String,
    /** The photo, kept in the cache until the split is confirmed or dropped. */
    val source: File,
    /** Outline of the spread, normalised in the photo. */
    val spread: Quad,
    /** The rectified spread shown on the split screen. */
    val preview: Bitmap,
    val spine: Spine,
    val order: ReadingOrder,
)

/** Book mode helpers: detection on the still, the split into two pages, the page order setting. */
object BookCapture {

    fun prepare(ctx: Context, store: DocumentStore, docId: String, bmp: Bitmap, spread: Quad?): BookPending {
        val dir = File(ctx.cacheDir, "book").apply { mkdirs() }
        val src = File(dir, "spread_${DocumentStore.newId()}.jpg")
        BitmapIO.saveJpeg(bmp, src, 92)
        val quad = spread ?: BitmapIO.FULL
        val warped = PageRenderer.warp(bmp, quad)
        val preview = BitmapIO.fit(warped, 1400)
        val k = min(1f, 800f / preview.width)
        val g = if (k < 1f) Bitmap.createScaledBitmap(preview, max(1, (preview.width * k).toInt()), max(1, (preview.height * k).toInt()), true) else preview
        val px = IntArray(g.width * g.height)
        g.getPixels(px, 0, g.width, 0, 0, g.width, g.height)
        val gray = ImageFilters.toGray(px, g.width, g.height)
        if (g !== preview) g.recycle()
        val spine = BookSplit.findSpine(gray)
        val saved = order(store, docId)
        val order = saved ?: BookSplit.guessDirection(gray, spine) ?: defaultOrder(ctx)
        return BookPending(docId, src, quad, preview, spine, order)
    }

    /**
     * The two pages in reading order, each a copy of the photo with its own outline (so each half
     * gets its own perspective correction, and re-crop works as for any page).
     */
    suspend fun split(store: DocumentStore, p: BookPending, spine: Spine, order: ReadingOrder, filter: Filter): List<ScanPage> {
        val (left, right) = BookSplit.halves(p.spread, spine) ?: return emptyList()
        val halves = listOf(left, right)
        val src = BitmapIO.decodeFile(p.source)
        val pages = BookSplit.order(order).map { i ->
            val id = DocumentStore.newId()
            p.source.copyTo(store.srcFile(p.docId, id), overwrite = true)
            val quality = src?.let { CaptureQuality.assess(it, halves[i], cut = false) }
            ScanPage(id, halves[i], 0, filter, quality = quality)
        }
        src?.recycle()
        for (page in pages) PageRenderer.renderAndSave(store, p.docId, page)?.recycle()
        return pages
    }

    fun discard(p: BookPending) {
        p.source.delete()
        p.preview.recycle()
    }

    /** Kept in the document's folder, so it goes when the document goes (never a page file). */
    private fun orderFile(store: DocumentStore, docId: String) = File(store.dir(docId), "book_order.txt")

    /** The per-document page order chosen earlier, or null. */
    fun order(store: DocumentStore, docId: String): ReadingOrder? =
        runCatching { ReadingOrder.valueOf(orderFile(store, docId).readText().trim()) }.getOrNull()

    fun setOrder(store: DocumentStore, docId: String, o: ReadingOrder) {
        runCatching { orderFile(store, docId).apply { parentFile?.mkdirs() }.writeText(o.name) }
    }

    /** Without a saved choice or a clear layout: Arabic UI = Arabic books. */
    fun defaultOrder(ctx: Context): ReadingOrder {
        val lang = ctx.resources.configuration.locales[0]?.language ?: "ar"
        return if (lang == "en") ReadingOrder.LTR else ReadingOrder.RTL
    }
}

/** Two-shot glare fusion on stored photos (A4). */
object GlareCapture {
    /** Fusion works on at most this many pixels per photo side (A8 memory budget). */
    const val MAX_SIDE = 2400

    /**
     * Fuses [second] (with its outline [secondQuad], normalised) into the stored source of [page].
     * Returns the fused photo, or null when the two shots could not be matched.
     */
    fun fuse(store: DocumentStore, docId: String, page: ScanPage, second: Bitmap, secondQuad: Quad?): Bitmap? {
        val first = BitmapIO.decodeFile(store.srcFile(docId, page.id), MAX_SIDE) ?: return null
        val aw = first.width; val ah = first.height
        val a = IntArray(aw * ah)
        first.getPixels(a, 0, aw, 0, 0, aw, ah)
        first.recycle()
        val b2 = BitmapIO.fit(second.copy(Bitmap.Config.ARGB_8888, false), MAX_SIDE)
        val bw = b2.width; val bh = b2.height
        val b = IntArray(bw * bh)
        b2.getPixels(b, 0, bw, 0, 0, bw, bh)
        b2.recycle()
        val qa = page.corners.scale(aw.toFloat(), ah.toFloat())
        val qb = (secondQuad ?: detect(b, bw, bh) ?: return null).scale(bw.toFloat(), bh.toFloat())
        val r = GlareFusion.fuse(a, aw, ah, qa, b, bw, bh, qb) ?: return null
        return Bitmap.createBitmap(r.px, aw, ah, Bitmap.Config.ARGB_8888)
    }

    private fun detect(px: IntArray, w: Int, h: Int): Quad? =
        QuadDetector(480).detectBest(listOf({ ImageFilters.toGray(px, w, h) }, { ImageFilters.paperMap(px, w, h) }))
            ?.quad?.map { Pt(it.x / w, it.y / h) }
}
