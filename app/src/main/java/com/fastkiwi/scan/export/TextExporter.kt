// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.export

import com.fastkiwi.scan.core.OcrWord
import com.fastkiwi.scan.data.DocumentStore
import com.fastkiwi.scan.data.PageText
import com.fastkiwi.scan.data.ScanDocument
import com.fastkiwi.scan.data.ScanPage
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File

/**
 * TXT export (OCR-08): the recognised text, with the user's corrections, as UTF-8 (no BOM) in
 * logical order. Each page's text is exactly what [PageText.text] makes of the engine's words:
 * the exporter never reorders, reshapes or adds direction marks. Pages are separated by a blank
 * line and a header line from [pageHeader] (only when the document has more than one page).
 *
 * Pages without cached text are read by [readMissing] when given (it runs OCR and caches the
 * words); a page that still has no text gets its header and an empty body.
 */
class TextExporter(private val store: DocumentStore) {

    fun interface MissingText {
        /** Recognises [page] and caches the words; null when OCR cannot run. Blocking or suspending. */
        suspend fun read(doc: ScanDocument, page: ScanPage): List<OcrWord>?
    }

    /** The whole document as text. [progress] is called with (page, total). */
    suspend fun text(
        doc: ScanDocument,
        pageHeader: (Int) -> String,
        readMissing: MissingText? = null,
        progress: (Int, Int) -> Unit = { _, _ -> },
    ): String {
        val sb = StringBuilder()
        val multi = doc.pages.size > 1
        for ((i, p) in doc.pages.withIndex()) {
            currentCoroutineContext().ensureActive()
            progress(i, doc.pages.size)
            val words = store.loadOcr(doc.id, p) ?: readMissing?.read(doc, p)?.let { store.loadOcr(doc.id, p) ?: it }
            if (multi) {
                if (i > 0) sb.append('\n')
                sb.append(pageHeader(i + 1)).append('\n')
            }
            val body = if (words.isNullOrEmpty()) "" else PageText.text(words)
            sb.append(body)
            if (body.isNotEmpty() || multi) sb.append('\n')
        }
        progress(doc.pages.size, doc.pages.size)
        return sb.toString()
    }

    /** Writes [text] to [dir]/<name>.txt through a temporary file; returns the file. */
    fun write(text: String, dir: File, baseName: String): File {
        dir.mkdirs()
        val out = File(dir, "$baseName.txt")
        val part = File(dir, out.name + ".part")
        try {
            part.writeBytes(text.toByteArray(Charsets.UTF_8))
            if (!part.renameTo(out)) { out.delete(); if (!part.renameTo(out)) throw java.io.IOException("cannot write ${out.name}") }
        } catch (e: Throwable) {
            part.delete()
            throw e
        }
        return out
    }
}
