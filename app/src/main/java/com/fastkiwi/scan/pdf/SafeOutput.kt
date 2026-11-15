// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.pdf

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream

/** Not enough free space for an export: [needed] bytes (already × 1.2), [available] bytes. */
class NoSpaceException(val needed: Long, val available: Long) : IOException("needs $needed bytes, $available free")

/**
 * PRD §6.3 integrity for every file we produce:
 * 1. a free-space check first (needed × 1.2),
 * 2. writing goes to a `.part` file in app storage,
 * 3. the finished file is validated (re-parse the xref and page count),
 * 4. then it is renamed onto its final name in one atomic step.
 *
 * Originals are never the target: [final] is always a new file in our cache. A cancelled,
 * failed or killed export leaves at most a `.part` file, which nobody can share (the share
 * path only ever sees committed files) and which [sweep] deletes at the next start.
 */
class SafeOutput(
    val final: File,
    /** Seam for tests: wraps the file stream (fault injection). */
    private val openStream: (File) -> OutputStream = { FileOutputStream(it) },
) {
    val part: File = File(final.parentFile, final.name + PART)

    fun checkSpace(neededBytes: Long, freeBytes: Long = usable(final.parentFile)) {
        val needed = (neededBytes * SPACE_FACTOR).toLong()
        if (needed > freeBytes) throw NoSpaceException(needed, freeBytes)
    }

    /** Writes through the `.part` file; on any failure (including cancellation) the part file is removed. */
    inline fun <T> write(block: (OutputStream) -> T): T {
        final.parentFile?.mkdirs()
        try {
            return openPart().use { block(it) }
        } catch (e: Throwable) {
            part.delete()
            throw e
        }
    }

    @PublishedApi internal fun openPart(): OutputStream {
        val raw = openStream(part)
        return object : OutputStream() {
            private val buf = raw.buffered(64 * 1024)
            override fun write(b: Int) = buf.write(b)
            override fun write(b: ByteArray, off: Int, len: Int) = buf.write(b, off, len)
            override fun flush() = buf.flush()
            override fun close() {
                buf.flush()
                (raw as? FileOutputStream)?.fd?.sync()
                buf.close()
            }
        }
    }

    /**
     * Validates the `.part` file and moves it onto [final]. Returns the page count.
     * Throws (and deletes the part file) when the PDF is incomplete or has the wrong page count.
     */
    fun commitPdf(expectedPages: Int?): Int {
        val pages = try {
            PdfValidator.validate(part, expectedPages)
        } catch (e: Exception) {
            part.delete()
            throw e
        }
        commit()
        return pages
    }

    /** Moves the finished `.part` file onto [final] (for non-PDF outputs such as JPEG). */
    fun commit() {
        if (part.renameTo(final)) return
        // Windows hosts (JVM tests) cannot rename over an existing file; Android can.
        final.delete()
        if (!part.renameTo(final)) {
            part.delete()
            throw IOException("cannot write ${final.name}")
        }
    }

    companion object {
        const val PART = ".part"
        const val SPACE_FACTOR = 1.2

        fun usable(dir: File?): Long {
            var d = dir
            while (d != null && !d.exists()) d = d.parentFile
            return d?.usableSpace?.takeIf { it > 0 } ?: Long.MAX_VALUE
        }

        /**
         * Deletes leftovers of exports that never finished (killed process, power loss):
         * every `.part` file under [dirs] last written before [before].
         */
        fun sweep(dirs: List<File>, before: Long): Int {
            var n = 0
            for (d in dirs) d.walkTopDown().maxDepth(3).forEach { f ->
                if (f.isFile && f.name.endsWith(PART) && f.lastModified() < before && f.delete()) n++
            }
            return n
        }
    }
}
