// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.produceState
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import com.fastkiwi.scan.ui.theme.Dimens
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fastkiwi.scan.R
import com.fastkiwi.scan.core.Filter
import com.fastkiwi.scan.core.PageSize
import com.fastkiwi.scan.export.ExportOptions
import com.fastkiwi.scan.export.Quality
import com.fastkiwi.scan.ocr.OcrLanguage
import com.fastkiwi.scan.pdf.ExportPreview
import com.fastkiwi.scan.pdf.NumberPosition
import com.fastkiwi.scan.pdf.PageDecor
import com.fastkiwi.scan.pdf.PageNumbering
import com.fastkiwi.scan.pdf.PagePlans
import com.fastkiwi.scan.pdf.RangeException
import com.fastkiwi.scan.pdf.Watermark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Document page grid (design spec §3.4): «إضافة صفحات» and the primary «تصدير» at the bottom.
 * 2.0-pdf: Redo next to Undo, drag-and-drop reorder (long-press a page; the menu and TalkBack
 * actions stay), page selection (extract, rotate, delete), split, blank pages and copy text.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(vm: AppViewModel, docId: String, snackbar: SnackbarHostState) {
    val docs by vm.docs.collectAsStateWithLifecycle()
    val doc = docs.firstOrNull { it.doc.id == docId }?.doc ?: vm.doc(docId)
    var rename by remember { mutableStateOf(false) }
    var showExport by remember { mutableStateOf(false) }
    var showAdd by remember { mutableStateOf(false) }
    var showSplit by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var selection by remember(docId) { mutableStateOf<Set<String>?>(null) } // null = not selecting
    var confirmDeleteSelected by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    val res = androidx.compose.ui.platform.LocalResources.current
    val scope = rememberCoroutineScope()

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        vm.importUris(docId, uris) {}
    }

    if (doc == null) {
        Text(stringResource(R.string.doc_missing), Modifier.padding(32.dp))
        return
    }
    // Pages that no longer exist drop out of the selection.
    val sel = selection?.let { s -> s.filter { id -> doc.pages.any { it.id == id } }.toSet() }
    val subtitle = pluralStringResource(R.plurals.page_count, doc.pages.size, doc.pages.size) +
        LocalFormat.metaSep(androidx.compose.ui.platform.LocalConfiguration.current.locales[0]) +
        stringResource(R.string.modified_at, LocalFormat.docDate(ctx, doc.modified))

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (sel != null) {
                // Selection mode: contextual bar (as on Home), destructive action in the menu.
                var selMenu by remember { mutableStateOf(false) }
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                    navigationIcon = { TooltipIconButton(KiwiIcons.close(), stringResource(R.string.clear_selection), { selection = null }) },
                    title = { Text(pluralStringResource(R.plurals.pdf_selected_pages, sel.size, sel.size), style = MaterialTheme.typography.titleLarge) },
                    actions = {
                        val ordered = doc.pages.mapIndexedNotNull { i, p -> if (p.id in sel) i else null }
                        TooltipIconButton(KiwiIcons.rotateRight(), stringResource(R.string.rotate_right), { vm.rotatePages(docId, sel, 90) }, enabled = sel.isNotEmpty())
                        TooltipIconButton(KiwiIcons.import(), stringResource(R.string.pdf_extract), {
                            vm.extractPages(docId, ordered) { id -> selection = null; vm.open(Screen.Editor(id)) }
                        }, enabled = sel.isNotEmpty())
                        Box {
                            TooltipIconButton(KiwiIcons.more(), stringResource(R.string.more_options), { selMenu = true })
                            DropdownMenu(expanded = selMenu, onDismissRequest = { selMenu = false }) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.pdf_select_all)) }, onClick = { selMenu = false; selection = doc.pages.map { it.id }.toSet() })
                                DropdownMenuItem(text = { Text(stringResource(R.string.rotate_left)) }, enabled = sel.isNotEmpty(), onClick = { selMenu = false; vm.rotatePages(docId, sel, 270) })
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }, enabled = sel.isNotEmpty(),
                                    onClick = { selMenu = false; confirmDeleteSelected = true },
                                )
                            }
                        }
                    },
                )
            } else {
                KiwiTopBar(
                    doc.name, NavIcon.BACK, { vm.back() }, subtitle = subtitle,
                    titleModifier = Modifier.clickable(onClickLabel = stringResource(R.string.rename)) { rename = true },
                ) {
                    TooltipIconButton(KiwiIcons.undo(), stringResource(R.string.undo), { vm.undo(docId) }, enabled = (vm.undoCounts[docId] ?: 0) > 0)
                    // 2.0-pdf: Redo (the undo arrow mirrored).
                    TooltipIconButton(ImageVector.vectorResource(R.drawable.ic_sym_redo), stringResource(R.string.redo), { vm.redo(docId) }, enabled = (vm.redoCounts[docId] ?: 0) > 0)
                    Box {
                        TooltipIconButton(KiwiIcons.more(), stringResource(R.string.more_options), { menu = true })
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.rename)) }, onClick = { menu = false; rename = true })
                            DropdownMenuItem(text = { Text(stringResource(R.string.pdf_select_pages)) }, enabled = doc.pages.isNotEmpty(), onClick = { menu = false; selection = emptySet() })
                            DropdownMenuItem(text = { Text(stringResource(R.string.pdf_split)) }, enabled = doc.pages.size > 1, onClick = { menu = false; showSplit = true })
                            DropdownMenuItem(text = { Text(stringResource(R.string.pdf_add_blank_end)) }, onClick = { menu = false; vm.insertBlankPage(docId, doc.pages.size) })
                            // BLACKBOX-2.0 #2: view / copy / correct the text from the document too (opens its first page).
                            DropdownMenuItem(text = { Text(stringResource(R.string.docs_text)) }, enabled = doc.pages.isNotEmpty(), onClick = {
                                menu = false; doc.pages.firstOrNull()?.let { vm.open(Screen.Text(docId, it.id)) }
                            })
                            DropdownMenuItem(text = { Text(stringResource(R.string.pdf_copy_text)) }, enabled = doc.pages.isNotEmpty(), onClick = {
                                menu = false
                                scope.launch {
                                    val t = vm.documentText(docId)
                                    if (t != null) { copyText(ctx, t); snackbar.showSnackbar(res.getString(R.string.pdf_msg_text_copied)) }
                                    else snackbar.showSnackbar(res.getString(R.string.pdf_msg_no_text))
                                }
                            })
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.delete_document), color = MaterialTheme.colorScheme.error) },
                                onClick = { menu = false; confirmDelete = true },
                            )
                        }
                    }
                }
            }
        },
        bottomBar = {
            if (sel == null) BottomActionBar {
                ButtonPair(
                    secondary = { m ->
                        FilledTonalButton(onClick = { showAdd = true }, modifier = m) {
                            Icon(KiwiIcons.add(), null, Modifier.size(20.dp)); Spacer(Modifier.width(Dimens.s)); Text(stringResource(R.string.add_pages))
                        }
                    },
                    primary = { m ->
                        Button(onClick = { showExport = true }, enabled = doc.pages.isNotEmpty(), modifier = m) {
                            Icon(KiwiIcons.share(), null, Modifier.size(20.dp)); Spacer(Modifier.width(Dimens.s)); Text(stringResource(R.string.export))
                        }
                    },
                )
            }
        },
    ) { padding ->
        if (doc.pages.isEmpty()) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.no_pages), style = MaterialTheme.typography.bodyLarge)
            }
        } else {
            PageGrid(vm, docId, doc.pages, sel, Modifier.padding(padding)) { id ->
                selection = sel?.let { if (id in it) it - id else it + id }
            }
        }
    }

    if (rename) RenameDialog(doc.name, suggestion = vm.suggestName(docId), onDismiss = { rename = false }) { vm.rename(docId, it) }
    if (showExport) ExportSheet(vm, docId, onDismiss = { showExport = false })
    if (showSplit) SplitDialog(doc.pages.size, onDismiss = { showSplit = false }) { groups -> showSplit = false; vm.splitDocument(docId, groups) }
    if (showAdd) AddPagesSheet(
        onDismiss = { showAdd = false },
        onCamera = { showAdd = false; vm.open(Screen.Camera(docId)) },
        onImport = { showAdd = false; importLauncher.launch(arrayOf("image/*", "application/pdf")) },
        onIdCard = { showAdd = false; vm.open(Screen.Camera(docId, idCard = true)) },
    )
    if (confirmDelete) {
        ConfirmDialog(
            title = pluralStringResource(R.plurals.delete_docs_title, 1, 1),
            body = stringResource(R.string.delete_docs_body),
            confirm = stringResource(R.string.delete),
            onDismiss = { confirmDelete = false },
        ) {
            vm.deleteDocs(setOf(docId))
            vm.back()
        }
    }
    if (confirmDeleteSelected && sel != null) {
        ConfirmDialog(
            title = pluralStringResource(R.plurals.pdf_selected_pages, sel.size, sel.size),
            body = stringResource(R.string.delete_page_body),
            confirm = stringResource(R.string.delete),
            onDismiss = { confirmDeleteSelected = false },
        ) {
            vm.deletePages(docId, sel)
            selection = null
        }
    }
}

