// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.feedback

import java.net.URLEncoder

/** What the user wants to tell us. */
enum class FeedbackKind(val arabic: String, val english: String, val template: String) {
    PROBLEM("مشكلة", "problem", "bug_report.yml"),
    SUGGESTION("اقتراح", "suggestion", "feature_request.yml"),
}

/**
 * The only technical details a report may carry, and only when the user ticks
 * «أرفق معلومات الجهاز». No identifiers, no documents, no file names, no logs.
 */
data class DeviceInfo(
    val appVersion: String,
    val versionCode: Int,
    val android: String,
    val model: String,
    val language: String,
    val errorCode: String? = null,
    val errorClass: String? = null,
)

/**
 * Builds the e-mail and GitHub-issue hand-offs for "report a problem or a
 * suggestion". The app never sends this itself (its only connection is the GitHub update check): it only opens the user's
 * own mail app or browser with this text, which the user sees and sends.
 * Pure Kotlin so the encoding and limits are unit-tested.
 */
object Feedback {
    const val EMAIL = "support@fastkiwi.app"
    const val REPO = "https://github.com/sarmadiq9090-coder/scan-kiwi"

    /** GitHub rejects very long URLs (~8 KB); stay well below. */
    const val MAX_URL = 7000

    /** Longest text the form accepts (the counter shows it). */
    const val MAX_TEXT = 4000

    /**
     * Applies the [MAX_TEXT] limit to typed or pasted text: a longer paste is cut
     * (it used to be ignored completely), never in the middle of a surrogate pair.
     */
    fun clipInput(s: String): String {
        if (s.length <= MAX_TEXT) return s
        val end = if (Character.isHighSurrogate(s[MAX_TEXT - 1])) MAX_TEXT - 1 else MAX_TEXT
        return s.substring(0, end)
    }

    fun subject(kind: FeedbackKind, appVersion: String) = "[Scan Kiwi $appVersion] ${kind.arabic}"

    /** Exactly the lines attached when the box is ticked (also shown on screen). */
    fun deviceLines(info: DeviceInfo): String = buildString {
        append("App: Scan Kiwi ").append(info.appVersion).append(" (").append(info.versionCode).append(")\n")
        append("Android: ").append(info.android).append('\n')
        append("Device: ").append(info.model).append('\n')
        append("Language: ").append(info.language)
        if (info.errorCode != null) append("\nError code: ").append(info.errorCode)
        if (info.errorClass != null) append("\nError type: ").append(info.errorClass)
    }

    fun body(text: String, info: DeviceInfo?): String {
        val t = text.trim()
        return if (info == null) t else "$t\n\n---\n${deviceLines(info)}"
    }

    /** RFC 3986 / RFC 6068 percent-encoding (UTF-8, spaces as %20). */
    fun encode(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    fun mailto(kind: FeedbackKind, appVersion: String, text: String, info: DeviceInfo?): String =
        "mailto:$EMAIL?subject=${encode(subject(kind, appVersion))}&body=${encode(body(text, info))}"

    /**
     * New-issue URL that pre-fills the issue form (fields `description` and
     * `device` of our templates). The user's text is shortened, never the
     * structure, so the URL always stays under [MAX_URL].
     */
    fun githubIssue(kind: FeedbackKind, appVersion: String, text: String, info: DeviceInfo?): String {
        val base = "$REPO/issues/new?template=${kind.template}&title=${encode(subject(kind, appVersion))}"
        val device = info?.let { "&device=${encode(deviceLines(it))}" } ?: ""
        var t = text.trim()
        while (true) {
            val url = "$base&description=${encode(t)}$device"
            if (url.length <= MAX_URL || t.isEmpty()) return url
            // Cut by characters (not bytes) so a surrogate pair is never split. The text
            // always gets shorter (cut + "…" < length), so the loop ends.
            var cut = minOf((t.length * 0.9).toInt(), t.length - 2)
            if (cut > 0 && Character.isHighSurrogate(t[cut - 1])) cut--
            t = if (cut <= 0) "" else t.substring(0, cut).trimEnd() + "…"
            if (t == "…") t = ""
        }
    }
}
