// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.perf

/**
 * The JVM's management beans through reflection: unit tests compile against android.jar, which has
 * no java.lang.management, but they run on a desktop JVM that does. Methods are looked up on the
 * public interfaces, so the JDK's module rules allow the calls.
 */
internal object Jmx {
    private val factory = Class.forName("java.lang.management.ManagementFactory")
    private val memoryBean = factory.getMethod("getMemoryMXBean").invoke(null)
    private val osBean = factory.getMethod("getOperatingSystemMXBean").invoke(null)
    private val threadBean = factory.getMethod("getThreadMXBean").invoke(null)
    private val memoryIf = Class.forName("java.lang.management.MemoryMXBean")
    private val usageCls = Class.forName("java.lang.management.MemoryUsage")
    private val sunOs = runCatching { Class.forName("com.sun.management.OperatingSystemMXBean") }.getOrNull()
    private val sunThreads = runCatching { Class.forName("com.sun.management.ThreadMXBean") }.getOrNull()

    private fun usage(name: String, field: String): Long =
        usageCls.getMethod(field).invoke(memoryIf.getMethod(name).invoke(memoryBean)) as Long

    fun heapUsed(): Long = usage("getHeapMemoryUsage", "getUsed")
    fun heapCommitted(): Long = usage("getHeapMemoryUsage", "getCommitted")
    fun nonHeapCommitted(): Long = usage("getNonHeapMemoryUsage", "getCommitted")

    /** Windows: the process commit charge (private bytes). Linux: virtual size (not used there). */
    fun committedVirtual(): Long? = sunOs?.takeIf { it.isInstance(osBean) }?.getMethod("getCommittedVirtualMemorySize")?.invoke(osBean) as Long?

    /** The test JVM's command-line options (-XX:..., -Xmx...). */
    @Suppress("UNCHECKED_CAST")
    fun inputArguments(): List<String> {
        val bean = factory.getMethod("getRuntimeMXBean").invoke(null)
        return Class.forName("java.lang.management.RuntimeMXBean").getMethod("getInputArguments").invoke(bean) as List<String>
    }

    /** Bytes allocated by all threads so far (JDK 21+), or by this thread. */
    fun allocatedBytes(): Long {
        val c = sunThreads?.takeIf { it.isInstance(threadBean) } ?: return 0L
        val total = runCatching { c.getMethod("getTotalThreadAllocatedBytes").invoke(threadBean) as Long }.getOrNull()
        if (total != null && total >= 0) return total
        return c.getMethod("getThreadAllocatedBytes", Long::class.javaPrimitiveType).invoke(threadBean, Thread.currentThread().id) as Long
    }

    /** Bytes allocated by this thread so far. */
    fun threadAllocatedBytes(): Long {
        val c = sunThreads ?: return 0L
        return c.getMethod("getThreadAllocatedBytes", Long::class.javaPrimitiveType).invoke(threadBean, Thread.currentThread().id) as Long
    }
}
