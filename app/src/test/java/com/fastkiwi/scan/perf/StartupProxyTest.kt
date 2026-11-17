// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.perf

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import com.fastkiwi.scan.MainActivity
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Cold-start PROXY (ROADMAP 1.7.0 item 3): time from creating MainActivity to its first laid-out
 * Compose frame of Home, on the JVM under Robolectric. This is not the phone number: no zygote, no
 * ART/JIT/Baseline Profile, a desktop CPU, and Robolectric's own overhead. It only catches big
 * regressions in what the app does before its first frame. The ≤ 1.0 s target is the owner's
 * phone check.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "ar-w400dp-h860dp-xxhdpi")
class StartupProxyTest {

    private fun composeLaidOut(v: View): Boolean {
        if (v.javaClass.name.contains("AndroidComposeView") && v.width > 0 && v.height > 0) return true
        if (v is ViewGroup) for (i in 0 until v.childCount) if (composeLaidOut(v.getChildAt(i))) return true
        return false
    }

    private fun startOnce(): Long {
        val t0 = System.nanoTime()
        val c = Robolectric.buildActivity(MainActivity::class.java).setup()
        val decor = c.get().window.decorView
        var frames = 0
        while (!composeLaidOut(decor) && frames < 200) {
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(16))
            frames++
        }
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("Home was laid out", composeLaidOut(decor))
        c.pause().stop().destroy()
        return ms
    }

    @Test
    fun startToFirstFrameProxy() {
        val first = startOnce() // includes loading Compose and app classes into this JVM
        val warm = (0 until 3).map { startOnce() }
        val line = "start-to-first-frame PROXY (Robolectric JVM, not a phone): first ${first} ms (class loading), warm ${warm.joinToString("/")} ms, median ${warm.sorted()[1]} ms"
        println("PERF-START $line")
        File("build/perf-heap.txt").appendText(line + "\n")
        // A regression guard only (desktop JVM): warm start well under 3 s.
        assertTrue(line, warm.sorted()[1] < 3_000)
    }
}
