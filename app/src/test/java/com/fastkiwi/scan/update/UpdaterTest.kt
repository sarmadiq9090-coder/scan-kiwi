// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.update

import com.fastkiwi.scan.ui.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest
import java.util.Collections

/**
 * 2.0-update: the GitHub update path against a local mock server (never the real GitHub): version
 * comparison, SHA256SUMS check, signing-certificate check, no network, the 7-day limit, Wi-Fi only,
 * the host allow-list and the request headers. Robolectric only for org.json and the preferences.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class UpdaterTest {
    private lateinit var server: MiniHttpServer
    private lateinit var base: String
    private val requests = Collections.synchronizedList(ArrayList<String>())
    private val userAgents = Collections.synchronizedList(ArrayList<String?>())
    private val apkBytes = ByteArray(300_000) { (it * 31 % 251).toByte() }
    private var tag = "v2.1.0"
    private var sums: String = ""
    private var redirectApkTo: String? = null
    private lateinit var work: File

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    @Before
    fun setUp() {
        work = File(RuntimeEnvironment.getApplication().cacheDir, "updates-test").apply { deleteRecursively(); mkdirs() }
        sums = "${sha(apkBytes)}  ScanKiwi-2.1.0.apk\n${"0".repeat(64)}  ScanKiwi-2.1.0.cdx.json\n"
        server = MiniHttpServer { path, headers ->
            requests.add(path)
            userAgents.add(headers["user-agent"])
            when (path) {
                "/repos/x/releases/latest" -> Triple(200, emptyMap(), """
                    {"tag_name":"$tag","draft":false,"prerelease":false,"assets":[
                      {"name":"ScanKiwi-2.1.0.apk","size":${apkBytes.size},"browser_download_url":"$base/dl/ScanKiwi-2.1.0.apk"},
                      {"name":"ScanKiwi-2.1.0.cdx.json","size":10,"browser_download_url":"$base/dl/sbom"},
                      {"name":"SHA256SUMS.txt","size":200,"browser_download_url":"$base/dl/SHA256SUMS.txt"}]}
                """.trimIndent().toByteArray())
                "/dl/SHA256SUMS.txt" -> Triple(200, emptyMap(), sums.toByteArray())
                "/dl/ScanKiwi-2.1.0.apk" -> redirectApkTo?.let { Triple(302, mapOf("Location" to it), ByteArray(0)) } ?: Triple(200, emptyMap(), apkBytes)
                else -> Triple(404, emptyMap(), "{}".toByteArray())
            }
        }
        base = "http://127.0.0.1:${server.port}"
    }

    @After
    fun tearDown() {
        server.close()
        work.deleteRecursively()
    }

    private fun client() = UpdateClient("ScanKiwi/2.0.0", "$base/repos/x/releases/latest", setOf("127.0.0.1"), requireHttps = false, timeoutMs = 5_000)

    private fun updater(current: String = "2.0.0", net: NetworkState = NetworkState.WIFI, sameSigner: Boolean = true, signerCalls: MutableList<File>? = null) =
        Updater(client(), current, { f -> signerCalls?.add(f); sameSigner }, work) { net }

    private fun available(u: Updater): Release = (u.check() as? CheckResult.Available)?.release ?: error("no update offered")

    // ------------------------------------------------------------------ versions

    @Test
    fun semverPrecedence() {
        fun v(s: String) = SemVer.parse(s)!!
        assertTrue(v("2.0.1") > v("2.0.0"))
        assertTrue(v("v2.1.0") > v("2.0.9"))
        assertTrue(v("10.0.0") > v("9.9.9"))
        assertTrue(v("2.0.0") > v("2.0.0-rc.1"))
        assertTrue(v("2.0.0-rc.2") > v("2.0.0-rc.1"))
        assertTrue(v("2.0.0-rc.10") > v("2.0.0-rc.9"))
        assertTrue(v("2.0.0-beta") > v("2.0.0-alpha.1"))
        assertTrue(v("2.0.0-alpha.1") > v("2.0.0-alpha"))
        assertEquals(0, v("2.0.0+build.5").compareTo(v("2.0.0")))
        for (bad in listOf("2.0", "2.0.0.1", "latest", "v02.0.0", "", "2.0.0-")) assertNull(bad, SemVer.parse(bad))
    }

    @Test
    fun onlyANewerReleaseIsOffered() {
        assertEquals("2.1.0", available(updater("2.0.0")).version.toString())
        assertEquals(CheckResult.UpToDate, updater("2.1.0").check())
        assertEquals(CheckResult.UpToDate, updater("2.2.0").check())
        tag = "nightly"
        assertEquals(CheckResult.Failed(UpdateError.BAD_RELEASE), updater("2.0.0").check())
        // A release that is not newer is never downloaded either.
        tag = "v2.1.0"
        val r = available(updater("2.0.0"))
        assertEquals(DownloadResult.Failed(UpdateError.NOT_NEWER), updater("2.1.0").download(r, wifiOnly = false))
    }

    // ------------------------------------------------------------------ verification

    @Test
    fun verifiedDownloadKeepsTheFileAndAsksTheCertificateCheck() {
        val calls = ArrayList<File>()
        val u = updater(signerCalls = calls)
        val r = u.download(available(u), wifiOnly = true) as DownloadResult.Verified
        assertTrue(r.apk.isFile)
        assertEquals(sha(apkBytes), sha(r.apk.readBytes()))
        assertEquals(listOf(r.apk), calls)
    }

    @Test
    fun shaMismatchDeletesTheFileAndInstallsNothing() {
        sums = "${"ab".repeat(32)}  ScanKiwi-2.1.0.apk\n"
        val calls = ArrayList<File>()
        val u = updater(signerCalls = calls)
        assertEquals(DownloadResult.Failed(UpdateError.SHA_MISMATCH), u.download(available(u), wifiOnly = false))
        assertTrue("files left: ${work.list()?.toList()}", work.listFiles().orEmpty().isEmpty())
        assertTrue("certificate check must not run on a bad file", calls.isEmpty())
        // No line for the APK in SHA256SUMS: refused before downloading it.
        sums = "${sha(apkBytes)}  Other.apk\n"
        requests.clear()
        assertEquals(DownloadResult.Failed(UpdateError.BAD_RELEASE), u.download(available(u), wifiOnly = false))
        assertFalse(requests.contains("/dl/ScanKiwi-2.1.0.apk"))
    }

    @Test
    fun certificateMismatchDeletesTheFileAndInstallsNothing() {
        val u = updater(sameSigner = false)
        assertEquals(DownloadResult.Failed(UpdateError.SIGNATURE_MISMATCH), u.download(available(u), wifiOnly = false))
        assertTrue(work.listFiles().orEmpty().isEmpty())
        // A verifier that throws counts as a mismatch.
        val throwing = Updater(client(), "2.0.0", { error("broken archive") }, work) { NetworkState.WIFI }
        assertEquals(DownloadResult.Failed(UpdateError.SIGNATURE_MISMATCH), throwing.download(available(throwing), wifiOnly = false))
        assertTrue(work.listFiles().orEmpty().isEmpty())
    }

    // ------------------------------------------------------------------ network

    @Test
    fun noNetworkMeansNoRequestAndAClearError() {
        assertEquals(CheckResult.Failed(UpdateError.OFFLINE), updater(net = NetworkState.OFFLINE).check())
        assertTrue(requests.isEmpty())
        val r = available(updater())
        requests.clear()
        assertEquals(DownloadResult.Failed(UpdateError.OFFLINE), updater(net = NetworkState.OFFLINE).download(r, wifiOnly = false))
        assertTrue(requests.isEmpty())
        // The server is gone (no route, refused): a network error, nothing left behind.
        server.close()
        assertEquals(CheckResult.Failed(UpdateError.NETWORK), updater().check())
        assertEquals(DownloadResult.Failed(UpdateError.NETWORK), updater().download(r, wifiOnly = false))
        assertTrue(work.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun wifiOnlyRefusesMobileDataButStillChecks() {
        val mobile = updater(net = NetworkState.MOBILE)
        val r = available(mobile)
        requests.clear()
        assertEquals(DownloadResult.Failed(UpdateError.WIFI_REQUIRED), mobile.download(r, wifiOnly = true))
        assertTrue("nothing downloaded on mobile data: $requests", requests.isEmpty())
        assertTrue(mobile.download(r, wifiOnly = false) is DownloadResult.Verified)
        assertTrue(updater(net = NetworkState.WIFI).download(r, wifiOnly = true) is DownloadResult.Verified)
        assertFalse(UpdatePolicy.downloadAllowed(true, NetworkState.MOBILE))
        assertTrue(UpdatePolicy.downloadAllowed(true, NetworkState.WIFI))
        assertFalse(UpdatePolicy.downloadAllowed(false, NetworkState.OFFLINE))
    }

    @Test
    fun onlyAllowedHostsOverHttpsAndAGenericUserAgent() {
        // A redirect to any other host is refused before it is followed.
        redirectApkTo = "http://localhost:${server.port}/dl/ScanKiwi-2.1.0.apk"
        val u = updater()
        assertEquals(DownloadResult.Failed(UpdateError.NETWORK), u.download(available(u), wifiOnly = false))
        assertFalse(requests.count { it == "/dl/ScanKiwi-2.1.0.apk" } > 1)
        // The app's client: HTTPS to the four GitHub hosts only.
        val real = UpdateClient("ScanKiwi/2.0.0")
        assertEquals("https://api.github.com/repos/sarmadiq9090-coder/scan-kiwi/releases/latest", real.latestUrl)
        for (bad in listOf("http://api.github.com/x", "https://example.com/x", "https://api.github.com.evil.test/x", "https://user:pw@github.com/x", "file:///etc/hosts")) {
            try { real.checked(bad); fail("accepted $bad") } catch (_: UpdateClient.NetworkException) {}
        }
        for (ok in UpdateClient.GITHUB_HOSTS) real.checked("https://$ok/a")
        // Requests carry only the generic User-Agent.
        assertTrue(userAgents.isNotEmpty())
        assertTrue(userAgents.toString(), userAgents.all { it == "ScanKiwi/2.0.0" })
    }

    // ------------------------------------------------------------------ 7-day limit

    @Test
    fun automaticCheckAtMostOnceEverySevenDays() {
        val day = 24L * 60 * 60 * 1000
        assertTrue(UpdatePolicy.autoCheckDue(true, true, 0L, 100 * day))
        assertFalse(UpdatePolicy.autoCheckDue(false, true, 0L, 100 * day))
        assertFalse("not before the notice", UpdatePolicy.autoCheckDue(true, false, 0L, 100 * day))
        assertFalse(UpdatePolicy.autoCheckDue(true, true, 100 * day, 106 * day))
        assertTrue(UpdatePolicy.autoCheckDue(true, true, 100 * day, 107 * day))
        assertFalse("clock set back", UpdatePolicy.autoCheckDue(true, true, 100 * day, 50 * day))

        val app = RuntimeEnvironment.getApplication()
        val settings = Settings(app).apply { updateNoticeShown = true; autoUpdateCheck = true; lastUpdateCheck = 0L }
        var now = 1_000 * day
        var net = NetworkState.WIFI
        var offered: Release? = null
        val c = UpdateController(app, settings, CoroutineScope(Dispatchers.Unconfined), Updater(client(), "2.0.0", { true }, work) { net }, { now }, { offered = it })
        fun open() = runBlocking { c.onAppOpened()?.join() }
        fun checks() = requests.count { it == "/repos/x/releases/latest" }
        open()
        assertEquals(1, checks())
        assertEquals("2.1.0", offered?.version.toString())
        assertTrue(c.state.value is UpdateState.Available)
        for (d in listOf(1, 3, 6)) { now = (1_000 + d) * day; open() }
        assertEquals("opened on days 1, 3 and 6: no new check", 1, checks())
        now = 1_007 * day; open()
        assertEquals(2, checks())
        // Offline at start: nothing asked and nothing recorded, so the next start checks.
        now = 1_014 * day; net = NetworkState.OFFLINE; open()
        assertEquals(2, checks())
        net = NetworkState.WIFI; now += 60_000; open()
        assertEquals(3, checks())
        // Turned off: never.
        settings.autoUpdateCheck = false
        now = 2_000 * day; open()
        assertEquals(3, checks())
        // The manual button works at any time, also right after a check.
        settings.autoUpdateCheck = true
        runBlocking { c.checkNow()?.join() }
        runBlocking { c.checkNow()?.join() }
        assertEquals(5, checks())
    }

    @Test
    fun noticeComesBeforeAnyAutomaticCheck() {
        val app = RuntimeEnvironment.getApplication()
        val settings = Settings(app).apply { updateNoticeShown = false; autoUpdateCheck = true; lastUpdateCheck = 0L }
        UpdateController.showNoticeInTests = true
        try {
            val c = UpdateController(app, settings, CoroutineScope(Dispatchers.Unconfined), updater(), { 5L })
            assertTrue(c.noticePending.value)
            runBlocking { c.onAppOpened()?.join() }
            assertTrue("no check before the notice", requests.isEmpty())
            c.noticeSeen()
            assertFalse(c.noticePending.value)
            assertTrue(settings.updateNoticeShown)
            runBlocking { c.onAppOpened()?.join() }
            assertEquals(1, requests.size)
            // Shown once: a new controller (next start) does not show it again.
            assertFalse(UpdateController(app, settings, CoroutineScope(Dispatchers.Unconfined), updater(), { 5L }).noticePending.value)
        } finally {
            UpdateController.showNoticeInTests = false
        }
    }

    @Test
    fun sha256SumsFormats() {
        val h = "a".repeat(64)
        assertEquals(h, Updater.sha256For("$h  ScanKiwi-2.1.0.apk\n", "ScanKiwi-2.1.0.apk"))
        assertEquals(h, Updater.sha256For("$h *ScanKiwi-2.1.0.apk\r\n", "ScanKiwi-2.1.0.apk"))
        assertEquals(h, Updater.sha256For("${"B".repeat(64)}  x\n${h.uppercase()}  ScanKiwi-2.1.0.apk", "ScanKiwi-2.1.0.apk"))
        assertNull(Updater.sha256For("$h  ScanKiwi-2.1.0.apk.part\n", "ScanKiwi-2.1.0.apk"))
        assertNull(Updater.sha256For("abc  ScanKiwi-2.1.0.apk\n", "ScanKiwi-2.1.0.apk"))
    }
}
