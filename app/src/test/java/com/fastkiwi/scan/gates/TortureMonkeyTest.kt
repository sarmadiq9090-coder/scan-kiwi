// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.gates

import android.Manifest
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.fastkiwi.scan.data.DocumentStore
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.random.Random

/**
 * UI monkey: seeded random walks over what TalkBack can reach (taps, long-presses,
 * scrolls, typing, Back, rotation, language), answering system dialogs at random, in
 * Arabic and English. Fails on a crash, an uncaught exception or a main-thread stall
 * over 5 s, printing the seed and the action trail (replay them with bb.sh).
 */
@Category(TortureSuite::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "ar-w400dp-h860dp-xxhdpi", shadows = [ShadowWorldPdfRenderer::class, ShadowWorldPdfPage::class])
class TortureMonkeyTest {
    @get:Rule
    val rule = createEmptyComposeRule()

    private val app get() = RuntimeEnvironment.getApplication()
    private val tmp = TemporaryFolder()
    private lateinit var uncaught: Gates.Uncaught

    @Before
    fun setUp() {
        Gates.assumeTorture()
        tmp.create()
        uncaught = Gates.Uncaught().install()
    }

    @After
    fun tearDown() {
        if (!Gates.torture) return
        uncaught.uninstall()
        tmp.delete()
        Gates.wipe(DocumentStore(app.filesDir))
        app.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        shadowOf(app).denyPermissions(Manifest.permission.CAMERA)
    }

    private val texts = listOf("فاتورة", "Invoice 1047", "عقد الإيجار ٢٠٢٦", "😀📄", "a".repeat(300), "‮abc", "  ", "../../etc", "مرحبا Hello ١٢٣")

    private fun seedDocs(store: DocumentStore) {
        if (store.list().size >= 2) return
        Gates.seed(store, 3, name = "فاتورة الكهرباء", w = 600, h = 800)
        Gates.seed(store, 2, name = "Lease contract", w = 600, h = 800)
    }

    private class Failure(val seed: Int, val lang: String, val step: Int, val what: String, val trail: List<String>)

    private fun walk(seed: Int, lang: String, steps: Int, failures: MutableList<Failure>): Int {
        val store = DocumentStore(app.filesDir)
        seedDocs(store)
        val bb = BlackBox(rule, tmp.newFolder("monkey-$lang-$seed"))
        bb.recordFiles = false
        bb.tickMs = 5
        bb.quietTicks = 3
        val rnd = Random(seed)
        val trail = ArrayList<String>()
        var done = 0
        try {
            bb.start(lang)
            bb.execute("gallery_has photo.jpg invoice")
            bb.execute("pdf_file two.pdf 2")
            var lastEvents: List<String> = emptyList()
            while (done < steps) {
                val cmd = next(rnd, bb, lastEvents) ?: "back"
                trail.add(cmd)
                bb.resetMaxMain()
                val s = bb.execute(cmd)
                done++
                lastEvents = s.events
                val crash = s.events.firstOrNull { it.startsWith("APP STOPPED") }
                val crashText = crash?.let { bb.dir.listFiles()?.filter { f -> f.name.endsWith("-crash.txt") }?.maxByOrNull { f -> f.lastModified() }?.readText() } ?: ""
                val bg = uncaught.errors.poll()
                when {
                    crash != null -> { failures.add(Failure(seed, lang, done, "crash: $crash\n$crashText", trail.toList())); break }
                    bg != null -> { failures.add(Failure(seed, lang, done, "uncaught on ${bg.first}: ${Gates.stack(bg.second)}", trail.toList())); break }
                    bb.maxMainMs > 5_000 -> { failures.add(Failure(seed, lang, done, "main thread stalled ${bb.maxMainMs} ms", trail.toList())); break }
                }
                if (s.events.any { it == BlackBox.CLOSED }) { trail.add("launch"); bb.execute("launch") }
                if (store.list().isEmpty()) { seedDocs(store); bb.activity()?.vm?.refresh() }
            }
        } finally {
            bb.close()
        }
        return done
    }

