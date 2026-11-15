// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.imaging

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import com.fastkiwi.scan.core.Detection
import com.fastkiwi.scan.core.ExifOrientation
import com.fastkiwi.scan.core.ImageFilters
import com.fastkiwi.scan.core.Pt
import com.fastkiwi.scan.core.Quad
import com.fastkiwi.scan.core.QuadDetector
import com.fastkiwi.scan.data.writeAtomically
import java.io.File
import java.io.IOException
import kotlin.math.max

object BitmapIO {
    /**
     * Longest side kept for page sources: 3200 px (~270 dpi on A4). Lowered to
     * 2400 px (~205 dpi, still sharp for text) on low-memory phones; see [configureForDevice].
     */
    @Volatile
    var MAX_SOURCE_SIDE = 3200
        private set

    /** Picks the source size for this phone: low-RAM devices or small heaps use 2400 px. */
    fun configureForDevice(ctx: Context) {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager ?: return
        // largeHeap is on, so largeMemoryClass is our real budget (MB).
        val small = am.isLowRamDevice || am.largeMemoryClass < 256
        MAX_SOURCE_SIDE = if (small) 2400 else 3200
    }

    // 2.0-perf: the source size follows the one budget (MemoryBudget: low-memory mode or a small heap).
    fun configureForBudget() {
        MAX_SOURCE_SIDE = com.fastkiwi.scan.perf.MemoryBudget.sourceSide()
    }

    /**
     * 2.0-perf: one mutable bitmap that consecutive decodes of the same size reuse (`inBitmap`), so an
     * export of n pages allocates its page bitmap once instead of n times (on Android 7 page pixels live
     * on the Java heap, where each new 30 MB bitmap forces a GC). Not thread-safe: one per export.
     */
    class Pool : AutoCloseable {
        private var spare: Bitmap? = null

        internal fun take(minBytes: Int): Bitmap? {
            val b = spare ?: return null
            return if (!b.isRecycled && b.isMutable && b.allocationByteCount >= minBytes) { spare = null; b } else null
        }

        /** Hands [bmp] back for the next decode (keeps the larger one), or recycles it. */
        fun give(bmp: Bitmap) {
            if (bmp.isRecycled) return
            val cur = spare
            if (!bmp.isMutable || bmp.config != Bitmap.Config.ARGB_8888) { bmp.recycle(); return }
            if (cur == null) { spare = bmp; return }
            if (cur === bmp) return
            if (bmp.allocationByteCount > cur.allocationByteCount) { cur.recycle(); spare = bmp } else bmp.recycle()
        }

        override fun close() { spare?.recycle(); spare = null }
    }

