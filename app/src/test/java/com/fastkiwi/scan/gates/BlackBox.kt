// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.gates

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Looper
import android.provider.OpenableColumns
import android.view.KeyEvent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.click
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.fastkiwi.scan.MainActivity
import com.fastkiwi.scan.R
import com.fastkiwi.scan.ui.AppViewModel
import com.fastkiwi.scan.ui.Screen
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowToast
import java.io.File
import java.util.Locale

/**
 * The black-box driver: runs the real app in a simulated phone and executes
 * user-level commands (see tools/blackbox/README-for-testers.md). Every command
 * produces a screenshot, the accessibility tree and the events it caused.
 *
 * Internals the tester never sees: the camera is simulated by handing a synthetic
 * photo to the same view-model call the camera callback makes (Robolectric has no
 * camera; see tools/blackbox/seams/ for the seam that would let the real shutter
 * code run), and the outside world (gallery, Files app, Save dialog, WhatsApp) is a
 * content provider plus answers to the activities the app starts.
 */
class BlackBox(private val rule: ComposeTestRule, val dir: File) {
    class Step(val number: Int, val command: String, val ok: Boolean, val png: File, val txt: File, val text: String, val events: List<String>)

    private val app = RuntimeEnvironment.getApplication()
    private var scenario: ActivityScenario<MainActivity>? = null
    private val events = ArrayList<String>()
    private var stepNo = -1
    private var lastNodes: List<A11y.Node> = emptyList()
    var quit = false
        private set

    // ---- simulated world state
    private var cameraView = "desk"
    private var viewSerial = 0
    private var cameraEntry = 0
    private var autoFired = Pair(-1, -1)
    private var entryLooked = -1
    private var wasOnCamera = false
    private var shotSeed = 100
    /** 2.0-gates camera seam: set while the driver taps the shutter, so a still is reported as manual. */
    @Volatile private var shutterTapped = false
    @Volatile private var shotsTaken = 0
    private var frameKey: Pair<String, Int>? = null
    private var frame: android.graphics.Bitmap? = null

    /**
     * The simulated camera (`ui/CaptureSeam`): CameraScreen takes its preview frames and stills from here,
     * so the shutter, the live outline, the auto-capture gate and the status texts are the app's own code.
     */
    private val fakeCamera = object : com.fastkiwi.scan.ui.CaptureSeam.FakeCamera {
        override fun previewFrame(): android.graphics.Bitmap? = synchronized(this@BlackBox) {
            // A steady hold: the same frame for as long as the same thing is in view.
            val key = cameraView to viewSerial
            if (frameKey != key) {
                // Not recycled: the app may still be reading the previous frame on a worker thread.
                frame = SyntheticPages.photo(cameraView, 7000 + viewSerial, 600, 800)
                frameKey = key
            }
            frame
        }

        override fun takePicture(): Pair<ByteArray, Int>? {
            val view = cameraView
            val photo = SyntheticPages.photo(view, shotSeed++)
            val jpeg = SyntheticPages.jpeg(photo)
            photo.recycle()
            shotsTaken++
            val how = if (shutterTapped) "with the shutter" else "automatically"
            events.add("camera: photo taken $how (it shows: ${SyntheticPages.spec(view)!!.description})")
            return jpeg to 0
        }
    }
    private var whatsappArmed = false
    private var pendingShare: Intent? = null
    private var pendingPicker: org.robolectric.shadows.ShadowActivity.IntentForResult? = null
    private var pendingSave: org.robolectric.shadows.ShadowActivity.IntentForResult? = null
    private var pendingPermission: Any? = null
    private var handledPermission: Any? = null
    private var autoSave = true
    private val savedSizes = HashMap<String, Long>()
    private var announced = HashSet<String>()
    private var toastCount = 0
    private var orientationLand = false
    private var language = "ar"
    private var fontScale = 1f
    private var dark = false
    private var appLocale = ""

    fun activity(): MainActivity? {
        var a: MainActivity? = null
        runCatching { scenario?.onActivity { a = it } }
        return a?.takeIf { !it.isFinishing && !it.isDestroyed }
    }

    private fun vm(): AppViewModel? = activity()?.vm

    // ------------------------------------------------------------------ lifecycle

    fun start(initialLanguage: String = "ar"): Step {
        dir.mkdirs()
        resetFileProviderCache()
        World.setUp(File(dir, "world"))
        World.onWrite = { f -> savedSizes.remove(f.name) }
        language = initialLanguage
        RuntimeEnvironment.setQualifiers("$language-w400dp-h860dp-port-notnight-xxhdpi")
        RuntimeEnvironment.setFontScale(1f)
        Locale.setDefault(Locale.forLanguageTag(language))
        resetViewModelFactory()
        installSeams()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        dropStaleViewModel()
        settle()
        return record("start", true, "app started")
    }

    /**
     * Robolectric keeps statics between tests; after an activity recreate in an earlier
     * test the new activity can be handed that test's view model (bound to the old
     * Application and data folder). Start this session with a fresh one.
     */
    private fun dropStaleViewModel() {
        val a = activity() ?: return
        if (a.vm.getApplication<android.app.Application>() !== app) {
            a.viewModelStore.clear()
            // The activity's `by viewModels()` lazy caches the instance: forget it too.
            runCatching {
                val d = MainActivity::class.java.getDeclaredField("vm\$delegate").apply { isAccessible = true }.get(a)
                d.javaClass.getDeclaredField("cached").apply { isAccessible = true }.set(d, null)
            }
            scenario?.recreate()
        }
        check(activity()?.vm?.getApplication<android.app.Application>() === app) { "stale view model from an earlier test" }
    }

    /**
     * The simulated camera and text recognition (2.0-gates seams). Set again by `launch`: close() clears
     * them, and a `launch` after the app was closed left the real CameraX path in place, which cannot run
     * here («الكاميرا غير متاحة», a shutter that did nothing: BLACKBOX-2.0 #3).
     */
    private fun installSeams() {
        com.fastkiwi.scan.ui.CaptureSeam.camera = fakeCamera
        SimulatedOcr.prepare()
        com.fastkiwi.scan.ocr.OcrSeam.engine = SimulatedOcr
    }

    fun close() {
        // After a recreate, Robolectric hands the retained view models to the next test's
        // activity (a static left behind); clearing the store ends them here.
        runCatching { scenario?.onActivity { it.viewModelStore.clear() } }
        runCatching { scenario?.close() }
        scenario = null
        com.fastkiwi.scan.ui.CaptureSeam.camera = null
        com.fastkiwi.scan.ocr.OcrSeam.engine = null
        synchronized(this) { frame = null; frameKey = null }
    }

