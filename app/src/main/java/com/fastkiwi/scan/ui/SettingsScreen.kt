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
            SettingsGroup(3) { i, shape ->
                when (i) {
                    0 -> SettingsRow(KiwiIcons.feedback(), stringResource(R.string.feedback_title), stringResource(R.string.feedback_settings_hint), shape, {
                        vm.open(Screen.Feedback())
                    })
                    1 -> SettingsRow(KiwiIcons.update(), stringResource(R.string.updates_title), stringResource(R.string.updates_version, BuildConfig.VERSION_NAME), shape, {
                        vm.open(Screen.Updates)
                    })
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
    }
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
