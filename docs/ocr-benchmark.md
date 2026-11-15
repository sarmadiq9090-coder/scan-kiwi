# Scan Kiwi OCR benchmark — 2.0.0

> **Desktop proxy.** Every number below was measured on the build PC (Windows, one CPU thread per engine),
> running the same model files and the same pipeline logic as the app, on **synthetic pages only** (every text
> invented, no real or owner document). Times and memory are desktop numbers, **not phone numbers**. Phone
> timings come from the owner's phone check (Settings → «تشخيص قراءة النص»), listed at the end.

## ملخص

- محرك القراءة «دقيق» (الافتراضي) يجمع كاشف أسطر PP-OCRv6 وقارئ العربية PP-OCRv5 على MNN، ثم يعيد
  Tesseract قراءة السطر حين يجد أرقامًا أكثر. على 156 صفحة اختبار جديدة لم يُضبط عليها شيء: نسبة خطأ
  الحروف 11.3% مقابل 29.0% لمحرك 1.2 («سريع»)، والجداول 4.8% مقابل 93.8%، ودقة الأرقام 72.0% مقابل 43.5%.
- الصفحات المختلطة عربي/إنجليزي أسوأ من «سريع» بـ 5.4 نقطة (الحد المسموح 5): مسألة معروفة.
- النص المشكول بالكامل ما زال ضعيفًا (40% خطأ مع تجاهل الحركات): لا نعد به.
- «سريع» هو محرك 1.2 نفسه دون تغيير.

## Engines

