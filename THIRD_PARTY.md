# Third-party components and licences

All components are free, work offline, and have permissive licences. Nothing here
contacts a server at run time. The app ships the full notices in
`app/src/main/assets/THIRD_PARTY_NOTICES.txt`, which you can open under Settings → Licences.

## Shipped inside the APK

| Component | Version | Licence | Use |
|---|---|---|---|
| Kotlin stdlib | 2.2.21 | Apache-2.0 | Language runtime |
| kotlinx-coroutines-android | 1.10.2 | Apache-2.0 | Background work |
| AndroidX Compose BOM (ui, foundation, material3) | 2025.12.01 | Apache-2.0 | Material 3 UI |
| androidx.activity:activity-compose | 1.11.0 | Apache-2.0 | Activity + result APIs (SAF pickers) |
| androidx.core:core-ktx | 1.17.0 | Apache-2.0 | FileProvider, IntentCompat |
| androidx.lifecycle (viewmodel-compose, runtime-compose) | 2.9.4 | Apache-2.0 | ViewModel and state |
| androidx.camera (camera2, lifecycle, view) | 1.5.3 | Apache-2.0 | Camera preview, analysis and capture |
| ↳ libyuv (inside camera-core, `libimage_processing_util_jni.so`) | bundled native | BSD-3-Clause | YUV conversion used by CameraX |
| cz.adaptech.tesseract4android:tesseract4android | 4.9.0 (JitPack) | Apache-2.0 | OCR JNI wrapper |
| ↳ Tesseract OCR | 5.x (bundled native) | Apache-2.0 | OCR engine |
| ↳ Leptonica | bundled native | BSD-2-Clause-style (Leptonica licence) | Image library used by Tesseract |
| ↳ libjpeg-turbo | bundled native | IJG + BSD-3-Clause + zlib | JPEG codec used by Leptonica |
| ↳ libpng | bundled native | PNG Reference Library License v2 | PNG codec used by Leptonica |
| MNN (Alibaba) `libMNN.so`, CPU runtime | 3.6.2 (official Android release) | Apache-2.0 | Runs the «دقيق» Accurate OCR models (arm64-v8a, armeabi-v7a) |
| LLVM libc++ `libc++_shared.so` | from NDK 27.3.13750724 | Apache-2.0 WITH LLVM-exception | C++ runtime needed by MNN and our JNI glue `libkiwi_mnn.so` |
| PP-OCRv6 tiny text detector (PaddlePaddle), `PP-OCRv6_det_tiny.fp16.mnn` | PaddleOCR 3 / RapidOCR 3.10.0 ONNX export, converted with MNNConvert 3.6.2 (fp16 weights) | Apache-2.0 | Finds text lines (Accurate mode) |
| PP-OCRv5 Arabic mobile recogniser (PaddlePaddle), `arabic_PP-OCRv5_rec_mobile.fp16.mnn` + `ppocrv5_arabic_dict.txt` | as above | Apache-2.0 | Reads Arabic lines (Accurate mode) |
| tessdata_fast `ara.traineddata`, `eng.traineddata` | main branch, 2026-11-14 | Apache-2.0 | Arabic and English OCR models |
| Tesseract `pdf.ttf` (renamed `glyphless.ttf`) | main branch | Apache-2.0 | Invisible font for the searchable-text layer |
| `sRGB-v2-micro.icc` (Compact-ICC-Profiles by Clinton Ingram) | master, 2026-11-14 | CC0-1.0 | sRGB output intent for PDF/A-2b ("أرشيفي") exports |
| Noto Sans Arabic Regular / Medium / Bold (unhinted static TTF) | 2.013 (notofonts/arabic release) | OFL-1.1 | Bundled UI font (`res/font/`) |
| Material Symbols Rounded (vector drawables, weight 400, grade 0, 24 dp) | google/material-design-icons, master 2026-11-14 | Apache-2.0 | UI icons (`res/drawable/ic_*.xml`) |
| com.tom-roush:pdfbox-android (2.0-pdf) | 2.0.27.0 (Maven Central, aar sha256 `30277f879cfd571db2a137582c95516a0d4ea6778e945519bc58ca93d57d88c7`) | Apache-2.0 | Lossless merge/split/extract/rotate, text and unlock of PDF files from other apps; runs only in the isolated `:pdf` process. BouncyCastle is excluded; CJK CMaps and `Scripts.txt` are not packaged |
| ↳ Adobe Core 14 AFM metrics, Adobe Glyph List + Zapf Dingbats list, Unicode `BidiMirroring.txt`, Liberation Sans Regular 2.1.5 (`LiberationSans-Regular.ttf` sha256 `76d04c18ea243f426b7de1f3ad208e927008f961dc5945e5aad352d0dfde8ee8`) | bundled assets of PdfBox-Android | APAFML; BSD-3-Clause; Unicode-DFS-2015; OFL-1.1 | Text extraction (font widths, glyph names, bidi mirroring) and PdfBox's last-resort font. Note: APAFML and the Unicode data licence are permissive but not on the ROADMAP §0 list; recorded for the lead/council |
| Other transitive libraries (more AndroidX modules, `com.google.guava:listenablefuture`, `com.google.auto.value:auto-value-annotations`, `org.jspecify:jspecify`) | see the SBOM | Apache-2.0 | Pulled in by the libraries above; the full list with versions is in the release SBOM (`ScanKiwi-<version>.cdx.json`) |

