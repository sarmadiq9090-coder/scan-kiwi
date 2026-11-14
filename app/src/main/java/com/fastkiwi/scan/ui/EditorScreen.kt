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
import androidx.compose.runtime.produceState
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExposedDropdownMenuAnchorType
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
        LocalFormat.metaSep(androidx.compose.ui.platform.LocalConfiguration.current.locales[0]) +
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

/**
 * Export sheet (design spec §3.6): segmented format and quality, a page-size
 * dropdown, grouped option rows (OCR, PDF/A, password; PDF/A and password
 * exclude each other), live page count and size, and Save / Share at the bottom.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportSheet(vm: AppViewModel, docId: String, onDismiss: () -> Unit) {
    val s = vm.settings
    val ctx = LocalContext.current
    var asJpeg by remember { mutableStateOf(false) }
    var pageSize by remember { mutableStateOf(s.pageSize) }
    var quality by remember { mutableStateOf(s.quality) }
    var ocr by remember { mutableStateOf(s.ocr) }
    var ocrLang by remember { mutableStateOf(s.ocrLanguage) }
    var protect by remember { mutableStateOf(false) }
    var archival by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var showPw by remember { mutableStateOf(false) }
    var sizeMenu by remember { mutableStateOf(false) }
    val busy by vm.busy

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

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        // Options scroll; the footer (Save / Share, or progress with Cancel) stays fixed at the
        // bottom of the sheet so the primary action is always in the thumb zone (spec §3, §3.6).
        Column(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = Dimens.sheet).padding(bottom = Dimens.l)) {
                // Header: title, then what will be shared (live).
                Text(stringResource(R.string.export), style = MaterialTheme.typography.titleLarge)
                val pageCount = vm.doc(docId)?.pages?.size ?: 0
                val estimate by produceState<Long?>(null, quality, asJpeg) {
                    value = null
                    value = vm.estimateSize(docId, ExportOptions(pageSize, quality))
                }
                val pagesText = pluralStringResource(R.plurals.page_count, pageCount, pageCount)
                val sizeText = estimate?.let { stringResource(R.string.estimated_size, formatSize(it)) } ?: stringResource(R.string.estimating_size)
                Text(
                    pagesText + LocalFormat.metaSep(androidx.compose.ui.platform.LocalConfiguration.current.locales[0]) + sizeText,
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite },
                )

                Label(R.string.format)
                ChoiceSegmentedRow(listOf("PDF", stringResource(R.string.format_jpg_images)), if (asJpeg) 1 else 0, { asJpeg = it == 1 })

                if (!asJpeg) {
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

                Label(R.string.quality)
                ChoiceSegmentedRow(
                    listOf(stringResource(R.string.quality_small), stringResource(R.string.quality_balanced), stringResource(R.string.quality_high)),
                    quality.ordinal, { quality = Quality.entries[it] },
                )
                Text(
                    stringResource(when (quality) { Quality.SMALL -> R.string.quality_small_hint; Quality.BALANCED -> R.string.quality_balanced_hint; Quality.HIGH -> R.string.quality_high_hint }),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Dimens.xs),
                )

                if (!asJpeg) {
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

            }
            Column(Modifier.fillMaxWidth().padding(horizontal = Dimens.sheet).padding(top = Dimens.s, bottom = Dimens.xl)) {
                // Footer: progress with a real Cancel while exporting, otherwise Save / Share.
                val ex = vm.exportState.value
                if (ex != null) {
                    Column(Modifier.semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite }) {
                        if (ex.total > 0) {
                            LinearProgressIndicator(progress = { ex.page.toFloat() / ex.total }, modifier = Modifier.fillMaxWidth())
                            val page = (ex.page + 1).coerceAtMost(ex.total)
                            Text(
                                stringResource(if (ex.readingText) R.string.export_reading_text else R.string.export_preparing, page, ex.total),
                                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = Dimens.s),
                            )
                        } else {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            Text(stringResource(R.string.export_starting), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = Dimens.s))
                        }
                        TextButton(onClick = { vm.cancelExport() }, modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.cancel))
                        }
                    }
                } else {
                    val canGo = busy == 0 && (!protect || asJpeg || password.isNotEmpty())
                    ButtonPair(
                        secondary = { m ->
                            OutlinedButton(onClick = {
                                if (asJpeg) folderLauncher.launch(null) else saveLauncher.launch(vm.suggestedFileName(docId))
                            }, enabled = canGo, modifier = m) {
                                Icon(KiwiIcons.download(), null, Modifier.size(20.dp)); Spacer(Modifier.width(Dimens.s)); Text(stringResource(R.string.save_to_device))
                            }
                        },
                        primary = { m ->
                            Button(onClick = { vm.share(docId, options(), asJpeg) }, enabled = canGo, modifier = m) {
                                Icon(KiwiIcons.share(), null, Modifier.size(20.dp)); Spacer(Modifier.width(Dimens.s)); Text(stringResource(R.string.share))
                            }
                        },
                    )
                }
            }
        }
    }
}

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
