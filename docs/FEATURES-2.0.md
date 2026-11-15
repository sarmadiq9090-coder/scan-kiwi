<!-- SPDX-FileCopyrightText: 2026 FastKiwi -->
<!-- SPDX-License-Identifier: Apache-2.0 -->
# ماسح كيوي 2.0.0 — قائمة الميزات / Scan Kiwi 2.0.0 — feature list

المصدر: `docs/features-2.0/{ocr,camera,pdf,docs,perf,update}.md` (التفاصيل والطرق هناك)، و`PRD-2.0.md` و`ROADMAP-2.0.md`.
كل الأرقام مقيسة على الحاسوب (JVM وRobolectric، صفحات وصور اصطناعية)؛ ما يحتاج هاتفاً مذكور في «فحوص المالك على الهاتف».
الحالة: **منجز** = المعيار تحقق بالرقم المذكور؛ **جزئي**؛ **غير منجز** مع السبب.

## العربية

### قراءة النص (OCR) — PRD §5
| الميزة | المعرّف | الحالة | معيار القبول | النتيجة | الاختبار |
|---|---|---|---|---|---|
| واجهة محرك مستقلة `OcrEngine` | §5.3، §13 | منجز | المُصدِّر والتطبيق عبر الواجهة فقط؛ الإلغاء يوقف المحرك | محرك وهمي يركّب؛ الإلغاء يوقف الصفحة | `OcrExportTest`، `OcrCoreTest` |
| «دقيق»: PP-OCRv6 كشف + PP-OCRv5 عربي على MNN (fp16) + إعادة قراءة Tesseract | OCR-01..05 | منجز | CER ≤ 20٪ وأقل بـ 30٪ من tessdata_fast؛ جداول ≤ 10٪؛ أرقام ≥ 65٪ | CER **9.0٪** (tess_fast 29.0٪)، جداول **4.8٪**، أرقام **72.1٪** على 156 صفحة محجوزة | bench، `FusedPipelineGoldenTest` |
| الصفحات المختلطة عربي/إنجليزي | OCR-03، قرار 2 | منجز | المختلط ≤ +5 نقاط عن tess_fast | **−9.4 نقطة** (5.9٪ مقابل 15.3٪) | `FusedPartsTest.mixedLines…` |
| تطابق الهاتف والمرجع (ONNX) | قرار 3 | منجز | ±0.5 نقطة | **−0.22 نقطة** | bench |
| «سريع» والرجوع التلقائي إليه | 1.5 بند 2 | منجز | OCR يعمل بلا مكتبة MNN | نص PDF موجود، لا رسالة «غير متاح» | `OcrSelectionTest` |
| الجداول صفوفاً من الخلايا بترتيب القراءة | OCR-06 | منجز | ≤ 10٪ | 4.8٪ | `FusedPartsTest`، golden |
| القراءة في الخلفية بعد التصوير | 1.5 بند 3 | منجز | التصدير لا يعيد القراءة | المحرك يُستدعى مرة واحدة | `OcrSelectionTest` |
| سطر التشخيص (محلي فقط) | 1.5 بند 7 | منجز | لا شيء يخرج من الهاتف | تفضيلات خاصة فقط | فحص المالك |
| ملاحظة الدقة بلا نسبة مئوية | §5 | منجز | لا نسبة في الواجهة | `ocr_quality_note` | — |
| arm64-v8a وarmeabi-v7a فقط | 1.5 بند 6 | منجز | معماريتان | بوابة الحجم | size gate |
| أداة القياس في المستودع | BENCH | جزئي | إعادة 27.7٪ ± 0.5 | 27.7٪ بصفحات Edge؛ Chromium +1.8 نقطة (مسجّل) | `tools/ocr-bench` |
| «دقيق» ≤ 8 ث/صفحة و≤ 300 م.ب على الهاتف | 1.5 | غير منجز | — | لا هاتف هنا؛ بديل الحاسوب 1.17 ث و231 م.ب | فحص المالك |