/**
 * The page grid with drag-and-drop (PDF-04): long-press a page and drag it; the others make
 * room live and the move is saved (one Undo step) on release. In selection mode a tap toggles.
 */
@Composable
private fun PageGrid(
    vm: AppViewModel,
    docId: String,
    pages: List<com.fastkiwi.scan.data.ScanPage>,
    selection: Set<String>?,
    modifier: Modifier,
    onToggle: (String) -> Unit,
) {
    val gridState = rememberLazyGridState()
    val reorder = remember(pages.map { it.id }) { ReorderState(pages.size) }
    val currentPages by androidx.compose.runtime.rememberUpdatedState(pages)
    // Everything in root (physical) coordinates, so RTL mirroring never matters: the pointer,
    // where the finger grabbed the tile, and each tile's untransformed bounds.
    var pointer by remember { mutableStateOf(Offset.Zero) }
    var grab by remember { mutableStateOf(Offset.Zero) }
    val bounds = remember { HashMap<String, androidx.compose.ui.geometry.Rect>() }
    val shown = reorder.order().map { pages[it] }
    val draggedId = if (reorder.dragging) pages[reorder.from].id else null
    val shownNow by androidx.compose.runtime.rememberUpdatedState(shown)

    fun hoverAt(p: Offset) {
        val key = bounds.entries.firstOrNull { (k, r) -> k != draggedId && r.contains(p) && shownNow.any { it.id == k } }?.key ?: return
        reorder.hover(shownNow.indexOfFirst { it.id == key })
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(160.dp),
        state = gridState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(Dimens.screen),
        horizontalArrangement = Arrangement.spacedBy(Dimens.l),
        verticalArrangement = Arrangement.spacedBy(Dimens.s),
    ) {
        itemsIndexed(shown, key = { _, p -> p.id }) { i, p ->
            val dragging = p.id == draggedId
            val originalIndex = pages.indexOfFirst { it.id == p.id }
            val dragModifier = if (selection != null) Modifier else Modifier.pointerInput(p.id) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { local ->
                        val r = bounds[p.id] ?: return@detectDragGesturesAfterLongPress
                        val idx = currentPages.indexOfFirst { it.id == p.id }
                        if (idx < 0) return@detectDragGesturesAfterLongPress
                        grab = local
                        pointer = Offset(r.left + local.x, r.top + local.y)
                        reorder.start(idx)
                    },
                    onDrag = { change, amount ->
                        change.consume()
                        pointer += amount
                        hoverAt(pointer)
                    },
                    onDragEnd = { reorder.drop()?.let { (from, to) -> vm.movePage(docId, from, to) } },
                    onDragCancel = { reorder.cancel() },
                )
            }
            val rect = bounds[p.id]
            val lift = if (dragging && rect != null) {
                Modifier.zIndex(1f).graphicsLayer {
                    translationX = pointer.x - grab.x - rect.left
                    translationY = pointer.y - grab.y - rect.top
                    scaleX = 1.04f; scaleY = 1.04f
                }.shadow(8.dp, RoundedCornerShape(12.dp))
            } else Modifier
            Box(
                Modifier
                    .then(if (dragging) Modifier else Modifier.animateItem())
                    .onGloballyPositioned { bounds[p.id] = it.boundsInRoot() } // before the lift layer
                    .then(lift)
                    .then(dragModifier),
            ) {
                PageTile(vm, docId, if (reorder.dragging) i else originalIndex, pages.size, p, selection, onToggle, movingTo = if (dragging) i else null)
            }
        }
    }
}

