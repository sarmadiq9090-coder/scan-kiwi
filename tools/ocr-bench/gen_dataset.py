# SPDX-FileCopyrightText: 2026 FastKiwi
# SPDX-License-Identifier: Apache-2.0
"""Synthetic Arabic/English OCR test sets for Scan Kiwi (PRD 2.0 §5.2).

Every text below is invented for this test; no real or owner document is used, ever.
Pages are laid out as HTML and rendered by a Chromium-based browser (real HarfBuzz shaping, bidi and
diacritic placement) with free OFL fonts, then degraded like phone-camera captures after the app's
perspective correction: residual tilt and perspective, defocus blur, uneven light, sensor noise, JPEG.

    python -I gen_dataset.py --set dev --out work/sets/dev [--renderer chromium|edge]

Output: <out>/<id>.png (degraded), <id>.clean.png, <id>.gt.txt (one line per visual line, logical order,
right-to-left cell order for tables) and manifest.json. Sets are defined in sets.json:
  dev      52 pages, seed 20261114, the 8 fonts and text pools of the roadmap §3 benchmark (tuning allowed)
  heldout  156 pages, other seeds, 6 other fonts, other sizes, and text pools of their own (never tuned on)
"""
import argparse
import json
import random
import shutil
import subprocess
import tempfile
from pathlib import Path

import cv2
import numpy as np

HERE = Path(__file__).resolve().parent
FONTS = HERE / "fonts"
EDGE = r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe"
IND = str.maketrans("0123456789", "٠١٢٣٤٥٦٧٨٩")

