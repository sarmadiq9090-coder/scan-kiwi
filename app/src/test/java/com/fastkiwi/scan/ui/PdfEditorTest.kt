// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import com.fastkiwi.scan.MainActivity
import com.fastkiwi.scan.core.Filter
import com.fastkiwi.scan.data.ScanDocument
import com.fastkiwi.scan.export.ExportSeed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The editor's 2.0 PDF features in the real app (Arabic): Redo, drag-and-drop reorder, blank
 * pages, rotate / delete / extract selected pages, split, the export sheet's new options and
 * the PDF tools entry.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "ar-w400dp-h860dp-xxhdpi")
class PdfEditorTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val vm get() = rule.activity.vm

    @Before
    fun clean() {
        vm.store.list().forEach { vm.store.delete(it.id) }
        rule.runOnUiThread { vm.refresh() }
        rule.waitUntil(10_000) { vm.docs.value.isEmpty() }
    }

    private fun seed(n: Int = 4): ScanDocument {
        val d = ExportSeed.doc(vm.store, "مستند تجريبي", n, seed = 3, w = 600, h = 849, mixBw = false)
        rule.runOnUiThread { vm.refresh() }
        rule.waitUntil(10_000) { vm.docs.value.any { it.doc.id == d.id } }
        return d
    }

    private fun pages(id: String) = vm.store.load(id)!!.pages.map { it.id }

    @Test
    fun redoReappliesWhatUndoTookBack() {
        val doc = seed()
        val ids = doc.pages.map { it.id }
        rule.runOnUiThread { vm.deletePage(doc.id, 0) }
        rule.waitUntil(10_000) { pages(doc.id) == ids.drop(1) }
        rule.runOnUiThread { vm.editPage(doc.id, ids[1]) { it.copy(filter = Filter.GRAYSCALE) } }
        rule.waitUntil(10_000) { vm.store.load(doc.id)!!.pages[0].filter == Filter.GRAYSCALE }
        rule.runOnUiThread { vm.undo(doc.id) }
        rule.waitUntil(10_000) { vm.store.load(doc.id)!!.pages[0].filter == Filter.ENHANCED }
        rule.runOnUiThread { vm.undo(doc.id) }
        rule.waitUntil(10_000) { pages(doc.id) == ids }
        rule.waitUntil(5_000) { vm.redoCounts[doc.id] == 2 }
        rule.runOnUiThread { vm.redo(doc.id) }
        rule.waitUntil(10_000) { pages(doc.id) == ids.drop(1) }
        rule.runOnUiThread { vm.redo(doc.id) }
        rule.waitUntil(10_000) { vm.store.load(doc.id)!!.pages[0].filter == Filter.GRAYSCALE }
        // A new edit after Undo clears Redo.
        rule.runOnUiThread { vm.undo(doc.id) }
        rule.waitUntil(10_000) { vm.store.load(doc.id)!!.pages[0].filter == Filter.ENHANCED }
        rule.runOnUiThread { vm.movePage(doc.id, 0, 2) }
        rule.waitUntil(5_000) { vm.redoCounts[doc.id] == 0 }
    }

    @Test
    fun redoButtonFollowsTheHistory() {
        val doc = seed(2)
        rule.runOnUiThread { vm.open(Screen.Editor(doc.id)) }
        rule.waitForIdle()
        rule.onNodeWithContentDescription("إعادة").assertIsNotEnabled()
        rule.runOnUiThread { vm.movePage(doc.id, 0, 1) }
        rule.waitUntil(5_000) { (vm.undoCounts[doc.id] ?: 0) == 1 }
        rule.onNodeWithContentDescription("تراجع").performClick()
        rule.waitUntil(5_000) { (vm.redoCounts[doc.id] ?: 0) == 1 }
        rule.waitForIdle()
        rule.onNodeWithContentDescription("إعادة").assertIsEnabled().performClick()
        rule.waitUntil(10_000) { pages(doc.id) == listOf(doc.pages[1].id, doc.pages[0].id) }
    }

    @Test
    fun dragAPageOntoAnotherReordersIt() {
        val doc = seed(4)
        rule.runOnUiThread { vm.open(Screen.Editor(doc.id)) }
        rule.waitForIdle()
        val first = rule.onAllNodesWithContentDescription("الصفحة ١ من ٤").fetchSemanticsNodes().firstOrNull()
            ?: rule.onAllNodesWithContentDescription("الصفحة 1 من 4").fetchSemanticsNodes().first()
        val third = rule.onAllNodesWithContentDescription("الصفحة ٣ من ٤").fetchSemanticsNodes().firstOrNull()
            ?: rule.onAllNodesWithContentDescription("الصفحة 3 من 4").fetchSemanticsNodes().first()
        val from = first.boundsInRoot.center
        val to = third.boundsInRoot.center
        rule.onNode(androidx.compose.ui.test.hasContentDescription(first.config[androidx.compose.ui.semantics.SemanticsProperties.ContentDescription].first()))
            .performTouchInput {
                val start = center
                down(start)
                advanceEventTime(1_000) // long press starts the drag
                val delta = Offset(to.x - from.x, to.y - from.y)
                for (i in 1..20) moveTo(start + delta * (i / 20f))
                up()
            }
        rule.waitUntil(10_000) { pages(doc.id).indexOf(doc.pages[0].id) == 2 }
        assertEquals(listOf(1, 2, 0, 3).map { doc.pages[it].id }, pages(doc.id))
    }

    @Test
    fun blankRotateDeleteExtractAndSplit() {
        val doc = seed(4)
        val ids = doc.pages.map { it.id }
        rule.runOnUiThread { vm.insertBlankPage(doc.id, 1) }
        rule.waitUntil(10_000) { pages(doc.id).size == 5 }
        val blank = pages(doc.id)[1]
        assertTrue(blank !in ids)
        assertTrue(vm.store.outFile(doc.id, blank).exists())
        rule.runOnUiThread { vm.rotatePages(doc.id, setOf(ids[0], ids[2]), 90) }
        rule.waitUntil(10_000) { vm.store.load(doc.id)!!.pages.count { it.rotation == 90 } == 2 }
        rule.runOnUiThread { vm.deletePages(doc.id, setOf(blank, ids[3])) }
        rule.waitUntil(10_000) { pages(doc.id) == ids.take(3) }
        // One Undo brings both deleted pages back.
        rule.waitUntil(5_000) { (vm.undoCounts[doc.id] ?: 0) == 3 }
        rule.runOnUiThread { vm.undo(doc.id) }
        rule.waitUntil(10_000) { pages(doc.id).size == 5 }
        val before0 = vm.store.list().size
        rule.runOnUiThread { vm.extractPages(doc.id, listOf(2, 0)) {} }
        // The new document is on disk before onDone runs on the main thread.
        rule.waitUntil(10_000) { vm.store.list().size == before0 + 1 }
        val newId = vm.store.list().first { it.id != doc.id }.id
        val extracted = vm.store.load(newId)!!
        assertEquals(2, extracted.pages.size)
        assertEquals(5, pages(doc.id).size) // the original is kept
        val known = vm.store.list().map { it.id }.toSet()
        rule.runOnUiThread { vm.splitDocument(doc.id, listOf(listOf(0, 1), listOf(2, 3, 4))) }
        rule.waitUntil(10_000) { vm.store.list().size == known.size + 2 }
        assertEquals(listOf(2, 3), vm.store.list().filter { it.id !in known }.map { it.pages.size }.sorted())
        assertEquals(5, pages(doc.id).size)
    }

    @Test
    fun exportSheetShowsTheNewOptionsAndHomeHasPdfTools() {
        val doc = seed(2)
        rule.waitForIdle()
        rule.onNodeWithContentDescription("فتح أدوات PDF").assertIsEnabled()
        rule.runOnUiThread { vm.open(Screen.Editor(doc.id)) }
        rule.waitForIdle()
        rule.onNodeWithText("تصدير").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("حجم محدد").assertIsEnabled()
        rule.onNodeWithText("معاينة الملف").assertIsEnabled()
        rule.onNodeWithText("ترقيم الصفحات").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithText("موضع الرقم").assertIsEnabled()
        rule.onNodeWithText("علامة مائية").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithText("نص العلامة").assertIsEnabled()
        rule.onNodeWithText("حجم محدد").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("الحد الأقصى للحجم").assertIsEnabled()
        rule.waitUntil(10_000) { rule.onAllNodesWithContentDescription("معاينة الصفحة ١").fetchSemanticsNodes().isNotEmpty() || rule.onAllNodesWithContentDescription("معاينة الصفحة 1").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun pdfToolsScreenOpens() {
        rule.runOnUiThread { vm.open(Screen.PdfTools) }
        rule.waitForIdle()
        rule.onNodeWithText("أدوات لملفات PDF").assertIsEnabled()
        rule.onAllNodesWithText("اختيار ملفات PDF").fetchSemanticsNodes().let { assertTrue(it.isNotEmpty()) }
        assertTouchTargets("pdf tools")
    }

    @Test
    fun selectionModeTargetsAreBigEnough() {
        val doc = seed(3)
        rule.runOnUiThread { vm.open(Screen.Editor(doc.id)) }
        rule.waitForIdle()
        assertTouchTargets("editor")
        rule.onNodeWithContentDescription("خيارات أخرى").performClick()
        rule.onNodeWithText("تحديد صفحات").performClick()
        rule.waitForIdle()
        rule.onAllNodesWithContentDescription("الصفحة ١ من ٣").fetchSemanticsNodes().ifEmpty { null }
            ?.let { rule.onAllNodesWithContentDescription("الصفحة ١ من ٣")[0].performClick() }
            ?: rule.onAllNodesWithContentDescription("الصفحة 1 من 3")[0].performClick()
        rule.waitForIdle()
        rule.onNodeWithText("صفحة واحدة محددة").assertIsEnabled()
        assertTouchTargets("editor selection")
    }

    private fun assertTouchTargets(screen: String) {
        val density = rule.activity.resources.displayMetrics.density
        val min = 48f * density - 1f
        val nodes = rule.onAllNodes(androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsActions.OnClick), useUnmergedTree = true).fetchSemanticsNodes()
        val small = nodes.filter { n -> n.boundsInRoot.width > 0f && n.boundsInRoot.height > 0f && (n.boundsInRoot.width < min || n.boundsInRoot.height < min) }
            .map { n -> "${n.config.toString().take(80)} ${n.boundsInRoot.width / density}x${n.boundsInRoot.height / density} dp" }
        assertTrue("$screen: touch targets under 48 dp:\n" + small.joinToString("\n"), small.isEmpty())
    }
}