/** «إضافة صفحات»: take photos (primary row), import images or PDF, ID card. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddPagesSheet(onDismiss: () -> Unit, onCamera: () -> Unit, onImport: () -> Unit, onIdCard: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerLow, dragHandle = null) {
        KiwiSheetHandle()
        Column(Modifier.fillMaxWidth().padding(horizontal = Dimens.sheet).padding(bottom = Dimens.xl)) {
            Text(stringResource(R.string.add_pages), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = Dimens.l))
            SettingsGroup(3) { i, shape ->
                when (i) {
                    0 -> SettingsRow(KiwiIcons.camera(), stringResource(R.string.take_pages), null, shape, onCamera, trailing = null)
                    1 -> SettingsRow(KiwiIcons.import(), stringResource(R.string.import_files), null, shape, onImport, trailing = null)
                    else -> SettingsRow(KiwiIcons.idCard(), stringResource(R.string.id_card_mode), null, shape, onIdCard, trailing = null)
                }
            }
        }
    }
}

@Composable
private fun PageTile(
    vm: AppViewModel,
    docId: String,
    index: Int,
    count: Int,
    page: com.fastkiwi.scan.data.ScanPage,
    selection: Set<String>? = null,
    onToggle: (String) -> Unit = {},
    movingTo: Int? = null,
) {
    var menu by remember { mutableStateOf(false) }
    val label = stringResource(R.string.page_n_of, index + 1, count)
    // TalkBack: the page menu's actions are also offered as accessibility actions.
    val aMoveEarlier = stringResource(R.string.move_earlier)
    val aMoveLater = stringResource(R.string.move_later)
    val aMoveFirst = stringResource(R.string.move_first)
    val aMoveLast = stringResource(R.string.move_last)
    val aCrop = stringResource(R.string.crop)
    val aRotate = stringResource(R.string.rotate_right)
    val aDelete = stringResource(R.string.delete)
    val selecting = selection != null
    val isSelected = selection?.contains(page.id) == true
    val selState = stringResource(if (isSelected) R.string.pdf_page_selected else R.string.pdf_page_not_selected)
    val moving = movingTo?.let { stringResource(R.string.pdf_drag_moving, LocalFormat.count(index + 1), LocalFormat.count(it + 1)) }
    Column(Modifier.fillMaxWidth()) {
        Box {
            PageThumbnail(
                vm.store.thumbFile(docId, page.id), page.revision,
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f / 1.414f)
                    .then(
                        if (selecting) Modifier.clickable(onClickLabel = selState) { onToggle(page.id) }
                        else Modifier.clickable(onClickLabel = stringResource(R.string.edit_page)) { vm.open(Screen.Page(docId, index)) },
                    )
                    .semantics {
                        contentDescription = label
                        if (selecting) { selected = isSelected; stateDescription = selState }
                        if (moving != null) { stateDescription = moving; liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite }
                        if (!selecting) customActions = buildList {
                            if (index > 0) add(CustomAccessibilityAction(aMoveEarlier) { vm.movePage(docId, index, index - 1); true })
                            if (index < count - 1) add(CustomAccessibilityAction(aMoveLater) { vm.movePage(docId, index, index + 1); true })
                            if (index > 0) add(CustomAccessibilityAction(aMoveFirst) { vm.movePage(docId, index, 0); true })
                            if (index < count - 1) add(CustomAccessibilityAction(aMoveLast) { vm.movePage(docId, index, count - 1); true })
                            add(CustomAccessibilityAction(aCrop) { vm.open(Screen.Crop(docId, page.id)); true })
                            add(CustomAccessibilityAction(aRotate) { vm.editPage(docId, page.id) { it.copy(rotation = (it.rotation + 90) % 360) }; true })
                            add(CustomAccessibilityAction(aDelete) { vm.deletePage(docId, index); true })
                        }
                    },
                radius = 12,
            )
            if (selecting) {
                Checkbox(
                    checked = isSelected, onCheckedChange = null,
                    modifier = Modifier.align(Alignment.TopEnd).padding(Dimens.xs).clip(CircleShape).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)),
                )
            }
        }
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = CircleShape, modifier = Modifier.padding(start = Dimens.xs)) {
                Text(
                    LocalFormat.count(index + 1), style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = Dimens.m, vertical = Dimens.xs),
                )
            }
            QualityBadge(page.quality, Modifier.padding(start = Dimens.xs)) // 2.0-cam: quality badge
            Spacer(Modifier.weight(1f))
            if (!selecting) Box {
                TooltipIconButton(KiwiIcons.more(), stringResource(R.string.page_actions, index + 1), { menu = true })
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.move_earlier)) }, enabled = index > 0, onClick = { menu = false; vm.movePage(docId, index, index - 1) })
                    DropdownMenuItem(text = { Text(stringResource(R.string.move_later)) }, enabled = index < count - 1, onClick = { menu = false; vm.movePage(docId, index, index + 1) })
                    DropdownMenuItem(text = { Text(stringResource(R.string.move_first)) }, enabled = index > 0, onClick = { menu = false; vm.movePage(docId, index, 0) })
                    DropdownMenuItem(text = { Text(stringResource(R.string.move_last)) }, enabled = index < count - 1, onClick = { menu = false; vm.movePage(docId, index, count - 1) })
                    DropdownMenuItem(text = { Text(stringResource(R.string.rotate_right)) }, onClick = { menu = false; vm.editPage(docId, page.id) { it.copy(rotation = (it.rotation + 90) % 360) } })
                    DropdownMenuItem(text = { Text(stringResource(R.string.crop)) }, onClick = { menu = false; vm.open(Screen.Crop(docId, page.id)) })
                    DropdownMenuItem(text = { Text(stringResource(R.string.signature)) }, onClick = { menu = false; vm.open(Screen.Sign(docId, page.id)) })
                    DropdownMenuItem(text = { Text(stringResource(R.string.docs_text)) }, onClick = { menu = false; vm.open(Screen.Text(docId, page.id)) }) // 2.0-docs: view / copy / correct text
                    DropdownMenuItem(text = { Text(stringResource(R.string.retake)) }, onClick = { menu = false; vm.open(Screen.Camera(docId, retakePageId = page.id)) })
                    // 2.0-pdf: PDF-11 blank pages.
                    DropdownMenuItem(text = { Text(stringResource(R.string.pdf_insert_blank_before)) }, onClick = { menu = false; vm.insertBlankPage(docId, index) })
                    DropdownMenuItem(text = { Text(stringResource(R.string.pdf_insert_blank_after)) }, onClick = { menu = false; vm.insertBlankPage(docId, index + 1) })
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) },
                        onClick = { menu = false; vm.deletePage(docId, index) },
                    )
                }
            }
        }
    }
}

/** PDF-02: split by typed ranges (each comma part a document) or every N pages. */
@Composable
private fun SplitDialog(pageCount: Int, onDismiss: () -> Unit, onSplit: (List<List<Int>>) -> Unit) {
    var everyN by remember { mutableStateOf(false) }
    var ranges by remember { mutableStateOf("") }
    var n by remember { mutableStateOf("1") }
    val groups: List<List<Int>>? = remember(everyN, ranges, n) {
        try {
            if (everyN) PagePlans.normalizeDigits(n).trim().toIntOrNull()?.takeIf { it >= 1 }?.let { PagePlans.everyN(pageCount, it) }
            else PagePlans.parseGroups(ranges, pageCount)
        } catch (e: RangeException) { null }
    }
    val err: Int? = if (everyN || ranges.isBlank()) null else try { PagePlans.parseGroups(ranges, pageCount); null } catch (e: RangeException) { PdfToolsViewModel.rangeError(e) }
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = TextFieldDialogProperties,
        modifier = TextFieldDialogModifier,
        title = { Text(stringResource(R.string.pdf_split)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.m)) {
                ChoiceSegmentedRow(listOf(stringResource(R.string.pdf_split_by_ranges), stringResource(R.string.pdf_split_every_n)), if (everyN) 1 else 0, { everyN = it == 1 })
                if (everyN) {
                    OutlinedTextField(n, { n = it.take(5) }, singleLine = true, label = { Text(stringResource(R.string.pdf_every_n_label)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                } else {
                    OutlinedTextField(ranges, { ranges = it }, singleLine = true, label = { Text(stringResource(R.string.pdf_ranges_label)) },
                        isError = err != null,
                        supportingText = { Text(err?.let { stringResource(it, LocalFormat.count(pageCount)) } ?: stringResource(R.string.pdf_ranges_split_hint)) },
                        modifier = Modifier.fillMaxWidth())
                }
                val parts = groups?.size ?: 0
                Text(
                    pluralStringResource(R.plurals.pdf_split_result, parts, parts), style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite },
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.cancel)) } },
        confirmButton = {
            TextButton(onClick = { groups?.let(onSplit) }, enabled = !groups.isNullOrEmpty(), modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.pdf_split)) }
        },
    )
}