# ----------------------------------------------------------------------------------------- pool A (dev)
POOL_A = dict(
    PROSE=[
        "تعمل اللجنة على مراجعة الطلبات المقدمة خلال الأسبوع الحالي",
        "يسعدنا إبلاغكم بأن الاجتماع القادم سيعقد في قاعة المؤتمرات الرئيسية",
        "يرجى تعبئة النموذج المرفق وإعادته إلى مكتب شؤون الموظفين",
        "تقدم الشركة خدماتها للعملاء في جميع المحافظات دون استثناء",
        "حرصت الإدارة على تطوير بيئة العمل وتحسين إجراءات المتابعة",
        "يتضمن التقرير ملخصا لأهم النتائج والتوصيات المقترحة",
        "نؤكد استلام خطابكم المؤرخ ونفيدكم بأنه قيد الدراسة",
        "تستمر أعمال الصيانة في المبنى الشمالي حتى إشعار آخر",
        "يحق للمستفيد تقديم اعتراض مكتوب خلال المدة المحددة",
        "شارك الطلاب في المسابقة العلمية التي نظمتها الجامعة",
        "تم تحديث قاعدة البيانات وفق المعايير المعتمدة لدى الوزارة",
        "يلتزم المورد بتسليم البضاعة كاملة وفي الموعد المتفق عليه",
        "أوصى المجلس بتشكيل فريق عمل لمتابعة تنفيذ الخطة السنوية",
        "تتولى الإدارة المالية إعداد الميزانية التقديرية للعام المقبل",
        "نرجو التكرم بالاطلاع واتخاذ ما ترونه مناسبا بهذا الشأن",
        "استقبل المركز الصحي عددا كبيرا من المراجعين صباح اليوم",
        "يعتبر هذا العقد نافذا من تاريخ توقيعه من الطرفين",
        "تضمنت الدورة التدريبية محاضرات عملية وورش عمل تفاعلية",
        "يمنع منعا باتا استخدام الأجهزة الشخصية داخل غرفة الخوادم",
        "ظهرت نتائج الفحص الدوري مطابقة للمواصفات القياسية",
        "تشكر الإدارة جميع الموظفين على جهودهم المخلصة",
        "سيتم صرف المستحقات بعد اكتمال إجراءات التدقيق الداخلي",
        "يقع المستودع الجديد بالقرب من الطريق الدائري الثاني",
        "تهدف المبادرة إلى نشر الوعي بأهمية القراءة بين الشباب",
    ],
    DIAC=[
        "إِنَّ الْعِلْمَ نُورٌ يَهْدِي صَاحِبَهُ إِلَى الْخَيْرِ",
        "كَتَبَ الطَّالِبُ الدَّرْسَ فِي دَفْتَرِهِ الْجَدِيدِ",
        "سَافَرَ الْمُهَنْدِسُ إِلَى الْمَدِينَةِ لِحُضُورِ الْمُؤْتَمَرِ",
        "يُحِبُّ الْأَطْفَالُ اللَّعِبَ فِي الْحَدِيقَةِ الْعَامَّةِ",
        "قَرَأْتُ الْكِتَابَ مَرَّتَيْنِ وَلَمْ أَمَلَّ مِنْهُ",
        "الصَّبْرُ مِفْتَاحُ الْفَرَجِ وَالتَّوَكُّلُ أَسَاسُ النَّجَاحِ",
        "تَعَلَّمَتِ الْفَتَاةُ السِّبَاحَةَ فِي فَصْلِ الصَّيْفِ",
        "أَرْسَلَ الْمُدِيرُ رِسَالَةً إِلَى جَمِيعِ الْمُوَظَّفِينَ",
        "زَرَعَ الْفَلَّاحُ الْقَمْحَ فِي أَرْضِهِ الْخِصْبَةِ",
        "شَرِبْتُ كُوبًا مِنَ الشَّايِ السَّاخِنِ صَبَاحًا",
        "يَجْتَهِدُ الْمُعَلِّمُ فِي شَرْحِ الْمَسَائِلِ الصَّعْبَةِ",
        "وَصَلَ الْقِطَارُ إِلَى الْمَحَطَّةِ قَبْلَ مَوْعِدِهِ",
    ],
    NUM_TPL=[
        "تم استلام {a} طلبا في الربع الثالث من عام {y}",
        "بلغ عدد المشاركين {a} مشاركا من {b} دولة",
        "يتكون المبنى من {b} طوابق ويضم {a} مكتبا",
        "رقم الملف {c} والرقم المرجعي {d}",
        "ارتفعت المبيعات بنسبة {p}% مقارنة بالعام الماضي",
        "يرجى الاتصال على الرقم {ph} خلال ساعات الدوام",
        "المادة {b} من اللائحة رقم {a} لسنة {y}",
        "سجل الفريق {a} نقطة في {b} مباريات",
    ],
    AMT_TPL=[
        "المبلغ المستحق: {m} ريال سعودي",
        "الإجمالي قبل الضريبة {m} درهم",
        "ضريبة القيمة المضافة 15%: {m} ر.س",
        "رصيد الحساب {m} دينار كويتي",
        "سعر الوحدة {m} جنيه مصري",
        "قيمة الشيك {m} دينار أردني",
        "الخصم الممنوح {m} ريال",
        "Total: {m} USD",
        "المبلغ بالدولار {m} $",
        "الدفعة الأولى {m} يورو",
    ],
    DATE_TPL=[
        "تاريخ الفاتورة: {d1}",
        "تاريخ الاستحقاق {d2}",
        "صدر في {dd} {mon} {y}",
        "الموافق {hd} {hmon} {hy} هـ",
        "Due date: {iso}",
        "حرر بتاريخ {d1} الساعة {t}",
        "تنتهي صلاحية العرض في {d2}",
    ],
    ITEMS=["ورق طباعة", "أقلام حبر", "حبر طابعة", "ملفات بلاستيك", "دباسة مكتبية", "شاشة عرض",
           "لوحة مفاتيح", "كرسي مكتب", "خدمة صيانة", "رسوم شحن", "مصابيح إنارة", "كابل شبكة"],
    HEADER=["البند", "الكمية", "سعر الوحدة", "الإجمالي"],
    MIXED=[
        "يرجى إرسال الملف بصيغة PDF إلى قسم Finance قبل نهاية الأسبوع",
        "تم تثبيت نظام Windows على جميع الأجهزة الجديدة",
        "رقم الفاتورة Invoice No. INV-{y}-{c4}",
        "البريد الإلكتروني للدعم support@example.com",
        "Meeting with the procurement team on Sunday",
        "اسم المنتج Smart Router موديل AX{b}00",
        "Please sign and return the attached contract",
        "الرقم الضريبي VAT {vat}",
        "قم بتحميل التطبيق من موقع الشركة ثم اختر Settings",
        "Reference: PO-{c4} / Branch {b}",
        "الحساب البنكي IBAN SA{iban}",
    ],
)

