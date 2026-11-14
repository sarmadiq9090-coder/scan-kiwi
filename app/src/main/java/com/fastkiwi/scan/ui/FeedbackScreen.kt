// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.TextButton
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.fastkiwi.scan.BuildConfig
import com.fastkiwi.scan.R
import com.fastkiwi.scan.feedback.DeviceInfo
import com.fastkiwi.scan.feedback.Feedback
import com.fastkiwi.scan.feedback.FeedbackKind
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * «أبلغنا عن مشكلة أو اقتراح». Nothing is sent from the app (it has no network
 * access): the user's own mail app or browser opens with the text, and the
 * user reviews and sends it there.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedbackScreen(vm: AppViewModel, start: Screen.Feedback) {
    val ctx = LocalContext.current
    var kind by rememberSaveable { mutableStateOf(if (start.errorCode != null) FeedbackKind.PROBLEM else FeedbackKind.SUGGESTION) }
    var text by rememberSaveable { mutableStateOf("") }
    var attach by rememberSaveable { mutableStateOf(false) } // off by default (privacy)
    val info = remember(start) { deviceInfo(ctx, start.errorCode, start.errorClass) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val noMail = stringResource(R.string.feedback_no_mail_app)
    val noBrowser = stringResource(R.string.feedback_no_browser)

    fun open(uri: String, action: String, failMsg: String) {
        try {
            val intent = Intent(action, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (action == Intent.ACTION_SENDTO) {
                // Some mail apps read the extras rather than the mailto: query.
                val attached = if (attach) info else null
                intent.putExtra(Intent.EXTRA_EMAIL, arrayOf(Feedback.EMAIL))
                intent.putExtra(Intent.EXTRA_SUBJECT, Feedback.subject(kind, BuildConfig.VERSION_NAME))
                intent.putExtra(Intent.EXTRA_TEXT, Feedback.body(text, attached))
            }
            ctx.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            scope.launch { snackbar.showSnackbar(failMsg) }
        } catch (e: SecurityException) {
            scope.launch { snackbar.showSnackbar(failMsg) }
        }
    }

    val attached = if (attach) info else null
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = { KiwiTopBar(stringResource(R.string.feedback_title), NavIcon.BACK, { vm.back() }) },
        bottomBar = {
            // Sticky footer: primary mail, secondary GitHub, review note.
            BottomActionBar {
                Button(
                    onClick = { open(Feedback.mailto(kind, BuildConfig.VERSION_NAME, text, attached), Intent.ACTION_SENDTO, noMail) },
                    enabled = text.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                ) { Icon(KiwiIcons.mail(), null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.feedback_send_email)) }
                TextButton(
                    onClick = { open(Feedback.githubIssue(kind, BuildConfig.VERSION_NAME, text, attached), Intent.ACTION_VIEW, noBrowser) },
                    enabled = text.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) { Icon(KiwiIcons.openInNew(), null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.feedback_send_github)) }
                Text(
                    stringResource(R.string.feedback_review_note), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.large) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(KiwiIcons.lock(), null)
                    Text(stringResource(R.string.feedback_privacy), Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodyMedium)
                }
            }
            Text(
                stringResource(R.string.feedback_kind), style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 24.dp, bottom = 8.dp).semantics { heading() },
            )
            ChoiceSegmentedRow(
                listOf(stringResource(R.string.feedback_problem), stringResource(R.string.feedback_suggestion)),
                kind.ordinal, { kind = FeedbackKind.entries[it] },
            )
            OutlinedTextField(
                value = text,
                onValueChange = { text = Feedback.clipInput(it) },
                label = { Text(stringResource(if (kind == FeedbackKind.PROBLEM) R.string.feedback_hint_problem else R.string.feedback_hint_suggestion)) },
                supportingText = { Text(LocalFormat.count(text.length) + " / " + LocalFormat.count(Feedback.MAX_TEXT)) },
                minLines = 5,
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .toggleable(value = attach, onValueChange = { attach = it }, role = Role.Checkbox)
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = attach, onCheckedChange = null)
                Text(stringResource(R.string.feedback_attach_device), Modifier.padding(start = 8.dp))
            }
            if (attach) {
                Text(stringResource(R.string.feedback_attached_preview), style = MaterialTheme.typography.labelLarge)
                Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    Text(Feedback.deviceLines(info), Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

/** Only non-identifying details; never documents, images, OCR text, file names or logs. */
fun deviceInfo(ctx: Context, errorCode: String?, errorClass: String?): DeviceInfo {
    val locale = ctx.resources.configuration.locales[0] ?: Locale.getDefault()
    return DeviceInfo(
        appVersion = BuildConfig.VERSION_NAME,
        versionCode = BuildConfig.VERSION_CODE,
        android = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        model = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
        language = locale.toLanguageTag(),
        errorCode = errorCode,
        errorClass = errorClass,
    )
}