### الكاميرا والصورة — PRD §4
| الميزة | المعرّف | الحالة | معيار القبول | النتيجة | الاختبار |
|---|---|---|---|---|---|
| كشف الحواف (إضاءة خافتة، تباين منخفض، خلفيات معقدة) | CAM-01/02/05 | منجز | ≥ 90٪ ضمن 3٪، صفحات كاذبة ≤ 5٪، ≤ 25 م.ث/إطار | **91.7٪**، **0٪** كاذبة، 18–22 م.ث (1.2.0: 69.4٪) | `EdgeBenchTest.acceptance20` |
| الاهتزاز وعدم الوضوح | CAM-03 | منجز | AUC ≥ 0.95 | **0.996** (معاينة)، 0.954 (لقطات) | `QualityBenchTest` |
| الصفحة غير المكتملة | CAM-04 | منجز | ≥ 95٪ | **95.0٪** (38/40) | `QualityBenchTest` |
| التصوير المتتابع حسب محتوى الصفحة | CAM-06 | منجز | معيار منفصل | **42/42**، 0 تكرار | batch bench |
| تقرير الجودة ومؤشرها وشريحة التنبيه | §4.3، §7.2 | منجز | تنبيه لا يمنع أبداً | `ScanPage.quality`؛ «تجاهل» يحفظ المراجعة | `CameraUi20Test` |
| إعادة القص والفلتر من الأصل | §4 قبول | منجز | بلا تصوير جديد ولا فقد جودة | بصمة الأصل ثابتة | `CameraUi20Test` |
| وضع الكتاب | A4 | منجز | الكعب ضمن 2٪ على ≥ 90٪ | **40/40** | `BookBenchTest` |
| دمج لقطتين لإزالة اللمعان | A4 | منجز | ≥ 90٪ من 30 زوجاً | **96.7٪**، SSIM ≥ 0.904 | `GlareBenchTest` |
| تقليل الضجيج | §4.2 | منجز | أنظف دون فقد النص | PSNR أفضل 54/54 | cam2 tests |
| المحاذاة التلقائية و«تسوية الميل» | §4.2 | منجز | ميل متبقٍ بلا إعادة عيّنة ثانية | 40/40 ضمن 0.3° | cam2 tests |
| المعالجة عالية الدقة بشرائح | §4.2، A8 | منجز | > 12 م.ب ضمن الذاكرة | صفحة 50 م.ب بذاكرة ≤ 24.2 م.ب، بكسلات مطابقة | cam2 tests |
| تسوية الصفحات المنحنية | A4 | غير منجز | — | بقرار A4: يحتاج نموذجاً عصبياً (2.1+) | — |

### أدوات PDF — PRD §6
| الميزة | المعرّف | الحالة | النتيجة | الاختبار |
|---|---|---|---|---|
| الدمج، التقسيم، الاستخراج | PDF-01/02/03 | منجز | العدد والترتيب والبايتات محفوظة؛ 30/30 استخراج دقيق | `PdfOpsAcceptanceTest` |
| إعادة الترتيب بالسحب + أوامر TalkBack | PDF-04 | منجز | سحب حقيقي في محرر RTL | `PdfEditorTest` |
| التدوير والحفظ | PDF-05 | منجز | خطوة تراجع واحدة لعدة صفحات | `PdfOpsAcceptanceTest` |
| الضغط الذكي والحجم المحدد | PDF-06، §6.2 | منجز | 50 تجربة: 49 وصلت، 1 أخبرت بالحجم الحقيقي، **0 نجاح كاذب** | `TargetSizeTest` |
| صور إلى PDF، صفحات إلى JPG | PDF-07/08 | منجز (JPG للملفات الخارجية: فحص الهاتف) | — | `AppUiTest` |
| ترقيم الصفحات والعلامة المائية | PDF-09/10 | منجز | PDF/A-2b يجتاز veraPDF | `DecoratedExportTest` |
| صفحة فارغة، استخراج النص | PDF-11/12 | منجز | ترتيب منطقي للعربية | `PdfOpsAcceptanceTest` |
| كلمة مرور AES-256 وإزالتها بكلمة المرور الصحيحة | PDF-13/14 | منجز | الخاطئة: خطأ بلا ملف | `PdfOpsAcceptanceTest.unlock…` |
| سلامة الملف (مؤقت ← تحقق ← إعادة تسمية ذرية) | §6.3 | منجز | 10/10 قطع: الأصل سليم | `ExportIntegrityTest` |
| PdfBox-Android معزول في عملية `:pdf` | 1.4 | منجز | +0.63 م.ب؛ 200 ملف تالف بلا انهيار | `PdfFuzzTest`، `IsolatedPdfEngineTest` |
| تراجع وإعادة في المحرر | §7.3 | منجز | 20 خطوة | `EditHistoryTest` |

