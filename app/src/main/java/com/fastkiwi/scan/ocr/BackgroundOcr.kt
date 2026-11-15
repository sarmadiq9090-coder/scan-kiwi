// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.ocr

import com.fastkiwi.scan.data.DocumentStore
import com.fastkiwi.scan.imaging.BitmapIO
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Reads the text of new and edited pages in the background, right after capture (roadmap 1.5 item 3),
 * so an export finds every page cached and does not wait. One page at a time, through the shared
 * [OcrService]: an export that starts meanwhile waits for at most the page being read.
 * A result is cached only if the page still has the revision that was read (cached per page revision).
 */
class BackgroundOcr(
    private val store: DocumentStore,
    private val ocr: OcrService,
    scope: CoroutineScope,
    /** Options for a background read, or null when it is switched off. */
    private val options: () -> Pair<OcrOptions, OcrLanguage>?,
    /** Called with the document id after its pages were read (search text may have changed). */
    private val onDocumentRead: (String) -> Unit = {},
) {
    private val queue = Channel<String>(Channel.UNLIMITED)

    init {
        scope.launch(Dispatchers.Default) {
            for (docId in queue) {
                try {
                    if (readDocument(docId)) onDocumentRead(docId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: OutOfMemoryError) {
                    // Low memory: give up on this document; an export reads it later.
                } catch (e: Exception) {
                    // OCR unavailable or the document changed under us: the export path handles it.
                }
            }
        }
    }

    /** Asks for the document's unread pages to be read. Cheap; repeated requests are fine. */
    fun enqueue(docId: String) {
        queue.trySend(docId)
    }

    /** Reads the pages of [docId] that have no cached text from the current engine. True if any was read. */
    suspend fun readDocument(docId: String): Boolean {
        val (opts, lang) = options() ?: return false
        val doc = store.load(docId) ?: return false
        var any = false
        for (page in doc.pages) {
            val sel = ocr.selectionFor(opts) ?: return any
            if (store.loadOcrFrame(docId, page, lang.code, sel.engine.key) != null) continue
            val f = store.outFile(docId, page.id)
            if (!f.exists()) continue
            val bmp = BitmapIO.decodeFile(f, opts.maxSide) ?: continue
            val input = try { OcrInput.fromBitmap(bmp, opts.maxSide) } finally { bmp.recycle() }
            val r = try {
                ocr.recognize(input, opts)
            } catch (e: OcrUnavailable) {
                return any
            }
            // Only if the page was not edited (new revision) or deleted while it was being read.
            val now = store.load(docId)?.pages?.firstOrNull { it.id == page.id } ?: continue
            if (now.revision != page.revision) continue
            store.saveOcr(docId, page, r, lang.code, opts.maxSide)
            any = true
        }
        return any
    }
}
