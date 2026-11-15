// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.update

/**
 * 2.0-update: when the automatic check may run. Only when the app is opened, at most once every
 * [INTERVAL_MS] (7 days, the owner's rule), only after the one-time network notice was shown, and
 * never when the user turned it off. The manual «تحقق من التحديثات» button ignores this policy.
 */
object UpdatePolicy {
    const val INTERVAL_MS = 7L * 24 * 60 * 60 * 1000

    /** [lastCheckMs] = 0: never checked. A clock set back (last > now) waits until the interval passed again from now. */
    fun autoCheckDue(enabled: Boolean, noticeShown: Boolean, lastCheckMs: Long, nowMs: Long): Boolean {
        if (!enabled || !noticeShown) return false
        if (lastCheckMs <= 0L) return true
        if (lastCheckMs > nowMs) return false
        return nowMs - lastCheckMs >= INTERVAL_MS
    }

    /** May the APK be downloaded on the current network? */
    fun downloadAllowed(wifiOnly: Boolean, net: NetworkState): Boolean = net.connected && (!wifiOnly || net.unmetered)
}

/** What the phone's connection is now (ConnectivityManager in the app, a fixed value in tests). */
data class NetworkState(val connected: Boolean, val unmetered: Boolean) {
    companion object {
        val OFFLINE = NetworkState(connected = false, unmetered = false)
        val WIFI = NetworkState(connected = true, unmetered = true)
        val MOBILE = NetworkState(connected = true, unmetered = false)
    }
}
