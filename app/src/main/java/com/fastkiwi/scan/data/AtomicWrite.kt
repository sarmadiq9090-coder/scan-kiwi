package com.fastkiwi.scan.data

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream

/**
 * Writes [target] through a temporary file that is flushed to disk (fsync) and
 * then renamed over the target. A crash, power loss or full disk during the
 * write leaves the previous version intact instead of a half-written file.
 */
fun writeAtomically(target: File, write: (OutputStream) -> Unit) {
    target.parentFile?.mkdirs()
    val tmp = File(target.parentFile, target.name + ".tmp")
    try {
        FileOutputStream(tmp).use { fos ->
            val out = fos.buffered()
            write(out)
            out.flush()
            fos.fd.sync()
        }
        replace(tmp, target)
    } catch (e: Throwable) {
        tmp.delete()
        throw e
    }
}

/** java.nio.file exists on Android 8+ and on any desktop JVM (unit tests). */
private val hasNioFiles: Boolean =
    android.os.Build.VERSION.SDK_INT >= 26 || !System.getProperty("java.vm.name", "").contains("Dalvik", ignoreCase = true)

@android.annotation.SuppressLint("NewApi") // guarded by hasNioFiles
private fun nioReplace(from: File, to: File) {
    java.nio.file.Files.move(from.toPath(), to.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
}

/**
 * Moves [from] over [to]. On Android (Linux) renameTo is an atomic replace. Other
 * hosts (the Windows build PC running the JVM tests) cannot rename over an existing
 * file, or not while a reader has it open, so try an NIO replace and retry briefly.
 */
private fun replace(from: File, to: File) {
    if (from.renameTo(to)) return
    var last: Exception? = null
    repeat(5) { attempt ->
        try {
            if (hasNioFiles) {
                nioReplace(from, to)
                return
            }
            // No java.nio.file before Android 8: plain delete + rename.
            to.delete()
            if (from.renameTo(to)) return
        } catch (e: Exception) {
            last = e
        }
        Thread.sleep(20L * (attempt + 1))
    }
    // Last resort for hosts where an open reader blocks any rename (never needed on
    // Android): overwrite in place. Not atomic, but the data is complete and synced.
    try {
        FileOutputStream(to).use { out ->
            from.inputStream().use { it.copyTo(out) }
            out.fd.sync()
        }
        from.delete()
    } catch (e: IOException) {
        throw IOException("cannot replace ${to.name}", last ?: e)
    }
}
