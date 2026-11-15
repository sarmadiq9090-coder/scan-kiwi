// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.ui

import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.window.Dialog
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.TimeUnit

/**
 * BLACKBOX-2.0 #1: on a phone wider than 320 dp, a text field in a dialog with the platform's default
 * width was measured at 320 dp and at the full width on every layout pass and asked for a new layout
 * each time, so the window never settled (the driver's AppNotIdleException on «مجلد جديد»).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "ar-w400dp-h860dp-xxhdpi")
class TextFieldDialogIdleTest {
    @get:Rule
    val rule = createComposeRule()

    @Before
    fun shortIdleTimeout() {
        // Espresso is only on the runtime classpath here.
        Class.forName("androidx.test.espresso.IdlingPolicies")
            .getMethod("setMasterPolicyTimeout", Long::class.javaPrimitiveType, TimeUnit::class.java)
            .invoke(null, 5L, TimeUnit.SECONDS)
    }

    private fun settles(content: @androidx.compose.runtime.Composable () -> Unit): Boolean {
        rule.mainClock.autoAdvance = false
        return runCatching { rule.setContent(content); rule.waitForIdle() }.isSuccess
    }

    @Test
    fun theRenameDialogSettles() {
        assertTrue(settles { RenameDialog("مسح 2026-11-15 11.53", onDismiss = {}, onRename = {}) })
    }

    /** The cause, kept as a check that the workaround is still needed (remove TextFieldDialogProperties when this fails). */
    @Test
    fun aTextFieldInAPlatformWidthDialogNeverSettles() {
        assertFalse(settles { Dialog(onDismissRequest = {}) { BasicTextField("", {}) } })
    }
}
