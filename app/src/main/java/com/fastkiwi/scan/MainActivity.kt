package com.fastkiwi.scan

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import com.fastkiwi.scan.ui.AppViewModel
import com.fastkiwi.scan.ui.CameraScreen
import com.fastkiwi.scan.ui.CropScreen
import com.fastkiwi.scan.ui.EditorScreen
import com.fastkiwi.scan.ui.ExportResult
import com.fastkiwi.scan.ui.HomeScreen
import com.fastkiwi.scan.ui.PageScreen
import com.fastkiwi.scan.ui.Screen
import com.fastkiwi.scan.ui.SettingsScreen
import com.fastkiwi.scan.ui.Settings
import com.fastkiwi.scan.ui.theme.KiwiTheme
import java.util.Locale

const val ACTION_QUICK_SCAN = "com.fastkiwi.scan.action.QUICK_SCAN"

class MainActivity : ComponentActivity() {
    internal val vm: AppViewModel by viewModels()

    override fun attachBaseContext(base: Context) {
        // Android < 13 has no per-app language setting: apply our own override.
        val tag = Settings.languageTag(base)
        if (Build.VERSION.SDK_INT < 33 && tag.isNotEmpty()) {
            val locale = Locale.forLanguageTag(tag)
            Locale.setDefault(locale)
            val cfg = Configuration(base.resources.configuration)
            cfg.setLocale(locale)
            cfg.setLayoutDirection(locale)
            super.attachBaseContext(base.createConfigurationContext(cfg))
        } else {
            super.attachBaseContext(base)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleIncoming(intent)
        setContent {
            KiwiTheme {
                Surface(Modifier.fillMaxSize()) { App(vm) }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncoming(intent)
    }

    /** Files shared to the app from other apps ("Share > Kiwi Scan"). */
    private fun handleIncoming(intent: Intent?) {
        intent ?: return
        // Home-screen shortcut "Scan": straight to the camera.
        if (intent.action == ACTION_QUICK_SCAN) {
            if (vm.screen !is Screen.Camera) vm.open(Screen.Camera(null))
            return
        }
        val uris: List<Uri> = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java) ?: emptyList()
            Intent.ACTION_VIEW -> listOfNotNull(intent.data)
            else -> emptyList()
        }.filter { isAcceptableIncoming(it, "$packageName.files") }
        if (uris.isNotEmpty()) vm.importUris(null, uris) { id -> vm.open(Screen.Editor(id)) }
    }
}

/**
 * Only content:// URIs from other apps are imported. Any app can send us an
 * intent, and a file:// URI (or one of our own FileProvider URIs) would make Kiwi
 * Scan read its own private files, such as the signature image or another
 * document's export, into a new document.
 */
internal fun isAcceptableIncoming(uri: Uri, ownAuthority: String): Boolean {
    if (!"content".equals(uri.scheme, ignoreCase = true)) return false
    // "10@authority" addresses the same provider in another user profile.
    val authority = uri.authority?.substringAfterLast('@') ?: return false
    return authority.isNotEmpty() && !authority.equals(ownAuthority, ignoreCase = true)
}

@Composable
private fun App(vm: AppViewModel) {
    val ctx = LocalContext.current
    val res = androidx.compose.ui.platform.LocalResources.current
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        vm.messages.collect { m ->
            val text = if (m.arg != null) res.getString(m.res, m.arg) else res.getString(m.res)
            val undoId = m.undoDocId
            if (undoId == null) {
                snackbar.showSnackbar(text)
            } else {
                val r = snackbar.showSnackbar(text, actionLabel = res.getString(R.string.undo), duration = SnackbarDuration.Long)
                if (r == SnackbarResult.ActionPerformed) vm.undo(undoId)
            }
        }
    }
    LaunchedEffect(Unit) {
        vm.exports.collect { r ->
            when (r) {
                is ExportResult.Share -> share(ctx, r.files, r.mime)
                is ExportResult.Saved -> snackbar.showSnackbar(
                    res.getString(R.string.msg_saved, android.text.format.Formatter.formatShortFileSize(ctx, r.bytes)),
                )
            }
        }
    }

    BackHandler(enabled = vm.backStack.size > 1) { vm.back() }

    Box(Modifier.fillMaxSize()) {
        when (val s = vm.screen) {
            Screen.Home -> HomeScreen(vm, snackbar)
            is Screen.Camera -> CameraScreen(vm, s)
            is Screen.Editor -> EditorScreen(vm, s.docId, snackbar)
            is Screen.Page -> PageScreen(vm, s.docId, s.index, snackbar)
            is Screen.Crop -> CropScreen(vm, s.docId, s.pageId)
            is Screen.Sign -> com.fastkiwi.scan.ui.SignatureScreen(vm, s.docId, s.pageId)
            Screen.Settings -> SettingsScreen(vm)
        }
    }
}

private fun share(ctx: Context, files: List<java.io.File>, mime: String) {
    if (files.isEmpty()) return
    val authority = "${ctx.packageName}.files"
    val uris = ArrayList(files.map { FileProvider.getUriForFile(ctx, authority, it) })
    val intent = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
    }
    intent.type = mime
    intent.clipData = ClipData.newRawUri(null, uris[0]).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    ctx.startActivity(Intent.createChooser(intent, ctx.getString(R.string.share_via)))
}
