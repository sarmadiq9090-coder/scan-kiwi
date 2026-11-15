// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.update

import android.content.Context
import android.os.Build
import androidx.annotation.VisibleForTesting
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import com.fastkiwi.scan.BuildConfig
import com.fastkiwi.scan.ui.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** What the «التحديثات» screen shows. */
sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: Release) : UpdateState
    data class Downloading(val release: Release, val done: Long, val total: Long) : UpdateState
    data class Ready(val release: Release, val apk: File) : UpdateState
    data class Failed(val error: UpdateError, val release: Release? = null) : UpdateState
}

/**
 * 2.0-update: the app side of [Updater]. The automatic check runs only when the app is opened, at
 * most once every 7 days ([UpdatePolicy]), and only after the one-time notice was shown; there is no
 * background service. [checkNow] is the manual button. Nothing is downloaded without a tap, and
 * nothing is installed without Android's own confirmation.
 */
class UpdateController(
    private val ctx: Context,
    private val settings: Settings,
    private val scope: CoroutineScope,
    private val updater: Updater = Updater(
        UpdateClient("ScanKiwi/${BuildConfig.VERSION_NAME}"),
        BuildConfig.VERSION_NAME,
        AndroidApkVerifier(ctx),
        File(ctx.cacheDir, "updates"),
    ) { if (isRobolectric()) NetworkState.OFFLINE else currentNetwork(ctx) },
    private val now: () -> Long = System::currentTimeMillis,
    /** Called when the automatic check finds a newer version (the app shows a message). */
    private val onAvailable: (Release) -> Unit = {},
) {
    private val _state = mutableStateOf<UpdateState>(UpdateState.Idle)
    val state: State<UpdateState> = _state

    private val _noticePending = mutableStateOf(!settings.updateNoticeShown && (showNoticeInTests || !isRobolectric()))
    /** The one-time notice about the update connection is waiting to be shown. */
    val noticePending: State<Boolean> = _noticePending

    private var job: Job? = null

    fun noticeSeen() {
        settings.updateNoticeShown = true
        _noticePending.value = false
    }

    /** At app start: the weekly automatic check, if it is due. Returns the job (tests wait on it). */
    fun onAppOpened(): Job? {
        val due = UpdatePolicy.autoCheckDue(settings.autoUpdateCheck, settings.updateNoticeShown, settings.lastUpdateCheck, now())
        if (!due || job?.isActive == true) return null
        return scope.launch {
            val r = withContext(Dispatchers.IO) { updater.check() }
            // Offline: nothing was asked; try again at the next start. Otherwise wait 7 days.
            if (!(r is CheckResult.Failed && r.error == UpdateError.OFFLINE)) settings.lastUpdateCheck = now()
            if (r is CheckResult.Available) {
                _state.value = UpdateState.Available(r.release)
                onAvailable(r.release)
            }
        }.also { job = it }
    }

    /** «تحقق من التحديثات»: any time. */
    fun checkNow(): Job? {
        if (job?.isActive == true) return null
        _state.value = UpdateState.Checking
        return scope.launch {
            val r = withContext(Dispatchers.IO) { updater.check() }
            _state.value = when (r) {
                CheckResult.UpToDate -> UpdateState.UpToDate
                is CheckResult.Available -> UpdateState.Available(r.release)
                is CheckResult.Failed -> UpdateState.Failed(r.error)
            }
        }.also { job = it }
    }

    /** «تنزيل وتثبيت»: download, verify (version, sha256, certificate), then hand to Android's installer. */
    fun downloadAndInstall(release: Release): Job? {
        if (job?.isActive == true) return null
        _state.value = UpdateState.Downloading(release, 0, release.apkSize)
        return scope.launch {
            val r = withContext(Dispatchers.IO) {
                updater.download(release, settings.updateWifiOnly) { done, total ->
                    scope.launch(Dispatchers.Main) { if (_state.value is UpdateState.Downloading) _state.value = UpdateState.Downloading(release, done, total) }
                }
            }
            when (r) {
                is DownloadResult.Failed -> _state.value = UpdateState.Failed(r.error, release)
                is DownloadResult.Verified -> {
                    _state.value = UpdateState.Ready(r.release, r.apk)
                    install(r.apk, r.release)
                }
            }
        }.also { job = it }
    }

    /** Opens Android's install confirmation for a verified file (again, if the user backed out). */
    fun install(apk: File, release: Release) {
        if (!apk.exists()) { _state.value = UpdateState.Failed(UpdateError.STORAGE, release); return }
        if (!installer(apk)) { updater.clean(); _state.value = UpdateState.Failed(UpdateError.STORAGE, release) }
    }

    @VisibleForTesting
    internal var installer: (File) -> Boolean = { f -> runCatching { UpdateInstaller.install(ctx, f) }.isSuccess }

    companion object {
        /**
         * Tests that check the notice itself turn it on; every other Robolectric test starts without it.
         * Under Robolectric the app's own updater also always sees "offline", so no test (and no black-box
         * session) can ever reach the real GitHub; the update tests inject a local mock server instead.
         */
        @VisibleForTesting
        @Volatile
        var showNoticeInTests = false

        private fun isRobolectric() = Build.FINGERPRINT == "robolectric"
    }
}
