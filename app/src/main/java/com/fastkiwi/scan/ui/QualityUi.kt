// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.fastkiwi.scan.R
import com.fastkiwi.scan.core.QualityWarning
import com.fastkiwi.scan.data.PageQuality
import com.fastkiwi.scan.imaging.warnings
import com.fastkiwi.scan.ui.theme.Dimens
import kotlinx.coroutines.delay

/** Text of a quality warning (PRD §4.3). */
fun qualityLabel(w: QualityWarning): Int = when (w) {
    QualityWarning.INCOMPLETE -> R.string.pq_incomplete
    QualityWarning.BLURRY -> R.string.pq_blurry
    QualityWarning.GLARE -> R.string.pq_glare
    QualityWarning.DIM -> R.string.pq_dim
    QualityWarning.SKEWED -> R.string.pq_skewed
}

/** TalkBack hears a camera hint at most this often, however fast the frames change it. */
const val QUALITY_ANNOUNCE_MS = 3000L

/**
 * The camera's quality pill: one warning at a time (incomplete > blur > glare > dim), amber on
 * 72% black. The pill updates at once; the polite announcement is rate-limited.
 */
@Composable
fun CameraQualityPill(text: String?, modifier: Modifier = Modifier) {
    var announced by remember { mutableStateOf<String?>(null) }
    var last by remember { mutableLongStateOf(0L) }
    LaunchedEffect(text) {
        val wait = last + QUALITY_ANNOUNCE_MS - System.currentTimeMillis()
        if (wait > 0) delay(wait)
        announced = text
        last = System.currentTimeMillis()
    }
    if (text == null) return
    Row(
        modifier
            .background(Color.Black.copy(alpha = 0.72f), CircleShape)
            .padding(horizontal = Dimens.l, vertical = Dimens.s)
            .clearAndSetSemantics {
                contentDescription = announced ?: text
                liveRegion = LiveRegionMode.Polite
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(KiwiIcons.warning(), null, Modifier.size(20.dp), tint = Color(0xFFFFC857))
        Spacer(Modifier.width(Dimens.s))
        Text(text, color = Color.White, style = MaterialTheme.typography.titleSmall)
    }
}

/**
 * The page editor's warning chip with «إعادة الالتقاط», «إعادة القص» and «تجاهل».
 * Shown only while the page has an undismissed warning.
 */
@Composable
fun QualityChip(quality: PageQuality?, onRetake: () -> Unit, onRecrop: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val warnings = quality?.warnings().orEmpty()
    if (warnings.isEmpty()) return
    val first = stringResource(qualityLabel(warnings.first()))
    val title = if (warnings.size > 1) first + " " + stringResource(R.string.pq_more, warnings.size - 1) else first
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = modifier.fillMaxWidth().padding(horizontal = Dimens.l),
    ) {
        Column(Modifier.padding(start = Dimens.l, end = Dimens.s, top = Dimens.s)) {
            Row(Modifier.semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
                Icon(KiwiIcons.warning(), null, Modifier.size(20.dp))
                Spacer(Modifier.width(Dimens.s))
                Text(title, style = MaterialTheme.typography.titleSmall)
            }
            Text(stringResource(R.string.pq_note), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 28.dp))
            FlowRow(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onRetake, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.pq_retake)) }
                TextButton(onClick = onRecrop, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.pq_recrop)) }
                TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.pq_dismiss)) }
            }
        }
    }
}

/** A small warning badge for page tiles in the document grid (decorative size, labelled for TalkBack). */
@Composable
fun QualityBadge(quality: PageQuality?, modifier: Modifier = Modifier) {
    val w = quality?.warnings()?.firstOrNull() ?: return
    val label = stringResource(R.string.pq_badge, stringResource(qualityLabel(w)))
    Box(
        modifier
            .size(28.dp)
            .background(MaterialTheme.colorScheme.tertiaryContainer, CircleShape)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { Icon(KiwiIcons.warning(), null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onTertiaryContainer) }
}
