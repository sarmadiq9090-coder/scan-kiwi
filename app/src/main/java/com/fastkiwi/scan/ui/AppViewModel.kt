package com.fastkiwi.scan.ui

import android.app.Application
import android.content.ContentResolver
import android.graphics.Bitmap
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fastkiwi.scan.R
import com.fastkiwi.scan.core.Filter
import com.fastkiwi.scan.core.Quad
import com.fastkiwi.scan.data.DocumentStore
import com.fastkiwi.scan.data.ScanDocument
import com.fastkiwi.scan.data.ScanPage
import com.fastkiwi.scan.export.ExportOptions
import com.fastkiwi.scan.export.Exporter
import com.fastkiwi.scan.imaging.BitmapIO
import com.fastkiwi.scan.imaging.IdCardComposer
import com.fastkiwi.scan.imaging.PageRenderer
import com.fastkiwi.scan.imaging.PdfImporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed interface Screen {
    data object Home : Screen
    data class Camera(val docId: String?, val retakePageId: String? = null, val idCard: Boolean = false, val captured: Int = 0) : Screen
    data class Editor(val docId: String) : Screen
    data class Page(val docId: String, val index: Int) : Screen
    data class Crop(val docId: String, val pageId: String) : Screen
    data class Sign(val docId: String, val pageId: String) : Screen
    data object Settings : Screen
}

data class DocEntry(val doc: ScanDocument, val text: String)

/** A user-visible message: string resource + optional argument. */
data class UiMessage(val res: Int, val arg: String? = null, val undoDocId: String? = null)

/** Outcome of one camera shot. [needsReview]: the outline is doubtful, open the crop editor. */
data class CaptureResult(val docId: String, val pageId: String?, val needsReview: Boolean)

/** What to do with an export once the file(s) are ready. */
sealed interface ExportResult {
    data class Share(val files: List<File>, val mime: String) : ExportResult
    data class Saved(val name: String, val bytes: Long) : ExportResult
}

class AppViewModel(app: Application) : AndroidViewModel(app) {
    val store = DocumentStore(app.filesDir)
    val settings = Settings(app)
    private val exporter = Exporter(app, store)

    val backStack = mutableStateListOf<Screen>(Screen.Home)
    val screen: Screen get() = backStack.last()

    private val _docs = MutableStateFlow<List<DocEntry>>(emptyList())
    val docs: StateFlow<List<DocEntry>> = _docs

    /** Number of long-running jobs; > 0 shows a progress indicator. */
    val busy = mutableStateOf(0)
    val progress = mutableStateOf<Pair<Int, Int>?>(null)

    private val _messages = Channel<UiMessage>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()
    private val _exports = Channel<ExportResult>(Channel.BUFFERED)
    val exports = _exports.receiveAsFlow()

    /** Front side kept while waiting for the back side in ID-card mode. */
    private var idFront: Bitmap? = null
    val idFrontCaptured = mutableStateOf(false)

    /** Per-document undo history (in memory, last 20 states). */
    private val undoStacks = HashMap<String, ArrayDeque<ScanDocument>>()
    val undoCounts = mutableStateMapOf<String, Int>()

    init {
        refresh()
        // Page files are kept after delete/retake so Undo can bring them back;
        // anything no longer referenced is cleaned up on the next start.
        viewModelScope.launch(Dispatchers.IO) { runCatching { store.collectGarbage() } }
    }

    private fun pushUndo(prev: ScanDocument) {
        val st = synchronized(undoStacks) { undoStacks.getOrPut(prev.id) { ArrayDeque() } }
        synchronized(st) {
            st.addLast(prev)
            while (st.size > 20) st.removeFirst()
            viewModelScope.launch { undoCounts[prev.id] = st.size }
        }
    }

    /** Restores the document as it was before the last page edit. */
    fun undo(docId: String) {
        viewModelScope.launch {
            job {
                val st = synchronized(undoStacks) { undoStacks[docId] } ?: return@job
                val prev = synchronized(st) { st.removeLastOrNull() } ?: return@job
                viewModelScope.launch { undoCounts[docId] = st.size }
                val cur = store.load(docId) ?: return@job
                val curById = cur.pages.associateBy { it.id }
                val pages = prev.pages.map { p ->
                    val now = curById[p.id]
                    val changed = now == null || now.corners != p.corners || now.rotation != p.rotation ||
                        now.filter != p.filter || now.stamps != p.stamps
                    if (changed || !store.outFile(docId, p.id).exists()) {
                        // New revision so caches (thumbnails, OCR) never mix states.
                        val restored = p.copy(revision = maxOf(p.revision, now?.revision ?: 0) + 1)
                        PageRenderer.renderAndSave(store, docId, restored)?.recycle()
                        restored
                    } else now
                }
                saveAndPublish(cur.copy(name = prev.name, pages = pages))
                _messages.send(UiMessage(R.string.msg_undone))
            }
        }
    }

