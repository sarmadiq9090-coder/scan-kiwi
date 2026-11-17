# SPDX-FileCopyrightText: 2026 FastKiwi
# SPDX-License-Identifier: Apache-2.0
"""Records raw model outputs of the reference pipeline for a few dev pages, for the app's JVM golden test
(app/src/test/java/com/fastkiwi/scan/ocr/fused/FusedPipelineGoldenTest.kt). The Kotlin post-processing
fed with these outputs must give exactly the text Python gives.

    python -I record_golden.py --data work/sets/dev --out ../../app/src/test/resources/ocr-golden ID [ID ...]
"""
import argparse
import gzip
import json
import os
import sys
from pathlib import Path

import cv2
import numpy as np

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import kiwi_pipeline as kp  # noqa: E402

MODELS = Path(os.environ.get("KIWI_BENCH_MODELS", HERE / "work" / "models"))


def main():
    import tesserocr
    ap = argparse.ArgumentParser()
    ap.add_argument("ids", nargs="+")
    ap.add_argument("--data", required=True, type=Path)
    ap.add_argument("--out", required=True, type=Path)
    a = ap.parse_args()
    det = kp.MnnModel(MODELS / "PP-OCRv6_det_tiny.mnn")
    rec = kp.MnnModel(MODELS / "arabic_PP-OCRv5_rec_mobile.mnn")
    api = tesserocr.PyTessBaseAPI(path=str(MODELS / "tessdata_fast") + os.sep, lang="ara+eng",
                                  oem=tesserocr.OEM.LSTM_ONLY, psm=tesserocr.PSM.SINGLE_LINE)
    eng = kp.KiwiFused(det, rec, MODELS / "ppocrv5_arabic_dict.txt", api)
    a.out.mkdir(parents=True, exist_ok=True)
    chars = kp.load_dict(MODELS / "ppocrv5_arabic_dict.txt")
    (a.out / "bidi_classes.txt").write_text(
        "".join(f"{ord(c):04x} {kp.bidi_class(c)}\n" for c in chars[1:]), encoding="utf-8", newline="\n")
    for pid in a.ids:
        gray = kp.luma_from_bgr(cv2.imread(str(a.data / f"{pid}.png")))
        eng.trace = {}
        lines = eng(gray=gray)
        t = eng.trace
        d = a.out / pid
        d.mkdir(exist_ok=True)
        h, w = gray.shape
        (d / "gray.pgm.gz").write_bytes(gzip.compress(f"P5\n{w} {h}\n255\n".encode() + gray.tobytes(), mtime=0))
        q = t.pop("det_map_u8")
        (d / "det_map.u8.gz").write_bytes(gzip.compress(q.tobytes(), mtime=0))
        t["width"], t["height"] = w, h
        t["lines"] = lines
        (d / "trace.json").write_text(json.dumps(t, ensure_ascii=False), encoding="utf-8", newline="\n")
        print(pid, len(t["boxes"]), "boxes", len(lines), "lines")


if __name__ == "__main__":
    main()
