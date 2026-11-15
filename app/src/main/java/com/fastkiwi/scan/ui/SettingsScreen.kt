// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.ui

import android.app.Activity
import android.app.LocaleManager
import android.os.Build
import android.os.LocaleList
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.fastkiwi.scan.BuildConfig
import com.fastkiwi.scan.R
import com.fastkiwi.scan.core.Filter
import com.fastkiwi.scan.ocr.FallbackReason
import com.fastkiwi.scan.ocr.OcrDiagnostics
import com.fastkiwi.scan.ocr.OcrMode
import com.fastkiwi.scan.ui.theme.Dimens
import com.fastkiwi.scan.ui.theme.ThemeMode

/** Settings as grouped rows (design spec §3.7); choices open in dialogs, changes apply at once. */
@Composable
fun SettingsScreen(vm: AppViewModel) {
    val ctx = LocalContext.current
    val s = vm.settings
    var auto by remember { mutableStateOf(s.autoCapture) }
    var review by remember { mutableStateOf(s.reviewAfterCapture) }
    var filter by remember { mutableStateOf(s.defaultFilter) }
    var lang by remember { mutableStateOf(currentLanguage(ctx)) }
    var dialog by remember { mutableStateOf<String?>(null) }
    // 2.0-ocr
    var ocrMode by remember { mutableStateOf(s.ocrMode) }
    var ocrAfter by remember { mutableStateOf(s.ocrAfterCapture) }
    val snackbar = remember { SnackbarHostState() }

    val langLabels = listOf("" to R.string.language_system, "ar" to R.string.language_arabic, "en" to R.string.language_english)
    val themeLabels = listOf(ThemeMode.SYSTEM to R.string.theme_system, ThemeMode.LIGHT to R.string.theme_light, ThemeMode.DARK to R.string.theme_dark)

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = { KiwiTopBar(stringResource(R.string.settings), NavIcon.BACK, { vm.back() }) },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = Dimens.screen).navigationBarsPadding(),
        ) {
            SectionHeader(stringResource(R.string.settings_group_scan))
            SettingsGroup(3) { i, shape ->
                when (i) {
                    0 -> SettingsSwitchRow(KiwiIcons.autoCapture(), stringResource(R.string.auto_capture), stringResource(R.string.auto_capture_hint), auto, shape) {
                        auto = it; s.autoCapture = it
                    }
                    1 -> SettingsSwitchRow(KiwiIcons.crop(), stringResource(R.string.review_after_capture), stringResource(R.string.review_after_capture_hint), review, shape) {
                        review = it; s.reviewAfterCapture = it
                    }
                    else -> SettingsRow(KiwiIcons.filter(), stringResource(R.string.default_filter), stringResource(filterLabel(filter)), shape, { dialog = "filter" })
                }
            }

            SectionHeader(stringResource(R.string.settings_group_display))
            val dynamicSupported = Build.VERSION.SDK_INT >= 31
            SettingsGroup(if (dynamicSupported) 3 else 2) { i, shape ->
                when (i) {
                    0 -> SettingsRow(
                        KiwiIcons.language(), stringResource(R.string.language),
                        stringResource(langLabels.first { it.first == lang }.second), shape, { dialog = "language" },
                    )
                    1 -> SettingsRow(
                        KiwiIcons.theme(), stringResource(R.string.theme_title),
                        stringResource(themeLabels.first { it.first == vm.themeMode.value }.second), shape, { dialog = "theme" },
                    )
                    else -> SettingsSwitchRow(
                        KiwiIcons.palette(), stringResource(R.string.dynamic_color_title), stringResource(R.string.dynamic_color_hint),
                        vm.dynamicColor.value, shape,
                    ) { vm.setDynamicColor(it) }
                }
            }

            // 2.0-ocr: reading mode and background reading
            SectionHeader(stringResource(R.string.ocr_settings_group))
            SettingsGroup(2) { i, shape ->
                when (i) {
                    0 -> SettingsRow(
                        KiwiIcons.ocr(), stringResource(R.string.ocr_mode_title),
                        stringResource(ocrModeLabel(ocrMode)) + " · " + stringResource(ocrModeHint(ocrMode)), shape, { dialog = "ocr_mode" },
                    )
                    else -> SettingsSwitchRow(KiwiIcons.ocr(), stringResource(R.string.ocr_after_capture), stringResource(R.string.ocr_after_capture_hint), ocrAfter, shape) {
                        ocrAfter = it; s.ocrAfterCapture = it
                    }
                }
            }

            // 2.0-perf: low-memory mode (automatic / always on / off)
            SectionHeader(stringResource(R.string.perf_settings_group))
            SettingsGroup(1) { _, shape ->
                SettingsRow(
                    KiwiIcons.settings(), stringResource(R.string.perf_low_memory_title),
                    stringResource(lowMemorySummary(vm.lowMemoryMode.value)) + " · " + stringResource(R.string.perf_low_memory_hint),
                    shape, { dialog = "low_memory" },
                )
            }

            SectionHeader(stringResource(R.string.privacy_title))
            Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(Dimens.l)) {
                    Icon(KiwiIcons.privacy(), null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
                    Spacer(Modifier.width(Dimens.l))
                    Column(verticalArrangement = Arrangement.spacedBy(Dimens.s)) {
                        Text(stringResource(R.string.privacy_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onTertiaryContainer)
                        Text(stringResource(R.string.network_statement), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onTertiaryContainer)
                    }
                }
            }

            SectionHeader(stringResource(R.string.settings_group_help))
            val diag = remember(dialog) { vm.ocrDiagnostics.snapshot() }
            SettingsGroup(4) { i, shape ->
                when (i) {
                    0 -> SettingsRow(KiwiIcons.feedback(), stringResource(R.string.feedback_title), stringResource(R.string.feedback_settings_hint), shape, {
                        vm.open(Screen.Feedback())
                    })
                    1 -> SettingsRow(KiwiIcons.update(), stringResource(R.string.updates_title), stringResource(R.string.updates_version, BuildConfig.VERSION_NAME), shape, {
                        vm.open(Screen.Updates)
                    })
                    // 2.0-ocr: local-only diagnostics (last OCR time per page, peak native heap)
                    2 -> SettingsRow(KiwiIcons.ocr(), stringResource(R.string.ocr_diag_row), ocrDiagSummary(diag), shape, { dialog = "ocr_diag" })
                    else -> SettingsRow(KiwiIcons.info(), stringResource(R.string.about_row), null, shape, { dialog = "about" })
                }
            }
            Spacer(Modifier.height(Dimens.xl))
        }
    }

    when (dialog) {
        "filter" -> ChoiceDialog(
            stringResource(R.string.default_filter), Filter.entries.map { stringResource(filterLabel(it)) }, Filter.entries.indexOf(filter),
            onDismiss = { dialog = null },
        ) { filter = Filter.entries[it]; s.defaultFilter = filter; dialog = null }
        "language" -> ChoiceDialog(
            stringResource(R.string.language), langLabels.map { stringResource(it.second) }, langLabels.indexOfFirst { it.first == lang },
            onDismiss = { dialog = null },
        ) {
            dialog = null
            lang = langLabels[it].first
            applyLanguage(ctx as Activity, lang)
        }
        "theme" -> ChoiceDialog(
            stringResource(R.string.theme_title), themeLabels.map { stringResource(it.second) }, themeLabels.indexOfFirst { it.first == vm.themeMode.value },
            onDismiss = { dialog = null },
        ) { vm.setThemeMode(themeLabels[it].first); dialog = null }
        "about" -> AboutDialog { dialog = null }
        // 2.0-ocr
        "ocr_mode" -> ChoiceDialog(
            stringResource(R.string.ocr_mode_title),
            OcrMode.entries.map { stringResource(ocrModeLabel(it)) + " — " + stringResource(ocrModeHint(it)) }, OcrMode.entries.indexOf(ocrMode),
            onDismiss = { dialog = null },
        ) { ocrMode = OcrMode.entries[it]; s.ocrMode = ocrMode; dialog = null }
        "ocr_diag" -> OcrDiagnosticsDialog(vm.ocrDiagnostics.snapshot()) { dialog = null }
        // 2.0-perf
        "low_memory" -> ChoiceDialog(
            stringResource(R.string.perf_low_memory_title),
            com.fastkiwi.scan.perf.MemoryBudget.Mode.entries.map { stringResource(lowMemoryLabel(it)) },
            com.fastkiwi.scan.perf.MemoryBudget.Mode.entries.indexOf(vm.lowMemoryMode.value),
            onDismiss = { dialog = null },
        ) { vm.setLowMemoryMode(com.fastkiwi.scan.perf.MemoryBudget.Mode.entries[it]); dialog = null }
    }
}

