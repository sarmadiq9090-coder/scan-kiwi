// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.data

import com.fastkiwi.scan.core.OcrWord
import com.fastkiwi.scan.ocr.OcrEngineKey
import com.fastkiwi.scan.ocr.OcrResult
import com.fastkiwi.scan.ocr.OcrResultCodec
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Stores documents privately inside the app's own storage
 * (filesDir/documents/<id>/). Nothing is written to shared storage or the
 * network; the user exports explicitly via share sheet or Storage Access Framework.
 */
class DocumentStore(root: File) {
    private val base = File(root, "documents").apply { mkdirs() }

    fun dir(docId: String) = File(base, docId)
    fun srcFile(docId: String, pageId: String) = File(dir(docId), "p_${pageId}.jpg")
    fun outFile(docId: String, pageId: String) = File(dir(docId), "p_${pageId}_out.jpg")
    fun thumbFile(docId: String, pageId: String) = File(dir(docId), "p_${pageId}_thumb.jpg")
    private fun ocrFile(docId: String, pageId: String) = File(dir(docId), "p_${pageId}_ocr.json")
    private fun metaFile(docId: String) = File(dir(docId), "doc.json")
    /** The user's text corrections for a page (OCR-10, see [TextOverlay]). */
    private fun textFile(docId: String, pageId: String) = File(dir(docId), "p_${pageId}_text.json")

    @Synchronized
    fun list(): List<ScanDocument> =
        (base.listFiles() ?: emptyArray())
            .filter { it.isDirectory }
            .mapNotNull { runCatching { load(it.name) }.getOrNull() }
            .sortedByDescending { it.modified }

    @Synchronized
    fun load(id: String): ScanDocument? {
        val f = metaFile(id)
        if (!f.exists()) return null
        return ScanDocument.fromJson(JSONObject(f.readText()))
    }

    /**
     * Saves [doc]. [touch] = false keeps its modified time: starring or filing a document into a
     * folder is not an edit and must not move it to the top of "Recent".
     */
    @Synchronized
    fun save(doc: ScanDocument, touch: Boolean = true): ScanDocument {
        val d = dir(doc.id).apply { mkdirs() }
        val updated = if (touch) doc.copy(modified = System.currentTimeMillis()) else doc
        // Atomic and synced: a crash never leaves a half-written doc.json (which
        // would hide the whole document).
        writeAtomically(File(d, "doc.json")) { it.write(updated.toJson().toString().toByteArray(Charsets.UTF_8)) }
        return updated
    }

    fun create(name: String): ScanDocument {
        val now = System.currentTimeMillis()
        return save(ScanDocument(newId(), name, now, now, emptyList()))
    }

    @Synchronized
    fun delete(id: String) {
        dir(id).deleteRecursively()
    }

    fun deletePageFiles(docId: String, pageId: String) {
        srcFile(docId, pageId).delete()
        outFile(docId, pageId).delete()
        thumbFile(docId, pageId).delete()
        ocrFile(docId, pageId).delete()
        textFile(docId, pageId).delete()
    }

    /** Copies a page's files into another document under a new id (for merge/duplicate). */
    fun copyPage(fromDoc: String, page: ScanPage, toDoc: String): ScanPage {
        val np = page.copy(id = newId())
        dir(toDoc).mkdirs()
        srcFile(fromDoc, page.id).takeIf { it.exists() }?.copyTo(srcFile(toDoc, np.id), overwrite = true)
        outFile(fromDoc, page.id).takeIf { it.exists() }?.copyTo(outFile(toDoc, np.id), overwrite = true)
        thumbFile(fromDoc, page.id).takeIf { it.exists() }?.copyTo(thumbFile(toDoc, np.id), overwrite = true)
        ocrFile(fromDoc, page.id).takeIf { it.exists() }?.copyTo(ocrFile(toDoc, np.id), overwrite = true)
        textFile(fromDoc, page.id).takeIf { it.exists() }?.copyTo(textFile(toDoc, np.id), overwrite = true)
        return np
    }

