// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.pdf

import android.content.ComponentName
import android.net.Uri
import android.os.Looper
import com.fastkiwi.scan.ui.PdfTool
import com.fastkiwi.scan.ui.PdfToolsViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/**
 * The PDF tools screen's state through the real [PdfService] protocol: pick two files (one
 * protected), unlock with the password, merge, split, and check the originals are unchanged.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PdfToolsFlowTest {
    private val app = RuntimeEnvironment.getApplication()
    private val dir = File(app.cacheDir, "flow-${System.nanoTime()}").apply { mkdirs() }

    private fun until(timeoutMs: Long = 30_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!cond()) {
            check(System.currentTimeMillis() < end) { "timed out" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
    }

    @Test
    fun pickUnlockMergeAndSplit() {
        val service = Robolectric.setupService(PdfService::class.java)
        shadowOf(app).setComponentNameAndServiceForBindService(ComponentName(app, PdfService::class.java), service.onBind(null))
        val a = SyntheticPdfs.external(dir, "VA", 3, seed = 21)
        val b = SyntheticPdfs.external(dir, "VB", 2, seed = 22, password = "كلمة سر")
        val shaA = SyntheticPdfs.sha256(a)
        val shaB = SyntheticPdfs.sha256(b)
        val vm = PdfToolsViewModel(app)
        vm.add(listOf(Uri.fromFile(a), Uri.fromFile(b)))
        until { vm.files.size == 2 && vm.files[0].pages != null && vm.files[1].locked && vm.working.value == null }
        assertEquals(3, vm.files[0].pages)
        // Wrong password: still locked and marked wrong; right password: readable.
        vm.unlockEntry(vm.files[1].id, "wrong")
        until { vm.working.value == null && vm.files[1].wrong }
        vm.unlockEntry(vm.files[1].id, "كلمة سر")
        until { vm.working.value == null && vm.files[1].pages == 2 }

        vm.runTool(PdfTool.MERGE)
        until { vm.results.value != null && vm.working.value == null }
        val merged = vm.results.value!!.single()
        assertEquals(SyntheticPdfs.markers(a) + SyntheticPdfs.markers(b, "كلمة سر"), SyntheticPdfs.markers(merged))
        vm.results.value = null

        vm.selected.value = vm.files[0].id
        vm.runTool(PdfTool.SPLIT, ranges = "١، ٢-٣")
        until { vm.results.value != null && vm.working.value == null }
        assertEquals(listOf(1, 2), vm.results.value!!.map { SyntheticPdfs.markers(it).size })
        assertEquals("originals are only read", shaA to shaB, SyntheticPdfs.sha256(a) to SyntheticPdfs.sha256(b))
        assertTrue(vm.results.value!!.all { it.parentFile!!.name == "exports" })
    }
}
