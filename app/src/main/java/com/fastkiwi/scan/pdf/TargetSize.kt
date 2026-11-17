// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.pdf

/** How pages are encoded: long side in pixels and JPEG quality; black-and-white pages use [bitonalLongSide]. */
data class EncodeSettings(val longSide: Int, val jpegQuality: Int, val bitonalLongSide: Int)

/**
 * PDF-06 / PRD §6.2 "Custom target size": find the best-looking settings whose file fits.
 *
 * The [LADDER] goes from best to smallest, alternating a step down in JPEG quality and in
 * resolution so that neither drops too early (text stays sharp at 150–200 dpi, then quality
 * gives way). [choose] binary-searches it with size estimates from sample pages; after a full
 * encode, [afterMiss] picks the next rung from the real size. The caller decides success only
 * from the real file size, never from an estimate.
 */
object TargetSize {

    val LADDER: List<EncodeSettings> = listOf(
        EncodeSettings(3200, 90, 3200),
        EncodeSettings(3200, 82, 3200),
        EncodeSettings(2800, 80, 3200),
        EncodeSettings(2400, 78, 2800),
        EncodeSettings(2400, 70, 2800),
        EncodeSettings(2000, 70, 2400),
        EncodeSettings(2000, 62, 2400),
        EncodeSettings(1700, 60, 2200),
        EncodeSettings(1700, 52, 2000),
        EncodeSettings(1400, 52, 1800),
        EncodeSettings(1400, 45, 1600),
        EncodeSettings(1200, 45, 1500),
        EncodeSettings(1200, 38, 1400),
        EncodeSettings(1000, 38, 1200),
        EncodeSettings(1000, 32, 1100),
        EncodeSettings(850, 30, 1000),
        EncodeSettings(700, 28, 850),
        EncodeSettings(600, 25, 700),
    )

    /** Margin for the estimate (headers, text layer, page-to-page variation). */
    const val SAFETY = 1.04

    /** At most this many full encodes in one export (the first one included). */
    const val MAX_PASSES = 4

    /**
     * Index of the best rung whose [estimate] fits [target] (estimates fall along the ladder);
     * the last rung when none fits.
     */
    suspend fun choose(target: Long, estimate: suspend (Int) -> Long): Int {
        var lo = 0
        var hi = LADDER.lastIndex
        if (estimate(hi) * SAFETY > target) return hi
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (estimate(mid) * SAFETY <= target) hi = mid else lo = mid + 1
        }
        return lo
    }

    /**
     * After a full encode at [current] came out at [actual] > [target]: the next rung, using the
     * ratio between the real size and the estimate. Null when [current] is already the smallest.
     */
    suspend fun afterMiss(current: Int, actual: Long, target: Long, estimate: suspend (Int) -> Long): Int? {
        if (current >= LADDER.lastIndex) return null
        val ratio = actual.toDouble() / estimate(current).coerceAtLeast(1)
        for (j in current + 1..LADDER.lastIndex) {
            if (estimate(j) * ratio * SAFETY <= target) return j
        }
        return LADDER.lastIndex
    }

    /** The largest target the field accepts (10 TB): anything beyond is a typing slip. */
    private const val MAX_TARGET_BYTES = 10_000_000_000_000L

    /**
     * The target size typed in the export sheet, in bytes ([megabytes]: the unit chip), or null when it is
     * not a positive number. Arabic-Indic digits and separators are read as typed: "1,000", "1٬000",
     * "١٬٠٠٠" and "1 000" are one thousand; "1.5", "1,5" and "١٫٥" are one and a half. A comma followed by
     * exactly three digits (and more digits before it) is a thousands separator, any other comma a decimal point.
     */
    fun parseBytes(text: String, megabytes: Boolean): Long? {
        var t = PagePlans.normalizeDigits(text.replace("٬", ",").replace(" ", " ").replace(" ", " ")).trim()
        t = t.replace(" ", "")
        if (!Regex("""[0-9.,]+""").matches(t)) return null
        val dot = t.lastIndexOf('.')
        val comma = t.lastIndexOf(',')
        t = when {
            comma < 0 -> t
            dot >= 0 && comma < dot -> t.replace(",", "")                 // 1,250.5
            dot >= 0 -> t.replace(".", "").replace(',', '.')              // 1.250,5
            t.count { it == ',' } > 1 -> t.replace(",", "")               // 1,234,567
            t.length - comma - 1 == 3 && comma >= 1 -> t.replace(",", "") // 1,000
            else -> t.replace(',', '.')                                   // 1,5
        }
        val n = runCatching { java.math.BigDecimal(t) }.getOrNull() ?: return null
        if (n.signum() <= 0) return null
        val bytes = n.multiply(java.math.BigDecimal(if (megabytes) 1_000_000L else 1_000L))
        if (bytes > java.math.BigDecimal(MAX_TARGET_BYTES)) return null
        return bytes.toLong().takeIf { it > 0 }
    }
}