# ------------------------------------------------------------------------------------- pool B (held-out)
POOL_B = dict(
    PROSE=[
        "قررت البلدية توسيع الحديقة العامة وزراعة أشجار جديدة",
        "يحضر المتدربون جلسات أسبوعية لمراجعة ما تعلموه",
        "أعلنت المكتبة عن تمديد ساعات العمل خلال فترة الامتحانات",
        "تلقت الجمعية تبرعات كثيرة لدعم برامج محو الأمية",
        "يناقش الفريق خطة التسويق قبل إطلاق المنتج الجديد",
        "اتفق الطرفان على تأجيل الموعد إلى بداية الشهر القادم",
        "أظهرت الدراسة تحسنا ملحوظا في مستوى رضا العملاء",
        "يجب حفظ المستندات الأصلية في مكان آمن بعيد عن الرطوبة",
        "انطلقت القافلة الطبية لتقديم الخدمات في القرى البعيدة",
        "أنجز المهندسون تصميم الجسر قبل الموعد المحدد بأسبوعين",
        "نظمت المدرسة رحلة تعليمية إلى المتحف الوطني",
        "تراجع استهلاك الكهرباء بعد تركيب الألواح الشمسية",
        "يرحب المعرض بالزوار يوميا من الصباح حتى المساء",
        "تسلمت الإدارة شكوى مكتوبة وتعمل على معالجتها",
        "حصل المشروع على جائزة أفضل فكرة مبتكرة لهذا العام",
        "ينصح الأطباء بشرب الماء بكميات كافية في فصل الصيف",
        "بدأت أعمال الترميم في السوق القديم وسط المدينة",
        "تتيح المنصة للطلاب متابعة دروسهم في أي وقت",
        "استعرض المدير نتائج الربع الأول أمام مجلس الإدارة",
        "ارتفع عدد المسافرين عبر المطار خلال موسم الإجازات",
    ],
    DIAC=[
        "جَلَسَ الشَّيْخُ تَحْتَ الشَّجَرَةِ يَقْرَأُ كِتَابًا قَدِيمًا",
        "رَسَمَتِ الْبِنْتُ لَوْحَةً جَمِيلَةً لِلْبَحْرِ",
        "اِشْتَرَى أَبِي سَيَّارَةً جَدِيدَةً مِنَ الْمَعْرِضِ",
        "نَامَ الطِّفْلُ مُبَكِّرًا بَعْدَ يَوْمٍ طَوِيلٍ",
        "فَتَحَ التَّاجِرُ دُكَّانَهُ مَعَ شُرُوقِ الشَّمْسِ",
        "حَفِظَ الطُّلَّابُ الْقَصِيدَةَ عَنْ ظَهْرِ قَلْبٍ",
        "هَطَلَ الْمَطَرُ غَزِيرًا عَلَى الْجِبَالِ الْعَالِيَةِ",
        "سَاعَدَ الْجَارُ جَارَهُ فِي نَقْلِ الْأَثَاثِ",
        "تَزْدَادُ الْمَعْرِفَةُ بِالْقِرَاءَةِ وَالسُّؤَالِ",
        "طَبَخَتِ الْأُمُّ طَعَامًا لَذِيذًا لِلضُّيُوفِ",
    ],
    NUM_TPL=[
        "وصل عدد الزوار إلى {a} زائرا في {b} أيام",
        "رقم الطلب {d} بتاريخ عام {y}",
        "يبعد المصنع {a} كيلومترا عن الميناء",
        "نسبة الإنجاز في المشروع بلغت {p}%",
        "صندوق البريد رقم {c} الرمز البريدي {d}",
        "للاستفسار اتصل على {ph}",
        "حضر الاجتماع {b} أعضاء من أصل {a}",
        "الدفعة رقم {b} من أصل {a} دفعة",
    ],
    AMT_TPL=[
        "قيمة الفاتورة {m} ريال قطري",
        "صافي الراتب {m} درهم إماراتي",
        "رسوم الخدمة {m} دينار بحريني",
        "المبلغ المسترد {m} ريال عماني",
        "Amount due: {m} EUR",
        "تكلفة الشحن {m} ريال",
        "قيمة العقد {m} جنيه",
        "Balance: {m} SAR",
    ],
    DATE_TPL=[
        "تاريخ التوقيع: {d1}",
        "يبدأ العقد في {d2}",
        "حرر في يوم {dd} من شهر {mon} لعام {y}",
        "الموافق {hd} {hmon} {hy}",
        "Issue date: {iso}",
        "آخر موعد للتسليم {d2} الساعة {t}",
    ],
    ITEMS=["أوراق لاصقة", "مجلدات", "قرطاسية", "طابعة ليزر", "جهاز عرض", "سماعات",
           "فأرة لاسلكية", "حقيبة ظهر", "اشتراك سنوي", "تركيب أجهزة", "أجهزة لوحية", "خزانة ملفات"],
    HEADER=["الصنف", "العدد", "السعر", "المجموع"],
    MIXED=[
        "أرسل التقرير بصيغة Excel إلى الفريق قبل يوم الخميس",
        "يعمل النظام على Android و iOS دون مشاكل",
        "رمز الشحنة Tracking No. SH{c4}{b}",
        "للدعم الفني راسلنا على help@example.org",
        "The meeting was moved to next Monday",
        "جهاز Laptop من نوع Pro{b} بذاكرة {b}GB",
        "Thank you for your order",
        "رقم السجل التجاري CR {d}",
        "افتح القائمة ثم اضغط على Save",
        "Order ID: ORD-{c4}-{b}",
    ],
)

