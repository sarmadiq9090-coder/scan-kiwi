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
import android.os.Build
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.ui.semantics.stateDescription
import com.fastkiwi.scan.ui.theme.CameraTheme
import com.fastkiwi.scan.ui.theme.Dimens
import com.fastkiwi.scan.ui.theme.KiwiAccent
import com.fastkiwi.scan.ui.theme.LocalReducedMotion
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

    CameraTheme {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            if (granted) {
                CameraContent(vm, target, onClose = leave, onGallery = {
                    galleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                })
            } else {
                // Permission screen (design spec §3.2): one primary in the thumb zone.
                Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = Dimens.xl)) {
                    Box(Modifier.fillMaxWidth().padding(top = Dimens.s)) {
                        CamIconButton(KiwiIcons.close(), stringResource(R.string.close_camera), leave, Modifier.align(Alignment.CenterStart))
                    }
                    Column(
                        Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Box(
                            Modifier.size(112.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) { Icon(KiwiIcons.camera(), null, Modifier.size(48.dp), tint = KiwiAccent) }
                        Spacer(Modifier.height(Dimens.xl))
                        Text(stringResource(R.string.camera_permission_title), style = MaterialTheme.typography.headlineSmall, color = Color.White, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(Dimens.s))
                        Text(stringResource(R.string.camera_permission_needed), style = MaterialTheme.typography.bodyLarge, color = Color.White.copy(alpha = 0.85f), textAlign = TextAlign.Center)
                        Spacer(Modifier.height(Dimens.l))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(KiwiIcons.lock(), null, tint = KiwiAccent, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(Dimens.s))
                            Text(stringResource(R.string.privacy_short), style = MaterialTheme.typography.bodyMedium, color = Color.White)
                        }
                    }
                    Column(Modifier.fillMaxWidth().padding(bottom = Dimens.l), verticalArrangement = Arrangement.spacedBy(Dimens.s)) {
                        Button(
                            onClick = {
                                if (asked) ctx.startActivity(Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null)))
                                else permLauncher.launch(Manifest.permission.CAMERA)
                            },
                            modifier = Modifier.fillMaxWidth().heightIn(min = Dimens.buttonLarge),
                        ) { Text(stringResource(if (asked) R.string.open_settings else R.string.allow_camera)) }
                        OutlinedButton(
                            onClick = { galleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = Dimens.button),
                        ) { Text(stringResource(R.string.pick_images)) }
                        TextButton(onClick = leave, modifier = Modifier.fillMaxWidth().heightIn(min = Dimens.button)) { Text(stringResource(R.string.not_now)) }
                    }
                }
            }
        }
    }
}

