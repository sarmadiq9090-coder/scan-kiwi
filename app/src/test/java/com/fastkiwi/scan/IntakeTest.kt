package com.fastkiwi.scan

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import com.fastkiwi.scan.core.ExifOrientation
import com.fastkiwi.scan.export.Exporter
import com.fastkiwi.scan.imaging.BitmapIO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Input that reaches the app from outside: shared URIs, file names, photo orientation. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class IntakeTest {
    private val own = "com.fastkiwi.scan.files"

    /**
     * Regression (1.1.1): any app may send "Share to Kiwi Scan". A file:// URI or one
     * of our own FileProvider URIs made the app read its own private files (the
     * signature image, other exports) into a new document.
     */
    @Test
    fun onlyForeignContentUrisAreAccepted() {
        assertTrue(isAcceptableIncoming(Uri.parse("content://com.android.providers.media.documents/document/image%3A12"), own))
        assertFalse(isAcceptableIncoming(Uri.parse("file:///data/data/com.fastkiwi.scan/files/signature/signature.png"), own))
        assertFalse(isAcceptableIncoming(Uri.parse("file:///sdcard/../data/data/com.fastkiwi.scan/files/documents/x/doc.json"), own))
        assertFalse(isAcceptableIncoming(Uri.parse("content://com.fastkiwi.scan.files/exports/x.pdf"), own))
        assertFalse(isAcceptableIncoming(Uri.parse("content://10@com.fastkiwi.scan.files/exports/x.pdf"), own))
        assertFalse(isAcceptableIncoming(Uri.parse("/data/data/com.fastkiwi.scan/files/signature/signature.png"), own))
        assertFalse(isAcceptableIncoming(Uri.parse("http://example.com/a.jpg"), own))
    }

    @Test
    fun exportFileNamesAreSafe() {
        val ex = Exporter(RuntimeEnvironment.getApplication(), com.fastkiwi.scan.data.DocumentStore(RuntimeEnvironment.getApplication().filesDir))
        assertEquals("a_b_c_d_e_f_g_h_i", ex.safeName("a/b\\c:d*e?f\"g<h>i"))
        assertEquals("_.._etc_passwd", ex.safeName("/../etc/passwd").let { it }) // no path separators survive
        assertEquals("scan", ex.safeName("  ...  "))
        assertEquals("scan", ex.safeName(""))
        assertEquals("فاتورة الكهرباء", ex.safeName("فاتورة الكهرباء"))
        // 79 letters + an emoji: cutting at 80 chars must not leave half the emoji.
        val cut = ex.safeName("a".repeat(79) + "📄")
        assertEquals("a".repeat(79), cut)
        assertTrue(ex.safeName("x".repeat(500)).length <= 80)
    }

    /** All 8 EXIF orientations, on real pixels through android.graphics.Matrix. */
    @Test
    fun exifOrientationsUprightThePhoto() {
        val rw = 5
        val rh = 3
        for (o in 1..8) {
            val raw = Bitmap.createBitmap(rw, rh, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.WHITE)
                setPixel(3, 1, Color.RED)
            }
            val t = ExifOrientation.transform(o)
            val up = BitmapIO.applyExif(raw, t)
            // Reference: rotate the marked pixel clockwise, then mirror.
            var cw = rw; var ch = rh; var x = 3; var y = 1
            repeat(t.rotation / 90) {
                val nx = ch - 1 - y
                y = x; x = nx
                val tmp = cw; cw = ch; ch = tmp
            }
            if (t.flipHorizontal) x = cw - 1 - x
            assertEquals("orientation $o width", cw, up.width)
            assertEquals("orientation $o height", ch, up.height)
            val p = up.getPixel(x, y)
            assertTrue("orientation $o: marker not at ($x,$y)", Color.red(p) > 200 && Color.green(p) < 80)
        }
    }
}
