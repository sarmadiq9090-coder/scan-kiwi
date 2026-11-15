# Scan Kiwi 2.0.0 — hand-off (2026-11-15)

For the lead session after a context reset. Goal (owner via manager: «اكمل كل ميزات هذا اخر اصدار»): one release,
2.0.0, with every feature of D-078 / `C:\BahthTest\scan-kiwi-design\ROADMAP-2.0.md` (1.3→2.0) and `PRD-2.0.md`,
all gates passed, signed APK. **Local commits only: no push, no PR, no publishing.** Report once to the manager
(`local_7cec2ec5-92bc-44bc-bb01-69cd7c1dbbb0`) at the end.

Work split and shared rules (ownership, env, commit identity, feature records): `C:\BahthTest\scan-kiwi-design\2.0-SPLIT.md`.

## 0b. Black-box findings fixed (2026-11-15): every U1 and U2 of `docs/BLACKBOX-2.0.md`, next = re-run those tasks

Each fix has a test (new: `gates/BlackBoxTasksTest` (6 tasks through the bb.sh interpreter), `gates/SimulatedOcrTest`,
`ui/TextFieldDialogIdleTest`; changed: `update/UpdateUiTest`).
- **U1 #1 folders (app bug).** Not a cursor or an animation: any text field in a Compose dialog with the platform's
  default width never settled on a phone wider than 320 dp. Android measures a wrap-content dialog window at 320 dp, then at
  the full width (the dialog's minimum width does not fit in 320 dp); the text field re-measured at a new width asked for another
  layout on every pass (busy even with the clock stopped). Fix: `TextFieldDialogProperties` (`usePlatformDefaultWidth = false`)
  + `TextFieldDialogModifier` (24 dp margins) on all five text-field dialogs (new folder/rename folder, rename, split, PDF tool, PDF password).
- **U1 #3 camera after a language/theme change (driver bug).** The recreate itself was fine; the tester had closed the app and used
  `launch`, and `BlackBox.close()` cleared the camera seam, so the real CameraX path ran («Camera unavailable», a dead shutter).
  `launch` now re-installs the seams (`installSeams()`).
- **U1 #2 OCR in the tester's environment (driver) + text screen discoverability (app).** New seam `ocr/OcrSeam` (null in the app,
  read by `DefaultOcrEngineFactory`); the driver installs `gates/SimulatedOcr`, which recognises the invented sheets (an ink-layout
  thumbnail, lighting- and filter-proof) and returns their words with boxes. Text screen: «عرض النص وتصحيحه» / "View and correct text"
  in the page editor's menu, the document's menu and each page's menu (was «النص»).
