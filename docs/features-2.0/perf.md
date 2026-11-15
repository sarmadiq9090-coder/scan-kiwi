# ميزات 2.0 — الأداء والذاكرة وأندرويد ٧ (ROADMAP 1.7.0)

الفرع `feat/2.0-perf` (من `release/2.0.0` 9cd71de). كل الأرقام مقيسة على الحاسوب (JVM 21 + Robolectric 4.16.1 بالرسوم الأصلية،
Windows 11)، ببيانات مصنوعة بالكامل في كود الاختبار (لا مستند حقيقي). لا شيء هنا قيس على هاتف.

## ملخص بالعربية

- **وضع الذاكرة المنخفضة**: يعمل تلقائياً إذا قال النظام إن الهاتف قليل الذاكرة (`isLowRamDevice`) أو كانت ذاكرته أقل من ٣ غيغابايت،
  ويمكن فرضه أو إيقافه من الإعدادات («الأداء ← وضع الذاكرة المنخفضة»: تلقائي / مفعّل دائماً / متوقف). فيه: قراءة النص والتصدير
  بدقة ٢٠٠٠ بكسل، وقارئ نص واحد («سريع»، خيط واحد)، وصور مصدر لا تتجاوز ٢٤٠٠ بكسل. كل الحدود في ملف واحد: `perf/MemoryBudget.kt`.
- **لا نسخ زائدة للصور**: قراءة النص تأخذ مستوى الرمادي شريطاً شريطاً (٦٤ سطراً) بدل نسخة ARGB كاملة: صفحة ٢٤٠٠×٣٢٠٠
  كانت تحجز **٣٦٫٦ م.ب** صارت **٧٫٩ م.ب**. صفحات الأبيض والأسود تُحزم بالطريقة نفسها. التصدير يعيد استخدام صورة فك الترميز نفسها
  لكل الصفحات (`inBitmap`)، وتصدير JPEG يفك الصورة بحجم التصدير فقط. محرّك النص يحرّر ذاكرته الأصلية بعد دقيقة بلا عمل.
- **اختبارات الذاكرة**: تصدير ٢٠ صفحة مع قراءة النص: **٦٧٫٧ م.ب** (الحد ٢٥٦). تصدير ٢٠٠ صفحة بلا نص: **٧٤٫٠ م.ب** (الحد ١٢٨)،
  ولا تنمو الذاكرة مع عدد الصفحات. ٥٠٠ صفحة مع النص ثم JPEG بذاكرة جافا محدودة بـ ٥١٢ م.ب: **لا نفاد للذاكرة**.
- **بدء التشغيل**: أضيف ملف Baseline Profile للتطبيق (لم يكن موجوداً رغم ما تقوله الخارطة) يغطي مسار البدء وكل شاشات 2.0،
  واختبار يمنع أن يقدم. زمن أول إطار على الحاسوب **١٠٢ مللي ثانية** (بديل تقريبي فقط؛ هدف ١ ثانية يُقاس على هاتف المالك).
- **أندرويد ٧٫٠ (API 24)**: الرئيسية، شاشة إذن الكاميرا، المحرر، التصدير بكل الخيارات، أدوات PDF، البحث، الإعدادات، شاشة النص:
  كلها تعمل في Robolectric على API 24، مع فحص على 26 و28. ما لا يدعمه أندرويد ٧ مخفي أو بديل (ألوان الخلفية، لغة التطبيق).
- **StrictMode** في نسخة التطوير فقط (تسجيل، بلا إيقاف)، ولا شيء منه في نسخة الإصدار.

## جدول الميزات

