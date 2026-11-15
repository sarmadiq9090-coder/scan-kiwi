// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FabPosition
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fastkiwi.scan.R
import com.fastkiwi.scan.data.Folder
import com.fastkiwi.scan.data.FolderStore
import com.fastkiwi.scan.export.ExportOptions
import com.fastkiwi.scan.ui.theme.Dimens
import java.text.Collator

/** Home order (2.0): newest first is "Recent". */
enum class DocSort(val label: Int) { DATE(R.string.docs_sort_date), NAME(R.string.docs_sort_name), SIZE(R.string.docs_sort_size) }

/** The documents a Home list shows, in the chosen order (pure, for tests). */
fun homeEntries(all: List<DocEntry>, folders: List<Folder>, folderId: String?, favoritesOnly: Boolean, sort: DocSort, collator: Collator): List<DocEntry> {
    val ids = folders.map { it.id }.toHashSet()
    val shown = when {
        favoritesOnly && folderId == null -> all.filter { it.doc.favorite }
        favoritesOnly -> all.filter { it.doc.favorite && FolderStore.effectiveFolder(it.doc, ids) == folderId }
        else -> all.filter { FolderStore.effectiveFolder(it.doc, ids) == folderId }
    }
    return when (sort) {
        DocSort.DATE -> shown.sortedByDescending { it.doc.modified }
        DocSort.NAME -> shown.sortedWith { a, b -> collator.compare(a.doc.name, b.doc.name) }
        DocSort.SIZE -> shown.sortedByDescending { it.bytes }
    }
}

