// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.ui

import androidx.compose.runtime.mutableIntStateOf
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.fastkiwi.scan.R
import com.fastkiwi.scan.core.ReadingOrder
import com.fastkiwi.scan.core.Spine
import com.fastkiwi.scan.imaging.BookPending
import com.fastkiwi.scan.ui.theme.Dimens
import com.fastkiwi.scan.ui.theme.KiwiAccent
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Book mode (A4): the photographed spread, rectified, with the detected spine as a line the user
 * can drag (each end on its own, or the whole line), the page order for this document, and an
 * honest note that curved pages are not flattened. Shown over the camera.
 */
@Composable
fun BookSplitScreen(p: BookPending, onConfirm: (Spine, ReadingOrder) -> Unit, onRetake: () -> Unit) {
    BackHandler { onRetake() }
    var top by remember(p) { mutableFloatStateOf(p.spine.top) }
    var bottom by remember(p) { mutableFloatStateOf(p.spine.bottom) }
    var order by remember(p) { mutableStateOf(p.order) }
    val image = remember(p) { p.preview.asImageBitmap() }
    val aspect = p.preview.width.toFloat() / p.preview.height
    val spineLabel = stringResource(R.string.book_spine)
    val spineState = stringResource(R.string.book_spine_state, (((top + bottom) / 2) * 100).roundToInt())
    val moveLeft = stringResource(R.string.book_spine_left)
    val moveRight = stringResource(R.string.book_spine_right)
    fun shift(d: Float) {
        top = (top + d).coerceIn(0.1f, 0.9f)
        bottom = (bottom + d).coerceIn(0.1f, 0.9f)
    }

    Column(
        Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding().padding(Dimens.l).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(Dimens.m),
    ) {
        Text(stringResource(R.string.book_title), style = MaterialTheme.typography.titleLarge, color = Color.White)
        Text(stringResource(R.string.book_drag_hint), style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.85f))
        BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(aspect)) {
            val density = LocalDensity.current
            val wPx = with(density) { maxWidth.toPx() }
            val hPx = with(density) { maxHeight.toPx() }
            // The image is never mirrored in RTL, so the drag works in image (left-to-right) space.
            Image(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
            var grab by remember { mutableIntStateOf(0) } // 1 top end, 2 bottom end, 3 whole line
            Canvas(
                Modifier
                    .fillMaxSize()
                    .semantics {
                        contentDescription = spineLabel
                        stateDescription = spineState
                        customActions = listOf(
                            CustomAccessibilityAction(moveLeft) { shift(-0.01f); true },
                            CustomAccessibilityAction(moveRight) { shift(0.01f); true },
                        )
                    }
                    .pointerInput(p) {
                        detectDragGestures(
                            onDragStart = { o ->
                                val v = o.y / hPx
                                val x = (top + (bottom - top) * v) * wPx
                                val handle = 32.dp.toPx()
                                grab = when {
                                    o.y < handle * 1.5f && abs(o.x - top * wPx) < handle -> 1
                                    o.y > hPx - handle * 1.5f && abs(o.x - bottom * wPx) < handle -> 2
                                    abs(o.x - x) < handle -> 3
                                    else -> 0
                                }
                            },
                            onDragEnd = { grab = 0 },
                        ) { change, drag ->
                            val d = drag.x / wPx
                            when (grab) {
                                1 -> top = (top + d).coerceIn(0.1f, 0.9f)
                                2 -> bottom = (bottom + d).coerceIn(0.1f, 0.9f)
                                3 -> shift(d)
                            }
                            if (grab != 0) change.consume()
                        }
                    },
            ) {
                val a = Offset(top * size.width, 0f)
                val b = Offset(bottom * size.width, size.height)
                drawLine(Color.Black.copy(alpha = 0.5f), a, b, strokeWidth = 7.dp.toPx())
                drawLine(KiwiAccent, a, b, strokeWidth = 3.dp.toPx())
                val r = 12.dp.toPx()
                drawCircle(KiwiAccent, r, a.copy(y = r))
                drawCircle(KiwiAccent, r, b.copy(y = size.height - r))
                drawCircle(Color.Black, r, a.copy(y = r), style = Stroke(2.dp.toPx()))
                drawCircle(Color.Black, r, b.copy(y = size.height - r), style = Stroke(2.dp.toPx()))
            }
        }
        Text(stringResource(R.string.book_order), style = MaterialTheme.typography.titleSmall, color = Color.White)
        // Options listed in reading order of the UI language; the choice is stored per document.
        val options = listOf(ReadingOrder.RTL, ReadingOrder.LTR)
        ChoiceSegmentedRow(
            options.map { stringResource(if (it == ReadingOrder.RTL) R.string.book_order_rtl else R.string.book_order_ltr) },
            options.indexOf(order),
            { order = options[it] },
        )
        Text(stringResource(R.string.book_no_dewarp), style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.85f))
        Spacer(Modifier.height(Dimens.s))
        Button(onClick = { onConfirm(Spine(top, bottom), order) }, modifier = Modifier.fillMaxWidth().heightIn(min = Dimens.buttonLarge)) {
            Text(stringResource(R.string.book_split_add))
        }
        OutlinedButton(onClick = onRetake, modifier = Modifier.fillMaxWidth().heightIn(min = Dimens.button)) {
            Text(stringResource(R.string.book_retake), color = Color.White)
        }
    }
}