/** A 48 dp round icon button on a 45% black disc, readable over any camera image. */
@Composable
private fun CamIconButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, stateText: String? = null) {
    Box(
        modifier
            .size(48.dp)
            .background(Color.Black.copy(alpha = 0.45f), CircleShape)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = label
                role = Role.Button
                if (stateText != null) stateDescription = stateText
            },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = Color.White) }
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
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current

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
            // No usable camera (emulator without one, camera service error): show the status, never crash.
            val provider = runCatching { future.get() }.getOrNull() ?: run { status = R.string.status_error; return@addListener }
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
        if (live.state == AutoCapture.State.FIRE && auto) {
            haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.Confirm)
            capture(auto = true)
        }
    }

    LaunchedEffect(torch, camera) { camera?.cameraControl?.enableTorch(torch) }

    // The activity handles rotation itself, so keep capture/analysis in step with the display.
    val orientation = LocalConfiguration.current.orientation
    LaunchedEffect(orientation) {
        val r = previewView.display?.rotation ?: return@LaunchedEffect
        imageCapture.targetRotation = r
        analysis.targetRotation = r
    }

    val galleryLabel = stringResource(R.string.pick_from_gallery)
    // Capture feedback: a short white flash (skipped with reduced motion) and haptics.
    val reducedMotion = LocalReducedMotion.current
    var flash by remember { mutableStateOf(false) }
    LaunchedEffect(captured) {
        if (captured > 0 && !reducedMotion) { flash = true; kotlinx.coroutines.delay(120); flash = false }
    }
    // «تعديل» on the status pill for a few seconds after each capture (accessibility-aware timeout).
    var editUntil by remember { mutableStateOf(0L) }
    val a11y = remember { ctx.getSystemService(android.view.accessibility.AccessibilityManager::class.java) }
    val editMillis = remember {
        if (Build.VERSION.SDK_INT >= 29) a11y?.getRecommendedTimeoutMillis(4000, android.view.accessibility.AccessibilityManager.FLAG_CONTENT_CONTROLS or android.view.accessibility.AccessibilityManager.FLAG_CONTENT_TEXT)?.toLong() ?: 4000L
        else 4000L
    }
    LaunchedEffect(captured) {
        if (captured > 0) { editUntil = System.currentTimeMillis() + editMillis; kotlinx.coroutines.delay(editMillis); editUntil = 0L }
    }

    val docsState by vm.docs.collectAsStateWithLifecycle()
    val last = target.docId?.let { id -> docsState.firstOrNull { it.doc.id == id }?.doc }?.takeIf { target.captured > 0 }?.pages?.lastOrNull()
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
                QuadOverlay(live)
                if (flash) Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = 0.3f)))

                // Top: close (start), auto/manual pill (centre), flash (end).
                Box(Modifier.fillMaxWidth().statusBarsPadding().padding(Dimens.m)) {
                    CamIconButton(KiwiIcons.close(), stringResource(R.string.close_camera), onClose, Modifier.align(Alignment.CenterStart))
                    AutoManualPill(auto, Modifier.align(Alignment.Center)) { auto = it; vm.settings.autoCapture = it }
                    CamIconButton(
                        if (torch) KiwiIcons.flashOn() else KiwiIcons.flashOff(),
                        stringResource(R.string.flash), { torch = !torch }, Modifier.align(Alignment.CenterEnd),
                        stateText = stringResource(if (torch) R.string.state_on else R.string.state_off),
                    )
                }

                // Status pill: white on 72% black, polite live region, «تعديل» after a capture.
                val statusText = when {
                    target.idCard && !idFront && status != R.string.status_capturing -> stringResource(R.string.id_front_hint)
                    target.idCard && idFront && status != R.string.status_capturing -> stringResource(R.string.id_back_hint)
                    status == R.string.status_added -> stringResource(R.string.status_added, captured)
                    else -> stringResource(status)
                }
                Row(
                    Modifier.align(Alignment.BottomCenter).padding(bottom = Dimens.l)
                        .background(Color.Black.copy(alpha = 0.72f), CircleShape)
                        .padding(start = Dimens.l, end = if (editUntil > 0L && last != null) Dimens.xs else Dimens.l)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(statusText, color = Color.White, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.padding(vertical = Dimens.s))
                    if (editUntil > 0L && last != null) {
                        TextButton(onClick = { vm.open(Screen.Crop(target.docId!!, last.id)) }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.edit_edges_action), color = KiwiAccent)
                        }
                    }
                }
            }

            // Controls panel on black.
            Column(Modifier.fillMaxWidth().background(Color.Black).navigationBarsPadding().padding(bottom = Dimens.l)) {
                if (target.retakePageId == null) {
                    ModeTabs(target.idCard, Modifier.align(Alignment.CenterHorizontally).padding(vertical = Dimens.s)) { id ->
                        if (id != target.idCard) { vm.cancelIdCard(); vm.replaceTop(target.copy(idCard = id)) }
                    }
                }
                // Weights 1 : 96 dp : 1 keep the shutter centred whatever sits beside it.
                Row(Modifier.fillMaxWidth().padding(horizontal = Dimens.l), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        Box(
                            Modifier.size(56.dp).background(Color.White.copy(alpha = 0.14f), CircleShape).clip(CircleShape)
                                .clickable(onClick = onGallery)
                                .semantics { contentDescription = galleryLabel; role = Role.Button },
                            contentAlignment = Alignment.Center,
                        ) { Icon(KiwiIcons.gallery(), null, tint = Color.White) }
                    }
                    Box(Modifier.width(96.dp), contentAlignment = Alignment.Center) {
                        Shutter(capturing, if (auto && live.state == AutoCapture.State.HOLD) live.progress else 0f) { capture(auto = false) }
                    }
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                        if (captured > 0 && last != null) {
                            DonePill(vm, target.docId!!, last, captured, enabled = !capturing) { target.docId?.let { vm.finishCamera(it) } ?: onClose() }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AutoManualPill(auto: Boolean, modifier: Modifier, onChange: (Boolean) -> Unit) {
    Row(modifier.background(Color.Black.copy(alpha = 0.45f), CircleShape).padding(Dimens.xs).selectableGroup()) {
        listOf(true to R.string.auto_short, false to R.string.mode_manual).forEach { (value, label) ->
            val sel = auto == value
            Row(
                Modifier
                    .heightIn(min = 40.dp)
                    .background(if (sel) KiwiAccent else Color.Transparent, CircleShape)
                    .clip(CircleShape)
                    .selectable(selected = sel, onClick = { onChange(value) }, role = Role.RadioButton)
                    .padding(horizontal = Dimens.l),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (value) { Icon(KiwiIcons.autoCapture(), null, Modifier.size(18.dp), tint = if (sel) Color(0xFF082100) else Color.White); Spacer(Modifier.width(Dimens.xs)) }
                Text(stringResource(label), color = if (sel) Color(0xFF082100) else Color.White, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun ModeTabs(idCard: Boolean, modifier: Modifier, onSelect: (Boolean) -> Unit) {
    Row(modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(Dimens.xl)) {
        listOf(false to R.string.mode_document, true to R.string.id_card_mode).forEach { (value, label) ->
            val sel = idCard == value
            Column(
                Modifier.heightIn(min = 48.dp).selectable(selected = sel, onClick = { onSelect(value) }, role = Role.Tab).padding(horizontal = Dimens.s),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(stringResource(label), color = if (sel) KiwiAccent else Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.titleSmall)
                Box(Modifier.padding(top = Dimens.xs).size(6.dp).background(if (sel) KiwiAccent else Color.Transparent, CircleShape))
            }
        }
    }
}

@Composable
private fun Shutter(capturing: Boolean, ring: Float, onClick: () -> Unit) {
    val label = stringResource(R.string.take_photo)
    Box(
        Modifier
            .size(80.dp)
            .border(4.dp, Color.White, CircleShape)
            // Auto-capture countdown (functional, kept even with reduced motion).
            .drawWithContent {
                drawContent()
                if (ring > 0f) {
                    drawArc(
                        KiwiAccent, -90f, 360f * ring, useCenter = false,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(5.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round),
                    )
                }
            }
            .padding(9.dp)
            .background(if (capturing) Color.Gray else Color.White, CircleShape)
            .clip(CircleShape)
            .clickable(enabled = !capturing, onClick = onClick)
            .semantics { contentDescription = label; role = Role.Button },
    )
}

/** «تم» pill with the last page and the page count; tap finishes the batch. */
@Composable
private fun DonePill(vm: AppViewModel, docId: String, last: com.fastkiwi.scan.data.ScanPage, count: Int, enabled: Boolean, onClick: () -> Unit) {
    val label = stringResource(R.string.done_with_count, count)
    Row(
        Modifier
            .heightIn(min = 56.dp)
            .background(KiwiAccent, CircleShape)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = label; role = Role.Button }
            .padding(horizontal = Dimens.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PageThumbnail(vm.store.thumbFile(docId, last.id), last.revision, Modifier.size(width = 36.dp, height = 44.dp), radius = 4)
        Spacer(Modifier.width(Dimens.s))
        Text(stringResource(R.string.done), color = Color(0xFF082100), style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.width(Dimens.s))
        Box(Modifier.size(24.dp).background(Color(0xFF356A1E), CircleShape), contentAlignment = Alignment.Center) {
            Text(LocalFormat.count(count), color = Color.White, style = MaterialTheme.typography.labelSmall)
        }
        Spacer(Modifier.width(Dimens.xs))
    }
}

@Composable
private fun QuadOverlay(live: Live) {
    val color = KiwiAccent
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val vw = with(density) { maxWidth.toPx() }
        val vh = with(density) { maxHeight.toPx() }
        Canvas(Modifier.fillMaxSize()) {
            val q = live.quad
            if (q == null) {
                // No page yet: faint corner brackets in the middle of the frame.
                val bw = size.width * 0.6f
                val bh = bw * 1.3f
                val l = (size.width - bw) / 2f
                val t = (size.height - bh) / 2f
                val arm = 28.dp.toPx()
                val c = Color.White.copy(alpha = 0.5f)
                val st = Stroke(3.dp.toPx())
                for ((x, y, dx, dy) in listOf(
                    listOf(l, t, 1f, 1f), listOf(l + bw, t, -1f, 1f), listOf(l, t + bh, 1f, -1f), listOf(l + bw, t + bh, -1f, -1f),
                )) {
                    drawPath(Path().apply { moveTo(x, y + dy * arm); lineTo(x, y); lineTo(x + dx * arm, y) }, c, style = st)
                }
                return@Canvas
            }
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
            drawPath(path, color.copy(alpha = 0.2f))
            drawPath(path, color, style = Stroke(width = 3.5.dp.toPx()))
            pts.forEach { drawCircle(color, radius = 6.dp.toPx(), center = it) }
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
