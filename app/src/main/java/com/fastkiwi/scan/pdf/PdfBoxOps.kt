// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.pdf

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxConfig
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import java.io.IOException

/** Why an operation on an external PDF failed. Codes travel between processes as ints. */
enum class PdfError {
    NEEDS_PASSWORD, BAD_PASSWORD, MALFORMED, UNSUPPORTED, NO_SPACE, IO, TIMEOUT, CRASHED, CANCELLED;

    companion object {
        fun of(code: Int): PdfError = entries.getOrElse(code) { MALFORMED }
    }
}

class PdfOpException(val error: PdfError, cause: Throwable? = null) : IOException(error.name, cause)

/**
 * Lossless operations on external PDFs with PdfBox-Android (Apache-2.0). Pages are copied
 * object for object: content streams, images and fonts are written back with their original
 * bytes and filters, so nothing is re-encoded or rasterised.
 *
 * This code parses untrusted files. In the app it only ever runs inside the isolated `:pdf`
 * process ([PdfService]); the main process talks to it through [IsolatedPdfEngine], which
 * enforces the timeout and survives a crash here.
 */
class PdfBoxOps(ctx: Context, private val tempDir: File = File(ctx.cacheDir, "pdfwork")) {

    init {
        PDFBoxResourceLoader.init(ctx.applicationContext ?: ctx)
        // No rendering here: never scan the system fonts (only the bundled last-resort font,
        // which PdfBox's font mapper always opens, is used).
        PDFBoxConfig.setFontLoadLevel(PDFBoxConfig.FontLoadLevel.NONE)
        tempDir.mkdirs()
    }

    private fun memory(): MemoryUsageSetting = MemoryUsageSetting.setupMixed(MAIN_MEMORY_BYTES).setTempDir(tempDir)

    /** Opens [file]; maps password and parser failures to [PdfOpException]. */
    fun open(file: File, password: String?): PDDocument = try {
        PDDocument.load(file, password ?: "", memory())
    } catch (e: InvalidPasswordException) {
        throw PdfOpException(if (password.isNullOrEmpty()) PdfError.NEEDS_PASSWORD else PdfError.BAD_PASSWORD, e)
    } catch (e: PdfOpException) {
        throw e
    } catch (e: NoClassDefFoundError) {
        // Certificate (public-key) encryption needs BouncyCastle, which we do not ship.
        throw PdfOpException(PdfError.UNSUPPORTED, e)
    } catch (e: IOException) {
        throw PdfOpException(PdfError.MALFORMED, e)
    }

    fun pageCount(file: File, password: String?): Int = open(file, password).use { it.numberOfPages }

    /**
     * Writes a new PDF to [out] with the pages of [plan], taken from [inputs] (PDF-01..05, 11).
     * The result is not encrypted. Returns the page count after re-reading [out].
     */
    fun assemble(inputs: List<File>, passwords: List<String?>, plan: List<PageRef>, out: File): Int {
        require(plan.isNotEmpty()) { "empty plan" }
        val docs = ArrayList<PDDocument>()
        try {
            for ((i, f) in inputs.withIndex()) docs.add(open(f, passwords.getOrNull(i)).also { it.isAllSecurityToBeRemoved = true })
            PDDocument(memory()).use { dest ->
                // Source page dictionary -> its copy, to re-point links between copied pages.
                val copies = HashMap<COSDictionary, COSDictionary>()
                val pending = ArrayList<Pair<COSDictionary, List<COSDictionary>>>()
                var lastBox: PDRectangle? = null
                for (ref in plan) {
                    if (ref.isBlank) {
                        val box = lastBox ?: PDRectangle.A4
                        dest.addPage(PDPage(PDRectangle(box.lowerLeftX, box.lowerLeftY, box.width, box.height)))
                        continue
                    }
                    val src = docs.getOrNull(ref.source) ?: throw IllegalArgumentException("no input ${ref.source}")
                    if (ref.index !in 0 until src.numberOfPages) throw IllegalArgumentException("no page ${ref.index + 1}")
                    val sp = src.getPage(ref.index)
                    val copy = copyPage(sp)
                    copy.rotation = PagePlans.norm(sp.rotation + ref.rotate)
                    dest.addPage(copy)
                    lastBox = copy.mediaBox
                    copies.putIfAbsent(sp.cosObject, copy.cosObject)
                    pending.add(copy.cosObject to annotationsOf(copy.cosObject))
                }
                for ((page, annots) in pending) fixAnnotations(page, annots, copies)
                dest.save(out)
            }
        } finally {
            docs.forEach { runCatching { it.close() } }
        }
        // Re-read what we wrote: the page count must match the plan.
        val n = try {
            PDDocument.load(out, memory()).use { it.numberOfPages }
        } catch (e: IOException) {
            throw PdfOpException(PdfError.IO, e)
        }
        if (n != plan.size) throw PdfOpException(PdfError.IO)
        return n
    }

