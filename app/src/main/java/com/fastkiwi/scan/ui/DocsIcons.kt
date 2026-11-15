// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import com.fastkiwi.scan.R

/**
 * Icons of the 2.0 document manager (Home, folders, favorites, search, text), same family as
 * [KiwiIcons]: Material Symbols Rounded, weight 400, fill 1 only for the active star. Kept in a
 * separate object so the Documents work does not edit KiwiIcons. Sort and "move to folder" mirror in RTL.
 */
object DocsIcons {
    @Composable private fun v(id: Int): ImageVector = ImageVector.vectorResource(id)

    @Composable fun star() = v(R.drawable.ic_sym_star)
    @Composable fun starFilled() = v(R.drawable.ic_sym_star_fill)
    @Composable fun folder() = v(R.drawable.ic_sym_folder)
    @Composable fun newFolder() = v(R.drawable.ic_sym_create_new_folder)
    @Composable fun sort() = v(R.drawable.ic_sym_sort)
    @Composable fun copy() = v(R.drawable.ic_sym_content_copy)
    @Composable fun edit() = v(R.drawable.ic_sym_edit)
    @Composable fun moveToFolder() = v(R.drawable.ic_sym_drive_file_move)
}
