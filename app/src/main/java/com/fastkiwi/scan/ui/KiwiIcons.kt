// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Feedback
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MergeType
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Rotate90DegreesCcw
import androidx.compose.material.icons.filled.Rotate90DegreesCw
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Update
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.MotionPhotosAuto
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * One place for every icon the UI uses (design spec §2.4), so the icon family can
 * be swapped in one file. RTL mirroring follows the spec table.
 */
object KiwiIcons {
    @Composable fun back(): ImageVector = Icons.AutoMirrored.Filled.ArrowBack
    @Composable fun close(): ImageVector = Icons.Filled.Close
    @Composable fun scan(): ImageVector = Icons.Filled.DocumentScanner
    @Composable fun import(): ImageVector = Icons.Filled.UploadFile
    @Composable fun settings(): ImageVector = Icons.Filled.Settings
    @Composable fun search(): ImageVector = Icons.Filled.Search
    @Composable fun more(): ImageVector = Icons.Filled.MoreVert
    @Composable fun undo(): ImageVector = Icons.AutoMirrored.Filled.Undo
    @Composable fun chevron(): ImageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight
    @Composable fun openInNew(): ImageVector = Icons.AutoMirrored.Filled.OpenInNew
    @Composable fun add(): ImageVector = Icons.Filled.Add
    @Composable fun camera(): ImageVector = Icons.Filled.PhotoCamera
    @Composable fun gallery(): ImageVector = Icons.Filled.PhotoLibrary
    @Composable fun idCard(): ImageVector = Icons.Filled.Badge
    @Composable fun share(): ImageVector = Icons.Filled.Share
    @Composable fun download(): ImageVector = Icons.Filled.Download
    @Composable fun crop(): ImageVector = Icons.Filled.Crop
    @Composable fun rotateRight(): ImageVector = Icons.Filled.Rotate90DegreesCw
    @Composable fun rotateLeft(): ImageVector = Icons.Filled.Rotate90DegreesCcw
    @Composable fun signature(): ImageVector = Icons.Filled.Draw
    @Composable fun retake(): ImageVector = Icons.Filled.Replay
    @Composable fun delete(): ImageVector = Icons.Filled.Delete
    @Composable fun cropFree(): ImageVector = Icons.Filled.CropFree
    @Composable fun redetect(): ImageVector = Icons.Filled.AutoFixHigh
    @Composable fun autoCapture(): ImageVector = Icons.Filled.MotionPhotosAuto
    @Composable fun flashOn(): ImageVector = Icons.Filled.FlashOn
    @Composable fun flashOff(): ImageVector = Icons.Filled.FlashOff
    @Composable fun ocr(): ImageVector = Icons.Filled.TextFields
    @Composable fun pdfa(): ImageVector = Icons.Filled.Inventory2
    @Composable fun lock(): ImageVector = Icons.Filled.Lock
    @Composable fun language(): ImageVector = Icons.Filled.Language
    @Composable fun theme(): ImageVector = Icons.Filled.Contrast
    @Composable fun palette(): ImageVector = Icons.Filled.Palette
    @Composable fun privacy(): ImageVector = Icons.Filled.VerifiedUser
    @Composable fun feedback(): ImageVector = Icons.Filled.Feedback
    @Composable fun update(): ImageVector = Icons.Filled.Update
    @Composable fun info(): ImageVector = Icons.Filled.Info
    @Composable fun mail(): ImageVector = Icons.Filled.Email
    @Composable fun check(): ImageVector = Icons.Filled.Check
    @Composable fun checkCircle(): ImageVector = Icons.Filled.CheckCircle
    @Composable fun merge(): ImageVector = Icons.Filled.MergeType
    @Composable fun warning(): ImageVector = Icons.Filled.Warning
    @Composable fun visibility(): ImageVector = Icons.Filled.Visibility
    @Composable fun visibilityOff(): ImageVector = Icons.Filled.VisibilityOff
    @Composable fun filter(): ImageVector = Icons.Filled.AutoAwesome
}
