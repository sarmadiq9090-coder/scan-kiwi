// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.gates

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.io.File

/**
 * The interactive black-box session (tools/blackbox/bb.sh). Runs only when the
 * environment variable BB_SESSION names a session folder; skipped in the normal suite.
 *
 * Protocol: the driver writes `in/NNN.cmd` (NNN = 001, 002, ...); the session runs it
 * and writes `out/NNN.png`, `out/NNN.txt` and finally `out/NNN.done`. Step 000 is the
 * start screen. `quit` (or the file `in/stop`) ends the session.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = [36], qualifiers = "ar-w400dp-h860dp-xxhdpi", shadows = [ShadowWorldPdfRenderer::class, ShadowWorldPdfPage::class])
class BlackBoxSession {
    @get:Rule
    val rule = createEmptyComposeRule()

    @Test(timeout = 0)
    fun session() {
        val root = System.getenv("BB_SESSION")?.takeIf { it.isNotBlank() }
        Assume.assumeTrue("interactive session: start it with tools/blackbox/bb.sh start", root != null)
        val dir = File(root!!)
        val input = File(dir, "in").apply { mkdirs() }
        val out = File(dir, "out").apply { mkdirs() }
        val log = File(dir, "session.log")
        fun note(s: String) = log.appendText("${java.time.LocalTime.now()} $s\n")
        val lang = System.getenv("BB_LANG")?.takeIf { it == "en" } ?: "ar"
        val bb = BlackBox(rule, out)
        note("starting (language $lang)")
        bb.start(lang) // step 000: the start screen
        note("ready")
        var next = 1
        val idleSince = longArrayOf(System.currentTimeMillis())
        val maxIdleMs = (System.getenv("BB_IDLE_MINUTES")?.toLongOrNull() ?: 120L) * 60_000
        try {
            while (!bb.quit) {
                if (File(input, "stop").exists()) break
                val f = File(input, String.format(java.util.Locale.ROOT, "%03d.cmd", next))
                if (!f.exists()) {
                    if (System.currentTimeMillis() - idleSince[0] > maxIdleMs) { note("idle timeout"); break }
                    org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                    Thread.sleep(80)
                    continue
                }
                val line = f.readText(Charsets.UTF_8).trim()
                note("cmd $next: $line")
                val step = bb.execute(line)
                note("done $next: ${if (step.ok) "ok" else "failed"}")
                next++
                idleSince[0] = System.currentTimeMillis()
            }
        } finally {
            note("session ended")
            File(dir, "ended").writeText("ok")
            bb.close()
        }
    }
}
