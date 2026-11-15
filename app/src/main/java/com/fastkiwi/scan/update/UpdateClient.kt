// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.update

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * 2.0-update (owner's order 2026-11-15, replaces D-077's "no network"): the app's ONLY network code.
 * Plain [HttpURLConnection] (no network library): HTTPS only, GitHub hosts only (every redirect is
 * checked against [allowedHosts] before it is followed), no cookies, no device identifier, a generic
 * User-Agent `ScanKiwi/<version>`. Certificates are checked by the system's default trust store
 * (`res/xml/network_security_config.xml` limits cleartext and trust to the same GitHub domains).
 */
class UpdateClient(
    private val userAgent: String,
    val latestUrl: String = LATEST_URL,
    private val allowedHosts: Set<String> = GITHUB_HOSTS,
    /** Tests only: a local mock server over plain HTTP. The app always passes true. */
    private val requireHttps: Boolean = true,
    private val timeoutMs: Int = 20_000,
) {
    class NetworkException(message: String, cause: Throwable? = null) : IOException(message, cause)

    /** GET a small text resource (release JSON, SHA256SUMS). */
    fun getText(url: String, maxBytes: Int = 1 shl 20): String = open(url, accept = "application/vnd.github+json").use { c ->
        // A connection that dies while the body is read (timeout, reset, TLS) is a network error too.
        val bytes = try { c.stream.readBounded(maxBytes) } catch (e: NetworkException) { throw e } catch (e: IOException) { throw NetworkException("read", e) }
        String(bytes, Charsets.UTF_8)
    }

    /**
     * Streams [url] into [dest], returning the file's sha256 (lower-case hex). [maxBytes] guards against
     * a runaway download; [onProgress] gets (bytes so far, total or -1); [keepGoing] is asked before each
     * chunk (false stops with a [NetworkException] [STOPPED], e.g. Wi-Fi lost with «Wi-Fi فقط»). Errors while
     * reading are [NetworkException]; errors while writing [dest] stay plain [IOException] (storage).
     */
    fun download(url: String, dest: File, maxBytes: Long, keepGoing: () -> Boolean = { true }, onProgress: (Long, Long) -> Unit = { _, _ -> }): String {
        val md = MessageDigest.getInstance("SHA-256")
        open(url, accept = "application/octet-stream").use { c ->
            val total = c.conn.contentLengthLong
            if (total > maxBytes) throw NetworkException("too large: $total")
            dest.parentFile?.mkdirs()
            dest.outputStream().buffered().use { out ->
                val buf = ByteArray(64 * 1024)
                var n = 0L
                while (true) {
                    if (Thread.currentThread().isInterrupted) throw NetworkException("cancelled")
                    if (!keepGoing()) throw NetworkException(STOPPED)
                    val r = try { c.stream.read(buf) } catch (e: IOException) { throw NetworkException("read", e) }
                    if (r < 0) break
                    n += r
                    if (n > maxBytes) throw NetworkException("too large")
                    md.update(buf, 0, r)
                    out.write(buf, 0, r)
                    onProgress(n, total)
                }
                if (total >= 0 && n != total) throw NetworkException("short download: $n of $total")
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private class Open(val conn: HttpURLConnection, val stream: InputStream) : AutoCloseable {
        override fun close() {
            runCatching { stream.close() }
            conn.disconnect()
        }
    }

    /** Opens [start], following at most [MAX_REDIRECTS] redirects, each one checked like the first URL. */
    private fun open(start: String, accept: String): Open {
        var url = checked(start)
        repeat(MAX_REDIRECTS + 1) {
            val c = try {
                (url.openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    useCaches = false
                    connectTimeout = timeoutMs
                    readTimeout = timeoutMs
                    setRequestProperty("User-Agent", userAgent)
                    setRequestProperty("Accept", accept)
                }
            } catch (e: IOException) {
                throw NetworkException("cannot open", e)
            }
            val code = try { c.responseCode } catch (e: IOException) { c.disconnect(); throw NetworkException("no connection", e) }
            when (code) {
                in 200..299 -> return Open(c, try { c.inputStream } catch (e: IOException) { c.disconnect(); throw NetworkException("read", e) })
                301, 302, 303, 307, 308 -> {
                    val loc = c.getHeaderField("Location")
                    c.disconnect()
                    if (loc.isNullOrBlank()) throw NetworkException("redirect without a location")
                    url = checked(URL(url, loc).toString())
                }
                else -> { c.disconnect(); throw NetworkException("HTTP $code") }
            }
        }
        throw NetworkException("too many redirects")
    }

    /** Refuses anything that is not HTTPS to an allowed GitHub host. */
    internal fun checked(s: String): URL {
        val u = try { URL(s) } catch (e: Exception) { throw NetworkException("bad url") }
        if (requireHttps && u.protocol != "https") throw NetworkException("not https")
        if (!requireHttps && u.protocol != "https" && u.protocol != "http") throw NetworkException("bad scheme")
        if (u.host.lowercase() !in allowedHosts) throw NetworkException("host not allowed")
        if (u.userInfo != null) throw NetworkException("credentials in url")
        return u
    }

    companion object {
        /** The project's own repository (`git remote -v`): the only update source. */
        const val REPO = "sarmadiq9090-coder/scan-kiwi"
        const val LATEST_URL = "https://api.github.com/repos/$REPO/releases/latest"

        /** The same list as `res/xml/network_security_config.xml`. */
        val GITHUB_HOSTS = setOf("api.github.com", "github.com", "objects.githubusercontent.com", "release-assets.githubusercontent.com")
        const val MAX_REDIRECTS = 5
        /** [download] stopped because its keepGoing check said no. */
        const val STOPPED = "stopped"

        private fun InputStream.readBounded(max: Int): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            while (true) {
                val r = read(buf)
                if (r < 0) break
                if (out.size() + r > max) throw NetworkException("response too large")
                out.write(buf, 0, r)
            }
            return out.toByteArray()
        }
    }
}
