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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Rotate90DegreesCw
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.fastkiwi.scan.R
import com.fastkiwi.scan.core.CropGeometry
import com.fastkiwi.scan.core.Detection
import com.fastkiwi.scan.core.Handle
import com.fastkiwi.scan.core.Pt
import com.fastkiwi.scan.core.Quad
import com.fastkiwi.scan.core.QuadProblem
import com.fastkiwi.scan.imaging.BitmapIO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.atan2

/**
 * Page outline editor. The original photo is always kept, so re-cropping is lossless.
 * Corners and side midpoints are draggable (72 dp touch area), a magnifier
 * floats above the finger, handles snap to the detected page edges, and the
 * view can be rotated in 90 degree steps.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CropScreen(vm: AppViewModel, docId: String, pageId: String) {
    val page = remember(docId, pageId) { vm.doc(docId)?.pages?.firstOrNull { it.id == pageId } }
    val loaded by produceState<Pair<Bitmap, Detection?>?>(null, docId, pageId) {
        value = withContext(Dispatchers.IO) {
            val bmp = BitmapIO.decodeFile(vm.store.srcFile(docId, pageId), 2048) ?: return@withContext null
            bmp to runCatching { BitmapIO.detectPage(bmp) }.getOrNull()
        }
    }
    // Saveable: an unapplied outline survives activity recreation (language or other config change).
    var corners by androidx.compose.runtime.saveable.rememberSaveable(docId, pageId, stateSaver = QuadSaver) {
        mutableStateOf(page?.corners ?: BitmapIO.FULL)
    }
    var rotation by androidx.compose.runtime.saveable.rememberSaveable(docId, pageId) { mutableIntStateOf(page?.rotation ?: 0) }
    val detected = loaded?.second?.quad

    val problem = CropGeometry.validate(corners, 1f, 1f, 0.005f)

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.cancel)) } },
                title = { Text(stringResource(R.string.crop)) },
                actions = {
                    Button(
                        onClick = {
                            vm.editPage(docId, pageId) { it.copy(corners = corners, rotation = rotation) }
                            vm.back()
                        },
                        enabled = problem == null && page != null,
                        modifier = Modifier.padding(end = 8.dp),
                    ) { Icon(Icons.Filled.Check, null); Text(" " + stringResource(R.string.apply)) }
                },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    ToolButton(Icons.Filled.Rotate90DegreesCw, R.string.rotate_right) { rotation = (rotation + 90) % 360 }
                    ToolButton(Icons.Filled.CropFree, R.string.crop_all) { corners = BitmapIO.FULL }
                    ToolButton(Icons.Filled.AutoFixHigh, R.string.crop_reset_detected, enabled = detected != null) {
                        detected?.let { corners = it }
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (problem != null) {
                ProblemBanner(problem) {
                    CropGeometry.fix(corners, 1f, 1f)?.let { corners = it } ?: run { corners = detected ?: BitmapIO.FULL }
                }
            } else {
                Text(
                    stringResource(R.string.crop_hint), style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp), contentAlignment = Alignment.Center) {
                val l = loaded
                if (l == null) {
                    CircularProgressIndicator()
                } else {
                    val view = remember(l.first, rotation) {
                        val copy = l.first.copy(Bitmap.Config.ARGB_8888, false)
                        BitmapIO.rotate(copy, rotation).asImageBitmap()
                    }
                    CornerEditor(view, corners, rotation, detected, problem != null) { corners = it }
                }
            }
        }
    }
}

private val QuadSaver = androidx.compose.runtime.saveable.Saver<Quad, FloatArray>(
    save = { it.toArray() },
    restore = { Quad.fromArray(it) },
)

@Composable
private fun ToolButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: Int, enabled: Boolean = true, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled, modifier = Modifier.heightIn(min = 56.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null)
            Text(stringResource(label), style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun ProblemBanner(problem: QuadProblem, onFix: () -> Unit) {
    val msg = stringResource(
        when (problem) {
            QuadProblem.CROSSED -> R.string.crop_problem_crossed
            QuadProblem.CONCAVE -> R.string.crop_problem_concave
            QuadProblem.TOO_SMALL -> R.string.crop_problem_small
        },
    )
    Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 6.dp).semantics { liveRegion = LiveRegionMode.Assertive },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Warning, null, tint = MaterialTheme.colorScheme.onErrorContainer)
            Text(msg, Modifier.weight(1f).padding(horizontal = 12.dp), color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onFix) { Text(stringResource(R.string.crop_fix)) }
        }
    }
}

/**
 * [corners] and [detected] are normalised in the source image; the view shows the
 * source rotated by [rotation]. All dragging happens in on-screen pixels.
 */
