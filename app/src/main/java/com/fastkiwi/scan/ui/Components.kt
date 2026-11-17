// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.ui

import android.graphics.Bitmap
import android.text.format.Formatter
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.layout.padding
import com.fastkiwi.scan.R
import com.fastkiwi.scan.imaging.BitmapIO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Memory cache for decoded page images, keyed by file + revision. */
object ImageCache {
    private val lru = object : LruCache<String, Bitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun get(file: File, key: Any, maxSide: Int): ImageBitmap? {
        val k = "${file.absolutePath}|$key|$maxSide|${file.lastModified()}"
        lru.get(k)?.let { return it.asImageBitmap() }
        if (!file.exists()) return null
        val bmp = BitmapIO.decodeFile(file, maxSide) ?: return null
        lru.put(k, bmp)
        return bmp.asImageBitmap()
    }
}

@Composable
fun FileImage(
    file: File,
    key: Any,
    maxSide: Int,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
) {
    val img by produceState<ImageBitmap?>(null, file, key, maxSide) {
        value = withContext(Dispatchers.IO) { runCatching { ImageCache.get(file, key, maxSide) }.getOrNull() }
    }
    Box(modifier) {
        val i = img
        if (i != null) {
            Image(i, contentDescription, Modifier.fillMaxSize(), contentScale = contentScale)
        } else {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant))
        }
    }
}

/**
 * For every [AlertDialog] that holds a text field. With the platform's default dialog width the
 * window is wrap-content, and Android measures it twice on each layout pass (first at its preferred
 * dialog width, 320 dp, then at the full width, because the dialog's minimum width does not fit in 320 dp).
 * A text field measured at a new width asks for another layout, so the dialog never stopped laying
 * itself out (BLACKBOX-2.0 #1: «مجلد جديد» kept the app busy; under Robolectric the same framework code
 * never goes idle). A full-width window, inset like a Material dialog, is measured once.
 */
val TextFieldDialogProperties = DialogProperties(usePlatformDefaultWidth = false)

/** The side margins a Material dialog has with the platform width (pair with [TextFieldDialogProperties]). */
val TextFieldDialogModifier: Modifier = Modifier.padding(horizontal = 24.dp)

@Composable
fun RenameDialog(current: String, suggestion: String? = null, onDismiss: () -> Unit, onRename: (String) -> Unit) {
    var value by remember { mutableStateOf(TextFieldValue(current, TextRange(0, current.length))) }
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = TextFieldDialogProperties,
        modifier = TextFieldDialogModifier,
        title = { Text(stringResource(R.string.rename)) },
        text = {
            androidx.compose.foundation.layout.Column {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.document_name)) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                )
                // Name suggested from the first recognised words (after OCR).
                if (suggestion != null && suggestion != value.text) {
                    androidx.compose.material3.SuggestionChip(
                        onClick = { value = TextFieldValue(suggestion, TextRange(suggestion.length)) },
                        label = { Text(stringResource(R.string.name_suggestion, suggestion), maxLines = 1) },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onRename(value.text); onDismiss() }, enabled = value.text.isNotBlank()) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
fun ConfirmDialog(title: String, body: String, confirm: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            TextButton(onClick = { onConfirm(); onDismiss() }) {
                Text(confirm, color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
fun formatSize(bytes: Long): String = LocalFormat.size(LocalContext.current, bytes)
