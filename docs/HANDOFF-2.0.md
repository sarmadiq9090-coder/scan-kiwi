# Scan Kiwi 2.0.0 — hand-off (2026-11-15)

For the lead session after a context reset. Goal (owner via manager: «اكمل كل ميزات هذا اخر اصدار»): one release,
2.0.0, with every feature of D-078 / `C:\BahthTest\scan-kiwi-design\ROADMAP-2.0.md` (1.3→2.0) and `PRD-2.0.md`,
all gates passed, signed APK. **Local commits only: no push, no PR, no publishing.** Report once to the manager
(`local_7cec2ec5-92bc-44bc-bb01-69cd7c1dbbb0`) at the end.

Work split and shared rules (ownership, env, commit identity, feature records): `C:\BahthTest\scan-kiwi-design\2.0-SPLIT.md`.

## 1. Branches (repo `E:\fast kiwi pro neq\kiwi-scan`)

| Branch | Head | State |
|---|---|---|
| `release/2.0.0` (main checkout) | see `git log -1` (c7d32b4 + this file) | wave 0 + docs + ocr + cam + pdf merged; **210 tests green at 9cd71de**; APAFML commit on top |
| `feat/2.0-perf` (worktree `E:\fast kiwi pro neq\sk-wt\perf`) | a0b58b5 | **wip, untested as a whole**: low-memory mode, heap/stress tests, Android 7 pass, StrictMode. Was waiting for lint + release build when stopped |
| `feat/2.0-cam2` (`sk-wt\cam2`) | c1d8988 | **wip, untested**: `core/Denoise.kt`, deskew, tiling >12 MP, batch bench (`synth/DenoiseBenchTest`, `DeskewBenchTest`); was wiring into `CameraScreen` |
| `feat/2.0-gates` (`sk-wt\gates`) | ca1144b | **wip**, based on old 487c25b (merge release into it first): black-box driver (`gates/BlackBox*.kt`, `tools/blackbox/bb.sh`, `README-for-testers.md`), torture suite (`gates/Torture*Test.kt`, `tools/blackbox/torture.sh`), seam proposals `tools/blackbox/seams/*.patch` (camera capture, I/O faults). Was fixing a PDF page-count shadow |
| `feat/2.0-ocr` (`sk-wt\ocr`) | f3727df | ff'd to 9cd71de + 1 wip commit: started the mixed-page fix (`tools/ocr-bench/sets.json` +, 2 files). Not measured yet |
| `feat/2.0-docs`, `feat/2.0-cam`, `feat/2.0-pdf` | merged | done |

wip branches also carry some new untracked screenshots; all screenshots are regenerated once at the end anyway.
No agents are running (all stopped 2026-11-15, leftover Gradle/Kotlin daemons killed).

## 2. Done (merged, with numbers; details in `docs/features-2.0/{ocr,pdf,camera,docs}.md`)

- OCR: `OcrEngine` interface; fused PP-OCRv6-tiny det + PP-OCRv5 Arabic rec on MNN 3.6.2 (fp16) + Tesseract line re-read;
  «دقيق»/«سريع» with Fast fallback; background OCR after capture; diagnostics row; arm64-v8a + armeabi-v7a only.
  Held-out 156 pages: CER 11.3% (tess_fast 29.0%), tables 4.8%, numbers 72.0%; **mixed +5.4 pp (limit +5)**.
  Dev 52 pages: pipeline 15.5% vs roadmap §3 17.3%; MNN vs ONNX same logic −0.1 pp. Chromium vs Edge renderer: +1.8 pp (fail, recorded).
- PDF: PdfEngine; PDF-01..05, 07..12, 14; drag reorder; Undo/Redo; target size (50 runs, 0 false success); integrity (kill 10/10);
  fuzz 200 (0 crashes); PDF/A with numbers+watermark passes veraPDF; PdfBox-Android in `:pdf` process (+0.63 MB).
