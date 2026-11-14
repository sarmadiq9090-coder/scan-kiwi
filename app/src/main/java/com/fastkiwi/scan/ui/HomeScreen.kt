package com.fastkiwi.scan.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.MergeType
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fastkiwi.scan.R
import com.fastkiwi.scan.export.ExportOptions
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(vm: AppViewModel, snackbar: SnackbarHostState) {
    val docs by vm.docs.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var selection by remember { mutableStateOf(setOf<String>()) }
    var renameId by remember { mutableStateOf<String?>(null) }
    var deleteIds by remember { mutableStateOf<Set<String>?>(null) }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        vm.importUris(null, uris) { id -> vm.open(Screen.Editor(id)) }
    }

    val shown = remember(docs, query) {
        val q = query.trim()
        if (q.isEmpty()) docs else docs.filter { it.doc.name.contains(q, true) || it.text.contains(q, true) }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (selection.isEmpty()) {
                TopAppBar(
                    title = { Text(stringResource(R.string.app_name)) },
                    actions = {
                        IconButton(onClick = { vm.open(Screen.Settings) }) {
                            Icon(Icons.Filled.Settings, stringResource(R.string.settings))
                        }
                    },
                )
            } else {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                    navigationIcon = {
                        IconButton(onClick = { selection = emptySet() }) { Icon(Icons.Filled.Close, stringResource(R.string.clear_selection)) }
                    },
                    title = { Text(pluralStringResource(R.plurals.selected_count, selection.size, selection.size)) },
                    actions = {
                        IconButton(onClick = {
                            val ordered = docs.map { it.doc.id }.filter { it in selection }.reversed()
                            vm.merge(ordered) { id -> selection = emptySet(); vm.open(Screen.Editor(id)) }
                        }, enabled = selection.size >= 2) {
                            Icon(Icons.Filled.MergeType, stringResource(R.string.merge))
                        }
                        IconButton(onClick = { deleteIds = selection }) { Icon(Icons.Filled.Delete, stringResource(R.string.delete)) }
                    },
                )
            }
        },
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SmallFloatingActionButton(onClick = { importLauncher.launch(arrayOf("image/*", "application/pdf")) }) {
                    Icon(Icons.Filled.FileOpen, stringResource(R.string.import_files))
                }
                ExtendedFloatingActionButton(
                    onClick = { vm.open(Screen.Camera(null)) },
                    icon = { Icon(Icons.Filled.CameraAlt, null) },
                    text = { Text(stringResource(R.string.scan)) },
                )
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                trailingIcon = {
                    if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, stringResource(R.string.clear_search)) }
                },
                placeholder = { Text(stringResource(R.string.search_hint)) },
                shape = RoundedCornerShape(28.dp),
            )
            if (docs.isEmpty()) {
                EmptyState()
            } else if (shown.isEmpty()) {
                Text(
                    stringResource(R.string.no_results),
                    Modifier.fillMaxWidth().padding(32.dp),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyLarge,
                )
            } else {
                LazyColumn(contentPadding = PaddingValues(bottom = 160.dp)) {
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

private fun Set<String>.toggle(id: String) = if (id in this) this - id else this + id

@Composable
private fun EmptyState() {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Outlined.Description, null, Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.empty_title), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.empty_body), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Lock, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.privacy_short), style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(120.dp))
    }
}

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
    val doc = e.doc
    var menu by remember { mutableStateOf(false) }
    val date = remember(doc.modified) { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(doc.modified)) }
    val pagesText = pluralStringResource(R.plurals.page_count, doc.pages.size, doc.pages.size)
    val selectedLabel = stringResource(if (selected) R.string.selected else R.string.not_selected)
    Surface(
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 88.dp)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick, onLongClickLabel = stringResource(R.string.select))
            .semantics { if (selecting) stateDescription = selectedLabel },
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(width = 56.dp, height = 72.dp).clip(RoundedCornerShape(6.dp))) {
                val first = doc.pages.firstOrNull()
                if (first != null) {
                    FileImage(vm.store.thumbFile(doc.id, first.id), first.revision, 360, null, Modifier.fillMaxSize(), ContentScale.Crop)
                } else {
                    Box(Modifier.fillMaxSize())
                }
                if (selected) Icon(Icons.Filled.CheckCircle, null, Modifier.align(Alignment.Center), tint = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(doc.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("$pagesText · $date", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!selecting) {
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.more_options_for, doc.name)) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.share_pdf)) }, onClick = { menu = false; onShare() }, enabled = doc.pages.isNotEmpty())
                        DropdownMenuItem(text = { Text(stringResource(R.string.rename)) }, onClick = { menu = false; onRename() })
                        DropdownMenuItem(text = { Text(stringResource(R.string.select)) }, onClick = { menu = false; onLongClick() })
                        DropdownMenuItem(text = { Text(stringResource(R.string.delete)) }, onClick = { menu = false; onDelete() })
                    }
                }
            }
        }
    }
}
