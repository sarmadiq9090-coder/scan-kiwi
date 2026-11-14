// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings as AndroidSettings
import android.view.Surface
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.fastkiwi.scan.R
import com.fastkiwi.scan.core.AutoCapture
import com.fastkiwi.scan.core.GrayImage
import com.fastkiwi.scan.core.Pt
import com.fastkiwi.scan.core.Quad
import com.fastkiwi.scan.core.QuadDetector
import com.fastkiwi.scan.core.rotateNormalized
import java.util.concurrent.Executors
import kotlin.math.max

/** Live detection result in normalised, upright preview coordinates. */
private data class Live(
    val quad: Quad?,
    val aspect: Float,
    val state: AutoCapture.State = AutoCapture.State.SEARCHING,
    val progress: Float = 0f,
)

@Composable
fun CameraScreen(vm: AppViewModel, target: Screen.Camera) {
    val ctx = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var asked by remember { mutableStateOf(false) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it; asked = true }
    LaunchedEffect(Unit) { if (!granted) permLauncher.launch(Manifest.permission.CAMERA) }

    val leave: () -> Unit = {
        vm.cancelIdCard()
        vm.discardIfEmpty(target.docId)
        vm.back()
    }
    BackHandler { leave() }

    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris: List<Uri> ->
        vm.importUris(target.docId, uris) { id -> vm.cancelIdCard(); vm.finishCamera(id) }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (granted) {
            CameraContent(vm, target, onClose = leave, onGallery = {
                galleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            })
        } else {
            Column(
                Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(stringResource(R.string.camera_permission_needed), color = Color.White, textAlign = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                if (asked) {
                    Button(onClick = {
                        ctx.startActivity(Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null)))
                    }) { Text(stringResource(R.string.open_settings)) }
                } else {
                    Button(onClick = { permLauncher.launch(Manifest.permission.CAMERA) }) { Text(stringResource(R.string.allow_camera)) }
                }
                Spacer(Modifier.height(8.dp))
                Button(onClick = { galleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                    Text(stringResource(R.string.pick_from_gallery))
                }
                Spacer(Modifier.height(8.dp))
                Button(onClick = leave) { Text(stringResource(R.string.cancel)) }
            }
        }
    }
}

