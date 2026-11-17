// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.gates

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import java.io.ByteArrayOutputStream
import kotlin.random.Random

/**
 * Invented paper documents for the simulated world (never a real document).
 * [renderPage] draws a flat, upright page; [photo] puts it on a desk with a
 * perspective, lighting fall-off and a little noise, like a phone camera sees it.
 */
object SyntheticPages {
    class Spec(val id: String, val description: String, val aspect: Float = 1.414f, val draw: (Canvas, Int, Int) -> Unit)

    private fun ink(size: Float, bold: Boolean = false, color: Int = Color.rgb(28, 28, 36)) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        textSize = size
        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

    /** Right-aligned (Arabic) or left-aligned text line. */
    private fun Canvas.line(text: String, x: Float, y: Float, p: Paint, rtl: Boolean, w: Int) {
        if (rtl) {
            p.textAlign = Paint.Align.RIGHT
            drawText(text, w - x, y, p)
        } else {
            p.textAlign = Paint.Align.LEFT
            drawText(text, x, y, p)
        }
    }

    private val arabicBody = listOf(
        "السيد المحترم مدير شركة النخيل للتجارة",
        "تحية طيبة وبعد، نود إعلامكم بأن موعد تجديد العقد",
        "سيكون في الخامس عشر من الشهر القادم، ونرجو منكم",
        "تزويدنا بالمستندات المطلوبة قبل هذا التاريخ.",
        "تشمل المستندات صورة الهوية وشهادة السجل التجاري",
        "وكشف حساب مختوم من البنك لآخر ثلاثة أشهر.",
        "في حال وجود أي استفسار يمكنكم التواصل معنا",
        "عبر مكتب خدمة العملاء في ساعات الدوام الرسمي.",
        "شاكرين لكم حسن تعاونكم، وتفضلوا بقبول فائق الاحترام.",
    )
    private val englishBody = listOf(
        "Dear Ms. Taylor,",
        "We are pleased to confirm the renewal of your lease",
        "for the unit at 14 Harbour Road for another twelve",
        "months, starting on the first of next month.",
        "The monthly rent stays at 1,250 and is due on the",
        "fifth day of each month. Please sign both copies of",
        "the attached agreement and return one of them to",
        "our office before the end of this month.",
        "Kind regards,",
        "Northwind Property Services",
    )

