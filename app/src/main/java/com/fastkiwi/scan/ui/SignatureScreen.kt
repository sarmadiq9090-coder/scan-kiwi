// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import com.fastkiwi.scan.ui.theme.Dimens
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.fastkiwi.scan.R
import com.fastkiwi.scan.data.Stamp
import com.fastkiwi.scan.imaging.BitmapIO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

/** Draw your signature once (kept only on this phone), then place it on any page. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignatureScreen(vm: AppViewModel, docId: String, pageId: String) {
    var version by remember { mutableIntStateOf(0) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var drawing by remember { mutableStateOf(!vm.store.signatureFile.exists()) }
    val signature by produceState<ImageBitmap?>(null, version) {
        value = withContext(Dispatchers.IO) { BitmapIO.decodePng(vm.store.signatureFile)?.asImageBitmap() }
    }
    val page = vm.doc(docId)?.pages?.firstOrNull { it.id == pageId }

    Scaffold(topBar = { KiwiTopBar(stringResource(R.string.signature), NavIcon.CLOSE, { vm.back() }) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(16.dp)) {
            if (drawing || signature == null) {
                SignaturePad(onCancel = if (signature != null) ({ drawing = false }) else null) { bmp ->
                    // PNG encoding of a large pad takes a while: keep it off the main thread.
                    scope.launch {
                        withContext(Dispatchers.IO) { BitmapIO.savePng(bmp, vm.store.signatureFile) }
                        bmp.recycle()
                        version++
                        drawing = false
                    }
                }
            } else if (page != null) {
                Placement(vm, docId, page.id, page.revision, signature!!, hasStamps = page.stamps.isNotEmpty(), onRedraw = { drawing = true })
            }
        }
    }
}

@Composable
private fun SignaturePad(onCancel: (() -> Unit)?, onSave: (Bitmap) -> Unit) {
    val strokes = remember { mutableStateListOf<List<Offset>>() }
    var current by remember { mutableStateOf<List<Offset>>(emptyList()) }
    val ink = Color(0xFF1A237E)
    val density = LocalDensity.current
    val strokePx = with(density) { 3.dp.toPx() }
    var padSize by remember { mutableStateOf(IntSize.Zero) }
    val padDesc = stringResource(R.string.signature_pad_desc)

    Text(stringResource(R.string.signature_draw_hint), style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.width(8.dp))
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(2f)
            .padding(vertical = 12.dp)
            .background(Color.White, RoundedCornerShape(12.dp))
            .border(2.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp)),
    ) {
        Canvas(
            Modifier
                .fillMaxSize()
                .onSizeChanged { padSize = it }
                .semantics { contentDescription = padDesc }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { current = listOf(it) },
                        onDrag = { change, _ -> change.consume(); current = current + change.position },
                        onDragEnd = { if (current.size > 1) strokes.add(current); current = emptyList() },
                        onDragCancel = { current = emptyList() },
                    )
                }
                .pointerInput(Unit) { detectTapGestures { strokes.add(listOf(it, it + Offset(1f, 1f))) } },
        ) {
            // Baseline guide.
            drawLine(Color(0xFFB0B0B0), Offset(24f, size.height * 0.72f), Offset(size.width - 24f, size.height * 0.72f), 2f)
            for (s in strokes + listOf(current)) drawPath(pathOf(s), ink, style = Stroke(strokePx, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
    // Footer: secondary «مسح», tertiary «إلغاء», primary «حفظ» at the end.
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Dimens.s), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = { strokes.clear() }, modifier = Modifier.weight(1f).heightIn(min = Dimens.button)) { Text(stringResource(R.string.signature_clear)) }
        if (onCancel != null) TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = Dimens.button)) { Text(stringResource(R.string.cancel)) }
        Button(onClick = {
            renderSignature(strokes, padSize, strokePx * 2f, ink)?.let(onSave)
        }, enabled = strokes.isNotEmpty(), modifier = Modifier.weight(1f).heightIn(min = Dimens.button)) { Text(stringResource(R.string.save)) }
    }
}

private fun pathOf(points: List<Offset>) = Path().apply {
    if (points.isEmpty()) return@apply
    moveTo(points[0].x, points[0].y)
    for (i in 1 until points.size) {
        val a = points[i - 1]; val b = points[i]
        quadraticTo(a.x, a.y, (a.x + b.x) / 2f, (a.y + b.y) / 2f)
    }
    lineTo(points.last().x, points.last().y)
}

/** Renders the strokes at 2x into a transparent bitmap cropped to the ink. */
private fun renderSignature(strokes: List<List<Offset>>, pad: IntSize, strokePx: Float, ink: Color): Bitmap? {
    val all = strokes.flatten()
    if (all.isEmpty() || pad.width == 0) return null
    val scale = 2f
    val m = strokePx * 2
    val minX = max(0f, all.minOf { it.x } - m); val maxX = min(pad.width.toFloat(), all.maxOf { it.x } + m)
    val minY = max(0f, all.minOf { it.y } - m); val maxY = min(pad.height.toFloat(), all.maxOf { it.y } + m)
    val w = max(1, ((maxX - minX) * scale).toInt())
    val h = max(1, ((maxY - minY) * scale).toInt())
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(bmp)
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = strokePx * scale / 2f
        strokeCap = android.graphics.Paint.Cap.ROUND
        strokeJoin = android.graphics.Paint.Join.ROUND
        color = android.graphics.Color.argb((ink.alpha * 255).toInt(), (ink.red * 255).toInt(), (ink.green * 255).toInt(), (ink.blue * 255).toInt())
    }
    for (s in strokes) {
        val p = android.graphics.Path()
        p.moveTo((s[0].x - minX) * scale, (s[0].y - minY) * scale)
        for (i in 1 until s.size) {
            val a = s[i - 1]; val b = s[i]
            p.quadTo((a.x - minX) * scale, (a.y - minY) * scale, ((a.x + b.x) / 2f - minX) * scale, ((a.y + b.y) / 2f - minY) * scale)
        }
        p.lineTo((s.last().x - minX) * scale, (s.last().y - minY) * scale)
        c.drawPath(p, paint)
    }
    return bmp
}

