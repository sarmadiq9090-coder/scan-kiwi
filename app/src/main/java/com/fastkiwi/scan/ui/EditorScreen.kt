package com.fastkiwi.scan.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fastkiwi.scan.R
import com.fastkiwi.scan.core.PageSize
import com.fastkiwi.scan.export.ExportOptions
import com.fastkiwi.scan.export.Quality
import com.fastkiwi.scan.ocr.OcrLanguage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(vm: AppViewModel, docId: String, snackbar: SnackbarHostState) {
    val docs by vm.docs.collectAsStateWithLifecycle()
    val doc = docs.firstOrNull { it.doc.id == docId }?.doc ?: vm.doc(docId)
    var rename by remember { mutableStateOf(false) }
    var showExport by remember { mutableStateOf(false) }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        vm.importUris(docId, uris) {}
    }

    if (doc == null) {
        Text(stringResource(R.string.doc_missing), Modifier.padding(32.dp))
        return
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
                },
                title = {
                    Text(
                        doc.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable(onClickLabel = stringResource(R.string.rename)) { rename = true },
                    )
                },
                actions = {
                    IconButton(onClick = { rename = true }) { Icon(Icons.Filled.Edit, stringResource(R.string.rename)) }
                },
            )
        },
        bottomBar = {
            BottomAppBar(
                actions = {
                    IconButton(onClick = { vm.open(Screen.Camera(docId)) }) { Icon(Icons.Filled.AddAPhoto, stringResource(R.string.add_page_camera)) }
                    IconButton(onClick = { importLauncher.launch(arrayOf("image/*", "application/pdf")) }) {
                        Icon(Icons.Filled.FileOpen, stringResource(R.string.import_files))
                    }
                    IconButton(onClick = { vm.open(Screen.Camera(docId, idCard = true)) }) { Icon(Icons.Filled.Badge, stringResource(R.string.id_card_mode)) }
                },
                floatingActionButton = {
                    Button(onClick = { showExport = true }, enabled = doc.pages.isNotEmpty()) {
                        Icon(Icons.Filled.Share, null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.export))
                    }
                },
            )
        },
    ) { padding ->
        if (doc.pages.isEmpty()) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.no_pages), style = MaterialTheme.typography.bodyLarge)
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(150.dp),
                modifier = Modifier.padding(padding).fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                itemsIndexed(doc.pages, key = { _, p -> p.id }) { i, p ->
                    PageTile(vm, docId, i, doc.pages.size, p)
                }
            }
        }
    }

    if (rename) RenameDialog(doc.name, onDismiss = { rename = false }) { vm.rename(docId, it) }
    if (showExport) ExportSheet(vm, docId, onDismiss = { showExport = false })
}