    /**
     * Deletes page files that no document references any more (pages deleted
     * or retaken in an earlier session; kept until now so Undo could restore them)
     * and temporary files left by an interrupted write.
     *
     * Only files last modified before [olderThan] are touched: a page that an
     * import or capture started in this session is writing (not yet listed in
     * doc.json) must never be taken for garbage.
     */
    @Synchronized
    fun collectGarbage(olderThan: Long = Long.MAX_VALUE): Int {
        var removed = 0
        for (dir in base.listFiles() ?: emptyArray()) {
            if (!dir.isDirectory) continue
            val doc = runCatching { load(dir.name) }.getOrNull() ?: continue
            val live = doc.pages.map { it.id }.toSet()
            for (f in dir.listFiles() ?: emptyArray()) {
                if (f.lastModified() >= olderThan) continue
                if (f.name == "doc.json.tmp") { if (f.delete()) removed++; continue }
                val m = PAGE_FILE.matchEntire(f.name) ?: continue
                val leftover = m.groupValues[4].isNotEmpty()
                if ((leftover || m.groupValues[1] !in live) && f.delete()) removed++
            }
        }
        return removed
    }

    /** The user's own signature (transparent PNG), kept only inside the app. */
    val signatureFile = File(root, "signature/signature.png")

    // --------------------------------------------------------------- OCR cache
    // 2.0-ocr: format 2 (engine id/version, lines, confidences) via OcrResultCodec; 1.2 files stay readable.

    /**
     * Caches recognised words (1.2 format). [frameWidth] x [frameHeight] is the size of the image the
     * boxes are measured in; without it a later export cannot place them (see [loadOcrFrame]).
     */
    fun saveOcr(docId: String, page: ScanPage, words: List<OcrWord>, lang: String? = null, frameWidth: Int = 0, frameHeight: Int = 0) {
        val arr = JSONArray()
        for (w in words) arr.put(JSONArray().put(w.text).put(w.left).put(w.top).put(w.right).put(w.bottom))
        val o = JSONObject().put("revision", page.revision).put("words", arr)
        if (lang != null) o.put("lang", lang)
        if (frameWidth > 0 && frameHeight > 0) o.put("frame", JSONArray().put(frameWidth).put(frameHeight))
        writeAtomically(ocrFile(docId, page.id)) { it.write(o.toString().toByteArray(Charsets.UTF_8)) }
    }

    /** Caches a whole OCR result (format 2: engine, lines, confidences; boxes in the result's frame). */
    fun saveOcr(docId: String, page: ScanPage, result: OcrResult, lang: String, maxSide: Int) {
        val json = OcrResultCodec.encode(page.revision, result, lang, maxSide)
        writeAtomically(ocrFile(docId, page.id)) { it.write(json.toByteArray(Charsets.UTF_8)) }
    }

    /** The cache file as stored, or null (missing, unreadable, or another revision of the page). */
    private fun readOcr(docId: String, page: ScanPage): OcrResultCodec.Decoded? {
        val f = ocrFile(docId, page.id)
        if (!f.exists()) return null
        val d = runCatching { OcrResultCodec.decode(f.readText()) }.getOrNull() ?: return null
        return d.takeIf { it.revision == page.revision }
    }

    /** Cached words with the size of the image their boxes are measured in. */
    class FramedOcr(val words: List<OcrWord>, val frameWidth: Int, val frameHeight: Int)

    /**
     * Cached OCR for an export: as [loadOcr] with a language (and engine), and only if the cache recorded
     * its coordinate frame. Caches from 1.1.x and early 1.2.0 builds did not, and their boxes
     * could be in any frame from 1700 to 3200 px, so an export reads such a page again
     * rather than misplace the text layer.
     */
    fun loadOcrFrame(docId: String, page: ScanPage, lang: String, engine: OcrEngineKey? = null): FramedOcr? {
        val words = loadOcr(docId, page, lang, engine) ?: return null
        val d = readOcr(docId, page) ?: return null
        if (d.frameWidth <= 0 || d.frameHeight <= 0) return null
        return FramedOcr(words, d.frameWidth, d.frameHeight)
    }