@Composable
private fun CameraContent(vm: AppViewModel, initial: Screen.Camera, onClose: () -> Unit, onGallery: () -> Unit) {
    val ctx = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    // Camera state lives in the back stack entry, so it survives activity recreation.
    val target = initial
    val captured = target.captured
    var live by remember { mutableStateOf(Live(null, 3f / 4f)) }
    // Auto-capture decisions (time based, re-arms only when the page changes).
    val gate = remember { AutoCapture() }
    val autoFlag = remember { java.util.concurrent.atomic.AtomicBoolean(vm.settings.autoCapture) }
    var torch by remember { mutableStateOf(false) }
    var auto by remember { mutableStateOf(vm.settings.autoCapture) }
    var capturing by remember { mutableStateOf(false) }
    var cooldownUntil by remember { mutableStateOf(0L) }
    var status by remember { mutableIntStateOf(R.string.status_searching) }
    val idFront by vm.idFrontCaptured

    val previewView = remember { PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FIT_CENTER } }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    var camera by remember { mutableStateOf<Camera?>(null) }
    val analysis = remember {
        ImageAnalysis.Builder()
            .setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY).build())
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
    }
    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY).build())
            .build()
    }

    /** [auto]: taken by auto-capture (batch mode: no review unless the outline is doubtful). */
    fun capture(auto: Boolean) {
        if (capturing) return
        capturing = true
        status = R.string.status_capturing
        val liveQuad = live.quad
        synchronized(gate) { gate.captured(liveQuad) }
        imageCapture.takePicture(ContextCompat.getMainExecutor(ctx), object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val buf = image.planes[0].buffer
                val bytes = ByteArray(buf.remaining()).also { buf.get(it) }
                val rotation = image.imageInfo.rotationDegrees
                image.close()
                vm.addCapture(target, bytes, rotation, liveQuad) { r ->
                    capturing = false
                    cooldownUntil = System.currentTimeMillis() + 2000
                    if (r == null) {
                        status = R.string.status_error
                    } else if (vm.afterCapture(target, r, auto)) {
                        status = R.string.status_added
                    }
                }
            }

            override fun onError(exception: ImageCaptureException) {
                capturing = false
                status = R.string.status_error
            }
        })
    }

    DisposableEffect(lifecycleOwner) {
        val future = ProcessCameraProvider.getInstance(ctx)
        val detector = QuadDetector(320)
        future.addListener({
            val provider = future.get()
            val rotation = previewView.display?.rotation ?: Surface.ROTATION_0
            val preview = Preview.Builder()
                .setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY).build())
                .setTargetRotation(rotation)
                .build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            imageCapture.targetRotation = rotation
            analysis.targetRotation = rotation
            analysis.setAnalyzer(analysisExecutor) { img ->
                try {
                    val gray = lumaOf(img, 320)
                    // Luminance first; if that is doubtful, try "paper-likeness"
                    // (bright and colourless), which finds white pages on light coloured desks.
                    val det = detector.detectBest(listOf({ gray }, { paperOf(img, gray) }))
                    val rot = img.imageInfo.rotationDegrees
                    val norm = det?.quad?.map { Pt(it.x / gray.width, it.y / gray.height) }?.rotateNormalized(rot)
                    val upright = if (rot == 90 || rot == 270) img.height.toFloat() / img.width else img.width.toFloat() / img.height
                    val u = synchronized(gate) {
                        gate.feed(norm, det?.confident == true, android.os.SystemClock.uptimeMillis(), autoFlag.get())
                    }
                    ContextCompat.getMainExecutor(ctx).execute { live = Live(u.quad, upright, u.state, u.progress) }
                } catch (_: Throwable) {
                } finally {
                    img.close()
                }
            }
            try {
                provider.unbindAll()
                camera = provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture, analysis)
            } catch (_: Exception) {
                status = R.string.status_error
            }
        }, ContextCompat.getMainExecutor(ctx))
        onDispose {
            runCatching { future.get().unbindAll() }
            analysisExecutor.shutdown()
        }
    }

    // Status + auto-capture state machine (driven by live detection updates).
    LaunchedEffect(auto) { autoFlag.set(auto) }
    LaunchedEffect(live.state, capturing) {
        if (capturing) return@LaunchedEffect
        val recentlyAdded = status == R.string.status_added && System.currentTimeMillis() < cooldownUntil
        status = when (live.state) {
            AutoCapture.State.SEARCHING -> if (recentlyAdded) status else R.string.status_searching
            AutoCapture.State.TOO_SMALL -> R.string.status_move_closer
            AutoCapture.State.FOUND -> R.string.status_found
            AutoCapture.State.HOLD -> R.string.status_hold
            // Same page still in view after a shot: say so instead of capturing it again.
            AutoCapture.State.CAPTURED -> if (recentlyAdded) status else R.string.status_next_page
            AutoCapture.State.FIRE -> R.string.status_capturing
        }
        if (live.state == AutoCapture.State.FIRE && auto) capture(auto = true)
    }

    LaunchedEffect(torch, camera) { camera?.cameraControl?.enableTorch(torch) }

    // The activity handles rotation itself, so keep capture/analysis in step with the display.
    val orientation = LocalConfiguration.current.orientation
    LaunchedEffect(orientation) {
        val r = previewView.display?.rotation ?: return@LaunchedEffect
        imageCapture.targetRotation = r
        analysis.targetRotation = r
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        QuadOverlay(live)

        // Top controls
        Row(
            Modifier.fillMaxWidth().safeDrawingPadding().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            IconButton(onClick = onClose, colors = IconButtonDefaults.iconButtonColors(contentColor = Color.White)) {
                Icon(Icons.Filled.Close, stringResource(R.string.close_camera))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilterChip(
                    selected = auto,
                    onClick = { auto = !auto; vm.settings.autoCapture = auto },
                    label = { Text(stringResource(R.string.auto_capture)) },
                    colors = FilterChipDefaults.filterChipColors(labelColor = Color.White),
                )
                if (target.retakePageId == null) {
                    Spacer(Modifier.size(8.dp))
                    FilterChip(
                        selected = target.idCard,
                        onClick = { vm.cancelIdCard(); vm.replaceTop(target.copy(idCard = !target.idCard)) },
                        label = { Text(stringResource(R.string.id_card_mode)) },
                        colors = FilterChipDefaults.filterChipColors(labelColor = Color.White),
                    )
                }
                IconButton(onClick = { torch = !torch }, colors = IconButtonDefaults.iconButtonColors(contentColor = Color.White)) {
                    Icon(if (torch) Icons.Filled.FlashOn else Icons.Filled.FlashOff, stringResource(if (torch) R.string.torch_off else R.string.torch_on))
                }
            }
        }

        // Bottom controls
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().safeDrawingPadding().padding(bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val statusText = when {
                target.idCard && !idFront && status != R.string.status_capturing -> stringResource(R.string.id_front_hint)
                target.idCard && idFront && status != R.string.status_capturing -> stringResource(R.string.id_back_hint)
                status == R.string.status_added -> stringResource(R.string.status_added, captured)
                else -> stringResource(status)
            }
            Text(
                statusText,
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .background(Color(0x99000000), CircleShape)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onGallery, modifier = Modifier.size(56.dp), colors = IconButtonDefaults.iconButtonColors(contentColor = Color.White)) {
                    Icon(Icons.Filled.PhotoLibrary, stringResource(R.string.pick_from_gallery))
                }
                // Last captured page: tap to adjust its outline right away.
                val docs by vm.docs.collectAsStateWithLifecycle()
                val last = target.docId?.let { id -> docs.firstOrNull { it.doc.id == id }?.doc }?.takeIf { target.captured > 0 }?.pages?.lastOrNull()
                val lastLabel = stringResource(R.string.adjust_last_page)
                Box(
                    Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .border(2.dp, if (last != null) Color.White else Color.Transparent, RoundedCornerShape(8.dp))
                        .clickable(enabled = last != null && !capturing) { last?.let { vm.open(Screen.Crop(target.docId!!, it.id)) } }
                        .semantics { if (last != null) { contentDescription = lastLabel; role = Role.Button } },
                ) {
                    if (last != null) {
                        FileImage(vm.store.thumbFile(target.docId!!, last.id), last.revision, 360, null, Modifier.fillMaxSize(), androidx.compose.ui.layout.ContentScale.Crop)
                    }
                }
                val shutterLabel = stringResource(R.string.take_photo)
                val ring = if (auto && live.state == AutoCapture.State.HOLD) live.progress else 0f
                val ringColor = MaterialTheme.colorScheme.primaryContainer
                Box(
                    Modifier
                        .size(76.dp)
                        .border(4.dp, Color.White, CircleShape)
                        // Auto-capture countdown around the shutter while the page is held steady.
                        .drawWithContent {
                            drawContent()
                            if (ring > 0f) {
                                drawArc(
                                    ringColor, -90f, 360f * ring, useCenter = false,
                                    style = androidx.compose.ui.graphics.drawscope.Stroke(5.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round),
                                )
                            }
                        }
                        .padding(8.dp)
                        .background(if (capturing) Color.Gray else Color.White, CircleShape)
                        .clickable(enabled = !capturing) { capture(auto = false) }
                        .semantics { contentDescription = shutterLabel; role = Role.Button },
                )
                val doneLabel = stringResource(R.string.done_with_count, captured)
                IconButton(
                    onClick = { target.docId?.let { vm.finishCamera(it) } ?: onClose() },
                    enabled = captured > 0 && !capturing,
                    modifier = Modifier.size(56.dp).semantics { contentDescription = doneLabel },
                    colors = IconButtonDefaults.iconButtonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary),
                ) {
                    if (captured > 0) Text(captured.toString(), style = MaterialTheme.typography.titleMedium) else Icon(Icons.Filled.Done, null)
                }
            }
        }
    }
}

