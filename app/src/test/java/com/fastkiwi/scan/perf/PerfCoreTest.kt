// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.perf

import android.graphics.Bitmap
import android.os.StrictMode
import com.fastkiwi.scan.core.ImageFilters
import com.fastkiwi.scan.imaging.BitmapIO
import com.fastkiwi.scan.ocr.OcrEngineFactory
import com.fastkiwi.scan.ocr.OcrInput
import com.fastkiwi.scan.ocr.OcrLang
import com.fastkiwi.scan.ocr.OcrOptions
import com.fastkiwi.scan.ocr.OcrService
import com.fastkiwi.scan.ocr.fromBitmap
import com.fastkiwi.scan.pdf.EncodeSettings
import com.fastkiwi.scan.ui.AppViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class PerfCoreTest {
    private val ctx = RuntimeEnvironment.getApplication()

    @After
    fun normalMode() = MemoryBudget.setForTest(false)

    private fun record(line: String) {
        println("PERF-CORE $line")
        File("build/perf-heap.txt").appendText(line + "\n")
    }

    // ------------------------------------------------------------------ low-memory decision

    @Test
    fun lowMemoryThresholdsAndOverride() {
        val gb = 1024L * 1024 * 1024
        fun dev(lowRam: Boolean, total: Long, cls: Int = 512) = MemoryBudget.Device(lowRam, total, cls)
        val auto = MemoryBudget.Mode.AUTO
        assertTrue("isLowRamDevice", MemoryBudget.isLowMemory(dev(true, 8 * gb), auto))
        assertTrue("2 GB phone", MemoryBudget.isLowMemory(dev(false, 2 * gb), auto))
        assertTrue("2.9 GB phone (what a '3 GB' phone often reports is above 2.7)", MemoryBudget.isLowMemory(dev(false, 3 * gb - 1), auto))
        assertFalse("3 GB phone", MemoryBudget.isLowMemory(dev(false, 3 * gb), auto))
        assertFalse("8 GB phone", MemoryBudget.isLowMemory(dev(false, 8 * gb), auto))
        assertFalse("unknown RAM is not low", MemoryBudget.isLowMemory(dev(false, 0), auto))
        assertTrue("forced on", MemoryBudget.isLowMemory(dev(false, 8 * gb), MemoryBudget.Mode.ON))
        assertFalse("forced off", MemoryBudget.isLowMemory(dev(true, 1 * gb), MemoryBudget.Mode.OFF))

        MemoryBudget.configure(dev(false, 2 * gb), auto)
        assertTrue(MemoryBudget.lowMemory)
        assertEquals(2000, MemoryBudget.ocrMaxSide())
        assertEquals(2400, MemoryBudget.sourceSide())
        assertEquals(EncodeSettings(2000, 90, 2000), MemoryBudget.cap(EncodeSettings(3200, 90, 3200)))
        assertEquals(EncodeSettings(1700, 55, 2000), MemoryBudget.cap(EncodeSettings(1700, 55, 2200)))
        MemoryBudget.configure(dev(false, 8 * gb), auto)
        assertFalse(MemoryBudget.lowMemory)
        assertEquals(OcrOptions.DEFAULT_MAX_SIDE, MemoryBudget.ocrMaxSide())
        assertEquals(3200, MemoryBudget.sourceSide())
        assertEquals(EncodeSettings(3200, 90, 3200), MemoryBudget.cap(EncodeSettings(3200, 90, 3200)))
        MemoryBudget.configure(dev(false, 8 * gb, cls = 192), auto)
        assertEquals("small heap keeps 2400 px sources", 2400, MemoryBudget.sourceSide())
        // OCR's own low-RAM rule uses the same threshold.
        assertEquals(MemoryBudget.LOW_RAM_TOTAL_BYTES, com.fastkiwi.scan.ocr.DeviceProfile.LOW_RAM_BYTES)
    }

    /** Settings «وضع الذاكرة المنخفضة» reaches the budget, the source size and the OCR engine choice. */
    @Test
    fun settingsOverrideAppliesAtOnce() {
        val vm = AppViewModel(ctx)
        vm.setLowMemoryMode(MemoryBudget.Mode.ON)
        assertTrue(MemoryBudget.lowMemory)
        assertEquals(2400, BitmapIO.MAX_SOURCE_SIDE)
        assertEquals(MemoryBudget.Mode.ON, com.fastkiwi.scan.ui.Settings(ctx).lowMemoryMode)
        vm.setLowMemoryMode(MemoryBudget.Mode.OFF)
        assertFalse(MemoryBudget.lowMemory)
        vm.setLowMemoryMode(MemoryBudget.Mode.AUTO)
        assertEquals("Robolectric reports no RAM size: automatic means off", false, MemoryBudget.lowMemory)
        val factory = com.fastkiwi.scan.ocr.DefaultOcrEngineFactory(
            ctx, device = { com.fastkiwi.scan.ocr.DeviceProfile(true) }, mnnAvailable = { true },
            fast = { PerfSeed.FakeOcr() }, accurate = { error("Accurate must not load in low-memory mode") },
        )
        val sel = factory.create(com.fastkiwi.scan.ocr.OcrMode.ACCURATE, listOf(OcrLang.ARABIC))
        assertEquals("single worker: Fast", com.fastkiwi.scan.ocr.OcrMode.FAST, sel.effective)
    }

    // ------------------------------------------------------------------ no ARGB copy for OCR

    @Test
    fun grayPlaneInStripsEqualsTheFullCopy() {
        for ((w, h) in listOf(1 to 1, 7 to 3, 65 to 129, 401 to 77, 2400 to 3200)) {
            val bmp = PerfSeed.page(maxOf(w, 400), maxOf(h, 400), w).let { if (w < 400) Bitmap.createScaledBitmap(it, w, h, true) else it }
            val px = IntArray(bmp.width * bmp.height)
            bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
            assertArrayEquals("gray ${bmp.width}x${bmp.height}", ImageFilters.toGray(px, bmp.width, bmp.height).data, PixelStrips.gray(bmp))
            assertArrayEquals("bits ${bmp.width}x${bmp.height}", ImageFilters.packBits(px, bmp.width, bmp.height), PixelStrips.packBits(bmp))
        }
    }

    /** Bytes allocated to give OCR its plane: 1.2's full ARGB copy vs the strips (2400 x 3200 page). */
    @Test
    fun ocrInputAllocatesNoFullArgbCopy() {
        val bmp = PerfSeed.page(2400, 3200, 1)
        fun alloc(block: () -> Unit): Long {
            val a = Jmx.threadAllocatedBytes()
            block()
            return Jmx.threadAllocatedBytes() - a
        }
        repeat(2) { OcrInput.fromBitmap(bmp, 3200); PixelStrips.gray(bmp) } // warm-up
        val old = alloc {
            val px = IntArray(bmp.width * bmp.height)
            bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
            ImageFilters.toGray(px, bmp.width, bmp.height)
        }
        val now = alloc { OcrInput.fromBitmap(bmp, 3200) }
        record("OCR input of a 2400x3200 page: 1.2 path ${MemoryMeter.mb(old)} MB allocated, strips ${MemoryMeter.mb(now)} MB (plane itself ${MemoryMeter.mb(2400L * 3200)} MB)")
        assertTrue("strips allocate the plane + one strip, not a 4-byte copy: $now", now < 2400L * 3200 + 2400L * 4 * MemoryBudget.STRIP_ROWS + 64 * 1024)
        assertTrue(old > 4 * 2400L * 3200)
    }

    // ------------------------------------------------------------------ bitmap reuse

    @Test
    fun poolReusesTheDecodeBitmapAndGivesIdenticalPixels() {
        val dir = File(ctx.cacheDir, "pool-${System.nanoTime()}").apply { mkdirs() }
        val files = (0 until 3).map { i -> File(dir, "p$i.jpg").also { BitmapIO.saveJpeg(PerfSeed.page(2400, 3200, i), it, 92) } }
        BitmapIO.Pool().use { pool ->
            var previous: Bitmap? = null
            for (f in files) {
                val a = BitmapIO.decodeFile(f, 3200, pool)!!
                val b = BitmapIO.decodeFile(f, 3200)!!
                assertEquals(b.width, a.width); assertEquals(b.height, a.height)
                val pa = IntArray(a.width * a.height).also { a.getPixels(it, 0, a.width, 0, 0, a.width, a.height) }
                val pb = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }
                assertArrayEquals("pooled decode = plain decode", pb, pa)
                if (previous != null) assertSame("the same bitmap is reused (inBitmap)", previous, a)
                b.recycle()
                pool.give(a)
                previous = a
            }
            // A decode that must scale returns a new bitmap and keeps the decode one in the pool.
            val scaled = BitmapIO.decodeFile(files[0], 2400, pool)!!
            assertEquals(2400, maxOf(scaled.width, scaled.height))
            assertNotSame(previous, scaled)
            assertSame(previous, BitmapIO.decodeFile(files[1], 3200, pool))
        }
    }

    // ------------------------------------------------------------------ native OCR memory released when idle

    @Test
    fun ocrEngineIsReleasedAfterIdleTime() = runBlocking {
        val fake = PerfSeed.FakeOcr()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val svc = OcrService(OcrEngineFactory.fixed(fake), scope, idleMillis = 300)
        val opts = OcrOptions(listOf(OcrLang.ARABIC))
        svc.recognize(OcrInput(ByteArray(100 * 100) { 0x7F }, 100, 100), opts)
        Thread.sleep(100)
        assertEquals("not released while recent", 0, fake.closed)
        svc.recognize(OcrInput(ByteArray(100 * 100) { 0x7F }, 100, 100), opts) // a new page restarts the timer
        Thread.sleep(150)
        assertEquals(0, fake.closed)
        val t0 = System.currentTimeMillis()
        while (fake.closed == 0 && System.currentTimeMillis() - t0 < 5000) Thread.sleep(20)
        assertEquals("released after the idle time", 1, fake.closed)
        scope.cancel()
        // The app's service uses the one-minute budget.
        val vm = AppViewModel(ctx)
        val f = AppViewModel::class.java.getDeclaredField("ocrService").apply { isAccessible = true }
        val service = f.get(vm)
        val idle = OcrService::class.java.getDeclaredField("idleMillis").apply { isAccessible = true }.getLong(service)
        assertEquals(MemoryBudget.OCR_IDLE_RELEASE_MS, idle)
        assertEquals(60_000L, idle)
    }

    // ------------------------------------------------------------------ StrictMode

    @Test
    fun strictModeOnlyInDebug() {
        StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.LAX)
        StrictMode.setVmPolicy(StrictMode.VmPolicy.LAX)
        assertFalse(DebugStrictMode.install(debug = false))
        assertEquals("release: no thread policy", StrictMode.ThreadPolicy.LAX.toString(), StrictMode.getThreadPolicy().toString())
        assertTrue(DebugStrictMode.install(debug = true))
        val p = StrictMode.getThreadPolicy().toString()
        assertTrue("debug: disk/network detected and logged: $p", p != StrictMode.ThreadPolicy.LAX.toString())
        StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.LAX)
        StrictMode.setVmPolicy(StrictMode.VmPolicy.LAX)
        // The release build never passes true: MainActivity uses BuildConfig.DEBUG.
        val src = File("src/main/java/com/fastkiwi/scan/MainActivity.kt").readText()
        assertTrue(src.contains("DebugStrictMode.install(BuildConfig.DEBUG)"))
    }
}
