// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.export

import android.content.ContentResolver
import android.net.Uri
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/**
 * 2.0.1: copies a finished export to a place the user picked ("Save as", a folder) and reads it back.
 * The copy counts only when the saved bytes are the file's bytes: same length, same SHA-256. A provider
 * that cannot truncate ("wt") is written with "w" and then caught here if old bytes stayed at the end.
 */
object VerifiedCopy {
    class Mismatch(message: String) : IOException(message)

    fun copy(cr: ContentResolver, file: File, target: Uri) {
        val out = (try { cr.openOutputStream(target, "wt") } catch (e: IllegalArgumentException) { null } catch (e: java.io.FileNotFoundException) { null })
            ?: cr.openOutputStream(target, "w") ?: throw IOException("cannot open the chosen file")
        out.use { o -> file.inputStream().use { it.copyTo(o) } }
        val want = file.inputStream().use { digest(it) }
        val got = (cr.openInputStream(target) ?: throw Mismatch("cannot read the saved file back")).use { digest(it) }
        if (got.first != want.first) throw Mismatch("saved ${got.first} bytes, expected ${want.first}")
        if (!got.second.contentEquals(want.second)) throw Mismatch("the saved file differs from the export")
    }

    /**
     * The last check before a file is handed to another app: it is re-opened and must be complete
     * (a PDF passes [com.fastkiwi.scan.pdf.PdfValidator], a JPEG has its start and end markers, a text
     * file is not empty). Throws [Mismatch] otherwise, so a broken file is never shared.
     */
    fun checkFinished(file: File, mime: String) {
        if (!file.isFile || file.length() == 0L) throw Mismatch("${file.name} is missing or empty")
        when (mime) {
            "application/pdf" -> try {
                com.fastkiwi.scan.pdf.PdfValidator.validate(file)
            } catch (e: com.fastkiwi.scan.pdf.PdfValidator.Invalid) {
                throw Mismatch("${file.name}: ${e.message}")
            }
            "image/jpeg" -> java.io.RandomAccessFile(file, "r").use { f ->
                val head = ByteArray(2).also { f.readFully(it) }
                f.seek(f.length() - 2)
                val tail = ByteArray(2).also { f.readFully(it) }
                val ok = head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte() && tail[0] == 0xFF.toByte() && tail[1] == 0xD9.toByte()
                if (!ok) throw Mismatch("${file.name} is not a complete JPEG")
            }
        }
    }

    /** (length, SHA-256) of a stream. */
    fun digest(input: InputStream): Pair<Long, ByteArray> {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        var n = 0L
        while (true) {
            val r = input.read(buf)
            if (r < 0) break
            md.update(buf, 0, r)
            n += r
        }
        return n to md.digest()
    }
}
