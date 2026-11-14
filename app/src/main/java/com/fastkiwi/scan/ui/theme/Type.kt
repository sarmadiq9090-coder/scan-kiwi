// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp
import com.fastkiwi.scan.R

/** Noto Sans Arabic (SIL OFL 1.1), bundled so every phone shows the same UI face. */
val KiwiFont = FontFamily(
    Font(R.font.noto_sans_arabic_regular, FontWeight.Normal),
    Font(R.font.noto_sans_arabic_medium, FontWeight.Medium),
    Font(R.font.noto_sans_arabic_bold, FontWeight.Bold),
)

/**
 * Arabic-tuned type scale (design spec §2.2): letter spacing 0 (it breaks
 * joining), generous line heights, and no trimming so descender dots never clip.
 */
private fun style(size: Int, line: Int, weight: FontWeight) = TextStyle(
    fontFamily = KiwiFont,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = 0.sp,
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
)

val KiwiTypography = Typography(
    displayLarge = style(57, 72, FontWeight.Normal),
    displayMedium = style(45, 56, FontWeight.Normal),
    displaySmall = style(36, 48, FontWeight.Normal),
    headlineLarge = style(32, 44, FontWeight.Bold),
    headlineMedium = style(28, 40, FontWeight.Bold),
    headlineSmall = style(24, 36, FontWeight.Bold),
    titleLarge = style(22, 32, FontWeight.Bold),
    titleMedium = style(17, 26, FontWeight.Medium),
    titleSmall = style(15, 22, FontWeight.Bold),
    bodyLarge = style(16, 28, FontWeight.Normal),
    bodyMedium = style(14, 24, FontWeight.Normal),
    bodySmall = style(13, 20, FontWeight.Normal),
    labelLarge = style(15, 22, FontWeight.Medium),
    labelMedium = style(13, 18, FontWeight.Medium),
    labelSmall = style(12, 16, FontWeight.Medium),
)
