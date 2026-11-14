// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.fastkiwi.scan.BuildConfig
import com.fastkiwi.scan.R
import com.fastkiwi.scan.ui.theme.Dimens
import kotlinx.coroutines.launch

/**
 * Update links (council decision D-077). The app has no network code and no
 * INTERNET permission: these only hand a link to another app (Obtainium or the
 * browser), which the user chooses to use.
 */
object UpdateLinks {
    const val RELEASES = "https://github.com/sarmadiq9090-coder/scan-kiwi/releases/latest"
    const val OBTAINIUM_ADD = "obtainium://add/https://github.com/sarmadiq9090-coder/scan-kiwi"
    const val OBTAINIUM_DOWNLOAD = "https://github.com/ImranR98/Obtainium/releases/latest"
}

/** Opens a link in another app; false when no app can handle it. */
fun openUri(ctx: Context, uri: String): Boolean = try {
    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    true
} catch (e: ActivityNotFoundException) {
    false
} catch (e: SecurityException) {
    false
}

/** Settings ▸ «التحديثات» (design spec §3.9). */
@Composable
fun UpdatesScreen(vm: AppViewModel) {
    val ctx = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val noBrowser = stringResource(R.string.msg_no_browser)
    var obtainiumMissing by remember { mutableStateOf(false) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = { KiwiTopBar(stringResource(R.string.updates_title), NavIcon.BACK, { vm.back() }) },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = Dimens.screen).navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Hero: app mark, name, version, offline tag.
            Box(
                Modifier.padding(top = Dimens.l).size(72.dp).clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) { Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.size(72.dp)) }
            Spacer(Modifier.height(Dimens.m))
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall)
            Text(
                stringResource(R.string.version_full, BuildConfig.VERSION_NAME, LocalFormat.count(BuildConfig.VERSION_CODE)),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(Dimens.s))
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.small) {
                Text(stringResource(R.string.updates_offline_tag), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = Dimens.m, vertical = Dimens.xs))
            }

            Column(Modifier.fillMaxWidth()) {
                SectionHeader(stringResource(R.string.updates_group))
                SettingsGroup(2) { i, shape ->
                    if (i == 0) {
                        SettingsRow(KiwiIcons.update(), stringResource(R.string.updates_obtainium), stringResource(R.string.updates_obtainium_sub), shape, {
                            if (!openUri(ctx, UpdateLinks.OBTAINIUM_ADD)) obtainiumMissing = true
                        }, trailing = KiwiIcons.openInNew())
                    } else {
                        SettingsRow(KiwiIcons.openInNew(), stringResource(R.string.updates_check), stringResource(R.string.updates_check_sub), shape, {
                            if (!openUri(ctx, UpdateLinks.RELEASES)) scope.launch { snackbar.showSnackbar(noBrowser) }
                        }, trailing = KiwiIcons.openInNew())
                    }
                }
                Spacer(Modifier.height(Dimens.xl))
                Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(Dimens.l), verticalArrangement = Arrangement.spacedBy(Dimens.s)) {
                        Text(stringResource(R.string.updates_manual_title), style = MaterialTheme.typography.titleSmall)
                        Text("${LocalFormat.count(1)}. " + stringResource(R.string.updates_manual_step1), style = MaterialTheme.typography.bodyMedium)
                        Text("${LocalFormat.count(2)}. " + stringResource(R.string.updates_manual_step2), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Spacer(Modifier.height(Dimens.l))
                // D-077 wording, word for word.
                Text(stringResource(R.string.network_statement), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(Dimens.xl))
            }
        }
    }

    if (obtainiumMissing) {
        AlertDialog(
            onDismissRequest = { obtainiumMissing = false },
            title = { Text(stringResource(R.string.obtainium_missing_title)) },
            text = { Text(stringResource(R.string.obtainium_missing_body)) },
            confirmButton = {
                TextButton(onClick = {
                    obtainiumMissing = false
                    if (!openUri(ctx, UpdateLinks.OBTAINIUM_DOWNLOAD)) scope.launch { snackbar.showSnackbar(noBrowser) }
                }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.obtainium_download)) }
            },
            dismissButton = {
                TextButton(onClick = { obtainiumMissing = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}