- Camera: detection 91.7% within 3% (baseline 69.4%), 0% false pages; blur AUC 0.996; incomplete 95%; quality report
  in `ScanPage.quality`; book mode spine 40/40; glare fusion 29/30. Partial: CAM-06, A8 tiling, auto-deskew, noise → cam2.
- Documents: FTS4 search (recall 100%, worst 10.8 ms / 1000 pages), TXT, text correction overlay, favorites, folders (one level), 2.0 Home.
- APK (release, signed, before perf/cam2): 18.30 MB (target 25, cap 30).

## 3. Manager decisions (2026-11-15) — apply them

1. **APAFML accepted** (permissive, SPDX, Fedora/Debian). Done: ROADMAP §0, THIRD_PARTY, SBOM (commit c7d32b4), notices.
   Still to flag to the council: Unicode-DFS-2015 (`BidiMirroring.txt` in PdfBox), same reasoning.
2. **Mixed pages:** ONE bounded fix (per-line script detection → route Latin lines to Tesseract or the better model,
   tuned on the DEV set only, never the held-out). If held-out mixed ≤ +5 pp → done. Else write the exception with
   numbers in `docs/ocr-benchmark.md` and the release-notes line: «الصفحات المختلطة (عربي وإنجليزي) أضعف قليلاً، وتحسينها في 2.1».
3. **ONNX reference (−1.8 pp, better):** accepted if the cause is known (post-processing/fusion, not model drift). Explain
   in `docs/ocr-benchmark.md`; update the parity reference in an explicit commit; keep ±0.5 pp around the NEW reference.
4. **Folders:** one level in 2.0; nested folders on the 2.1 list.

## 4. Remaining work, in order

1. Finish + test + merge `feat/2.0-cam2` (noise reduction, deskew, tiling >12 MP, CAM-06 bench; update `docs/features-2.0/camera.md`).
2. Finish + test + merge `feat/2.0-perf` (1.7.0 heap budgets: 20-page OCR export ≤ 256 MB, 200-page no-OCR ≤ 128 MB,
   500-page stress no OOM at 512 MB; low-memory mode; Android 7 Robolectric pass; StrictMode debug; `docs/features-2.0/perf.md`).
3. OCR follow-up on `feat/2.0-ocr` (decisions 2 and 3), merge.
4. `feat/2.0-gates`: merge release into it, finish, apply/adapt the seam patches to app code, merge.
5. Write `docs/FEATURES-2.0.md` (Arabic first): every feature from the five `docs/features-2.0/*.md` with status,
   acceptance criterion, measured number, test; not-done items with reasons; a 2.1 list (nested folders, mixed pages
   if not fixed, curved-page dewarping is excluded by A4).
6. Gates on the complete build (all must pass):
   1. full tests, lint, `reuse lint`, CI-equivalent commands on a clean clone, size gate;
   2. independent bug hunter (Opus): fix every S1/S2;
   3. torture suite: fix everything found;
   4. code-blind user tester (sonnet; sees only `tools/blackbox/README-for-testers.md`, screenshots and the a11y tree):
      personas busy employee, older user with large text, student mixing Arabic/English; tasks cold, e.g. "scan 3 pages
      and send on WhatsApp under 500 KB", "find word X in an old document", "merge two PDFs", "protect with a password";
      fix every U1/U2, re-run;
   5. QC (Opus) against `DESIGN-SPEC.md`, `STANDARDS.md`, `docs/FEATURES-2.0.md`.
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
- New Maven artifacts: checksum checker `python C:BahthTestscan-kiwi-design	oolserify_new_entries.py OLD.xml NEW.xml GRADLE_USER_HOME` (compares each new verification-metadata sha256 with the repository's published .sha256/.sha1).
- UI tests rewrite `screenshots/*.png` (date text): `git checkout -- screenshots` before committing.

## 6. Cost rules after the reset (manager)

At most 2 subagents at a time; `model: sonnet` for mechanical work (test runs, torture, blind tester, screenshots);
Opus only for the bug hunter and QC. No long logs in context (tail/grep, summaries ≤ 30 lines); don't read big files whole.
