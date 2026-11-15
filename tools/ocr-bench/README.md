# OCR benchmark (desktop proxy)

The measurement that chose and checks the app's OCR (roadmap §3 and the 1.5.0 gates), as a reproducible
tool. No accuracy number is published without it (PRD §5.3). Results: [`docs/ocr-benchmark.md`](../../docs/ocr-benchmark.md).

**Synthetic pages only.** Every text is invented in `gen_dataset.py`; no real or owner document is ever
used. `bench.py` refuses a folder that `gen_dataset.py` did not write. Nothing here reaches the APK.

## Run

```sh
python -I tools/ocr-bench/run.py --set dev --engines tess_fast,kiwi_fused_mnn16
python -I tools/ocr-bench/run.py --set heldout --engines tess_fast,kiwi_fused_mnn16 --gates
```

`run.py` creates `work/venv` from `requirements-lock.txt` (`pip --require-hashes`), downloads the ONNX and
tessdata_best files listed in `models.json` and checks their sha256, copies the app's own MNN models and
tessdata_fast from `app/src/main/assets`, generates the set into `work/sets/<set>`, runs each engine with one
CPU thread and scores them. `work/` is git-ignored. In CI: the manual workflow `.github/workflows/ocr-bench.yml`
(`workflow_dispatch` only, read-only token, actions pinned by SHA, results uploaded as an artifact).

## Files

| File | What |
|---|---|
| `gen_dataset.py` | Lays out pages in HTML, renders them with Chromium (Playwright, default) or Microsoft Edge (`--renderer edge`, what roadmap §3 used), degrades them like phone photos. Writes `<id>.png`, `<id>.gt.txt`, `manifest.json` |
| `sets.json` | `dev`: 52 pages, seed 20261114, the 8 roadmap fonts and text pool A (tuning allowed). `heldout`: 156 pages, seed 20261201, 6 other fonts, sizes 10/13/14 pt (9 pt small print), its own text pool B (never tuned on) |
| `kiwi_pipeline.py` | The app's Accurate pipeline in Python, the same steps as the Kotlin code in `com.fastkiwi.scan.ocr.fused` (the JVM test `FusedPipelineGoldenTest` proves the Kotlin side gives the same text from the same model outputs) |
| `bench.py` | Runs one engine over a set. `tess_fast`/`tess_best` = Fast mode; `fused_fast_v6tiny`, `fused2_fast_v6tiny`, `ppv6tiny_v5` = the roadmap §3 engines (RapidOCR + ONNX Runtime); `kiwi_fused_onnx`, `kiwi_fused_mnn`, `kiwi_fused_mnn16` = the app's pipeline on ONNX Runtime, MNN fp32, MNN fp16 weights (the shipped files); `kiwi2_*` = with the Latin rule; `kiwi_pp_mnn` = without Tesseract |
| `score.py` | CER, WER, numbers, reading order, lines found, by category; `--gate` checks the 1.5.0 gates, `--parity` the ±0.5 pp parity (2.0 reference: `kiwi_fused_onnx`) |
| `record_golden.py` | Records raw model outputs for the app's JVM golden test |
| `models.json`, `requirements-lock.txt` | Pinned inputs (sha256) |
| `fonts/` | 15 OFL-1.1 font files with their `OFL-<family>.txt` (REUSE annotations in `REUSE.toml`) |

Metrics (unchanged from roadmap §3): CER and WER over the page text in the engine's own line order, after NFKC,
removing bidi controls, tatweel and harakat, and unifying Arabic-Indic digits and separators with ASCII.
Numbers = share of ground-truth numbers found exactly. Reading order = pairwise order agreement of matched lines;
lines found = share of ground-truth lines with a match (CER < 0.5).

Versions: Tesseract 5.5.4 (`tesserocr` 2.13.0 wheel; the app ships Tesseract 5.5.1 via Tesseract4Android 4.9.0,
no 5.5.1 wheel exists), ONNX Runtime 1.32.0, RapidOCR 3.10.0, MNN 3.6.2, Playwright 1.65.2 (Chromium build 1255).

## Reproduction checks (2026-11-15, this PC)

- Baseline: `tess_fast` on the roadmap's Edge-rendered dev pages: **CER 27.7 %, numbers 46.9 %**, identical to
  roadmap §3 (target 27.7 % ± 0.5 pp). PASS.
- Renderer: the same dev set rendered with Playwright Chromium scores `tess_fast` 29.5 % (+1.8 pp) and the
  shipped pipeline 15.7 % (+0.2 pp) against Edge. Tesseract is sensitive to the small rasterisation
  differences between Chromium 1255 and the installed Edge, so the ±0.5 pp renderer check **fails for
  tess_fast** and passes for the fused pipeline. The dev numbers in `docs/ocr-benchmark.md` use the Edge files
  (the roadmap's pages); `--renderer edge` regenerates them on Windows.
- `normalized_gray`: the `tess_*` engines keep the roadmap's OpenCV approximation so the baseline stays
  comparable; the app's exact `normalizedGray` is ported in `kiwi_pipeline.normalized_gray` (used by the
  Accurate pipeline) and checked in the app by `OcrCoreTest.illuminationMatchesImageFiltersByteForByte`. The
  plan's separate `--check-normalize` comparison was not built.
