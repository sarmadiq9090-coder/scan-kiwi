// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.update

import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import com.fastkiwi.scan.MainActivity
import com.fastkiwi.scan.ui.Settings
import kotlinx.coroutines.MainScope
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * 2.0 torture gate, S3 items 1 and 2: what the user sees when PackageInstaller reports a failure, and when its
 * confirmation screen could not be opened because the user left the app during the download.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "ar-w400dp-h860dp-xxhdpi")
class UpdateInstallTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val ctx = RuntimeEnvironment.getApplication()
    private val release = Release(SemVer.parse("2.0.1")!!, "v2.0.1", "ScanKiwi-2.0.1.apk", "https://github.com/x/ScanKiwi-2.0.1.apk", 10, "https://github.com/x/SHA256SUMS.txt")

    @Before
    fun onlyWithUpdater() = assumeTrue("the fdroid flavor has no updater", com.fastkiwi.scan.BuildConfig.UPDATER_ENABLED)

    @Before
    fun reset() {
        UpdateInstallEvents.pending = null
        UpdateInstallEvents.failed = false
        UpdateInstallEvents.foreground = false
    }

    @After
    fun after() = reset()

    private fun controller() = UpdateController(ctx, Settings(ctx), MainScope())

    private fun apk(): File = File(ctx.cacheDir, "updates/ScanKiwi-2.0.1.apk").apply { parentFile!!.mkdirs(); writeText("apk") }

    private fun report(status: Int, confirm: Intent? = null) {
        val i = Intent(UpdateInstaller.ACTION).putExtra(PackageInstaller.EXTRA_STATUS, status)
        if (confirm != null) i.putExtra(Intent.EXTRA_INTENT, confirm)
        UpdateInstallReceiver().onReceive(ctx, i)
        shadowOf(android.os.Looper.getMainLooper()).idle()
    }

    @Test
    fun anInstallFailureSaysNotInstalledAndTheNextTapTriesTheInstallerAgain() {
        val c = controller()
        var calls = 0
        c.installer = { calls++; true }
        val f = apk()
        c.install(f, release)
        assertEquals(1, calls)
        report(PackageInstaller.STATUS_FAILURE)
        // A clear message (never "storage"), the release kept for a retry, and the verified file kept.
        assertEquals(UpdateState.Failed(UpdateError.INSTALL, release), c.state.value)
        assertTrue("the verified file is kept for the retry", f.exists())
        // The next tap does not say storage: it runs the installer on the same file.
        c.retryInstall(release)
        assertEquals(2, calls)
        assertTrue(c.state.value !is UpdateState.Failed)
        // A failure that happens when the installer cannot even start is the same message, file kept.
        c.installer = { false }
        c.retryInstall(release)
        assertEquals(UpdateState.Failed(UpdateError.INSTALL, release), c.state.value)
        assertTrue(f.exists())
        // A success report forgets everything.
        report(PackageInstaller.STATUS_SUCCESS)
        assertFalse(f.exists())
    }

    @Test
    fun theFailureMessageIsInBothLanguages() {
        val ar = ctx.getString(com.fastkiwi.scan.R.string.update_err_install)
        assertTrue(ar, ar.contains("لم يُثبَّت التحديث") && !ar.contains("تخزين"))
        val en = ctx.createConfigurationContext(android.content.res.Configuration(ctx.resources.configuration).apply { setLocale(java.util.Locale.ENGLISH) })
            .getString(com.fastkiwi.scan.R.string.update_err_install)
        assertEquals("The update was not installed. Try again.", en)
    }

    @Test
    fun confirmationOpensAtOnceWhileTheAppIsInFront() {
        val c = controller()
        UpdateInstallEvents.foreground = true
        report(PackageInstaller.STATUS_PENDING_USER_ACTION, Intent("android.intent.action.VIEW"))
        assertNotNull(shadowOf(ctx).nextStartedActivity)
        assertNull(UpdateInstallEvents.pending)
        assertFalse(c.confirmPending.value)
    }

    @Test
    fun leavingTheAppDuringTheDownloadShowsReadyToInstallOnReturn() {
        val c = controller()
        UpdateInstallEvents.foreground = false // the user is in another app: Android would refuse to start the screen
        val confirm = Intent("android.intent.action.VIEW").putExtra("marker", 7)
        report(PackageInstaller.STATUS_PENDING_USER_ACTION, confirm)
        assertNull("nothing was forced onto the user's screen", shadowOf(ctx).nextStartedActivity)
        assertTrue("the banner is due", c.confirmPending.value)
        // The user taps it: Android's own confirmation opens.
        c.openPendingConfirm()
        val started = shadowOf(ctx).nextStartedActivity
        assertEquals(7, started.getIntExtra("marker", 0))
        assertFalse(c.confirmPending.value)
        assertNull(UpdateInstallEvents.pending)
    }

    @Test
    fun theBannerAppearsOnTheScreenWithTheOwnersWords() {
        val confirm = Intent("android.intent.action.VIEW")
        UpdateInstallEvents.foreground = false
        report(PackageInstaller.STATUS_PENDING_USER_ACTION, confirm)
        // The user comes back (onStart).
        rule.activity.vm.updates.refreshInstallStatus()
        rule.waitUntil(5_000) { rule.onAllNodesWithText("التحديث جاهز للتثبيت").fetchSemanticsNodes().isNotEmpty() }
    }
}
