package com.fastkiwi.scan.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageFiltersTest {
    private fun gray(v: Int) = (0xFF shl 24) or (v shl 16) or (v shl 8) or v

    /** Paper lit from the left (bright to dim) with dark text strokes. */
    private fun shadowedPage(w: Int, h: Int): IntArray {
        val px = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val paper = 230 - (x * 130 / w) // strong shadow towards the right
            val isText = y % 20 in 8..10 && x % 40 < 30
            px[y * w + x] = gray(if (isText) paper / 4 else paper)
        }
        return px
    }

    @Test
    fun whitenRemovesShadowButKeepsText() {
        val w = 400; val h = 300
        val px = shadowedPage(w, h)
        ImageFilters.whiten(px, w, h)
        // Paper on both the lit and the shadowed side becomes (near) white.
        val left = px[2 * w + 5] and 0xFF
        val right = px[2 * w + w - 5] and 0xFF
        assertTrue("left paper $left", left >= 245)
        assertTrue("right paper $right", right >= 235)
        // Text stays dark on the shadowed side.
        val textRight = px[9 * w + (w - 40 + 10)] and 0xFF
        assertTrue("text $textRight", textRight < 100)
    }

    @Test
    fun blackWhiteIsBitonalAndReadable() {
        val w = 400; val h = 300
        val px = shadowedPage(w, h)
        ImageFilters.blackWhite(px, w, h)
        assertTrue(ImageFilters.isBitonal(px))
        assertEquals(0xFFFFFF, px[2 * w + w - 5] and 0xFFFFFF) // shadowed paper -> white
        assertEquals(0, px[9 * w + (w - 40 + 10)] and 0xFFFFFF) // text -> black
    }

    @Test
    fun grayscaleStretchesContrast() {
        val w = 100; val h = 100
        val px = IntArray(w * h) { i -> if (i % 2 == 0) gray(100) else gray(150) }
        ImageFilters.grayscale(px, w, h)
        val lo = px.minOf { it and 0xFF }
        val hi = px.maxOf { it and 0xFF }
        assertEquals(0, lo)
        assertEquals(255, hi)
    }

    @Test
    fun packBitsUsesOneForWhite() {
        val px = intArrayOf(gray(255), gray(0), gray(255), gray(0), gray(255), gray(255), gray(255), gray(255), gray(0))
        val bits = ImageFilters.packBits(px, 9, 1)
        assertEquals(2, bits.size)
        assertEquals(0b10101111, bits[0].toInt() and 0xFF)
        assertEquals(0, bits[1].toInt() and 0xFF)
    }

    @Test
    fun allFiltersKeepSizeAndOpacity() {
        val w = 64; val h = 48
        for (f in Filter.entries) {
            val px = shadowedPage(w, h)
            ImageFilters.apply(f, px, w, h)
            assertTrue(px.all { (it ushr 24) == 0xFF })
        }
    }
}