    val catalogue: List<Spec> = listOf(
        Spec("ar-letter", "Arabic letter: heading «خطاب رسمي», nine lines of Arabic text, a signature line") { c, w, h ->
            val s = w / 1240f
            c.line("خطاب رسمي", 120 * s, 200 * s, ink(64 * s, true), true, w)
            c.line("التاريخ: ١٢ / ٠٣ / ٢٠٢٦", 120 * s, 290 * s, ink(34 * s), true, w)
            arabicBody.forEachIndexed { i, t -> c.line(t, 120 * s, (400 + i * 78) * s, ink(40 * s), true, w) }
            c.line("التوقيع: ____________", 120 * s, (400 + 10 * 78) * s, ink(38 * s), true, w)
        },
        Spec("en-letter", "English letter: heading «Notice of Renewal», ten lines of English text") { c, w, h ->
            val s = w / 1240f
            c.line("Notice of Renewal", 110 * s, 200 * s, ink(62 * s, true), false, w)
            c.line("Date: 12 March 2026", 110 * s, 285 * s, ink(34 * s), false, w)
            englishBody.forEachIndexed { i, t -> c.line(t, 110 * s, (400 + i * 76) * s, ink(40 * s), false, w) }
        },
        Spec("mixed-memo", "Arabic + English memo «مذكرة داخلية / Internal memo» with dates and codes like REF-2291") { c, w, h ->
            val s = w / 1240f
            c.line("مذكرة داخلية", 110 * s, 190 * s, ink(58 * s, true), true, w)
            c.line("Internal memo", 110 * s, 190 * s, ink(44 * s, true, Color.rgb(30, 80, 150)), false, w)
            val rows = listOf(
                "الموضوع: تحديث نظام الأرشفة" to "Subject: archive system update",
                "رقم الطلب: REF-2291" to "Request: REF-2291",
                "تاريخ البدء ٢٠٢٦/٠٤/٠١" to "Start date 2026-04-01",
                "المسؤول: قسم تقنية المعلومات" to "Owner: IT department",
            )
            rows.forEachIndexed { i, (ar, en) ->
                c.line(ar, 110 * s, (330 + i * 150) * s, ink(40 * s), true, w)
                c.line(en, 110 * s, (390 + i * 150) * s, ink(34 * s, color = Color.rgb(70, 70, 80)), false, w)
            }
        },
        Spec("invoice", "Bilingual invoice «فاتورة / Invoice No. 1047» with a 5-row table (item, qty, price) and a total of 2,385.50") { c, w, h ->
            val s = w / 1240f
            c.drawRect(0f, 0f, w.toFloat(), 150 * s, Paint().apply { color = Color.rgb(30, 90, 160) })
            c.line("فاتورة", 90 * s, 105 * s, ink(64 * s, true, Color.WHITE), true, w)
            c.line("Invoice No. 1047", 90 * s, 105 * s, ink(50 * s, true, Color.WHITE), false, w)
            val grid = Paint().apply { color = Color.rgb(60, 60, 70); strokeWidth = 3 * s; style = Paint.Style.STROKE }
            val top = 260 * s
            val rowH = 95 * s
            val left = 90 * s
            val right = w - 90 * s
            val items = listOf(
                listOf("Item / الصنف", "Qty", "Price"),
                listOf("ورق طباعة A4", "10", "450.00"),
                listOf("Toner cartridge", "2", "780.00"),
                listOf("ملفات أرشيف", "25", "312.50"),
                listOf("Desk lamp", "3", "540.00"),
                listOf("خدمة توصيل", "1", "303.00"),
            )
            for ((r, row) in items.withIndex()) {
                val y = top + r * rowH
                c.drawRect(left, y, right, y + rowH, grid)
                val cols = floatArrayOf(left, left + (right - left) * 0.55f, left + (right - left) * 0.75f)
                for ((k, cell) in row.withIndex()) {
                    c.line(cell, cols[k] + 20 * s, y + rowH * 0.66f, ink(36 * s, r == 0), false, w)
                }
                c.drawLine(cols[1], y, cols[1], y + rowH, grid)
                c.drawLine(cols[2], y, cols[2], y + rowH, grid)
            }
            c.line("Total / المجموع: 2,385.50", 90 * s, top + 7 * rowH, ink(44 * s, true), false, w)
        },
        Spec("find-number", "A form with a reference number to find: «رقم المرجع: 4821-7730» in the middle of the page") { c, w, h ->
            val s = w / 1240f
            c.line("نموذج طلب خدمة", 110 * s, 200 * s, ink(58 * s, true), true, w)
            val lines = listOf("الاسم: ____________________", "رقم الهاتف: ______________", "العنوان: _________________")
            lines.forEachIndexed { i, t -> c.line(t, 110 * s, (330 + i * 90) * s, ink(40 * s), true, w) }
            c.drawRect(110 * s, 640 * s, w - 110 * s, 780 * s, Paint().apply { color = Color.rgb(255, 244, 200) })
            c.line("رقم المرجع: 4821-7730", 150 * s, 735 * s, ink(54 * s, true), true, w)
            arabicBody.take(4).forEachIndexed { i, t -> c.line(t, 110 * s, (900 + i * 76) * s, ink(36 * s), true, w) }
        },
        Spec("receipt", "A long, narrow shop receipt «إيصال / Receipt» with six amounts and a total of 96.40", aspect = 3.0f) { c, w, h ->
            val s = w / 600f
            c.line("إيصال", 40 * s, 110 * s, ink(56 * s, true), true, w)
            c.line("Receipt #5521", 40 * s, 180 * s, ink(36 * s), false, w)
            val amounts = listOf("12.00", "8.50", "25.90", "4.00", "31.00", "15.00")
            amounts.forEachIndexed { i, a ->
                c.line("Item ${i + 1}", 40 * s, (280 + i * 80) * s, ink(32 * s), false, w)
                c.line(a, 40 * s, (280 + i * 80) * s, ink(32 * s), true, w)
            }
            c.line("TOTAL 96.40", 40 * s, (280 + 7 * 80) * s, ink(44 * s, true), false, w)
        },
        Spec("id-card", "An ID-card-sized card «بطاقة تعريف / ID card» with a photo box and a number 784-1990-1234567-1", aspect = 0.63f) { c, w, h ->
            val s = w / 1000f
            c.drawRect(0f, 0f, w.toFloat(), 120 * s, Paint().apply { color = Color.rgb(20, 110, 90) })
            c.line("بطاقة تعريف", 40 * s, 85 * s, ink(52 * s, true, Color.WHITE), true, w)
            c.line("ID card", 40 * s, 85 * s, ink(44 * s, true, Color.WHITE), false, w)
            c.drawRect(40 * s, 170 * s, 300 * s, 520 * s, Paint().apply { color = Color.rgb(170, 180, 190) })
            c.line("Name: Sami Example", 340 * s, 240 * s, ink(36 * s), false, w)
            c.line("784-1990-1234567-1", 340 * s, 320 * s, ink(38 * s, true), false, w)
        },
        // BLACKBOX-2.0 #3: a two-page spread for book mode (the tester had none to split).
        Spec("book-spread", "An open book, two facing Arabic pages: «الفصل الأول» on the right, «الفصل الثاني» on the left, the spine in the middle", aspect = 0.72f) { c, w, h ->
            val s = w / 1600f
            // The gutter: a soft shadow down the middle, no text near it.
            c.drawRect(w * 0.46f, 0f, w * 0.54f, h.toFloat(), Paint().apply {
                shader = LinearGradient(w * 0.46f, 0f, w * 0.54f, 0f, intArrayOf(Color.rgb(250, 249, 245), Color.rgb(150, 146, 138), Color.rgb(250, 249, 245)), null, Shader.TileMode.CLAMP)
            })
            // One page per half; x is measured from the half's outer edge (right page: from the right).
            fun half(rightPage: Boolean, title: String, body: List<String>) {
                val margin = 70 * s
                val pageW = w / 2f
                fun at(text: String, y: Float, p: Paint) {
                    p.textAlign = Paint.Align.RIGHT
                    val rightEdge = if (rightPage) w - margin else pageW - margin * 1.6f
                    c.drawText(text, rightEdge, y, p)
                }
                at(title, 170 * s, ink(52 * s, true))
                body.forEachIndexed { i, t -> at(t, (270 + i * 66) * s, ink(32 * s)) }
            }
            half(true, "الفصل الأول", arabicBody.take(7))
            half(false, "الفصل الثاني", arabicBody.drop(2).map { it.split(' ').take(5).joinToString(" ") })
        },
        Spec("desk", "No paper at all: only the empty desk") { _, _, _ -> },
    )

