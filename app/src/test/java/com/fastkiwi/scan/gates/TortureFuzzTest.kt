// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.gates

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import com.fastkiwi.scan.MainActivity
import com.fastkiwi.scan.data.DocumentStore
import com.fastkiwi.scan.ui.AppViewModel
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Fuzz: 300+ malformed or hostile inputs through the import paths (system picker and
 * "Share to Scan Kiwi"). The app must import cleanly or refuse, never crash, never hang
 * (per-case limit) and never leave an empty document behind.
 */
@Category(TortureSuite::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "ar-w400dp-h860dp-xxhdpi", shadows = [ShadowWorldPdfRenderer::class, ShadowWorldPdfPage::class])
class TortureFuzzTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val tmp = TemporaryFolder()
    private lateinit var uncaught: Gates.Uncaught
    private val report = StringBuilder()

    @Before
    fun setUp() {
        Gates.assumeTorture()
        tmp.create()
        uncaught = Gates.Uncaught().install()
        Gates.wipe(DocumentStore(app.filesDir))
        World.setUp(tmp.newFolder("world"))
    }

    @After
    fun tearDown() {
        if (!Gates.torture) return
        uncaught.uninstall()
        tmp.delete()
        Gates.wipe(DocumentStore(app.filesDir))
        File("build").mkdirs()
        File("build/torture-fuzz.txt").appendText(report.toString())
    }

    private class Outcome(val id: String, val result: String, val problem: String?)

    /** Runs one case; [act] starts the import. Checks crash, hang, empty documents. */
    private fun runCase(id: String, vm: AppViewModel, perCaseMs: Long = 45_000, beforeCleanup: () -> Unit = {}, act: () -> Unit): Outcome {
        val before = vm.store.list().map { it.id }.toSet()
        val t0 = System.nanoTime()
        val crash = runCatching {
            act()
            if (!Gates.awaitIdle(vm, perCaseMs)) return Outcome(id, "HANG", "still busy after ${perCaseMs / 1000} s")
        }.exceptionOrNull()
        val ms = Gates.ms(t0)
        if (crash != null) return Outcome(id, "CRASH", Gates.stack(crash).take(3000))
        uncaught.errors.poll()?.let { return Outcome(id, "CRASH", "${it.first}: ${Gates.stack(it.second).take(3000)}") }
        val docs = vm.store.list()
        val empty = docs.filter { it.pages.isEmpty() }
        val created = docs.filter { it.id !in before }
        // A folder whose doc.json is gone but whose pages remain: the app deleted it, and only
        // Windows (a decoder stream not yet closed) kept files; on a phone the delete succeeds.
        vm.store.dir("x").parentFile?.listFiles()?.filter { it.isDirectory && !File(it, "doc.json").exists() }?.forEach { Gates.deleteDir(it) }
        val problems = Gates.integrity(vm.store)
        beforeCleanup()
        val result = if (created.isEmpty()) "refused" else "imported ${created.sumOf { it.pages.size }} page(s)"
        for (d in created) {
            vm.store.delete(d.id)
            if (!Gates.deleteDir(vm.store.dir(d.id))) return Outcome(id, result, "test could not delete ${vm.store.dir(d.id).list()?.toList()}")
        }
        vm.refresh()
        Gates.idleMain()
        return when {
            empty.isNotEmpty() -> Outcome(id, result, "empty document left behind: ${empty.map { it.name }}")
            problems.isNotEmpty() -> Outcome(id, result, "inconsistent files: $problems")
            else -> Outcome(id, "$result in $ms ms", null)
        }
    }

    private fun check(outcomes: List<Outcome>, title: String) {
        report.appendLine("== $title (${outcomes.size} cases)")
        outcomes.forEach { report.appendLine("${it.id}: ${it.result}${it.problem?.let { p -> "  PROBLEM: ${p.lineSequence().first()}" } ?: ""}") }
        val bad = outcomes.filter { it.problem != null }
        Gates.perf("fuzz: $title: ${outcomes.size} cases, ${outcomes.count { it.result.startsWith("imported") }} imported, ${outcomes.count { it.result.startsWith("refused") }} refused, ${bad.size} problems")
        assertTrue("fuzz problems ($title):\n" + bad.joinToString("\n\n") { "${it.id}: ${it.result}\n${it.problem}" }, bad.isEmpty())
    }

    private fun put(c: Fuzz.Case): Uri {
        val uri = World.put(c.name, c.bytes)
        val p = World.provider!!
        if (c.mime != null) p.typeOverride[c.name] = c.mime.ifEmpty { null } else p.typeOverride.remove(c.name)
        return uri
    }

    /** Images and PDFs through the system picker path (import into a new document). */
    @Test(timeout = 1_200_000)
    fun pickerImportOfHostileFiles() {
        var vm = AppViewModel(app)
        drainMessages(vm)
        val cases = Fuzz.imageCases() + Fuzz.pdfCases()
        val outcomes = cases.map { c ->
            val uri = put(c)
            val o = runCase(c.id, vm) { vm.importUris(null, listOf(uri)) { } }
            File(World.filesDir(), c.name).delete()
            // A stuck job keeps the old view model busy for ever: carry on with a new one.
            if (o.result == "HANG") vm = AppViewModel(app).also { drainMessages(it) }
            o
        }.toMutableList()
        // Batches: good and bad files together (the good ones must still arrive), revoked access, vanished files.
        val good = put(Fuzz.Case("good", "good.jpg", SyntheticPages.jpeg(Gates.pageBitmap(600, 800), 80)))
        val bad = put(Fuzz.Case("bad", "bad.jpg", ByteArray(100) { 7 }))
        val pdfBad = put(Fuzz.Case("badpdf", "bad.pdf", "%PDF-1.4 broken".toByteArray()))
        val revoked = put(Fuzz.Case("revoked", "revoked.jpg", SyntheticPages.jpeg(Gates.pageBitmap(300, 400), 80)))
        World.provider!!.failing.add("revoked.jpg")
        val gone = World.uriOf("does-not-exist.jpg")
        outcomes += runCase("batch-good-and-bad", vm) { vm.importUris(null, listOf(bad, good, pdfBad, gone, revoked, good)) { } }
        outcomes += runCase("batch-all-bad", vm) { vm.importUris(null, listOf(bad, pdfBad, gone, revoked)) { } }
        outcomes += runCase("revoked-only", vm) { vm.importUris(null, listOf(revoked)) { } }
        outcomes += runCase("vanished-only", vm) { vm.importUris(null, listOf(gone)) { } }
        outcomes += runCase("foreign-authority-unknown", vm) { vm.importUris(null, listOf(Uri.parse("content://com.nobody.here/x.jpg"))) { } }
        // Into an existing document: a failed import must leave it as it was.
        val doc = Gates.seed(vm.store, 2, w = 400, h = 500)
        outcomes += runCase("existing-doc-bad-files", vm) { vm.importUris(doc.id, listOf(bad, pdfBad, gone)) { } }
        assertTrue("existing document changed", vm.store.load(doc.id)!!.pages.size == 2)
        check(outcomes, "picker import")
    }

    /** "Share to Scan Kiwi" intents with missing, odd or hostile extras and URIs. */
    @Test(timeout = 900_000)
    fun shareIntentsWithOddExtras() {
        resetViewModelFactory()
        BlackBox.resetFileProviderCache()
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val act = controller.get()
        val vm = act.vm
        val good = put(Fuzz.Case("good", "good.jpg", SyntheticPages.jpeg(Gates.pageBitmap(600, 800), 80)))
        val pdf = put(Fuzz.Case("pdf", "two.pdf", SyntheticPages.pdf(2)))
        val garbage = put(Fuzz.Case("garbage", "garbage.jpg", ByteArray(5000) { (it * 31).toByte() }))
        val secret = File(app.filesDir, "signature/signature.png").apply { parentFile?.mkdirs(); writeBytes(SyntheticPages.encode(Gates.pageBitmap(100, 50), "png")) }
        val ownExport = File(app.cacheDir, "exports/private.pdf").apply { parentFile?.mkdirs(); writeBytes(SyntheticPages.pdf(1)) }
        val own = "${app.packageName}.files"
        fun send(vararg uris: Uri?, type: String? = "image/*") = Intent(Intent.ACTION_SEND).apply {
            uris.firstOrNull()?.let { putExtra(Intent.EXTRA_STREAM, it) }
            this.type = type
        }
        fun multi(list: ArrayList<Uri?>?, type: String? = "*/*") = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            if (list != null) putParcelableArrayListExtra(Intent.EXTRA_STREAM, list)
            this.type = type
        }
        // Expected: true = may import, false = must import nothing (security or nothing to read).
        val cases: List<Triple<String, Intent, Boolean>> = listOf(
            Triple("send-good", send(good), true),
            Triple("send-pdf", send(pdf, type = "application/pdf"), true),
            Triple("send-no-stream", send(null), false),
            Triple("send-no-type", send(good, type = null), true),
            Triple("send-wrong-type", send(pdf, type = "image/jpeg"), true),
            Triple("send-garbage", send(garbage), false),
            Triple("send-stream-as-string", Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, good.toString()).setType("image/*"), false),
            Triple("send-stream-as-int", Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, 42).setType("image/*"), false),
            Triple("send-text-only", Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, "hello").setType("text/plain"), false),
            Triple("file-uri-signature", send(Uri.fromFile(secret)), false),
            Triple("file-uri-world", send(Uri.fromFile(File(World.filesDir(), "good.jpg"))), false),
            Triple("own-provider", send(Uri.parse("content://$own/exports/private.pdf"), type = "application/pdf"), false),
            Triple("own-provider-user10", send(Uri.parse("content://10@$own/exports/private.pdf"), type = "application/pdf"), false),
            Triple("own-provider-upper", send(Uri.parse("content://${own.uppercase()}/exports/private.pdf"), type = "application/pdf"), false),
            Triple("own-provider-traversal", send(Uri.parse("content://$own/exports/../../files/signature/signature.png")), false),
            Triple("http-uri", send(Uri.parse("https://example.com/a.jpg")), false),
            Triple("no-scheme", send(Uri.parse("/data/data/${app.packageName}/files/signature/signature.png")), false),
            Triple("content-no-authority", send(Uri.parse("content:///x.jpg")), false),
            Triple("content-at-only", send(Uri.parse("content://10@/x.jpg")), false),
            Triple("content-unknown-provider", send(Uri.parse("content://com.nobody.here/x.jpg")), false),
            Triple("content-missing-file", send(World.uriOf("missing.jpg")), false),
            Triple("content-very-long", send(Uri.parse("content://${World.AUTHORITY}/files/" + "a".repeat(5000) + ".jpg")), false),
            Triple("multi-null-list", multi(null), false),
            Triple("multi-empty", multi(arrayListOf()), false),
            Triple("multi-with-null", multi(arrayListOf(null, good)), true),
            Triple("multi-good-and-own", multi(arrayListOf(good, Uri.parse("content://$own/exports/private.pdf"), Uri.fromFile(secret))), true),
            Triple("multi-50-garbage", multi(ArrayList(List(50) { garbage })), false),
            Triple("multi-mixed", multi(arrayListOf(good, garbage, pdf, World.uriOf("missing.jpg"))), true),
            Triple("multi-stream-single-uri", Intent(Intent.ACTION_SEND_MULTIPLE).putExtra(Intent.EXTRA_STREAM, good).setType("image/*"), false),
            Triple("view-good", Intent(Intent.ACTION_VIEW, good), true),
            Triple("view-null-data", Intent(Intent.ACTION_VIEW), false),
            Triple("view-file", Intent(Intent.ACTION_VIEW, Uri.fromFile(secret)), false),
            Triple("unknown-action", Intent("com.evil.ACTION").putExtra(Intent.EXTRA_STREAM, good), false),
            Triple("no-action", Intent().putExtra(Intent.EXTRA_STREAM, good), false),
            Triple("quick-scan-shortcut", Intent(com.fastkiwi.scan.ACTION_QUICK_SCAN), false),
            Triple("quick-scan-twice", Intent(com.fastkiwi.scan.ACTION_QUICK_SCAN), false),
            Triple("clipdata-only", Intent(Intent.ACTION_SEND).setType("image/*").apply { clipData = ClipData.newRawUri(null, good) }, false),
            Triple("extras-bundle-junk", send(good).apply { putExtra("android.intent.extra.STREAM_EXTRA", ByteArray(100_000)); putExtra(Intent.EXTRA_TITLE, "x".repeat(10_000)) }, true),
            Triple("send-pdf-protected", send(put(Fuzz.Case("prot", "prot.pdf", SyntheticPages.pdf(1, password = "pw"))), type = "application/pdf"), false),
            Triple("send-pdf-broken", send(put(Fuzz.Case("brk", "brk.pdf", SyntheticPages.pdf(3).let { it.copyOf(it.size / 2) })), type = "application/pdf"), false),
        )
        val outcomes = ArrayList<Outcome>()
        for ((id, intent, mayImport) in cases) {
            // Back to Home before clean-up (a successful share opens the document).
            val o = runCase(id, vm, beforeCleanup = { while (vm.back()) Unit; Gates.awaitIdle(vm) }) { controller.newIntent(intent); Gates.idleMain() }
            val wrongImport = !mayImport && o.result.startsWith("imported")
            outcomes += if (wrongImport) Outcome(id, o.result, "imported although it must be refused") else o
        }
        assertTrue("own private files were read", vm.store.list().isEmpty())
        controller.pause().stop().destroy()
        check(outcomes, "share intents")
    }
}