| الميزة | English | المعرّف | الحالة | معيار القبول | النتيجة المقيسة | الاختبار |
|---|---|---|---|---|---|---|
| وضع الذاكرة المنخفضة | Low-memory mode | 1.7.0 بند 1، PRD §8 | منجز | `isLowRamDevice` أو < ٣ غ.ب ← OCR وتصدير ٢٠٠٠ بكسل، عامل واحد، فك ترميز بالعيّنات | ٦ صفحات HIGH مع OCR: المستوى المقروء **٢٠٠٠** بكسل (عادي ٢٤٠٠)، أكبر صورة في PDF **٢٠٠٠** (عادي ٣٢٠٠)، الذاكرة **٥٨٫٧** مقابل **٦٦٫٤** م.ب؛ المحرّك «سريع» (Tesseract خيط واحد) ولا يُحمَّل «دقيق» | `perf.HeapBudgetTest.lowMemoryModeReadsAndExportsAt2000px`، `perf.PerfCoreTest.lowMemoryThresholdsAndOverride`، `perf.PerfCoreTest.settingsOverrideAppliesAtOnce` |
| خيار الإعدادات | Settings override | 1.7.0 بند 1 | منجز | — | «تلقائي · مفعّل/غير مطلوب على هذا الهاتف»، «مفعّل دائماً»، «متوقف»؛ يُطبَّق فوراً (حجم المصدر، اختيار المحرك) | `perf.PerfCoreTest.settingsOverrideAppliesAtOnce`، `perf.OldAndroidFlowTest.screensRenderAndWork` |
| لا نسخة ARGB لقراءة النص | OCR gray plane, no `getPixels` copy | 1.7.0 بند 2 | منجز | تمرير مستوى رمادي مباشرة | صفحة ٢٤٠٠×٣٢٠٠: **٣٦٫٦ ← ٧٫٩ م.ب** محجوزة (المستوى نفسه ٧٫٣)؛ البايتات مطابقة تماماً لطريقة 1.2 | `perf.PerfCoreTest.ocrInputAllocatesNoFullArgbCopy`، `perf.PerfCoreTest.grayPlaneInStripsEqualsTheFullCopy` |
| حزم الأبيض والأسود بلا نسخة | Bitonal packing in strips | 1.7.0 بند 2 | منجز | — | بتات مطابقة لـ `packBits`؛ لا مصفوفة ٤ بايت لكل بكسل (صفحة ٢٨٠٠×٢١٠٠: كانت ٢٢٫٤ م.ب) | `perf.PerfCoreTest.grayPlaneInStripsEqualsTheFullCopy` |
| إعادة استخدام الصورة | Bitmap reuse (`inBitmap`) | 1.7.0 بند 2 | منجز | — | الصورة نفسها لكل صفحات التصدير، والبكسلات مطابقة لفك الترميز العادي | `perf.PerfCoreTest.poolReusesTheDecodeBitmapAndGivesIdenticalPixels` |
| تصدير يفك ما يلزم فقط | Export decodes only what it needs | 1.7.0 بند 2 | منجز | — | JPEG يفك بحجم التصدير (كان ٣٢٠٠ دائماً)؛ PDF كان كذلك من قبل | `perf.HeapBudgetTest.jpegExportDecodesOnlyWhatItNeeds` |
| تحرير ذاكرة المحرك بعد دقيقة | Release native OCR memory after an idle minute | 1.7.0 بند 2 | منجز (كان موجوداً، تحقّق وربط بالحد) | — | المحرك يُغلق بعد مدة الخمول ولا يُغلق قبلها؛ صفحة جديدة تعيد العدّاد؛ خدمة التطبيق ٦٠٠٠٠ مللي ثانية من `MemoryBudget` | `perf.PerfCoreTest.ocrEngineIsReleasedAfterIdleTime` |
| اختبار الذاكرة ٢٠ صفحة مع OCR | Heap test, 20 pages + OCR | 1.7.0 قبول | منجز | ≤ ٢٥٦ م.ب | **٦٧٫٧ م.ب** (أصلية ٥٦٫٣ + جافا ١١٫٤)، HIGH، صفحات ٢٤٠٠×٣٢٠٠ | `perf.HeapBudgetTest.twentyPagesWithOcrStayUnder256MB` |
| اختبار الذاكرة ٢٠٠ صفحة | Heap test, 200 pages, no OCR | 1.7.0 قبول | منجز | ≤ ١٢٨ م.ب | **٧٤٫٠ م.ب** (أصلية ٧٠٫٦ + جافا ٣٫٤)؛ ذاكرة جافا المتبقية بعده **٠٫٠** م.ب | `perf.HeapBudgetTest.twoHundredPagesWithoutOcrStayUnder128MB` |
| اختبار الضغط ٥٠٠ صفحة | 500-page stress, 512 MB heap cap | 1.7.0 قبول | منجز | لا OutOfMemoryError | **لا نفاد**: PDF مع OCR لـ ٥٠٠ صفحة في ٤٤ ث، ثم JPEG لـ ٥٠٠ صفحة في ٣٢ ث، في JVM حدها ٥١٢ م.ب | `perf.StressHeapCapTest` (مهمة Gradle `perfStressTest`) |
| بدء التشغيل | Cold start / Baseline Profile | 1.7.0 بند 3 | جزئي | ≤ ١٫٠ ث على هاتف المالك | ملف `baseline-prof.txt` جديد (البدء + كل شاشات 2.0)؛ بديل JVM: أول إطار **١٠٢ مللي ثانية** (الوسيط بعد التحميل)، ٢١٨٩ مللي ثانية لأول مرة مع تحميل الأصناف. الهاتف لم يُقس | `perf.BaselineProfileTest`، `perf.StartupProxyTest` |
| أندرويد ٧٫٠ | Android 7.0 (API 24) pass | 1.7.0 بند 4 | منجز في Robolectric | كل ميزة تعمل أو تتراجع برسالة | ٩/٩ اختبارات تدفق على API 24 و26 و28 | `perf.OldAndroidFlowTest` |
| StrictMode | StrictMode in debug | 1.7.0 خطة الاختبار | منجز | في التطوير فقط | يُسجّل القرص/الشبكة على الخيط الرئيسي والتسريبات؛ لا شيء في الإصدار | `perf.PerfCoreTest.strictModeOnlyInDebug` |
| تحسين زمن OCR ٢٠٪ على الهاتف | OCR time per page −20 % on the phone | 1.7.0 قبول | غير منجز | ≥ ٢٠٪ أو تفسير | لم يُقس: لا هاتف هنا. ما تغيّر يقلل الحجز (٣٦٫٦ ← ٧٫٩ م.ب للصفحة) وجمع القمامة، لا حساب المحرك نفسه؛ الوضع المنخفض يقرأ ٢٠٠٠ بكسل (٣٠٪ بكسلات أقل) | فحص المالك |