Model checksums (SHA-256):
- `ara.traineddata` `e3206d3dc87fd50c24a0fb9f01838615911d25168f4e64415244b67d2bb3e729`
- `eng.traineddata` `7d4322bd2a7749724879683fc3912cb542f19906c83bcc1a52132556427170b2`
- `PP-OCRv6_det_tiny.fp16.mnn` `a1d21086e7508b10fe977de651cdcba902d60d5fc6d1f87f17cea20f0fe129e6` (from ONNX `f42c0fbd294d95eac1a550e131b277dac97462c8025fa4b6c3cec1b7894bd3d5`)
- `arabic_PP-OCRv5_rec_mobile.fp16.mnn` `9c0098c4d0b37437f55e1e1225c6be2fae6506239bfef868fb3c8f90a5cc484e` (from ONNX `c1192e632d0baa9146ae5b756a0e635e3dc63c1733737ebfd1629e87144e9295`)
- `ppocrv5_arabic_dict.txt` `7f92f7dbb9b75a4787a83bfb4f6d14a8ab515525130c9d40a9036f61cf6999e9`
- `libMNN.so` arm64-v8a `f915474f37c6e04046eae2206ac9de0c1c336f648564f983a640e21021e1bb00`, armeabi-v7a `d07d529b5fbbdf4bdae0fba6bd4f7e41f45b78ea3b2234205a68380c3f7f7444` (release zip `0b4cc255964de1e3ac8dce75dce316699a0a28b914ecacb7bea8465666858ece`)
- Model licence evidence, conversion and reproduction: `tools/mnn/README.md`.
- `glyphless.ttf` `c7845420925a23d88ed830a63957b8af85a66a8daf8d9fc90e843673b2ef1a59`
- `sRGB-v2-micro.icc` `0a8a33aea66a6f154a5642ebe168ef287e73265d9f7b51c42a45e6eedbacda7a`
- `noto_sans_arabic_regular.ttf` `bd86ca02f087d7f3c3788ba458fb6b73744c7639ed276b8d870dba6def6c40d0`
- `noto_sans_arabic_medium.ttf` `a66dfdfd2fd345d500a8f169d07297eb29fc683938c61d6bdd5717cafca86d51`
- `noto_sans_arabic_bold.ttf` `f4cb79f842a07d1bcf597879574358fb7010f73761396d1d0f2445dfedb4d2ca`

Our own code (edge detection, filters, PDF writer with AES-256, UI) uses only the Android
framework and the libraries above. No OpenCV, ML Kit, Firebase, Play services, analytics or ad SDKs.

## Test-only (not in the APK)

| Component | Version | Licence |
|---|---|---|
| JUnit 4 | 4.13.2 | EPL-1.0 |
| org.json | 20250517 | Public Domain (JSON-java) |
| Apache PDFBox | 3.0.6 | Apache-2.0 |
| Robolectric | 4.16.1 | MIT |
| androidx.compose.ui:ui-test-junit4 / ui-test-manifest | via Compose BOM 2025.12.01 | Apache-2.0 |
| veraPDF validation-model | 1.30.4 | MPL-2.0 or GPL-3.0-or-later (we use it under MPL-2.0) |

## OCR benchmark tools only (`tools/ocr-bench`, not in the APK)

Installed by `tools/ocr-bench/run.py` from the hash-pinned `requirements-lock.txt`; run on a developer PC or in the
manual `ocr-bench` workflow only.

| Component | Version | Licence |
|---|---|---|
| tesserocr (Tesseract 5.5.4, Leptonica inside the wheel) | 2.13.0 | MIT (tesserocr); Apache-2.0 (Tesseract); BSD-2-Clause-style (Leptonica) |
| ONNX Runtime | 1.32.0 | MIT |
| RapidOCR | 3.10.0 | Apache-2.0 |
| MNN Python wheel (MNNConvert) | 3.6.2 | Apache-2.0 |
| Playwright for Python (+ Chromium build 1255, downloaded by Playwright) | 1.65.2 | Apache-2.0 (Chromium: BSD-3-Clause and others) |
| OpenCV (headless), NumPy, Pillow, RapidFuzz, shapely, pyclipper, python-bidi, psutil and their dependencies | see the lock file | Apache-2.0, BSD, MIT, HPND, MIT and BSL-1.0 (pyclipper), LGPL-3.0 (python-bidi; tool only, never shipped) |
| tessdata_best `ara`, `eng` | 4.1.0 | Apache-2.0 |
| Fonts in `tools/ocr-bench/fonts/` (Noto Naskh/Sans/Kufi Arabic, Noto Sans, Amiri, Cairo, Tajawal, IBM Plex Sans Arabic, Almarai, Scheherazade New, Lateef, Markazi Text, Vazirmatn, Readex Pro, Harmattan) | google/fonts `cc39c4c2` and earlier copies | OFL-1.1 (licence file next to each font) |

## Build-only (not in the APK)

| Component | Version | Licence | Use |
|---|---|---|---|
| CycloneDX Gradle plugin | 3.x | Apache-2.0 | Generates the release SBOM (`dist/*.cdx.json`) |

## Build toolchain (downloaded from official sources into `.tools/`, not shipped)

| Tool | Version | Licence | Source |
|---|---|---|---|
| Eclipse Temurin JDK | 21.0.13+9 | GPL-2.0 with Classpath Exception | api.adoptium.net (SHA-256 verified) |
| Android SDK command-line tools | 16111833 | Android SDK License | dl.google.com (SHA-1 verified against repository XML) |
| Android SDK platform 36, build-tools 36.0.0, platform-tools | — | Android SDK License | Android SDK repository |
| Gradle | 8.14.3 | Apache-2.0 | services.gradle.org (SHA-256 verified, pinned in the wrapper) |
| Android Gradle Plugin | 8.13.2 | Apache-2.0 | Google Maven |
| Android NDK | 27.3.13750724 | Android SDK License (toolchain: Apache-2.0 WITH LLVM-exception) | Android SDK repository (default NDK of GitHub's ubuntu-24.04 runner) |
| CMake | 3.31.5 | BSD-3-Clause | Android SDK repository |
