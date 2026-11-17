# SPDX-FileCopyrightText: 2026 FastKiwi
# SPDX-License-Identifier: Apache-2.0
"""Run one OCR engine over a generated set and write <out>/<engine>.json.

Desktop proxy: the same engines and model files the phone uses, run on this PC's CPU with one
thread. Times and memory are desktop numbers, not phone numbers.

    python -I bench.py --data work/sets/dev --out work/results/dev ENGINE

Engines
  tess_fast, tess_best            Tesseract as the app's Fast mode (ara+eng, LSTM, PSM auto, <= 2400 px)
  ppv6tiny_v5, fused_fast_v6tiny, fused2_fast_v6tiny
                                  the roadmap section 3 engines (RapidOCR 3.10.0 + ONNX Runtime), unchanged
  kiwi_fused_onnx, kiwi_fused_mnn, kiwi_fused_mnn16
                                  the app's own pipeline (kiwi_pipeline.py == the Kotlin code) on ONNX
                                  Runtime, MNN fp32 and MNN fp16-weight models
  kiwi2_* (same, with the Latin rule), kiwi_pp_mnn (no Tesseract re-read)
"""
import argparse
import json
import os
import sys
import time
from pathlib import Path

os.environ.setdefault("OMP_THREAD_LIMIT", "1")
import numpy as np  # noqa: E402
import cv2  # noqa: E402
import psutil  # noqa: E402

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import kiwi_pipeline as kp  # noqa: E402

MODELS = Path(os.environ.get("KIWI_BENCH_MODELS", HERE / "work" / "models"))