MONTHS = ["يناير", "فبراير", "مارس", "أبريل", "مايو", "يونيو", "يوليو", "أغسطس",
          "سبتمبر", "أكتوبر", "نوفمبر", "ديسمبر"]
HMONTHS = ["محرم", "صفر", "ربيع الأول", "جمادى الآخرة", "رجب", "شعبان", "رمضان", "شوال", "ذو القعدة"]
CATS = ["connected", "diacritics", "numbers", "amounts", "dates", "table", "mixed"]


def digits(s, ind):
    return s.translate(IND).replace(",", "٬").replace(".", "٫") if ind else s


def money(r, ind):
    v = r.choice([r.randint(5, 999) + r.randint(0, 99) / 100, r.randint(1000, 99999) + r.randint(0, 99) / 100])
    return digits(f"{v:,.2f}", ind)


def fill(tpl, r, ind):
    y = str(r.randint(2019, 2027))
    v = dict(a=str(r.randint(12, 980)), b=str(r.randint(2, 9)), y=y, c=f"{r.randint(1000, 9999)}/{r.randint(10, 99)}",
             d=str(r.randint(100000, 999999)), p=f"{r.randint(2, 45)}.{r.randint(0, 9)}",
             ph=f"05{r.randint(10000000, 99999999)}", c4=f"{r.randint(1, 9999):04d}",
             vat=str(r.randint(300000000000000, 399999999999999)), iban=str(r.randint(10**19, 10**20 - 1))[:20])
    out = tpl.format(**v)
    if ind:  # Arabic-Indic digits only inside Arabic-only templates
        out = "".join(ch.translate(IND) if ch.isdigit() else ch for ch in out)
    return out


def date_line(tpl, r, ind):
    y, mo, d = r.randint(2020, 2027), r.randint(1, 12), r.randint(1, 28)
    v = dict(d1=f"{y}/{mo:02d}/{d:02d}", d2=f"{d:02d}/{mo:02d}/{y}", dd=str(d), mon=MONTHS[mo - 1], y=str(y),
             hd=str(r.randint(1, 29)), hmon=r.choice(HMONTHS), hy=str(r.randint(1440, 1449)),
             iso=f"{y}-{mo:02d}-{d:02d}", t=f"{r.randint(7, 22):02d}:{r.choice(['00', '15', '30', '45'])}")
    s = tpl.format(**v)
    if ind and "Due" not in tpl and "Issue" not in tpl:
        s = s.translate(IND)
    return s


def make_lines(cat, r, P):
    ind = r.random() < 0.4  # 40% of numeric pages use Arabic-Indic digits
    if cat == "connected":
        return r.sample(P["PROSE"], 6), False
    if cat == "diacritics":
        return r.sample(P["DIAC"], 5), False
    if cat == "numbers":
        return [fill(t, r, ind) for t in r.sample(P["NUM_TPL"], 6)], False
    if cat == "amounts":
        out = []
        for t in r.sample(P["AMT_TPL"], 6):
            latin = any(k in t for k in ("USD", "$", "EUR", "SAR"))
            out.append(t.format(m=money(r, ind and not latin)))
        return out, False
    if cat == "dates":
        return [date_line(t, r, ind) for t in r.sample(P["DATE_TPL"], 6)], False
    if cat == "table":
        rows = [list(P["HEADER"])]
        for it in r.sample(P["ITEMS"], 5):
            q = r.randint(1, 40); p = r.randint(3, 900) + r.choice([0, 0.5, 0.25, 0.75])
            rows.append([it, digits(str(q), ind), digits(f"{p:,.2f}", ind), digits(f"{q * p:,.2f}", ind)])
        return rows, True
    if cat == "mixed":
        return [fill(t, r, False) for t in r.sample(P["MIXED"], 6)], False
    raise ValueError(cat)


