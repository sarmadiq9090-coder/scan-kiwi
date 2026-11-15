// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.update

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * A tiny local HTTP/1.1 server for the update tests (JDK's com.sun.net.httpserver is not on the Android
 * test classpath). One request per connection; [handler] gets the path and the headers and returns
 * (status, extra headers, body). Binds to 127.0.0.1 only.
 */
class MiniHttpServer(private val handler: (path: String, headers: Map<String, String>) -> Triple<Int, Map<String, String>, ByteArray>) : AutoCloseable {
    private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    val port: Int get() = socket.localPort
    @Volatile private var open = true

    init {
        thread(isDaemon = true, name = "mini-http") {
            while (open) {
                val s = runCatching { socket.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { runCatching { serve(s) }; runCatching { s.close() } }
            }
        }
    }

    private fun serve(s: Socket) {
        val input = s.getInputStream().bufferedReader(Charsets.ISO_8859_1)
        val requestLine = input.readLine() ?: return
        val headers = HashMap<String, String>()
        while (true) {
            val line = input.readLine() ?: break
            if (line.isEmpty()) break
            val i = line.indexOf(':')
            if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
        }
        val path = requestLine.split(' ').getOrElse(1) { "/" }.substringBefore('?')
        val (code, extra, body) = handler(path, headers)
        val out = s.getOutputStream()
        val head = StringBuilder("HTTP/1.1 $code X\r\nContent-Length: ${body.size}\r\nConnection: close\r\n")
        extra.forEach { (k, v) -> head.append("$k: $v\r\n") }
        head.append("\r\n")
        out.write(head.toString().toByteArray(Charsets.ISO_8859_1))
        out.write(body)
        out.flush()
    }

    override fun close() {
        open = false
        runCatching { socket.close() }
    }
}