---

# 2.0 features — Performance, memory and Android 7 (ROADMAP 1.7.0)

Branch `feat/2.0-perf`. Every number below was measured on this PC (JVM 21, Robolectric 4.16.1 native graphics, Windows 11)
on synthetic pages generated in test code. Nothing was measured on a phone.

## Thresholds (one place: `app/src/main/java/com/fastkiwi/scan/perf/MemoryBudget.kt`)

| Constant | Value | Meaning |
|---|---|---|
| `LOW_RAM_TOTAL_BYTES` | 3 GiB | total RAM below this → low-memory mode (also `isLowRamDevice`); unknown RAM (0) is not low. `ocr.DeviceProfile.LOW_RAM_BYTES` points here |
| `Mode` | AUTO / ON / OFF | Settings override «وضع الذاكرة المنخفضة» |
| `LOW_MEMORY_LONG_SIDE` | 2000 px | OCR plane and every exported page (colour and 1-bit) in low-memory mode |
| `SOURCE_SIDE_NORMAL` / `_SMALL` | 3200 / 2400 px | stored page sources; small when low-memory or `largeMemoryClass` < `SMALL_HEAP_MB` (256) |
| `MAX_DECODE_PIXELS` | 13 MP | largest sampled-decode intermediate (A8); `BitmapIO.MAX_DECODE_PIXELS` points here |
| `STRIP_ROWS` | 64 | rows per `getPixels` strip (OCR plane, 1-bit packing) |
| `OCR_IDLE_RELEASE_MS` | 60 000 | OCR engines (MNN / Tesseract native memory) released after this idle time |
| `BUDGET_20_PAGES_OCR` / `BUDGET_200_PAGES` | 256 / 128 MB | the acceptance budgets the heap tests assert |

Low-memory mode = OCR plane 2000 px; export `EncodeSettings` capped at 2000 px (target-size ladder included, so the size
estimate matches what is written); a single OCR worker: `AppViewModel` gives `DefaultOcrEngineFactory` a `DeviceProfile`
from the mode, so «دقيق» falls back to «سريع» (Tesseract, one thread) with the existing `LOW_RAM_DEVICE` reason, and
`OcrService` already reads one page at a time for export and background; page sources at 2400 px.

## How memory is measured (`perf/MemoryMeter.kt`)

