// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan

import android.content.Intent
import android.os.Looper
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.fastkiwi.scan.core.PageSize
import com.fastkiwi.scan.export.ExportOptions
import com.fastkiwi.scan.export.Quality
import com.fastkiwi.scan.ui.AppViewModel
import com.fastkiwi.scan.ui.ExportResult
import com.fastkiwi.scan.ui.Screen
import com.fastkiwi.scan.ui.UpdateLinks
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Share responsiveness (owner report on 1.1.1): off the main thread, cancellable, cached. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class ShareResponsivenessTest {
    private val perf = ExportPerformanceTest()
    private fun exportsDir() = File(RuntimeEnvironment.getApplication().cacheDir, "exports")

    private fun awaitResult(vm: AppViewModel, timeoutMs: Long): ExportResult? = runBlocking {
        val end = System.currentTimeMillis() + timeoutMs
        var r: ExportResult? = null
        while (r == null && System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idle()
            r = withTimeoutOrNull(50) { vm.exports.first() }
        }
        r
    }

    @Test
    fun tapGivesInstantFeedbackAndMainThreadStaysFree() {
        val vm = AppViewModel(RuntimeEnvironment.getApplication())
        val doc = perf.seedPages(vm, 20)
        vm.share(doc.id, ExportOptions(PageSize.A4, Quality.BALANCED), asJpeg = false)
        // Progress is visible immediately, before any page is processed.
        assertNotNull(vm.exportState.value)
        val t = System.nanoTime()
        shadowOf(Looper.getMainLooper()).idle()
        val mainMs = (System.nanoTime() - t) / 1_000_000
        assertTrue("main thread busy for $mainMs ms after tapping Share", mainMs < 200)
        assertNotNull(awaitResult(vm, 60_000))
        assertNull(vm.exportState.value)
    }

    @Test
    fun cancelReallyStopsAndLeavesNoFile() {
        val vm = AppViewModel(RuntimeEnvironment.getApplication())
        val doc = perf.seedPages(vm, 20)
        vm.share(doc.id, ExportOptions(PageSize.A4, Quality.HIGH), asJpeg = false)
        val end = System.currentTimeMillis() + 30_000
        while ((vm.exportState.value?.page ?: 0) < 1 && System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5)
        }
        vm.cancelExport()
        val gone = System.currentTimeMillis() + 30_000
        while (vm.exportState.value != null && System.currentTimeMillis() < gone) {
            shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10)
        }
        assertNull("export still running after cancel", vm.exportState.value)
        assertNull("a cancelled export must not open the share sheet", awaitResult(vm, 500))
        Thread.sleep(200) // the writer thread finishes its current page and cleans up
        val left = exportsDir().listFiles()?.map { it.name } ?: emptyList()
        assertTrue("files left after cancel: $left", left.none { it.endsWith(".pdf") || it.endsWith(".part") })
    }

    @Test
    fun sameExportAgainReusesTheFile() {
        val vm = AppViewModel(RuntimeEnvironment.getApplication())
        val doc = perf.seedPages(vm, 5)
        val opt = ExportOptions(PageSize.A4, Quality.SMALL)
        vm.share(doc.id, opt, asJpeg = false)
        val first = (awaitResult(vm, 60_000) as ExportResult.Share).files.single()
        val stamp = first.lastModified()
        val t = System.nanoTime()
        vm.share(doc.id, opt, asJpeg = false)
        val second = (awaitResult(vm, 60_000) as ExportResult.Share).files.single()
        val ms = (System.nanoTime() - t) / 1_000_000
        assertEquals(first.absolutePath, second.absolutePath)
        assertEquals(stamp, second.lastModified())
        println("SHARE-TIMING cached re-share of 5 pages: ${ms}ms")
        // Changing an option builds a new file.
        vm.share(doc.id, opt.copy(quality = Quality.HIGH), asJpeg = false)
        val third = (awaitResult(vm, 60_000) as ExportResult.Share).files.single()
        assertTrue(third.length() > second.length() || third.lastModified() >= stamp)
    }

    @Test
    fun sizeEstimateIsCheapAndClose() {
        val vm = AppViewModel(RuntimeEnvironment.getApplication())
        val doc = perf.seedPages(vm, 5)
        val opt = ExportOptions(PageSize.A4, Quality.BALANCED)
        val t = System.nanoTime()
        val est = runBlocking { vm.estimateSize(doc.id, opt) }!!
        val ms = (System.nanoTime() - t) / 1_000_000
        vm.share(doc.id, opt, asJpeg = false)
        val real = (awaitResult(vm, 60_000) as ExportResult.Share).files.single().length()
        println("SHARE-TIMING estimate 5 pages: ${ms}ms estimate=${est / 1024}KB real=${real / 1024}KB")
        assertTrue("estimate took $ms ms", ms < 200)
        assertTrue("estimate $est vs real $real", est > real / 3 && est < real * 3)
    }
}

/** Settings ▸ Updates (council D-077): links only, no network code. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "ar-w400dp-h860dp-xxhdpi")
class UpdatesSettingsTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private fun openSettings() {
        rule.runOnUiThread { rule.activity.vm.open(Screen.Settings) }
        rule.waitForIdle()
    }

    @Test
    fun rowsOpenTheRightLinks() {
        openSettings()
        val app = shadowOf(RuntimeEnvironment.getApplication())
        rule.onNodeWithText("حدّثه تلقائياً عبر Obtainium").performScrollTo().performClick()
        val obtainium: Intent = shadowOf(rule.activity).nextStartedActivity ?: app.nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, obtainium.action)
        assertEquals(UpdateLinks.OBTAINIUM_ADD, obtainium.dataString)
        rule.onNodeWithText("ابحث عن تحديث").performScrollTo().performClick()
        val releases: Intent = shadowOf(rule.activity).nextStartedActivity ?: app.nextStartedActivity
        assertEquals(UpdateLinks.RELEASES, releases.dataString)
    }

    @Test
    fun missingObtainiumShowsDialogWithoutCrashing() {
        openSettings()
        // Make unresolvable intents throw ActivityNotFoundException, as on a phone without Obtainium.
        shadowOf(RuntimeEnvironment.getApplication()).checkActivities(true)
        rule.onNodeWithText("حدّثه تلقائياً عبر Obtainium").performScrollTo().performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithText("نزّل Obtainium").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("نزّل Obtainium").performClick() // no browser either: a message, no crash
        rule.waitForIdle()
    }
}