// 2.0-perf: low-memory mode labels
private fun lowMemoryLabel(m: com.fastkiwi.scan.perf.MemoryBudget.Mode) = when (m) {
    com.fastkiwi.scan.perf.MemoryBudget.Mode.AUTO -> R.string.perf_low_memory_auto
    com.fastkiwi.scan.perf.MemoryBudget.Mode.ON -> R.string.perf_low_memory_on
    com.fastkiwi.scan.perf.MemoryBudget.Mode.OFF -> R.string.perf_low_memory_off
}

private fun lowMemorySummary(m: com.fastkiwi.scan.perf.MemoryBudget.Mode) =
    if (m != com.fastkiwi.scan.perf.MemoryBudget.Mode.AUTO) lowMemoryLabel(m)
    else if (com.fastkiwi.scan.perf.MemoryBudget.deviceLow) R.string.perf_low_memory_auto_on
    else R.string.perf_low_memory_auto_off

// 2.0-ocr: OCR settings helpers
private fun ocrModeLabel(m: OcrMode) = if (m == OcrMode.ACCURATE) R.string.ocr_mode_accurate else R.string.ocr_mode_fast
private fun ocrModeHint(m: OcrMode) = if (m == OcrMode.ACCURATE) R.string.ocr_mode_accurate_hint else R.string.ocr_mode_fast_hint

