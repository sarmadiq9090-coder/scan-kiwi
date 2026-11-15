// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.ui

import androidx.compose.runtime.mutableIntStateOf
import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fastkiwi.scan.R
import com.fastkiwi.scan.data.DocumentStore
import com.fastkiwi.scan.pdf.ExternalPdfEngine
import com.fastkiwi.scan.pdf.IsolatedPdfEngine
import com.fastkiwi.scan.pdf.NoSpaceException
import com.fastkiwi.scan.pdf.PagePlans
import com.fastkiwi.scan.pdf.PdfError
import com.fastkiwi.scan.pdf.PdfFile
import com.fastkiwi.scan.pdf.PdfImages
import com.fastkiwi.scan.pdf.PdfOpException
import com.fastkiwi.scan.pdf.RangeException
import com.fastkiwi.scan.pdf.SafeOutput
import com.fastkiwi.scan.ui.theme.Dimens
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** The tools of the PDF tools screen (PDF-01..05, 08, 11, 12, 14 for files from other apps). */
enum class PdfTool { MERGE, SPLIT, EXTRACT, REORDER, ROTATE, BLANK, UNLOCK, TEXT, JPG }

/**
 * State of the PDF tools screen. Chosen files are copied into app storage first (the originals
 * behind the content URIs are only ever read), every PdfBox operation runs in the `:pdf` process,
 * and results are new files in the export cache.
 */
class PdfToolsViewModel(app: Application) : AndroidViewModel(app) {

    /** One chosen file. [pages] is null while it is being read or when it is locked. */
    data class Entry(val id: String, val name: String, val file: File, val password: String? = null, val pages: Int? = null, val locked: Boolean = false, val wrong: Boolean = false)

    val files = mutableStateListOf<Entry>()
    val selected = mutableStateOf<String?>(null)
    val working = mutableStateOf<Pair<Int, Int>?>(null)
    val results = mutableStateOf<List<File>?>(null)
    val text = mutableStateOf<List<String>?>(null)
    private var job: Job? = null

    private val _messages = Channel<Int>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    private val inDir get() = File(getApplication<Application>().cacheDir, "pdftools/in").apply { mkdirs() }
    private val outDir get() = File(getApplication<Application>().cacheDir, "exports").apply { mkdirs() }
    private val engine by lazy { ExternalPdfEngine(IsolatedPdfEngine(getApplication()), outDir) }

    fun current(): Entry? = files.firstOrNull { it.id == selected.value } ?: files.firstOrNull()

