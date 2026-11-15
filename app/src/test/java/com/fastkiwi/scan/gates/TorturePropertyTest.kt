// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.gates

import com.fastkiwi.scan.core.OcrWord
import com.fastkiwi.scan.data.DocumentStore
import com.fastkiwi.scan.data.FolderStore
import com.fastkiwi.scan.data.TextOverlay
import com.fastkiwi.scan.pdf.TargetSize
import com.fastkiwi.scan.update.SemVer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.random.Random

/**
 * Property-based torture (seeded, thousands of cases, no framework): the text-correction overlay, the typed target
 * size, the folder list under damage, and SemVer parsing. A failing property prints its seed and case.
 */
@Category(TortureSuite::class)
class TorturePropertyTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Before
    fun gate() = Gates.assumeTorture()

    private val vocab = listOf("a", "bb", "ccc", "المبلغ", "١٢٠٠", "x1", "Total:", "شكرا", "e", "f", "ff", "٣", "ط", "الفاتورة", "USD")

    private fun words(r: Random, n: Int) = List(n) { vocab[r.nextInt(vocab.size)] }

    private fun ocr(t: List<String>) = t.mapIndexed { i, s -> OcrWord(s, i * 10, 0, i * 10 + 8, 10) }

    private fun mutate(r: Random, base: List<String>): List<String> {
        val out = base.toMutableList()
        repeat(r.nextInt(0, 5)) {
            when (r.nextInt(3)) {
                0 -> if (out.isNotEmpty()) out.removeAt(r.nextInt(out.size))
                1 -> out.add(r.nextInt(out.size + 1), vocab[r.nextInt(vocab.size)])
                else -> if (out.isNotEmpty()) out[r.nextInt(out.size)] = vocab[r.nextInt(vocab.size)] + "z"
            }
        }
        return out
    }

    @Test
    fun overlayRoundTripAndRebaseKeepTheCorrectedText() {
        var cases = 0
        for (seed in 1..3000) {
            val r = Random(seed)
            val base = words(r, r.nextInt(1, 40))
            val edited = mutate(r, base)
            val ov = TextOverlay(0, base, TextOverlay.diff(base, edited.joinToString(" ")))
            // Applying the diff yields the typed text (token by token), whatever the edit.
            assertEquals("seed $seed", edited, ov.correctedTokens())
            assertEquals("seed $seed apply", edited, ov.apply(ocr(base)).flatMap { it.text.split(" ") })
            // A later re-read with different words: the corrected text survives.
            val reread = mutate(r, base).ifEmpty { listOf("q") }
            val moved = ov.rebasedOn(reread)
            if (moved != null) {
                assertEquals("seed $seed rebase", edited, moved.correctedTokens())
                assertTrue("seed $seed applies", moved.appliesTo(0, ocr(reread)))
            }
            cases++
        }
        println("TORTURE-PROPERTY overlay cases=$cases")
    }

    @Test
    fun typedTargetSizesAreReadInTheSelectedUnit() {
        val arabic = "٠١٢٣٤٥٦٧٨٩"
        var cases = 0
        for (seed in 1..4000) {
            val r = Random(seed)
            val n = r.nextLong(1, 5_000_000)
            val mb = r.nextBoolean()
            val plain = n.toString()
            val grouped = "%,d".format(java.util.Locale.US, n)
            val spaced = grouped.replace(",", " ")
            val form = listOf(plain, grouped, spaced)[r.nextInt(3)]
            val typed = if (r.nextBoolean()) form.map { c -> if (c.isDigit()) arabic[c - '0'] else c }.joinToString("") else form
            val unit = if (mb) 1_000_000L else 1_000L
            // 1-3 digit numbers and "x,yyy" are one number; the only ambiguity ("1,500" = 1500 or 1.5) is resolved as thousands.
            assertEquals("seed $seed <$typed>", n * unit, TargetSize.parseBytes(typed, mb))
            cases++
        }
        // Garbage never throws and never becomes a positive size by accident.
        for (seed in 1..4000) {
            val r = Random(seed)
            val junk = String(CharArray(r.nextInt(0, 14)) { "0123456789.,٬٫،- eE+ abx".random(r) })
            val v = TargetSize.parseBytes(junk, r.nextBoolean())
            assertTrue("seed $seed <$junk> -> $v", v == null || v > 0)
            cases++
        }
        println("TORTURE-PROPERTY target-size cases=$cases")
    }

    @Test
    fun foldersSurviveAnyDamageToTheMainFile() {
        var cases = 0
        for (seed in 1..300) {
            val r = Random(seed)
            val root = tmp.newFolder("f$seed")
            val folders = FolderStore(root)
            val store = DocumentStore(root)
            val live = ArrayList<String>()
            repeat(r.nextInt(1, 12)) {
                when {
                    live.isEmpty() || r.nextInt(3) > 0 -> live.add(folders.create(words(r, 2).joinToString(" ")).id)
                    r.nextBoolean() -> folders.rename(live[r.nextInt(live.size)], "n" + r.nextInt(1000))
                    else -> folders.delete(live.removeAt(r.nextInt(live.size)), store)
                }
            }
            val expected = folders.list()
            val main = File(root, "folders.json")
            if (main.exists()) {
                val bytes = main.readBytes()
                val broken = when (r.nextInt(4)) {
                    0 -> ByteArray(0)
                    1 -> bytes.copyOf(r.nextInt(0, maxOf(1, bytes.size)))
                    2 -> ByteArray(bytes.size).also { r.nextBytes(it) }
                    else -> bytes.copyOf(bytes.size / 2) + "}}{{".toByteArray()
                }
                main.writeBytes(broken)
                assertEquals("seed $seed", expected, FolderStore(root).list())
            }
            cases++
        }
        println("TORTURE-PROPERTY folders cases=$cases")
    }

    @Test
    fun semVerNeverThrowsAndOrdersConsistently() {
        var cases = 0
        for (seed in 1..5000) {
            val r = Random(seed)
            val s = String(CharArray(r.nextInt(0, 16)) { "0123456789.v-+ab".random(r) })
            val v = SemVer.parse(s)
            val w = SemVer.parse("${r.nextInt(0, 30)}.${r.nextInt(0, 30)}.${r.nextInt(0, 30)}")!!
            if (v != null) {
                assertTrue("seed $seed antisymmetry", (v > w) != (v < w) || v.compareTo(w) == 0)
                assertEquals(v, v)
            }
            cases++
        }
        println("TORTURE-PROPERTY semver cases=$cases")
    }
}