private fun seconds(ms: Long) = String.format(java.util.Locale.ROOT, "%.1f", ms / 1000.0)
private fun megabytes(bytes: Long) = (bytes / (1024 * 1024)).toString()

@Composable
private fun ocrDiagSummary(d: OcrDiagnostics.Snapshot): String =
    if (!d.hasData) stringResource(R.string.ocr_diag_none)
    else stringResource(R.string.ocr_diag_summary, seconds(d.lastMillis), megabytes(d.peakNativeHeapBytes))

@Composable
private fun ocrEngineLabel(d: OcrDiagnostics.Snapshot): String = when (d.fallback) {
    FallbackReason.LOW_RAM_DEVICE -> stringResource(R.string.ocr_fallback_low_ram)
    FallbackReason.RUNTIME_MISSING -> stringResource(R.string.ocr_fallback_runtime)
    FallbackReason.MODEL_MISSING -> stringResource(R.string.ocr_fallback_model)
    FallbackReason.INIT_FAILED -> stringResource(R.string.ocr_fallback_init)
    FallbackReason.ENGLISH_ONLY -> stringResource(R.string.ocr_fallback_english)
    null -> stringResource(ocrModeLabel(d.effective ?: OcrMode.ACCURATE))
}

/** The diagnostics row's details, for the owner's phone check (Accurate ≤ 8 s per page, heap ≤ 300 MB). */
@Composable
private fun OcrDiagnosticsDialog(d: OcrDiagnostics.Snapshot, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ocr_diag_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.s)) {
                if (!d.hasData) {
                    Text(stringResource(R.string.ocr_diag_none), style = MaterialTheme.typography.bodyMedium)
                } else {
                    Text(stringResource(R.string.ocr_diag_last, seconds(d.lastMillis)), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.ocr_diag_recent, d.recentMillis.joinToString(" · ") { seconds(it) }), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.ocr_diag_heap, megabytes(d.peakNativeHeapBytes)), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.ocr_diag_engine, ocrEngineLabel(d)), style = MaterialTheme.typography.bodyMedium)
                }
                Text(stringResource(R.string.ocr_diag_note), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.close)) } },
    )
}

/** Single-choice list in a dialog (56 dp rows). */
@Composable
fun ChoiceDialog(title: String, options: List<String>, selected: Int, onDismiss: () -> Unit, onSelect: (Int) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.selectableGroup()) {
                options.forEachIndexed { i, label ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 56.dp)
                            .selectable(selected = i == selected, onClick = { onSelect(i) }, role = Role.RadioButton),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = i == selected, onClick = null)
                        Text(label, Modifier.padding(start = Dimens.l), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.close)) } },
    )
}

/** About: version, network statement and the bundled licence notices. */
@Composable
private fun AboutDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val notices = remember { runCatching { ctx.assets.open("THIRD_PARTY_NOTICES.txt").bufferedReader().readText() }.getOrDefault("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.about_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Dimens.m)) {
                Text(stringResource(R.string.about_body, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.network_statement), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.licenses_body), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.show_licenses), style = MaterialTheme.typography.titleSmall)
                Text(notices, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.close)) } },
    )
}

private fun currentLanguage(ctx: android.content.Context): String =
    if (Build.VERSION.SDK_INT >= 33) {
        ctx.getSystemService(LocaleManager::class.java).applicationLocales.toLanguageTags().substringBefore('-').substringBefore(',')
    } else {
        Settings.languageTag(ctx)
    }.let { if (it == "ar" || it == "en") it else "" }

/** Per-app language: system setting on Android 13+, our own override below that. */
fun applyLanguage(activity: Activity, tag: String) {
    if (Build.VERSION.SDK_INT >= 33) {
        activity.getSystemService(LocaleManager::class.java).applicationLocales =
            if (tag.isEmpty()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
    } else {
        Settings.setLanguageTag(activity, tag)
        activity.recreate()
    }
}