    fun add(uris: List<Uri>) {
        if (uris.isEmpty()) return
        run {
            val ctx = getApplication<Application>()
            for (uri in uris) {
                val id = DocumentStore.newId()
                val name = displayName(ctx, uri)
                val f = File(inDir, "$id.pdf")
                val size = runCatching { ctx.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } }.getOrNull() ?: 0L
                SafeOutput(f).checkSpace(size)
                ctx.contentResolver.openInputStream(uri)?.use { input -> f.outputStream().use { input.copyTo(it) } } ?: continue
                val e = Entry(id, name, f)
                withContext(Dispatchers.Main) { files.add(e); if (selected.value == null) selected.value = id }
                readPages(e)
            }
        }
    }

    private suspend fun readPages(e: Entry) {
        val updated = try {
            e.copy(pages = engine.pageCount(PdfFile(e.file, e.password)), locked = false, wrong = false)
        } catch (x: PdfOpException) {
            when (x.error) {
                PdfError.NEEDS_PASSWORD -> e.copy(locked = true)
                PdfError.BAD_PASSWORD -> e.copy(locked = true, wrong = true, password = null)
                else -> { withContext(Dispatchers.Main) { files.removeAll { it.id == e.id } }; e.file.delete(); throw x }
            }
        }
        withContext(Dispatchers.Main) { val i = files.indexOfFirst { it.id == e.id }; if (i >= 0) files[i] = updated }
    }

    fun unlockEntry(id: String, password: String) {
        val e = files.firstOrNull { it.id == id } ?: return
        run { readPages(e.copy(password = password)) }
    }

    fun remove(id: String) { files.firstOrNull { it.id == id }?.file?.delete(); files.removeAll { it.id == id } }

    fun move(id: String, by: Int) {
        val i = files.indexOfFirst { it.id == id }
        val j = i + by
        if (i < 0 || j !in files.indices) return
        files.add(j, files.removeAt(i))
    }

    fun cancel() { job?.cancel() }

    fun say(res: Int) { viewModelScope.launch { _messages.send(res) } }

    private fun base(e: Entry) = e.name.removeSuffix(".pdf").removeSuffix(".PDF").replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").take(60).ifBlank { "pdf" }

    /** Runs a tool; [ranges] is the typed page text, [n] a number (every N, blank-before page, dpi), [degrees] for rotate. */
    fun runTool(tool: PdfTool, ranges: String = "", n: Int = 0, degrees: Int = 90, password: String = "", everyN: Boolean = false) {
        run {
            val e = current() ?: return@run
            val src = PdfFile(e.file, e.password)
            val count = e.pages ?: 0
            val b = base(e)
            fun pages(): List<Int> = PagePlans.parse(ranges, count)
            val out: List<File> = when (tool) {
                PdfTool.MERGE -> {
                    val usable = files.filter { it.pages != null }
                    listOf(engine.assemble(usable.map { PdfFile(it.file, it.password) }, PagePlans.merge(usable.map { it.pages!! }), "$b-merged.pdf").file)
                }
                PdfTool.SPLIT -> {
                    val groups = if (everyN) PagePlans.everyN(count, n.coerceAtLeast(1)) else PagePlans.parseGroups(ranges, count)
                    groups.mapIndexed { i, g -> engine.assemble(listOf(src), PagePlans.select(g), "$b-${i + 1}.pdf").file }
                }
                PdfTool.EXTRACT -> listOf(engine.assemble(listOf(src), PagePlans.select(pages()), "$b-pages.pdf").file)
                PdfTool.REORDER -> listOf(engine.assemble(listOf(src), PagePlans.select(pages()), "$b-reordered.pdf").file)
                PdfTool.ROTATE -> {
                    val only = if (ranges.isBlank()) null else pages().toSet()
                    listOf(engine.assemble(listOf(src), PagePlans.rotate(count, degrees, only), "$b-rotated.pdf").file)
                }
                PdfTool.BLANK -> listOf(engine.assemble(listOf(src), PagePlans.insertBlank(count, (n - 1).coerceIn(0, count)), "$b-blank.pdf").file)
                PdfTool.UNLOCK -> listOf(engine.unlock(src, password.ifEmpty { e.password ?: "" }, "$b-unlocked.pdf").file)
                PdfTool.TEXT -> { val t = engine.text(src); withContext(Dispatchers.Main) { text.value = t }; emptyList() }
                PdfTool.JPG -> {
                    // A protected file is first opened (with its password) in the :pdf process.
                    val plain = if (e.password.isNullOrEmpty()) e.file else engine.unlock(src, e.password, "$b-tmp-open.pdf").file
                    try {
                        PdfImages.toJpegs(plain, outDir, b, n.coerceIn(72, 300)) { a, t -> working.value = a to t }
                    } finally {
                        if (plain != e.file) plain.delete()
                    }
                }
            }
            if (out.isNotEmpty()) withContext(Dispatchers.Main) { results.value = out }
        }
    }

    /** Runs [block] in the background with the busy bar; errors become messages, never crashes. */
    private fun run(block: suspend () -> Unit) {
        if (job?.isActive == true) return
        working.value = 0 to 0
        job = viewModelScope.launch(Dispatchers.IO) {
            try {
                block()
            } catch (e: CancellationException) {
                _messages.send(R.string.msg_export_cancelled)
            } catch (e: RangeException) {
                _messages.send(rangeError(e))
            } catch (e: NoSpaceException) {
                _messages.send(R.string.pdf_err_no_space)
            } catch (e: PdfOpException) {
                _messages.send(errorText(e.error))
            } catch (e: Exception) {
                _messages.send(R.string.pdf_err_malformed)
            } finally {
                withContext(Dispatchers.Main + kotlinx.coroutines.NonCancellable) { working.value = null }
            }
        }
    }

    override fun onCleared() {
        inDir.deleteRecursively()
        super.onCleared()
    }

    companion object {
        fun errorText(e: PdfError): Int = when (e) {
            PdfError.NEEDS_PASSWORD -> R.string.pdf_err_needs_password
            PdfError.BAD_PASSWORD -> R.string.pdf_err_bad_password
            PdfError.UNSUPPORTED -> R.string.pdf_err_unsupported
            PdfError.NO_SPACE -> R.string.pdf_err_no_space
            PdfError.TIMEOUT -> R.string.pdf_err_timeout
            PdfError.CANCELLED -> R.string.msg_export_cancelled
            else -> R.string.pdf_err_malformed
        }

        fun rangeError(e: RangeException): Int = when (e.reason) {
            RangeException.Reason.EMPTY -> R.string.pdf_range_err_empty
            RangeException.Reason.SYNTAX -> R.string.pdf_range_err_syntax
            RangeException.Reason.OUT_OF_RANGE -> R.string.pdf_range_err_range
            RangeException.Reason.REVERSED -> R.string.pdf_range_err_reversed
        }

        fun displayName(ctx: Context, uri: Uri): String = runCatching {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file.pdf"
    }
}