    /** Runs one command line and records the step. Never throws for app problems. */
    fun execute(line: String): Step {
        val cmd = line.trim()
        val result = try {
            run(cmd)
        } catch (e: UsageError) {
            false to (e.message ?: "error")
        } catch (e: Throwable) {
            crash(e)
            false to "the command could not finish"
        }
        return record(cmd, result.first, result.second)
    }

    private class UsageError(msg: String) : Exception(msg)

    private fun usage(msg: String): Nothing = throw UsageError(msg)

    private fun crash(e: Throwable) {
        val f = File(dir, String.format(java.util.Locale.ROOT, "%03d-crash.txt", stepNo + 1))
        f.writeText(Gates.stack(e))
        events.add("APP STOPPED: the app crashed (${e.javaClass.simpleName}). Details for developers: ${f.name}")
    }

    // ------------------------------------------------------------------ commands

    private fun run(cmd: String): Pair<Boolean, String> {
        val t = tokens(cmd)
        if (t.isEmpty()) usage("empty command (try: help)")
        val verb = t[0].lowercase()
        val args = t.drop(1)
        if (activity() == null && verb !in setOf("launch", "quit", "pages", "files", "help", "share_in", "screenshot", "wait", "gallery_has", "pdf_file", "camera_sees", "whatsapp_target", "pick_result")) {
            usage("the app is not open (use: launch)")
        }
        return when (verb) {
            "help" -> true to HELP
            "tap" -> { val n = target(args, prefer = { it.clickable }); tap(n); settle(); true to "tapped [${n.index}] ${n.role} \"${n.label}\"" }
            "longpress", "long_press" -> {
                val n = target(args, prefer = { it.longClickable })
                interaction(n).performTouchInput { longClick(center) }
                settle(); true to "long-pressed [${n.index}] \"${n.label}\""
            }
            "type" -> {
                if (args.size < 2) usage("type \"<field label>\" \"<text>\"")
                val n = target(args.dropLast(1), prefer = { it.editable }, require = { it.editable }, what = "text field")
                val i = interaction(n)
                i.performTouchInput { click(center) }
                rule.waitForIdle()
                interaction(n).performTextInput(args.last())
                settle(); true to "typed ${args.last().length} characters into \"${n.label}\""
            }
            "clear" -> {
                val n = target(args, prefer = { it.editable }, require = { it.editable }, what = "text field")
                interaction(n).performTextClearance()
                settle(); true to "cleared \"${n.label}\""
            }
            "scroll" -> { scroll(args); settle(); true to "scrolled ${args.firstOrNull()}" }
            "swipe" -> { swipe(args); settle(); true to "swiped ${args.firstOrNull()}" }
            "drag" -> { drag(args); settle(); true to "dragged" }
            "back" -> { back(); settle(); true to "pressed Back" }
            "wait" -> { val ms = args.firstOrNull()?.toLongOrNull() ?: usage("wait <milliseconds>"); waitFor(ms.coerceIn(0, 120_000)); true to "waited $ms ms" }
            "rotate" -> { orientationLand = !orientationLand; reconfigure(handled = true); true to "turned the phone to ${if (orientationLand) "landscape" else "portrait"}" }
            "fontscale" -> {
                val x = args.firstOrNull()?.toFloatOrNull() ?: usage("fontscale <0.85..2.0>")
                fontScale = x.coerceIn(0.5f, 3f); reconfigure(handled = false); true to "text size set to ${fontScale}x"
            }
            "locale" -> {
                val l = args.firstOrNull()?.lowercase() ?: usage("locale ar|en")
                if (l != "ar" && l != "en") usage("locale ar|en")
                language = l; reconfigure(handled = false); true to "phone language set to $l"
            }
            "darkmode" -> {
                dark = when (args.firstOrNull()?.lowercase()) { "on" -> true; "off" -> false; else -> usage("darkmode on|off") }
                reconfigure(handled = true); true to "dark mode ${if (dark) "on" else "off"}"
            }
            "screenshot" -> { settle(); true to "screenshot" }
            "quit" -> { quit = true; true to "session ends" }
            "launch" -> {
                if (activity() != null) true to "the app is already open"
                else { close(); resetViewModelFactory(); installSeams(); scenario = ActivityScenario.launch(MainActivity::class.java); settle(); true to "app opened from the launcher" }
            }
            "pages" -> true to SyntheticPages.catalogue.joinToString("\n", "Pages the camera can see (camera_sees <id>):\n") { "  ${it.id} - ${it.description}" }
            "files" -> true to filesList()
            "camera_sees" -> {
                val id = args.firstOrNull() ?: usage("camera_sees <page-id> (see: pages)")
                val spec = SyntheticPages.spec(if (id == "nothing") "desk" else id) ?: usage("unknown page \"$id\" (see: pages)")
                cameraView = spec.id; viewSerial++
                settle(); true to "the camera now sees: ${spec.description}"
            }
            "gallery_has" -> { val msg = galleryHas(args); settle(); true to msg }
            "pdf_file" -> { val msg = pdfFile(args); settle(); true to msg }
            "share_in" -> { val msg = shareIn(args); settle(); true to msg }
            "pick" -> { val msg = pick(args); settle(); true to msg }
            "pick_cancel" -> {
                val p = pendingPicker ?: usage("no file picker is open")
                pendingPicker = null
                deliver(p, Activity.RESULT_CANCELED, null); settle(); true to "closed the file picker without choosing"
            }
            "pick_result" -> { val msg = pickResult(args); settle(); true to msg }
            "permission" -> { val msg = permission(args); settle(); true to msg }
            "whatsapp_target" -> {
                whatsappArmed = args.firstOrNull()?.lowercase() != "off"
                val msg = if (whatsappArmed) "WhatsApp (simulated) will be chosen in the share sheet" else "no share target will be chosen"
                pendingShare?.let { if (whatsappArmed) { pendingShare = null; receiveShare(it) } }
                settle(); true to msg
            }
            else -> usage("unknown command \"$verb\" (try: help)")
        }
    }

    private fun filesList(): String {
        val files = World.filesDir().listFiles()?.sortedBy { it.name } ?: emptyList()
        val saved = World.savedDir().listFiles()?.sortedBy { it.name } ?: emptyList()
        return buildString {
            appendLine("Files in the phone's gallery / Files app:")
            if (files.isEmpty()) appendLine("  (none)")
            files.forEach { appendLine("  ${it.name}  ${it.length()} bytes  ${World.mimeOf(it.name)}") }
            appendLine("Files saved by apps (Downloads):")
            if (saved.isEmpty()) appendLine("  (none)")
            saved.forEach { appendLine("  ${it.name}  ${it.length()} bytes") }
        }.trimEnd()
    }

