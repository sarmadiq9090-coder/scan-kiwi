// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.core

/**
 * 2.0-cam2 (A8 high-resolution processing): packed opaque ARGB pixels that filters read and write in
 * horizontal strips, so a large page is processed in tiles without a second full-size copy.
 * [IntPixels] wraps an array (tests, small pages); the app wraps the page bitmap itself.
 */
interface PixelStore {
    val width: Int
    val height: Int

    /** Copies rows [y0, y0 + rows) into [dst] from index [offset], row-major, [width] values per row. */
    fun read(y0: Int, rows: Int, dst: IntArray, offset: Int = 0)

    /** Copies rows [y0, y0 + rows) from [src] (from index [offset]) back into the store. */
    fun write(y0: Int, rows: Int, src: IntArray, offset: Int = 0)
}

class IntPixels(val px: IntArray, override val width: Int, override val height: Int) : PixelStore {
    init {
        require(px.size >= width * height) { "pixels ${px.size} < $width x $height" }
    }

    override fun read(y0: Int, rows: Int, dst: IntArray, offset: Int) {
        System.arraycopy(px, y0 * width, dst, offset, rows * width)
    }

    override fun write(y0: Int, rows: Int, src: IntArray, offset: Int) {
        System.arraycopy(src, offset, px, y0 * width, rows * width)
    }
}

/**
 * How filters run. [stripPixels] bounds the working memory: about this many pixels per strip
 * (4 bytes each), whatever the page size. [denoise] enables the automatic noise reduction.
 */
data class FilterOptions(
    val denoise: Boolean = true,
    val stripPixels: Int = 1 shl 19,
) {
    companion object {
        val DEFAULT = FilterOptions()
    }
}