### المستندات والبحث — PRD §7.1، OCR-07/08/10
| الميزة | الحالة | النتيجة | الاختبار |
|---|---|---|---|
| البحث بالعربية في كل المستندات (FTS4) | منجز | استرجاع **100٪**، أسوأ استعلام **10.8 م.ث** على 1000 صفحة | `SearchIndexTest` |
| المطبّع العربي (حركات، همزات، ى/ي، ة/ه، أرقام هندية) | منجز | 29 صورة | `ArabicNormalizerTest` |
| تظليل الكلمة على صورة الصفحة | منجز | — | `DocsArabicUiTest` |
| تصدير TXT ونسخ النص | منجز | 52 صفحة متطابقة بايتاً ببايت | `TextExporterTest` |
| تصحيح النص (البحث وطبقة PDF يريان التصحيح) | منجز | الصورة لا تتغير؛ التراجع يعيد النص | `TextOverlayTest` |
| المفضلة، المجلدات (مستوى واحد)، الترتيب، الرئيسية 2.0 | منجز | ترحيل 25/25 مستنداً؛ حذف مجلد لا يحذف مستندات؛ ≥ 48 dp وخط 200٪ | `FolderStoreTest`، `DocsEnglishUiTest` |

### الأداء والذاكرة — PRD §8
| الميزة | الحالة | النتيجة | الاختبار |
|---|---|---|---|
| وضع الذاكرة المنخفضة (تلقائي < 3 غ.ب، وخيار في الإعدادات) | منجز | OCR وتصدير 2000 بكسل | `PerfCoreTest` |
| 20 صفحة مع OCR ≤ 256 م.ب؛ 200 صفحة ≤ 128 م.ب؛ 500 صفحة بلا نفاد | منجز | **89.7** و**49.1** م.ب؛ 500 صفحة في 512 م.ب بلا OOM | `HeapBudgetTest`، `StressHeapCapTest` |
| أندرويد 7.0 | منجز في Robolectric | 9/9 تدفقات على API 24/26/28 | `OldAndroidFlowTest` |
| StrictMode في التطوير، Baseline Profile | منجز | — | `PerfCoreTest` |
| بدء التشغيل ≤ 1 ث، OCR أسرع بـ 20٪ على الهاتف | غير منجز | لا هاتف هنا (بديل JVM: 86 م.ث) | فحص المالك |

### التحديث من GitHub (D30، أمر المالك)
| الميزة | الحالة | النتيجة | الاختبار |
|---|---|---|---|
| مصدر واحد عبر HTTPS لنطاقات GitHub الأربعة فقط، كل إعادة توجيه تُفحص | منجز | http ونطاقات أخرى مرفوضة | `UpdaterTest.onlyAllowedHosts…` |
| SemVer، تطابق SHA256SUMS، شهادة التوقيع نفسها، versionCode أعلى | منجز (الشهادة على الهاتف لم تُجرَّب) | أي فشل يحذف الملف | `UpdaterTest` |
| فحص تلقائي مرة كل 7 أيام عند فتح التطبيق؛ زر يدوي في أي وقت | منجز | ساعة أُرجعت: يُعاد العدّ من الوقت المصحّح | `UpdaterTest.automaticCheck…` |
| «التنزيل عبر Wi-Fi فقط» طوال التنزيل | منجز | بيانات الهاتف أثناء التنزيل توقفه | `UpdaterTest.wifiLost…` |
| انقطاع الاتصال أثناء الاستجابة | منجز | رسالة شبكة، لا انهيار | `UpdaterTest.aConnectionThatDies…` |
| الإشعار لمرة واحدة («ماسح كيوي»)، شاشة «التحديثات»، PackageInstaller | منجز | — | `UpdateUiTest` |
| الأذونات: CAMERA وINTERNET وACCESS_NETWORK_STATE وREQUEST_INSTALL_PACKAGES فقط | منجز | CI يفحص القائمة وأن كود الشبكة الوحيد `update/UpdateClient.kt` | CI |

### ما لم يُنجز وأسبابه
- تسوية الصفحات المنحنية: بقرار A4 (تحتاج نموذجاً عصبياً كبيراً).
- قياسات الهاتف (زمن «دقيق»، ذاكرة المحرك، بدء التشغيل، OCR −20٪، عتبات الوضوح، أندرويد 7/8 على جهاز حقيقي، مسار التحديث): لا هاتف في بيئة التطوير؛ أداة القياس جاهزة (سطر التشخيص).
- إعادة إنتاج القياس بمتصفح Chromium: فرق 1.8 نقطة لـ Tesseract وحده (مسجّل؛ صفحات Edge هي المرجع).

### مشكلات معروفة (تُعالج في 2.1)
- النص المكتوب ولم يُحفظ بعد (حقل تصحيح النص وما شابه) يضيع إن أنهى النظام عملية التطبيق وهو في الخلفية؛ يعود التطبيق إلى الشاشة الرئيسية كما في 1.x. لا يضيع أي مستند محفوظ.

### قائمة 2.1
- المجلدات المتداخلة (2.0 بمستوى واحد بقرار المدير 4).
- تسوية الصفحات المنحنية (مستبعدة من 2.0 بقرار A4).
- (الصفحات المختلطة أُصلحت في 2.0، فلا بند لها.)
- حفظ النص غير المحفوظ عند إنهاء العملية (المشكلة المعروفة أعلاه).