@Composable
private fun PageTile(vm: AppViewModel, docId: String, index: Int, count: Int, page: com.fastkiwi.scan.data.ScanPage) {
    var menu by remember { mutableStateOf(false) }
    val label = stringResource(R.string.page_n_of, index + 1, count)
    Card(Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(0.72f)
                .clickable(onClickLabel = stringResource(R.string.edit_page)) { vm.open(Screen.Page(docId, index)) }
                .semantics { contentDescription = label },
        ) {
            FileImage(vm.store.thumbFile(docId, page.id), page.revision, 360, null, Modifier.fillMaxSize().padding(6.dp).clip(RoundedCornerShape(4.dp)))
        }
        Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text((index + 1).toString(), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.page_actions, index + 1)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.move_earlier)) }, enabled = index > 0, onClick = { menu = false; vm.movePage(docId, index, index - 1) })
                    DropdownMenuItem(text = { Text(stringResource(R.string.move_later)) }, enabled = index < count - 1, onClick = { menu = false; vm.movePage(docId, index, index + 1) })
                    DropdownMenuItem(text = { Text(stringResource(R.string.move_first)) }, enabled = index > 0, onClick = { menu = false; vm.movePage(docId, index, 0) })
                    DropdownMenuItem(text = { Text(stringResource(R.string.move_last)) }, enabled = index < count - 1, onClick = { menu = false; vm.movePage(docId, index, count - 1) })
                    DropdownMenuItem(text = { Text(stringResource(R.string.rotate_right)) }, onClick = { menu = false; vm.editPage(docId, page.id) { it.copy(rotation = (it.rotation + 90) % 360) } })
                    DropdownMenuItem(text = { Text(stringResource(R.string.crop)) }, onClick = { menu = false; vm.open(Screen.Crop(docId, page.id)) })
                    DropdownMenuItem(text = { Text(stringResource(R.string.retake)) }, onClick = { menu = false; vm.open(Screen.Camera(docId, retakePageId = page.id)) })
                    DropdownMenuItem(text = { Text(stringResource(R.string.delete)) }, onClick = { menu = false; vm.deletePage(docId, index) })
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportSheet(vm: AppViewModel, docId: String, onDismiss: () -> Unit) {
    val s = vm.settings
    var asJpeg by remember { mutableStateOf(false) }
    var pageSize by remember { mutableStateOf(s.pageSize) }
    var quality by remember { mutableStateOf(s.quality) }
    var ocr by remember { mutableStateOf(s.ocr) }
    var ocrLang by remember { mutableStateOf(s.ocrLanguage) }
    var protect by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var showPw by remember { mutableStateOf(false) }
    val busy by vm.busy
    val progress by vm.progress

    fun options(): ExportOptions {
        s.pageSize = pageSize; s.quality = quality; s.ocr = ocr; s.ocrLanguage = ocrLang
        return ExportOptions(pageSize, quality, ocr && !asJpeg, ocrLang, if (protect && !asJpeg) password else null)
    }

    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) vm.saveTo(docId, options(), uri)
    }
    val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.saveJpegsTo(docId, options(), uri)
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text(stringResource(R.string.export), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))

            Label(R.string.format)
            ChipRow {
                FilterChip(selected = !asJpeg, onClick = { asJpeg = false }, label = { Text("PDF") })
                FilterChip(selected = asJpeg, onClick = { asJpeg = true }, label = { Text("JPG") })
            }
            if (!asJpeg) {
                Label(R.string.page_size)
                ChipRow {
                    PageSize.entries.forEach { ps ->
                        FilterChip(selected = pageSize == ps, onClick = { pageSize = ps }, label = {
                            Text(stringResource(when (ps) { PageSize.A4 -> R.string.size_a4; PageSize.LETTER -> R.string.size_letter; PageSize.FIT -> R.string.size_fit }))
                        })
                    }
                }
            }
            Label(R.string.quality)
            ChipRow {
                Quality.entries.forEach { q ->
                    FilterChip(selected = quality == q, onClick = { quality = q }, label = {
                        Text(stringResource(when (q) { Quality.SMALL -> R.string.quality_small; Quality.BALANCED -> R.string.quality_balanced; Quality.HIGH -> R.string.quality_high }))
                    })
                }
            }
            Text(
                stringResource(when (quality) { Quality.SMALL -> R.string.quality_small_hint; Quality.BALANCED -> R.string.quality_balanced_hint; Quality.HIGH -> R.string.quality_high_hint }),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (!asJpeg) {
                SwitchRow(R.string.ocr_title, R.string.ocr_hint, ocr) { ocr = it }
                if (ocr) {
                    ChipRow {
                        OcrLanguage.entries.forEach { l ->
                            FilterChip(selected = ocrLang == l, onClick = { ocrLang = l }, label = {
                                Text(stringResource(when (l) { OcrLanguage.ARABIC_ENGLISH -> R.string.ocr_ara_eng; OcrLanguage.ENGLISH -> R.string.ocr_eng; OcrLanguage.ARABIC -> R.string.ocr_ara }))
                            })
                        }
                    }
                    Text(stringResource(R.string.ocr_quality_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                SwitchRow(R.string.password_title, R.string.password_hint, protect) { protect = it }
                if (protect) {
                    OutlinedTextField(
                        value = password,
                        onValueChange = { v -> password = v.filter { it.code in 0x20..0x7E } },
                        supportingText = { Text(stringResource(R.string.password_ascii_hint)) },
                        label = { Text(stringResource(R.string.password)) },
                        singleLine = true,
                        visualTransformation = if (showPw) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            IconButton(onClick = { showPw = !showPw }) {
                                Icon(if (showPw) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, stringResource(if (showPw) R.string.hide_password else R.string.show_password))
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            if (busy > 0) {
                val p = progress
                if (p != null && p.second > 0) {
                    LinearProgressIndicator(progress = { p.first.toFloat() / p.second }, modifier = Modifier.fillMaxWidth())
                    Text(stringResource(R.string.processing_page, (p.first + 1).coerceAtMost(p.second), p.second), style = MaterialTheme.typography.bodySmall)
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                Spacer(Modifier.height(12.dp))
            }
            val canGo = busy == 0 && (!protect || asJpeg || password.isNotEmpty())
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { vm.share(docId, options(), asJpeg) }, enabled = canGo, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Share, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.share))
                }
                OutlinedButton(onClick = {
                    if (asJpeg) folderLauncher.launch(null) else saveLauncher.launch(vm.suggestedFileName(docId))
                }, enabled = canGo, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Save, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.save_to_device))
                }
            }
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text(stringResource(R.string.close)) }
        }
    }
}

@Composable
private fun Label(res: Int) {
    Text(stringResource(res), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}

@Composable
fun SwitchRow(title: Int, hint: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
    Surface(
        onClick = { onChange(!checked) },
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp).semantics {
            toggleableState = if (checked) ToggleableState.On else ToggleableState.Off
        },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(title), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = checked, onCheckedChange = null)
        }
    }
}