    /**
     * PDF-14: writes [file] without its protection, only when [password] opens it (the
     * user's own password; nothing is guessed or bypassed). Returns the page count.
     */
    fun unlock(file: File, password: String, out: File): Int {
        open(file, password).use { doc ->
            // An unprotected file is simply copied, so the caller has one path.
            doc.isAllSecurityToBeRemoved = true
            // A classic xref table, which [PdfValidator] checks before the file is used.
            doc.document.setIsXRefStream(false)
            doc.save(out)
        }
        return PDDocument.load(out, memory()).use { it.numberOfPages }
    }

    /** PDF-12 for external files: text per page in logical order (see [ArabicText.fix]). */
    fun text(file: File, password: String?): List<String> = open(file, password).use { doc ->
        val stripper = PDFTextStripper()
        stripper.lineSeparator = "\n"
        // By position: rotated pages (/Rotate 90) otherwise come out one character per line.
        stripper.sortByPosition = true
        List(doc.numberOfPages) { i ->
            stripper.startPage = i + 1
            stripper.endPage = i + 1
            ArabicText.fix(stripper.getText(doc))
        }
    }

    /**
     * Shallow copy of a page dictionary: the same content, resource, image and font objects
     * (so their bytes are written back unchanged), with inherited attributes made explicit
     * and the links into the old page tree removed.
     */
    private fun copyPage(sp: PDPage): PDPage {
        val d = COSDictionary(sp.cosObject)
        d.removeItem(COSName.PARENT)
        d.removeItem(B) // article beads point into the old document's threads
        d.removeItem(STRUCT_PARENTS) // no structure tree is copied
        val p = PDPage(d)
        if (!d.containsKey(COSName.MEDIA_BOX)) p.mediaBox = sp.mediaBox
        if (!d.containsKey(COSName.CROP_BOX) && sp.cropBox != sp.mediaBox) p.cropBox = sp.cropBox
        if (!d.containsKey(COSName.RESOURCES)) sp.resources?.let { p.resources = it }
        return p
    }

    private fun annotationsOf(page: COSDictionary): List<COSDictionary> {
        val arr = page.getDictionaryObject(COSName.ANNOTS) as? COSArray ?: return emptyList()
        return (0 until arr.size()).mapNotNull { arr.getObject(it) as? COSDictionary }
    }

    /**
     * Copies a page's annotations so that none still points at a page of the source file
     * (that would pull pages the user did not choose, e.g. by "extract", into the output):
     * /P is the new page, link targets are re-pointed or dropped, form widgets lose their
     * field parent (their appearance stays).
     */
    private fun fixAnnotations(page: COSDictionary, annots: List<COSDictionary>, copies: Map<COSDictionary, COSDictionary>) {
        if (annots.isEmpty()) return
        val map = HashMap<COSDictionary, COSDictionary>()
        for (a in annots) map[a] = COSDictionary(a)
        val out = COSArray()
        for (c in map.values) {
            c.setItem(COSName.P, page)
            c.removeItem(STRUCT_PARENT)
            // Widgets: the field tree links to other pages.
            if (c.getDictionaryObject(COSName.PARENT) != null && c.getCOSName(COSName.SUBTYPE) == WIDGET) c.removeItem(COSName.PARENT)
            // Popups and their markup parents: keep only pairs on this page.
            for (key in listOf(POPUP, COSName.PARENT, IRT)) {
                val target = c.getDictionaryObject(key) as? COSDictionary ?: continue
                val mapped = map[target]
                if (mapped != null) c.setItem(key, mapped) else c.removeItem(key)
            }
            if (!fixDestination(c, COSName.DEST, copies)) c.removeItem(COSName.DEST)
            val action = c.getDictionaryObject(COSName.A) as? COSDictionary
            if (action != null) {
                val ac = COSDictionary(action)
                ac.removeItem(COSName.NEXT)
                if (fixDestination(ac, COSName.D, copies)) c.setItem(COSName.A, ac) else c.removeItem(COSName.A)
            }
            out.add(c)
        }
        page.setItem(COSName.ANNOTS, out)
    }

    /** True when [key] is absent, a named destination, or an explicit one re-pointed to a copied page. */
    private fun fixDestination(holder: COSDictionary, key: COSName, copies: Map<COSDictionary, COSDictionary>): Boolean {
        val dest = holder.getDictionaryObject(key) as? COSArray ?: return true
        val target = dest.getObject(0) as? COSDictionary ?: return true // page number (remote) or named
        val mapped = copies[target] ?: return false
        val nd = COSArray()
        nd.add(mapped)
        for (i in 1 until dest.size()) nd.add(dest.get(i))
        holder.setItem(key, nd)
        return true
    }

    companion object {
        /** Above this, PdfBox spills parsed data to temp files in [tempDir]. */
        const val MAIN_MEMORY_BYTES = 24L * 1024 * 1024

        private val B: COSName = COSName.getPDFName("B")
        private val IRT: COSName = COSName.getPDFName("IRT")
        private val WIDGET: COSName = COSName.getPDFName("Widget")
        private val STRUCT_PARENT: COSName = COSName.getPDFName("StructParent")
        private val STRUCT_PARENTS: COSName = COSName.getPDFName("StructParents")
        private val POPUP: COSName = COSName.getPDFName("Popup")
    }
}
