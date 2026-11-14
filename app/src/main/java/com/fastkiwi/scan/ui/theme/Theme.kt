// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.platform.LocalContext

/** «السمة»: follow the system (default), always light or always dark. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** True when the user turned animations off (Developer options or accessibility). */
val LocalReducedMotion = compositionLocalOf { false }

/**
 * Brand theme. [dynamic] (Material You wallpaper colours) is opt-in and only on
 * Android 12+; the camera route always uses [DarkColors] whatever is chosen here.
 */
@Composable
fun KiwiTheme(mode: ThemeMode = ThemeMode.SYSTEM, dynamic: Boolean = false, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val scheme = if (dynamic && Build.VERSION.SDK_INT >= 31) {
        val ctx = LocalContext.current
        if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    } else if (dark) DarkColors else LightColors
    MaterialTheme(colorScheme = scheme, typography = KiwiTypography, shapes = KiwiShapes, content = content)
}

/** The always-dark camera surface. */
@Composable
fun CameraTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkColors, typography = KiwiTypography, shapes = KiwiShapes, content = content)
}
