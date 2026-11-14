// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
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
import com.fastkiwi.scan.BuildConfig
import com.fastkiwi.scan.R
import com.fastkiwi.scan.core.Filter
import com.fastkiwi.scan.core.Quad
import com.fastkiwi.scan.core.StampMapper
import com.fastkiwi.scan.data.Stamp
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
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
    /** «أبلغنا عن مشكلة أو اقتراح»; [errorCode]/[errorClass] are set when opened from an error message. */
    data class Feedback(val errorCode: String? = null, val errorClass: String? = null) : Screen
    data object Settings : Screen
}

data class DocEntry(val doc: ScanDocument, val text: String)

/** A user-visible message: string resource + optional argument. */
data class UiMessage(
    val res: Int,
    val arg: String? = null,
    val undoDocId: String? = null,
    /** Offers «أبلغ عن هذه المشكلة»: (error code, exception class name). Never a message text. */
    val report: Pair<String, String>? = null,
)

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

    /** A running export (Share / Save): which page, and whether it is reading text (OCR). */
    data class ExportProgress(val page: Int, val total: Int, val readingText: Boolean)

    val exportState = mutableStateOf<ExportProgress?>(null)
    private var exportJob: kotlinx.coroutines.Job? = null

    /** Stops the running export; OCR is interrupted and the half-written file deleted. */
    fun cancelExport() {
        val j = exportJob ?: return
        if (j.isActive) {
            j.cancel()
            viewModelScope.launch { _messages.send(UiMessage(R.string.msg_export_cancelled)) }
        }
    }

    /** Starts an export job; only one at a time (a second tap is ignored while one runs). */
    private fun launchExport(block: suspend () -> Unit) {
        if (exportJob?.isActive == true) return
        exportState.value = ExportProgress(0, 0, false) // instant feedback on tap
        exportJob = viewModelScope.launch {
            try {
                block()
            } finally {
                exportState.value = null
            }
        }
    }

    private val _messages = Channel<UiMessage>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()
    private val _exports = Channel<ExportResult>(Channel.BUFFERED)
    val exports = _exports.receiveAsFlow()

    /** Front side kept while waiting for the back side in ID-card mode. */
    private var idFront: Bitmap? = null
    val idFrontCaptured = mutableStateOf(false)

    /** Appearance, observed by the theme so a change applies at once. */
    val themeMode = mutableStateOf(settings.themeMode)
    val dynamicColor = mutableStateOf(settings.dynamicColor)

    fun setThemeMode(m: com.fastkiwi.scan.ui.theme.ThemeMode) { settings.themeMode = m; themeMode.value = m }
    fun setDynamicColor(on: Boolean) { settings.dynamicColor = on; dynamicColor.value = on }

    /** Per-document undo history (in memory, last 20 states). */
    private val undoStacks = HashMap<String, ArrayDeque<ScanDocument>>()
    val undoCounts = mutableStateMapOf<String, Int>()

    /**
     * Every load-modify-save of a document runs under this lock. Jobs run in
     * parallel on background threads, so without it two edits (a capture and a
     * crop, an import and a delete, two quick rotations) each saved their own
     * stale copy and silently dropped the other's pages or changes.
     */
    private val docLock = Mutex()
    private val startedAt = System.currentTimeMillis()

    init {
        BitmapIO.configureForDevice(app)
        refresh()
        // Page files are kept after delete/retake so Undo can bring them back;
        // anything no longer referenced is cleaned up on the next start. Files
        // written since this start (an import from "Share to Scan Kiwi" may already
        // be running) are left alone; 2 s margin for coarse file-system clocks.
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { store.collectGarbage(olderThan = startedAt - 2_000) }
            runCatching { exporter.clearOldExports(before = startedAt - 60 * 60 * 1000L) }
        }
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
                docLock.withLock {
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
                    // Renames are not undo steps: keep the current name.
                    saveAndPublish(cur.copy(pages = pages))
                }
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

    /**
     * Runs [block] off the main thread with a progress indicator and turns
     * failures into messages. [code] names the operation for problem reports.
     */
    private suspend fun <T> job(code: String = "GENERAL", block: suspend () -> T): T? {
        busy.value++
        return try {
            withContext(Dispatchers.Default) { block() }
        } catch (e: OutOfMemoryError) {
            _messages.send(UiMessage(R.string.msg_out_of_memory, report = code to "OutOfMemoryError")); null
        } catch (e: PdfImporter.ProtectedPdfException) {
            _messages.send(UiMessage(R.string.msg_pdf_protected)); null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Exception texts can carry document content (org.json quotes the whole
            // file it failed on), so release builds log nothing and show a short text.
            if (BuildConfig.DEBUG) android.util.Log.w("ScanKiwi", "job failed", e)
            _messages.send(UiMessage(R.string.msg_error, (e.message ?: e.javaClass.simpleName).take(120), report = code to e.javaClass.simpleName)); null
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

    /** The document to add pages to: [docId] if it still exists, else a new one. */
    private fun targetDoc(docId: String?): ScanDocument =
        docId?.let { store.load(it) } ?: store.create(defaultName())

    /**
     * Loads, changes and saves a document under [docLock]. A document deleted in
     * the meantime is recreated (same id) rather than losing the pages being added.
     */
    private suspend fun updateDoc(docId: String, undoable: Boolean, change: (ScanDocument) -> ScanDocument): ScanDocument =
        docLock.withLock {
            val now = System.currentTimeMillis()
            val cur = store.load(docId) ?: ScanDocument(docId, defaultName(), now, now, emptyList())
            if (undoable) pushUndo(cur)
            saveAndPublish(change(cur))
        }

    /**
     * A photo from the camera. [liveQuad] is the outline seen in the preview,
     * used when detection on the still image fails.
     */
    fun addCapture(target: Screen.Camera, jpeg: ByteArray, rotation: Int, liveQuad: Quad?, onDone: (CaptureResult?) -> Unit) {
        viewModelScope.launch {
            val result = job("CAPTURE") {
                val bmp = BitmapIO.decodeBytes(jpeg, rotation) ?: error("decode")
                val det = BitmapIO.detectPage(bmp)
                val corners = det?.quad ?: liveQuad
                // Doubtful outline: ask the user instead of guessing.
                val doubtful = det == null || !det.confident
                val doc = targetDoc(target.docId)
                if (target.idCard) {
                    val card = PageRenderer.render(bmp, ScanPage("tmp", corners ?: BitmapIO.FULL, 0, Filter.ENHANCED))
                    bmp.recycle()
                    val front = idFront
                    if (front == null) {
                        idFront = card
                        idFrontCaptured.value = true
                        _messages.send(UiMessage(R.string.id_now_back))
                        if (target.docId == null) docLock.withLock { saveAndPublish(doc) }
                        return@job CaptureResult(doc.id, null, false)
                    }
                    val composed = IdCardComposer.compose(front, card)
                    front.recycle(); card.recycle()
                    idFront = null
                    idFrontCaptured.value = false
                    val page = addSource(doc.id, composed, null, Filter.ORIGINAL)
                    composed.recycle()
                    val saved = updateDoc(doc.id, undoable = true) { it.copy(pages = it.pages + page) }
                    _messages.send(UiMessage(R.string.msg_id_added))
                    return@job CaptureResult(saved.id, page.id, false)
                }
                val retake = target.retakePageId
                val keepFilter = retake?.let { id -> doc.pages.firstOrNull { it.id == id }?.filter }
                // The heavy work (encode, render) happens outside the lock...
                val newPage = addSource(doc.id, bmp, corners, keepFilter ?: settings.defaultFilter)
                bmp.recycle()
                // ...and the page goes into the document as it is now, not as it was.
                val saved = updateDoc(doc.id, undoable = true) { cur ->
                    val idx = if (retake != null) cur.pages.indexOfFirst { it.id == retake } else -1
                    cur.copy(pages = if (idx >= 0) cur.pages.toMutableList().also { it[idx] = newPage } else cur.pages + newPage)
                }
                CaptureResult(saved.id, newPage.id, doubtful)
            }
            onDone(result)
        }
    }

    /**
     * Navigation after a shot. Does nothing when the user has left this camera
     * screen while the photo was being processed (it used to replace whatever
     * screen was on top with the camera, or pop it). Returns true when a page was added.
     */
    fun afterCapture(target: Screen.Camera, r: CaptureResult, auto: Boolean): Boolean {
        val top = screen as? Screen.Camera ?: return false
        if (top.docId != target.docId || top.retakePageId != target.retakePageId) return false
        // Open the outline editor when detection was doubtful, or after a manual
        // shot when "review after capture" is on (the owner's v1.0 feedback).
        val review = r.pageId != null && !top.idCard && (r.needsReview || (!auto && settings.reviewAfterCapture))
        if (top.retakePageId != null) {
            back()
            if (review) open(Screen.Crop(r.docId, r.pageId!!))
            return false
        }
        val added = r.pageId != null
        replaceTop(top.copy(docId = r.docId, captured = top.captured + if (added) 1 else 0))
        if (review) open(Screen.Crop(r.docId, r.pageId!!))
        return added
    }

    /** Removes a document that was created by the camera but never got a page. */
    fun discardIfEmpty(docId: String?) {
        if (docId == null) return
        viewModelScope.launch(Dispatchers.IO) {
            docLock.withLock {
                val d = store.load(docId) ?: return@launch
                if (d.pages.isEmpty()) {
                    store.delete(docId)
                    _docs.value = _docs.value.filter { it.doc.id != docId }
                }
            }
        }
    }

    fun cancelIdCard() {
        idFront?.recycle()
        idFront = null
        idFrontCaptured.value = false
    }

    /**
     * Imports images and PDFs chosen through the system picker (no storage permission).
     * A file that cannot be read is skipped without stopping the others; when nothing
     * could be imported, no empty document is left behind.
     */
    fun importUris(docId: String?, uris: List<Uri>, onDone: (String) -> Unit) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val id = job("IMPORT") {
                val ctx = getApplication<Application>()
                val existing = docId?.let { store.load(it) }
                val doc = existing ?: store.create(defaultName())
                val pending = ArrayList<ScanPage>()
                var added = 0
                var undoPushed = existing == null // a new document has nothing to undo to
                suspend fun flush() {
                    if (pending.isEmpty()) return
                    val batch = pending.toList()
                    pending.clear()
                    updateDoc(doc.id, undoable = !undoPushed) { it.copy(pages = it.pages + batch) }
                    undoPushed = true
                    added += batch.size
                }
                var okFiles = 0
                var protectedPdf = false
                var failure: Exception? = null
                for ((n, uri) in uris.withIndex()) {
                    progress.value = n to uris.size
                    val before = added
                    try {
                        val type = ctx.contentResolver.getType(uri) ?: ""
                        if (type == "application/pdf" || uri.toString().endsWith(".pdf", true)) {
                            PdfImporter.importPages(ctx, uri) { bmp ->
                                pending.add(addSource(doc.id, bmp, null, Filter.ORIGINAL))
                                bmp.recycle()
                                if (pending.size >= 10) flush() // long PDFs: save as we go
                            }
                        } else {
                            val bmp = BitmapIO.decodeUri(ctx, uri)
                            if (bmp != null) {
                                val corners = BitmapIO.detect(bmp)
                                // A clear page outline means a photo of paper: crop + enhance it.
                                val filter = if (corners != null) settings.defaultFilter else Filter.ORIGINAL
                                pending.add(addSource(doc.id, bmp, corners, filter))
                                bmp.recycle()
                            }
                        }
                        flush()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: PdfImporter.ProtectedPdfException) {
                        protectedPdf = true
                    } catch (e: Exception) {
                        // Unreadable, corrupt, or no permission: skip this file, keep the rest
                        // (and the pages a damaged PDF gave before it failed).
                        runCatching { flush() }
                        pending.clear()
                        if (failure == null) failure = e
                    }
                    if (added > before) okFiles++
                }
                if (added == 0) {
                    if (existing == null) docLock.withLock {
                        store.load(doc.id)?.takeIf { it.pages.isEmpty() }?.let {
                            store.delete(it.id)
                            _docs.value = _docs.value.filter { e -> e.doc.id != it.id }
                        }
                    }
                    _messages.send(
                        when {
                            protectedPdf -> UiMessage(R.string.msg_pdf_protected)
                            failure != null -> UiMessage(R.string.msg_error, (failure!!.message ?: failure!!.javaClass.simpleName).take(120))
                            else -> UiMessage(R.string.msg_nothing_imported)
                        },
                    )
                    return@job null
                }
                val count = if (okFiles == uris.size) "$okFiles" else "$okFiles/${uris.size}"
                _messages.send(UiMessage(R.string.msg_imported, count))
                doc.id
            }
            if (id != null) onDone(id)
        }
    }

    private fun mutatePages(docId: String, message: UiMessage? = null, f: (MutableList<ScanPage>) -> Unit) {
        viewModelScope.launch {
            job {
                val changed = docLock.withLock {
                    val doc = store.load(docId) ?: return@job
                    val pages = doc.pages.toMutableList()
                    f(pages)
                    if (pages == doc.pages) return@job
                    pushUndo(doc)
                    saveAndPublish(doc.copy(pages = pages))
                    true
                }
                if (changed) message?.let { _messages.send(it) }
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

    /** Applies an edit to one page and re-renders it. Edits apply in order, each to the latest page state. */
    fun editPage(docId: String, pageId: String, edit: (ScanPage) -> ScanPage) {
        viewModelScope.launch {
            job {
                docLock.withLock {
                    val doc = store.load(docId) ?: return@job
                    val idx = doc.pages.indexOfFirst { it.id == pageId }
                    if (idx < 0) return@job
                    val before = doc.pages[idx]
                    val edited = keepStampsOnPaper(docId, before, edit(before))
                    if (edited == before) return@job
                    val page = edited.copy(revision = edited.revision + 1)
                    pushUndo(doc)
                    PageRenderer.renderAndSave(store, docId, page)?.recycle()
                    val pages = doc.pages.toMutableList().also { it[idx] = page }
                    saveAndPublish(doc.copy(pages = pages))
                }
            }
        }
    }

    /**
     * A re-crop or rotation moves the page content; signatures placed earlier
     * are moved with it so they stay on the same spot of the paper.
     */
    private fun keepStampsOnPaper(docId: String, before: ScanPage, after: ScanPage): ScanPage {
        if (before.stamps.isEmpty() || after.stamps != before.stamps) return after
        if (after.corners == before.corners && after.rotation == before.rotation) return after
        val (w, h) = BitmapIO.imageSize(store.srcFile(docId, before.id)) ?: return after
        val mapped = StampMapper.remap(
            before.stamps.map { floatArrayOf(it.cx, it.cy, it.width) },
            w.toFloat(), h.toFloat(), before.corners, before.rotation, after.corners, after.rotation,
        ) ?: return after
        return after.copy(stamps = mapped.map { Stamp(it[0], it[1], it[2]) })
    }

    /** Words from the recognised text, offered as a document name ("فاتورة كهرباء ..."). */
    fun suggestName(docId: String): String? {
        // Uses the text already loaded for search: called while composing, so no file reads here.
        val text = _docs.value.firstOrNull { it.doc.id == docId }?.text ?: return null
        val words = text.split(Regex("\\s+"))
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
        viewModelScope.launch { job { docLock.withLock { store.load(docId)?.let { saveAndPublish(it.copy(name = n)) } } } }
    }

    fun deleteDocs(ids: Collection<String>) {
        viewModelScope.launch {
            job { docLock.withLock { ids.forEach { store.delete(it) } } }
            _docs.value = _docs.value.filter { it.doc.id !in ids }
        }
    }

    /** Merges documents (in list order) into a new document; originals are kept. */
    fun merge(ids: List<String>, onDone: (String) -> Unit) {
        viewModelScope.launch {
            val id = job("MERGE") {
                docLock.withLock {
                    val target = store.create(defaultName())
                    val pages = ArrayList<ScanPage>()
                    for (id in ids) {
                        val d = store.load(id) ?: continue
                        d.pages.forEach { pages.add(store.copyPage(d.id, it, target.id)) }
                    }
                    saveAndPublish(target.copy(pages = pages)).id
                }
            }
            if (id != null) onDone(id)
        }
    }

    // -------------------------------------------------------------- export

    private suspend fun exportPdf(doc: ScanDocument, opt: ExportOptions): File {
        var noOcr = false
        var reading = false
        val file = exporter.exportPdf(
            doc, opt,
            onOcrUnavailable = { noOcr = true },
            phase = { reading = it == Exporter.Phase.READING_TEXT; exportState.value = exportState.value?.copy(readingText = reading) },
        ) { a, b ->
            progress.value = a to b
            exportState.value = ExportProgress(a, b, reading)
        }
        if (noOcr) _messages.send(UiMessage(R.string.msg_ocr_unavailable, report = "OCR_INIT" to "OcrUnavailable"))
        return file
    }

    fun share(docId: String, opt: ExportOptions, asJpeg: Boolean) {
        launchExport {
            val r = job("EXPORT") {
                val doc = store.load(docId) ?: return@job null
                if (doc.pages.isEmpty()) return@job null
                if (asJpeg) ExportResult.Share(exporter.exportJpegs(doc, opt) { a, b -> progress.value = a to b; exportState.value = ExportProgress(a, b, false) }, "image/jpeg")
                else ExportResult.Share(listOf(exportPdf(doc, opt)), "application/pdf")
            }
            if (r != null) {
                refresh() // OCR text may now be searchable
                _exports.send(r)
            }
        }
    }

    /** Writes the PDF to a location the user picked with the system "Save as" dialog. */
    fun saveTo(docId: String, opt: ExportOptions, target: Uri) {
        launchExport {
            val r = job("EXPORT") {
                val doc = store.load(docId) ?: return@job null
                val file = exportPdf(doc, opt)
                try {
                    val cr: ContentResolver = getApplication<Application>().contentResolver
                    val out = cr.openOutputStream(target, "wt") ?: throw IOException("cannot open the chosen file")
                    out.use { o -> file.inputStream().use { it.copyTo(o) } }
                    ExportResult.Saved(file.name, file.length())
                } finally {
                    // The copy in the cache is no longer needed once saved.
                    file.delete()
                }
            }
            if (r != null) {
                refresh()
                _exports.send(r)
            }
        }
    }

    /** Saves every page as JPEG into a folder the user picked (Storage Access Framework). */
    fun saveJpegsTo(docId: String, opt: ExportOptions, tree: Uri) {
        launchExport {
            val r = job("EXPORT") {
                val doc = store.load(docId) ?: return@job null
                val files = exporter.exportJpegs(doc, opt) { a, b -> progress.value = a to b; exportState.value = ExportProgress(a, b, false) }
                try {
                    val cr = getApplication<Application>().contentResolver
                    val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
                    var total = 0L
                    for (f in files) {
                        val dest = DocumentsContract.createDocument(cr, parent, "image/jpeg", f.name) ?: continue
                        cr.openOutputStream(dest, "w")?.use { out -> f.inputStream().use { it.copyTo(out) } }
                        total += f.length()
                    }
                    ExportResult.Saved(files.size.toString(), total)
                } finally {
                    files.forEach { it.delete() }
                }
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
