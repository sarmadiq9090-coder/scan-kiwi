# Scan Kiwi v1.2.0 — feature checklist

Legend: [x] done · [~] done with a stated limit · [ ] not done

## v1 must-haves

- [x] Camera capture (CameraX) with **live edge detection** overlay
- [x] **Auto-capture** when the page outline is steady (~12 frames), with on/off toggle; manual shutter too
- [x] **Manual corner adjustment** — rebuilt in 1.1: corner + side-midpoint handles (72 dp grab area), magnifier above the finger, snapping to detected edges, «الكل», «إعادة الكشف», 90° rotation, invalid-shape warning with «إصلاح»; opens automatically after capture when the outline is doubtful (and after each manual shot if «مراجعة القص» is on)
- [x] **Perspective correction and auto-crop** (detected outline applied on capture; corner refinement by line fitting)
- [x] Filters: **Original, Enhanced colour, Grayscale, Black & white, Remove shadows (whitening)**
- [x] Multi-page documents: **reorder** (earlier/later/first/last), **rotate** (left/right), **delete**, **retake**, **add pages from gallery** (system photo picker)
- [x] **Import images and existing PDFs**, and **merge** documents (multi-select on the home list → Merge)
- [~] Imported PDF pages are rasterised at ~200 dpi (their original text layer is not kept)
- [x] **PDF export** with page size **A4 / Letter / Fit to image** and quality **Small / Balanced / High**
- [x] Small-file output: JPEG passthrough (no re-encode inside the PDF) and **1-bit pages for Black & white**
- [x] **Share** through the Android share sheet (FileProvider, read-only grant)
- [x] **Save** through the Storage Access Framework ("Save as" dialog; folder picker for JPGs)
- [x] **Document list** with **rename** and **search** (by name, and by recognised text after OCR)
- [~] **On-device OCR → searchable PDF** (Tesseract, offline): **English good; Arabic supported but lower accuracy**, stated in the UI

## Extras

- [x] **ID-card mode**: front + back placed at true ID-1 size on one A4 page (300 dpi)
- [x] **Password-protected PDF**: AES-256 (PDF standard security handler R6). Password limited to Latin letters/digits/symbols for reader compatibility
- [x] **Export as JPG** (share several, or save into a chosen folder)
- [x] **Signature stamp** (1.1): draw once, drag and resize on any page; stored only in app-private storage
- [x] Receive "Share to Scan Kiwi" from other apps (images and PDFs become a new document)

## New in 1.1

- [x] Crop editor rebuilt (see above); last captured page shown in the camera, tap to re-crop
- [x] Batch auto-capture: auto-captured pages skip review unless the outline is doubtful
- [x] Undo (20 steps) for delete, reorder, rotate, filter, crop, retake; "Undo" on the delete message
- [x] Page count and estimated file size shown before sharing
- [x] A5 and Legal page sizes
- [x] Name suggestion from the first OCR words in the rename dialog
- [x] Home-screen "Quick scan" shortcut (long-press the icon)
- [x] TalkBack: page and document actions as accessibility actions, headings, live warning bar
- [~] Arabic OCR: shadow/illumination normalisation before Tesseract (model unchanged; not measured on a phone)
- [x] EXIF: all 8 orientations (including mirrored) handled for imported photos

## New in 1.2.0

- [x] English name **Scan Kiwi** (Arabic «ماسح كيوي» unchanged); open source under Apache-2.0
- [x] Professional redesign per the design council's spec (DECISIONS.md D31), light and dark, Noto Sans Arabic, Material Symbols
- [x] «أبلغنا عن مشكلة أو اقتراح» by mail or GitHub issue (hand-off only; device info opt-in; never documents)
- [x] «التحديثات»: Obtainium link and releases page (no network code, no new permissions)
- [x] Share: instant progress, real Cancel (stops OCR), identical export reused, cheap size estimate
- [x] «أرشيفي (PDF/A)»: PDF/A-2b validated with veraPDF
- [x] Signature stays on the paper after re-crop/rotation; pipe-only PDF providers import; crop preview off the main thread
- [x] Steadier auto capture (time based, never the same page twice) and better detection on light coloured desks
- [x] Low-memory phones: 2400 px page sources; OCR on ≤ 2400 px images
- [ ] Book mode (split facing pages) — not now (D26)
- [~] Better Arabic OCR — preprocessing only; `tessdata_best` not bundled (size/speed, D26)

## Standards and privacy

