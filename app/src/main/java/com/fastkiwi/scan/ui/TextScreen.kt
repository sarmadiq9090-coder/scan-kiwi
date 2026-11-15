// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fastkiwi.scan.R
import com.fastkiwi.scan.imaging.BitmapIO
import com.fastkiwi.scan.ui.theme.Dimens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A page's recognised text (OCR-10): the page image (with the words of a search highlighted),
 * the text to read, select and copy, and «تصحيح النص» to correct it. A correction is stored as an
 * overlay on the OCR words (see TextOverlay): the image never changes, each word keeps its box,
 * and search, the PDF text layer and TXT use the corrected words.
 */
@Composable
fun TextScreen(vm: AppViewModel, docId: String, pageId: String, query: String?, snackbar: SnackbarHostState) {
    val docs by vm.docs.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var editing by rememberSaveable { mutableStateOf(false) }
    var edited by rememberSaveable { mutableStateOf("") }
    var reading by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    val state by produceState<AppViewModel.PageTextState?>(null, docs, pageId, reload) { value = vm.pageText(docId, pageId, query) }
    val doc = docs.firstOrNull { it.doc.id == docId }?.doc
    val s = state
    val pageNo = LocalFormat.count((s?.index ?: 0) + 1)
    val undoKey = "$docId/$pageId"
    val copiedMsg = stringResource(R.string.docs_text_copied)
    val label = stringResource(R.string.docs_text_field)

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            KiwiTopBar(
                stringResource(R.string.docs_text_title, pageNo), NavIcon.BACK, { if (editing) editing = false else vm.back() },
                subtitle = doc?.name,
            ) {
                if (!editing) {
                    TooltipIconButton(KiwiIcons.undo(), stringResource(R.string.docs_text_undo), { vm.undoText(docId, pageId) }, enabled = (vm.textUndoCounts[undoKey] ?: 0) > 0)
                    TooltipIconButton(DocsIcons.copy(), stringResource(R.string.docs_text_copy), {
                        val cm = ctx.getSystemService(ClipboardManager::class.java)
                        cm?.setPrimaryClip(ClipData.newPlainText(label, s?.text ?: ""))
                        scope.launch { snackbar.showSnackbar(copiedMsg) }
                    }, enabled = !s?.text.isNullOrEmpty())
                    TooltipIconButton(DocsIcons.edit(), stringResource(R.string.docs_text_edit), {
                        edited = s?.text ?: ""
                        editing = true
                    }, enabled = !s?.words.isNullOrEmpty())
                }
            }
        },
        bottomBar = {
            if (editing) {
                BottomActionBar {
                    ButtonPair(
                        secondary = { m -> OutlinedButton(onClick = { editing = false }, modifier = m) { Text(stringResource(R.string.cancel)) } },
                        primary = { m ->
                            Button(onClick = { vm.saveText(docId, pageId, edited) { ok -> if (ok) editing = false } }, modifier = m) {
                                Icon(KiwiIcons.check(), null, Modifier.size(20.dp)); Spacer(Modifier.width(Dimens.s)); Text(stringResource(R.string.docs_text_save))
                            }
                        },
                    )
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Dimens.screen)) {
            if (editing) {
                Text(stringResource(R.string.docs_text_edit_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = Dimens.s))
                OutlinedTextField(
                    value = edited,
                    onValueChange = { edited = it },
                    label = { Text(label) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 240.dp),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content),
                )
                return@Column
            }
            if (s != null) PageWithHighlights(vm, docId, s, pageNo)
            Spacer(Modifier.size(Dimens.l))
            when {
                s == null -> {}
                s.words.isEmpty() -> Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.docs_text_none_title), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.docs_text_none_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = Dimens.s))
                    Button(
                        onClick = { reading = true; vm.readPageText(docId, pageId) { reading = false; reload++ } },
                        enabled = !reading, modifier = Modifier.heightIn(min = Dimens.button),
                    ) {
                        Icon(KiwiIcons.ocr(), null, Modifier.size(20.dp)); Spacer(Modifier.width(Dimens.s))
                        Text(stringResource(if (reading) R.string.docs_text_reading else R.string.docs_text_read_now))
                    }
                }
                else -> {
                    if (s.corrected) {
                        Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.small, modifier = Modifier.padding(bottom = Dimens.s)) {
                            Text(stringResource(R.string.docs_text_corrected), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = Dimens.m, vertical = Dimens.xs))
                        }
                    }
                    SelectionContainer {
                        Text(
                            s.text, style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content),
                            modifier = Modifier.fillMaxWidth().padding(bottom = Dimens.xl),
                        )
                    }
                }
            }
        }
    }
}

/** The page image, never tinted, with a frame around each word that matched the search. */
@Composable
private fun PageWithHighlights(vm: AppViewModel, docId: String, s: AppViewModel.PageTextState, pageNo: String) {
    val file = vm.store.outFile(docId, s.page.id)
    val dims by produceState<Pair<Int, Int>?>(null, file, s.page.revision) { value = withContext(Dispatchers.IO) { BitmapIO.imageSize(file) } }
    val ratio = dims?.let { it.first.toFloat() / it.second } ?: (1f / 1.414f)
    val desc = stringResource(if (s.highlights.isEmpty()) R.string.docs_text_page_image else R.string.docs_text_matches, pageNo)
    val mark = MaterialTheme.colorScheme.primary
    Row(Modifier.fillMaxWidth().padding(top = Dimens.s), horizontalArrangement = Arrangement.Center) {
        Box(
            Modifier.widthIn(max = 420.dp).fillMaxWidth().aspectRatio(ratio).clip(MaterialTheme.shapes.extraSmall).background(Color.White)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.extraSmall)
                .semantics { contentDescription = desc },
        ) {
            FileImage(file, s.page.revision, 1600, null, Modifier.fillMaxSize())
            if (s.highlights.isNotEmpty()) Canvas(Modifier.fillMaxSize()) {
                for (r in s.highlights) {
                    val tl = Offset(r.left * size.width, r.top * size.height)
                    val sz = Size((r.right - r.left) * size.width, (r.bottom - r.top) * size.height)
                    drawRect(mark.copy(alpha = 0.25f), tl, sz)
                    drawRect(mark, tl, sz, style = Stroke(2.dp.toPx()))
                }
            }
        }
    }
}

/**
 * The TXT entry of the export sheet (OCR-08): the document's text as a UTF-8 file, shared or saved.
 * One composable so the export sheet needs a single line for it.
 */
@Composable
fun TxtExportRow(vm: AppViewModel, docId: String) {
    val busy = vm.exportState.value != null || vm.busy.value > 0
    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) vm.saveTxtTo(docId, uri)
    }
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth().padding(top = Dimens.l)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = Dimens.l, end = Dimens.xs, top = Dimens.s, bottom = Dimens.s), verticalAlignment = Alignment.CenterVertically) {
            Icon(KiwiIcons.ocr(), null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(Dimens.l))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.docs_txt_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.docs_txt_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TooltipIconButton(KiwiIcons.download(), stringResource(R.string.docs_txt_save), { saveLauncher.launch(vm.suggestedTxtName(docId)) }, enabled = !busy)
            TooltipIconButton(KiwiIcons.share(), stringResource(R.string.docs_txt_share), { vm.shareTxt(docId) }, enabled = !busy)
        }
    }
}
