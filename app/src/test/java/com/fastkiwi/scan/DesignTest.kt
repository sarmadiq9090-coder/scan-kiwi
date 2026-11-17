// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.fastkiwi.scan.data.ScanDocument
import com.fastkiwi.scan.ui.Screen
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Shared steps for the design checks (spec step 15). */
private fun seed(rule: androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, MainActivity>): ScanDocument {
    val vm = rule.activity.vm
    val doc = vm.store.save(ExportPerformanceTest().seedPages(vm, 3).copy(name = "عقد الإيجار"))
    rule.runOnUiThread { vm.refresh() }
    rule.waitUntil(10_000) { vm.docs.value.any { it.doc.id == doc.id } }
    return doc
}

private fun shot(rule: androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, MainActivity>, name: String) {
    rule.waitForIdle(); Thread.sleep(800); rule.waitForIdle()
    File("../screenshots").mkdirs()
    saveWindows(File("../screenshots/$name.png"))
}

/** Walks every clickable node of the current screen and checks the 48 dp minimum. */
private fun assertTouchTargets(rule: androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, MainActivity>, screen: String) {
    val density = rule.activity.resources.displayMetrics.density
    val min = 48f * density - 1f
    val nodes = rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick), useUnmergedTree = true).fetchSemanticsNodes()
    // The node's own (unclipped) size: a row cut by the screen edge or the top bar is not a small target.
    val small = nodes.filter { n -> visible(n) && (n.size.width < min || n.size.height < min) }
        .map { n -> "${n.config.toString().take(80)} ${n.size.width / density}x${n.size.height / density} dp" }
    assertTrue("$screen: touch targets under 48 dp:\n" + small.joinToString("\n"), small.isEmpty())
}

private fun visible(n: SemanticsNode): Boolean = n.boundsInRoot.width > 0f && n.boundsInRoot.height > 0f

private fun boundsOf(rule: androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, MainActivity>, text: String): Rect =
    rule.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "ar-w400dp-h860dp-xxhdpi")
class DesignArabicTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun primaryIsAtTheEndInRtlAndTargetsAreBigEnough() {
        val doc = seed(rule)
        assertTouchTargets(rule, "Home")
        rule.runOnUiThread { rule.activity.vm.open(Screen.Editor(doc.id)) }
        rule.waitForIdle()
        // RTL: the primary «تصدير» sits to the LEFT of «إضافة صفحات».
        assertTrue(boundsOf(rule, "تصدير").center.x < boundsOf(rule, "إضافة صفحات").center.x)
        assertTouchTargets(rule, "Editor")
        rule.runOnUiThread { rule.activity.vm.open(Screen.Settings) }
        rule.waitForIdle()
        assertTouchTargets(rule, "Settings")
        rule.runOnUiThread { rule.activity.vm.open(Screen.Updates) }
        rule.waitForIdle()
        assertTouchTargets(rule, "Updates")
        rule.runOnUiThread { rule.activity.vm.open(Screen.Feedback()) }
        rule.waitForIdle()
        assertTouchTargets(rule, "Feedback")
        shot(rule, "10-feedback-ar")
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w400dp-h860dp-xxhdpi")
class DesignEnglishTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun primaryIsAtTheEndInLtr() {
        val doc = seed(rule)
        rule.runOnUiThread { rule.activity.vm.open(Screen.Editor(doc.id)) }
        rule.waitForIdle()
        // LTR mirrors it: "Export" is to the RIGHT of "Add pages".
        assertTrue(boundsOf(rule, "Export").center.x > boundsOf(rule, "Add pages").center.x)
        shot(rule, "11-document-en")
    }
}

/** Dark theme screenshots of the main screens. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "ar-w400dp-h860dp-night-xxhdpi")
class DesignDarkTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun darkScreens() {
        val doc = seed(rule)
        shot(rule, "20-home-dark-ar")
        rule.runOnUiThread { rule.activity.vm.open(Screen.Editor(doc.id)) }
        shot(rule, "21-document-dark-ar")
        rule.onNodeWithText("تصدير").performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithText("حماية بكلمة مرور").fetchSemanticsNodes().isNotEmpty() }
        shot(rule, "22-export-dark-ar")
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.runOnUiThread { while (rule.activity.vm.back()) Unit; rule.activity.vm.open(Screen.Settings) }
        shot(rule, "23-settings-dark-ar")
    }
}

/** 200 % text: primary actions stay visible and inside the window (WCAG 1.4.4). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "ar-w400dp-h860dp-xxhdpi", fontScale = 2.0f)
class DesignLargeTextTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private fun assertInside(text: String) {
        val root = rule.onRoot().fetchSemanticsNode().boundsInRoot
        val b = rule.onNodeWithText(text, useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("$text outside the window: $b in $root", b.left >= root.left - 1 && b.right <= root.right + 1 && b.bottom <= root.bottom + 1)
    }

    @Test
    fun largeTextKeepsPrimaryActionsVisible() {
        val doc = seed(rule)
        assertInside("مسح")
        shot(rule, "30-home-200pct-ar")
        rule.runOnUiThread { rule.activity.vm.open(Screen.Editor(doc.id)) }
        rule.waitForIdle()
        assertInside("تصدير")
        shot(rule, "31-document-200pct-ar")
        rule.onNodeWithText("تصدير").performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithText("حماية بكلمة مرور").fetchSemanticsNodes().isNotEmpty() }
        shot(rule, "32-export-200pct-ar")
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.runOnUiThread { while (rule.activity.vm.back()) Unit; rule.activity.vm.open(Screen.Settings) }
        rule.waitForIdle()
        assertInside("الإعدادات")
        shot(rule, "33-settings-200pct-ar")
    }
}