/** Shares files from the export cache through our FileProvider (content:// only). */
internal fun shareFiles(ctx: Context, files: List<File>, mime: String) {
    if (files.isEmpty()) return
    val authority = "${ctx.packageName}.files"
    val uris = ArrayList(files.map { FileProvider.getUriForFile(ctx, authority, it) })
    val intent = if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
    else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
    intent.type = mime
    intent.clipData = ClipData.newRawUri(null, uris[0]).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    ctx.startActivity(Intent.createChooser(intent, ctx.getString(R.string.share_via)))
}

@Composable
private fun pdfToolsIcon(): ImageVector = ImageVector.vectorResource(R.drawable.ic_kiwi_pdf_tools)

/** Icon for the Home entry. */
@Composable
fun pdfToolsHomeIcon(): ImageVector = pdfToolsIcon()

/** «أدوات PDF»: files from other apps; one primary action (pick files) until files are chosen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfToolsScreen(appVm: AppViewModel, snackbar: SnackbarHostState, vm: PdfToolsViewModel = viewModel()) {
    val ctx = LocalContext.current
    var tool by remember { mutableStateOf<PdfTool?>(null) }
    var unlockFor by remember { mutableStateOf<String?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { vm.add(it) }
    val pickPdf = { pick.launch(arrayOf("application/pdf")) }
    val res = androidx.compose.ui.platform.LocalResources.current
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(res.getString(it, LocalFormat.count(vm.current()?.pages ?: 0))) } }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = { KiwiTopBar(stringResource(R.string.pdf_tools_title), NavIcon.BACK, { appVm.back() }) },
        bottomBar = {
            BottomActionBar {
                val w = vm.working.value
                if (w != null) {
                    Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                        if (w.second > 0) LinearProgressIndicator(progress = { w.first.toFloat() / w.second }, modifier = Modifier.fillMaxWidth())
                        else LinearProgressIndicator(Modifier.fillMaxWidth())
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.pdf_working), Modifier.weight(1f).padding(top = Dimens.s))
                            TextButton(onClick = { vm.cancel() }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.cancel)) }
                        }
                    }
                } else {
                    Button(onClick = pickPdf, modifier = Modifier.fillMaxWidth().heightIn(min = Dimens.button)) {
                        Icon(KiwiIcons.add(), null, Modifier.size(20.dp)); Spacer(Modifier.width(Dimens.s))
                        Text(stringResource(if (vm.files.isEmpty()) R.string.pdf_tools_pick else R.string.pdf_tools_add))
                    }
                }
            }
        },
    ) { padding ->
        if (vm.files.isEmpty()) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(pdfToolsIcon(), stringResource(R.string.pdf_tools_empty_title), stringResource(R.string.pdf_tools_empty_body)) {
                    Text(stringResource(R.string.pdf_tools_privacy), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Dimens.l))
                }
            }
        } else {
            LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(horizontal = Dimens.screen, vertical = Dimens.s)) {
                item { SectionHeader(stringResource(R.string.pdf_tools_files)) }
                itemsIndexed(vm.files, key = { _, e -> e.id }) { i, e ->
                    FileRow(vm, e, i, onUnlock = { unlockFor = e.id })
                }
                item {
                    Text(stringResource(R.string.pdf_tools_privacy), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(Dimens.l))
                    SectionHeader(stringResource(R.string.pdf_tools_tools))
                    val tools = PdfTool.entries
                    SettingsGroup(tools.size) { i, shape ->
                        val t = tools[i]
                        val (icon, title, hint) = toolLabels(t)
                        SettingsRow(icon, title, hint, shape, {
                            val cur = vm.current()
                            when {
                                t == PdfTool.MERGE && vm.files.count { it.pages != null } < 2 -> vm.say(R.string.pdf_tool_needs_two)
                                cur == null -> {}
                                cur.locked && t != PdfTool.UNLOCK -> unlockFor = cur.id
                                t == PdfTool.MERGE || t == PdfTool.TEXT -> vm.runTool(t)
                                else -> tool = t
                            }
                        })
                    }
                    Spacer(Modifier.height(88.dp))
                }
            }
        }
    }

    tool?.let { t -> ToolDialog(t, vm.current(), onDismiss = { tool = null }) { ranges, n, deg, pw, everyN -> tool = null; vm.runTool(t, ranges, n, deg, pw, everyN) } }
    unlockFor?.let { id ->
        val e = vm.files.firstOrNull { it.id == id }
        if (e == null) unlockFor = null else PasswordDialog(e.name, wrong = e.wrong, onDismiss = { unlockFor = null }) { pw -> unlockFor = null; vm.unlockEntry(id, pw) }
    }
    vm.results.value?.let { files -> ResultSheet(appVm, files, onDismiss = { vm.results.value = null }) }
    vm.text.value?.let { pages -> TextDialog(pages, onDismiss = { vm.text.value = null }) }
}

@Composable
private fun toolLabels(t: PdfTool): Triple<ImageVector, String, String> = when (t) {
    PdfTool.MERGE -> Triple(KiwiIcons.merge(), stringResource(R.string.pdf_tool_merge), stringResource(R.string.pdf_tool_merge_hint))
    PdfTool.SPLIT -> Triple(KiwiIcons.cropFree(), stringResource(R.string.pdf_tool_split), stringResource(R.string.pdf_tool_split_hint))
    PdfTool.EXTRACT -> Triple(KiwiIcons.import(), stringResource(R.string.pdf_tool_extract), stringResource(R.string.pdf_tool_extract_hint))
    PdfTool.REORDER -> Triple(KiwiIcons.retake(), stringResource(R.string.pdf_tool_reorder), stringResource(R.string.pdf_tool_reorder_hint))
    PdfTool.ROTATE -> Triple(KiwiIcons.rotateRight(), stringResource(R.string.pdf_tool_rotate), stringResource(R.string.pdf_tool_rotate_hint))
    PdfTool.BLANK -> Triple(KiwiIcons.add(), stringResource(R.string.pdf_tool_blank), stringResource(R.string.pdf_tool_blank_hint))
    PdfTool.UNLOCK -> Triple(KiwiIcons.lock(), stringResource(R.string.pdf_tool_unlock), stringResource(R.string.pdf_tool_unlock_hint))
    PdfTool.TEXT -> Triple(KiwiIcons.ocr(), stringResource(R.string.pdf_tool_text), stringResource(R.string.pdf_tool_text_hint))
    PdfTool.JPG -> Triple(KiwiIcons.gallery(), stringResource(R.string.pdf_tool_jpg), stringResource(R.string.pdf_tool_jpg_hint))
}

@Composable
private fun FileRow(vm: PdfToolsViewModel, e: PdfToolsViewModel.Entry, index: Int, onUnlock: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val selected = (vm.current()?.id == e.id)
    val sub = when {
        e.locked -> stringResource(R.string.pdf_tools_locked)
        e.pages == null -> stringResource(R.string.pdf_tools_reading)
        else -> pluralStringResource(R.plurals.page_count, e.pages, e.pages)
    }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(
            Modifier
                .selectable(selected = selected, role = Role.RadioButton) { if (e.locked) onUnlock() else vm.selected.value = e.id }
                .heightIn(min = 64.dp).padding(start = Dimens.s, end = Dimens.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected, onClick = null)
            Column(Modifier.weight(1f).padding(horizontal = Dimens.m)) {
                Text(e.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(sub, style = MaterialTheme.typography.bodyMedium, color = if (e.locked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (e.locked) Icon(KiwiIcons.lock(), null, tint = MaterialTheme.colorScheme.error)
            Box {
                TooltipIconButton(KiwiIcons.more(), stringResource(R.string.more_options_for, e.name), { menu = true })
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.pdf_tools_move_up)) }, enabled = index > 0, onClick = { menu = false; vm.move(e.id, -1) })
                    DropdownMenuItem(text = { Text(stringResource(R.string.pdf_tools_move_down)) }, enabled = index < vm.files.size - 1, onClick = { menu = false; vm.move(e.id, 1) })
                    DropdownMenuItem(text = { Text(stringResource(R.string.pdf_tools_remove)) }, onClick = { menu = false; vm.remove(e.id) })
                }
            }
        }
    }
}

/** Parameters for one tool. The confirm button stays disabled until the page text parses. */
@Composable
private fun ToolDialog(t: PdfTool, e: PdfToolsViewModel.Entry?, onDismiss: () -> Unit, onRun: (String, Int, Int, String, Boolean) -> Unit) {
    val count = e?.pages ?: 0
    var ranges by remember { mutableStateOf("") }
    var n by remember { mutableStateOf(if (t == PdfTool.JPG) "200" else if (t == PdfTool.BLANK) "1" else "1") }
    var deg by remember { mutableIntStateOf(0) }
    var pw by remember { mutableStateOf("") }
    var everyN by remember { mutableStateOf(false) }
    val needsRanges = t == PdfTool.EXTRACT || t == PdfTool.REORDER || (t == PdfTool.SPLIT && !everyN)
    val rangeErr: Int? = if (!needsRanges && !(t == PdfTool.ROTATE && ranges.isNotBlank())) null else try {
        PagePlans.parse(ranges, count); null
    } catch (x: RangeException) {
        PdfToolsViewModel.rangeError(x)
    }
    val num = PagePlans.normalizeDigits(n).trim().toIntOrNull()
    val ok = rangeErr == null && when (t) {
        PdfTool.SPLIT -> !everyN || (num != null && num >= 1)
        PdfTool.BLANK -> num != null && num in 1..count + 1
        PdfTool.UNLOCK -> pw.isNotEmpty() || !e?.password.isNullOrEmpty()
        else -> true
    }
    val (_, title, hint) = toolLabels(t)
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = TextFieldDialogProperties,
        modifier = TextFieldDialogModifier,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Dimens.m)) {
                Text(hint, style = MaterialTheme.typography.bodyMedium)
                if (t == PdfTool.SPLIT) ChoiceSegmentedRow(listOf(stringResource(R.string.pdf_split_by_ranges), stringResource(R.string.pdf_split_every_n)), if (everyN) 1 else 0, { everyN = it == 1 })
                if (needsRanges || t == PdfTool.ROTATE) {
                    OutlinedTextField(
                        value = ranges, onValueChange = { ranges = it }, singleLine = true,
                        label = { Text(stringResource(if (t == PdfTool.ROTATE) R.string.pdf_all_pages else R.string.pdf_ranges_label)) },
                        isError = rangeErr != null,
                        supportingText = {
                            Text(
                                rangeErr?.let { stringResource(it, LocalFormat.count(count)) }
                                    ?: if (t == PdfTool.SPLIT) stringResource(R.string.pdf_ranges_split_hint) else pluralStringResource(R.plurals.page_count, count, count),
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if ((t == PdfTool.SPLIT && everyN) || t == PdfTool.BLANK) {
                    OutlinedTextField(
                        value = n, onValueChange = { n = it.take(5) }, singleLine = true,
                        label = { Text(stringResource(if (t == PdfTool.BLANK) R.string.pdf_blank_before else R.string.pdf_every_n_label)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), isError = !ok, modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (t == PdfTool.ROTATE) ChoiceSegmentedRow(listOf(stringResource(R.string.pdf_rotate_right), stringResource(R.string.pdf_rotate_180), stringResource(R.string.pdf_rotate_left)), deg, { deg = it })
                if (t == PdfTool.JPG) {
                    Text(stringResource(R.string.pdf_jpg_dpi), style = MaterialTheme.typography.titleSmall)
                    val dpis = listOf(150, 200, 300)
                    ChoiceSegmentedRow(dpis.map { LocalFormat.count(it) }, dpis.indexOf(num ?: 200).coerceAtLeast(0), { n = dpis[it].toString() })
                }
                if (t == PdfTool.UNLOCK && e?.password.isNullOrEmpty()) {
                    OutlinedTextField(
                        value = pw, onValueChange = { pw = it }, singleLine = true, label = { Text(stringResource(R.string.password)) },
                        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (t != PdfTool.JPG) Text(stringResource(R.string.pdf_output_unprotected), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.cancel)) } },
        confirmButton = {
            TextButton(onClick = { onRun(ranges, num ?: 0, listOf(90, 180, 270)[deg], pw, everyN) }, enabled = ok, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.pdf_run))
            }
        },
    )
}

/** Password for a protected PDF (PDF-14). Only the user's own password; nothing is guessed. */
@Composable
fun PasswordDialog(name: String, wrong: Boolean, onDismiss: () -> Unit, onOpen: (String) -> Unit) {
    var pw by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = TextFieldDialogProperties,
        modifier = TextFieldDialogModifier,
        title = { Text(stringResource(R.string.pdf_password_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.m)) {
                Text(LocalFormat.isolate(name), style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(stringResource(R.string.pdf_password_body), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = pw, onValueChange = { pw = it }, singleLine = true, label = { Text(stringResource(R.string.password)) },
                    isError = wrong, supportingText = if (wrong) ({ Text(stringResource(R.string.pdf_password_wrong)) }) else null,
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth().semantics { if (wrong) liveRegion = LiveRegionMode.Polite },
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.cancel)) } },
        confirmButton = { TextButton(onClick = { onOpen(pw) }, enabled = pw.isNotEmpty(), modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.pdf_open)) } },
    )
}