- [x] Material 3 (Compose), light and dark themes, WCAG AA colour pairs
- [x] TalkBack labels on all icon buttons, page tiles and the shutter; live-region camera status; 48 dp touch targets
- [x] Full RTL; Arabic default, English included; in-app language switch (system per-app language on Android 13+)
- [x] **Only permission: CAMERA.** No storage permission. **No INTERNET permission** (stripped)
- [x] No trackers, analytics, ads or accounts; cloud backup and device transfer of documents disabled
- [x] targetSdk 36, compileSdk 36, minSdk 24
- [x] Native libraries 16 KB page aligned (Android 15+ devices)
- [x] Original name and icon; licences recorded (THIRD_PARTY.md) and viewable in the app

## Not done / known limits

- [ ] Book mode (split two facing pages) — deferred by the council (D22)
- [ ] Drag-and-drop reordering (menu actions instead)
- [ ] Tested on a real phone or emulator by me — the build PC has no hypervisor; the owner tested 1.0.0 on his phone
- [~] Edge detection is classical CV: may miss white paper on a white surface; manual crop always works

## Fixed in 1.1.1 (independent bug hunt, no new features)

- Parallel edits no longer overwrite each other (a capture while a crop was being applied, an import while
  deleting, two quick rotations): every document change is serialised.
- Adding pages (camera, import) is now an undo step; Undo used to drop pages added since the last edit, and
  it no longer reverts a rename.
- Start-up clean-up no longer deletes pages that a "Share to Scan Kiwi" import is still writing.
- A side dragged past the opposite side (mirrored outline) is flagged with «إصلاح» instead of giving a mirrored page.
- PDF export streams page by page (200-page documents on 2–3 GB phones); missing OCR engine/model exports
  without a text layer instead of failing or crashing.
- Shared-in file:// and own-provider URIs are refused (they could pull the private signature into a document);
  unreadable imports are skipped and never leave an empty document.
- Atomic, synced writes for doc.json, page images and OCR cache; a failed JPEG write no longer replaces the page.
- Release builds log nothing; exports are removed from the cache after "Save" and after an hour otherwise.

## What was tested (88 automated tests, all passing)

| Area | How | Result |
|---|---|---|
| Edge detection | Synthetic tilted/perspective pages, textured desk, pure noise (JVM) | Corners within 2–2.5%; no false page in noise |
| Filters | Shadowed synthetic page: whiten, B&W, grayscale, all filters (JVM) | Shadow removed, text kept, B&W strictly 1-bit |
| PDF writer | Read back with Apache PDFBox: A4/Letter/Fit sizes, landscape pages, Arabic title, searchable English + Arabic text layer, AES-256 open/deny, image bytes intact | Pass |
| Crop geometry (1.1) | JVM: handle hit-test, corner and side drag with clamping, snapping to detected corners/sides, crossed/concave/tiny/degenerate outlines and Fix, view rotation round-trip, all 8 EXIF orientations against a pixel reference | Pass (11 tests) |
| App UI | Robolectric + native Skia: home, pages, page filters, crop, a real drag that crosses the outline (warning shown, Fix works, no crash), signature placement, export sheet with size estimate, settings, camera-permission screen, English home | Pass, screenshots in `screenshots/` |
| Undo (1.1) | Robolectric: delete page + filter change, undo twice, page order and files restored; signature stamp saved and rendered | Pass |
| Image pipeline | Robolectric: detect on synthetic photos → perspective warp → filters → thumbnails → 3-page PDF export (79.8 KB with "Small") and password export | Pass |
| Release APK | apksigner verify (v2), aapt2 permissions (CAMERA only), 16 KB ELF alignment of every 64-bit .so, Android lint (0 errors) | Pass |
| 1.1.1 regressions | Robolectric: parallel edits kept, undo after capture, unreadable import, OCR unavailable, capture after leaving camera, incoming-URI filter, safe file names, EXIF on real pixels. JVM: GC age cutoff, atomic write, strict xref offsets, streamed writer keeps no pages, 200-page encrypted PDF, Arabic text layer, empty password. The bug cases were also run against the 1.1.0 code (8 proof tests) and all 8 failed there; the others cover areas that were already right | Pass (17 new) |

| Independent QC (1.2.0) | `Qc120Test`: export-sheet Share/Save on screen (normal, OCR open, 200 %), OCR cache per language (and 1.1.1 caches), PDF recognised by its header, auto-capture status on dropped frames, report text clipping and URL loop, 1.1.1 → 1.2.0 data migration (documents, settings, signature, OCR text, clean-up, re-crop), export/share timings for 1/10/50 pages with cancel | Pass (11 new; the 9 bug cases failed on 77ad6d6) |

Not tested: live CameraX preview/auto-capture, real photos, Tesseract OCR on device, share sheet and
Storage Access Framework dialogs, TalkBack walk-through, performance and memory on low-end phones.

## Build

```
# toolchain lives in .tools/ (git-ignored); see THIRD_PARTY.md for versions
gradlew :app:testDebugUnitTest :app:assembleRelease
```
Release signing reads the git-ignored `keystore/keystore.properties`.
