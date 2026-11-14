package com.fastkiwi.scan.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.NavigateBefore
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material.icons.automirrored.filled.RotateLeft
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fastkiwi.scan.R
import com.fastkiwi.scan.core.Filter

fun filterLabel(f: Filter): Int = when (f) {
    Filter.ORIGINAL -> R.string.filter_original
    Filter.ENHANCED -> R.string.filter_enhanced
    Filter.WHITEN -> R.string.filter_whiten
    Filter.GRAYSCALE -> R.string.filter_gray
    Filter.BW -> R.string.filter_bw
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PageScreen(vm: AppViewModel, docId: String, startIndex: Int, snackbar: SnackbarHostState) {
    val docs by vm.docs.collectAsStateWithLifecycle()
    val doc = docs.firstOrNull { it.doc.id == docId }?.doc ?: vm.doc(docId)
    var index by remember { mutableStateOf(startIndex) }
    var confirmDelete by remember { mutableStateOf(false) }
    val busy by vm.busy
    if (doc == null || doc.pages.isEmpty()) {
        androidx.compose.runtime.LaunchedEffect(Unit) { vm.back() }
        return
    }
    index = index.coerceIn(0, doc.pages.lastIndex)
    val page = doc.pages[index]

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
                title = { Text(stringResource(R.string.page_n_of, index + 1, doc.pages.size)) },
                actions = {
                    IconButton(onClick = { index-- }, enabled = index > 0) { Icon(Icons.AutoMirrored.Filled.NavigateBefore, stringResource(R.string.previous_page)) }
                    IconButton(onClick = { index++ }, enabled = index < doc.pages.lastIndex) { Icon(Icons.AutoMirrored.Filled.NavigateNext, stringResource(R.string.next_page)) }
                },
            )
        },
        bottomBar = {
            BottomAppBar {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    IconButton(onClick = { vm.open(Screen.Crop(docId, page.id)) }) { Icon(Icons.Filled.Crop, stringResource(R.string.crop)) }
                    IconButton(onClick = { vm.editPage(docId, page.id) { it.copy(rotation = (it.rotation + 270) % 360) } }) {
                        Icon(Icons.AutoMirrored.Filled.RotateLeft, stringResource(R.string.rotate_left))
                    }
                    IconButton(onClick = { vm.editPage(docId, page.id) { it.copy(rotation = (it.rotation + 90) % 360) } }) {
                        Icon(Icons.AutoMirrored.Filled.RotateRight, stringResource(R.string.rotate_right))
                    }
                    IconButton(onClick = { vm.open(Screen.Camera(docId, retakePageId = page.id)) }) { Icon(Icons.Filled.Cameraswitch, stringResource(R.string.retake)) }
                    IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, stringResource(R.string.delete_page)) }
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                FileImage(
                    vm.store.outFile(docId, page.id), page.revision, 2048,
                    stringResource(R.string.page_n_of, index + 1, doc.pages.size),
                    Modifier.fillMaxSize(),
                )
                if (busy > 0) CircularProgressIndicator()
            }
            Text(
                stringResource(R.string.filter), style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            LazyRow(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
            ) {
                items(Filter.entries) { f ->
                    FilterChip(
                        selected = page.filter == f,
                        onClick = { if (page.filter != f) vm.editPage(docId, page.id) { it.copy(filter = f) } },
                        label = { Text(stringResource(filterLabel(f))) },
                        enabled = busy == 0,
                    )
                }
            }
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
