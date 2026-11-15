// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.ui

import androidx.core.net.toUri
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
import com.fastkiwi.scan.update.UpdateError
import com.fastkiwi.scan.update.UpdateState
import kotlinx.coroutines.launch

/**
 * Update links. Since 2.0 the app checks GitHub itself (`update/`, owner's order 2026-11-15, replacing
 * D-077's "no network"); these links stay as other ways: Obtainium or the browser, which the user chooses.
 */
object UpdateLinks {
    const val RELEASES = "https://github.com/sarmadiq9090-coder/scan-kiwi/releases/latest"
    const val OBTAINIUM_ADD = "obtainium://add/https://github.com/sarmadiq9090-coder/scan-kiwi"
    const val OBTAINIUM_DOWNLOAD = "https://github.com/ImranR98/Obtainium/releases/latest"
}

/** Opens a link in another app; false when no app can handle it. */
fun openUri(ctx: Context, uri: String): Boolean = try {
    ctx.startActivity(Intent(Intent.ACTION_VIEW, uri.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
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
                // 2.0-update: the app's own check (GitHub only), and its two settings.
                SectionHeader(stringResource(R.string.updates_group))
                val st = vm.updates.state.value
                val busy = st is UpdateState.Checking || st is UpdateState.Downloading
                val status = updateStatus(st)
                val release = when (st) {
                    is UpdateState.Available -> st.release
                    is UpdateState.Failed -> st.release
                    else -> null
                }
                val rows = if (release != null || st is UpdateState.Ready) 2 else 1
                SettingsGroup(rows) { i, shape ->
                    if (i == 0) {
                        SettingsRow(KiwiIcons.update(), stringResource(R.string.update_check_now), status, shape, {
                            if (!busy) vm.updates.checkNow()
                        })
                    } else if (st is UpdateState.Ready) {
                        SettingsRow(KiwiIcons.update(), stringResource(R.string.update_install_again), stringResource(R.string.update_ready), shape, {
                            vm.updates.install(st.apk, st.release)
                        })
                    } else if (st is UpdateState.Failed && st.error == UpdateError.INSTALL && release != null) {
                        SettingsRow(KiwiIcons.update(), stringResource(R.string.update_retry), stringResource(R.string.update_err_install), shape, {
                            if (!busy) vm.updates.retryInstall(release)
                        })
                    } else if (release != null) {
                        SettingsRow(KiwiIcons.update(), stringResource(R.string.update_download_install), stringResource(R.string.update_available, release.version.toString()), shape, {
                            if (!busy) vm.updates.downloadAndInstall(release)
                        })
                    }
                }
                Spacer(Modifier.height(Dimens.m))
                var auto by remember { mutableStateOf(vm.settings.autoUpdateCheck) }
                var wifi by remember { mutableStateOf(vm.settings.updateWifiOnly) }
                SettingsGroup(2) { i, shape ->
                    if (i == 0) {
                        SettingsSwitchRow(KiwiIcons.update(), stringResource(R.string.update_auto), stringResource(R.string.update_auto_hint), auto, shape) {
                            auto = it; vm.settings.autoUpdateCheck = it
                        }
                    } else {
                        SettingsSwitchRow(KiwiIcons.update(), stringResource(R.string.update_wifi_only), stringResource(R.string.update_wifi_only_hint), wifi, shape) {
                            wifi = it; vm.settings.updateWifiOnly = it
                        }
                    }
                }
                SectionHeader(stringResource(R.string.update_other_ways))
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
                Text(stringResource(R.string.whats_new_line), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(Dimens.m))
                // 2.0-update: the owner's wording, word for word, then the details.
                Text(stringResource(R.string.network_statement), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(Dimens.s))
                Text(stringResource(R.string.update_details), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

/** 2.0-update: the status line under «تحقق من التحديثات». */
@Composable
private fun updateStatus(st: UpdateState): String = when (st) {
    UpdateState.Idle -> stringResource(R.string.updates_version, BuildConfig.VERSION_NAME)
    UpdateState.Checking -> stringResource(R.string.update_checking)
    UpdateState.UpToDate -> stringResource(R.string.update_up_to_date)
    is UpdateState.Available -> stringResource(R.string.update_available, st.release.version.toString())
    is UpdateState.Downloading -> stringResource(
        R.string.update_downloading,
        if (st.total > 0) java.text.NumberFormat.getPercentInstance().format(st.done.toDouble() / st.total) else "",
    )
    is UpdateState.Ready -> stringResource(R.string.update_ready)
    is UpdateState.Failed -> stringResource(updateErrorText(st.error))
}

fun updateErrorText(e: UpdateError): Int = when (e) {
    UpdateError.OFFLINE -> R.string.update_err_offline
    UpdateError.NETWORK -> R.string.update_err_network
    UpdateError.NO_RELEASE -> R.string.update_err_no_release
    UpdateError.BAD_RELEASE -> R.string.update_err_bad_release
    UpdateError.WIFI_REQUIRED -> R.string.update_err_wifi
    UpdateError.SHA_MISMATCH -> R.string.update_err_sha
    UpdateError.SIGNATURE_MISMATCH -> R.string.update_err_signature
    UpdateError.NOT_NEWER -> R.string.update_err_not_newer
    UpdateError.STORAGE -> R.string.update_err_storage
    UpdateError.INSTALL -> R.string.update_err_install
}

/** 2.0-update: shown once (the owner's wording), before the first automatic check. */
@Composable
fun UpdateNoticeDialog(vm: AppViewModel) {
    if (!vm.updates.noticePending.value) return
    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.update_notice_title)) },
        text = { Text(stringResource(R.string.network_statement), style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            TextButton(onClick = { vm.updates.noticeSeen(); vm.updates.onAppOpened() }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.update_notice_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = { vm.updates.noticeSeen(); vm.open(Screen.Updates) }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.update_notice_settings))
            }
        },
    )
}

/** 2.0 torture gate: the user left the app while the update downloaded, so Android's confirmation could not open then. */
@Composable
fun UpdateConfirmDialog(vm: AppViewModel) {
    if (!vm.updates.confirmPending.value) return
    AlertDialog(
        onDismissRequest = { vm.updates.dismissPendingConfirm() },
        title = { Text(stringResource(R.string.update_ready_title)) },
        text = { Text(stringResource(R.string.update_ready_body), style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            TextButton(onClick = { vm.updates.openPendingConfirm() }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.update_install_again))
            }
        },
        dismissButton = {
            TextButton(onClick = { vm.updates.dismissPendingConfirm() }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.update_later))
            }
        },
    )
}