    // ------------------------------------------------------------------ matching

    private fun norm(s: String) = s.lowercase(Locale.ROOT).replace(Regex("[ً-ْـ]"), "").replace(Regex("\\s+"), " ").trim()

    /** The usable nodes: the top modal layer if there is one, else all layers. */
    private fun usable(nodes: List<A11y.Node>): List<A11y.Node> {
        val modal = nodes.map { it.window }.filter { it.modal }.maxByOrNull { it.index } ?: return nodes
        return nodes.filter { it.window.index >= modal.index }
    }

    private fun target(
        args: List<String>,
        prefer: (A11y.Node) -> Boolean = { true },
        require: (A11y.Node) -> Boolean = { true },
        what: String = "element",
    ): A11y.Node {
        if (args.isEmpty()) usage("which $what? give its visible label in quotes")
        var label = args[0]
        var nth = 1
        if (args.size >= 2 && args[1].matches(Regex("#\\d+"))) nth = args[1].drop(1).toInt()
        val nodes = A11y.snapshot()
        lastNodes = nodes
        if (label.startsWith("@") && label.drop(1).toIntOrNull() != null) {
            val n = nodes.firstOrNull { it.index == label.drop(1).toInt() } ?: usage("no element [${label.drop(1)}] on screen now")
            if (n !in usable(nodes)) usage("[${n.index}] is covered by a dialog or menu (close it first, e.g. with back)")
            return n
        }
        val m = Regex("^(.*)#(\\d+)$").find(label)
        val candidatesAll = usable(nodes).filter(require)
        fun match(l: String): List<A11y.Node> {
            val q = norm(l)
            val exact = candidatesAll.filter { n -> norm(n.label) == q || n.label.split(" · ").any { norm(it) == q } || (n.value != null && norm(n.value) == q) }
            if (exact.isNotEmpty()) return exact
            // A label that starts with the words given ("Done" for "Done, pages: 3"); never a
            // word in the middle of a sentence, which would tap the wrong thing.
            return candidatesAll.filter { n -> n.label.split(" · ").any { p -> val s = norm(p); s.startsWith("$q ") || s.startsWith("$q،") || s.startsWith("$q,") || s.startsWith("$q:") } }
        }
        var found = match(label)
        if (found.isEmpty() && m != null) {
            label = m.groupValues[1]; nth = m.groupValues[2].toInt(); found = match(label)
        }
        if (found.isEmpty()) {
            val covered = nodes.filter { it !in usable(nodes) && norm(it.label).contains(norm(label)) }
            if (covered.isNotEmpty()) usage("\"$label\" is covered by a dialog or menu (close it first, e.g. with back)")
            val words = norm(label).split(' ').filter { it.length >= 2 }
            val similar = usable(nodes).filter { n -> words.any { norm(n.label).contains(it) } }.take(6).map { "[${it.index}] \"${it.label}\"" }
            usage("no visible $what labelled \"$label\"" + if (similar.isNotEmpty()) "; similar: ${similar.joinToString(", ")}" else "")
        }
        // Prefer the actionable node when a label appears on a container and inside it.
        val preferred = found.filter(prefer).ifEmpty { found }
        if (nth < 1 || nth > preferred.size) usage("\"$label\" matches ${preferred.size} element(s); #$nth does not exist")
        return preferred[nth - 1]
    }

    private fun interaction(n: A11y.Node): SemanticsNodeInteraction {
        val id = n.id
        return rule.onNode(SemanticsMatcher("node $id") { it.id == id }, useUnmergedTree = false)
    }

    /** The root node of [n]'s window, for gestures that span the screen. */
    private fun rootOf(n: A11y.Node): SemanticsNodeInteraction {
        var r = n.sn
        while (r.parent != null) r = r.parent!!
        val id = r.id
        return rule.onNode(SemanticsMatcher("root $id") { it.id == id }, useUnmergedTree = false)
    }

    private fun localCenter(n: A11y.Node, of: A11y.Node): Offset {
        val loc = IntArray(2)
        (of.sn.root as android.view.View).getLocationOnScreen(loc)
        return Offset(n.bounds.exactCenterX() - loc[0], n.bounds.exactCenterY() - loc[1])
    }

    // ------------------------------------------------------------------ gestures

    private fun tap(n: A11y.Node) {
        val vm = vm()
        val shutter = app.getString(R.string.take_photo)
        if (vm != null && vm.screen is Screen.Camera && norm(n.label).contains(norm(shutter))) {
            // The shutter: the real button; the camera seam supplies the photo.
            if (n.states.contains("disabled")) { events.add("the shutter is disabled"); return }
            shutterTapped = true
            try {
                interaction(n).performTouchInput { click(center) }
                settle()
            } finally {
                shutterTapped = false
            }
            return
        }
        interaction(n).performTouchInput { click(center) }
    }

    private fun scroll(args: List<String>) {
        val dirName = args.firstOrNull()?.lowercase() ?: usage("scroll down|up|left|right [\"<container or something inside it>\"]")
        val nodes = A11y.snapshot()
        val pool = usable(nodes)
        val vertical = dirName == "down" || dirName == "up"
        if (!vertical && dirName != "left" && dirName != "right") usage("scroll down|up|left|right")
        val scrollables = pool.filter { if (vertical) it.scrollV else it.scrollH }
        if (scrollables.isEmpty()) usage("nothing here scrolls ${if (vertical) "up/down" else "sideways"}")
        val container = if (args.size >= 2) {
            val inner = target(args.drop(1))
            scrollables.filter { it.bounds.contains(inner.bounds.centerX(), inner.bounds.centerY()) }.minByOrNull { it.bounds.width() * it.bounds.height() }
                ?: usage("\"${args[1]}\" is not inside anything that scrolls that way")
        } else scrollables.maxBy { it.bounds.width() * it.bounds.height() }
        interaction(container).performTouchInput {
            val l = 0.18f
            when (dirName) {
                "down" -> swipeUp(startY = height * (1 - l), endY = height * l, durationMillis = 350)
                "up" -> swipeDown(startY = height * l, endY = height * (1 - l), durationMillis = 350)
                "right" -> swipeLeft(startX = width * (1 - l), endX = width * l, durationMillis = 350)
                else -> swipeRight(startX = width * l, endX = width * (1 - l), durationMillis = 350)
            }
        }
    }

