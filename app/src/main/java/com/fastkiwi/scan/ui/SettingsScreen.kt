package com.fastkiwi.scan.ui

import android.app.Activity
import android.app.LocaleManager
import android.os.Build
import android.os.LocaleList
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: AppViewModel) {
    val ctx = LocalContext.current
    val s = vm.settings
    var auto by remember { mutableStateOf(s.autoCapture) }
    var filter by remember { mutableStateOf(s.defaultFilter) }
    var lang by remember { mutableStateOf(currentLanguage(ctx)) }
    var showLicenses by remember { mutableStateOf(false) }

    Scaffold(topBar = {
        TopAppBar(
            navigationIcon = { IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            title = { Text(stringResource(R.string.settings)) },
        )
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Section(R.string.language)
            Column(Modifier.selectableGroup()) {
                listOf("" to R.string.language_system, "ar" to R.string.language_arabic, "en" to R.string.language_english).forEach { (tag, label) ->
                    Radio(stringResource(label), lang == tag) {
                        lang = tag
                        applyLanguage(ctx as Activity, tag)
                    }
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SwitchRow(R.string.auto_capture, R.string.auto_capture_hint, auto) { auto = it; s.autoCapture = it }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Section(R.string.default_filter)
            Column(Modifier.selectableGroup()) {
                Filter.entries.forEach { f ->
                    Radio(stringResource(filterLabel(f)), filter == f) { filter = f; s.defaultFilter = f }
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Section(R.string.privacy_title)
            Text(stringResource(R.string.privacy_body), style = MaterialTheme.typography.bodyMedium)
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Section(R.string.about_title)
            Text(stringResource(R.string.about_body, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.licenses_body), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp))
            androidx.compose.material3.TextButton(onClick = { showLicenses = true }) { Text(stringResource(R.string.show_licenses)) }
        }
    }
    if (showLicenses) {
        val text = remember { runCatching { ctx.assets.open("THIRD_PARTY_NOTICES.txt").bufferedReader().readText() }.getOrDefault("") }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showLicenses = false },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { showLicenses = false }) { Text(stringResource(R.string.close)) } },
            title = { Text(stringResource(R.string.show_licenses)) },
            text = {
                Text(
                    text, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            },
        )
    }
}

@Composable
private fun Section(res: Int) {
    Text(stringResource(res), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
}

@Composable
private fun Radio(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, onClick = onClick, role = Role.RadioButton).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, Modifier.padding(start = 16.dp), style = MaterialTheme.typography.bodyLarge)
    }
}

private fun currentLanguage(ctx: android.content.Context): String =
    if (Build.VERSION.SDK_INT >= 33) {
        ctx.getSystemService(LocaleManager::class.java).applicationLocales.toLanguageTags().substringBefore('-').substringBefore(',')
    } else {
        Settings.languageTag(ctx)
    }

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
