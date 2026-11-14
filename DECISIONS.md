# Kiwi Scan — decision log

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
