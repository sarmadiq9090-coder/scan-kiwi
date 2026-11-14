// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/** Shape tokens (design spec §2.3). */
val KiwiShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** Grouped settings rows: 20 dp outer corners, 4 dp inner corners, 2 dp gaps. */
object GroupShapes {
    val Outer = 20.dp
    val Inner = 4.dp
    val Gap = 2.dp

    fun forRow(index: Int, count: Int): RoundedCornerShape {
        val top = if (index == 0) Outer else Inner
        val bottom = if (index == count - 1) Outer else Inner
        return RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)
    }
}