    private fun swipe(args: List<String>) {
        val d = args.firstOrNull()?.lowercase() ?: usage("swipe left|right|up|down [\"<label>\"]")
        val nodes = A11y.snapshot()
        val on = if (args.size >= 2) target(args.drop(1)) else usable(nodes).maxByOrNull { it.bounds.width() * it.bounds.height() } ?: usage("nothing on screen")
        interaction(on).performTouchInput {
            when (d) {
                "left" -> swipeLeft(durationMillis = 250)
                "right" -> swipeRight(durationMillis = 250)
                "up" -> swipeUp(durationMillis = 250)
                "down" -> swipeDown(durationMillis = 250)
                else -> throw UsageError("swipe left|right|up|down")
            }
        }
    }

    private fun drag(args: List<String>) {
        val toAt = args.indexOfFirst { it.equals("to", true) }
        if (toAt < 1 || toAt == args.lastIndex) usage("drag \"<label>\" to \"<label>\"")
        val a = target(args.subList(0, toAt))
        val b = target(args.subList(toAt + 1, args.size))
        val start = localCenter(a, a)
        val end = localCenter(b, a)
        rootOf(a).performTouchInput {
            down(start)
            advanceEventTime(800) // hold to pick it up
            for (i in 1..24) moveTo(start + (end - start) * (i / 24f), delayMillis = 25)
            advanceEventTime(200)
            up()
        }
    }