@Composable
private fun CornerEditor(
    image: ImageBitmap,
    corners: Quad,
    rotation: Int,
    detected: Quad?,
    invalid: Boolean,
    onChange: (Quad) -> Unit,
) {
    val primary = MaterialTheme.colorScheme.primary
    val error = MaterialTheme.colorScheme.error
    val handleFill = MaterialTheme.colorScheme.primaryContainer
    val haptics = LocalHapticFeedback.current
    val desc = stringResource(R.string.crop_area_desc)
    val latestCorners by rememberUpdatedState(corners)
    val latestDetected by rememberUpdatedState(detected)
    val latestRotation by rememberUpdatedState(rotation)

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val vw = with(density) { maxWidth.toPx() }
        val vh = with(density) { maxHeight.toPx() }
        val k = minOf(vw / image.width, vh / image.height)
        val dw = image.width * k
        val dh = image.height * k
        val ox = (vw - dw) / 2f
        val oy = (vh - dh) / 2f
        val touch = with(density) { 36.dp.toPx() } // 72 dp wide grab area
        val snapR = with(density) { 18.dp.toPx() }

        var active by remember { mutableStateOf<Handle?>(null) }
        var focus by remember { mutableStateOf(Offset.Zero) } // point shown in the loupe (local px)
        var finger by remember { mutableStateOf(Offset.Zero) }

        // Source-normalised quad <-> local view pixels (origin at the image's top-left).
        fun toLocal(q: Quad, rot: Int) = q.map { CropGeometry.toView(it, rot).let { v -> Pt(v.x * dw, v.y * dh) } }
        fun fromLocal(q: Quad, rot: Int) = q.map { CropGeometry.fromView(Pt(it.x / dw, it.y / dh), rot) }

        Canvas(
            Modifier
                .fillMaxSize()
                .semantics { contentDescription = desc }
                .pointerInput(image, dw, dh) {
                    var startQuad: Quad? = null
                    var grabOffset = Pt(0f, 0f)
                    var total = Pt(0f, 0f)
                    var snapped = false
                    detectDragGestures(
                        onDragStart = { start ->
                            val local = toLocal(latestCorners, latestRotation)
                            val p = Pt(start.x - ox, start.y - oy)
                            val h = CropGeometry.hitTest(local, p, touch)
                            active = h
                            startQuad = local
                            total = Pt(0f, 0f)
                            snapped = false
                            finger = start
                            if (h != null) {
                                val anchor = if (h.kind == Handle.Kind.CORNER) local.points[h.index] else CropGeometry.midpoint(local, h.index)
                                grabOffset = anchor - p
                                focus = Offset(anchor.x, anchor.y)
                            }
                        },
                        onDragEnd = { active = null },
                        onDragCancel = { active = null },
                        onDrag = { change, drag ->
                            val h = active ?: return@detectDragGestures
                            val sq = startQuad ?: return@detectDragGestures
                            change.consume()
                            finger = change.position
                            total = Pt(total.x + drag.x, total.y + drag.y)
                            val det = latestDetected?.let { toLocal(it, latestRotation) }
                            var next: Quad
                            var nowSnapped = false
                            if (h.kind == Handle.Kind.CORNER) {
                                val target = Pt(change.position.x - ox + grabOffset.x, change.position.y - oy + grabOffset.y)
                                next = CropGeometry.dragCorner(sq, h.index, target, dw, dh)
                                CropGeometry.snapCorner(next.points[h.index], det, snapR)?.let {
                                    next = CropGeometry.dragCorner(next, h.index, it, dw, dh); nowSnapped = true
                                }
                                val c = next.points[h.index]
                                focus = Offset(c.x, c.y)
                            } else {
                                next = CropGeometry.dragEdge(sq, h.index, total, dw, dh)
                                CropGeometry.snapEdge(next, h.index, det, snapR)?.let { next = it; nowSnapped = true }
                                val m = CropGeometry.midpoint(next, h.index)
                                focus = Offset(m.x, m.y)
                            }
                            if (nowSnapped && !snapped) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            snapped = nowSnapped
                            onChange(fromLocal(next, latestRotation))
                        },
                    )
                },
        ) {
            drawImage(image, dstOffset = IntOffset(ox.toInt(), oy.toInt()), dstSize = IntSize(dw.toInt(), dh.toInt()))
            val local = toLocal(corners, rotation)
            val pts = local.points.map { Offset(ox + it.x, oy + it.y) }
            val outline = Path().apply {
                moveTo(pts[0].x, pts[0].y); for (i in 1 until 4) lineTo(pts[i].x, pts[i].y); close()
            }
            // Dim everything outside the page.
            val scrim = Path().apply {
                fillType = PathFillType.EvenOdd
                addRect(Rect(ox, oy, ox + dw, oy + dh))
                addPath(outline)
            }
            drawPath(scrim, Color.Black.copy(alpha = 0.45f))
            val lineColor = if (invalid) error else primary
            drawPath(outline, Color.White, style = Stroke(width = 5.dp.toPx()))
            drawPath(outline, lineColor, style = Stroke(width = 3.dp.toPx()))

            // Side handles: white capsules along each side.
            for (s in 0 until 4) {
                val a = pts[s]
                val b = pts[(s + 1) % 4]
                val mid = Offset((a.x + b.x) / 2f, (a.y + b.y) / 2f)
                val angle = Math.toDegrees(atan2((b.y - a.y).toDouble(), (b.x - a.x).toDouble())).toFloat()
                val isActive = active == Handle(Handle.Kind.EDGE, s)
                val len = (if (isActive) 40.dp else 32.dp).toPx()
                val thick = 12.dp.toPx()
                rotate(angle, pivot = mid) {
                    drawRoundRect(
                        if (isActive) primary else Color.White,
                        topLeft = Offset(mid.x - len / 2, mid.y - thick / 2), size = Size(len, thick),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(thick / 2),
                    )
                    drawRoundRect(
                        lineColor, topLeft = Offset(mid.x - len / 2, mid.y - thick / 2), size = Size(len, thick),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(thick / 2), style = Stroke(2.dp.toPx()),
                    )
                }
            }
            // Corner handles.
            pts.forEachIndexed { i, p ->
                val isActive = active == Handle(Handle.Kind.CORNER, i)
                val r = (if (isActive) 18.dp else 15.dp).toPx()
                drawCircle(Color.White, radius = r + 3.dp.toPx(), center = p)
                drawCircle(if (isActive) primary else handleFill, radius = r, center = p)
                drawCircle(lineColor, radius = r + 3.dp.toPx(), center = p, style = Stroke(2.dp.toPx()))
            }

            // Magnifier above the finger (below it when near the top), never under it.
            if (active != null) {
                val r = 60.dp.toPx()
                val gap = 48.dp.toPx()
                var cy = finger.y - gap - r
                if (cy - r < 0f) cy = finger.y + gap + r
                val cx = finger.x.coerceIn(r, size.width - r)
                val center = Offset(cx, cy.coerceIn(r, size.height - r))
                val zoom = 2.5f
                val circle = Path().apply { addOval(Rect(center, r)) }
                clipPath(circle) {
                    drawRect(Color.Black, topLeft = center - Offset(r, r), size = Size(2 * r, 2 * r))
                    // Same image, scaled around the focus point.
                    val s = k * zoom
                    val left = center.x - focus.x * zoom
                    val top = center.y - focus.y * zoom
                    drawImage(
                        image,
                        dstOffset = IntOffset(left.toInt(), top.toInt()),
                        dstSize = IntSize((image.width * s).toInt(), (image.height * s).toInt()),
                    )
                    // The outline inside the loupe, at the same zoom.
                    val zp = local.points.map { Offset(left + it.x * zoom, top + it.y * zoom) }
                    val zpath = Path().apply { moveTo(zp[0].x, zp[0].y); for (i in 1 until 4) lineTo(zp[i].x, zp[i].y); close() }
                    drawPath(zpath, lineColor, style = Stroke(2.dp.toPx()))
                    val arm = 14.dp.toPx()
                    drawLine(Color.White, center - Offset(arm, 0f), center + Offset(arm, 0f), 3.dp.toPx())
                    drawLine(Color.White, center - Offset(0f, arm), center + Offset(0f, arm), 3.dp.toPx())
                    drawLine(lineColor, center - Offset(arm, 0f), center + Offset(arm, 0f), 1.5.dp.toPx())
                    drawLine(lineColor, center - Offset(0f, arm), center + Offset(0f, arm), 1.5.dp.toPx())
                }
                drawCircle(Color.White, radius = r, center = center, style = Stroke(4.dp.toPx()))
                drawCircle(lineColor, radius = r, center = center, style = Stroke(2.dp.toPx()))
            }
        }
    }
}
