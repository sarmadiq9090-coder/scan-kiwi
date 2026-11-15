// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.ui

import android.graphics.Bitmap
import androidx.annotation.VisibleForTesting

/**
 * 2.0-gates: test seam for the camera. Robolectric (and an emulator without a camera)
 * cannot run CameraX, so the shutter, the live outline and auto-capture could not be
 * exercised end to end. When [camera] is set, CameraScreen takes its preview frames and
 * stills from it instead of CameraX, and everything after that (detection, the
 * auto-capture gate, status texts, addCapture/afterCapture, review) is the real code.
 * Always null in the app.
 */
object CaptureSeam {
    interface FakeCamera {
        /** The next preview frame (upright), or null when nothing new is in view. */
        fun previewFrame(): Bitmap?

        /** A still: JPEG bytes and the rotation in degrees, or null for a failed shot. */
        fun takePicture(): Pair<ByteArray, Int>?
    }

    @VisibleForTesting
    @Volatile
    var camera: FakeCamera? = null
}
