package com.fastkiwi.scan.core

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.security.SecureRandom
import java.util.Locale
import java.util.zip.Deflater

/** Page sizes in PDF points (1/72 inch). */
enum class PageSize(val widthPt: Float, val heightPt: Float) {
    A4(595.28f, 841.89f),
    LETTER(612f, 792f),
    LEGAL(612f, 1008f),
    A5(419.53f, 595.28f),
    FIT(0f, 0f), // page takes the image's own size
}

/** One word of recognised text, in image pixel coordinates. */
data class OcrWord(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int)

/** An image ready to embed. */
sealed class PdfImage(val width: Int, val height: Int) {
    /** Baseline JPEG bytes, embedded unchanged (DCTDecode). */
    class Jpeg(width: Int, height: Int, val bytes: ByteArray, val components: Int) : PdfImage(width, height)

    /** 1-bit packed rows, 1 = white (FlateDecode). */
    class Bitonal(width: Int, height: Int, val bits: ByteArray) : PdfImage(width, height)
}

class PdfPage(val image: PdfImage, val dpi: Float, val words: List<OcrWord> = emptyList())

/**
 * Minimal, standards-based PDF writer (ISO 32000): image pages, an optional
 * invisible text layer for search/copy (OCR), and optional AES-256 password
 * protection (Standard security handler, V5/R6).
 *
 * The images are embedded as-is (JPEG) or as 1-bit Flate, which is what keeps
 * our files small compared with re-encoding through android.graphics.pdf.
 */
