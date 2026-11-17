// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.data

import androidx.annotation.VisibleForTesting
import java.io.File
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream

/**
 * 2.0-gates: test seam for write failures (disk full, I/O error) in the middle of a
 * write. The torture suite can only make a write fail before it starts (a blocked
 * path); with [hook] set, a test fails a write after any number of bytes, e.g.
 * `IoFaults.hook = { f, written -> if (f.name.endsWith(".part") && written > 200_000) throw IOException("ENOSPC") }`.
 * Always null in the app; wrapping costs one null check per write call.
 */
object IoFaults {
    @VisibleForTesting
    @Volatile
    var hook: ((file: File, bytesWrittenSoFar: Long) -> Unit)? = null

    fun wrap(out: OutputStream, file: File): OutputStream {
        if (hook == null) return out
        return object : FilterOutputStream(out) {
            private var n = 0L

            override fun write(b: Int) {
                hook?.invoke(file, n)
                out.write(b)
                n++
            }

            override fun write(b: ByteArray, off: Int, len: Int) {
                hook?.invoke(file, n)
                out.write(b, off, len)
                n += len
            }
        }
    }

    /** What a full disk throws on Android. */
    fun diskFull(): Nothing = throw IOException("write failed: ENOSPC (No space left on device)")
}
