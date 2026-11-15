# Scan Kiwi 2.0.0 — hand-off (2026-11-15)

For the lead session after a context reset. Goal (owner via manager: «اكمل كل ميزات هذا اخر اصدار»): one release,
2.0.0, with every feature of D-078 / `C:\BahthTest\scan-kiwi-design\ROADMAP-2.0.md` (1.3→2.0) and `PRD-2.0.md`,
all gates passed, signed APK. **Local commits only: no push, no PR, no publishing.** Report once to the manager
(`local_7cec2ec5-92bc-44bc-bb01-69cd7c1dbbb0`) at the end.

Work split and shared rules (ownership, env, commit identity, feature records): `C:\BahthTest\scan-kiwi-design\2.0-SPLIT.md`.

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
   **Open for gate 6.1:** `reuse lint` reports "Missing licenses: APAFML, Unicode-DFS-2015" (no `LICENSES/APAFML.txt`,
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
   **Leader decisions open:** (a) ACCESS_NETWORK_STATE was added although the spec named only INTERNET and
   REQUEST_INSTALL_PACKAGES: «التنزيل عبر Wi-Fi فقط» cannot work without it; (b) the source is
   `sarmadiq9090-coder/scan-kiwi` (the real remote and the existing links), not `kiwi-scan` as written in the order;
   (c) the notice says «سكان كيوي» (owner's words, verbatim) while the app's Arabic name is «ماسح كيوي».
   The site's wording is updated by the coordinator at publication. CHANGELOG 2.0.0 must say it (release phase).
   **Release-phase contract:** the GitHub release must carry `ScanKiwi-<version>.apk` and `SHA256SUMS.txt` with a
   line for that APK, tag `v<SemVer>`, not a draft/pre-release; otherwise the app refuses the update.

## 4. Remaining work, in order

Items 1–4 of the old list (cam2, perf, OCR decisions 2 and 3, gates seams) are done (phase 1). Next phase starts here:

5. Write `docs/FEATURES-2.0.md` (Arabic first): every feature from the six `docs/features-2.0/*.md` with status,
   acceptance criterion, measured number, test; not-done items with reasons; a 2.1 list (nested folders,
   curved-page dewarping excluded by A4; mixed pages are fixed, no 2.1 entry). Owner phone checks still open:
   Accurate ≤ 8 s/page and ≤ 300 MB native heap, cold start ≤ 1 s, OCR −20 %, blur thresholds, Android 7/8 device,
   and the update path on a phone (certificate read, PackageInstaller confirmation, Wi-Fi detection, a real release).
6. Gates on the complete build (all must pass):
   1. full tests (phase 1 ran them once on the merged release, see §1; re-run only after new commits), lint
      (`lintDebug`, not run since the perf/cam2 merges), `reuse lint` (fix the two missing licence texts, §3.1),
      CI-equivalent commands on a clean clone, release build + size gate (the APK grew by perf/cam2 code only);
   2. independent bug hunter (Opus): fix every S1/S2;
   3. torture suite (`tools/blackbox/torture.sh`, run once, then only failed cases; includes the new
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