class PdfWriter(
    private val pageSize: PageSize,
    private val title: String,
    private val password: String? = null,
    private val glyphlessFont: ByteArray? = null,
    private val random: SecureRandom = SecureRandom(),
) {
    /** Pages buffered by [addPage] before [begin] (the one-shot [write] API used by tests). */
    private val pending = ArrayList<PdfPage>()

    // Streaming state: after [begin], every page is written at once and dropped, so a
    // 200-page document never holds more than one page image in memory.
    private var w: Writer? = null
    private var crypt: PdfCrypt? = null
    private val pageIds = ArrayList<Int>()
    /** Object numbering: 1 catalog, 2 pages, 3 info; the rest in write order. */
    private var nextId = 4
    private var fontId = 0

    /** Starts a streamed file; pages passed to [addPage] afterwards go straight to [out]. */
    fun begin(out: OutputStream) {
        check(w == null) { "already started" }
        val wr = Writer(out)
        w = wr
        crypt = password?.let { PdfCrypt.create(it, random) }
        wr.raw("%PDF-1.7\n%âãÏÓ\n".toByteArray(Charsets.ISO_8859_1))
    }

    fun addPage(page: PdfPage) {
        val wr = w
        if (wr == null) pending.add(page) else writePage(wr, page)
    }

    /** Writes everything at once: buffered pages, then the document structure. */
    fun write(out: OutputStream) {
        begin(out)
        val wr = w!!
        for (p in pending) writePage(wr, p)
        pending.clear()
        finish()
    }

    /** Ends a streamed file: fonts, page tree, catalog, info, encryption, xref, trailer. */
    fun finish() {
        val wr = checkNotNull(w) { "begin() first" }
        val crypt = crypt
        val catalogId = 1
        val pagesId = 2
        val infoId = 3

        if (fontId != 0) {
            val font = glyphlessFont!!
            val cidFontId = fontId + 1; val descId = fontId + 2; val fileId2 = fontId + 3
            val cidMapId = fontId + 4; val toUniId = fontId + 5
            wr.obj(fontId, "<< /Type /Font /Subtype /Type0 /BaseFont /GlyphLessFont /Encoding /Identity-H " +
                "/DescendantFonts [$cidFontId 0 R] /ToUnicode $toUniId 0 R >>")
            wr.obj(cidFontId, "<< /Type /Font /Subtype /CIDFontType2 /BaseFont /GlyphLessFont " +
                "/CIDSystemInfo << /Registry (Adobe) /Ordering (Identity) /Supplement 0 >> " +
                "/FontDescriptor $descId 0 R /DW ${CHAR_WIDTH_UNITS} /CIDToGIDMap $cidMapId 0 R >>")
            wr.obj(descId, "<< /Type /FontDescriptor /FontName /GlyphLessFont /Flags 5 /FontBBox [0 0 500 1000] " +
                "/ItalicAngle 0 /Ascent 1000 /Descent 0 /CapHeight 1000 /StemV 80 /FontFile2 $fileId2 0 R >>")
            wr.stream(fileId2, "/Length1 ${font.size}", font, crypt, deflate = true)
            // Every CID maps to glyph 1 of the glyphless font.
            val map = ByteArray(65536 * 2)
            for (i in 0 until 65536) map[i * 2 + 1] = 1
            wr.stream(cidMapId, "", map, crypt, deflate = true)
            wr.stream(toUniId, "", TO_UNICODE.toByteArray(Charsets.ISO_8859_1), crypt, deflate = true)
        }

        val ext = if (crypt != null) " /Extensions << /ADBE << /BaseVersion /1.7 /ExtensionLevel 8 >> >>" else ""
        wr.obj(catalogId, "<< /Type /Catalog /Pages $pagesId 0 R$ext /ViewerPreferences << /DisplayDocTitle true >> >>")
        wr.obj(pagesId, "<< /Type /Pages /Count ${pageIds.size} /Kids [${pageIds.joinToString(" ") { "$it 0 R" }}] >>")
        val titleStr = pdfString(utf16(title), crypt)
        val producer = pdfString(utf16("Kiwi Scan"), crypt)
        wr.obj(infoId, "<< /Title $titleStr /Producer $producer /Creator $producer >>")
        val encryptId = if (crypt != null) nextId++ else 0
        if (crypt != null) wr.obj(encryptId, crypt.encryptDictionary())

        val fileId = ByteArray(16).also { random.nextBytes(it) }
        val xrefPos = wr.pos
        val sb = StringBuilder()
        sb.append("xref\n0 ${nextId}\n0000000000 65535 f \n")
        for (id in 1 until nextId) sb.append(String.format(Locale.ROOT, "%010d 00000 n \n", wr.offsets.getValue(id)))
        val idHex = hex(fileId)
        sb.append("trailer\n<< /Size $nextId /Root $catalogId 0 R /Info $infoId 0 R /ID [<$idHex> <$idHex>]")
        if (crypt != null) sb.append(" /Encrypt $encryptId 0 R")
        sb.append(" >>\nstartxref\n$xrefPos\n%%EOF\n")
        wr.raw(sb.toString().toByteArray(Charsets.ISO_8859_1))
        wr.flush()
    }

    private fun writePage(wr: Writer, p: PdfPage) {
        val crypt = crypt
        val hasText = p.words.isNotEmpty() && glyphlessFont != null
        // The font objects get their numbers with the first page that needs them.
        if (hasText && fontId == 0) { fontId = nextId; nextId += 6 }
        val pageId = nextId++
        val contentId = nextId++
        val imageId = nextId++
        pageIds.add(pageId)

        val img = p.image
        val imgWpt = img.width * 72f / p.dpi
        val imgHpt = img.height * 72f / p.dpi
        val (pw, ph) = when (pageSize) {
            PageSize.FIT -> imgWpt to imgHpt
            else -> {
                val landscape = img.width > img.height
                if (landscape) pageSize.heightPt to pageSize.widthPt else pageSize.widthPt to pageSize.heightPt
            }
        }
        // Fit the image inside the page, centred, keeping aspect ratio.
        val s = minOf(pw / img.width, ph / img.height)
        val dw = img.width * s
        val dh = img.height * s
        val ox = (pw - dw) / 2f
        val oy = (ph - dh) / 2f

        val font = if (hasText) " /Font << /F1 $fontId 0 R >>" else ""
        wr.obj(pageId, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${f(pw)} ${f(ph)}] " +
            "/Resources << /XObject << /Im0 $imageId 0 R >>$font >> /Contents $contentId 0 R >>")

        val cs = StringBuilder()
        cs.append("q ${f(dw)} 0 0 ${f(dh)} ${f(ox)} ${f(oy)} cm /Im0 Do Q\n")
        if (hasText) appendText(cs, p.words, s, ox, oy, img.height)
        wr.stream(contentId, "", cs.toString().toByteArray(Charsets.ISO_8859_1), crypt, deflate = true)

        when (img) {
            is PdfImage.Jpeg -> {
                val space = if (img.components == 1) "/DeviceGray" else "/DeviceRGB"
                wr.stream(imageId, "/Type /XObject /Subtype /Image /Width ${img.width} /Height ${img.height} " +
                    "/ColorSpace $space /BitsPerComponent 8 /Filter /DCTDecode", img.bytes, crypt, deflate = false)
            }
            is PdfImage.Bitonal -> {
                wr.stream(imageId, "/Type /XObject /Subtype /Image /Width ${img.width} /Height ${img.height} " +
                    "/ColorSpace /DeviceGray /BitsPerComponent 1", img.bits, crypt, deflate = true)
            }
        }
    }

    /** Invisible (render mode 3) text placed over each word's box, scaled to fit. */
    private fun appendText(cs: StringBuilder, words: List<OcrWord>, s: Float, ox: Float, oy: Float, imgH: Int) {
        cs.append("BT 3 Tr\n")
        for (wd in words) {
            val text = wd.text.trim()
            if (text.isEmpty()) continue
            val x0 = ox + wd.left * s
            val x1 = ox + wd.right * s
            val yBase = oy + (imgH - wd.bottom) * s
            val hgt = ((wd.bottom - wd.top) * s).coerceAtLeast(1f)
            val wid = (x1 - x0).coerceAtLeast(1f)
            val units = text.length
            val natural = units * hgt * CHAR_WIDTH_UNITS / 1000f
            val tz = (100f * wid / natural).coerceIn(1f, 10000f)
            // PDF text is stored in visual (left-to-right glyph) order, as Word and
            // other producers do; readers apply the Unicode bidi algorithm on
            // extraction, so Arabic search/copy returns the logical order.
            val shown = visualOrder(text)
            cs.append("/F1 ${f(hgt)} Tf ${f(tz)} Tz 1 0 0 1 ${f(x0)} ${f(yBase)} Tm <")
            for (ch in shown) cs.append(String.format(Locale.ROOT, "%04X", ch.code))
            cs.append("> Tj\n")
        }
        cs.append("ET\n")
    }

    /** Logical -> visual order (Unicode bidi), so RTL runs are written left to right. */
    internal fun visualOrder(s: String): String {
        val bidi = java.text.Bidi(s, java.text.Bidi.DIRECTION_DEFAULT_LEFT_TO_RIGHT)
        if (bidi.isLeftToRight) return s
        val n = bidi.runCount
        val levels = ByteArray(n)
        val runs = arrayOfNulls<Any>(n)
        for (i in 0 until n) {
            val level = bidi.getRunLevel(i)
            levels[i] = level.toByte()
            val run = s.substring(bidi.getRunStart(i), bidi.getRunLimit(i))
            runs[i] = if (level % 2 == 1) run.reversed() else run
        }
        java.text.Bidi.reorderVisually(levels, 0, runs, 0, n)
        return runs.joinToString("")
    }

    private fun pdfString(bytes: ByteArray, crypt: PdfCrypt?): String =
        "<" + hex(crypt?.encrypt(bytes) ?: bytes) + ">"

    private inner class Writer(private val out: OutputStream) {
        var pos = 0L
        val offsets = HashMap<Int, Long>()

        fun raw(b: ByteArray) {
            out.write(b); pos += b.size
        }

        fun flush() = out.flush()

        fun obj(id: Int, body: String) {
            offsets[id] = pos
            raw("$id 0 obj\n$body\nendobj\n".toByteArray(Charsets.ISO_8859_1))
        }

        fun stream(id: Int, dict: String, data: ByteArray, crypt: PdfCrypt?, deflate: Boolean) {
            var payload = if (deflate) deflate(data) else data
            if (crypt != null) payload = crypt.encrypt(payload)
            val filter = if (deflate) " /Filter /FlateDecode" else ""
            offsets[id] = pos
            raw("$id 0 obj\n<< $dict$filter /Length ${payload.size} >>\nstream\n".toByteArray(Charsets.ISO_8859_1))
            raw(payload)
            raw("\nendstream\nendobj\n".toByteArray(Charsets.ISO_8859_1))
        }
    }

    companion object {
        /** Glyph advance width in 1/1000 em for every CID. */
        const val CHAR_WIDTH_UNITS = 500

        private const val TO_UNICODE = "/CIDInit /ProcSet findresource begin\n12 dict begin\nbegincmap\n" +
            "/CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def\n" +
            "/CMapName /Adobe-Identity-UCS def\n/CMapType 2 def\n" +
            "1 begincodespacerange\n<0000> <FFFF>\nendcodespacerange\n" +
            "1 beginbfrange\n<0000> <FFFF> <0000>\nendbfrange\n" +
            "endcmap\nCMapName currentdict /CMap defineresource pop\nend\nend\n"

        fun deflate(data: ByteArray): ByteArray {
            val d = Deflater(Deflater.BEST_COMPRESSION)
            d.setInput(data)
            d.finish()
            val bos = ByteArrayOutputStream(maxOf(64, data.size / 4))
            val buf = ByteArray(16384)
            while (!d.finished()) {
                val n = d.deflate(buf)
                bos.write(buf, 0, n)
            }
            d.end()
            return bos.toByteArray()
        }

        fun hex(b: ByteArray): String {
            val sb = StringBuilder(b.size * 2)
            for (x in b) sb.append(String.format(Locale.ROOT, "%02X", x.toInt() and 0xFF))
            return sb.toString()
        }

        /** UTF-16BE with BOM, the PDF text-string encoding that handles Arabic. */
        fun utf16(s: String): ByteArray = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + s.toByteArray(Charsets.UTF_16BE)

        private fun f(v: Float): String {
            val r = String.format(Locale.ROOT, "%.3f", v)
            return r.trimEnd('0').trimEnd('.')
        }

        /** Number of colour components declared in a JPEG's SOF marker (1 or 3). */
        fun jpegComponents(jpeg: ByteArray): Int {
            var i = 2
            while (i + 9 < jpeg.size) {
                if (jpeg[i].toInt() and 0xFF != 0xFF) { i++; continue }
                val marker = jpeg[i + 1].toInt() and 0xFF
                if (marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
                    return jpeg[i + 9].toInt() and 0xFF
                }
                val len = ((jpeg[i + 2].toInt() and 0xFF) shl 8) or (jpeg[i + 3].toInt() and 0xFF)
                i += 2 + len
            }
            return 3
        }
    }
}
