// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.perf

import android.os.StrictMode

/**
 * 2.0-perf (ROADMAP 1.7.0 test plan): StrictMode in debug builds only. Disk reads/writes, network
 * and slow calls on the main thread, and leaked closeables / SQLite cursors / activities are logged
 * (`adb logcat -s StrictMode`). Logging only, never a crash: the debug build is what the owner tests,
 * and a penalty-death would turn a slow read into a lost scan. Release builds never call [install]
 * with true (BuildConfig.DEBUG is false, and R8 removes the branch).
 */
object DebugStrictMode {
    /** Installs the policies when [debug]; returns whether it did. */
    fun install(debug: Boolean): Boolean {
        if (!debug) return false
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectDiskReads()
                .detectDiskWrites()
                .detectNetwork()
                .detectCustomSlowCalls()
                .penaltyLog()
                .build(),
        )
        StrictMode.setVmPolicy(
            StrictMode.VmPolicy.Builder()
                .detectLeakedClosableObjects()
                .detectLeakedSqlLiteObjects()
                .detectActivityLeaks()
                .detectLeakedRegistrationObjects()
                .penaltyLog()
                .build(),
        )
        return true
    }
}
