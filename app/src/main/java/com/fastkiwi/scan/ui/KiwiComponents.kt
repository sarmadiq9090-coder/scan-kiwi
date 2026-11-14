// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fastkiwi.scan.R
import com.fastkiwi.scan.ui.theme.Dimens
import com.fastkiwi.scan.ui.theme.GroupShapes
import java.io.File
import java.text.NumberFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// ------------------------------------------------------------------ buttons and bars

/** Icon-only button with a content description and a long-press tooltip of the same text. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TooltipIconButton(icon: ImageVector, label: String, onClick: () -> Unit, enabled: Boolean = true, tint: Color? = null) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
    ) {
        // 48 dp on purpose: the tooltip anchor (long-press) must be a full touch target too.
        IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(48.dp)) {
            if (tint != null) Icon(icon, label, tint = tint) else Icon(icon, label)
        }
    }
}

enum class NavIcon { BACK, CLOSE, NONE }

/** Small top app bar with an optional subtitle; titles never clip Arabic descenders. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KiwiTopBar(
    title: String,
    nav: NavIcon,
    onNav: () -> Unit = {},
    subtitle: String? = null,
    titleModifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        navigationIcon = {
            when (nav) {
                NavIcon.BACK -> TooltipIconButton(KiwiIcons.back(), androidx.compose.ui.res.stringResource(R.string.back), onNav)
                NavIcon.CLOSE -> TooltipIconButton(KiwiIcons.close(), androidx.compose.ui.res.stringResource(R.string.close), onNav)
                NavIcon.NONE -> leading?.invoke()
            }
        },
        title = {
            Column(titleModifier) {
                Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) {
                    Text(
                        subtitle, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        actions = actions,
    )
}

/** Bottom bar holding the screen's buttons, on surfaceContainer above the gesture bar. */
@Composable
fun BottomActionBar(content: @Composable ColumnScope.() -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(Dimens.l), content = content)
    }
}

/**
 * Two buttons, secondary first and primary at the end (the primary is on the
 * left in RTL, like Android's own dialogs). At large font scales they stack,
 * primary on top, so labels are never cut.
 */
@Composable
fun ButtonPair(
    secondary: @Composable (Modifier) -> Unit,
    primary: @Composable (Modifier) -> Unit,
) {
    val stack = LocalDensity.current.fontScale >= 1.5f
    if (stack) {
        Column(verticalArrangement = Arrangement.spacedBy(Dimens.s)) {
            primary(Modifier.fillMaxWidth().heightIn(min = Dimens.button))
            secondary(Modifier.fillMaxWidth().heightIn(min = Dimens.button))
        }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.m), verticalAlignment = Alignment.CenterVertically) {
            secondary(Modifier.weight(1f).heightIn(min = Dimens.button))
            primary(Modifier.weight(1f).heightIn(min = Dimens.button))
        }
    }
}

/** A labelled toolbar action: icon in a 56x32 dp indicator with the label under it. */
@Composable
fun RowScope.ToolbarAction(icon: ImageVector, label: String, onClick: () -> Unit, enabled: Boolean = true) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        color = Color.Transparent,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.weight(1f).heightIn(min = 64.dp),
    ) {
        Column(
            Modifier.padding(vertical = Dimens.xs),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            val color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            Box(Modifier.size(width = 56.dp, height = 32.dp), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = color)
            }
            Text(
                label, style = MaterialTheme.typography.labelMedium, color = color, textAlign = TextAlign.Center,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Up to five [ToolbarAction]s in a bottom bar. */
@Composable
fun ActionToolbar(systemInsets: Boolean = true, content: @Composable RowScope.() -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(
            Modifier.fillMaxWidth().then(if (systemInsets) Modifier.navigationBarsPadding() else Modifier).padding(horizontal = Dimens.s, vertical = Dimens.xs),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

// ------------------------------------------------------------------ settings-style groups

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(start = Dimens.l, end = Dimens.l, top = 20.dp, bottom = Dimens.s).semantics { heading() },
    )
}

/** A group of rows with 20 dp outer and 4 dp inner corners and 2 dp gaps. */
@Composable
fun SettingsGroup(count: Int, row: @Composable (index: Int, shape: Shape) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(GroupShapes.Gap)) {
        for (i in 0 until count) row(i, GroupShapes.forRow(i, count))
    }
}

@Composable
fun SettingsRow(
    icon: ImageVector,
    title: String,
    supporting: String?,
    shape: Shape,
    onClick: () -> Unit,
    trailing: ImageVector? = KiwiIcons.chevron(),
) {
    Surface(onClick = onClick, shape = shape, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        RowContent(icon, title, supporting) { trailing?.let { Icon(it, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) } }
    }
}

@Composable
fun SettingsSwitchRow(
    icon: ImageVector?,
    title: String,
    supporting: String?,
    checked: Boolean,
    shape: Shape,
    enabled: Boolean = true,
    color: Color = MaterialTheme.colorScheme.surfaceContainer,
    onChange: (Boolean) -> Unit,
) {
    Surface(shape = shape, color = color, modifier = Modifier.fillMaxWidth()) {
        Box(Modifier.toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)) {
            RowContent(icon, title, supporting, enabled) { Switch(checked = checked, onCheckedChange = null, enabled = enabled) }
        }
    }
}

