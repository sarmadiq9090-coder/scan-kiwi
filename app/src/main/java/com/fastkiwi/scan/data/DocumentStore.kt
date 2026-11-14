package com.fastkiwi.scan.data

import com.fastkiwi.scan.core.OcrWord
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

    @Synchronized
    fun save(doc: ScanDocument): ScanDocument {
        val d = dir(doc.id).apply { mkdirs() }
        val updated = doc.copy(modified = System.currentTimeMillis())
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
    }

    /** Copies a page's files into another document under a new id (for merge/duplicate). */
    fun copyPage(fromDoc: String, page: ScanPage, toDoc: String): ScanPage {
        val np = page.copy(id = newId())
        dir(toDoc).mkdirs()
        srcFile(fromDoc, page.id).takeIf { it.exists() }?.copyTo(srcFile(toDoc, np.id), overwrite = true)
        outFile(fromDoc, page.id).takeIf { it.exists() }?.copyTo(outFile(toDoc, np.id), overwrite = true)
        thumbFile(fromDoc, page.id).takeIf { it.exists() }?.copyTo(thumbFile(toDoc, np.id), overwrite = true)
        ocrFile(fromDoc, page.id).takeIf { it.exists() }?.copyTo(ocrFile(toDoc, np.id), overwrite = true)
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

    fun saveOcr(docId: String, page: ScanPage, words: List<OcrWord>) {
        val arr = JSONArray()
        for (w in words) arr.put(JSONArray().put(w.text).put(w.left).put(w.top).put(w.right).put(w.bottom))
        val o = JSONObject().put("revision", page.revision).put("words", arr)
        writeAtomically(ocrFile(docId, page.id)) { it.write(o.toString().toByteArray(Charsets.UTF_8)) }
    }

    /** Cached OCR words if they match the page's current revision. */
    fun loadOcr(docId: String, page: ScanPage): List<OcrWord>? {
        val f = ocrFile(docId, page.id)
        if (!f.exists()) return null
        return runCatching {
            val o = JSONObject(f.readText())
            if (o.optInt("revision", -1) != page.revision) return null
            val arr = o.getJSONArray("words")
            List(arr.length()) {
                val a = arr.getJSONArray(it)
                OcrWord(a.getString(0), a.getInt(1), a.getInt(2), a.getInt(3), a.getInt(4))
            }
        }.getOrNull()
    }

    /** Plain recognised text of a document, for in-app search. */
    fun documentText(doc: ScanDocument): String =
        doc.pages.joinToString(" ") { p -> loadOcr(doc.id, p)?.joinToString(" ") { it.text } ?: "" }

    companion object {
        /** Page files, plus the ".tmp" an interrupted atomic write leaves behind (group 4). */
        private val PAGE_FILE = Regex("p_([0-9a-f]+)(_out|_thumb|_ocr)?\\.(jpg|json)(\\.tmp)?")

        fun newId(): String = UUID.randomUUID().toString().replace("-", "").take(16)
    }
}
