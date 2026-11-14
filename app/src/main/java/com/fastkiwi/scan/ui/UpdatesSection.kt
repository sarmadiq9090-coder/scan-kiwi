// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.fastkiwi.scan.BuildConfig
import com.fastkiwi.scan.R

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

/** Settings ▸ «التحديثات» / "Updates". */
@Composable
fun UpdatesSection(onNoBrowser: () -> Unit, onObtainiumMissing: () -> Unit) {
    val ctx = LocalContext.current
    Text(
        stringResource(R.string.updates_title), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp).semantics { heading() },
    )
    Text(stringResource(R.string.updates_version, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.bodyLarge)
    LinkRow(Icons.Filled.SystemUpdate, R.string.updates_obtainium, R.string.updates_obtainium_sub) {
        if (!openUri(ctx, UpdateLinks.OBTAINIUM_ADD)) onObtainiumMissing()
    }
    LinkRow(Icons.AutoMirrored.Filled.OpenInNew, R.string.updates_check, R.string.updates_check_sub) {
        if (!openUri(ctx, UpdateLinks.RELEASES)) onNoBrowser()
    }
    Text(stringResource(R.string.updates_body), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun LinkRow(icon: ImageVector, title: Int, subtitle: Int, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).semantics { role = Role.Button },
    ) {
        Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.padding(start = 16.dp)) {
                Text(stringResource(title), style = MaterialTheme.typography.bodyLarge)
                Text(stringResource(subtitle), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