    private fun back() {
        val ws = A11y.windows()
        val top = ws.lastOrNull() ?: usage("nothing on screen")
        val a = activity()
        if (ws.size == 1 && a != null) {
            a.onBackPressedDispatcher.onBackPressed()
        } else {
            top.view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK))
            top.view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
        }
    }

    private fun waitFor(ms: Long) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            tick(50)
        }
        settle(5_000)
    }

    // ------------------------------------------------------------------ configuration

    private fun qualifiers() = "$language-${if (orientationLand) "w860dp-h400dp-land" else "w400dp-h860dp-port"}-${if (dark) "night" else "notnight"}-xxhdpi"

    /**
     * [handled]: MainActivity declares this change in configChanges, so the system
     * does not recreate it; the views get onConfigurationChanged and a new size.
     * Otherwise (language, text size) the activity is recreated, as on a phone.
     */
    private fun reconfigure(handled: Boolean) {
        val effectiveLang = appLocale.ifEmpty { language }
        RuntimeEnvironment.setQualifiers(qualifiers().replaceFirst(language, effectiveLang))
        RuntimeEnvironment.setFontScale(fontScale)
        Locale.setDefault(Locale.forLanguageTag(effectiveLang))
        val a = activity()
        if (a == null) { settle(); return }
        if (handled) {
            val ok = runCatching {
                val cfg = Configuration(app.resources.configuration)
                a.onConfigurationChanged(cfg)
                for (w in A11y.windows()) {
                    w.view.dispatchConfigurationChanged(cfg)
                    val vri = org.robolectric.util.ReflectionHelpers.callInstanceMethod<Any>(w.view, "getViewRootImpl")
                    org.robolectric.shadow.api.Shadow.extract<org.robolectric.shadows.ShadowViewRootImpl>(vri).callDispatchResized()
                    w.view.requestLayout()
                }
            }.isSuccess
            if (!ok) scenario?.recreate()
        } else {
            scenario?.recreate()
        }
        settle()
    }

    // ------------------------------------------------------------------ simulated world


    private fun galleryHas(args: List<String>): String {
        val name = args.firstOrNull() ?: usage("gallery_has <file name> [<page-id>|garbage|empty|truncated|huge]")
        val kind = args.getOrNull(1)?.lowercase() ?: "mixed-memo"
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext == "pdf") return pdfFile(listOf(name, args.getOrNull(1) ?: "3"))
        val bytes: ByteArray = when (kind) {
            "garbage" -> kotlin.random.Random(name.hashCode()).nextBytes(40_000)
            "empty" -> ByteArray(0)
            "truncated" -> SyntheticPages.photo("en-letter", 7).let { val b = SyntheticPages.encode(it, ext); it.recycle(); b.copyOf(b.size / 3) }
            "huge" -> Fuzz.pngHeader(60_000, 60_000)
            else -> {
                SyntheticPages.spec(kind) ?: usage("unknown kind \"$kind\": a page id (see: pages) or garbage|empty|truncated|huge")
                val p = SyntheticPages.photo(kind, name.hashCode())
                SyntheticPages.encode(p, ext).also { p.recycle() }
            }
        }
        World.put(name, bytes)
        val note = if (ext == "heic" || ext == "heif") " (simulated HEIC: the file holds JPEG data)" else ""
        return "the gallery now has $name (${bytes.size} bytes, ${World.mimeOf(name)})$note"
    }

    private fun pdfFile(args: List<String>): String {
        val name = args.firstOrNull() ?: usage("pdf_file <name.pdf> <pages> [protected|broken]")
        val pages = args.getOrNull(1)?.toIntOrNull()?.coerceIn(1, 300) ?: usage("pdf_file <name.pdf> <pages> [protected|broken]")
        val mode = args.getOrNull(2)?.lowercase()
        var bytes = SyntheticPages.pdf(pages, password = if (mode == "protected") "1234" else null)
        if (mode == "broken") bytes = bytes.copyOf(bytes.size / 2)
        val n = if (name.endsWith(".pdf", true)) name else "$name.pdf"
        World.put(n, bytes)
        return "the Files app now has $n ($pages pages, ${bytes.size} bytes${if (mode == "protected") ", password 1234" else ""}${if (mode == "broken") ", damaged" else ""})"
    }

    private fun shareIn(args: List<String>): String {
        if (args.isEmpty()) usage("share_in <file> [more files]")
        val uris = args.map { n -> if (!File(World.filesDir(), n).exists()) usage("no file \"$n\" (create it with gallery_has or pdf_file)"); World.uriOf(n) }
        val types = args.map { World.mimeOf(it) }.distinct()
        val intent = if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        intent.type = if (types.size == 1) types[0] else if (types.all { it.startsWith("image/") }) "image/*" else "*/*"
        intent.clipData = ClipData.newRawUri(null, uris[0]).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        intent.setClassName(app, MainActivity::class.java.name)
        val a = activity()
        if (a != null) {
            InstrumentationRegistry.getInstrumentation().callActivityOnNewIntent(a, intent)
        } else {
            close()
            resetViewModelFactory()
            scenario = ActivityScenario.launch(intent)
        }
        return "another app shared ${args.joinToString(", ")} to Scan Kiwi"
    }

    private fun pick(args: List<String>): String {
        val p = pendingPicker ?: usage("no file picker is open")
        if (args.isEmpty()) usage("pick \"<file>\" [more files]")
        for (n in args) if (!File(World.filesDir(), n).exists()) usage("the picker has no file \"$n\"")
        val accepted = acceptedTypes(p.intent)
        val rejected = args.filter { n -> accepted.none { t -> typeMatches(t, World.mimeOf(n)) } }
        if (rejected.isNotEmpty()) usage("the picker does not offer ${rejected.joinToString()} (it shows only ${accepted.joinToString()})")
        val multiple = p.intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false) || p.intent.action == "android.provider.action.PICK_IMAGES"
        if (args.size > 1 && !multiple) usage("this picker lets you choose only one file")
        val uris = args.map { World.uriOf(it) }
        val data = Intent().setData(uris[0]).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        data.clipData = ClipData.newRawUri(null, uris[0]).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
        pendingPicker = null
        deliver(p, Activity.RESULT_OK, data)
        return "chose ${args.joinToString(", ")} in the file picker"
    }

    private fun pickResult(args: List<String>): String {
        when (args.firstOrNull()?.lowercase()) {
            "auto" -> { autoSave = args.getOrNull(1)?.lowercase() != "off"; return "Save dialogs are ${if (autoSave) "answered automatically" else "left for you (pick_result ok|cancel)"}" }
            "manual" -> { autoSave = false; return "Save dialogs are left for you (pick_result ok|cancel)" }
            "ok" -> { val p = pendingSave ?: usage("no Save dialog is open"); pendingSave = null; return answerSave(p, args.getOrNull(1)) }
            "cancel" -> { val p = pendingSave ?: usage("no Save dialog is open"); pendingSave = null; deliver(p, Activity.RESULT_CANCELED, null); return "cancelled the Save dialog" }
            else -> usage("pick_result ok [name] | cancel | auto on|off")
        }
    }

    private fun answerSave(p: org.robolectric.shadows.ShadowActivity.IntentForResult, rename: String?): String {
        return if (p.intent.action == Intent.ACTION_OPEN_DOCUMENT_TREE) {
            deliver(p, Activity.RESULT_OK, Intent().setData(World.treeUri()).addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION))
            "chose the folder Downloads in the folder picker"
        } else {
            val name = rename ?: p.intent.getStringExtra(Intent.EXTRA_TITLE) ?: "file"
            File(World.savedDir(), name).also { it.parentFile?.mkdirs(); it.writeBytes(ByteArray(0)) }
            savedSizes[name] = 0L
            deliver(p, Activity.RESULT_OK, Intent().setData(World.saveUri(name)).addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION))
            "Save dialog: saved as Downloads/$name"
        }
    }

    private fun permission(args: List<String>): String {
        val req = pendingPermission ?: usage("no permission dialog is open")
        val allow = when (args.firstOrNull()?.lowercase()) { "allow" -> true; "deny" -> false; else -> usage("permission allow|deny") }
        val perms = org.robolectric.util.ReflectionHelpers.getField<Array<String>>(req, "requestedPermissions")
        val code = org.robolectric.util.ReflectionHelpers.getField<Int>(req, "requestCode")
        if (allow) shadowOf(app).grantPermissions(*perms) else shadowOf(app).denyPermissions(*perms)
        pendingPermission = null
        val a = activity() ?: usage("the app is not open")
        val results = IntArray(perms.size) { if (allow) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED }
        a.onRequestPermissionsResult(code, perms, results)
        return "${if (allow) "allowed" else "denied"} ${perms.joinToString { it.substringAfterLast('.') }}"
    }

    private fun deliver(p: org.robolectric.shadows.ShadowActivity.IntentForResult, code: Int, data: Intent?) {
        val a = activity() ?: usage("the app is not open")
        shadowOf(a).receiveResult(p.intent, code, data)
    }

    private fun acceptedTypes(i: Intent): List<String> {
        if (i.action == "android.provider.action.PICK_IMAGES" || i.action == Intent.ACTION_PICK) return listOf(i.type ?: "image/*")
        return i.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)?.toList() ?: listOf(i.type ?: "*/*")
    }

    private fun typeMatches(pattern: String, type: String): Boolean =
        pattern == "*/*" || pattern == type || (pattern.endsWith("/*") && type.startsWith(pattern.dropLast(1)))

    // ------------------------------------------------------------------ watching the app

    /** Off for the UI monkey: no screenshot or text files, shorter settling. */
    var recordFiles = true
    var tickMs = 40L
    var quietTicks = 8

    /** Longest single main-thread drain seen (an ANR-like stall shows here). */
    var maxMainMs = 0L
        private set

    private fun timedIdle() {
        val t0 = System.nanoTime()
        rule.waitForIdle()
        val ms = (System.nanoTime() - t0) / 1_000_000
        if (ms > maxMainMs) maxMainMs = ms
    }

    fun resetMaxMain() { maxMainMs = 0 }

    /** Recreates the activity as the system does after a change it does not handle. */
    fun recreate() { scenario?.recreate(); settle() }

    /** One round: drain the main looper, let compose time follow real time, collect events. */
    private fun tick(ms: Long) {
        try {
            Thread.sleep(ms)
            rule.mainClock.advanceTimeBy(ms)
            timedIdle()
        } catch (e: Throwable) {
            if (e is IllegalStateException && e.message?.contains("No compose hierarchies") == true) Unit else throw e
        }
        harvest()
    }

    /** Waits until the app has nothing running (at most [maxMs]) and collects events. */
    private fun settle(maxMs: Long = 30_000) {
        val end = System.currentTimeMillis() + maxMs
        var quiet = 0
        try {
            timedIdle()
        } catch (e: Throwable) {
            if (!(e is IllegalStateException && e.message?.contains("No compose hierarchies") == true)) throw e
        }
        harvest()
        while (true) {
            val vm = vm()
            val busy = vm != null && (vm.busy.intValue > 0 || vm.exportState.value != null)
            quiet = if (busy) 0 else quiet + 1
            if (quiet >= quietTicks) break
            if (System.currentTimeMillis() > end) {
                events.add("the app is still working after ${maxMs / 1000} s (use: wait <ms>)")
                break
            }
            tick(tickMs)
        }
        autoCapture()
    }

    /** Auto-capture: a page held still in front of the camera is taken once. */
    private fun autoCapture() {
        val vm = vm() ?: return
        if (vm.screen !is Screen.Camera) return
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        if (!vm.settings.autoCapture) return
        // Just opened (or allowed): the user first looks at the screen and may choose a mode, about 1.5 s,
        // before holding the page still (BLACKBOX-2.0 re-test: a 4 s hold right away left no time to choose Book).
        if (entryLooked != cameraEntry) {
            entryLooked = cameraEntry
            if (cameraView != "desk") {
                val before = shotsTaken
                for (i in 0 until (1500L / tickMs).toInt()) { tick(tickMs); if (shotsTaken != before) break }
                if (shotsTaken != before) settle()
                return
            }
        }
        if (cameraView == "desk") return
        // The app's own gate decides (outline median, hold time, content re-arm). Hold the page still for
        // up to 4 s the first time it is in view, ~1 s after that (the gate should not fire again).
        val first = autoFired != Pair(viewSerial, cameraEntry)
        autoFired = Pair(viewSerial, cameraEntry)
        val before = shotsTaken
        val ticks = ((if (first) 4000L else 1000L) / tickMs).toInt()
        for (i in 0 until ticks) {
            tick(tickMs)
            if (shotsTaken != before) break
        }
        if (shotsTaken != before) settle()
        else if (first) events.add("camera: no photo was taken automatically after holding the page still for 4 s")
    }

    private fun harvest() {
        val a = activity()
        val vm = a?.vm
        // Camera entered (fresh) → auto-capture may fire again for the same page.
        val onCamera = vm?.screen is Screen.Camera
        if (onCamera && !wasOnCamera) cameraEntry++
        wasOnCamera = onCamera
        // App-specific language (Settings ▸ Language): the system applies it by recreating.
        if (a != null) {
            val tags = runCatching { a.getSystemService(android.app.LocaleManager::class.java).applicationLocales.toLanguageTags() }.getOrDefault("")
            val tag = tags.substringBefore(',').substringBefore('-')
            if (tag != appLocale) {
                appLocale = tag
                events.add("the system applied the app language: ${tag.ifEmpty { "phone default" }}")
                val eff = appLocale.ifEmpty { language }
                RuntimeEnvironment.setQualifiers(qualifiers().replaceFirst(language, eff))
                Locale.setDefault(Locale.forLanguageTag(eff))
                scenario?.recreate()
                return
            }
        }
        // Toasts.
        val tc = ShadowToast.shownToastCount()
        if (tc > toastCount) {
            toastCount = tc
            ShadowToast.getTextOfLatestToast()?.let { events.add("toast: \"$it\"") }
        }
        // Activities the app starts (share sheet, pickers, settings, links).
        if (a != null) {
            val sa = shadowOf(a)
            val forResult = ArrayList<org.robolectric.shadows.ShadowActivity.IntentForResult>()
            while (true) forResult.add(sa.nextStartedActivityForResult ?: break)
            val handled = forResult.map { it.intent }
            for (r in forResult) onStartedForResult(r)
            val app2 = shadowOf(app)
            while (true) {
                val i = app2.nextStartedActivity ?: break
                if (handled.any { it === i || it.filterEquals(i) }) continue
                onStarted(i)
            }
            // Permission dialog.
            val req = sa.lastRequestedPermission
            if (req != null && req !== handledPermission) {
                handledPermission = req
                pendingPermission = req
                val perms = org.robolectric.util.ReflectionHelpers.getField<Array<String>>(req, "requestedPermissions")
                val what = perms.joinToString { if (it == Manifest.permission.CAMERA) "take pictures and record video (camera)" else it.substringAfterLast('.') }
                events.add("SYSTEM PERMISSION DIALOG: Allow Scan Kiwi to $what? Answer with: permission allow | permission deny")
            }
        } else if (scenario != null && !quit) {
            if (!events.contains(CLOSED)) events.add(CLOSED)
        }
        // Announcements (snackbars, status lines): live regions whose text is new.
        val nodes = runCatching { A11y.snapshot() }.getOrDefault(emptyList())
        val live = nodes.filter { (it.liveRegion || it.actions.contains("dismiss")) && it.role != "clickable" }.map { l ->
            // A snackbar is an unlabelled live pane; its message is in the nodes inside it (its semantics
            // descendants, as TalkBack reads it, not whatever list row lies under it on screen).
            l.label.ifEmpty {
                val inside = HashSet<Int>()
                fun collect(n: androidx.compose.ui.semantics.SemanticsNode) { for (c in n.children) { inside += c.id; collect(c) } }
                collect(l.sn)
                nodes.filter { it !== l && it.window === l.window && it.id in inside && it.label.isNotEmpty() }
                    .joinToString(" · ") { if (it.clickable) "[button: ${it.label}]" else it.label }
            }
        }.filter { it.isNotEmpty() }.toHashSet()
        for (t in live) if (t !in announced) events.add("announced: \"$t\"")
        announced = live
        // Files written through the Save dialog.
        for (f in World.savedDir().listFiles() ?: emptyArray()) {
            val len = f.length()
            if (savedSizes[f.name] != len && len > 0) {
                savedSizes[f.name] = len
                events.add("file saved: Downloads/${f.name}, $len bytes${pdfInfo(f.readBytes())}")
            }
        }
    }

    private fun onStartedForResult(r: org.robolectric.shadows.ShadowActivity.IntentForResult) {
        val i = r.intent
        if (r.requestCode < 0) { onStarted(i); return }
        when (i.action) {
            Intent.ACTION_OPEN_DOCUMENT, Intent.ACTION_GET_CONTENT, "android.provider.action.PICK_IMAGES", Intent.ACTION_PICK -> {
                pendingPicker = r
                val accepted = acceptedTypes(i)
                val offered = (World.filesDir().listFiles() ?: emptyArray()).filter { f -> accepted.any { typeMatches(it, World.mimeOf(f.name)) } }.map { it.name }.sorted()
                val many = i.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false) || i.action == "android.provider.action.PICK_IMAGES"
                events.add(
                    "SYSTEM FILE PICKER opened (shows ${accepted.joinToString()}; ${if (many) "several files allowed" else "one file"}). " +
                        "Files offered: ${offered.ifEmpty { listOf("(none - add some with gallery_has / pdf_file)") }.joinToString(", ")}. " +
                        "Answer with: pick \"<file>\" ... | pick_cancel",
                )
            }
            Intent.ACTION_CREATE_DOCUMENT, Intent.ACTION_OPEN_DOCUMENT_TREE -> {
                val what = if (i.action == Intent.ACTION_CREATE_DOCUMENT) "SYSTEM SAVE DIALOG opened: name \"${i.getStringExtra(Intent.EXTRA_TITLE)}\", type ${i.type}" else "SYSTEM FOLDER PICKER opened"
                if (autoSave) {
                    events.add("$what. ${answerSave(r, null)}")
                } else {
                    pendingSave = r
                    events.add("$what. Answer with: pick_result ok [name] | pick_result cancel")
                }
            }
            else -> events.add("the app asked the system for: ${describe(i)} (nothing in this simulated phone answers it)")
        }
    }

    private fun onStarted(i: Intent) {
        when (i.action) {
            Intent.ACTION_CHOOSER -> {
                val inner = IntentCompat.getParcelableExtra(i, Intent.EXTRA_INTENT, Intent::class.java)
                val title = i.getCharSequenceExtra(Intent.EXTRA_TITLE)
                events.add("SHARE SHEET opened${title?.let { " \"$it\"" } ?: ""}: ${inner?.let { describe(it) } ?: "(empty)"}")
                if (inner != null) {
                    if (whatsappArmed) receiveShare(inner) else {
                        pendingShare = inner
                        events.add("(no app chosen yet: use whatsapp_target to send it to WhatsApp)")
                    }
                }
            }
            Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE -> {
                events.add("the app sends directly: ${describe(i)}")
                if (whatsappArmed) receiveShare(i) else pendingShare = i
            }
            Intent.ACTION_VIEW -> {
                val d = i.dataString ?: ""
                events.add(if (d.startsWith("http")) "the app opens a web link: $d (this simulated phone is offline; the browser shows nothing)" else "the app opens: ${describe(i)}")
            }
            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS -> events.add("Android settings opened: App info for ${i.data?.schemeSpecificPart} (simulated; nothing to change here)")
            else -> events.add("the app started: ${describe(i)}")
        }
    }

    private fun describe(i: Intent): String = buildString {
        append(i.action ?: "(no action)")
        i.type?.let { append(", type ").append(it) }
        i.dataString?.let { append(", data ").append(it) }
        if ((i.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0) append(", grants read access")
        val ex = i.extras
        if (ex != null) for (k in ex.keySet().sorted()) {
            if (k == Intent.EXTRA_STREAM) continue
            @Suppress("DEPRECATION") val v = ex.get(k)
            append(", extra ").append(k.substringAfterLast('.')).append('=').append(v.toString().take(80))
        }
        val streams = streams(i)
        if (streams.isNotEmpty()) append(", files: ").append(streams.joinToString("; ") { fileInfo(it) })
    }

    private fun streams(i: Intent): List<Uri> =
        if (i.action == Intent.ACTION_SEND_MULTIPLE) IntentCompat.getParcelableArrayListExtra(i, Intent.EXTRA_STREAM, Uri::class.java) ?: emptyList()
        else listOfNotNull(IntentCompat.getParcelableExtra(i, Intent.EXTRA_STREAM, Uri::class.java))

    private fun fileInfo(u: Uri): String {
        val cr = app.contentResolver
        var name = u.lastPathSegment ?: "?"
        var size = -1L
        runCatching {
            cr.query(u, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) { name = c.getString(0) ?: name; size = c.getLong(1) }
            }
        }
        return "\"$name\" ($size bytes)"
    }

    private fun pdfInfo(bytes: ByteArray): String {
        if (bytes.size < 5 || String(bytes, 0, 5, Charsets.ISO_8859_1) != "%PDF-") return ""
        return runCatching {
            org.apache.pdfbox.Loader.loadPDF(bytes).use { d -> ", PDF with ${d.numberOfPages} page(s)" }
        }.getOrElse { e -> if (e is org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException) ", PDF (password-protected)" else ", not a readable PDF" }
    }

    /** The simulated WhatsApp: opens each shared file with the granted access and reports what it got. */
    private fun receiveShare(i: Intent) {
        val streams = streams(i)
        if (streams.isEmpty()) { events.add("WhatsApp (simulated) got no file: ${describe(i)}"); return }
        val granted = (i.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0
        val recv = File(dir, "received").apply { mkdirs() }
        for ((k, u) in streams.withIndex()) {
            val info = fileInfo(u)
            val bytes = runCatching { app.contentResolver.openInputStream(u)?.use { it.readBytes() } }.getOrNull()
            if (bytes == null) { events.add("WhatsApp (simulated) could NOT open $info"); continue }
            val name = Regex("\"(.*)\"").find(info)?.groupValues?.get(1) ?: "file$k"
            File(recv, String.format(java.util.Locale.ROOT, "%03d-%s", stepNo + 1, name)).writeBytes(bytes)
            var extra = pdfInfo(bytes)
            if (extra.contains("PDF with")) {
                runCatching {
                    org.apache.pdfbox.Loader.loadPDF(bytes).use { d ->
                        val img = PdfRaster.render(org.apache.pdfbox.rendering.PDFRenderer(d), 0, 0.6f)
                        val png = File(dir, String.format(java.util.Locale.ROOT, "%03d-received-%d.png", stepNo + 1, k + 1))
                        png.outputStream().use { img.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                        img.recycle()
                        val text = org.apache.pdfbox.text.PDFTextStripper().apply { endPage = 1 }.getText(d).trim().replace(Regex("\\s+"), " ")
                        extra += if (text.isEmpty()) ", no searchable text" else ", searchable text on page 1: \"${text.take(80)}\""
                        extra += ", first page picture: ${png.name}"
                    }
                }
            } else if (bytes.size > 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()) {
                val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
                extra = ", JPEG ${o.outWidth}x${o.outHeight}"
            }
            events.add("WhatsApp (simulated) received $info, MIME ${i.type}, read ${bytes.size} bytes${if (granted) "" else " (no read permission was granted!)"}$extra")
        }
    }

    // ------------------------------------------------------------------ output

    private fun record(cmd: String, ok: Boolean, message: String): Step {
        stepNo++
        val png = File(dir, String.format(java.util.Locale.ROOT, "%03d.png", stepNo))
        val txt = File(dir, String.format(java.util.Locale.ROOT, "%03d.txt", stepNo))
        if (!recordFiles) {
            val ev = ArrayList(events)
            events.clear()
            return Step(stepNo, cmd, ok, png, txt, message, ev)
        }
        val nodes = runCatching { A11y.snapshot() }.getOrDefault(emptyList())
        lastNodes = nodes
        runCatching { if (A11y.windows().isNotEmpty()) A11y.screenshot(png) else blankShot(png) }
        val dm = app.resources.displayMetrics
        val header = listOf(
            String.format(java.util.Locale.ROOT, "Step %03d: %s", stepNo, cmd),
            "Result: ${if (ok) "ok" else "FAILED"} - $message",
            "Phone: ${dm.widthPixels}x${dm.heightPixels} px, density ${dm.density}, language ${appLocale.ifEmpty { language }}${if (appLocale.ifEmpty { language } == "ar") " (right-to-left)" else ""}, " +
                "text size ${fontScale}x, dark mode ${if (dark) "on" else "off"}, ${if (orientationLand) "landscape" else "portrait"}",
        ) + (if (events.isEmpty()) listOf("Events: none") else listOf("Events:") + events.map { "  - $it" }) +
            listOf("Screen (what TalkBack can reach; bounds in px [left,top][right,bottom]):") +
            (if (nodes.isEmpty()) listOf("  (nothing: the app is not on screen)") else emptyList())
        val text = A11y.format(nodes, header)
        txt.writeText(text)
        val ev = ArrayList(events)
        events.clear()
        File(dir, String.format(java.util.Locale.ROOT, "%03d.done", stepNo)).writeText("ok")
        return Step(stepNo, cmd, ok, png, txt, text, ev)
    }

    private fun blankShot(png: File) {
        val dm = app.resources.displayMetrics
        val b = android.graphics.Bitmap.createBitmap(dm.widthPixels, dm.heightPixels, android.graphics.Bitmap.Config.ARGB_8888)
        b.eraseColor(android.graphics.Color.rgb(20, 20, 30))
        png.outputStream().use { b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    companion object {
        /**
         * FileProvider caches its roots in a static map; Robolectric keeps statics between
         * tests while each test gets a new data folder, so a stale root makes sharing fail.
         */
        @Suppress("UNCHECKED_CAST")
        fun resetFileProviderCache() {
            val f = androidx.core.content.FileProvider::class.java.getDeclaredField("sCache")
            f.isAccessible = true
            val cache = f.get(null) as MutableMap<String, Any>
            // Robolectric does not create the manifest's FileProvider by itself.
            val app = RuntimeEnvironment.getApplication()
            val authority = app.packageName + ".files"
            val info = app.packageManager.resolveContentProvider(authority, PackageManager.GET_META_DATA)
                ?: android.content.pm.ProviderInfo().apply {
                    this.authority = authority
                    name = androidx.core.content.FileProvider::class.java.name
                    packageName = app.packageName
                    grantUriPermissions = true
                    exported = false
                }
            org.robolectric.Robolectric.buildContentProvider(androidx.core.content.FileProvider::class.java).create(info)
            // attachInfo drops the cached strategy: install ours after it.
            synchronized(cache) { cache.clear() }
            if (File.separatorChar == '\\') installWindowsPathStrategy(cache)
        }

        /**
         * FileProvider compares canonical paths with '/' and so never matches on a Windows
         * JVM (the build PC); on Linux CI and on phones the real strategy is used. This one
         * serves exactly what res/xml/file_paths.xml configures: cache/exports/.
         */
        private fun installWindowsPathStrategy(cache: MutableMap<String, Any>) {
            val app = RuntimeEnvironment.getApplication()
            val authority = app.packageName + ".files"
            val root = File(app.cacheDir, "exports").apply { mkdirs() }.canonicalFile
            val iface = Class.forName("androidx.core.content.FileProvider\$PathStrategy")
            val proxy = java.lang.reflect.Proxy.newProxyInstance(iface.classLoader, arrayOf(iface)) { self, m, a ->
                when (m.name) {
                    "getUriForFile" -> {
                        val file = (a!![0] as File).canonicalFile
                        if (!file.path.startsWith(root.path + File.separator)) throw IllegalArgumentException("Failed to find configured root that contains $file (root $root)")
                        val rel = file.path.substring(root.path.length + 1).replace('\\', '/')
                        Uri.Builder().scheme("content").authority(authority).encodedPath("exports/" + Uri.encode(rel, "/")).build()
                    }
                    "getFileForUri" -> {
                        val u = a!![0] as Uri
                        val segs = u.pathSegments
                        if (segs.size < 2 || segs[0] != "exports") throw IllegalArgumentException("Unable to find configured root for $u")
                        val file = File(root, segs.drop(1).joinToString(File.separator)).canonicalFile
                        if (!file.path.startsWith(root.path + File.separator)) throw SecurityException("Resolved path jumped beyond configured root")
                        file
                    }
                    "hashCode" -> System.identityHashCode(self)
                    "equals" -> self === a?.get(0)
                    else -> "WindowsPathStrategy($root)"
                }
            }
            synchronized(cache) { cache[authority] = proxy }
        }

        const val CLOSED ="the app is closed (you are on the phone's home screen); use: launch"

        /** Splits a command line; "double quotes" group words, \" is a literal quote. */
        fun tokens(line: String): List<String> {
            val out = ArrayList<String>()
            val cur = StringBuilder()
            var inQ = false
            var had = false
            var i = 0
            while (i < line.length) {
                val ch = line[i]
                when {
                    ch == '\\' && i + 1 < line.length && line[i + 1] == '"' -> { cur.append('"'); i++; had = true }
                    ch == '"' -> { inQ = !inQ; had = true }
                    ch.isWhitespace() && !inQ -> { if (had || cur.isNotEmpty()) out.add(cur.toString()); cur.clear(); had = false }
                    else -> { cur.append(ch); had = true }
                }
                i++
            }
            if (had || cur.isNotEmpty()) out.add(cur.toString())
            return out
        }

        val HELP = """
            Commands:
              tap "<label>" [#n]          tap a visible element (n-th match), or tap @<number> from the last screen list
              longpress "<label>" [#n]    touch and hold
              type "<field>" "<text>"     tap a text field and type
              clear "<field>"             empty a text field
              scroll down|up|left|right ["<something inside the area>"]
              swipe left|right|up|down ["<label>"]
              drag "<label>" to "<label>" hold, move, release
              back                        the phone's Back gesture
              wait <ms>                   let time pass
              rotate | fontscale <x> | locale ar|en | darkmode on|off
              screenshot | quit | launch (reopen the app after it closed)
            The simulated world:
              pages                       list what the camera can be pointed at
              camera_sees <page-id>       point the camera at a page (or: nothing)
              gallery_has <file> [page-id|garbage|empty|truncated|huge]
              pdf_file <name.pdf> <pages> [protected|broken]
              files                       list files in the gallery / Files app and Downloads
              share_in <file> [more]      another app shares files to Scan Kiwi
              pick "<file>" [more] | pick_cancel      answer a system file picker
              pick_result ok [name] | cancel | auto on|off   answer Save dialogs
              permission allow|deny       answer a system permission dialog
              whatsapp_target [off]       WhatsApp is chosen in share sheets and reports what it got
        """.trimIndent()
    }
}
