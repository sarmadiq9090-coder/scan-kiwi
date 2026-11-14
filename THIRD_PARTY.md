# Third-party components and licences

All components are free, work offline, and have permissive licences. Nothing here
contacts a server at run time. The app ships the full notices in
`app/src/main/assets/THIRD_PARTY_NOTICES.txt`, which you can open under Settings → Licences.

## Shipped inside the APK

| Component | Version | Licence | Use |
|---|---|---|---|
| Kotlin stdlib | 2.2.21 | Apache-2.0 | Language runtime |
| kotlinx-coroutines-android | 1.10.2 | Apache-2.0 | Background work |
| AndroidX Compose BOM (ui, foundation, material3, material-icons-extended) | 2025.12.01 | Apache-2.0 | Material 3 UI |
| androidx.activity:activity-compose | 1.11.0 | Apache-2.0 | Activity + result APIs (SAF pickers) |
| androidx.core:core-ktx | 1.17.0 | Apache-2.0 | FileProvider, IntentCompat |
| androidx.lifecycle (viewmodel-compose, runtime-compose) | 2.9.4 | Apache-2.0 | ViewModel and state |
| androidx.camera (camera2, lifecycle, view) | 1.5.3 | Apache-2.0 | Camera preview, analysis and capture |
| cz.adaptech.tesseract4android:tesseract4android | 4.9.0 (JitPack) | Apache-2.0 | OCR JNI wrapper |
| ↳ Tesseract OCR | 5.x (bundled native) | Apache-2.0 | OCR engine |
| ↳ Leptonica | bundled native | BSD-2-Clause-style (Leptonica licence) | Image library used by Tesseract |
| ↳ libjpeg-turbo | bundled native | IJG + BSD-3-Clause + zlib | JPEG codec used by Leptonica |
| ↳ libpng | bundled native | PNG Reference Library License v2 | PNG codec used by Leptonica |
| tessdata_fast `ara.traineddata`, `eng.traineddata` | main branch, 2026-11-14 | Apache-2.0 | Arabic and English OCR models |
| Tesseract `pdf.ttf` (renamed `glyphless.ttf`) | main branch | Apache-2.0 | Invisible font for the searchable-text layer |

Model checksums (SHA-256):
- `ara.traineddata` `e3206d3dc87fd50c24a0fb9f01838615911d25168f4e64415244b67d2bb3e729`
- `eng.traineddata` `7d4322bd2a7749724879683fc3912cb542f19906c83bcc1a52132556427170b2`
- `glyphless.ttf` `c7845420925a23d88ed830a63957b8af85a66a8daf8d9fc90e843673b2ef1a59`

Our own code (edge detection, filters, PDF writer with AES-256, UI) uses only the Android
framework and the libraries above. No OpenCV, ML Kit, Firebase, Play services, analytics or ad SDKs.

## Test-only (not in the APK)

| Component | Version | Licence |
|---|---|---|
| JUnit 4 | 4.13.2 | EPL-1.0 |
| org.json | 20250517 | Public Domain (JSON-java) |
| Apache PDFBox | 3.0.6 | Apache-2.0 |

## Build toolchain (downloaded from official sources into `.tools/`, not shipped)

| Tool | Version | Licence | Source |
|---|---|---|---|
| Eclipse Temurin JDK | 21.0.13+9 | GPL-2.0 with Classpath Exception | api.adoptium.net (SHA-256 verified) |
| Android SDK command-line tools | 16111833 | Android SDK License | dl.google.com (SHA-1 verified against repository XML) |
| Android SDK platform 36, build-tools 36.0.0, platform-tools | — | Android SDK License | Android SDK repository |
| Gradle | 8.14.3 | Apache-2.0 | services.gradle.org (SHA-256 verified, pinned in the wrapper) |
| Android Gradle Plugin | 8.13.2 | Apache-2.0 | Google Maven |
