// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.gates

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import org.robolectric.Robolectric
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.shadow.api.Shadow
import org.robolectric.util.ReflectionHelpers
import java.io.File
import java.io.FileInputStream
import java.io.IOException

/**
 * Files of "other apps" in the simulated phone: the gallery / Files app (what the
 * system picker offers and what other apps share to Scan Kiwi) and the place the
 * system Save dialog writes to. A real ContentProvider, so the app reads it through
 * the same ContentResolver calls (query, getType, openFileDescriptor, openInputStream,
 * openOutputStream, DocumentsContract.createDocument) as on a phone.
 */
class WorldProvider : ContentProvider() {
    override fun onCreate() = true

    /** Overrides a file's MIME type (fuzzing wrong types); null = from the extension. */
    val typeOverride = HashMap<String, String?>()

    /** Makes reads of a file fail like a revoked permission or a vanished cloud file. */
    val failing = HashSet<String>()

    private fun fileOf(uri: Uri): File {
        val segs = uri.pathSegments
        val root = World.root ?: throw IllegalStateException("world not set up")
        val rel = when {
            segs.size >= 4 && segs[0] == "tree" && segs[2] == "document" -> segs[3]
            segs.size >= 2 && segs[0] == "tree" -> segs[1]
            segs.size >= 2 -> segs[0] + "/" + segs.drop(1).joinToString("/")
            else -> throw IllegalArgumentException("bad uri $uri")
        }
        val f = File(root, rel).canonicalFile
        if (!f.path.startsWith(root.canonicalPath)) throw SecurityException("outside world")
        return f
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor {
        val f = fileOf(uri)
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val c = MatrixCursor(cols)
        if (f.exists()) c.addRow(cols.map { col ->
            when (col) {
                OpenableColumns.DISPLAY_NAME, DocumentsContract.Document.COLUMN_DISPLAY_NAME -> f.name
                OpenableColumns.SIZE, DocumentsContract.Document.COLUMN_SIZE -> f.length()
                DocumentsContract.Document.COLUMN_MIME_TYPE -> getType(uri)
                DocumentsContract.Document.COLUMN_DOCUMENT_ID -> f.relativeTo(World.root!!).path.replace('\\', '/')
                else -> null
            }
        }.toTypedArray())
        return c
    }

    override fun getType(uri: Uri): String? {
        val f = runCatching { fileOf(uri) }.getOrNull() ?: return null
        if (typeOverride.containsKey(f.name)) return typeOverride[f.name]
        return World.mimeOf(f.name)
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val f = fileOf(uri)
        if (f.name in failing) throw java.io.FileNotFoundException("permission revoked: ${f.name}")
        if (mode.contains('w')) {
            f.parentFile?.mkdirs()
            World.onWrite?.invoke(f)
        } else if (!f.exists()) throw java.io.FileNotFoundException(f.name)
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.parseMode(mode))
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method == "android:createDocument" && extras != null) {
            val name = extras.getString(DocumentsContract.Document.COLUMN_DISPLAY_NAME) ?: "file"
            val tree = DocumentsContract.buildTreeDocumentUri(World.AUTHORITY, "saved")
            var n = name
            var i = 1
            while (File(World.root, "saved/$n").exists()) n = name.substringBeforeLast('.') + " (${i++})." + name.substringAfterLast('.', "")
            File(World.root, "saved/$n").also { it.parentFile?.mkdirs(); it.createNewFile() }
            val doc = DocumentsContract.buildDocumentUriUsingTree(tree, "saved/$n")
            return Bundle().apply { putParcelable("uri", doc) }
        }
        return super.call(method, arg, extras)
    }
}

/** The simulated outside world: folders, URIs and the provider instance. */
object World {
    const val AUTHORITY = "com.example.worldfiles"

    @Volatile var root: File? = null
    @Volatile var provider: WorldProvider? = null
    @Volatile var onWrite: ((File) -> Unit)? = null

    /** Creates the folders and registers the provider (once per Robolectric test). */
    fun setUp(dir: File): WorldProvider {
        root = dir.apply { mkdirs() }
        File(dir, "files").mkdirs()
        File(dir, "saved").mkdirs()
        val p = Robolectric.setupContentProvider(WorldProvider::class.java, AUTHORITY)
        provider = p
        return p
    }

    fun filesDir() = File(root, "files")
    fun savedDir() = File(root, "saved")

    fun uriOf(name: String): Uri = Uri.parse("content://$AUTHORITY/files/" + Uri.encode(name))
    fun saveUri(name: String): Uri = Uri.parse("content://$AUTHORITY/saved/" + Uri.encode(name))
    fun treeUri(): Uri = DocumentsContract.buildTreeDocumentUri(AUTHORITY, "saved")

    fun put(name: String, bytes: ByteArray): Uri {
        File(filesDir(), name).writeBytes(bytes)
        return uriOf(name)
    }

    fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "heic", "heif" -> "image/heic"
        "gif" -> "image/gif"
        "pdf" -> "application/pdf"
        "txt" -> "text/plain"
        else -> "application/octet-stream"
    }
}

/**
 * Robolectric has no pdfium, so android.graphics.pdf.PdfRenderer fails with a
 * NoSuchMethodError on the JVM. This shadow renders with PDFBox and fails the way
 * the platform does: SecurityException for a password-protected file, IOException
 * for anything that is not a readable PDF. (A simulation of the platform renderer,
 * not of pdfium's own parser.)
 */
@Implements(PdfRenderer::class)
class ShadowWorldPdfRenderer {
    @RealObject lateinit var real: PdfRenderer
    internal var doc: org.apache.pdfbox.pdmodel.PDDocument? = null
    internal var renderer: org.apache.pdfbox.rendering.PDFRenderer? = null
    private var input: ParcelFileDescriptor? = null

