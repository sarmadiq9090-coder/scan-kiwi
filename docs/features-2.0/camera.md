<!-- SPDX-FileCopyrightText: 2026 FastKiwi -->
<!-- SPDX-License-Identifier: Apache-2.0 -->
# الكاميرا وجودة الصورة ووضع الكتاب واللمعان — Scan Kiwi 2.0.0 (Camera area)

الفرع: `feat/2.0-cam`. كل الأرقام أدناه مقاسة على الحاسوب (JVM) بصور اصطناعية تُولَّد في كود الاختبار من بذور
ثابتة؛ لا صورة حقيقية ولا صورة محفوظة في المستودع. أرقام الهاتف تأتي من قائمة فحص المالك في آخر الملف.

All numbers are JVM measurements on synthetic images generated in test code from fixed seeds (no real
document, no committed image). Timings were taken while three other agents were building on the same PC.

## الخلاصة / Summary

| # | الميزة | Feature | ID | الحالة / Status | القبول / Criterion | النتيجة / Result | الاختبار / Test |
|---|---|---|---|---|---|---|---|
| 1 | مجموعة مشاهد اصطناعية وخط أساس 1.2.0 | Synthetic scene set + 1.2.0 baseline | ROADMAP 1.3.0 | done | ≥ 60 pages, 6 backgrounds, 3 lights, 4 tilts, 20 empty; baseline first | 72 pages + 20 empty. **1.2.0: 69.4% within 3%, 5.0% false pages, ~8–11 ms/frame** | `synth/EdgeBenchTest.baseline120` |
| 2 | كشف الحواف في الإضاءة الخافتة والتباين المنخفض | Low light / low contrast detection | CAM-01/02 | done | ≥ 90% within 3% of the page diagonal; false pages ≤ 5%; ≤ 25 ms/frame | **91.7% (66/72); 0.0% (0/20); 18–22 ms** per 240×320 frame, both channels | `synth/EdgeBenchTest.acceptance20` |
| 3 | الوسيط الزمني لخمسة إطارات | 5-frame corner median | CAM-01/02 | done | one bad frame neither moves the outline nor restarts the countdown | proof test fails on the 1.2.0 config | `core/AutoCapture20Test.medianIgnoresSingleOutlierFrames` |
| 4 | كشف الاهتزاز وعدم الوضوح | Blur and shake | CAM-03 | done | AUC ≥ 0.95, σ ≤ 0.8 vs σ ≥ 2.0, 40 pages | **AUC 0.996** (320 px live frame, 40 pages × 7 σ; config threshold 99.2% balanced accuracy); **AUC 0.954** on 960 px stills (20 pages) | `synth/QualityBenchTest.blurAucOnLiveFrames`, `blurAucOnStills`, `AutoCapture20Test.waitsForSharpFrame`, `shakingPhoneDoesNotFire` |
| 5 | الصفحة غير مكتملة | Incomplete page | CAM-04 | done | ≥ 95% on 40 crops (20 cut, 20 whole) | **38/40 = 95.0%** (2 whole pages at 30° tilt reported as cut) | `synth/QualityBenchTest.incompletePagesOn40Crops`, `AutoCapture20Test.completenessRules` |
| 6 | تقرير الجودة عند الالتقاط | Capture quality report | PRD §4.3 | done | sharpness, completeness, lighting, glare (> 1.5% clipped), skew; never blocks | stored in `ScanPage.quality`; glare spot 4.3% flagged, clean page 0 warnings; dim scene lighting 0.46 (< 0.5 = warning); a 3° turned outline measures 2.75° skew | `QualityBenchTest.glareLightingAndSkewMeasures`, `CameraUi20Test.captureReportFlagsGlareOnAStill` |
| 7 | مؤشر الجودة وعدّاد الصفحات في الكاميرا | Camera quality pill + page counter | PRD §7.2 | done | one pill at a time (incomplete > blur > glare > dim); TalkBack rate-limited | live measures on the 320 px frame only; announcement at most every 3 s; the «تم» pill keeps the count (1.2.0) | `CameraUi20Test.bookModeSplitsInReadingOrder` (camera renders), code review |
| 8 | شريحة التنبيه في محرر الصفحة وشارة في الشبكة | Page warning chip + tile badge | PRD §4.3 | done | retake / re-crop / dismiss; 48 dp; dismissal stored without re-render | dismiss keeps the revision (OCR caches valid) | `CameraUi20Test.pageChipOffersRetakeRecropDismissAndNeverBlocks` |
| 9 | إعادة القص والفلتر من الأصل | Re-crop / re-filter from the kept original | ROADMAP 1.3.0 #6 | done (was true; now tested) | no new photo, no quality loss | original file hash unchanged after crop → filter → full crop; full width restored ±2 px | `CameraUi20Test.recropAndRefilterUseTheKeptOriginal` |
| 10 | الخلفيات المعقدة | Complex backgrounds | CAM-05 | done | tuned on the set | cluttered desk 6/12 → **12/12**, light wood 9/12 → 12/12, carpet 11/12 → 12/12 | `EdgeBenchTest.acceptance20` (per group) |
| 11 | كشف أفضل في التصوير المتتابع | Better batch detection | CAM-06 | partial | tuned on the set | no false page on 20 empty scenes (1.2.0: 1), median + motion gate stop double/blurred auto shots; no separate multi-page batch benchmark | `EdgeBenchTest`, `AutoCapture20Test` |
| 12 | وضع الكتاب | Book mode (spine split) | A4 / 1.8.0 | done | spine within 2% of page width on ≥ 90% of 40 spreads; order 100% | **40/40 (max error 0.7% of a page width)**; order from the setting 40/40; direction guessed from the layout 40/40 | `synth/BookBenchTest`, `CameraUi20Test.bookModeSplitsInReadingOrder` |
| 13 | تسوية الصفحات المنحنية | Curved-page dewarping | A4 | not done (by decision) | — | needs a neural model (A4, P2 after 2.0); the split screen says so | — |
| 14 | دمج لقطتين لإزالة اللمعان | Two-shot glare fusion | A4 / 1.8.0 | done | OCR-proxy error of fused ≤ better single shot on ≥ 90% of 30 pairs; text SSIM ≥ 0.9 | **29/30 = 96.7%**; mean glyph error 0.065 vs 0.099; **SSIM mean 0.936, min 0.904**; 29/30 aligned by features, 1 refused (first photo kept) | `synth/GlareBenchTest` |
| 15 | المعالجة عالية الدقة ضمن الذاكرة | High-resolution processing | PRD §4.2 / A8 | partial | tiles or sampled decode above 12 MP | sampled decode: the decoded bitmap never exceeds 13 MP (50/200 MP tested); glare fusion works at ≤ 2400 px; tiles not implemented | `CameraUi20Test.highResolutionDecodeStaysWithinBudget` |
| 16 | تقليل الضجيج | Noise reduction | PRD §4.2 | not done | — | a denoise step changes every page's output (and the OCR/PDF parity fixtures); the dim-light warning tells the user instead. Denoise variants were tried in the detector and did not help (see "Remaining misses") | — |
| 17 | تحسين التباين | Contrast enhancement | PRD §4.2 | done (existing + detector) | — | «ألوان محسّنة» auto-levels (1.2.0); 2.0 adds a percentile stretch inside the detector | `ImageFiltersTest`, `EdgeBenchTest` |
| 18 | الحفاظ على الألوان الأصلية | Original colour preservation | PRD §4.2 | done (existing) | — | «الأصلي» filter + the untouched source JPEG | `CameraUi20Test.recropAndRefilterUseTheKeptOriginal` |
| 19 | المحاذاة التلقائية | Automatic alignment | PRD §4.2 | partial | — | perspective correction (1.2.0) + residual skew measured and warned (> 2°); no automatic deskew rotation | `QualityBenchTest.glareLightingAndSkewMeasures` |

