// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.gates

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import kotlin.random.Random

/** Malformed and hostile inputs for the import paths. Deterministic (seeded). */
object Fuzz {
    /** One input: file [name] with [bytes]; [mime] overrides the type the provider reports ("" = none). */
    class Case(val id: String, val name: String, val bytes: ByteArray, val mime: String? = null)

    // ------------------------------------------------------------- PNG

    private fun chunk(out: DataOutputStream, type: String, data: ByteArray) {
        out.writeInt(data.size)
        val t = type.toByteArray(Charsets.ISO_8859_1)
        out.write(t)
        out.write(data)
        val crc = CRC32().apply { update(t); update(data) }
        out.writeInt(crc.value.toInt())
    }

    private fun ihdr(w: Int, h: Int, colorType: Int = 2, depth: Int = 8): ByteArray =
        ByteArrayOutputStream().also { b -> DataOutputStream(b).apply { writeInt(w); writeInt(h); write(depth); write(colorType); write(0); write(0); write(0) } }.toByteArray()

    private val PNG_SIG = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)

    /** A PNG that claims [w] x [h] pixels but carries (almost) no image data. */
    fun pngHeader(w: Int, h: Int): ByteArray {
        val b = ByteArrayOutputStream()
        val d = DataOutputStream(b)
        d.write(PNG_SIG)
        chunk(d, "IHDR", ihdr(w, h))
        chunk(d, "IDAT", byteArrayOf(0x78, 0x9c.toByte(), 0x63, 0, 0, 0, 1, 0, 1))
        chunk(d, "IEND", ByteArray(0))
        return b.toByteArray()
    }

    /** A real, valid grey PNG of [w] x [h] whose pixels are all zero: tiny file, huge image. */
    fun pngBomb(w: Int, h: Int): ByteArray {
        val raw = ByteArrayOutputStream()
        DeflaterOutputStream(raw, Deflater(9)).use { z ->
            val row = ByteArray(w + 1)
            repeat(h) { z.write(row) }
        }
        val b = ByteArrayOutputStream()
        val d = DataOutputStream(b)
        d.write(PNG_SIG)
        chunk(d, "IHDR", ihdr(w, h, colorType = 0))
        chunk(d, "IDAT", raw.toByteArray())
        chunk(d, "IEND", ByteArray(0))
        return b.toByteArray()
    }

    // ------------------------------------------------------------- JPEG

    /** SOI + SOF0 claiming [w] x [h], then EOI: no scan data at all. */
    fun jpegHeader(w: Int, h: Int): ByteArray {
        val b = ByteArrayOutputStream()
        b.write(byteArrayOf(-1, -40))
        b.write(byteArrayOf(-1, -64, 0, 17, 8, (h shr 8).toByte(), h.toByte(), (w shr 8).toByte(), w.toByte(), 3, 1, 0x22, 0, 2, 0x11, 1, 3, 0x11, 1))
        b.write(byteArrayOf(-1, -39))
        return b.toByteArray()
    }

    fun flip(src: ByteArray, seed: Int, count: Int, skipHeader: Int = 2): ByteArray {
        val r = Random(seed)
        val out = src.copyOf()
        repeat(count) {
            val i = skipHeader + r.nextInt((out.size - skipHeader).coerceAtLeast(1))
            if (i < out.size) out[i] = (out[i].toInt() xor (1 shl r.nextInt(8))).toByte()
        }
        return out
    }

    // ------------------------------------------------------------- WebP

    fun webpHeader(riffSize: Int, kind: String, payload: ByteArray): ByteArray {
        val b = ByteArrayOutputStream()
        b.write("RIFF".toByteArray())
        b.write(byteArrayOf(riffSize.toByte(), (riffSize shr 8).toByte(), (riffSize shr 16).toByte(), (riffSize shr 24).toByte()))
        b.write("WEBP".toByteArray())
        b.write(kind.toByteArray())
        b.write(byteArrayOf(payload.size.toByte(), (payload.size shr 8).toByte(), (payload.size shr 16).toByte(), (payload.size shr 24).toByte()))
        b.write(payload)
        return b.toByteArray()
    }

    // ------------------------------------------------------------- PDF

    /** Assembles a classic PDF from numbered objects with a correct xref table. */
    fun rawPdf(objects: List<String>, trailerExtra: String = "", xrefShift: Int = 0, startXref: Int? = null, eof: Boolean = true): ByteArray {
        val b = StringBuilder("%PDF-1.7\n%âãÏÓ\n")
        val offsets = ArrayList<Int>()
        for ((i, o) in objects.withIndex()) {
            offsets.add(b.length)
            b.append("${i + 1} 0 obj\n").append(o).append("\nendobj\n")
        }
        val xref = b.length
        b.append("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n")
        for (off in offsets) b.append(String.format(java.util.Locale.ROOT, "%010d 00000 n \n", off + xrefShift))
        b.append("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R $trailerExtra >>\nstartxref\n${startXref ?: xref}\n")
        if (eof) b.append("%%EOF\n")
        return b.toString().toByteArray(Charsets.ISO_8859_1)
    }

    private fun onePage(mediaBox: String = "[0 0 612 792]", content: String = "BT /F1 24 Tf 72 700 Td (Hello) Tj ET") = listOf(
        "<< /Type /Catalog /Pages 2 0 R >>",
        "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
        "<< /Type /Page /Parent 2 0 R /MediaBox $mediaBox /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>",
        "<< /Length ${content.length} >>\nstream\n$content\nendstream",
        "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
    )

    fun pdfCases(): List<Case> {
        val list = ArrayList<Case>()
        fun add(id: String, bytes: ByteArray, name: String = "$id.pdf", mime: String? = null) = list.add(Case("pdf-$id", name, bytes, mime))
        val valid = rawPdf(onePage())
        add("valid-raw", valid)
        add("valid-pdfbox-2p", SyntheticPages.pdf(2))
        add("encrypted", SyntheticPages.pdf(1, password = "secret"))
        add("encrypted-empty-user", SyntheticPages.pdf(1, password = ""))
        for (k in listOf(10, 25, 50, 75, 90, 99)) add("truncated-$k", valid.copyOf(valid.size * k / 100))
        add("xref-shifted", rawPdf(onePage(), xrefShift = 7))
        add("xref-negative", rawPdf(onePage(), xrefShift = -30))
        add("startxref-beyond", rawPdf(onePage(), startXref = 9_999_999))
        add("startxref-zero", rawPdf(onePage(), startXref = 0))
        add("no-eof", rawPdf(onePage(), eof = false))
        add("no-trailer", valid.copyOf(String(valid, Charsets.ISO_8859_1).indexOf("trailer")))
        add("kids-self-loop", rawPdf(listOf("<< /Type /Catalog /Pages 2 0 R >>", "<< /Type /Pages /Kids [2 0 R] /Count 1 >>")))
        add("kids-mutual-loop", rawPdf(listOf("<< /Type /Catalog /Pages 2 0 R >>", "<< /Type /Pages /Kids [3 0 R] /Count 2 >>", "<< /Type /Pages /Parent 2 0 R /Kids [2 0 R] /Count 1 >>")))
        add("count-huge", rawPdf(onePage().toMutableList().also { it[1] = "<< /Type /Pages /Kids [3 0 R] /Count 2147483647 >>" }))
        add("count-negative", rawPdf(onePage().toMutableList().also { it[1] = "<< /Type /Pages /Kids [3 0 R] /Count -5 >>" }))
        add("zero-pages", rawPdf(listOf("<< /Type /Catalog /Pages 2 0 R >>", "<< /Type /Pages /Kids [] /Count 0 >>")))
        add("root-missing", rawPdf(listOf("<< /Type /Pages /Kids [] /Count 0 >>")).let { String(it, Charsets.ISO_8859_1).replace("/Root 1 0 R", "/Root 9 0 R").toByteArray(Charsets.ISO_8859_1) })
        add("mediabox-huge", rawPdf(onePage(mediaBox = "[0 0 200000 200000]")))
        add("mediabox-zero", rawPdf(onePage(mediaBox = "[0 0 0 0]")))
        add("mediabox-negative", rawPdf(onePage(mediaBox = "[0 0 -600 -800]")))
        add("mediabox-nan", rawPdf(onePage(mediaBox = "[0 0 abc def]")))
        add("thin-strip", rawPdf(onePage(mediaBox = "[0 0 14400 2]")))
        add("content-length-wrong", String(valid, Charsets.ISO_8859_1).replace(Regex("/Length \\d+"),"/Length 999999").toByteArray(Charsets.ISO_8859_1))
        add("content-garbage", rawPdf(onePage(content = "q 1 0 0 1 0 0 cm BI /W 99999 /H 99999 ID \u0001\u0002 EI Q ((((((((")))
        add("deep-arrays", rawPdf(onePage().toMutableList().also { it[4] = "<< /Type /Font /X ${"[".repeat(20_000)}${"]".repeat(20_000)} >>" }))
        add("deep-dicts", rawPdf(onePage().toMutableList().also { it[4] = "<< /A ".repeat(5_000) + ">> ".repeat(5_000) }))
        add("encrypt-bogus", rawPdf(onePage(), trailerExtra = "/Encrypt << /Filter /Standard /V 9 /R 9 /O (x) /U (y) /P -1 >>"))
        add("encrypt-unknown-filter", rawPdf(onePage(), trailerExtra = "/Encrypt << /Filter /Rot13 >> /ID [<00> <00>]"))
        add("header-only", "%PDF-1.7\n".toByteArray())
        add("header-then-garbage", "%PDF-1.4\n".toByteArray() + Random(3).nextBytes(20_000))
        add("header-late", Random(4).nextBytes(900) + valid)
        add("pdf-as-jpg-name", valid, name = "scan.jpg", mime = "image/jpeg")
        add("pdf-no-type-no-ext", valid, name = "attachment", mime = "")
        add("pdf-octet-stream", valid, name = "download.bin", mime = "application/octet-stream")
        add("garbage-named-pdf", Random(5).nextBytes(5_000))
        add("empty-named-pdf", ByteArray(0))
        add("html-named-pdf", "<html><body>%PDF-1.4 not really</body></html>".toByteArray())
        add("utf16-bom", byteArrayOf(-2, -1) + valid)
        for (s in 0 until 21) add("bitflip-$s", flip(valid, 1000 + s, 1 + s % 6, skipHeader = 9))
        return list
    }

    // ------------------------------------------------------------- images

    fun imageCases(): List<Case> {
        val list = ArrayList<Case>()
        fun add(id: String, name: String, bytes: ByteArray, mime: String? = null) = list.add(Case(id, name, bytes, mime))
        val photo = SyntheticPages.photo("mixed-memo", 11, 900, 1200)
        val jpg = SyntheticPages.encode(photo, "jpg")
        val png = SyntheticPages.encode(photo, "png")
        val webp = SyntheticPages.encode(photo, "webp")
        photo.recycle()
        // JPEG (70)
        add("jpeg-valid", "ok.jpg", jpg)
        for (k in listOf(1, 2, 5, 10, 20, 30, 40, 50, 60, 70, 80, 90, 95, 99)) add("jpeg-truncated-$k", "t$k.jpg", jpg.copyOf((jpg.size * k / 100).coerceAtLeast(1)))
        add("jpeg-soi-only", "soi.jpg", byteArrayOf(-1, -40))
        add("jpeg-soi-eoi", "soieoi.jpg", byteArrayOf(-1, -40, -1, -39))
        for ((w, h) in listOf(65500 to 65500, 1 to 65500, 65500 to 1, 0 to 0, 0 to 100, 30000 to 30000, 40000 to 2, 3 to 3)) add("jpeg-header-${w}x$h", "h${w}x$h.jpg", jpegHeader(w, h))
        add("jpeg-garbage-after-soi", "g.jpg", byteArrayOf(-1, -40) + Random(6).nextBytes(30_000))
        add("jpeg-repeated-markers", "m.jpg", byteArrayOf(-1, -40) + ByteArray(20_000) { if (it % 2 == 0) -1 else -64 })
        add("jpeg-trailing-junk", "tj.jpg", jpg + Random(7).nextBytes(100_000))
        add("jpeg-twice", "tw.jpg", jpg + jpg)
        for (s in 0 until 40) add("jpeg-bitflip-$s", "bf$s.jpg", flip(jpg, 2000 + s, 1 + s % 12, skipHeader = 4))
        // PNG (50)
        add("png-valid", "ok.png", png)
        for ((w, h) in listOf(60000 to 60000, 1 to 100000, 100000 to 1, 0 to 0, -1 to -1, 2_000_000 to 2_000_000, 1 to 1)) add("png-header-${w}x$h", "p${w}x$h.png", pngHeader(w, h))
        add("png-bomb-12000", "bomb.png", pngBomb(12_000, 12_000))
        add("png-bomb-strip", "strip.png", pngBomb(40_000, 3))
        add("png-sig-only", "sig.png", PNG_SIG)
        for (k in listOf(5, 20, 50, 80, 97)) add("png-truncated-$k", "pt$k.png", png.copyOf(png.size * k / 100))
        add("png-bad-crc", "crc.png", png.copyOf().also { it[29] = (it[29] + 1).toByte() })
        for (s in 0 until 33) add("png-bitflip-$s", "pbf$s.png", flip(png, 3000 + s, 1 + s % 8, skipHeader = 8))
        // WebP (30)
        add("webp-valid", "ok.webp", webp)
        add("webp-riff-huge", "rh.webp", webpHeader(Int.MAX_VALUE, "VP8 ", ByteArray(10)))
        add("webp-riff-zero", "rz.webp", webpHeader(0, "VP8L", ByteArray(5)))
        add("webp-unknown-chunk", "uc.webp", webpHeader(100, "ABCD", ByteArray(88)))
        add("webp-vp8l-garbage", "vg.webp", webpHeader(5000, "VP8L", Random(8).nextBytes(4988)))
        add("webp-vp8x-huge-canvas", "vx.webp", webpHeader(30, "VP8X", byteArrayOf(0, 0, 0, 0, -1, -1, -1, -1, -1, -1)))
        for (k in listOf(3, 10, 30, 60, 90)) add("webp-truncated-$k", "wt$k.webp", webp.copyOf((webp.size * k / 100).coerceAtLeast(1)))
        for (s in 0 until 19) add("webp-bitflip-$s", "wbf$s.webp", flip(webp, 4000 + s, 1 + s % 6, skipHeader = 12))
        // HEIC-named (20)
        add("heic-jpeg-inside", "photo.heic", jpg, mime = "image/heic")
        add("heic-ftyp-garbage", "ftyp.heic", byteArrayOf(0, 0, 0, 24) + "ftypheic".toByteArray() + Random(9).nextBytes(4000), mime = "image/heic")
        add("heic-empty", "e.heic", ByteArray(0), mime = "image/heic")
        add("heif-truncated", "t.heif", jpg.copyOf(500), mime = "image/heif")
        for (s in 0 until 16) add("heic-random-$s", "r$s.heic", byteArrayOf(0, 0, 0, 28) + "ftypmif1".toByteArray() + Random(5000 + s).nextBytes(200 + s * 400), mime = "image/heic")
        // Zero-byte and wrong MIME (35)
        for (ext in listOf("jpg", "png", "webp", "gif", "bmp", "pdf", "txt", "")) add("zero-$ext", if (ext.isEmpty()) "zero" else "zero.$ext", ByteArray(0))
        add("jpeg-as-pdf-mime", "x.pdf", jpg, mime = "application/pdf")
        add("png-as-jpeg-mime", "x.jpg", png, mime = "image/jpeg")
        add("text-as-png", "notes.png", "just some notes, not an image".toByteArray(), mime = "image/png")
        add("text-mime-null", "notes", "plain text".toByteArray(), mime = "")
        add("jpeg-mime-wildcard", "w.jpg", jpg, mime = "*/*")
        add("jpeg-mime-video", "v.mp4", jpg, mime = "video/mp4")
        add("jpeg-mime-garbage", "g.jpg", jpg, mime = "\u0000\u0001weird/;;")
        add("zip-as-jpg", "a.jpg", byteArrayOf(80, 75, 3, 4) + Random(10).nextBytes(3000), mime = "image/jpeg")
        add("gif-tiny", "t.gif", byteArrayOf(71, 73, 70, 56, 57, 97, 1, 0, 1, 0, -128, 0, 0, 0, 0, 0, -1, -1, -1, 33, -7, 4, 1, 0, 0, 0, 0, 44, 0, 0, 0, 0, 1, 0, 1, 0, 0, 2, 2, 68, 1, 0, 59))
        add("bmp-header-huge", "h.bmp", byteArrayOf(66, 77) + ByteArray(52).also { it[16] = -1; it[17] = -1; it[18] = 127; it[20] = -1; it[21] = -1; it[22] = 127 }, mime = "image/bmp")
        for (s in 0 until 15) add("random-$s", "rnd$s.jpg", Random(6000 + s).nextBytes(1 + s * 997), mime = listOf("image/jpeg", "image/png", "application/pdf", "")[s % 4])
        return list
    }
}
