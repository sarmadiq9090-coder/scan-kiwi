// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.update

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import com.fastkiwi.scan.MainActivity
import com.fastkiwi.scan.ui.Screen
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 2.0-update on the real app UI (Arabic): the one-time notice with the owner's wording, the Updates
 * screen's check button, its two settings (both on by default) and the statement. Under Robolectric the
 * app's updater always sees "offline", so tapping the button never reaches GitHub.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "ar-w400dp-h860dp-xxhdpi")
class UpdateUiTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    companion object {
        private const val NOTICE = "يتصل ماسح كيوي بالإنترنت فقط للتحقق من التحديثات وتنزيلها من GitHub. لا يُرسل أي ملف أو صورة أو بيانات عنك. يمكنك إيقافه من الإعدادات."

        @JvmStatic
        @BeforeClass
        fun showNotice() { UpdateController.showNoticeInTests = true }

        @JvmStatic
        @AfterClass
        fun hideNotice() { UpdateController.showNoticeInTests = false }
    }

    @Test
    fun noticeOnceThenUpdatesScreenWithItsSettings() {
        val vm = rule.activity.vm
        // First start: the notice, word for word, before anything else is checked.
        rule.waitUntil(5_000) { rule.onAllNodesWithText(NOTICE).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(0L, vm.settings.lastUpdateCheck)
        rule.onNodeWithText("الإعدادات").assertHeightIsAtLeast(48.dp).performClick()
        rule.waitForIdle()
        assertTrue(vm.settings.updateNoticeShown)
        assertFalse(vm.updates.noticePending.value)
        assertEquals(Screen.Updates, vm.screen)
        // The Updates screen: check button, both switches on by default, the statement.
        rule.onNodeWithText("تحقق من التحديثات").performScrollTo().assertHeightIsAtLeast(48.dp)
        rule.onNodeWithText("التحقق التلقائي من التحديثات").performScrollTo().assertIsOn()
        rule.onNodeWithText("التنزيل عبر Wi-Fi فقط").performScrollTo().assertIsOn()
        assertTrue(vm.settings.autoUpdateCheck && vm.settings.updateWifiOnly)
        rule.onNodeWithText(NOTICE).performScrollTo()
        // The switches change the settings.
        rule.onNodeWithText("التنزيل عبر Wi-Fi فقط").performClick()
        rule.waitForIdle()
        assertFalse(vm.settings.updateWifiOnly)
        rule.onNodeWithText("التحقق التلقائي من التحديثات").performClick()
        rule.waitForIdle()
        assertFalse(vm.settings.autoUpdateCheck)
        // The check button with no connection: a clear message, no crash, nothing downloaded.
        rule.onNodeWithText("تحقق من التحديثات").performScrollTo().performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithText("لا يوجد اتصال بالإنترنت.", substring = true).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(UpdateState.Failed(UpdateError.OFFLINE), vm.updates.state.value)
        // Not shown again at the next start.
        assertFalse(UpdateController(rule.activity, vm.settings, kotlinx.coroutines.MainScope()).noticePending.value)
    }
}
