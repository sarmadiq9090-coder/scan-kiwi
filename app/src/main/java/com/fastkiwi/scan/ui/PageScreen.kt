// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fastkiwi.scan.R
import com.fastkiwi.scan.core.Filter
import com.fastkiwi.scan.ui.theme.Dimens

fun filterLabel(f: Filter): Int = when (f) {
    Filter.ORIGINAL -> R.string.filter_original
    Filter.ENHANCED -> R.string.filter_enhanced
    Filter.WHITEN -> R.string.filter_whiten
    Filter.GRAYSCALE -> R.string.filter_gray
    Filter.BW -> R.string.filter_bw
}

/**
 * Page editor (design spec §3.5): swipe between pages, filter chips, and a
 * labelled toolbar (crop, rotate, signature, retake); rotate-left and delete in ⋮.
 */
@Composable
fun PageScreen(vm: AppViewModel, docId: String, startIndex: Int, snackbar: SnackbarHostState) {
    val docs by vm.docs.collectAsStateWithLifecycle()
    val doc = docs.firstOrNull { it.doc.id == docId }?.doc ?: vm.doc(docId)
    var confirmDelete by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val busy by vm.busy
    if (doc == null || doc.pages.isEmpty()) {
        LaunchedEffect(Unit) { vm.back() }
        return
    }
    val pager = rememberPagerState(initialPage = startIndex.coerceIn(0, doc.pages.lastIndex)) { doc.pages.size }
    val index = pager.currentPage.coerceIn(0, doc.pages.lastIndex)
    val page = doc.pages[index]

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            KiwiTopBar(
                stringResource(R.string.page_n_of, index + 1, doc.pages.size), NavIcon.BACK, { vm.back() },
                titleModifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            ) {
                TooltipIconButton(KiwiIcons.undo(), stringResource(R.string.undo), { vm.undo(docId) }, enabled = (vm.undoCounts[docId] ?: 0) > 0)
                Box {
                    TooltipIconButton(KiwiIcons.more(), stringResource(R.string.more_options), { menu = true })
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.rotate_left)) },
                            leadingIcon = { Icon(KiwiIcons.rotateLeft(), null) },
                            onClick = { menu = false; vm.editPage(docId, page.id) { it.copy(rotation = (it.rotation + 270) % 360) } },
                        )
                        // BLACKBOX-2.0 #2, #5: the text screen and glare fusion, by name, from the page itself.
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.docs_text)) },
                            leadingIcon = { Icon(DocsIcons.copy(), null) },
                            onClick = { menu = false; vm.open(Screen.Text(docId, page.id)) },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.glare_remove)) },
                            leadingIcon = { Icon(KiwiIcons.retake(), null) },
                            onClick = { menu = false; vm.removeGlare(docId, page.id) },
                        )
                        // 2.0-cam2: automatic alignment on demand (PRD §4.2).
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.straighten)) },
                            leadingIcon = { Icon(KiwiIcons.crop(), null) },
                            onClick = { menu = false; vm.straightenPage(docId, page.id) },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.delete_page), color = MaterialTheme.colorScheme.error) },
                            leadingIcon = { Icon(KiwiIcons.delete(), null, tint = MaterialTheme.colorScheme.error) },
                            onClick = { menu = false; confirmDelete = true },
                        )
                    }
                }
            }
        },
        bottomBar = {
            ActionToolbar {
                ToolbarAction(KiwiIcons.crop(), stringResource(R.string.crop), { vm.open(Screen.Crop(docId, page.id)) })
                ToolbarAction(KiwiIcons.rotateRight(), stringResource(R.string.rotate), {
                    vm.editPage(docId, page.id) { it.copy(rotation = (it.rotation + 90) % 360) }
                })
                ToolbarAction(KiwiIcons.signature(), stringResource(R.string.signature), { vm.open(Screen.Sign(docId, page.id)) })
                ToolbarAction(KiwiIcons.retake(), stringResource(R.string.retake), { vm.open(Screen.Camera(docId, retakePageId = page.id)) })
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow)) {
                HorizontalPager(pager, Modifier.fillMaxSize(), key = { doc.pages[it].id }) { i ->
                    val p = doc.pages[i]
                    val file = vm.store.outFile(docId, p.id)
                    val aspect by androidx.compose.runtime.produceState(0.707f, file, p.revision) {
                        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            com.fastkiwi.scan.imaging.BitmapIO.imageSize(file)?.let { (w, h) -> w.toFloat() / h } ?: 0.707f
                        }
                    }
                    Box(Modifier.fillMaxSize().padding(Dimens.l), contentAlignment = Alignment.Center) {
                        // The page as a sheet of paper (level-1 shadow), never tinted.
                        Surface(shadowElevation = 1.dp, color = Color.White, modifier = Modifier.aspectRatio(aspect, matchHeightConstraintsFirst = aspect < 1f)) {
                            FileImage(file, p.revision, 2048, stringResource(R.string.page_n_of, i + 1, doc.pages.size), Modifier.fillMaxSize())
                        }
                    }
                }
                if (busy > 0) CircularProgressIndicator(Modifier.align(Alignment.Center))
                if (doc.pages.size > 1) PageDots(doc.pages.size, index, Modifier.align(Alignment.BottomCenter).padding(bottom = Dimens.s))
            }
            // 2.0-cam: quality warning chip (PRD §4.3): retake, re-crop from the kept original, or dismiss.
            QualityChip(
                page.quality,
                onRetake = { vm.open(Screen.Camera(docId, retakePageId = page.id)) },
                onRecrop = { vm.open(Screen.Crop(docId, page.id)) },
                onDismiss = { vm.dismissQuality(docId, page.id) },
                modifier = Modifier.padding(top = Dimens.s),
            )
            FilterRow(page.filter, enabled = busy == 0) { f -> if (page.filter != f) vm.editPage(docId, page.id) { it.copy(filter = f) } }
        }
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = stringResource(R.string.delete_page),
            body = stringResource(R.string.delete_page_body),
            confirm = stringResource(R.string.delete),
            onDismiss = { confirmDelete = false },
        ) {
            vm.deletePage(docId, index)
            if (doc.pages.size <= 1) vm.back()
        }
    }
}

@Composable
private fun PageDots(count: Int, current: Int, modifier: Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(count.coerceAtMost(12)) { i ->
            Box(
                Modifier.size(if (i == current) 8.dp else 6.dp)
                    .background(if (i == current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, CircleShape),
            )
        }
    }
}

/** Single-select filter chips; a fade at the end edge shows that the row scrolls. */
@Composable
private fun FilterRow(selected: Filter, enabled: Boolean, onSelect: (Filter) -> Unit) {
    val bg = MaterialTheme.colorScheme.surface
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Box(Modifier.fillMaxWidth().padding(vertical = Dimens.s)) {
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(Dimens.s),
            contentPadding = PaddingValues(horizontal = Dimens.l),
        ) {
            items(Filter.entries) { f ->
                val isSel = selected == f
                FilterChip(
                    selected = isSel,
                    onClick = { onSelect(f) },
                    label = { Text(stringResource(filterLabel(f))) },
                    leadingIcon = if (isSel) ({ Icon(KiwiIcons.check(), null, Modifier.size(18.dp)) }) else null,
                    enabled = enabled,
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer),
                )
            }
        }
        val fade = if (rtl) listOf(bg, Color.Transparent) else listOf(Color.Transparent, bg)
        Box(Modifier.align(Alignment.CenterEnd).width(48.dp).size(width = 48.dp, height = 48.dp).background(Brush.horizontalGradient(fade)))
    }
}