    /**
     * 2.0-perf: as [decodeFile], decoding into [pool]'s spare bitmap when it is large enough. The
     * result is mutable; give it back to [pool] (not recycle) when done. A sampled decode still larger
     * than [maxSide] is scaled down as before (that scaled bitmap is new, the decoded one goes back).
     */
    fun decodeFile(file: File, maxSide: Int, pool: Pool): Bitmap? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, opts)
        if (opts.outWidth <= 0) return null
        val s = sampleSize(opts.outWidth, opts.outHeight, maxSide)
        val need = ((opts.outWidth + s - 1) / s) * ((opts.outHeight + s - 1) / s) * 4
        fun options(reuse: Bitmap?) = BitmapFactory.Options().apply {
            inSampleSize = s
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inMutable = true
            inBitmap = reuse
        }
        val reuse = pool.take(need)
        val bmp = (if (reuse != null) runCatching { BitmapFactory.decodeFile(file.absolutePath, options(reuse)) }.getOrNull() else null)
            ?: run { reuse?.let { pool.give(it) }; BitmapFactory.decodeFile(file.absolutePath, options(null)) }
            ?: return null
        if (max(bmp.width, bmp.height) <= maxSide) return bmp
        val k = maxSide.toFloat() / max(bmp.width, bmp.height)
        val out = Bitmap.createScaledBitmap(bmp, max(1, (bmp.width * k).toInt()), max(1, (bmp.height * k).toInt()), true)
        pool.give(bmp)
        return out
    }

    fun decodeFile(file: File, maxSide: Int = MAX_SOURCE_SIDE): Bitmap? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, opts)
        if (opts.outWidth <= 0) return null
        val o2 = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(opts.outWidth, opts.outHeight, maxSide)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bmp = BitmapFactory.decodeFile(file.absolutePath, o2) ?: return null
        return fit(bmp, maxSide)
    }

    fun decodeBytes(bytes: ByteArray, rotation: Int, maxSide: Int = MAX_SOURCE_SIDE): Bitmap? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        if (opts.outWidth <= 0) return null
        val o2 = BitmapFactory.Options().apply { inSampleSize = sampleSize(opts.outWidth, opts.outHeight, maxSide) }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o2) ?: return null
        return rotate(fit(bmp, maxSide), rotation)
    }

    /** Decodes an image picked through the system picker, honouring EXIF orientation. */
    fun decodeUri(ctx: Context, uri: Uri, maxSide: Int = MAX_SOURCE_SIDE): Bitmap? {
        val cr = ctx.contentResolver
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        if (opts.outWidth <= 0) return null
        val o2 = BitmapFactory.Options().apply { inSampleSize = sampleSize(opts.outWidth, opts.outHeight, maxSide) }
        val bmp = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o2) } ?: return null
        // All 8 EXIF orientations (rotations and mirrors) are baked into the stored
        // page source, so crop corners always live in upright image space.
        val orientation = runCatching {
            cr.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            } ?: ExifInterface.ORIENTATION_NORMAL
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        return applyExif(fit(bmp, maxSide), ExifOrientation.transform(orientation))
    }

    /**
     * Power-of-two sampling for the decode. 2.0 (A8): the decoded intermediate also stays within
     * [MAX_DECODE_PIXELS], so 48-200 MP photos never allocate a full-size bitmap.
     */
    internal fun sampleSize(w: Int, h: Int, maxSide: Int): Int {
        var s = 1
        while (max(w, h) / (s * 2) >= maxSide) s *= 2
        // 2.0-cam: high-resolution processing within the memory budget (A8).
        while ((w.toLong() / s) * (h.toLong() / s) > MAX_DECODE_PIXELS) s *= 2
        return s
    }

    /** Largest bitmap a decode may allocate before scaling: 13 MP (a 12.5 MP sensor still decodes whole). */
    const val MAX_DECODE_PIXELS = com.fastkiwi.scan.perf.MemoryBudget.MAX_DECODE_PIXELS // 2.0-perf: one place

    fun fit(bmp: Bitmap, maxSide: Int): Bitmap {
        val m = max(bmp.width, bmp.height)
        if (m <= maxSide) return bmp
        val k = maxSide.toFloat() / m
        val out = Bitmap.createScaledBitmap(bmp, max(1, (bmp.width * k).toInt()), max(1, (bmp.height * k).toInt()), true)
        if (out !== bmp) bmp.recycle()
        return out
    }

    fun rotate(bmp: Bitmap, degrees: Int): Bitmap {
        val d = ((degrees % 360) + 360) % 360
        if (d == 0) return bmp
        val m = Matrix().apply { postRotate(d.toFloat()) }
        val out = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        if (out !== bmp) bmp.recycle()
        return out
    }

    /**
     * Saves atomically. compress() reports a failed write (full disk, I/O error)
     * only through its result; ignoring it used to rename a truncated JPEG over
     * the good page source.
     */
    fun saveJpeg(bmp: Bitmap, file: File, quality: Int) = save(bmp, file, Bitmap.CompressFormat.JPEG, quality)

    private fun save(bmp: Bitmap, file: File, format: Bitmap.CompressFormat, quality: Int) {
        writeAtomically(file) { out ->
            if (!bmp.compress(format, quality, out)) throw IOException("could not write ${file.name}")
        }
    }

    /** Pixel size of an image file without decoding it. */
    fun imageSize(file: File): Pair<Int, Int>? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, o)
        return if (o.outWidth > 0 && o.outHeight > 0) o.outWidth to o.outHeight else null
    }

    /** Decodes a PNG keeping transparency (used for the signature). */
    fun decodePng(file: File): Bitmap? =
        if (file.exists()) BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }) else null

    fun savePng(bmp: Bitmap, file: File) = save(bmp, file, Bitmap.CompressFormat.PNG, 100)

    /** Finds the page outline in a bitmap; returns normalised corners or null. */
    fun detect(bmp: Bitmap): Quad? = detectPage(bmp)?.quad

    /** Page outline (normalised 0..1 corners) with its confidence, or null. */
    fun detectPage(bmp: Bitmap): Detection? {
        val k = 480f / max(bmp.width, bmp.height)
        val small = if (k < 1f) Bitmap.createScaledBitmap(bmp, max(1, (bmp.width * k).toInt()), max(1, (bmp.height * k).toInt()), true) else bmp
        val px = IntArray(small.width * small.height)
        small.getPixels(px, 0, small.width, 0, 0, small.width, small.height)
        val sw = small.width
        val sh = small.height
        val det = QuadDetector(workSize = 480).detectBest(
            listOf({ ImageFilters.toGray(px, sw, sh) }, { ImageFilters.paperMap(px, sw, sh) }),
        )
        val w = small.width.toFloat()
        val h = small.height.toFloat()
        if (small !== bmp) small.recycle()
        return det?.let { it.copy(quad = it.quad.map { p -> Pt(p.x / w, p.y / h) }) }
    }

    /** Applies an EXIF orientation (rotate clockwise, then mirror) so the image is upright. */
    fun applyExif(bmp: Bitmap, t: ExifOrientation.Transform): Bitmap {
        if (t.rotation == 0 && !t.flipHorizontal) return bmp
        val m = Matrix()
        m.postRotate(t.rotation.toFloat())
        if (t.flipHorizontal) m.postScale(-1f, 1f)
        val out = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        if (out !== bmp) bmp.recycle()
        return out
    }

    val FULL: Quad = Quad.full(1f, 1f)
}
