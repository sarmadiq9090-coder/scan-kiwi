// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.search

import com.fastkiwi.scan.core.OcrWord
import kotlin.random.Random

/**
 * Synthetic Arabic / English page texts for the search and TXT tests. Every sentence is invented
 * here (categories after the OCR test set: prose, fully vocalised text, numbers, amounts, dates,
 * mixed Arabic/English, tables); no real document is used. Pages come out as OCR words with boxes
 * laid out right to left per line, in logical (engine) order, as the OCR cache stores them.
 */
object SynthCorpus {
    private val prose = listOf(
        "راجعت الإدارة طلبات الإجازة المقدمة خلال الشهر الماضي",
        "نفيدكم بأن موعد التسليم النهائي سيكون في الأسبوع القادم",
        "يرجى إحضار أصل الهوية وصورة منها عند مراجعة الفرع",
        "أعلنت البلدية عن إغلاق الطريق الشرقي لأعمال الصيانة",
        "تسلّم المستودع شحنة جديدة من الأجهزة الطبية صباح الأحد",
        "قررت اللجنة تأجيل الاجتماع إلى إشعار آخر بسبب الظروف",
        "حصل الفريق على المركز الأول في مسابقة الابتكار الإقليمية",
        "تشمل الخطة تدريب الموظفين الجدد على أنظمة الأمان",
        "أرسلت الشركة عرض أسعار محدّث إلى إدارة المشتريات",
        "يستقبل المستشفى المراجعين من الساعة الثامنة حتى الرابعة",
        "تمت الموافقة على طلب نقل الكفالة بعد استكمال الأوراق",
        "يلتزم المستأجر بدفع الإيجار في بداية كل شهر ميلادي",
        "انتهت أعمال ترميم المكتبة العامة في وسط المدينة",
        "نأمل منكم التعاون وإبلاغنا بأي ملاحظة على الفاتورة",
        "يحتوي الملف المرفق على جدول المواعيد وأسماء المشرفين",
        "ألغت الجامعة الرسوم الإضافية على طلاب السنة الأولى",
        "استأنف المصنع الإنتاج بعد توقف دام ثلاثة أيام",
        "توزّع الجمعية السلال الغذائية على الأسر المسجلة لديها",
    )
    private val vocalised = listOf(
        "ذَهَبَ الْمُوَظَّفُ إِلَى الْمَكْتَبِ مُبَكِّرًا",
        "قَرَأَتِ الطَّالِبَةُ الْقِصَّةَ بِصَوْتٍ عَالٍ",
        "يَسْكُنُ أَخِي فِي مَدِينَةٍ سَاحِلِيَّةٍ جَمِيلَةٍ",
        "أَنْهَى الْمُهَنْدِسُ تَصْمِيمَ الْجِسْرِ الْجَدِيدِ",
        "تُشْرِقُ الشَّمْسُ عَلَى الْقَرْيَةِ الْهَادِئَةِ",
        "اِشْتَرَى الْأَبُ هَدِيَّةً لِابْنَتِهِ الصُّغْرَى",
        "عَادَ الْمُسَافِرُ إِلَى وَطَنِهِ بَعْدَ غِيَابٍ طَوِيلٍ",
        "إِنَّ الصِّدْقَ طَرِيقُ الثِّقَةِ بَيْنَ النَّاسِ",
    )
    private val numberTpl = listOf(
        "سُجّل {a} طلبا جديدا في عام {y}",
        "يضم المجمع {b} مبان و{a} شقة سكنية",
        "رقم المعاملة {c} بتاريخ {y}",
        "زادت التكلفة بنسبة {b}% عن الخطة",
        "للاستفسار اتصل بالرقم {ph}",
    )
    private val amountTpl = listOf(
        "المبلغ الإجمالي {m} ريال",
        "قيمة الفاتورة {m} درهم إماراتي",
        "الرصيد المتبقي {m} دينار",
        "Amount due: {m} USD",
        "رسوم الخدمة {m} جنيه",
    )
    private val dateTpl = listOf(
        "تاريخ الإصدار {d}",
        "يبدأ العقد في {dd} {mon} {y}",
        "Valid until {iso}",
        "آخر موعد للسداد {d}",
    )
    private val mixed = listOf(
        "أرسل نسخة PDF إلى قسم Accounting اليوم",
        "تم تحديث برنامج Office على أجهزة القسم",
        "Order No. ORD-{c} من فرع Riyadh",
        "Please review the attached quotation",
        "اسم الجهاز Scanner Pro طراز X{b}",
    )
    private val items = listOf("أوراق تصوير", "أحبار ملونة", "مكيف هواء", "مقاعد انتظار", "حاسب محمول", "خدمة نقل", "صيانة دورية", "ملصقات")
    private val months = listOf("يناير", "فبراير", "مارس", "أبريل", "مايو", "يونيو", "يوليو", "أغسطس", "سبتمبر", "أكتوبر", "نوفمبر", "ديسمبر")
    private const val IND = "٠١٢٣٤٥٦٧٨٩"