    // -------------------------------------------------------------- navigation

    fun open(s: Screen) {
        backStack.add(s)
    }

    fun back(): Boolean {
        if (backStack.size <= 1) return false
        backStack.removeAt(backStack.lastIndex)
        return true
    }

    fun replaceTop(s: Screen) {
        backStack[backStack.lastIndex] = s
    }

    /** Leaves the camera for the document's page list without stacking it twice. */
    fun finishCamera(docId: String) {
        back()
        val top = backStack.last()
        if (!(top is Screen.Editor && top.docId == docId)) open(Screen.Editor(docId))
    }

    // -------------------------------------------------------------- documents

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            _docs.value = store.list().map { DocEntry(it, store.documentText(it)) }
        }
    }

    fun doc(id: String): ScanDocument? = _docs.value.firstOrNull { it.doc.id == id }?.doc ?: store.load(id)

    private fun defaultName(): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH.mm", Locale.ROOT).format(Date())
        return getApplication<Application>().getString(R.string.default_doc_name, stamp)
    }

    private suspend fun <T> job(block: suspend () -> T): T? {
        busy.value++
        return try {
            withContext(Dispatchers.Default) { block() }
        } catch (e: OutOfMemoryError) {
            _messages.send(UiMessage(R.string.msg_out_of_memory)); null
        } catch (e: PdfImporter.ProtectedPdfException) {
            _messages.send(UiMessage(R.string.msg_pdf_protected)); null
        } catch (e: Exception) {
            android.util.Log.w("KiwiScan", "job failed", e)
            _messages.send(UiMessage(R.string.msg_error, e.message ?: e.javaClass.simpleName)); null
        } finally {
            busy.value--
            progress.value = null
        }
    }

    private fun saveAndPublish(doc: ScanDocument): ScanDocument {
        val saved = store.save(doc)
        val list = _docs.value.filter { it.doc.id != saved.id }.toMutableList()
        list.add(0, DocEntry(saved, store.documentText(saved)))
        _docs.value = list
        return saved
    }

    private fun ensureDoc(docId: String?): ScanDocument =
        docId?.let { store.load(it) } ?: store.create(defaultName())

    /** Stores a new page source and renders it; returns the page. */
    private fun addSource(docId: String, src: Bitmap, corners: Quad?, filter: Filter): ScanPage {
        val id = DocumentStore.newId()
        BitmapIO.saveJpeg(src, store.srcFile(docId, id), 92)
        val page = ScanPage(id, corners ?: BitmapIO.FULL, 0, filter)
        val out = BitmapIO.decodeFile(store.srcFile(docId, id))
        if (out != null) {
            val rendered = PageRenderer.render(out, page)
            out.recycle()
            BitmapIO.saveJpeg(rendered, store.outFile(docId, id), 92)
            val thumb = BitmapIO.fit(rendered, PageRenderer.THUMB_SIDE)
            BitmapIO.saveJpeg(thumb, store.thumbFile(docId, id), 82)
            thumb.recycle()
        }
        return page
    }

    /**
     * A photo from the camera. [liveQuad] is the outline seen in the preview,
     * used when detection on the still image fails.
     */
    fun addCapture(target: Screen.Camera, jpeg: ByteArray, rotation: Int, liveQuad: Quad?, onDone: (CaptureResult?) -> Unit) {
        viewModelScope.launch {
            val result = job {
                val bmp = BitmapIO.decodeBytes(jpeg, rotation) ?: error("decode")
                val det = BitmapIO.detectPage(bmp)
                val corners = det?.quad ?: liveQuad
                // Doubtful outline: ask the user instead of guessing.
                val doubtful = det == null || !det.confident
                var doc = ensureDoc(target.docId)
                if (target.idCard) {
                    val card = PageRenderer.render(bmp, ScanPage("tmp", corners ?: BitmapIO.FULL, 0, Filter.ENHANCED))
                    bmp.recycle()
                    val front = idFront
                    if (front == null) {
                        idFront = card
                        idFrontCaptured.value = true
                        _messages.send(UiMessage(R.string.id_now_back))
                        val id = target.docId ?: doc.id.also { saveAndPublish(doc) }
                        return@job CaptureResult(id, null, false)
                    }
                    val composed = IdCardComposer.compose(front, card)
                    front.recycle(); card.recycle()
                    idFront = null
                    idFrontCaptured.value = false
                    val page = addSource(doc.id, composed, null, Filter.ORIGINAL)
                    composed.recycle()
                    doc = saveAndPublish(doc.copy(pages = doc.pages + page))
                    _messages.send(UiMessage(R.string.msg_id_added))
                    return@job CaptureResult(doc.id, page.id, false)
                }
                val filter = settings.defaultFilter
                val newPage: ScanPage
                if (target.retakePageId != null) {
                    val idx = doc.pages.indexOfFirst { it.id == target.retakePageId }
                    newPage = addSource(doc.id, bmp, corners, doc.pages.getOrNull(idx)?.filter ?: filter)
                    bmp.recycle()
                    pushUndo(doc)
                    doc = if (idx >= 0) {
                        doc.copy(pages = doc.pages.toMutableList().also { it[idx] = newPage })
                    } else {
                        doc.copy(pages = doc.pages + newPage)
                    }
                } else {
                    newPage = addSource(doc.id, bmp, corners, filter)
                    bmp.recycle()
                    doc = doc.copy(pages = doc.pages + newPage)
                }
                CaptureResult(saveAndPublish(doc).id, newPage.id, doubtful)
            }
            onDone(result)
        }
    }

    /** Removes a document that was created by the camera but never got a page. */
    fun discardIfEmpty(docId: String?) {
        if (docId == null) return
        viewModelScope.launch(Dispatchers.IO) {
            val d = store.load(docId) ?: return@launch
            if (d.pages.isEmpty()) {
                store.delete(docId)
                _docs.value = _docs.value.filter { it.doc.id != docId }
            }
        }
    }

    fun cancelIdCard() {
        idFront?.recycle()
        idFront = null
        idFrontCaptured.value = false
    }

    /** Imports images and PDFs chosen through the system picker (no storage permission). */
    fun importUris(docId: String?, uris: List<Uri>, onDone: (String) -> Unit) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val id = job {
                val ctx = getApplication<Application>()
                var doc = ensureDoc(docId)
                val pages = doc.pages.toMutableList()
                for ((n, uri) in uris.withIndex()) {
                    progress.value = n to uris.size
                    val type = ctx.contentResolver.getType(uri) ?: ""
                    if (type == "application/pdf" || uri.toString().endsWith(".pdf", true)) {
                        PdfImporter.importPages(ctx, uri) { bmp ->
                            pages.add(addSource(doc.id, bmp, null, Filter.ORIGINAL))
                            bmp.recycle()
                        }
                    } else {
                        val bmp = BitmapIO.decodeUri(ctx, uri) ?: continue
                        val corners = BitmapIO.detect(bmp)
                        // A clear page outline means a photo of paper: crop + enhance it.
                        val filter = if (corners != null) settings.defaultFilter else Filter.ORIGINAL
                        pages.add(addSource(doc.id, bmp, corners, filter))
                        bmp.recycle()
                    }
                    doc = saveAndPublish(doc.copy(pages = pages.toList()))
                }
                _messages.send(UiMessage(R.string.msg_imported, uris.size.toString()))
                doc.id
            }
            if (id != null) onDone(id)
        }
    }

    private fun mutatePages(docId: String, message: UiMessage? = null, f: (MutableList<ScanPage>) -> Unit) {
        viewModelScope.launch {
            job {
                val doc = store.load(docId) ?: return@job
                val pages = doc.pages.toMutableList()
                f(pages)
                if (pages == doc.pages) return@job
                pushUndo(doc)
                saveAndPublish(doc.copy(pages = pages))
                message?.let { _messages.send(it) }
            }
        }
    }

    fun movePage(docId: String, from: Int, to: Int) = mutatePages(docId) { p ->
        if (from in p.indices && to in p.indices) p.add(to, p.removeAt(from))
    }

    /** Removes a page from the document; its files stay until restart so Undo works. */
    fun deletePage(docId: String, index: Int) = mutatePages(docId, UiMessage(R.string.msg_page_deleted, undoDocId = docId)) { p ->
        if (index in p.indices) p.removeAt(index)
    }

    /** Applies an edit to one page and re-renders it. */
    fun editPage(docId: String, pageId: String, edit: (ScanPage) -> ScanPage) {
        viewModelScope.launch {
            job {
                val doc = store.load(docId) ?: return@job
                val idx = doc.pages.indexOfFirst { it.id == pageId }
                if (idx < 0) return@job
                val edited = edit(doc.pages[idx])
                if (edited == doc.pages[idx]) return@job
                val page = edited.copy(revision = edited.revision + 1)
                pushUndo(doc)
                PageRenderer.renderAndSave(store, docId, page)?.recycle()
                val pages = doc.pages.toMutableList().also { it[idx] = page }
                saveAndPublish(doc.copy(pages = pages))
            }
        }
    }

    /** Words from the recognised text, offered as a document name ("فاتورة كهرباء ..."). */
    fun suggestName(docId: String): String? {
        val d = doc(docId) ?: return null
        val words = store.documentText(d).split(Regex("\\s+"))
            .map { it.trim { c -> !c.isLetterOrDigit() } }
            .filter { w -> w.length >= 2 && w.any { it.isLetter() } }
            .take(5)
        if (words.isEmpty()) return null
        return words.joinToString(" ").take(48)
    }

    /** Rough size of the export (encodes up to 3 sample pages at the chosen quality). */
    suspend fun estimateSize(docId: String, opt: ExportOptions): Long? = withContext(Dispatchers.Default) {
        val d = store.load(docId) ?: return@withContext null
        runCatching { exporter.estimatePdfSize(d, opt) }.getOrNull()
    }

    fun rename(docId: String, name: String) {
        val n = name.trim()
        if (n.isEmpty()) return
        viewModelScope.launch { job { store.load(docId)?.let { saveAndPublish(it.copy(name = n)) } } }
    }

    fun deleteDocs(ids: Collection<String>) {
        viewModelScope.launch {
            job { ids.forEach { store.delete(it) } }
            _docs.value = _docs.value.filter { it.doc.id !in ids }
        }
    }

    /** Merges documents (in list order) into a new document; originals are kept. */
    fun merge(ids: List<String>, onDone: (String) -> Unit) {
        viewModelScope.launch {
            val id = job {
                val target = store.create(defaultName())
                val pages = ArrayList<ScanPage>()
                for (id in ids) {
                    val d = store.load(id) ?: continue
                    d.pages.forEach { pages.add(store.copyPage(d.id, it, target.id)) }
                }
                saveAndPublish(target.copy(pages = pages)).id
            }
            if (id != null) onDone(id)
        }
    }

    // -------------------------------------------------------------- export

    fun share(docId: String, opt: ExportOptions, asJpeg: Boolean) {
        viewModelScope.launch {
            val r = job {
                val doc = store.load(docId) ?: return@job null
                if (doc.pages.isEmpty()) return@job null
                if (asJpeg) ExportResult.Share(exporter.exportJpegs(doc, opt) { a, b -> progress.value = a to b }, "image/jpeg")
                else ExportResult.Share(listOf(exporter.exportPdf(doc, opt) { a, b -> progress.value = a to b }), "application/pdf")
            }
            if (r != null) {
                refresh() // OCR text may now be searchable
                _exports.send(r)
            }
        }
    }

    /** Writes the PDF to a location the user picked with the system "Save as" dialog. */
    fun saveTo(docId: String, opt: ExportOptions, target: Uri) {
        viewModelScope.launch {
            val r = job {
                val doc = store.load(docId) ?: return@job null
                val file = exporter.exportPdf(doc, opt) { a, b -> progress.value = a to b }
                val cr: ContentResolver = getApplication<Application>().contentResolver
                cr.openOutputStream(target, "wt")?.use { out -> file.inputStream().use { it.copyTo(out) } }
                ExportResult.Saved(file.name, file.length())
            }
            if (r != null) {
                refresh()
                _exports.send(r)
            }
        }
    }

    /** Saves every page as JPEG into a folder the user picked (Storage Access Framework). */
    fun saveJpegsTo(docId: String, opt: ExportOptions, tree: Uri) {
        viewModelScope.launch {
            val r = job {
                val doc = store.load(docId) ?: return@job null
                val files = exporter.exportJpegs(doc, opt) { a, b -> progress.value = a to b }
                val cr = getApplication<Application>().contentResolver
                val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
                var total = 0L
                for (f in files) {
                    val dest = DocumentsContract.createDocument(cr, parent, "image/jpeg", f.name) ?: continue
                    cr.openOutputStream(dest, "w")?.use { out -> f.inputStream().use { it.copyTo(out) } }
                    total += f.length()
                }
                ExportResult.Saved(files.size.toString(), total)
            }
            if (r != null) _exports.send(r)
        }
    }

    fun suggestedFileName(docId: String): String = exporter.safeName(doc(docId)?.name ?: "scan") + ".pdf"

    override fun onCleared() {
        cancelIdCard()
        super.onCleared()
    }
}
