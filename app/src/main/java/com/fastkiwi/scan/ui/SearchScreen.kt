// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fastkiwi.scan.R
import com.fastkiwi.scan.data.ScanDocument
import com.fastkiwi.scan.ui.theme.Dimens
import kotlinx.coroutines.delay

/**
 * Search across all documents (OCR-07): names and the recognised text of every page, Arabic-aware
 * (harakat, alef / ya / ta marbuta variants and digit forms do not matter). A page result shows the
 * document, the page and the matching line; opening it shows the page with the words highlighted.
 */
@Composable
fun SearchScreen(vm: AppViewModel, snackbar: SnackbarHostState) {
    var query by rememberSaveable { mutableStateOf("") }
    val docs by vm.docs.collectAsStateWithLifecycle()
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val results by produceState<AppViewModel.SearchResults?>(null, query, docs) {
        if (query.isBlank()) { value = null; return@produceState }
        delay(120) // typing: search once the user pauses
        value = vm.search(query)
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Surface(color = MaterialTheme.colorScheme.surface) {
                Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = Dimens.xs, end = Dimens.screen, top = Dimens.s, bottom = Dimens.s), verticalAlignment = Alignment.CenterVertically) {
                    TooltipIconButton(KiwiIcons.back(), stringResource(R.string.back), { vm.back() })
                    TextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.weight(1f).heightIn(min = 56.dp).focusRequester(focus),
                        singleLine = true,
                        placeholder = { Text(stringResource(R.string.docs_search_field), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingIcon = { Icon(KiwiIcons.search(), null) },
                        trailingIcon = { if (query.isNotEmpty()) TooltipIconButton(KiwiIcons.close(), stringResource(R.string.clear_search), { query = "" }) },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                        shape = CircleShape,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                        ),
                    )
                }
            }
        },
    ) { padding ->
        val r = results
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(bottom = Dimens.xl)) {
            when {
                query.isBlank() -> item { Hint(stringResource(R.string.docs_search_tip)) }
                r == null -> {}
                r.byName.isEmpty() && r.pages.isEmpty() -> {
                    item {
                        Text(
                            stringResource(R.string.docs_search_no_results, query), style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.fillMaxWidth().padding(Dimens.xl).semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    }
                    item { Hint(stringResource(R.string.docs_search_unread_note)) }
                }
                else -> {
                    if (r.byName.isNotEmpty()) {
                        item { Section(stringResource(R.string.docs_search_names)) }
                        items(r.byName, key = { "n:" + it.id }) { d -> NameHit(vm, d) { vm.open(Screen.Editor(d.id)) } }
                    }
                    if (r.pages.isNotEmpty()) {
                        item { Section(stringResource(R.string.docs_search_pages)) }
                        items(r.pages, key = { "p:" + it.doc.id + ":" + it.page.id }) { h ->
                            PageHitRow(vm, h) { vm.open(Screen.Text(h.doc.id, h.page.id, query)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Section(text: String) {
    Text(
        text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = Dimens.screen, vertical = Dimens.s).padding(top = Dimens.s).semantics { heading() },
    )
}

@Composable
private fun Hint(text: String) {
    Text(
        text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.xl, vertical = Dimens.l),
    )
}

@Composable
private fun Thumb(vm: AppViewModel, docId: String, page: com.fastkiwi.scan.data.ScanPage?) {
    Box(Modifier.size(width = 52.dp, height = 68.dp)) {
        if (page != null) PageThumbnail(vm.store.thumbFile(docId, page.id), page.revision, Modifier.fillMaxSize())
        else Box(Modifier.fillMaxSize().clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.surfaceContainerHigh))
    }
}

@Composable
private fun NameHit(vm: AppViewModel, d: ScanDocument, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 88.dp).clickable(onClick = onClick).padding(horizontal = Dimens.l, vertical = Dimens.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Thumb(vm, d.id, d.pages.firstOrNull())
        Spacer(Modifier.width(Dimens.l))
        Column(Modifier.weight(1f)) {
            Text(d.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(pluralStringResource(R.plurals.page_count, d.pages.size, d.pages.size), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PageHitRow(vm: AppViewModel, h: AppViewModel.PageHit, onClick: () -> Unit) {
    val mark = SpanStyle(fontWeight = FontWeight.Bold, background = MaterialTheme.colorScheme.primaryContainer, color = MaterialTheme.colorScheme.onPrimaryContainer)
    val snippet = remember(h.snippet, h.marks, mark) { highlighted(h.snippet, h.marks, mark) }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 88.dp).clickable(onClick = onClick).padding(horizontal = Dimens.l, vertical = Dimens.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Thumb(vm, h.doc.id, h.page)
        Spacer(Modifier.width(Dimens.l))
        Column(Modifier.weight(1f)) {
            Text(h.doc.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                stringResource(R.string.docs_search_page_n, LocalFormat.count(h.index + 1)),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
            )
            if (h.snippet.isNotEmpty()) Text(snippet, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

internal fun highlighted(text: String, marks: List<IntRange>, style: SpanStyle): AnnotatedString = buildAnnotatedString {
    append(text)
    for (m in marks) if (m.first >= 0 && m.last < text.length) addStyle(style, m.first, m.last + 1)
}
