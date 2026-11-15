// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FabPosition
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fastkiwi.scan.R
import com.fastkiwi.scan.export.ExportOptions
import com.fastkiwi.scan.ui.theme.Dimens

/** Document list (design spec §3.1): one centred primary «مسح», import and settings in the top bar. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(vm: AppViewModel, snackbar: SnackbarHostState) {
    val docs by vm.docs.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var selection by remember { mutableStateOf(setOf<String>()) }
    var renameId by remember { mutableStateOf<String?>(null) }
    var deleteIds by remember { mutableStateOf<Set<String>?>(null) }
    val listState = rememberLazyListState()
    val fabExpanded by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 } }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        vm.importUris(null, uris) { id -> vm.open(Screen.Editor(id)) }
    }
    val importFiles = { importLauncher.launch(arrayOf("image/*", "application/pdf")) }

    val shown = remember(docs, query) {
        val q = query.trim()
        if (q.isEmpty()) docs else docs.filter { it.doc.name.contains(q, true) || it.text.contains(q, true) }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (selection.isEmpty()) {
                TopAppBar(
                    navigationIcon = { BrandMark(Modifier.padding(start = Dimens.m)) },
                    title = { Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge, maxLines = 1) },
                    actions = {
                        TooltipIconButton(KiwiIcons.import(), stringResource(R.string.import_files), importFiles)
                        // 2.0-pdf: entry to the PDF tools (files from other apps).
                        TooltipIconButton(PdfToolsIcon(), stringResource(R.string.pdf_tools_open), { vm.open(Screen.PdfTools) })
                        TooltipIconButton(KiwiIcons.settings(), stringResource(R.string.settings), { vm.open(Screen.Settings) })
                    },
                )
            } else {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                    navigationIcon = { TooltipIconButton(KiwiIcons.close(), stringResource(R.string.clear_selection), { selection = emptySet() }) },
                    title = { Text(pluralStringResource(R.plurals.selected_count, selection.size, selection.size)) },
                    actions = {
                        TooltipIconButton(KiwiIcons.merge(), stringResource(R.string.merge), {
                            val ordered = docs.map { it.doc.id }.filter { it in selection }.reversed()
                            vm.merge(ordered) { id -> selection = emptySet(); vm.open(Screen.Editor(id)) }
                        }, enabled = selection.size >= 2)
                        TooltipIconButton(KiwiIcons.delete(), stringResource(R.string.delete), { deleteIds = selection })
                    },
                )
            }
        },
        floatingActionButton = {
            if (selection.isEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = { vm.open(Screen.Camera(null)) },
                    expanded = fabExpanded,
                    icon = { Icon(KiwiIcons.scan(), null) },
                    text = { Text(stringResource(R.string.scan)) },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                )
            }
        },
        floatingActionButtonPosition = FabPosition.Center,
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            TextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.screen, vertical = Dimens.s).heightIn(min = 56.dp),
                singleLine = true,
                leadingIcon = { Icon(KiwiIcons.search(), null) },
                trailingIcon = {
                    if (query.isNotEmpty()) TooltipIconButton(KiwiIcons.close(), stringResource(R.string.clear_search), { query = "" })
                },
                placeholder = { Text(stringResource(R.string.search_hint)) },
                shape = CircleShape,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
            )
            when {
                docs.isEmpty() -> Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
                    EmptyState(KiwiIcons.scan(), stringResource(R.string.empty_title), stringResource(R.string.empty_body), Modifier.padding(bottom = Dimens.underFab)) {
                        OutlinedButton(onClick = importFiles, modifier = Modifier.heightIn(min = Dimens.button)) {
                            Icon(KiwiIcons.import(), null, Modifier.size(20.dp)); Spacer(Modifier.width(Dimens.s))
                            Text(stringResource(R.string.import_short))
                        }
                        Spacer(Modifier.size(Dimens.xl))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(KiwiIcons.lock(), null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(Dimens.s))
                            Text(stringResource(R.string.privacy_short), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                shown.isEmpty() -> Text(
                    stringResource(R.string.no_results), Modifier.fillMaxWidth().padding(Dimens.xxl),
                    textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge,
                )
                else -> LazyColumn(state = listState, contentPadding = PaddingValues(bottom = Dimens.underFab)) {
                    item {
                        Text(
                            pluralStringResource(R.plurals.doc_count, shown.size, shown.size),
                            style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = Dimens.screen, vertical = Dimens.s),
                        )
                    }
                    items(shown, key = { it.doc.id }) { e ->
                        DocRow(
                            vm, e, selected = e.doc.id in selection, selecting = selection.isNotEmpty(),
                            onClick = {
                                if (selection.isNotEmpty()) selection = selection.toggle(e.doc.id)
                                else vm.open(Screen.Editor(e.doc.id))
                            },
                            onLongClick = { selection = selection.toggle(e.doc.id) },
                            onRename = { renameId = e.doc.id },
                            onDelete = { deleteIds = setOf(e.doc.id) },
                            onShare = {
                                vm.share(e.doc.id, ExportOptions(vm.settings.pageSize, vm.settings.quality, false), asJpeg = false)
                            },
                        )
                    }
                }
            }
        }
    }

    renameId?.let { id ->
        RenameDialog(vm.doc(id)?.name ?: "", onDismiss = { renameId = null }) { vm.rename(id, it) }
    }
    deleteIds?.let { ids ->
        ConfirmDialog(
            title = pluralStringResource(R.plurals.delete_docs_title, ids.size, ids.size),
            body = stringResource(R.string.delete_docs_body),
            confirm = stringResource(R.string.delete),
            onDismiss = { deleteIds = null },
        ) {
            vm.deleteDocs(ids)
            selection = emptySet()
        }
    }
}

/** The app's own mark (kiwi page) at 32 dp. */
@Composable
fun BrandMark(modifier: Modifier = Modifier, size: Int = 32) {
    Box(
        modifier.size(size.dp).clip(RoundedCornerShape((size / 4).dp)).background(MaterialTheme.colorScheme.primary),
        contentAlignment = Alignment.Center,
    ) { Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.size((size * 1.5f).dp)) }
}