@Composable
private fun ColumnScope.Placement(vm: AppViewModel, docId: String, pageId: String, revision: Int, signature: ImageBitmap, hasStamps: Boolean, onRedraw: () -> Unit) {
    val pageImg by produceState<ImageBitmap?>(null, docId, pageId, revision) {
        value = withContext(Dispatchers.IO) { BitmapIO.decodeFile(vm.store.outFile(docId, pageId), 1600)?.asImageBitmap() }
    }
    var cx by remember { mutableFloatStateOf(0.72f) }
    var cy by remember { mutableFloatStateOf(0.86f) }
    var width by remember { mutableFloatStateOf(0.32f) }
    val primary = MaterialTheme.colorScheme.primary
    val placeDesc = stringResource(R.string.signature_place_desc)

    Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
        val img = pageImg
        if (img == null) {
            CircularProgressIndicator()
        } else {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val density = LocalDensity.current
                val vw = with(density) { maxWidth.toPx() }
                val vh = with(density) { maxHeight.toPx() }
                val k = minOf(vw / img.width, vh / img.height)
                val dw = img.width * k
                val dh = img.height * k
                val ox = (vw - dw) / 2f
                val oy = (vh - dh) / 2f
                Canvas(
                    Modifier.fillMaxSize().semantics { contentDescription = placeDesc }.pointerInput(dw, dh) {
                        detectDragGestures { change, drag ->
                            change.consume()
                            cx = (cx + drag.x / dw).coerceIn(0f, 1f)
                            cy = (cy + drag.y / dh).coerceIn(0f, 1f)
                        }
                    },
                ) {
                    drawImage(img, dstOffset = IntOffset(ox.toInt(), oy.toInt()), dstSize = IntSize(dw.toInt(), dh.toInt()))
                    val sw = width * dw
                    val sh = sw * signature.height / signature.width
                    val l = ox + cx * dw - sw / 2
                    val t = oy + cy * dh - sh / 2
                    drawImage(signature, dstOffset = IntOffset(l.toInt(), t.toInt()), dstSize = IntSize(sw.toInt(), sh.toInt()))
                    // Selected stamp: dashed primary frame with two resize knobs.
                    drawRect(
                        primary, topLeft = Offset(l, t), size = androidx.compose.ui.geometry.Size(sw, sh),
                        style = Stroke(2.dp.toPx(), pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(10f, 8f))),
                    )
                    drawCircle(Color.White, 6.dp.toPx(), Offset(l, t)); drawCircle(primary, 6.dp.toPx(), Offset(l, t), style = Stroke(2.dp.toPx()))
                    drawCircle(Color.White, 6.dp.toPx(), Offset(l + sw, t + sh)); drawCircle(primary, 6.dp.toPx(), Offset(l + sw, t + sh), style = Stroke(2.dp.toPx()))
                }
            }
        }
        HintPill(stringResource(R.string.signature_place_hint), Modifier.align(Alignment.TopCenter).padding(Dimens.s))
    }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Dimens.l)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.signature_size), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                if (hasStamps) {
                    TextButton(onClick = {
                        vm.editPage(docId, pageId) { it.copy(stamps = emptyList()) }
                        vm.back()
                    }, modifier = Modifier.heightIn(min = Dimens.button)) { Text(stringResource(R.string.signature_remove), color = MaterialTheme.colorScheme.error) }
                }
            }
            Slider(value = width, onValueChange = { width = it }, valueRange = 0.12f..0.7f)
            Spacer(Modifier.height(Dimens.s))
            ButtonPair(
                secondary = { m -> OutlinedButton(onClick = onRedraw, modifier = m) { Text(stringResource(R.string.signature_redraw)) } },
                primary = { m ->
                    Button(onClick = {
                        vm.editPage(docId, pageId) { it.copy(stamps = it.stamps + Stamp(cx, cy, width)) }
                        vm.back()
                    }, modifier = m) { Icon(KiwiIcons.check(), null, Modifier.size(20.dp)); Spacer(Modifier.width(Dimens.s)); Text(stringResource(R.string.done)) }
                },
            )
        }
    }
}
