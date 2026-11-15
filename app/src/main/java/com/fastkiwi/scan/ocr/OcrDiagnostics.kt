// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.ocr

import android.content.Context

/**
 * Local-only OCR diagnostics for Settings → About (roadmap 1.5 item 7): the time of the last pages and
 * the peak native heap while reading, so the owner can screenshot phone numbers. Stored in the app's
 * private preferences; no text, no logging, nothing leaves the phone.
 */
class OcrDiagnostics(ctx: Context) {
    private val p = ctx.getSharedPreferences("ocr_diagnostics", Context.MODE_PRIVATE)

    data class Snapshot(
        val lastMillis: Long,
        /** Up to the last 5 page times, newest first. */
        val recentMillis: List<Long>,
        val peakNativeHeapBytes: Long,
        val effective: OcrMode?,
        val fallback: FallbackReason?,
    ) {
        val hasData: Boolean get() = lastMillis > 0
    }

    fun record(sel: OcrSelection, millis: Long, peakHeapBytes: Long) {
        val recent = (listOf(millis) + snapshot().recentMillis).take(5)
        p.edit()
            .putLong("last_ms", millis)
            .putString("recent_ms", recent.joinToString(","))
            .putLong("peak_heap", maxOf(peakHeapBytes, 0L))
            .putString("effective", sel.effective.name)
            .putString("fallback", sel.fallback?.name ?: "")
            .apply()
    }

    fun snapshot() = Snapshot(
        p.getLong("last_ms", 0L),
        p.getString("recent_ms", "")!!.split(",").mapNotNull { it.toLongOrNull() },
        p.getLong("peak_heap", 0L),
        p.getString("effective", null)?.let { n -> OcrMode.entries.firstOrNull { it.name == n } },
        p.getString("fallback", null)?.let { n -> FallbackReason.entries.firstOrNull { it.name == n } },
    )
}
