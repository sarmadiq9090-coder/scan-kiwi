// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Kiwi-green Material 3 tonal palette. Text/background pairs meet WCAG 2.2 AA (>= 4.5:1).
private val Light = lightColorScheme(
    primary = Color(0xFF356A1E),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFB6F293),
    onPrimaryContainer = Color(0xFF082100),
    secondary = Color(0xFF55624C),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD9E7CB),
    onSecondaryContainer = Color(0xFF131F0D),
    tertiary = Color(0xFF386667),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFBBEBEC),
    onTertiaryContainer = Color(0xFF002021),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    background = Color(0xFFF9FAEF),
    onBackground = Color(0xFF1A1C16),
    surface = Color(0xFFF9FAEF),
    onSurface = Color(0xFF1A1C16),
    surfaceVariant = Color(0xFFDFE4D6),
    onSurfaceVariant = Color(0xFF43483E),
    outline = Color(0xFF73796D),
    surfaceContainer = Color(0xFFEDEFE3),
    surfaceContainerHigh = Color(0xFFE7E9DE),
    surfaceContainerHighest = Color(0xFFE2E3D8),
    surfaceContainerLow = Color(0xFFF3F5E9),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF9BD67A),
    onPrimary = Color(0xFF113800),
    primaryContainer = Color(0xFF1E5107),
    onPrimaryContainer = Color(0xFFB6F293),
    secondary = Color(0xFFBDCBB0),
    onSecondary = Color(0xFF283420),
    secondaryContainer = Color(0xFF3E4A35),
    onSecondaryContainer = Color(0xFFD9E7CB),
    tertiary = Color(0xFFA0CFD0),
    onTertiary = Color(0xFF003738),
    tertiaryContainer = Color(0xFF1E4E4F),
    onTertiaryContainer = Color(0xFFBBEBEC),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    background = Color(0xFF12140E),
    onBackground = Color(0xFFE2E3D8),
    surface = Color(0xFF12140E),
    onSurface = Color(0xFFE2E3D8),
    surfaceVariant = Color(0xFF43483E),
    onSurfaceVariant = Color(0xFFC3C8BB),
    outline = Color(0xFF8D9286),
    surfaceContainer = Color(0xFF1E201A),
    surfaceContainerHigh = Color(0xFF282B24),
    surfaceContainerHighest = Color(0xFF33362E),
    surfaceContainerLow = Color(0xFF1A1C16),
)

@Composable
fun KiwiTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