## How it works (short)

- **`core/DetectionConfig`** holds every threshold (detector, auto-capture, quality). `LEGACY_1_2` reproduces the
  1.2.0 detector so the baseline stays measurable.
- **Detector (`core/QuadDetector`)**: grey closing removes thin text; percentile stretch when p2–p98 < 160;
  both channels (luminance, paper-likeness) are scored and the best outline wins; every side must show the same
  inside/outside contrast sign (kills texture); a gradient-directed Hough transform proposes quads from four
  strong lines (shadows, clutter or text touching the outline no longer merge into it); pages running off the
  frame come back as **cut** outlines (frame border as a virtual line, corners outside allowed) with extra
  paper-likeness checks so empty scenes stay clean.
- **Remaining misses (6/72)**: white page on a white table in a dim room (4/4: paper only 6 grey levels above the
  table under σ = 7 noise) and 2 side-lit white-table scenes. Tried and dropped: box/median denoise, flat-field,
  tiled stretch (no gain or slower).
- **Quality (`core/QualityMetrics`)**: Laplacian variance minus the noise's own share (20σ²), divided by the
  page contrast (p1–p99 less the noise spread); live on the 320 px frame, report on a 960 px copy of the still.
  Blur thresholds sit just above the noise floor (0.0003 live, 0.00015 still) — to be checked on the phone.
