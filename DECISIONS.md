# Scan Kiwi (ماسح كيوي) — decision log

Each decision below would normally go to the owner. It was settled by a short internal
"council" review (options weighed, one chosen, reason recorded), as instructed.

| # | Decision | Options weighed | Choice and why |
|---|---|---|---|
| D1 | **App name** | "Kiwi Scan" / "ماسح كيوي"; a new unrelated brand; a generic name | **Kiwi Scan — ماسح كيوي.** Fits the FastKiwi suite, is descriptive in both languages, and copies no competitor. Package id `com.fastkiwi.scan`. |
| D2 | **Icon** | Stock icon; competitor-style "camera + page"; our own mark | **Own vector mark**: white page with a folded corner, a kiwi-slice seal and scan brackets on kiwi green. Adaptive + monochrome (themed icon) variants. |
| D3 | **Edge detection engine** | OpenCV (≈10–30 MB native per ABI); ML Kit Document Scanner (needs Google Play services, Google's UI, model download through GMS); our own Kotlin CV | **Own pure-Kotlin detector** (Canny + edge components + convex hull + best-supported quad). Keeps the APK small, works without Google services, unit-testable on the JVM. Trade-off: weaker than a neural model on low-contrast scenes; manual crop is always available. |
| D4 | **Perspective warp** | OpenCV `warpPerspective`; own bilinear sampler | **`android.graphics.Matrix.setPolyToPoly`** (4-point homography, hardware-friendly Skia drawing). Zero extra size. |
| D5 | **OCR engine** | ML Kit Text Recognition v2 (no Arabic script; Google terms; sends usage metrics); Tesseract (Apache-2.0, has Arabic); none | **Tesseract 5 via Tesseract4Android 4.9.0 + `tessdata_fast` ara/eng.** Only free offline option with Arabic. Libraries are 16 KB-page aligned (checked). Honest UI note: Arabic accuracy is lower than English. |
| D6 | **PDF writer** | `android.graphics.pdf.PdfDocument` (re-encodes images losslessly → huge files, no text layer, no encryption); PdfBox-Android (+2–3 MB); own writer | **Own ISO 32000 writer**: embeds JPEG bytes as-is (DCTDecode), 1-bit Flate for B&W pages, invisible OCR text layer (glyphless font + ToUnicode, RTL handled), AES-256 (R6) encryption. Verified by unit tests with Apache PDFBox. |
| D7 | **Imported PDFs** | Lossless page copy (needs a full PDF parser); rasterise | **Rasterise at ~200 dpi with the platform `PdfRenderer`.** Simple, no library; pages then behave like scans (filters, reorder, merge). Original text layer is not kept — stated in RESEARCH.md. |
| D8 | **Capture flow** | Crop screen after every shot (Lens style); continuous batch (Adobe style) | **Continuous batch**: each capture is auto-cropped with the detected outline and added; the user reviews/adjusts crops afterwards. Faster for multi-page documents and matches auto-capture. |
| D9 | **Default UI language** | System default with English fallback; Arabic default | **Arabic is the default resource language** (`values/`), English in `values-en/`; in-app language switch (Android 13+ uses the system per-app language API). |
| D10 | **Default filter** | Original; Enhanced; Remove shadows | **Enhanced colour** (keeps colours natural, lifts shadows partly). Changeable in Settings. Imported images without a detected page outline default to Original. |
| D11 | **Target / min SDK** | target 35/36/37; min 21/24/26 | **targetSdk 36** (the Google Play requirement since 31 Aug 2026), **compileSdk 36, minSdk 24** as requested. |
| D12 | **Toolchain versions** | Newest AGP 9.x / Kotlin 2.4 / Gradle 9.x; latest stable 8.x line | **AGP 8.13.2, Gradle 8.14.3, Kotlin 2.2.21, Compose BOM 2025.12.01, CameraX 1.5.3** — proven, stable combination that supports API 36; avoids AGP 9 DSL migration risk. |
| D13 | **ABIs and packaging** | arm-only; universal with x86; per-ABI splits | **One APK with arm64-v8a, armeabi-v7a, x86_64** (phones + emulators/Chromebooks), native libs stored compressed (`useLegacyPackaging`) to keep the file small enough for chat. |
| D14 | **Network** | Keep INTERNET for nothing; remove | **No INTERNET permission**, and `tools:node="remove"` guards against any library adding it. |
| D15 | **Backups** | Allow Android auto-backup; disable | **Disabled** (`allowBackup=false`, data-extraction rules exclude everything): documents never go to a cloud backup without the user's explicit export. |
| D16 | **Reordering pages** | Drag-and-drop grid; menu actions | **Menu actions** (move earlier/later/first/last) on each page: reliable, and fully usable with TalkBack and switch access. Drag-and-drop is a later improvement. |
| D17 | **Signature stamp** | Build now; defer | **Deferred** to a later version: it needs a good placement/resize UI to be worth shipping. |
| D18 | **Signing key** | Debug key; new release key | **New RSA-4096 PKCS12 release key** in git-ignored `keystore/`, random password kept only in git-ignored `keystore/README-local.txt` and `keystore.properties`. |
| D19 | **PDF password characters** | Any Unicode; ASCII only | **Printable ASCII only.** PDF readers apply SASLprep to passwords; mixed Arabic/digit passwords break its bidi rule (found by our PDFBox test), so some readers could never open the file. The UI explains this. |
| D20 | **Testing without a device** | Emulator (needs Hyper-V/WHPX or the AEHD driver: system changes we may not make); skip UI testing; Robolectric | **Robolectric with native graphics** runs the real app UI and image pipeline on the JVM, makes the screenshots in `screenshots/`, and exports/decrypts real PDFs. Camera and Tesseract (native code) still need a phone. |

## v1.1 — owner feedback and council review (2026-11-14)

Owner feedback on 1.0.0: «تعديل على تحديد الصوره بعد الالتقاط» (adjusting the page selection after capture)
and «استشر المجلس حول الاضافات و التحسينات وطبقها».

### D21 — Crop after capture (owner priority)

Benchmarked against Adobe Scan, Genius Scan and Google's ML Kit document scanner. All three offer draggable corners
with a magnifier; Adobe and Genius add side handles; Google re-detects. We took the best of each:

- 72 dp grab area per handle (corners **and** side midpoints; a side moves parallel to itself).
- Magnifier (2.5x) floats **above** the finger (below it near the top edge), with a crosshair and the outline drawn inside.
- Snapping to the detected page corners and sides, with a haptic tick.
- Buttons: «الكل» (whole image), «إعادة الكشف» (back to the detected outline), 90° rotation inside the crop screen.
- Crossed or concave outlines show a red warning bar with «إصلاح»; Apply is disabled until it is valid (no crash path).
- After capture: a doubtful outline (low edge support) **always** opens the crop screen; a manual shot opens it when
  «مراجعة القص بعد كل لقطة» is on (default **on**, as the owner asked); auto-captured pages flow on as a batch.
- The last captured page is shown as a thumbnail in the camera; tap it to re-crop at once.
- «قص» stays in every page's menu; the original photo is always kept, so re-cropping is lossless.
- All 8 EXIF orientations (including mirrored ones) are baked into the stored source, so outlines stay correct for
  gallery imports. Geometry is unit-tested (handle hit-test, corner/edge drag, snapping, invalid shapes, EXIF).

### D22 — Council on v1.1 additions

Viewpoints: **U** everyday user, **P** privacy/security, **A** accessibility/Arabic, **S** performance/size,
**C** competitor parity. Budget: about one day after the crop work.

| Candidate | Verdict | Reason |
|---|---|---|
| Signature stamp | **Done** | U+C: signing forms is a top reason to scan; Adobe/CamScanner charge for it. P: signature PNG stays in app-private storage, never exported alone. Draw once, drag to place, resize; burned in after filters. |
| Batch auto-capture | **Done** | U: multi-page scanning without a tap per page. Auto-captured pages skip review unless the outline is doubtful. |
| Book mode (split facing pages) | **Not now** | S+U: needs spine detection and page de-warping to be good; a naive half-split disappoints. Revisit with a better model. |
| Page count + size before sharing | **Done** | U: avoids "file too big for WhatsApp" surprises; estimate from up to 3 encoded sample pages. |
| Default names from date + first OCR words | **Done (as a suggestion)** | U wants meaningful names; P/A: renaming silently would surprise users, so the rename dialog offers the OCR words as a one-tap suggestion; the date-based default stays. |
| Home-screen quick-scan shortcut | **Done** | U: one tap to the camera; static shortcut, no permissions. |
| Dark theme | **Already in 1.0** | Follows the system; checked again. |
| Send as images | **Already in 1.0** | JPG export / share several images. |
| A5 / Legal page size | **Done** | C+U: cheap and common (Legal in the Gulf/US forms, A5 receipts and booklets). |
| Better Arabic OCR | **Partly** | A wants it most; S objects to `tessdata_best` (+12.6 MB APK, ~3x slower OCR). We added shadow/illumination normalisation before OCR, which helps Tesseract on phone photos, and kept the fast model. Real-phone measurements decide the next step. |
| Undo in the editor | **Done** | U: deletes and filter changes are reversible (20 steps; undo button + "Undo" on the delete snackbar). P: deleted page files are only removed at the next app start. |
| TalkBack pass | **Done** | A: page tiles and document rows expose their menu actions as accessibility actions; section titles are headings; warning bar is a live region; rotation icons no longer mirror in RTL (rotation direction is physical). |

### D23 — Version and signing

**1.1.0 (versionCode 2)**, signed with the same release key as 1.0.0 so it installs over it and keeps the user's documents.

## v1.1.1 — independent bug hunt (2026-11-14)

### D24 — Fix-only release

Bugs found by an adversarial review and each proven by a test (the bug cases fail on 1.1.0). No features added.
**1.1.1 (versionCode 3)**, same release key. Choices made on the way:

- **Undo after adding pages**: adding pages (camera, import) became an undo step, rather than clearing the history.
  Either fixes the data loss; this one keeps earlier undo steps and makes "Undo" mean "undo the last change".
- **Serialising edits**: one lock for every document read-modify-write, held during the page re-render. Simpler and
  safer than merging concurrent changes; heavy work for captures and imports stays outside the lock.
- **OCR unavailable**: export continues without a text layer and says so, rather than failing every export while
  the remembered OCR switch is on.
- **Not fixed (S3, would change behaviour)**: a signature placed before a later rotation or re-crop keeps its page
  position instead of following the content; PDFs from providers that only offer a pipe (not seekable) cannot be
  imported; the crop screen copies its preview bitmap on the main thread when rotating.

## 1.2.0 — rename, open source, standards (2026-11-14)

### D25 — Rename
English name **Scan Kiwi** (owner: «اسمه scan kiwi»); Arabic name «ماسح كيوي» unchanged. applicationId
`com.fastkiwi.scan` and the release key stay, so 1.2.0 installs over 1.1.1. Internal identifiers that users never
see (`Theme.KiwiScan` → renamed anyway to `Theme.ScanKiwi`, folder `kiwi-scan`) do not matter.

### D26 — Council on 1.2.0 improvements (quality over feature count)
Viewpoints: everyday user (U), privacy/security (P), accessibility/Arabic (A), performance/size (S), competitor parity (C).

| Candidate | Verdict | Reason |
|---|---|---|
| S3: signature does not follow re-crop/rotation | **Done** | U: a signature must stay on the signature line. Stamps are mapped back to the photo through the old crop (homography) and forward through the new one; width kept along the baseline. Unit-tested. |
| S3: pipe-only PDF providers | **Done** | U: cloud/mail apps often hand out pipes. Non-seekable descriptors are copied to a private temp file, deleted right after. |
| S3: crop preview copy on the main thread | **Done** | S: rotated preview built on `Dispatchers.Default`. |
| Book mode | **Not now** | U+S: a good result needs spine detection, per-page crop **and** page de-warping; without de-warping the text near the spine stays curved. "Properly or not at all" → not in 1.2.0. |
| Better Arabic OCR | **Partly** | A wants `tessdata_best` (better Arabic); S rejects it: +12.6 MB (APK ≈ 26 MB, at the 28 MB chat limit) and ~3x slower OCR on phones. A downloadable model is impossible (no INTERNET permission). Kept `tessdata_fast` + shadow normalisation before OCR. |
| Edge detection on hard backgrounds | **Done** | U: white paper on a light wood/coloured desk. Added a "paper-likeness" channel (bright and colourless) tried when luminance is doubtful, for stills and live frames. Test with a same-brightness coloured desk. |
| Auto-capture stability | **Done** | U: 1.1 could re-capture the same page after its 2 s cooldown. New time-based gate: holds 0.9 s within 2 %, tolerates 4 missed frames, fires only on confident outlines, re-arms only when the page leaves or changes. Countdown ring on the shutter. Unit-tested. |
| Speed and memory on low-end phones | **Done** | S: page sources 2400 px on low-RAM/small-heap phones (3200 otherwise); OCR reads ≤ 2400 px; export decodes only as large as needed; size estimate no longer decodes images. |
| Code-quality refactors | **Limited** | New logic lives in small pure-Kotlin, unit-tested classes (`Homography`, `StampMapper`, `AutoCapture`, `Feedback`). Large screen refactors wait for the design spec to avoid churn twice. SPDX headers everywhere; README "Project layout". |
| From RESEARCH.md: archival PDF | **Done** | C+U: PDF/A-2b «أرشيفي» option, validated by veraPDF in tests. |

### D27 — Share lag («عند الضغط على زر مشاركة كانه يتاخر او يعلق»)
Findings on 1.1.1: nothing heavy ran on the main thread, but (1) the export gave no feedback until the first page
was processed and had no Cancel; (2) the size estimate encoded three full-size pages **in parallel with** the export
(CPU and ~100 MB of extra bitmaps on a phone); (3) OCR read 3200 px pages (slow per page with `ara+eng`);
(4) the same export was rebuilt every time Share was tapped again. Fix: progress shown on tap («يجهّز الملف… صفحة ٣ من ١٠»,
«يقرأ النص…» during OCR), a real Cancel (coroutine cancellation + `TessBaseAPI.stop()`, partial file deleted),
OCR cache reused (keyed by page revision, which changes with filter/crop/rotation), identical exports reused,
image-free size estimate, OCR at ≤ 2400 px. Robolectric timings (PC, 2400×3200 pages, no OCR):
before 1 / 5 / 20 pages = 150 / 368 / 1439 ms; after = 226 / 377 / 1171 ms; main thread busy after tap 0 ms in both;
cached re-share of 5 pages 62 ms; size estimate 39 ms. The on-phone gain comes mostly from the estimate no longer
competing with the export, OCR on smaller images and reuse; OCR could not be timed on the PC (native Tesseract).

### D28 — Report a problem or suggestion
Hand-off only (no network permission): `mailto:` (with subject/body extras) or a prefilled GitHub issue form
(`bug_report.yml` / `feature_request.yml`, fields `description` and `device`, URL ≤ 7000 chars, cut on character
boundaries). Device info is off by default and shown verbatim before sending; it has app version, Android version,
model, language and, from an error, only an error code and exception class. Never documents, images, OCR text,
file names, the signature or logs. Errors offer «أبلغ عن هذه المشكلة» on the message.

### D29 — Updates (council D-077)
No network code, no new permissions. Settings ▸ «التحديثات»: version line, «حدّثه تلقائياً عبر Obtainium»
(`obtainium://add/...`, dialog with «نزّل Obtainium» when not installed), «ابحث عن تحديث» (releases page).
Required wording used verbatim in the README, the privacy text and About. CI fails if the APK requests any
permission except CAMERA (the app-internal `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` from androidx.core is
a signature permission of this app, not a system permission, and is allowed).

### D30 — Standards
See `STANDARDS.md`. Notable choices: PDF/A-2b was feasible without bloat (456-byte CC0 sRGB profile, uncompressed
XMP) and is validated with veraPDF; Gradle dependency verification enabled (Linux/macOS aapt2 hashes added by hand
so CI can verify); CycloneDX SBOM from the build; unsigned SLSA L1 provenance because signing is local;
MASVS-RESILIENCE deliberately not pursued (open source, no secrets).

### D31 — Professional redesign («تصميم ... اكثر احترافيه») in 1.2.0
The design council's spec (FastKiwi design council, 2026-11-14) was implemented in 1.2.0 instead of 1.3.0 on the
coordinator's decision (one writer, so no merge risk), as 15 commits, one per spec step, with the full test suite
green after each. Summary: complete Material 3 colour scheme from the kiwi seed; bundled Noto Sans Arabic;
Arabic-tuned type scale; one primary action per screen in the thumb zone; paired buttons secondary-first,
primary at the end; centred scan FAB; card-less page grid with «إضافة صفحات» / «تصدير»; swipe pager page
editor with a labelled toolbar; crop with a full-width «تم»; export sheet with segmented choices and a size
dropdown; grouped Settings with dialogs, theme choice and opt-in dynamic colour; Updates as its own screen;
camera with a centred shutter and a «تم» pill; Material Symbols Rounded icons; locale digits and the Arabic comma.

Deviations from the spec, with reasons:
- **Release**: 1.2.0, not 1.3.0 (coordinator decision).
- **Font build**: static unhinted Noto Sans Arabic 2.013 from the notofonts release (OFL). It has no Latin
  glyphs, so Latin text falls back to the system sans (Roboto/Noto Sans), which is visually close. The Google
  Fonts variable build with Latin would need variable-font support (API 26+) while minSdk is 24.
- **Not built**: shared-axis screen transitions, the thumbnail "flight" into the «تم» pill, swipe-to-switch
  camera mode, and the optional Home sort menu. The capture flash, confirm haptic and auto-capture ring are in.
  These are motion polish; left out to keep the release stable.
- **FAB collapse**: collapses once the first list row scrolls away (not on scroll direction); same effect, simpler.
- **Updates hero**: shows version and versionCode but no release date (a build date would make builds
  non-reproducible). The full D-077 network statement is shown on the screen, word for word, instead of behind «لماذا؟».
- **Fill 1 icons**: only `check_circle` (selection); other selected states use colour.
- **Rename**: «الكل» in the crop tools is now «الصورة كاملة» as the spec asks.

### D32 — Independent QC and bug hunt before release (2026-11-14)
An agent that did not write 1.2.0 checked it against the design spec, D-076/D-077 and the standards, and hunted
bugs in everything new since 1.1.1. Each finding has a test in `Qc120Test` that failed on 77ad6d6.
- **Fixed (S2)**: the export sheet's «مشاركة» / «حفظ في الجهاز» were inside the scrolling options, below the fold
  on a normal phone (spec §3: the primary action is always in the thumb zone). The footer is now fixed under
  the options. OCR words cached for one language were reused for another, so switching to «عربي + إنجليزي»
  kept an English-only text layer; the cache now records its language (1.1.x caches stay searchable and are
  redone on the next export with OCR).
- **Fixed (S3, cheap)**: PDFs with no MIME type and no ".pdf" in the URI are recognised by their header; a paste
  over the 4000-character report limit is cut instead of ignored; the GitHub-URL shortening loop always ends;
  a dropped camera frame no longer shows «اثبت قليلًا…» without a countdown; libyuv's BSD-3-Clause notice
  added; the SBOM lists the Noto Sans Arabic fonts and Material Symbols and corrects two licence entries.
- **Not fixed (S3, recorded)**: cancelling or failing a «حفظ في الجهاز» export leaves the empty file that the
  system "Save as" dialog already created; sharing JPGs removes the cached PDF, so the next PDF share is
  rebuilt; a re-share after OCR was unavailable reuses the file without a text layer; the cosmetic design
  differences listed in the QC report.
- **Release build**: the first 1.2.0 APK was built from 92f1c43 and never left the PC. Because code changed,
  and so that the provenance names the commit the v1.2.0 tag points at, the APK, SBOM, provenance and
  checksums are rebuilt from the final commit. Version stays 1.2.0 (versionCode 4).


### D33 — Two OCR export defects found by the Scan Kiwi lead before launch (2026-11-14)
Found by reading the 1.2.0 tree while designing the 1.3.0 `OcrEngine` interface; not in the QC findings. Both
fixed in 1.2.0, each with a test that failed on the old code (`OcrExportTest`).
- **S2 — text layer misplaced on cache reuse.** Fresh OCR read the page at `max(export size, 2400)` and cached
  the boxes in that frame without recording it; a later export from the cache decoded the page at the export
  size and drew the boxes unscaled. Reusing a cache at another quality (or at "Small", where a fresh OCR reads
  2400 px but the page is drawn at 1700 px) put words up to 1.41x off, with the right and bottom third outside
  the page. **Fix:** the cache records its frame (`"frame": [w, h]`) and an export scales the boxes into the
  bitmap it draws, exactly. Caches without a frame (1.1.x, early 1.2.0 builds) are not trusted for placement:
  an export reads such a page again (once); search still uses their text. Rejected: "always decode at the OCR
  size" — it still mismatches when the first OCR ran at High (3200 px) and the reuse at Balanced.
  Test: every first-OCR quality x every reuse quality, colour and black-and-white pages, PDFBox checks each
  word's left edge and baseline within 1 % of where the engine saw it, and inside the page.
- **S3 — Cancel did not reach Tesseract.** `Job.invokeOnCompletion` runs only after the job has completed, which
  cannot happen while the export is blocked in `getUTF8Text()`, and the handler was disposed in `finally`
  before that. So `TessBaseAPI.stop()` was never called and Cancel waited for the page (D27 claimed otherwise).
  **Fix:** `stopOnCancel` runs the blocking call with a watcher coroutine that calls `stop()` from a pool
  thread as soon as the export is cancelled. Test: a fake engine that blocks until `stop()`; Cancel must call
  it within 2 s, end the export, cache nothing and leave no PDF. Whether Tesseract4Android's `stop()` cuts a
  page short is a phone check (the cancel monitor is documented for hOCR); if it does not, the page still
  finishes but the export ends right after it, as before.
- A small `PageOcr` interface (recognize/stop/close) lets tests inject a fake engine; it is the first step of
  the 1.3.0 `OcrEngine` interface. Word boxes are now rounded instead of truncated when scaled.
- Rebuilt the release APK, SBOM, provenance and checksums from the new final commit; version stays 1.2.0 (4).
