// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.pdf

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 2.0.1 rejected every exported PDF on a phone with "bad xref subsection": PdfValidator used `\G`
 * with `Regex.find(input, start)`. The JVM anchors `\G` at the start index; Android's ICU engine
 * does not, so no JVM or Robolectric test could see it. This guard keeps `\G` out of app code.
 */
class AndroidRegexGuardTest {
    @Test
    fun noBackslashGInAppCode() {
        val root = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }
        val offenders = root.walkTopDown().filter { it.extension == "kt" || it.extension == "java" }
            .flatMap { f -> f.readLines().mapIndexedNotNull { n, l -> if (l.contains("\\\\G") || l.contains("\\G(")) "${f.name}:${n + 1}" else null } }
            .toList()
        // Comments mentioning the problem are fine; a regex literal is not.
        val real = offenders.filter { loc ->
            val (name, line) = loc.split(":")
            val text = root.walkTopDown().first { it.name == name }.readLines()[line.toInt() - 1].trim()
            !text.startsWith("//") && !text.startsWith("*")
        }
        assertTrue("\\G does not work with find(start) on Android (ICU): $real", real.isEmpty())
    }

    @Test
    fun validatorReadsTheWritersXref() {
        val f = File.createTempFile("xref", ".pdf")
        try {
            val body = StringBuilder("%PDF-1.4\n")
            val o1 = body.length; body.append("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")
            val o2 = body.length; body.append("2 0 obj\n<< /Type /Pages /Kids [] /Count 0 >>\nendobj\n")
            val x = body.length
            body.append("xref\n0 3\n0000000000 65535 f \n")
            body.append(String.format("%010d 00000 n \n%010d 00000 n \n", o1, o2))
            body.append("trailer\n<< /Size 3 /Root 1 0 R >>\nstartxref\n$x\n%%EOF\n")
            f.writeBytes(body.toString().toByteArray(Charsets.ISO_8859_1))
            assertTrue(PdfValidator.validate(f) == 0)
        } finally {
            f.delete()
        }
    }
}
