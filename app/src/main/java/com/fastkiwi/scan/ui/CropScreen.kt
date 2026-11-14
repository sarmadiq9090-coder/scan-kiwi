package com.fastkiwi.scan.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.fastkiwi.scan.R
import com.fastkiwi.scan.core.Pt
import com.fastkiwi.scan.core.Quad
import com.fastkiwi.scan.imaging.BitmapIO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Manual corner adjustment with a magnifier under the finger. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CropScreen(vm: AppViewModel, docId: String, pageId: String) {
    val page = vm.doc(docId)?.pages?.firstOrNull { it.id == pageId }
    val src by produceState<Bitmap?>(null, docId, pageId) {
        value = withContext(Dispatchers.IO) { BitmapIO.decodeFile(vm.store.srcFile(docId, pageId), 1600) }
    }
    var corners by remember { mutableStateOf(page?.corners ?: BitmapIO.FULL) }
    var detecting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
                title = { Text(stringResource(R.string.crop)) },
            )
        },
        bottomBar = {
            BottomAppBar {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = {
                        val b = src ?: return@TextButton
                        detecting = true
                        scope.launch {
                            val q = withContext(Dispatchers.Default) { BitmapIO.detect(b) }
                            detecting = false
                            corners = q ?: corners
                        }
                    }) { Icon(Icons.Filled.AutoFixHigh, null); Text(" " + stringResource(R.string.auto_detect)) }
                    TextButton(onClick = { corners = BitmapIO.FULL }) { Icon(Icons.Filled.CropFree, null); Text(" " + stringResource(R.string.whole_image)) }
                    Button(onClick = {
                        val q = Quad.order(corners.points)
                        vm.editPage(docId, pageId) { it.copy(corners = q) }
                        vm.back()
                    }) { Icon(Icons.Filled.Check, null); Text(" " + stringResource(R.string.apply)) }
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Text(
                stringResource(R.string.crop_hint), style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            Box(Modifier.weight(1f).fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                val b = src
                if (b == null || detecting) CircularProgressIndicator()
                if (b != null) {
                    val img = remember(b) { b.asImageBitmap() }
                    CornerEditor(img, corners) { corners = it }
                }
            }
        }
    }
}

@Composable
private fun CornerEditor(image: ImageBitmap, corners: Quad, onChange: (Quad) -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    val handleColor = MaterialTheme.colorScheme.primaryContainer
    val desc = stringResource(R.string.crop_area_desc)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val vw = with(density) { maxWidth.toPx() }
        val vh = with(density) { maxHeight.toPx() }
        val k = minOf(vw / image.width, vh / image.height)
        val dw = image.width * k
        val dh = image.height * k
        val ox = (vw - dw) / 2f
        val oy = (vh - dh) / 2f
        val touch = with(density) { 40.dp.toPx() }
        var active by remember { mutableStateOf(-1) }
        var finger by remember { mutableStateOf(Offset.Zero) }
        var current by remember { mutableStateOf(corners) }
        current = corners

        fun toView(p: Pt) = Offset(ox + p.x * dw, oy + p.y * dh)
        fun toNorm(o: Offset) = Pt(((o.x - ox) / dw).coerceIn(0f, 1f), ((o.y - oy) / dh).coerceIn(0f, 1f))

        Canvas(
            Modifier
                .fillMaxSize()
                .semantics { contentDescription = desc }
                .pointerInput(image, dw, dh) {
                    detectDragGestures(
                        onDragStart = { start ->
                            val pts = current.points.map { toView(it) }
                            val i = pts.indices.minBy { (pts[it] - start).getDistance() }
                            active = if ((pts[i] - start).getDistance() < touch * 1.5f) i else -1
                            finger = start
                        },
                        onDragEnd = { active = -1 },
                        onDragCancel = { active = -1 },
                        onDrag = { change, drag ->
                            if (active >= 0) {
                                change.consume()
                                val p = current.points.toMutableList()
                                val moved = toView(p[active]) + drag
                                p[active] = toNorm(moved)
                                finger = toView(p[active])
                                onChange(Quad(p[0], p[1], p[2], p[3]))
                            }
                        },
                    )
                },
        ) {
            drawImage(image, dstOffset = IntOffset(ox.toInt(), oy.toInt()), dstSize = IntSize(dw.toInt(), dh.toInt()))
            val pts = current.points.map { toView(it) }
            val path = Path().apply {
                moveTo(pts[0].x, pts[0].y); for (i in 1 until 4) lineTo(pts[i].x, pts[i].y); close()
            }
            drawPath(path, primary.copy(alpha = 0.18f))
            drawPath(path, primary, style = Stroke(width = 3.dp.toPx()))
            pts.forEachIndexed { i, p ->
                drawCircle(Color.White, radius = 14.dp.toPx(), center = p)
                drawCircle(if (i == active) primary else handleColor, radius = 11.dp.toPx(), center = p)
                drawCircle(primary, radius = 14.dp.toPx(), center = p, style = Stroke(2.dp.toPx()))
            }
            // Magnifier: 2.5x view of the area under the dragged corner.
            if (active >= 0) {
                val r = 56.dp.toPx()
                val center = if (finger.y > size.height / 2) Offset(r + 12.dp.toPx(), r + 12.dp.toPx()) else Offset(r + 12.dp.toPx(), size.height - r - 12.dp.toPx())
                val zoom = 2.5f
                val circle = Path().apply { addOval(androidx.compose.ui.geometry.Rect(center, r)) }
                clipPath(circle) {
                    drawRect(Color.Black, topLeft = center - Offset(r, r), size = Size(2 * r, 2 * r))
                    val srcCx = (finger.x - ox) / k
                    val srcCy = (finger.y - oy) / k
                    val half = r / (k * zoom)
                    val sx = (srcCx - half).toInt().coerceIn(0, image.width - 1)
                    val sy = (srcCy - half).toInt().coerceIn(0, image.height - 1)
                    val sw = (2 * half).toInt().coerceAtLeast(1).coerceAtMost(image.width - sx)
                    val sh = (2 * half).toInt().coerceAtLeast(1).coerceAtMost(image.height - sy)
                    val dx = center.x - (srcCx - sx) * k * zoom
                    val dy = center.y - (srcCy - sy) * k * zoom
                    drawImage(
                        image, srcOffset = IntOffset(sx, sy), srcSize = IntSize(sw, sh),
                        dstOffset = IntOffset(dx.toInt(), dy.toInt()), dstSize = IntSize((sw * k * zoom).toInt(), (sh * k * zoom).toInt()),
                    )
                    drawLine(primary, center - Offset(12.dp.toPx(), 0f), center + Offset(12.dp.toPx(), 0f), 2.dp.toPx())
                    drawLine(primary, center - Offset(0f, 12.dp.toPx()), center + Offset(0f, 12.dp.toPx()), 2.dp.toPx())
                }
                drawCircle(primary, radius = r, center = center, style = Stroke(3.dp.toPx()))
            }
        }
    }
}
