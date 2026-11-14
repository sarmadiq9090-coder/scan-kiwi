# Kiwi Scan v1.1.0 — feature checklist

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
- [x] Receive "Share to Kiwi Scan" from other apps (images and PDFs become a new document)

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

## What was tested (32 automated tests, all passing)

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

Not tested: live CameraX preview/auto-capture, real photos, Tesseract OCR on device, share sheet and
Storage Access Framework dialogs, TalkBack walk-through, performance and memory on low-end phones.

## Build

```
# toolchain lives in .tools/ (git-ignored); see THIRD_PARTY.md for versions
gradlew :app:testDebugUnitTest :app:assembleRelease
```
Release signing reads the git-ignored `keystore/keystore.properties`.