- **U2:** Scan FAB named «مسح»/"Scan" (M3 clears the FAB text from semantics; the icon now carries it) · «إزالة الوهج»/"Remove glare" in
  the page editor's menu (`AppViewModel.removeGlare`: camera opens armed for the second shot, fused into that page) · TXT as a third
  format in the export sheet («نص TXT»/"Text (TXT)", Save and Share) · export sheet from 150 % text: the footer stays fixed (QC-1
  `QcExportFooterLargeTextTest`) but compact: Share and Save side by side without icons, labels wrap (`ButtonPair(stackOnLargeText = false)`);
  at 200 % it is about 14 % of the screen instead of a third · book mode: a persistent guide («ضع الكتاب مفتوحاً…»); auto-capture already worked in book mode
  (its failure was #3) and is tested with the new `book-spread` sample.
- **U3 done:** offline check failure is a polite live line under the row with a hint («…اتصل بشبكة Wi-Fi أو ببيانات الجوال ثم حاول مرة أخرى»);
  Home/camera privacy line «ملفاتك تبقى على هاتفك؛ الاتصال فقط للتحقق من التحديثات»; automatic document names follow the UI language
  (`localizeDefaultNames` on every activity create; new names use the in-app language below Android 13 too).
- **Seen, not fixed (S3, for 2.1):** page detection leaves out a dark header band at the paper's top edge (synthetic invoice and ID card),
  so text in that band is not in the page (searching «فاتورة» on the invoice finds nothing; body words are found).
- **Tester guide:** `tools/blackbox/README-for-testers.md` says OCR is simulated, to `wait` for background reading, and that the camera
  works after `launch`/`locale`/`darkmode`; `pages` lists the new two-page `book-spread`.
- **Full suite once, on the fix commit:** f50aead: 312 tests, 0 failures, 0 errors, 21 skipped (torture classes, `KIWI_TORTURE=1` only). A first run before the commit had 3 failures (QC-1 footer at 200 %, the smoke test's 200 % Share, an over-strict SimulatedOcr assertion), fixed and re-run by class. Torture not re-run (app code changed only in UI, dialogs and names; run it before the release if the manager wants it).
- **Re-run with the black-box tester (gate 6.4):** task 9 folders (Home and «نقل إلى مجلد»), tasks 7–8 search / view / copy / correct text,
  task 10 book mode (camera and gallery, Book tab), task 11 glare by name, task 6 TXT export, task 1 TalkBack name of «مسح», and the
  200 % export sheet; quick checks of task 12 (offline message, privacy line) and the English default name.

## 0a. Phase 3 status (2026-11-15): torture gate 6.3 DONE, next = black-box tester (gate 6.4)

Head of `release/2.0.0`: `git log -1` (torture commits 91a0dac, 2857379 + the doc/test commit after them).
- **Leader decisions applied:** the six lint exclusions in `app/lint.xml` stay as written (accepted). The five open S3 items of phase 2
  are fixed, each with a test written first (`UpdateInstallTest`, `TargetSizeTest.typedTargetsAreReadInTheSelectedUnit`,
  `TextOverlayTest.aLaterExportKeepsTheUsersCorrectionsAuthoritative` + `anEmptyRereadNeverWipesCorrections`,
  `FolderStoreTest.aDamagedFoldersFileIsRestoredFromTheLastGoodCopy`):
  1. install failure: `UpdateError.INSTALL` («لم يُثبَّت التحديث. حاول مرة أخرى.» / "The update was not installed. Try again."),
     row «حاول مرة أخرى», the verified file is kept, `UpdateController.retryInstall` (never "storage");
  2. user left the app during the download: `UpdateInstallReceiver` keeps Android's confirmation in `UpdateInstallEvents.pending`
     when the app is not in front (`MainActivity.onStart/onStop` set `foreground`); on return `UpdateConfirmDialog` shows
     «التحديث جاهز للتثبيت» and its button opens the confirmation. A dialog, not a notification: no new permission (POST_NOTIFICATIONS would change the CI-checked list);
  3. target size: `TargetSize.parseBytes` ("1,000", "1٬000", "١٬٠٠٠", "1 000" = 1000; "1,5", "١٫٥" = 1.5; BigDecimal, 10 TB cap);
  4. corrections: `DocumentStore.saveOcr` (both overloads) re-bases the overlay of the same revision onto the new words
     (`TextOverlay.rebasedOn`), so the corrected text stays authoritative; an empty re-read never replaces a corrected cache; Undo re-bases too;
  5. `folders.json`: atomic write + `folders.json.bak` (same bytes); a damaged or missing main file is restored from it.
- **Known issue for 2.1 (recorded in `docs/FEATURES-2.0.md`):** typed but unsaved text is lost on process death.
- **Torture suite (gate 6.3), run once (`tools/blackbox/torture.sh`), then only the failed/unfinished cases:** 20 test methods
  (chaos 7, fuzz 2, monkey 3, perf 1, stress 3, property 4), about 17,500 generated cases/actions: fuzz 312 (40 share intents, 272 hostile files),
  monkey 255 actions over 8 seeds (en 505/606/707/808, ar 101/202/303/404), property-based 16,300 seeded cases (overlay diff/rebase 3000,
  typed target size 8000, folders under damage 300, SemVer 5000), stress (200 reorder/delete/undo steps, 30-photo import, 50 export taps with cancels, 300-edit burst),
  chaos (death mid-save 8 snapshots, disk full at 0 B/1 KB/100 KB/1 MB for edit and PDF export, blocked writes, permission revoked, memory pressure),
  perf (100 pages: open 81 ms, export 4.4 s, OCR on 9.1 s). `TortureChaosTest.diskFullMidWriteKeepsDocumentsIntact`: passed.
  - **S1: 0. S2: 2 found, 2 fixed (regression tests in `IntakeTest` and the fuzz cases themselves):**
    (a) `ACTION_SEND_MULTIPLE` with a null entry crashed the intake (`isAcceptableIncoming` took a non-null Uri);
    (b) a PDF declaring `/Count 2147483647` kept the import busy for ever: `PdfImporter.requireImportable` refuses more than 5000 pages before reading any.
  - **S3 open (2.1):** `deletePage(docId, index)` and `movePage` are index-based and queued on `Dispatchers.Default`; a burst of many edits within a few ms
    can run out of call order (a delete by a stale index is then a no-op). Not reachable at human speed; `deletePages(ids)` is order-independent.
  - **Known tool issues (no app code in the stack, do not block):** monkey runs end early with 7 × `AppNotIdleException` (Robolectric/Compose
    idle wait, 60 s, `BlackBox.settle`) and 1 × `AssertionError` ("Failed to perform text input" on a dropdown node, `BlackBox.run` line 224, seed 505 step 9).
    Test-design fix: `fiftyRapidExportsWithCancels` waited 60 s for a result a cancelled export never sends (it made the run take 30+ minutes); `concurrentEditBurst`
    now deletes by page id.
- **Full suite once, on 4c3a300 (code identical to the final commit): 302 tests, 0 failures, 0 errors, 21 skipped (the torture classes, which run only with `KIWI_TORTURE=1`).** Not re-run after the doc-only commit.

**Next:** 6.4 code-blind user tester (sonnet; `tools/blackbox/README-for-testers.md`), then 6.5 QC, screenshots, release (§4 items 6–9).
Do not re-run the torture suite unless app code changes; do not re-run the full suite on the same commit.

## 0. Phase 2 status (2026-11-15): cleanup + bug hunt DONE, next = torture (gate 6.3)

Head of `release/2.0.0`: see `git log -1` (bug-hunt commits 0014ea4, 4861f9e, 33ba415, 7f01686, b2085d1 + this doc).
- **Leader decisions applied:** ACCESS_NETWORK_STATE accepted (README ar/en, D30, update.md list CAMERA, INTERNET,
  ACCESS_NETWORK_STATE, REQUEST_INSTALL_PACKAGES); repo `sarmadiq9090-coder/scan-kiwi` everywhere (already true); the
  notice, About and privacy text say «ماسح كيوي».
- **Licences:** `LICENSES/APAFML.txt`, `LICENSES/Unicode-DFS-2015.txt` (SPDX license-list-data v3.27.0). `reuse lint`: compliant, 427/427.
- **`docs/FEATURES-2.0.md`** written (item 5 below is done).
- **Lint:** `lintDebug` and `lintRelease`: **No issues found** (was 5 errors, 105 warnings, 5 hints). Non-applicable
  checks are ignored with reasons in `app/lint.xml` (dependency versions, ChromeOS x86_64, app-bundle locales, ExifInterface).
- **Full suite once on 7f01686:** 287 tests, 286 pass, 1 fail (`BlackBoxSmokeTest` expected the old English pill
  «Done, 1 pages», corrected on purpose); fixed in b2085d1, the class re-runs 2/2. `perfStressTest` 1/1. Do not re-run
  the full suite on b2085d1 unless code changes.
- **Bug hunt (gate 6.2) — S1: 0. S2: 1 found, 1 fixed. S3: 12 fixed, 6 open.**
  - S2 fixed: a connection that died while the release JSON or SHA256SUMS body was read threw a plain IOException
    out of the updater and crashed the app at the automatic check on app open (test
    `UpdaterTest.aConnectionThatDiesMidResponseIsANetworkErrorNeverACrash`, failed first, passes now).
  - S3 fixed: Wi-Fi only now holds for the whole download; a clock that ran ahead no longer turns the weekly check
    off; leftover update files cleaned at app open; archive must carry a higher versionCode (no same-version loop);
    failed install session abandoned; only `ScanKiwi-<tag version>.apk` is taken; APK read errors say "network", not
    "storage"; English «Done, 1 pages»; Arabic plural for «و٢ تنبيه آخر»; OCR diagnostics in the app's digits; system
    back while correcting text leaves the correction, not the screen; a very thin page no longer makes a 0-pixel OCR
    input (crash-to-message); README said "camera is the only permission".
  - S3 open (for QC / 2.1; **items 1-5 fixed in phase 3, §0a; only item 6 stays for 2.1**): (1) an install failure reported by PackageInstaller shows no message, and «install again»
    then says storage error (file already deleted); (2) the install confirmation may not open if the user left the app
    during the download (Android's background-activity rule; «install again» works); (3) target size typed as "1,000"
    is read as 1.0 KB (true size reported, never a false success); (4) a damaged `folders.json` loses folder names
    (documents stay, at top level); (5) corrections made on an OCR result that a later export replaced are hidden and
    a new correction overwrites them; (6) typed but unsaved text is lost on process death (the app restarts at Home, as in 1.x).
  - Checked, no defect: TLS/host allow-list (redirects re-checked, NSC system trust, no cleartext), sha256 and
    signer bypasses (verifier exceptions = mismatch, no SUMS line = no download), downgrade (SemVer + versionCode +
    Android's own refusal), path traversal (asset name regex has no separators), no network code outside
    `update/UpdateClient.kt`, document/folder/index writes atomic, rotation (configChanges) and saved-state types.

**Next:** gate 6.3 torture (`tools/blackbox/torture.sh`, once, then failed cases only; includes
`TortureChaosTest.diskFullMidWriteKeepsDocumentsIntact`), then 6.4 blind tester, 6.5 QC, then release (§4 items 6–9).

## 1. Branches (repo `E:\fast kiwi pro neq\kiwi-scan`)

**Phase 1 (features) is finished (2026-11-15).** Every feature branch is merged into `release/2.0.0`; nothing is wip.

| Branch | Head | State |
|---|---|---|
| `release/2.0.0` (main checkout) | see `git log -1` | all feature branches merged (merge commits bf841ff cam2, eecbe41 perf, 2123da0 ocr, 87fe76e gates, 9321873 update) + docs commit. **Full suite (once, on 9321873 = everything merged): 283 tests, 282 pass, 1 fail** (`DesignArabicTest`: a clipped row on the longer Updates screen) **fixed in 6e826e4/9a457c5; the failed class + update tests re-run green (5/5); `perfStressTest` 1/1 pass.** (Earlier run before the update branch, 87fe76e: 271/271 + stress.) |
| `feat/2.0-cam2` (`sk-wt\cam2`) | 468e0b4 | merged. Noise reduction, auto-deskew, strip filtering > 12 MP, content-aware batch capture |
| `feat/2.0-perf` (`sk-wt\perf`) | 7b57aa6 | merged. Low-memory mode, heap/stress budgets, Android 7 (API 24 Robolectric), StrictMode, baseline profile |
| `feat/2.0-ocr` (`sk-wt\ocr`) | b48aadb | merged. Mixed-page fix (decision 2) + new parity reference (decision 3) |
| `feat/2.0-update` (`sk-wt\update`) | 858fd45 | merged. In-app GitHub update check (owner's order 2026-11-15, D30), see §3.5 |
| `feat/2.0-gates` (`sk-wt\gates`) | 4171ef2 | merged. Seams applied (`ui/CaptureSeam`, `data/IoFaults`), black-box driver uses the real shutter and auto-capture gate |
| `feat/2.0-docs`, `feat/2.0-cam`, `feat/2.0-pdf` | merged | done |

The wip screenshots (40–48) were dropped from the branches; untracked copies sit in the main checkout's `screenshots/`
(test output). All screenshots are regenerated once at the end. No agents or Gradle daemons are running.
OCR bench work folder (venv, models, sets incl. `dev2`, results): `E:\fast kiwi pro neq\sk-ocr-work` (results of the
2.0 fix in `results/route/`).

## 2. Done (merged, with numbers; details in `docs/features-2.0/{ocr,pdf,camera,docs,perf,update}.md`)

- OCR: `OcrEngine` interface; fused PP-OCRv6-tiny det + PP-OCRv5 Arabic rec on MNN 3.6.2 (fp16) + Tesseract line re-read;
  «دقيق»/«سريع» with Fast fallback; background OCR after capture; diagnostics row; arm64-v8a + armeabi-v7a only.
  **2.0 mixed fix** (Tesseract mixed lines in logical order + per-line routing; tuned on dev + dev2 only):
  held-out 156 pages CER **9.0%** (was 11.3%; tess_fast 29.0%), tables 4.8%, numbers 72.1%, **mixed −9.4 pp vs Fast
  (was +5.4; limit +5): every gate PASS**. Dev 52 pages: 15.5% → 11.8%. **Parity reference** = `kiwi_fused_onnx`
  (same logic on ONNX Runtime): MNN fp16 −0.22 pp, PASS. Chromium vs Edge renderer: +1.8 pp (fail, recorded).
- PDF: PdfEngine; PDF-01..05, 07..12, 14; drag reorder; Undo/Redo; target size (50 runs, 0 false success); integrity (kill 10/10);
  fuzz 200 (0 crashes); PDF/A with numbers+watermark passes veraPDF; PdfBox-Android in `:pdf` process (+0.63 MB).
- Camera: detection 91.7% within 3% (baseline 69.4%), 0% false pages; blur AUC 0.996; incomplete 95%; quality report
  in `ScanPage.quality`; book mode spine 40/40; glare fusion 29/30. **cam2:** batch capture by page content 42/42 correct,
  0 duplicates (outline-only 29/42, 10 duplicates); auto-deskew after capture + «تسوية الميل» (40/40 within 0.3°);
  noise reduction (PSNR better 54/54, OCR-proxy not worse 54/54, clean pages unchanged); strip filtering (50 MP page with
  ≤ 24.2 MB working memory, identical pixels). Only curved-page dewarping is not done (by decision A4).
- Documents: FTS4 search (recall 100%, worst 10.8 ms / 1000 pages), TXT, text correction overlay, favorites, folders (one level), 2.0 Home.
- Performance: low-memory mode (auto < 3 GB / `isLowRamDevice`, Settings override); heap on the merged release: 20-page OCR export 89.7 MB (≤ 256), 200 pages 49.1 MB (≤ 128), 500-page stress at 512 MB no OOM (PDF+OCR 44 s, JPEG 33 s); totals vary ±30 MB between runs; Android 7 flows
  9/9 on API 24/26/28 (Robolectric only); StrictMode in debug; baseline profile. Not measurable here: cold start ≤ 1 s and
  OCR −20 % on the phone (owner check).
- Gate tooling: black-box driver (`tools/blackbox/bb.sh`, `README-for-testers.md`) now drives the app's own camera code
  through `CaptureSeam`; torture suite (`tools/blackbox/torture.sh`, `KIWI_TORTURE=1`) + new
  `TortureChaosTest.diskFullMidWriteKeepsDocumentsIntact` (IoFaults; compiled, **not run yet**: it belongs to gate 6.3).
  `BlackBoxSmokeTest` (ar; en at 200 %) passes.
- Update from GitHub (D30, `docs/features-2.0/update.md`): weekly automatic check at app open + «تحقق من التحديثات»;
  SemVer + SHA256SUMS + signing-certificate checks, any failure deletes the file; PackageInstaller with Android's
  confirmation; «التحقق التلقائي» and «Wi-Fi فقط» on by default; one-time notice (owner's wording). 12 update tests
  against a local mock server; under Robolectric the app's updater always sees "offline" (no test can reach GitHub).
- APK (release, signed, before perf/cam2/update): 18.30 MB (target 25, cap 30). Not rebuilt in phase 1.

## 3. Manager decisions (2026-11-15) — apply them

1. **APAFML accepted** (permissive, SPDX, Fedora/Debian). Done: ROADMAP §0, THIRD_PARTY, SBOM (commit c7d32b4), notices.
   Still to flag to the council: Unicode-DFS-2015 (`BidiMirroring.txt` in PdfBox), same reasoning.
   **Done (0014ea4), reuse lint compliant.** Was: `reuse lint` reported "Missing licenses: APAFML, Unicode-DFS-2015" (no `LICENSES/APAFML.txt`,
   `LICENSES/Unicode-DFS-2015.txt`); add the two SPDX texts (`reuse download APAFML Unicode-DFS-2015`, a download).
2. **Mixed pages — DONE, passed (no exception needed):** ONE bounded fix (per-line script detection → route Latin lines to Tesseract or the better model,
   tuned on the DEV set only, never the held-out). If held-out mixed ≤ +5 pp → done. Else write the exception with
   numbers in `docs/ocr-benchmark.md` and the release-notes line: «الصفحات المختلطة (عربي وإنجليزي) أضعف قليلاً، وتحسينها في 2.1».
3. **ONNX reference — DONE** (commit b48aadb; cause documented in `docs/ocr-benchmark.md` → Parity): accepted if the cause is known (post-processing/fusion, not model drift). Explain
   in `docs/ocr-benchmark.md`; update the parity reference in an explicit commit; keep ±0.5 pp around the NEW reference.
4. **Folders:** one level in 2.0; nested folders on the 2.1 list.
5. **Update from GitHub (owner's direct order 2026-11-15, amended to weekly) — DONE** in `feat/2.0-update` (D30 in
   `DECISIONS.md`). Replaces D-077's "no network": permissions now CAMERA, INTERNET, ACCESS_NETWORK_STATE,
   REQUEST_INSTALL_PACKAGES (CI checks the exact list and that `update/UpdateClient.kt` is the only network code).
   **Leader decisions (resolved 2026-11-15, applied in 0014ea4: (a) accepted, (b) `sarmadiq9090-coder/scan-kiwi`, (c) «ماسح كيوي»):** (a) ACCESS_NETWORK_STATE was added although the spec named only INTERNET and
   REQUEST_INSTALL_PACKAGES: «التنزيل عبر Wi-Fi فقط» cannot work without it; (b) the source is
   `sarmadiq9090-coder/scan-kiwi` (the real remote and the existing links), not `kiwi-scan` as written in the order;
   (c) the notice says «سكان كيوي» (owner's words, verbatim) while the app's Arabic name is «ماسح كيوي».
   The site's wording is updated by the coordinator at publication. CHANGELOG 2.0.0 must say it (release phase).
   **Release-phase contract:** the GitHub release must carry `ScanKiwi-<version>.apk` and `SHA256SUMS.txt` with a
   line for that APK, tag `v<SemVer>`, not a draft/pre-release; otherwise the app refuses the update.

## 4. Remaining work, in order

Items 1–4 of the old list (cam2, perf, OCR decisions 2 and 3, gates seams) are done (phase 1). Next phase starts here:

5. **DONE (phase 2).** Write `docs/FEATURES-2.0.md` (Arabic first): every feature from the six `docs/features-2.0/*.md` with status,
   acceptance criterion, measured number, test; not-done items with reasons; a 2.1 list (nested folders,
   curved-page dewarping excluded by A4; mixed pages are fixed, no 2.1 entry). Owner phone checks still open:
   Accurate ≤ 8 s/page and ≤ 300 MB native heap, cold start ≤ 1 s, OCR −20 %, blur thresholds, Android 7/8 device,
   and the update path on a phone (certificate read, PackageInstaller confirmation, Wi-Fi detection, a real release).
6. Gates on the complete build (all must pass):
   1. **Partly done (phase 2, §0): full tests, lintDebug/lintRelease, reuse lint pass.** Still open: CI-equivalent commands on a clean clone, release build + size gate.
      Original text: full tests (phase 1 ran them once on the merged release, see §1; re-run only after new commits), lint
      (`lintDebug`, not run since the perf/cam2 merges), `reuse lint` (fix the two missing licence texts, §3.1),
      CI-equivalent commands on a clean clone, release build + size gate (the APK grew by perf/cam2 code only);
   2. **DONE (phase 2, §0):** independent bug hunter (Opus): fix every S1/S2;
   3. **DONE (phase 3, §0a):** torture suite (`tools/blackbox/torture.sh`, run once, then only failed cases; includes the new
      `diskFullMidWriteKeepsDocumentsIntact`, never run so far): fix everything found;
   4. code-blind user tester (sonnet; sees only `tools/blackbox/README-for-testers.md`, screenshots and the a11y tree):
      personas busy employee, older user with large text, student mixing Arabic/English; tasks cold, e.g. "scan 3 pages
      and send on WhatsApp under 500 KB", "find word X in an old document", "merge two PDFs", "protect with a password";
      fix every U1/U2, re-run;
   5. QC (Opus) against `DESIGN-SPEC.md`, `STANDARDS.md`, `docs/FEATURES-2.0.md`.
   The bug hunter and QC should look hard at the new network surface (`update/`, manifest, NSC, CI check).
7. Regenerate screenshots (ar + en) once; restore unrelated churn.
8. Release: signed APK (same key, versionCode 5, versionName 2.0.0) installing over 1.2.0 without data loss (upgrade
   test like `dataFrom111SurvivesTheUpgrade`); `dist/ScanKiwi-2.0.0.apk`, `ScanKiwi-2.0.0.cdx.json`, in-toto at the
   final HEAD, `SHA256SUMS.txt` (LF); CHANGELOG 2.0.0 (ar then en); release notes `C:\BahthTest\scan-kiwi-design\release-2.0.0-notes.md`.
9. One report to the manager: APK path, size, sha256, feature count, every gate result in numbers.

## 5. Commands

Environment (bash; never a shared scratchpad env file):
```sh
export JAVA_HOME="E:\fast kiwi pro neq\kiwi-scan\.tools\jdk-21.0.13+9"
export ANDROID_HOME="E:\fast kiwi pro neq\kiwi-scan\.tools\android-sdk" ANDROID_SDK_ROOT="$ANDROID_HOME"
export ANDROID_USER_HOME="E:\fast kiwi pro neq\kiwi-scan\.tools\android-user"
export GRADLE_USER_HOME="E:\fast kiwi pro neq\kiwi-scan\.tools\gh-<area>"   # main checkout: .tools/gradle-home (source .tools/env.sh)
export PATH="/e/fast kiwi pro neq/kiwi-scan/.tools/jdk-21.0.13+9/bin:/e/fast kiwi pro neq/kiwi-scan/.tools/gradle-8.14.3/bin:$PATH"
```
- Main checkout tests: `bash .tools/runtests.sh` (prints `tests N failures F`); filter: `bash .tools/runtests.sh --tests '*gates.Torture*'`.
- Worktree tests: `gradle --no-daemon -q :app:testDebugUnitTest` (run in the background; ~10–15 min).
- Release build (signed from the main checkout, keystore is git-ignored; never print it): `source .tools/env.sh && gradle --no-daemon -q clean :app:assembleRelease :app:lintDebug :app:releaseSbom`.
- Size gate: `python -I C:\BahthTest\scan-kiwi-design\size-gate\size_gate.py <apk> --budget C:\BahthTest\scan-kiwi-design\size-gate\budgets.json --version 2.0.0` (add a 2.0.0 entry: target 25, cap 30, ABIs arm64-v8a + armeabi-v7a).
- REUSE: `.tools/reuse-venv/Scripts/reuse.exe lint`.
- Verify signature/permissions: `apksigner verify --print-certs -v`, `aapt2 dump permissions` (build-tools under `.tools/android-sdk/build-tools/*`). Cert SHA-256 must be `26e5ed00…2dcce6`.
- Provenance: rewrite `dist/*.intoto.jsonl` (subject sha256, `gitCommit` = final HEAD, finishedOn); `sha256sum -c SHA256SUMS.txt`.
- New Maven artifacts: checksum checker `python C:/BahthTest/scan-kiwi-design/tools/verify_new_entries.py OLD.xml NEW.xml GRADLE_USER_HOME` (compares each new verification-metadata sha256 with the repository's published .sha256/.sha1).
- UI tests rewrite `screenshots/*.png` (date text): `git checkout -- screenshots` before committing.

## 6. Cost rules after the reset (manager)

At most 2 subagents at a time; `model: sonnet` for mechanical work (test runs, torture, blind tester, screenshots);
Opus only for the bug hunter and QC. No long logs in context (tail/grep, summaries ≤ 30 lines); don't read big files whole.

Owner (via manager): «اختصر غير الضروري وحافظ على جودة المنتج النهائي».
- QC screenshots by sample: each screen once; dark mode, 200% font and English only for a few key screens;
  no mass screenshot sweeps, never read every image into context.
- Unchanged: the independent bug hunter (Opus), the torture suite (run in the shell, read only its summary),
  the code-blind tester with three personas (sonnet), and QC against the specs.

## Owner rule, 2026-11-15: tests

«الاختبارات الغير ضرورية لا تقوم بتشغيلها او شغلها مره واحده».

- While working, run only the changed module's tests.
- Run the full suite once, before the build.
- Never repeat a run that already passed on the same commit.
- Torture runs once, then only the cases that failed.
