// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.update

/**
 * 2.0-update: Semantic Versioning 2.0.0 precedence (semver.org §11). A leading "v" (GitHub tags) is
 * accepted; build metadata ("+...") is ignored; a pre-release ("-beta.1") is lower than its release.
 */
data class SemVer(val major: Int, val minor: Int, val patch: Int, val pre: List<String> = emptyList()) : Comparable<SemVer> {

    override fun compareTo(other: SemVer): Int {
        compareValues(major, other.major).let { if (it != 0) return it }
        compareValues(minor, other.minor).let { if (it != 0) return it }
        compareValues(patch, other.patch).let { if (it != 0) return it }
        if (pre.isEmpty() || other.pre.isEmpty()) return compareValues(other.pre.size.coerceAtMost(1), pre.size.coerceAtMost(1))
        for (i in 0 until minOf(pre.size, other.pre.size)) {
            val a = pre[i]; val b = other.pre[i]
            val an = a.toLongOrNull(); val bn = b.toLongOrNull()
            val c = when {
                an != null && bn != null -> an.compareTo(bn)
                an != null -> -1 // numeric identifiers are lower than alphanumeric ones
                bn != null -> 1
                else -> a.compareTo(b)
            }
            if (c != 0) return c
        }
        return pre.size.compareTo(other.pre.size)
    }

    override fun toString() = "$major.$minor.$patch" + if (pre.isEmpty()) "" else "-" + pre.joinToString(".")

    companion object {
        private val RE = Regex("""^[vV]?(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?(?:\+[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?$""")

        /** Null for anything that is not a SemVer version (an update with an odd tag is never offered). */
        fun parse(s: String): SemVer? {
            val m = RE.matchEntire(s.trim()) ?: return null
            val (a, b, c, p) = m.destructured
            return runCatching { SemVer(a.toInt(), b.toInt(), c.toInt(), if (p.isEmpty()) emptyList() else p.split('.')) }.getOrNull()
        }
    }
}