| Name | What | In the app |
|---|---|---|
| `tess_fast` | Tesseract 5 + tessdata_fast `ara+eng`, LSTM, page segmentation auto, ≤ 2400 px, illumination normalised | «سريع» Fast (1.2's engine) |
| `tess_best` | the same with tessdata_best (+21.6 MB) | no |
| `kiwi_fused_mnn16` | PP-OCRv6 tiny det (input ≤ 960 px) → line boxes → PP-OCRv5 Arabic mobile rec per line → Tesseract fast re-reads the line (PSM 7), its text wins when it holds more numbers → Arabic comma between digits → ٫ → rows (RTL on Arabic pages, LTR on Latin pages). MNN 3.6.2, fp16 weights | «دقيق» Accurate (default) — the shipped files |
| `kiwi_fused_mnn`, `kiwi_fused_onnx` | the same logic with fp32 MNN files / ONNX Runtime 1.32.0 | parity runs |
| `fused_fast_v6tiny` | roadmap §3's fused engine (RapidOCR 3.10.0 pre/post-processing + ONNX Runtime) | reference |

Method (`tools/ocr-bench/README.md`): CER and WER over the page text in the engine's own line order after NFKC,
without bidi controls, tatweel and harakat, Arabic-Indic digits and separators unified with ASCII. Numbers =
share of ground-truth numbers found exactly. Order = pairwise order agreement of matched lines; lines found =
share of ground-truth lines matched (CER < 0.5). Tesseract 5.5.4 (`tesserocr` wheel; the app ships 5.5.1).

## Held-out set (156 pages, never tuned on) — the 1.5.0 / 2.0.0 accuracy gates

Seed 20261201, 6 fonts that are not in the dev set (Noto Kufi Arabic, Lateef, Markazi Text, Vazirmatn, Readex Pro,
Harmattan), sizes 10/13/14 pt and 9 pt small print, and its own invented texts. 20 pages per category + 16
small/low-quality pages; rendered by Playwright Chromium 1255, degraded like phone photos (scan / phone / low).

| Engine | CER | WER | Numbers | Order | Lines found | s/page | Peak RSS |
|---|---|---|---|---|---|---|---|
| `tess_fast` (Fast) | 29.0% | 33.4% | 43.5% | 1.00 | 69.2% | 0.26 | 130 MB |
| `tess_best` | 26.8% | 31.8% | 43.9% | 1.00 | 71.7% | 0.56 | 148 MB |
| `fused_fast_v6tiny` (roadmap §3) | 13.8% | 24.3% | 73.4% | 1.00 | 89.3% | 1.76 | 452 MB |
| `kiwi_fused_mnn` (fp32) | 11.4% | 21.9% | 71.8% | 1.00 | 93.5% | 1.02 | 233 MB |
| **`kiwi_fused_mnn16` (Accurate, shipped)** | **11.3%** | **21.9%** | **72.0%** | 1.00 | 93.7% | 1.17 | 231 MB |

CER by category (lower is better):

| Engine | Prose | Diacritics | Numbers | Amounts | Dates | Tables | Mixed | Small/low |
|---|---|---|---|---|---|---|---|---|
| `tess_fast` | 10.9% | 74.0% | 7.5% | 11.0% | 10.4% | 93.8% | 15.3% | 34.0% |
| `tess_best` | 6.1% | 63.4% | 8.2% | 9.4% | 11.2% | 94.3% | 13.8% | 34.3% |
| `fused_fast_v6tiny` | 3.3% | 49.9% | 7.0% | 7.1% | 6.9% | 3.4% | 25.3% | 18.5% |
| `kiwi_fused_mnn16` | 0.4% | 40.3% | 5.4% | 6.7% | 6.1% | 4.8% | 20.7% | 16.1% |

Number accuracy by category: `tess_fast` numbers 62.3%, amounts 63.3%, dates 74.4%, tables 5.3%, mixed 67.2%,
small 25.3%; `kiwi_fused_mnn16` 69.8%, 64.2%, 82.8%, 82.0%, 51.7%, 44.8%.
Diacritics pages with harakat kept (reported, not gated): `tess_fast` 77.9%, `tess_best` 67.1%, roadmap fused 53.6%, Accurate 47.7%.

Gates (`score.py --gate base=tess_fast,new=kiwi_fused_mnn16 …`):

| Gate | Limit | Result | |
|---|---|---|---|
| Overall CER | ≤ 20% | 11.3% | PASS |
| Relative CER gain vs tessdata_fast | ≥ 30% | 61.1% | PASS |
| Tables CER | ≤ 10% | 4.8% | PASS |
| Number accuracy | ≥ 65% | 72.0% | PASS |
| Each category vs tessdata_fast | ≤ +2 pp | prose −10.5, numbers −2.1, amounts −4.2, dates −4.4, tables −89.1, small −17.9 | PASS |
| Mixed Arabic/English vs tessdata_fast | ≤ +5 pp | **+5.4 pp** | **FAIL** (known issue) |
| Diacritics | reported | 40.3% (tess_fast 74.0%) | — |

**Known issue — mixed pages.** The Arabic recogniser drops or mangles Latin runs inside Arabic lines;
Tesseract replaces a line only when it finds more numbers. A "Latin rule" (Tesseract also wins with ≥ 3 more
Latin letters) improves mixed pages on the dev set (29.2% → 23.0%) but costs more elsewhere (overall 15.5% →
16.9%, tables 7.2% → 12.9%), so it was not adopted. Mixed lines that matter to a user can be read in Fast
mode. Not tuned on the held-out set.

## Dev set (52 pages, roadmap §3 pages) — tuning and parity

The roadmap's own Edge-rendered pages (seed 20261114, 8 fonts). Tuning allowed here only; decided on it: the
recogniser's right-hand padding (25% of the line, ≥ 96 px; without it prose CER was 12.9%), the Latin rule
(off), fp16 weights (kept).

| Engine | CER | WER | Numbers | Order | Lines found | s/page | Peak RSS |
|---|---|---|---|---|---|---|---|
| `tess_fast` (Fast) | 27.7% | 36.7% | 46.9% | 1.00 | 69.6% | 0.24 | 131 MB |
| `fused_fast_v6tiny` (roadmap §3) | 17.3% | 30.0% | 68.5% | 1.00 | 83.2% | 1.38 | 414 MB |
| `kiwi_fused_onnx` | 15.6% | 28.4% | 70.1% | 1.00 | 87.4% | 1.64 | 387 MB |
| `kiwi_fused_mnn` (fp32) | 15.5% | 28.6% | 70.1% | 1.00 | 87.4% | 1.22 | 237 MB |
| **`kiwi_fused_mnn16` (Accurate, shipped)** | **15.5%** | **28.2%** | **70.1%** | 1.00 | 88.5% | 1.25 | 231 MB |
| `kiwi2_fused_mnn` (Latin rule) | 16.9% | 29.5% | 66.0% | 1.00 | 84.4% | 0.98 | 237 MB |
| `kiwi_pp_mnn` (no Tesseract) | 21.6% | 36.7% | 33.3% | 1.00 | 85.9% | 0.59 | 211 MB |

| Engine | Prose | Diacritics | Numbers | Amounts | Dates | Tables | Mixed | Small/low |
|---|---|---|---|---|---|---|---|---|
| `tess_fast` | 7.1% | 69.3% | 5.9% | 12.5% | 17.9% | 93.1% | 18.8% | 27.0% |
| `fused_fast_v6tiny` | 2.2% | 53.3% | 5.2% | 9.5% | 13.7% | 5.2% | 33.1% | 22.3% |
| `kiwi_fused_mnn16` | 1.3% | 39.9% | 3.9% | 8.2% | 14.1% | 7.5% | 30.7% | 21.7% |

Parity:
- **Runtime parity (same logic):** MNN fp32 vs ONNX Runtime −0.11 pp CER; MNN fp16 vs ONNX −0.1 pp. PASS (±0.5 pp).
- **Against roadmap §3's 17.3%:** the shipped pipeline gives 15.5%, −1.8 pp, outside the ±0.5 pp band in the
  better direction. The difference is the pipeline, not the runtime: RapidOCR's own pre/post-processing (no right
  padding, 90° turn of narrow boxes, batch padding, larger detector input) versus the app's.
- **Kotlin = Python:** `FusedPipelineGoldenTest` feeds the raw MNN outputs recorded from the Python reference on
  three dev pages to the Kotlin code: same model inputs, same boxes, identical text.
- Renderer check: the dev set re-rendered with Playwright Chromium: `tess_fast` 29.5% (+1.8 pp vs Edge),
  Accurate 15.7% (+0.2 pp).

## Limits of the proxy

- Pages are fragments (≈ 500–2400 px wide), not whole A4 pages. On the phone the detector sees a whole page
  scaled to 960 px, so small print has fewer detector pixels than here; the owner's phone check with printed
  benchmark pages covers this.
- One CPU thread on a desktop; the app uses 4 MNN threads on the phone. Expect a mid-range phone to be several
  times slower per thread.
- Peak RSS is the whole Python process, not the app's native heap.

## Phone (owner check pending)

| Check | Target | Result |
|---|---|---|
| Accurate, time per full A4 page of text (diagnostics row) | ≤ 8 s, else Accurate ships opt-in | pending |
| Peak native heap during OCR (diagnostics row) | ≤ 300 MB | pending |
| Fast mode | unchanged from 1.2 | pending |
| 20-page OCR export | no crash | pending |

Reproduce: `python -I tools/ocr-bench/run.py --set heldout --engines tess_fast,kiwi_fused_mnn16 --gates`.