- **Book mode (`core/BookSplit`, `ui/BookSplitScreen`)**: spine = gutter valley inside a text-free band, found
  separately in the top and bottom halves (slanted spines). Each half is a normal page (own outline, own
  perspective correction, re-crop works); each keeps a copy of the spread photo (storage ×2 for book pages).
  The order is saved per document in `book_order.txt` in the document folder; first spread: the layout guess,
  else the app language.
- **Glare (`core/GlareFusion`)**: page corners give a first homography, refined with Shi–Tomasi corners matched
  by NCC and a normalised DLT with outlier rejection; exposure-matched; per tile the darker (less reflected)
  photo wins, feathered. No feature agreement = no fusion (the second photo becomes a new page). The fused photo
  gets a new page id, so Undo brings the first photo back. Single-shot use only warns.
- **OCR proxy for glare**: glyph error = (lost + false ink pixels) / truth ink pixels inside the text block after
  binarising each rectified page at 60% of its local paper level. Measured after rectifying with the true
  outlines, so it sees fusion errors (ghosting) rather than corner-detection error; fusion itself uses detected corners.

## Touchpoints edited (marked `// 2.0-cam:`)

`ui/AppViewModel.kt`: `Screen.Camera.book`; `addCapture` (quality report, book spread, armed glare second shot);
a block with `bookPending`, `glareOffer`, `glareSecond`, `confirmBookSplit`, `cancelBookSplit`, `fuseGlare`,
`dismissQuality`; `onCleared`. `ui/EditorScreen.kt`: one `QualityBadge` line on the page tile.

## Size

Code and strings only (no assets, no dependency). Not measured on a release APK in this branch; estimated well under 0.1 MB.

## قائمة فحص المالك على الهاتف (الكاميرا) — Owner phone checklist (10–15 min)

1. ورقة بيضاء على طاولة بيضاء: هل يرسم التطبيق حدود الورقة؟
2. غرفة خافتة الإضاءة: هل يجد الورقة؟ هل يظهر «الإضاءة خافتة» فقط حين تكون خافتة فعلًا؟
3. حرّك يدك عمدًا: هل ينتظر التصوير التلقائي حتى تثبت (يظهر «الصورة غير واضحة، ثبّت الهاتف» أو «ثبّت الهاتف قليلًا»)؟
4. ورقة مقطوعة عند حافة الشاشة: هل تظهر «الصفحة غير مكتملة»، ويعمل زر التصوير اليدوي رغم ذلك؟
5. ورقة لامعة تحت مصباح: هل يظهر «لمعان على الصفحة» مع «لقطة ثانية»؟ صوّرها مرة ثانية من زاوية أخرى: هل خفّ اللمعان؟ ثم «تراجع»: هل تعود الصورة الأولى؟
6. افتح صفحة قديمة وأعد قصّها ثم غيّر الفلتر: هل تعود الصورة الأصلية كاملة بلا تصوير جديد؟ وهل تختفي شريحة التنبيه بعد «تجاهل»؟
7. كتاب مفتوح (كتابك أنت): اختر «كتاب»، صوّر صفحتين متقابلتين: هل يقع خط الكعب في مكانه، ويمكن سحبه؟ هل تأتي الصفحة اليمنى أولًا لكتاب عربي؟ (لا نعد بتسوية الانحناء قرب الكعب.)
8. TalkBack في الكاميرا: هل تُقرأ التنبيهات دون تكرار مزعج (مرة كل 3 ثوانٍ على الأكثر)؟
