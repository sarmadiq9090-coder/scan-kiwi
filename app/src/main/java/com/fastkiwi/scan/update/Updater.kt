// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.update

import org.json.JSONObject
import java.io.File

/** One published release: its version, the APK asset and the SHA256SUMS asset. */
data class Release(val version: SemVer, val tag: String, val apkName: String, val apkUrl: String, val apkSize: Long, val sumsUrl: String)

/** Why an update could not be offered or installed. Each maps to one message in the UI. */
enum class UpdateError { OFFLINE, NETWORK, NO_RELEASE, BAD_RELEASE, WIFI_REQUIRED, SHA_MISMATCH, SIGNATURE_MISMATCH, NOT_NEWER, STORAGE }

sealed interface CheckResult {
    data object UpToDate : CheckResult
    data class Available(val release: Release) : CheckResult
    data class Failed(val error: UpdateError) : CheckResult
}

sealed interface DownloadResult {
    /** Verified: newer, sha256 equal to SHA256SUMS, same signing certificate as the installed app. */
    data class Verified(val apk: File, val release: Release) : DownloadResult
    data class Failed(val error: UpdateError) : DownloadResult
}

/**
 * Checks the archive's signing certificates and package against the installed app
 * ([AndroidApkVerifier] in the app, a fake in tests).
 */
fun interface ApkVerifier {
    /** True only when the archive is this app (same package), signed with the installed app's certificate(s). */
    fun sameSignerAndPackage(apk: File): Boolean
}

/**
 * 2.0-update: check → download → verify. Every condition is mandatory; on any failure the file is
 * deleted and nothing is installed. Pure Kotlin + org.json; the network, the connection state and the
 * certificate check are injected.
 */
class Updater(
    private val client: UpdateClient,
    private val currentVersion: String,
    private val verifier: ApkVerifier,
    private val workDir: File,
    private val network: () -> NetworkState,
) {
    fun check(): CheckResult {
        if (!network().connected) return CheckResult.Failed(UpdateError.OFFLINE)
        val json = try {
            client.getText(client.latestUrl)
        } catch (e: UpdateClient.NetworkException) {
            return CheckResult.Failed(if (e.message == "HTTP 404") UpdateError.NO_RELEASE else UpdateError.NETWORK)
        } catch (e: java.io.IOException) {
            return CheckResult.Failed(UpdateError.NETWORK)
        } catch (e: RuntimeException) {
            // Never crash the app over an update check (the automatic one runs at app open).
            return CheckResult.Failed(UpdateError.NETWORK)
        }
        val release = parse(json) ?: return CheckResult.Failed(UpdateError.BAD_RELEASE)
        val current = SemVer.parse(currentVersion) ?: return CheckResult.Failed(UpdateError.BAD_RELEASE)
        return if (release.version > current) CheckResult.Available(release) else CheckResult.UpToDate
    }

    fun download(release: Release, wifiOnly: Boolean, onProgress: (Long, Long) -> Unit = { _, _ -> }): DownloadResult {
        val net = network()
        if (!net.connected) return DownloadResult.Failed(UpdateError.OFFLINE)
        if (!UpdatePolicy.downloadAllowed(wifiOnly, net)) return DownloadResult.Failed(UpdateError.WIFI_REQUIRED)
        val current = SemVer.parse(currentVersion)
        if (current == null || release.version <= current) return DownloadResult.Failed(UpdateError.NOT_NEWER)
        clean()
        // The expected hash comes from the same release's SHA256SUMS.txt.
        val expected = try {
            sha256For(client.getText(release.sumsUrl, maxBytes = 64 * 1024), release.apkName)
        } catch (e: java.io.IOException) {
            return DownloadResult.Failed(UpdateError.NETWORK)
        } catch (e: RuntimeException) {
            return DownloadResult.Failed(UpdateError.NETWORK)
        } ?: return DownloadResult.Failed(UpdateError.BAD_RELEASE)
        val part = File(workDir, release.apkName + ".part")
        // «Wi-Fi فقط» holds for the whole download: moving to mobile data (or going offline) stops it.
        var stoppedBy: UpdateError? = null
        val keepGoing = {
            val now = network()
            stoppedBy = if (!now.connected) UpdateError.OFFLINE else if (!UpdatePolicy.downloadAllowed(wifiOnly, now)) UpdateError.WIFI_REQUIRED else null
            stoppedBy == null
        }
        val got = try {
            client.download(release.apkUrl, part, MAX_APK_BYTES, keepGoing, onProgress)
        } catch (e: UpdateClient.NetworkException) {
            part.delete(); return DownloadResult.Failed(stoppedBy ?: UpdateError.NETWORK)
        } catch (e: java.io.IOException) {
            part.delete(); return DownloadResult.Failed(UpdateError.STORAGE)
        } catch (e: RuntimeException) {
            part.delete(); return DownloadResult.Failed(UpdateError.NETWORK)
        }
        if (!got.equals(expected, ignoreCase = true)) { part.delete(); return DownloadResult.Failed(UpdateError.SHA_MISMATCH) }
        val apk = File(workDir, release.apkName)
        if (!part.renameTo(apk)) { part.delete(); return DownloadResult.Failed(UpdateError.STORAGE) }
        val sameSigner = runCatching { verifier.sameSignerAndPackage(apk) }.getOrDefault(false)
        if (!sameSigner) { apk.delete(); return DownloadResult.Failed(UpdateError.SIGNATURE_MISMATCH) }
        return DownloadResult.Verified(apk, release)
    }

    /** Removes downloaded or partial update files (after an install, a failure or a cancel). */
    fun clean() {
        workDir.listFiles()?.forEach { it.delete() }
    }

    companion object {
        /** The 2.0 cap is 30 MB; a release asset several times larger is not ours. */
        const val MAX_APK_BYTES = 100L * 1024 * 1024

        private val APK_NAME = Regex("""^ScanKiwi-[0-9A-Za-z.+-]+\.apk$""")

        /** The GitHub "latest release" JSON → [Release]; null when the version or an asset is missing. */
        fun parse(json: String): Release? = runCatching {
            val o = JSONObject(json)
            if (o.optBoolean("draft") || o.optBoolean("prerelease")) return null
            val tag = o.getString("tag_name")
            val version = SemVer.parse(tag) ?: return null
            val assets = o.getJSONArray("assets")
            var apk: JSONObject? = null
            var sums: JSONObject? = null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                val name = a.optString("name")
                when {
                    name == "SHA256SUMS.txt" || name == "SHA256SUMS" -> sums = a
                    APK_NAME.matches(name) && apk == null -> apk = a
                }
            }
            val ap = apk ?: return null
            val su = sums ?: return null
            Release(version, tag, ap.getString("name"), ap.getString("browser_download_url"), ap.optLong("size", -1), su.getString("browser_download_url"))
        }.getOrNull()

        /** The sha256 listed for [file] in a `sha256sum` file ("<hex>  <name>" or "<hex> *<name>"). */
        fun sha256For(sums: String, file: String): String? = sums.lineSequence()
            .map { it.trim() }
            .mapNotNull { line -> Regex("""^([0-9a-fA-F]{64})\s+\*?(.+)$""").matchEntire(line) }
            .firstOrNull { it.groupValues[2].trim() == file }
            ?.groupValues?.get(1)?.lowercase()
    }
}
