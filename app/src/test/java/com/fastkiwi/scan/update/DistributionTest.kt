// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.update

import android.content.pm.PackageManager
import com.fastkiwi.scan.BuildConfig
import com.fastkiwi.scan.ui.Settings
import kotlinx.coroutines.MainScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * 2.0.3: the two distributions. `full` (GitHub) keeps the updater and its three permissions; `fdroid`
 * has no updater and requests only CAMERA. The merged manifest of the flavor under test is checked.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DistributionTest {
    private val ctx = RuntimeEnvironment.getApplication()
    private val updatePermissions = setOf(
        "android.permission.INTERNET",
        "android.permission.ACCESS_NETWORK_STATE",
        "android.permission.REQUEST_INSTALL_PACKAGES",
    )

    private fun requested(): Set<String> =
        ctx.packageManager.getPackageInfo(ctx.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty().toSet()

    @Test
    fun permissionsMatchTheFlavor() {
        val perms = requested()
        assertTrue(perms.contains("android.permission.CAMERA"))
        if (BuildConfig.UPDATER_ENABLED) assertTrue(perms.containsAll(updatePermissions))
        else assertEquals(emptySet<String>(), perms intersect updatePermissions)
    }

    @Test
    fun disabledControllerDoesNothing() {
        if (BuildConfig.UPDATER_ENABLED) return
        val c = UpdateController(ctx, Settings(ctx), MainScope())
        assertNull(c.onAppOpened())
        assertNull(c.checkNow())
        assertFalse(c.noticePending.value)
        assertEquals(UpdateState.Idle, c.state.value)
    }

    /**
     * 2.0.3 bug hunt: the fdroid build has no network permission, so no text it shows outside the (unreachable)
     * Updates screen may say it connects for updates. «privacy_short» (Home and Camera) still did.
     */
    @Test
    fun fdroidTextDoesNotClaimAnUpdateConnection() {
        if (BuildConfig.UPDATER_ENABLED) return
        val shown = listOf(
            com.fastkiwi.scan.R.string.privacy_short,
            com.fastkiwi.scan.R.string.privacy_body,
            com.fastkiwi.scan.R.string.network_statement,
            com.fastkiwi.scan.R.string.update_details,
        )
        val claims = listOf("GitHub", "check for updates", "التحقق من التحديثات", "للتحقق", "internet is used", "الاتصال فقط")
        for (lang in listOf("ar", "en")) {
            val cfg = android.content.res.Configuration(ctx.resources.configuration).apply { setLocale(Locale.forLanguageTag(lang)) }
            val res = ctx.createConfigurationContext(cfg).resources
            for (id in shown) {
                val text = res.getString(id)
                for (c in claims) assertFalse("$lang ${res.getResourceEntryName(id)}: \"$text\" mentions \"$c\"", text.contains(c, ignoreCase = true))
            }
        }
    }
}
