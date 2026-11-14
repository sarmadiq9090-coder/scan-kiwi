// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import com.fastkiwi.scan.R

/**
 * Every icon the UI uses, in one family: Material Symbols Rounded (Apache-2.0),
 * weight 400, grade 0, 24 dp, as vector drawables `res/drawable/ic_sym_*.xml`
 * (design spec §2.4). Only back, undo, chevron and open-in-new mirror in RTL;
 * rotation is physical and never mirrors.
 */
object KiwiIcons {
    @Composable private fun v(id: Int): ImageVector = ImageVector.vectorResource(id)

    @Composable fun back() = v(R.drawable.ic_sym_arrow_back)
    @Composable fun close() = v(R.drawable.ic_sym_close)
    @Composable fun scan() = v(R.drawable.ic_sym_document_scanner)
    @Composable fun import() = v(R.drawable.ic_sym_upload_file)
    @Composable fun settings() = v(R.drawable.ic_sym_settings)
    @Composable fun search() = v(R.drawable.ic_sym_search)
    @Composable fun more() = v(R.drawable.ic_sym_more_vert)
    @Composable fun undo() = v(R.drawable.ic_sym_undo)
    @Composable fun chevron() = v(R.drawable.ic_sym_chevron_right)
    @Composable fun openInNew() = v(R.drawable.ic_sym_open_in_new)
    @Composable fun add() = v(R.drawable.ic_sym_add)
    @Composable fun camera() = v(R.drawable.ic_sym_photo_camera)
    @Composable fun gallery() = v(R.drawable.ic_sym_photo_library)
    @Composable fun idCard() = v(R.drawable.ic_sym_id_card)
    @Composable fun share() = v(R.drawable.ic_sym_share)
    @Composable fun download() = v(R.drawable.ic_sym_download)
    @Composable fun crop() = v(R.drawable.ic_sym_crop)
    @Composable fun rotateRight() = v(R.drawable.ic_sym_rotate_right)
    @Composable fun rotateLeft() = v(R.drawable.ic_sym_rotate_left)
    @Composable fun signature() = v(R.drawable.ic_sym_signature)
    @Composable fun retake() = v(R.drawable.ic_sym_replay)
    @Composable fun delete() = v(R.drawable.ic_sym_delete)
    @Composable fun cropFree() = v(R.drawable.ic_sym_crop_free)
    @Composable fun redetect() = v(R.drawable.ic_sym_auto_fix_high)
    @Composable fun autoCapture() = v(R.drawable.ic_sym_motion_photos_auto)
    @Composable fun flashOn() = v(R.drawable.ic_sym_flash_on)
    @Composable fun flashOff() = v(R.drawable.ic_sym_flash_off)
    @Composable fun ocr() = v(R.drawable.ic_sym_text_fields)
    @Composable fun pdfa() = v(R.drawable.ic_sym_inventory_2)
    @Composable fun lock() = v(R.drawable.ic_sym_lock)
    @Composable fun language() = v(R.drawable.ic_sym_language)
    @Composable fun theme() = v(R.drawable.ic_sym_contrast)
    @Composable fun palette() = v(R.drawable.ic_sym_palette)
    @Composable fun privacy() = v(R.drawable.ic_sym_verified_user)
    @Composable fun feedback() = v(R.drawable.ic_sym_feedback)
    @Composable fun update() = v(R.drawable.ic_sym_update)
    @Composable fun info() = v(R.drawable.ic_sym_info)
    @Composable fun mail() = v(R.drawable.ic_sym_mail)
    @Composable fun check() = v(R.drawable.ic_sym_check)
    @Composable fun checkCircle() = v(R.drawable.ic_sym_check_circle)
    @Composable fun merge() = v(R.drawable.ic_sym_merge)
    @Composable fun warning() = v(R.drawable.ic_sym_warning)
    @Composable fun visibility() = v(R.drawable.ic_sym_visibility)
    @Composable fun visibilityOff() = v(R.drawable.ic_sym_visibility_off)
    @Composable fun filter() = v(R.drawable.ic_sym_auto_awesome)
}