/** The import path's prompt: shown wherever the user imported a protected PDF from. */
@Composable
fun ImportPasswordPrompt(vm: AppViewModel) {
    val req = vm.passwordRequest.value ?: return
    val ctx = LocalContext.current
    val name = remember(req.uri) { PdfToolsViewModel.displayName(ctx, req.uri) }
    PasswordDialog(name, req.wrong, onDismiss = { vm.dismissPasswordRequest() }) { pw ->
        vm.importProtected(req, pw) { id -> if ((vm.screen as? Screen.Editor)?.docId != id) vm.open(Screen.Editor(id)) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ResultSheet(appVm: AppViewModel, files: List<File>, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val pdf = files.all { it.name.endsWith(".pdf") }
    val mime = if (pdf) "application/pdf" else "image/jpeg"
    val saveOne = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(mime)) { uri ->
        if (uri != null) copyOut(ctx, files.first(), uri)
    }
    val saveAll = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree != null) {
            val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            for (f in files) DocumentsContract.createDocument(ctx.contentResolver, parent, mime, f.name)?.let { copyOut(ctx, f, it) }
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(horizontal = Dimens.sheet).padding(bottom = Dimens.xl)) {
            Text(stringResource(R.string.pdf_result_ready), style = MaterialTheme.typography.titleLarge)
            Text(
                pluralStringResource(R.plurals.pdf_result_files, files.size, files.size) + LocalFormat.metaSep() + LocalFormat.size(ctx, files.sumOf { it.length() }),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            Spacer(Modifier.height(Dimens.m))
            files.take(6).forEach { Text(LocalFormat.isolate(it.name) + LocalFormat.metaSep() + LocalFormat.size(ctx, it.length()), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            if (pdf && files.size == 1) {
                TextButton(onClick = { onDismiss(); appVm.importUris(null, listOf(Uri.fromFile(files.first()))) { id -> appVm.open(Screen.Editor(id)) } }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.pdf_import_to_app))
                }
            }
            Spacer(Modifier.height(Dimens.l))
            ButtonPair(
                secondary = { m ->
                    OutlinedButton(onClick = { if (files.size == 1) saveOne.launch(files.first().name) else saveAll.launch(null) }, modifier = m) {
                        Icon(KiwiIcons.download(), null, Modifier.size(20.dp)); Spacer(Modifier.width(Dimens.s)); Text(stringResource(R.string.save_to_device))
                    }
                },
                primary = { m ->
                    Button(onClick = { shareFiles(ctx, files, mime) }, modifier = m) {
                        Icon(KiwiIcons.share(), null, Modifier.size(20.dp)); Spacer(Modifier.width(Dimens.s)); Text(stringResource(R.string.share))
                    }
                },
            )
        }
    }
}

private fun copyOut(ctx: Context, f: File, uri: Uri) {
    try {
        ctx.contentResolver.openOutputStream(uri, "wt")?.use { o -> f.inputStream().use { it.copyTo(o) } }
    } catch (e: Exception) {
        // Never leave a half-copied file where the user saved.
        runCatching { DocumentsContract.deleteDocument(ctx.contentResolver, uri) }
    }
}

@Composable
private fun TextDialog(pages: List<String>, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val all = pages.map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n\n")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pdf_text_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Text(all.ifEmpty { stringResource(R.string.pdf_text_empty) }, style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.close)) } },
        confirmButton = {
            TextButton(onClick = { copyText(ctx, all); onDismiss() }, enabled = all.isNotEmpty(), modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.pdf_copy)) }
        },
    )
}

internal fun copyText(ctx: Context, text: String) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText(ctx.getString(R.string.app_name), text))
}