### فحوص المالك على الهاتف
«دقيق» ≤ 8 ث/صفحة و≤ 300 م.ب، بدء ≤ 1 ث، OCR −20٪، عتبات الوضوح، جهاز أندرويد 7/8، ومسار التحديث (قراءة الشهادة، تأكيد PackageInstaller، كشف Wi-Fi، إصدار حقيقي)، إضافة إلى قوائم الكاميرا وPDF في ملفاتها.

---

## English

Same content in short; details in `docs/features-2.0/*.md`. All numbers are desktop measurements (JVM/Robolectric,
synthetic data). Status: done / partial / not done (with reason).

| Area | Feature | Status | Measured | Test |
|---|---|---|---|---|
| OCR | Engine interface; Accurate (PP-OCRv6 det + PP-OCRv5 Arabic rec, MNN fp16, Tesseract re-read); Fast fallback | done | held-out CER 9.0 % (tess_fast 29.0 %), tables 4.8 %, numbers 72.1 %, mixed −9.4 pp, ONNX parity −0.22 pp | bench, `OcrSelectionTest`, `FusedPartsTest` |
| OCR | Background OCR, diagnostics row, neutral accuracy note, 2 ABIs | done | export runs no OCR after a background read | `OcrSelectionTest` |
| OCR | Benchmark harness | partial | 27.7 % reproduced (Edge); Chromium +1.8 pp | `tools/ocr-bench` |
| OCR | Accurate ≤ 8 s/page, ≤ 300 MB native on the phone | not done | no phone (desktop 1.17 s, 231 MB) | owner check |
| Camera | Edge detection (dim, low contrast, clutter), blur, incomplete page, content-based batch | done | 91.7 % / 0 % false; AUC 0.996; 95 %; 42/42 | `EdgeBenchTest`, `QualityBenchTest` |
| Camera | Quality report and chips, re-crop from original, book mode, glare fusion, denoise, auto-deskew, strip processing | done | 40/40 spine; 96.7 % glare; 54/54 PSNR; 40/40 deskew; 50 MP in ≤ 24.2 MB | `CameraUi20Test`, benches |
| Camera | Curved-page dewarping | not done | decision A4 (needs a neural model) | — |
| PDF | PDF-01..14 (merge, split, extract, reorder, rotate, compress/target size, images↔PDF/JPG, numbers, watermark, blank page, text, AES-256, unlock) | done (external PDF→JPG: phone check) | target size 0 false successes / 50; veraPDF pass; fuzz 200/200 | `PdfOpsAcceptanceTest`, `TargetSizeTest`, `PdfFuzzTest` |
| PDF | Integrity (temp → validate → atomic rename), `:pdf` isolation, Undo/Redo | done | 10/10 kills keep the original; +0.63 MB | `ExportIntegrityTest`, `IsolatedPdfEngineTest` |
| Docs | FTS4 Arabic search, highlight, TXT, text correction, favorites, one-level folders, sort, Home 2.0 | done | recall 100 %, worst 10.8 ms / 1000 pages; TXT byte-identical | `SearchIndexTest`, `TextExporterTest`, `DocsArabicUiTest` |
| Perf | Low-memory mode, heap budgets, 500-page stress, Android 7 (Robolectric), StrictMode, baseline profile | done | 89.7 MB / 49.1 MB; no OOM at 512 MB; 9/9 flows on API 24/26/28 | `HeapBudgetTest`, `OldAndroidFlowTest` |
| Perf | Cold start ≤ 1 s, OCR −20 % on the phone | not done | no phone (JVM proxy 86 ms) | owner check |
| Update | GitHub-only HTTPS check (4 hosts), SemVer, SHA256SUMS, same signer, higher versionCode, weekly at app open, Wi-Fi only for the whole download, one-time notice, PackageInstaller | done (phone path unverified) | any failure deletes the file; a dropped connection is a network error, never a crash | `UpdaterTest`, `UpdateUiTest`, CI permission check |

**Known issue (2.1):** text typed but not yet saved (for example in the text-correction field) is lost if Android ends the app process while it is in the background; the app restarts at Home, as in 1.x. No saved document is affected.
**2.1 list:** nested folders; curved-page dewarping. (Mixed pages were fixed in 2.0.)
**2.1 list also:** keep unsaved typed text across process death.
**Owner phone checks:** Accurate time and native heap, cold start, OCR −20 %, blur thresholds, an Android 7/8 device, the
update path on a phone (certificate read, PackageInstaller confirmation, Wi-Fi detection, a real release).
