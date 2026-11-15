// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.pdf

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.os.RemoteException
import java.io.File

/**
 * The isolated `:pdf` process (manifest: `android:process=":pdf"`, not exported). PdfBox-Android
 * parses files from other apps here, so a hostile or broken PDF (deep recursion, huge objects,
 * endless loops) can at worst crash or hang this process; the main process keeps running,
 * kills this one after the timeout ([IsolatedPdfEngine]) and shows an error.
 *
 * Protocol: one [Message] per request ([PdfProtocol]); the reply goes to [Message.replyTo].
 * Paths are files in the app's own storage (same UID), never content URIs.
 */
class PdfService : Service() {

    private lateinit var messenger: Messenger
    private val ops by lazy { PdfBoxOps(this) }

    private val inbox = HandlerThread("pdf-inbox")

    override fun onCreate() {
        super.onCreate()
        inbox.start()
        val handler = object : Handler(inbox.looper) {
            override fun handleMessage(msg: Message) {
                if (msg.what != PdfProtocol.MSG_RUN) return
                val replyTo = msg.replyTo ?: return
                val req = msg.data ?: Bundle()
                // A deep stack for deeply nested objects; each request on its own thread so the
                // binder thread stays free.
                Thread(null, { run(req, replyTo) }, "pdf-op", 64L * 1024 * 1024).start()
            }
        }
        messenger = Messenger(handler)
    }

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onDestroy() {
        inbox.quitSafely()
        super.onDestroy()
    }

    private fun run(req: Bundle, replyTo: Messenger) {
        val reply = PdfProtocol.handle(ops, req)
        try {
            replyTo.send(Message.obtain(null, PdfProtocol.MSG_RESULT).apply { data = reply })
        } catch (_: RemoteException) {
            // The caller is gone (timeout or cancel); nothing to report.
        }
        // After an Error (stack overflow, out of memory) the parser's state is suspect:
        // end this process; the next request starts a fresh one.
        if (reply.getBoolean(PdfProtocol.K_FATAL)) Handler(Looper.getMainLooper()).postDelayed({ Process.killProcess(Process.myPid()) }, 200)
    }
}

/** Request/response encoding shared by [PdfService] and [IsolatedPdfEngine]. */
object PdfProtocol {
    const val MSG_RUN = 1
    const val MSG_RESULT = 2

    const val K_OP = "op"
    const val K_IN = "in"
    const val K_PW = "pw"
    const val K_PLAN = "plan"
    const val K_OUT = "out"
    const val K_OK = "ok"
    const val K_ERR = "err"
    const val K_PAGES = "pages"
    const val K_TEXT = "text"
    const val K_FATAL = "fatal"

    const val OP_COUNT = "count"
    const val OP_ASSEMBLE = "assemble"
    const val OP_TEXT = "text"
    const val OP_UNLOCK = "unlock"

    fun request(op: String, inputs: List<File>, passwords: List<String?> = emptyList(), plan: List<PageRef> = emptyList(), out: File? = null): Bundle =
        Bundle().apply {
            putString(K_OP, op)
            putStringArray(K_IN, inputs.map { it.absolutePath }.toTypedArray())
            putStringArray(K_PW, Array(inputs.size) { passwords.getOrNull(it) })
            putIntArray(K_PLAN, IntArray(plan.size * 3).also { a -> plan.forEachIndexed { i, r -> a[i * 3] = r.source; a[i * 3 + 1] = r.index; a[i * 3 + 2] = r.rotate } })
            if (out != null) putString(K_OUT, out.absolutePath)
        }

    fun plan(b: Bundle): List<PageRef> {
        val a = b.getIntArray(K_PLAN) ?: return emptyList()
        return List(a.size / 3) { PageRef(a[it * 3], a[it * 3 + 1], a[it * 3 + 2]) }
    }

    /** Runs one request and never throws: every failure becomes an error code. */
    fun handle(ops: PdfBoxOps, req: Bundle): Bundle {
        val out = Bundle()
        try {
            val inputs = req.getStringArray(K_IN)?.map { File(it) } ?: emptyList()
            val pws = req.getStringArray(K_PW)?.toList() ?: emptyList()
            when (req.getString(K_OP)) {
                OP_COUNT -> out.putInt(K_PAGES, ops.pageCount(inputs.single(), pws.firstOrNull()))
                OP_ASSEMBLE -> out.putInt(K_PAGES, ops.assemble(inputs, pws, plan(req), File(req.getString(K_OUT)!!)))
                OP_TEXT -> out.putStringArray(K_TEXT, ops.text(inputs.single(), pws.firstOrNull()).toTypedArray())
                OP_UNLOCK -> out.putInt(K_PAGES, ops.unlock(inputs.single(), pws.firstOrNull() ?: "", File(req.getString(K_OUT)!!)))
                else -> throw IllegalArgumentException("unknown op")
            }
            out.putBoolean(K_OK, true)
        } catch (e: PdfOpException) {
            out.putInt(K_ERR, e.error.ordinal)
        } catch (e: Exception) {
            val noSpace = generateSequence<Throwable>(e) { it.cause }.any { it.message?.contains("ENOSPC") == true || it.message?.contains("No space") == true }
            out.putInt(K_ERR, (if (noSpace) PdfError.NO_SPACE else PdfError.MALFORMED).ordinal)
        } catch (e: Throwable) {
            // StackOverflowError, OutOfMemoryError, NoClassDefFoundError...
            out.putInt(K_ERR, (if (e is NoClassDefFoundError) PdfError.UNSUPPORTED else PdfError.MALFORMED).ordinal)
            out.putBoolean(K_FATAL, e !is NoClassDefFoundError)
        }
        if (!out.getBoolean(K_OK)) req.getString(K_OUT)?.let { File(it).delete() }
        return out
    }
}
