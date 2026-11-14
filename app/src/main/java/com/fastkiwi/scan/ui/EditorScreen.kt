// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.runtime.produceState
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
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
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import com.fastkiwi.scan.ui.theme.Dimens
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

/** Document page grid (design spec §3.4): «إضافة صفحات» and the primary «تصدير» at the bottom. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(vm: AppViewModel, docId: String, snackbar: SnackbarHostState) {
    val docs by vm.docs.collectAsStateWithLifecycle()
    val doc = docs.firstOrNull { it.doc.id == docId }?.doc ?: vm.doc(docId)
    var rename by remember { mutableStateOf(false) }
    var showExport by remember { mutableStateOf(false) }
    var showAdd by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val ctx = LocalContext.current

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        vm.importUris(docId, uris) {}
    }

    if (doc == null) {
        Text(stringResource(R.string.doc_missing), Modifier.padding(32.dp))
        return
    }
    val subtitle = pluralStringResource(R.plurals.page_count, doc.pages.size, doc.pages.size) +
        LocalFormat.metaSep(ctx.resources.configuration.locales[0]) +
        stringResource(R.string.modified_at, LocalFormat.docDate(ctx, doc.modified))

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            KiwiTopBar(
                doc.name, NavIcon.BACK, { vm.back() }, subtitle = subtitle,
                titleModifier = Modifier.clickable(onClickLabel = stringResource(R.string.rename)) { rename = true },
            ) {
                TooltipIconButton(KiwiIcons.undo(), stringResource(R.string.undo), { vm.undo(docId) }, enabled = (vm.undoCounts[docId] ?: 0) > 0)
                Box {
                    TooltipIconButton(KiwiIcons.more(), stringResource(R.string.more_options), { menu = true })
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.rename)) }, onClick = { menu = false; rename = true })
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.delete_document), color = MaterialTheme.colorScheme.error) },
                            onClick = { menu = false; confirmDelete = true },
                        )
                    }
                }
            }
        },
        bottomBar = {
            BottomActionBar {
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
            LazyVerticalGrid(
                columns = GridCells.Adaptive(160.dp),
                modifier = Modifier.padding(padding).fillMaxSize(),
                contentPadding = PaddingValues(Dimens.screen),
                horizontalArrangement = Arrangement.spacedBy(Dimens.l),
                verticalArrangement = Arrangement.spacedBy(Dimens.s),
            ) {
                itemsIndexed(doc.pages, key = { _, p -> p.id }) { i, p ->
                    PageTile(vm, docId, i, doc.pages.size, p)
                }
            }
        }
    }

    if (rename) RenameDialog(doc.name, suggestion = vm.suggestName(docId), onDismiss = { rename = false }) { vm.rename(docId, it) }
    if (showExport) ExportSheet(vm, docId, onDismiss = { showExport = false })
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
}

/** «إضافة صفحات»: take photos (primary row), import images or PDF, ID card. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddPagesSheet(onDismiss: () -> Unit, onCamera: () -> Unit, onImport: () -> Unit, onIdCard: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
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
private fun PageTile(vm: AppViewModel, docId: String, index: Int, count: Int, page: com.fastkiwi.scan.data.ScanPage) {
    var menu by remember { mutableStateOf(false) }
    val label = stringResource(R.string.page_n_of, index + 1, count)
    // TalkBack: the page menu's actions are also offered as accessibility actions.
    val aMoveEarlier = stringResource(R.string.move_earlier)
    val aMoveLater = stringResource(R.string.move_later)
    val aCrop = stringResource(R.string.crop)
    val aRotate = stringResource(R.string.rotate_right)
    val aDelete = stringResource(R.string.delete)
    Column(Modifier.fillMaxWidth()) {
        PageThumbnail(
            vm.store.thumbFile(docId, page.id), page.revision,
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f / 1.414f)
                .clickable(onClickLabel = stringResource(R.string.edit_page)) { vm.open(Screen.Page(docId, index)) }
                .semantics {
                    contentDescription = label
                    customActions = buildList {
                        if (index > 0) add(CustomAccessibilityAction(aMoveEarlier) { vm.movePage(docId, index, index - 1); true })
                        if (index < count - 1) add(CustomAccessibilityAction(aMoveLater) { vm.movePage(docId, index, index + 1); true })
                        add(CustomAccessibilityAction(aCrop) { vm.open(Screen.Crop(docId, page.id)); true })
                        add(CustomAccessibilityAction(aRotate) { vm.editPage(docId, page.id) { it.copy(rotation = (it.rotation + 90) % 360) }; true })
                        add(CustomAccessibilityAction(aDelete) { vm.deletePage(docId, index); true })
                    }
                },
            radius = 12,
        )
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = CircleShape, modifier = Modifier.padding(start = Dimens.xs)) {
                Text(
                    LocalFormat.count(index + 1), style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = Dimens.m, vertical = Dimens.xs),
                )
            }
            Spacer(Modifier.weight(1f))
            Box {
                TooltipIconButton(KiwiIcons.more(), stringResource(R.string.page_actions, index + 1), { menu = true })
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.move_earlier)) }, enabled = index > 0, onClick = { menu = false; vm.movePage(docId, index, index - 1) })
                    DropdownMenuItem(text = { Text(stringResource(R.string.move_later)) }, enabled = index < count - 1, onClick = { menu = false; vm.movePage(docId, index, index + 1) })
                    DropdownMenuItem(text = { Text(stringResource(R.string.move_first)) }, enabled = index > 0, onClick = { menu = false; vm.movePage(docId, index, 0) })
                    DropdownMenuItem(text = { Text(stringResource(R.string.move_last)) }, enabled = index < count - 1, onClick = { menu = false; vm.movePage(docId, index, count - 1) })
                    DropdownMenuItem(text = { Text(stringResource(R.string.rotate_right)) }, onClick = { menu = false; vm.editPage(docId, page.id) { it.copy(rotation = (it.rotation + 90) % 360) } })
                    DropdownMenuItem(text = { Text(stringResource(R.string.crop)) }, onClick = { menu = false; vm.open(Screen.Crop(docId, page.id)) })
                    DropdownMenuItem(text = { Text(stringResource(R.string.signature)) }, onClick = { menu = false; vm.open(Screen.Sign(docId, page.id)) })
                    DropdownMenuItem(text = { Text(stringResource(R.string.retake)) }, onClick = { menu = false; vm.open(Screen.Camera(docId, retakePageId = page.id)) })
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) },
                        onClick = { menu = false; vm.deletePage(docId, index) },
                    )
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
    var archival by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var showPw by remember { mutableStateOf(false) }
    val busy by vm.busy
    val progress by vm.progress

    fun options(): ExportOptions {
        s.pageSize = pageSize; s.quality = quality; s.ocr = ocr; s.ocrLanguage = ocrLang
        return ExportOptions(pageSize, quality, ocr && !asJpeg, ocrLang, if (protect && !asJpeg) password else null, archival = archival && !protect && !asJpeg)
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
                            Text(stringResource(when (ps) {
                                PageSize.A4 -> R.string.size_a4; PageSize.LETTER -> R.string.size_letter; PageSize.LEGAL -> R.string.size_legal
                                PageSize.A5 -> R.string.size_a5; PageSize.FIT -> R.string.size_fit
                            }))
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
                SwitchRow(R.string.pdfa_title, R.string.pdfa_hint, archival && !protect) { archival = it; if (it) protect = false }
                SwitchRow(R.string.password_title, R.string.password_hint, protect) { protect = it; if (it) archival = false }
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
            // What will be shared: page count and an estimated size, before tapping Share.
            val pageCount = vm.doc(docId)?.pages?.size ?: 0
            val estimate by produceState<Long?>(null, quality, asJpeg) {
                value = null
                value = vm.estimateSize(docId, ExportOptions(pageSize, quality))
            }
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                val pagesText = pluralStringResource(R.plurals.page_count, pageCount, pageCount)
                val sizeText = estimate?.let { stringResource(R.string.estimated_size, formatSize(it)) } ?: stringResource(R.string.estimating_size)
                Text(
                    "$pagesText · $sizeText",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(12.dp).semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite },
                )
            }
            Spacer(Modifier.height(12.dp))
            // Export in progress: shown the moment Share/Save is tapped, with a real Cancel.
            val ex = vm.exportState.value
            if (ex != null) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp).semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite }) {
                        if (ex.total > 0) {
                            LinearProgressIndicator(progress = { ex.page.toFloat() / ex.total }, modifier = Modifier.fillMaxWidth())
                            val page = (ex.page + 1).coerceAtMost(ex.total)
                            Text(
                                stringResource(if (ex.readingText) R.string.export_reading_text else R.string.export_preparing, page, ex.total),
                                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp),
                            )
                        } else {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            Text(stringResource(R.string.export_starting), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
                        }
                        OutlinedButton(onClick = { vm.cancelExport() }, modifier = Modifier.align(Alignment.End).padding(top = 4.dp).heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.cancel))
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
            val canGo = ex == null && busy == 0 && (!protect || asJpeg || password.isNotEmpty())
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
    Text(stringResource(res), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp).semantics { heading() })
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
