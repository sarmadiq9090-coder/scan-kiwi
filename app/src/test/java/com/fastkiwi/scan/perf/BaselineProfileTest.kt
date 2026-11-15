// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.perf

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Keeps `app/src/main/baseline-prof.txt` current (ROADMAP 1.7.0 item 3): every rule names a class or
 * package that exists, and every screen of 2.0 and the cold-start path has a rule.
 */
class BaselineProfileTest {
    private val profile = File("src/main/baseline-prof.txt")

    private fun rules(): List<String> = profile.readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }

    /** `HSPLcom/x/Foo**;->**(**)**` or `Lcom/x/Foo;` → `com/x/Foo` (wildcards cut). */
    private fun classPattern(rule: String): String {
        val body = rule.dropWhile { it in "HSP" }.removePrefix("L")
        return body.substringBefore(';').substringBefore("**").substringBefore('*')
    }

    private fun exists(pattern: String): Boolean {
        if (pattern.endsWith("/")) return File("src/main/java/$pattern").isDirectory
        val name = pattern.replace('/', '.')
        return runCatching { Class.forName(name, false, javaClass.classLoader) }.isSuccess
    }

    @Test
    fun everyRuleNamesAnExistingClass() {
        assertTrue("profile exists", profile.isFile)
        // ART profile syntax: flags, then the class descriptor starting with L (AGP rejects anything else).
        val syntax = Regex("^[HSP]*L[A-Za-z0-9_/$*?]+;(->[A-Za-z0-9_<>$*?]+\\([^)]*\\).+)?$")
        val malformed = rules().filterNot { syntax.matches(it) }
        assertTrue("malformed rules: $malformed", malformed.isEmpty())
        val bad = rules().map { it to classPattern(it) }.filterNot { exists(it.second) }
        assertTrue("rules for classes that no longer exist: $bad", bad.isEmpty())
    }

    @Test
    fun every20ScreenAndTheStartPathHasARule() {
        val patterns = rules().map(::classPattern).toSet()
        val needed = listOf(
            // cold start to the first Home frame
            "com/fastkiwi/scan/MainActivity", "com/fastkiwi/scan/ui/AppViewModel", "com/fastkiwi/scan/ui/HomeScreenKt",
            "com/fastkiwi/scan/data/DocumentStore", "com/fastkiwi/scan/data/FolderStore", "com/fastkiwi/scan/search/SearchIndex",
            "com/fastkiwi/scan/ui/theme/", "com/fastkiwi/scan/ui/KiwiComponentsKt",
            // every 2.0 screen
            "com/fastkiwi/scan/ui/CameraScreenKt", "com/fastkiwi/scan/ui/BookSplitScreenKt", "com/fastkiwi/scan/ui/EditorScreenKt",
            "com/fastkiwi/scan/ui/PageScreenKt", "com/fastkiwi/scan/ui/CropScreenKt", "com/fastkiwi/scan/ui/PdfToolsScreenKt",
            "com/fastkiwi/scan/ui/SearchScreenKt", "com/fastkiwi/scan/ui/TextScreenKt", "com/fastkiwi/scan/ui/SettingsScreenKt",
            "com/fastkiwi/scan/ui/QualityUiKt",
        )
        val missing = needed.filter { it !in patterns }
        assertTrue("screens without a profile rule: $missing", missing.isEmpty())
        // Every screen file in ui/ is covered, so a new screen cannot be forgotten.
        val screens = File("src/main/java/com/fastkiwi/scan/ui").listFiles()!!.map { it.name }.filter { it.endsWith("Screen.kt") }
        val uncovered = screens.map { "com/fastkiwi/scan/ui/" + it.removeSuffix(".kt") + "Kt" }.filter { it !in patterns }
        assertTrue("screen files without a rule: $uncovered", uncovered.isEmpty())
    }
}