@Composable
private fun QuadOverlay(live: Live) {
    val color = MaterialTheme.colorScheme.primaryContainer
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val vw = with(density) { maxWidth.toPx() }
        val vh = with(density) { maxHeight.toPx() }
        Canvas(Modifier.fillMaxSize()) {
            val q = live.quad ?: return@Canvas
            // PreviewView FIT_CENTER: the frame is letter-boxed inside the view.
            val viewAspect = vw / vh
            val (cw, ch) = if (live.aspect > viewAspect) vw to vw / live.aspect else vh * live.aspect to vh
            val ox = (vw - cw) / 2f
            val oy = (vh - ch) / 2f
            val pts = q.points.map { Offset(ox + it.x * cw, oy + it.y * ch) }
            val path = Path().apply {
                moveTo(pts[0].x, pts[0].y)
                for (i in 1 until 4) lineTo(pts[i].x, pts[i].y)
                close()
            }
            drawPath(path, color.copy(alpha = 0.25f))
            drawPath(path, color, style = Stroke(width = 4.dp.toPx()))
            pts.forEach { drawCircle(color, radius = 8.dp.toPx(), center = it) }
        }
    }
}

/** Subsamples the Y (luminance) plane of a YUV frame to at most [maxSide] px. */
private fun lumaOf(img: ImageProxy, maxSide: Int): GrayImage {
    val plane = img.planes[0]
    val buf = plane.buffer
    val rowStride = plane.rowStride
    val pixStride = plane.pixelStride
    val step = max(1, max(img.width, img.height) / maxSide)
    val w = img.width / step
    val h = img.height / step
    val out = ByteArray(w * h)
    for (y in 0 until h) {
        val row = y * step * rowStride
        for (x in 0 until w) {
            out[y * w + x] = buf.get(row + x * step * pixStride)
        }
    }
    return GrayImage(w, h, out)
}

/**
 * "Paper-likeness" on the same grid as [lumaOf]: Y minus 1.5 x chroma
 * (|U-128| + |V-128|), so white pages stand out from light coloured surfaces.
 */
private fun paperOf(img: ImageProxy, luma: GrayImage): GrayImage {
    val u = img.planes[1]
    val v = img.planes[2]
    val ub = u.buffer
    val vb = v.buffer
    val step = max(1, max(img.width, img.height) / 320) // same grid as lumaOf(img, 320)
    val out = ByteArray(luma.width * luma.height)
    for (y in 0 until luma.height) {
        val sy = (y * step) / 2
        for (x in 0 until luma.width) {
            val sx = (x * step) / 2
            val cu = (ub.get(sy * u.rowStride + sx * u.pixelStride).toInt() and 0xFF) - 128
            val cv = (vb.get(sy * v.rowStride + sx * v.pixelStride).toInt() and 0xFF) - 128
            val chroma = kotlin.math.abs(cu) + kotlin.math.abs(cv)
            val p = luma[x, y] - chroma * 3 / 2
            out[y * luma.width + x] = p.coerceIn(0, 255).toByte()
        }
    }
    return GrayImage(luma.width, luma.height, out)
}