    enum class Category { PROSE, VOCALISED, NUMBERS, AMOUNTS, DATES, MIXED, TABLE }

    private fun indic(s: String) = s.map { if (it in '0'..'9') IND[it - '0'] else it }.joinToString("").replace(",", "٬").replace(".", "٫")

    private fun fill(t: String, r: Random, ind: Boolean): String {
        val y = (2019..2027).random(r)
        val mo = (1..12).random(r)
        val d = (1..28).random(r)
        val m = "%,.2f".format(java.util.Locale.ROOT, (5..99_999).random(r) + (0..99).random(r) / 100.0)
        var s = t.replace("{a}", "${(12..980).random(r)}").replace("{b}", "${(2..9).random(r)}").replace("{y}", "$y")
            .replace("{c}", "${(1000..9999).random(r)}").replace("{ph}", "05${(10_000_000..99_999_999).random(r)}")
            .replace("{d}", "%04d/%02d/%02d".format(y, mo, d)).replace("{dd}", "$d").replace("{mon}", months[mo - 1])
            .replace("{iso}", "%04d-%02d-%02d".format(y, mo, d))
        val latin = t.any { it in 'A'..'Z' || it in 'a'..'z' }
        s = s.replace("{m}", if (ind && !latin) indic(m) else m)
        return if (ind && !latin) s.map { if (it in '0'..'9') IND[it - '0'] else it }.joinToString("") else s
    }

    /** The lines of one synthetic page. */
    fun lines(cat: Category, r: Random): List<String> {
        val ind = r.nextFloat() < 0.4f
        return when (cat) {
            Category.PROSE -> prose.shuffled(r).take(5)
            Category.VOCALISED -> vocalised.shuffled(r).take(4)
            Category.NUMBERS -> numberTpl.shuffled(r).take(4).map { fill(it, r, ind) }
            Category.AMOUNTS -> amountTpl.shuffled(r).take(4).map { fill(it, r, ind) }
            Category.DATES -> dateTpl.shuffled(r).take(4).map { fill(it, r, ind) }
            Category.MIXED -> mixed.shuffled(r).take(4).map { fill(it, r, false) }
            Category.TABLE -> listOf("البند الكمية السعر") + items.shuffled(r).take(4).map { "$it ${(1..40).random(r)} ${fill("{m}", r, ind)}" }
        }
    }

    /**
     * OCR words for [lines]: one word per space-separated token, each line laid out right to left
     * (x falls), lines top to bottom, in a 2000 x 2828 frame. Logical order is kept.
     */
    fun words(lines: List<String>): List<OcrWord> {
        val out = ArrayList<OcrWord>()
        for ((li, line) in lines.withIndex()) {
            val top = 150 + li * 110
            var right = 1850
            for (t in line.split(' ').filter { it.isNotEmpty() }) {
                val w = 30 + t.length * 22
                out.add(OcrWord(t, right - w, top, right, top + 70))
                right -= w + 25
            }
        }
        return out
    }

    /**
     * How a user types [word] in a query: without harakat and tatweel, and with the alef / ya /
     * ta marbuta / digit form switched to another variant ([variant] picks which).
     */
    fun typed(word: String, variant: Int): String {
        val plain = word.filter { it !in 'ً'..'ٟ' && it != 'ٰ' && it != 'ـ' }
        val sb = StringBuilder()
        for ((i, c) in plain.withIndex()) {
            val last = i == plain.lastIndex
            sb.append(
                when {
                    c == 'أ' || c == 'إ' || c == 'آ' -> if (variant % 2 == 0) 'ا' else 'إ'
                    c == 'ا' && i == 0 && variant % 3 == 0 -> 'أ'
                    c == 'ى' -> 'ي'
                    c == 'ي' && last && variant % 2 == 1 -> 'ى'
                    c == 'ة' -> 'ه'
                    c == 'ه' && last && variant % 2 == 0 -> 'ة'
                    c in IND -> '0' + IND.indexOf(c)
                    c in '0'..'9' && variant % 2 == 1 -> IND[c - '0']
                    c == '٬' || c == '٫' -> if (c == '٬') ',' else '.'
                    else -> c
                },
            )
        }
        return sb.toString()
    }

    /**
     * Reference fold for relevance judgements, written independently of [ArabicNormalizer]
     * (plain regexes over the approved rules), so the recall test is not circular.
     */
    fun referenceFold(s: String): String = s
        .replace(Regex("[\\u064B-\\u065F\\u0670\\u0640]"), "")
        .replace(Regex("[أإآ]"), "ا").replace('ى', 'ي').replace('ة', 'ه')
        .map { c -> if (c in IND) '0' + IND.indexOf(c) else c }.joinToString("")
        .replace(Regex("(?<=\\d)[,٬](?=\\d{3}(?!\\d))"), "")
        .lowercase()
}