    @Implementation
    protected fun __constructor__(pfd: ParcelFileDescriptor) {
        input = pfd
        val bytes = try {
            val ch = FileInputStream(pfd.fileDescriptor).channel
            ch.position(0)
            val size = ch.size()
            if (size > 512L * 1024 * 1024) throw IOException("file too large")
            val buf = java.nio.ByteBuffer.allocate(size.toInt())
            while (buf.hasRemaining() && ch.read(buf) >= 0) Unit
            buf.array()
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException("cannot read file", e)
        }
        try {
            // PDFBox stands in for pdfium; its own loops on hostile files must not look like an app hang.
            val d = timed(10_000) {
                org.apache.pdfbox.Loader.loadPDF(bytes).also { it.numberOfPages }
            }
            doc = d
            renderer = org.apache.pdfbox.rendering.PDFRenderer(d)
        } catch (e: org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException) {
            throw SecurityException("password required or incorrect password")
        } catch (e: Throwable) {
            if (e is OutOfMemoryError) throw e
            throw IOException("file not in PDF format or corrupted")
        }
    }

    internal companion object {
        private val pool = java.util.concurrent.Executors.newCachedThreadPool { r -> Thread(r, "pdf-sim").apply { isDaemon = true } }

        /** Runs [block] with a time limit; the cause of a failure is rethrown as is. */
        fun <T> timed(ms: Long, block: () -> T): T {
            val f = pool.submit<T> { block() }
            try {
                return f.get(ms, java.util.concurrent.TimeUnit.MILLISECONDS)
            } catch (e: java.util.concurrent.ExecutionException) {
                throw e.cause ?: e
            } catch (e: java.util.concurrent.TimeoutException) {
                f.cancel(true)
                throw IOException("simulated renderer gave up (timeout)")
            }
        }
    }

    @Implementation
    protected fun getPageCount(): Int = try {
        doc?.numberOfPages ?: 0
    } catch (e: Throwable) {
        0
    }

    @Implementation
    protected fun openPage(index: Int): PdfRenderer.Page {
        if (index < 0 || index >= getPageCount()) throw IllegalArgumentException("Invalid page index")
        return ReflectionHelpers.callConstructor(
            PdfRenderer.Page::class.java,
            ReflectionHelpers.ClassParameter.from(PdfRenderer::class.java, real),
            ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType, index),
        )
    }

    @Implementation
    protected fun close() {
        runCatching { doc?.close() }
        doc = null
        runCatching { input?.close() }
    }
}

@Implements(PdfRenderer.Page::class)
class ShadowWorldPdfPage {
    private var owner: ShadowWorldPdfRenderer? = null
    private var index = 0
    private var w = 0
    private var h = 0

    @Implementation
    protected fun __constructor__(renderer: PdfRenderer, index: Int) {
        val o = Shadow.extract<ShadowWorldPdfRenderer>(renderer)
        owner = o
        this.index = index
        val box = try {
            o.doc!!.getPage(index).cropBox
        } catch (e: Throwable) {
            null
        }
        w = ((box?.width ?: 612f).takeIf { it.isFinite() && it > 0 } ?: 612f).toInt().coerceAtLeast(1)
        h = ((box?.height ?: 792f).takeIf { it.isFinite() && it > 0 } ?: 792f).toInt().coerceAtLeast(1)
    }

    @Implementation protected fun getIndex() = index
    @Implementation protected fun getWidth() = w
    @Implementation protected fun getHeight() = h

    @Implementation
    protected fun render(dest: Bitmap, clip: Rect?, transform: Matrix?, mode: Int) {
        val r = owner?.renderer ?: throw IllegalStateException("Already closed")
        val scale = dest.width.toFloat() / w
        val img = try {
            ShadowWorldPdfRenderer.timed(20_000) { PdfRaster.render(r, index, scale, dest.width, dest.height) }
        } catch (e: Throwable) {
            if (e is OutOfMemoryError) throw e
            null // pdfium draws what it can; a broken content stream leaves the page white
        } ?: return
        android.graphics.Canvas(dest).drawBitmap(img, 0f, 0f, null)
        img.recycle()
    }

    @Implementation protected fun close() = Unit
}


/**
 * PDFBox renders to java.awt images, which the Android compile classpath does not
 * know; they exist at run time on the JVM, so they are reached by reflection.
 */
object PdfRaster {
    /** Renders page [index] of [renderer] at [scale] (1 = 72 dpi) into an ARGB Android bitmap. */
    fun render(renderer: org.apache.pdfbox.rendering.PDFRenderer, index: Int, scale: Float, maxW: Int = Int.MAX_VALUE, maxH: Int = Int.MAX_VALUE): Bitmap {
        val m = renderer.javaClass.getMethod("renderImage", Int::class.javaPrimitiveType, Float::class.javaPrimitiveType, org.apache.pdfbox.rendering.ImageType::class.java)
        val img = try {
            m.invoke(renderer, index, scale, org.apache.pdfbox.rendering.ImageType.RGB)
        } catch (e: java.lang.reflect.InvocationTargetException) {
            throw e.cause ?: e
        }
        val cls = img.javaClass
        val w = minOf(maxW, cls.getMethod("getWidth").invoke(img) as Int)
        val h = minOf(maxH, cls.getMethod("getHeight").invoke(img) as Int)
        val px = cls.getMethod("getRGB", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, IntArray::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .invoke(img, 0, 0, w, h, null, 0, w) as IntArray
        for (i in px.indices) px[i] = px[i] or (0xFF shl 24)
        val out = Bitmap.createBitmap(w.coerceAtLeast(1), h.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }
}
