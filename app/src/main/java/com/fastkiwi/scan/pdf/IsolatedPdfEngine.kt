// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.pdf

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.os.RemoteException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume

/** How the main process reaches the PDF worker. */
fun interface PdfChannel {
    /** Runs one [PdfProtocol] request; returns its reply bundle or throws [PdfOpException]. */
    suspend fun call(request: Bundle): Bundle
}

/**
 * The main-process side of the `:pdf` process: binds [PdfService], sends one request, waits at
 * most [timeoutMs] (60 s), and turns a hang, a crash or a cancel into an error instead of
 * taking the app down. On timeout or cancel the worker process is killed, so a runaway parse
 * stops using the CPU at once.
 */
class IsolatedPdfEngine(
    private val ctx: Context,
    private val timeoutMs: Long = TIMEOUT_MS,
    private val component: ComponentName = ComponentName(ctx, PdfService::class.java),
) : PdfChannel {

    override suspend fun call(request: Bundle): Bundle {
        val app = ctx.applicationContext ?: ctx
        var connection: ServiceConnection? = null
        val replies = HandlerThread("pdf-reply").apply { start() }
        try {
            val reply = withTimeout(timeoutMs) {
                suspendCancellableCoroutine<Bundle> { cont ->
                    val replyHandler = object : Handler(replies.looper) {
                        override fun handleMessage(msg: Message) {
                            if (msg.what == PdfProtocol.MSG_RESULT && cont.isActive) cont.resume(Bundle(msg.data))
                        }
                    }
                    val conn = object : ServiceConnection {
                        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                            if (binder == null) { fail(PdfError.CRASHED); return }
                            try {
                                binder.linkToDeath({ fail(PdfError.CRASHED) }, 0)
                                Messenger(binder).send(Message.obtain(null, PdfProtocol.MSG_RUN).apply {
                                    data = request
                                    replyTo = Messenger(replyHandler)
                                })
                            } catch (e: RemoteException) {
                                fail(PdfError.CRASHED)
                            }
                        }

                        override fun onServiceDisconnected(name: ComponentName?) = fail(PdfError.CRASHED)
                        override fun onBindingDied(name: ComponentName?) = fail(PdfError.CRASHED)
                        override fun onNullBinding(name: ComponentName?) = fail(PdfError.CRASHED)

                        fun fail(e: PdfError) {
                            if (cont.isActive) cont.resume(Bundle().apply { putInt(PdfProtocol.K_ERR, e.ordinal) })
                        }
                    }
                    connection = conn
                    val bound = app.bindService(Intent().setComponent(component), conn, Context.BIND_AUTO_CREATE)
                    if (!bound) conn.fail(PdfError.CRASHED)
                }
            }
            if (!reply.getBoolean(PdfProtocol.K_OK)) throw PdfOpException(PdfError.of(reply.getInt(PdfProtocol.K_ERR, PdfError.MALFORMED.ordinal)))
            return reply
        } catch (e: TimeoutCancellationException) {
            killWorker(app)
            throw PdfOpException(PdfError.TIMEOUT, e)
        } catch (e: CancellationException) {
            killWorker(app)
            throw e
        } finally {
            connection?.let { runCatching { app.unbindService(it) } }
            replies.quitSafely()
        }
    }

    /** Ends the `:pdf` process (same app, same user: allowed). */
    private fun killWorker(app: Context) {
        val am = app.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return
        val name = app.packageName + PROCESS_SUFFIX
        am.runningAppProcesses?.filter { it.processName == name && it.pid != Process.myPid() }?.forEach { Process.killProcess(it.pid) }
    }

    companion object {
        const val TIMEOUT_MS = 60_000L
        const val PROCESS_SUFFIX = ":pdf"
    }
}

/** Runs requests in this process. Tests only: the app always goes through [IsolatedPdfEngine]. */
class InProcessChannel(private val ops: PdfBoxOps) : PdfChannel {
    override suspend fun call(request: Bundle): Bundle {
        val reply = PdfProtocol.handle(ops, request)
        if (!reply.getBoolean(PdfProtocol.K_OK)) throw PdfOpException(PdfError.of(reply.getInt(PdfProtocol.K_ERR, PdfError.MALFORMED.ordinal)))
        return reply
    }
}