    /**
     * Cached OCR words in reading order, if they match the page's current revision and, when [lang] is
     * given (an export), the OCR language too: words read as English only must not stand in for an
     * Arabic + English export. When [engine] is given, only that engine's words (an Accurate export
     * does not reuse Fast words). Search passes neither and uses any cached text, including 1.1.x caches.
     * This is the one place cached words are read.
     */
    fun loadOcr(docId: String, page: ScanPage, lang: String? = null, engine: OcrEngineKey? = null, corrected: Boolean = true /* 2.0-docs */): List<OcrWord>? {
        val d = readOcr(docId, page) ?: return null
        if (lang != null && d.lang != lang) return null
        if (engine != null && d.engine != engine) return null
        return if (corrected) applyTextEdits(docId, page, d.words) else d.words // 2.0-docs: OCR-10 overlay
    }

    // ------------------------------------------------- text corrections (2.0-docs, OCR-10)

    /** The page's correction overlay, whether or not it still applies. */
    fun loadTextOverlay(docId: String, pageId: String): TextOverlay? {
        val f = textFile(docId, pageId)
        if (!f.exists()) return null
        return runCatching { TextOverlay.fromJson(JSONObject(f.readText())) }.getOrNull()
    }

    /** Stores (or with null / no edits removes) a page's corrections; atomic, like doc.json. */
    fun saveTextOverlay(docId: String, pageId: String, overlay: TextOverlay?) {
        val f = textFile(docId, pageId)
        if (overlay == null || overlay.edits.isEmpty()) { f.delete(); return }
        writeAtomically(f) { it.write(overlay.toJson().toString().toByteArray(Charsets.UTF_8)) }
    }

    /** [words] (as cached) with the page's corrections, when they were made on exactly these words. */
    private fun applyTextEdits(docId: String, page: ScanPage, words: List<OcrWord>): List<OcrWord> {
        val ov = loadTextOverlay(docId, page.id) ?: return words
        return if (ov.appliesTo(page.revision, words)) ov.apply(words) else words
    }

    /**
     * A page's text for viewing and correcting: the cached words as recognised ([base]) and with
     * the corrections ([corrected]). Null when the page has no text for its current revision.
     */
    class PageWords(val base: List<OcrWord>, val corrected: List<OcrWord>, val overlay: TextOverlay?)

    fun pageWords(docId: String, page: ScanPage): PageWords? {
        val base = loadOcr(docId, page, corrected = false) ?: return null
        val ov = loadTextOverlay(docId, page.id)?.takeIf { it.appliesTo(page.revision, base) }
        return PageWords(base, ov?.apply(base) ?: base, ov)
    }

    /**
     * Size (w, h) of the image the cached boxes are measured in, any language; null for caches
     * that did not record it (1.1.x, early 1.2.0), whose boxes cannot be placed on the page.
     */
    fun ocrFrame(docId: String, page: ScanPage): Pair<Int, Int>? {
        val d = readOcr(docId, page) ?: return null
        return (d.frameWidth to d.frameHeight).takeIf { it.first > 0 && it.second > 0 }
    }

    /** Changes whenever anything the search index reads for this page changes (revision, OCR, corrections). */
    fun searchStamp(docId: String, page: ScanPage): String {
        val o = ocrFile(docId, page.id)
        val t = textFile(docId, page.id)
        return "${page.revision}:${o.lastModified()}:${o.length()}:${t.lastModified()}:${t.length()}"
    }

    /** Bytes the document takes on disk (pages and caches), for "sort by size". */
    fun sizeOnDisk(docId: String): Long = (dir(docId).listFiles() ?: emptyArray()).sumOf { if (it.isFile) it.length() else 0L }

    /** Plain recognised text of a document, for in-app search. */
    fun documentText(doc: ScanDocument): String =
        doc.pages.joinToString(" ") { p -> loadOcr(doc.id, p)?.joinToString(" ") { it.text } ?: "" }

    companion object {
        /** Page files, plus the ".tmp" an interrupted atomic write leaves behind (group 4). */
        private val PAGE_FILE = Regex("p_([0-9a-f]+)(_out|_thumb|_ocr|_text)?\\.(jpg|json)(\\.tmp)?")

        fun newId(): String = UUID.randomUUID().toString().replace("-", "").take(16)
    }
}