    /** A line of text as drawn on the page; [box] and the word boxes are fractions of the page (0..1). */
    class Run(val text: String, val rtl: Boolean, val box: android.graphics.RectF, val words: List<Pair<String, android.graphics.RectF>>)

    /** Records every line [renderPage] draws, with its words' boxes (for the simulated OCR, gates/SimulatedOcr). */
    private class Recorder(bmp: Bitmap) : Canvas(bmp) {
        val runs = ArrayList<Run>()

        override fun drawText(text: String, x: Float, y: Float, paint: Paint) {
            super.drawText(text, x, y, paint)
            val words = text.split(' ').filter { it.isNotBlank() }
            if (words.isEmpty() || text.all { it == '_' || it == ' ' }) return
            val total = paint.measureText(text)
            val space = paint.measureText(" ")
            val left = when (paint.textAlign) { Paint.Align.RIGHT -> x - total; Paint.Align.CENTER -> x - total / 2; else -> x }
            val rtl = paint.textAlign == Paint.Align.RIGHT && text.any { it.code in 0x0600..0x06FF }
            val top = y - paint.textSize * 0.8f
            val bottom = y + paint.textSize * 0.25f
            val w = width.toFloat()
            val h = height.toFloat()
            var cursor = if (rtl) left + total else left
            val boxes = words.map { word ->
                val ww = paint.measureText(word)
                val r = if (rtl) android.graphics.RectF(cursor - ww, top, cursor, bottom).also { cursor -= ww + space }
                else android.graphics.RectF(cursor, top, cursor + ww, bottom).also { cursor += ww + space }
                word to android.graphics.RectF(r.left / w, r.top / h, r.right / w, r.bottom / h)
            }
            runs.add(Run(text, rtl, android.graphics.RectF(left / w, top / h, (left + total) / w, bottom / h), boxes))
        }
    }

    /** The page as [renderPage] draws it, and the text lines on it in drawing order. */
    fun renderWithText(id: String, width: Int = 1240): Pair<Bitmap, List<Run>> {
        val spec = spec(id) ?: error("unknown page $id")
        val bmp = Bitmap.createBitmap(width, (width * spec.aspect).toInt(), Bitmap.Config.ARGB_8888)
        val c = Recorder(bmp)
        c.drawColor(Color.rgb(250, 249, 245))
        spec.draw(c, bmp.width, bmp.height)
        return bmp to c.runs
    }

    fun spec(id: String): Spec? = catalogue.firstOrNull { it.id.equals(id, ignoreCase = true) }

    /** The flat page (a perfect scan). */
    fun renderPage(id: String, width: Int = 1240): Bitmap {
        val spec = spec(id) ?: error("unknown page $id")
        val h = (width * spec.aspect).toInt()
        val bmp = Bitmap.createBitmap(width, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.rgb(250, 249, 245))
        spec.draw(c, width, h)
        return bmp
    }

