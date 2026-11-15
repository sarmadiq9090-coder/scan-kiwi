// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.ocr

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs the blocking native [block] on the current thread. If the calling coroutine is cancelled
 * meanwhile, [stop] is called once from another thread so the block can return early. A cancelled
 * caller then gets CancellationException (the block's result is dropped).
 */
suspend fun <T> runStoppable(stop: () -> Unit, block: () -> T): T {
    val finished = AtomicBoolean(false)
    val value = coroutineScope {
        // UNDISPATCHED: the try/finally is in place before block() starts, even if already cancelled.
        val watcher = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                if (!finished.get()) runCatching(stop)
            }
        }
        try {
            block()
        } finally {
            finished.set(true)
            watcher.cancel()
        }
    }
    currentCoroutineContext().ensureActive()
    return value
}
