package com.fastkiwi.scan.imaging

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import com.fastkiwi.scan.core.ImageFilters
import com.fastkiwi.scan.core.Pt
import com.fastkiwi.scan.core.Quad
import com.fastkiwi.scan.core.QuadDetector
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max

object BitmapIO {
    /** Longest side kept for page sources (~270 dpi on an A4 page). */
    const val MAX_SOURCE_SIDE = 3200

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
        val rotation = runCatching {
            cr.openInputStream(uri)?.use {
                when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270
                    else -> 0
                }
            } ?: 0
        }.getOrDefault(0)
        return rotate(fit(bmp, maxSide), rotation)
    }

    private fun sampleSize(w: Int, h: Int, maxSide: Int): Int {
        var s = 1
        while (max(w, h) / (s * 2) >= maxSide) s *= 2
        return s
    }

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

    fun saveJpeg(bmp: Bitmap, file: File, quality: Int) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(tmp).use { bmp.compress(Bitmap.CompressFormat.JPEG, quality, it) }
        if (!tmp.renameTo(file)) {
            file.delete(); tmp.renameTo(file)
        }
    }

    /** Finds the page outline in a bitmap; returns normalised corners or null. */
    fun detect(bmp: Bitmap): Quad? {
        val k = 480f / max(bmp.width, bmp.height)
        val small = if (k < 1f) Bitmap.createScaledBitmap(bmp, max(1, (bmp.width * k).toInt()), max(1, (bmp.height * k).toInt()), true) else bmp
        val px = IntArray(small.width * small.height)
        small.getPixels(px, 0, small.width, 0, 0, small.width, small.height)
        val det = QuadDetector(workSize = 480).detect(ImageFilters.toGray(px, small.width, small.height))
        val w = small.width.toFloat()
        val h = small.height.toFloat()
        if (small !== bmp) small.recycle()
        return det?.quad?.map { Pt(it.x / w, it.y / h) }
    }

    val FULL: Quad = Quad.full(1f, 1f)
}