    /** A camera photo of the page on a desk; [seed] varies perspective and light. */
    fun photo(id: String, seed: Int, w: Int = 1500, h: Int = 2000): Bitmap {
        val rnd = Random(seed)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        // Wooden desk: warm gradient with darker grain stripes.
        c.drawPaint(Paint().apply { shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), Color.rgb(112, 80, 54), Color.rgb(78, 54, 38), Shader.TileMode.CLAMP) })
        val grain = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(40, 40, 22, 10); strokeWidth = 3f }
        for (i in 0 until 60) {
            val y = rnd.nextFloat() * h
            c.drawLine(0f, y, w.toFloat(), y + rnd.nextFloat() * 60f - 30f, grain)
        }
        val spec = spec(id) ?: error("unknown page $id")
        if (spec.id != "desk") {
            val page = renderPage(id)
            // Paper fills 55-80% of the frame, tilted, with a perspective (top narrower).
            val pw = page.width.toFloat()
            val ph = page.height.toFloat()
            val fill = 0.55f + rnd.nextFloat() * 0.25f
            val k = minOf(w * fill / pw, h * fill / ph)
            val cx = w / 2f + (rnd.nextFloat() - 0.5f) * w * 0.08f
            val cy = h / 2f + (rnd.nextFloat() - 0.5f) * h * 0.06f
            val hw = pw * k / 2f
            val hh = ph * k / 2f
            val narrow = hw * (0.04f + rnd.nextFloat() * 0.08f)
            val angle = (rnd.nextFloat() - 0.5f) * 0.18f
            fun rot(x: Float, y: Float): FloatArray {
                val cs = kotlin.math.cos(angle)
                val sn = kotlin.math.sin(angle)
                return floatArrayOf(cx + x * cs - y * sn, cy + x * sn + y * cs)
            }
            val dst = rot(-hw + narrow, -hh) + rot(hw - narrow, -hh) + rot(hw, hh) + rot(-hw, hh)
            val src = floatArrayOf(0f, 0f, pw, 0f, pw, ph, 0f, ph)
            val m = Matrix().apply { setPolyToPoly(src, 0, dst, 0, 4) }
            // Soft shadow under the paper.
            val shadow = Path().apply {
                moveTo(dst[0] + 14, dst[1] + 18); lineTo(dst[2] + 14, dst[3] + 18); lineTo(dst[4] + 14, dst[5] + 18); lineTo(dst[6] + 14, dst[7] + 18); close()
            }
            c.drawPath(shadow, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(90, 0, 0, 0) })
            c.drawBitmap(page, m, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
            page.recycle()
        }
        // Uneven room light: brighter near one corner.
        c.drawPaint(Paint().apply {
            shader = RadialGradient(w * rnd.nextFloat(), h * 0.3f * rnd.nextFloat(), maxOf(w, h).toFloat(), Color.argb(0, 0, 0, 0), Color.argb(70, 0, 0, 0), Shader.TileMode.CLAMP)
        })
        return out
    }

    fun jpeg(bmp: Bitmap, quality: Int = 90): ByteArray =
        ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()

    fun encode(bmp: Bitmap, ext: String): ByteArray {
        val fmt = when (ext.lowercase()) {
            "png" -> Bitmap.CompressFormat.PNG
            "webp" -> @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP
            else -> Bitmap.CompressFormat.JPEG
        }
        return ByteArrayOutputStream().also { bmp.compress(fmt, 90, it) }.toByteArray()
    }

    /** A real PDF (PDFBox) with [pages] pages, each a rendered catalogue page; optionally password-protected. */
    fun pdf(pages: Int, password: String? = null, ids: List<String> = catalogue.map { it.id }.filter { it != "desk" }): ByteArray {
        val doc = org.apache.pdfbox.pdmodel.PDDocument()
        try {
            for (i in 0 until pages) {
                val id = ids[i % ids.size]
                val bmp = renderPage(id, 900)
                val jpg = jpeg(bmp, 80)
                val img = org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory.createFromByteArray(doc, jpg)
                val page = org.apache.pdfbox.pdmodel.PDPage(org.apache.pdfbox.pdmodel.common.PDRectangle(bmp.width * 0.66f, bmp.height * 0.66f))
                bmp.recycle()
                doc.addPage(page)
                org.apache.pdfbox.pdmodel.PDPageContentStream(doc, page).use { cs ->
                    cs.drawImage(img, 0f, 0f, page.mediaBox.width, page.mediaBox.height)
                }
            }
            if (password != null) {
                val ap = org.apache.pdfbox.pdmodel.encryption.AccessPermission()
                val policy = org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy(password + "-owner", password, ap)
                policy.encryptionKeyLength = 128
                doc.protect(policy)
            }
            return ByteArrayOutputStream().also { doc.save(it) }.toByteArray()
        } finally {
            doc.close()
        }
    }
}