/**
 * Home (PRD §7.1, design spec §3.1, 2.0): one centred primary «مسح»; import and settings in the
 * top bar; the search field (opens Search); All / Favorites; Folders; Recent; the document list
 * with its count and sort. [folderId] shows one folder's documents instead (same screen).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(vm: AppViewModel, snackbar: SnackbarHostState, folderId: String? = null) {
    val docs by vm.docs.collectAsStateWithLifecycle()
    val folders by vm.folders.collectAsStateWithLifecycle()
    var selection by remember { mutableStateOf(setOf<String>()) }
    var renameId by remember { mutableStateOf<String?>(null) }
    var deleteIds by remember { mutableStateOf<Set<String>?>(null) }
    // Move / copy target picker: ids and whether it copies.
    var pick by remember { mutableStateOf<Pair<Set<String>, Boolean>?>(null) }
    // New folder dialog; the ids to move into it once created.
    var newFolderFor by remember { mutableStateOf<Pair<Set<String>, Boolean>?>(null) }
    var renameFolder by remember { mutableStateOf<Folder?>(null) }
    var deleteFolder by remember { mutableStateOf<Folder?>(null) }
    var sortMenu by remember { mutableStateOf(false) }
    var folderMenu by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val fabExpanded by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 } }
    val favoritesOnly = folderId == null && vm.favoritesOnly.value
    val sort = vm.docSort.value
    val locale = LocalConfiguration.current.locales[0]
    val folder = folderId?.let { id -> folders.firstOrNull { it.id == id } }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        vm.importUris(null, uris) { id -> vm.open(Screen.Editor(id)) }
    }
    val importFiles = { importLauncher.launch(arrayOf("image/*", "application/pdf")) }

    val shown = remember(docs, folders, folderId, favoritesOnly, sort, locale) {
        homeEntries(docs, folders, folderId, favoritesOnly, sort, Collator.getInstance(locale))
    }
    val folderIds = remember(folders) { folders.map { it.id }.toHashSet() }
    val counts = remember(docs, folderIds) { docs.groupingBy { FolderStore.effectiveFolder(it.doc, folderIds) }.eachCount() }
    val atTop = folderId == null && !favoritesOnly
    // "Recent" across folders is only worth a row when documents are filed away in folders.
    val recent = remember(docs, folderIds, atTop) {
        if (!atTop || docs.none { FolderStore.effectiveFolder(it.doc, folderIds) != null }) emptyList()
        else docs.sortedByDescending { it.doc.modified }.take(8)
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (selection.isNotEmpty()) {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                    navigationIcon = { TooltipIconButton(KiwiIcons.close(), stringResource(R.string.clear_selection), { selection = emptySet() }) },
                    title = { Text(pluralStringResource(R.plurals.selected_count, selection.size, selection.size)) },
                    actions = {
                        TooltipIconButton(KiwiIcons.merge(), stringResource(R.string.merge), {
                            val ordered = docs.map { it.doc.id }.filter { it in selection }.reversed()
                            vm.merge(ordered) { id -> selection = emptySet(); vm.open(Screen.Editor(id)) }
                        }, enabled = selection.size >= 2)
                        TooltipIconButton(DocsIcons.moveToFolder(), stringResource(R.string.docs_move_to_folder), { pick = selection to false })
                        TooltipIconButton(KiwiIcons.delete(), stringResource(R.string.delete), { deleteIds = selection })
                    },
                )
            } else if (folderId != null) {
                KiwiTopBar(
                    folder?.name ?: "", NavIcon.BACK, { vm.back() },
                    subtitle = pluralStringResource(R.plurals.docs_folder_doc_count, shown.size, shown.size),
                ) {
                    TooltipIconButton(KiwiIcons.import(), stringResource(R.string.import_files), importFiles)
                    Box {
                        TooltipIconButton(KiwiIcons.more(), stringResource(R.string.docs_folder_options, folder?.name ?: ""), { folderMenu = true })
                        DropdownMenu(expanded = folderMenu, onDismissRequest = { folderMenu = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.docs_folder_rename)) }, onClick = { folderMenu = false; renameFolder = folder })
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.docs_folder_delete), color = MaterialTheme.colorScheme.error) },
                                onClick = { folderMenu = false; deleteFolder = folder },
                            )
                        }
                    }
                }
            } else {
                TopAppBar(
                    navigationIcon = { BrandMark(Modifier.padding(start = Dimens.m)) },
                    title = { Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge, maxLines = 1) },
                    actions = {
                        // 2.0-pdf: the "PDF tools" entry goes here (before import).
                        TooltipIconButton(KiwiIcons.import(), stringResource(R.string.import_files), importFiles)
                        TooltipIconButton(KiwiIcons.settings(), stringResource(R.string.settings), { vm.open(Screen.Settings) })
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
        if (folderId == null && docs.isEmpty() && folders.isEmpty()) {
            Column(Modifier.padding(padding).fillMaxSize()) {
                SearchEntry(vm)
                Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
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
            }
            return@Scaffold
        }
        LazyColumn(Modifier.padding(padding).fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = Dimens.underFab)) {
            if (folderId == null) {
                item(key = "search") { SearchEntry(vm) }
                item(key = "filters") {
                    // All / Favorites: a segmented choice (48 dp targets), selected on secondaryContainer.
                    SingleChoiceSegmentedButtonRow(Modifier.padding(horizontal = Dimens.screen)) {
                        SegmentedButton(
                            selected = !favoritesOnly, onClick = { vm.favoritesOnly.value = false },
                            shape = SegmentedButtonDefaults.itemShape(0, 2), modifier = Modifier.heightIn(min = Dimens.button),
                        ) { Text(stringResource(R.string.docs_filter_all), maxLines = 1) }
                        SegmentedButton(
                            selected = favoritesOnly, onClick = { vm.favoritesOnly.value = true },
                            shape = SegmentedButtonDefaults.itemShape(1, 2), modifier = Modifier.heightIn(min = Dimens.button),
                            icon = { Icon(if (favoritesOnly) DocsIcons.starFilled() else DocsIcons.star(), null, Modifier.size(18.dp)) },
                        ) { Text(stringResource(R.string.docs_filter_favorites), maxLines = 1) }
                    }
                }
            }
            if (atTop) {
                item(key = "folders-header") {
                    Row(Modifier.fillMaxWidth().padding(start = Dimens.screen, end = Dimens.xs, top = Dimens.s), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.docs_section_folders), style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f).semantics { heading() },
                        )
                        TextButton(onClick = { newFolderFor = emptySet<String>() to false }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Icon(DocsIcons.newFolder(), null, Modifier.size(20.dp)); Spacer(Modifier.width(Dimens.s))
                            Text(stringResource(R.string.docs_folder_new))
                        }
                    }
                }
                items(folders, key = { "f:" + it.id }) { f ->
                    FolderRow(f, counts[f.id] ?: 0, onOpen = { vm.open(Screen.FolderDocs(f.id)) }, onRename = { renameFolder = f }, onDelete = { deleteFolder = f })
                }
                if (recent.isNotEmpty()) {
                    item(key = "recent") {
                        Column {
                            Text(
                                stringResource(R.string.docs_section_recent), style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = Dimens.screen, vertical = Dimens.s).semantics { heading() },
                            )
                            LazyRow(contentPadding = PaddingValues(horizontal = Dimens.screen), horizontalArrangement = Arrangement.spacedBy(Dimens.m)) {
                                items(recent, key = { "r:" + it.doc.id }) { e -> RecentCard(vm, e) { vm.open(Screen.Editor(e.doc.id)) } }
                            }
                        }
                    }
                }
            }
            item(key = "count") {
                Row(Modifier.fillMaxWidth().padding(start = Dimens.screen, end = Dimens.xs, top = Dimens.s), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        pluralStringResource(R.plurals.doc_count, shown.size, shown.size),
                        style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f).semantics { heading() },
                    )
                    Box {
                        TextButton(onClick = { sortMenu = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Icon(DocsIcons.sort(), null, Modifier.size(20.dp)); Spacer(Modifier.width(Dimens.s))
                            Text(stringResource(sort.label), maxLines = 1)
                        }
                        DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                            DocSort.entries.forEach { s ->
                                DropdownMenuItem(
                                    text = { Text(stringResource(s.label)) },
                                    leadingIcon = { if (s == sort) Icon(KiwiIcons.check(), null) },
                                    onClick = { sortMenu = false; vm.setDocSort(s) },
                                )
                            }
                        }
                    }
                }
            }
            if (shown.isEmpty()) {
                item(key = "empty") {
                    when {
                        favoritesOnly -> EmptyState(DocsIcons.star(), stringResource(R.string.docs_favorites_empty_title), stringResource(R.string.docs_favorites_empty_body), Modifier.fillMaxWidth())
                        folderId != null -> EmptyState(DocsIcons.folder(), stringResource(R.string.docs_folder_empty_title), stringResource(R.string.docs_folder_empty_body), Modifier.fillMaxWidth())
                        else -> Text(
                            stringResource(R.string.no_results), Modifier.fillMaxWidth().padding(Dimens.xxl),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
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
                    onMove = { pick = setOf(e.doc.id) to false },
                    onCopy = { pick = setOf(e.doc.id) to true },
                )
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
    pick?.let { (ids, copy) ->
        FolderPicker(
            title = stringResource(if (copy) R.string.docs_copy_to_folder else R.string.docs_move_to_folder),
            folders = folders,
            onDismiss = { pick = null },
            onNewFolder = { pick = null; newFolderFor = ids to copy },
        ) { target ->
            pick = null
            selection = emptySet()
            if (copy) vm.copyDocs(ids, target) else vm.moveDocs(ids, target)
        }
    }
    newFolderFor?.let { (ids, copy) ->
        FolderNameDialog(stringResource(R.string.docs_folder_new), "", stringResource(R.string.docs_folder_create), onDismiss = { newFolderFor = null }) {
            vm.createFolder(it, ids, copy)
            selection = emptySet()
        }
    }
    renameFolder?.let { f ->
        FolderNameDialog(stringResource(R.string.docs_folder_rename), f.name, stringResource(R.string.save), onDismiss = { renameFolder = null }) { vm.renameFolder(f.id, it) }
    }
    deleteFolder?.let { f ->
        ConfirmDialog(
            title = stringResource(R.string.docs_folder_delete_title, f.name),
            body = stringResource(R.string.docs_folder_delete_body),
            confirm = stringResource(R.string.delete),
            onDismiss = { deleteFolder = null },
        ) {
            vm.deleteFolder(f.id)
            if (folderId == f.id) vm.back()
        }
    }
}

/** The filled search field of the design spec; opens the Search screen (OCR-07). */
@Composable
private fun SearchEntry(vm: AppViewModel) {
    val label = stringResource(R.string.docs_search_hint)
    Surface(
        onClick = { vm.open(Screen.Search) },
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.screen, vertical = Dimens.s).heightIn(min = 56.dp),
    ) {
        Row(Modifier.padding(horizontal = Dimens.l), verticalAlignment = Alignment.CenterVertically) {
            Icon(KiwiIcons.search(), null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(Dimens.l))
            Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun FolderRow(f: Folder, count: Int, onOpen: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val aRename = stringResource(R.string.docs_folder_rename)
    val aDelete = stringResource(R.string.docs_folder_delete)
    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)
            .clickable(onClickLabel = stringResource(R.string.docs_folder_open), onClick = onOpen)
            .semantics {
                customActions = listOf(
                    CustomAccessibilityAction(aRename) { onRename(); true },
                    CustomAccessibilityAction(aDelete) { onDelete(); true },
                )
            },
    ) {
        Row(Modifier.padding(start = Dimens.l, end = Dimens.xs, top = Dimens.s, bottom = Dimens.s), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(52.dp).clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
                Icon(DocsIcons.folder(), null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
            }
            Spacer(Modifier.width(Dimens.l))
            Column(Modifier.weight(1f)) {
                Text(f.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(pluralStringResource(R.plurals.docs_folder_doc_count, count, count), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            Box {
                TooltipIconButton(KiwiIcons.more(), stringResource(R.string.docs_folder_options, f.name), { menu = true })
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(aRename) }, onClick = { menu = false; onRename() })
                    DropdownMenuItem(text = { Text(aDelete, color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}

/** A recently changed document, from any folder. */
@Composable
private fun RecentCard(vm: AppViewModel, e: DocEntry, onOpen: () -> Unit) {
    Column(
        Modifier.width(104.dp).clip(MaterialTheme.shapes.medium).clickable(onClick = onOpen).padding(Dimens.xs),
    ) {
        val first = e.doc.pages.firstOrNull()
        Box(Modifier.size(width = 96.dp, height = 128.dp)) {
            if (first != null) PageThumbnail(vm.store.thumbFile(e.doc.id, first.id), first.revision, Modifier.fillMaxSize())
            else Box(Modifier.fillMaxSize().clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.surfaceContainerHigh))
        }
        Text(e.doc.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = Dimens.xs))
    }
}

/** «نقل إلى مجلد» / «نسخ إلى مجلد»: the top level, every folder, and a new folder. */
@Composable
private fun FolderPicker(title: String, folders: List<Folder>, onDismiss: () -> Unit, onNewFolder: () -> Unit, onPick: (String?) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                PickRow(KiwiIcons.scan(), stringResource(R.string.docs_top_level)) { onPick(null) }
                folders.forEach { f -> PickRow(DocsIcons.folder(), f.name) { onPick(f.id) } }
                PickRow(DocsIcons.newFolder(), stringResource(R.string.docs_folder_new), onNewFolder)
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun PickRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(MaterialTheme.shapes.small).clickable(onClick = onClick).padding(horizontal = Dimens.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(Dimens.l))
        Text(label, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun FolderNameDialog(title: String, current: String, confirm: String, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    var value by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(value = value, onValueChange = { value = it }, singleLine = true, label = { Text(stringResource(R.string.docs_folder_name)) })
        },
        confirmButton = {
            TextButton(onClick = { onDone(value.trim()); onDismiss() }, enabled = value.isNotBlank()) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
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
    onMove: () -> Unit,
    onCopy: () -> Unit,
) {
    val ctx = LocalContext.current
    val doc = e.doc
    var menu by remember { mutableStateOf(false) }
    val date = remember(doc.modified) { LocalFormat.docDate(ctx, doc.modified) }
    val pagesText = pluralStringResource(R.plurals.page_count, doc.pages.size, doc.pages.size)
    val sep = LocalFormat.metaSep(LocalConfiguration.current.locales[0])
    val selectedLabel = stringResource(if (selected) R.string.selected else R.string.not_selected)
    val aShare = stringResource(R.string.share_pdf)
    val aRename = stringResource(R.string.rename)
    val aDelete = stringResource(R.string.delete)
    val aMove = stringResource(R.string.docs_move_to_folder)
    val aCopy = stringResource(R.string.docs_copy_to_folder)
    val aStar = stringResource(if (doc.favorite) R.string.docs_favorite_remove else R.string.docs_favorite_add, doc.name)
    val favState = stringResource(R.string.docs_favorite)
    Surface(
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 88.dp)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick, onLongClickLabel = stringResource(R.string.select))
            .semantics {
                if (selecting) stateDescription = selectedLabel else if (doc.favorite) stateDescription = favState
                // TalkBack actions mirroring the star and the ⋮ menu.
                if (!selecting) customActions = listOf(
                    CustomAccessibilityAction(aStar) { vm.setFavorite(doc.id, !doc.favorite); true },
                    CustomAccessibilityAction(aShare) { onShare(); true },
                    CustomAccessibilityAction(aRename) { onRename(); true },
                    CustomAccessibilityAction(aMove) { onMove(); true },
                    CustomAccessibilityAction(aCopy) { onCopy(); true },
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
                TooltipIconButton(
                    if (doc.favorite) DocsIcons.starFilled() else DocsIcons.star(), aStar, { vm.setFavorite(doc.id, !doc.favorite) },
                    tint = if (doc.favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Box {
                    TooltipIconButton(KiwiIcons.more(), stringResource(R.string.more_options_for, doc.name), { menu = true })
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.share_pdf)) }, onClick = { menu = false; onShare() }, enabled = doc.pages.isNotEmpty())
                        DropdownMenuItem(text = { Text(stringResource(R.string.rename)) }, onClick = { menu = false; onRename() })
                        DropdownMenuItem(text = { Text(aMove) }, onClick = { menu = false; onMove() })
                        DropdownMenuItem(text = { Text(aCopy) }, onClick = { menu = false; onCopy() })
                        DropdownMenuItem(text = { Text(stringResource(R.string.select)) }, onClick = { menu = false; onLongClick() })
                        DropdownMenuItem(text = { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; onDelete() })
                    }
                }
            }
        }
    }
}
