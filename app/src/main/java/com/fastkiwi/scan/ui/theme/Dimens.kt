// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.ui.theme

import androidx.compose.ui.unit.dp

/** 8 dp grid with a 4 dp half step (design spec §2.3). */
object Dimens {
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 24.dp
    val xxl = 32.dp

    /** Screen side margin and bottom-sheet content margin. */
    val screen = 16.dp
    val sheet = 24.dp

    /** Content padding under a FAB: 56 + 16 + 16. */
    val underFab = 88.dp

    val button = 48.dp
    val buttonLarge = 56.dp
    val minTouch = 48.dp
}