def html_page(lines, is_table, font, size_pt):
    px = size_pt * 150 / 72  # page is 1240 CSS px wide = A4 at 150 CSS px per inch
    ff = f"'{font}', 'NotoSans-400'"
    faces = "".join(f"@font-face{{font-family:'{f}';src:url('{(FONTS / (f + '.ttf')).as_uri()}');}}"
                    for f in sorted(set([font, "NotoSans-400"])))
    if is_table:
        body = "<table>" + "".join(
            "<tr>" + "".join(f"<{'th' if i == 0 else 'td'}>{c}</{'th' if i == 0 else 'td'}>" for c in row) + "</tr>"
            for i, row in enumerate(lines)) + "</table>"
    else:
        body = "".join(f"<p>{l}</p>" for l in lines)
    return f"""<!doctype html><html lang="ar" dir="rtl"><head><meta charset="utf-8"><style>{faces}
html,body{{margin:0;background:#fff;}} body{{width:1240px;padding:70px 90px;box-sizing:border-box;font-family:{ff};
font-size:{px:.1f}px;color:#111;line-height:1.9}} p{{margin:0 0 {px * 0.6:.0f}px 0;white-space:nowrap}}
table{{border-collapse:collapse;width:100%}} td,th{{border:2px solid #333;padding:{px * 0.5:.0f}px {px * 0.7:.0f}px;text-align:right;white-space:nowrap}}
th{{background:#e8e8e8;font-weight:normal}}</style></head><body>{body}</body></html>"""


class EdgeRenderer:
    """Headless Microsoft Edge (Windows), as in the roadmap §3 run."""

    def __init__(self, work):
        self.prof = Path(work).resolve() / ".edge-profile"

    def render(self, html_file: Path, out_png: Path):
        cmd = [EDGE, "--headless=new", "--disable-gpu", "--no-first-run", "--no-default-browser-check",
               "--disable-background-networking", "--disable-component-update", "--disable-sync",
               "--disable-extensions", "--hide-scrollbars", f"--user-data-dir={self.prof}",
               "--force-device-scale-factor=2", "--window-size=1240,1100", f"--screenshot={out_png}", html_file.as_uri()]
        subprocess.run(cmd, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=60)

    def close(self):
        pass


class ChromiumRenderer:
    """Playwright's Chromium (pinned in requirements-lock.txt): runs on Windows and Linux CI."""

    def __init__(self, work):
        from playwright.sync_api import sync_playwright
        self.pw = sync_playwright().start()
        # channel "chromium": the full browser in the new headless mode (as Edge --headless=new), not the
        # separate headless shell, whose text rasterisation differs.
        self.browser = self.pw.chromium.launch(channel="chromium", args=["--disable-gpu", "--hide-scrollbars", "--disable-background-networking"])
        self.page = self.browser.new_page(viewport={"width": 1240, "height": 1100}, device_scale_factor=2)

    def render(self, html_file: Path, out_png: Path):
        self.page.goto(html_file.as_uri(), wait_until="load")
        self.page.evaluate("document.fonts.ready.then(() => true)")
        self.page.screenshot(path=str(out_png), full_page=False)

    def close(self):
        self.browser.close()
        self.pw.stop()


def crop_content(img, margin=60):
    g = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
    ys, xs = np.where(g < 200)
    y0, y1 = max(0, ys.min() - margin), min(img.shape[0], ys.max() + margin)
    x0, x1 = max(0, xs.min() - margin), min(img.shape[1], xs.max() + margin)
    return img[y0:y1, x0:x1]