private fun Set<String>.toggle(id: String) = if (id in this) this - id else this + id

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DocRow(
    vm: AppViewModel,
    e: DocEntry,
    selected: Boolean,
    selecting: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onShare: () -> Unit,
) {
    val ctx = LocalContext.current
    val doc = e.doc
    var menu by remember { mutableStateOf(false) }
    val date = remember(doc.modified) { LocalFormat.docDate(ctx, doc.modified) }
    val pagesText = pluralStringResource(R.plurals.page_count, doc.pages.size, doc.pages.size)
    val sep = LocalFormat.metaSep(androidx.compose.ui.platform.LocalConfiguration.current.locales[0])
    val selectedLabel = stringResource(if (selected) R.string.selected else R.string.not_selected)
    val aShare = stringResource(R.string.share_pdf)
    val aRename = stringResource(R.string.rename)
    val aDelete = stringResource(R.string.delete)
    Surface(
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 88.dp)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick, onLongClickLabel = stringResource(R.string.select))
            .semantics {
                if (selecting) stateDescription = selectedLabel
                // TalkBack actions mirroring the ⋮ menu.
                if (!selecting) customActions = listOf(
                    CustomAccessibilityAction(aShare) { onShare(); true },
                    CustomAccessibilityAction(aRename) { onRename(); true },
                    CustomAccessibilityAction(aDelete) { onDelete(); true },
                )
            },
    ) {
        Row(Modifier.padding(start = Dimens.l, end = Dimens.xs, top = Dimens.s, bottom = Dimens.s), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(width = 52.dp, height = 68.dp)) {
                val first = doc.pages.firstOrNull()
                if (first != null) {
                    PageThumbnail(vm.store.thumbFile(doc.id, first.id), first.revision, Modifier.fillMaxSize())
                } else {
                    Box(Modifier.fillMaxSize().clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.surfaceContainerHigh))
                }
                if (selected) Icon(KiwiIcons.checkCircle(), null, Modifier.align(Alignment.Center), tint = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.width(Dimens.l))
            Column(Modifier.weight(1f)) {
                Text(doc.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("$pagesText$sep$date", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            if (!selecting) {
                Box {
                    TooltipIconButton(KiwiIcons.more(), stringResource(R.string.more_options_for, doc.name), { menu = true })
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.share_pdf)) }, onClick = { menu = false; onShare() }, enabled = doc.pages.isNotEmpty())
                        DropdownMenuItem(text = { Text(stringResource(R.string.rename)) }, onClick = { menu = false; onRename() })
                        DropdownMenuItem(text = { Text(stringResource(R.string.select)) }, onClick = { menu = false; onLongClick() })
                        DropdownMenuItem(text = { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; onDelete() })
                    }
                }
            }
        }
    }
}
