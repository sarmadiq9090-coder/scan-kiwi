// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import java.io.File
import java.security.MessageDigest

/**
 * 2.0-update: the downloaded archive must be this app, signed with the installed app's certificate(s),
 * read through PackageManager on the archive path. Android would refuse a different key anyway; this
 * check refuses it before the user is asked anything.
 */
class AndroidApkVerifier(private val ctx: Context) : ApkVerifier {
    override fun sameSignerAndPackage(apk: File): Boolean {
        val pm = ctx.packageManager
        val archive = archiveInfo(pm, apk.path) ?: return false
        if (archive.packageName != ctx.packageName) return false
        val installed = installedInfo(pm) ?: return false
        return acceptable(archive.packageName, ctx.packageName, certs(archive), certs(installed),
            androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(archive),
            androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(installed))
    }

    companion object {
        /**
         * The archive is this app, signed with exactly the installed app's certificate(s), with a higher
         * versionCode: a release whose tag says newer but whose APK is the same or an older build is refused
         * here (before Android's own downgrade refusal) instead of being offered again and again.
         */
        fun acceptable(archivePackage: String?, appPackage: String, archiveCerts: Set<String>, installedCerts: Set<String>, archiveVersionCode: Long, installedVersionCode: Long): Boolean =
            archivePackage == appPackage && archiveCerts.isNotEmpty() && archiveCerts == installedCerts && archiveVersionCode > installedVersionCode
    }

    @Suppress("DEPRECATION")
    private fun archiveInfo(pm: PackageManager, path: String): PackageInfo? =
        if (Build.VERSION.SDK_INT >= 28) {
            pm.getPackageArchiveInfo(path, PackageManager.GET_SIGNING_CERTIFICATES)?.takeIf { it.signingInfo != null }
                ?: pm.getPackageArchiveInfo(path, PackageManager.GET_SIGNATURES)
        } else pm.getPackageArchiveInfo(path, PackageManager.GET_SIGNATURES)

    @Suppress("DEPRECATION")
    private fun installedInfo(pm: PackageManager): PackageInfo? = runCatching {
        pm.getPackageInfo(ctx.packageName, if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES)
    }.getOrNull()

    /** SHA-256 of each signing certificate (the current signers; key rotation is not used by this app). */
    @Suppress("DEPRECATION")
    private fun certs(p: PackageInfo): Set<String> {
        val sigs = if (Build.VERSION.SDK_INT >= 28 && p.signingInfo != null) p.signingInfo!!.apkContentsSigners else p.signatures
        return sigs.orEmpty().map { s -> MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) } }.toSet()
    }
}

/** The connection now: connected, and unmetered (Wi-Fi or Ethernet) or not. Needs ACCESS_NETWORK_STATE. */
fun currentNetwork(ctx: Context): NetworkState = runCatching {
    val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return NetworkState.OFFLINE
    val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return NetworkState.OFFLINE) ?: return NetworkState.OFFLINE
    val connected = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    val unmetered = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) ||
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    NetworkState(connected, connected && unmetered && !cm.isActiveNetworkMetered)
}.getOrDefault(NetworkState.OFFLINE)

/**
 * Installs a verified APK with PackageInstaller. Android shows its usual confirmation to the user
 * ([UpdateInstallReceiver] opens it); nothing is installed without that tap.
 */
object UpdateInstaller {
    const val ACTION = "com.fastkiwi.scan.update.INSTALL_STATUS"

    fun install(ctx: Context, apk: File) {
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(ctx.packageName)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            try {
                session.openWrite("base.apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                val intent = Intent(ACTION).setPackage(ctx.packageName)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                session.commit(PendingIntent.getBroadcast(ctx, id, intent, flags).intentSender)
            } catch (e: Exception) {
                // A half-written session would hold the copied bytes until Android expires it.
                runCatching { session.abandon() }
                throw e
            }
        }
    }
}

/**
 * What the installer reported, shared between [UpdateInstallReceiver] (which can run when no screen is open)
 * and the app: whether the app is in front, Android's confirmation screen waiting for the user, and who to tell.
 */
object UpdateInstallEvents {
    /** True between MainActivity.onStart and onStop: only then may the confirmation screen be opened directly. */
    @Volatile var foreground = false

    /** Android's confirmation screen (from the installer) that could not be opened because the app was in the background. */
    @Volatile var pending: Intent? = null

    /** Called on the main thread when the installer reported something that changes what the screen shows. */
    @Volatile var listener: (() -> Unit)? = null

    /** The installer said the install failed (PackageInstaller status other than success or "needs the user"). */
    @Volatile var failed = false
}

/** Receives the install status; on «needs the user» it opens Android's own confirmation screen. */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        @Suppress("DEPRECATION")
        val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java) else intent.getParcelableExtra(Intent.EXTRA_INTENT)
        handle(ctx, intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE), confirm)
    }

    internal fun handle(ctx: Context, status: Int, confirm: Intent?) {
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                if (confirm == null) return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                // Android refuses to start an activity from the background (10+): the user left the app during the
                // download. Keep the screen and let the app offer it when the user comes back (banner «التحديث جاهز للتثبيت»).
                val opened = UpdateInstallEvents.foreground && runCatching { ctx.startActivity(confirm) }.isSuccess
                UpdateInstallEvents.pending = if (opened) null else confirm
            }
            PackageInstaller.STATUS_SUCCESS -> {
                UpdateInstallEvents.pending = null
                UpdateInstallEvents.failed = false
                File(ctx.cacheDir, "updates").listFiles()?.forEach { it.delete() }
            }
            else -> {
                // Failed (or aborted by the user): the verified file stays so «try again» does not download it again.
                UpdateInstallEvents.pending = null
                UpdateInstallEvents.failed = true
            }
        }
        UpdateInstallEvents.listener?.invoke() // a broadcast receiver runs on the main thread
    }
}