def degrade(img, level, r):
    """Phone-camera look after the app's perspective correction. Deterministic per seed."""
    h, w = img.shape[:2]
    if level == "scan":
        p = dict(tilt=0.4, persp=0.003, blur=0.6, light=0.08, noise=3, jpeg=88, scale=1.0)
    elif level == "phone":
        p = dict(tilt=1.2, persp=0.012, blur=1.3, light=0.30, noise=7, jpeg=70, scale=0.85)
    else:  # "low": small print, cheap camera, dim room
        p = dict(tilt=1.8, persp=0.02, blur=1.9, light=0.42, noise=11, jpeg=55, scale=0.6)
    pad = int(0.03 * max(h, w))
    img = cv2.copyMakeBorder(img, pad, pad, pad, pad, cv2.BORDER_CONSTANT, value=(255, 255, 255))
    h, w = img.shape[:2]
    j = lambda: r.uniform(-1, 1) * p["persp"] * w
    src = np.float32([[0, 0], [w, 0], [w, h], [0, h]])
    dst = np.float32([[j(), j()], [w + j(), j()], [w + j(), h + j()], [j(), h + j()]])
    M = cv2.getPerspectiveTransform(src, dst)
    R = cv2.getRotationMatrix2D((w / 2, h / 2), r.uniform(-p["tilt"], p["tilt"]), 1.0)
    M = np.vstack([R, [0, 0, 1]]) @ M
    img = cv2.warpPerspective(img, M, (w, h), flags=cv2.INTER_LINEAR, borderValue=(250, 250, 250))
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float32)
    cx, cy = r.uniform(0.2, 0.8) * w, r.uniform(0.2, 0.8) * h
    d = np.sqrt(((xx - cx) / w) ** 2 + ((yy - cy) / h) ** 2)
    side = np.clip((xx / w - r.uniform(0.6, 0.9)) * 3, 0, 1) * r.uniform(0.3, 1.0)
    light = 1 - p["light"] * (0.6 * d + 0.4 * side)
    tint = np.array([0.93, 0.97, 1.0], np.float32)  # BGR, slightly warm
    f = img.astype(np.float32) * tint * light[..., None]
    f = cv2.GaussianBlur(f, (0, 0), p["blur"])
    f += np.random.default_rng(r.randint(0, 2**31)).normal(0, p["noise"], f.shape).astype(np.float32)
    img = np.clip(f, 0, 255).astype(np.uint8)
    if p["scale"] != 1.0:
        img = cv2.resize(img, None, fx=p["scale"], fy=p["scale"], interpolation=cv2.INTER_AREA)
    ok, enc = cv2.imencode(".jpg", img, [cv2.IMWRITE_JPEG_QUALITY, p["jpeg"]])
    return cv2.imdecode(enc, cv2.IMREAD_COLOR)


def plan_for(spec):
    """(category, level, size_pt) per page, in generation order."""
    plan = []
    levels = ["scan", "phone", "phone", "scan", "phone", "phone"]
    sizes = spec["sizes_pt"]
    for cat in CATS:
        for k in range(spec["pages_per_category"]):
            plan.append((cat, levels[k % len(levels)], sizes[k % len(sizes)]))
    for k in range(spec["small_lowq_pages"]):
        plan.append((CATS[k % len(CATS)], "low", spec["small_pt"]))
    return plan


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--set", required=True)
    ap.add_argument("--out", required=True, type=Path)
    ap.add_argument("--renderer", choices=["chromium", "edge"], default="chromium")
    a = ap.parse_args()
    spec = json.loads((HERE / "sets.json").read_text(encoding="utf-8"))[a.set]
    pool = {"A": POOL_A, "B": POOL_B}[spec["pool"]]
    fonts = spec["fonts"]
    r = random.Random(spec["seed"])
    if a.out.exists():
        shutil.rmtree(a.out)
    a.out.mkdir(parents=True)
    pages = []
    renderer = (ChromiumRenderer if a.renderer == "chromium" else EdgeRenderer)(a.out)
    try:
        with tempfile.TemporaryDirectory() as td:
            for i, (cat, level, pt) in enumerate(plan_for(spec)):
                lines, is_table = make_lines(cat, r, pool)
                font = fonts[i % len(fonts)]
                sid = f"{i:03d}_{'small_lowq' if level == 'low' else cat}"
                clean = a.out / f"{sid}.clean.png"
                html = Path(td) / "page.html"
                html.write_text(html_page(lines, is_table, font, pt), encoding="utf-8")
                renderer.render(html, clean)
                img = crop_content(cv2.imread(str(clean)))
                cv2.imwrite(str(clean), img)
                cv2.imwrite(str(a.out / f"{sid}.png"), degrade(img, level, r))
                gt = [" ".join(row) for row in lines] if is_table else lines
                (a.out / f"{sid}.gt.txt").write_text("\n".join(gt) + "\n", encoding="utf-8")
                pages.append(dict(id=sid, category="small_lowq" if level == "low" else cat, source_category=cat,
                                  level=level, font=font, size_pt=pt, table=is_table, lines=len(gt)))
                print(sid, font, level, flush=True)
    finally:
        renderer.close()
        shutil.rmtree(a.out / ".edge-profile", ignore_errors=True)
    man = dict(generator="scan-kiwi gen_dataset.py", set=a.set, seed=spec["seed"], renderer=a.renderer, pages=pages)
    (a.out / "manifest.json").write_text(json.dumps(man, ensure_ascii=False, indent=1), encoding="utf-8")


if __name__ == "__main__":
    main()