/**
 * Export sheet (design spec §3.6, PRD §7.4): segmented format and quality (with «حجم محدد»,
 * a target size), a page-size dropdown, grouped option rows (OCR, PDF/A, password; PDF/A and
 * password exclude each other; page numbers and watermark), a live page count and size, the
 * final document preview, and Save / Share at the bottom.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportSheet(vm: AppViewModel, docId: String, onDismiss: () -> Unit) {
    val s = vm.settings
    val ctx = LocalContext.current
    // 0 = PDF, 1 = JPG images, 2 = text only (TXT; BLACKBOX-2.0 #6: TXT was only reachable from the Text screen).
    var format by remember { mutableStateOf(0) }
    val asJpeg = format == 1
    val asTxt = format == 2
    var pageSize by remember { mutableStateOf(s.pageSize) }
    var quality by remember { mutableStateOf(s.quality) }
    var ocr by remember { mutableStateOf(s.ocr) }
    var ocrLang by remember { mutableStateOf(s.ocrLanguage) }
    var protect by remember { mutableStateOf(false) }
    var archival by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var showPw by remember { mutableStateOf(false) }
    var sizeMenu by remember { mutableStateOf(false) }
    // 2.0-pdf: target size, page numbers, watermark.
    var targetMode by remember { mutableStateOf(false) }
    var targetText by remember { mutableStateOf("500") }
    var targetMb by remember { mutableStateOf(false) }
    var numbersOn by remember { mutableStateOf(false) }
    var numbers by remember { mutableStateOf(PageNumbering()) }
    var startText by remember { mutableStateOf("1") }
    var posMenu by remember { mutableStateOf(false) }
    var watermarkOn by remember { mutableStateOf(false) }
    var watermark by remember { mutableStateOf(Watermark("", 0.25f, true)) }
    val busy by vm.busy
    // 2.0.1: an error belongs to this opening of the sheet.
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { vm.exportError.value = null; vm.exportDone.value = null } }

    val targetBytes: Long? = if (!targetMode || format != 0) null else com.fastkiwi.scan.pdf.TargetSize.parseBytes(targetText, targetMb)
    val numbering = if (numbersOn) numbers.copy(start = PagePlans.normalizeDigits(startText).trim().toIntOrNull()?.coerceIn(0, 99_999) ?: 1) else null
    val mark = if (watermarkOn && watermark.text.isNotBlank()) watermark else null

    fun options(): ExportOptions {
        s.pageSize = pageSize; s.quality = quality; s.ocr = ocr; s.ocrLanguage = ocrLang
        return ExportOptions(
            pageSize, quality, ocr && !asJpeg, ocrLang, if (protect && !asJpeg) password else null, archival = archival && !protect && !asJpeg,
            targetBytes = targetBytes, pageNumbers = numbering, watermark = mark,
        )
    }

    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) vm.saveTo(docId, options(), uri)
    }
    val txtLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) vm.saveTxtTo(docId, uri)
    }
    val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.saveJpegsTo(docId, options(), uri)
    }

    // BLACKBOX-2.0 #9: at 200 % text the stacked Share / Save buttons took a third of the screen. From 150 %
    // the footer stays fixed (QC-1: reachable without scrolling) but compact: side by side, no icons,
    // labels wrap, less bottom space.
    val largeText = androidx.compose.ui.platform.LocalDensity.current.fontScale >= 1.5f
    val optionsScroll = rememberScrollState()
    val footer: @Composable () -> Unit = {
            // More options below (BLACKBOX-2.0 re-test, 200 %: the page-size field looked cut off behind the
            // buttons): a divider says the list goes on under the fixed footer.
            if (optionsScroll.canScrollForward) androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Column(Modifier.fillMaxWidth().padding(horizontal = Dimens.sheet).padding(top = Dimens.s, bottom = if (largeText) Dimens.m else Dimens.xl)) {
                // Footer: progress with a real Cancel while exporting, otherwise Save / Share.
                val ex = vm.exportState.value
                if (ex != null) {
                    Column(Modifier.semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite }) {
                        if (ex.total > 0 && !ex.fitting) {
                            LinearProgressIndicator(progress = { ex.page.toFloat() / ex.total }, modifier = Modifier.fillMaxWidth())
                            val page = (ex.page + 1).coerceAtMost(ex.total)
                            Text(
                                stringResource(if (ex.readingText) R.string.export_reading_text else R.string.export_preparing, page, ex.total),
                                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = Dimens.s),
                            )
                        } else {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            Text(stringResource(if (ex.fitting) R.string.pdf_export_fitting else R.string.export_starting), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = Dimens.s))
                        }
                        TextButton(onClick = { vm.cancelExport() }, modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.cancel))
                        }
                    }
                } else {
                    // 2.0.1: a failed export says why right here, above the buttons (the snackbar is under the sheet).
                    vm.exportError.value?.let { err ->
                        Text(
                            err, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.fillMaxWidth().padding(bottom = Dimens.s)
                                .semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Assertive },
                        )
                    } ?: vm.exportDone.value?.let { done ->
                        Text(
                            done, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.fillMaxWidth().padding(bottom = Dimens.s)
                                .semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite },
                        )
                    }
                    val canGo = busy == 0 && (asTxt || ((!protect || asJpeg || password.isNotEmpty()) && (!targetMode || asJpeg || targetBytes != null)))
                    ButtonPair(
                        stackOnLargeText = false,
                        secondary = { m ->
                            OutlinedButton(onClick = {
                                when {
                                    asTxt -> txtLauncher.launch(vm.suggestedTxtName(docId))
                                    asJpeg -> folderLauncher.launch(null)
                                    else -> saveLauncher.launch(vm.suggestedFileName(docId))
                                }
                            }, enabled = canGo, modifier = m) {
                                if (!largeText) { Icon(KiwiIcons.download(), null, Modifier.size(20.dp)); Spacer(Modifier.width(Dimens.s)) }
                                Text(stringResource(R.string.save_to_device), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                            }
                        },
                        primary = { m ->
                            Button(onClick = { if (asTxt) vm.shareTxt(docId) else vm.share(docId, options(), asJpeg) }, enabled = canGo, modifier = m) {
                                if (!largeText) { Icon(KiwiIcons.share(), null, Modifier.size(20.dp)); Spacer(Modifier.width(Dimens.s)) }
                                Text(stringResource(R.string.share), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                            }
                        },
                    )
                }
            }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        dragHandle = null,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        KiwiSheetHandle()
        // Options scroll; the footer (Save / Share, or progress with Cancel) stays fixed at the
        // bottom of the sheet so the primary action is always in the thumb zone (spec §3, §3.6).
        Column(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(optionsScroll).padding(horizontal = Dimens.sheet).padding(bottom = Dimens.l)) {
                // Header: title, then what will be shared (live).
                Text(stringResource(R.string.export), style = MaterialTheme.typography.titleLarge)
                val doc = vm.doc(docId)
                val pageCount = doc?.pages?.size ?: 0
                val estimate by produceState<Long?>(null, quality, asJpeg) {
                    value = null
                    value = vm.estimateSize(docId, ExportOptions(pageSize, quality))
                }
                val pagesText = pluralStringResource(R.plurals.page_count, pageCount, pageCount)
                val sizeText = if (targetBytes != null) stringResource(R.string.pdf_target_estimate, formatSize(targetBytes))
                else estimate?.let { stringResource(R.string.estimated_size, formatSize(it)) } ?: stringResource(R.string.estimating_size)
                Text(
                    if (asTxt) pagesText else pagesText + LocalFormat.metaSep(androidx.compose.ui.platform.LocalConfiguration.current.locales[0]) + sizeText,
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite },
                )

                // 2.0-pdf: final document preview (first pages, as they will be in the file).
                if (doc != null && doc.pages.isNotEmpty()) {
                    Label(R.string.pdf_preview)
                    val decor = remember { PageDecor.load(ctx) }
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(Dimens.m)) {
                        itemsIndexed(doc.pages.take(3), key = { _, p -> p.id }) { i, p ->
                            val bmp by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, p.revision, pageSize, numbering, mark, asJpeg) {
                                value = withContext(Dispatchers.Default) {
                                    ExportPreview.render(vm.store.thumbFile(docId, p.id), if (asJpeg) PageSize.FIT else pageSize, decor, i, doc.pages.size, numbering, mark, p.filter == Filter.BW)?.asImageBitmap()
                                }
                            }
                            val desc = stringResource(R.string.pdf_preview_page, LocalFormat.count(i + 1))
                            Surface(shape = RoundedCornerShape(8.dp), color = androidx.compose.ui.graphics.Color.White, shadowElevation = 1.dp, modifier = Modifier.height(168.dp).semantics { contentDescription = desc }) {
                                bmp?.let { androidx.compose.foundation.Image(it, null, Modifier.height(168.dp)) }
                                    ?: Box(Modifier.size(119.dp, 168.dp))
                            }
                        }
                    }
                }

                Label(R.string.format)
                ChoiceSegmentedRow(listOf("PDF", stringResource(R.string.format_jpg_images), stringResource(R.string.format_txt)), format, { format = it })
                if (asTxt) {
                    Text(
                        stringResource(R.string.docs_txt_hint), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Dimens.xs),
                    )
                }

                if (format == 0) {
                    Spacer(Modifier.height(Dimens.l))
                    ExposedDropdownMenuBox(expanded = sizeMenu, onExpandedChange = { sizeMenu = it }) {
                        OutlinedTextField(
                            value = pageSizeLabel(pageSize),
                            onValueChange = {},
                            readOnly = true,
                            label = { Text(stringResource(R.string.page_size)) },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = sizeMenu) },
                            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                        )
                        ExposedDropdownMenu(expanded = sizeMenu, onDismissRequest = { sizeMenu = false }) {
                            PageSize.entries.forEach { ps ->
                                DropdownMenuItem(text = { Text(pageSizeLabel(ps)) }, onClick = { pageSize = ps; sizeMenu = false })
                            }
                        }
                    }
                }

                if (!asTxt) {
                Label(R.string.quality)
                val qualityLabels = listOf(stringResource(R.string.quality_small), stringResource(R.string.quality_balanced), stringResource(R.string.quality_high))
                ChoiceSegmentedRow(
                    if (asJpeg) qualityLabels else qualityLabels + stringResource(R.string.pdf_quality_target),
                    if (targetMode && !asJpeg) 3 else quality.ordinal,
                    { if (it == 3) targetMode = true else { targetMode = false; quality = Quality.entries[it] } },
                )
                Text(
                    stringResource(
                        if (targetMode && !asJpeg) R.string.pdf_quality_target_hint
                        else when (quality) { Quality.SMALL -> R.string.quality_small_hint; Quality.BALANCED -> R.string.quality_balanced_hint; Quality.HIGH -> R.string.quality_high_hint },
                    ),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Dimens.xs),
                )
                if (targetMode && !asJpeg) {
                    Spacer(Modifier.height(Dimens.m))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = targetText, onValueChange = { targetText = it.take(14) }, singleLine = true,
                            label = { Text(stringResource(R.string.pdf_target_label)) }, isError = targetBytes == null,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(Dimens.m))
                        ChoiceSegmentedRow(listOf(stringResource(R.string.pdf_unit_kb), stringResource(R.string.pdf_unit_mb)), if (targetMb) 1 else 0, { targetMb = it == 1 }, Modifier.width(150.dp))
                    }
                }
                }

                if (format == 0) {
                    Spacer(Modifier.height(Dimens.l))
                    val groupColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    SettingsGroup(3) { i, shape ->
                        when (i) {
                            0 -> SettingsSwitchRow(KiwiIcons.ocr(), stringResource(R.string.ocr_title), stringResource(R.string.ocr_hint), ocr, shape, color = groupColor) { ocr = it }
                            1 -> SettingsSwitchRow(
                                KiwiIcons.pdfa(), stringResource(R.string.pdfa_title),
                                stringResource(if (protect) R.string.pdfa_excluded else R.string.pdfa_hint),
                                archival && !protect, shape, enabled = !protect, color = groupColor,
                            ) { archival = it }
                            else -> SettingsSwitchRow(
                                KiwiIcons.lock(), stringResource(R.string.password_title),
                                stringResource(if (archival) R.string.password_excluded else R.string.password_hint),
                                protect && !archival, shape, enabled = !archival, color = groupColor,
                            ) { protect = it }
                        }
                    }
                    if (ocr) {
                        Spacer(Modifier.height(Dimens.m))
                        ChoiceSegmentedRow(
                            listOf(stringResource(R.string.ocr_ara_eng), stringResource(R.string.ocr_eng), stringResource(R.string.ocr_ara)),
                            ocrLang.ordinal, { ocrLang = OcrLanguage.entries[it] },
                        )
                        Text(stringResource(R.string.ocr_quality_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Dimens.xs))
                    }
                    if (protect) {
                        Spacer(Modifier.height(Dimens.m))
                        OutlinedTextField(
                            value = password,
                            onValueChange = { v -> password = v.filter { it.code in 0x20..0x7E } },
                            supportingText = { Text(stringResource(R.string.password_ascii_hint)) },
                            label = { Text(stringResource(R.string.password)) },
                            singleLine = true,
                            visualTransformation = if (showPw) VisualTransformation.None else PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            trailingIcon = {
                                TooltipIconButton(
                                    if (showPw) KiwiIcons.visibilityOff() else KiwiIcons.visibility(),
                                    stringResource(if (showPw) R.string.hide_password else R.string.show_password), { showPw = !showPw },
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                TxtExportRow(vm, docId) // 2.0-docs: OCR-08 TXT export

                // 2.0-pdf: page numbers (PDF-09) and watermark (PDF-10), for PDF and JPG.
                Spacer(Modifier.height(Dimens.l))
                val groupColor = MaterialTheme.colorScheme.surfaceContainerHigh
                SettingsGroup(2) { i, shape ->
                    if (i == 0) SettingsSwitchRow(null, stringResource(R.string.pdf_numbers_title), stringResource(R.string.pdf_numbers_hint), numbersOn, shape, color = groupColor) { numbersOn = it }
                    else SettingsSwitchRow(null, stringResource(R.string.pdf_watermark_title), stringResource(R.string.pdf_watermark_hint), watermarkOn, shape, color = groupColor) { watermarkOn = it }
                }
                if (numbersOn) {
                    Spacer(Modifier.height(Dimens.m))
                    ChoiceSegmentedRow(listOf(stringResource(R.string.pdf_digits_arabic), stringResource(R.string.pdf_digits_western)), if (numbers.arabicDigits) 0 else 1, { numbers = numbers.copy(arabicDigits = it == 0) })
                    Spacer(Modifier.height(Dimens.m))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ExposedDropdownMenuBox(expanded = posMenu, onExpandedChange = { posMenu = it }, modifier = Modifier.weight(1f)) {
                            OutlinedTextField(
                                value = positionLabel(numbers.position), onValueChange = {}, readOnly = true,
                                label = { Text(stringResource(R.string.pdf_number_position)) },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = posMenu) },
                                modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                            )
                            ExposedDropdownMenu(expanded = posMenu, onDismissRequest = { posMenu = false }) {
                                NumberPosition.entries.forEach { p -> DropdownMenuItem(text = { Text(positionLabel(p)) }, onClick = { numbers = numbers.copy(position = p); posMenu = false }) }
                            }
                        }
                        Spacer(Modifier.width(Dimens.m))
                        OutlinedTextField(
                            value = startText, onValueChange = { startText = it.take(5) }, singleLine = true,
                            label = { Text(stringResource(R.string.pdf_number_start)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.width(110.dp),
                        )
                    }
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = androidx.compose.ui.semantics.Role.Checkbox) { numbers = numbers.copy(withTotal = !numbers.withTotal) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = numbers.withTotal, onCheckedChange = null)
                        Spacer(Modifier.width(Dimens.m))
                        Text(stringResource(R.string.pdf_number_with_total), style = MaterialTheme.typography.bodyLarge)
                    }
                }
                if (watermarkOn) {
                    Spacer(Modifier.height(Dimens.m))
                    OutlinedTextField(
                        value = watermark.text, onValueChange = { watermark = watermark.copy(text = it.take(60)) }, singleLine = true,
                        label = { Text(stringResource(R.string.pdf_watermark_text)) }, modifier = Modifier.fillMaxWidth(),
                    )
                    val pct = java.text.NumberFormat.getPercentInstance(androidx.compose.ui.platform.LocalConfiguration.current.locales[0]).format(watermark.opacity.toDouble())
                    Text(stringResource(R.string.pdf_watermark_opacity, pct), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = Dimens.s))
                    Slider(value = watermark.opacity, onValueChange = { watermark = watermark.copy(opacity = it) }, valueRange = 0.1f..0.6f, steps = 4, modifier = Modifier.fillMaxWidth().semantics { stateDescription = pct })
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = androidx.compose.ui.semantics.Role.Checkbox) { watermark = watermark.copy(diagonal = !watermark.diagonal) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = watermark.diagonal, onCheckedChange = null)
                        Spacer(Modifier.width(Dimens.m))
                        Text(stringResource(R.string.pdf_watermark_diagonal), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
            footer()
        }
    }
}

@Composable
private fun positionLabel(p: NumberPosition): String = stringResource(
    when (p) {
        NumberPosition.BOTTOM_CENTER -> R.string.pdf_pos_bottom_center
        NumberPosition.BOTTOM_LEFT -> R.string.pdf_pos_bottom_left
        NumberPosition.BOTTOM_RIGHT -> R.string.pdf_pos_bottom_right
        NumberPosition.TOP_CENTER -> R.string.pdf_pos_top_center
        NumberPosition.TOP_LEFT -> R.string.pdf_pos_top_left
        NumberPosition.TOP_RIGHT -> R.string.pdf_pos_top_right
    },
)

/** «A4 (٢١ × ٢٩٫٧ سم)»: name plus size in the locale's digits. */
@Composable
private fun pageSizeLabel(ps: PageSize): String {
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    val nf = java.text.NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 1 }
    @Composable fun dims(w: Double, h: Double, unit: Int) = stringResource(R.string.size_dims, nf.format(w), nf.format(h), stringResource(unit))
    return when (ps) {
        PageSize.A4 -> "A4 (" + dims(21.0, 29.7, R.string.unit_cm) + ")"
        PageSize.LETTER -> "Letter (" + dims(8.5, 11.0, R.string.unit_in) + ")"
        PageSize.LEGAL -> "Legal (" + dims(8.5, 14.0, R.string.unit_in) + ")"
        PageSize.A5 -> "A5 (" + dims(14.8, 21.0, R.string.unit_cm) + ")"
        PageSize.FIT -> stringResource(R.string.size_fit)
    }
}

@Composable
private fun Label(res: Int) {
    Text(
        stringResource(res), style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(top = Dimens.xl, bottom = Dimens.s).semantics { heading() },
    )
}