    /** Picks the next action from what is on screen now. */
    private fun next(rnd: Random, bb: BlackBox, events: List<String>): String? {
        events.firstOrNull { it.startsWith("SYSTEM PERMISSION DIALOG") }?.let { return if (rnd.nextInt(3) == 0) "permission deny" else "permission allow" }
        events.firstOrNull { it.startsWith("SYSTEM FILE PICKER") }?.let {
            return when (rnd.nextInt(4)) { 0 -> "pick_cancel"; 1 -> "pick \"two.pdf\""; else -> "pick \"photo.jpg\"" }
        }
        if (bb.activity() == null) return "launch"
        val nodes = A11y.snapshot()
        val modal = nodes.map { it.window }.filter { it.modal }.maxByOrNull { it.index }
        val usable = if (modal == null) nodes else nodes.filter { it.window.index >= modal.index }
        val onlyApp = A11y.windows().size <= 1
        val atRoot = (bb.activity()?.vm?.backStack?.size ?: 1) <= 1 && onlyApp
        val r = rnd.nextInt(100)
        val clickable = usable.filter { it.clickable && !it.states.contains("disabled") }
        return when {
            r < 3 -> listOf("rotate", "camera_sees ${SyntheticPages.catalogue[rnd.nextInt(SyntheticPages.catalogue.size)].id}", "wait 300").random(rnd)
            r < 4 -> if (rnd.nextBoolean()) "darkmode ${if (rnd.nextBoolean()) "on" else "off"}" else "fontscale ${listOf("1.0", "1.3", "2.0").random(rnd)}"
            r < 14 && !atRoot -> "back"
            r < 22 -> usable.filter { it.longClickable }.randomOrNull(rnd)?.let { "longpress @${it.index}" }
            r < 32 -> usable.filter { it.scrollV || it.scrollH }.randomOrNull(rnd)?.let { n ->
                "scroll ${if (n.scrollV) listOf("down", "up").random(rnd) else listOf("left", "right").random(rnd)}"
            }
            r < 38 -> usable.filter { it.editable }.randomOrNull(rnd)?.let { "type @${it.index} \"${texts.random(rnd).replace("\"", "\\\"")}\"" }
            r < 40 -> "swipe ${listOf("left", "right", "up", "down").random(rnd)}"
            else -> null
        } ?: clickable.randomOrNull(rnd)?.let { "tap @${it.index}" } ?: if (atRoot) "wait 100" else "back"
    }

    private fun run(lang: String, seeds: List<Int>, steps: Int) {
        val failures = ArrayList<Failure>()
        val t0 = System.nanoTime()
        var total = 0
        for (s in seeds) total += walk(s, lang, steps, failures)
        Gates.perf("monkey $lang: $total actions over seeds $seeds in ${Gates.ms(t0)} ms, ${failures.size} failure(s)")
        val text = failures.joinToString("\n\n") { f ->
            "seed ${f.seed} ($lang) step ${f.step}: ${f.what}\nreplay: tools/blackbox/bb.sh start ${f.lang}, then:\n" +
                f.trail.joinToString("\n") { "  $it" }
        }
        if (failures.isNotEmpty()) { File("build").mkdirs(); File("build/torture-monkey-$lang.txt").writeText(text) }
        assertTrue(text, failures.isEmpty())
    }

    @Test(timeout = 1_200_000)
    fun monkeyArabic() = run("ar", listOf(101, 202, 303, 404), 300)

    @Test(timeout = 1_200_000)
    fun monkeyEnglish() = run("en", listOf(505, 606, 707, 808), 260)

    /**
     * Every control TalkBack can activate has a name (spec: labels on every control).
     * Visits Home, a document, a page, the crop editor, the export sheet, Settings and the camera.
     */
    @Test(timeout = 600_000)
    fun everyActionableControlHasAName() {
        val store = DocumentStore(app.filesDir)
        seedDocs(store)
        shadowOf(app).grantPermissions(Manifest.permission.CAMERA)
        val bb = BlackBox(rule, tmp.newFolder("names"))
        bb.recordFiles = false
        val unnamed = LinkedHashSet<String>()
        fun look(where: String) {
            for (n in A11y.snapshot()) if ((n.clickable || n.longClickable) && n.label.isEmpty()) unnamed.add("$where: ${n.role} at ${n.bounds.toShortString()} (actions ${n.actions.joinToString()})")
        }
        try {
            bb.start("ar")
            look("Home")
            val vm = bb.activity()!!.vm
            val doc = store.list().first()
            for ((where, screen) in listOf(
                "Document" to com.fastkiwi.scan.ui.Screen.Editor(doc.id),
                "Page" to com.fastkiwi.scan.ui.Screen.Page(doc.id, 0),
                "Crop" to com.fastkiwi.scan.ui.Screen.Crop(doc.id, doc.pages[0].id),
                "Settings" to com.fastkiwi.scan.ui.Screen.Settings,
                "Camera" to com.fastkiwi.scan.ui.Screen.Camera(null),
            )) {
                bb.activity()!!.vm.open(screen)
                bb.execute("wait 500")
                look(where)
                if (where == "Document") { bb.execute("tap \"تصدير\""); look("Export sheet"); bb.execute("back") }
                while (vm.back()) Unit
                bb.execute("wait 200")
            }
        } finally {
            bb.close()
        }
        File("build").mkdirs()
        File("build/torture-a11y-names.txt").writeText(unnamed.joinToString("\n") + "\n")
        assertTrue("controls without an accessible name:\n" + unnamed.joinToString("\n"), unnamed.isEmpty())
    }
}