@Composable
private fun RowContent(icon: ImageVector?, title: String, supporting: String?, enabled: Boolean = true, trailing: @Composable () -> Unit) {
    val alpha = if (enabled) 1f else 0.38f
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = Dimens.l, vertical = Dimens.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha))
            Spacer(Modifier.width(Dimens.l))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha))
            if (supporting != null) {
                Text(supporting, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha))
            }
        }
        Spacer(Modifier.width(Dimens.m))
        trailing()
    }
}

/** Single choice between a few options (format, quality, feedback type). */
@Composable
fun ChoiceSegmentedRow(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    SingleChoiceSegmentedButtonRow(modifier.fillMaxWidth()) {
        options.forEachIndexed { i, label ->
            SegmentedButton(
                selected = i == selected,
                onClick = { onSelect(i) },
                shape = SegmentedButtonDefaults.itemShape(i, options.size),
                modifier = Modifier.heightIn(min = Dimens.button),
            ) { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

// ------------------------------------------------------------------ pages, hints, empty state

/** A page image: always on white (documents are never tinted) with a 1 dp outlineVariant edge. */
@Composable
fun PageThumbnail(file: File, key: Any, modifier: Modifier = Modifier, contentDescription: String? = null, radius: Int = 8) {
    val shape = RoundedCornerShape(radius.dp)
    Box(
        modifier
            .clip(shape)
            .background(Color.White)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape),
    ) {
        FileImage(file, key, 360, contentDescription, Modifier.matchParentSize(), ContentScale.Crop)
    }
}

/** A short instruction floating over content (inverseSurface). */
@Composable
fun HintPill(text: String, modifier: Modifier = Modifier) {
    Surface(color = MaterialTheme.colorScheme.inverseSurface, shape = CircleShape, modifier = modifier) {
        Text(
            text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.inverseOnSurface,
            modifier = Modifier.padding(horizontal = Dimens.l, vertical = Dimens.s),
        )
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, body: String, modifier: Modifier = Modifier, extra: @Composable ColumnScope.() -> Unit = {}) {
    Column(modifier.padding(Dimens.xxl).widthIn(max = 480.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, Modifier.size(120.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(Dimens.l))
        Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(Dimens.s))
        Text(body, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(Dimens.xl))
        extra()
    }
}

/** Marks a node for TalkBack with a label (used where a custom draw has no text). */
fun Modifier.described(label: String) = semantics { contentDescription = label }

// ------------------------------------------------------------------ numbers, dates, separators

/** Locale-aware formatting (CLDR digits: Arabic-Indic in ar, Western in ar-MA/ar-TN/ar-DZ and en). */
object LocalFormat {
    fun count(n: Int, locale: Locale = Locale.getDefault()): String = NumberFormat.getIntegerInstance(locale).format(n.toLong())

    /** Metadata separator: the Arabic comma in Arabic («٣ صفحات، اليوم»), a middle dot otherwise. */
    fun metaSep(locale: Locale = Locale.getDefault()): String = if (locale.language == "ar") "، " else " · "

    /** «اليوم ٦:٢٢ م» / «أمس» / «١٢ نوفمبر» / «١٢ نوفمبر ٢٠٢٥». */
    fun docDate(ctx: Context, millis: Long, now: Long = System.currentTimeMillis()): String {
        val locale = ctx.resources.configuration.locales[0] ?: Locale.getDefault()
        val then = Calendar.getInstance().apply { timeInMillis = millis }
        val today = Calendar.getInstance().apply { timeInMillis = now }
        val days = dayIndex(today) - dayIndex(then)
        return when {
            days == 0L -> ctx.getString(R.string.date_today, android.text.format.DateFormat.getTimeFormat(ctx).format(Date(millis)))
            days == 1L -> ctx.getString(R.string.date_yesterday)
            then.get(Calendar.YEAR) == today.get(Calendar.YEAR) ->
                java.text.SimpleDateFormat(android.text.format.DateFormat.getBestDateTimePattern(locale, "dMMMM"), locale).format(Date(millis))
            else -> java.text.SimpleDateFormat(android.text.format.DateFormat.getBestDateTimePattern(locale, "dMMMMy"), locale).format(Date(millis))
        }
    }

    /** File size in the locale's digits: «٧٨ ك.ب» / «١٫٢ م.ب» / "78 KB". */
    fun size(ctx: Context, bytes: Long): String {
        val locale = ctx.resources.configuration.locales[0] ?: Locale.getDefault()
        val nf = NumberFormat.getNumberInstance(locale)
        return if (bytes < 1_000_000L) {
            nf.maximumFractionDigits = 0
            ctx.getString(R.string.size_kb, nf.format(maxOf(1L, (bytes + 999) / 1000)))
        } else {
            nf.maximumFractionDigits = 1
            ctx.getString(R.string.size_mb, nf.format(bytes / 1_000_000.0))
        }
    }

    private fun dayIndex(c: Calendar): Long =
        c.get(Calendar.YEAR) * 400L + c.get(Calendar.DAY_OF_YEAR)

    /** Isolates a Latin run (document name, A4, AES-256) inside Arabic text. */
    fun isolate(s: String): String = "${0x2068.toChar()}$s${0x2069.toChar()}"
}