def normalized_gray_cv(bgr):
    """The roadmap section 3 approximation of the app's normalizedGray (kept for the tess_* baseline)."""
    g = cv2.cvtColor(bgr, cv2.COLOR_BGR2GRAY).astype(np.float32)
    k = max(15, (min(g.shape) // 20) | 1)
    bg = cv2.dilate(g, cv2.getStructuringElement(cv2.MORPH_RECT, (k, k)))
    bg = cv2.GaussianBlur(bg, (0, 0), k / 2)
    return np.clip(g / np.maximum(bg, 1) * 255, 0, 255).astype(np.uint8)


def tess_api(kind, psm):
    import tesserocr
    return tesserocr.PyTessBaseAPI(path=str(MODELS / f"tessdata_{kind}") + os.sep, lang="ara+eng",
                                   oem=tesserocr.OEM.LSTM_ONLY, psm=psm)


def make_tess(kind):
    import tesserocr
    from PIL import Image
    api = tess_api(kind, tesserocr.PSM.AUTO)

    def run(bgr):
        h, w = bgr.shape[:2]
        k = 2400 / max(h, w)  # the app's maxSide: downscale only
        if k < 1:
            bgr = cv2.resize(bgr, (int(w * k), int(h * k)), interpolation=cv2.INTER_AREA)
        api.SetImage(Image.fromarray(normalized_gray_cv(bgr)))
        txt = api.GetUTF8Text()
        return [l.strip() for l in txt.splitlines() if l.strip()]
    return run


def rapid(det, rec="arabic_PP-OCRv5_rec_mobile.onnx"):
    from rapidocr import RapidOCR
    from rapidocr.utils.typings import LangRec, OCRVersion, ModelType, LangDet
    v6 = "v6" in det.lower()
    return RapidOCR(params={
        "Det.model_path": str(MODELS / det), "Rec.model_path": str(MODELS / rec),
        "Det.ocr_version": OCRVersion.PPOCRV6 if v6 else OCRVersion.PPOCRV5,
        "Det.model_type": ModelType.TINY if v6 else ModelType.MOBILE,
        "Det.lang_type": LangDet.MULTI if v6 else LangDet.CH,
        "Rec.lang_type": LangRec.ARABIC, "Rec.ocr_version": OCRVersion.PPOCRV5, "Rec.model_type": ModelType.MOBILE,
        "Global.use_cls": False, "Global.log_level": "error", "Global.text_score": 0.3,
        "EngineConfig.onnxruntime.intra_op_num_threads": 1, "EngineConfig.onnxruntime.inter_op_num_threads": 1})


def group_rapid(items):
    boxes = [(float(np.asarray(b)[:, 0].min()), float(np.asarray(b)[:, 1].min()),
              float(np.asarray(b)[:, 0].max()), float(np.asarray(b)[:, 1].max()), t) for b, t in items]
    return [g[2] for g in kp.group_lines(boxes, rtl=True)]


def make_rapid(det):
    eng = rapid(det)

    def run(bgr):
        r = eng(bgr)
        return [] if r.boxes is None else group_rapid(list(zip(r.boxes, r.txts)))
    return run


def make_fused_rapid(det, latin_rule=False):
    """Roadmap section 3 'fused' engine: RapidOCR det + rec, Tesseract fast re-reads every line crop."""
    import tesserocr
    from PIL import Image
    eng = rapid(det)
    api = tess_api("fast", tesserocr.PSM.SINGLE_LINE)

    def run(bgr):
        r = eng(bgr)
        if r.boxes is None:
            return []
        gray = normalized_gray_cv(bgr)
        items = []
        for b, t in zip(r.boxes, r.txts):
            b = np.asarray(b, np.float32)
            x0, y0 = b.min(0); x1, y1 = b.max(0)
            pad = int(0.25 * (y1 - y0))
            x0, y0 = max(0, int(x0) - pad), max(0, int(y0) - pad)
            x1, y1 = min(bgr.shape[1], int(x1) + pad), min(bgr.shape[0], int(y1) + pad)
            api.SetImage(Image.fromarray(gray[y0:y1, x0:x1]))
            items.append((b, kp.post_process(kp.fuse(t, api.GetUTF8Text().strip(), latin_rule))))
        return group_rapid(items)
    return run


def make_kiwi(runtime, latin_rule=False, use_tess=True):
    import tesserocr
    det_name, rec_name = "PP-OCRv6_det_tiny", "arabic_PP-OCRv5_rec_mobile"
    if runtime == "onnx":
        det, rec = kp.OrtModel(MODELS / f"{det_name}.onnx"), kp.OrtModel(MODELS / f"{rec_name}.onnx")
    elif runtime == "mnn":
        det, rec = kp.MnnModel(MODELS / f"{det_name}.mnn"), kp.MnnModel(MODELS / f"{rec_name}.mnn")
    elif runtime == "mnn16":
        det, rec = kp.MnnModel(MODELS / f"{det_name}.fp16.mnn"), kp.MnnModel(MODELS / f"{rec_name}.fp16.mnn")
    else:
        raise ValueError(runtime)
    api = tess_api("fast", tesserocr.PSM.SINGLE_LINE) if use_tess else None
    eng = kp.KiwiFused(det, rec, MODELS / "ppocrv5_arabic_dict.txt", api, latin_rule=latin_rule, use_tess=use_tess)
    return lambda bgr: eng(bgr=bgr)


ENGINES = {
    "tess_fast": lambda: make_tess("fast"),
    "tess_best": lambda: make_tess("best"),
    "ppv6tiny_v5": lambda: make_rapid("PP-OCRv6_det_tiny.onnx"),
    "fused_fast_v6tiny": lambda: make_fused_rapid("PP-OCRv6_det_tiny.onnx"),
    "fused2_fast_v6tiny": lambda: make_fused_rapid("PP-OCRv6_det_tiny.onnx", latin_rule=True),
    "kiwi_fused_onnx": lambda: make_kiwi("onnx"),
    "kiwi_fused_mnn": lambda: make_kiwi("mnn"),
    "kiwi_fused_mnn16": lambda: make_kiwi("mnn16"),
    "kiwi2_fused_onnx": lambda: make_kiwi("onnx", latin_rule=True),
    "kiwi2_fused_mnn": lambda: make_kiwi("mnn", latin_rule=True),
    "kiwi2_fused_mnn16": lambda: make_kiwi("mnn16", latin_rule=True),
    "kiwi_pp_mnn": lambda: make_kiwi("mnn", use_tess=False),
}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("engine", choices=sorted(ENGINES))
    ap.add_argument("--data", required=True, type=Path, help="a set generated by gen_dataset.py")
    ap.add_argument("--out", required=True, type=Path)
    a = ap.parse_args()
    man = json.loads((a.data / "manifest.json").read_text(encoding="utf-8"))
    if not isinstance(man, dict) or man.get("generator") != "scan-kiwi gen_dataset.py":
        sys.exit("refusing: --data is not a folder written by gen_dataset.py (synthetic pages only)")
    proc = psutil.Process()
    base = proc.memory_info().rss
    t0 = time.perf_counter()
    run = ENGINES[a.engine]()
    load_s = time.perf_counter() - t0
    after_load = proc.memory_info().rss
    out = dict(engine=a.engine, set=man["set"], load_s=load_s, rss_base_mb=base / 1e6,
               rss_after_load_mb=after_load / 1e6, samples=[])
    pages = man["pages"]
    run(cv2.imread(str(a.data / f"{pages[0]['id']}.png")))  # warm-up, not timed
    for m in pages:
        img = cv2.imread(str(a.data / f"{m['id']}.png"))
        t = time.perf_counter()
        lines = run(img)
        dt = time.perf_counter() - t
        out["samples"].append(dict(id=m["id"], w=img.shape[1], h=img.shape[0], seconds=dt, lines=lines))
        print(f"{a.engine} {m['id']} {dt:.2f}s", flush=True)
    mi = proc.memory_info()
    out["peak_mb"] = getattr(mi, "peak_wset", mi.rss) / 1e6
    a.out.mkdir(parents=True, exist_ok=True)
    (a.out / f"{a.engine}.json").write_text(json.dumps(out, ensure_ascii=False, indent=1), encoding="utf-8")


if __name__ == "__main__":
    main()