Under Robolectric `Debug.getNativeHeapAllocatedSize()` returns the Java heap in use (ShadowDebug), and with native graphics
page bitmaps live in real native memory that no JVM counter sees. So the meter reports two deltas over the running app
(Robolectric, the app and the seeded document are loaded before it starts):
- **native peak**: process private memory (Windows commit charge; Linux `RssAnon`) minus what the JVM committed (heap +
  non-heap), sampled every 5 ms; peak minus a settled baseline. The test JVM runs with `-XX:MaxHeapFreeRatio=100` so G1's
  delayed uncommit cannot hide memory. A calibration test checks one 2400×3200 bitmap reads as 29.4 MB (exact: 29.3), and
  both budget tests assert the native peak is ≥ 25 MB (the page bitmap is seen, the pass is not vacuous).
- **Java live bound**: live heap after a full GC at each page start plus all bytes allocated since (all threads' counters):
  an upper bound of the live Java heap at any moment, garbage excluded.
Total = native peak + Java bound. `Debug.getNativeHeapAllocatedSize()` is still recorded (93.2 MB peak in the 20-page test,
which is Robolectric's whole Java heap, garbage included, not the app's native heap).

## Measured results

| Test | Result | Budget |
|---|---|---|
| 20 pages 2400×3200, OCR (fake engine reading the whole plane, 40 words/page), HIGH | **67.7 MB** = native 56.3 + Java 11.4; Java retained after 0.8 MB; 3.3 s | ≤ 256 MB |
| 200 pages, no OCR, BALANCED | **74.0 MB** = native 70.6 + Java 3.4; Java retained after 0.0 MB; 22 s incl. 202 GC checkpoints; 17 MB PDF | ≤ 128 MB |
| 500 pages, PDF + OCR, then JPEG export of all 500, JVM `maxHeapSize = 512m` | **no OutOfMemoryError**; PDF 44 s, JPEG 32 s | no OOM |
| 6 pages HIGH + OCR, normal vs low-memory | plane 2400 → **2000** px, largest PDF image 3200 → **2000** px, 66.4 → **58.7** MB | — |
| OCR input of one 2400×3200 page | allocated **36.6 MB** (1.2 path: ARGB `IntArray` + plane) → **7.9 MB** (plane 7.3 + one strip) | — |
| JPEG export, 3 pages SMALL | 37.7 MB peak; output 1700 px | — |
| Start to first Home frame, **PROXY** (Robolectric JVM) | first 2189 ms (class loading into a fresh sandbox); warm 117 / 102 / 84 ms, median **102 ms** | phone: ≤ 1.0 s (owner check) |

Why the native peak is ~55–70 MB: a 3200 px source is decoded whole (inSampleSize is a power of two, so a 2400 or 2000 px
target still decodes 3200 × 2400 = 29.3 MB), plus the scaled page (16.5 MB at 2400 px) and the JPEG encoder's buffers. New
captures in low-memory mode are stored at 2400 px, which lowers this for them. The decode bitmap is reused for every page.

## Cold start

The roadmap says the Baseline Profile "already" exists; the build had none (no `baseline-prof.txt`, no profile module).
Added `app/src/main/baseline-prof.txt` (hand-written rules: cold start path HSPL + class preload, every 2.0 screen, capture,
export, PDF and OCR code HSP). AGP compiles it into the release APK (`assets/dexopt/baseline.prof`), and
`androidx.profileinstaller` (already shipped through Compose) installs it on sideloaded installs. `BaselineProfileTest` fails
when a rule names a missing class or a `*Screen.kt` file has no rule. The startup number above is a JVM **proxy**: no zygote,
no ART/JIT, no profile, a desktop CPU; it only guards against big regressions before the first frame. The ≤ 1.0 s target is
the owner's phone check (cold start from the launcher, e.g. `adb shell am start -W`).

## Android 7.0 pass (`perf.OldAndroidFlowTest`, `@Config(sdk = [24, 26, 28])`, Arabic RTL)

Each of three flows runs on API 24, 26 and 28 (9 runs, all pass):
1. Screens: Home (search box, «مسح»), camera without permission (the explanation screen, no crash), editor, search through
   FTS4 with a query typed without hamza («فاتوره الكهرباء» finds the page) plus the search screen, page text screen,
   Settings (low-memory row; language row), PDF tools screen.
2. Export with every option: A4/Letter/Legal/A5/fit, SMALL/BALANCED/HIGH, OCR text layer (Arabic+English and Arabic),
   password (AES-256), PDF/A-2b, target size, page numbers (Arabic digits, with total) + watermark; JPEG export with page
   numbers; TXT export. Every PDF opens in PDFBox (with its password) with all pages.
3. PDF tools (`:pdf` service protocol): open a protected PDF with its password, merge (5 pages).

Degrades below newer APIs (by design, already in the code): wallpaper colours (Material You) need Android 12, so the row is
hidden on 7–11 (checked: absent on API 24); the launcher shortcut «مسح» (static app shortcut) needs Android 7.1 (API 25), so on 7.0 the app starts from its icon only (lint `UnusedAttribute`); per-app language uses the system setting on Android 13+ and the app's own
override below (`MainActivity.attachBaseContext`); `AtomicWrite` renames atomically (Linux `rename`), and its rare-failure fallback uses `java.nio.file` on Android 8+ and delete-and-rename
on Android 7. Lint (`lintDebug`): **0 `NewApi` and 0 `InlinedApi` issues** for minSdk 24.

What Robolectric **cannot** prove here:
- On API 24/25 Robolectric has no native graphics: bitmaps are `ShadowLegacyBitmap` objects, so decode, filters, detection
  and JPEG bytes are not real on those runs (the flow, files, PDF structure and text layer are). API 26/28 run real Skia.
- CameraX preview and capture never run (no camera); only the permission path is exercised.
- Tesseract and MNN native libraries do not load on the JVM; a fake engine stands behind the `OcrEngine` seam.
- The JVM is not Dalvik/ART, so the Android 7 delete-and-rename fallback of `AtomicWrite` is not taken; memory limits,
  `largeMemoryClass`, GC behaviour and speed of a real Android 7 phone are not modelled.
- ROADMAP 2.0.0: "Android 7: the owner's check on an Android 7/8 device if one is available; otherwise stated as
  'API 24 tested in Robolectric only'" — this is **API 24 tested in Robolectric only**.

## StrictMode

`perf/DebugStrictMode.install(BuildConfig.DEBUG)` first thing in `MainActivity.onCreate`: thread policy (disk reads/writes,
network, custom slow calls) and VM policy (leaked closeables, SQLite objects, activities, registrations), `penaltyLog` only
(`adb logcat -s StrictMode`). Never `penaltyDeath`: the debug build is what the owner tests, and a crash would lose a scan.
The release build passes `false` (R8 removes the call's body).

## Touchpoints (marked `// 2.0-perf:`)

`export/Exporter.kt` (OCR plane size and export settings from the budget, bitmap pool, strip packing, JPEG decode size,
estimate uses the capped settings), `imaging/BitmapIO.kt` (`Pool`, pooled `decodeFile`, `configureForBudget`,
`MAX_DECODE_PIXELS` from the budget), `ocr/OcrInputs.kt` (strip gray plane), `ocr/DefaultOcrEngineFactory.kt` (one line:
`LOW_RAM_BYTES` from the budget), `ui/AppViewModel.kt` (budget configured first, `DeviceProfile` from the mode, idle time
from the budget, OCR plane size for background and «اقرأ النص الآن», `setLowMemoryMode`), `ui/Settings.kt`
(`lowMemoryMode`), `ui/SettingsScreen.kt` (one «الأداء» group with the choice dialog), `MainActivity.kt` (StrictMode),
`app/build.gradle.kts` (test JVM arg, stress-test exclusion, `perfStressTest` task finalizing `testDebugUnitTest`).
New: `perf/MemoryBudget.kt`, `perf/PixelStrips.kt`, `perf/DebugStrictMode.kt`, `app/src/main/baseline-prof.txt`,
`strings_perf.xml` (ar/en, 8 strings).

For the camera agent (not edited here): `PixelStrips.gray(bitmap)` gives a gray plane without an ARGB copy, and
`MemoryBudget.lowMemory` / `MemoryBudget.MAX_DECODE_PIXELS` are the switches for tiling filters on > 12 MP images
(`ImageFilters`, `PageRenderer`, `BitmapIO.detectPage` still use full `getPixels` copies; tiling there is not done).

## Not done / partial

- **Cold start ≤ 1.0 s**: partial — the profile exists and is guarded, but the time is a JVM proxy; the phone number is the owner's.
- **OCR time −20 % on the phone**: not measured (no phone); the release notes must say so.
- **Filter tiling for > 12 MP** (1.7.0 item 1, second half): not done here — the filter and render code belongs to the camera
  agent working in parallel; sampled decode (13 MP cap) already keeps > 12 MP photos from allocating a full-size bitmap.
